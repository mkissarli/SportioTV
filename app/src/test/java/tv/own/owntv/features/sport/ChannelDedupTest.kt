package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rule-4 identity + dedup + ordering: resolution and fps variants of one channel are one
 * identity, "FR: …" is a different option, "Main Event" is never stripped as noise, and an
 * identity renders at most two rows — the best 4K/UHD copy and the best HD-or-lower copy.
 */
class ChannelDedupTest {

    @Test
    fun identityStripsResolutionCodecAndNoiseButKeepsMainEvent() {
        fun id(name: String) = ChannelDedup.identityOf(name)?.let { it.first + "|" + it.second }
        assertEquals("now|sky sports main event", id("NOW: Sky Sports Main Event 4K"))
        assertEquals("now|sky sports main event", id("NOW: Sky Sports Main Event 8K"))
        assertEquals("now|sky sports main event", id("NOW: Sky Sports Main Event"))
        // "FR:" is a different country/source → a different option.
        assertEquals("fr|sky sports main event", id("FR: Sky Sports Main Event"))
        // "EVENT" is channel identity, never strip-tag noise; superscript suffixes fall away.
        assertEquals("uk|sky sport main event", id("UK| Sky Sport Main Event ᴿᴬᵂ"))
        // Codec/audio tags go; the base stays readable.
        assertEquals("uk|tnt sports 1", id("UK: TNT Sports 1 HD HEVC AAC"))
        // fps markers go, in every spelling the provider uses.
        assertEquals("uk|sky sports f1", id("UK: SKY SPORTS F1 50FPS"))
        assertEquals("uk|sky sports f1", id("UK: SKY SPORTS F1 ᴿᴬᵂ ⁵⁰ FPS"))
        assertEquals("uk|tnt sports 1", id("UK: TNT SPORTS 1 1080p50"))
        // A name stripped to nothing never gets deduplicated.
        assertNull(ChannelDedup.identityOf("HD"))
        assertNull(ChannelDedup.identityOf(""))
    }

    @Test
    fun resolutionRankKeepsTheBestCopy() {
        assertEquals(5, ChannelDedup.resolutionRank("NOW: Sky Sports Main Event 8K"))
        assertEquals(4, ChannelDedup.resolutionRank("NOW: Sky Sports Main Event 4K"))
        assertEquals(4, ChannelDedup.resolutionRank("UK| SKY SPORTS UHD HDR"))
        assertEquals(3, ChannelDedup.resolutionRank("TNT Sports 1 FHD"))
        assertEquals(2, ChannelDedup.resolutionRank("TNT Sports 1 HD"))
        assertEquals(0, ChannelDedup.resolutionRank("TNT Sports 1 SD"))
        assertEquals(1, ChannelDedup.resolutionRank("TNT Sports 1"))
        // Resolution+fps spellings keep their resolution class.
        assertEquals(3, ChannelDedup.resolutionRank("TNT Sports 1 1080p50"))
        assertEquals(4, ChannelDedup.resolutionRank("TNT Sports 1 4k60fps"))
    }

    @Test
    fun fpsRankPicksTheBetterCopyInsideAClass() {
        assertEquals(60, ChannelDedup.fpsRank("UK: SKY SPORTS F1 60FPS"))
        assertEquals(50, ChannelDedup.fpsRank("UK: SKY SPORTS F1 50FPS"))
        assertEquals(50, ChannelDedup.fpsRank("UK: SKY SPORTS F1 1080p50"))
        assertEquals(1, ChannelDedup.fpsRank("UK: SKY SPORTS F1 ᴿᴬᵂ ⁵⁰ FPS"))
        assertEquals(0, ChannelDedup.fpsRank("UK: SKY SPORTS F1 HD"))
    }

    @Test
    fun dedupeKeepsOneRowPerResolutionClass() {
        val entries = listOf(
            match(1, "NOW: Sky Sports Main Event", score = 1000),
            match(2, "NOW: Sky Sports Main Event 4K", score = 1000),
            match(3, "FR: Sky Sports Main Event HD", score = 1000),
        )
        val out = ChannelDedup.dedupe(entries.map { it to 0 })
        // 4K and HD are both rendered for the "NOW:" identity; the French copy stays — different country.
        assertEquals(
            listOf(
                "NOW: Sky Sports Main Event 4K",
                "NOW: Sky Sports Main Event",
                "FR: Sky Sports Main Event HD",
            ),
            out.map { it.channelName },
        )
    }

