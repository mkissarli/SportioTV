package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * Full-playlist AI matching, on the JVM: catalogue building, strict schema validation, batching,
 * id→row mapping, cache invalidation and failure behaviour. No network, no Android, no database.
 */
class AiPlaylistTest {

    // --- catalogue ------------------------------------------------------------------------------

    @Test
    fun theCatalogueCoversEveryRowIncludingOpaquePpvNames() {
        val channels = listOf(
            channel(1, "UK| TNT SPORTS 1 HD"),
            channel(2, "NOW: Sky Sports Main Event 4K"),
            channel(3, "PPV"),
            channel(4, "PPV 1"),
            channel(5, "Event 1"),
            channel(6, "Web Channel"),
        )

        val catalogue = AiPlaylistCatalogue.build(fixture(), channels, emptyList())

        assertEquals(channels.size, catalogue.size)
        assertEquals(
            listOf("C00001", "C00002", "C00003", "C00004", "C00005", "C00006"),
            catalogue.map { it.requestId },
        )
        val lines = catalogue.map { it.line() }
        listOf("PPV", "PPV 1", "Event 1", "Web Channel").forEach { opaque ->
            assertTrue("missing $opaque", lines.any { it.contains(" | $opaque") })
        }
        // Never leak a stream URL or credentials into the catalogue.
        assertTrue(lines.none { "provider.invalid" in it || "http" in it })
    }

    @Test
    fun theCatalogueCarriesCompactEpgAndThePriorIndicator() {
        val channels = listOf(channel(1, "UK| TNT SPORTS 1 HD", epgKey = "tnt"))
        val programmes = listOf(
            programme("tnt", title = "Chelsea v Tottenham Hotspur", startMs = Noon, stopMs = Noon + 2 * HourMs),
        )

        val line = AiPlaylistCatalogue.build(
            fixture(),
            channels,
            programmes,
            priorCarriers = listOf("TNT Sports 1"),
        ).single().line()

        assertTrue(line.contains("UK| TNT SPORTS 1 HD"))
        assertTrue(line.contains("epg="))
        assertTrue(line.contains("Chelsea v Tottenham Hotspur"))
        assertTrue(line.contains("prior=TNT Sports 1"))
    }

    // --- schema ---------------------------------------------------------------------------------

    @Test
    fun parserAcceptsSelectAndNone() {
        val select = AiPlaylistResponseParser.parse(
            """{"decision":"select","channels":[{"id":"C00012","confidence":0.91,"reason":"Specific competition coverage"}]}""",
            setOf("C00012"),
        )
        assertTrue(select is AiPlaylistAnswer.Selected)
        assertEquals("C00012", (select as AiPlaylistAnswer.Selected).picks.single().requestId)
        assertEquals(0.91, select.picks.single().confidence, 0.0001)

        val none = AiPlaylistResponseParser.parse(
            """{"decision":"none","channels":[],"reason":"Insufficient evidence"}""",
            setOf("C00012"),
        )
        assertTrue(none is AiPlaylistAnswer.None)
        assertEquals("Insufficient evidence", (none as AiPlaylistAnswer.None).reason)
    }

    @Test
    fun parserRejectsIdsOutsideTheSubmittedCatalogue() {
        val parsed = AiPlaylistResponseParser.parse(
            """{"decision":"select","channels":[{"id":"C09999","confidence":0.9,"reason":"invented"}]}""",
            setOf("C00001", "C00002"),
        )
        assertNull(parsed)
    }

