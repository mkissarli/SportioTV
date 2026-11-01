package tv.own.owntv.features.sport

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source parsers and fixture relevance, against captured/sanitised page shapes. No network, no
 * Android: every test would fail fast if a site's markup changed in a way the adapter cannot read.
 */
class BroadcastSourceTest {

    private val day = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()
    private val window = BroadcastWindow(day - HourMs, day + 8 * DayMs)
    private val retrievedAt = Instant.parse("2026-11-01T10:00:00Z").toEpochMilli()

    // --- FootTV: fixture rows with the numbered Ligue 1+ feeds --------------------------------

    @Test
    fun footTvParsesFixtureAndNumberedFeeds() {
        val listings = BroadcastSourceParsers.footTv(FootTvHtml, window, retrievedAt)
        assertEquals(3, listings.size)

        val lyon = listings.filter { it.participants == listOf("Lyon", "Angers") }
        assertEquals(listOf("Ligue 1+ 2", "Ligue 1+ 3"), lyon.map { it.channel })
        assertTrue(lyon.all { it.direct && it.coverageType == CoverageType.FIXTURE })
        assertEquals("Ligue 1", lyon.first().competition)
        assertEquals("FR", lyon.first().territory)
        // 17:15 Paris = 16:15Z.
        assertEquals(Instant.parse("2026-11-01T16:15:00Z").toEpochMilli(), lyon.first().eventStartTimeMs)

        val network = listings.first { it.participants == listOf("Strasbourg", "Auxerre") }
        assertEquals("Ligue 1+", network.channel)
        assertEquals(CoverageType.SERVICE, network.coverageType)
        assertFalse(network.direct)
    }

    @Test
    fun footTvParsesPlainTextCarriersToo() {
        val html = """
            <table><tr class="cabeceraCompericion"><td><a href="/competition/bundesliga">Bundesliga</a></td></tr>
            <tr><td class="hora">15:30</td>
            <td class="local"><span title="Union Berlin">Union Berlin</span></td>
            <td class="visitante"><span title="FC Cologne">FC Cologne</span></td>
            <td class="canales"><meta itemprop="startDate" content="2026-11-01T14:30:00" />
            <ul class="listaCanales">
              <li class="canal-sin-enlace " title="beIN SPORTS MAX 4">beIN SPORTS MAX 4</li>
              <li title="beIN SPORTS HD 2"><a class="internalLinkCanal" href="/chaine/bein-sports-2">beIN SPORTS HD 2</a></li>
              <li title="DAZN (Voir en direct)"><a href="https://www.awin1.com/cread.php?awinmid=1">DAZN (Voir en direct)</a></li>
            </ul></td></tr></table>
        """.trimIndent()
        val listings = BroadcastSourceParsers.footTv(html, window, retrievedAt)
        assertEquals(listOf("beIN SPORTS MAX 4", "beIN SPORTS HD 2"), listings.map { it.channel })
    }

    @Test
    fun footTvSkipsAffiliateLinksAndRowsOutsideTheWindow() {
        val narrow = BroadcastWindow(day, day + HourMs)
        assertTrue(BroadcastSourceParsers.footTv(FootTvHtml, narrow, retrievedAt).isEmpty())
        assertFalse(BroadcastSourceParsers.footTv(FootTvHtml, window, retrievedAt).any { it.channel.contains("awin1") })
    }

    @Test
    fun footTvDropsMalformedRowsInsteadOfThrowing() {
        val html = "<table><tr><td class=\"canales\">no metadata</td></tr><tr><td class=\"hora\">15:00</td></tr></table>"
        assertTrue(BroadcastSourceParsers.footTv(html, window, retrievedAt).isEmpty())
    }

    // --- Live Football on TV: UK fixture rows and service-level channels ----------------------

