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
 * Labelled playlist-matching regression set.
 *
 * Every case carries its own expected playlist rows, including the empty ones. Two runs are
 * measured: deterministic evidence (guide + name/EPG) and the same run with a **stubbed perfect**
 * full-playlist model answer for the opaque feeds. The stub measures the merge/validation wiring —
 * it is not a claim about DeepSeek's live accuracy; that needs a real key and a manual run.
 */
class PlaylistEvaluationTest {

    @Test
    fun labelledSetMeetsThePrecisionAndRecallGates() = runBlocking {
        val cases = labelledCases()

        val deterministic = cases.map { it to run(cases = listOf(it), ai = false).single() }
        val withAi = cases.map { it to run(cases = listOf(it), ai = true).single() }

        val det = measure(deterministic)
        val plus = measure(withAi)

        // No displayed row may be missing from the case's playlist at any point.
        (deterministic + withAi).forEach { (case, result) ->
            val ids = case.channels.mapTo(HashSet()) { it.id }
            assertTrue(result.all { it.channelId in ids })
        }

        assertTrue("deterministic precision ${det.precision}", det.precision >= 0.98)
        assertTrue("plus-AI precision ${plus.precision}", plus.precision >= 0.98)
        assertTrue("false positives on empty labels ${plus.falsePositiveRate}", plus.falsePositiveRate <= 0.02)
        assertEquals(0.0, plus.falsePositiveRate, 0.0001)
        assertTrue("recall must improve: ${det.recall} → ${plus.recall}", plus.recall > det.recall)
        assertEquals(1.0, plus.recall, 0.0001)

        // Keep the sample size honest: the task asks for roughly 100–120 labelled fixtures.
        assertTrue("labelled cases ${cases.size}", cases.size in 100..120)
    }

    // --- labelled cases -------------------------------------------------------------------------

    private data class EvalCase(
        val name: String,
        val fixture: Fixture,
        val channels: List<ChannelEntity>,
        val programmes: List<EpgProgrammeEntity>,
        val guideCarriers: List<String>,
        val expected: Set<Long>,
        val aiExpected: Set<Long>,
    )