    @Test
    fun parserRejectsDuplicatesInvalidConfidenceAndMalformedJson() {
        val allowed = setOf("C00001")
        assertNull(
            AiPlaylistResponseParser.parse(
                """{"decision":"select","channels":[{"id":"C00001","confidence":0.9},{"id":"C00001","confidence":0.8}]}""",
                allowed,
            ),
        )
        assertNull(
            AiPlaylistResponseParser.parse(
                """{"decision":"select","channels":[{"id":"C00001","confidence":1.7}]}""",
                allowed,
            ),
        )
        assertNull(
            AiPlaylistResponseParser.parse(
                """{"decision":"select","channels":[{"id":"C00001","confidence":"high"}]}""",
                allowed,
            ),
        )
        // Truncated response.
        assertNull(AiPlaylistResponseParser.parse("""{"decision":"select","channels":[""", allowed))
        // A `none` decision must not smuggle channels in.
        assertNull(
            AiPlaylistResponseParser.parse(
                """{"decision":"none","channels":[{"id":"C00001","confidence":0.9}]}""",
                allowed,
            ),
        )
        // More than the contract's ten selections.
        val many = (1..11).joinToString(",") { """{"id":"C00001","confidence":0.9}""" }
        assertNull(AiPlaylistResponseParser.parse("""{"decision":"select","channels":[$many]}""", allowed))
    }

    // --- batching -------------------------------------------------------------------------------

    @Test
    fun fullContextIsOneBatchWhileTheFallbackCoversEveryRowExactlyOnce() {
        val lines = (1..25).map { "C%05d | Channel %d".format(it, it) }

        assertEquals(listOf(lines), AiPlaylistBatching.batches(lines, maxChars = 1_000_000))

        val batches = AiPlaylistBatching.batches(lines, maxChars = 6_200)
        assertTrue(batches.size > 1)
        assertEquals(lines, batches.flatten())
    }

    // --- analyst --------------------------------------------------------------------------------

    @Test
    fun theAnalystSendsTheWholePlaylistAndMapsIdsBackToPlaylistRows() = runBlocking {
        val store = FakePlaylist(
            listOf(
                channel(1, "UK| TNT SPORTS 1 HD"),
                channel(2, "PPV"),
                channel(3, "Web Channel"),
            ),
        )
        var promptSeen = ""
        val result = analyst(store) { _, user, _ ->
            promptSeen = user
            selection(listOf("C00002"))
        }.analyze(fixture(), apiKey = "test-key")

        assertEquals(AiPlaylistDecision.SELECTED, result.decision)
        assertEquals(3, result.candidateCount)
        assertEquals(listOf("PPV"), result.picks.map { it.channelName })
        assertEquals(listOf(2L), result.picks.map { it.channelId })
        assertTrue(result.picks.single().aiConfirmed)
        // The model saw every row, including the opaque one, and no stream URL.
        assertTrue(promptSeen.contains("PPV"))
        assertTrue(promptSeen.contains("Web Channel"))
        assertFalse(promptSeen.contains("provider.invalid"))
    }

    @Test
    fun aValidNoneAddsNoAiSelection() = runBlocking {
        val store = FakePlaylist(listOf(channel(1, "UK| TNT SPORTS 1 HD")))
        val result = analyst(store) { _, _, _ -> """{"decision":"none","channels":[],"reason":"no evidence"}""" }
            .analyze(fixture(), apiKey = "test-key")

        assertEquals(AiPlaylistDecision.NONE, result.decision)
        assertTrue(result.picks.isEmpty())
        assertEquals(1, result.validResponses)
    }

    @Test
    fun anIdOutsideTheRequestIsRejectedWithoutAddingChannels() = runBlocking {
        val store = FakePlaylist(listOf(channel(1, "UK| TNT SPORTS 1 HD")))
        val result = analyst(store) { _, _, _ -> selection(listOf("C99999")) }
            .analyze(fixture(), apiKey = "test-key")

        assertTrue(result.picks.isEmpty())
        assertEquals(0, result.validResponses)
        assertEquals(1, result.invalidResponses)
    }

