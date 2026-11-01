package tv.own.owntv.features.sport

/** How specific a broadcast listing is about the fixture. */
enum class CoverageType { FIXTURE, SESSION, TOURNAMENT, EVENT_CARD, UNKNOWN }

/**
 * One normalised external broadcast listing. Sources fill what they have; a missing field is
 * missing evidence, never a match. `direct` means the source names **this** fixture/session and
 * channel; [CoverageType.TOURNAMENT] is deliberately not direct.
 */
data class BroadcastListing(
    val eventId: String?,
    val sport: SportClass,
    val competition: String?,
    val participants: List<String>,
    val eventStartTimeMs: Long,
    val coverageStartTimeMs: Long,
    val coverageEndTimeMs: Long,
    val broadcaster: String?,
    val channel: String,
    val territory: String?,
    val language: String?,
    val coverageType: CoverageType,
    val sourceId: String,
    val retrievedAtMs: Long,
    val confidence: Int,
    val direct: Boolean,
    val sourceRef: String? = null,
)

/** The relative strength classes of the evidence model. */
enum class EvidenceKind(val baseStrength: Int, val direct: Boolean) {
    EPG_FIXTURE(100, true),
    EXTERNAL_FIXTURE(90, true),
    OFFICIAL_SCHEDULE(90, true),
    USER_CONFIRMED(80, true),
    TOURNAMENT_SCHEDULE(45, false),
    SPECIALIST_IDENTITY(18, false),
    BROADCASTER_RIGHTS(10, false),
    PLAYLIST_CONTEXT(6, false),
}

/**
 * One independently traceable piece of evidence. `underlyingKey` collapses the same broadcast
 * information that reached us through two feeds or two derived paths, so it is not double-counted.
 */
data class BroadcastEvidence(
    val kind: EvidenceKind,
    val sourceId: String,
    val eventId: String?,
    val channelIdentity: String,
    val channelName: String,
    val territory: String?,
    val language: String?,
    val startMs: Long,
    val endMs: Long,
    val retrievedAtMs: Long,
    val direct: Boolean,
    val reference: String?,
    val underlyingKey: String,
)

/**
 * Deterministic confidence model. It has two separate outputs: [score] orders channels and [tier]
 * states how well the event is evidenced. User preferences are applied later by the matcher and
 * never enter this object.
 *
 * Rules, in order:
 *  - disagreeing direct evidence for one channel → [ChannelEvidenceTier.CONFLICTING];
 *  - fresh, direct, fixture-specific evidence → SUPPORTED;
 *  - stale direct evidence, or tournament-level evidence on a specialist channel → LIKELY;
 *  - tournament-level evidence on a general channel → POSSIBLE;
 *  - specialist identity, broadcaster rights or playlist context alone → POSSIBLE.
 *
 * The last rule is the guard against "Sky Sports shows football, therefore this game is on Sky
 * Sports": a relationship or a specialist classification never reaches SUPPORTED.
 */
object EvidenceFusion {

    /** How long a retrieved listing counts as fresh. */
    const val FreshnessWindowMs = 30L * 60L * 1000L

    /** After this age only the fact that a listing existed remains, at reduced strength. */
    const val StaleAfterMs = 2L * 60L * 60L * 1000L

    fun freshness(nowMs: Long, retrievedAtMs: Long): Int {
        if (retrievedAtMs <= 0) return StaleFreshness
        val age = (nowMs - retrievedAtMs).coerceAtLeast(0)
        return when {
            age <= FreshnessWindowMs -> FreshFreshness
            age <= StaleAfterMs -> AgedFreshness
            else -> StaleFreshness
        }
    }

    /** Evidence after collapsing duplicate sources. Order is stable (first appearance wins). */
    fun dedupe(evidence: List<BroadcastEvidence>, nowMs: Long = 0L): List<BroadcastEvidence> {
        val byUnderlying = LinkedHashMap<String, BroadcastEvidence>()
        for (item in evidence) {
            val key = item.underlyingKey.ifBlank {
                item.sourceId + DuplicateSeparator + item.kind.name + DuplicateSeparator + item.channelIdentity
            }
            val previous = byUnderlying[key]
            byUnderlying[key] = if (previous == null || strengthOf(item, nowMs) > strengthOf(previous, nowMs)) {
                item
            } else {
                previous
            }
        }
        return byUnderlying.values.toList()
    }

