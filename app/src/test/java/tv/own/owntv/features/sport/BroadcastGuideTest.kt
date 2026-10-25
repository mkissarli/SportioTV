package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The pure Where's The Match machinery: the day-window row parser (real page snippets) and the
 * guide ↔ fixture matching (kickoff time, both participants, event title, nearest session row).
 * No Android, no IO.
 */
class BroadcastGuideTest {

    // --- the parser against the real page shape ---------------------------------------------

    private val wtmTeamsRow = """
        <table><tr>
        <td class="home-team"></td>
        <td class="fixture-details"><span class="fixture">
          <a title="Chelsea Fixtures on TV" href="/Football/Chelsea.asp"><em class=" "> Chelsea</em></a>
          <em class=" ">v</em>
          <a title="Tottenham Hotspur Fixtures on TV" href="/Football/Tottenham-Hotspur.asp"><em class=" "> Tottenham Hotspur</em></a>
        </span>
        <span class="fixture-comp"><a title="Premier League TV Schedule" href="/epl-fixtures-on-tv/">Premier League</a></span></td>
        <td class="start-details"><span class="time"><em>17:30</em></span>
        <time class="sr-only" datetime="2026-10-24T17:30:00+01:00"></time></td>
        <td class="competition-name"><a title="Premier League TV Schedule" href="/epl-fixtures-on-tv/"><span>Premier League</span></a></td>
        <td class="channel-details">
          <a href="/match/chelsea-vs-tottenham-hotspur/214330" aria-label="Chelsea v Tottenham Hotspur TV coverage on Sky Sports Main Event"><span class="sr-only">Sky Sports Main Event</span></a>
          <a href="/match/chelsea-vs-tottenham-hotspur/214330" aria-label="Chelsea v Tottenham Hotspur TV coverage on TNT Sports 1"><span class="sr-only">TNT Sports 1</span></a>
        </td></tr></table>
    """

    @Test
    fun wtmRowsParseTeamsTimeLeagueAndChannels() {
        val entry = GuideParsers.wtm(wtmTeamsRow).single()
        // The whole fixture-details cell is captured: fixture text + competition.
        assertEquals("Chelsea v Tottenham Hotspur Premier League", entry.teams)
        assertEquals("Premier League", entry.league)
        assertEquals(listOf("Sky Sports Main Event", "TNT Sports 1"), entry.channels)
        assertTrue(entry.dateMs > 0)
    }

    @Test
    fun wtmEventRowsParseTitleCompetitionAndMultipleChannels() {
        // Real row shape (2026-10): one `<tr>` per event, channels as several anchors in
        // channel-details, exact UTC kickoff in the <time> element (never the local display time).
        val html = """
            <table><tr>
            <td class="home-team">
              <a title="F1 Academy Viewing Options" href="/event/f1-academy/219053" aria-label="F1 Academy TV channels and start time"></a>
            </td>
            <td class="fixture-details"><span class="fixture"><a title="F1 Academy TV Schedule" class=" eventlink" href="/events/live-f1-academy-on-tv.html"><strong class="">F1 Academy</strong></a></span> <em class=""> <span class="event-text">Race 3 - Circuit of the Americas, Austin, Texas</span></em></td>
            <td class="away-team"></td><td class="start-details"><span class="time"><em>17:00</em></span><time class="sr-only" datetime="2026-10-25T17:00:00Z"></time></td>
            <td class="competition-name"><img alt="F1 2026 season - Live Formula One" /><span>F1 2026 season</span> <span class="stage"></span></td>
            <td class="channel-details">
              <a href="/event/f1-academy/219053" aria-label="F1 Academy TV coverage on Sky Sports F1"><span class="sr-only">Sky Sports F1</span></a>
              <a href="/event/f1-academy/219053" aria-label="F1 Academy TV coverage on Sky Sports Ultra HDR"><span class="sr-only">Sky Sports Ultra HDR</span></a>
            </td></tr></table>
        """
        val entry = GuideParsers.wtm(html).single()
        // The event-text session line lands in the captured cell — the session label and venue
        // make it distinguishable from other rows of the same weekend.
        assertEquals("F1 Academy Race 3 - Circuit of the Americas, Austin, Texas", entry.teams)
        assertEquals("F1 2026 season", entry.league)
        assertEquals(listOf("Sky Sports F1", "Sky Sports Ultra HDR"), entry.channels)
        // 17:00Z UTC epoch, not the display text.
        assertEquals(1792947600000L, entry.dateMs) // 2026-10-25T17:00:00Z
    }

