package tv.own.owntv.features.sport

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** The accepted outcome of one full-playlist analysis. */
data class AiPlaylistResult(
    /** Verified playlist rows, best first; empty for a valid `none` or a fully rejected answer. */
    val picks: List<ChannelMatch>,
    val decision: AiPlaylistDecision,
    val reason: String?,
    val requestCount: Int,
    val validResponses: Int,
    val invalidResponses: Int,
    val candidateCount: Int,
    val inputChars: Int,
    val latencyMs: Long,
)

enum class AiPlaylistDecision { SELECTED, NONE }

/**
 * Full-playlist event matching.
 *
 * Every playable row of the profile's live sources becomes one temporary candidate id. The model
 * sees the complete catalogue (one request when it fits, otherwise every row split across bounded
 * batches — never a keyword shortlist), answers with candidate ids only, and those ids are mapped
 * back to the quoted playlist rows and re-verified before display.
 *
 * The pipeline is deliberately opt-in: it runs only when the user confirms the per-fixture consent
 * action. Successful answers (including a valid `none`) are cached by every material input; HTTP
 * failures are never cached.
 */
class AiPlaylistAnalyst(
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
    private val sources: LiveSourceIdsProvider,
    private val priors: SportsPriors,
    private val leagueLabel: (String) -> String,
    private val ask: suspend (system: String, user: String, apiKey: String) -> String,
    private val maxFullRequestChars: Int = AiPlaylistPolicy.MaxFullRequestChars,
    private val maxBatchRequestChars: Int = AiPlaylistPolicy.MaxBatchRequestChars,
) {

    private data class Cached(val result: AiPlaylistResult)

    /** Small in-memory cache; the key already carries every materially relevant input. */
    private val cache = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean =
            size > MaxCachedResults
    }

    suspend fun analyze(
        fixture: Fixture,
        apiKey: String,
        market: String? = null,
        guideVersion: Int = 0,
        guideEvidence: List<GuideEntry> = emptyList(),
    ): AiPlaylistResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiRequestException(AiFailure.NO_KEY)
        val startedAt = System.currentTimeMillis()
        val sourceIds = sources.liveSourceIds()
        if (sourceIds.isEmpty()) return@withContext emptyResult()

        val rows = runCatching { channelDao.allForSources(sourceIds, MaxCatalogueRows) }
            .getOrDefault(emptyList())
        if (rows.isEmpty()) return@withContext emptyResult()

        val priorCarriers = priors.carriersFor(fixture, market)
        val programmes = loadProgrammes(rows, fixture)
        val candidates = AiPlaylistCatalogue.build(fixture, rows, programmes, priorCarriers)
        if (candidates.isEmpty()) return@withContext emptyResult()
        val byId = candidates.associateBy { it.requestId }
        val lines = candidates.map { it.line() }

        val leagueName = runCatching { leagueLabel(fixture.leagueId) }.getOrDefault(fixture.leagueId)
        val catalogueHash = hash(lines.joinToString("\n"))
        val guideHash = hash(guideEvidence.joinToString("\n") { it.toString() })
        val cacheKey = listOf(
            fixture.id,
            fixture.dateMs.toString(),
            fixture.leagueId,
            fixture.title.orEmpty(),
            fixture.home?.name.orEmpty(),
            fixture.away?.name.orEmpty(),
            playlistFingerprint(rows),
            catalogueHash,
            guideHash,
            guideVersion.toString(),
            priors.datasetVersion,
            market.orEmpty(),
            AiPlaylistPolicy.Model,
            AiPlaylistPolicy.PromptVersion,
        ).joinToString(CacheKeySeparator)

        val cached = synchronized(cache) { cache[cacheKey] }
        cached?.let {
            return@withContext it.result.copy(requestCount = 0, latencyMs = 0)
        }

        val chunks = planBatches(lines)
        if (chunks.isEmpty()) return@withContext emptyResult()

        val accepted = ArrayList<AiPlaylistPick>()
        var validResponses = 0
        var invalidResponses = 0
        var noneReason: String? = null
        var inputChars = 0
        for ((index, chunk) in chunks.withIndex()) {
            val allowed = chunk.mapTo(HashSet<String>()) { it.substringBefore(CandidateIdEnd) }
            val prompt = AiPlaylistPrompt.user(
                fixture = fixture,
                leagueLabel = leagueName,
                market = market,
                priorCarriers = priorCarriers,
                guideEvidence = guideEvidence,
                catalogueLines = chunk,
                batchIndex = index + 1,
                batchCount = chunks.size,
            )
            inputChars += prompt.length
            // Transient failures propagate: the caller shows a typed error and never caches it.
            val content = ask(AiPlaylistPrompt.System, prompt, apiKey)
            when (val answer = AiPlaylistResponseParser.parse(content, allowed)) {
                is AiPlaylistAnswer.Selected -> {
                    validResponses++
                    accepted += answer.picks
                }
                is AiPlaylistAnswer.None -> {
                    validResponses++
                    noneReason = answer.reason
                }
                null -> invalidResponses++
            }
        }

        // Consolidate the batch selections: one row per playlist id, strongest confidence first.
        val consolidated = accepted
            .distinctBy { it.requestId }
            .sortedWith(compareByDescending<AiPlaylistPick> { it.confidence }.thenBy { it.requestId })
            .take(AiPlaylistPolicy.MaxSelections)

        val verified = consolidated.mapNotNull { pick ->
            val candidate = byId[pick.requestId] ?: return@mapNotNull null
            // The mapping must still exist and still be the same playable row.
            val row = runCatching { channelDao.getById(candidate.row.id) }.getOrNull()
                ?: return@mapNotNull null
            if (row.name != candidate.row.name) return@mapNotNull null
            ChannelMatch(
                channelId = row.id,
                channelName = row.name,
                logoUrl = row.logoUrl,
                epgTitle = null,
                score = AiEvidenceScore + (pick.confidence * AiConfidenceSpan).toInt(),
                aiConfirmed = true,
                aiReason = pick.reason.takeIf { it.isNotBlank() },
                sourceId = row.sourceId,
            )
        }
        val result = AiPlaylistResult(
            picks = verified,
            decision = if (verified.isEmpty()) AiPlaylistDecision.NONE else AiPlaylistDecision.SELECTED,
            reason = noneReason,
            requestCount = chunks.size,
            validResponses = validResponses,
            invalidResponses = invalidResponses,
            candidateCount = candidates.size,
            inputChars = inputChars,
            latencyMs = System.currentTimeMillis() - startedAt,
        )
        // A valid `none` and a valid selection are worth keeping; an all-invalid answer is not a
        // permanent truth about the fixture, so it is deliberately not cached.
        if (validResponses > 0) synchronized(cache) { cache[cacheKey] = Cached(result) }
        result
    }

    /**
     * The request plan: one complete-context request when the measured catalogue fits, otherwise
     * bounded batches that together contain every line exactly once.
     */
    private fun planBatches(lines: List<String>): List<List<String>> {
        val measured = lines.sumOf { it.length + 1 } + AiPlaylistBatching.PromptOverheadChars
        if (measured <= maxFullRequestChars) return listOf(lines)
        return AiPlaylistBatching.batches(lines, maxBatchRequestChars)
    }

    private suspend fun loadProgrammes(
        rows: List<ChannelEntity>,
        fixture: Fixture,
    ): List<EpgProgrammeEntity> {
        val keys = rows.mapNotNull { it.epgChannelId?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        if (keys.isEmpty()) return emptyList()
        val from = fixture.dateMs - EpgBeforeMs
        val to = fixture.dateMs + EpgAfterMs
        val out = ArrayList<EpgProgrammeEntity>()
        for (batch in keys.chunked(EpgKeyBatch)) {
            runCatching { epgDao.programmeSummariesForChannels(batch, from, to) }
                .getOrNull()
                ?.let(out::addAll)
        }
        return out
    }

    private fun playlistFingerprint(rows: List<ChannelEntity>): String = hash(
        rows.joinToString("\n") { row ->
            row.id.toString() + FingerprintSeparator + row.name + FingerprintSeparator + row.epgChannelId.orEmpty()
        },
    )

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun emptyResult() = AiPlaylistResult(
        picks = emptyList(),
        decision = AiPlaylistDecision.NONE,
        reason = null,
        requestCount = 0,
        validResponses = 0,
        invalidResponses = 0,
        candidateCount = 0,
        inputChars = 0,
        latencyMs = 0,
    )

    private companion object {
        const val MaxCatalogueRows = 100_000
        const val EpgKeyBatch = 400
        const val EpgBeforeMs = 3L * 60L * 60L * 1000L
        const val EpgAfterMs = 4L * 60L * 60L * 1000L
        const val MaxCachedResults = 24
        const val AiEvidenceScore = 1
        const val AiConfidenceSpan = 8
        const val CandidateIdEnd = ' '
        const val CacheKeySeparator = "|"
        const val FingerprintSeparator = "\u0001"
    }
}
