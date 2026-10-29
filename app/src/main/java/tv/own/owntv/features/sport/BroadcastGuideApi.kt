package tv.own.owntv.features.sport

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
data class GuideEntry(
    val dateMs: Long,
    val league: String,
    val teams: String,
    val channels: List<String>,
    /**
     * The row exists but its carriers are hidden behind the site's Premier/login gate. That is
     * **missing carrier evidence**, not proof that the event is not televised.
     */
    val gated: Boolean = false,
)

/**
 * The day windows one guide refresh requests. Pure so the "never start in the past" rule (the site
 * degrades a past-start range to today's remaining rows) is unit-testable without a network.
 */
object GuideWindows {

    /** Days covered per request — the site returns only ~3–5 days of any range. */
    private const val DaysPerWindow = 3L
    private const val WindowCount = 3L

    fun forDay(today: LocalDate): List<Pair<LocalDate, LocalDate>> =
        (0 until WindowCount).map { index ->
            val start = today.plusDays(index * DaysPerWindow)
            start to start.plusDays(DaysPerWindow - 1)
        }
}

/**
 * Keyless "where to watch" lookup, scraped from Where's The Match (wheresthematch.com).
 *
 * One page URL with the day-window parameters covers **all sports** (football, F1/motorsport,
 * tennis, UFC, …) in the hub's ~7-day horizon: the listing rows are identical across the site's
 * per-sport pages, so one fetch duplicates nothing. The rows carry an exact UTC kickoff
 * (`<time datetime="…Z">`) used directly for epoch matching — never the BST display text.
 *
 * The per-sport pages (cycling, athletics, boxing) are fetched as well: those sports have no ESPN
 * league, so their board is built from these rows ([GuideBoard]). They are *not* a subset of the
 * all-sports page — measured 2026-10-29, the cycling page carried 5 rows for a week where the
 * all-sports page showed 2.
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

    /** The per-sport snapshots (cycling, athletics, boxing), keyed by the sport page slug. */
    @Volatile
    private var sportEntries: Map<String, List<GuideEntry>> = emptyMap()
    private var refreshedAtMs = 0L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _version = MutableStateFlow(0)

    /** Bumped when a new snapshot lands, so the open fixture can be re-matched with it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** Whether a background refresh is worth starting (never decide in the UI path). */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - refreshedAtMs >= CacheTtlMs

    /** The guide channels that (probably) carry [fixture]. Never fetches: reads the last snapshot,
     * so fixture selection is instant whether or not a refresh has finished yet.
     */
    fun confirmedBroadcasters(fixture: Fixture): List<String> =
        GuideMatch.confirmedBroadcasters(entries, fixture)

    /**
     * The rows of one per-sport page (cycling, athletics, boxing). Those sports have no ESPN league,
     * so their board is built from these rows ([GuideBoard]) instead of a fixtures API.
     */
    fun entriesFor(sport: String): List<GuideEntry> = sportEntries[sport].orEmpty()

    /**
     * The rows plausibly related to [fixture] (including gated and tour-level rows) for the AI
     * prompt. Evidence only: this never confirms a carrier by itself.
     */
    fun evidenceFor(fixture: Fixture): List<GuideEntry> = GuideMatch.evidenceFor(entries, fixture)

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
     * Three contiguous day windows covering today..+8, never starting in the past: a start date in
     * the past makes the site answer with a degenerate "today only" page — measured 2026-10-25,
     * `showdatestart=<yesterday>` returned 33 rows for today while `showdatestart=<today>` returned
     * 111+ rows including tomorrow. That silently dropped the F1 race row. A request covers ~3–5
     * days, so 3-day chunks stay well inside the site's limit.
     */
    private suspend fun refreshOnce() {
        val (merged, extras) = withContext(Dispatchers.IO) {
            val found = ArrayList<GuideEntry>()
            for ((start, end) in GuideWindows.forDay(LocalDate.now())) {
                fetchInto(found, dayWindowUrl(start, end))
            }
            val sportRows = GuideBoard.sportPages.associateWith { sport ->
                val rows = ArrayList<GuideEntry>()
                for ((start, end) in GuideWindows.forDay(LocalDate.now())) {
                    fetchInto(rows, sportWindowUrl(sport, start, end))
                }
                merge(rows)
            }
            merge(found) to sportRows
        }
        if (merged.isNotEmpty()) {
            entries = merged
            refreshedAtMs = System.currentTimeMillis()
        }
        // A sport page with no rows this week keeps its previous snapshot: an empty page is not
        // proof that the competition has no televised events. A changed page must bump the version,
        // or the guide-backed boards would never pick it up.
        val next = extras.filterValues { it.isNotEmpty() }
        val changed = next.any { (sport, rows) -> sportEntries[sport] != rows }
        sportEntries = sportEntries + next
        if (merged.isNotEmpty() || changed) _version.value++
    }

    private fun fetchInto(out: MutableList<GuideEntry>, url: String) {
        runCatching { get(url) }.getOrNull()?.let { html ->
            runCatching { GuideParsers.wtm(html) }.getOrNull()?.let { out += it }
        }
    }

    /** One row per (start, competition, fixture text), carriers merged. */
    private fun merge(raw: List<GuideEntry>): List<GuideEntry> =
        raw.groupBy { Triple(it.dateMs, it.league, it.teams) }
            .map { (key, group) ->
                GuideEntry(
                    dateMs = key.first,
                    league = key.second,
                    teams = key.third,
                    channels = group.flatMap { it.channels }.distinct(),
                    gated = group.all { it.gated },
                )
            }

    private fun dayWindowUrl(start: LocalDate, end: LocalDate): String =
        AllSportsUrl + "?showdatestart=" + start.format(BasicIsoDate) + "&showdateend=" + end.format(BasicIsoDate)

    private fun sportWindowUrl(sport: String, start: LocalDate, end: LocalDate): String =
        SportPageBase + sport + SportPageSuffix + "?showdatestart=" +
            start.format(BasicIsoDate) + "&showdateend=" + end.format(BasicIsoDate)

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
        const val SportPageBase = "https://www.wheresthematch.com/live-"
        const val SportPageSuffix = "-on-tv/"
        const val UserAgentHeader = "User-Agent"
        const val UserAgentValue = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Matchday/1"
        const val AcceptLanguageHeader = "Accept-Language"
        const val AcceptLanguageValue = "en-GB,en;q=0.9"
        const val CacheTtlMs = 30L * 60L * 1000L
        const val MaxPageBytes = 4L * 1024L * 1024L
        val BasicIsoDate: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}

