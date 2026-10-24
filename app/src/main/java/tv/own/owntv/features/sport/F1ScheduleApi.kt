package tv.own.owntv.features.sport

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.R

/**
 * F1 weekend schedule from Jolpica (Ergast's free successor — no key, no auth).
 *
 * Why this exists: ESPN's racing scoreboard returns only the **most recent** event and already
 * marks it `post` while the race weekend is still ahead, so the ESPN path can never show "this
 * weekend's Grand Prix" as live/upcoming. Jolpica carries the authoritative calendar with
 * practice/qualifying/sprint/race times, so the F1 league's board is built from it instead.
 * Every session becomes an EVENT fixture; the channel matching is unchanged (the league's
 * "Sky Sports Main Event" hint).
 */
class F1ScheduleApi(private val client: OkHttpClient, context: Context) {

    private val appContext = context.applicationContext

    suspend fun scoreboard(): EspnApi.Scoreboard = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val hint = appContext.getString(R.string.sport_channel_sky_main_event)
        val leagueName = appContext.getString(R.string.sport_league_racing_f1)
        val rounds = runCatching { fetchCalendar() }.getOrDefault(emptyList())
        val detailed = rounds.map { round ->
            if (F1Schedule.needsDetail(round, now)) {
                runCatching { fetchRoundDetail(round.round) }.getOrDefault(round)
            } else {
                round
            }
        }
        EspnApi.Scoreboard(leagueName, F1Schedule.fixtures(detailed, now, hint))
    }

    private fun fetchCalendar(): List<F1Schedule.Round> {
        val root = get(CalendarUrl)
        return F1Schedule.parseCalendar(root)
    }

    private fun fetchRoundDetail(round: String): F1Schedule.Round {
        val root = get(DetailBase + round + DetailSuffix)
        return F1Schedule.parseRoundDetail(root)
    }

    private fun get(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header(UserAgentHeader, UserAgentValue)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Jolpica HTTP " + response.code)
            return JSONObject(response.body.string())
        }
    }

    private companion object {
        const val CalendarUrl = "https://api.jolpi.ca/ergast/f1/current.json"
        const val DetailBase = "https://api.jolpi.ca/ergast/f1/current/"
        const val DetailSuffix = ".json"
        const val UserAgentHeader = "User-Agent"
        const val UserAgentValue = "Matchday/1 (Android TV IPTV app)"
    }
}

/**
 * Pure mapping from Jolpica JSON to fixtures — no Android, no IO, unit-testable on the JVM.
 */
object F1Schedule {

    data class Session(val key: String, val label: String, val startMs: Long, val durationMs: Long)

    data class Round(val round: String, val raceName: String, val locality: String, val sessions: List<Session>) {
        val raceStartMs: Long get() = sessions.lastOrNull()?.startMs ?: 0L
    }

    /** The race weekend's own label order: practice sessions, sprint, qualifying, race. */
    private val SessionOrder = listOf(
        Triple("FirstPractice", "fp1", "Practice 1"),
        Triple("SecondPractice", "fp2", "Practice 2"),
        Triple("ThirdPractice", "fp3", "Practice 3"),
        Triple("Sprint", "sprint", "Sprint"),
        Triple("Qualifying", "qualifying", "Qualifying"),
    )

    fun parseCalendar(json: JSONObject): List<Round> {
        val races = json.optJSONObject(MrDataKey)?.optJSONObject(RaceTableKey)?.optJSONArray(RacesKey)
            ?: return emptyList()
        val out = ArrayList<Round>()
        for (i in 0 until races.length()) {
            val race = races.optJSONObject(i) ?: continue
            val round = race.optString(RoundKey)
            val raceName = race.optString(RaceNameKey)
            val date = race.optString(DateKey)
            val time = race.optString(TimeKey)
            if (round.isBlank() || raceName.isBlank()) continue
            val locality = race.optJSONObject(CircuitKey)?.optJSONObject(LocationKey)?.optString(LocalityKey).orEmpty()
            val raceStart = epochOf(date, time)
            val sessions = buildList {
                // Session times arrive with the round detail; the calendar alone carries the race.
                add(Session(RaceSessionKey, "Race", raceStart, RaceDurationMs))
            }
            out += Round(round, raceName, locality, sessions)
        }
        return out
    }