    @Test
    fun liveFootballOnTvParsesDateTeamsAndChannels() {
        val listings = BroadcastSourceParsers.liveFootballOnTv(LiveFootballHtml, window, retrievedAt)
        assertEquals(3, listings.size)

        val liverpool = listings.filter { it.participants == listOf("Liverpool", "Arsenal") }
        assertEquals(listOf("Sky Sports Main Event", "Sky Sports Premier League"), liverpool.map { it.channel })
        assertEquals("Premier League", liverpool.first().competition)
        assertTrue(liverpool.all { it.direct && it.coverageType == CoverageType.FIXTURE })
        // 16:30 London (GMT on 1 Nov) = 16:30Z.
        assertEquals(Instant.parse("2026-11-01T16:30:00Z").toEpochMilli(), liverpool.first().eventStartTimeMs)

        val ligue = listings.first { it.participants == listOf("Lyon", "Angers") }
        assertEquals(CoverageType.SERVICE, ligue.coverageType)
        assertFalse(ligue.direct)
    }

    @Test
    fun liveFootballOnTvDropsRowsWithoutACarrierOrWithUnparseableTeams() {
        val html = """
            <div class="fixture-date">Sunday 1st November 2026</div>
            <div class="fixture"><div class="fixture__time">14:00</div>
            <div class="fixture__teams">TBC</div><div class="fixture__competition">Cup</div>
            <div class="fixture__channel"><div class="span3 channels"></div></div></div>
            <div class="fixture"><div class="fixture__time">15:00</div>
            <div class="fixture__teams">A v B</div><div class="fixture__competition">Cup</div>
            <div class="fixture__channel"><div class="span3 channels"><span class="channel-pill" style="x">Sky Sports+</span></div></div></div>
        """.trimIndent()
        val listings = BroadcastSourceParsers.liveFootballOnTv(html, window, retrievedAt)
        assertEquals(1, listings.size)
        assertEquals("Sky Sports+", listings.single().channel)
        assertEquals(CoverageType.FIXTURE, listings.single().coverageType)
    }

    // --- Sky Sports tennis: tournament rows with a channel and session time -------------------

    @Test
    fun skySportsTennisParsesTournamentRows() {
        val listings = BroadcastSourceParsers.skySportsTennis(SkyTennisHtml, window, retrievedAt)
        assertEquals(4, listings.size)
        assertTrue(listings.all { it.sport == SportClass.TENNIS && it.coverageType == CoverageType.TOURNAMENT })
        assertFalse(listings.first().direct)
        assertEquals("WTA Pan Pacific Open / WTA Guangzhou Open", listings.first().competition)
        assertEquals("Sky Sports Tennis", listings.first().channel)
        // 03:00 London = 03:00Z.
        assertEquals(Instant.parse("2026-11-01T03:00:00Z").toEpochMilli(), listings.first().eventStartTimeMs)
        assertTrue(listings.any { it.channel == "Sky Sports Main Event" })
    }

    @Test
    fun skySportsTennisIgnoresADayWithoutRows() {
        val html = """
            <h3 class="text-h4 -rs-style20 box">Mon 2nd November</h3>
            <div class="box"><p>No events</p></div>
            <h3 class="text-h4 -rs-style20 box">Tue 3rd November</h3>
            <div class="event-group -layout2"><ul class="row-table event">
            <li class="col col1 -center"><strong>ATP Paris Masters</strong></li></ul>
            <p class="event-detail -center caption">Sky Sports Tennis (10:00)</p></div>
        """.trimIndent()
        val listings = BroadcastSourceParsers.skySportsTennis(html, window, retrievedAt)
        assertEquals(1, listings.size)
        assertEquals(Instant.parse("2026-11-03T10:00:00Z").toEpochMilli(), listings.single().eventStartTimeMs)
    }

    // --- Sport-TV tennis: French session rows ------------------------------------------------

    @Test
    fun sportTvTennisParsesSessionRowsInFrench() {
        val listings = BroadcastSourceParsers.sportTvTennis(SportTvHtml, window, retrievedAt)
        assertEquals(2, listings.size)
        assertTrue(listings.all { it.coverageType == CoverageType.SESSION && !it.direct })
        val vienna = listings.first { it.competition.orEmpty().contains("Vienne") }
        assertEquals("Eurosport 2", vienna.channel)
        // 14:00 Paris = 13:00Z.
        assertEquals(Instant.parse("2026-11-01T13:00:00Z").toEpochMilli(), vienna.eventStartTimeMs)
        assertEquals("ATP Vienne — Finale", vienna.competition)
    }

