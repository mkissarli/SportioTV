package tv.own.owntv.features.sport

import java.util.Locale

/** The sport a channel identity or a fixture belongs to. */
enum class SportClass {
    FOOTBALL,
    AMERICAN_FOOTBALL,
    BASKETBALL,
    BASEBALL,
    HOCKEY,
    TENNIS,
    MOTORSPORT,
    GOLF,
    COMBAT,
    CRICKET,
    RUGBY,
    CYCLING,
    ATHLETICS,
    MULTI,
    UNKNOWN;

    /** Specialist search terms for the low-confidence fallback (no guide, no EPG). */
    fun specialistTerms(): List<String> = when (this) {
        FOOTBALL -> listOf("football", "soccer", "fussball", "calcio", "premier", "laliga", "bundesliga", "serie", "ligue")
        AMERICAN_FOOTBALL -> listOf("nfl", "college", "football")
        BASKETBALL -> listOf("nba", "basketball", "euroleague")
        BASEBALL -> listOf("mlb", "baseball")
        HOCKEY -> listOf("nhl", "hockey")
        TENNIS -> listOf("tennis", "atp", "wta")
        MOTORSPORT -> listOf("f1", "formula", "motorsport", "racing", "motogp")
        GOLF -> listOf("golf", "pga", "dpwt")
        COMBAT -> listOf("ufc", "mma", "boxing", "fight")
        CRICKET -> listOf("cricket")
        RUGBY -> listOf("rugby")
        CYCLING -> listOf("cycling", "cyclocross", "uci")
        ATHLETICS -> listOf("athletics", "marathon", "track")
        MULTI, UNKNOWN -> emptyList()
    }

    companion object {
        /** The sport an ESPN league id belongs to. Unknown prefixes degrade to [UNKNOWN]. */
        fun fromLeagueId(leagueId: String): SportClass = when {
            leagueId.startsWith("soccer.") -> FOOTBALL
            leagueId.startsWith("football.") -> AMERICAN_FOOTBALL
            leagueId.startsWith("basketball.") -> BASKETBALL
            leagueId.startsWith("baseball.") -> BASEBALL
            leagueId.startsWith("hockey.") -> HOCKEY
            leagueId.startsWith("tennis.") -> TENNIS
            leagueId.startsWith("racing.") || leagueId.startsWith("motogp") -> MOTORSPORT
            leagueId.startsWith("golf.") -> GOLF
            leagueId.startsWith("mma.") || leagueId.startsWith("boxing.") -> COMBAT
            leagueId.startsWith("cricket.") -> CRICKET
            leagueId.startsWith("rugby") -> RUGBY
            leagueId.startsWith("cycling.") -> CYCLING
            leagueId.startsWith("athletics.") -> ATHLETICS
            else -> UNKNOWN
        }
    }
}

/** How a playlist entry behaves, for identity and ranking only. */
enum class ChannelType { LINEAR, PPV, EVENT, REGIONAL }

/** How a playlist name was resolved to its canonical identity. */
enum class IdentityBasis { EXACT, ALIAS, METADATA, INFERRED }

/**
 * One canonical channel identity. Deliberately compact: the matcher keeps thousands of these for
 * one fixture at most, never the whole playlist as an object graph.
 *
 * `key` is the only equality token: `broadcaster|channel` for a known broadcaster, otherwise
 * `channel:<channel>`. `channel` never contains quality, country or provider words.
 */
data class ChannelIdentity(
    val key: String,
    val broadcaster: String?,
    val channel: String,
    /** The provider name after normalization — lets [ChannelIdentityResolver.relationOf] say EXACT. */
    val normalized: String,
    val territory: String?,
    val sport: SportClass,
    val specialist: Boolean,
    val type: ChannelType,
    val basis: IdentityBasis,
    val reason: String,
)

/** Why two names are the same underlying channel. */
data class IdentityMatch(val basis: IdentityBasis, val reason: String)

