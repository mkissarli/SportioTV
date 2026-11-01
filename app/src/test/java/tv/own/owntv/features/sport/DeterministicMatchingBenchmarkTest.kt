package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelSearchResult
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity

/**
 * JVM performance smoke test for the deterministic pipeline. It is deliberately not a device
 * benchmark: the printed numbers are the CI baseline for regressions, while the Fire TV targets in
 * `docs/SPORT-DETERMINISTIC-MATCHING.md` still need real hardware.
 *
 * The assertions are deliberately loose (an order of magnitude above the expected run) so a busy
 * CI machine does not fail the suite; a regression that makes matching quadratic still trips them.
 */
class DeterministicMatchingBenchmarkTest {

    @Test
    fun channelIdentityResolutionScalesToSixtyThousandNames() {
        for (size in listOf(10_000, 30_000, 60_000)) {
            val names = (0 until size).map { index ->
                when (index % 4) {
                    0 -> "UK| SKY SPORTS MAIN EVENT $index HD"
                    1 -> "DE: SKY SPORT BUNDESLIGA $index HD"
                    2 -> "US: ESPN$index 4K"
                    else -> "FR: CANAL+ SPORT $index HD"
                }
            }
            val start = System.nanoTime()
            val identities = names.mapNotNull(ChannelIdentityResolver::resolve)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("identity index: $size names -> ${identities.size} identities in ${elapsedMs}ms")
            assertTrue("identity resolution too slow for $size names: ${elapsedMs}ms", elapsedMs < 30_000)

            val lookupStart = System.nanoTime()
            repeat(1_000) { index -> ChannelIdentityResolver.resolve(names[index % names.size]) }
            val lookupMs = (System.nanoTime() - lookupStart) / 1_000_000
            println("identity lookup: 1000 names in ${lookupMs}ms")
            assertTrue("identity lookup too slow: ${lookupMs}ms", lookupMs < 5_000)
        }
    }

