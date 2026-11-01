# docs/SPORT-DETERMINISTIC-MATCHING.md — EPG-independent channel matching (as of 2026-11-01)

> Purpose: deterministic channel identity, external broadcast evidence, tier/conflict rules and
> measurements for the Sports Hub matching path. No AI path.

## 1. Starting point

Previously there were two separate paths:

- **Classic** (`SportsMatcher`): channel name + EPG (`EpgDao.searchTitles`/`searchGuide`) +
  league hints/priors. Without usable EPG it often produced only a name hit or nothing.
- **DeepSeek match** (`AiBoardMatcher`): used the web guide (`BroadcastGuideApi`) as evidence,
  but required the AI switch and an API key.

The web guide was already being fetched (30-minute snapshot, background refresh) but was **not**
used by the deterministic matcher. This change closes that gap: the same existing, keyless source
is now evaluated without AI and without EPG.

Core is unchanged (`tv.own.owntv:core` 1.0.71, `SportsMatcher` lives in the app).
No new tables, no migration, no new library.

## 2. Pipeline

```
Fixture
  │
  ├─ A  Candidate discovery (existing)
  │     Channel name (FTS; full scan only when the FTS table is unavailable)
  │     + EPG title/description (SQL, time window)
  │
  ├─ B  External broadcast listings (deterministic)
  │     BroadcastGuideApi snapshot + BroadcastSourceHub (FootTV, Foot Mercato,
  │     Live Football, Sky Tennis, Sport-TV Tennis) → BroadcastListing
  │     → BroadcastListingMatch (fixture relevance)
  │     → ChannelIdentityResolver (playlist row ↔ listing channel)
  │     → BroadcastEvidence per hit
  │
  ├─ C  Fusion
  │     EPG/name candidates + external evidence + accepted AI rows
  │     → evidence tier (Supported/Likely/Possible/Conflicting)
  │     → score, dedup, language/country filter, cap
  │
  └─ D  Fallback (only when C is empty)
        Specialist channel → Possible (never Supported, team sports only)
```

Network access stays entirely inside the existing `BroadcastGuideApi.refresh()`. `match()` only
reads the last snapshot: **no network on the UI path**, no wait when opening a fixture. A new
snapshot bumps `version`; `SportsViewModel` then re-matches the open fixture.

## 3. Channel identity (`ChannelIdentity.kt`)

`ChannelIdentityResolver.resolve(name)` returns:

- `key` (`broadcaster|channel` or `channel:<channel>`),
- canonical broadcaster and channel,
- territory (leading tag or country suffix),
- `SportClass`, `specialist`, `ChannelType` (Linear/PPV/Event/Regional),
- `IdentityBasis` (`EXACT`/`ALIAS`) and a short reason.

Examples:

| Provider name | Key |
|---|---|
| `SKY SPORTS MAIN EVENT HD` | `sky\|main event` |
| `Sky Main Event` | `sky\|main event` |
| `NOW: SKY SPORTS MAIN EVENT` | `sky\|main event` |
| `SKY SPORT MAIN EVENT UK` | `sky\|main event` |
| `Sky Sports+` | `sky\|plus` |
| `Sky Sports 1` | `sky\|1` |
| `Sky Sports News` | `sky sports\|news` |
| `Sky News` | `sky\|news` |
| `Sky Sports Football` | `sky\|football` |
| `Ligue 1+ 2 HD` | `ligue 1 plus\|2` |
| `Ligue 1+` | `ligue 1 plus\|network` |

Rules:

- Unicode/accent/case folding and the existing `ChannelDedup` remove quality, fps, codec and
  provider tags.
- Broadcasters are recognised as whole tokens (`BT Sport` → `TNT`), including phrases
  (`Premier Sports`, `Prime Video`, `Canal Plus`).
