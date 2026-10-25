package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search query limits and the extractor's grounding rules, without Android or network. */
class SportsSearchTest {

    @Test
    fun theQueryKeepsTheDateAndFitsTheApiLimitEvenForALongEvent() {
        val fixture = Fixture(
            id = "long",
            leagueId = "racing.f1",
            dateMs = Noon,
            state = FixtureState.UPCOMING,
            statusLine = "20:00",
            format = FixtureFormat.EVENT,
            title = "Formula 1 United States Grand Prix at Circuit of the Americas — Sprint Qualifying Session",
            tournament = "FIA Formula One World Championship 2026",
            broadcasts = emptyList(),
            venue = "Austin",
        )

        val query = SportsSearchQueryBuilder.build(fixture)

        assertTrue(query.primary.length in 1..SportsSearchQueryBuilder.MaxQueryChars)
        assertTrue("the date must survive the 128-character limit", query.primary.contains("2026-10-24"))
        query.fallback?.let {
            assertTrue(it.length in 1..SportsSearchQueryBuilder.MaxQueryChars)
            assertTrue(it.contains("2026-10-24"))
        }
    }

    @Test
    fun tennisQueriesUseBothParticipants() {
        val fixture = Fixture(
            id = "tennis",
            leagueId = "tennis.atp",
            dateMs = Noon,
            state = FixtureState.UPCOMING,
            statusLine = "19:00",
            format = FixtureFormat.VERSUS,
            entries = listOf(
                FixtureEntry("1", "Tommy Paul", 1, null, null),
                FixtureEntry("2", "Taylor Fritz", 2, null, null),
            ),
            broadcasts = emptyList(),
            venue = null,
        )

        val query = SportsSearchQueryBuilder.build(fixture)

        assertTrue(query.primary.contains("Tommy Paul"))
        assertTrue(query.primary.contains("Taylor Fritz"))
    }

    @Test
    fun extractorKeepsOnlyNamesQuotedFromARealResult() {
        val results = listOf(hit("Sport Klub 5 live TV", "Sport Klub 5 carries the match"))
        val parsed = SportsSearchExtractorParser.parse(
            """
            {"schemaVersion":1,"channels":[
              {"name":"Sport Klub 5","aliases":["Sport Klub"],"country":"HR","sourceIndex":0,"excerpt":"Sport Klub 5"},
              {"name":"Invented TV","aliases":[],"country":"US","sourceIndex":0,"excerpt":"Invented TV"},
              {"name":"Wrong Index","aliases":[],"country":"US","sourceIndex":9,"excerpt":"Wrong Index"}
            ]}
            """.trimIndent(),
            results,
        )

        assertNotNull(parsed)
        assertEquals(listOf("Sport Klub 5"), parsed!!.map { it.name })
        assertEquals("HR", parsed.single().country)
    }

    @Test
    fun extractorRejectsANameWhoseExcerptIsNotInTheResult() {
        val results = listOf(hit("Premier Sports 1", "Premier Sports 1 shows LaLiga"))
        val parsed = SportsSearchExtractorParser.parse(
            """{"schemaVersion":1,"channels":[{"name":"Premier Sports 1","sourceIndex":0,"excerpt":"a quote nobody wrote"}]}""",
            results,
        )

        assertTrue(parsed!!.isEmpty())
    }

    @Test
    fun groundingAcceptsEllipsisFragmentsOnlyWhenEveryFragmentExists() {
        val text = "Premier Sports 1 shows Barcelona v Real Madrid at 20:00 UTC."
        assertTrue(isGroundedExcerpt("Premier Sports 1 ... 20:00 UTC", text))
        assertFalse(isGroundedExcerpt("Premier Sports 1 ... 23:00 UTC", text))
    }

    private fun hit(title: String, content: String) = DeepSeekSearchHit(
        title = title,
        url = "https://example.invalid/listing",
        siteName = "example.invalid",
        date = "2026-10-24",
        snippet = content,
        summary = "",
        content = content,
    )

    private companion object {
        const val Noon = 1_792_843_200_000L
    }
}
