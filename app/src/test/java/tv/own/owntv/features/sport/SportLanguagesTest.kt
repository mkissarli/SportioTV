package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure language logic behind the Sports-Hub channel filter: the provider's tag, the country →
 * language mapping and the "never wholly empty" filter fallback. No Android, no DAO.
 */
class SportLanguagesTest {

    // --- countryCodeOf: the tag forms a provider actually writes ---

    @Test
    fun colonBarAndSlashTagsAreRecognised() {
        assertEquals("DE", SportLanguages.countryCodeOf("DE: SKY SPORT BUNDESLIGA 1 HD"))
        assertEquals("UK", SportLanguages.countryCodeOf("UK| SKY SPORTS MAIN EVENT"))
        assertEquals("UK", SportLanguages.countryCodeOf("UK | Sky Sports"))
        assertEquals("FR", SportLanguages.countryCodeOf("FR/CANAL+ SPORT"))
        assertEquals("SG", SportLanguages.countryCodeOf("SG: HUB PREMIER 1"))
    }

    @Test
    fun namesWithoutATagHaveNoCountry() {
        assertNull(SportLanguages.countryCodeOf("Sky Sport Bundesliga 1"))
        // A parenthesised marker is not a country tag: "AU" is not followed by a separator.
        assertNull(SportLanguages.countryCodeOf("AU (STAN 01) | Coventry City v Newcastle United"))
    }

    @Test
    fun theBlockFormIsRecognised() {
        // Another provider wraps its tag between two bars — one of them the heavy bar.
        assertEquals("DE", SportLanguages.countryCodeOf("┃DE┃ DAZN LA LIGA"))
        assertEquals("US", SportLanguages.countryCodeOf("| US | NBC SPORTS"))
        assertEquals("GB", SportLanguages.countryCodeOf("| GB | SKY SPORTS"))
        assertEquals("DE", SportLanguages.countryCodeOf("|DE| DAZN LA LIGA"))
    }

    @Test
    fun aTagThatNamesNoCountryHasNoCountry() {
        // A language, a region or a brand is no country — the Sport-Hub's country filter must not
        // read "HINDI" as a country.
        assertNull(SportLanguages.countryCodeOf("┃HINDI┃ ACTOR┃ SHASHI KAPOOR HD"))
        assertNull(SportLanguages.countryCodeOf("┃AFR┃ GENERAL"))
        assertNull(SportLanguages.countryCodeOf("┃CANAL+┃ NPO 1 HD"))
    }

    @Test
    fun aTwoWordTagCarriesItsCountryInTheFirstWord() {
        assertEquals("CA", SportLanguages.countryCodeOf("┃CA EN┃ CBC HD"))
    }

    // --- mapping ---

