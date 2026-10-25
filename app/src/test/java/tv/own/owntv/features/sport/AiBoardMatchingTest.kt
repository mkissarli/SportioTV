package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.CategoryEntity
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity
import tv.own.owntv.core.model.MediaType

/**
 * Two-phase board matching on the JVM: prompt/answer contracts, batching, broad local candidate
 * discovery (including opaque rows), id mapping and cache behaviour. No network, no Android.
 */
class AiBoardMatchingTest {

    // --- phase 1 contract ------------------------------------------------------------------------

    @Test
    fun discoveryParserKeepsKnownFixturesAndIgnoresUnknownOrInvalidEntries() {
        val parsed = BoardDiscoveryParser.parse(
            """
            {"schemaVersion":1,"fixtures":[
              {"fixtureId":"known#1","suggestions":[
                {"country":"gb","broadcaster":"TNT Sports","channelAliases":["TNT Sports 1","TNT Sports 2"],"reason":"competition_rights"},
                {"country":"DE","broadcaster":"","channelAliases":[],"reason":"empty"},
                {"country":"US","broadcaster":"ESPN","channelAliases":["ESPN2"],"reason":"market_rights"}
              ]},
              {"fixtureId":"invented#9","suggestions":[{"country":"GB","broadcaster":"Sky","channelAliases":[]}]},
              {"fixtureId":"known#2","suggestions":[]}
            ]}
            """.trimIndent(),
            setOf("known#1", "known#2"),
        )

        assertTrue(parsed != null)
        assertEquals(setOf("known#1", "known#2"), parsed!!.keys)
        assertEquals(2, parsed["known#1"]!!.size)
        assertEquals("GB", parsed["known#1"]!!.first().country)
        assertTrue(parsed["known#2"]!!.isEmpty())
        assertFalse(parsed.containsKey("invented#9"))
    }

    @Test
    fun discoveryParserRejectsBrokenOrWrongVersionJson() {
        assertNull(BoardDiscoveryParser.parse("not json", setOf("a")))
        assertNull(BoardDiscoveryParser.parse("""{"schemaVersion":2,"fixtures":[]}""", setOf("a")))
        assertNull(BoardDiscoveryParser.parse("""{"fixtures":""", setOf("a")))
    }

    // --- phase 2 contract ------------------------------------------------------------------------

    @Test
    fun adjudicationParserDropsUnknownIdsAndBadConfidenceInsteadOfInventingAChannel() {
        val parsed = BoardAdjudicationParser.parse(
            """
            {"schemaVersion":1,"fixtures":[
              {"fixtureId":"known#1","channels":[
                {"candidateId":"C00001","confidence":0.9,"reason":"exact"},
                {"candidateId":"C09999","confidence":0.9,"reason":"outside request"},
                {"candidateId":"C00002","confidence":1.7,"reason":"bad"},
                {"candidateId":"C00003","confidence":"high","reason":"bad"},
                {"candidateId":"C00001","confidence":0.8,"reason":"duplicate"}
              ]},
              {"fixtureId":"invented#9","channels":[{"candidateId":"C00001","confidence":0.9}]}
            ]}
            """.trimIndent(),
            allowedFixtureIds = setOf("known#1"),
            allowedCandidateIds = setOf("C00001", "C00002", "C00003"),
        )

        assertTrue(parsed != null)
        assertEquals(setOf("known#1"), parsed!!.keys)
        assertEquals(1, parsed["known#1"]!!.size)
        assertEquals("C00001", parsed["known#1"]!!.single().candidateId)
        assertEquals(0.9, parsed["known#1"]!!.single().confidence, 0.0001)
    }

    @Test
    fun adjudicationParserAcceptsAnEmptySelection() {
        val parsed = BoardAdjudicationParser.parse(
            """{"schemaVersion":1,"fixtures":[{"fixtureId":"known#1","channels":[]}]}""",
            setOf("known#1"),
            setOf("C00001"),
        )
        assertTrue(parsed != null)
        assertTrue(parsed!!["known#1"]!!.isEmpty())
    }

    // --- batching ---------------------------------------------------------------------------------

