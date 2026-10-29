package tv.own.owntv.features.sport

import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.Year
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Season calendars from Wikipedia (MediaWiki API, keyless) for the sports ESPN does not carry.
 *
 * Why: the Where's The Match rows only cover what a UK channel airs, so a worldwide calendar is the
 * missing half. Wikipedia has one page per season and sport (`2026 UCI World Tour`, `2026 Diamond
 * League`) whose rendered table lists **every** race and meeting with its date — the page title is
 * derived from the current year, so nothing goes stale.
 *
 * Dates only: neither page quotes start times, so an entry sits at noon UTC and shows no clock time.
 * The guide still contributes times and carriers for the events it lists (see [CalendarEvents.merge]).
 */
class WikipediaCalendarApi(private val client: OkHttpClient) {

    suspend fun cycling(year: Int): List<CalendarEvent> = withContext(Dispatchers.IO) {
        WikiCalendar.cycling(pageText("$year UCI World Tour"), year)
    }

    suspend fun athletics(year: Int): List<CalendarEvent> = withContext(Dispatchers.IO) {
        WikiCalendar.athletics(pageText("$year Diamond League"), year)
    }

    /** The rendered HTML of one page (`action=parse`), which keeps the tables the parsers expect. */
    private fun pageText(page: String): String {
        val url = ApiBase + "?action=parse&prop=text&format=json&formatversion=2&page=" +
            URLEncoder.encode(page, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header(UserAgentHeader, UserAgentValue)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Wikipedia HTTP " + response.code)
            return JSONObject(response.body.string()).getJSONObject("parse").getString("text")
        }
    }

    private companion object {
        const val ApiBase = "https://en.wikipedia.org/w/api.php"
        const val UserAgentHeader = "User-Agent"
        const val UserAgentValue = "Matchday/1 (Android TV IPTV app)"
    }
}

/**
 * Pure wiki-HTML → calendar entries. No Android, no IO.
 *
 * Both pages expose a plain `wikitable`: cycling with `Race | Date | Winner …`, athletics with
 * `Leg | Date | Meet | Stadium | City | Country …`. The calendar table is picked by its header
 * tokens, so navboxes and result tables on the same page are ignored.
 */
object WikiCalendar {

    /** One entry per race day: a three-week Grand Tour becomes a row per day, not one 21-day row. */
    fun cycling(html: String, year: Int): List<CalendarEvent> {
        val rows = tableRows(html, headerTokens = listOf("Race", "Date")) ?: return emptyList()
        val out = ArrayList<CalendarEvent>()
        for (cells in rows) {
            if (cells.size < 2) continue
            val name = cells[0].takeIf { it.isNotBlank() && it != "Race" } ?: continue
            val dates = dateRange(cells[1], year) ?: continue
            out += days(name, dates.first, dates.second)
        }
        return out.distinctBy { it.slug }.sortedBy { it.startMs }
    }

    fun athletics(html: String, year: Int): List<CalendarEvent> {
        val rows = tableRows(html, headerTokens = listOf("Meet", "Date")) ?: return emptyList()
        val out = ArrayList<CalendarEvent>()
        for (cells in rows) {
            if (cells.size < 3) continue
            val date = cells[1].takeIf { it != "Date" } ?: continue
            val meet = cells[2].takeIf { it.isNotBlank() && it != "Meet" } ?: continue
            val city = cells.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { ", " + it }.orEmpty()
            val range = dateRange(date, year) ?: continue
            out += days(meet + city, range.first, range.second)
        }
        return out.distinctBy { it.slug }.sortedBy { it.startMs }
    }

    private fun days(name: String, start: LocalDate, end: LocalDate): List<CalendarEvent> =
        generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .take(MaxDaysPerEvent)
            .map { day ->
                CalendarEvent(
                    slug = slugOf(name, day),
                    title = name,
                    startMs = day.atTime(NoonUtc).toInstant(ZoneOffset.UTC).toEpochMilli(),
                    hasTime = false,
                    venue = null,
                    carriers = emptyList(),
                )
            }.toList()

    /** "20–25 January", "1 February", "16 May" → first and last day in [year]. */
    private fun dateRange(text: String, year: Int): Pair<LocalDate, LocalDate>? {
        val m = DatePattern.find(clean(text)) ?: return null
        val firstDay = m.groupValues[1].toIntOrNull() ?: return null
        val lastDay = m.groupValues[2].ifBlank { m.groupValues[1] }.toIntOrNull() ?: firstDay
        val month = Month.entries.firstOrNull { it.name.startsWith(m.groupValues[3].uppercase(Locale.ROOT)) }
            ?: return null
        val start = runCatching { LocalDate.of(year, month, firstDay) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.of(year, month, lastDay) }.getOrNull() ?: start
        return start to end
    }

    /**
     * The first table whose header row contains every [headerTokens] word, as a list of cell texts.
     * Header cells are returned too (the callers skip them by name), which keeps the filter simple.
     */
    private fun tableRows(html: String, headerTokens: List<String>): List<List<String>>? {
        for (table in html.split(TableOpen).drop(1)) {
            val body = table.substringBefore(TableClose)
            val rows = RowOpen.split(body).drop(1).map { row ->
                Cell.findAll(row).map { clean(it.groupValues[1]) }.toList()
            }.filter { it.isNotEmpty() }
            val header = rows.firstOrNull()?.joinToString(" ") ?: continue
            if (headerTokens.all { header.contains(it) }) return rows
        }
        return null
    }

    private fun slugOf(name: String, day: LocalDate): String =
        SportNameMatch.normalize(name).replace(' ', '-').take(MaxSlugChars) + "-" + day

    private fun clean(html: String): String =
        html.replace(TagRegex, " ")
            .replace("&#160;", " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&#91;", "[")
            .replace("&#93;", "]")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace(WhitespaceRegex, " ")
            .trim()

    /** Cycling league id — the board switches to this source for it (see `SportsViewModel.loadBoard`). */
    const val CyclingLeagueId = "cycling.uci"

    /** Athletics league id — the board switches to this source for it (see `SportsViewModel.loadBoard`). */
    const val AthleticsLeagueId = "athletics.world"

    /** A stage race or a long meeting never needs more rows than this. */
    private const val MaxDaysPerEvent = 23
    private const val MaxSlugChars = 32
    private val NoonUtc = LocalTime.NOON
    private val TableOpen = Regex("<table")
    private const val TableClose = "</table>"
    private val RowOpen = Regex("<tr")
    private val Cell = Regex("<t[dh][^>]*>([\\s\\S]*?)</t[dh]>")
    private val TagRegex = Regex("<[^>]*>")
    private val WhitespaceRegex = Regex("\\s+")
    private val DatePattern = Regex("""(\d{1,2})\s*(?:[–-]\s*(\d{1,2}))?\s+([A-Za-z]+)""")

    /** Current season, so callers do not repeat the year plumbing. */
    fun currentYear(): Int = Year.now().value
}
