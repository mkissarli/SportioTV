package tv.own.owntv.features.sport

import androidx.annotation.StringRes
import java.util.Locale
import tv.own.owntv.R

/**
 * A broadcast language a user channel can be in, inferred from the tag a provider puts at the
 * start of the name ("DE: Sky Sport…", "UK| Sky Sports…", "┃HINDI┃ …").
 *
 * A provider's country tag is not the audio language 1:1 — Germany/Austria/Switzerland all carry
 * German, the UK/US/Singapore all carry English — which is why several countries map to one
 * [SportLanguage] (see [SportLanguages.CountryLanguages]). A provider may also tag by *language*
 * instead of by country ("┃HINDI┃", "┃CA EN┃"); those tags are read by the same table
 * ([SportLanguages.TagLanguages]).
 */
enum class SportLanguage(@StringRes val labelRes: Int) {
    GERMAN(R.string.sport_language_german),
    ENGLISH(R.string.sport_language_english),
    SPANISH(R.string.sport_language_spanish),
    PORTUGUESE(R.string.sport_language_portuguese),
    FRENCH(R.string.sport_language_french),
    DUTCH(R.string.sport_language_dutch),
    ITALIAN(R.string.sport_language_italian),
    POLISH(R.string.sport_language_polish),
    TURKISH(R.string.sport_language_turkish),
    ARABIC(R.string.sport_language_arabic),
    GREEK(R.string.sport_language_greek),
    SWEDISH(R.string.sport_language_swedish),
    ALBANIAN(R.string.sport_language_albanian),
    ROMANIAN(R.string.sport_language_romanian),
    CZECH(R.string.sport_language_czech),
    SERBIAN(R.string.sport_language_serbian),
    CROATIAN(R.string.sport_language_croatian),
    BULGARIAN(R.string.sport_language_bulgarian),
    HUNGARIAN(R.string.sport_language_hungarian),
    LATVIAN(R.string.sport_language_latvian),
    LITHUANIAN(R.string.sport_language_lithuanian),
    SLOVENIAN(R.string.sport_language_slovenian),
    RUSSIAN(R.string.sport_language_russian),
    PERSIAN(R.string.sport_language_persian),
    HEBREW(R.string.sport_language_hebrew),
    KURDISH(R.string.sport_language_kurdish),
    HINDI(R.string.sport_language_hindi),
    URDU(R.string.sport_language_urdu),
    PUNJABI(R.string.sport_language_punjabi),
    BENGALI(R.string.sport_language_bengali),
    TAMIL(R.string.sport_language_tamil),
    TELUGU(R.string.sport_language_telugu),
    KANNADA(R.string.sport_language_kannada),
    GUJARATI(R.string.sport_language_gujarati),
    MARATHI(R.string.sport_language_marathi),
    MALAYALAM(R.string.sport_language_malayalam),
    THAI(R.string.sport_language_thai),
    MALAY(R.string.sport_language_malay),
    VIETNAMESE(R.string.sport_language_vietnamese),
    INDONESIAN(R.string.sport_language_indonesian),
    CHINESE(R.string.sport_language_chinese),
    KOREAN(R.string.sport_language_korean),
    JAPANESE(R.string.sport_language_japanese),
    NORWEGIAN(R.string.sport_language_norwegian),
    DANISH(R.string.sport_language_danish),
    FINNISH(R.string.sport_language_finnish),
    UKRAINIAN(R.string.sport_language_ukrainian),
}