    @Test
    fun plannerKeepsEveryFixtureExactlyOnceAndRespectsTheRequestBudget() {
        val fixtures = (1..12).map { index ->
            ref(fixture("f$index", Noon + index * 60_000L))
        }
        val planned = BoardAdjudicationPlanner.plan(
            fixtures = fixtures,
            candidatesByFixture = emptyMap(),
            maxRequests = 10,
            maxRequestChars = 1_000_000,
            maxFixturesPerRequest = 4,
        )

        assertEquals(3, planned.size)
        assertEquals(fixtures.map { it.id }.toSet(), planned.flatMap { it.fixtures }.map { it.id }.toSet())
        assertEquals(fixtures.size, planned.sumOf { it.fixtures.size })
        assertTrue(planned.all { it.fixtures.size <= 4 })
    }

    @Test
    fun plannerMergesWindowsWhenTheRequestBudgetIsTightAndNeverDuplicatesAFixture() {
        val fixtures = (1..12).map { index ->
            ref(fixture("f$index", Noon + index * 60_000L))
        }
        val planned = BoardAdjudicationPlanner.plan(
            fixtures = fixtures,
            candidatesByFixture = emptyMap(),
            maxRequests = 2,
            maxRequestChars = 1_000_000,
            maxFixturesPerRequest = 4,
        )

        assertEquals(2, planned.size)
        assertEquals(fixtures.map { it.id }.toSet(), planned.flatMap { it.fixtures }.map { it.id }.toSet())
    }

    @Test
    fun theBoardCapSharesFixturesAcrossSports() {
        val soccer = (1..10).map { index -> ref(fixture("s$index", Noon + index * 60_000L)) }
        val tennis = (1..10).map { index ->
            val base = fixture("t$index", Noon + index * 60_000L)
            BoardFixtureRef(base, "tennis.atp", "tennis")
        }

        val selected = boardRoundRobinBySport(soccer + tennis, 6)

        assertEquals(6, selected.size)
        assertEquals(3, selected.count { it.sport == "soccer" })
        assertEquals(3, selected.count { it.sport == "tennis" })
    }

    // --- local discovery --------------------------------------------------------------------------

    @Test
    fun opaqueChannelsNeedContextAndNeverLeakAStreamUrl() = runBlocking {
        val store = FakeBoardStore(
            channels = listOf(
                channel(1, "UK| TNT SPORTS 1 HD", epgKey = "tnt", categoryId = 10),
                channel(2, "PPV 1", epgKey = "ppv1", categoryId = 10),
                channel(3, "PPV 2", epgKey = null, categoryId = null),
                channel(4, "Event 5", epgKey = null, categoryId = 20),
                channel(5, "TENNIS PPV", epgKey = null, categoryId = 10),
            ),
            programmes = listOf(
                programme("tnt", "Chelsea v Tottenham Hotspur"),
                programme("ppv1", "Premier League: Chelsea v Tottenham"),
            ),
            categories = mapOf(10L to "Sports", 20L to "Tennis"),
        )
        var phase2Prompt = ""
        val matcher = matcher(store) { _, user, _ ->
            if (user.contains("ADJUDICATION")) {
                phase2Prompt = user
                none()
            } else {
                """{"schemaVersion":1,"fixtures":[]}"""
            }
        }

        matcher.match(
            fixtures = listOf(footballFixture(), tennisFixture()),
            countries = listOf("GB"),
            market = "GB",
            apiKey = "test-key",
        )

        assertTrue("EPG-backed PPV stays in the catalogue", phase2Prompt.contains("PPV 1"))
        assertFalse("opaque without any context is not guessed", phase2Prompt.contains("PPV 2"))
        assertTrue("group context makes an opaque row plausible", phase2Prompt.contains("Event 5"))
        assertTrue("a sport word in a generic name is context", phase2Prompt.contains("TENNIS PPV"))
        assertFalse(phase2Prompt.contains("provider.invalid"))
        assertFalse(phase2Prompt.contains("http"))
    }

