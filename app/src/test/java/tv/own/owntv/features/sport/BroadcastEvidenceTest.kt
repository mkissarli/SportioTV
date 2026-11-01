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
    fun theFreshnessBoundaryIsStrict() {
        val now = 10_000_000L
        fun tierAt(ageMs: Long): ChannelEvidenceTier {
            val item = evidence(EvidenceKind.EXTERNAL_FIXTURE, now - ageMs, direct = true, event = "fixture-1")
            return EvidenceFusion.tier(listOf(item), now, specialistChannel = false)
        }
        assertEquals(ChannelEvidenceTier.SUPPORTED, tierAt(EvidenceFusion.FreshnessWindowMs))
        assertEquals(ChannelEvidenceTier.LIKELY, tierAt(EvidenceFusion.FreshnessWindowMs + 1))
        assertEquals(ChannelEvidenceTier.LIKELY, tierAt(EvidenceFusion.StaleAfterMs))
        assertEquals(ChannelEvidenceTier.LIKELY, tierAt(EvidenceFusion.StaleAfterMs + 1))
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
    fun aConflictSurvivesAStrongerDirectItemInTheSameFusion() {
        val now = 1_000_000L
        val a = evidence(EvidenceKind.EXTERNAL_FIXTURE, now, direct = true, event = "fixture-1", underlying = "a")
        val b = evidence(EvidenceKind.OFFICIAL_SCHEDULE, now, direct = true, event = "fixture-2", underlying = "b")
        val stronger = evidence(EvidenceKind.USER_CONFIRMED, now, direct = true, event = "fixture-1", underlying = "c")
        assertEquals(
            ChannelEvidenceTier.CONFLICTING,
            EvidenceFusion.tier(listOf(a, b, stronger), now, specialistChannel = false),
        )
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

    @Test
    fun aSessionListingIsLikelyEvenOnAGeneralChannel() {
        val now = 1_000_000L
        val session = evidence(EvidenceKind.SESSION_SCHEDULE, now, direct = false, event = "tennis-1")
        assertEquals(ChannelEvidenceTier.LIKELY, EvidenceFusion.tier(listOf(session), now, specialistChannel = false))
        val tournament = evidence(EvidenceKind.TOURNAMENT_SCHEDULE, now, direct = false, event = "tennis-1")
        assertEquals(ChannelEvidenceTier.POSSIBLE, EvidenceFusion.tier(listOf(tournament), now, specialistChannel = false))
    }

    @Test
    fun aServiceRowNeverReachesSupported() {
        val now = 1_000_000L
        val service = evidence(EvidenceKind.BROADCASTER_RIGHTS, now, direct = false, event = "fixture-1")
        assertEquals(ChannelEvidenceTier.POSSIBLE, EvidenceFusion.tier(listOf(service), now, specialistChannel = true))
    }

    @Test
    fun twoSitesOfTheSameFamilyAreOneConfirmation() {
        val now = 1_000_000L
        val first = evidence(
            EvidenceKind.EXTERNAL_FIXTURE, now, direct = true, event = "fixture-1",
            underlying = "family|fixture-1|ligue 1 plus|2|FIXTURE", family = "foottv",
        )
        val syndicated = first.copy(sourceId = "other-site", sourceFamily = "foottv")
        assertEquals(1, EvidenceFusion.dedupe(listOf(first, syndicated), now).size)
        assertEquals(EvidenceFusion.score(listOf(first), now), EvidenceFusion.score(listOf(first, syndicated), now))
    }

    private fun evidence(
        kind: EvidenceKind,
        retrievedAtMs: Long,
        direct: Boolean,
        event: String?,
        underlying: String = "u1",
        family: String = "test",
    ): BroadcastEvidence = BroadcastEvidence(
        kind = kind,
        sourceId = "test",
        sourceFamily = family,
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
