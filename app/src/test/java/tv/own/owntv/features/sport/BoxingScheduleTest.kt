package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FightNights HTML → boxing cards: the card anchor carries the fight title, the gold date line the
 * quoted start time (ET/UK) and the broadcaster, the geo line the venue. The merge with the guide's
 * UK rows lives in `CalendarEvents` and is covered by `CalendarEventsTest`.
 */
class BoxingScheduleTest {

    private val html = """
        <a href="/Julian-Vogel-vs-Bakari-Diallo-3876" class="text-decoration-none d-block mb-3">
          <h2 class="h5 fw-bold text-white mb-1">Julian Vogel vs Bakari Diallo</h2>
          <div class="fw-bold small mb-2" style="color:#d4a017;">FRI, OCT 30, 2026 - GERMANY MDR CHECK LOCAL LISTINGS</div>
          <div class="mb-1 small"><i class="bi bi-geo-alt-fill me-2"></i>SES-Box-Gala in Sachsen-Anhalt, Ballhaus Arena, Germany</div>
        </a>
        <a href="/Mbilli-vs-Canelo-WBC-World-Super-Middleweight-3838" class="text-decoration-none d-block mb-3">
          <h2 class="h5 fw-bold text-white mb-1">Mbilli vs Canelo</h2>
          <div class="fw-bold small mb-2" style="color:#d4a017;">SAT, OCT 31, 2026 - 10AM ET • 9AM CT • 7AM PT • 2PM UK Riyadh, Saudi Arabia ${'$'}79.99 DAZN</div>
          <div class="mb-1 small"><i class="bi bi-geo-alt-fill me-2"></i>Riyadh, Saudi Arabia</div>
        </a>
    """.trimIndent()

    @Test
    fun cardsCarryTitleTimeVenueAndCarrier() {
        val cards = BoxingSchedule.parse(html)
        assertEquals(2, cards.size)

        val german = cards[0]
        assertEquals("Julian Vogel vs Bakari Diallo", german.title)
        assertEquals("SES-Box-Gala in Sachsen-Anhalt, Ballhaus Arena, Germany", german.venue)
        assertFalse("the listing quotes no time here", german.hasTime)
        assertEquals(listOf("MDR"), german.carriers)

        val saudi = cards[1]
        assertEquals("Mbilli vs Canelo", saudi.title)
        assertTrue(saudi.hasTime)
        assertEquals(listOf("DAZN"), saudi.carriers)
        // 10AM ET on 31 October is 14:00 UTC (EDT, UTC-4).
        assertEquals(java.time.Instant.parse("2026-10-31T14:00:00Z").toEpochMilli(), saudi.startMs)
    }

    @Test
    fun fixturesAreEventRowsWithStatesAndStableIds() {
        val cards = BoxingSchedule.parse(html)
        val saudiStart = cards[1].startMs

        val upcoming = CalendarEvents.fixtures(
            BoxingSchedule.BoxingLeagueId, cards, saudiStart - 60_000L, BoxingSchedule.CardDurationMs,
        )
        assertEquals(FixtureState.UPCOMING, upcoming[1].state)
        assertEquals(FixtureFormat.EVENT, upcoming[1].format)
        assertEquals("boxing.world-Mbilli-vs-Canelo-WBC-World-Super-Middleweight-3838", upcoming[1].id)
        assertEquals("boxing.world", upcoming[1].leagueId)
        assertEquals(listOf("DAZN"), upcoming[1].broadcasts)
        assertTrue(upcoming[1].statusLine.matches(Regex("\\d{2}:\\d{2}")))
        // No quoted time: no time is shown rather than a made-up one.
        assertEquals("", upcoming[0].statusLine)
    }

    @Test
    fun pagesWithoutCardsYieldNothingInsteadOfThrowing() {
        assertEquals(emptyList<CalendarEvent>(), BoxingSchedule.parse("<html><body>Blocked</body></html>"))
    }
}