    @Test
    fun youthFeedsAreNotInheritedAndTaggedNamesStayParseable() = runBlocking {
        val store = FakeBoardStore(
            channels = listOf(
                channel(1, "UK| TNT SPORTS 1 HD", epgKey = "tnt", categoryId = 10),
                channel(2, "DE: SKY SPORT U19 HD", epgKey = "youth", categoryId = 10),
            ),
            programmes = listOf(
                programme("tnt", "Chelsea v Tottenham Hotspur"),
                programme("youth", "Chelsea v Tottenham Hotspur U19"),
            ),
            categories = mapOf(10L to "Sports"),
        )
        var phase2Prompt = ""
        val matcher = matcher(store) { _, user, _ ->
            if (user.contains("ADJUDICATION")) {
                phase2Prompt = user
                none()
            } else {
                """{"schemaVersion":1,"fixtures":[]}"""
            }
        }

        matcher.match(listOf(footballFixture()), listOf("GB"), "GB", "test-key")

        assertTrue(phase2Prompt.contains("UK/ TNT SPORTS 1 HD"))
        assertFalse("the pipe separator is not part of the display name", phase2Prompt.contains("UK| TNT"))
        assertFalse("a senior fixture never inherits a youth feed", phase2Prompt.contains("U19"))
    }

    @Test
    fun boardCleanStripsUrlsAndPipeSeparators() {
        val cleaned = boardClean("UK| TNT SPORTS http://secret.invalid/live?user=a")
        assertFalse(cleaned.contains("secret.invalid"))
        assertFalse(cleaned.contains("http"))
        assertFalse(cleaned.contains("|"))
        assertTrue(cleaned.contains("UK/ TNT SPORTS"))
    }

    // --- end to end -------------------------------------------------------------------------------

    @Test
    fun thePipelineMapsIdsBackToRowsAndReusesItsCaches() = runBlocking {
        val store = FakeBoardStore(
            channels = listOf(
                channel(1, "UK| TNT SPORTS 1 HD", epgKey = "tnt", categoryId = 10),
                channel(2, "PPV 1", epgKey = "ppv1", categoryId = 10),
                channel(3, "PPV 2", epgKey = null, categoryId = null),
                channel(4, "Sky Sports F1", epgKey = "f1", categoryId = 10),
                channel(5, "US: TENNIS PPV", epgKey = "tennis", categoryId = 10),
                channel(6, "TNT SPORTS 2", epgKey = null, categoryId = 10),
            ),
            programmes = listOf(
                programme("tnt", "Chelsea v Tottenham Hotspur", Noon, Noon + 2 * HourMs),
                programme("ppv1", "Premier League: Chelsea v Tottenham", Noon, Noon + 2 * HourMs),
                programme("f1", "Formula 1: United States Grand Prix Race", Noon + 2 * HourMs, Noon + 4 * HourMs),
                programme("tennis", "ATP: Alcaraz vs Sinner", Noon + HourMs, Noon + 3 * HourMs),
            ),
            categories = mapOf(10L to "Sports"),
        )
        var requests = 0
        val matcher = matcher(store) { _, user, _ ->
            requests++
            if (user.contains("BOARD BROADCAST DISCOVERY")) {
                """
                {"schemaVersion":1,"fixtures":[
                  {"fixtureId":"soccer.eng.1#fb","suggestions":[{"country":"GB","broadcaster":"TNT Sports","channelAliases":["TNT Sports 2"],"reason":"competition_rights"}]},
                  {"fixtureId":"invented#9","suggestions":[]}
                ]}
                """.trimIndent()
            } else {
                val ids = candidateIds(user)
                fun id(name: String): String? = ids.entries.firstOrNull { it.value.contains(name) }?.key
                val football = buildList {
                    id("TNT SPORTS 1")?.let(::add)
                    id("TNT SPORTS 2")?.let(::add)
                    id("PPV 1")?.let(::add)
                    add("C99999")
                }
                val f1 = listOfNotNull(id("Sky Sports F1"))
                val tennis = listOfNotNull(id("TENNIS PPV"))
                buildSelection(mapOf("soccer.eng.1#fb" to football, "racing.f1#f1" to f1, "tennis.atp#tennis" to tennis))
            }
        }

        val fixtures = listOf(footballFixture(), f1Fixture(), tennisFixture())
        val first = matcher.match(fixtures, listOf("GB"), "GB", "test-key")

        assertEquals(1, first.validResponses)
        assertEquals(2, requests)
        assertEquals(
            setOf("UK| TNT SPORTS 1 HD", "TNT SPORTS 2", "PPV 1"),
            first.hits["soccer.eng.1#fb"].orEmpty().map { it.channelName }.toSet(),
        )
        assertEquals(listOf("Sky Sports F1"), first.hits["racing.f1#f1"].orEmpty().map { it.channelName })
        assertEquals(listOf("US: TENNIS PPV"), first.hits["tennis.atp#tennis"].orEmpty().map { it.channelName })

        val second = matcher.match(fixtures, listOf("GB"), "GB", "test-key")
        assertEquals("a completed board must not be paid for twice", 2, requests)
        assertEquals(first.hits.keys, second.hits.keys)
        assertEquals(0, second.adjudicationRequests)
    }