    private fun labelledCases(): List<EvalCase> {
        val out = ArrayList<EvalCase>(120)

        // 25 football rows where the EPG names both teams at kickoff.
        for (i in 1..25) {
            val home = "Alpha$i FC"
            val away = "Beta$i FC"
            out += case(
                name = "football-epg-both-$i",
                fixture = teamFixture(i, home, away),
                channels = listOf(channel(1000L + i, "DE: SPORT $i", "k$i")),
                programmes = listOf(programme("k$i", "$home v $away")),
                expected = setOf(1000L + i),
            )
        }

        // 15 rows naming only one team: a news/ticker line, not the fixture.
        for (i in 1..15) {
            val home = "Gamma$i FC"
            val away = "Delta$i FC"
            out += case(
                name = "football-epg-one-team-$i",
                fixture = teamFixture(100 + i, home, away),
                channels = listOf(channel(2000L + i, "DE: SPORT NEWS $i", "n$i")),
                programmes = listOf(programme("n$i", "$home News")),
                expected = emptySet(),
            )
        }

        // 10 same-name EPG rows at another hour (a replay), not this kickoff.
        for (i in 1..10) {
            val home = "Epsilon$i FC"
            val away = "Zeta$i FC"
            out += case(
                name = "football-wrong-time-$i",
                fixture = teamFixture(200 + i, home, away),
                channels = listOf(channel(3000L + i, "IT: SPORT $i", "t$i")),
                programmes = listOf(
                    programme("t$i", "$home v $away", startMs = Noon + 3 * HourMs, stopMs = Noon + 5 * HourMs),
                ),
                expected = emptySet(),
            )
        }

        // 15 guide-confirmed football rows resolved against a real playlist row.
        for (i in 1..15) {
            val carrier = "Guide Sports $i"
            out += case(
                name = "football-guide-$i",
                fixture = teamFixture(300 + i, "Eta$i FC", "Theta$i FC"),
                channels = listOf(channel(4000L + i, "UK| GUIDE SPORTS $i HD")),
                programmes = emptyList(),
                guideCarriers = listOf(carrier),
                expected = setOf(4000L + i),
            )
        }

        // 10 gated guide listings: the row exists, carriers are hidden — no confirmation.
        for (i in 1..10) {
            out += case(
                name = "football-gated-$i",
                fixture = teamFixture(400 + i, "Iota$i FC", "Kappa$i FC"),
                channels = listOf(channel(5000L + i, "UK| SKY SPORTS MAIN EVENT $i")),
                programmes = emptyList(),
                expected = emptySet(),
            )
        }

        // 10 ATP/WTA tour rows only: one row per tour per day, not proof for one match.
        for (i in 1..10) {
            out += case(
                name = "tennis-tour-row-$i",
                fixture = duelFixture(500 + i, "Player A$i", "Player B$i"),
                channels = listOf(
                    channel(6000L + i, "UK: SKY SPORT TENNIS $i"),
                    channel(6500L + i, "UK: SKY SPORT PLUS $i"),
                ),
                programmes = emptyList(),
                expected = emptySet(),
            )
        }

        // 10 tennis matches with a per-match EPG row (both athletes).
        for (i in 1..10) {
            out += case(
                name = "tennis-epg-match-$i",
                fixture = duelFixture(600 + i, "Coral$i", "Dune$i"),
                channels = listOf(channel(7000L + i, "US: PPV $i", "m$i")),
                programmes = listOf(programme("m$i", "Tennis: Coral$i vs Dune$i")),
                expected = setOf(7000L + i),
            )
        }

        // 5 F1 races with their race programme; 5 practice sessions must not inherit it.
        for (i in 1..5) {
            out += case(
                name = "f1-race-$i",
                fixture = eventFixture(700 + i, "Grand Prix $i — Race"),
                channels = listOf(channel(8000L + i, "UK| SKY SPORTS F1 $i", "r$i")),
                programmes = listOf(programme("r$i", "Formula 1: Grand Prix $i Race")),
                expected = setOf(8000L + i),
            )
        }
        for (i in 1..5) {
            out += case(
                name = "f1-practice-$i",
                fixture = eventFixture(750 + i, "Grand Prix $i — Practice 1"),
                channels = listOf(channel(8500L + i, "UK| SKY SPORTS F1 $i", "p$i")),
                programmes = listOf(programme("p$i", "Formula 1: Grand Prix $i Race")),
                expected = emptySet(),
            )
        }

        // 10 opaque PPV feeds: no deterministic evidence; a validated AI pick confirms them.
        for (i in 1..10) {
            out += case(
                name = "opaque-ppv-$i",
                fixture = teamFixture(800 + i, "Lambda$i FC", "Mu$i FC"),
                channels = listOf(channel(9000L + i, "PPV $i")),
                programmes = emptyList(),
                expected = emptySet(),
                aiExpected = setOf(9000L + i),
            )
        }

        return out
    }

    private fun case(
        name: String,
        fixture: Fixture,
        channels: List<ChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
        guideCarriers: List<String> = emptyList(),
        expected: Set<Long>,
        aiExpected: Set<Long> = emptySet(),
    ) = EvalCase(name, fixture, channels, programmes, guideCarriers, expected, aiExpected)

    // --- one run --------------------------------------------------------------------------------

    private suspend fun run(cases: List<EvalCase>, ai: Boolean): List<List<ChannelMatch>> {
        // One matcher per case: the in-memory scan cache is scoped to a source set, and these cases
        // use different playlists.
        return cases.map { case ->
            val store = EvalStore(case.channels, case.programmes)
            val matcher = SportsMatcher(
                channelDao = store.channelDao,
                epgDao = store.epgDao,
                vocab = SportsVocab.EMPTY,
                sources = { listOf(SourceId) },
            )
            val aiRows = if (ai) {
                case.aiExpected.map { id ->
                    val row = case.channels.first { it.id == id }
                    ChannelMatch(
                        channelId = row.id,
                        channelName = row.name,
                        logoUrl = null,
                        epgTitle = null,
                        score = 8,
                        aiConfirmed = true,
                        sourceId = SourceId,
                    )
                }
            } else {
                emptyList()
            }
            matcher.match(
                case.fixture,
                SportMatchOptions(
                    languages = emptySet(),
                    confirmedBroadcasters = case.guideCarriers,
                    aiChannels = aiRows,
                ),
            )
        }
    }