    // --- Foot Mercato: European football with numbered Ligue 1+ feeds ------------------------

    @Test
    fun footMercatoParsesFixtureAndNumberedFeeds() {
        val listings = BroadcastSourceParsers.footMercato(FootMercatoHtml, window, retrievedAt)
        assertEquals(2, listings.size)
        val lyon = listings.first { it.channel == "Ligue 1+ 2" }
        assertEquals(listOf("Lyon", "Angers"), lyon.participants)
        assertEquals("Ligue 1+ 2", lyon.channel)
        assertEquals(CoverageType.FIXTURE, lyon.coverageType)
        assertTrue(lyon.direct)
        // 17:15 Paris = 16:15Z.
        assertEquals(Instant.parse("2026-11-01T16:15:00Z").toEpochMilli(), lyon.eventStartTimeMs)

        val dazn = listings.first { it.channel == "DAZN" }
        assertEquals(CoverageType.SERVICE, dazn.coverageType)
        assertFalse(dazn.direct)
    }

    @Test
    fun footMercatoSkipsLiveRowsWithoutAKickoff() {
        val html = """
            <div class="matchFull" data-live-id="1" data-live="1">
              <a href="/live/1-union-berlin-vs-cologne" class="matchFull__link">
                <span class="matchFull__teams">
                  <span class="matchTeam__name">Union Berlin</span>
                  <span class="matchTeam__name">Cologne</span>
                </span>
                <span class="matchFull__infos"><span class="matchFull__infosChrono">77'</span></span>
              </a>
            </div>
        """.trimIndent()
        assertTrue(BroadcastSourceParsers.footMercato(html, window, retrievedAt).isEmpty())
    }

    // --- relevance: the source row must belong to this fixture -------------------------------

    @Test
    fun footballRowsNeedBothSidesInAnyOrder() {
        val fixture = football()
        val row = listing(
            participants = listOf("Arsenal", "Liverpool"),
            startMs = fixture.dateMs,
            coverage = CoverageType.FIXTURE,
            sport = SportClass.FOOTBALL,
        )
        assertTrue(BroadcastListingMatch.relevant(row, fixture))
        assertFalse(BroadcastListingMatch.relevant(row.copy(participants = listOf("Liverpool", "Chelsea")), fixture))
        assertFalse(BroadcastListingMatch.relevant(row.copy(eventStartTimeMs = fixture.dateMs + 3 * HourMs), fixture))
        assertFalse(BroadcastListingMatch.relevant(row.copy(coverageType = CoverageType.SERVICE), fixture))
    }

    @Test
    fun tennisTournamentRowsOnlyMatchTheirOwnTournamentAndDay() {
        val fixture = tennis(tournament = "Paris Masters")
        val paris = listing(
            participants = emptyList(),
            competition = "ATP Paris Masters",
            startMs = fixture.dateMs,
            coverage = CoverageType.TOURNAMENT,
            sport = SportClass.TENNIS,
        )
        assertTrue(BroadcastListingMatch.relevant(paris, fixture))
        assertFalse(BroadcastListingMatch.relevant(paris.copy(competition = "ATP Vienna Open"), fixture))
        assertFalse(
            BroadcastListingMatch.relevant(
                paris.copy(
                    eventStartTimeMs = fixture.dateMs + 3 * DayMs,
                    coverageStartTimeMs = fixture.dateMs + 3 * DayMs,
                ),
                fixture,
            ),
        )
    }

    @Test
    fun tennisSessionRowsMatchTheSameTournamentSession() {
        val fixture = tennis(tournament = "Paris Masters")
        val session = listing(
            participants = emptyList(),
            competition = "Masters ATP Paris — Session soir",
            startMs = fixture.dateMs + HourMs,
            coverage = CoverageType.SESSION,
            sport = SportClass.TENNIS,
        )
        assertTrue(BroadcastListingMatch.relevant(session, fixture))
    }