    @Test
    fun aMissingKeySendsNothing() = runBlocking {
        val store = FakeBoardStore(listOf(channel(1, "PPV 1")))
        var requests = 0
        val matcher = matcher(store) { _, _, _ -> requests++; none() }

        val result = matcher.match(listOf(footballFixture()), listOf("GB"), null, "")

        assertEquals(0, requests)
        assertTrue(result.hits.isEmpty())
    }

    // --- search-derived candidates and evidence validation ---------------------------------------

    @Test
    fun searchAddsAPlaylistRowThatPhase1AndEpgMissed() = runBlocking {
        val store = FakeBoardStore(listOf(channel(1, "HR: SPORT KLUB 5 HD")))
        var searchQueries = 0
        var phase2Prompt = ""
        val matcher = matcher(
            store = store,
            search = { _, _ ->
                searchQueries++
                listOf(
                    DeepSeekSearchHit(
                        title = "Sport Klub 5 live TV",
                        url = "https://example.invalid/tennis",
                        siteName = "example.invalid",
                        date = "2026-10-24",
                        snippet = "Sport Klub 5 carries the match",
                        summary = "",
                        content = "",
                    ),
                )
            },
        ) { _, user, _ ->
            when {
                user.contains("Extract broadcaster/channel names") ->
                    """{"schemaVersion":1,"channels":[{"name":"Sport Klub 5","aliases":[],"country":"HR","sourceIndex":0,"excerpt":"Sport Klub 5"}]}"""
                user.contains("BOARD BROADCAST DISCOVERY") ->
                    """{"schemaVersion":1,"fixtures":[{"fixtureId":"tennis.atp#tennis","suggestions":[]}]}"""
                user.contains("ADJUDICATION") -> {
                    phase2Prompt = user
                    val id = candidateIds(user).entries.firstOrNull { it.value.contains("SPORT KLUB 5") }?.key
                    if (id == null) {
                        none()
                    } else {
                        """
                        {"schemaVersion":1,"fixtures":[{"fixtureId":"tennis.atp#tennis","channels":[
                          {"candidateId":"$id","evidence":"supported","source":"search","sourceIndex":0,
                           "excerpt":"Sport Klub 5 carries the match","confidence":0.95,"reason":"listing"}
                        ]}]}
                        """.trimIndent()
                    }
                }
                else -> none()
            }
        }

        val result = matcher.match(listOf(tennisFixture()), listOf("HR"), null, "test-key")

        assertEquals(1, searchQueries)
        assertTrue("the extracted name must reach phase 2", phase2Prompt.contains("SPORT KLUB 5"))
        assertTrue(phase2Prompt.contains("LIVE SEARCH RESULTS"))
        val hit = result.hits["tennis.atp#tennis"].orEmpty().single()
        assertEquals("HR: SPORT KLUB 5 HD", hit.channelName)
        assertEquals(ChannelEvidenceTier.SUPPORTED, hit.evidenceTier)
        assertEquals(ChannelEvidenceSource.SEARCH, hit.evidenceSource)
        assertEquals("https://example.invalid/tennis", hit.evidenceUrl)
    }

    @Test
    fun aFabricatedSearchExcerptCannotStaySupported() = runBlocking {
        val store = FakeBoardStore(listOf(channel(1, "HR: SPORT KLUB 5 HD")))
        val matcher = matcher(
            store = store,
            search = { _, _ ->
                listOf(
                    DeepSeekSearchHit(
                        title = "Sport Klub 5 live TV",
                        url = "https://example.invalid/tennis",
                        siteName = "example.invalid",
                        date = "2026-10-24",
                        snippet = "Sport Klub 5 carries the match",
                        summary = "",
                        content = "",
                    ),
                )
            },
        ) { _, user, _ ->
            when {
                user.contains("Extract broadcaster/channel names") ->
                    """{"schemaVersion":1,"channels":[{"name":"Sport Klub 5","sourceIndex":0,"excerpt":"Sport Klub 5"}]}"""
                user.contains("BOARD BROADCAST DISCOVERY") ->
                    """{"schemaVersion":1,"fixtures":[{"fixtureId":"tennis.atp#tennis","suggestions":[]}]}"""
                user.contains("ADJUDICATION") -> {
                    val id = candidateIds(user).entries.firstOrNull { it.value.contains("SPORT KLUB 5") }?.key
                    if (id == null) none() else evidenceSelection("tennis.atp#tennis", id, "supported", "search", "Invented quote")
                }
                else -> none()
            }
        }

        val hit = matcher.match(listOf(tennisFixture()), listOf("HR"), null, "test-key")
            .hits["tennis.atp#tennis"].orEmpty().single()

        assertEquals(ChannelEvidenceTier.LIKELY, hit.evidenceTier)
    }

