package tv.own.owntv.features.sport

import java.time.ZonedDateTime
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure pulselive → fixture mapping for the MotoGP league: only `kind = GP` weekends become
 * fixtures, race start comes from the last schedule day, and the live/upcoming/finished state.
 */
class MotoGpScheduleTest {

    private val eventsJson = JSONArray(
        """
        [
          {"id":"evt-test","name":"SEPANG TEST","kind":"TEST","date_end":"2026-02-08T18:00:00+08:00",
           "circuit":{"name":"Sepang International Circuit","city":"Sepang"},"schedule":{"options":[]}},
          {"id":"evt-thai","name":"PT GRAND PRIX OF THAILAND ","kind":"GP","date_end":"2026-03-01T18:00:00+07:00",
           "circuit":{"name":"Chang International Circuit","city":"Buriram"},
           "schedule":{"options":[
             {"dateStart":"2026-02-26T18:00:00+0700"},
             {"dateStart":"2026-03-01T16:40:00+0700"}
           ]}}
        ]
        """,
    )

    @Test
    fun onlyGrandPrixWeekendsBecomeEvents() {
        val races = MotoGpSchedule.parseEvents(eventsJson)
        assertEquals(1, races.size)
        val race = races.single()
        assertEquals("evt-thai", race.id)
        assertEquals("PT GRAND PRIX OF THAILAND", race.name)
        assertEquals("Buriram", race.venue)
        assertEquals(utc("2026-03-01T16:40:00+07:00"), race.raceStartMs)
    }

    @Test
    fun theEventEndIsTheFallbackRaceStart() {
        val noSchedule = JSONArray(
            """
            [{"id":"evt-qatar","name":"GRAND PRIX OF QATAR","kind":"GP","date_end":"2026-11-15T22:00:00+03:00",
              "circuit":{"name":"Lusail International Circuit","city":""}}]
            """,
        )
        val race = MotoGpSchedule.parseEvents(noSchedule).single()
        assertEquals("Lusail International Circuit", race.venue)
        assertEquals(utc("2026-11-15T22:00:00+03:00"), race.raceStartMs)
    }

    @Test
    fun fixturesBecomeEventRowsWithCorrectStates() {
        val races = MotoGpSchedule.parseEvents(eventsJson)
        val raceStart = races.single().raceStartMs

        val upcoming = MotoGpSchedule.fixtures(races, raceStart - 60_000L).single()
        assertEquals(FixtureState.UPCOMING, upcoming.state)
        assertEquals(FixtureFormat.EVENT, upcoming.format)
        assertEquals("motogp-evt-thai", upcoming.id)
        assertEquals(MotoGpSchedule.MotoGpLeagueId, upcoming.leagueId)
        assertEquals("PT GRAND PRIX OF THAILAND", upcoming.title)
        assertEquals("Buriram", upcoming.venue)
        assertTrue(upcoming.statusLine.matches(Regex("\\d{2}:\\d{2}")))

        assertEquals(FixtureState.LIVE, MotoGpSchedule.fixtures(races, raceStart + 60_000L).single().state)
        assertEquals(FixtureState.FINISHED, MotoGpSchedule.fixtures(races, raceStart + 3L * 60L * 60L * 1000L).single().state)
    }

    private fun utc(iso: String): Long = ZonedDateTime.parse(iso).toInstant().toEpochMilli()
}
