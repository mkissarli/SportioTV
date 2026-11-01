package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelSearchResult
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Small labelled quality set over the deterministic rules: one expected top-1 answer per scenario,
 * including the cases that must return nothing. It is a regression guard, not a claim about real
 * provider accuracy (that needs a real playlist, see docs/SPORT-DETERMINISTIC-MATCHING.md).
 */
class SportMatchingQualityTest {

    @Test
    fun theLabelledScenariosKeepTheirExpectedTopOne() = runBlocking {
        var correct = 0
        var expected = 0
        var noResultCorrect = 0
        var noResultCases = 0

        for (case in cases()) {
            val matches = matcher(case.store).match(case.fixture, case.options)
            val top = matches.firstOrNull()?.channelName
            if (case.expected == null) {
                noResultCases++
                if (matches.isEmpty()) noResultCorrect++
            } else {
                expected++
                if (top == case.expected) correct++
            }
            assertEquals(case.name, case.expected, top)
        }

        println("precision@1: $correct/$expected, no-result accuracy: $noResultCorrect/$noResultCases")
        assertEquals(expected, correct)
        assertEquals(noResultCases, noResultCorrect)
    }

    private fun cases(): List<QualityCase> {
        val epgStore = store(
            channels = listOf(channel(1, "IT: SKY SPORT ARENA", epg = "arena")),
            programmes = listOf(programme("arena", "Coventry City v Newcastle United")),
        )
        val directGuide = listing("Sky Sports Main Event", direct = true)
        val tournamentGuide = listing("Sky Sports Tennis", direct = false, coverage = CoverageType.TOURNAMENT, sport = SportClass.TENNIS)

        val f1Race = Fixture(
            id = "f1-race",
            leagueId = "racing.f1",
            dateMs = Noon,
            state = FixtureState.LIVE,
            statusLine = "LIVE",
            format = FixtureFormat.EVENT,
            title = "United States Grand Prix — Race",
            broadcasts = emptyList(),
            venue = "Austin",
        )
        val f1Row = GuideEntry(
            dateMs = Noon,
            league = "Formula 1",
            teams = "Formula 1 United States Grand Prix Race",
            channels = listOf("Sky Sports F1"),
        )
        val f1Store = store(channels = listOf(channel(7, "UK| SKY SPORTS F1")), programmes = emptyList())

        return listOf(
            QualityCase(
                "epg both teams",
                epgStore,
                fixture(),
                SportMatchOptions(languages = emptySet()),
                "IT: SKY SPORT ARENA",
            ),
            QualityCase(
                "direct guide without EPG",
                store(
                    channels = listOf(channel(2, "UK| SKY SPORTS MAIN EVENT"), channel(20, "UK| SKY SPORTS CRICKET")),
                    programmes = emptyList(),
                ),
                fixture(),
                SportMatchOptions(languages = emptySet(), guideListings = listOf(directGuide)),
                "UK| SKY SPORTS MAIN EVENT",
            ),
            QualityCase(
                "specialist fallback",
                store(channels = listOf(channel(3, "UK| SKY SPORTS FOOTBALL")), programmes = emptyList()),
                fixture(),
                SportMatchOptions(languages = emptySet()),
                "UK| SKY SPORTS FOOTBALL",
            ),
            QualityCase(
                "tennis tour listing is not a match confirmation",
                store(channels = listOf(channel(4, "UK| SKY SPORTS TENNIS")), programmes = emptyList()),
                tennisFixture(),
                SportMatchOptions(languages = emptySet(), guideListings = listOf(tournamentGuide)),
                "UK| SKY SPORTS TENNIS",
            ),
            QualityCase(
                "direct guide survives preferred language",
                store(channels = listOf(channel(5, "DE: SKY SPORT BUNDESLIGA")), programmes = emptyList()),
                fixture(),
                SportMatchOptions(
                    languages = setOf(SportLanguage.ENGLISH),
                    guideListings = listOf(listing("Sky Sport Bundesliga", direct = true)),
                ),
                "DE: SKY SPORT BUNDESLIGA",
            ),
            QualityCase(
                "F1 race session row",
                f1Store,
                f1Race,
                SportMatchOptions(languages = emptySet(), guideListings = GuideMatch.listings(listOf(f1Row), f1Race, System.currentTimeMillis())),
                "UK| SKY SPORTS F1",
            ),
            QualityCase(
                "F1 practice does not borrow the race row",
                f1Store,
                f1Race.copy(id = "f1-practice", title = "United States Grand Prix — Practice 1"),
                SportMatchOptions(
                    languages = emptySet(),
                    guideListings = GuideMatch.listings(
                        listOf(f1Row),
                        f1Race.copy(id = "f1-practice", title = "United States Grand Prix — Practice 1"),
                        System.currentTimeMillis(),
                    ),
                ),
                null,
            ),
            QualityCase(
                "one team in the EPG is not the fixture",
                store(
                    channels = listOf(channel(8, "DE: SKY SPORT NEWS", epg = "news")),
                    programmes = listOf(programme("news", "Newcastle United News")),
                ),
                fixture(),
                SportMatchOptions(languages = emptySet()),
                null,
            ),
        )
    }