    private data class Metrics(
        val precision: Double,
        val recall: Double,
        val falsePositiveRate: Double,
    )

    private fun measure(results: List<Pair<EvalCase, List<ChannelMatch>>>): Metrics {
        var displayed = 0
        var truePositive = 0
        var expected = 0
        var emptyCases = 0
        var falsePositiveEmpty = 0
        for ((case, matches) in results) {
            val target = case.expected + case.aiExpected
            expected += target.size
            emptyCases += if (target.isEmpty()) 1 else 0
            displayed += matches.size
            val hits = matches.count { it.channelId in target }
            truePositive += hits
            if (target.isEmpty() && matches.isNotEmpty()) falsePositiveEmpty++
        }
        return Metrics(
            precision = if (displayed == 0) 1.0 else truePositive.toDouble() / displayed,
            recall = if (expected == 0) 1.0 else truePositive.toDouble() / expected,
            falsePositiveRate = if (emptyCases == 0) 0.0 else falsePositiveEmpty.toDouble() / emptyCases,
        )
    }

    // --- fixtures -------------------------------------------------------------------------------

    private fun teamFixture(i: Int, home: String, away: String) = Fixture(
        id = "team-$i",
        leagueId = "soccer.eng.1",
        dateMs = Noon,
        state = FixtureState.UPCOMING,
        statusLine = "17:30",
        home = SportTeam("$i", home, "H$i", null, null, null, false, "home"),
        away = SportTeam("${i + 1}", away, "A$i", null, null, null, false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun duelFixture(i: Int, first: String, second: String) = Fixture(
        id = "tennis-$i",
        leagueId = "tennis.atp",
        dateMs = Noon,
        state = FixtureState.UPCOMING,
        statusLine = "14:00",
        format = FixtureFormat.VERSUS,
        entries = listOf(
            FixtureEntry(id = "$i", name = first, order = null, detail = null, logoUrl = null),
            FixtureEntry(id = "${i + 1}", name = second, order = null, detail = null, logoUrl = null),
        ),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun eventFixture(i: Int, title: String) = Fixture(
        id = "f1-$i",
        leagueId = "racing.f1",
        dateMs = Noon,
        state = FixtureState.UPCOMING,
        statusLine = "20:00",
        format = FixtureFormat.EVENT,
        title = title,
        broadcasts = emptyList(),
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
        startMs: Long = Noon,
        stopMs: Long = Noon + 2 * HourMs,
    ) = EpgProgrammeEntity(
        sourceId = SourceId,
        epgChannelId = key,
        startMs = startMs,
        stopMs = stopMs,
        title = title,
        description = null,
    )

    private companion object {
        const val SourceId = 1L
        const val Noon = 1_760_000_000_000L
        const val HourMs = 60L * 60L * 1000L
    }
}

/** Fake DAOs for the evaluation set. */
private class EvalStore(
    private val channels: List<ChannelEntity>,
    private val programmes: List<EpgProgrammeEntity>,
) {
    val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
        when (name) {
            "allForSources" -> channels
            "byEpgKeys" -> {
                val keys = (args!![1] as List<*>).filterIsInstance<String>()
                    .map { it.trim().lowercase() }
                channels.filter { it.epgChannelId?.trim()?.lowercase() in keys }
            }
            "getById" -> channels.firstOrNull { it.id == args!![0] as Long }
            else -> null
        }
    }

    val epgDao: EpgDao = proxy(EpgDao::class.java) { name, args ->
        val query = (args!![0] as String).lowercase()
        when (name) {
            "searchTitles" -> programmes.filter { it.title.lowercase().contains(query) }
            "searchGuide" -> programmes.filter {
                it.title.lowercase().contains(query) ||
                    it.description?.lowercase()?.contains(query) == true
            }
            "programmeSummariesForChannels" -> programmes
            else -> null
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        when (method.name) {
            "toString" -> "Eval${type.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> handler(method.name, args)
        }
    } as T