/**
 * Deterministic channel identity resolution for IPTV provider names.
 *
 * The goal is precision, not fuzzy recall: `SKY SPORTS MAIN EVENT HD`, `Sky Main Event`,
 * `NOW: SKY SPORTS MAIN EVENT` and `SKY SPORT MAIN EVENT UK` resolve to `sky|main event`, while
 * `Sky Sports+` (`sky|plus`) and `Sky Sports 1` (`sky|1`) stay distinct. Broadcaster names are
 * matched as whole tokens, generic words (`sports`, `HD`, `LIVE`) never create an identity, and a
 * network-level name (`Sky Sports`) never resolves to one of its channels.
 *
 * The lookup tables are static data, not user-visible copy; the i18n gate classifies them as
 * technical literals in `tools/i18n/safe_literals.txt`.
 */
object ChannelIdentityResolver {

    /**
     * The canonical identity of [name], or null when nothing identifying remains (e.g. `"SPORTS
     * HD"` or an empty name). Never throws.
     */
    fun resolve(name: String): ChannelIdentity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        // "+" is a channel name (Sky Sports+), not punctuation. Keep it as a token.
        val deduped = ChannelDedup.identityOf(trimmed.replace("+", " plus "))
        val tag = deduped?.first.orEmpty()
        var base = deduped?.second.orEmpty()
        if (base.isBlank()) base = SportNameMatch.normalize(trimmed).trim()
        if (base.isBlank()) return null
        var territory = tag.takeIf { it in CountryTags }?.uppercase(Locale.ROOT)
            ?: SportLanguages.countryCodeOf(trimmed)

        var tokens = base.split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        // A trailing country/region word ("SKY SPORT MAIN EVENT UK") is not part of the channel.
        if (tokens.size > 1 && tokens.last() in CountryTags) {
            if (territory == null) territory = tokens.last().uppercase(Locale.ROOT)
            tokens = tokens.dropLast(1)
        }

        val sportsBrand = tokens.any { it in SportWords }
        var broadcaster: String? = null
        var leftover = tokens
        val phrase = BroadcasterPhrases.firstOrNull { (words, _) ->
            words.size <= tokens.size && tokens.windowed(words.size).any { it == words }
        }
        if (phrase != null) {
            broadcaster = phrase.second
            val at = tokens.indexOfSubList(phrase.first)
            leftover = tokens.toMutableList().apply { repeat(phrase.first.size) { removeAt(at) } }
        } else {
            val rest = ArrayList<String>(tokens.size)
            for (token in tokens) {
                val split = broadcasterFor(token)
                if (broadcaster == null && split != null) {
                    broadcaster = split.first
                    split.second?.let(rest::add)
                } else {
                    rest += token
                }
            }
            leftover = rest
        }

        // The brand already implies "sports"; a leading sports word is not a channel.
        if (leftover.size > 1 && leftover.first() in SportWords) leftover = leftover.drop(1)
        leftover = leftover.filterNot { it in GenericChannelWords }
        while (leftover.isNotEmpty() && leftover.first() == TheWord) leftover = leftover.drop(1)
        if (leftover.isNotEmpty() && leftover.last() in CountryTags) {
            if (territory == null) territory = leftover.last().uppercase(Locale.ROOT)
            leftover = leftover.dropLast(1)
        }

