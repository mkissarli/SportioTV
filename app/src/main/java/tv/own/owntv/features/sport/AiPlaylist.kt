package tv.own.owntv.features.sport

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** Wire and size policy shared by the client and the playlist analyst. */
object AiPlaylistPolicy {
    /** Current documented model (2026-10: 1M context). Not the retired alias. */
    const val Model = "deepseek-flash"
    const val PromptVersion = "playlist-v1"

    /**
     * Prefer one request. ~3 characters per token is a deliberately conservative estimate, so
     * 700k characters are well inside the 1M-token context even with the prompt boilerplate.
     */
    const val MaxFullRequestChars = 700_000

    /** Fallback batches stay far below the full-context budget for a fast, bounded request. */
    const val MaxBatchRequestChars = 400_000

    /** The model may return at most this many logical channels per request. */
    const val MaxSelections = 10
}

/** One playable playlist row as the model sees it: a temporary id, display name and local evidence. */
data class AiPlaylistCandidate(
    val requestId: String,
    val row: ChannelEntity,
    val territory: String?,
    /** Compact EPG around the fixture, or null when the channel has no guide. */
    val epg: String?,
    /** The broadcaster prior that names this channel, if any; context only. */
    val prior: String?,
) {
    /**
     * One catalogue line. The display name is not normalised or lowercased — it is all the model
     * has to recognise an opaque `PPV` feed. Line breaks are flattened so the line format holds.
     */
    fun line(): String {
        val parts = ArrayList<String>(5)
        parts += requestId
        parts += row.name.replace(LineBreak, Space).trim()
        territory?.let { parts += TerritoryPrefix + it }
        epg?.takeIf { it.isNotBlank() }?.let { parts += EpgPrefix + it }
        prior?.let { parts += PriorPrefix + it }
        return parts.joinToString(Separator)
    }

    private companion object {
        const val Separator = " | "
        const val TerritoryPrefix = "territory="
        const val EpgPrefix = "epg="
        const val PriorPrefix = "prior="
        const val LineBreak = "[\\r\\n\\t]+"
        const val Space = " "
    }
}

/** Everything the model is allowed to answer for one catalogue. */
sealed interface AiPlaylistAnswer {
    data class Selected(val picks: List<AiPlaylistPick>, val reason: String?) : AiPlaylistAnswer
    data class None(val reason: String?) : AiPlaylistAnswer
}

/** One accepted model pick, already mapped to the submitted request id. */
data class AiPlaylistPick(val requestId: String, val confidence: Double, val reason: String)

/**
 * Builds the catalogue and validates the model's JSON. Pure: no Android, database or network, so
 * every rejection path is testable on the JVM.
 */
object AiPlaylistCatalogue {

    private const val MaxEpgRows = 2
    private const val MaxEpgDescriptionChars = 90
    private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    /**
     * One candidate per playable row, including opaque `PPV`/`Event` names and rows without EPG.
     * Temporary ids run over the **whole** playlist before batching, so `C00012` means the same row
     * in every request and no row is silently dropped by preprocessing.
     */
    fun build(
        fixture: Fixture,
        channels: List<ChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
        priorCarriers: List<String> = emptyList(),
    ): List<AiPlaylistCandidate> {
        val byEpgKey = programmes.groupBy { it.epgChannelId.trim().lowercase(Locale.ROOT) }
        // Category separators imported as channels ("#### SPORT ####") are navigation markers, not
        // playable entries; every real row — including opaque PPV names — stays in the catalogue.
        val playable = channels.filter { it.name.isNotBlank() && it.name.any(Char::isLetterOrDigit) }
        return playable.mapIndexed { index, channel ->
            val key = channel.epgChannelId?.trim()?.lowercase(Locale.ROOT)
            val rows = byEpgKey[key].orEmpty()
                .sortedBy { kotlin.math.abs(it.startMs - fixture.dateMs) }
                .take(MaxEpgRows)
            AiPlaylistCandidate(
                requestId = requestId(index),
                row = channel,
                territory = SportLanguages.countryCodeOf(channel.name),
                epg = rows.takeIf { it.isNotEmpty() }?.joinToString(EpgRowSeparator) { programme ->
                    utcTime(programme.startMs) + TimeRangeSeparator + utcTime(programme.stopMs) + Space +
                        programme.title.replace(LineBreak, Space).trim() + descriptionSuffix(programme.description)
                },
                prior = priorCarriers.firstOrNull { prior ->
                    SportNameMatch.containsPhraseLoose(prior, SportNameMatch.normalize(channel.name))
                },
            )
        }
    }

    fun requestId(index: Int): String = RequestIdPrefix + (index + 1).toString().padStart(5, '0')

    private fun descriptionSuffix(description: String?): String {
        val text = description?.replace(LineBreak, Space)?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return DescriptionPrefix + text.take(MaxEpgDescriptionChars)
    }

