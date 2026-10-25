package tv.own.owntv.features.sport

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** Guide evidence handed to one board fixture. Read-only; never a network call in the matcher. */
data class BoardGuideEvidence(
    val carriers: List<String> = emptyList(),
    val rows: List<GuideEntry> = emptyList(),
)

/** The outcome of one board run. `hits` only contains fixtures with at least one verified row. */
data class BoardMatchResult(
    val hits: Map<String, List<ChannelMatch>>,
    val fixtureCount: Int = 0,
    val discoverySuggestions: Int = 0,
    val candidateCount: Int = 0,
    val adjudicationRequests: Int = 0,
    val validResponses: Int = 0,
    val invalidResponses: Int = 0,
    val inputChars: Int = 0,
    val latencyMs: Long = 0,
    val searchHits: Int = 0,
    val searchCandidates: Int = 0,
    val failure: AiFailure? = null,
)

/**
 * Two-phase, board-level AI channel matching.
 *
 * Phase 1 is one compact discovery request for the whole day. Local candidate discovery runs at the
 * same time over the **entire** live playlist (normalised once) and the EPG. Phase 2 sends a small
 * number of bounded requests, each carrying several fixtures and the candidate channels those
 * fixtures produced.
 *
 * Only ids that exist in this request and still resolve to the same playlist row are accepted. A
 * valid empty answer is normal; a model answer never creates a playable row.
 */