    @Test
    fun theBatchFallbackConsidersEveryRowAndConsolidatesTheSelections() = runBlocking {
        val channels = (1..12).map { channel(it.toLong(), "PPV %d".format(it)) }
        val store = FakePlaylist(channels)
        val seenIds = LinkedHashSet<String>()
        val result = analyst(store, maxFull = 1, maxBatch = 6_030) { _, user, _ ->
            val ids = candidateIds(user)
            seenIds += ids
            selection(ids)
        }.analyze(fixture(), apiKey = "test-key")

        assertEquals(channels.size, result.candidateCount)
        assertTrue(result.requestCount > 1)
        // Every playlist row was submitted in exactly one batch; no row was silently dropped.
        assertEquals(channels.size, seenIds.size)
        // No batch may return more than the cap; consolidation keeps the requested maximum.
        assertEquals(AiPlaylistPolicy.MaxSelections, result.picks.size)
    }

    @Test
    fun anApiFailureLeavesNoAiSelectionAndPropagates() = runBlocking {
        val store = FakePlaylist(listOf(channel(1, "UK| TNT SPORTS 1 HD")))
        try {
            analyst(store) { _, _, _ -> throw AiRequestException(AiFailure.NETWORK) }
                .analyze(fixture(), apiKey = "test-key")
            fail("expected AiRequestException")
        } catch (expected: AiRequestException) {
            assertEquals(AiFailure.NETWORK, expected.failure)
        }
    }

    @Test
    fun aCacheHitAvoidsTheSecondRequestAndAPlaylistChangeInvalidatesIt() = runBlocking {
        val store = FakePlaylist(listOf(channel(1, "PPV")))
        var requests = 0
        val analyst = analyst(store) { _, _, _ ->
            requests++
            selection(listOf("C00001"))
        }

        val first = analyst.analyze(fixture(), apiKey = "test-key")
        val second = analyst.analyze(fixture(), apiKey = "test-key")
        assertEquals(1, requests)
        assertEquals(1, first.requestCount)
        assertEquals(0, second.requestCount)
        assertEquals(first.picks.map { it.channelId }, second.picks.map { it.channelId })

        // A changed playlist fingerprint is a different input and must ask again.
        store.channels = listOf(channel(2, "PPV 2"))
        val third = analyst.analyze(fixture(), apiKey = "test-key")
        assertEquals(2, requests)
        assertEquals(listOf(2L), third.picks.map { it.channelId })
    }

    @Test
    fun anApiFailureIsNotCachedAsAPermanentNone() = runBlocking {
        val store = FakePlaylist(listOf(channel(1, "PPV")))
        var requests = 0
        val analyst = analyst(store) { _, _, _ ->
            requests++
            if (requests == 1) throw AiRequestException(AiFailure.RATE_LIMITED)
            selection(listOf("C00001"))
        }

        try {
            analyst.analyze(fixture(), apiKey = "test-key")
            fail("expected AiRequestException")
        } catch (expected: AiRequestException) {
            assertEquals(AiFailure.RATE_LIMITED, expected.failure)
        }
        // A transient failure must not become a cached `none`: the retry asks again and succeeds.
        val retry = analyst.analyze(fixture(), apiKey = "test-key")
        assertEquals(2, requests)
        assertEquals(listOf("PPV"), retry.picks.map { it.channelName })
    }

    @Test
    fun aPriorDatasetLoadsWithItsScopeAndNeverInventsAChannel() {
        val priors = SportsPriors.parse(
            """
            {"schemaVersion":1,"datasetVersion":"test-1","competitions":[
              {"competitionId":"racing.f1","aliases":["f1"],"seasons":["2026"],"markets":["UK"],
               "carriers":["Sky Sports F1","Sky Sports Main Event"]}
            ]}
            """.trimIndent(),
        )

        assertEquals("test-1", priors.datasetVersion)
        val f1 = f1Fixture()
        assertTrue(priors.carriersFor(f1, market = "DE", season = "2026").isEmpty())
        assertEquals(
            listOf("Sky Sports F1", "Sky Sports Main Event"),
            priors.carriersFor(f1, market = "UK", season = "2026"),
        )
        // A season the dataset does not cover is out of scope.
        assertTrue(priors.carriersFor(f1, market = "UK", season = "2025").isEmpty())
        // An unknown schema is rejected instead of being half-read.
        assertEquals(SportsPriors.EMPTY, SportsPriors.parse("""{"schemaVersion":99}"""))
    }