    private fun utcTime(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalTime().format(TimeFormat)

    private const val RequestIdPrefix = "C"
    private const val EpgRowSeparator = "; "
    private const val TimeRangeSeparator = "-"
    private const val DescriptionPrefix = " — "
    private const val Space = " "
    private val LineBreak = Regex("[\\r\\n\\t]+")
}

/**
 * Strict schema validation for the playlist answer. JSON mode is not schema validation: a response
 * is accepted only when every id is in *that request's* catalogue, ids are unique, confidence is a
 * real number in 0..1 and the decision matches the shape. Anything else is rejected as a whole —
 * a rejected response contributes no AI channel.
 */
object AiPlaylistResponseParser {

    fun parse(content: String, allowedIds: Set<String>): AiPlaylistAnswer? {
        val json = stripFences(content)
        if (json.isBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val decision = root.optString(DecisionKey).trim().lowercase(Locale.ROOT)
        val reason = root.optString(ReasonKey).trim().takeIf { it.isNotEmpty() }
        return when (decision) {
            DecisionNone -> {
                val channels = root.optJSONArray(ChannelsKey)
                if (channels != null && channels.length() > 0) null else AiPlaylistAnswer.None(reason)
            }
            DecisionSelect -> parseSelect(root.optJSONArray(ChannelsKey), allowedIds, reason)
            else -> null
        }
    }

    private fun parseSelect(
        channels: JSONArray?,
        allowedIds: Set<String>,
        reason: String?,
    ): AiPlaylistAnswer? {
        if (channels == null || channels.length() > AiPlaylistPolicy.MaxSelections) return null
        val seen = HashSet<String>()
        val picks = ArrayList<AiPlaylistPick>(channels.length())
        for (i in 0 until channels.length()) {
            val item = channels.optJSONObject(i) ?: return null
            val rawId = item.opt(IDKey)
            if (rawId !is String) return null
            val requestId = rawId.trim()
            if (requestId !in allowedIds) return null
            if (!seen.add(requestId)) return null
            val rawConfidence = item.opt(ConfidenceKey)
            if (rawConfidence !is Number) return null
            val confidence = rawConfidence.toDouble()
            if (confidence.isNaN() || confidence < 0.0 || confidence > 1.0) return null
            picks += AiPlaylistPick(
                requestId = requestId,
                confidence = confidence,
                reason = item.optString(ReasonKey).trim(),
            )
        }
        return AiPlaylistAnswer.Selected(picks, reason)
    }

    /** Some models still wrap JSON in a fence despite JSON mode. */
    private fun stripFences(content: String): String {
        var text = content.trim()
        if (!text.startsWith(Fence)) return text
        text = text.removePrefix(FenceJson).removePrefix(Fence).trim()
        val end = text.lastIndexOf(Fence)
        if (end >= 0) text = text.substring(0, end).trim()
        return text
    }

    private const val DecisionKey = "decision"
    private const val DecisionSelect = "select"
    private const val DecisionNone = "none"
    private const val ChannelsKey = "channels"
    private const val IDKey = "id"
    private const val ConfidenceKey = "confidence"
    private const val ReasonKey = "reason"
    private const val Fence = "```"
    private const val FenceJson = "```json"
}

/** Splits the full catalogue into request-sized batches; the full-context case is one batch. */
object AiPlaylistBatching {

