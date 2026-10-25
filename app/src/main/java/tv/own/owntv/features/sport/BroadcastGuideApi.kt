package tv.own.owntv.features.sport

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.R

/** One scraped WTM listing: exact UTC kickoff, competition name, fixture text, channels. */
data class GuideEntry(val dateMs: Long, val league: String, val teams: String, val channels: List<String>)

/**
 * Keyless "where to watch" lookup, scraped from Where's The Match (wheresthematch.com).
 *
 * One page URL with the day-window parameters covers **all sports** (football, F1/motorsport,
 * tennis, UFC, …) in the hub's ~7-day horizon: the listing rows are identical across the site's
 * per-sport pages, so one fetch duplicates nothing. The rows carry an exact UTC kickoff
 * (`<time datetime="…Z">`) used directly for epoch matching — never the BST display text.
 *
 * ESPN's own "broadcast" field is US-centric, so a Premier League game would never name Sky
 * Sports; these rows do. The confirmed broadcaster names ("Sky Sports F1") are probed against the
 * user's channel catalogue — evidence from an independent source, unlike name-only signals which
 * never qualify a channel on their own.
 *
 * Cached in memory for [CacheTtlMs] with one immediate retry per page (the site is flaky); every
 * fetch is defensive — a page that changed shape yields nothing, never a crash, and the matcher
 * falls back to its EPG-only behaviour.
 */
class BroadcastGuideApi(private val client: OkHttpClient) {

    /** The last successful guide snapshot — read synchronously, refreshed in the background. */
    @Volatile
    private var entries: List<GuideEntry> = emptyList()
    private var refreshedAtMs = 0L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _version = MutableStateFlow(0)

    /** Bumped when a new snapshot lands, so the open fixture can be re-matched with it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Whether a background refresh is worth starting (never decide in the UI path). */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - refreshedAtMs >= CacheTtlMs

    /**
     * The guide channels that (probably) carry [fixture]. Never fetches: reads the last snapshot,
     * so fixture selection is instant whether or not a refresh has finished yet.
     */
    fun confirmedBroadcasters(fixture: Fixture): List<String> =
        GuideMatch.confirmedBroadcasters(entries, fixture)

    /** Background refresh; every page independently guarded — a bad site never breaks the rest. */
    suspend fun refresh() {
        // One refresh at a time: callers (every board load) would otherwise stack whole copies of
        // the pages and their parses on top of each other.
        if (!refreshing.compareAndSet(false, true)) return
        try {
            refreshOnce()
        } finally {
            refreshing.set(false)
        }
    }

    /**
     * Two overlapping day windows: the site shortens long ranges to a few days of rows, so
     * "tomorrow" plus "+3..+8" together cover what the hub can show. Same rows in the seam are
     * collapsed by the merge below.
     */
    private suspend fun refreshOnce() {
        val merged = withContext(Dispatchers.IO) {
            val today = LocalDate.now()
            val ranges = listOf(
                today.minusDays(1) to today.plusDays(3),
                today.plusDays(4) to today.plusDays(8),
            )
            val found = ArrayList<GuideEntry>()
            for ((start, end) in ranges) {
                runCatching { get(dayWindowUrl(start, end)) }.getOrNull()?.let { html ->
                    runCatching { GuideParsers.wtm(html) }.getOrNull()?.let { found += it }
                }
            }
            found.groupBy { Triple(it.dateMs, it.league, it.teams) }
                .map { (key, group) -> GuideEntry(key.first, key.second, key.third, group.flatMap { it.channels }.distinct()) }
        }
        if (merged.isNotEmpty()) {
            entries = merged
            refreshedAtMs = System.currentTimeMillis()
            _version.value++
        }
    }

    private fun dayWindowUrl(start: LocalDate, end: LocalDate): String =
        AllSportsUrl + "?showdatestart=" + start.format(BasicIsoDate) + "&showdateend=" + end.format(BasicIsoDate)

    private fun get(url: String): String {
        return try {
            getOnce(url)
        } catch (first: Exception) {
            // One immediate retry — the site is flaky at peak times.
            getOnce(url)
        }
    }

    private fun getOnce(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(UserAgentHeader, UserAgentValue)
            .header(AcceptLanguageHeader, AcceptLanguageValue)
            .build()
        // Bounded timeouts: a hanging guide page must not stall matching for ever.
        client.newBuilder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("Guide HTTP " + response.code)
                val source = response.body.source()
                // A page that ballooned would cost more memory than the confirmation is worth.
                if (source.request(MaxPageBytes)) throw java.io.IOException("Guide page too large")
                return source.readUtf8()
            }
    }

    private companion object {
        const val AllSportsUrl = "https://www.wheresthematch.com/live-sport-on-tv/"
        const val UserAgentHeader = "User-Agent"
        const val UserAgentValue = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Matchday/1"
        const val AcceptLanguageHeader = "Accept-Language"
        const val AcceptLanguageValue = "en-GB,en;q=0.9"
        const val CacheTtlMs = 30L * 60L * 1000L
        const val MaxPageBytes = 4L * 1024L * 1024L
        val BasicIsoDate: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}

