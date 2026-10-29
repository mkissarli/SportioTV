package tv.own.owntv.features.sport

import android.content.Context
import java.time.Instant
import java.time.OffsetDateTime
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.R

/**
 * MotoGP calendar from the official (keyless) pulselive backend.
 *
 * Why this exists: ESPN carries no MotoGP league — `racing/motogp` answers 400 — so the regular
 * scoreboard path can never show it. Each Grand Prix weekend becomes one EVENT fixture dated on the
 * race day; channel matching then runs on the event name, exactly like F1 (see `F1ScheduleApi`).
 */
class MotoGpScheduleApi(private val client: OkHttpClient, context: Context) {

    private val appContext = context.applicationContext

    suspend fun scoreboard(): EspnApi.Scoreboard = withContext(Dispatchers.IO) {
        val leagueName = appContext.getString(R.string.sport_league_racing_motogp)
        val races = MotoGpSchedule.parseEvents(JSONArray(get(MotoGpSchedule.eventsUrl(Year.now().value))))
        EspnApi.Scoreboard(leagueName, MotoGpSchedule.fixtures(races, System.currentTimeMillis()))
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(UserAgentHeader, UserAgentValue)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("MotoGP HTTP " + response.code)
            return response.body.string()
        }
    }

    private companion object {
        const val UserAgentHeader = "User-Agent"
        const val UserAgentValue = "Matchday/1 (Android TV IPTV app)"
    }
}

/**
 * Pure mapping from the pulselive events JSON to fixtures — no Android, no IO, unit-testable.
 */
object MotoGpSchedule {

    data class GrandPrix(val id: String, val name: String, val venue: String?, val raceStartMs: Long)

    fun eventsUrl(year: Int): String = EventsUrl + year

    fun parseEvents(events: JSONArray): List<GrandPrix> {
        val out = ArrayList<GrandPrix>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            if (event.optString(KindKey) != GrandPrixKind) continue
            val id = event.optString(IdKey)
            val name = event.optString(NameKey).trim()
            val start = raceStartOf(event)
            if (id.isBlank() || name.isBlank() || start <= 0) continue
            val circuit = event.optJSONObject(CircuitKey)
            val city = circuit?.optString(CityKey)?.trim().orEmpty()
            val venue = city.ifEmpty { circuit?.optString(NameKey)?.trim().orEmpty() }
            out += GrandPrix(id, name, venue.ifEmpty { null }, start)
        }
        return out.sortedBy { it.raceStartMs }
    }

    /** EVENT fixtures: the race name, its circuit and a 2 h live window. */
    fun fixtures(races: List<GrandPrix>, nowMs: Long): List<Fixture> = races.map { race ->
        val state = when {
            nowMs < race.raceStartMs -> FixtureState.UPCOMING
            nowMs < race.raceStartMs + RaceDurationMs -> FixtureState.LIVE
            else -> FixtureState.FINISHED
        }
        Fixture(
            id = IdPrefix + race.id,
            leagueId = MotoGpLeagueId,
            dateMs = race.raceStartMs,
            state = state,
            statusLine = localTimeOf(race.raceStartMs),
            format = FixtureFormat.EVENT,
            title = race.name,
            home = null,
            away = null,
            broadcasts = emptyList(),
            channelHints = emptyList(),
            venue = race.venue,
        )
    }

    /**
     * The last schedule day is race day and carries its start ("2026-03-01T16:40:00+0700"). The
     * event's own `date_end` is the fallback when the schedule is missing.
     */
    private fun raceStartOf(event: JSONObject): Long {
        val options = event.optJSONObject(ScheduleKey)?.optJSONArray(OptionsKey)
        if (options != null && options.length() > 0) {
            val last = options.optJSONObject(options.length() - 1)?.optString(StartKey).orEmpty()
            val start = epochOf(last)
            if (start > 0) return start
        }
        return epochOf(event.optString(EndKey))
    }

    /** Pulselive writes offsets without a colon ("+0700"); java.time wants "+07:00". */
    private fun epochOf(raw: String): Long {
        if (raw.isBlank()) return 0L
        val iso = raw.trim().replace(OffsetPattern, "$1:$2")
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    private fun localTimeOf(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** MotoGP league id — the board switches to this source for it (see `SportsViewModel.loadBoard`). */
    const val MotoGpLeagueId = "racing.motogp"

    private const val EventsUrl = "https://api.motogp.pulselive.com/motogp/v1/events?seasonYear="
    private val OffsetPattern = Regex("([+-]\\d{2})(\\d{2})$")
    private const val IdPrefix = "motogp-"
    private const val GrandPrixKind = "GP"
    private const val IdKey = "id"
    private const val NameKey = "name"
    private const val KindKey = "kind"
    private const val EndKey = "date_end"
    private const val CircuitKey = "circuit"
    private const val CityKey = "city"
    private const val ScheduleKey = "schedule"
    private const val OptionsKey = "options"
    private const val StartKey = "dateStart"
    private const val RaceDurationMs = 2L * 60L * 60L * 1000L
}
