# docs/BROADCAST-SOURCES.md — Source inventory (as of 2026-11-01)

> Purpose: what is actually integrated, what was only investigated, and why. A source is not
> "integrated" until its adapter, parser test and refresh wiring exist.

## 1. Integrated sources

| Source | `sourceId` / family | Content | Specificity | TTL | Parser |
|---|---|---|---|---|---|
| FootTV.fr (`https://www.foottv.fr/`) | `foottv` / `foottv` | French football, fixture rows, numbered Ligue 1+ feeds | `FIXTURE` + exact channel; network names (`Ligue 1+`, `DAZN`) → `SERVICE` | 60 min | `BroadcastSourceParsers.footTv` |
| Foot Mercato (`https://www.footmercato.net/programme-tv/`) | `footmercato` / `footmercato` | European football in French, fixture rows, numbered Ligue 1+ feeds | `FIXTURE` + exact channel; network names (`Ligue 1+`, `DAZN`, `Canal+`) → `SERVICE` | 60 min | `footMercato` |
| Live Football on TV (`https://www.live-footballontv.com/`) | `livefootballontv` / `livefootballontv` | UK football, fixture rows, linear channels | `FIXTURE` + exact channel; `DAZN`/`Amazon Prime`/`Ligue1+` → `SERVICE` | 60 min | `liveFootballOnTv` |
| Sky Sports Tennis (`https://www.skysports.com/watch/tennis-on-sky`) | `skysports-tennis` / `sky` | UK tennis, tournament/day rows with channel and start time | `TOURNAMENT` | 60 min | `skySportsTennis` |
| Sport-TV.fr Tennis (`https://sport-tv.fr/tennis/`) | `sport-tv-tennis` / `sport-tv` | French tennis, session rows ("session soir", final) | `SESSION` | 60 min | `sportTvTennis` |
| Where's The Match (`https://www.wheresthematch.com/`) | `wheresthematch` / `wheresthematch` | Existing: all sports, fixture rows and tennis tour rows | `FIXTURE` / `TOURNAMENT` | 30 min | `GuideParsers.wtm` |

All four new adapters live in `BroadcastSources.kt`; the hub (`BroadcastSourceHub`) is refreshed
by the existing `BroadcastGuideApi.refresh()` in the background. `listingsFor(fixture)` only
reads the last snapshot — no network on the fixture/UI path.

### Opt-in per adapter

Every new adapter is **off by default**. Sport settings → **Channels** → **Broadcast sources**
shows one switch per adapter. Switching one on fetches it immediately; switching it off drops
its cached rows and stops all future requests. The existing Where's The Match guide is not part
of this switch list and keeps working as before. A fresh install therefore makes no request to
FootTV, Foot Mercato, Live Football, Sky or Sport-TV until the user opts in.

### Fetch rules

- One page request per source per refresh, no season download, no detail pages.
- Sources refresh one at a time, not in parallel: a TV has little heap headroom, and
  five ~1 MB pages plus their regex trees at once is avoidable peak memory.
- Hard byte limits: FootTV 3 MB, Foot Mercato 3 MB, Live Football 3 MB, Sky 2 MB,
  Sport-TV 1 MB.
- 15 s timeout per source, one retry, `withTimeout` in the hub.
- At most one concurrent refresh (`BroadcastGuideApi.refreshing`).
- Empty page ≠ "not televised": the previous snapshot is kept.
- Failed refresh: the previous snapshot is kept and marked stale when read
  (`retrievedAtMs = 0`, so it can no longer reach `Supported`).