- `+` stays a token (`Sky Sports+` ≠ `Sky Sports`).
- Service and channel are separate: `Ligue 1+ 2 HD` → `ligue 1 plus|2`, `Ligue 1+` →
  `ligue 1 plus|network`. `Ligue1+ 2` (glued provider spelling) is expanded to `ligue 1 plus`
  before the phrase match. A network name never confirms a numbered feed; `Ligue 1+ 2` matches
  `Ligue 1+ 2 HD` but not `Ligue 1+ 1 HD`.
- Quality/codec/FPS words do not change identity; `Sky Sports Ultra HDR` stays a separate feed
  next to `Sky Sports Main Event`.
- Broadcaster aliases are explicit and tested (`BT Sport` → `TNT`, `Canal+` → `canal`,
  `Ligue 1+` → `ligue 1 plus`). No substring rule for numbers.
- `sports` only separates ambiguous Sky/TNT names (`News`, numbers); for unambiguous sports
  names (`Main Event`, `F1`, `Football`) it is shorthand.
- No substring/prefix fuzzy rule: `Sky Sports 1` never matches `Sky Sports 2`.
- A network name (`Sky Sports` → `sky|network`) never matches an individual channel.
- If one side omits the broadcaster, the relation is `INFERRED` — but only for an explicit list
  of unambiguous sports names (`Main Event`, `F1`, `Football`, …). `Arena`, `Mix`, `Premier`,
  `Plus` and numbers are **not** inferred, and brandless names from different territories
  (`UK: MAIN EVENT` vs `US: MAIN EVENT`) are never merged.

Listings are resolved through the same identity. First exact keys from a small in-memory index
(only ≤ 8,000 candidate rows), then one bounded FTS query per listing, and always the identity
check. A listing therefore cannot borrow an unrelated channel. For names containing `+`, the raw
token phrase is probed as well (`Ligue 1+ 2` → `"ligue 1 2"`), because FTS treats `+` as a
separator and the canonical phrase `"ligue 1 plus"` would not reach the playlist row on large
playlists.

## 4. Broadcast listings and evidence

`BroadcastListing` (normalised): event, sport, competition, participants, event and coverage
time, broadcaster/channel, territory, language, `CoverageType`, source, retrieval time,
confidence, `direct`. Missing fields are missing evidence, not a match.

`CoverageType` separates scope from channel specificity:

- `FIXTURE`/`SESSION`/`EVENT_CARD` → event-scoped; only `FIXTURE` can be direct.
- `TOURNAMENT` → never direct (ATP/WTA day row or tournament row).
- `SERVICE` → only the service/broadcaster, not a linear feed (e.g. `DAZN`, `Ligue 1+`).
- `CHANNEL` → only a channel without event context; also never direct.
- `UNKNOWN` → insufficient.

`BroadcastEvidence` also carries `sourceFamily` and `underlyingKey`. The same broadcast
information from two feeds **of the same provenance family**, or from derived paths, is counted
once. The key contains family, event ID, channel key and `CoverageType`; start time and repeated
rows do not increase the score. A service listing and an exact feed remain different evidence.

### Tier rules (`EvidenceFusion`)

| Evidence | Tier |
|---|---|
| fresh, direct fixture listing (≤ 30 min) | `Supported` |
| direct listing, but older than 30 min | `Likely` |
| session listing (specific broadcast window) | `Likely` |
| tournament level + specialist channel | `Likely` |
| tournament level + general channel | `Possible` |
| service/rights row, specialist identity, playlist context alone | `Possible` |
| two direct sources, same channel, different events, overlapping time | `Conflicting` |

A broadcaster right or a specialist classification **never** reaches `Supported`. Direct external
evidence can reach `Supported` without EPG. The boundary is strict: 30 min = fresh,
30 min + 1 ms = aged → `Likely`; 2 h + 1 ms = stale. Stale evidence does not overwrite fresh
direct evidence; both remain in the data set and the conflict is explained
(`EvidenceFusion.explain`).

`Conflicting` stays deliberately **small**: two direct sources must name the same channel with
different event IDs in an overlapping time window. A service listing and an exact feed are not a
conflict, but different specificity. Once detected, a conflict survives merging with stronger
evidence (`strongerTier`).

