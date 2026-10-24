package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure TV-guide parsers (Where's The Match rows, Live Football On TV blocks) and the
 * guide ↔ fixture matching (league, kickoff time, teams/tournament). No Android, no IO.
 */
class BroadcastGuideTest {

    // --- Where's The Match ---------------------------------------------------------------

    private val wtmRow = """
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
          <a href="/match/chelsea-vs-tottenham-hotspur/214330" aria-label="Chelsea v Tottenham Hotspur TV coverage on Sky Sports Main Event"></a>
          <a href="/match/chelsea-vs-tottenham-hotspur/214330" aria-label="Chelsea v Tottenham Hotspur TV coverage on Sky Sports Premier League"></a>
        </td></tr></table>
    """

    @Test
    fun wtmRowsParseTeamsTimeLeagueAndChannels() {
        val entries = GuideParsers.wtm(wtmRow)
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("Chelsea v Tottenham Hotspur", entry.teams)
        assertEquals("Premier League", entry.league)
        assertEquals(listOf("Sky Sports Main Event", "Sky Sports Premier League"), entry.channels)
        assertTrue(entry.dateMs > 0)
    }

    @Test
    fun wtmTennisRowsCarryTheTournamentTextAsTeams() {
        val html = """
            <table><tr>
            <td class="fixture-details"><span class="fixture">ATP Lyon / ATP Almaty / ATP Brussels</span></td>
            <td class="start-details"><time class="sr-only" datetime="2026-10-24T13:30:00+01:00"></time></td>
            <td class="competition-name"><span>ATP World Tour</span></td>
            <td class="channel-details">
              <a href="/match/atp-lyon/1" aria-label="ATP Lyon TV coverage on Sky Sports Tennis"></a>
            </td></tr></table>
        """
        val entries = GuideParsers.wtm(html)
        assertEquals(1, entries.size)
        assertEquals("ATP Lyon / ATP Almaty / ATP Brussels", entries.single().teams)
        assertEquals(listOf("Sky Sports Tennis"), entries.single().channels)
    }

    // --- Live Football On TV --------------------------------------------------------------

    private val liveFootballHtml = """
        <html><body>
        <div class="fixture-date">Saturday 24th October 2026</div>
        <div class="fixture">
          <div class="fixture__time">17:30</div>
          <div class="fixture__teams">Chelsea v Tottenham Hotspur  </div>
          <div class="fixture__competition">Premier League</div>
          <div class="fixture__channel"><div class="span3 channels">
            <span class="channel-pill">Sky Sports Main Event</span>
            <span class="channel-pill">Sky Sports Premier League</span>
          </div></div>
        </div>
        <div class="fixture">
          <div class="fixture__time">20:00</div>
          <div class="fixture__teams">Manchester United v Newcastle United  </div>
          <div class="fixture__competition">Premier League</div>
          <div class="fixture__channel"><div class="span3 channels">
            <span class="channel-pill">TNT Sports 1</span>
          </div></div>
        </div>
        </body></html>
    """

    @Test
    fun liveFootballBlocksParseBothFixturesWithTheSharedDateHeader() {
        val entries = GuideParsers.liveFootball(liveFootballHtml)
        assertEquals(2, entries.size)
        assertEquals("Chelsea v Tottenham Hotspur", entries[0].teams)
        assertEquals(listOf("Sky Sports Main Event", "Sky Sports Premier League"), entries[0].channels)
        assertEquals("Manchester United v Newcastle United", entries[1].teams)
        assertEquals(listOf("TNT Sports 1"), entries[1].channels)
        assertTrue(entries[1].dateMs > entries[0].dateMs)
    }

    // --- guide ↔ fixture matching ----------------------------------------------------------

    @Test
    fun leagueMatchingIsLooseEnoughForUefaPrefixes() {
        assertTrue(GuideMatch.leagueMatches("Premier League", "Premier League"))
        assertTrue(GuideMatch.leagueMatches("Champions League", "UEFA Champions League"))
        assertTrue(GuideMatch.leagueMatches("ATP World Tour", "ATP"))
        assertTrue(!GuideMatch.leagueMatches("National League", "Premier League"))
    }

    @Test
    fun teamMatchingCoversFootballAndTennisShapes() {
        assertTrue(GuideMatch.teamsMatch("Chelsea v Tottenham Hotspur", "Chelsea", "Tottenham Hotspur", ""))
        assertTrue(!GuideMatch.teamsMatch("Chelsea v Arsenal", "Chelsea", "Tottenham Hotspur", ""))
        // Tennis: the guide names the tournament; the fixture carries it in `tournament`.
        assertTrue(GuideMatch.teamsMatch("ATP Lyon / ATP Almaty / ATP Brussels", "", "", "ATP Lyon"))
        assertTrue(!GuideMatch.teamsMatch("ATP Almaty / ATP Brussels", "", "", "ATP Lyon"))
    }
}