    private fun football() = Fixture(
        id = "football",
        leagueId = "soccer.eng.1",
        dateMs = Instant.parse("2026-11-01T16:30:00Z").toEpochMilli(),
        state = FixtureState.LIVE,
        statusLine = "LIVE",
        home = SportTeam("1", "Liverpool", "LIV", null, null, "0", false, "home"),
        away = SportTeam("2", "Arsenal", "ARS", null, null, "0", false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun tennis(tournament: String) = Fixture(
        id = "tennis",
        leagueId = "tennis.atp",
        dateMs = day + 15 * HourMs,
        state = FixtureState.UPCOMING,
        statusLine = "15:00",
        format = FixtureFormat.VERSUS,
        entries = listOf(
            FixtureEntry("1", "Player One", 1, null, null),
            FixtureEntry("2", "Player Two", 2, null, null),
        ),
        broadcasts = emptyList(),
        tournament = tournament,
        venue = null,
    )

    private fun listing(
        participants: List<String>,
        competition: String? = null,
        startMs: Long,
        coverage: CoverageType,
        sport: SportClass,
    ) = BroadcastListing(
        eventId = "e1",
        sport = sport,
        competition = competition,
        participants = participants,
        eventStartTimeMs = startMs,
        coverageStartTimeMs = startMs,
        coverageEndTimeMs = startMs + 2 * HourMs,
        broadcaster = "Sky Sports Tennis",
        channel = "Sky Sports Tennis",
        territory = "UK",
        language = "en",
        coverageType = coverage,
        sourceId = "test",
        retrievedAtMs = retrievedAt,
        confidence = 90,
        direct = coverage == CoverageType.FIXTURE,
    )

    private companion object {
        const val DayMs = 24L * 60L * 60L * 1000L
        const val HourMs = 60L * 60L * 1000L

        val FootTvHtml = """
            <table class="tablaPrincipal"><tbody>
            <tr class="cabeceraCompericion"><td colspan="5">
              <img alt="Ligue 1" title="Ligue 1" />
              <a class="internalLink" href="/competition/ligue-1"> Ligue 1 </a>
            </td></tr>
            <tr>
              <td class="hora "> 15:00 </td><td class="detalles sinDetalles"></td>
              <td class="local"><a href="/equipes/strasbourg-alsace"><span title="Strasbourg">Strasbourg</span></a></td>
              <td class="visitante"><a href="/equipes/auxerre"><span title="Auxerre">Auxerre</span></a></td>
              <td class="canales">
                <div itemscope itemtype="https://schema.org/Event">
                  <meta itemprop="name" content="Strasbourg - Auxerre" />
                  <meta itemprop="startDate" content="2026-11-01T14:00:00" />
                  <meta itemprop="duration" content="T1H45M" />
                </div>
                <ul class="listaCanales">
                  <li title="Ligue 1+"><a class="internalLinkCanal" href="/chaine/ligue-1-plus">Ligue 1+</a></li>
                  <li title="DAZN (Voir en direct)"><a href="https://www.awin1.com/cread.php?awinmid=1">DAZN (Voir en direct)</a></li>
                </ul>
              </td>
            </tr>
            <tr>
              <td class="hora "> 17:15 </td><td class="detalles sinDetalles"></td>
              <td class="local"><a href="/equipes/olympique-lyon"><span title="Lyon">Lyon</span></a></td>
              <td class="visitante"><a href="/equipes/angers"><span title="Angers">Angers</span></a></td>
              <td class="canales">
                <div itemscope itemtype="https://schema.org/Event">
                  <meta itemprop="name" content="Lyon - Angers" />
                  <meta itemprop="startDate" content="2026-11-01T16:15:00" />
                  <meta itemprop="duration" content="T1H45M" />
                </div>
                <ul class="listaCanales">
                  <li title="Ligue 1+ 2"><a class="internalLinkCanal" href="/chaine/ligue-1-plus-2">Ligue 1+ 2</a></li>
                  <li title="Ligue 1+ 3"><a class="internalLinkCanal" href="/chaine/ligue-1-plus-3">Ligue 1+ 3</a></li>
                  <li title="DAZN (Voir en direct)"><a href="https://www.awin1.com/cread.php?awinmid=1">DAZN (Voir en direct)</a></li>
                </ul>
              </td>
            </tr>
            </tbody></table>
        """.trimIndent()

        val FootMercatoHtml = """
            <div class="matchFull" data-live-id="1629482990132320515" data-live="1">
              <a href="/live/1629482990132320515-lyon-vs-angers" class="matchFull__link">
                <span class="matchFull__teams">
                  <span class="matchFull__team"><span class="matchTeam__name">Lyon</span></span>
                  <span class="matchFull__team matchFull__team--away"><span class="matchTeam__name">Angers</span></span>
                </span>
                <span class="matchFull__infos">
                  <span class="matchFull__dateTimeChrono"><span class="matchFull__infosDate">
                    <time datetime="2026-11-01T16:15:00+00:00">17:15</time>
                  </span></span>
                  <span class="matchFull__broadcasts">
                    <img data-src="https://assets-fr.imgfoot.com/media/cache/150x150/dazn.png" alt="Logo DAZN" class="matchFull__broadcastImage" width="40" height="40" />
                    <img data-src="https://assets-fr.imgfoot.com/media/cache/150x150/ligue-1-plus-2.png" alt="Logo Ligue 1+ 2" class="matchFull__broadcastImage" width="40" height="40" />
                  </span>
                </span>
              </a>
            </div>
        """.trimIndent()

        val LiveFootballHtml = """
            <div class="fixture-date">Sunday 1st November 2026</div>
            <div class="fixture"><div class="fixture__time">16:30</div>
              <div class="fixture__teams">Liverpool v Arsenal  </div>
              <div class="fixture__competition">Premier League</div>
              <div class="fixture__channel"><div class="span3 channels">
                <span class="channel-pill" style="x">Sky Sports Main Event</span>
                <span class="channel-pill" style="x">Sky Sports Premier League</span>
              </div></div>
            </div>
            <div class="fixture"><div class="fixture__time">16:15</div>
              <div class="fixture__teams">Lyon v Angers  </div>
              <div class="fixture__competition">Ligue 1</div>
              <div class="fixture__channel"><div class="span3 channels">
                <span class="channel-pill" style="x">Ligue1+</span>
              </div></div>
            </div>
        """.trimIndent()

        val SkyTennisHtml = """
            <h3 class="text-h4 -rs-style20 box">Sun 1st November</h3>
            <div class="box">
              <div class="event-group -layout2">
                <ul class="row-table event"><li class="col col1 -center">
                  <strong>WTA Pan Pacific Open / WTA Guangzhou Open</strong>
                </li></ul>
                <p class="event-detail -center caption">Sky Sports Tennis (03:00)</p>
              </div>
              <div class="event-group -layout2">
                <ul class="row-table event"><li class="col col1 -center">
                  <strong>ATP Paris Masters</strong>
                </li></ul>
                <p class="event-detail -center caption">Sky Sports Main Event (10:00), Sky Sports Tennis (10:00)</p>
              </div>
            </div>
            <h3 class="text-h4 -rs-style20 box">Mon 2nd November</h3>
            <div class="box">
              <div class="event-group -layout2">
                <ul class="row-table event"><li class="col col1 -center">
                  <strong>WTA Hong Kong Open</strong>
                </li></ul>
                <p class="event-detail -center caption">Sky Sports+ (06:00)</p>
              </div>
            </div>
        """.trimIndent()

        val SportTvHtml = """
            <section class="day-section active" data-day="1nov">
              <h2 class="day-title">Dimanche 1er novembre</h2>
              <div class="match">
                <span class="match-time">04:00</span>
                <div class="match-info"><div class="match-teams">WTA Tokyo</div>
                <div class="match-comp">WTA — Tokyo — d?s 04h00</div></div>
                <span class="match-channel">beIN SPORTS 3</span>
              </div>
              <div class="match featured">
                <span class="match-time">14:00</span>
                <div class="match-info"><div class="match-teams">ATP Vienne — Finale</div>
                <div class="match-comp">ATP — Vienne — Finale — d?s 14h00</div></div>
                <span class="match-channel">Eurosport 2</span>
              </div>
            </section>
        """.trimIndent()
    }
}
