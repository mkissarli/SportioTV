package tv.own.owntv.features.sport

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

/** The bounded date range one source fetch covers. */
data class BroadcastWindow(val startMs: Long, val endMs: Long)

/** One source's answer: its listings, retrieval time and a failure string when the fetch failed. */
data class SourceResult(
    val sourceId: String,
    val sourceFamily: String,
    val retrievedAtMs: Long,
    val listings: List<BroadcastListing>,
    val failure: String? = null,
)

/** A deterministic broadcast source. Fetching never touches the UI thread. */
interface BroadcastSource {
    val sourceId: String

    /** Sites that syndicate one upstream schedule share a family, so they count once. */
    val sourceFamily: String
    val ttlMs: Long
    val timeoutMs: Long
    val url: String

    suspend fun fetch(window: BroadcastWindow, client: OkHttpClient, retrievedAtMs: Long): SourceResult
}

/**
 * Shared HTML fetch: bounded bytes, bounded time, one retry, and a failure object instead of an
 * exception. A changed or gated page yields no rows; it never crashes the guide refresh.
 */
abstract class HtmlBroadcastSource(
    override val sourceId: String,
    override val sourceFamily: String,
    override val url: String,
    override val ttlMs: Long = DefaultTtlMs,
    override val timeoutMs: Long = DefaultTimeoutMs,
    private val maxBytes: Long = DefaultMaxBytes,
) : BroadcastSource {

    abstract fun parse(html: String, window: BroadcastWindow, retrievedAtMs: Long): List<BroadcastListing>

    override suspend fun fetch(
        window: BroadcastWindow,
        client: OkHttpClient,
        retrievedAtMs: Long,
    ): SourceResult = withContext(Dispatchers.IO) {
        runCatching {
            val html = get(client, url, maxBytes, timeoutMs)
            SourceResult(sourceId, sourceFamily, retrievedAtMs, parse(html, window, retrievedAtMs))
        }.getOrElse { error ->
            SourceResult(
                sourceId = sourceId,
                sourceFamily = sourceFamily,
                retrievedAtMs = 0L,
                listings = emptyList(),
                failure = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun get(client: OkHttpClient, url: String, maxBytes: Long, timeoutMs: Long): String =
        BroadcastHttp.get(client, url, maxBytes, timeoutMs)

    companion object {
        const val DefaultTtlMs = 30L * 60L * 1000L
        const val DefaultTimeoutMs = 15_000L
        const val DefaultMaxBytes = 3L * 1024L * 1024L
    }
}

/** Shared bounded HTTP GET: user agent, byte cap, timeouts and one retry. */
internal object BroadcastHttp {
    fun get(client: OkHttpClient, url: String, maxBytes: Long, timeoutMs: Long): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Matchday/1")
            .header("Accept-Language", "en-GB,en;q=0.9,fr;q=0.8")
            .build()
        val bounded = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        fun once(): String = bounded.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP " + response.code)
            val source = response.body.source()
            if (source.request(maxBytes)) throw java.io.IOException("page too large")
            source.readUtf8()
        }
        return try {
            once()
        } catch (first: Exception) {
            once()
        }
    }
}

/** FootTV.fr — French football, fixture-specific, exposes the numbered Ligue 1+ feeds. */
object FootTvSource : HtmlBroadcastSource(
    sourceId = "foottv",
    sourceFamily = "foottv",
    url = "https://www.foottv.fr/",
    ttlMs = 30L * 60L * 1000L,
    maxBytes = 3L * 1024L * 1024L,
) {
    override fun parse(html: String, window: BroadcastWindow, retrievedAtMs: Long): List<BroadcastListing> =
        BroadcastSourceParsers.footTv(html, window, retrievedAtMs, sourceId, sourceFamily)
}

/** Foot Mercato — French-language European football, fixture-specific, including numbered feeds. */
object FootMercatoSource : HtmlBroadcastSource(
    sourceId = "footmercato",
    sourceFamily = "footmercato",
    url = "https://www.footmercato.net/programme-tv/",
    ttlMs = 30L * 60L * 1000L,
    maxBytes = 3L * 1024L * 1024L,
) {
    override fun parse(html: String, window: BroadcastWindow, retrievedAtMs: Long): List<BroadcastListing> =
        BroadcastSourceParsers.footMercato(html, window, retrievedAtMs, sourceId, sourceFamily)
}

/** Live Football on TV — UK football, fixture-specific, exact linear channels only. */
object LiveFootballOnTvSource : HtmlBroadcastSource(
    sourceId = "livefootballontv",
    sourceFamily = "livefootballontv",
    url = "https://www.live-footballontv.com/",
    ttlMs = 30L * 60L * 1000L,
    maxBytes = 3L * 1024L * 1024L,
) {
    override fun parse(html: String, window: BroadcastWindow, retrievedAtMs: Long): List<BroadcastListing> =
        BroadcastSourceParsers.liveFootballOnTv(html, window, retrievedAtMs, sourceId, sourceFamily)
}

/** Sky Sports' own tennis schedule — tournament/day rows with exact channel and start time. */
object SkySportsTennisSource : HtmlBroadcastSource(
    sourceId = "skysports-tennis",
    sourceFamily = "sky",
    url = "https://www.skysports.com/watch/tennis-on-sky",
    ttlMs = 60L * 60L * 1000L,
    maxBytes = 2L * 1024L * 1024L,
) {
    override fun parse(html: String, window: BroadcastWindow, retrievedAtMs: Long): List<BroadcastListing> =
        BroadcastSourceParsers.skySportsTennis(html, window, retrievedAtMs, sourceId, sourceFamily)
}

/** Sport-TV.fr — French tennis, session-level rows ("Masters ATP Paris — session soir"). */
object SportTvTennisSource : HtmlBroadcastSource(
    sourceId = "sport-tv-tennis",
    sourceFamily = "sport-tv",
    url = "https://sport-tv.fr/tennis/",
    ttlMs = 60L * 60L * 1000L,
    maxBytes = 1L * 1024L * 1024L,
) {
    override fun parse(html: String, window: BroadcastWindow, retrievedAtMs: Long): List<BroadcastListing> =
        BroadcastSourceParsers.sportTvTennis(html, window, retrievedAtMs, sourceId, sourceFamily)
}

/**
 * The last snapshot of every source. Reads are lock-free, refresh is background-only, a failed
 * source keeps its previous rows but they are marked stale (retrieval time 0) instead of silently
 * looking fresh. An empty successful page is not proof that an event is not televised, so it keeps
 * the previous snapshot too.
 */
class BroadcastSourceHub(
    private val client: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sources: List<BroadcastSource> = DefaultSources,
) {

    private data class Snapshot(
        val family: String,
        val listings: List<BroadcastListing>,
        val retrievedAtMs: Long,
        val failed: Boolean,
    )

    @Volatile
    private var snapshots: Map<String, Snapshot> = emptyMap()

    private val _version = MutableStateFlow(0)

    /** Bumped when any source snapshot changes, so the open fixture re-matches. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Fetch every due source concurrently. Returns true when a snapshot changed. */
    suspend fun refresh(): Boolean {
        val now = clock()
        val window = BroadcastWindow(now - DayMs, now + WindowDays * DayMs)
        val due = sources.filter { source ->
            val previous = snapshots[source.sourceId]
            previous == null || previous.failed || now - previous.retrievedAtMs >= source.ttlMs
        }
        if (due.isEmpty()) return false
        val results = coroutineScope {
            due.map { source ->
                async {
                    runCatching {
                        withTimeout(source.timeoutMs + GraceMs) {
                            source.fetch(window, client, now)
                        }
                    }.getOrElse { error ->
                        SourceResult(
                            sourceId = source.sourceId,
                            sourceFamily = source.sourceFamily,
                            retrievedAtMs = 0L,
                            listings = emptyList(),
                            failure = error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            }.awaitAll()
        }
        var changed = false
        val next = HashMap(snapshots)
        for (result in results) {
            val previous = next[result.sourceId]
            when {
                result.failure != null -> {
                    if (previous != null && !previous.failed) changed = true
                    next[result.sourceId] = Snapshot(
                        family = result.sourceFamily,
                        listings = previous?.listings.orEmpty(),
                        retrievedAtMs = previous?.retrievedAtMs ?: 0L,
                        failed = true,
                    )
                }
                result.listings.isNotEmpty() -> {
                    if (previous == null || previous.listings != result.listings || previous.failed) changed = true
                    next[result.sourceId] = Snapshot(
                        family = result.sourceFamily,
                        listings = result.listings,
                        retrievedAtMs = result.retrievedAtMs,
                        failed = false,
                    )
                }
                // An empty page is missing data, not a negative claim. Keep what we had.
                previous != null -> Unit
            }
        }
        snapshots = next
        if (changed) _version.value++
        return changed
    }

    /** Listings relevant to [fixture], from the last snapshot only. Never fetches. */
    fun listingsFor(fixture: Fixture): List<BroadcastListing> {
        val out = ArrayList<BroadcastListing>()
        for (snapshot in snapshots.values) {
            for (listing in snapshot.listings) {
                if (!BroadcastListingMatch.relevant(listing, fixture)) continue
                // A failed refresh means "we could not confirm this is still current".
                out += if (snapshot.failed) listing.copy(retrievedAtMs = 0L) else listing
            }
        }
        return out
    }

    /** Diagnostics: source id, whether its last fetch failed, and how many rows it holds. */
    fun status(): List<String> = sources.map { source ->
        val snapshot = snapshots[source.sourceId]
        source.sourceId + ":" + (snapshot?.listings?.size ?: 0) + if (snapshot?.failed == true) ":failed" else ""
    }

    companion object {
        val DefaultSources: List<BroadcastSource> = listOf(
            FootTvSource,
            FootMercatoSource,
            LiveFootballOnTvSource,
            SkySportsTennisSource,
            SportTvTennisSource,
        )
        private const val DayMs = 24L * 60L * 60L * 1000L
        private const val WindowDays = 8L
        private const val GraceMs = 2_000L
    }
}

/** Fixture ↔ source-listing relevance. Source rows stay independent of the matcher's fusion. */
object BroadcastListingMatch {

    fun relevant(listing: BroadcastListing, fixture: Fixture): Boolean {
        val sport = SportClass.fromLeagueId(fixture.leagueId)
        if (sport != listing.sport) return false
        if (sport == SportClass.TENNIS) return tennisRelevant(listing, fixture)
        val sides = teamSides(fixture) ?: return false
        if (listing.coverageType != CoverageType.FIXTURE) return false
        if (listing.participants.size < 2) return false
        if (!GuideMatch.timeMatches(listing.eventStartTimeMs, fixture.dateMs, ParticipantWindowMs)) return false
        val row = SportNameMatch.normalize(
            listing.participants.joinToString(" ") + " " + listing.competition.orEmpty(),
        )
        return GuideMatch.bothSidesInRow(row, sides.first, sides.second)
    }

    private fun teamSides(fixture: Fixture): Pair<String, String>? {
        val home = fixture.home ?: return null
        val away = fixture.away ?: return null
        return home.name to away.name
    }

    private fun tennisRelevant(listing: BroadcastListing, fixture: Fixture): Boolean {
        val tournament = fixture.tournament.orEmpty().trim()
        val competition = listing.competition.orEmpty().trim()
        val participants = listing.participants.joinToString(" ")
        val text = SportNameMatch.normalize(competition + " " + participants)
        val tour = when {
            fixture.leagueId.endsWith(".atp") -> "atp"
            fixture.leagueId.endsWith(".wta") -> "wta"
            else -> ""
        }
        // A row naming both players is a match listing; it is the only direct tennis evidence.
        if (listing.participants.size >= 2 && GuideMatch.bothSidesInRow(
                text,
                listing.participants[0],
                listing.participants[1],
            ) && GuideMatch.timeMatches(listing.eventStartTimeMs, fixture.dateMs, ParticipantWindowMs)
        ) {
            return true
        }
        // Otherwise it is tournament/session coverage. It must name this tournament (or, when the
        // fixture has no tournament name, at least the same tour) and overlap the fixture's day.
        val sameTour = tour.isNotEmpty() && SportNameMatch.containsWord(tour, text)
        val sameTournament = tournament.isNotEmpty() && competition.isNotEmpty() &&
            GuideMatch.eventTokensInRow(SportNameMatch.normalize(competition), tournament)
        if (tournament.isNotEmpty() && competition.isNotEmpty() && !sameTournament) return false
        if (!sameTour && !sameTournament) return false
        return overlapsDay(listing.coverageStartTimeMs, listing.coverageEndTimeMs, fixture.dateMs)
    }

    private fun overlapsDay(startMs: Long, endMs: Long, fixtureMs: Long): Boolean {
        if (startMs <= 0L) return false
        val start = startMs - DaySlackMs
        val end = maxOf(endMs, startMs) + DaySlackMs
        return fixtureMs in start..end
    }

    private const val ParticipantWindowMs = 90L * 60L * 1000L
    private const val DaySlackMs = 12L * 60L * 60L * 1000L
}

/** Pure parsers for captured page shapes. No network, no Android. */
object BroadcastSourceParsers {

    fun footTv(
        html: String,
        window: BroadcastWindow,
        retrievedAtMs: Long,
        sourceId: String = "foottv",
        sourceFamily: String = sourceId,
    ): List<BroadcastListing> {
        val out = ArrayList<BroadcastListing>()
        var competition = ""
        for (row in TrRegex.findAll(html)) {
            val block = row.groupValues[1]
            if (row.value.contains("cabeceraCompericion")) {
                competition = anchorText(block).ifBlank { competition }
                continue
            }
            if (!block.contains("class=\"canales\"")) continue
            val start = StartDateRegex.find(block)?.groupValues?.get(1)?.let(::parseUtc) ?: continue
            if (start !in window.startMs..window.endMs) continue
            val local = cellName(block, "local") ?: continue
            val away = cellName(block, "visitante") ?: continue
            val channels = channelList(block)
            if (channels.isEmpty()) continue
            val eventId = sourceId + ":" + start + ":" + local + "-" + away
            val end = start + durationMs(DurationRegex.find(block)?.groupValues?.get(1))
            for (channel in channels) {
                val service = isService(channel)
                out += BroadcastListing(
                    eventId = eventId,
                    sport = SportClass.FOOTBALL,
                    competition = competition.takeIf { it.isNotBlank() },
                    participants = listOf(local, away),
                    eventStartTimeMs = start,
                    coverageStartTimeMs = start,
                    coverageEndTimeMs = end,
                    broadcaster = channel,
                    channel = channel,
                    territory = "FR",
                    language = "fr",
                    coverageType = if (service) CoverageType.SERVICE else CoverageType.FIXTURE,
                    sourceId = sourceId,
                    retrievedAtMs = retrievedAtMs,
                    confidence = if (service) ServiceConfidence else FixtureConfidence,
                    direct = !service,
                    sourceRef = listOf(competition, "$local - $away").filter { it.isNotBlank() }.joinToString(" · "),
                    sourceFamily = sourceFamily,
                )
            }
            if (out.size >= MaxListings) break
        }
        return out
    }

    fun footMercato(
        html: String,
        window: BroadcastWindow,
        retrievedAtMs: Long,
        sourceId: String = "footmercato",
        sourceFamily: String = sourceId,
    ): List<BroadcastListing> {
        val out = ArrayList<BroadcastListing>()
        for (match in FmMatchRegex.findAll(html)) {
            val block = match.groupValues[1]
            val start = FmTimeRegex.find(block)?.groupValues?.get(1)
                ?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                ?: continue
            if (start !in window.startMs..window.endMs) continue
            val teams = FmTeamRegex.findAll(block)
                .map { decodeHtml(stripTags(it.groupValues[1])) }
                .filter { it.isNotBlank() }
                .take(2)
                .toList()
            if (teams.size != 2) continue
            val channels = FmBroadcastRegex.findAll(block).mapNotNull { image ->
                AltAttrRegex.find(image.value)?.groupValues?.get(1)
                    ?.let(::decodeHtml)
                    ?.replace(LogoPrefix, "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.distinctBy { it.lowercase(Locale.ROOT) }.toList()
            if (channels.isEmpty()) continue
            val eventId = sourceId + ":" + start + ":" + teams[0] + "-" + teams[1]
            for (channel in channels) {
                val service = isService(channel)
                out += BroadcastListing(
                    eventId = eventId,
                    sport = SportClass.FOOTBALL,
                    competition = null,
                    participants = teams,
                    eventStartTimeMs = start,
                    coverageStartTimeMs = start,
                    coverageEndTimeMs = start + MatchDurationMs,
                    broadcaster = channel,
                    channel = channel,
                    territory = "FR",
                    language = "fr",
                    coverageType = if (service) CoverageType.SERVICE else CoverageType.FIXTURE,
                    sourceId = sourceId,
                    retrievedAtMs = retrievedAtMs,
                    confidence = if (service) ServiceConfidence else FixtureConfidence,
                    direct = !service,
                    sourceRef = teams[0] + " - " + teams[1],
                    sourceFamily = sourceFamily,
                )
            }
            if (out.size >= MaxListings) break
        }
        return out.distinctBy { it.eventId + "|" + it.channel + "|" + it.coverageType }
    }

    fun liveFootballOnTv(        html: String,
        window: BroadcastWindow,
        retrievedAtMs: Long,
        sourceId: String = "livefootballontv",
        sourceFamily: String = sourceId,
    ): List<BroadcastListing> {
        val out = ArrayList<BroadcastListing>()
        var date: LocalDate? = null
        val anchors = AnchorTagRegex.findAll(html).toList()
        for ((index, anchor) in anchors.withIndex()) {
            val end = anchors.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(anchor.range.last + 1, end)
            if (anchor.groupValues[1] == "fixture-date") {
                date = parseEnglishDate(stripTags(block))
                continue
            }
            val day = date ?: continue
            val time = LfotTimeRegex.find(block)?.groupValues?.get(1)?.let(::parseTime) ?: continue
            val teams = LfotTeamsRegex.find(block)?.groupValues?.get(1)
                ?.let { decodeHtml(stripTags(it)) }.orEmpty()
            val sides = TeamsSplit.split(teams)
            if (sides.size != 2 || sides.any { it.isBlank() }) continue
            val competition = LfotCompetitionRegex.find(block)?.groupValues?.get(1)
                ?.let { decodeHtml(stripTags(it)) }.orEmpty()
            val start = ZonedDateTime.of(day, time, UkZone).toInstant().toEpochMilli()
            if (start !in window.startMs..window.endMs) continue
            val channels = ChannelPillRegex.findAll(block)
                .map { decodeHtml(stripTags(it.groupValues[1])) }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .toList()
            if (channels.isEmpty()) continue
            val eventId = sourceId + ":" + start + ":" + sides[0] + "-" + sides[1]
            for (channel in channels) {
                val service = isService(channel)
                out += BroadcastListing(
                    eventId = eventId,
                    sport = SportClass.FOOTBALL,
                    competition = competition.takeIf { it.isNotBlank() },
                    participants = sides,
                    eventStartTimeMs = start,
                    coverageStartTimeMs = start,
                    coverageEndTimeMs = start + MatchDurationMs,
                    broadcaster = channel,
                    channel = channel,
                    territory = "UK",
                    language = "en",
                    coverageType = if (service) CoverageType.SERVICE else CoverageType.FIXTURE,
                    sourceId = sourceId,
                    retrievedAtMs = retrievedAtMs,
                    confidence = if (service) ServiceConfidence else FixtureConfidence,
                    direct = !service,
                    sourceRef = listOf(competition, teams).filter { it.isNotBlank() }.joinToString(" · "),
                    sourceFamily = sourceFamily,
                )
            }
            if (out.size >= MaxListings) break
        }
        return out
    }

    fun skySportsTennis(
        html: String,
        window: BroadcastWindow,
        retrievedAtMs: Long,
        sourceId: String = "skysports-tennis",
        sourceFamily: String = "sky",
    ): List<BroadcastListing> {
        val out = ArrayList<BroadcastListing>()
        val dates = SkyDateRegex.findAll(html).toList()
        for ((index, dateMatch) in dates.withIndex()) {
            val end = dates.getOrNull(index + 1)?.range?.first ?: html.length
            val region = html.substring(dateMatch.range.last + 1, end)
            val day = parseSkyDay(stripTags(dateMatch.groupValues[1]), retrievedAtMs) ?: continue
            for (event in SkyEventRegex.findAll(region)) {
                val block = event.groupValues[1]
                val name = SkyStrongRegex.find(block)?.groupValues?.get(1)
                    ?.let { decodeHtml(stripTags(it)) }.orEmpty()
                if (name.isBlank()) continue
                val detail = SkyDetailRegex.find(block)?.groupValues?.get(1)
                    ?.let { decodeHtml(stripTags(it)) }.orEmpty()
                if (detail.isBlank()) continue
                for (part in detail.split(',')) {
                    val match = ChannelTimeRegex.find(part.trim()) ?: continue
                    val channel = match.groupValues[1].trim()
                    val time = parseTime(match.groupValues[2]) ?: continue
                    val start = ZonedDateTime.of(day, time, UkZone).toInstant().toEpochMilli()
                    if (start !in window.startMs..window.endMs) continue
                    out += BroadcastListing(
                        eventId = sourceId + ":" + name + ":" + day,
                        sport = SportClass.TENNIS,
                        competition = name,
                        participants = emptyList(),
                        eventStartTimeMs = start,
                        coverageStartTimeMs = start,
                        coverageEndTimeMs = start + TournamentWindowMs,
                        broadcaster = channel,
                        channel = channel,
                        territory = "UK",
                        language = "en",
                        coverageType = CoverageType.TOURNAMENT,
                        sourceId = sourceId,
                        retrievedAtMs = retrievedAtMs,
                        confidence = TournamentConfidence,
                        direct = false,
                        sourceRef = name + " · " + channel + " " + match.groupValues[2],
                        sourceFamily = sourceFamily,
                    )
                }
            }
        }
        return out.take(MaxListings)
    }

    fun sportTvTennis(
        html: String,
        window: BroadcastWindow,
        retrievedAtMs: Long,
        sourceId: String = "sport-tv-tennis",
        sourceFamily: String = "sport-tv",
    ): List<BroadcastListing> {
        val out = ArrayList<BroadcastListing>()
        for (section in SectionRegex.findAll(html)) {
            val day = dayFromDataDay(section.groupValues[1], retrievedAtMs) ?: continue
            for (match in SportTvMatchRegex.findAll(section.groupValues[2])) {
                val block = match.groupValues[1]
                val time = SportTvTimeRegex.find(block)?.groupValues?.get(1)?.let(::parseTime) ?: continue
                val teams = SportTvTeamsRegex.find(block)?.groupValues?.get(1)
                    ?.let { decodeHtml(stripTags(it)) }.orEmpty()
                val channel = decodeHtml(stripTags(match.groupValues[2])).trim().ifBlank { continue }
                if (teams.isBlank() || channel.isBlank()) continue
                val start = ZonedDateTime.of(day, time, FranceZone).toInstant().toEpochMilli()
                if (start !in window.startMs..window.endMs) continue
                out += BroadcastListing(
                    eventId = sourceId + ":" + teams + ":" + day,
                    sport = SportClass.TENNIS,
                    competition = teams,
                    participants = emptyList(),
                    eventStartTimeMs = start,
                    coverageStartTimeMs = start,
                    coverageEndTimeMs = start + SessionWindowMs,
                    broadcaster = channel,
                    channel = channel,
                    territory = "FR",
                    language = "fr",
                    coverageType = CoverageType.SESSION,
                    sourceId = sourceId,
                    retrievedAtMs = retrievedAtMs,
                    confidence = SessionConfidence,
                    direct = false,
                    sourceRef = teams + " · " + channel,
                    sourceFamily = sourceFamily,
                )
            }
        }
        return out.take(MaxListings)
    }

    // ---- shared parsing helpers ---------------------------------------------------------------

    private fun parseUtc(value: String): Long =
        runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME) }
            .map { it.toInstant(ZoneOffset.UTC).toEpochMilli() }
            .getOrDefault(0L)
            .takeIf { it > 0L } ?: 0L

    private fun parseTime(value: String): LocalTime? =
        runCatching { LocalTime.parse(value.trim(), DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()

    private fun parseEnglishDate(text: String): LocalDate? {
        val cleaned = OrdinalRegex.replace(text.trim(), "$1")
        return runCatching {
            LocalDate.parse(cleaned, DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))
        }.getOrNull()
    }

    /** Sky writes "Sun 1st November" without a year; use the retrieval year, rolling over. */
    private fun parseSkyDay(text: String, retrievedAtMs: Long): LocalDate? {
        val match = SkyDayRegex.find(text) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = monthOf(match.groupValues[2]) ?: return null
        return yearFor(day, month, retrievedAtMs, UkZone)
    }

    /** Sport-TV writes `data-day="1nov"` without a year. */
    private fun dayFromDataDay(value: String, retrievedAtMs: Long): LocalDate? {
        val match = DataDayRegex.find(value) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = monthOf(match.groupValues[2]) ?: return null
        return yearFor(day, month, retrievedAtMs, FranceZone)
    }

    private fun yearFor(day: Int, month: Month, retrievedAtMs: Long, zone: ZoneId): LocalDate? {
        val today = Instant.ofEpochMilli(retrievedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis())
            .atZone(zone).toLocalDate()
        var date = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        if (date.isBefore(today.minusDays(RolloverDays))) date = date.plusYears(1)
        return date
    }

    private fun monthOf(text: String): Month? {
        val key = text.trim().lowercase(Locale.ROOT).take(3)
        return MonthShortNames[key]
    }

    private fun cellName(block: String, cssClass: String): String? {
        val cell = Regex("class=\"$cssClass[^\"]*\"[^>]*>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1) ?: return null
        val title = TitleAttrRegex.find(cell)?.groupValues?.get(1)
            ?.let(::decodeHtml)?.trim()
        if (!title.isNullOrBlank()) return title
        return decodeHtml(stripTags(cell)).trim().takeIf { it.isNotBlank() }
    }

    /**
     * FootTV writes some carriers as links and some as plain `<li class="canal-sin-enlace">`.
     * Both are the source's channel, so both are read; affiliate redirects are not carriers.
     */
    private fun channelList(block: String): List<String> {
        val list = ListaCanalesRegex.find(block)?.groupValues?.get(1) ?: return emptyList()
        return ChannelItemRegex.findAll(list).mapNotNull { item ->
            val attrs = item.groupValues[1]
            val text = decodeHtml(stripTags(item.groupValues[2]))
            if ("awin1.com" in attrs || text.contains("Voir en direct", ignoreCase = true)) return@mapNotNull null
            val title = LiTitleAttrRegex.find(attrs)?.groupValues?.get(1)?.let(::decodeHtml)?.trim()
            (title?.takeIf { it.isNotBlank() } ?: text).takeIf { it.isNotBlank() }
        }.distinctBy { it.lowercase(Locale.ROOT) }.toList()
    }

    private fun anchorText(block: String): String =
        AnchorRegex.find(block)?.groupValues?.get(1)?.let { decodeHtml(stripTags(it)).trim() }.orEmpty()

    private fun isService(channel: String): Boolean =
        ChannelIdentityResolver.resolve(channel)?.channel == ChannelIdentityResolver.NetworkChannel

    private fun durationMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return MatchDurationMs
        val match = IsoDurationRegex.find(iso) ?: return MatchDurationMs
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val total = hours * HourMs + minutes * MinuteMs
        return total.coerceIn(MinDurationMs, MaxDurationMs)
    }

    private fun stripTags(text: String): String =
        decodeHtml(TagRegex.replace(text, " ")).replace(WhitespaceRegex, " ").trim()

    private fun decodeHtml(text: String): String {
        if ('&' !in text) return text.trim()
        val numeric = NumericEntityRegex.replace(text) { match ->
            val hex = match.groupValues[1]
            val decimal = match.groupValues[2]
            val code = (if (hex.isNotEmpty()) hex.toIntOrNull(16) else decimal.toIntOrNull()) ?: 0
            if (code > 0) String(Character.toChars(code)) else match.value
        }
        return NamedEntityRegex.replace(numeric) { match ->
            NamedEntities[match.groupValues[1].lowercase(Locale.ROOT)] ?: match.value
        }.replace('\u00a0', ' ').trim()
    }

    private const val MaxListings = 2_000
    private const val FixtureConfidence = 90
    private const val ServiceConfidence = 40
    private const val SessionConfidence = 60
    private const val TournamentConfidence = 45
    private const val MatchDurationMs = 2L * 60L * 60L * 1000L
    private const val SessionWindowMs = 6L * 60L * 60L * 1000L
    private const val TournamentWindowMs = 8L * 60L * 60L * 1000L
    private const val HourMs = 60L * 60L * 1000L
    private const val MinuteMs = 60L * 1000L
    private const val MinDurationMs = 30L * MinuteMs
    private const val MaxDurationMs = 4L * HourMs
    private const val RolloverDays = 180L

    private val UkZone: ZoneId = ZoneId.of("Europe/London")
    private val FranceZone: ZoneId = ZoneId.of("Europe/Paris")

    private val TrRegex = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val StartDateRegex = Regex("itemprop=\"startDate\"\\s+content=\"([^\"]+)\"")
    private val DurationRegex = Regex("itemprop=\"duration\"\\s+content=\"([^\"]+)\"")
    private val ListaCanalesRegex =
        Regex("<ul class=\"listaCanales\">(.*?)</ul>", RegexOption.DOT_MATCHES_ALL)
    private val ChannelItemRegex = Regex("<li([^>]*)>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
    private val LiTitleAttrRegex = Regex("title=\"([^\"]+)\"")
    private val TitleAttrRegex = Regex("<span[^>]*\\stitle=\"([^\"]+)\"")
    private val AnchorRegex = Regex("<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val IsoDurationRegex = Regex("T(?:(\\d+)H)?(?:(\\d+)M)?")

    private val FmMatchRegex = Regex("<div class=\"matchFull\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
    private val FmTimeRegex = Regex("<time[^>]*datetime=\"([^\"]+)\"")
    private val FmTeamRegex = Regex("<span class=\"matchTeam__name\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private val FmBroadcastRegex = Regex(
        "<img[^>]*class=\"matchFull__broadcastImage\"[^>]*>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val AltAttrRegex = Regex("alt=\"([^\"]+)\"")
    private val LogoPrefix = Regex("^Logo\\s+", RegexOption.IGNORE_CASE)

    private val AnchorTagRegex = Regex("<div class=\"(fixture-date|fixture)\">")
    private val LfotTimeRegex = Regex("fixture__time\">([^<]*)<")
    private val LfotTeamsRegex = Regex("fixture__teams\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val LfotCompetitionRegex = Regex("fixture__competition\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val ChannelPillRegex = Regex("<span class=\"channel-pill\"[^>]*>(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private val TeamsSplit = Regex("\\s+v\\s+", RegexOption.IGNORE_CASE)
    private val OrdinalRegex = Regex("(\\d+)(?:st|nd|rd|th)")
    private val SkyDayRegex = Regex("(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]+)")

    private val SkyDateRegex = Regex("<h3 class=\"text-h4[^\"]*\"[^>]*>(.*?)</h3>", RegexOption.DOT_MATCHES_ALL)
    private val SkyEventRegex =
        Regex("<div class=\"event-group[^\"]*\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL)
    private val SkyStrongRegex = Regex("<strong>(.*?)</strong>", RegexOption.DOT_MATCHES_ALL)
    private val SkyDetailRegex = Regex("<p class=\"event-detail[^\"]*\"[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    private val ChannelTimeRegex = Regex("(.+?)\\s*\\((\\d{1,2}:\\d{2})\\)")
    private val DataDayRegex = Regex("(\\d{1,2})([a-z]{3})")

    private val SectionRegex = Regex(
        "<section class=\"day-section[^\"]*\" data-day=\"([^\"]+)\">(.*?)</section>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SportTvMatchRegex = Regex(
        "<div class=\"match[^\"]*\">(.*?)<span class=\"match-channel\">(.*?)</span>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val SportTvTimeRegex = Regex("match-time\">([^<]*)<")
    private val SportTvTeamsRegex = Regex("match-teams\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL)

    private val TagRegex = Regex("<[^>]*>")
    private val WhitespaceRegex = Regex("\\s+")
    private val NumericEntityRegex = Regex("&#(?:x([0-9a-fA-F]+)|(\\d+));")
    private val NamedEntityRegex = Regex("&([a-zA-Z]+);")

    private val NamedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "eacute" to "é",
        "egrave" to "è",
        "ecirc" to "ê",
        "agrave" to "à",
        "acirc" to "â",
        "ccedil" to "ç",
        "ugrave" to "ù",
        "ucirc" to "û",
        "icirc" to "î",
        "ocirc" to "ô",
        "oelig" to "œ",
        "aelig" to "æ",
        "Eacute" to "É",
        "Egrave" to "È",
        "Agrave" to "À",
    )

    private val MonthShortNames = mapOf(
        "jan" to Month.JANUARY,
        "feb" to Month.FEBRUARY,
        "mar" to Month.MARCH,
        "apr" to Month.APRIL,
        "may" to Month.MAY,
        "jun" to Month.JUNE,
        "jul" to Month.JULY,
        "aug" to Month.AUGUST,
        "sep" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER,
        "nov" to Month.NOVEMBER,
        "dec" to Month.DECEMBER,
    )
}
