package tv.own.owntv.features.sport

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.own.owntv.R

/** One scraped listing: kickoff, league name, fixture text ("A v B" or a tournament), channels. */
data class GuideEntry(val dateMs: Long, val league: String, val teams: String, val channels: List<String>)

/**
 * UK TV-guide confirmation layer: which broadcaster actually carries a fixture, scraped from
 * Where's The Match (football + tennis pages) and Live Football On TV. ESPN's own "broadcast"
 * field is US-centric, so a Premier League game would never name Sky Sports; these pages do.
 *
 * The guide entries are matched against ESPN fixtures by league + teams + kickoff time, and the
 * confirmed broadcaster names ("Sky Sports Premier League") are then probed against the user's
 * channel catalogue — evidence from an independent source, unlike the old name-only tiers.
 *
 * Cached in memory for [CacheTtlMs]; every fetch is defensive — a page that changed shape yields
 * nothing, never a crash, and the matcher falls back to its EPG-only behaviour.
 */
class BroadcastGuideApi(private val client: OkHttpClient, context: Context) {

    private val appContext = context.applicationContext

    /** The last successful guide snapshot — read synchronously, refreshed in the background. */
    @Volatile
    private var entries: List<GuideEntry> = emptyList()
    private var refreshedAtMs = 0L
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Whether a background refresh is worth starting (never decide in the UI path). */
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - refreshedAtMs >= CacheTtlMs

    /**
     * The guide channels that (probably) carry [fixture]. Never fetches: reads the last snapshot,
     * so fixture selection is instant whether or not a refresh has finished yet.
     */
    fun confirmedBroadcasters(fixture: Fixture): List<String> {
        val league = sportsLeagueById(fixture.leagueId) ?: return emptyList()
        val label = appContext.getString(league.labelRes)
        val home = fixture.home?.name.orEmpty()
        val away = fixture.away?.name.orEmpty()
        val tournament = fixture.tournament.orEmpty()
        return entries.filter { entry ->
            GuideMatch.leagueMatches(entry.league, label) &&
                GuideMatch.timeMatches(entry.dateMs, fixture.dateMs) &&
                GuideMatch.teamsMatch(entry.teams, home, away, tournament)
        }.flatMap { it.channels }.distinct()
    }

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

    private suspend fun refreshOnce() {
        val merged = withContext(Dispatchers.IO) {
            val found = ArrayList<GuideEntry>()
            runCatching { get(FootballGuideUrl) }.getOrNull()?.let { html -> runCatching { GuideParsers.wtm(html) }.getOrNull()?.let { found += it } }
            runCatching { get(TennisGuideUrl) }.getOrNull()?.let { html -> runCatching { GuideParsers.wtm(html) }.getOrNull()?.let { found += it } }
            runCatching { get(LiveFootballUrl) }.getOrNull()?.let { html -> runCatching { GuideParsers.liveFootball(html) }.getOrNull()?.let { found += it } }
            found.groupBy { Triple(it.dateMs, it.league, it.teams) }
                .map { (key, group) -> GuideEntry(key.first, key.second, key.third, group.flatMap { it.channels }.distinct()) }
        }
        if (merged.isNotEmpty()) {
            entries = merged
            refreshedAtMs = System.currentTimeMillis()
        }
    }

    private fun get(url: String): String {
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
        const val FootballGuideUrl = "https://www.wheresthematch.com/live-football-on-tv/"
        const val TennisGuideUrl = "https://www.wheresthematch.com/live-tennis-on-tv/"
        const val LiveFootballUrl = "https://www.live-footballontv.com/"
        const val UserAgentHeader = "User-Agent"
        const val UserAgentValue = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Matchday/1"
        const val AcceptLanguageHeader = "Accept-Language"
        const val AcceptLanguageValue = "en-GB,en;q=0.9"
        const val CacheTtlMs = 4L * 60L * 60L * 1000L
        const val MaxPageBytes = 4L * 1024L * 1024L
    }
}

/** Pure guide ↔ fixture matching (league, kickoff time, teams/tournament). */
object GuideMatch {

    fun leagueMatches(guide: String, fixture: String): Boolean {
        val g = SportNameMatch.normalize(guide)
        val f = SportNameMatch.normalize(fixture)
        if (g.isBlank() || f.isBlank()) return false
        // "Champions League" inside "UEFA Champions League" and vice versa.
        return g == f || g in f || f in g
    }

    fun timeMatches(guideMs: Long, fixtureMs: Long): Boolean =
        guideMs > 0 && kotlin.math.abs(guideMs - fixtureMs) <= MaxTimeDeltaMs

    fun teamsMatch(guideTeams: String, home: String, away: String, tournament: String): Boolean {
        val g = SportNameMatch.normalize(guideTeams)
        if (g.isBlank()) return false
        if (home.isBlank() && away.isBlank()) {
            // Duel/tournament fixture: the guide names the tournament ("ATP Lyon").
            val t = SportNameMatch.normalize(tournament)
            return t.isNotBlank() && (g.contains(t) || t.contains(g))
        }
        val h = SportNameMatch.normalize(home)
        val a = SportNameMatch.normalize(away)
        if (h.isBlank() || a.isBlank()) return false
        return (g.contains(h) || h.contains(g)) && (g.contains(a) || a.contains(g))
    }

