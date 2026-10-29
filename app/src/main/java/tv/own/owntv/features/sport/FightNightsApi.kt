package tv.own.owntv.features.sport

import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Boxing calendar from FightNights (fightnights.com/upcoming-boxing-schedule) — keyless, worldwide,
 * no sign-up.
 *
 * Why this exists: ESPN has no boxing league (404), and every fight database (BoxRec, Tapology,
 * SofaScore, BoxingScene) blocks scraping with Cloudflare. FightNights lists one card per entry with
 * its date, quoted US/UK start time, venue and broadcaster, which is exactly the hub's fixture shape.
 */
class FightNightsApi(private val client: OkHttpClient) {

    /** The upcoming cards; the boxing board merges the guide's rows into these. */
    suspend fun cards(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        BoxingSchedule.parse(get(ScheduleUrl))
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(UserAgentHeader, UserAgentValue)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("FightNights HTTP " + response.code)
            return response.body.string()
        }
    }

    private companion object {
        const val ScheduleUrl = "https://fightnights.com/upcoming-boxing-schedule"
        const val UserAgentHeader = "User-Agent"
        const val UserAgentValue = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Matchday/1"
    }
}

/**
 * Pure FightNights HTML → boxing cards. No Android, no IO, unit-testable on the JVM.
 *
 * Each card is one entry titled "Fighter A vs Fighter B". The card's broadcaster ("DAZN", "Netflix",
 * …) becomes the event's carrier list, so the normal matcher can rank the user's channels for it;
 * the guide then adds whatever UK listing it has.
 */
object BoxingSchedule {

    fun parse(html: String): List<CalendarEvent> {
        val anchors = EventAnchor.findAll(html).toList()
        val out = ArrayList<CalendarEvent>()
        anchors.forEachIndexed { index, anchor ->
            val segment = html.substring(
                anchor.range.last + 1,
                anchors.getOrNull(index + 1)?.range?.first ?: html.length,
            )
            val slug = anchor.groupValues[1].trim('/').takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val title = TitleTag.find(segment)?.let { plain(it.groupValues[1]) }?.takeIf { it.isNotBlank() }
                ?: return@forEachIndexed
            val dateLine = DateLine.find(segment)?.let { plain(it.groupValues[1]) } ?: return@forEachIndexed
            val date = parseDate(dateLine) ?: return@forEachIndexed
            val time = parseTime(date, dateLine)
            out += CalendarEvent(
                slug = slug,
                title = title,
                startMs = time ?: date.atTime(NoonUtc).toInstant(ZoneOffset.UTC).toEpochMilli(),
                hasTime = time != null,
                venue = VenueIcon.find(segment)?.let { plain(it.groupValues[1]) }?.takeIf { it.isNotBlank() },
                carriers = carriersIn(segment),
            )
        }
        return out.distinctBy { it.slug }.sortedBy { it.startMs }
    }

    /** "SAT, OCT 31, 2026 - 10AM ET • …" → the date part only. */
    private fun parseDate(dateLine: String): LocalDate? {
        val m = DatePattern.find(dateLine) ?: return null
        val month = Month.entries.firstOrNull { it.name.startsWith(m.groupValues[2].uppercase()) } ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null
        val year = m.groupValues[4].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** "10AM ET" / "2PM UK" → epoch millis in the quoted zone; null when the listing has no time. */
    private fun parseTime(date: LocalDate, dateLine: String): Long? {
        val m = TimePattern.find(dateLine) ?: return null
        val hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: 0
        val zone = when (m.groupValues[4].uppercase()) {
            "ET" -> ZoneId.of("America/New_York")
            "UK" -> ZoneId.of("Europe/London")
            else -> return null
        }
        val hour24 = when {
            m.groupValues[3].uppercase() == "AM" -> if (hour == 12) 0 else hour
            hour == 12 -> 12
            else -> hour + 12
        }
        return runCatching {
            date.atTime(LocalTime.of(hour24, minute)).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    /** Broadcasters the card lists; only used as a matching signal, so the list stays short. */
    private fun carriersIn(segment: String): List<String> {
        val text = plain(segment).lowercase()
        return KnownCarriers.filter { text.contains(it.lowercase()) }
    }

    private fun plain(html: String): String =
        html.replace(TagRegex, " ")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(WhitespaceRegex, " ")
            .trim()

    /** Boxing league id — the board switches to this source for it (see `SportsViewModel.loadBoard`). */
    const val BoxingLeagueId = "boxing.world"

    /** A card runs long (walk-ins, undercard, main event): a wide live window beats flicker. */
    const val CardDurationMs = 6L * 60L * 60L * 1000L

    private val EventAnchor = Regex("""<a href="([^"]+)" class="text-decoration-none d-block mb-3">""")
    private val TitleTag = Regex("""<h2[^>]*>([\s\S]*?)</h2>""")
    private val DateLine = Regex("""fw-bold small mb-2"[^>]*>([\s\S]*?)</div>""")
    private val VenueIcon = Regex("""bi-geo-alt-fill[^>]*></i>([\s\S]*?)</div>""")
    private val DatePattern = Regex("""([A-Za-z]{3}),\s*([A-Za-z]{3})\s+(\d{1,2}),\s+(\d{4})""")
    private val TimePattern = Regex("""(\d{1,2})(?::(\d{2}))?\s*(AM|PM)\s*(ET|UK)""", RegexOption.IGNORE_CASE)
    private val TagRegex = Regex("<[^>]*>")
    private val WhitespaceRegex = Regex("\\s+")
    private val KnownCarriers = listOf(
        "DAZN", "Netflix", "ESPN", "Sky Sports", "TNT Sports", "Amazon Prime", "Paramount+",
        "ProBox TV", "MDR",
    )
    private val NoonUtc = LocalTime.of(12, 0)
}
