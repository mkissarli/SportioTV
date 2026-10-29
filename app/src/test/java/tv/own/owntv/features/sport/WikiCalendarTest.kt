package tv.own.owntv.features.sport

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Wikipedia season tables → calendar entries: cycling races (multi-day ranges expand to one row per
 * day) and Diamond League meetings, with navboxes and result tables ignored.
 */
class WikiCalendarTest {

    private val navbox = """
        <table class="navbox"><tr><td>v t e 2026 UCI World Tour</td></tr></table>
    """.trimIndent()

    private val cyclingTable = """
        <table class="wikitable plainrowheaders">
        <tr><th>Race</th><th>Date</th><th>Winner</th></tr>
        <tr><td>Tour Down Under</td><td>20–25 January</td><td>Jay Vine</td></tr>
        <tr><td>Cadel Evans Great Ocean Road Race</td><td>1 February</td><td>Tobias Lund Andresen</td></tr>
        </table>
    """.trimIndent()

    private val athleticsTable = """
        <table class="wikitable sortable">
        <tr><th>Leg</th><th>Date</th><th>Meet</th><th>Stadium</th><th>City</th><th>Country</th></tr>
        <tr><td>1</td><td>16 May</td><td>Shanghai Diamond League</td><td>Textile City Sports Center</td><td>Shaoxing</td><td>China</td></tr>
        <tr><td>2</td><td>23 May</td><td>Xiamen Diamond League</td><td>Xiamen Egret Stadium</td><td>Xiamen</td><td>China</td></tr>
        </table>
    """.trimIndent()

    @Test
    fun aStageRaceExpandsToOneEntryPerDay() {
        val events = WikiCalendar.cycling(navbox + cyclingTable, 2026)

        // Tour Down Under: 20–25 January = 6 days, plus the one-day race.
        assertEquals(7, events.size)
        assertEquals("Tour Down Under", events.first().title)
        assertEquals(Instant.parse("2026-01-20T12:00:00Z").toEpochMilli(), events.first().startMs)
        assertFalse("Wikipedia carries dates only", events.first().hasTime)
        assertEquals(emptyList<String>(), events.first().carriers)
        assertEquals("Cadel Evans Great Ocean Road Race", events.last().title)
        assertEquals(Instant.parse("2026-02-01T12:00:00Z").toEpochMilli(), events.last().startMs)
        assertEquals(events.size, events.map { it.slug }.distinct().size)
    }

    @Test
    fun diamondLeagueMeetingsCarryTheMeetNameAndCity() {
        val events = WikiCalendar.athletics(athleticsTable, 2026)

        assertEquals(2, events.size)
        assertEquals("Shanghai Diamond League, Shaoxing", events.first().title)
        assertEquals(Instant.parse("2026-05-16T12:00:00Z").toEpochMilli(), events.first().startMs)
        assertEquals("Xiamen Diamond League, Xiamen", events.last().title)
    }

    @Test
    fun aPageWithNoCalendarTableYieldsNothing() {
        assertEquals(emptyList<CalendarEvent>(), WikiCalendar.cycling(navbox, 2026))
        assertEquals(emptyList<CalendarEvent>(), WikiCalendar.athletics(navbox, 2026))
    }
}
