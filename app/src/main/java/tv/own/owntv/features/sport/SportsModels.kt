package tv.own.owntv.features.sport

import androidx.annotation.StringRes

/**
 * Sports hub data (Lume-style): fixtures, live scores and standings from ESPN, matched against
 * the user's own live channels via EPG so one tap starts playback.
 *
 * ESPN's site API needs no key. League/team/broadcast names are proper nouns and come from the API
 * (or the league catalogue's `labelRes`, kept in `donottranslate.xml`); every label around them is a
 * string resource.
 */

/** Where a league sits in the picker's grouping. */
enum class LeagueRegion { GERMANY, EUROPE, INTERNATIONAL, AMERICAS, REST }

/**
 * How a league's events are shaped:
 *  - [TEAMS] — home/away competitors (soccer, US sports, rugby, …);
 *  - [EVENT] — no home/away (golf, racing, MMA, tennis): rendered from [Fixture.entries] instead.
 */
enum class LeagueKind { TEAMS, EVENT }

data class SportLeague(
    /** Stable id, also the follow key (e.g. "soccer.ger.1"). */
    val id: String,
    /** ESPN scoreboard path segment, e.g. "soccer". */
    val espnSport: String,
    /** ESPN league slug, e.g. "ger.1". */
    val espnLeague: String,
    /** Non-translatable display name (proper noun) in `donottranslate.xml`. */
    @StringRes val labelRes: Int,
    val region: LeagueRegion,
    val kind: LeagueKind,
    /** Active (shown + loaded) on a fresh install. */
    val defaultActive: Boolean = false,
    /**
     * Extra channel search term for this league, matched **in addition to** the normal
     * name/team/broadcast matching — e.g. Formula 1 → "Sky Sports Main Event". A proper noun, so it
     * lives in `donottranslate.xml`.
     */
    @StringRes val extraChannelRes: Int? = null,
)

data class SportTeam(
    val id: String,
    val name: String,
    val abbreviation: String,
    val logoUrl: String?,
    /** ESPN "color" hex without '#', may be blank. */
    val colorHex: String?,
    val score: String?,
    val winner: Boolean,
    val homeAway: String,
)

enum class FixtureState { LIVE, UPCOMING, FINISHED }

/**
 * How a fixture is drawn:
 *  - [VERSUS] — two sides ([Fixture.home]/[Fixture.away], or two [Fixture.entries] for a
 *    no-home/away duel such as MMA/tennis);
 *  - [LEADERBOARD] — an ordered field (golf) drawn from [Fixture.entries];
 *  - [EVENT] — a single occasion with **no participants shown** (a Formula 1 Grand Prix: name +
 *    circuit only, watched through the matched channel).
 */
enum class FixtureFormat { VERSUS, LEADERBOARD, EVENT }

/** One competitor/entry in a [Fixture] that has no home/away shape (single-sport events). */
data class FixtureEntry(
    /** Stable id (ESPN competitor id) — the follow key for a tennis player / MMA fighter. */
    val id: String? = null,
    val name: String,
    val order: Int?,
    /** Score/time/„-3" — whatever the event carries for this entry. */
    val detail: String?,
    val logoUrl: String?,
    val winner: Boolean = false,
)

data class Fixture(
    val id: String,
    val leagueId: String,
    val dateMs: Long,
    val state: FixtureState,
    /** Short status line from ESPN ("Q3 04:12", "FT", "19:30"), never translated. */
    val statusLine: String,
    val format: FixtureFormat = FixtureFormat.VERSUS,
    /** Event name for a [FixtureFormat.EVENT] (e.g. a Grand Prix); null for team/duel/leaderboard. */
    val title: String? = null,
    /** Team sports only; null for [LeagueKind.EVENT] leagues. */
    val home: SportTeam? = null,
    val away: SportTeam? = null,
    /** Single-sport events: ordered entries (golf leaderboard, MMA/tennis duel). */
    val entries: List<FixtureEntry> = emptyList(),
    /** Broadcast names from ESPN ("Sky Sport Bundesliga 1"), matched against channel names. */
    val broadcasts: List<String>,
    /** Extra channel search terms from the league (e.g. F1 → "Sky Sports Main Event"). */
    val channelHints: List<String> = emptyList(),
    /**
     * Tournament name for a nested event (tennis: the event holding the match, e.g. the Shanghai
     * Masters). Null for team sports and single-occasion events. Used only as the weak,
     * tournament-level fallback in channel matching — never shown in UI directly.
     */
    val tournament: String? = null,
    val venue: String?,
)

data class StandingRow(
    val rank: String,
    val teamName: String,
    val teamAbbrev: String,
    val logoUrl: String?,
    /** The stat columns ESPN reports for this league (W/D/L, PTS, ...), in order. */
    val stats: List<String>,
)

data class LeagueTable(
    val leagueId: String,
    val groupName: String?,
    val columns: List<String>,
    val rows: List<StandingRow>,
)

/** A user channel that (probably) carries [fixture], highest score first. */
data class ChannelMatch(
    val channelId: Long,
    val channelName: String,
    val logoUrl: String?,
    /** EPG programme title that matched, when the match came from the guide. */
    val epgTitle: String?,
    val score: Int,
    /**
     * True when the channel's own name carries both sides (both teams, both duel athletes) — an
     * exact per-event channel (`FR: MAX PPV 21` for one tennis duel). Exact hits bypass the
     * language filter: they are the event itself, not a foreign-language feed of it.
     */
    val isExact: Boolean = false,
)

/** A league's team from the ESPN teams endpoint — the phone picker's team rows. */
data class TeamRef(
    val id: String,
    val abbreviation: String,
    val name: String,
    val logoUrl: String?,
)