    private data class QualityCase(
        val name: String,
        val store: QualityStore,
        val fixture: Fixture,
        val options: SportMatchOptions,
        val expected: String?,
    )

    private fun fixture() = Fixture(
        id = "1",
        leagueId = "soccer.eng.1",
        dateMs = Noon,
        state = FixtureState.LIVE,
        statusLine = "LIVE",
        home = SportTeam("1", "Coventry City", "COV", null, null, "0", false, "home"),
        away = SportTeam("2", "Newcastle United", "NEW", null, null, "0", false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun tennisFixture() = Fixture(
        id = "tennis-1",
        leagueId = "tennis.atp",
        dateMs = Noon,
        state = FixtureState.LIVE,
        statusLine = "LIVE",
        format = FixtureFormat.VERSUS,
        entries = listOf(
            FixtureEntry("1", "Karen Khachanov", 1, null, null),
            FixtureEntry("2", "Alex de Minaur", 2, null, null),
        ),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun listing(
        name: String,
        direct: Boolean,
        coverage: CoverageType = if (direct) CoverageType.FIXTURE else CoverageType.TOURNAMENT,
        sport: SportClass = SportClass.FOOTBALL,
    ) = BroadcastListing(
        eventId = "1",
        sport = sport,
        competition = null,
        participants = listOf("Coventry City", "Newcastle United"),
        eventStartTimeMs = Noon,
        coverageStartTimeMs = Noon,
        coverageEndTimeMs = Noon + 2 * HourMs,
        broadcaster = name,
        channel = name,
        territory = "UK",
        language = "en",
        coverageType = coverage,
        sourceId = "wheresthematch",
        retrievedAtMs = System.currentTimeMillis(),
        confidence = if (direct) 90 else 45,
        direct = direct,
    )

    private fun matcher(store: QualityStore) = SportsMatcher(
        channelDao = store.channelDao,
        epgDao = store.epgDao,
        vocab = SportsVocab.EMPTY,
        sources = { listOf(SourceId) },
    )

    private fun store(channels: List<ChannelEntity>, programmes: List<EpgProgrammeEntity>) = QualityStore(channels, programmes)

    private fun channel(id: Long, name: String, epg: String? = null) = ChannelEntity(
        id = id,
        sourceId = SourceId,
        name = name,
        streamUrl = "http://provider.invalid/$id.ts",
        epgChannelId = epg,
    )

    private fun programme(key: String, title: String) = EpgProgrammeEntity(
        sourceId = SourceId,
        epgChannelId = key,
        startMs = Noon,
        stopMs = Noon + 2 * HourMs,
        title = title,
        description = null,
    )

    private class QualityStore(
        private val channels: List<ChannelEntity>,
        private val programmes: List<EpgProgrammeEntity>,
    ) {
        val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
            when (name) {
                "allForSources" -> channels
                "byEpgKeys" -> {
                    val keys = (args!![1] as List<*>).filterIsInstance<String>().map { it.lowercase() }
                    channels.filter { it.epgChannelId?.lowercase() in keys }
                }
                "searchListDetailedFts" -> {
                    val query = args!![0] as String
                    val terms = Regex("\"([^\"]+)\"").findAll(query).map { it.groupValues[1].lowercase() }.toList()
                    channels.filter { row -> terms.any { row.name.lowercase().contains(it) } }
                        .map { ChannelSearchResult(it, "") }
                }
                else -> null
            }
        }
        val epgDao: EpgDao = proxy(EpgDao::class.java) { name, args ->
            when (name) {
                "searchTitles", "searchGuide" -> {
                    val needle = (args!![0] as String).lowercase()
                    programmes.filter { programme ->
                        programme.title.lowercase().contains(needle) ||
                            (name == "searchGuide" && programme.description?.lowercase()?.contains(needle) == true)
                    }
                }
                else -> emptyList<Any>()
            }
        }
    }

    private companion object {
        const val SourceId = 1L
        const val Noon = 1_760_000_000_000L
        const val HourMs = 60L * 60L * 1000L
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        when (method.name) {
            "toString" -> "Quality${type.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> handler(method.name, args)
        }
    } as T