/**
 * Pure guide ↔ fixture matching. The guide's rows are not uniform, so neither is their evidence:
 *
 *  - team fixtures (and MMA duels) get a row naming both sides;
 *  - **tennis has one row per tour per day** ("ATP Tennis ATP Lyon / ATP Almaty", 12:00Z) — never a
 *    per-match row, so that row is the tour's carrier list for the fixture's day;
 *  - events (F1) get one row per session, with the session named in the row text.
 *
 * A fixture with no matching row gets no channels: an empty list is the honest answer, never a
 * guess from a nearby row.
 */
object GuideMatch {

    /**
     * The channels that (probably) carry [fixture], from [snapEntries]. Never fetches.
     *
     * Team/duel fixtures need a row naming both participants inside [ParticipantWindowMs]. Event
     * fixtures (F1) match on title tokens and the same session, nearest row winning. A tour-level
     * tennis row ("ATP Tennis ATP Lyon / ATP Almaty") is **not** confirmation for one specific
     * match: it is prompt evidence only, so a specific fixture never inherits the whole tour's
     * carrier list. An empty list is the honest answer.
     */
    fun confirmedBroadcasters(snapEntries: List<GuideEntry>, fixture: Fixture): List<String> {
        if (snapEntries.isEmpty()) return emptyList()
        val sides = participantsOf(fixture)
        if (sides != null) {
            val nearby = snapEntries.filter { timeMatches(it.dateMs, fixture.dateMs, ParticipantWindowMs) }
            val teamRows = nearby.filter { !it.gated && bothSidesInRow(rowText(it), sides.first, sides.second) }
            return teamRows.flatMap { it.channels }.distinct()
        }
        val eventTitle = fixture.title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: fixture.tournament?.trim().orEmpty()
        if (eventTitle.isBlank()) return emptyList()
        val nearby = snapEntries.filter { !it.gated && timeMatches(it.dateMs, fixture.dateMs, EventWindowMs) }
        val titled = nearby.filter { eventTokensInRow(rowText(it), eventTitle) }
        val session = sessionWordsIn(eventTitle)
        val usable = if (session.isEmpty()) {
            titled
        } else {
            // A practice fixture must not take the race row's channels because the event name
            // matches: accept the same session, or a row that names no session at all.
            titled.filter { row ->
                val rowSession = sessionWordsIn(rowText(row))
                rowSession.isEmpty() || rowSession.any { it in session }
            }
        }
        return usable.minByOrNull { kotlin.math.abs(it.dateMs - fixture.dateMs) }
            ?.channels?.distinct().orEmpty()
    }

