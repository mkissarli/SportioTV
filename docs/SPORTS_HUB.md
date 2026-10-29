# Sports Hub

> **Sportio's second headline feature.** It answers one question: *"I know which game is on — which of
> my channels is showing it?"* It lists sports fixtures for 16 leagues, then matches each fixture
> against the user's **own** live channels (by name and by the guide), so one tap starts playback.

Origin: the scheme follows [Lume](https://github.com/bilipp/Lume). Data comes from **ESPN's keyless
Site API** — there is no account, no key, no backend of ours.

---

## 1. Where the code lives

**App (`SportioTV`)** — `app/src/main/java/tv/own/owntv/features/sport/`

| File | Responsibility |
|---|---|
| `SportsLeagues.kt` | The 16 leagues (id → ESPN sport/league slug). 27 lines. |
| `GuideBoard.kt` | League → Where's The Match sport page (carrier rows for the non-ESPN sports). |
| `SportsModels.kt` | `SportLeague`, `SportTeam`, `Fixture`, `FixtureState`, `StandingRow`, `LeagueTable`, `ChannelMatch`. |
| `EspnApi.kt` | OkHttp + `org.json` client: scoreboard + standings, parsing, the date normaliser. **No new dependencies.** |
| `SportsMatcher.kt` | Scores the user's channels against a fixture; returns the best matches. |
| `SportsPrefs.kt` | Its own DataStore file (`sports`): followed leagues, followed teams, hub visibility. |
| `SportsViewModel.kt` | State, caching, follow/unfollow, board/standings/matches loading. |
| `SportsScreen.kt` | The Stage UI: league rail, fixture list, detail pane, standings, settings dialog. |

**Core (`SportioTV_Core`)**

| File | Responsibility |
|---|---|
| `core/.../nav/MainSection.kt` | `MainSection.SPORT` (`R.string.common_nav_sport`), part of `browseOrder`, visible only when the profile has live sources. |
| `core/.../res/values*/strings.xml` | `common_nav_sport` = "Sport". |

**App-side wiring**

| File | What |
|---|---|
| `di/AppModule.kt` | `singleOf(::EspnApi)`, `singleOf(::SportsMatcher)`, `singleOf(::SportsPrefs)`, `viewModelOf(::SportsViewModel)`. |
| `features/shell/OwnTVShell.kt` | Hosts `SportsScreen`; on play it calls `liveVm.ensurePlayingByIdAsync(id, zap)` then `openFullscreen(MainSection.LIVE_TV)`. |
| `res/values*/strings.xml` | The 27 `sport_*` keys (see §9). |
| `ui/components/OwnTVIcon.kt` + `NavDuotoneIcon.kt` | The `SPORT` trophy icon (exhaustive `when` — add a branch if you add an icon). |

---

## 2. Data flow

```
SportsViewModel.init
      │  refreshAll()  ──────────────► EspnApi.scoreboard(league)  for all 16 leagues (parallel)
      ▼
boards: StateFlow<Map<leagueId, BoardState>>      15-minute TTL per league
      │
      │  user picks a league ─► selectLeague(id) ─► load board (cached) + loadStandings(id)
      │  user picks a fixture ─► selectFixture(f) ─► SportsMatcher.match(f)  ─► matches: StateFlow
      ▼
SportsScreen renders rail ▸ fixture list ▸ detail pane (channels + standings)
      │  user taps a channel
      ▼
LiveViewModel.ensurePlayingByIdAsync(channelId, zapContext) ─► fullscreen Live TV
```

The `zapContext` passed to the player is the matched channel list, so once playback starts the user
can zapp through *the channels showing that fixture* with CH+/CH−.

### ESPN endpoints (`EspnApi.kt`)

| Purpose | URL |
|---|---|
| Scoreboard | `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard` |
| Standings | `https://site.web.api.espn.com/apis/v2/sports/{sport}/{league}/standings` |

- The standings call **must** use the `site.web.api` host — the plain `site.api` host answers `200`
  with an **empty body**. (This cost a debugging round; do not "simplify" it.)
- `?dates=yyyyMMdd&limit=100` is appended **only when a date is passed**; the ViewModel never passes
  one, so in practice the board is the API's default window.
- Requests send a browser-ish `User-Agent`, `Accept: application/json` and `Referer: https://www.espn.com/`.
  A non-2xx scoreboard response throws → `BoardState.Error`; a failing standings call is swallowed to
  an empty list.

### JSON fields read

- Scoreboard: `leagues[0].name` (display name, fallback `league.id`), then per event
  `competitions[0].date`, `.status.type.state` (`in`/`post`/`pre`), `.status.shortDetail`,
  `.broadcasts[].names[]`, `.venue.fullName` + `.venue.address.city`, and per competitor
  `team.displayName`/`shortDisplayName`/`abbreviation`/`logo`/`color`, plus `score`, `winner`,
  `homeAway`.
- Standings: `children[].{name, standings.entries[]}`; each entry has `team.*` and `stats[]`
  (`abbreviation`/`shortDisplayName`/`name` → header, `displayValue` → value). The first row's headers
  become the columns; `rank` is a string.

### The date gotcha (`normalizeEspnDate`)

ESPN emits `2026-10-11T13:30Z` **without seconds**, but Java's `Instant.parse` requires them. The
fix is a regex that inserts `:00` before the zone. An event whose date still fails to parse is
**dropped** (logged `nodate`). If you touch date parsing, keep this.

---

## 3. The default leagues (`SportsLeagues.kt`)

| id (follow key) | ESPN path |
|---|---|
| `soccer.ger.1` | soccer/ger.1 (Bundesliga) |
| `soccer.eng.1` | soccer/eng.1 (Premier League) |
| `soccer.esp.1` | soccer/esp.1 (La Liga) |
| `soccer.ita.1` | soccer/ita.1 (Serie A) |
| `soccer.fra.1` | soccer/fra.1 (Ligue 1) |
| `soccer.uefa.champions` | soccer/uefa.champions |
| `soccer.uefa.europa` | soccer/uefa.europa |
| `football.nfl` | football/nfl |
| `basketball.nba` | basketball/nba |
| `baseball.mlb` | baseball/mlb |
| `hockey.nhl` | hockey/nhl |
| `tennis.atp` | tennis/atp |
| `tennis.wta` | tennis/wta |
| `racing.f1` | racing/f1 |
| `racing.motogp` | **not ESPN** — pulselive events calendar (see below) |
| `cycling.uci` / `athletics.world` / `boxing.world` | **not ESPN** — Where's The Match sport page (see below) |
| `mma.ufc` | mma/ufc |
| `golf.pga` | golf/pga |

**MotoGP** has no ESPN league (`racing/motogp` answers 400), so `MotoGpScheduleApi` reads the
official keyless calendar (`api.motogp.pulselive.com/motogp/v1/events?seasonYear=…`) and turns each
`kind = GP` weekend into one EVENT fixture, dated on its last schedule day (race day). `loadBoard`
switches to it by league id, the same way F1 uses `F1ScheduleApi`. Standings stay empty (no source).

**Cycling, athletics and boxing** have no ESPN league either. Each board merges two sources:
- fixtures from a real calendar — boxing from FightNights (`fightnights.com/upcoming-boxing-schedule`,
  worldwide cards with quoted US/UK times) and cycling/athletics from the Wikipedia season pages
  (`<year> UCI World Tour`, `<year> Diamond League`) via `WikipediaCalendarApi` + `WikiCalendar`;
- carrier evidence from the Where's The Match per-sport pages (`live-<sport>-on-tv/`), fetched by
  `BroadcastGuideApi` next to the all-sports page.

`CalendarEvents` merges the two: a guide row naming the same event within 8 h adds its channels to the
calendar entry, and a guide row no calendar covers becomes an entry of its own — an extra row beats a
missing event. Rows carry the catalogue's pseudo slug `espnLeague = "wtm"` (`GuideBoard` maps
league → sport page). Limits: Wikipedia gives dates but no start times (entries sit at noon UTC and
show no clock), and FightNights only lists scheduled cards, not results.

