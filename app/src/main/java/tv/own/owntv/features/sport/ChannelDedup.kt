package tv.own.owntv.features.sport

/**
 * Rule-4 identity of a playlist channel name — pure, unit-testable without Android.
 *
 * IPTV playlists spell the same underlying channel many ways: "NOW: Sky Sports Main Event 4K" and
 * "NOW: Sky Sports Main Event" are one channel, as is any name equal after stripping …
 *
 *  - leading tags "NOW:", "FR:", "UK:", "US:", "VIP", "BACKUP", "SERVER", "FEED" (any two-letter
 *    country code) and
 *  - resolution words (4K/UHD/2160P/FHD/1080P/HD/720P/SD/HDR/RAW) and
 *  - codec/audio noise (HEVC, H265, AAC, …).
 *
 * The tag itself stays part of the identity: "NOW: Sky Sports Main Event" and
 * "FR: Sky Sports Main Event" are genuinely different options. Words are stripped as whole words,
 * so "Main Event" (channel identity) is never eaten as tag noise.
 */
object ChannelDedup {

    /**
     * All resolution/quality words stripped from a channel name before identity comparison.
     * Deliberately NOT the word "event" — "Main Event" is the channel's identity, not noise.
     */
    private val NoiseWords = setOf(
        // resolution/quality
        "4k", "uhd", "2160p", "2k", "fhd", "1080i", "1080p", "1440p", "720p", "hd", "sd", "hdr", "raw",
        // codec/audio tags
        "hevc", "h265", "h264", "x265", "x264", "aac", "ac3", "eac3", "dts", "dolby", "atmos",
        "mpeg2", "mpeg4", "mp3",
        // source/backup markers without a tag separator
        "backup", "server", "feed", "vip",
    )

    /** Known leading tags; a two-letter country code always counts as one. */
    private val TagWords = setOf("now", "vip", "backup", "server", "feed")

    private val TagSplit = Regex("^(now|vip|backup|server|feed|[a-z]{2})[|:┃]+\\s*(.*)$", RegexOption.IGNORE_CASE)

    /**
     * The identity of one channel variant: its leading tag plus the noise-stripped base name, or
     * null when nothing usable remains (never dedupe a name down to nothing).
     */
    fun identityOf(name: String): Pair<String, String>? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        val tagMatch = TagSplit.matchEntire(raw)
        val tag = tagMatch?.groupValues?.get(1).orEmpty().lowercase()
        val rest = tagMatch?.groupValues?.get(2).orEmpty().ifEmpty { raw }
        val base = stripNoise(rest)
        if (base.isBlank()) return null
        return tag.lowercase() to base
    }

    /**
     * Resolution rank of a channel name variant — the highest-resolution copy wins an identity
     * group: 4K/UHD/HDR > FHD/1080p > HD/720p > SD > unknown.
     */
    fun resolutionRank(name: String): Int {
        val norm = SportNameMatch.normalize(name)
        return when {
            ranks(norm, "4k", "uhd", "2160p", "hdr") -> 4
            ranks(norm, "fhd", "1080p", "1440p") -> 3
            ranks(norm, "hd", "720p") -> 2
            ranks(norm, "sd") -> 0
            else -> 1
        }
    }

    private fun ranks(norm: String, vararg words: String): Boolean =
        words.any { SportNameMatch.containsWord(it, norm) }

    private fun stripNoise(text: String): String {
        val words = SportNameMatch.normalize(text).trim().split(' ').filterNot { it in NoiseWords }
        return words.joinToString(" ")
    }

    /**
     * Keeps one display entry per identity, best resolution first, evidence score as tie-break.
     * [entries] are `(match, sortKey)` pairs in ranked order; the list order is preserved.
     *
     * The playlist source is part of the identity: two providers' `Sky Sports Main Event` feeds are
     * distinct entries, while resolution variants inside one source still collapse.
     */
    fun dedupe(entries: List<Pair<ChannelMatch, Int>>): List<ChannelMatch> {
        val byIdentity = LinkedHashMap<String, Pair<ChannelMatch, Int>>()
        entries.forEach { (match, sortKey) ->
            val source = match.sourceId?.toString().orEmpty()
            val identity = identityOf(match.channelName)
                ?.let { source + SourceSeparator + it.first + "|" + it.second }
                ?: "id" + match.channelId
            val rank = resolutionRank(match.channelName)
            val incumbent = byIdentity[identity]
            val replace = incumbent == null || rank > resolutionRank(incumbent.first.channelName) ||
                (rank == resolutionRank(incumbent.first.channelName) && sortKey > incumbent.second)
            if (replace) byIdentity[identity] = match to sortKey
        }
        return byIdentity.values.map { it.first }
    }

    private const val SourceSeparator = "@"
}