/** Pure guide ↔ fixture matching (kickoff time, both participants, event title). */
object GuideMatch {

    /**
     * The channels that (probably) carry [fixture], from [snapEntries]. A row matches when its
     * UTC kickoff is inside the fixture's window and — for a fixture with two participants (teams
     * or duel athletes) — **both** names token-overlap the row; otherwise (events, and duels the
     * guide names by tournament) the title/token overlap decides. When several rows match, the
     * one closest to the kickoff wins (race-weekend sessions list separately); team fixtures keep
     * every matching row with their channels merged and deduplicated.
     */
    fun confirmedBroadcasters(snapEntries: List<GuideEntry>, fixture: Fixture): List<String> {
        if (snapEntries.isEmpty()) return emptyList()
        val inWindow = snapEntries.filter { timeMatches(it.dateMs, fixture.dateMs) }
        if (inWindow.isEmpty()) return emptyList()
        val sides = participantsOf(fixture)
        if (sides != null) {
            val teamRows = inWindow.filter {
                bothSidesInRow(rowText(it), sides.first, sides.second)
            }
            if (teamRows.isNotEmpty()) return teamRows.flatMap { it.channels }.distinct()
            // Tennis-style duels: the guide groups by tournament, the fixture carries player
            // names — the tournament text falls back to the event path below.
        }
        val eventTitle = fixture.title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: fixture.tournament?.trim().orEmpty()
        if (eventTitle.isBlank()) return emptyList()
        val matchingRows = inWindow.filter { eventTokensInRow(rowText(it), eventTitle) }
        if (matchingRows.isEmpty()) return emptyList()
        // Several matching rows are race-weekend sessions: the nearest one is this session's
        // listing. (Team fixtures are decided by the both-sides path above, so closest-row never
        // trims a legitimate team duplicate.)
        return matchingRows.minByOrNull { kotlin.math.abs(it.dateMs - fixture.dateMs) }
            ?.channels?.distinct().orEmpty()
    }

    private fun rowText(entry: GuideEntry): String =
        SportNameMatch.normalize(entry.teams + " " + entry.league)

    /** Both participant names, or null when the fixture runs through the event-title path. */
    private fun participantsOf(fixture: Fixture): Pair<String, String>? {
        fixture.home?.let { home ->
            fixture.away?.let { away -> return home.name to away.name }
        }
        if (fixture.home != null || fixture.away != null) return null
        if (fixture.format != FixtureFormat.VERSUS || fixture.entries.size != 2) return null
        // A doubles side "A / B" keeps its whole text; the tokens overlap either way.
        return fixture.entries[0].name to fixture.entries[1].name
    }

    fun timeMatches(guideMs: Long, fixtureMs: Long): Boolean =
        guideMs > 0 && kotlin.math.abs(guideMs - fixtureMs) <= MaxTimeDeltaMs

    /**
     * Both participant names must token-overlap the row text: tokens after stripping generic club
     * affixes (fc/cf/afc/…), lowercased, diacritic-free, plural-tolerant. Only a row naming both
     * sides is this fixture — one side alone is any other event on that day.
     */
    fun bothSidesInRow(rowNorm: String, sideA: String, sideB: String): Boolean =
        sideA.isNotBlank() && sideB.isNotBlank() &&
            sideTokensHit(rowNorm, sideA) && sideTokensHit(rowNorm, sideB)

    private fun sideTokensHit(rowNorm: String, side: String): Boolean {
        val tokens = SportNameMatch.tokensForName(side, TeamSuffixes)
        return tokens.any { token ->
            SportNameMatch.containsWord(token, rowNorm) ||
                SportNameMatch.containsWord(SportNameMatch.singularize(token).trim(), rowNorm)
        }
    }

    /**
     * Title/token overlap for fixtures without participants (F1, tournaments, and duels whose
     * sides the guide names by tournament instead). At least two distinctive tokens must hit the
     * row, at least one of them NOT a series word ("grand", "prix", "cup", … every round of a
     * series shares those): "atp"+"lyon" against "ATP Lyon / ATP Almaty" is a real listing,
     * "Monaco Grand Prix" against an Australian race row is not.
     */
    fun eventTokensInRow(rowNorm: String, eventTitle: String): Boolean {
        val tokens = SportNameMatch.normalize(eventTitle).trim().split(' ')
            .filter { it.length >= MinEventTokenLength && it !in SessionWords }
        if (tokens.size < 2) return false
        val hits = tokens.filter { SportNameMatch.containsWord(it, rowNorm) }
        return hits.size >= 2 && hits.any { it !in SeriesWords }
    }

