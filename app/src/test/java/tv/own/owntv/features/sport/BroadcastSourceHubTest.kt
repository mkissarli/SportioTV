package tv.own.owntv.features.sport

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hub failure/retention rules: a broken or empty page never becomes negative evidence. */
class BroadcastSourceHubTest {

    private val now = 1_800_000_000_000L
    private val fixture = Fixture(
        id = "hub-1",
        leagueId = "soccer.eng.1",
        dateMs = now,
        state = FixtureState.LIVE,
        statusLine = "LIVE",
        home = SportTeam("1", "Liverpool", "LIV", null, null, "0", false, "home"),
        away = SportTeam("2", "Arsenal", "ARS", null, null, "0", false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    @Test
    fun aFailedRefreshKeepsTheRowsButMarksThemStale() = runBlocking {
        val source = FakeSource(
            SourceResult(SourceId, Family, now, listOf(listing())),
            SourceResult(SourceId, Family, 0, emptyList(), failure = "HTTP 503"),
        )
        val hub = BroadcastSourceHub(OkHttpClient(), { now }, listOf(source))
        assertTrue(hub.refresh())
        assertEquals(now, hub.listingsFor(fixture).single().retrievedAtMs)

        assertTrue(hub.refresh())
        assertEquals(0L, hub.listingsFor(fixture).single().retrievedAtMs)
        assertEquals(1, hub.listingsFor(fixture).size)
    }

    @Test
    fun anEmptyPageIsNotProofThatNothingIsTelevised() = runBlocking {
        val source = FakeSource(
            SourceResult(SourceId, Family, now, listOf(listing())),
            SourceResult(SourceId, Family, now + 60_000L, emptyList()),
        )
        val hub = BroadcastSourceHub(OkHttpClient(), { now }, listOf(source))
        hub.refresh()
        assertFalse(hub.refresh())
        assertEquals(now, hub.listingsFor(fixture).single().retrievedAtMs)
    }

    private fun listing() = BroadcastListing(
        eventId = "hub-1",
        sport = SportClass.FOOTBALL,
        competition = "Premier League",
        participants = listOf("Liverpool", "Arsenal"),
        eventStartTimeMs = now,
        coverageStartTimeMs = now,
        coverageEndTimeMs = now + HourMs,
        broadcaster = "Sky Sports Main Event",
        channel = "Sky Sports Main Event",
        territory = "UK",
        language = "en",
        coverageType = CoverageType.FIXTURE,
        sourceId = SourceId,
        retrievedAtMs = now,
        confidence = 90,
        direct = true,
        sourceFamily = Family,
    )

    private class FakeSource(vararg results: SourceResult) : BroadcastSource {
        private val queue = ArrayDeque(results.toList())
        override val sourceId = SourceId
        override val sourceFamily = Family
        override val ttlMs = 0L
        override val timeoutMs = 1_000L
        override val url = "https://example.invalid/"

        override suspend fun fetch(
            window: BroadcastWindow,
            client: OkHttpClient,
            retrievedAtMs: Long,
        ): SourceResult = queue.removeFirstOrNull()
            ?: SourceResult(sourceId, sourceFamily, retrievedAtMs, emptyList(), failure = "no result")
    }

    private companion object {
        const val SourceId = "test-source"
        const val Family = "test-family"
        const val HourMs = 60L * 60L * 1000L
    }
}
