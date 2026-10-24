package tv.own.owntv.features.sport

import java.text.Normalizer
import java.util.Locale

/**
 * Pure, stateless text helpers for the Sports-Hub channel matching.
 *
 * Ported from Lume's `SportsMatcher.normalize` / `tokens(forName:)` (AGPL-3.0,
 * https://github.com/bilipp/Lume) so a provider's spelling and a team's ESPN name meet in the
 * middle:
 *
 *  - **Accents folded** — "München" and "Munchen" normalize to the same token, as do "Köln" and
 *    "Koln" and the German "ß" → "ss" (Fußball → fussball).
 *  - **Punctuation flattened** — every non-alphanumeric run becomes a single space, so
 *    "Borussia M'gladbach", "1. FC Köln" and "Brighton & Hove Albion" tokenize cleanly.
 *  - **Space-padded words** — [normalize] wraps the result in spaces so [containsWord] can rely on
 *    whole-word bounds: "city" must NOT match inside "velocity".
 *
 * Everything here is static and dependency-free by design: it can be unit-tested without Android,
 * a database or a network, and it holds no vocabulary — the stop-word and sport-word sets live in
 * `res/raw/sports_matching.json` and are passed in (see [SportsVocab]).
 *
 * Two distinct call paths use these helpers, and they must not be confused:
 *
 *  1. **Discovery** ([discoveryPhrases]) feeds SQL `LIKE '%…%'`. It works on the **raw, accented**
 *     spelling, because the database stores the feed's own text — `LIKE '%Munchen%'` would miss a
 *     stored "München".
 *  2. **Scoring** ([tokensForName], [containsWord]) works on **normalized** text on both sides, so
 *     accents and casing never decide a match.
 */
object SportNameMatch {

    /**
     * Lowercases, folds accents/diacritics, turns every non-alphanumeric run into one space and
     * pads the result with a leading and trailing space. Returns [Padded] (two spaces) for empty
     * input, so callers can always split/trim safely.
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return Padded
        val lowered = text.lowercase(Locale.ROOT).replace("ß", "ss")
        val stripped = combiningMarks.replace(Normalizer.normalize(lowered, Normalizer.Form.NFD), "")
        return PaddedSpace + nonAlphanumeric.replace(stripped, PaddedSpace).trim() + PaddedSpace
    }

    /**
     * Whole-word containment against a [normalize]d haystack: the token must be bounded by the
     * padding/non-word characters, so "city" does not match inside "velocity".
     */
    fun containsWord(token: String, haystack: String): Boolean =
        token.isNotEmpty() && haystack.contains(PaddedSpace + token + PaddedSpace)

    /**
     * Whole-phrase containment against a [normalize]d haystack. Used to spot a broadcast's full
     * name ("Sky Sport Bundesliga 1") in a channel name, where matching the phrase is a far stronger
     * signal than matching any single word of it.
     */
    fun containsPhrase(phrase: String, haystack: String): Boolean {
        val normalized = normalize(phrase).trim()
        return normalized.isNotEmpty() && haystack.contains(PaddedSpace + normalized + PaddedSpace)
    }

    /**
     * [containsPhrase] that ignores a trailing plural "s" on any word — providers write
     * "UK: SKY SPORT MAIN EVENT" and "UK: TNT SPORT 1" where the guides write "Sky Sports" and
     * "TNT Sports", and a literal phrase match would never see them.
     */
    fun containsPhraseLoose(phrase: String, haystack: String): Boolean =
        containsPhrase(singularize(normalize(phrase)), singularize(haystack))

    /**
     * [text] normalized with a trailing plural "s" dropped from every word ("sports" → "sport").
     * Keeps normalize's space padding, so the result still works with [containsPhrase].
     */
    fun singularize(text: String): String {
        val norm = normalize(text)
        if (norm.isEmpty()) return norm
        val words = norm.trim().split(' ').joinToString(" ") { word ->
            if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word
        }
        return PaddedSpace + words + PaddedSpace
    }

    /**
     * The distinctive tokens of one name for scoring: normalized words of at least
     * [MinTokenLength], with [stopwords] removed. Falls back to the unfiltered words when filtering
     * would leave nothing (e.g. a club actually named "Club"), never to empty.
     */
    fun tokensForName(name: String, stopwords: Set<String>): Set<String> {
        val words = normalize(name).trim().split(' ').filter { it.length >= MinTokenLength }
        val meaningful = words.filterNot { it in stopwords }
        return (if (meaningful.isEmpty()) words else meaningful).toSet()
    }

    /**
     * The raw (accent-preserving) strings to feed `LIKE '%…%'`: the full phrase plus each word of at
     * least [MinTokenLength] that is not in [stopwords]. The full phrase is kept because it is the
     * most specific probe a channel name can answer ("FC Bayern München" in "FC Bayern München HD").
     *
     * Raw, not normalized: the database holds the feed's own spelling. See the class KDoc.
     */
    fun discoveryPhrases(text: String, stopwords: Set<String>): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val words = trimmed.split(WordSplit)
            .filter { it.length >= MinTokenLength && it.lowercase(Locale.ROOT) !in stopwords }
        return (listOf(trimmed) + words).distinct()
    }

    /** Whether an EPG `<category>` string names a sport we care about. Whole-word, normalized. */
    fun mentionsSport(category: String, sportWords: Set<String>): Boolean {
        if (sportWords.isEmpty() || category.isEmpty()) return false
        val haystack = normalize(category)
        return sportWords.any { containsWord(it, haystack) }
    }

    /** Two spaces — the padded form an empty string normalizes to. */
    private const val Padded = "  "
    private const val MinTokenLength = 3

    /** A single space; a named constant so the intent is legible at every call site. */
    private const val PaddedSpace = " "

    private val WordSplit = Regex("\\s+")
    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")
}
