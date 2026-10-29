package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Test

/** The catalogue's `espnLeague = "wtm"` marks the leagues whose carrier rows live on a sport page. */
class GuideBoardTest {

    @Test
    fun theCatalogueMarksExactlyTheGuideBackedSports() {
        assertEquals(
            mapOf("cycling.uci" to "cycling", "athletics.world" to "athletics", "boxing.world" to "boxing"),
            GuideBoard.sports,
        )
        assertEquals(listOf("cycling", "athletics", "boxing"), GuideBoard.sportPages)
    }
}