    // --- helpers --------------------------------------------------------------------------------

    private fun analyst(
        store: FakePlaylist,
        maxFull: Int = AiPlaylistPolicy.MaxFullRequestChars,
        maxBatch: Int = AiPlaylistPolicy.MaxBatchRequestChars,
        ask: suspend (system: String, user: String, apiKey: String) -> String,
    ) = AiPlaylistAnalyst(
        channelDao = store.channelDao,
        epgDao = store.epgDao,
        sources = { listOf(SourceId) },
        priors = SportsPriors.EMPTY,
        leagueLabel = { "Premier League" },
        ask = ask,
        maxFullRequestChars = maxFull,
        maxBatchRequestChars = maxBatch,
    )

    private fun candidateIds(prompt: String): List<String> =
        prompt.lineSequence()
            .mapNotNull { Regex("^(C\\d{5}) ").find(it)?.groupValues?.get(1) }
            .toList()

    private fun selection(ids: List<String>): String =
        ids.joinToString(prefix = """{"decision":"select","channels":[""", postfix = "]}") {
            """{"id":"$it","confidence":0.9,"reason":"test"}"""
        }

    private fun fixture() = Fixture(
        id = "epl-1",
        leagueId = "soccer.eng.1",
        dateMs = Noon,
        state = FixtureState.UPCOMING,
        statusLine = "17:30",
        home = SportTeam("1", "Chelsea", "CHE", null, null, null, false, "home"),
        away = SportTeam("2", "Tottenham Hotspur", "TOT", null, null, null, false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun f1Fixture() = Fixture(
        id = "f1-1",
        leagueId = "racing.f1",
        dateMs = Noon,
        state = FixtureState.UPCOMING,
        statusLine = "20:00",
        format = FixtureFormat.EVENT,
        title = "United States Grand Prix — Race",
        broadcasts = emptyList(),
        venue = "Austin",
    )

    private fun channel(id: Long, name: String, epgKey: String? = null) = ChannelEntity(
        id = id,
        sourceId = SourceId,
        name = name,
        streamUrl = "http://provider.invalid/$id.ts",
        epgChannelId = epgKey,
    )

    private fun programme(
        key: String,
        title: String,
        startMs: Long = Noon,
        stopMs: Long = Noon + 2 * HourMs,
    ) = EpgProgrammeEntity(
        sourceId = SourceId,
        epgChannelId = key,
        startMs = startMs,
        stopMs = stopMs,
        title = title,
        description = null,
    )

    private companion object {
        const val SourceId = 1L
        const val Noon = 1_760_000_000_000L
        const val HourMs = 60L * 60L * 1000L
    }
}

/** A mutable in-memory playlist + EPG for the analyst's DAO calls. */
private class FakePlaylist(
    channels: List<ChannelEntity>,
    private val programmes: List<EpgProgrammeEntity> = emptyList(),
) {
    var channels: List<ChannelEntity> = channels

    val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
        when (name) {
            "allForSources" -> this@FakePlaylist.channels
            "getById" -> this@FakePlaylist.channels.firstOrNull { it.id == args!![0] as Long }
            else -> null
        }
    }

    val epgDao: EpgDao = proxy(EpgDao::class.java) { name, args ->
        when (name) {
            "programmeSummariesForChannels" -> {
                val keys = (args!![0] as List<*>).filterIsInstance<String>()
                    .map { it.trim().lowercase() }.toSet()
                programmes.filter { it.epgChannelId.trim().lowercase() in keys }
            }
            else -> null
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        when (method.name) {
            "toString" -> "Fake${type.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> handler(method.name, args)
        }
    } as T