    private const val MaxTimeDeltaMs = 3L * 60L * 60L * 1000L
}

/** HTML parsers for the guide pages — pure string work, unit-testable without Android. */
object GuideParsers {

    // --- Where's The Match: one <tr> per match, channels in "TV coverage on X" aria-labels -------

    fun wtm(html: String): List<GuideEntry> {
        val out = ArrayList<GuideEntry>()
        for (row in ROW_SPLIT.split(html).drop(1)) {
            val time = TIME_DATETIME.find(row)?.groupValues?.get(1) ?: continue
            val dateMs = runCatching { OffsetDateTime.parse(time).toInstant().toEpochMilli() }.getOrDefault(0L)
            if (dateMs <= 0) continue
            // Football rows read "TeamA v TeamB"; tennis rows carry the tournament text instead
            // ("ATP Lyon / ATP Almaty") — both go into `teams`, the matcher handles either shape.
            val teams = FIXTURE_BLOCK.find(row)?.let { m -> decode(stripTags(m.groupValues[1])) } ?: continue
            if (teams.isBlank()) continue
            val league = COMPETITION_SPAN.find(row)?.groupValues?.get(1)?.trim() ?: ""
            val channels = COVERAGE_ANCHOR.findAll(row).map { decode(it.groupValues[1].trim()) }.toList()
            if (channels.isEmpty()) continue
            out += GuideEntry(dateMs, league, teams, channels.distinct())
        }
        return out
    }

    // --- Live Football On TV: div.fixture blocks under div.fixture-date headers -------------------

    fun liveFootball(html: String): List<GuideEntry> {
        val out = ArrayList<GuideEntry>()
        // Walk the document in order: date headers set the day, time blocks carry one fixture each
        // (everything up to the next date/time marker).
        // One shared java.util.regex matcher: Kotlin's Regex.findAll re-creates a matcher per match,
        // and on Android each of those copies the whole page (gigabytes on a real guide page).
        val marks = ArrayList<Marker>()
        val matcher = MARKER.toPattern().matcher(html)
        while (matcher.find()) marks += Marker(matcher.end(), matcher.group(1), matcher.group(2))
        var day: LocalDate? = null
        for (i in marks.indices) {
            val marker = marks[i]
            val dateText = marker.date
            if (dateText != null) {
                runCatching {
                    LocalDate.parse(stripOrdinal(dateText.trim()), DayFormat)
                }.getOrNull()?.let { day = it }
                continue
            }
            val currentDay = day ?: continue
            val time = marker.time?.trim() ?: continue
            val end = marks.getOrNull(i + 1)?.start ?: html.length
            val block = html.substring(marker.start, end)
            val teams = BLOCK_TEAMS.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val league = BLOCK_COMPETITION.find(block)?.groupValues?.get(1)?.trim() ?: ""
            val channels = CHANNEL_PILL.findAll(block).map { decode(it.groupValues[1].trim()) }.toList()
            if (channels.isEmpty()) continue
            val dateMs = runCatching {
                currentDay.atTime(LocalTime.parse(time, TimeFormat))
                    .atZone(UkZone)
                    .toInstant()
                    .toEpochMilli()
            }.getOrDefault(0L)
            if (dateMs <= 0) continue
            out += GuideEntry(dateMs, league, decode(teams), channels.distinct())
        }
        return out
    }

    private fun stripTags(text: String): String = text.replace(TagRegex, " ").replace(WhitespaceRegex, " ").trim()

    private class Marker(val start: Int, val date: String?, val time: String?)

    private fun stripOrdinal(text: String): String = text.replace(OrdinalRegex, "$1")

    private fun decode(text: String): String =
        text.replace("&amp;", "&").replace("&nbsp;", " ").replace("&#39;", "'").trim()

    private val TagRegex = Regex("<[^>]*>")
    private val WhitespaceRegex = Regex("\\s+")
    private val OrdinalRegex = Regex("(\\d)(st|nd|rd|th)\\b")
    private val ROW_SPLIT = Regex("<tr")
    private val TIME_DATETIME = Regex("<time[^>]*datetime=\"([^\"]+)\"")
    private val FIXTURE_BLOCK = Regex("<span class=\"fixture\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
    private val COMPETITION_SPAN = Regex("<td class=\"competition-name\"[^>]*>.*?<span>([^<]+)</span>", RegexOption.DOT_MATCHES_ALL)
    private val COVERAGE_ANCHOR = Regex("aria-label=\"[^\"]*TV coverage on ([^\"]+)\"")

    private val MARKER = Regex("<div class=\"fixture-date\">([^<]+)</div>|<div class=\"fixture__time\">([^<]+)</div>")
    private val BLOCK_TEAMS = Regex("<div class=\"fixture__teams\">([^<]+)</div>")
    private val BLOCK_COMPETITION = Regex("<div class=\"fixture__competition\">([^<]+)</div>")
    private val CHANNEL_PILL = Regex("class=\"channel-pill\"[^>]*>([^<]+)</span>")

    private val DayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.UK)
    private val TimeFormat = DateTimeFormatter.ofPattern("H:mm")
    private val UkZone = ZoneId.of("Europe/London")
}
