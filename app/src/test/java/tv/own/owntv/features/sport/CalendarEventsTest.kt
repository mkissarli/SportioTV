package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Calendar + guide merge: the same event within a few hours takes the guide's channels, a guide row
 * nobody else lists becomes an entry of its own, and an unrelated same-name event stays separate.
 */
class CalendarEventsTest {

    private val start = 1_800_000_000_000L

    private fun event(slug: String, title: String, startMs: Long) =
        CalendarEvent(slug, title, startMs, hasTime = false, venue = null, carriers = emptyList())

    private fun row(teams: String, startMs: Long, channels: List<String>) =
        GuideEntry(startMs, "Boxing", teams, channels)

    @Test
    fun theGuideAddsChannelsToTheEventItNames() {
        val merged = CalendarEvents.merge(
            events = listOf(event("mbilli-canelo", "Mbilli vs Canelo", start)),
            guide = listOf(row("Live Boxing Canelo Alvarez vs Christian M'billi", start - 2L * 60L * 60L * 1000L, listOf("DAZN"))),
        )

        assertEquals(1, merged.size)
        assertEquals(listOf("DAZN"), merged.single().carriers)
        assertEquals(start, merged.single().startMs)
    }

    @Test
    fun aGuideOnlyRowBecomesItsOwnEntry() {
        val merged = CalendarEvents.merge(
            events = listOf(event("tour", "Tour de France", start)),
            guide = listOf(row("CycloCross Superprestige Elite Men's Race - Oudenaarde", start, listOf("TNT Sports 3"))),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("TNT Sports 3"), merged.last().carriers)
    }

    @Test
    fun theSameNameOnAnotherDayStaysSeparate() {
        val merged = CalendarEvents.merge(
            events = listOf(event("race", "Tour de France", start)),
            guide = listOf(row("Tour de France Stage 12", start + 24L * 60L * 60L * 1000L, listOf("ITV4"))),
        )

        assertEquals(2, merged.size)
    }
}