        val channel = canonicalChannel(leftover, broadcaster != null)
            ?: return null
        // "Sky Sports News" and "Sky News" (and "Sky Sports 1" vs "Sky 1") are different
        // channels; the sports brand is dropped only for the unambiguous sports names.
        val sportsMarker = if (sportsBrand && broadcaster != null &&
            broadcaster in SportsBrandBroadcasters && channel !in SportsShorthand
        ) {
            SportsBrandMarker
        } else {
            ""
        }
        val key = (broadcaster ?: ChannelPrefix) + sportsMarker + KeySeparator + channel
        val sport = sportOf(broadcaster, channel, tokens)
        val specialist = sport != SportClass.UNKNOWN && sport != SportClass.MULTI &&
            (broadcaster != null || hasSportWord(tokens))
        val type = when {
            PpvWord in leftover -> ChannelType.PPV
            EventWord in leftover && channel != MainEvent -> ChannelType.EVENT
            leftover.any { it in RegionalWords } -> ChannelType.REGIONAL
            else -> ChannelType.LINEAR
        }
        val normalizedRaw = SportNameMatch.normalize(trimmed).trim()
        val basis = if (normalizedRaw == channel && broadcaster == null && tag.isEmpty() && territory == null) {
            IdentityBasis.EXACT
        } else {
            IdentityBasis.ALIAS
        }
        val reason = when {
            basis == IdentityBasis.EXACT -> ExactReason
            tag.isNotEmpty() -> TagReasonPrefix + tag
            broadcaster != null -> BroadcasterReasonPrefix + broadcaster
            else -> NormalisedReason
        }
        return ChannelIdentity(key, broadcaster, channel, normalizedRaw, territory, sport, specialist, type, basis, reason)
    }

    /** Why [first] and [second] are (or are not) the same channel. Null means "not the same". */
    fun relation(first: String, second: String): IdentityMatch? {
        val a = resolve(first) ?: return null
        val b = resolve(second) ?: return null
        return relationOf(a, b)
    }

    /** [relation] on already-resolved identities — the matcher's hot path. */
    fun relationOf(a: ChannelIdentity, b: ChannelIdentity): IdentityMatch? {
        if (a.key == b.key) {
            val exact = a.normalized == b.normalized ||
                (a.basis == IdentityBasis.EXACT && b.basis == IdentityBasis.EXACT)
            return IdentityMatch(if (exact) IdentityBasis.EXACT else IdentityBasis.ALIAS, SameKeyReason)
        }
        // A provider that drops the brand ("MAIN EVENT" next to "SKY SPORTS MAIN EVENT") still
        // names the same channel; a network-level name never does, and a bare number ("1") is far
        // too generic to infer from.
        if (a.channel != NetworkChannel && a.channel == b.channel &&
            (a.broadcaster == null || b.broadcaster == null) &&
            (a.channel.length >= MinInferredChannelLength || a.channel.contains(' '))
        ) {
            return IdentityMatch(IdentityBasis.INFERRED, MissingBrandReason)
        }
        return null
    }

    /**
     * Compact SQL/FTS probes for a listing channel name: the channel phrase first, then its
     * distinctive words and the broadcaster. Deliberately capped; the caller applies the exact
     * identity relation to every row the probe returns.
     */
    fun searchTerms(name: String): List<String> {
        val identity = resolve(name) ?: return emptyList()
        val out = LinkedHashSet<String>()
        if (identity.channel != NetworkChannel && identity.channel.isNotBlank()) {
            out += identity.channel
            out += identity.channel.split(' ').filter { it.length >= MinTermLength }
        }
        identity.broadcaster?.let { out += it }
        return out.take(MaxSearchTerms)
    }

    private fun canonicalChannel(words: List<String>, hasBroadcaster: Boolean): String? {
        if (words.isEmpty()) return if (hasBroadcaster) NetworkChannel else null
        val joined = words.joinToString(" ")
        ChannelAliases[joined]?.let { return it }
        if (!hasBroadcaster && words.all { it in GenericOnlyWords }) return null
        return joined
    }

    private fun sportOf(broadcaster: String?, channel: String, all: List<String>): SportClass {
        val text = (channel + " " + all.joinToString(" ")).let { " $it " }
        fun hit(vararg words: String): Boolean = words.any { SportNameMatch.containsWord(it, text) }
        return when {
            hit("f1", "formula", "motorsport", "racing", "motogp", "nascar", "indycar") -> SportClass.MOTORSPORT
            hit("tennis", "atp", "wta") -> SportClass.TENNIS
            hit("golf", "pga", "dpwt", "ryder") -> SportClass.GOLF
            hit("cricket") -> SportClass.CRICKET
            hit("rugby") -> SportClass.RUGBY
            hit("cycling", "cyclocross", "uci") -> SportClass.CYCLING
            hit("athletics", "marathon") -> SportClass.ATHLETICS
            hit("ufc", "mma", "boxing", "fight", "wwe", "wrestling") -> SportClass.COMBAT
            hit("nba", "basketball", "euroleague") -> SportClass.BASKETBALL
            hit("mlb", "baseball") -> SportClass.BASEBALL
            hit("nhl", "hockey") -> SportClass.HOCKEY
            hit("nfl", "ncaa", "college") -> SportClass.AMERICAN_FOOTBALL
            hit(
                "football", "soccer", "fussball", "futbol", "calcio", "foot", "premier", "laliga",
                "bundesliga", "serie", "ligue", "eredivisie", "mls",
            ) -> SportClass.FOOTBALL
            hit("sport", "sports", "eurosport", "dazn", "arena", "main", "event", "network") ->
                SportClass.MULTI
            broadcaster != null -> SportClass.MULTI
            else -> SportClass.UNKNOWN
        }
    }

    private fun hasSportWord(tokens: List<String>): Boolean =
        tokens.any { it in SportWords || it in SportClassTerms }

    /** `espn2` → ("espn", "2"); a known brand with a trailing number is a numbered channel. */
    private fun broadcasterFor(token: String): Pair<String, String?>? {
        SingleBroadcasters[token]?.let { return it to null }
        for ((alias, canonical) in SingleBroadcasters) {
            if (token.length > alias.length && token.startsWith(alias) &&
                token.substring(alias.length).all(Char::isDigit)
            ) {
                return canonical to token.substring(alias.length)
            }
        }
        return null
    }

    private fun List<String>.indexOfSubList(part: List<String>): Int {
        if (part.isEmpty()) return 0
        for (i in 0..size - part.size) {
            if (subList(i, i + part.size) == part) return i
        }
        return 0
    }

    private const val KeySeparator = "|"
    private const val ChannelPrefix = "channel"

    /** The channel part used when a name is only the broadcaster ("Sky Sports"). */
    const val NetworkChannel = "network"
    private const val MainEvent = "main event"
    private const val PpvWord = "ppv"
    private const val EventWord = "event"
    private const val TheWord = "the"
    private const val ExactReason = "exact"
    private const val SameKeyReason = "same canonical key"
    private const val MissingBrandReason = "same channel, one side omits the broadcaster"
    private const val TagReasonPrefix = "provider tag "
    private const val BroadcasterReasonPrefix = "broadcaster "
    private const val NormalisedReason = "normalized spelling"
    private const val MinTermLength = 3
    private const val MaxSearchTerms = 6
    private const val MinInferredChannelLength = 4

    /** The brand words that imply a sports network; removed after the broadcaster was found. */
    private val SportWords = setOf("sport", "sports")

    /** Only these broadcasters' "sports" spelling separates their news/numbered channels. */
    private val SportsBrandBroadcasters = setOf("sky", "tnt")

    /** Appended to a key when the "sports" brand is part of the identity ("sky sports|news"). */
    private const val SportsBrandMarker = " sports"

    /** Sports names where "Sky Main Event" is an unambiguous shorthand for "Sky Sports …". */
    private val SportsShorthand = setOf(
        "main event", "f1", "football", "soccer", "tennis", "golf", "cricket", "rugby",
        "racing", "arena", "mix", "premier league", "bundesliga", "laliga", "serie a",
        "ligue 1", "nfl", "nba", "mlb", "nhl", "ufc", "boxing",
    )

    /** Words that carry no identity on their own. */
    private val GenericChannelWords = setOf(
        "live", "tv", "channel", "network", "online", "stream", "feed",
        "backup", "server", "vip", "hd", "fhd", "uhd", "4k", "8k", "sd", "hdr",
    )

    /** A name made only of these is not a channel (no broadcaster). */
    private val GenericOnlyWords = GenericChannelWords + SportWords + setOf("1", "2", "3", "plus", "extra")

    private val CountryTags = setOf(
        "uk", "gb", "us", "usa", "de", "at", "ch", "fr", "it", "es", "pt", "nl", "be", "ie",
        "ca", "au", "nz", "ar", "br", "mx", "cl", "co", "pe", "ve", "uy", "se", "no", "dk",
        "fi", "pl", "cz", "sk", "hu", "ro", "bg", "gr", "tr", "ru", "ua", "rs", "hr", "si",
        "ba", "mk", "al", "il", "sa", "ae", "qa", "kw", "eg", "za", "in", "pk", "bd", "lk",
        "jp", "kr", "cn", "hk", "tw", "sg", "my", "id", "th", "vn", "ph",
    )

    private val RegionalWords = setOf("regional", "local", "region")

    /** Canonical channel spellings after quality/tag removal. Keys are normalized text. */
    private val ChannelAliases = mapOf(
        "main event" to MainEvent,
        "main event hd" to MainEvent,
        "premier league" to "premier league",
        "epl" to "premier league",
        "pl" to "premier league",
        "formula 1" to "f1",
        "formula one" to "f1",
        "f 1" to "f1",
        "sports plus" to "plus",
    )

    /** Sport words used to decide whether a generic brand is a specialist sports channel. */
    private val SportClassTerms = setOf(
        "football", "soccer", "fussball", "futbol", "calcio", "tennis", "golf", "cricket",
        "rugby", "cycling", "athletics", "boxing", "mma", "ufc", "nba", "nfl", "mlb", "nhl",
        "f1", "formula", "motorsport", "racing", "motogp", "cricket",
    )

    /** Multi-word brands, longest first. A phrase match beats a single-token match. */
    private val BroadcasterPhrases: List<Pair<List<String>, String>> = listOf(
        listOf("canal", "plus") to "canal",
        listOf("premier", "sports") to "premier sports",
        listOf("prime", "video") to "prime video",
        listOf("apple", "tv") to "apple tv",
        listOf("sport", "klub") to "sport klub",
        listOf("match", "tv") to "match tv",
        listOf("sky", "sports") to "sky",
        listOf("sky", "sport") to "sky",
    )

    /** Single-token broadcaster aliases. "bt" is TNT Sports in current rights tables. */
    private val SingleBroadcasters: Map<String, String> = mapOf(
        "sky" to "sky",
        "tnt" to "tnt",
        "bt" to "tnt",
        "dazn" to "dazn",
        "canal" to "canal",
        "canalplus" to "canal",
        "bein" to "bein",
        "espn" to "espn",
        "eurosport" to "eurosport",
        "bbc" to "bbc",
        "itv" to "itv",
        "fox" to "fox",
        "nbc" to "nbc",
        "cbs" to "cbs",
        "abc" to "abc",
        "rmc" to "rmc",
        "tf1" to "tf1",
        "ard" to "ard",
        "zdf" to "zdf",
        "movistar" to "movistar",
        "sport1" to "sport1",
        "viaplay" to "viaplay",
        "eleven" to "eleven",
        "paramount" to "paramount",
        "peacock" to "peacock",
        "showtime" to "showtime",
        "hbo" to "hbo",
        "supersport" to "supersport",
        "tsn" to "tsn",
        "sportsnet" to "sportsnet",
        "rds" to "rds",
        "tv2" to "tv2",
        "nrk" to "nrk",
        "svt" to "svt",
        "dr" to "dr",
        "yle" to "yle",
        "polsat" to "polsat",
        "npo" to "npo",
        "nos" to "nos",
        "rtl" to "rtl",
        "servus" to "servus",
        "directv" to "directv",
        "fubotv" to "fubotv",
        "sling" to "sling",
        "amazon" to "amazon",
        "youtube" to "youtube",
    )
}
