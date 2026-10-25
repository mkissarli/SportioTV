package tv.own.owntv.features.sport

import java.time.ZonedDateTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure Jolpica → fixture mapping for the F1 league: calendar parsing, session times from the
 * round detail, and the EVENT fixtures per session with their live/upcoming/finished state. No
 * Android, no IO.
 */
class F1ScheduleTest {

    private val calendarJson = JSONObject(
        """
        {"MRData":{"RaceTable":{"Races":[
          {"round":"18","raceName":"United States Grand Prix","date":"2026-10-25","time":"20:00:00Z",
           "Circuit":{"Location":{"locality":"Austin"}}}
        ]}}}
        """,
    )

    private val detailJson = JSONObject(
        """
        {"MRData":{"RaceTable":{"Races":[
          {"round":"18","raceName":"United States Grand Prix","date":"2026-10-25","time":"20:00:00Z",
           "Circuit":{"Location":{"locality":"Austin"}},
           "FirstPractice":{"date":"2026-10-23","time":"17:30:00Z"},
           "Sprint":{"date":"2026-10-24","time":"17:00:00Z"},
           "Qualifying":{"date":"2026-10-24","time":"21:00:00Z"}}
        ]}}}
        """,
    )

    @Test
    fun theCalendarCarriesTheRaceSession() {
        val rounds = F1Schedule.parseCalendar(calendarJson)
        assertEquals(1, rounds.size)
        val round = rounds.single()
        assertEquals("18", round.round)
        assertEquals("United States Grand Prix", round.raceName)
        assertEquals("Austin", round.locality)
        assertEquals(listOf("race"), round.sessions.map { it.key })
        assertEquals(utc("2026-10-25T20:00:00Z"), round.raceStartMs)
    }

    @Test
    fun theRoundDetailSuppliesPracticeSprintAndQualifying() {
        val round = F1Schedule.parseRoundDetail(detailJson)
        // fp1, sprint, qualifying, then the race itself — sorted by start time.
        assertEquals(listOf("fp1", "sprint", "qualifying", "race"), round.sessions.map { it.key })
        assertEquals(utc("2026-10-23T17:30:00Z"), round.sessions[0].startMs)
        assertEquals(utc("2026-10-25T20:00:00Z"), round.raceStartMs)
    }

    @Test
    fun fixturesBecomeEventRowsWithCorrectStatesAndTitles() {
        val round = F1Schedule.parseRoundDetail(detailJson)
        val now = utc("2026-10-24T17:10:00Z") // during the sprint
        val fixtures = F1Schedule.fixtures(listOf(round), now)

        assertEquals(4, fixtures.size)
        assertEquals(listOf(FixtureState.FINISHED, FixtureState.LIVE, FixtureState.UPCOMING, FixtureState.UPCOMING), fixtures.map { it.state })
        assertEquals("United States Grand Prix — Practice 1", fixtures[0].title)
        assertEquals("United States Grand Prix — Sprint", fixtures[1].title)
        assertEquals("United States Grand Prix", fixtures[3].title)
        assertEquals(emptyList<String>(), fixtures[1].channelHints)
        assertEquals("Austin", fixtures[1].venue)
        assertEquals("f1-18-sprint", fixtures[1].id)
        assertTrue(fixtures[1].statusLine.matches(Regex("\\d{2}:\\d{2}")))
    }

    @Test
    fun detailIsRequestedOnlyForThisAndTheNextWeekends() {
        val round = F1Schedule.parseCalendar(calendarJson).single()
        val raceStart = round.raceStartMs
        assertTrue(F1Schedule.needsDetail(round, raceStart - 2L * 24L * 60L * 60L * 1000L))
        assertTrue(F1Schedule.needsDetail(round, raceStart + 2L * 24L * 60L * 60L * 1000L))
        assertTrue(F1Schedule.needsDetail(round, raceStart - 10L * 24L * 60L * 60L * 1000L))
        assertTrue(!F1Schedule.needsDetail(round, raceStart + 20L * 24L * 60L * 60L * 1000L))
        assertTrue(!F1Schedule.needsDetail(round, raceStart - 30L * 24L * 60L * 60L * 1000L))
    }

    private fun utc(iso: String): Long = ZonedDateTime.parse(iso).toInstant().toEpochMilli()
}