**Display names are not hardcoded** — they come from ESPN's `leagues[0].name`. Before the first load
(and if the name is blank) the UI falls back to the raw id.

**Adding a league:** append one `SportLeague(id, espnSport, espnLeague)` to the list. Nothing else is
required — boards, standings, follows and the rail are all driven off the list. **The list order is
the hub's display order** (the league bands inside a sport and in "All" follow it, not the kickoff
time): F1 and MotoGP first, then football (World Cup, Euros, Champions League, then the domestic
leagues by average attendance — Premier League, La Liga, Bundesliga, Serie A, Ligue 1 — with the FA
Cup and EFL Cup right behind the Europa League, then the other cups, women's leagues, the smaller
leagues and continental club competitions), then tennis, UFC, IndyCar
and the remaining sports. Put a new league where it belongs in that ranking, not at the end.
Verify the slug
against `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard` first, and note
that non-team sports do not work yet (§8).

---

## 4. Channel matching (`SportsMatcher.kt`)

`match(fixture, limit = 6)` runs on `Dispatchers.IO` and returns `List<ChannelMatch>` (score desc).

**Scope.** Only the **active profile's** sources, and only those with `syncLive = true`. If the user
picked a default source, that one source only. No active profile → no matches.

**Candidates.** For each of home/away `abbreviation`, `name` and the name's first word (each ≥ 3
chars), it asks `channelDao.searchList(query, sourceIds, 20)` — a case-insensitive `LIKE %query%` on
the channel name. Each broadcast's longest word ≥ 4 chars is searched too. Candidates de-duplicate by
channel id and stop at 60.