    @Test
    fun wtmPremierGatedRowsAreSkipped() {
        val html = """
            <table><tr>
            <td class="fixture-details"><span class="fixture"><strong>Lions v Waratahs</strong></span></td>
            <td class="start-details"><time class="sr-only" datetime="2026-10-25T19:00:00Z"></time></td>
            <td class="competition-name"><span>United Rugby Championship</span></td>
            <td class="channel-details"><div class="unlock-premier-listing"><a aria-label="Lions v Waratahs Log in to view"><span class="sr-only">Log in to view</span></a></div></td></tr></table>
        """
        assertTrue(GuideParsers.wtm(html).isEmpty())
    }

    @Test
    fun wtmNotTelevisedAndDuplicateChannelNamesCollapse() {
        val html = """
            <table><tr>
            <td class="fixture-details"><span class="fixture"><strong>Alpine Skiing World Cup</strong></span></td>
            <td class="start-details"><time class="sr-only" datetime="2026-10-25T10:00:00Z"></time></td>
            <td class="competition-name"><span>Alpine Skiing</span></td>
            <td class="channel-details">
              <a aria-label="Alpine Skiing World Cup TV coverage on Eurosport 1"><span class="sr-only">Eurosport 1</span></a>
              <a aria-label="Alpine Skiing World Cup TV coverage on Eurosport 1"><span class="sr-only">Eurosport 1</span></a>
              <a aria-label="Alpine Skiing World Cup TV coverage on Eurosport 2"><span class="sr-only">Eurosport 2</span></a>
              <a aria-label="Alpine Skiing World Cup TV coverage on [Not Televised]"><span class="sr-only">[Not Televised]</span></a>
            </td></tr></table>
        """
        val entry = GuideParsers.wtm(html).single()
        assertEquals(listOf("Eurosport 1", "Eurosport 2"), entry.channels)
    }

    // --- guide ↔ fixture matching (the Lume-style rules, spec §"Match an event to its row") ---

    private val chelseaRow = GuideEntry(
        dateMs = GuideEntryTestScope.time(2026, 10, 31, 17, 30),
        league = "Premier League",
        teams = "Chelsea v Tottenham Hotspur",
        channels = listOf("Sky Sports Main Event", "TNT Sports 1"),
    )

    // --- the teams case ---------------------------------------------------------------------

    @Test
    fun footballTeamsCaseRequiresBothSidesTokenOverlap() {
        val rowNorm = SportNameMatch.normalize(chelseaRow.teams + " " + chelseaRow.league)
        assertTrue(GuideMatch.bothSidesInRow(rowNorm, "Chelsea", "Tottenham Hotspur"))
        // Suffix-stripped: "Chelsea FC" and plural-tolerant spellings still meet the row.
        assertTrue(GuideMatch.bothSidesInRow(rowNorm, "Chelsea FC", "Tottenham Hotspur FC"))
        // Accents folded / diacritic-free tokens.
        assertTrue(GuideMatch.bothSidesInRow(rowNorm, "Chelsea FC", "Tottenham Hotspur"))
        // One side missing → not this fixture.
        assertFalse(GuideMatch.bothSidesInRow(rowNorm, "Chelsea", "Arsenal"))
        // Same spelling but the row carries only one team → no.
        assertFalse(GuideMatch.bothSidesInRow(rowNorm, "Chelsea", ""))
    }