/**
 * Pure, stateless helpers that turn a provider's tag into a [SportLanguage] and filter the
 * Sports-Hub channel matches by the viewer's preferred languages.
 *
 * The tag is what the provider writes at the start of the name — a country code ("DE:", "UK|",
 * "┃DE┃"), a language ("┃HINDI┃"), a region ("┃AFR┃") or a brand ("┃CANAL+┃"). The forms are read
 * by [CountryTags]; what a tag *means* is decided here: a country stands for the language of that
 * country ([CountryLanguages]), a language name for itself ([TagLanguages]), and a brand or a
 * region for no language at all — a brand says nothing about the audio, and the app never guesses
 * (those channels stay in "Other").
 *
 * With a filter active, a tag naming any other language is dropped, so a German-only viewer no
 * longer sees the Italian, Albanian or Serbian feeds of the same match. The user picks the
 * languages once (see `SportsPrefs` / the Sport settings).
 *
 * Like [SportNameMatch], everything here is dependency-free and unit-testable without Android.
 */
object SportLanguages {

    /** Display order in the picker and in the "Language: …" label. */
    val order: List<SportLanguage> = listOf(
        SportLanguage.GERMAN,
        SportLanguage.ENGLISH,
        SportLanguage.SPANISH,
        SportLanguage.PORTUGUESE,
        SportLanguage.FRENCH,
        SportLanguage.DUTCH,
        SportLanguage.ITALIAN,
        SportLanguage.POLISH,
        SportLanguage.TURKISH,
        SportLanguage.ARABIC,
        SportLanguage.GREEK,
        SportLanguage.SWEDISH,
        SportLanguage.ALBANIAN,
        SportLanguage.ROMANIAN,
        SportLanguage.CZECH,
        SportLanguage.SERBIAN,
        SportLanguage.CROATIAN,
        SportLanguage.BULGARIAN,
        SportLanguage.HUNGARIAN,
        SportLanguage.LATVIAN,
        SportLanguage.LITHUANIAN,
        SportLanguage.SLOVENIAN,
        SportLanguage.RUSSIAN,
        SportLanguage.PERSIAN,
        SportLanguage.HEBREW,
        SportLanguage.KURDISH,
        SportLanguage.HINDI,
        SportLanguage.URDU,
        SportLanguage.PUNJABI,
        SportLanguage.BENGALI,
        SportLanguage.TAMIL,
        SportLanguage.TELUGU,
        SportLanguage.KANNADA,
        SportLanguage.GUJARATI,
        SportLanguage.MARATHI,
        SportLanguage.MALAYALAM,
        SportLanguage.THAI,
        SportLanguage.MALAY,
        SportLanguage.VIETNAMESE,
        SportLanguage.INDONESIAN,
        SportLanguage.CHINESE,
        SportLanguage.KOREAN,
        SportLanguage.JAPANESE,
        SportLanguage.NORWEGIAN,
        SportLanguage.DANISH,
        SportLanguage.FINNISH,
        SportLanguage.UKRAINIAN,
    )

    /**
     * An empty set means "all languages" — no filter. That is the first-run default: hiding a
     * broadcaster the TV guide names (Sky Sports for a Premier League game) is worse than showing
     * a few foreign feeds, and the country tiers already put UK first.
     */
    val default: Set<SportLanguage> = emptySet()

    /**
     * The country code of the tag at the start of [name], upper-cased, or null when the name
     * carries no country tag. A tag that names a language ("HINDI"), a region ("AFR") or a brand
     * ("CANAL+") is no country — the callers that need a country (the Sport-Hub's hard country
     * filter) must not read a language as one.
     */
    fun countryCodeOf(name: String): String? {
        val tag = CountryTags.tagOf(name) ?: return null
        return countryOf(tag.normalized)
    }

    /** The provider's tag at the start of [name], upper-cased, or null. */
    fun tagOf(name: String): String? = CountryTags.tagOf(name)?.normalized

    /**
     * The provider's tag words at the start of [name], lower case — they are not part of the
     * channel's name and must not weigh in a match ("┃CA EN┃ CBC" is "CBC").
     */
    fun tagWordsOf(name: String): Set<String> =
        CountryTags.tagOf(name)?.words?.toSet() ?: emptySet()

