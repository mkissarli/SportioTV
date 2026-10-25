package tv.own.owntv.features.sport

import android.content.Context
import java.util.Locale
import org.json.JSONObject
import tv.own.owntv.R

/**
 * Versioned broadcaster priors from `res/raw/sports_broadcaster_priors.json`.
 *
 * A prior is **context, never confirmation**: it may reach the LLM prompt and break a tie between
 * channels that already have guide, EPG or accepted-AI evidence, but it never creates a playlist
 * entry on its own. Rights differ per season and market, so every dataset entry carries the
 * competition, a season scope and a market scope; `*` means "any".
 */
class SportsPriors private constructor(
    val datasetVersion: String,
    private val entries: List<PriorEntry>,
) {

    data class PriorEntry(
        val competitionId: String,
        val aliases: List<String>,
        val seasons: Set<String>,
        val markets: Set<String>,
        /** Ranked carrier aliases; the order is the tie-break order. */
        val carriers: List<String>,
    )

    /**
     * The ranked carrier aliases that apply to [fixture] in [market]/[season]. Empty when no entry
     * matches — an absent prior is the honest answer, never a wildcard guess.
     */
    fun carriersFor(fixture: Fixture, market: String? = null, season: String? = null): List<String> {
        if (entries.isEmpty()) return emptyList()
        val text = SportNameMatch.normalize(
            listOf(fixture.leagueId, fixture.tournament.orEmpty(), fixture.title.orEmpty()).joinToString(" "),
        )
        return entries.asSequence()
            .filter { entry -> matchesCompetition(entry, fixture, text) }
            .filter { entry -> matchesScope(entry.seasons, season) }
            .filter { entry -> matchesScope(entry.markets, market) }
            .flatMap { it.carriers.asSequence() }
            .distinct()
            .toList()
    }

    private fun matchesCompetition(entry: PriorEntry, fixture: Fixture, text: String): Boolean {
        if (entry.competitionId == fixture.leagueId) return true
        val leagueText = " " + fixture.leagueId.lowercase(Locale.ROOT).replace('.', ' ') + " "
        return entry.aliases.any { alias ->
            val norm = SportNameMatch.normalize(alias).trim()
            norm.isNotEmpty() && (leagueText.contains(" $norm ") || SportNameMatch.containsPhrase(norm, text))
        }
    }

    private fun matchesScope(scope: Set<String>, value: String?): Boolean {
        if (AnyScope in scope) return true
        if (value.isNullOrBlank()) return false
        return scope.any { it.equals(value, ignoreCase = true) }
    }

    companion object {
        private const val AnyScope = "*"
        private const val SchemaVersion = 1

        val EMPTY = SportsPriors("", emptyList())

        /** Load the bundled dataset; a malformed file degrades to [EMPTY], never a crash. */
        fun load(context: Context): SportsPriors = runCatching {
            val json = context.resources.openRawResource(R.raw.sports_broadcaster_priors)
                .bufferedReader().use { it.readText() }
            parse(json)
        }.getOrDefault(EMPTY)

        /** Pure parser, unit-testable without Android. Unknown schema versions are rejected. */
        fun parse(json: String): SportsPriors {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return EMPTY
            if (root.optInt("schemaVersion", 0) != SchemaVersion) return EMPTY
            val datasetVersion = root.optString("datasetVersion").trim()
            val array = root.optJSONArray("competitions") ?: return EMPTY
            val parsed = ArrayList<PriorEntry>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val competitionId = item.optString("competitionId").trim()
                if (competitionId.isEmpty()) continue
                parsed += PriorEntry(
                    competitionId = competitionId,
                    aliases = item.stringList("aliases"),
                    seasons = item.stringList("seasons").toSet().ifEmpty { setOf(AnyScope) },
                    markets = item.stringList("markets").toSet().ifEmpty { setOf(AnyScope) },
                    carriers = item.stringList("carriers"),
                )
            }
            return if (parsed.isEmpty()) EMPTY else SportsPriors(datasetVersion, parsed)
        }

        private fun JSONObject.stringList(key: String): List<String> {
            val array = optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optString(index).trim().takeIf { it.isNotEmpty() }
            }
        }
    }
}