    /**
     * Full context when the catalogue (plus boilerplate) fits [maxChars]; otherwise every line is
     * placed in exactly one batch. A single pathological line larger than the budget gets its own
     * request instead of being dropped.
     */
    fun batches(
        lines: List<String>,
        maxChars: Int = AiPlaylistPolicy.MaxBatchRequestChars,
        promptOverheadChars: Int = PromptOverheadChars,
    ): List<List<String>> {
        if (lines.isEmpty()) return emptyList()
        val total = lines.sumOf { it.length + 1 } + promptOverheadChars
        if (total <= maxChars) return listOf(lines)
        val out = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var size = promptOverheadChars
        for (line in lines) {
            val cost = line.length + 1
            if (current.isNotEmpty() && size + cost > maxChars) {
                out += current
                current = ArrayList()
                size = promptOverheadChars
            }
            current += line
            size += cost
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /** Fixture fields, instructions and JSON contract — the non-catalogue part of a request. */
    const val PromptOverheadChars = 6_000
}

/**
 * The playlist prompt. Technical English, never translated; the schema contract is repeated in the
 * user message because JSON mode guarantees syntax, not shape.
 */
object AiPlaylistPrompt {

    const val System =
        "You identify the exact IPTV playlist channels that carry one specific sports event. " +
            "You may use broadcast-rights knowledge, the event context and channel metadata, but you " +
            "must never invent a channel or an id. Reply with valid JSON only."

    fun user(
        fixture: Fixture,
        leagueLabel: String,
        market: String?,
        priorCarriers: List<String>,
        guideEvidence: List<GuideEntry>,
        catalogueLines: List<String>,
        batchIndex: Int,
        batchCount: Int,
    ): String = buildString {
        appendLine("EVENT")
        appendLine("Title: " + eventName(fixture))
        fixture.home?.let { appendLine("Home: " + it.name + " (" + it.abbreviation + ")") }
        fixture.away?.let { appendLine("Away: " + it.name + " (" + it.abbreviation + ")") }
        appendLine("Competition: " + leagueLabel.ifBlank { fixture.leagueId })
        appendLine("Kickoff (UTC): " + Instant.ofEpochMilli(fixture.dateMs).toString())
        fixture.title?.takeIf { it.isNotBlank() }?.let { appendLine("Event title: " + it) }
        appendLine("Session: " + sessionOf(fixture))
        appendLine("Season: unknown")
        appendLine("Broadcast market: " + (market?.takeIf { it.isNotBlank() } ?: "unspecified"))
        appendLine(
            "ESPN broadcast labels: " +
                fixture.broadcasts.take(MaxGuideCarriers).joinToString(", ").ifBlank { "none" },
        )
        appendLine()
        appendLine("WEB GUIDE EVIDENCE (independent source; rows may be gated and carriers hidden)")
        if (guideEvidence.isEmpty()) {
            appendLine("- none")
        } else {
            guideEvidence.take(MaxGuideRows).forEach { row ->
                val carriers = if (row.gated || row.channels.isEmpty()) {
                    "hidden (login required or not listed)"
                } else {
                    row.channels.joinToString(", ")
                }
                appendLine(
                    "- " + Instant.ofEpochMilli(row.dateMs).toString() + " | " + row.league + " | " +
                        row.teams + " | carriers: " + carriers,
                )
            }
        }
        appendLine()
        appendLine("BROADCASTER PRIOR CONTEXT (rights hint only; never proof)")
        appendLine(priorCarriers.take(MaxPriorCarriers).joinToString(", ").ifBlank { "none" })
        appendLine()
        appendLine("PLAYLIST CANDIDATES — batch " + batchIndex + " of " + batchCount)
        appendLine("Format: id | exact playlist display name | territory | epg=window title | prior=match")
        catalogueLines.forEach(::appendLine)
        appendLine()
        appendLine("TASK")
        appendLine(
            "Select the channels that carry THIS exact event. A channel that only specialises in the " +
                "sport, the competition or the broadcaster is not enough.",
        )
        appendLine(
            "- Opaque names such as PPV, PPV 1, Event 1 or Web Channel may be event-specific feeds; a " +
                "matching schedule/rights context is evidence, a name alone is not.",
        )
        appendLine("- A guide row naming the tour (e.g. ATP Tennis) is not proof for one specific match.")
        appendLine("- Different sessions of the same Grand Prix are different events.")
        appendLine("- Use only ids present in this request's candidate list. Never invent a channel, id or URL.")
        appendLine("- Return at most " + AiPlaylistPolicy.MaxSelections + " channels, best first.")
        appendLine("- If the evidence is insufficient, abstain. Empty is better than wrong.")
        appendLine()
        appendLine("OUTPUT — JSON only")
        appendLine(
            """{"decision":"select","channels":[{"id":"C00012","confidence":0.91,"reason":"short reason"}]}""",
        )
        appendLine("""{"decision":"none","channels":[],"reason":"insufficient evidence"}""")
    }

    /** A readable event name; team names are proper nouns and are never translated. */
    fun eventName(fixture: Fixture): String {
        val home = fixture.home
        val away = fixture.away
        return when {
            home != null && away != null -> home.name + " vs " + away.name
            fixture.format == FixtureFormat.VERSUS ->
                fixture.entries.take(2).joinToString(" vs ") { it.name }
            !fixture.title.isNullOrBlank() -> fixture.title
            else -> fixture.entries.firstOrNull()?.name ?: fixture.leagueId
        }
    }

    /** The session label an event fixture names ("Practice 1", "Race"), or "match" for duels. */
    private fun sessionOf(fixture: Fixture): String {
        if (fixture.home != null || fixture.away != null) return "match"
        val text = fixture.title.orEmpty() + " " + fixture.tournament.orEmpty()
        val norm = SportNameMatch.normalize(text)
        val session = SessionWords.firstOrNull { SportNameMatch.containsWord(it, norm) }
        return session ?: "unspecified"
    }

    private val SessionWords = listOf(
        "practice", "qualifying", "sprint", "race", "warm", "final", "semifinal", "quarterfinal",
    )
    private const val MaxGuideRows = 12
    private const val MaxGuideCarriers = 8
    private const val MaxPriorCarriers = 8
}
