package tv.own.owntv.features.sport

import android.content.Context
import java.io.IOException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.R

/**
 * DeepSeek chat-completions client for the Sports hub's AI recommendation.
 *
 * The model is asked for broadcaster **names only** per requested country. It never receives the
 * user's playlists, channel ids or stream URLs, and its answer is matched against the local channel
 * catalogue afterwards ([AiChannelMatcher]). A model answer is therefore a hint, never a stream.
 *
 * The second job is the playlist import: there the model reads a pasted provider message
 * ([analyzeSourceMessage]). That one does carry access data to DeepSeek — the import page asks for
 * the user's consent first (see docs/PLAYLIST-IMPORT.md).
 *
 * The prompt template lives in `res/raw/sports_ai_prompt.txt` (technical, not translated); the
 * import's own template is `res/raw/source_import_prompt.txt`. The API key is read from
 * `SportsPrefs` and sent only as the `Authorization` header; it is never logged and never placed in
 * a URL.
 */
class DeepSeekClient(
    client: OkHttpClient,
    context: Context,
) {

    private val appContext = context.applicationContext
    private val baseClient = client
    private val http = client.newBuilder()
        .connectTimeout(ConnectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(ReadTimeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(CallTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    /** A full-playlist request is large but still bounded: 120 s read, 180 s call. */
    private val playlistHttp by lazy {
        baseClient.newBuilder()
            .connectTimeout(ConnectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(PlaylistReadTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(PlaylistCallTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    private val promptTemplate: String by lazy {
        appContext.resources.openRawResource(R.raw.sports_ai_prompt).bufferedReader().use { it.readText() }
    }

    private val sourcePromptTemplate: String by lazy {
        appContext.resources.openRawResource(R.raw.source_import_prompt).bufferedReader().use { it.readText() }
    }

    /**
     * Ask DeepSeek for the likely broadcasters of [fixture] in [countries]. Throws
     * [AiRequestException] with a typed [AiFailure] on every error path; empty answers become
     * [AiFailure.EMPTY] so the UI can say so instead of showing a blank result.
     */
    suspend fun recommend(
        fixture: Fixture,
        countries: List<AiCountry>,
        apiKey: String,
    ): List<AiBroadcast> {
        val key = extractKey(apiKey)
        if (key.isEmpty()) throw AiRequestException(AiFailure.NO_KEY)
        if (countries.isEmpty()) return emptyList()
        val content = ask(SystemPrompt, buildPrompt(fixture, countries), key)
        val broadcasts = AiResponseParser.parse(content, countries)
        if (broadcasts.isEmpty()) throw AiRequestException(AiFailure.EMPTY)
        return broadcasts
    }

    /**
     * Ask DeepSeek to read a pasted provider message (the "URL / user / pass" blob a shop or
     * reseller sends) and answer with the connection details.
     *
     * Returns the model's **raw** answer; the caller parses it, because only the caller knows what
     * a field means ([tv.own.owntv.features.setup.SourceMessageAiParser]). Throws
     * [AiRequestException] with a typed [AiFailure] on every error path.
     */
    suspend fun analyzeSourceMessage(message: String, apiKey: String): String {
        val key = extractKey(apiKey)
        if (key.isEmpty()) throw AiRequestException(AiFailure.NO_KEY)
        val content = ask(SourceSystemPrompt, buildSourcePrompt(message), key)
        if (content.isBlank()) throw AiRequestException(AiFailure.EMPTY)
        return content
    }

    /**
     * One full-playlist selection request. The caller builds both prompts (the catalogue is local);
     * this method only enforces the key, the bounded timeout and the typed failure mapping. The
     * answer is returned raw so schema validation stays a pure, testable step.
     */
    suspend fun completePlaylistSelection(system: String, user: String, apiKey: String): String {
        val key = extractKey(apiKey)
        if (key.isEmpty()) throw AiRequestException(AiFailure.NO_KEY)
        return ask(system, user, key, playlistHttp)
    }

    /**
     * One two-phase board request. Both prompts are built locally; this method only enforces the
     * key, the bounded timeout and the typed failure mapping. The answer stays raw so schema
     * validation remains a pure, testable step.
     */
    suspend fun completeBoardMatching(system: String, user: String, apiKey: String): String {
        val key = extractKey(apiKey)
        if (key.isEmpty()) throw AiRequestException(AiFailure.NO_KEY)
        return ask(system, user, key, playlistHttp, BoardMaxTokens)
    }

    /**
     * One chat completion, returning the answer's content. Every error path becomes an
     * [AiRequestException] with a typed [AiFailure], so the caller never has to read an HTTP code.
     */
    private suspend fun ask(
        system: String,
        user: String,
        key: String,
        client: OkHttpClient = http,
        maxTokens: Int = MaxTokens,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(Endpoint)
            .header(AuthorizationHeader, AuthPrefix + key)
            .header(ContentTypeHeader, JsonMediaType)
            .header(AcceptHeader, JsonMediaType)
            .post(requestBody(system, user, maxTokens))
            .build()

        val responseText = try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw AiRequestException(failureFor(response.code))
                }
                body
            }
        } catch (e: AiRequestException) {
            throw e
        } catch (e: IOException) {
            throw AiRequestException(AiFailure.NETWORK, e)
        }

        try {
            JSONObject(responseText)
                .optJSONArray(ChoicesKey)
                ?.optJSONObject(0)
                ?.optJSONObject(MessageKey)
                ?.optString(ContentKey)
                .orEmpty()
        } catch (e: Exception) {
            throw AiRequestException(AiFailure.UNKNOWN, e)
        }
    }

    /**
     * The key as the API wants it: without the `Bearer ` prefix a user may have pasted along, and
     * without the spaces copy/paste likes to add.
     */
    private fun extractKey(rawKey: String): String {
        val trimmed = rawKey.trim()
        val withoutPrefix = if (trimmed.startsWith(AuthPrefix, ignoreCase = true)) {
            trimmed.substring(AuthPrefix.length)
        } else {
            trimmed
        }
        return withoutPrefix.filterNot { it.isWhitespace() }
    }

    private fun requestBody(system: String, user: String, maxTokens: Int): okhttp3.RequestBody {
        val messages = JSONArray()
            .put(JSONObject().put(RoleKey, SystemRole).put(ContentKey, system))
            .put(JSONObject().put(RoleKey, UserRole).put(ContentKey, user))
        val body = JSONObject()
            .put(ModelKey, Model)
            .put(MessagesKey, messages)
            .put(TemperatureKey, Temperature)
            .put(MaxTokensKey, maxTokens)
            .put(ResponseFormatKey, JSONObject().put(ResponseFormatTypeKey, JsonObjectType))
        return body.toString().toRequestBody(JsonMediaType.toMediaType())
    }

    private fun buildPrompt(fixture: Fixture, countries: List<AiCountry>): String {
        val league = sportsLeagueById(fixture.leagueId)
            ?.let { runCatching { appContext.getString(it.labelRes) }.getOrDefault(fixture.leagueId) }
            ?: fixture.leagueId
        val countriesText = countries.joinToString(", ") { "${it.code} (${it.promptName})" }
        val broadcasts = fixture.broadcasts.take(MaxPromptBroadcasts).joinToString(", ")
        val venue = fixture.venue.orEmpty()
        return String.format(
            Locale.ROOT,
            promptTemplate,
            fixtureEventName(fixture),
            league,
            Instant.ofEpochMilli(fixture.dateMs).toString(),
            venue.ifBlank { UnknownValue },
            broadcasts.ifBlank { UnknownValue },
            countriesText,
        )
    }

    /**
     * The pasted message goes into the template as it is. A `%` in a password would otherwise be
     * read as a format placeholder, so it is escaped — the model has to see the password the user
     * actually sent.
     */
    private fun buildSourcePrompt(message: String): String =
        String.format(Locale.ROOT, sourcePromptTemplate, message.replace("%", "%%"))

    /** A readable event name; team names are proper nouns from ESPN, never translated. */
    private fun fixtureEventName(fixture: Fixture): String {
        val home = fixture.home
        val away = fixture.away
        return when {
            home != null && away != null -> home.name + VersusSeparator + away.name
            fixture.format == FixtureFormat.VERSUS ->
                fixture.entries.take(2).joinToString(VersusSeparator) { it.name }
            fixture.title != null -> fixture.title
            else -> fixture.entries.firstOrNull()?.name ?: fixture.leagueId
        }
    }

    /** HTTP status → user-facing failure kind (the UI owns the translated sentence). */
    private fun failureFor(code: Int): AiFailure = when (code) {
        401, 403 -> AiFailure.INVALID_KEY
        402 -> AiFailure.INSUFFICIENT_CREDIT
        429 -> AiFailure.RATE_LIMITED
        in 500..599 -> AiFailure.NETWORK
        else -> AiFailure.UNKNOWN
    }

    private companion object {
        const val Endpoint = "https://api.deepseek.com/chat/completions"
        /** Current documented model (2026-10): deepseek-flash, 1M-token context. */
        const val Model = AiPlaylistPolicy.Model
        const val JsonMediaType = "application/json; charset=utf-8"
        const val AuthorizationHeader = "Authorization"
        const val AuthPrefix = "Bearer "
        const val ContentTypeHeader = "Content-Type"
        const val AcceptHeader = "Accept"
        const val ModelKey = "model"
        const val MessagesKey = "messages"
        const val TemperatureKey = "temperature"
        const val MaxTokensKey = "max_tokens"
        const val ResponseFormatKey = "response_format"
        const val ResponseFormatTypeKey = "type"
        const val JsonObjectType = "json_object"
        const val RoleKey = "role"
        const val SystemRole = "system"
        const val UserRole = "user"
        const val ContentKey = "content"
        const val ChoicesKey = "choices"
        const val MessageKey = "message"
        const val SystemPrompt = "You are a precise sports TV guide. Reply with valid JSON only."
        const val SourceSystemPrompt =
            "You read IPTV provider messages and answer with the access data they contain. Reply with valid JSON only."
        const val VersusSeparator = " vs "
        const val UnknownValue = "unknown"
        const val Temperature = 0.2
        const val MaxTokens = 3_000
        const val BoardMaxTokens = 8_000
        const val MaxPromptBroadcasts = 8
        const val ConnectTimeoutSeconds = 15L
        const val ReadTimeoutSeconds = 60L
        const val CallTimeoutSeconds = 75L
        const val PlaylistReadTimeoutSeconds = 120L
        const val PlaylistCallTimeoutSeconds = 180L
    }
}

/**
 * Parses the model's JSON answer. Separated from the network client so the schema handling can be
 * unit-tested on the JVM without OkHttp or Android.
 *
 * Accepted shapes (the prompt asks for the first, the others are cheap robustness):
 *  - `{"broadcasts":[{"country":"DE","channel":"Sky Sport Bundesliga 1"}]}`
 *  - a bare JSON array of the same objects
 *  - fenced JSON (` ```json … ``` `), which some models still emit despite JSON mode.
 */
object AiResponseParser {

    fun parse(content: String, allowed: List<AiCountry>): List<AiBroadcast> {
        val json = stripFences(content)
        if (json.isBlank()) return emptyList()
        val array = try {
            if (json.startsWith(JsonArrayStart)) JSONArray(json)
            else JSONObject(json).optJSONArray(BroadcastsKey)
        } catch (e: Exception) {
            return emptyList()
        } ?: return emptyList()

        val allowedSet = allowed.toSet()
        val seen = HashSet<String>()
        val out = ArrayList<AiBroadcast>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val country = AiCountry.fromCodeOrName(item.optString(CountryKey)) ?: continue
            if (country !in allowedSet) continue
            val channel = listOf(ChannelKey, BroadcasterKey, NameKey)
                .asSequence()
                .map { item.optString(it).trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: continue
            val dedupe = country.code + DedupeSeparator + channel.lowercase(Locale.ROOT)
            if (seen.add(dedupe)) out.add(AiBroadcast(country, channel))
        }
        return out
    }

    private fun stripFences(content: String): String {
        var text = content.trim()
        if (!text.startsWith(Fence)) return text
        text = text.removePrefix(FenceJson).removePrefix(Fence).trim()
        val end = text.lastIndexOf(Fence)
        if (end >= 0) text = text.substring(0, end).trim()
        return text
    }

    private const val BroadcastsKey = "broadcasts"
    private const val CountryKey = "country"
    private const val ChannelKey = "channel"
    private const val BroadcasterKey = "broadcaster"
    private const val NameKey = "name"
    private const val DedupeSeparator = "|"
    private const val JsonArrayStart = "["
    private const val Fence = "```"
    private const val FenceJson = "```json"
}
