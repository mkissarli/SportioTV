package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

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

    // --- (l) duels: a broadcaster hint alone is a bonus, not a qualification ------------

    @Test
    fun duelHintAloneDoesNotQualifyAChannel() = runBlocking {
        // "US TENNIS PPV" shares only the word "tennis" with this duel — exactly the weak-signal
        // flood the web-guide rule removed: any tennis fixture on any tennis-named channel.
        val store = FakeStore(
            channels = listOf(channel(15, "US TENNIS PPV")),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(duelFixture(hints = listOf("Tennis")))

        assertTrue(matches.isEmpty())
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
        // The hint ("Tennis") alone no longer qualifies "DE: SKY SPORT TENNIS" — only the exact
        // per-event channel, which names both athletes, survives the foreign language filter.
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
            listOf("NEXT | ALEX DE MINAUR - KAREN KHACHANOV | Tue 13 Oct 06:00 CEST (FR) | FR: MAX PPV 21"),
            matches.map { it.channelName },
        )
        assertEquals(listOf(4), matches.map { it.score })
        assertEquals(listOf(true), matches.map { it.isExact })
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
            vocab = vocab,
            sources = LiveSourceIdsProvider { ids },
        )

        assertEquals(1, matcher.match(fixture()).size)
        ids = emptyList()
        assertTrue(matcher.match(fixture()).isEmpty())
        ids = listOf(SourceId)
        assertEquals(1, matcher.match(fixture()).size)
    }

    // --- the preferred country (soft boost) -----------------------------------------------------

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

        // Prefer UK: Sky Sports sorts first, the US feed stays listed below. The tier is a sort
        // key only, so the reported score stays the pure evidence value.
        val preferred = matcher(store).match(
            fixture(),
            SportMatchOptions(preferCountry = "UK", languages = emptySet()),
        )
        assertEquals(listOf("UK| SKY SPORTS MAIN EVENT", "US: ESPN+"), preferred.map { it.channelName })
        assertTrue(preferred.all { it.score == 10 })
    }

    // --- kickoff containment: a same-name listing at another hour is not the fixture ------------

    @Test
    fun aProgrammeNotCoveringKickoffDoesNotScore() = runBlocking {
        // The guide lists the fixture at 23:55 — three hours after the 20:55 kickoff (a replay),
        // still inside the discovery window but not covering kickoff.
        val store = FakeStore(
            channels = listOf(channel(610, "UK| SKY SPORTS MAIN EVENT", epgKey = "late")),
            programmes = listOf(
                programme(
                    "late",
                    title = "Coventry City v Newcastle United",
                    startMs = Noon + 3 * HourMs,
                    stopMs = Noon + 5 * HourMs,
                ),
            ),
        )

        assertTrue(matcher(store).match(fixture()).isEmpty())
    }

    @Test
    fun thePreMatchShowCoveringKickoffStillScores() = runBlocking {
        // Build-up from 2h before kickoff through the final whistle names both teams and covers
        // kickoff — the pre-match channel the viewer wants to tune into early.
        val store = FakeStore(
            channels = listOf(channel(611, "UK| SKY SPORTS MAIN EVENT", epgKey = "buildup")),
            programmes = listOf(
                programme(
                    "buildup",
                    title = "Coventry City v Newcastle United",
                    startMs = Noon - 2 * HourMs,
                    stopMs = Noon + 3 * HourMs,
                ),
            ),
        )

        val matches = matcher(store).match(fixture())
        assertEquals(listOf("UK| SKY SPORTS MAIN EVENT"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
    }

    // --- PPV channels: demoted for team sports, kept for duels (tennis/MMA) and events ----------

    @Test
    fun ppvChannelsSortBelowRealChannelsEvenWithPreference() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(612, "US: ESPN+", epgKey = "us"),
                channel(613, "UK| PPV EVENT 01 | Coventry City v Newcastle United"),
            ),
            programmes = listOf(programme("us", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(preferCountry = "UK", languages = emptySet()),
        )

        // The UK PPV feed names both teams and gets the UK boost, but the PPV demotion — applied
        // after the boost — keeps it below the US channel that actually carries the fixture.
        assertEquals(
            listOf("US: ESPN+", "UK| PPV EVENT 01 | Coventry City v Newcastle United"),
            matches.map { it.channelName },
        )
        assertEquals(10, matches.first().score)
    }

    @Test
    fun ppvChannelsAreNotDemotedForDuelFixtures() = runBlocking {
        // Tennis: "US TENNIS PPV" IS the carrier. The tournament tier (+1) plus the "Tennis" hint
        // (+6) must keep it above a channel that only names the tournament — the PPV demotion is
        // for team sports, where a dedicated event feed competes with real broadcasters.
        val store = FakeStore(
            channels = listOf(
                channel(622, "US TENNIS PPV", epgKey = "ppv"),
                channel(623, "IT: SUPER SPORT ARENA", epgKey = "it"),
            ),
            programmes = listOf(
                programme("ppv", title = "Tennis: ATP Shanghai"),
                programme("it", title = "Tennis: ATP Shanghai"),
            ),
        )

        val duel = duelFixture(hints = listOf("Tennis"), tournament = "ATP Shanghai")
        val matches = matcher(store).match(duel, SportMatchOptions(languages = emptySet()))

        assertEquals(listOf("US TENNIS PPV", "IT: SUPER SPORT ARENA"), matches.map { it.channelName })
        assertEquals(7, matches.first().score)
        assertEquals(1, matches.last().score)
    }

    // --- broadcast words: a multi-word broadcast must not match by one generic word ------------

    @Test
    fun aMultiWordBroadcastDoesNotMatchByASingleWord() = runBlocking {
        // "NBA League Pass" splits into "league" / "pass": neither may match "UK: NATIONAL LEAGUE"
        // (a football league's channels) for a basketball fixture.
        val store = FakeStore(
            channels = listOf(channel(620, "UK| NATIONAL LEAGUE 1")),
            programmes = emptyList(),
        )

        val nba = Fixture(
            id = "nba-1",
            leagueId = "basketball.nba",
            dateMs = Noon,
            state = FixtureState.LIVE,
            statusLine = "Q3 04:12",
            home = SportTeam("1", "Los Angeles Lakers", "LAL", null, null, "51", false, "home"),
            away = SportTeam("2", "Denver Nuggets", "DEN", null, null, "47", false, "away"),
            broadcasts = listOf("NBA League Pass"),
            venue = null,
        )

        assertTrue(matcher(store).match(nba).isEmpty())
    }

    @Test
    fun aSingleWordBroadcastAloneDoesNotQualifyAChannel() = runBlocking {
        // The broadcast word ("ESPN" in "ESPN 2") is a rank signal, not evidence: a fixture with
        // two participants needs both teams (name or one EPG programme) or a guide confirmation.
        val store = FakeStore(
            channels = listOf(channel(621, "ESPN 2")),
            programmes = emptyList(),
        )

        val nba = Fixture(
            id = "nba-2",
            leagueId = "basketball.nba",
            dateMs = Noon,
            state = FixtureState.LIVE,
            statusLine = "Q3 04:12",
            home = SportTeam("1", "Los Angeles Lakers", "LAL", null, null, "51", false, "home"),
            away = SportTeam("2", "Denver Nuggets", "DEN", null, null, "47", false, "away"),
            broadcasts = listOf("ESPN"),
            venue = null,
        )

        assertTrue(matcher(store).match(nba).isEmpty())
    }

    // --- events without participants: competition signals still qualify -----------------------

    @Test
    fun aBroadcastAloneCanQualifyAnEventWithoutParticipants() = runBlocking {
        // An EVENT fixture (F1) has no team names — the broadcast name IS the competition signal.
        val store = FakeStore(
            channels = listOf(channel(622, "UK| SKY SPORTS F1")),
            programmes = emptyList(),
        )

        val f1 = Fixture(
            id = "f1-1",
            leagueId = "racing.f1",
            dateMs = Noon,
            state = FixtureState.UPCOMING,
            statusLine = "16:00",
            format = FixtureFormat.EVENT,
            title = "United States Grand Prix",
            broadcasts = listOf("Sky Sports F1"),
            venue = "Austin",
        )

        val matches = matcher(store).match(f1)

        assertEquals(listOf("UK| SKY SPORTS F1"), matches.map { it.channelName })
        assertEquals(3, matches.single().score)
    }

    @Test
    fun anEventTitleMatchesItsEpgCarrierWithoutAnyHint() = runBlocking {
        // No hard-coded channel hint: the race title ("United States Grand Prix") is the fixture's
        // competition text — it drives discovery AND scoring against the EPG line.
        val store = FakeStore(
            channels = listOf(channel(623, "PT| TVI SPORTS", epgKey = "tvi")),
            programmes = listOf(programme("tvi", title = "Formula 1: United States Grand Prix")),
        )

        val f1 = Fixture(
            id = "f1-2",
            leagueId = "racing.f1",
            dateMs = Noon,
            state = FixtureState.UPCOMING,
            statusLine = "16:00",
            format = FixtureFormat.EVENT,
            title = "United States Grand Prix",
            broadcasts = emptyList(),
            venue = null,
        )

        val matches = matcher(store).match(f1)

        assertEquals(listOf("PT| TVI SPORTS"), matches.map { it.channelName })
        assertEquals(1, matches.single().score)
    }

    @Test
    fun anEpgRaceProgrammeDoesNotMatchThePracticeSession() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(624, "UK| SKY SPORTS F1", epgKey = "f1")),
            programmes = listOf(
                programme("f1", title = "Formula 1: United States Grand Prix Race"),
            ),
        )
        val practice = Fixture(
            id = "f1-practice",
            leagueId = "racing.f1",
            dateMs = Noon,
            state = FixtureState.UPCOMING,
            statusLine = "15:00",
            format = FixtureFormat.EVENT,
            title = "United States Grand Prix — Practice 1",
            broadcasts = emptyList(),
            venue = "Austin",
        )
        val race = practice.copy(id = "f1-race", title = "United States Grand Prix — Race")

        assertTrue(matcher(store).match(practice).isEmpty())
        assertEquals(listOf("UK| SKY SPORTS F1"), matcher(store).match(race).map { it.channelName })
    }

    // --- rule-4 dedup: one display entry per underlying channel -------------------------------

    @Test
    fun theSameChannelVariantAppearsOnceAndKeepsTheBestResolution() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(641, "UK| SKY SPORTS PREMIER LEAGUE"),
                channel(642, "UK: SKY SPORTS PREMIER LEAGUE HD"),
                channel(643, "DE: SKY SPORT PREMIER LEAGUE", epgKey = "de-premier"),
            ),
            programmes = listOf(programme("de-premier", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(languages = emptySet(), confirmedBroadcasters = listOf("Sky Sports Premier League")),
        )

        // Two variants of one Sky channel collapse into the best-resolution copy; the German EPG
        // evidence channel keeps its own slot.
        assertEquals(
            listOf("UK: SKY SPORTS PREMIER LEAGUE HD", "DE: SKY SPORT PREMIER LEAGUE"),
            matches.map { it.channelName },
        )
    }

    // --- TV-guide-confirmed broadcasters --------------------------------------------------------

    @Test
    fun theChelseaTottenhamRowResolvesBothGuideCarriersToPlaylistRows() = runBlocking {
        // The exact observed row: Chelsea v Tottenham Hotspur, Premier League, carriers Sky Sports
        // Main Event and TNT Sports 1. The provider spells the second one "UK| TNT SPORTS 1 HD".
        val store = FakeStore(
            channels = listOf(
                channel(800, "UK| TNT SPORTS 1 HD"),
                channel(801, "NOW: Sky Sports Main Event 4K"),
                channel(802, "UK| SKY SPORTS NEWS"),
            ),
            programmes = emptyList(),
        )
        val chelsea = fixture().copy(
            home = SportTeam("1", "Chelsea", "CHE", null, null, "1", false, "home"),
            away = SportTeam("2", "Tottenham Hotspur", "TOT", null, null, "0", false, "away"),
        )

        val matches = matcher(store).match(
            chelsea,
            SportMatchOptions(
                languages = emptySet(),
                confirmedBroadcasters = listOf("Sky Sports Main Event", "TNT Sports 1"),
            ),
        )

        assertEquals(
            listOf("NOW: Sky Sports Main Event 4K", "UK| TNT SPORTS 1 HD"),
            matches.map { it.channelName },
        )
        assertTrue(matches.all { it.guideConfirmed })
        assertTrue(matches.none { it.channelName.contains("NEWS") })
    }


    @Test
    fun theWebGuideCarrierIsTheWholeList() = runBlocking {
        // The guide names "Sky Sports Premier League" as the carrier; the provider's EPG says
        // nothing. The guide is the answer — no scoring tiers involved.
        val store = FakeStore(
            channels = listOf(channel(630, "UK| SKY SPORTS PREMIER LEAGUE")),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(languages = emptySet(), confirmedBroadcasters = listOf("Sky Sports Premier League")),
        )

        assertEquals(listOf("UK| SKY SPORTS PREMIER LEAGUE"), matches.map { it.channelName })
        assertTrue(matches.single().guideConfirmed)
    }

    @Test
    fun aGuideCarrierAndAnIndependentEpgCarrierBothSurvive() = runBlocking {
        // The guide names Sky Sports; an independent EPG programme names both teams on ESPN+.
        // Both evidence sources survive the merge — the guide no longer suppresses the EPG.
        val store = FakeStore(
            channels = listOf(
                channel(631, "UK| SKY SPORTS PREMIER LEAGUE"),
                channel(632, "US: ESPN+", epgKey = "us"),
            ),
            programmes = listOf(programme("us", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(languages = emptySet(), confirmedBroadcasters = listOf("Sky Sports Premier League")),
        )

        assertEquals(
            listOf("UK| SKY SPORTS PREMIER LEAGUE", "US: ESPN+"),
            matches.map { it.channelName },
        )
        assertTrue(matches.first().guideConfirmed)
        assertTrue(matches.last().epgConfirmed)
    }

    @Test
    fun theHouseCountryOrderIsUkUsFrAr() = runBlocking {
        val title = "Coventry City v Newcastle United"
        val store = FakeStore(
            channels = listOf(
                channel(640, "AR: BEIN SPORTS 1", epgKey = "ar"),
                channel(641, "FR: BEIN SPORTS 1", epgKey = "fr"),
                channel(642, "US: ESPN+", epgKey = "us"),
                channel(643, "UK| SKY SPORTS MAIN EVENT", epgKey = "uk"),
            ),
            programmes = listOf(
                programme("ar", title = title),
                programme("fr", title = title),
                programme("us", title = title),
                programme("uk", title = title),
            ),
        )

        // Equal evidence for all four, so only the country tiers decide the order.
        val matches = matcher(store).match(fixture(), SportMatchOptions(languages = emptySet()))
        assertEquals(
            listOf("UK| SKY SPORTS MAIN EVENT", "US: ESPN+", "FR: BEIN SPORTS 1", "AR: BEIN SPORTS 1"),
            matches.map { it.channelName },
        )
        assertTrue(matches.all { it.score == 10 })
    }

    @Test
    fun theWebGuideNameMatchesWithAPrefixSuffixAndPluralS() = runBlocking {
        // The provider writes "UK: SKY SPORT …" (singular) and prefixes the feed; the guide writes
        // "Sky Sports …". A full-phrase, plural-tolerant match must still find them.
        val store = FakeStore(
            channels = listOf(
                channel(650, "UK: SKY SPORT MAIN EVENT ᴿᴬᵂ"),
                channel(651, "NOW: SKY SPORTS PREMIER LEAGUE ᴴᴰ"),
                channel(652, "DE: SKY SPORT NEWS"),
            ),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(
                languages = emptySet(),
                confirmedBroadcasters = listOf("Sky Sports Main Event", "Sky Sports Premier League"),
            ),
        )

        assertEquals(
            setOf("UK: SKY SPORT MAIN EVENT ᴿᴬᵂ", "NOW: SKY SPORTS PREMIER LEAGUE ᴴᴰ"),
            matches.map { it.channelName }.toSet(),
        )
        assertTrue(matches.all { it.guideConfirmed })
    }

    @Test
    fun aPlusInAGuideNameMeansThePlusServiceNotEverySkyChannel() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(653, "UK: SKY SPORT PLUS ᴿᴬᵂ"),
                channel(654, "DE: SKY SPORT NEWS"),
                channel(655, "DE: SKY SPORT MIX"),
            ),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(languages = emptySet(), confirmedBroadcasters = listOf("Sky Sports+")),
        )

        assertEquals(listOf("UK: SKY SPORT PLUS ᴿᴬᵂ"), matches.map { it.channelName })
    }

    @Test
    fun aGuideCarrierSurvivesAStrictLanguagePreference() = runBlocking {
        // The user's preference is German-only and strict; the guide says a UK channel carries it.
        // The guide wins: the list must still show the channel Where's The Match names.
        val store = FakeStore(
            channels = listOf(
                channel(660, "UK: SKY SPORTS PREMIER LEAGUE"),
                channel(661, "DE: SKY SPORT PREMIER LEAGUE"),
            ),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(
                languages = setOf(SportLanguage.GERMAN),
                strict = true,
                confirmedBroadcasters = listOf("Sky Sports Premier League"),
            ),
        )

        assertEquals("UK: SKY SPORTS PREMIER LEAGUE", matches.first().channelName)
    }

    // --- priors and accepted full-playlist AI evidence ----------------------------------------

    @Test
    fun aPriorAloneNeverConfirmsAChannel() = runBlocking {
        // The prior names Sky Sports Main Event, but neither the guide, the EPG nor the channel
        // name identifies this fixture. A prior is context, never confirmation.
        val store = FakeStore(
            channels = listOf(channel(700, "UK| SKY SPORTS MAIN EVENT")),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(
                languages = emptySet(),
                priorCarriers = listOf("Sky Sports Main Event"),
            ),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun anAcceptedAiSelectionMapsToItsPlaylistRow() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(710, "PPV 1"),
                channel(711, "UK| SKY SPORTS MAIN EVENT"),
            ),
            programmes = emptyList(),
        )
        val aiRow = ChannelMatch(
            channelId = 710,
            channelName = "PPV 1",
            logoUrl = null,
            epgTitle = null,
            score = 8,
            aiConfirmed = true,
            aiReason = "Event-specific feed",
            sourceId = SourceId,
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(
                languages = emptySet(),
                priorCarriers = listOf("PPV 1", "Sky Sports Main Event"),
                aiChannels = listOf(aiRow),
            ),
        )

        assertEquals(listOf("PPV 1"), matches.map { it.channelName })
        assertTrue(matches.single().aiConfirmed)
        assertEquals("Event-specific feed", matches.single().aiReason)
    }

    @Test
    fun aStaleAiRowFromAnotherSourceIsDropped() = runBlocking {
        val store = FakeStore(channels = listOf(channel(712, "PPV 2")), programmes = emptyList())
        val stale = ChannelMatch(
            channelId = 999,
            channelName = "PPV 2",
            logoUrl = null,
            epgTitle = null,
            score = 8,
            aiConfirmed = true,
            sourceId = SourceId,
        )

        assertTrue(
            matcher(store).match(
                fixture(),
                SportMatchOptions(languages = emptySet(), aiChannels = listOf(stale)),
            ).isEmpty(),
        )
    }

    @Test
    fun aValidAiNoneAddsNothing() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(720, "UK| SKY SPORTS MAIN EVENT", epgKey = "uk")),
            programmes = listOf(programme("uk", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(languages = emptySet(), aiChannels = emptyList()),
        )

        assertEquals(listOf("UK| SKY SPORTS MAIN EVENT"), matches.map { it.channelName })
        assertEquals(listOf(10), matches.map { it.score })
    }

    @Test
    fun strongGuideEvidenceOutranksAnAiOnlyRowEvenInThePreferredCountry() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(730, "US: PPV EVENT 9"),
                channel(731, "DE: SKY SPORT PREMIER LEAGUE"),
            ),
            programmes = emptyList(),
        )
        val aiRow = ChannelMatch(
            channelId = 730,
            channelName = "US: PPV EVENT 9",
            logoUrl = null,
            epgTitle = null,
            score = 9,
            aiConfirmed = true,
            sourceId = SourceId,
        )

        val matches = matcher(store).match(
            fixture(),
            SportMatchOptions(
                languages = emptySet(),
                preferCountry = "US",
                confirmedBroadcasters = listOf("Sky Sport Premier League"),
                aiChannels = listOf(aiRow),
            ),
        )

        // Guide evidence is its own class; the weak country preference cannot lift AI-only above it.
        assertEquals(listOf("DE: SKY SPORT PREMIER LEAGUE", "US: PPV EVENT 9"), matches.map { it.channelName })
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

    private fun channel(id: Long, name: String, epgKey: String? = null) = ChannelEntity(
        id = id,
        sourceId = SourceId,
        name = name,
        streamUrl = "http://provider.invalid/$id.ts",
        epgChannelId = epgKey,
    )

    private fun programme(
        key: String,
        title: String,
        description: String? = null,
        startMs: Long = Noon,
        stopMs: Long = Noon + 2 * HourMs,
    ) = EpgProgrammeEntity(
        sourceId = SourceId,
        epgChannelId = key,
        startMs = startMs,
        stopMs = stopMs,
        title = title,
        description = description,
    )

    private companion object {
        const val SourceId = 1L

        /** A kickoff time; every programme above sits inside the matcher's t-2h … t+4h window. */
        const val Noon = 1_760_000_000_000L
        const val HourMs = 60L * 60L * 1000L
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