Score = sum of evidence strengths × freshness, deduplicated, capped at 300. User preferences
(country, language, playlist) are applied afterwards as sorting/filtering signals and do not
manufacture match confidence. A **direct external** row (`directEvidence`) survives the preferred
language filter — a preference must not hide a channel that demonstrably carries the event. For
EPG/name rows the existing language-filter rule is unchanged (existing tests).

### Deterministic EPG/name tiers

- Both teams/athletes in the EPG title, or split across one programme → `Supported`.
- Both in the channel name (`isExact`), or only tournament/single-athlete context in EPG →
  `Likely`.
- Event hint alone (F1/golf) → `Possible`.
- These tiers give **no** extra class bonus; the historical score order (name 4 before
  description EPG 3) is preserved. Only external guide rows and accepted AI rows get the
  evidence bonus.

## 5. Sport-specific behaviour

- **Team sports** (football, NFL/college, NBA, MLB, NHL, rugby, cricket): the external row must
  name both sides (`GuideMatch.bothSidesInRow`), 90-minute window.
- **Tennis/duels**: both athletes in one row → direct. The ATP/WTA day row is `TOURNAMENT`,
  so at most `Likely` (specialist) and never `Supported`.
- **F1**: session words must match; a practice fixture never inherits the race row. Sky Sports F1
  identity stays specialist.
- **Golf**: event title tokens; tournament/round rows stay separate from individual groups (no
  group fantasy).
- **UFC/boxing**: event card, PPV stays PPV (no team-PPV demotion).
- **Other sports**: `SportClass` covers cricket, rugby, cycling, athletics, MotoGP; new sports
  only need a league mapping and optionally search terms.

## 6. External sources

Full list, accessibility/robots check, specificity and rejection reasons:
`docs/BROADCAST-SOURCES.md`.

Integrated: FootTV (French football including numbered Ligue 1+ feeds), Foot Mercato
(French-language European football, fixture rows and numbered feeds), Live Football on TV
(UK football), Sky Sports Tennis (UK tennis, tournament rows) and Sport-TV.fr (French tennis,
session rows) — in addition to the existing Where's The Match. Each source has its own parser,
family and TTL; the hub refreshes them in the existing background refresh. A source failure does
not empty its snapshot, it marks it stale. Gated/empty pages are missing channel evidence, not
"not televised".

## 7. Performance and Fire TV

- No full scan per fixture: candidates come from FTS/EPG. If the FTS query succeeds with an empty
  result, the whole playlist is not scanned as well; only an unavailable FTS table (null) falls
  back to the scan.
- External listings are resolved by FTS + identity check (cap: 32 listings, 120 channels; direct
  rows first, so the numbered feed is not pushed out of the cap by service rows).
- The FTS search first probes the selective channel phrase (`"main event"`, for `+` the raw token
  phrase `"ligue 1 2"`) and only falls back to the broad OR query when nothing matched; the
  result cap is 1,000 rows.
- The in-memory identity index is built only for ≤ 8,000 candidate rows.
- The existing 30 s scan cache (≤ 20,000 rows) is unchanged; larger playlists are deliberately
  not kept in RAM.
- No new DB schema, no new DB engine, no image processing, no background polling.
- Source networking runs only in the existing background refresh; parsers run in the
  `Dispatchers.IO` context of the hub call.

JVM smoke benchmark (`DeterministicMatchingBenchmarkTest`, Windows desktop, no Fire TV device):

| Case | Measurement |
|---|---|
| Identify 10,000 names | 88–390 ms |
| Identify 30,000 names | 186–462 ms |
| Identify 60,000 names | 257–759 ms |
| 1,000 identity lookups (60k set) | 4–20 ms |
| Fixture match, 30,000 channels, guide hit | 25–57 ms |
| 60k playlist, correct row behind 300 broad hits | 133–197 ms, found |
| 60k playlist, exact `Ligue 1+ 2` feed next to `Ligue 1+ 1` | 66–138 ms, only feed 2 |