    @Test
    fun rightsKnowledgeAloneCannotBeSupported() = runBlocking {
        val store = FakeBoardStore(listOf(channel(1, "UK: TNT SPORTS 1 HD")))
        val matcher = matcher(store) { _, user, _ ->
            when {
                user.contains("BOARD BROADCAST DISCOVERY") -> """
                    {"schemaVersion":1,"fixtures":[{"fixtureId":"soccer.eng.1#fb","suggestions":[
                      {"country":"GB","broadcaster":"TNT Sports","channelAliases":["TNT Sports 1"],"reason":"rights"}
                    ]}]}
                """.trimIndent()
                user.contains("ADJUDICATION") -> {
                    val id = candidateIds(user).entries.firstOrNull { it.value.contains("TNT SPORTS 1") }?.key
                    if (id == null) none() else evidenceSelection("soccer.eng.1#fb", id, "supported", "rights", null)
                }
                else -> none()
            }
        }

        val hit = matcher.match(listOf(footballFixture()), listOf("GB"), "GB", "test-key")
            .hits["soccer.eng.1#fb"].orEmpty().single()

        assertEquals(ChannelEvidenceTier.LIKELY, hit.evidenceTier)
        assertEquals(ChannelEvidenceSource.RIGHTS, hit.evidenceSource)
    }