    @Test
    fun aThirtyThousandChannelPlaylistStillMatchesThroughTheGuide() = runBlocking {
        val channels = (0 until 30_000).map { index ->
            ChannelEntity(
                id = index.toLong() + 1,
                sourceId = 1L,
                name = "UK| CHANNEL $index HD",
                streamUrl = "http://provider.invalid/$index.ts",
            )
        } + ChannelEntity(
            id = 100_000L,
            sourceId = 1L,
            name = "UK| SKY SPORTS MAIN EVENT HD",
            streamUrl = "http://provider.invalid/sky.ts",
        )
        val store = BenchmarkStore(channels)
        val matcher = SportsMatcher(
            channelDao = store.channelDao,
            epgDao = store.epgDao,
            vocab = SportsVocab.EMPTY,
            sources = { listOf(1L) },
        )
        val fixture = Fixture(
            id = "bench",
            leagueId = "soccer.eng.1",
            dateMs = System.currentTimeMillis(),
            state = FixtureState.LIVE,
            statusLine = "LIVE",
            home = SportTeam("1", "Coventry City", "COV", null, null, "0", false, "home"),
            away = SportTeam("2", "Newcastle United", "NEW", null, null, "0", false, "away"),
            broadcasts = emptyList(),
            venue = null,
        )
        val listing = BroadcastListing(
            eventId = "bench",
            sport = SportClass.FOOTBALL,
            competition = "Premier League",
            participants = listOf("Coventry City", "Newcastle United"),
            eventStartTimeMs = fixture.dateMs,
            coverageStartTimeMs = fixture.dateMs,
            coverageEndTimeMs = fixture.dateMs + 2 * 60 * 60 * 1000,
            broadcaster = "Sky Sports Main Event",
            channel = "Sky Sports Main Event",
            territory = "UK",
            language = "en",
            coverageType = CoverageType.FIXTURE,
            sourceId = "wheresthematch",
            retrievedAtMs = System.currentTimeMillis(),
            confidence = 90,
            direct = true,
        )
        val start = System.nanoTime()
        val matches = matcher.match(
            fixture,
            SportMatchOptions(languages = emptySet(), guideListings = listOf(listing)),
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("fixture matching: 30000 channels in ${elapsedMs}ms, ${matches.size} rows")
        assertTrue("matching too slow: ${elapsedMs}ms", elapsedMs < 10_000)
        assertTrue(matches.any { it.channelName.contains("MAIN EVENT") })
    }

    @Test
    fun guideLookupFindsTheRightChannelBehindThreeHundredBroadMatches() = runBlocking {
        // The old 200-row broad FTS cap would stop before the correct row at position 301.
        val decoys = (0 until 300).map { index ->
            ChannelEntity(
                id = index.toLong() + 1,
                sourceId = 1L,
                name = "UK| TNT SPORTS MAIN EVENT $index HD",
                streamUrl = "http://provider.invalid/decoy$index.ts",
            )
        }
        val correct = ChannelEntity(
            id = 900_000L,
            sourceId = 1L,
            name = "UK| SKY SPORTS MAIN EVENT HD",
            streamUrl = "http://provider.invalid/correct.ts",
        )
        val filler = (0 until 59_700).map { index ->
            ChannelEntity(
                id = 1_000_000L + index,
                sourceId = 1L,
                name = "UK| FILLER CHANNEL $index HD",
                streamUrl = "http://provider.invalid/filler$index.ts",
            )
        }
        val matcher = SportsMatcher(
            channelDao = BenchmarkStore(decoys + correct + filler).channelDao,
            epgDao = BenchmarkStore(emptyList()).epgDao,
            vocab = SportsVocab.EMPTY,
            sources = { listOf(1L) },
        )
        val fixture = Fixture(
            id = "recall",
            leagueId = "soccer.eng.1",
            dateMs = System.currentTimeMillis(),
            state = FixtureState.LIVE,
            statusLine = "LIVE",
            home = SportTeam("1", "Coventry City", "COV", null, null, "0", false, "home"),
            away = SportTeam("2", "Newcastle United", "NEW", null, null, "0", false, "away"),
            broadcasts = emptyList(),
            venue = null,
        )
        val listing = BroadcastListing(
            eventId = "recall",
            sport = SportClass.FOOTBALL,
            competition = "Premier League",
            participants = listOf("Coventry City", "Newcastle United"),
            eventStartTimeMs = fixture.dateMs,
            coverageStartTimeMs = fixture.dateMs,
            coverageEndTimeMs = fixture.dateMs + 2 * 60 * 60 * 1000,
            broadcaster = "Sky Sports Main Event",
            channel = "Sky Sports Main Event",
            territory = "UK",
            language = "en",
            coverageType = CoverageType.FIXTURE,
            sourceId = "wheresthematch",
            retrievedAtMs = System.currentTimeMillis(),
            confidence = 90,
            direct = true,
        )
        val start = System.nanoTime()
        val matches = matcher.match(fixture, SportMatchOptions(languages = emptySet(), guideListings = listOf(listing)))
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("60k guide recall: ${elapsedMs}ms, ${matches.size} rows")
        assertTrue(matches.any { it.channelName == "UK| SKY SPORTS MAIN EVENT HD" })
    }

    private class BenchmarkStore(channels: List<ChannelEntity>) {
        val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
            when (name) {
                "allForSources" -> channels
                "searchListDetailedFts" -> {
                    val query = args!![0] as String
                    val limit = args[2] as Int
                    val terms = Regex("\"([^\"]+)\"").findAll(query)
                        .map { it.groupValues[1].lowercase() }
                        .toList()
                    channels.filter { channel -> terms.any { channel.name.lowercase().contains(it) } }
                        .take(limit)
                        .map { ChannelSearchResult(it, "") }
                }
                else -> null
            }
        }
        val epgDao: EpgDao = proxy(EpgDao::class.java) { _, _ -> emptyList<Any>() }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        when (method.name) {
            "toString" -> "Benchmark${type.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> handler(method.name, args)
        }
    } as T