    /** Merge one round's session times into its calendar entry. */
    fun parseRoundDetail(json: JSONObject): Round {
        val race = json.optJSONObject(MrDataKey)?.optJSONObject(RaceTableKey)?.optJSONArray(RacesKey)
            ?.optJSONObject(0) ?: return Round("", "", "", emptyList())
        val round = race.optString(RoundKey)
        val raceName = race.optString(RaceNameKey)
        val locality = race.optJSONObject(CircuitKey)?.optJSONObject(LocationKey)?.optString(LocalityKey).orEmpty()
        val sessions = ArrayList<Session>()
        for ((jsonKey, key, label) in SessionOrder) {
            val session = race.optJSONObject(jsonKey) ?: continue
            val start = epochOf(session.optString(DateKey), session.optString(TimeKey))
            if (start > 0) sessions += Session(key, label, start, if (key == SprintSessionKey) SprintDurationMs else SessionDurationMs)
        }
        val raceStart = epochOf(race.optString(DateKey), race.optString(TimeKey))
        sessions += Session(RaceSessionKey, "Race", raceStart, RaceDurationMs)
        return Round(round, raceName, locality, sessions.sortedBy { it.startMs })
    }

    /** Whether one round's session times are worth a second request (this or the next weekends). */
    fun needsDetail(round: Round, nowMs: Long): Boolean =
        round.raceStartMs in (nowMs - DetailBeforeMs)..(nowMs + DetailAfterMs)

    /** EVENT fixtures per session: the race keeps the plain name, sessions get their label. */
    fun fixtures(rounds: List<Round>, nowMs: Long, hint: String): List<Fixture> =
        rounds.flatMap { round ->
            round.sessions.mapNotNull { session ->
                if (session.startMs <= 0) return@mapNotNull null
                val title = if (session.key == RaceSessionKey) round.raceName else round.raceName + TitleSeparator + session.label
                val state = when {
                    nowMs < session.startMs -> FixtureState.UPCOMING
                    nowMs < session.startMs + session.durationMs -> FixtureState.LIVE
                    else -> FixtureState.FINISHED
                }
                Fixture(
                    id = "f1-" + round.round + "-" + session.key,
                    leagueId = F1LeagueId,
                    dateMs = session.startMs,
                    state = state,
                    statusLine = localTimeOf(session.startMs),
                    format = FixtureFormat.EVENT,
                    title = title,
                    home = null,
                    away = null,
                    broadcasts = emptyList(),
                    channelHints = listOf(hint),
                    venue = round.locality.ifBlank { null },
                )
            }
        }.sortedBy { it.dateMs }

    /** "2026-10-25" + "20:00:00Z" → epoch millis (0 when unparseable or absent). */
    private fun epochOf(date: String, time: String): Long {
        if (date.isBlank() || time.isBlank()) return 0L
        return runCatching {
            LocalDateTime.parse(date + "T" + time.removeSuffix("Z"), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun localTimeOf(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** F1 league id — the board switches to this source for it (see `SportsViewModel.loadBoard`). */
    const val F1LeagueId = "racing.f1"

    private const val MrDataKey = "MRData"
    private const val RaceTableKey = "RaceTable"
    private const val RacesKey = "Races"
    private const val RoundKey = "round"
    private const val RaceNameKey = "raceName"
    private const val DateKey = "date"
    private const val TimeKey = "time"
    private const val CircuitKey = "Circuit"
    private const val LocationKey = "Location"
    private const val LocalityKey = "locality"
    private const val RaceSessionKey = "race"
    private const val SprintSessionKey = "sprint"
    private const val TitleSeparator = " — "
    private const val RaceDurationMs = 2L * 60L * 60L * 1000L
    private const val SprintDurationMs = 60L * 60L * 1000L
    private const val SessionDurationMs = 60L * 60L * 1000L
    private const val DetailBeforeMs = 3L * 24L * 60L * 60L * 1000L
    private const val DetailAfterMs = 21L * 24L * 60L * 60L * 1000L
}