    /** Same window as the hub's board fill (large enough for a delayed listing row). */
    private const val MaxTimeDeltaMs = 3L * 60L * 60L * 1000L

    /** Generic club affixes (after [SportNameMatch.tokensForName]'s length rule). */
    private val TeamSuffixes = setOf(
        "fc", "cf", "afc", "sc", "ac", "sk", "if", "bk", "club", "calcio",
    )

    /** Session words name a race-weekend session, not the event — never event tokens. */
    private val SessionWords = setOf(
        "race", "qualifying", "practice", "warm", "final", "semifinal", "quarterfinal", "heat",
    )

    /** Series words every round of a competition shares — never the distinctive overlap. */
    private val SeriesWords = setOf(
        "grand", "prix", "cup", "open", "series", "world", "masters", "tour", "finals",
        "championship", "championships", "trophy",
    )

    /** Shortest event title token considered (matches [SportNameMatch]'s minimum). */
    private const val MinEventTokenLength = 3
}

/** HTML parsers for the WTM day-window pages — pure string work, unit-testable without Android. */
object GuideParsers {

    /**
     * One `<tr>` per listing; everything is read within the row via aria-label
     * (`"...TV coverage on Premiere Sports 2"`, which mirrors the row's `<span class="sr-only">`)
     * so the parser needs neither images nor the site's CSS classes to name a carrier.
     */
    fun wtm(html: String): List<GuideEntry> {
        val out = ArrayList<GuideEntry>()
        for (row in ROW_SPLIT.split(html).drop(1)) {
            val time = TIME_DATETIME.find(row)?.groupValues?.get(1) ?: continue
            val dateMs = runCatching { OffsetDateTime.parse(time).toInstant().toEpochMilli() }.getOrDefault(0L)
            if (dateMs <= 0) continue
            // Football/rugby rows read "TeamA v TeamB"; tennis/F1 rows carry the event text
            // ("ATP Tennis ATP Lyon / ATP Almaty", "F1 Academy Race 3 - …") — the whole
            // fixture-details cell is captured so the tournament/session names are matchable.
            val teams = FIXTURE_CELL.find(row)?.let { m -> decode(stripTags(m.groupValues[1])) } ?: continue
            if (teams.isBlank()) continue
            val league = COMPETITION_SPAN.find(row)?.groupValues?.get(1)?.trim() ?: ""
            // Channels live in the channel-details cell — scoped so the row's other aria-labels
            // and the `<time>`-cell "sr-only" spans never leak in; Premier-gated rows carry no
            // real channels and are skipped.
            val cell = row.substringAfter(ChannelCellOpen, "")
            if (cell.isEmpty()) continue
            if (GatedMarkers.any { cell.contains(it, ignoreCase = true) }) continue
            val channels = COVERAGE_ANCHOR.findAll(cell).map { decode(it.groupValues[1].trim()) }
                .filterNot { isNotABroadcaster(it) }.toList()
            if (channels.isEmpty()) continue
            out += GuideEntry(dateMs, league, teams, channels.distinct())
        }
        return out
    }

    /** WTM writes "[Not Televised]" in the channel column; that is a marker, not a carrier. */
    private fun isNotABroadcaster(name: String): Boolean =
        name.trim().trim('[', ']').equals("Not Televised", ignoreCase = true)

    private fun stripTags(text: String): String = text.replace(TagRegex, " ").replace(WhitespaceRegex, " ").trim()

    private fun decode(text: String): String =
        text.replace("&amp;", "&").replace("&nbsp;", " ").replace("&#39;", "'").trim()

    private val TagRegex = Regex("<[^>]*>")
    private val WhitespaceRegex = Regex("\\s+")
    private val ROW_SPLIT = Regex("<tr")
    private val TIME_DATETIME = Regex("<time[^>]*datetime=\"([^\"]+)\"")
    private val FIXTURE_CELL = Regex("<td class=\"fixture-details\">(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val COMPETITION_SPAN = Regex("<td class=\"competition-name\"[^>]*>.*?<span>([^<]+)</span>", RegexOption.DOT_MATCHES_ALL)
    private val COVERAGE_ANCHOR = Regex("aria-label=\"[^\"]*TV coverage on ([^\"]+)\"")
    private const val ChannelCellOpen = "<td class=\"channel-details\""
    private val GatedMarkers = listOf("unlock-premier-listing", "Log in to view")
}