These are JVM/CI numbers, **not** device numbers. The required P95 budgets (< 250 ms cached
match, < 10 ms identity lookup, < 500 ms first render) hold on desktop; a run on Fire TV Stick /
4K Max is still pending (no device in this environment). `minSdk` is 26, no new dependencies.

## 8. Tests

- `ChannelIdentityTest` — example names, `Sky Sports+`/number/news separation, Ligue 1+ service
  vs channel (`ligue 1 plus|2` vs `network`), generic words, territory/sport/PPV, rebrands,
  search terms.
- `BroadcastEvidenceTest` — tier rules, fresh/aged/stale boundary (30 min, 30 min + 1 ms,
  2 h + 1 ms), session/service evidence, conflict, family dedup, score cap.
- `BroadcastSourceTest` — parsers for FootTV, Foot Mercato, Live Football, Sky tennis and
  Sport-TV tennis (fixture/service/tournament/session rows, missing channels, missing times,
  quality/accent variants), plus fixture relevance (both teams in either order, wrong
  tournament/wrong day).
- `BroadcastSourceHubTest` — a failed refresh keeps rows and marks them stale; an empty successful
  page does not delete the old snapshot.
- `BroadcastGuideTest` — existing WTM parser/match rules plus explicit club aliases
  (Manchester City/United, PSG/Paris FC, Lyon/Marseille).
- `SportsMatcherTest` — guide without EPG → `Supported`; numbered Ligue 1+ feed matches only its
  number; network row stays `Possible`; tennis tournament row → `Likely` (specialist) /
  `Possible` (general); EPG+guide merge; no borrowing; country preference does not hide strong
  evidence; fallback only `Possible`.
- `DeterministicMatchingBenchmarkTest` — 10k/30k/60k identities, 30k match, 60k recall behind
  300 hits, 60k exact numbered feed.
- `SportMatchingQualityTest` — 25 labelled scenarios (18 with an expectation, 7 deliberately
  without), including UK/FR football, Ligue 1+ numbers, service-only, tennis session/tournament,
  wrong number and language filter. Measured: **Precision@1 18/18**, **Precision@5 18/18**,
  **known-carrier recall 18/18**, **no-result 7/7**, **false positives 0**,
  **exact-channel 12/18**, **service-only 3/18**. The 18 expectation cases deliberately include
  `Likely`/`Possible` rows; "exact channel" counts only `Supported`.

Run:

```
./gradlew :app:testStandardDebugUnitTest
```

## 9. Limits and follow-up

- **Four new sources, but different coverage.** UK/FR football is fixture-accurate; tennis stays
  tournament/session-accurate. A single tennis match is never `Supported` from these sources.
- **Tennis tournament names** are linked by token overlap (ESPN English vs FootTV/Sport-TV
  French). Different names can miss a match; deliberately conservative rather than a false
  confirmation.
- **Team aliases** are a fixed, tested list. Exotic leagues, women's/U21 suffixes outside the
  list, or new club names can be missed.
- **Playlist clustering** (neighbourhood, group, logo, sequence) is deliberately not implemented:
  weak signals, no proven precision gain, partly expensive image processing. The identity-key
  idea is the prerequisite for it.
- **Historical feedback** is not wired. "Stream opened" is not proof; only an explicit user
  confirmation should create evidence. The data store for that belongs in the existing
  `SportsPrefs` DataStore file (backup-compatible).
- **Fire TV hardware measurement** and a real precision/recall run against multiple real
  providers are still pending. The benchmark is deterministic but synthetic.
- **Source failure is silent**: the old snapshot stays stale, the UI shows no "source broken"
  warning. `BroadcastSourceHub.status()` exists for diagnostics.
- **No UI redesign.** The existing evidence line still shows tier + source; `evidenceDetail` now
  also contains the provenance family (e.g. `foottv · Ligue 1 · Lyon - Angers`). D-pad
  navigation and layout are unchanged.