- Measurement on 2026-11-01 (real pages, 8-day window): FootTV 394 listings,
  Foot Mercato 183 listings across 158 fixtures, Live Football 435, Sky tennis 22,
  Sport-TV tennis 11. FootTV writes some channels as links and some as
  `<li class="canal-sin-enlace">`; both are read. Foot Mercato carries channels as
  `matchFull__broadcastImage` logos and the numbered feed in the image `alt`
  (e.g. `alt="Logo Ligue 1+ 2"`). Affiliate redirects (`awin1.com`, "Voir en
  direct") are not carriers.
- `robots.txt` was checked on 2026-11-01. FootTV allows the schedule page `/`
  (disallows include `/canal`, `/competicion`, `/api`); Live Football allows all;
  Sky and Sport-TV allow `/`. Only public schedule pages are read — no channel pages,
  no API, no paywall, no bot-protection bypass.
- Attribution stays in the model (`sourceId`, `sourceFamily`, `sourceRef`). Only facts
  (teams, times, channel names) are transferred, not page text.

### Provenance and syndication

`sourceFamily` is the dedup family, not the website. Two sites that demonstrably mirror
the same upstream dataset must share a family and then count once. No shared upstream
database is proven for the integrated sources, so they are kept as separate families;
FootTV and Foot Mercato are both French football aggregators, so if they are later shown
to consume one upstream feed they should be merged into one family. The `underlyingKey`
is `family|eventId|channelKey|coverageType` — the same row from one source (even
repeated) does not increase the score, while a service listing and an exact feed remain
different evidence.

## 2. Investigated, not integrated

| Source | Finding | Decision |
|---|---|---|
| `whereismatch.com/football-on-tv/` | robots allows `/proxy/matches*`; structured proxy endpoints are visible. | Deferred: structure/provenance and terms not reviewed enough; WTM already covers the path. |
| `footballkickoff.co.uk` | robots allows; homepage is a ~10 KB JS shell; TheSportsDB premium v2 provenance is claimed, not verified. | Rejected: paid upstream must not become a runtime dependency; no own parser. |
| `skysports.com/watch/football` | robots allows; channel/tournament schedule, similar to the tennis page. | Not integrated: UK football is already covered by Live Football; it would double the same Sky schedule family. |
| `ligue1.com` | robots allows (some query paths disallowed); official match sheets carry fixture JSON-LD and a channel heading. Foot Mercato already exposes the same numbered Ligue 1+ feeds from the same broadcaster schedule. | Not integrated separately: one reliable numbered-feed adapter is enough; the official page remains the source of truth for manual checks. |
| `losc.fr/diffuseurs` | robots allows; club page names broadcasters, not the channel per match. | Rejected: service/rights level only. |
| `wheresthematch.com/live-tennis-on-tv/` | Already covered by the existing WTM all-sports fetch; tennis rows are tour/day rows. | Kept, no second integration. |
| `livetennis.com/how-to-watch` | robots returns HTTP 403. | Rejected: no automated access. |
| `sportsontv.co.uk/home/sport/tennis` | robots allows; tennis listings exist (Sky/Eurosport). | Not integrated: Sky tennis already covers the UK; the page is heavy and would add another family without proven precision gain. |
| `lta.org.uk/fan-zone/watch-live/` | robots allows; federation/watch page, no fixture-to-channel schedule. | Rejected: rights/service level. |
| `lequipe.fr/programme-tv/agenda/` | robots returns HTTP 403. | Rejected: no automated access. |
| `programmetv.ouest-france.fr` | robots names only selected search-engine bots; our user agent is not allowed. | Rejected: robots. |
| `sport-tv-news.com/tennis/...` | robots allows; WordPress article "where to watch tennis", not a structured fixture schedule. | Rejected: editorial article, no stable rows. |
| Official ATP/WTA broadcast information | No documented keyless fixture-to-channel API found; only rights/tournament pages. | Rejected: no keyless runtime dependency. |
| `sport-tv.fr/foot/` | robots allows; French football would be possible. | Not integrated: FootTV already provides numbered feeds; Sport-TV stays tennis-only for now. |

## 3. Known limits

- **Tennis:** The integrated sources provide tournament/session rows, not player pairings.
  A single match is therefore at most `Likely` (session) or `Possible`/`Likely`
  (tournament, depending on the specialist channel), never `Supported` from these
  sources alone. Tournament names are linked between ESPN (English) and
  FootTV/Sport-TV (French) only by token overlap; different names can miss a match.
  This is deliberately conservative.
- **Football:** Team aliases are a fixed, tested list (PSG, Lyon, Marseille,
  Manchester City/United, Wolves, QPR, and others). Exotic leagues and women's/U21
  suffixes outside the list can be missed.
- **Numbered feeds:** Only FootTV names `Ligue 1+ 1/2/3`. Live Football names
  `Ligue1+` without a number and stays `SERVICE`; it never confirms a numbered feed.
- **Parsers:** Page changes produce empty rows and keep the old snapshot; there is no
  UI warning for "source broken".
- **No Fire TV hardware run** in this environment; all numbers are desktop JVM.