    /**
     * Rows worth showing to the full-playlist model as context: nearby team rows (including gated
     * ones), the tennis tour's day row, and event-title rows. Never used as confirmation.
     */
    fun evidenceFor(snapEntries: List<GuideEntry>, fixture: Fixture): List<GuideEntry> {
        if (snapEntries.isEmpty()) return emptyList()
        val sides = participantsOf(fixture)
        if (sides != null) {
            val nearby = snapEntries.filter { timeMatches(it.dateMs, fixture.dateMs, EvidenceWindowMs) }
            val teamRows = nearby.filter { bothSidesInRow(rowText(it), sides.first, sides.second) }
            val tours = tourRows(snapEntries, fixture)
            return (teamRows + tours).distinctBy { Triple(it.dateMs, it.league, it.teams) }
                .sortedBy { kotlin.math.abs(it.dateMs - fixture.dateMs) }
        }
        val eventTitle = fixture.title?.trim().takeUnless { it.isNullOrEmpty() }
            ?: fixture.tournament?.trim().orEmpty()
        if (eventTitle.isBlank()) return emptyList()
        val session = sessionWordsIn(eventTitle)
        return snapEntries
            .filter { timeMatches(it.dateMs, fixture.dateMs, EvidenceWindowMs) }
            .filter { eventTokensInRow(rowText(it), eventTitle) }
            .filter { row ->
                session.isEmpty() || sessionWordsIn(rowText(row)).let { it.isEmpty() || it.any { w -> w in session } }
            }
            .sortedBy { kotlin.math.abs(it.dateMs - fixture.dateMs) }
    }

    /**
     * The tennis tour's day row. Kept for evidence only: one ATP/WTA row per day names the tour's
     * channels but cannot prove that a particular match is shown there.
     */
    private fun tourRows(entries: List<GuideEntry>, fixture: Fixture): List<GuideEntry> {
        val tour = when {
            fixture.leagueId.endsWith(".atp") -> "atp"
            fixture.leagueId.endsWith(".wta") -> "wta"
            else -> return emptyList()
        }
        return entries.filter {
            sameUtcDay(it.dateMs, fixture.dateMs) &&
                SportNameMatch.containsWord(tour, SportNameMatch.normalize(it.league))
        }
    }

    private fun sameUtcDay(firstMs: Long, secondMs: Long): Boolean =
        Instant.ofEpochMilli(firstMs).atZone(ZoneOffset.UTC).toLocalDate() ==
            Instant.ofEpochMilli(secondMs).atZone(ZoneOffset.UTC).toLocalDate()

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

    fun timeMatches(guideMs: Long, fixtureMs: Long, windowMs: Long = EventWindowMs): Boolean =
        guideMs > 0 && kotlin.math.abs(guideMs - fixtureMs) <= windowMs

    /**
     * Both participant names must token-overlap the row text: tokens after stripping generic club
     * affixes (fc/cf/afc/…) and generic club words (united/city/…), lowercased, diacritic-free,
     * plural-tolerant. Only a row naming both sides is this fixture.
     *
     * Widely used short spellings are accepted for a full club name, so "Tottenham" still means
     * Tottenham Hotspur, "Brighton" means Brighton & Hove Albion and "M'gladbach" means Borussia
     * Mönchengladbach. This is a fixed alias list, not a fuzzy prefix match: "Borussia" alone is
     * not Mönchengladbach.
     */
    fun bothSidesInRow(rowNorm: String, sideA: String, sideB: String): Boolean =
        sideA.isNotBlank() && sideB.isNotBlank() &&
            sideTokensHit(rowNorm, sideA) && sideTokensHit(rowNorm, sideB)