    /**
     * The [SportLanguage] a provider's tag stands for, or null when the tag names no language
     * the app knows (a brand, a region, a country the app cannot place).
     *
     * A two-word tag is read word by word: the language word wins ("CA EN" → English, "CA FR" →
     * French), the country word after it ("KURD DVB-T" → Kurdish).
     */
    fun languageOfTag(tag: String): SportLanguage? {
        val key = tag.trim().uppercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }.joinToString(" ")
        if (key.isEmpty()) return null
        CountryLanguages[key]?.let { return it }
        TagLanguages[key]?.let { return it }
        val words = key.split(' ')
        if (words.size == 2) {
            val first = words[0]
            val last = words[1]
            // "CA EN": the first word names a country we know, the second one the language.
            if (isKnownCountry(first) || first in TagLanguages) {
                TagLanguages[last]?.let { return it }
                CountryLanguages[last]?.let { return it }
            }
            // "KURD DVB-T": the first word names the language, the second one is a mark.
            TagLanguages[first]?.let { return it }
            CountryLanguages[first]?.let { return it }
        }
        return null
    }

    /** Convenience: the language of the tag at the start of [name], or null. */
    fun languageOfName(name: String): SportLanguage? {
        val tag = CountryTags.tagOf(name) ?: return null
        return languageOfTag(tag.normalized)
    }

    /** The [SportLanguage] a country tag stands for, or null when the tag is not a known country. */
    fun languageOfCountry(countryCode: String): SportLanguage? =
        CountryLanguages[countryCode.uppercase(Locale.ROOT)]

    /** Whether [countryCode] is a country the app knows, whatever its language. */
    fun isKnownCountry(countryCode: String): Boolean = countryOf(countryCode.uppercase(Locale.ROOT)) != null

    /**
     * The country code of a tag, or null when the tag names no country. A two-word tag ("CA EN")
     * carries the country in its first word.
     */
    private fun countryOf(tag: String): String? {
        val first = tag.split(' ').filter { it.isNotBlank() }.firstOrNull() ?: return null
        if (first.length in 2..3 && first.all { it.isLetter() } && first in KnownCountries) return first
        return null
    }

    /**
     * Country tag → language. Codes follow IPTV convention rather than strict ISO: providers write
     * "UK" where ISO says "GB", and Singapore ("SG") is an English feed. Extend this map — not the
     * enum — when a new country turns up; a code that is not here is a language the app cannot place,
     * and its channels are dropped while a filter is active (see [filter]).
     */
    private val CountryLanguages: Map<String, SportLanguage> = mapOf(
        // German
        "DE" to SportLanguage.GERMAN,
        "AT" to SportLanguage.GERMAN,
        "CH" to SportLanguage.GERMAN,
        // English
        "UK" to SportLanguage.ENGLISH,
        "GB" to SportLanguage.ENGLISH,
        "US" to SportLanguage.ENGLISH,
        "USA" to SportLanguage.ENGLISH,
        "SG" to SportLanguage.ENGLISH,
        "IE" to SportLanguage.ENGLISH,
        "AU" to SportLanguage.ENGLISH,
        "NZ" to SportLanguage.ENGLISH,
        "CA" to SportLanguage.ENGLISH,
        "ZA" to SportLanguage.ENGLISH,
        "NG" to SportLanguage.ENGLISH,
        "GH" to SportLanguage.ENGLISH,
        "KE" to SportLanguage.ENGLISH,
        "TZ" to SportLanguage.ENGLISH,
        "UG" to SportLanguage.ENGLISH,
        "MT" to SportLanguage.ENGLISH,
        // The Philippines broadcast in English.
        "PH" to SportLanguage.ENGLISH,
        // Spanish
        "ES" to SportLanguage.SPANISH,
        "MX" to SportLanguage.SPANISH,
        "AR" to SportLanguage.SPANISH,
        "CO" to SportLanguage.SPANISH,
        "CL" to SportLanguage.SPANISH,
        "PE" to SportLanguage.SPANISH,
        "VE" to SportLanguage.SPANISH,
        "UY" to SportLanguage.SPANISH,
        "CU" to SportLanguage.SPANISH,
        "EC" to SportLanguage.SPANISH,
        "BO" to SportLanguage.SPANISH,
        "PY" to SportLanguage.SPANISH,
        "PR" to SportLanguage.SPANISH,
        "DO" to SportLanguage.SPANISH,
        "RDOM" to SportLanguage.SPANISH,
        "DOMR" to SportLanguage.SPANISH,
        "HONDURAS" to SportLanguage.SPANISH,
        "GUATEMALA" to SportLanguage.SPANISH,
        "CR" to SportLanguage.SPANISH,
        "GT" to SportLanguage.SPANISH,
        "HN" to SportLanguage.SPANISH,
        "NI" to SportLanguage.SPANISH,
        "PA" to SportLanguage.SPANISH,
        "SV" to SportLanguage.SPANISH,
        "JM" to SportLanguage.SPANISH,
        "TT" to SportLanguage.SPANISH,
        "BZ" to SportLanguage.SPANISH,
        "CUBA" to SportLanguage.SPANISH,
        "ARG" to SportLanguage.SPANISH,
        "CHL" to SportLanguage.SPANISH,
        "ECU" to SportLanguage.SPANISH,
        "PER" to SportLanguage.SPANISH,
        "COL" to SportLanguage.SPANISH,
        "URY" to SportLanguage.SPANISH,
        "VEN" to SportLanguage.SPANISH,
        // Portuguese
        "PT" to SportLanguage.PORTUGUESE,
        "BR" to SportLanguage.PORTUGUESE,
        "AO" to SportLanguage.PORTUGUESE,
        "MZ" to SportLanguage.PORTUGUESE,
        // French
        "FR" to SportLanguage.FRENCH,
        "BE" to SportLanguage.FRENCH,
        "LU" to SportLanguage.FRENCH,
        "SN" to SportLanguage.FRENCH,
        "CI" to SportLanguage.FRENCH,
        "CD" to SportLanguage.FRENCH,
        "CM" to SportLanguage.FRENCH,
        "ML" to SportLanguage.FRENCH,
        "BF" to SportLanguage.FRENCH,
        "NE" to SportLanguage.FRENCH,
        "TD" to SportLanguage.FRENCH,
        "MG" to SportLanguage.FRENCH,
        "RE" to SportLanguage.FRENCH,
        "YT" to SportLanguage.FRENCH,
        "HT" to SportLanguage.FRENCH,
        "HAT" to SportLanguage.FRENCH,
        "DRC" to SportLanguage.FRENCH,
        // Dutch
        "NL" to SportLanguage.DUTCH,
        "AN" to SportLanguage.DUTCH,
        // Italian
        "IT" to SportLanguage.ITALIAN,
        "SM" to SportLanguage.ITALIAN,
        // Polish
        "PL" to SportLanguage.POLISH,
        // Turkish
        "TR" to SportLanguage.TURKISH,
        // Arabic
        "SA" to SportLanguage.ARABIC,
        "AE" to SportLanguage.ARABIC,
        "QA" to SportLanguage.ARABIC,
        "EG" to SportLanguage.ARABIC,
        "MA" to SportLanguage.ARABIC,
        "DZ" to SportLanguage.ARABIC,
        "TN" to SportLanguage.ARABIC,
        "IQ" to SportLanguage.ARABIC,
        "JO" to SportLanguage.ARABIC,
        "KW" to SportLanguage.ARABIC,
        "BH" to SportLanguage.ARABIC,
        "OM" to SportLanguage.ARABIC,
        "QAT" to SportLanguage.ARABIC,
        "SOM" to SportLanguage.ARABIC,
        "SYR" to SportLanguage.ARABIC,
        "KU" to SportLanguage.ARABIC,
        "LB" to SportLanguage.ARABIC,
        "LY" to SportLanguage.ARABIC,
        "SD" to SportLanguage.ARABIC,
        "YE" to SportLanguage.ARABIC,
        "SY" to SportLanguage.ARABIC,
        "PS" to SportLanguage.ARABIC,
        "SO" to SportLanguage.ARABIC,
        "SU" to SportLanguage.ARABIC,
        "DJ" to SportLanguage.ARABIC,
        "KM" to SportLanguage.ARABIC,
        "MR" to SportLanguage.ARABIC,
        "ER" to SportLanguage.ARABIC,
        // Greek
        "GR" to SportLanguage.GREEK,
        "CY" to SportLanguage.GREEK,
        // Swedish
        "SE" to SportLanguage.SWEDISH,
        // Albanian
        "AL" to SportLanguage.ALBANIAN,
        // Romanian
        "RO" to SportLanguage.ROMANIAN,
        // Persian
        "IR" to SportLanguage.PERSIAN,
        // Czech
        "CZ" to SportLanguage.CZECH,
        // Serbian
        "RS" to SportLanguage.SERBIAN,
        "MK" to SportLanguage.SERBIAN,
        "ME" to SportLanguage.SERBIAN,
        "BA" to SportLanguage.SERBIAN,
        // Croatian
        "HR" to SportLanguage.CROATIAN,
        // Bulgarian
        "BG" to SportLanguage.BULGARIAN,
        // Hungarian
        "HU" to SportLanguage.HUNGARIAN,
        // Latvian
        "LV" to SportLanguage.LATVIAN,
        // Lithuanian
        "LT" to SportLanguage.LITHUANIAN,
        // Slovenian
        "SI" to SportLanguage.SLOVENIAN,
        // Russian
        "RU" to SportLanguage.RUSSIAN,
        // Hebrew
        "IL" to SportLanguage.HEBREW,
        // Hindi
        "IN" to SportLanguage.HINDI,
        // Urdu
        "PK" to SportLanguage.URDU,
        // Bengali
        "BD" to SportLanguage.BENGALI,
        // Thai
        "TH" to SportLanguage.THAI,
        // Malay
        "MY" to SportLanguage.MALAY,
        // Vietnamese
        "VN" to SportLanguage.VIETNAMESE,
        // Indonesian
        "ID" to SportLanguage.INDONESIAN,
        // Chinese
        "CN" to SportLanguage.CHINESE,
        "HK" to SportLanguage.CHINESE,
        "TW" to SportLanguage.CHINESE,
        "TWN" to SportLanguage.CHINESE,
        "MO" to SportLanguage.CHINESE,
        // Korean
        "KR" to SportLanguage.KOREAN,
        // Japanese
        "JP" to SportLanguage.JAPANESE,
        // Norwegian
        "NO" to SportLanguage.NORWEGIAN,
        "NR" to SportLanguage.NORWEGIAN,
        // Danish
        "DK" to SportLanguage.DANISH,
        // Finnish
        "FI" to SportLanguage.FINNISH,
        // Ukrainian
        "UA" to SportLanguage.UKRAINIAN,
        "UKR" to SportLanguage.UKRAINIAN,
    )

    /**
     * Tag → language for the tags that name a *language* instead of a country. "HINDI" and
     * "CA EN" are such tags; the country table has no say in them.
     */
    private val TagLanguages: Map<String, SportLanguage> = mapOf(
        "EN" to SportLanguage.ENGLISH,
        "ENGLISH" to SportLanguage.ENGLISH,
        "ES" to SportLanguage.SPANISH,
        "FR" to SportLanguage.FRENCH,
        "DE" to SportLanguage.GERMAN,
        "IT" to SportLanguage.ITALIAN,
        "PT" to SportLanguage.PORTUGUESE,
        "NL" to SportLanguage.DUTCH,
        "PL" to SportLanguage.POLISH,
        "TR" to SportLanguage.TURKISH,
        "HINDI" to SportLanguage.HINDI,
        "INDIAN" to SportLanguage.HINDI,
        "BHOJPURI" to SportLanguage.HINDI,
        "DUBBED" to SportLanguage.HINDI,
        "URDU" to SportLanguage.URDU,
        "PAKISTANI" to SportLanguage.URDU,
        "PUNJABI" to SportLanguage.PUNJABI,
        "BANGLA" to SportLanguage.BENGALI,
        "TAMIL" to SportLanguage.TAMIL,
        "TELUGU" to SportLanguage.TELUGU,
        "KANNADA" to SportLanguage.KANNADA,
        "GUJARATI" to SportLanguage.GUJARATI,
        "MARATHI" to SportLanguage.MARATHI,
        "MALAYALAM" to SportLanguage.MALAYALAM,
        "KURD" to SportLanguage.KURDISH,
        "KURD-SAT" to SportLanguage.KURDISH,
        "LATIN" to SportLanguage.SPANISH,
        "LAT" to SportLanguage.SPANISH,
        "LA" to SportLanguage.SPANISH,
        "TV" to SportLanguage.SPANISH,
        "CAR" to SportLanguage.ENGLISH,
        "EXYU" to SportLanguage.CROATIAN,
        "AF" to SportLanguage.URDU,
    )

    /**
     * The countries the app knows by their code. Used to tell a country tag from a language or a
     * brand — "GB" and "UK" are the same country, "USA" the alpha-3 spelling of "US".
     */
    private val KnownCountries: Set<String> =
        CountryLanguages.keys + setOf("GB", "US", "UK", "USA")

    /**
     * The matches to show for [languages]. An empty [languages] means "all" and returns [matches]
     * unchanged. Otherwise only channels whose tag maps to one of [languages] are kept: a
     * channel with no tag, or a tag that is not a known country, is dropped. This is deliberately
     * strict — a provider prefixes a foreign feed with a quality/brand mark ("VIP: CANAL+…",
     * "SAT: ARENA…", "8K: beIN…") that is not a country, and those must not fill the limited slots
     * ahead of a real selected-language feed. If the filter would leave nothing, the full list is
     * returned, so the pane is never empty.
     *
     * Exact per-event channels ([ChannelMatch.isExact] — the name itself carries both sides, e.g. a
     * tennis PPV named for the duel) always survive: they are the event itself, not a
     * foreign-language feed of it.
     */
    fun filter(matches: List<ChannelMatch>, languages: Set<SportLanguage>): List<ChannelMatch> {
        if (languages.isEmpty()) return matches
        val kept = matches.filter { it.isExact || keeps(it.channelName, languages) }
        return kept.ifEmpty { matches }
    }

    /**
     * Strict variant of [filter] without the never-empty fallback: excluded languages are dropped
     * first (an exact per-event channel does NOT survive its language being excluded — excluding is
     * an explicit "never show", filtering is a preference), then only channels in [languages] are
     * kept, with exact per-event channels ([ChannelMatch.isExact]) always surviving the preference
     * step. An empty [languages] means "all" (minus [excluded]). A short list stays short — no
     * foreign feed is padded back in (see `docs/sport-roadmap.md` Punkt 4, Mindestquote).
     */
    fun filterStrict(
        matches: List<ChannelMatch>,
        languages: Set<SportLanguage>,
        excluded: Set<SportLanguage> = emptySet(),
    ): List<ChannelMatch> {
        val kept = matches.filterNot { languageOfName(it.channelName) in excluded }
        if (languages.isEmpty()) return kept
        return kept.filter { it.isExact || keeps(it.channelName, languages) }
    }

    private fun keeps(channelName: String, languages: Set<SportLanguage>): Boolean {
        val language = languageOfName(channelName) ?: return false
        return language in languages
    }

    // ---- Soft country preference (Sky Sports over US feeds) ------------------------------------

    /**
     * Whether a channel belongs to [code]'s broadcaster pool — the soft "prefer these channels"
     * signal the matcher applies as a score boost. It is *not* a filter: channels outside the pool
     * stay listed, they just rank below.
     *
     * A country tag wins: `UK| SKY SPORTS` is UK, `US: ESPN` is US, and a tag of another known
     * country blocks the brand-word fallback (`AT: SKY SPORT AUSTRIA` must not count as UK). With
     * no tag at all, the brand words decide — many providers leave the big sports feeds untagged.
     * [code] is one of [preferredCountryOptions]'s keys; anything else returns false.
     */
    fun isFromCountry(name: String, code: String): Boolean {
        // NOW is Sky's UK streaming brand ("Now Entertainment"), so "NOW: SKY SPORTS …" is a UK feed.
        if (code == UkCode && SportNameMatch.normalize(name).trim().startsWith(NowBrand)) return true
        val tags = PreferredCountryTags[code] ?: return false
        val country = countryCodeOf(name)
        if (country != null) return country in tags
        val words = PreferredBrandWords[code] ?: return false
        val folded = SportNameMatch.normalize(name)
        return words.any { folded.contains(it) }
    }

    /** The countries a user can prefer channels from; the key is stored in `SportsPrefs`. */
    val preferredCountryOptions: List<String> = listOf("UK", "US", "FR", "AR", "DE")

    /**
     * The ranking order the matcher applies: the user's pick first, then the house order
     * UK → US → FR → AR. Countries not in the list rank with no bonus at all.
     */
    fun countryOrder(preferred: String?): List<String> {
        val first = preferred?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        return (listOfNotNull(first) + HouseCountryOrder).distinct()
    }

    /** Position of [name] in [order], or -1 when no country applies. */
    fun countryRank(name: String, order: List<String>): Int =
        order.indexOfFirst { isFromCountry(name, it) }

    private val HouseCountryOrder: List<String> = listOf("UK", "US", "FR", "AR")

    /**
     * Country tag sets per preferred country. UK covers GB/IE, DE covers AT/CH, FR the French
     * broadcast countries, AR the Arab-language countries — the same grouping the language
     * filter uses.
     */
    private val PreferredCountryTags: Map<String, Set<String>> = mapOf(
        "UK" to setOf("UK", "GB", "IE"),
        "US" to setOf("US", "USA"),
        "FR" to setOf(
            "FR", "BE", "LU", "SN", "CI", "CD", "CM", "ML", "BF", "NE", "TD", "MG", "RE", "YT",
            "HT", "HAT", "DRC",
        ),
        "AR" to setOf(
            "SA", "AE", "QA", "EG", "MA", "DZ", "TN", "IQ", "JO", "KW", "BH", "OM", "QAT", "SOM",
            "SYR", "KU", "LB", "LY", "SD", "YE", "SY", "PS", "SO", "SU", "DJ", "KM", "MR", "ER",
        ),
        "DE" to setOf("DE", "AT", "CH"),
    )

    /**
     * Brand words per preferred country for untagged channels, matched against the normalized
     * channel name. "sky sport" is a prefix of "sky sports", so both spellings hit; "sky sport
     * news" cannot reach the matcher anyway (the both-teams rule keeps news channels out). "AR"
     * also matches language-tagged names ("ARABIC: …"), which carry no country code.
     */
    private const val UkCode = "UK"
    private const val NowBrand = "now"

    private val PreferredBrandWords: Map<String, List<String>> = mapOf(
        "UK" to listOf("sky sport", "tnt sport", "bt sport", "itv", "bbc"),
        "US" to listOf("espn", "fox sport", "cbs sport", "nbc sport", "peacock", "paramount", "usa network", "fubo"),
        "FR" to listOf("canal", "rmc sport", "bein"),
        "AR" to listOf("bein", "osn", "ssc", "alkass", "shahid", "arabic", "arab"),
        "DE" to listOf("dazn"),
    )
}
