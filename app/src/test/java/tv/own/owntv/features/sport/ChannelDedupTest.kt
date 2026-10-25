package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rule-4 identity + dedup + ordering: "NOW: Sky Sports Main Event 4K" and
 * "NOW: Sky Sports Main Event" are one channel (keep the 4K copy), "FR: …" is a different
 * option, "Main Event" is never stripped as noise, and one identity appears once, keeping
 * its best-resolution variant.
 */
class ChannelDedupTest {

    @Test
    fun identityStripsResolutionCodecAndNoiseButKeepsMainEvent() {
        fun id(name: String) = ChannelDedup.identityOf(name)?.let { it.first + "|" + it.second }
        assertEquals("now|sky sports main event", id("NOW: Sky Sports Main Event 4K"))
        assertEquals("now|sky sports main event", id("NOW: Sky Sports Main Event"))
        // "FR:" is a different country/source → a different option.
        assertEquals("fr|sky sports main event", id("FR: Sky Sports Main Event"))
        // "EVENT" is channel identity, never strip-tag noise; superscript suffixes fall away.
        assertEquals("uk|sky sport main event", id("UK| Sky Sport Main Event ᴿᴬᵂ"))
        // Codec/audio tags go; the base stays readable.
        assertEquals("uk|tnt sports 1", id("UK: TNT Sports 1 HD HEVC AAC"))
        // A name stripped to nothing never gets deduplicated.
        assertNull(ChannelDedup.identityOf("HD"))
        assertNull(ChannelDedup.identityOf(""))
    }

    @Test
    fun resolutionRankKeepsTheBestCopy() {
        assertEquals(4, ChannelDedup.resolutionRank("NOW: Sky Sports Main Event 4K"))
        assertEquals(4, ChannelDedup.resolutionRank("UK| SKY SPORTS UHD HDR"))
        assertEquals(3, ChannelDedup.resolutionRank("TNT Sports 1 FHD"))
        assertEquals(2, ChannelDedup.resolutionRank("TNT Sports 1 HD"))
        assertEquals(0, ChannelDedup.resolutionRank("TNT Sports 1 SD"))
        assertEquals(1, ChannelDedup.resolutionRank("TNT Sports 1"))
    }

    @Test
    fun dedupeKeepsOneEntryPerIdentityAndTheHighestResolution() {
        val entries = listOf(
            match(1, "NOW: Sky Sports Main Event", score = 1000),
            match(2, "NOW: Sky Sports Main Event 4K", score = 1000),
            match(3, "FR: Sky Sports Main Event HD", score = 1000),
        )
        val out = ChannelDedup.dedupe(entries.map { it to 0 })
        // 4K wins the "NOW:" identity; the French copy stays — different country.
        assertEquals(listOf("NOW: Sky Sports Main Event 4K", "FR: Sky Sports Main Event HD"), out.map { it.channelName })
    }

    @Test
    fun dedupeUsesTheEvidenceOrderAndNeverDoublesAcrossRankings() {
        val now4k = match(1, "NOW: Sky Sports Main Event", score = 3)
        val nowHd = match(2, "NOW: Sky Sports Main Event 4K", score = 1000)
        val out = ChannelDedup.dedupe(listOf(nowHd to 1000, now4k to 3))
        // Sorted input put the strong variant first; both identity slots collapse to one entry.
        assertEquals(listOf("NOW: Sky Sports Main Event 4K"), out.map { it.channelName })

        val reversed = ChannelDedup.dedupe(listOf(now4k to 3, nowHd to 1000))
        // Same identity again — resolution rank decides inside the group, not arrival order.
        assertEquals(listOf("NOW: Sky Sports Main Event 4K"), reversed.map { it.channelName })
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

    private fun match(id: Long, name: String, score: Int = 0) = ChannelMatch(
        channelId = id,
        channelName = name,
        logoUrl = null,
        epgTitle = null,
        score = score,
    )
}