**EPG signal.** For each candidate channel with an EPG id, programmes in
`[fixture.date − 2h, fixture.date + 4h]` are fetched and checked for a team name/abbreviation in the
title.

**Scoring** (on the lowercased channel name):

| Points | Condition |
|---|---|
| +2 | channel name contains the home **or** away **abbreviation** |
| +2 | channel name contains the home **or** away **full name** |
| +3 | channel name contains a broadcast word (≥ 4 chars) |
| +5 | an EPG programme overlapping the fixture has a title containing a team name or abbreviation |

Max 12. **There is no minimum threshold — any score > 0 is kept**, sorted by score, top `limit` (6).
The matched EPG title is surfaced as `ChannelMatch.epgTitle`.

> If matching returns too much noise, tightening the threshold is the obvious lever, but it must stay
> permissive: provider channel names are inconsistent and false negatives ("no channel found") are
> worse than a low-confidence extra row.

---

## 5. Caching and preferences

| Cache | TTL | Notes |
|---|---|---|
| Board (`boards` + `boardAt`) | **15 min** | Skipped when fresh *and* state is `Content`; a failed board retries on the next non-forced call. `refreshSelected()` forces. |
| Standings (`standingsCache`) | none | Read-through `HashMap`, filled by `selectLeague`. |
| Matches (`matchesCache`) | none | Keyed by fixture id, filled by `selectFixture`. |

**`SportsPrefs`** — its own DataStore file, `name = "sports"` (intentionally separate from the core
settings store):

| Key | Type | Default |
|---|---|---|
| `followed_leagues` | string set | **all 16 ids** |
| `followed_teams` | string set | empty |
| `hub_visible` | boolean | true |

A team key is `leagueId + "|" + abbreviation`.

---

## 6. ViewModel API (`SportsViewModel.kt`)

**States:** `BoardState` (`Loading`/`Content`/`Error`), `MatchesState` (`Idle`/`Loading`/`Content`),
`LeagueUi(league, name, followed, liveCount, loading)`.

**Flows:** `followedLeagues`, `followedTeams`, `hubVisible`, `selectedLeagueId`, `selectedFixture`,
`selectedBoard`, `matches`, `standings`, `standingsLoading`, `leagues` (followed first, with live
counts), `myTeamFixtures` (fixtures whose home/away team is followed, LIVE first).

