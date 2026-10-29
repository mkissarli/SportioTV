package tv.own.owntv.features.sport

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One calendar entry before it becomes a [Fixture]: a fight card, a bike race or an athletics
 * meeting. Each source (FightNights, Wikipedia) normalises into this, so merging with the guide and
 * building the board happen once.
 */
data class CalendarEvent(
    /** Stable, source-unique id fragment (a site slug, or the normalised name + day). */
    val slug: String,
    val title: String,
    /** Start of the entry; the date at noon UTC when the source quotes no time. */
    val startMs: Long,
    /** Whether [startMs] carries a real time-of-day (Wikipedia calendars do not). */
    val hasTime: Boolean,
    val venue: String?,
    val carriers: List<String>,
)

/**
 * Shared calendar work for the sports without an ESPN league: merge the Where's The Match rows into
 * a real calendar, then build `EVENT` fixtures.
 *
 * The sources overlap (a world-title card, a Tour stage) but never line up perfectly, so a guide row
 * that names the same entry within a few hours contributes its channels to the calendar entry, and a
 * row nobody else lists becomes an entry of its own: an extra row beats a missing event.
 */
object CalendarEvents {

    /** A guide row and a calendar entry sit a few hours apart (TV coverage start vs event start). */
    private const val SameEventWindowMs = 8L * 60L * 60L * 1000L
    private const val MinNameToken = 4
    private val TokenSplit = Regex("[^\\p{L}\\p{N}]+")

    /** Words that name the format, not the event — they must never decide a match. */
    private val NameStopwords = setOf(
        "live", "boxing", "fight", "title", "world", "interim", "vs", "the", "and", "at", "in",
        "final", "race", "stage", "session", "round",
    )

    private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun merge(events: List<CalendarEvent>, guide: List<GuideEntry>): List<CalendarEvent> {
        val merged = events.toMutableList()
        val extra = ArrayList<CalendarEvent>()
        for (entry in guide) {
            if (entry.dateMs <= 0 || entry.teams.isBlank()) continue
            val hit = merged.indexOfFirst { sameEvent(it, entry) }
            if (hit >= 0) {
                val event = merged[hit]
                merged[hit] = event.copy(carriers = (event.carriers + entry.channels).distinct())
                continue
            }
            if (extra.any { sameEvent(it, entry) }) continue
            extra += CalendarEvent(
                slug = SportNameMatch.normalize(entry.teams).replace(' ', '-').take(MaxSlugChars),
                title = entry.teams,
                startMs = entry.dateMs,
                hasTime = true,
                venue = null,
                carriers = entry.channels,
            )
        }
        return (merged + extra).sortedBy { it.startMs }
    }

    fun fixtures(leagueId: String, events: List<CalendarEvent>, nowMs: Long, durationMs: Long): List<Fixture> =
        events.map { event ->
            val state = when {
                nowMs < event.startMs -> FixtureState.UPCOMING
                nowMs < event.startMs + durationMs -> FixtureState.LIVE
                else -> FixtureState.FINISHED
            }
            Fixture(
                id = leagueId + "-" + event.slug,
                leagueId = leagueId,
                dateMs = event.startMs,
                state = state,
                statusLine = if (event.hasTime) localTimeOf(event.startMs) else "",
                format = FixtureFormat.EVENT,
                title = event.title,
                home = null,
                away = null,
                broadcasts = event.carriers,
                channelHints = emptyList(),
                venue = event.venue,
            )
        }

    private fun sameEvent(event: CalendarEvent, entry: GuideEntry): Boolean =
        kotlin.math.abs(event.startMs - entry.dateMs) <= SameEventWindowMs &&
            nameTokens(event.title).any { it in nameTokens(entry.teams) }

    private fun nameTokens(text: String): Set<String> =
        text.lowercase().split(TokenSplit)
            .filter { it.length >= MinNameToken && it !in NameStopwords }
            .toSet()

    private fun localTimeOf(epochMs: Long): String =
        ClockFormat.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    private const val MaxSlugChars = 40
}
