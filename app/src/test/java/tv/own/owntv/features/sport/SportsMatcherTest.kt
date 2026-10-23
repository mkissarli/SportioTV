package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.model.MediaType

/**
 * The matcher's Lume rules, nailed down on the JVM with fake DAOs — no emulator, no Android.
 *
 * The six cases are the ones `docs/sport-channel-matching.md` §9 lists: the two EPG tiers, the
 * nickname alias, the whole-channel-name tier, the one-team regression net and the language filter
 * that must run **before** the limit cut. The reference fixture is the one the doc was written
 * against — Coventry City v Newcastle United.
 *
 * [SportsMatcher] takes no Android-bound dependency: the two DAOs are plain interfaces (faked below)
 * and the live-playlist lookup is the [LiveSourceIdsProvider] seam, handed a one-entry lambda. That
 * is what lets this run as `:app:testStandardDebugUnitTest` without MockK/Robolectric.
 */
class SportsMatcherTest {

    // --- (a) both teams in the EPG title → +10 ------------

    @Test
    fun bothTeamsInTheEpgTitleScoreTen() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(1, "IT: SKY SPORT ARENA", epgKey = "arena")),
            programmes = listOf(programme("arena", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("IT: SKY SPORT ARENA"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
        assertEquals("Coventry City v Newcastle United", matches.single().epgTitle)
    }

    // --- (b) the teams split across title and description → +3 ------------

    @Test
    fun teamsSplitAcrossTitleAndDescriptionScoreThree() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(2, "DE: SKY SPORT PREMIER LEAGUE", epgKey = "de-premier")),
            programmes = listOf(
                programme(
                    "de-premier",
                    title = "Coventry City im Fokus",
                    description = "Der Gegner an diesem Abend: Newcastle United.",
                ),
            ),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("DE: SKY SPORT PREMIER LEAGUE"), matches.map { it.channelName })
        assertEquals(3, matches.single().score)
    }

    // --- (c) only one team → no match (news-channel regression net) ------------

    @Test
    fun aProgrammeNamingOnlyOneTeamDoesNotMatch() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(3, "DE: SKY SPORT NEWS", epgKey = "de-news")),
            programmes = listOf(programme("de-news", title = "Newcastle United News")),
        )

        assertTrue(matcher(store).match(fixture()).isEmpty())
    }

    // --- (d) generic title, the other team only as a nickname alias → +3 ------------

    @Test
    fun aNicknameAliasSuppliesTheSecondTeam() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(4, "DE: SKY SPORT TOP EVENT", epgKey = "de-top")),
            programmes = listOf(
                programme(
                    "de-top",
                    title = "Fußball: England, Premier League",
                    description = "Premier League: Coventry City gegen die Magpies.",
                ),
            ),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("DE: SKY SPORT TOP EVENT"), matches.map { it.channelName })
        assertEquals(3, matches.single().score)

        // Without the alias the feed names only one team and must drop out — this is exactly why the
        // nickname table is load-bearing for generically titled feeds.
        assertTrue(matcher(store, vocab = SportsVocab.EMPTY).match(fixture()).isEmpty())
    }

    // --- (e) the channel's own name names both teams → +4 ------------

    @Test
    fun bothTeamsInTheChannelNameScoreFour() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(5, "AU (STAN 01) | Coventry City v Newcastle United HD"),
            ),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("AU (STAN 01) | Coventry City v Newcastle United HD"), matches.map { it.channelName })
        assertEquals(4, matches.single().score)
    }

    // --- (f) the language filter runs before the cap ------------
    //
    // The channel limit is clamped to [SportsMatcher.MinChannelLimit] (5) since the configurable
    // limit (roadmap point 4), so this uses five foreign feeds to fill every slot.

    @Test
    fun theLanguageFilterCutsBeforeTheLimit() = runBlocking {
        val foreign = (1..5).map { n ->
            channel(100L + n, "IT: SKY SPORT ARENA $n", epgKey = "arena$n")
        }
        val store = FakeStore(
            channels = foreign + channel(200, "DE: SKY SPORT PREMIER LEAGUE", epgKey = "de-premier"),
            programmes = (1..5).map { n ->
                programme("arena$n", title = "Coventry City v Newcastle United")
            } + programme(
                "de-premier",
                title = "Fußball: England, Premier League",
                description = "Coventry City gegen die Magpies.",
            ),
        )
        val matcher = matcher(store)

        // Without a filter the five +10 foreign feeds win every slot...
        val unfiltered = matcher.match(fixture(), languages = emptySet(), limit = 5)
        assertEquals(5, unfiltered.size)
        assertTrue(unfiltered.none { it.channelName.startsWith("DE:") })

        // ...but the German feed (score 3) must still be visible to a German viewer, which only works
        // if the filter drops the foreign channels BEFORE `take(limit)`.
        val german = matcher.match(fixture(), languages = setOf(SportLanguage.GERMAN), limit = 5)
        assertEquals(listOf("DE: SKY SPORT PREMIER LEAGUE"), german.map { it.channelName })
    }

    // --- (g) duel: both athletes in the EPG title → +10 ------------

    @Test
    fun duelBothAthletesInTheEpgTitleScoreTen() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(10, "US: PPV 4", epgKey = "ppv4")),
            programmes = listOf(programme("ppv4", title = "Karen Khachanov vs Alex de Minaur")),
        )

        val matches = matcher(store).match(duelFixture())

        assertEquals(listOf("US: PPV 4"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
        assertEquals("Karen Khachanov vs Alex de Minaur", matches.single().epgTitle)
    }

    // --- (h) duel: athletes split across title and description → +3 ------------

    @Test
    fun duelAthletesSplitAcrossTitleAndDescriptionScoreThree() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(11, "US: PPV 4", epgKey = "ppv4")),
            programmes = listOf(
                programme(
                    "ppv4",
                    title = "Khachanov im Fokus",
                    description = "Der Gegner an diesem Abend: Alex de Minaur.",
                ),
            ),
        )

        val matches = matcher(store).match(duelFixture())

        assertEquals(listOf("US: PPV 4"), matches.map { it.channelName })
        assertEquals(3, matches.single().score)
    }

    // --- (i) duel: a lone surname without tennis context → no match ------------

    @Test
    fun duelSingleAthleteWithoutContextDoesNotMatch() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(12, "DE: SPORT NEWS", epgKey = "de-news")),
            programmes = listOf(programme("de-news", title = "De Minaur News")),
        )

        assertTrue(matcher(store).match(duelFixture()).isEmpty())
    }

    // --- (j) duel: one athlete + tennis context → +2 ------------

    @Test
    fun duelSingleAthleteWithTennisContextScoresTwo() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(13, "EUROSPORT 1", epgKey = "eurosport")),
            programmes = listOf(
                programme(
                    "eurosport",
                    title = "Tennis: ATP Shanghai",
                    description = "U.a. Alex de Minaur im Einsatz.",
                ),
            ),
        )

        val matches = matcher(store).match(duelFixture())

        assertEquals(listOf("EUROSPORT 1"), matches.map { it.channelName })
        assertEquals(2, matches.single().score)
    }

    // --- (k) duel: tournament only → +1 ------------

    @Test
    fun duelTournamentOnlyScoresOne() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(14, "EUROSPORT 1", epgKey = "eurosport")),
            programmes = listOf(
                programme("eurosport", title = "Tennis: Shanghai Masters, Viertelfinale"),
            ),
        )

        val matches = matcher(store).match(duelFixture(tournament = "Shanghai Masters"))

        assertEquals(listOf("EUROSPORT 1"), matches.map { it.channelName })
        assertEquals(1, matches.single().score)
    }

    // --- (l) duel: the league hint matches a tennis-named channel without any guide ------------

    @Test
    fun duelHintMatchesTennisChannelByName() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(15, "US TENNIS PPV")),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(duelFixture(hints = listOf("Tennis")))

        assertEquals(listOf("US TENNIS PPV"), matches.map { it.channelName })
        assertEquals(6, matches.single().score)
    }

    // --- (m) doubles: a "A / B" roster side contributes both players ------------

    @Test
    fun doublesRosterSidesMatchBothPairs() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(16, "US: PPV 5", epgKey = "ppv5")),
            programmes = listOf(
                programme("ppv5", title = "Cash/Glasspool vs Darderi/Etcheverry"),
            ),
        )
        val fixture = duelFixture(
            sideA = "Julian Cash / Lloyd Glasspool",
            sideB = "Luciano Darderi / Tomas Martin Etcheverry",
        )

        val matches = matcher(store).match(fixture)

        assertEquals(listOf("US: PPV 5"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
    }

    // --- (n) duel: a TBD side still matches through the tournament ------------

    @Test
    fun duelTbdSideKeepsTournamentFallback() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(17, "EUROSPORT 1", epgKey = "eurosport")),
            programmes = listOf(
                programme("eurosport", title = "Tennis: Shanghai Masters"),
            ),
        )
        val fixture = duelFixture(sideB = "TBD", tournament = "Shanghai Masters")

        val matches = matcher(store).match(fixture)

        assertEquals(listOf("EUROSPORT 1"), matches.map { it.channelName })
        assertEquals(1, matches.single().score)
    }

    // --- (o) duel: the exact per-event channel survives a foreign language filter ------------

    @Test
    fun duelExactChannelSurvivesForeignLanguageFilter() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(20, "NEXT | ALEX DE MINAUR - KAREN KHACHANOV | Tue 13 Oct 06:00 CEST (FR) | FR: MAX PPV 21"),
                channel(21, "DE: SKY SPORT TENNIS"),
            ),
            programmes = emptyList(),
        )
        val fixture = duelFixture(hints = listOf("Tennis"))

        val matches = matcher(store).match(fixture, languages = setOf(SportLanguage.GERMAN))

        assertEquals(
            listOf("DE: SKY SPORT TENNIS", "NEXT | ALEX DE MINAUR - KAREN KHACHANOV | Tue 13 Oct 06:00 CEST (FR) | FR: MAX PPV 21"),
            matches.map { it.channelName },
        )
        assertEquals(listOf(6, 4), matches.map { it.score })
        assertEquals(listOf(false, true), matches.map { it.isExact })
    }

    // --- (p) teams: the exact per-event channel survives a foreign language filter ------------

    @Test
    fun teamExactChannelSurvivesForeignLanguageFilter() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(22, "AU (STAN 01) | Coventry City v Newcastle United HD"),
                channel(23, "DE: SKY SPORT PREMIER LEAGUE", epgKey = "de-premier"),
            ),
            programmes = listOf(
                programme(
                    "de-premier",
                    title = "Fußball: England, Premier League",
                    description = "Coventry City gegen die Magpies.",
                ),
            ),
        )

        val matches = matcher(store).match(fixture(), languages = setOf(SportLanguage.GERMAN))

        assertEquals(
            listOf("AU (STAN 01) | Coventry City v Newcastle United HD", "DE: SKY SPORT PREMIER LEAGUE"),
            matches.map { it.channelName },
        )
        assertEquals(listOf(4, 3), matches.map { it.score })
    }

    // --- (q) US region priority: the US feed wins the tie on equal evidence ------------

    @Test
    fun usFeedWinsOverCaAndAuFeedsOnEqualEvidence() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(30, "CA: TSN COLLEGE FOOTBALL", epgKey = "ca-cfb"),
                channel(31, "US: ESPN COLLEGE PRIMETIME", epgKey = "us-cfb"),
                channel(32, "AU: ESPN AU COLLEGE", epgKey = "au-cfb"),
            ),
            programmes = listOf(
                programme("ca-cfb", title = "Ohio State v Michigan"),
                programme("us-cfb", title = "Ohio State v Michigan"),
                programme("au-cfb", title = "Ohio State v Michigan"),
            ),
        )

        val matches = matcher(store).match(collegeFixture())

        // Same game, same +10 title evidence everywhere (short names via aliases); the US home
        // feed takes the bonus and sorts first, the foreign feeds keep their order behind it.
        assertEquals(
            listOf("US: ESPN COLLEGE PRIMETIME", "CA: TSN COLLEGE FOOTBALL", "AU: ESPN AU COLLEGE"),
            matches.map { it.channelName },
        )
        assertEquals(listOf(12, 10, 10), matches.map { it.score })
    }

    // --- (r) US region priority: no US feed leaves a non-empty foreign list ------------

    @Test
    fun foreignFeedsRemainWithoutAUsFeed() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(33, "CA: TSN COLLEGE FOOTBALL", epgKey = "ca-cfb"),
                channel(34, "AU: ESPN AU COLLEGE", epgKey = "au-cfb"),
            ),
            programmes = listOf(
                programme("ca-cfb", title = "Ohio State v Michigan"),
                programme("au-cfb", title = "Ohio State v Michigan"),
            ),
        )

        val matches = matcher(store).match(collegeFixture())

        // Bonus-only, never a filter: the region logic must not empty the pane.
        assertEquals(
            listOf("CA: TSN COLLEGE FOOTBALL", "AU: ESPN AU COLLEGE"),
            matches.map { it.channelName },
        )
        assertEquals(listOf(10, 10), matches.map { it.score })
    }

    // --- (s) youth firewall: a senior CL fixture never matches youth programmes ------------

    @Test
    fun seniorFixtureIgnoresYouthProgrammes() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(40, "DE: SKY SPORT TOP EVENT", epgKey = "youth1"),
                channel(41, "DE: SKY SPORT MIX", epgKey = "youth2"),
                channel(42, "DE: SKY SPORT NEWS", epgKey = "youth3"),
                channel(43, "DE: SKY SPORT EXTRA", epgKey = "youth4"),
            ),
            programmes = listOf(
                programme("youth1", title = "Coventry City v Newcastle United Youth League"),
                programme("youth2", title = "Coventry City v Newcastle U19"),
                programme("youth3", title = "Coventry City v Newcastle U-19"),
                programme("youth4", title = "Jugendliga: Coventry City gegen Newcastle"),
            ),
        )

        assertTrue(matcher(store).match(championsFixture()).isEmpty())
    }

    // --- (t) youth firewall: a youth fixture still matches its youth programmes ------------

    @Test
    fun youthFixtureStillMatchesYouthProgrammes() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(44, "DE: SKY SPORT TOP EVENT", epgKey = "youth")),
            programmes = listOf(
                programme("youth", title = "Coventry City v Newcastle United Youth League"),
            ),
        )

        val matches = matcher(store).match(youthFixture())

        assertEquals(listOf("DE: SKY SPORT TOP EVENT"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
    }

    // --- (u) youth firewall, symmetric: a youth fixture ignores senior programmes ------------

    @Test
    fun youthFixtureIgnoresSeniorProgrammes() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(45, "IT: SKY SPORT ARENA", epgKey = "arena")),
            programmes = listOf(programme("arena", title = "Coventry City v Newcastle United")),
        )

        assertTrue(matcher(store).match(youthFixture()).isEmpty())
    }

    // --- (v) youth firewall in the channel name ------------

    @Test
    fun youthChannelNameMatchesOnlyTheYouthFixture() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(46, "YOUTH | Coventry City v Newcastle United HD"),
            ),
            programmes = emptyList(),
        )

        assertTrue(matcher(store).match(championsFixture()).isEmpty())

        val youthMatches = matcher(store).match(youthFixture())
        assertEquals(
            listOf("YOUTH | Coventry City v Newcastle United HD"),
            youthMatches.map { it.channelName },
        )
        assertEquals(4, youthMatches.single().score)
    }

    // --- (s) strict options: preferred filter before the cap, capped to limit ------------

    @Test
    fun strictOptionsKeepOnlyPreferredUpToLimit() = runBlocking {
        val channels = (1..6).map { channel(100L + it, "DE: SKY SPORT $it", epgKey = "de-$it") } +
            (1..3).map { channel(200L + it, "IT: SKY SPORT $it", epgKey = "it-$it") }
        val programmes = (1..6).map { programme("de-$it", title = "Coventry City v Newcastle United") } +
            (1..3).map { programme("it-$it", title = "Coventry City v Newcastle United") }
        val store = FakeStore(channels = channels, programmes = programmes)

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(languages = setOf(SportLanguage.GERMAN), limit = 5),
        )

        assertEquals(5, matches.size)
        assertTrue(matches.all { it.channelName.startsWith("DE:") })
    }

    // --- (t) the limit is clamped to the 5–50 range ------------

    @Test
    fun limitBelowMinimumIsClampedUp() = runBlocking {
        val channels = (1..6).map { channel(100L + it, "DE: SKY SPORT $it", epgKey = "de-$it") }
        val programmes = (1..6).map { programme("de-$it", title = "Coventry City v Newcastle United") }
        val store = FakeStore(channels = channels, programmes = programmes)

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(languages = setOf(SportLanguage.GERMAN), limit = 2),
        )

        assertEquals(SportsMatcher.MinChannelLimit, matches.size)
    }

    // --- (u) strict never pads below the quota, legacy falls back ------------

    @Test
    fun strictModeNeverPadsBelowTheMinimumWhileLegacyFallsBack() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(301, "DE: SKY SPORT 1", epgKey = "de-1"),
                channel(302, "DE: SKY SPORT 2", epgKey = "de-2"),
                channel(303, "IT: SKY SPORT 1", epgKey = "it-1"),
                channel(304, "IT: SKY SPORT 2", epgKey = "it-2"),
                channel(305, "IT: SKY SPORT 3", epgKey = "it-3"),
            ),
            programmes = listOf(
                programme("de-1", title = "Coventry City v Newcastle United"),
                programme("de-2", title = "Coventry City v Newcastle United"),
                programme("it-1", title = "Coventry City v Newcastle United"),
                programme("it-2", title = "Coventry City v Newcastle United"),
                programme("it-3", title = "Coventry City v Newcastle United"),
            ),
        )
        val matcher = matcher(store)

        // Strict: two German hits stay two German hits — no Italian padding up to the quota of 5.
        val strict = matcher.match(fixture(), SportMatchOptions(languages = setOf(SportLanguage.GERMAN)))
        assertEquals(listOf("DE: SKY SPORT 1", "DE: SKY SPORT 2"), strict.map { it.channelName })

        // Legacy: below the quota the full list comes back instead of an honestly short one.
        val legacy = matcher.match(
            fixture(),
            SportMatchOptions(languages = setOf(SportLanguage.GERMAN), strict = false),
        )
        assertEquals(5, legacy.size)

        // Legacy with a met quota keeps the preferred-only list.
        val legacyMet = matcher.match(
            fixture(),
            SportMatchOptions(languages = setOf(SportLanguage.GERMAN), strict = false, minPreferred = 2),
        )
        assertEquals(listOf("DE: SKY SPORT 1", "DE: SKY SPORT 2"), legacyMet.map { it.channelName })
    }

    // --- (v) excluded languages drop everything, including exact channels ------------

    @Test
    fun excludedLanguagesDropEverythingIncludingExactChannels() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(401, "DE: SKY SPORT BUNDESLIGA", epgKey = "de-x"),
                channel(402, "DE: Coventry City v Newcastle United HD"),
                channel(403, "UK: SKY SPORTS PREMIER LEAGUE", epgKey = "uk-x"),
                channel(404, "SKY SPORT EXTRA", epgKey = "extra"),
            ),
            programmes = listOf(
                programme("de-x", title = "Coventry City v Newcastle United"),
                programme("uk-x", title = "Coventry City v Newcastle United"),
                programme("extra", title = "Coventry City v Newcastle United"),
            ),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(excludedLanguages = setOf(SportLanguage.GERMAN)),
        )

        // The exact per-event channel does NOT survive its language being excluded; the untagged
        // channel (no recognisable language) is not excluded and stays.
        assertEquals(
            listOf("UK: SKY SPORTS PREMIER LEAGUE", "SKY SPORT EXTRA"),
            matches.map { it.channelName },
        )
    }

    // --- (w) the matcher reads the live playlists fresh on every call ------------
    //
    // Only the view-model cache can go stale across a source change: the matcher itself asks the
    // provider on every match, so flipping the ids between two calls flips the result.

    @Test
    fun matcherReadsLiveSourcesFreshOnEveryCall() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(500, "DE: SKY SPORT TOP EVENT", epgKey = "fresh")),
            programmes = listOf(programme("fresh", title = "Coventry City v Newcastle United")),
        )
        var ids: List<Long> = listOf(SourceId)
        val matcher = SportsMatcher(
            channelDao = store.channelDao,
            epgDao = store.epgDao,
            categoryDao = store.categoryDao,
            vocab = vocab,
            sources = LiveSourceIdsProvider { ids },
        )

        assertEquals(1, matcher.match(fixture()).size)
        ids = emptyList()
        assertTrue(matcher.match(fixture()).isEmpty())
        ids = listOf(SourceId)
        assertEquals(1, matcher.match(fixture()).size)
    }

    // --- the soft country preference -------------------------------------------------------------

    @Test
    fun thePreferredCountryBoostsItsChannelsAboveTheRest() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(600, "US: ESPN+", epgKey = "us"),
                channel(601, "UK| SKY SPORTS MAIN EVENT", epgKey = "uk"),
            ),
            programmes = listOf(
                programme("us", title = "Coventry City v Newcastle United"),
                programme("uk", title = "Coventry City v Newcastle United"),
            ),
        )

        // No preference: both score 10, neither outranks the other.
        val plain = matcher(store).match(fixture())
        assertEquals(setOf("US: ESPN+", "UK| SKY SPORTS MAIN EVENT"), plain.map { it.channelName }.toSet())
        assertTrue(plain.all { it.score == 10 })

        // Prefer UK: Sky Sports sorts first with the boost, the US feed stays listed below.
        val preferred = matcher(store).match(
            fixture(),
            SportMatchOptions(preferCountry = "UK", languages = emptySet()),
        )
        assertEquals(listOf("UK| SKY SPORTS MAIN EVENT", "US: ESPN+"), preferred.map { it.channelName })
        assertEquals(SportsMatcher.PreferenceBoost + 10, preferred.first().score)
        assertEquals(10, preferred.last().score)
    }

    // --- the broadcast tier (regular channels, no team evidence) -------------------------------

    @Test
    fun theLeaguesOwnChannelMatchesWithoutTeamEvidence() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(700, "UK| SKY SPORTS PREMIER LEAGUE")),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("UK| SKY SPORTS PREMIER LEAGUE"), matches.map { it.channelName })
        assertEquals(2, matches.single().score)
        assertTrue(matches.single().isBroadcastTier)
        assertTrue(!matches.single().isExact)
    }

    @Test
    fun theSportsGenericChannelMatchesAtTheLowestTier() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(701, "UK| SKY SPORTS FOOTBALL")),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("UK| SKY SPORTS FOOTBALL"), matches.map { it.channelName })
        assertEquals(1, matches.single().score)
        assertTrue(matches.single().isBroadcastTier)
    }

    @Test
    fun aBroadcastTierChannelNeverBeatsRealEvidence() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(702, "UK| SKY SPORTS PREMIER LEAGUE"),
                channel(703, "US: ESPN+", epgKey = "us"),
            ),
            programmes = listOf(programme("us", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(fixture())

        // Without a country preference the team-evidenced feed leads, the league channel follows.
        assertEquals(listOf("US: ESPN+", "UK| SKY SPORTS PREMIER LEAGUE"), matches.map { it.channelName })
        assertEquals(10, matches.first().score)
        assertEquals(2, matches.last().score)
    }

    @Test
    fun thePreferredCountryLiftsTheLeagueChannelForTuningInEarly() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(704, "US: ESPN+", epgKey = "us"),
                channel(705, "UK| SKY SPORTS PREMIER LEAGUE"),
            ),
            programmes = listOf(programme("us", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(
            fixture().copy(state = FixtureState.UPCOMING),
            SportMatchOptions(preferCountry = "UK", languages = emptySet()),
        )

        assertEquals(
            listOf("UK| SKY SPORTS PREMIER LEAGUE", "US: ESPN+"),
            matches.map { it.channelName },
        )
        assertEquals(SportsMatcher.PreferenceBoost + 2, matches.first().score)
    }

    // --- the selector tier (sports category from the channel selector) --------------------------

    @Test
    fun aChannelTheSelectorCallsSportMatchesAtTheLowestTier() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(706, "VIP: SUPERSPORT VARIETY 1", categoryId = 9)),
            programmes = emptyList(),
            categories = listOf(category(9, "Sports")),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("VIP: SUPERSPORT VARIETY 1"), matches.map { it.channelName })
        assertEquals(1, matches.single().score)
        assertTrue(matches.single().isBroadcastTier)
    }

    @Test
    fun aChannelTheSelectorDoesNotCallSportDoesNotMatch() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(707, "VIP: MOVIES NOW", categoryId = 10)),
            programmes = emptyList(),
            categories = listOf(category(10, "Movies")),
        )

        assertTrue(matcher(store).match(fixture()).isEmpty())
    }

    @Test
    fun selectorSportChannelsStillYieldToRealEvidence() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(708, "US: ESPN+", epgKey = "us"),
                channel(709, "UK| SKY SPORTS MAIN EVENT", categoryId = 11),
            ),
            programmes = listOf(programme("us", title = "Coventry City v Newcastle United")),
            categories = listOf(category(11, "Sport")),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(preferCountry = "UK", languages = emptySet()),
        )

        // The selector-tier Sky channel is the only UK channel: the preference lifts it above the
        // US-evidenced feed for tuning in early, exactly as intended.
        assertEquals(
            listOf("UK| SKY SPORTS MAIN EVENT", "US: ESPN+"),
            matches.map { it.channelName },
        )
        assertEquals(SportsMatcher.PreferenceBoost + 1, matches.first().score)
        assertEquals(10, matches.last().score)
    }

    // --- fixtures / vocabulary ------------------------------------------------------------------

    private val vocab = SportsVocab.of(
        aliases = mapOf(
            "Newcastle United" to listOf("Magpies"),
            "Ohio State Buckeyes" to listOf("Ohio State", "OSU", "Buckeyes"),
            "Michigan Wolverines" to listOf("Michigan", "Wolverines"),
        ),
        teamStopwords = setOf("fc", "club"),
        broadcastStopwords = setOf("sky", "sport", "hd", "tv"),
        sportWords = setOf("football", "soccer", "fussball"),
    )

    private fun matcher(store: FakeStore, vocab: SportsVocab = this.vocab) = SportsMatcher(
        channelDao = store.channelDao,
        epgDao = store.epgDao,
        categoryDao = store.categoryDao,
        vocab = vocab,
        sources = object : LiveSourceIdsProvider {
            override suspend fun liveSourceIds(): List<Long> = listOf(SourceId)
        },
    )

    private fun fixture() = Fixture(
        id = "1",
        leagueId = "soccer.eng.1",
        dateMs = Noon,
        state = FixtureState.LIVE,
        statusLine = "20:55",
        home = SportTeam("1", "Coventry City", "COV", null, null, "1", false, "home"),
        away = SportTeam("2", "Newcastle United", "NEW", null, null, "0", false, "away"),
        broadcasts = emptyList(),
        leaguePhrase = "Premier League",
        venue = null,
    )

    private fun championsFixture() = fixture().copy(id = "cl-1", leagueId = "soccer.uefa.champions")

    private fun youthFixture() = fixture().copy(id = "yl-1", leagueId = "soccer.uefa.youth")

    private fun collegeFixture() = Fixture(
        id = "cfb-1",
        leagueId = "football.college-football",
        dateMs = Noon,
        state = FixtureState.LIVE,
        statusLine = "Q3 04:12",
        home = SportTeam("1", "Ohio State Buckeyes", "OSU", null, null, "21", false, "home"),
        away = SportTeam("2", "Michigan Wolverines", "MICH", null, null, "17", false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun duelFixture(
        sideA: String = "Karen Khachanov",
        sideB: String = "Alex de Minaur",
        hints: List<String> = emptyList(),
        tournament: String? = null,
    ) = Fixture(
        id = "tennis.atp/1/2",
        leagueId = "tennis.atp",
        dateMs = Noon,
        state = FixtureState.LIVE,
        statusLine = "LIVE",
        format = FixtureFormat.VERSUS,
        entries = listOf(
            FixtureEntry(id = "1", name = sideA, order = 1, detail = null, logoUrl = null),
            FixtureEntry(id = "2", name = sideB, order = 2, detail = null, logoUrl = null),
        ),
        broadcasts = emptyList(),
        channelHints = hints,
        tournament = tournament,
        venue = null,
    )

    private fun channel(id: Long, name: String, epgKey: String? = null, categoryId: Long? = null) = ChannelEntity(
        id = id,
        sourceId = SourceId,
        categoryId = categoryId,
        name = name,
        streamUrl = "http://provider.invalid/$id.ts",
        epgChannelId = epgKey,
    )

    private fun category(id: Long, name: String) = CategoryEntity(
        id = id,
        sourceId = SourceId,
        mediaType = MediaType.LIVE,
        name = name,
        remoteId = "cat-$id",
    )

    private fun programme(key: String, title: String, description: String? = null) = EpgProgrammeEntity(
        sourceId = SourceId,
        epgChannelId = key,
        startMs = Noon,
        stopMs = Noon + 2L * 60L * 60L * 1000L,
        title = title,
        description = description,
    )

    private companion object {
        const val SourceId = 1L

        /** A kickoff time; every programme above sits inside the matcher's t-2h … t+4h window. */
        const val Noon = 1_760_000_000_000L
    }
}

/**
 * An in-memory stand-in for the two DAOs, implementing only the handful of queries the matcher
 * issues and returning `null` for everything else (each call site is `runCatching`-guarded, so an
 * un-implemented method is simply "no rows"). Hand-writing all ~70 [ChannelDao] members would bury
 * the test's intent, so a [Proxy] fills the rest.
 */
private class FakeStore(
    private val channels: List<ChannelEntity>,
    private val programmes: List<EpgProgrammeEntity>,
    private val categories: List<CategoryEntity> = emptyList(),
) {
    val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
        when (name) {
            "searchList" -> {
                val query = args!![0] as String
                channels.filter { it.name.contains(query, ignoreCase = true) }
            }
            "byEpgKeys" -> {
                val keys = (args!![1] as List<*>).filterIsInstance<String>()
                    .map { it.trim().lowercase() }
                channels.filter { channel -> channel.epgChannelId?.trim()?.lowercase() in keys }
            }
            "getById" -> channels.firstOrNull { it.id == args!![0] as Long }
            "allForSources" -> channels
            else -> null
        }
    }

    val categoryDao: CategoryDao = proxy(CategoryDao::class.java) { name, _ ->
        when (name) {
            "observe" -> flowOf(categories)
            else -> null
        }
    }

    val epgDao: EpgDao = proxy(EpgDao::class.java) { name, args ->
        when (name) {
            // `title LIKE '%query%'`, case-insensitive for ASCII as SQLite's LIKE is.
            "searchTitles" -> matches(args!![0] as String, includeDescription = false)
            // `title LIKE '%query%' OR description LIKE '%query%'`.
            "searchGuide" -> matches(args!![0] as String, includeDescription = true)
            "programmesForChannel" -> {
                val key = (args!![0] as String).trim().lowercase()
                programmes.filter { it.epgChannelId.trim().lowercase() == key }
            }
            else -> null
        }
    }

    private fun matches(query: String, includeDescription: Boolean): List<EpgProgrammeEntity> {
        val needle = query.lowercase()
        return programmes.filter { programme ->
            programme.title.lowercase().contains(needle) ||
                (includeDescription && programme.description?.lowercase()?.contains(needle) == true)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        when (method.name) {
            "toString" -> "Fake${type.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> handler(method.name, args)
        }
    } as T