**Actions:** `refreshAll()`, `refreshSelected()`, `selectLeague(id)`, `selectFixture(fixture?)`,
`toggleLeagueFollowed(id)`, `toggleTeamFollowed(leagueId, abbrev)`, `isTeamFollowed(...)`,
`playMatch(match, matches, onPlay)`, `entitiesFor(matches)`, `setHubVisible(visible)`.

**Playback hand-off:** the screen calls `onPlayChannel`, wired in `OwnTVShell.kt` to
`liveVm.ensurePlayingByIdAsync(id, zap)` + `openFullscreen(MainSection.LIVE_TV)`.

---

## 7. Screen (`SportsScreen.kt`)

A three-column `Row`, scaled from a 1920-wide design (`fx(px) = screenWidth * px/1920`):

1. **League rail** (330 wide) — `sport_title`/`sport_leagues`, one row per league; tap = select,
   long-press = follow/unfollow. Shows a live dot with the league's live count.
2. **Fixtures** — league name, **Settings** and **Retry**; a **My teams** chip row; then the grouped
   fixture list. Groups: **Live** (asc) → **Today** (asc) → **Upcoming** (asc) → **Results**
   (desc, **capped at 30**). Empty/error states use `sport_no_games` / `sport_load_failed`.
3. **Detail pane** — the fixture's teams, status, venue, up to 4 broadcasts, follow buttons, the
   **Channels** section (matched channels with a play action), and the **Standings** table (up to
   10 rows × 3 stats per group). An optional inline video preview runs after a 700 ms settle.

The screen auto-selects a fixture on open: the first LIVE, else the earliest upcoming, else the
latest. A **Settings** dialog toggles which leagues are shown.

---

## 8. Known gaps

These are real and visible in the code — check here before filing a bug.

1. **Non-team sports produce no fixtures.** `parseEvent` requires both a home and an away competitor;
   competitors are split by `homeAway == "home"`. Golf, F1, UFC and tennis do not carry that, so all
   their events are dropped and those five leagues always show "No games". They still appear in the
   list and in follows. **This is the biggest known limitation.**
2. **Standings are not loaded for the initially selected league** — `init` only calls `refreshAll()`;
   standings load on `selectLeague`. On first open the table is absent until the user taps a league.
3. **`standingsCache` and `matchesCache` never expire** (plain `HashMap`), unlike the 15-minute board.
4. **`hubVisible` / `setHubVisible` are dead** — persisted and exposed but never read by the screen.
   (Hiding Sport is done through the normal navigation visibility settings instead.)
5. **`limit=100` is never actually applied** — it is coupled to the date branch, which is unused.
6. **Truncations:** results capped at 30, standings at 10 rows × 3 stats, broadcasts at 4 — regardless
   of the payload.
7. **Rank fallback is positional only** — if ESPN omits `rank`, the list index is shown; no points-based
   fallback.
8. **Team identity is abbreviation-based** — `leagueId|abbrev`. Two teams sharing an abbreviation in
   one league would collide, and non-team sports cannot be followed.
9. **The EPG overlap test is effectively always true** inside the 2h/4h window, so it adds no filtering
   beyond the title containment.
10. **Parse failures are silent** except a `Log.w("SportsHub", …)` — a whole-league shape change
    renders empty by design.
11. **Standings/loading state is global, not per league** — switching leagues quickly can briefly show
    the previous league's table.

---

## 9. Strings

The `sport_*` keys live in **this repo** (`app/src/main/res/values/strings.xml` + `values-de/…`), the
nav key `common_nav_sport` lives in **core**. The set: `sport_title`, `sport_leagues`, `sport_my_teams`,
`sport_group_live/today/upcoming/results`, `sport_details`, `sport_standings`, `sport_channels`,
`sport_play`, `sport_no_channel`, `sport_follow`, `sport_unfollow`, `sport_loading`, `sport_load_failed`,
`sport_retry`, `sport_no_games`, `sport_venue`, `sport_broadcast`, `sport_today_word`,
`sport_tomorrow_word`, `sport_yesterday_word`, `sport_score`, `sport_venue_separator`, `sport_settings`,
`sport_settings_leagues`.

New user-visible text goes into `strings.xml` (English) — never inline in Kotlin. See
[I18N.md](I18N.md).
