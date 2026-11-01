package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tier and score rules of the deterministic evidence model. */
class BroadcastEvidenceTest {

    @Test
    fun freshDirectFixtureEvidenceIsSupported() {
        val now = 1_000_000L
        val evidence = evidence(EvidenceKind.EXTERNAL_FIXTURE, now, direct = true, event = "fixture-1")
        assertEquals(ChannelEvidenceTier.SUPPORTED, EvidenceFusion.tier(listOf(evidence), now, specialistChannel = false))
    }

    @Test
    fun staleDirectEvidenceDowngradesToLikely() {
        val now = 10_000_000L
        val evidence = evidence(
            EvidenceKind.EXTERNAL_FIXTURE,
            now - EvidenceFusion.StaleAfterMs - 1,
            direct = true,
            event = "fixture-1",
        )
        assertEquals(ChannelEvidenceTier.LIKELY, EvidenceFusion.tier(listOf(evidence), now, specialistChannel = false))
    }

    @Test
    fun tournamentLevelIsLikelyForASpecialistAndPossibleOtherwise() {
        val now = 1_000_000L
        val evidence = evidence(EvidenceKind.TOURNAMENT_SCHEDULE, now, direct = false, event = "fixture-1")
        assertEquals(ChannelEvidenceTier.LIKELY, EvidenceFusion.tier(listOf(evidence), now, specialistChannel = true))
        assertEquals(ChannelEvidenceTier.POSSIBLE, EvidenceFusion.tier(listOf(evidence), now, specialistChannel = false))
    }

    @Test
    fun specialistIdentityAloneIsNeverSupported() {
        val now = 1_000_000L
        val evidence = evidence(EvidenceKind.SPECIALIST_IDENTITY, now, direct = false, event = null)
        assertEquals(ChannelEvidenceTier.POSSIBLE, EvidenceFusion.tier(listOf(evidence), now, specialistChannel = true))
    }

    @Test
    fun broadcasterRightsAloneIsNeverSupported() {
        val now = 1_000_000L
        val evidence = evidence(EvidenceKind.BROADCASTER_RIGHTS, now, direct = false, event = null)
        assertEquals(ChannelEvidenceTier.POSSIBLE, EvidenceFusion.tier(listOf(evidence), now, specialistChannel = false))
    }

    @Test
    fun twoDirectSourcesNamingDifferentEventsConflict() {
        val now = 1_000_000L
        val a = evidence(EvidenceKind.EXTERNAL_FIXTURE, now, direct = true, event = "fixture-1", underlying = "a")
        val b = evidence(EvidenceKind.OFFICIAL_SCHEDULE, now, direct = true, event = "fixture-2", underlying = "b")
        assertEquals(ChannelEvidenceTier.CONFLICTING, EvidenceFusion.tier(listOf(a, b), now, specialistChannel = false))
    }

    @Test
    fun theSameListingFromTwoFeedsIsCountedOnce() {
        val now = 1_000_000L
        val a = evidence(EvidenceKind.EXTERNAL_FIXTURE, now, direct = true, event = "fixture-1", underlying = "same")
        val b = evidence(EvidenceKind.OFFICIAL_SCHEDULE, now, direct = true, event = "fixture-1", underlying = "same")
        val once = EvidenceFusion.score(listOf(a), now)
        val twice = EvidenceFusion.score(listOf(a, b), now)
        assertEquals(once, twice)
        assertEquals(1, EvidenceFusion.dedupe(listOf(a, b), now).size)
    }

    @Test
    fun scoreIsBoundedAndFallsWithStaleness() {
        val now = 10_000_000L
        val fresh = evidence(EvidenceKind.EXTERNAL_FIXTURE, now, direct = true, event = "fixture-1")
        val stale = evidence(
            EvidenceKind.EXTERNAL_FIXTURE,
            now - EvidenceFusion.StaleAfterMs - 1,
            direct = true,
            event = "fixture-1",
        )
        assertTrue(EvidenceFusion.score(listOf(fresh), now) > EvidenceFusion.score(listOf(stale), now))
        val capped = List(50) { fresh.copy(underlyingKey = "u$it") }
        assertEquals(300, EvidenceFusion.score(capped, now))
    }

    private fun evidence(
        kind: EvidenceKind,
        retrievedAtMs: Long,
        direct: Boolean,
        event: String?,
        underlying: String = "u1",
    ): BroadcastEvidence = BroadcastEvidence(
        kind = kind,
        sourceId = "test",
        eventId = event,
        channelIdentity = "sky|main event",
        channelName = "Sky Sports Main Event",
        territory = "UK",
        language = "en",
        startMs = 1_000_000L,
        endMs = 2_000_000L,
        retrievedAtMs = retrievedAtMs,
        direct = direct,
        reference = "test row",
        underlyingKey = underlying,
    )
}