    private fun sideTokensHit(rowNorm: String, side: String): Boolean {
        val tokens = SportNameMatch.tokensForName(side, TeamSuffixes)
        if (tokens.isEmpty()) return false
        // "Newcastle United" is identified by "newcastle"; "united"/"city" are shared by dozens of
        // clubs and must not decide a side on their own. All remaining tokens must hit, so a row
        // naming "Manchester United" never stands in for "Manchester City".
        val required = tokens.filterNot { it in GenericTeamWords }.ifEmpty { tokens }
        if (required.all { tokenHits(rowNorm, it) }) return true
        if (ShortClubNames[SportNameMatch.normalize(side).trim()].orEmpty().none { alias ->
                SportNameMatch.tokensForName(alias, TeamSuffixes).ifEmpty { setOf(alias) }.all { tokenHits(rowNorm, it) }
            }
        ) {
            return false
        }
        return true
    }

    private fun tokenHits(rowNorm: String, token: String): Boolean =
        SportNameMatch.containsWord(token, rowNorm) ||
            SportNameMatch.containsWord(SportNameMatch.singularize(token).trim(), rowNorm)

    /**
     * Title/token overlap for fixtures without participants (F1, golf) and events. At least two
     * distinctive tokens must hit the row, one of them not a series word ("grand", "prix", "cup", …
     * every round of a series shares those). Session words are matched separately so a practice
     * fixture cannot claim the race's row.
     */
    fun eventTokensInRow(rowNorm: String, eventTitle: String): Boolean {
        val tokens = SportNameMatch.normalize(eventTitle).trim().split(' ')
            .filter { it.length >= MinEventTokenLength && it !in SessionWords }
        if (tokens.size < 2) return false
        val hits = tokens.filter { SportNameMatch.containsWord(it, rowNorm) }
        return hits.size >= 2 && hits.any { it !in SeriesWords }
    }

    /** The session labels ("race", "qualifying", …) a title or row text names, if any. */
    private fun sessionWordsIn(text: String): Set<String> =
        SportNameMatch.normalize(text).trim().split(' ').filter { it in SessionWords }.toSet()

    /** A row listing one kickoff: a row hours away is another event, not this fixture. */
    private const val ParticipantWindowMs = 90L * 60L * 1000L

    /** Session rows can sit a couple of hours off the fixture time (schedule vs broadcast start). */
    private const val EventWindowMs = 3L * 60L * 60L * 1000L

    /** Prompt evidence may cover the whole matchday, not only the kickoff window. */
    private const val EvidenceWindowMs = 12L * 60L * 60L * 1000L

    /** Fixed short spellings of full club names used by TV guides; never a fuzzy prefix rule. */
    private val ShortClubNames = mapOf(
        "tottenham hotspur" to listOf("tottenham"),
        "brighton hove albion" to listOf("brighton"),
        "borussia monchengladbach" to listOf("gladbach", "monchengladbach"),
    )

    /** Generic club affixes (after [SportNameMatch.tokensForName]'s length rule). */
    private val TeamSuffixes = setOf(
        "fc", "cf", "afc", "sc", "ac", "sk", "if", "bk", "club", "calcio",
    )

    /** Club words dozens of sides share — never enough to identify one side. */
    private val GenericTeamWords = setOf(
        "united", "city", "town", "rovers", "wanderers", "athletic", "sporting", "club", "sports",
    )

    /** Session words name a race-weekend session, not the event — never event tokens. */
    private val SessionWords = setOf(
        "race", "qualifying", "practice", "sprint", "warm", "final", "semifinal", "quarterfinal",
        "heat",
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
            // and the `<time>`-cell "sr-only" spans never leak in. Premier-gated rows carry no
            // real channels; they are kept as gated evidence, never as carriers.
            val cell = row.substringAfter(ChannelCellOpen, "")
            if (cell.isEmpty()) continue
            val gated = GatedMarkers.any { cell.contains(it, ignoreCase = true) }
            val channels = if (gated) {
                emptyList()
            } else {
                COVERAGE_ANCHOR.findAll(cell).map { decode(it.groupValues[1].trim()) }
                    .filterNot { isNotABroadcaster(it) }.toList().distinct()
            }
            if (channels.isEmpty() && !gated) continue
            out += GuideEntry(dateMs, league, teams, channels, gated = gated)
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