    @Test
    fun dedupeKeepsTheBestCopyInsideEachClass() {
        val entries = listOf(
            match(1, "UK: SKY SPORTS F1 HD"),
            match(2, "UK: SKY SPORTS F1 4K"),
            match(3, "UK: SKY SPORTS F1 8K"),
            match(4, "UK: SKY SPORTS F1 FHD"),
        )
        val out = ChannelDedup.dedupe(entries.map { it to 0 })
        assertEquals(listOf("UK: SKY SPORTS F1 8K", "UK: SKY SPORTS F1 FHD"), out.map { it.channelName })
    }

    @Test
    fun fpsVariantsCollapseAndTheHigherFpsWinsWithinTheSameResolution() {
        val entries = listOf(
            match(1, "UK: SKY SPORTS F1 ᴿᴬᵂ ⁵⁰ FPS"),
            match(2, "UK: SKY SPORTS F1"),
            match(3, "UK: SKY SPORTS F1 50FPS"),
        )
        val out = ChannelDedup.dedupe(entries.map { it to 0 })
        assertEquals(listOf("UK: SKY SPORTS F1 50FPS"), out.map { it.channelName })
    }

    @Test
    fun dedupeOrderIsStableAnd4kComesBeforeTheLowerClass() {
        val lower = match(1, "NOW: Sky Sports Main Event", score = 3)
        val ultra = match(2, "NOW: Sky Sports Main Event 4K", score = 1000)
        val forward = ChannelDedup.dedupe(listOf(ultra to 1000, lower to 3))
        val reversed = ChannelDedup.dedupe(listOf(lower to 3, ultra to 1000))
        // Same two rows either way, 4K first inside the identity, identity order preserved.
        assertEquals(listOf("NOW: Sky Sports Main Event 4K", "NOW: Sky Sports Main Event"), forward.map { it.channelName })
        assertEquals(listOf("NOW: Sky Sports Main Event 4K", "NOW: Sky Sports Main Event"), reversed.map { it.channelName })
    }

    @Test
    fun realVariantsOfTheSameChannelCollapse() {
        val entries = listOf(
            match(10, "UK| TNT SPORTS 1 HD"),
            match(11, "UK: TNT Sports 1"),
            match(12, "UK| TNT SPORTS 2 HD"),
        )
        val out = ChannelDedup.dedupe(entries.map { it to 0 })
        // TNT Sports 1 once (best resolution kept), TNT Sports 2 untouched.
        assertEquals(listOf("UK| TNT SPORTS 1 HD", "UK| TNT SPORTS 2 HD"), out.map { it.channelName })
    }

    @Test
    fun distinctChannelsWithTheSameBrandButDifferentNumbersStay() {
        val entries = listOf(
            match(21, "UK| SKY SPORTS MAIN EVENT"),
            match(22, "UK| SKY SPORTS TENNIS"),
            match(23, "UK| SKY SPORTS F1"),
            match(24, "FR: SKY SPORTS F1"),
        )
        val out = ChannelDedup.dedupe(entries.map { it to 0 })
        // Different channel lines stay; the UK/FR F1 pair stays (different country).
        assertEquals(4, out.size)
    }

    @Test
    fun unrelatedProvidersAndOpaquePpvFeedsNeverMerge() {
        val entries = listOf(
            // Same display name, two different playlist sources: genuinely different options.
            match(30, "UK| TNT SPORTS 1", sourceId = 1),
            match(31, "UK| TNT SPORTS 1", sourceId = 2),
            // Resolution variants inside one source still collapse.
            match(32, "UK| TNT SPORTS 1 HD", sourceId = 1),
            // Opaque event feeds are independent entries.
            match(33, "PPV", sourceId = 1),
            match(34, "PPV 1", sourceId = 1),
            match(35, "Event 1", sourceId = 1),
        )
        val out = ChannelDedup.dedupe(entries.map { it to 0 })
        assertEquals(
            listOf("UK| TNT SPORTS 1 HD", "UK| TNT SPORTS 1", "PPV", "PPV 1", "Event 1"),
            out.map { it.channelName },
        )
        assertEquals(listOf(1L, 2L, 1L, 1L, 1L), out.map { it.sourceId })
    }

    private fun match(id: Long, name: String, score: Int = 0, sourceId: Long? = null) = ChannelMatch(
        channelId = id,
        channelName = name,
        logoUrl = null,
        epgTitle = null,
        score = score,
        sourceId = sourceId,
    )
}