    @Test
    fun anExactEpgProgrammeCanBeSupported() = runBlocking {
        val store = FakeBoardStore(
            channels = listOf(channel(1, "UK: TNT SPORTS 1 HD", epgKey = "tnt")),
            programmes = listOf(programme("tnt", "Chelsea v Tottenham Hotspur")),
        )
        val matcher = matcher(store) { _, user, _ ->
            when {
                user.contains("BOARD BROADCAST DISCOVERY") ->
                    """{"schemaVersion":1,"fixtures":[{"fixtureId":"soccer.eng.1#fb","suggestions":[]}]}"""
                user.contains("ADJUDICATION") -> {
                    val id = candidateIds(user).entries.firstOrNull { it.value.contains("TNT SPORTS 1") }?.key
                    if (id == null) none() else evidenceSelection("soccer.eng.1#fb", id, "supported", "epg", null)
                }
                else -> none()
            }
        }

        val hit = matcher.match(listOf(footballFixture()), listOf("GB"), "GB", "test-key")
            .hits["soccer.eng.1#fb"].orEmpty().single()

        assertEquals(ChannelEvidenceTier.SUPPORTED, hit.evidenceTier)
        assertEquals(ChannelEvidenceSource.EPG, hit.evidenceSource)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun matcher(
        store: FakeBoardStore,
        search: (suspend (String, String) -> List<DeepSeekSearchHit>)? = null,
        ask: suspend (String, String, String) -> String,
    ) = AiBoardMatcher(
        channelDao = store.channelDao,
        epgDao = store.epgDao,
        categoryDao = store.categoryDao,
        sources = { listOf(SourceId) },
        vocab = SportsVocab.EMPTY,
        priors = SportsPriors.EMPTY,
        leagueLabel = { it },
        ask = ask,
        search = search,
        clock = { Noon },
    )

    private fun evidenceSelection(
        fixtureId: String,
        id: String,
        evidence: String,
        source: String,
        excerpt: String?,
    ): String {
        val excerptJson = excerpt?.let { ",\"excerpt\":\"$it\"" }.orEmpty()
        val indexJson = if (source == "search") ",\"sourceIndex\":0" else ""
        return """{"schemaVersion":1,"fixtures":[{"fixtureId":"$fixtureId","channels":[{"candidateId":"$id","evidence":"$evidence","source":"$source"$indexJson$excerptJson,"confidence":0.95,"reason":"test"}]}]}"""
    }

    private fun candidateIds(prompt: String): Map<String, String> =
        prompt.lineSequence()
            .mapNotNull { line ->
                Regex("^(C\\d{5}) \\| (.+?)(?: \\| |$)").find(line)?.let { match ->
                    match.groupValues[1] to match.groupValues[2]
                }
            }
            .toMap()

    private fun buildSelection(byFixture: Map<String, List<String>>): String {
        val fixtures = byFixture.entries.joinToString(",") { (fixtureId, ids) ->
            val channels = ids.joinToString(",") { id ->
                """{"candidateId":"$id","confidence":0.9,"reason":"test"}"""
            }
            """{"fixtureId":"$fixtureId","channels":[$channels]}"""
        }
        return """{"schemaVersion":1,"fixtures":[$fixtures]}"""
    }

    private fun none(): String = """{"schemaVersion":1,"fixtures":[]}"""

    private fun ref(fixture: Fixture) = BoardFixtureRef(fixture, fixture.leagueId, fixture.leagueId.substringBefore('.'))

    private fun footballFixture() = Fixture(
        id = "fb",
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
        id = "f1",
        leagueId = "racing.f1",
        dateMs = Noon + 2 * HourMs,
        state = FixtureState.UPCOMING,
        statusLine = "20:00",
        format = FixtureFormat.EVENT,
        title = "United States Grand Prix — Race",
        broadcasts = emptyList(),
        venue = "Austin",
    )

    private fun tennisFixture() = Fixture(
        id = "tennis",
        leagueId = "tennis.atp",
        dateMs = Noon + HourMs,
        state = FixtureState.UPCOMING,
        statusLine = "19:00",
        format = FixtureFormat.VERSUS,
        entries = listOf(
            FixtureEntry(id = "1", name = "Carlos Alcaraz", order = 1, detail = null, logoUrl = null),
            FixtureEntry(id = "2", name = "Jannik Sinner", order = 2, detail = null, logoUrl = null),
        ),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun fixture(id: String, at: Long) = Fixture(
        id = id,
        leagueId = "soccer.eng.1",
        dateMs = at,
        state = FixtureState.UPCOMING,
        statusLine = "17:30",
        home = SportTeam("1", "Chelsea", "CHE", null, null, null, false, "home"),
        away = SportTeam("2", "Tottenham Hotspur", "TOT", null, null, null, false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun channel(
        id: Long,
        name: String,
        epgKey: String? = null,
        categoryId: Long? = null,
    ) = ChannelEntity(
        id = id,
        sourceId = SourceId,
        categoryId = categoryId,
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

/** Mutable in-memory playlist/EPG/categories for the matcher's DAO calls. */
private class FakeBoardStore(
    private val channels: List<ChannelEntity>,
    private val programmes: List<EpgProgrammeEntity> = emptyList(),
    private val categories: Map<Long, String> = emptyMap(),
) {
    val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
        when (name) {
            "allForSources" -> channels
            "getById" -> channels.firstOrNull { it.id == args!![0] as Long }
            else -> null
        }
    }

    val epgDao: EpgDao = proxy(EpgDao::class.java) { name, args ->
        when (name) {
            "searchTitles", "searchGuide" -> {
                val probe = (args!![0] as String).lowercase()
                programmes.filter { programme ->
                    programme.title.lowercase().contains(probe) ||
                        programme.description.orEmpty().lowercase().contains(probe)
                }
            }
            "programmeSummariesForChannels" -> {
                val keys = (args!![0] as List<*>).filterIsInstance<String>().toSet()
                programmes.filter { it.epgChannelId in keys }
            }
            else -> null
        }
    }

    val categoryDao: CategoryDao = proxy(CategoryDao::class.java) { name, args ->
        when (name) {
            "getById" -> {
                val id = args!![0] as Long
                categories[id]?.let { nameValue ->
                    CategoryEntity(
                        id = id,
                        sourceId = SourceId,
                        mediaType = MediaType.LIVE,
                        name = nameValue,
                        remoteId = null,
                        sortOrder = 0,
                    )
                }
            }
            else -> null
        }
    }

    private companion object {
        const val SourceId = 1L
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