    /** Bounded, source-independent score. One source can never dominate by repeating itself. */
    fun score(evidence: List<BroadcastEvidence>, nowMs: Long): Int {
        val total = dedupe(evidence, nowMs).sumOf { item ->
            item.kind.baseStrength * freshness(nowMs, item.retrievedAtMs) / FreshFreshness
        }
        return total.coerceIn(0, MaxScore)
    }

    fun tier(evidence: List<BroadcastEvidence>, nowMs: Long, specialistChannel: Boolean): ChannelEvidenceTier {
        if (evidence.isEmpty()) return ChannelEvidenceTier.POSSIBLE
        val items = dedupe(evidence, nowMs)
        if (hasConflict(items)) return ChannelEvidenceTier.CONFLICTING
        // Only the fresh band is Supported. The aged band (30 min … 2 h) is Likely even when the
        // listing is direct; otherwise a 31-minute-old row would silently regain the top tier.
        val freshDirect = items.any {
            it.direct && it.kind in DirectKinds && freshness(nowMs, it.retrievedAtMs) >= FreshFreshness
        }
        if (freshDirect) return ChannelEvidenceTier.SUPPORTED
        if (items.any { it.kind in DirectKinds }) return ChannelEvidenceTier.LIKELY
        val tournament = items.any { it.kind == EvidenceKind.TOURNAMENT_SCHEDULE }
        if (tournament) {
            return if (specialistChannel) ChannelEvidenceTier.LIKELY else ChannelEvidenceTier.POSSIBLE
        }
        return ChannelEvidenceTier.POSSIBLE
    }

    /**
     * Two direct sources naming different events for the same channel in overlapping time is a
     * real contradiction. Same event from two sources is agreement, not a conflict.
     */
    fun hasConflict(evidence: List<BroadcastEvidence>): Boolean {
        val direct = evidence.filter { it.direct && it.kind in DirectKinds && it.eventId != null }
        for (i in direct.indices) {
            for (j in i + 1 until direct.size) {
                val a = direct[i]
                val b = direct[j]
                if (a.channelIdentity != b.channelIdentity) continue
                if (a.eventId == b.eventId) continue
                if (a.startMs < b.endMs && b.startMs < a.endMs) return true
            }
        }
        return false
    }

    /** Human-readable audit line for the UI/diagnostics. */
    fun explain(evidence: List<BroadcastEvidence>, nowMs: Long): String {
        val items = dedupe(evidence, nowMs)
        if (items.isEmpty()) return ""
        val kinds = items.groupBy { it.kind }.mapValues { it.value.size }
        return kinds.entries.joinToString(", ") { (kind, count) ->
            if (count > 1) kind.name + " x" + count else kind.name
        } + reasonSuffix(items, nowMs)
    }

    private fun reasonSuffix(items: List<BroadcastEvidence>, nowMs: Long): String {
        val stale = items.all { freshness(nowMs, it.retrievedAtMs) < AgedFreshness }
        return if (stale) " (stale)" else ""
    }

    private fun strengthOf(item: BroadcastEvidence, nowMs: Long): Int =
        item.kind.baseStrength * freshness(nowMs, item.retrievedAtMs)

    private val DirectKinds = setOf(
        EvidenceKind.EPG_FIXTURE,
        EvidenceKind.EXTERNAL_FIXTURE,
        EvidenceKind.OFFICIAL_SCHEDULE,
        EvidenceKind.USER_CONFIRMED,
    )

    private const val FreshFreshness = 100
    private const val AgedFreshness = 55
    private const val StaleFreshness = 20
    private const val MaxScore = 300
    private const val DuplicateSeparator = "|"
}