    @Test
    fun theUsersCountryGroupsMapToTheirLanguage() {
        assertEquals(SportLanguage.GERMAN, SportLanguages.languageOfName("DE: SKY SPORT AUSTRIA 1 HD"))
        assertEquals(SportLanguage.GERMAN, SportLanguages.languageOfName("AT: SKY SPORT AUSTRIA 1"))
        assertEquals(SportLanguage.GERMAN, SportLanguages.languageOfName("CH: SRF ZWEI"))
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfName("UK: SKY SPORTS PREMIER LEAGUE"))
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfName("US: NBC SPORTS"))
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfName("SG: HUB PREMIER 1"))
        assertEquals(SportLanguage.SPANISH, SportLanguages.languageOfName("ES: MOVISTAR LALIGA"))
        assertEquals(SportLanguage.PORTUGUESE, SportLanguages.languageOfName("PT: SPORT TV 1"))
        assertEquals(SportLanguage.FRENCH, SportLanguages.languageOfName("FR: CANAL+ FOOT"))
        assertEquals(SportLanguage.ARABIC, SportLanguages.languageOfName("SA: SSC 1 HD"))
        assertEquals(SportLanguage.ARABIC, SportLanguages.languageOfName("AE: AD SPORTS 1"))
        assertEquals(SportLanguage.ARABIC, SportLanguages.languageOfName("QA: BEIN SPORTS MENA 1"))
        assertEquals(SportLanguage.ARABIC, SportLanguages.languageOfName("EG: ON TIME SPORTS 1"))
        assertEquals(SportLanguage.ARABIC, SportLanguages.languageOfName("MA: ARRYADIA"))
    }

    @Test
    fun dutchItalianPolishAndTurkishMapToTheNewLanguages() {
        assertEquals(SportLanguage.DUTCH, SportLanguages.languageOfName("NL: ESPN 1"))
        assertEquals(SportLanguage.DUTCH, SportLanguages.languageOfName("NL| ESPN 2"))
        assertEquals(SportLanguage.ITALIAN, SportLanguages.languageOfName("IT: SKY SPORT ARENA"))
        assertEquals(SportLanguage.POLISH, SportLanguages.languageOfName("PL: CANAL+ SPORT"))
        assertEquals(SportLanguage.TURKISH, SportLanguages.languageOfName("TR: BEIN SPORTS 1"))
    }

    @Test
    fun theBlockFormMapsToTheSameLanguageAsTheColonForm() {
        assertEquals(SportLanguage.GERMAN, SportLanguages.languageOfName("┃DE┃ DAZN LA LIGA"))
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfName("┃USA┃ E! HD"))
        assertEquals(SportLanguage.SPANISH, SportLanguages.languageOfName("┃ARG┃ TV PUBLICA HD"))
    }

    @Test
    fun theIndianAndKurdishTagsMapToTheirLanguage() {
        assertEquals(SportLanguage.HINDI, SportLanguages.languageOfName("┃HINDI┃ ACTOR┃ SHASHI KAPOOR HD"))
        assertEquals(SportLanguage.HINDI, SportLanguages.languageOfName("┃INDIAN┃ SHAMROO THRILLER CINEMA HD"))
        assertEquals(SportLanguage.URDU, SportLanguages.languageOfName("┃PAKISTANI┃ URDU CINEMA 1 HD"))
        assertEquals(SportLanguage.PUNJABI, SportLanguages.languageOfName("┃PUNJABI┃ PUNJABI CLASSIC CINEMA 1 HD"))
        assertEquals(SportLanguage.BENGALI, SportLanguages.languageOfName("┃BANGLA┃ BANGLA NEWS"))
        assertEquals(SportLanguage.KURDISH, SportLanguages.languageOfName("┃KURD┃ SURYOYO SAT"))
        assertEquals(SportLanguage.KURDISH, SportLanguages.languageOfName("┃KURD DVB-T┃ 4 KURD TV"))
        assertEquals(SportLanguage.KURDISH, SportLanguages.languageOfName("┃KURD-SAT┃ KURDISH SAT"))
    }

    @Test
    fun aTwoWordTagReadsTheLanguageWordFirst() {
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfName("┃CA EN┃ CBC HD"))
        assertEquals(SportLanguage.FRENCH, SportLanguages.languageOfName("┃CA FR┃ CANAL D"))
    }

    @Test
    fun theEuropeanCountriesMapToTheirLanguage() {
        assertEquals(SportLanguage.GREEK, SportLanguages.languageOfName("┃GR┃ NOVA SPORTS"))
        assertEquals(SportLanguage.SWEDISH, SportLanguages.languageOfName("┃SE┃ SVT1 HD"))
        assertEquals(SportLanguage.ALBANIAN, SportLanguages.languageOfName("┃AL┃ TOP CHANNEL HD"))
        assertEquals(SportLanguage.ROMANIAN, SportLanguages.languageOfName("┃RO┃ PRO TV HD"))
        assertEquals(SportLanguage.PERSIAN, SportLanguages.languageOfName("┃IR┃ IRAN INTERNATIONAL HD"))
        assertEquals(SportLanguage.CZECH, SportLanguages.languageOfName("┃CZ┃ FILMY | SÉRIE"))
        assertEquals(SportLanguage.SERBIAN, SportLanguages.languageOfName("┃RS┃ ARENA SPORT"))
        assertEquals(SportLanguage.CROATIAN, SportLanguages.languageOfName("┃HR┃ ARENA SPORT"))
        assertEquals(SportLanguage.BULGARIAN, SportLanguages.languageOfName("┃BG┃ BULGARIA"))
        assertEquals(SportLanguage.HUNGARIAN, SportLanguages.languageOfName("┃HU┃ HUNGARY"))
        assertEquals(SportLanguage.RUSSIAN, SportLanguages.languageOfName("┃RU┃ RUSSIA 1"))
    }

    @Test
    fun anUnknownCountryMapsToNoLanguage() {
        assertNull(SportLanguages.languageOfName("XX: MYSTERY FEED"))
        // Afghanistan broadcasts in Dari and Pashto — a language the app does not place.
        assertNull(SportLanguages.languageOfName("┃AFG┃ AFGHAN INTERNATIONAL HD"))
    }

    @Test
    fun aBrandOrRegionMapsToNoLanguage() {
        // A brand says nothing about the audio — the app never guesses.
        assertNull(SportLanguages.languageOfName("┃CANAL+┃ NPO 1 HD"))
        assertNull(SportLanguages.languageOfName("┃OSN┃ E! HD"))
        assertNull(SportLanguages.languageOfName("┃PPV┃ TENNIS TV FHD"))
        assertNull(SportLanguages.languageOfName("┃UCL┃ ZIGGO SPORT 1 HD"))
        assertNull(SportLanguages.languageOfName("┃AFR┃ GENERAL"))
        assertNull(SportLanguages.languageOfName("┃ASIA┃ PUNJABI"))
        assertNull(SportLanguages.languageOfName("┃F1 TV┃ F1 TV PRO HD"))
    }

    // --- filter ---

    @Test
    fun anEmptySelectionKeepsEverything() {
        val matches = listOf(match("DE: SKY"), match("IT: SKY"), match("Untagged"))
        assertEquals(matches, SportLanguages.filter(matches, emptySet()))
    }

    @Test
    fun onlyTheSelectedLanguagesSurvive() {
        val matches = listOf(
            match("DE: SKY SPORT AUSTRIA 1 HD"),
            match("UK: SKY SPORTS PREMIER LEAGUE"),
            match("NL: ESPN 1"),
            match("AL: SUPER SPORT 3"),
            match("VIP: CANAL+ PREMIER LEAGUE"),
            match("UNPREFIXED PROVIDER FEED"),
        )
        val kept = SportLanguages.filter(matches, setOf(SportLanguage.GERMAN, SportLanguage.ENGLISH))
        assertEquals(
            listOf("DE: SKY SPORT AUSTRIA 1 HD", "UK: SKY SPORTS PREMIER LEAGUE"),
            kept.map { it.channelName },
        )
    }

    @Test
    fun dutchFeedSurvivesOnlyWhenDutchSelected() {
        val matches = listOf(
            match("NL: ESPN 1"),
            match("DE: SKY SPORT BUNDESLIGA 1 HD"),
        )
        val kept = SportLanguages.filter(matches, setOf(SportLanguage.DUTCH))
        assertEquals(listOf("NL: ESPN 1"), kept.map { it.channelName })
    }

    @Test
    fun theFilterNeverLeavesTheListEmpty() {
        val matches = listOf(match("AL: SUPER SPORT 3"), match("XX: MYSTERY FEED"))
        assertEquals(matches, SportLanguages.filter(matches, setOf(SportLanguage.GERMAN)))
    }

    @Test
    fun exactEventChannelsSurviveInAnotherLanguage() {
        val matches = listOf(
            match("FR: MAX PPV 21", isExact = true),
            match("DE: SKY SPORT TENNIS", isExact = false),
            match("AL: SUPER SPORT 3", isExact = false),
        )
        val kept = SportLanguages.filter(matches, setOf(SportLanguage.GERMAN))
        assertEquals(
            listOf("FR: MAX PPV 21", "DE: SKY SPORT TENNIS"),
            kept.map { it.channelName },
        )
    }

    @Test
    fun arabicTagDoesNotStealArgentinaSpanish() {
        assertEquals(SportLanguage.SPANISH, SportLanguages.languageOfName("AR: TYC SPORTS"))
    }

    @Test
    fun onlyArabicSurvivesWhenArabicSelected() {
        val matches = listOf(
            match("SA: SSC 1 HD"),
            match("AE: AD SPORTS 1"),
            match("DE: SKY SPORT BUNDESLIGA 1 HD"),
            match("UK: SKY SPORTS MAIN EVENT"),
        )
        val kept = SportLanguages.filter(matches, setOf(SportLanguage.ARABIC))
        assertEquals(
            listOf("SA: SSC 1 HD", "AE: AD SPORTS 1"),
            kept.map { it.channelName },
        )
    }

    @Test
    fun strictFilterHasNoNeverEmptyFallback() {
        val matches = listOf(match("AL: SUPER SPORT 3"), match("XX: MYSTERY FEED"))
        assertTrue(SportLanguages.filterStrict(matches, setOf(SportLanguage.GERMAN)).isEmpty())
    }

    @Test
    fun strictFilterDropsExcludedEvenWhenExact() {
        val matches = listOf(
            match("DE: Coventry City v Newcastle United HD", isExact = true),
            match("DE: SKY SPORT BUNDESLIGA"),
            match("UK: SKY SPORTS PREMIER LEAGUE"),
            match("Untagged feed"),
        )
        val kept = SportLanguages.filterStrict(matches, emptySet(), setOf(SportLanguage.GERMAN))
        assertEquals(
            listOf("UK: SKY SPORTS PREMIER LEAGUE", "Untagged feed"),
            kept.map { it.channelName },
        )
    }

    @Test
    fun strictFilterKeepsExactThroughThePreferenceStep() {
        val matches = listOf(
            match("FR: MAX PPV 21", isExact = true),
            match("DE: SKY SPORT BUNDESLIGA"),
        )
        val kept = SportLanguages.filterStrict(matches, setOf(SportLanguage.GERMAN))
        assertEquals(
            listOf("FR: MAX PPV 21", "DE: SKY SPORT BUNDESLIGA"),
            kept.map { it.channelName },
        )
    }

    // --- soft country preference (isFromCountry) ---

    @Test
    fun aPreferredCountryTagDecides() {
        assertTrue(SportLanguages.isFromCountry("UK| SKY SPORTS MAIN EVENT", "UK"))
        assertTrue(SportLanguages.isFromCountry("| GB | SKY SPORTS", "UK"))
        assertTrue(SportLanguages.isFromCountry("IE: PREMIER SPORTS 1", "UK"))
        assertTrue(SportLanguages.isFromCountry("US: ESPN", "US"))
        assertTrue(SportLanguages.isFromCountry("FR/CANAL+ SPORT", "FR"))
        assertTrue(SportLanguages.isFromCountry("SA: SSC 1", "AR"))
        assertTrue(SportLanguages.isFromCountry("DE: SKY SPORT BUNDESLIGA", "DE"))
    }

    @Test
    fun aForeignTagBlocksTheBrandWordFallback() {
        // "SKY SPORT AUSTRIA" carries an AT tag: it must not count as UK.
        assertTrue(!SportLanguages.isFromCountry("AT: SKY SPORT AUSTRIA 1 HD", "UK"))
        // DE is German, not UK, even though the name says "sky sport".
        assertTrue(!SportLanguages.isFromCountry("DE: SKY SPORT PREMIER LEAGUE", "UK"))
    }

    @Test
    fun anUntaggedNameFallsBackToBrandWords() {
        assertTrue(SportLanguages.isFromCountry("SKY SPORTS MAIN EVENT", "UK"))
        assertTrue(SportLanguages.isFromCountry("SKY SPORT PREMIER LEAGUE", "UK"))
        assertTrue(SportLanguages.isFromCountry("TNT SPORTS 1", "UK"))
        assertTrue(SportLanguages.isFromCountry("ESPN+", "US"))
        assertTrue(SportLanguages.isFromCountry("CANAL+ FOOT", "FR"))
        assertTrue(SportLanguages.isFromCountry("beIN SPORTS 1", "AR"))
        assertTrue(SportLanguages.isFromCountry("ARABIC: ALKASS 1", "AR"))
        assertTrue(!SportLanguages.isFromCountry("HUB PREMIER 1", "UK"))
    }

    @Test
    fun anUnknownPreferenceMatchesNothing() {
        assertTrue(!SportLanguages.isFromCountry("UK| SKY SPORTS", "XX"))
        assertTrue(!SportLanguages.isFromCountry("UK| SKY SPORTS", ""))
    }

    // --- helpers ---

    private fun match(name: String, isExact: Boolean = false) = ChannelMatch(
        channelId = 1L,
        channelName = name,
        logoUrl = null,
        epgTitle = null,
        score = 10,
        isExact = isExact,
    )
}
