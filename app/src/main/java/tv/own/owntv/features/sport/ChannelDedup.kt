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
 *  - frame-rate markers ("50FPS", "⁵⁰ FPS", "1080p50") and
 *  - codec/audio noise (HEVC, H265, AAC, …).
 *
 * The tag itself stays part of the identity: "NOW: Sky Sports Main Event" and
 * "FR: Sky Sports Main Event" are genuinely different options. Words are stripped as whole words,
 * so "Main Event" (channel identity) is never eaten as tag noise.
 *
 * One identity may render **two** rows when both quality classes exist: the best 4K/UHD/8K copy
 * and the best HD-or-lower copy. A channel with only one class stays one row.
 */
object ChannelDedup {

    /**
     * All resolution/quality words stripped from a channel name before identity comparison.
     * Deliberately NOT the word "event" — "Main Event" is the channel's identity, not noise.
     */
    private val NoiseWords = setOf(
        // resolution/quality
        "8k", "4320p", "4k", "uhd", "2160p", "2k", "fhd", "1080i", "1080p", "1440p", "720p", "hd", "sd", "hdr", "raw",
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
     * Resolution rank of a channel name variant: 8K > 4K/UHD/HDR > FHD/1080p > HD/720p > SD >
     * unknown. Rank 4 and above is the "4K/UHD" class; everything below is the "HD/lower" class.
     */
    fun resolutionRank(name: String): Int {
        val norm = resolutionBase(name)
        return when {
            ranks(norm, "8k", "4320p") -> 5
            ranks(norm, "4k", "uhd", "2160p", "hdr") -> 4
            ranks(norm, "fhd", "1080p", "1440p") -> 3
            ranks(norm, "hd", "720p") -> 2
            ranks(norm, "sd") -> 0
            else -> 1
        }
    }

    /**
     * Frame-rate rank of a variant: the explicit number (60 > 50 > 30 > 25 …), else 1 for a bare
     * "FPS" marker whose digits the provider spelled as superscripts, else 0. Used only to pick
     * the best copy inside one resolution class, never to split an identity.
     */
    fun fpsRank(name: String): Int {
        val norm = SportNameMatch.normalize(name)
        val explicit = FpsNumber.find(norm)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val embedded = ResolutionFpsNumber.find(norm)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val value = maxOf(explicit, embedded)
        return if (value > 0) value else if (SportNameMatch.containsWord("fps", norm)) 1 else 0
    }

    private fun ranks(norm: String, vararg words: String): Boolean =
        words.any { SportNameMatch.containsWord(it, norm) }

    /** Normalized name with "1080p50"/"4k60" reduced to their resolution word. */
    private fun resolutionBase(name: String): String {
        val words = SportNameMatch.normalize(name).trim().split(' ')
        val mapped = words.flatMap { word ->
            val resolution = ResolutionFps.matchEntire(word)?.groupValues?.get(1)
            if (resolution != null) listOf(resolution) else listOf(word)
        }
        return " " + mapped.joinToString(" ") + " "
    }

    private fun stripNoise(text: String): String {
        val words = SportNameMatch.normalize(text).trim().split(' ').filterNot(::isNoise)
        return words.joinToString(" ")
    }

    /** Resolution/codec words, fps markers and resolution+fps forms ("1080p50") never identify a channel. */
    private fun isNoise(word: String): Boolean =
        word in NoiseWords || FpsWord.matches(word) || ResolutionFps.matches(word)

    /**
     * Keeps up to two display entries per identity, in first-appearance order:
     *  - the best 4K/UHD/8K copy, and
     *  - the best HD-or-lower copy.
     * A channel with only one class keeps one row. [entries] are `(match, sortKey)` pairs in ranked
     * order; the identity order is preserved.
     *
     * The playlist source is part of the identity: two providers' `Sky Sports Main Event` feeds are
     * distinct entries, while variants inside one source still collapse.
     */
    fun dedupe(entries: List<Pair<ChannelMatch, Int>>): List<ChannelMatch> {
        val byIdentity = LinkedHashMap<String, Variants>()
        entries.forEach { (match, sortKey) ->
            val source = match.sourceId?.toString().orEmpty()
            val identity = identityOf(match.channelName)
                ?.let { source + SourceSeparator + it.first + "|" + it.second }
                ?: "id" + match.channelId
            val group = byIdentity.getOrPut(identity) { Variants() }
            val candidate = match to sortKey
            if (resolutionRank(match.channelName) >= UltraHdRank) {
                group.ultra = betterOf(group.ultra, candidate)
            } else {
                group.lower = betterOf(group.lower, candidate)
            }
        }
        return byIdentity.values.flatMap { group ->
            listOfNotNull(group.ultra, group.lower).map { it.first }
        }
    }

    /** Best copy inside one class: resolution, then fps, then the caller's roster. */
    private fun betterOf(
        incumbent: Pair<ChannelMatch, Int>?,
        candidate: Pair<ChannelMatch, Int>,
    ): Pair<ChannelMatch, Int> {
        if (incumbent == null) return candidate
        val candidateRank = resolutionRank(candidate.first.channelName)
        val incumbentRank = resolutionRank(incumbent.first.channelName)
        if (candidateRank != incumbentRank) return if (candidateRank > incumbentRank) candidate else incumbent
        val candidateFps = fpsRank(candidate.first.channelName)
        val incumbentFps = fpsRank(incumbent.first.channelName)
        if (candidateFps != incumbentFps) return if (candidateFps > incumbentFps) candidate else incumbent
        return if (candidate.second > incumbent.second) candidate else incumbent
    }

    private class Variants {
        var ultra: Pair<ChannelMatch, Int>? = null
        var lower: Pair<ChannelMatch, Int>? = null
    }

    private const val SourceSeparator = "@"
    /** Rank at which a variant counts as the "4K/UHD" class rather than "HD/lower". */
    private const val UltraHdRank = 4
    /** "50fps", "60FPS" or a bare "fps" marker. */
    private val FpsWord = Regex("(?:\\d{2,3}fps|fps)")
    /** Resolution word carrying fps: "1080p50", "4k60", "2160p50fps"; group 1 is the resolution. */
    private val ResolutionFps = Regex("(8k|4320p|4k|uhd|2160p|2k|fhd|1080[pi]|1440p|720p|hd|sd)\\d{2,3}(?:fps)?")
    private val FpsNumber = Regex(" (\\d{2,3})fps ")
    private val ResolutionFpsNumber =
        Regex(" (?:8k|4320p|4k|uhd|2160p|2k|fhd|1080[pi]|1440p|720p|hd|sd)(\\d{2,3})(?:fps)? ")
}