class AiBoardMatcher(
    private val channelDao: ChannelDao,
    private val epgDao: EpgDao,
    private val categoryDao: CategoryDao,
    private val sources: LiveSourceIdsProvider,
    private val vocab: SportsVocab,
    private val priors: SportsPriors,
    private val leagueLabel: (String) -> String,
    private val ask: suspend (system: String, user: String, apiKey: String) -> String,
    /** DeepSeek Search; null in unit tests and when the caller has no search client. */
    private val search: (suspend (query: String, apiKey: String) -> List<DeepSeekSearchHit>)? = null,
    private val maxRequests: Int = AiBoardPolicy.MaxAdjudicationRequests,
    private val maxConcurrent: Int = AiBoardPolicy.MaxConcurrentRequests,
    private val maxRequestChars: Int = AiBoardPolicy.MaxRequestChars,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class CachedDiscovery(val atMs: Long, val byFixture: Map<String, List<BroadcastSuggestion>>)

    private data class CachedBoard(val atMs: Long, val result: BoardMatchResult)

    private data class CachedSearch(val atMs: Long, val hits: List<DeepSeekSearchHit>)

    private data class CachedHints(val atMs: Long, val hints: List<SearchChannelHint>)

    private val searchCache = object : LinkedHashMap<String, CachedSearch>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSearch>?): Boolean =
            size > MaxSearchCache
    }

    private val hintsCache = object : LinkedHashMap<String, CachedHints>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedHints>?): Boolean =
            size > MaxHintsCache
    }

    private val discoveryCache = object : LinkedHashMap<String, CachedDiscovery>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedDiscovery>?): Boolean =
            size > MaxDiscoveryCache
    }

    private val boardCache = object : LinkedHashMap<String, CachedBoard>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBoard>?): Boolean =
            size > MaxBoardCache
    }

    /** Fast repeat-open cache; search-independent, so reopening the tab does not rescan 60k rows. */
    private val quickBoardCache = object : LinkedHashMap<String, CachedBoard>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedBoard>?): Boolean =
            size > MaxQuickBoardCache
    }

    private val cacheLock = Any()
    private val groupCache = HashMap<Long, String?>()

    suspend fun match(
        fixtures: List<Fixture>,
        countries: List<String>,
        market: String?,
        apiKey: String,
        guide: (Fixture) -> BoardGuideEvidence = { BoardGuideEvidence() },
    ): BoardMatchResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || fixtures.isEmpty()) return@withContext emptyResult()

        val allRefs = fixtures
            .distinctBy { boardFixtureId(it) }
            .sortedWith(compareBy({ it.dateMs }, { it.id }))
            .map { BoardFixtureRef(it, leagueLabelOf(it.leagueId), sportOf(it.leagueId)) }
        if (allRefs.isEmpty()) return@withContext emptyResult()
        val refs = if (allRefs.size <= AiBoardPolicy.MaxDiscoveryFixtures) {
            allRefs
        } else {
            boardRoundRobinBySport(allRefs, AiBoardPolicy.MaxDiscoveryFixtures)
        }

        val sourceIds = sources.liveSourceIds()
        if (sourceIds.isEmpty()) return@withContext emptyResult()
        val loadedRows = runCatching { channelDao.allForSources(sourceIds, MaxCatalogueRows) }
        val rows = loadedRows.getOrDefault(emptyList()).mapNotNull(::indexed)
        if (rows.isEmpty()) {
            return@withContext emptyResult().copy(
                failure = if (loadedRows.isFailure) AiFailure.UNKNOWN else null,
            )
        }

        val guideByFixture = refs.associate { it.id to guide(it.fixture) }
        val cappedCountries = countries.take(AiBoardPolicy.MaxMarkets)
        val discoveryKey = discoveryKey(refs, cappedCountries)
        val baseBoardKey = boardKey(
            discoveryKey = discoveryKey,
            rows = rows,
            guide = guideByFixture.values,
            market = market,
            searchHits = emptyList(),
            searchHints = emptyList(),
        )
        val quickKey = QuickBoardPrefix + baseBoardKey
        val now = clock()

        synchronized(cacheLock) {
            quickBoardCache[quickKey]?.takeIf { now - it.atMs < QuickBoardCacheTtlMs }?.let { cached ->
                return@withContext cached.result.copy(adjudicationRequests = 0, latencyMs = 0)
            }
        }

        val startedAt = System.currentTimeMillis()
        // Search and local/phase-1 discovery are independent. Run them together; doing search
        // plus extractor first added their latency to every 60k-channel scan.
        val searchPending = if (refs.size == 1 && search != null) {
            async {
                val hits = searchFor(refs.single(), apiKey)
                val hints = if (hits.isNotEmpty()) {
                    extractSearchHints(refs.single(), hits, apiKey)
                } else {
                    emptyList()
                }
                hits to hints
            }
        } else {
            null
        }
        val boardDate = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val cachedDiscovery = synchronized(cacheLock) {
            discoveryCache[discoveryKey]?.takeIf { now - it.atMs < AiBoardPolicy.CacheTtlMs }
        }
        val local: Map<String, List<BoardCandidate>>
        val phase1: Map<String, List<BroadcastSuggestion>>
        var discoveryFailure: AiFailure? = null
        if (cachedDiscovery != null) {
            phase1 = cachedDiscovery.byFixture
            local = discoverCandidates(refs, rows, guideByFixture)
        } else {
            // Start phase 1 immediately; local discovery below never waits for it.
            val pending = async { requestDiscovery(refs, cappedCountries, apiKey, boardDate) }
            local = discoverCandidates(refs, rows, guideByFixture)
            val response = pending.await()
            phase1 = response.byFixture
            discoveryFailure = response.failure
            if (response.networkValid) {
                synchronized(cacheLock) { discoveryCache[discoveryKey] = CachedDiscovery(now, response.byFixture) }
            }
        }
        val (searchHits, searchHints) = searchPending?.await()
            ?: (emptyList<DeepSeekSearchHit>() to emptyList<SearchChannelHint>())
        val boardKey = if (searchHits.isEmpty() && searchHints.isEmpty()) {
            baseBoardKey
        } else {
            hash(baseBoardKey + "\n" + searchKey(searchHits, searchHints))
        }
        synchronized(cacheLock) {
            boardCache[boardKey]?.takeIf { now - it.atMs < AiBoardPolicy.CacheTtlMs }?.let { cached ->
                return@withContext cached.result.copy(adjudicationRequests = 0, latencyMs = 0)
            }
        }
        var merged = mergePhase1(refs, rows, local, phase1)
        if (searchHints.isNotEmpty()) {
            merged = mergeSearchHints(refs, rows, merged, searchHints)
        }
        val searchCandidateCount = merged.values.sumOf { candidates ->
            candidates.count { FlagSearch in it.flags }
        }
        val batches = BoardAdjudicationPlanner.plan(
            fixtures = refs,
            candidatesByFixture = merged,
            maxRequests = maxRequests,
            maxRequestChars = maxRequestChars,
        ).filter { it.candidates.isNotEmpty() }
        if (batches.isEmpty()) {
            return@withContext emptyResult().copy(
                failure = discoveryFailure,
                searchHits = searchHits.size,
                searchCandidates = searchCandidateCount,
            )
        }
        val result = adjudicate(
            refs = refs,
            batches = batches,
            guideByFixture = guideByFixture,
            phase1 = phase1,
            discoveryFailure = discoveryFailure,
            apiKey = apiKey,
            startedAt = startedAt,
            boardKey = boardKey,
            sourceIds = sourceIds.toSet(),
            market = market,
            searchHits = searchHits,
            searchCandidateCount = searchCandidateCount,
        )
        if (result.validResponses > 0) {
            synchronized(cacheLock) { quickBoardCache[quickKey] = CachedBoard(clock(), result) }
        }
        result
    }

    private data class DiscoveryResponse(
        val byFixture: Map<String, List<BroadcastSuggestion>>,
        val networkValid: Boolean,
        val failure: AiFailure?,
    )

    private data class VerifiedPick(
        val fixtureId: String,
        val channelId: Long,
        val channelName: String,
        val confidence: Double,
        val reason: String,
        val evidence: ChannelEvidenceTier,
        val source: ChannelEvidenceSource,
        val sourceIndex: Int?,
        val excerpt: String?,
        val epgEvidence: BoardEpgEvidence?,
    )

    private data class BatchOutcome(
        val picks: List<VerifiedPick>,
        val validResponse: Boolean,
        val inputChars: Int,
        val failure: AiFailure?,
    )

    private suspend fun requestDiscovery(
        refs: List<BoardFixtureRef>,
        countries: List<String>,
        apiKey: String,
        boardDate: String,
    ): DiscoveryResponse {
        val prompt = BoardDiscoveryPrompt.user(refs, countries, boardDate)
        return try {
            val content = ask(BoardDiscoveryPrompt.System, prompt, apiKey)
            val parsed = BoardDiscoveryParser.parse(content, refs.mapTo(HashSet()) { it.id })
            if (parsed == null) {
                DiscoveryResponse(emptyMap(), networkValid = false, failure = AiFailure.UNKNOWN)
            } else {
                DiscoveryResponse(parsed, networkValid = true, failure = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiRequestException) {
            DiscoveryResponse(emptyMap(), networkValid = false, failure = e.failure)
        } catch (e: Exception) {
            DiscoveryResponse(emptyMap(), networkValid = false, failure = AiFailure.UNKNOWN)
        }
    }

    private suspend fun adjudicate(
        refs: List<BoardFixtureRef>,
        batches: List<BoardAdjudicationBatch>,
        guideByFixture: Map<String, BoardGuideEvidence>,
        phase1: Map<String, List<BroadcastSuggestion>>,
        discoveryFailure: AiFailure?,
        apiKey: String,
        startedAt: Long,
        boardKey: String,
        sourceIds: Set<Long>,
        market: String?,
        searchHits: List<DeepSeekSearchHit>,
        searchCandidateCount: Int,
    ): BoardMatchResult = coroutineScope {
        val semaphore = Semaphore(maxConcurrent.coerceAtLeast(1))
        val outcomes = batches.mapIndexed { index, batch ->
            async {
                semaphore.withPermit {
                    runBatch(
                        batch = batch,
                        batchIndex = index + 1,
                        batchCount = batches.size,
                        guideByFixture = guideByFixture,
                        phase1 = phase1,
                        apiKey = apiKey,
                        market = market,
                        searchHits = searchHits,
                    )
                }
            }
        }.awaitAll()

        val valid = outcomes.count { it.validResponse }
        val invalid = outcomes.count { !it.validResponse }
        val rawPicks = outcomes.flatMap { it.picks }
        val hits = verify(rawPicks, sourceIds, searchHits)
        val failure = outcomes.firstNotNullOfOrNull { it.failure } ?: discoveryFailure
        val result = BoardMatchResult(
            hits = hits,
            fixtureCount = refs.size,
            discoverySuggestions = phase1.values.sumOf { it.size },
            candidateCount = batches.sumOf { it.candidates.size },
            adjudicationRequests = batches.size,
            validResponses = valid,
            invalidResponses = invalid,
            inputChars = outcomes.sumOf { it.inputChars },
            latencyMs = System.currentTimeMillis() - startedAt,
            searchHits = searchHits.size,
            searchCandidates = searchCandidateCount,
            failure = failure,
        )
        if (valid > 0) synchronized(cacheLock) { boardCache[boardKey] = CachedBoard(clock(), result) }
        result
    }

    private suspend fun runBatch(
        batch: BoardAdjudicationBatch,
        batchIndex: Int,
        batchCount: Int,
        guideByFixture: Map<String, BoardGuideEvidence>,
        phase1: Map<String, List<BroadcastSuggestion>>,
        apiKey: String,
        market: String?,
        searchHits: List<DeepSeekSearchHit>,
    ): BatchOutcome {
        val byRequestId = LinkedHashMap<String, BoardCandidate>()
        batch.candidates.forEachIndexed { index, candidate ->
            byRequestId[requestId(index)] = candidate
        }
        if (byRequestId.isEmpty()) return BatchOutcome(emptyList(), true, 0, null)
        val lines = byRequestId.map { (id, candidate) -> candidate.line(id) }
        val prompt = BoardAdjudicationPrompt.user(
            fixtures = batch.fixtures,
            candidates = lines,
            batchIndex = batchIndex,
            batchCount = batchCount,
            guide = guideByFixture,
            phase1 = phase1,
            market = market,
            searchResults = searchHits,
        )
        return try {
            val content = ask(BoardAdjudicationPrompt.System, prompt, apiKey)
            val parsed = BoardAdjudicationParser.parse(
                content = content,
                allowedFixtureIds = batch.fixtures.mapTo(HashSet()) { it.id },
                allowedCandidateIds = byRequestId.keys,
            ) ?: return BatchOutcome(emptyList(), false, prompt.length, AiFailure.UNKNOWN)
            val picks = parsed.flatMap { (fixtureId, selection) ->
                selection.mapNotNull { pick ->
                    val candidate = byRequestId[pick.candidateId] ?: return@mapNotNull null
                    // A model that omits `source` but names an exactly matched EPG row still gets
                    // EPG provenance; rights alone can never make a row supported.
                    val source = if (
                        pick.source == ChannelEvidenceSource.RIGHTS &&
                        pick.evidence == ChannelEvidenceTier.SUPPORTED &&
                        candidate.epgEvidence != null
                    ) {
                        ChannelEvidenceSource.EPG
                    } else {
                        pick.source
                    }
                    VerifiedPick(
                        fixtureId = fixtureId,
                        channelId = candidate.row.id,
                        channelName = candidate.row.name,
                        confidence = pick.confidence,
                        reason = pick.reason,
                        evidence = pick.evidence,
                        source = source,
                        sourceIndex = pick.sourceIndex,
                        excerpt = pick.excerpt,
                        epgEvidence = candidate.epgEvidence,
                    )
                }
            }
            BatchOutcome(picks, true, prompt.length, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiRequestException) {
            BatchOutcome(emptyList(), false, prompt.length, e.failure)
        } catch (e: Exception) {
            BatchOutcome(emptyList(), false, prompt.length, AiFailure.UNKNOWN)
        }
    }

    /** Ids are re-read from the live table; a changed name or a vanished row is dropped. */
    private suspend fun verify(
        picks: List<VerifiedPick>,
        sourceIds: Set<Long>,
        searchHits: List<DeepSeekSearchHit>,
    ): Map<String, List<ChannelMatch>> {
        if (picks.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, MutableList<ChannelMatch>>()
        for (pick in picks.sortedWith(compareByDescending<VerifiedPick> { it.confidence }.thenBy { it.channelId })) {
            val row = runCatching { channelDao.getById(pick.channelId) }.getOrNull() ?: continue
            if (row.name != pick.channelName || row.sourceId !in sourceIds) continue
            val bucket = out.getOrPut(pick.fixtureId) { ArrayList() }
            if (bucket.any { it.channelId == row.id }) continue
            val evidence = validateEvidence(pick, searchHits)
            bucket += ChannelMatch(
                channelId = row.id,
                channelName = row.name,
                logoUrl = row.logoUrl,
                epgTitle = null,
                score = 1 + (pick.confidence * 90).toInt() + ChannelDedup.resolutionRank(row.name) * 5,
                aiConfirmed = true,
                aiReason = pick.reason.takeIf { it.isNotBlank() },
                evidenceTier = evidence.tier,
                evidenceSource = evidence.source,
                evidenceDetail = evidence.detail,
                evidenceUrl = evidence.url,
                sourceId = row.sourceId,
            )
        }
        return out.mapValues { (_, value) ->
            value.sortedWith(
                compareByDescending<ChannelMatch> {
                    when (it.evidenceTier) {
                        ChannelEvidenceTier.SUPPORTED -> 2
                        ChannelEvidenceTier.LIKELY -> 1
                        ChannelEvidenceTier.CONFLICTING -> 0
                        else -> 0
                    }
                }
                    .thenByDescending { it.score }
                    .thenByDescending { ChannelDedup.resolutionRank(it.channelName) }
                    .thenBy { it.channelName },
            ).take(AiBoardPolicy.MaxPicksPerFixture)
        }.filterValues { it.isNotEmpty() }
    }

    private data class ValidatedEvidence(
        val tier: ChannelEvidenceTier,
        val source: ChannelEvidenceSource,
        val detail: String?,
        val url: String?,
    )

    /**
     * The model's tier is only a claim. Search picks are re-read against the actual result text; EPG
     * picks need the programme the app found itself; rights knowledge can never be `supported`.
     */
    private fun validateEvidence(
        pick: VerifiedPick,
        searchHits: List<DeepSeekSearchHit>,
    ): ValidatedEvidence {
        var tier = pick.evidence
        var detail = pick.reason.takeIf { it.isNotBlank() }
        var url: String? = null
        when (pick.source) {
            ChannelEvidenceSource.SEARCH -> {
                val hit = pick.sourceIndex?.let(searchHits::getOrNull)
                val grounded = hit != null && !pick.excerpt.isNullOrBlank() &&
                    isGroundedExcerpt(pick.excerpt, hit.haystack) &&
                    namesChannel(pick.channelName, hit.haystack)
                if (!grounded) {
                    if (tier == ChannelEvidenceTier.SUPPORTED) tier = ChannelEvidenceTier.LIKELY
                } else {
                    detail = hit.title.ifBlank { hit.siteName }.ifBlank { detail }
                    url = hit.url.takeIf { it.isNotBlank() }
                }
            }
            ChannelEvidenceSource.EPG -> {
                val epg = pick.epgEvidence
                if (epg == null) {
                    if (tier == ChannelEvidenceTier.SUPPORTED) tier = ChannelEvidenceTier.LIKELY
                } else {
                    detail = epg.title
                }
            }
            ChannelEvidenceSource.RIGHTS -> {
                if (tier == ChannelEvidenceTier.SUPPORTED) tier = ChannelEvidenceTier.LIKELY
            }
        }
        return ValidatedEvidence(tier, pick.source, detail, url)
    }

    /**
     * The cited result must actually name the playlist row, not just some other channel. Tags and
     * quality words are stripped first; a shortened broadcast brand is enough only when it keeps
     * at least two words (and the channel number when the result has one).
     */
    private fun namesChannel(channelName: String, haystack: String): Boolean {
        val base = ChannelDedup.identityOf(channelName)?.second
            ?.takeIf { it.isNotBlank() }
            ?: SportNameMatch.normalize(channelName)
        if (base.isBlank()) return false
        if (isGroundedExcerpt(base, haystack)) return true
        val words = base.split(' ').filter { it.length >= 2 }
        for (size in minOf(MaxChannelPhraseWords, words.size) downTo 2) {
            if (isGroundedExcerpt(words.take(size).joinToString(" "), haystack)) return true
        }
        return false
    }

    // ---- Local catalogue -----------------------------------------------------------------------

    private data class IndexedChannel(
        val row: ChannelEntity,
        val rawLower: String,
        val norm: String,
        val singular: String,
        val tokens: List<String>,
        val territory: String?,
        val epgKey: String?,
        val generic: Boolean,
    )

    private data class FixtureSignals(
        val ref: BoardFixtureRef,
        val participantTexts: List<String>,
        val participantTokens: Set<String>,
        val eventTexts: List<String>,
        val eventTokens: Set<String>,
        val broadcasterTexts: List<String>,
        val broadcasterTokens: Set<String>,
        val priorCarriers: List<String>,
        val sportWords: Set<String>,
        val session: String,
    )

    private suspend fun discoverCandidates(
        refs: List<BoardFixtureRef>,
        rows: List<IndexedChannel>,
        guideByFixture: Map<String, BoardGuideEvidence>,
    ): Map<String, List<BoardCandidate>> {
        val signals = refs.map { ref ->
            signalsFor(ref, guideByFixture[ref.id] ?: BoardGuideEvidence(), priors.carriersFor(ref.fixture))
        }
        val byId = signals.associateBy { it.ref.id }
        val rowById = rows.associateBy { it.row.id }
        val rowsByEpgKey = rows.mapNotNull { row -> row.epgKey?.let { it to row } }
            .groupBy({ it.first }, { it.second })

        val acc = HashMap<String, LinkedHashMap<Long, MutableSet<String>>>()
        fun add(fixtureId: String, rowId: Long, flag: String) {
            acc.getOrPut(fixtureId) { LinkedHashMap() }.getOrPut(rowId) { LinkedHashSet() }.add(flag)
        }

        val tokenIndex = HashMap<String, MutableSet<String>>()
        fun index(texts: List<String>, stopwords: Set<String>, fixtureId: String) {
            for (text in texts) {
                for (token in SportNameMatch.tokensForName(text, stopwords)) {
                    tokenIndex.getOrPut(token) { LinkedHashSet() }.add(fixtureId)
                }
            }
        }
        for (signal in signals) {
            index(signal.participantTexts, vocab.teamStopwords, signal.ref.id)
            index(signal.eventTexts, vocab.broadcastStopwords, signal.ref.id)
            index(signal.broadcasterTexts, vocab.broadcastStopwords, signal.ref.id)
        }

        // One pass over the whole catalogue; every row is normalised once in `indexed`.
        for (row in rows) {
            val matched = HashSet<String>()
            for (token in row.tokens) tokenIndex[token]?.let(matched::addAll)
            for (fixtureId in matched) {
                val signal = byId[fixtureId] ?: continue
                if (signal.participantTokens.any { it in row.tokens }) add(fixtureId, row.row.id, FlagParticipant)
                if (signal.eventTokens.any { it in row.tokens }) add(fixtureId, row.row.id, FlagEvent)
                if (signal.broadcasterTokens.any { it in row.tokens }) add(fixtureId, row.row.id, FlagBroadcaster)
            }
        }

        // Phrase check on the (much smaller) candidate set: the flag says why the row was kept.
        for ((fixtureId, bucket) in acc) {
            val signal = byId[fixtureId] ?: continue
            for ((rowId, flags) in bucket) {
                val row = rowById[rowId] ?: continue
                if (signal.participantTexts.any { matchesText(it, row) }) flags += FlagParticipant
                if (signal.eventTexts.any { matchesText(it, row) }) flags += FlagEvent
                if (signal.broadcasterTexts.any { matchesText(it, row) }) flags += FlagBroadcaster
                if (guideByFixture[fixtureId]?.carriers.orEmpty().any { matchesText(it, row) }) flags += FlagGuide
                if (signal.priorCarriers.any { matchesText(it, row) }) flags += FlagPrior
            }
        }

        // EPG discovery: broad SQL probes over the whole board window, mapped back through the
        // in-memory EPG-key index (no per-fixture table scans).
        val boardFrom = refs.minOf { it.fixture.dateMs } - EpgBeforeMs
        val boardTo = refs.maxOf { it.fixture.dateMs } + EpgAfterMs
        val epgByKey = HashMap<String, MutableList<EpgProgrammeEntity>>()
        val epgFixtures = HashMap<String, MutableSet<String>>()
        val seenProgrammes = HashSet<String>()
        fun addProgramme(programme: EpgProgrammeEntity, fixtureIds: Collection<String>) {
            val key = programme.epgChannelId.trim().lowercase(Locale.ROOT)
            if (key.isEmpty()) return
            val dedupe = key + "|" + programme.startMs + "|" + programme.stopMs + "|" + programme.title
            if (seenProgrammes.add(dedupe)) {
                epgByKey.getOrPut(key) { ArrayList() }.add(programme)
            }
            epgFixtures.getOrPut(key) { LinkedHashSet() }.addAll(fixtureIds)
        }
        val probes = planProbes(signals)
        if (probes.isNotEmpty()) {
            val probeResults = coroutineScope {
                val semaphore = Semaphore(EpgProbeConcurrency)
                probes.map { (probe, fixtureIds) ->
                    async { fixtureIds to semaphore.withPermit { loadProbe(probe, boardFrom, boardTo) } }
                }.awaitAll()
            }
            for ((fixtureIds, programmes) in probeResults) {
                for (programme in programmes) addProgramme(programme, fixtureIds)
            }
        }

        for ((key, _) in epgByKey) {
            val fixtureIds = epgFixtures[key].orEmpty()
            if (fixtureIds.isEmpty()) continue
            for (row in rowsByEpgKey[key].orEmpty()) {
                for (fixtureId in fixtureIds) add(fixtureId, row.row.id, FlagEpg)
            }
        }

        // Load the EPG context of every generic row and of every candidate row around the board.
        val genericRows = rows.filter { it.generic }
        val candidateRowIds = acc.values.flatMapTo(HashSet()) { it.keys }
        val contextKeys = LinkedHashSet<String>()
        genericRows.mapNotNullTo(contextKeys) { it.epgKey }
        rows.filter { it.row.id in candidateRowIds }.mapNotNullTo(contextKeys) { it.epgKey }
        if (contextKeys.isNotEmpty()) {
            for (programme in loadProgrammes(contextKeys, boardFrom, boardTo)) {
                addProgramme(programme, emptyList())
            }
        }

        // Opaque names: keep only rows with EPG, group or another identifying context.
        for (signal in signals) {
            val fixture = signal.ref.fixture
            for (row in genericRows) {
                val programmes = row.epgKey?.let { epgByKey[it] }.orEmpty()
                    .filter { overlaps(it, fixture.dateMs) }
                val group = groupOf(row.row)
                val groupMatch = groupMatches(group, signal)
                val sportNameMatch = row.tokens.any { it in signal.sportWords }
                val epgMatch = programmes.any { epgTextMatches(it, signal) || epgSportMatches(it, signal) }
                if (programmes.isEmpty() && !groupMatch && !sportNameMatch) continue
                when {
                    epgMatch -> add(signal.ref.id, row.row.id, FlagGeneric)
                    groupMatch -> add(signal.ref.id, row.row.id, FlagGeneric)
                    sportNameMatch -> {
                        add(signal.ref.id, row.row.id, FlagGeneric)
                        acc[signal.ref.id]?.get(row.row.id)?.add(FlagSportName)
                    }
                    else -> continue
                }
                if (epgMatch) acc[signal.ref.id]?.get(row.row.id)?.add(FlagEpg)
                if (groupMatch) acc[signal.ref.id]?.get(row.row.id)?.add(FlagGroup)
            }
        }

        // Build the model-visible candidates.
        val out = HashMap<String, List<BoardCandidate>>()
        for ((fixtureId, bucket) in acc) {
            val signal = byId[fixtureId] ?: continue
            val fixture = signal.ref.fixture
            val fixtureYouth = isYouthText(
                fixture.leagueId + " " + fixture.title.orEmpty() + " " + fixture.tournament.orEmpty(),
            )
            out[fixtureId] = bucket.mapNotNull { (rowId, flags) ->
                val row = rowById[rowId] ?: return@mapNotNull null
                val programmes = programmesFor(epgByKey, row, fixture)
                if (isYouthText(row.row.name + " " + programmes.joinToString(" ") { it.title + " " + it.description.orEmpty() }) !=
                    fixtureYouth
                ) {
                    return@mapNotNull null
                }
                val epg = formatEpg(programmes, fixture)
                val exactEpg = programmes.firstOrNull { epgTextMatches(it, signal) }
                val sessionMatch = signal.ref.fixture.format == FixtureFormat.EVENT &&
                    programmes.any { sessionsCompatible(signal.session, it) }
                val allFlags = LinkedHashSet(flags)
                if (exactEpg != null) allFlags += FlagEpg
                if (sessionMatch) allFlags += FlagSession
                BoardCandidate(
                    row = row.row,
                    territory = row.territory,
                    group = groupOf(row.row),
                    epg = epg,
                    flags = allFlags,
                    epgEvidence = exactEpg?.let {
                        BoardEpgEvidence(
                            epgChannelId = row.epgKey.orEmpty(),
                            title = it.title,
                            startMs = it.startMs,
                            stopMs = it.stopMs,
                        )
                    },
                )
            }.sortedWith(
                compareByDescending<BoardCandidate> { priority(it) }.thenBy { it.row.id },
            ).take(AiBoardPolicy.MaxCandidatesPerFixture)
        }
        return out
    }

    /** Phase-1 names are candidate generation only: they may add rows, never remove one. */
    private fun mergePhase1(
        refs: List<BoardFixtureRef>,
        rows: List<IndexedChannel>,
        local: Map<String, List<BoardCandidate>>,
        phase1: Map<String, List<BroadcastSuggestion>>,
    ): Map<String, List<BoardCandidate>> {
        val out = local.mapValuesTo(LinkedHashMap()) { (_, value) -> value.toMutableList() }
        val byFixture = refs.associateBy { it.id }
        for ((fixtureId, suggestions) in phase1) {
            if (fixtureId !in byFixture || suggestions.isEmpty()) continue
            val phrases = suggestions.flatMap { listOf(it.broadcaster) + it.channelAliases }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (phrases.isEmpty()) continue
            val prepared = phrases.map { phrase ->
                phrase.lowercase(Locale.ROOT) to SportNameMatch.singularize(phrase).trim()
            }
            for (row in rows) {
                val matched = prepared.any { (raw, norm) ->
                    row.rawLower.contains(raw) || (norm.isNotEmpty() && row.singular.contains(" " + norm + " "))
                }
                if (!matched) continue
                val list = out.getOrPut(fixtureId) { ArrayList() }
                val existing = list.indexOfFirst { it.row.id == row.row.id }
                if (existing >= 0) {
                    list[existing] = list[existing].copy(flags = list[existing].flags + FlagPhase1)
                } else {
                    list += BoardCandidate(
                        row = row.row,
                        territory = row.territory,
                        group = null,
                        epg = null,
                        flags = setOf(FlagPhase1),
                    )
                }
            }
        }
        return out.mapValues { (_, value) ->
            value.sortedWith(compareByDescending<BoardCandidate> { priority(it) }.thenBy { it.row.id })
                .take(AiBoardPolicy.MaxCandidatesPerFixture)
        }
    }

    /**
     * Search results add rows that phase 1 and the local EPG missed. The extractor only returns
     * names it quoted from a result; this method then matches those names against the full
     * catalogue. A search lead stays a lead until phase 2 confirms it.
     */
    private fun mergeSearchHints(
        refs: List<BoardFixtureRef>,
        rows: List<IndexedChannel>,
        local: Map<String, List<BoardCandidate>>,
        hints: List<SearchChannelHint>,
    ): Map<String, List<BoardCandidate>> {
        val ref = refs.singleOrNull() ?: return local
        if (hints.isEmpty()) return local
        val out = local.mapValuesTo(LinkedHashMap()) { (_, value) -> value.toMutableList() }
        val phrases = hints.flatMap { listOf(it.name) + it.aliases }
            .map { it.trim() }
            .filter { it.length >= MinTokenLength }
            .distinct()
        if (phrases.isEmpty()) return local
        for (row in rows) {
            if (!phrases.any { matchesText(it, row) }) continue
            val list = out.getOrPut(ref.id) { ArrayList() }
            val existing = list.indexOfFirst { it.row.id == row.row.id }
            if (existing >= 0) {
                list[existing] = list[existing].copy(flags = list[existing].flags + FlagSearch)
            } else {
                list += BoardCandidate(
                    row = row.row,
                    territory = row.territory,
                    group = null,
                    epg = null,
                    flags = setOf(FlagSearch),
                )
            }
        }
        return out.mapValues { (_, value) ->
            value.sortedWith(compareByDescending<BoardCandidate> { priority(it) }.thenBy { it.row.id })
                .take(AiBoardPolicy.MaxCandidatesPerFixture)
        }
    }

    /** One search per opened fixture, with a short fallback only when the first query is empty. */
    private suspend fun searchFor(ref: BoardFixtureRef, apiKey: String): List<DeepSeekSearchHit> {
        val run = search ?: return emptyList()
        val query = SportsSearchQueryBuilder.build(ref.fixture)
        for (text in query.all()) {
            val cached = synchronized(cacheLock) {
                searchCache[text]?.takeIf { clock() - it.atMs < SearchCacheTtlMs }
            }
            if (cached != null && cached.hits.isNotEmpty()) return cached.hits
            val hits = try {
                run(text, apiKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (hits.isNotEmpty()) {
                synchronized(cacheLock) { searchCache[text] = CachedSearch(clock(), hits) }
                return hits
            }
        }
        return emptyList()
    }

    private suspend fun extractSearchHints(
        ref: BoardFixtureRef,
        hits: List<DeepSeekSearchHit>,
        apiKey: String,
    ): List<SearchChannelHint> {
        val key = hash(
            ref.id + "\n" + hits.joinToString("\n") { it.title + "|" + it.url + "|" + it.haystack } +
                "\n" + SearchPromptVersion,
        )
        synchronized(cacheLock) {
            hintsCache[key]?.takeIf { clock() - it.atMs < AiBoardPolicy.CacheTtlMs }?.let {
                return it.hints
            }
        }
        val parsed = try {
            val content = ask(
                SportsSearchExtractorPrompt.System,
                SportsSearchExtractorPrompt.user(ref.fixture, hits),
                apiKey,
            )
            SportsSearchExtractorParser.parse(content, hits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        synchronized(cacheLock) { hintsCache[key] = CachedHints(clock(), parsed) }
        return parsed
    }

    private fun signalsFor(
        ref: BoardFixtureRef,
        guide: BoardGuideEvidence,
        priorCarriers: List<String>,
    ): FixtureSignals {
        val fixture = ref.fixture
        val participantTexts = LinkedHashSet<String>()
        for (team in listOfNotNull(fixture.home, fixture.away)) {
            participantTexts += team.name
            participantTexts += team.abbreviation
            participantTexts += vocab.aliasesFor(listOf(team.name, team.abbreviation))
        }
        for (entry in fixture.entries.take(2)) {
            participantTexts += entry.name.split(RosterSplit).map { it.trim() }
        }
        participantTexts.removeAll { it.isBlank() || it.equals(PlaceholderName, ignoreCase = true) }

        val eventTexts = LinkedHashSet<String>()
        fixture.title?.takeIf { it.isNotBlank() }?.let(eventTexts::add)
        fixture.tournament?.takeIf { it.isNotBlank() }?.let(eventTexts::add)
        eventTexts += ref.leagueLabel
        eventTexts += fixture.leagueId.replace('.', ' ')
        fixture.channelHints.forEach { eventTexts += it }

        val broadcasterTexts = LinkedHashSet<String>()
        broadcasterTexts += fixture.broadcasts
        broadcasterTexts += fixture.channelHints
        broadcasterTexts += priorCarriers
        broadcasterTexts += guide.carriers

        return FixtureSignals(
            ref = ref,
            participantTexts = participantTexts.toList(),
            participantTokens = tokens(participantTexts, vocab.teamStopwords),
            eventTexts = eventTexts.filter { it.isNotBlank() }.toList(),
            eventTokens = tokens(eventTexts, vocab.broadcastStopwords),
            broadcasterTexts = broadcasterTexts.filter { it.isNotBlank() }.toList(),
            broadcasterTokens = tokens(broadcasterTexts, vocab.broadcastStopwords),
            priorCarriers = priorCarriers,
            sportWords = sportWordsFor(ref.sport),
            session = BoardDiscoveryPrompt.sessionOf(fixture),
        )
    }

    private fun tokens(texts: Collection<String>, stopwords: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (text in texts) out += SportNameMatch.tokensForName(text, stopwords)
        return out
    }

    private fun matchesText(text: String, row: IndexedChannel): Boolean {
        val phrase = text.trim()
        if (phrase.length < 3) return false
        if (row.rawLower.contains(phrase.lowercase(Locale.ROOT))) return true
        val norm = SportNameMatch.singularize(phrase).trim()
        return norm.isNotEmpty() && row.singular.contains(" " + norm + " ")
    }

    private fun planProbes(signals: List<FixtureSignals>): Map<String, Set<String>> {
        val perFixture = signals.associate { signal ->
            val probes = LinkedHashSet<String>()
            for (text in signal.participantTexts) {
                probes += SportNameMatch.discoveryPhrases(text, vocab.teamStopwords)
            }
            for (text in signal.eventTexts) {
                probes += SportNameMatch.discoveryPhrases(text, vocab.broadcastStopwords)
            }
            signal.ref.id to probes.filter { it.length >= MinProbeLength }.take(ProbesPerFixture)
        }
        val out = LinkedHashMap<String, MutableSet<String>>()
        var index = 0
        var added = true
        while (added && out.size < MaxProbes) {
            added = false
            for ((fixtureId, list) in perFixture) {
                val probe = list.getOrNull(index) ?: continue
                added = true
                out.getOrPut(probe) { LinkedHashSet() }.add(fixtureId)
                if (out.size >= MaxProbes) break
            }
            index++
        }
        return out
    }

    private suspend fun loadProbe(probe: String, from: Long, to: Long): List<EpgProgrammeEntity> {
        val out = ArrayList<EpgProgrammeEntity>()
        runCatching { epgDao.searchTitles(probe, from, to, ProbeResultLimit) }
            .getOrNull()?.let(out::addAll)
        if (probe.contains(' ')) {
            runCatching { epgDao.searchGuide(probe, from, to, ProbeResultLimit) }
                .getOrNull()?.let(out::addAll)
        }
        return out
    }

    private suspend fun loadProgrammes(
        keys: Collection<String>,
        from: Long,
        to: Long,
    ): List<EpgProgrammeEntity> {
        val out = ArrayList<EpgProgrammeEntity>()
        for (chunk in keys.distinct().chunked(EpgKeyBatch)) {
            runCatching { epgDao.programmeSummariesForChannels(chunk, from, to) }
                .getOrNull()?.let(out::addAll)
        }
        return out
    }

    private fun programmesFor(
        byKey: Map<String, List<EpgProgrammeEntity>>,
        row: IndexedChannel,
        fixture: Fixture,
    ): List<EpgProgrammeEntity> =
        row.epgKey?.let { byKey[it] }.orEmpty()
            .filter { overlaps(it, fixture.dateMs) }
            .sortedBy { kotlin.math.abs(it.startMs - fixture.dateMs) }
            .take(MaxProgrammesPerCandidate)

    private fun formatEpg(programmes: List<EpgProgrammeEntity>, fixture: Fixture): String? {
        if (programmes.isEmpty()) return null
        return programmes.joinToString("; ") { programme ->
            utcTime(programme.startMs) + "-" + utcTime(programme.stopMs) + " " +
                programme.title.replace(LineBreak, Space).trim() +
                programme.description?.replace(LineBreak, Space)?.trim()?.take(MaxEpgDescriptionChars)
                    ?.takeIf { it.isNotEmpty() }?.let { " — " + it }.orEmpty()
        }
    }

    private fun epgTextMatches(programme: EpgProgrammeEntity, signal: FixtureSignals): Boolean {
        val norm = SportNameMatch.normalize(programme.title + " " + programme.description.orEmpty())
        return signal.participantTokens.any { it.length >= MinTokenLength && SportNameMatch.containsWord(it, norm) } ||
            signal.eventTokens.any { it.length >= MinTokenLength && SportNameMatch.containsWord(it, norm) }
    }

    private fun epgSportMatches(programme: EpgProgrammeEntity, signal: FixtureSignals): Boolean {
        if (signal.sportWords.isEmpty()) return false
        val norm = SportNameMatch.normalize(programme.title + " " + programme.description.orEmpty())
        return signal.sportWords.any { SportNameMatch.containsWord(it, norm) }
    }

    private fun groupMatches(group: String?, signal: FixtureSignals): Boolean {
        if (group.isNullOrBlank()) return false
        val norm = SportNameMatch.normalize(group)
        if (signal.sportWords.any { SportNameMatch.containsWord(it, norm) }) return true
        return signal.eventTokens.any { it.length >= MinTokenLength && SportNameMatch.containsWord(it, norm) }
    }

    private fun sessionsCompatible(fixtureSession: String, programme: EpgProgrammeEntity): Boolean {
        if (fixtureSession == "match" || fixtureSession == "duel" || fixtureSession == "unspecified") return true
        val norm = SportNameMatch.normalize(programme.title + " " + programme.description.orEmpty())
        val programmeSession = SessionWords.firstOrNull { SportNameMatch.containsWord(it, norm) } ?: return true
        return programmeSession == fixtureSession
    }

    /** Senior and youth events never inherit each other's channels (same rule as the matcher). */
    private fun isYouthText(text: String): Boolean {
        if (text.isBlank()) return false
        val norm = SportNameMatch.normalize(text)
        if (norm.contains("youth") || norm.contains("jugend")) return true
        return YouthAgePattern.containsMatchIn(norm)
    }

    private suspend fun groupOf(channel: ChannelEntity): String? {
        val categoryId = channel.categoryId ?: return null
        if (groupCache.containsKey(categoryId)) return groupCache[categoryId]
        val name = runCatching { categoryDao.getById(categoryId) }.getOrNull()?.name
        groupCache[categoryId] = name
        return name
    }

    private fun overlaps(programme: EpgProgrammeEntity, kickoff: Long): Boolean =
        programme.stopMs >= kickoff - EpgBeforeMs && programme.startMs <= kickoff + EpgAfterMs

    private fun indexed(channel: ChannelEntity): IndexedChannel? {
        val name = channel.name.trim()
        if (name.isEmpty() || name.none(Char::isLetterOrDigit)) return null
        val norm = SportNameMatch.normalize(name)
        val tokens = norm.trim().split(WordSplit).filter { it.isNotEmpty() }
        return IndexedChannel(
            row = channel,
            rawLower = name.lowercase(Locale.ROOT),
            norm = norm,
            singular = SportNameMatch.singularize(name),
            tokens = tokens,
            territory = SportLanguages.countryCodeOf(name),
            epgKey = channel.epgChannelId?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
            generic = isGenericName(norm),
        )
    }

    private fun isGenericName(norm: String): Boolean {
        if (SportNameMatch.containsWord("ppv", norm)) return true
        if (norm.contains(" web channel ")) return true
        val words = norm.trim().split(WordSplit).filter { it.isNotEmpty() && it.any(Char::isLetter) }
        if (words.isEmpty()) return true
        return words.all { it in GenericNameWords }
    }

    private fun priority(candidate: BoardCandidate): Int {
        var score = ChannelDedup.resolutionRank(candidate.row.name)
        if (FlagEpg in candidate.flags) score += 100
        if (FlagSearch in candidate.flags) score += 80
        if (FlagPhase1 in candidate.flags) score += 60
        if (FlagParticipant in candidate.flags) score += 50
        if (FlagGuide in candidate.flags) score += 40
        if (FlagPrior in candidate.flags) score += 15
        if (FlagEvent in candidate.flags) score += 30
        if (FlagBroadcaster in candidate.flags) score += 20
        if (FlagGroup in candidate.flags) score += 10
        if (FlagSportName in candidate.flags) score += 8
        if (FlagGeneric in candidate.flags) score += 5
        return score
    }

    private fun sportOf(leagueId: String): String =
        sportsLeagueById(leagueId)?.espnSport ?: leagueId.substringBefore('.')

    private fun leagueLabelOf(leagueId: String): String =
        runCatching { leagueLabel(leagueId) }.getOrDefault(leagueId)

    private fun sportWordsFor(sport: String): Set<String> = when (sport) {
        "soccer" -> setOf("soccer", "football", "fussball", "futbol", "calcio")
        "tennis" -> setOf("tennis", "atp", "wta")
        "racing" -> setOf("f1", "formula", "racing", "motorsport", "grand", "prix")
        "golf" -> setOf("golf", "pga", "dpwt")
        "football" -> setOf("nfl", "football", "college")
        "basketball" -> setOf("nba", "basketball", "euroleague")
        "baseball" -> setOf("mlb", "baseball")
        "hockey" -> setOf("nhl", "hockey")
        "mma" -> setOf("mma", "ufc")
        "boxing" -> setOf("boxing")
        "cricket" -> setOf("cricket")
        "rugby", "rugby-league" -> setOf("rugby")
        "australian-football" -> setOf("afl", "football")
        else -> sport.lowercase(Locale.ROOT).split('-').filter { it.length >= MinTokenLength }.toSet()
    }

    private fun discoveryKey(refs: List<BoardFixtureRef>, countries: List<String>): String {
        val day = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        return hash(
            listOf(
                day,
                refs.joinToString("\n") { ref ->
                    val f = ref.fixture
                    ref.id + "|" + f.dateMs + "|" + f.leagueId + "|" + ref.leagueLabel + "|" +
                        f.title.orEmpty() + "|" + f.tournament.orEmpty() + "|" +
                        f.home?.name.orEmpty() + "|" + f.away?.name.orEmpty() + "|" +
                        f.broadcasts.joinToString(",") + "|" +
                        f.entries.joinToString(",") { it.name }
                },
                refs.map { it.sport }.distinct().sorted().joinToString(","),
                countries.map { it.uppercase(Locale.ROOT) }.sorted().distinct().joinToString(","),
                AiBoardPolicy.Model,
                AiBoardPolicy.DiscoveryPromptVersion,
            ).joinToString("\n"),
        )
    }

    private fun boardKey(
        discoveryKey: String,
        rows: List<IndexedChannel>,
        guide: Collection<BoardGuideEvidence>,
        market: String?,
        searchHits: List<DeepSeekSearchHit>,
        searchHints: List<SearchChannelHint>,
    ): String {
        // Rolling hash, not a 60k-string sort/join: the key is only a cache identity, and a DB
        // order change or edit safely becomes a miss.
        var playlistHash = PlaylistHashSeed
        for (row in rows) {
            playlistHash = playlistHash * PlaylistHashPrime + row.row.id
            playlistHash = playlistHash * PlaylistHashPrime + row.row.name.hashCode()
            playlistHash = playlistHash * PlaylistHashPrime + row.epgKey.hashCode()
        }
        val guideHash = hash(
            guide.joinToString("\n") { evidence ->
                evidence.carriers.sorted().joinToString(",") + "|" +
                    evidence.rows.joinToString(";") { it.toString() }
            },
        )
        val searchHash = searchKey(searchHits, searchHints)
        return hash(
            discoveryKey + "\n" + playlistHash.toString(16) + "\n" + guideHash + "\n" +
                market.orEmpty() + "\n" + searchHash + "\n" +
                AiBoardPolicy.AdjudicationPromptVersion,
        )
    }

    private fun searchKey(
        searchHits: List<DeepSeekSearchHit>,
        searchHints: List<SearchChannelHint>,
    ): String = hash(
        searchHits.joinToString("\n") { it.title + "|" + it.url + "|" + it.haystack } +
            "\n" + searchHints.joinToString("\n") {
                it.name + "|" + it.country + "|" + it.sourceIndex + "|" + it.excerpt
            },
    )

    private fun hash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
    }

    private fun requestId(index: Int): String = RequestIdPrefix + (index + 1).toString().padStart(5, '0')

    private fun utcTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalTime().format(TimeFormat)

    private fun emptyResult() = BoardMatchResult(hits = emptyMap())

    private companion object {
        const val MaxCatalogueRows = 120_000
        const val EpgKeyBatch = 400
        const val EpgProbeConcurrency = 4
        const val ProbeResultLimit = 40
        const val ProbesPerFixture = 8
        const val MaxProbes = 100
        const val MaxProgrammesPerCandidate = 2
        const val MaxEpgDescriptionChars = 80
        const val MinProbeLength = 4
        const val MinTokenLength = 3
        const val MaxChannelPhraseWords = 3
        const val PlaylistHashSeed = 1_125_899_906_842_597L
        const val PlaylistHashPrime = 1_000_000_007L
        const val EpgBeforeMs = 2L * 60L * 60L * 1000L
        const val EpgAfterMs = 4L * 60L * 60L * 1000L
        const val MaxDiscoveryCache = 8
        const val MaxBoardCache = 4
        const val MaxQuickBoardCache = 8
        const val MaxSearchCache = 16
        const val MaxHintsCache = 8
        const val SearchCacheTtlMs = 30L * 60L * 1000L
        const val QuickBoardCacheTtlMs = 30L * 60L * 1000L
        const val QuickBoardPrefix = "quick:"
        const val SearchPromptVersion = "search-extractor-v1"
        const val RequestIdPrefix = "C"
        const val Space = " "
        val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val WordSplit = Regex("\\s+")
        val LineBreak = Regex("[\\r\\n\\t]+")
        val RosterSplit = Regex("\\s*/\\s*")
        const val PlaceholderName = "TBD"

        const val FlagParticipant = "PARTICIPANT_NAME_MATCH"
        const val FlagEvent = "EVENT_NAME_MATCH"
        const val FlagBroadcaster = "BROADCASTER_NAME_MATCH"
        const val FlagSearch = "SEARCH_BROADCASTER_MATCH"
        const val FlagPhase1 = "PHASE1_BROADCASTER_MATCH"
        const val FlagEpg = "EPG_EVENT_TITLE_MATCH"
        const val FlagSession = "EVENT_SESSION_MATCH"
        const val FlagGeneric = "GENERIC_EVENT_FEED"
        const val FlagGroup = "GROUP_SPORT_MATCH"
        const val FlagSportName = "SPORT_NAME_MATCH"
        const val FlagGuide = "GUIDE_CHANNEL_MATCH"
        const val FlagPrior = "BROADCASTER_PRIOR"

        val GenericNameWords = setOf(
            "ppv", "event", "web", "channel", "live", "feed", "stream", "hd", "sd", "fhd", "uhd",
            "4k", "tv", "backup", "test", "sport", "sports", "network", "extra",
        )

        val SessionWords = listOf(
            "practice", "qualifying", "sprint", "race", "warm", "final", "semifinal",
            "quarterfinal", "heat",
        )
        val YouthAgePattern = Regex("\\bu\\s?(17|18|19|20|21|23)\\b")
    }
}
