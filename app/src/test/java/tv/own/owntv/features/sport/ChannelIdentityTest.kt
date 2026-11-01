package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic identity rules from the channel-matching design (no Android, no database). */
class ChannelIdentityTest {

    @Test
    fun providerSpellingsResolveToTheSameSkyChannel() {
        val key = ChannelIdentityResolver.resolve("SKY SPORTS MAIN EVENT HD")?.key
        assertNotNull(key)
        assertEquals(key, ChannelIdentityResolver.resolve("Sky Main Event")?.key)
        assertEquals(key, ChannelIdentityResolver.resolve("SKY MAIN EVENT")?.key)
        assertEquals(key, ChannelIdentityResolver.resolve("NOW: SKY SPORTS MAIN EVENT")?.key)
        assertEquals(key, ChannelIdentityResolver.resolve("SKY SPORT MAIN EVENT UK")?.key)
    }

    @Test
    fun plusAndNumberedSkyChannelsStayDistinct() {
        val main = ChannelIdentityResolver.resolve("Sky Sports Main Event")?.key
        val plus = ChannelIdentityResolver.resolve("Sky Sports+")?.key
        val one = ChannelIdentityResolver.resolve("Sky Sports 1")?.key
        assertNotNull(plus)
        assertNotNull(one)
        assertNotEquals(main, plus)
        assertNotEquals(main, one)
        assertNotEquals(plus, one)
    }

    @Test
    fun sportsBrandSeparatesNewsAndNumberedChannels() {
        // Sky Sports News is not Sky News, and Sky Sports 1 is not the entertainment channel Sky 1.
        assertNotEquals(
            ChannelIdentityResolver.resolve("Sky Sports News")?.key,
            ChannelIdentityResolver.resolve("Sky News")?.key,
        )
        assertNotEquals(
            ChannelIdentityResolver.resolve("Sky Sports 1")?.key,
            ChannelIdentityResolver.resolve("Sky 1")?.key,
        )
    }

    @Test
    fun qualityAndProviderWordsDoNotCreateIdentities() {
        assertNull(ChannelIdentityResolver.resolve("SPORTS HD"))
        assertNull(ChannelIdentityResolver.resolve("LIVE 4K"))
        assertNull(ChannelIdentityResolver.resolve(""))
    }

    @Test
    fun genericWordsDoNotFuzzyMatchAnotherChannel() {
        assertNull(ChannelIdentityResolver.relation("Sky Sports+", "Sky Sports Main Event"))
        assertNull(ChannelIdentityResolver.relation("Sky Sports 1", "Sky Sports 2"))
        assertNull(ChannelIdentityResolver.relation("Sky Sports", "Sky Sports Football"))
    }

    @Test
    fun missingBrandIsAnInferredMatch() {
        val relation = ChannelIdentityResolver.relation("Main Event", "Sky Sports Main Event")
        assertEquals(IdentityBasis.INFERRED, relation?.basis)
    }

    @Test
    fun ambiguousBrandlessNamesNeverMergeByInference() {
        assertNull(ChannelIdentityResolver.relation("Arena", "Sky Sports Arena"))
        assertNull(ChannelIdentityResolver.relation("Mix", "Sky Sports Mix"))
        assertNull(ChannelIdentityResolver.relation("Premier", "Sky Sports Premier League"))
        assertNull(ChannelIdentityResolver.relation("Plus", "Sky Sports+"))
        assertNull(ChannelIdentityResolver.relation("1", "Sky Sports 1"))
    }

    @Test
    fun brandlessNamesFromDifferentTerritoriesDoNotMerge() {
        assertNull(ChannelIdentityResolver.relation("UK: MAIN EVENT", "US: MAIN EVENT"))
        // A branded row is territory-independent for this purpose.
        assertNotNull(
            ChannelIdentityResolver.relation(
                "UK: SKY SPORTS MAIN EVENT",
                "US: SKY SPORTS MAIN EVENT",
            ),
        )
    }

    @Test
    fun numberedAndPlusVariantsNeverInferIntoEachOther() {
        assertNull(ChannelIdentityResolver.relation("Sky Sports 1", "Sky Sports 2"))
        assertNull(ChannelIdentityResolver.relation("Sky Sports 1", "Sky Sports+"))
        assertNull(ChannelIdentityResolver.relation("Sky Sports+", "Sky Sports Main Event"))
    }

    @Test
    fun identicalNamesAreExactAndDifferentSpellingsAreAlias() {
        assertEquals(
            IdentityBasis.EXACT,
            ChannelIdentityResolver.relation("Sky Sports Main Event", "Sky Sports Main Event")?.basis,
        )
        assertEquals(
            IdentityBasis.ALIAS,
            ChannelIdentityResolver.relation("SKY SPORTS MAIN EVENT HD", "Sky Sports Main Event")?.basis,
        )
    }

    @Test
    fun providerTagAndSpellingResolveAsAlias() {
        val relation = ChannelIdentityResolver.relation(
            "NOW: SKY SPORTS MAIN EVENT 4K",
            "Sky Sports Main Event",
        )
        assertEquals(IdentityBasis.ALIAS, relation?.basis)
    }

    @Test
    fun territorySportAndTypeAreDerivedFromTheName() {
        val skyF1 = ChannelIdentityResolver.resolve("UK: SKY SPORTS F1 HD")
        assertEquals("sky", skyF1?.broadcaster)
        assertEquals("UK", skyF1?.territory)
        assertEquals(SportClass.MOTORSPORT, skyF1?.sport)
        assertTrue(skyF1?.specialist == true)

        val ppv = ChannelIdentityResolver.resolve("US: UFC PPV 1")
        assertEquals(ChannelType.PPV, ppv?.type)
        assertEquals(SportClass.COMBAT, ppv?.sport)
    }

    @Test
    fun broadcasterRebrandAliasesResolve() {
        assertEquals(
            ChannelIdentityResolver.resolve("BT Sport 1")?.key,
            ChannelIdentityResolver.resolve("TNT Sports 1")?.key,
        )
        assertEquals(
            ChannelIdentityResolver.resolve("Canal+ Foot")?.key,
            ChannelIdentityResolver.resolve("Canal Plus Foot")?.key,
        )
    }

    @Test
    fun searchTermsStayCompactAndDistinctive() {
        val terms = ChannelIdentityResolver.searchTerms("SKY SPORTS MAIN EVENT 4K")
        assertTrue(terms.contains("main event"))
        assertTrue(terms.size <= 6)
        assertFalse(terms.contains("4k"))
    }

    @Test
    fun leagueIdsMapToASportClass() {
        assertEquals(SportClass.FOOTBALL, SportClass.fromLeagueId("soccer.eng.1"))
        assertEquals(SportClass.TENNIS, SportClass.fromLeagueId("tennis.atp"))
        assertEquals(SportClass.MOTORSPORT, SportClass.fromLeagueId("racing.f1"))
        assertEquals(SportClass.COMBAT, SportClass.fromLeagueId("mma.ufc"))
        assertEquals(SportClass.UNKNOWN, SportClass.fromLeagueId("unknown.league"))
    }
}