    @Test
    fun duelsCaseUsesTheTwoAthletesLikeTwoTeams() {
        val rowNorm = SportNameMatch.normalize("Barbora Krejčíková v Emma Raducanu WTA")
        // Fixture spells the athlete diacritic-free; the row carries accents — token overlap holds.
        assertTrue(GuideMatch.bothSidesInRow(rowNorm, "Emma Raducanu", "Barbora Krejcikova"))
        assertFalse(GuideMatch.bothSidesInRow(rowNorm, "Emma Raducanu", "Iga Swiatek"))
    }

    private object GuideEntryTestScope {
        fun time(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            java.time.OffsetDateTime.of(year, month, day, hour, minute, 0, 0, java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
    }

    // --- time window -----------------------------------------------------------------------

    @Test
    fun timeMatchingKeepsTheKickoffWindow() {
        val row = GuideEntry(
            dateMs = GuideEntryTestScope.time(2026, 10, 31, 17, 30),
            league = "Premier League",
            teams = "Chelsea v Tottenham Hotspur",
            channels = listOf("Sky Sports Main Event"),
        )
        assertTrue(GuideMatch.timeMatches(row.dateMs, row.dateMs + 30L * 60L * 1000L))
        assertFalse(GuideMatch.timeMatches(row.dateMs, row.dateMs + 4L * 60L * 60L * 1000L))
        assertFalse(GuideMatch.timeMatches(0L, row.dateMs))
    }

    @Test
    fun aRowTwoHoursAwayDoesNotConfirmATeamFixture() {
        // The guide lists this fixture at another time — a different listing, not this kickoff.
        val kickoff = GuideEntryTestScope.time(2026, 10, 31, 17, 30)
        val twoHoursOff = chelseaRow.copy(dateMs = kickoff + 2L * 60L * 60L * 1000L)
        assertTrue(GuideMatch.confirmedBroadcasters(listOf(twoHoursOff), teamsFixtureAt(kickoff)).isEmpty())
        // The row at kickoff confirms its carriers.
        val onTime = chelseaRow.copy(dateMs = kickoff)
        assertEquals(
            listOf("Sky Sports Main Event", "TNT Sports 1"),
            GuideMatch.confirmedBroadcasters(listOf(onTime), teamsFixtureAt(kickoff)),
        )
    }

    // --- the event/tournament case -----------------------------------------------------------

    @Test
    fun eventFixturesMatchOnTitleTokenOverlap() {
        val f1Row = SportNameMatch.normalize("Formula 1 Australian Grand Prix F1 2026 season")
        assertTrue(GuideMatch.eventTokensInRow(f1Row, "Australian Grand Prix"))
        // A different race's row does not claim a wrong grand prix ("Australian Open" shares only
        // "australian" — one hit is not an overlap).
        assertFalse(GuideMatch.eventTokensInRow(f1Row, "Monaco Grand Prix"))
        assertFalse(GuideMatch.eventTokensInRow(f1Row, "Australian Open"))
    }

    @Test
    fun genericClubWordsDoNotIdentifyASide() {
        val row = SportNameMatch.normalize("Manchester United v Manchester City Premier League")
        // "United" and "City" belong to dozens of clubs — they identify no side on their own.
        assertFalse(GuideMatch.bothSidesInRow(row, "Newcastle United", "Leicester City"))
        assertTrue(GuideMatch.bothSidesInRow(row, "Manchester United", "Manchester City"))
    }

    @Test
    fun seriesWordsAloneAreNotAnEventOverlap() {
        val row = SportNameMatch.normalize("Formula 1 Australian Grand Prix F1 2026 season")
        assertFalse(GuideMatch.eventTokensInRow(row, "Grand Prix"))
        assertTrue(GuideMatch.eventTokensInRow(row, "Australian Grand Prix"))
    }

    // --- tennis: the guide has one row per tour per day, never per match ----------------------

    private fun tennisFixtureAt(ms: Long, leagueId: String, first: String, second: String) = Fixture(
        id = "tennis-1", leagueId = leagueId, dateMs = ms, state = FixtureState.UPCOMING,
        statusLine = "14:00", format = FixtureFormat.VERSUS,
        entries = listOf(
            FixtureEntry(id = "1", name = first, order = null, detail = null, logoUrl = null),
            FixtureEntry(id = "2", name = second, order = null, detail = null, logoUrl = null),
        ),
        broadcasts = emptyList(), venue = null,
    )

    @Test
    fun everyTennisMatchOfTheTourGetsTheTourChannels() {
        val atpRow = GuideEntry(
            GuideEntryTestScope.time(2026, 10, 25, 12, 0),
            "ATP World Tour",
            "ATP Tennis ATP Lyon / ATP Almaty / ATP Brussels",
            listOf("Sky Sports Tennis", "Sky Sports+"),
        )
        val wtaRow = GuideEntry(
            GuideEntryTestScope.time(2026, 10, 25, 5, 30),
            "WTA Tour",
            "WTA Tennis WTA Japan Open / WTA Ningbo Open",
            listOf("Sky Sports Tennis", "Sky Sports+"),
        )
        val entries = listOf(atpRow, wtaRow)
        val players = "Alex de Minaur" to "Karen Khachanov"
        // Every ATP match of that day runs on the tour's channel — first match, mid-day, evening.
        for (hour in listOf(12, 15, 20)) {
            val fixture = tennisFixtureAt(GuideEntryTestScope.time(2026, 10, 25, hour, 0), "tennis.atp", players.first, players.second)
            assertEquals(
                listOf("Sky Sports Tennis", "Sky Sports+"),
                GuideMatch.confirmedBroadcasters(entries, fixture),
            )
        }
        // A different tour's row does not serve an ATP fixture, and neither does another day.
        val nextDay = tennisFixtureAt(GuideEntryTestScope.time(2026, 10, 26, 14, 0), "tennis.atp", players.first, players.second)
        assertTrue(GuideMatch.confirmedBroadcasters(listOf(wtaRow), nextDay).isEmpty())
    }

    @Test
    fun aTennisRowNamingBothPlayersWinsOverTheTourRow() {
        val day = GuideEntryTestScope.time(2026, 10, 25, 12, 0)
        val matchRow = GuideEntry(day, "ATP World Tour", "ATP Tennis Alex de Minaur v Karen Khachanov", listOf("Sky Sports Tennis"))
        val tourRow = GuideEntry(day, "ATP World Tour", "ATP Tennis ATP Lyon / ATP Almaty", listOf("Sky Sports Tennis", "Sky Sports+"))
        val fixture = tennisFixtureAt(day, "tennis.atp", "Alex de Minaur", "Karen Khachanov")
        // The per-match row is the stronger evidence; the tour row only fills gaps.
        assertEquals(listOf("Sky Sports Tennis"), GuideMatch.confirmedBroadcasters(listOf(matchRow, tourRow), fixture))
    }

    // --- F1: sessions are distinct, the row names one of them --------------------------------

    @Test
    fun f1SessionsNeverBorrowAnotherSessionsRow() {
        val raceMs = GuideEntryTestScope.time(2026, 10, 25, 20, 0)
        val qualiMs = GuideEntryTestScope.time(2026, 10, 25, 17, 0)
        val entries = listOf(
            entryAt(qualiMs, "United States Grand Prix Qualifying - Circuit of the Americas", listOf("Sky Sports F1")),
            entryAt(raceMs, "United States Grand Prix Race - Circuit of the Americas", listOf("Sky Sports F1", "Sky Sports Main Event")),
        )
        assertEquals(
            listOf("Sky Sports F1", "Sky Sports Main Event"),
            GuideMatch.confirmedBroadcasters(entries, fixtureAt(raceMs, title = "United States Grand Prix — Race")),
        )
        assertEquals(
            listOf("Sky Sports F1"),
            GuideMatch.confirmedBroadcasters(entries, fixtureAt(qualiMs, title = "United States Grand Prix — Qualifying")),
        )
        // A practice session with no row of its own must not inherit the race's channels.
        val practiceMs = GuideEntryTestScope.time(2026, 10, 25, 15, 0)
        assertTrue(
            GuideMatch.confirmedBroadcasters(entries, fixtureAt(practiceMs, title = "United States Grand Prix — Practice 1"))
                .isEmpty(),
        )
    }

    // --- fetch windows: a past start date makes the site drop rows --------------------------

    @Test
    fun guideWindowsNeverStartInThePast() {
        val today = LocalDate.of(2026, 10, 25)
        val windows = GuideWindows.forDay(today)
        assertEquals(today, windows.first().first)
        windows.forEach { (start, end) ->
            assertFalse(start.isBefore(today))
            assertFalse(end.isBefore(start))
        }
        // Contiguous coverage today..+8; the measured US GP row (today 20:00) is inside it.
        assertEquals(8L, ChronoUnit.DAYS.between(today, windows.last().second))
        windows.zipWithNext().forEach { (first, second) ->
            assertEquals(first.second.plusDays(1), second.first)
        }
    }

    @Test
    fun sessionWordsNeverBecomeEventTokens() {
        // Fixture "Warm-up Race": session labels are stripped — no tokens, no overlap.
        val row = SportNameMatch.normalize("Formula 1 Australian Grand Prix Race 3")
        assertFalse(GuideMatch.eventTokensInRow(row, "Warm-up Race"))
    }

    // --- selection: closest row wins for event fixtures --------------------------------------

    private fun fixtureAt(ms: Long, title: String? = null, tournament: String? = null) = Fixture(
        id = "f1-1", leagueId = "racing.f1", dateMs = ms, state = FixtureState.UPCOMING,
        statusLine = "20:00", format = FixtureFormat.EVENT, title = title, tournament = tournament,
        broadcasts = emptyList(), venue = null,
    )

    private fun entryAt(ms: Long, teams: String, channels: List<String>) =
        GuideEntry(ms, "F1 2026 season", teams, channels)

    @Test
    fun asRaceWeekendTheClosestSessionRowWins() {
        val raceMs = GuideEntryTestScope.time(2026, 10, 25, 20, 0)
        val entries = listOf(
            entryAt(raceMs - 3L * 60L * 60L * 1000L, "United States Grand Prix", listOf("Sky Sports F1")),
            entryAt(raceMs, "United States Grand Prix", listOf("Sky Sports Main Event")),
        )
        val confirmed = GuideMatch.confirmedBroadcasters(entries, fixtureAt(raceMs, title = "United States Grand Prix"))
        // The other weekend session is 3h away and matches the same tokens; only the nearest row
        // is this session's listing.
        assertEquals(listOf("Sky Sports Main Event"), confirmed)
    }

    @Test
    fun teamFixturesKeepEveryMatchingRowButNeverDuplicateAChannel() {
        val raceMs = GuideEntryTestScope.time(2026, 10, 31, 17, 30)
        val near = chelseaRow.copy(dateMs = raceMs, channels = listOf("Sky Sports Main Event", "TNT Sports 1"))
        val sameTimeDifferentGame = near.copy(teams = "Chelsea v Arsenal", channels = listOf("Sky Sports Football"))
        val confirmed = GuideMatch.confirmedBroadcasters(
            listOf(near, sameTimeDifferentGame),
            teamsFixtureAt(raceMs),
        )
        // "Chelsea v Arsenal" shares no both-sides overlap with "Chelsea v Tottenham Hotspur".
        assertEquals(listOf("Sky Sports Main Event", "TNT Sports 1"), confirmed)
    }

    private fun teamsFixtureAt(ms: Long) = Fixture(
        id = "epl-1", leagueId = "soccer.england.1", dateMs = ms, state = FixtureState.UPCOMING,
        statusLine = "17:30", format = FixtureFormat.VERSUS,
        home = SportTeam("1", "Chelsea", "CHE", null, null, "51", false, "home"),
        away = SportTeam("2", "Tottenham Hotspur", "TOT", null, null, "47", false, "away"),
        broadcasts = emptyList(), venue = null,
    )
}
