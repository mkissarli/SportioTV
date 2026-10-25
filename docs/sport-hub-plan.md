# Sport-Hub – Ausbauplan (Sportio)

Stand: 2026-10-12 · Sprache mit dem User: Deutsch · Autor: Agent
Status: **Phase 1 umgesetzt (2026-10-12); Phasen 2–7 geplant.**

## Umsetzungsstand

- **Phase 1 – erledigt (2026-10-12)**
  - `SportLeague` + `labelRes`/`region`/`kind`/`defaultActive`; `Fixture` + `format`/`entries`,
    `home`/`away` nullable.
  - Katalog **81 Ligen** in `SportsLeagues.kt`, **16 default aktiv**; Labels als
    proper nouns in `res/values/donottranslate.xml` (nicht übersetzt, Gate-konform).
  - `SportsPrefs.followedLeagues` Default = `DefaultActiveLeagueIds`; neu
    `setActiveLeagues(ids)`.
  - `SportsViewModel.activeLeagues`; lädt **nur aktive** Ligen (reaktiv, purgt
    deaktivierte Boards); `myTeamFixtures`/`SportsMatcher` null-sicher.
  - Rail zeigt nur aktive Ligen; der „Sport → Ligen"-Dialog ist jetzt der gruppierte
    Katalog-Picker (Phase 2).
  - Verifiziert: Emulator, Hub öffnet → **genau 16** `SportsHub: board ok`.
- **Phase 2 – erledigt (2026-10-12)**
  - „Add leagues"-Zeile am Ende der Rail + gruppierter `SportPickerDialog`
    (Region-Gruppen, Haken, Titel/Untertitel). Zahnrad-Button öffnet denselben Picker.
  - Toggle lädt/entfernt live; Emulator-verifiziert: „2. Bundesliga" an → nur
    `SportsHub: board ok soccer.ger.2`, aus → Zelle verschwindet.
- **Phase 3 – erledigt (2026-10-12)**
  - Drei Tabs in Spalte 2: `Live / Demnächst / Vergangen` (lokalisiert) mit
    `FixtureTab`-State im VM; Default Live-wenn-vorhanden, sonst Upcoming, manuelle
    Wahl bleibt bis zum Ligawechsel.
  - Filter/Empty-States/Tages-Untertitel; `buildRows`/`SportsRow`/`isToday` entfernt,
    alte `sport_group_*` gelöscht.
  - Emulator-verifiziert: Upcoming leer, Live „No live games", Past zeigt Ergebnisse;
    D-Pad: Settings → ▼ = Live-Tab → ▶▶ = Past → OK.
- **Phase 4 – erledigt (2026-10-12)**
  - Rail-Eintrag **Favourites** (Star) über dem „Ligen"-Header; Aggregat der aktiven
    Ligen + gefolgter Teams (live-first), Team-Chips nur hier.
  - Lade-Set inkl. Ligen gefolgter Teams → ein Team aus einer deaktivierten Liga
    bleibt in Favourites.
  - Emulator-verifiziert: Favourites mischt Ligen; SCF gefolgt → Chip + Spiele;
    Bundesliga deaktiviert → Rail ohne BUL, SCF bleibt.
- **Phase 5 – erledigt (2026-10-12)**
  - `EspnApi` Einzelsport-Zweig: entries + `format` (LEADERBOARD für golf/racing, sonst
    VERSUS); Tennis über `groupings[].competitions[]`; Cricket normaler Team-Pfad.
  - Emulator-verifiziert: PGA Leaderboard (Brennan -16 …), F1 22 Einträge, UFC/ATP/WTA
    Duelle. Kanal-Matching für EVENT bleibt bewusst leer.
- **Phase 6 (Stufe 1, Ligen) – erledigt (2026-10-12)**
  - Core: generischer `CompanionMode.SELECT_ITEMS` (Pick-List-Seite + `POST /select`);
    Core-Strings + Core-Gate grün.
  - App: „Manage on phone" im Picker → QR/PIN-Panel; Auswahl schreibt `setActiveLeagues`.
  - Emulator-verifiziert end-to-end via `adb forward` + curl (81 Checkboxen → Rail
    wechselt live). **Stufe 2 (Teams) offen.**
- **Phase 6 Stufe 2 – erledigt (2026-10-12)**: Handy-Seite zusätzlich mit **Teams**
  (`EspnApi.teams` → `…/teams`) und **Spielern/Kämpfern** (aus den geladenen Duellen),
  gruppiert nach Liga. Ein Submit gleicht Ligen + gefolgte Teams/Spieler ab
  (`team:`/`player:`-Präfixe; `setFollowedTeams/-Players`). Emulator-verifiziert.
- **Nachtrag (2026-10-12): Tennis-Matches + Follow-Regeln**
  - Tennis-Turniere werden in **je ein Fixture pro Match** zerlegt
    (`groupings[].competitions[]`): alle Spiele sichtbar (ATP **135**, WTA **243**).
  - `FixtureEntry` + `id`/`winner`; **Entry-Follow nur bei Duellen** (Tennis/MMA),
    **kein** Follow bei Leaderboards (F1/Golf/Racing). Neues Pref-Set
    `followed_players` (`leagueId|playerId`), in Favourites und „My teams" eingebunden.
  - Emulator-verifiziert: ATP 2 Follow-Buttons, UFC 2, Formula 1 0.
  - **Darstellung**: Duelle (`VERSUS`) zeigen **beide** Athleten gleich groß (Karte **und**
    Detail), **_gleiche Farbe_** — kein Grün/Weiß-Mix; Sieger nur fett + Häkchen.
    Leaderboards behalten „Führender groß + Verfolger klein" bzw. die Rangliste. (Vorher
    wurde beim Duell der erste Spieler als Titel groß, der zweite klein gezeigt.)
  - **Icons**: Tennis-Athleten haben **keine** Headshots, aber eine **Länderflagge**
    (`athlete.flag`) — die dient jetzt als Icon (Fallback hinter dem Headshot), damit die
    Spieler erkennbar sind.
  - **F1/Racing** (Nachtrag): eigenes `FixtureFormat.EVENT` → Karte **und** Detail zeigen nur
    Rennen (`event.shortName`) + **Circuit-Standort** (`event.circuit.fullName`), **keine
    Fahrer**. Kanal-Matching läuft normal **plus** ein Liga-Hint
    (`SportLeague.extraChannelRes`, F1 → „Sky Sports Main Event", `donottranslate`) — der
    Hint wird als **ganze Phrase** gesucht, damit der eigene Sky-Kanal erscheint.
    Emulator-verifiziert: „Singapore Airlines Singapore GP" + „Marina Bay Street Circuit,
    Singapore" + Kanal „4K: SKY SPORTS MAIN EVENTS …".
    (Veraltet 2026-10-25: der F1-Hint ist entfernt — fest kodierte Kanalnamen gab falsche
   Treffer. Der Where's-The-Match-Guide deckt F1 dynamisch ab, siehe `SPORT-HUB.md`.)
- **Abweichung vom §9-Entwurf**: Liga-Labels nicht als Kotlin-Literale klassifiziert,
  sondern als nicht-übersetzbare String-Ressourcen (`donottranslate.xml`) abgelegt —
  sauberer für das i18n-Gate und die Projektregel „UI-Text in Resources".
- **Alle Phasen 1–7 erledigt (2026-10-12).** Keystore/Signing vorhanden
  (`keystore/sportio-release.jks`, Alias `sportio`); signierter Release gebaut. In-App-Updates
  aktiv: `releaseRepo = trenntl/SportioTV`, `updateCheckOnStart` an, Release `v1.0.0`
  veröffentlicht. Siehe §11.
- **Nachtrag (2026-10-13): Kanal-Matching für Tennis/MMA-Duelle.** `Fixture.tournament`
  (Event-Name) + Doppel-Roster-Split (`roster.displayName`) + Tennis-Hint (`extraChannelRes`
  für ATP/WTA) + Duell-Tiers im Matcher (beide Athleten wie beide Teams, darunter ein Athlet mit
  Tennis-Kontext +2, Turnier allein +1) + Exact-Bypass im Sprachfilter (`ChannelMatch.isExact`).
  Details und Tests (16+9) in `sport-channel-matching.md`; Einstieg in `SPORT-HUB.md`.


Kontext: Dieses Dokument plant den Ausbau des Sport-Hubs (`features/sport/`).
Es ergänzt `HANDOFF.md` (dort steht die Kurzfassung + Pointer). Alle Ligen-Slugs
in Kapitel 3 wurden am 2026-10-12 gegen die ESPN-API verifiziert (Methode in
§3.5); der `/teams`-Endpoint für die Handy-Team-Auswahl (§7.6) ebenfalls.

---

## 0. Ziel des Ausbaus

1. **Mehr Ligen**, aber nicht alles gleichzeitig laden: voller Katalog als
   Metadaten, nur aktivierte Ligen werden gefetcht.
2. **Tabs in Spalte 2** je Liga: `Live | Demnächst | Vergangen`
   (lokalisiert; engl. `Live | Upcoming | Past`).
3. **Favourites-Spalte** ganz oben links, die alles vom User Ausgewählte bündelt
   (aktivierte Ligen + gefolgte Teams).
4. **Einzelsport** (Golf, Tennis, F1, MMA, Cricket) soll mitgeplant und später
   korrekt gerendert werden (kein home/away → eigener Renderer).
5. **Verwaltung auch per Handy**: Ligen (und später Teams) bequem am Telefon
   auswählen — über eine Companion-Webseite im selben WLAN, erreichbar per
   QR-Code auf dem TV (bestehende Companion-Infrastruktur des Cores).

---

## 1. Ausgangslage (Ist-Zustand)

### 1.1 Relevante Dateien

| Pfad | Rolle |
|---|---|
| `app/src/main/java/tv/own/owntv/features/sport/SportsLeagues.kt` | statische Liga-Liste (16) + `sportsLeagueById` |
| `.../sport/SportsModels.kt` | `SportLeague`, `SportTeam`, `Fixture`, `StandingRow`, `LeagueTable`, `ChannelMatch` |
| `.../sport/EspnApi.kt` | Scoreboard-/Standings-Parser (OkHttp + org.json) |
| `.../sport/SportsMatcher.kt` | Fixture→Kanal-Matching (EPG/Sender/Team) |
| `.../sport/SportsPrefs.kt` | DataStore `sports`: `followed_leagues`, `followed_teams`, `hub_visible` |
| `.../sport/SportsViewModel.kt` | Boards-Cache, Auswahl, Standings, `playMatch` |
| `.../sport/SportsScreen.kt` | Stage-UI: Ligen-Rail, Fixtures, Detail-Pane |
| `.../shell/OwnTVShell.kt` | SPORT-Branch (Z. ~1318), `onPlayChannel`, StageSections |
| `app/src/main/res/values/strings.xml` + `values-de` | `sport_*`-Strings |
| `app/src/main/res/values/donottranslate.xml` | nur Branding; hier ggf. fixe Strings |
| **Core** `OwnTV_Core-main/core/src/main/java/tv/own/owntv/core/companion/CompanionMode.kt` | Modus-Enum der Companion-Seiten |
| **Core** `.../core/companion/CompanionHttpServer.kt` | HTTP-Router + Seite je Modus |
| **Core** `.../core/companion/CompanionController.kt` | Lifecycle/State/Flows + `startFor…` |
| **Core** `.../core/companion/CompanionHtml.kt` | HTML der Companion-Seiten |
| **Core** `.../core/companion/CompanionLink.kt` | LAN-URLs + QR-Rendering |
| App `.../features/setup/RemoteSetupScreen.kt` | Beispiel-Flow: PIN-Gate + QR (Vorbild) |
| App `.../ui/components/BackgroundImageChooserDialog.kt` | `StageCompanionStatus` (QR-Anzeige, wiederverwendbar) |

### 1.2 Aktuelles Verhalten

- `SportsLeagues` = 16 Einträge, nur `id`/`espnSport`/`espnLeague`.
- `SportsViewModel.refreshAll()` lädt beim Öffnen **alle** Ligen parallel
  (16 OkHttp-Requests), TTL 15 min, `boards` mapCache.
- Rail zeigt **immer alle** `SportsLeagues`, gefolgte zuerst sortiert
  (`followedLeagues` Default = **alle** IDs).
- Spalte 2 ist **eine** LazyColumn mit Kopfzeilen-Gruppen
  `Live / Heute / Demnächst / Ergebnisse`.
- Es gibt bereits eine „My teams"-LazyRow (gefolgte Teams) in Spalte 2.
- Detail-Pane: Vorschau (`GuideVideo`), Details, Kanäle, Tabelle.
- `SportsSettingsDialog` („Sport → Ligen") togglet `followedLeagues`.
- `EspnApi.parseEvent` verwirft Events **ohne** home/away-Competitor
  (`noteams`-Log) → Tennis/F1/Golf/MMA/Cricket rendern leer.
- Kanal-Start läuft über `liveVm.ensurePlayingByIdAsync` → `openFullscreen`.

### 1.3 Bereits behoben (Fix 2026-10-12)

`SportsScreen` hatte `DisposableEffect`-Stops (`liveVm.stopPreview()`), die beim
Fullscreen-Gehen (Shell blendet Browse-UI aus, `OwnTVShell.kt:1025`) den gerade
gestarteten Sender killten. Ersetzt durch `LaunchedEffect(selectedFixture?.id)`
mit Null-Sentinel. Siehe `HANDOFF.md` → „Fixes 12.10.". **Beim Umbau beachten:
keine `stopPreview()`-Aufrufe in Disposal-Handlern von Screens, die fullscreen
gehen können** (Vorbild-Guard bleibt in `SportsScreen`).

---

## 2. Entscheidungen (mit User abgestimmt, 2026-10-12)

| Thema | Entscheidung |
|---|---|
| Liga-Umfang | **Kompletter Katalog** aller verfügbaren Ligen als Metadaten; nur aktivierte laden |
| Default aktiv | **die bisherigen 16** (kein Regress) |
| Nicht alles laden | Ja: nur aktivierte Ligen + gewählte Liga werden gefetcht |
| Favourites | **Ligen opt-in + Teams**: Favourites = aktivierte Ligen + gefolgte Teams aggregiert |
| Tab-Labels | **lokalisiert** (de: `Live/Demnächst/Vergangen`, en: `Live/Upcoming/Past`) |
| Einzelsport | **jetzt mit einplanen** (eigener Renderer) |
| Picker-Einstieg | eigene Zeile **„Add leagues"** unten in der Rail + Dialog |
| Handy-Verwaltung | zusätzlich Companion-Webseite per **QR-Code** (Ligen + später Teams), siehe §7.6 |

---

## 3. Liga-Katalog

### 3.1 Modell

`SportLeague` (umgesetzt in Phase 1):

```kotlin
enum class LeagueRegion { GERMANY, EUROPE, INTERNATIONAL, AMERICAS, REST }
enum class LeagueKind { TEAMS, EVENT }   // EVENT = kein home/away

data class SportLeague(
    val id: String,            // "soccer.ger.1" – Follow-Key + Fixture-Präfix
    val espnSport: String,     // "soccer"
    val espnLeague: String,    // "ger.1"
    @StringRes val labelRes: Int,  // proper noun in donottranslate.xml, z.B. R.string.sport_league_soccer_ger_1
    val region: LeagueRegion,
    val kind: LeagueKind,
    val defaultActive: Boolean = false,
)
```

- `labelRes` (statt eines String-Literals) hält die Liga-Namen aus dem i18n-Gate
  heraus und liefert einen schönen Rail-/Picker-Namen **vor** dem Laden.
- `kind = EVENT` steuert den Renderer (§8) und blendet Team-Matching aus.

### 3.2 Regeln

- **Aktiv** = in `followed_leagues` gespeichert; Default = alle mit
  `defaultActive = true` (die bisherigen 16).
- In der Rail erscheinen **nur aktive** Ligen (+ Favourites-Pseudo-Eintrag +
  „Add leagues"-Zeile).
- Gefetcht werden nur aktive Ligen; die gerade ausgewählte wird immer geladen.
- Deaktivieren einer aktiven Liga entfernt sie aus Rail, Boards, Favourites und
  Zählern; ihr Board wird aus dem Cache verworfen.

### 3.3 Katalog – bisher aktiv (`defaultActive = true`)

| id | Label | Region | Kind |
|---|---|---|---|
| `soccer.ger.1` | Bundesliga | GERMANY | TEAMS |
| `soccer.eng.1` | Premier League | EUROPE | TEAMS |
| `soccer.esp.1` | LaLiga | EUROPE | TEAMS |
| `soccer.ita.1` | Serie A | EUROPE | TEAMS |
| `soccer.fra.1` | Ligue 1 | EUROPE | TEAMS |
| `soccer.uefa.champions` | UEFA Champions League | EUROPE | TEAMS |
| `soccer.uefa.europa` | UEFA Europa League | EUROPE | TEAMS |
| `football.nfl` | NFL | AMERICAS | TEAMS |
| `basketball.nba` | NBA | AMERICAS | TEAMS |
| `baseball.mlb` | MLB | AMERICAS | TEAMS |
| `hockey.nhl` | NHL | AMERICAS | TEAMS |
| `tennis.atp` | ATP | INTERNATIONAL | EVENT |
| `tennis.wta` | WTA | INTERNATIONAL | EVENT |
| `racing.f1` | Formula 1 | INTERNATIONAL | EVENT |
| `mma.ufc` | UFC | INTERNATIONAL | EVENT |
| `golf.pga` | PGA Tour | AMERICAS | EVENT |

### 3.4 Katalog – neu (alle verifiziert, `defaultActive = false`)

**Soccer – nationale Top-Ligen (Europa)**

| id | Label | Region | Standings |
|---|---|---|---|
| `soccer.ger.2` | 2. Bundesliga | GERMANY | ✅ 18 |
| `soccer.eng.2` | EFL Championship | EUROPE | n. gepr. |
| `soccer.esp.2` | LaLiga 2 | EUROPE | n. gepr. |
| `soccer.ita.2` | Serie B | EUROPE | n. gepr. |
| `soccer.fra.2` | Ligue 2 | EUROPE | n. gepr. |
| `soccer.ned.1` | Eredivisie | EUROPE | ✅ 18 |
| `soccer.por.1` | Primeira Liga | EUROPE | ✅ 18 |
| `soccer.tur.1` | Süper Lig | EUROPE | ✅ 18 |
| `soccer.sco.1` | Scottish Premiership | EUROPE | n. gepr. |
| `soccer.bel.1` | Belgian Pro League | EUROPE | n. gepr. |
| `soccer.aut.1` | Austrian Bundesliga | EUROPE | n. gepr. |
| `soccer.sui.1` | Swiss Super League | EUROPE | n. gepr. |
| `soccer.gre.1` | Greek Super League | EUROPE | n. gepr. |
| `soccer.rus.1` | Russian Premier League | EUROPE | n. gepr. |
| `soccer.den.1` | Danish Superliga | EUROPE | n. gepr. |
| `soccer.nor.1` | Eliteserien | EUROPE | n. gepr. |
| `soccer.swe.1` | Allsvenskan | EUROPE | n. gepr. |

**Soccer – nationale Top-Ligen (Amerika/Rest)**

| id | Label | Region | Standings |
|---|---|---|---|
| `soccer.mex.1` | Liga MX | AMERICAS | ✅ 18 |
| `soccer.usa.1` | MLS | AMERICAS | ✅ 30 (2 Gruppen) |
| `soccer.arg.1` | Liga Profesional | AMERICAS | n. gepr. |
| `soccer.bra.1` | Brasileirão Série A | AMERICAS | n. gepr. |
| `soccer.jpn.1` | J.League | REST | n. gepr. |
| `soccer.chn.1` | Chinese Super League | REST | n. gepr. |
| `soccer.aus.1` | A-League Men | REST | n. gepr. |
| `soccer.ind.1` | Indian Super League | REST | n. gepr. |
| `soccer.rsa.1` | South African Premiership | REST | n. gepr. |

**Soccer – Pokale & International**

| id | Label | Region | Hinweis |
|---|---|---|---|
| `soccer.uefa.europa.conf` | UEFA Conference League | EUROPE | Standings ✅ 36 |
| `soccer.uefa.champions_qual` | UEFA CL Qualifying | EUROPE | nur Quali-Phase |
| `soccer.uefa.super_cup` | UEFA Super Cup | EUROPE | nur 1 Spiel/Jahr |
| `soccer.uefa.euro` | UEFA Euro | EUROPE | nur im Turnier (Juni/Juli) |
| `soccer.uefa.nations` | UEFA Nations League | EUROPE | blockweise |
| `soccer.eng.fa` | FA Cup | EUROPE | Runden |
| `soccer.eng.league_cup` | EFL Cup | EUROPE | Runden |
| `soccer.ger.dfb_pokal` | DFB-Pokal | GERMANY | keine Standings |
| `soccer.esp.copa_del_rey` | Copa del Rey | EUROPE | Runden |
| `soccer.ita.coppa_italia` | Coppa Italia | EUROPE | Runden |
| `soccer.esp.super_cup` | Supercopa | EUROPE | 1 Wochenende |
| `soccer.ita.super_cup` | Supercoppa | EUROPE | 1 Spiel |
| `soccer.fifa.world` | FIFA World Cup | INTERNATIONAL | nur im Turnier |
| `soccer.fifa.wwc` | FIFA Women's World Cup | INTERNATIONAL | nur im Turnier |
| `soccer.afc.champions` | AFC Champions League Elite | REST | |
| `soccer.caf.champions` | CAF Champions League | REST | |
| `soccer.afc.asian.cup` | AFC Asian Cup | REST | nur im Turnier |
| `soccer.caf.nations` | Africa Cup of Nations | REST | nur im Turnier |
| `soccer.concacaf.champions` | Concacaf Champions Cup | AMERICAS | |

**Soccer – Frauen**

| id | Label | Region |
|---|---|---|
| `soccer.eng.w.1` | Women's Super League | EUROPE |
| `soccer.esp.w.1` | Liga F | EUROPE |
| `soccer.fra.w.1` | Première Ligue | EUROPE |
| `soccer.usa.nwsl` | NWSL | AMERICAS |
| `soccer.uefa.wchampions` | Women's Champions League | EUROPE |

**Andere Teamsportarten**

| id | Label | Region | Standings |
|---|---|---|---|
| `basketball.wnba` | WNBA | AMERICAS | n. gepr. |
| `basketball.euroleague` | EuroLeague | EUROPE | keine (0) |
| `basketball.mens-college-basketball` | NCAA Men's Basketball | AMERICAS | n. gepr. |
| `basketball.womens-college-basketball` | NCAA Women's Basketball | AMERICAS | n. gepr. |
| `football.college-football` | NCAA Football | AMERICAS | ✅ 124 (11 Gruppen) |
| `baseball.college-baseball` | NCAA Baseball | AMERICAS | n. gepr. |
| `australian-football.afl` | AFL | REST | keine (0) |
| `rugby.270557` | United Rugby Championship | EUROPE | ✅ 16 |
| `volleyball.womens-college-volleyball` | NCAA Women's Volleyball | AMERICAS | n. gepr. |

**Einzelsport (EVENT)**

| id | Label | Region | Bemerkung |
|---|---|---|---|
| `golf.lpga` | LPGA | AMERICAS | Leaderboard |
| `golf.liv` | LIV Golf | INTERNATIONAL | Leaderboard |
| `golf.eur` | DP World Tour | EUROPE | Leaderboard |
| `mma.pfl` | PFL | INTERNATIONAL | 2 Kämpfer |
| `racing.nhra` | NHRA | AMERICAS | Leaderboard |
| `cricket.8039` | Cricket World Cup | INTERNATIONAL | Team-Cricket, Shape prüfen |

> Gesamt: 16 aktiv + 66 neu = **82 Katalog-Einträge**.
> `cricket/1` und `cricket/8039` liefern identisch den World Cup → nur `8039` führen.

### 3.5 Nicht verfügbar (getestet, HTTP-Fehler/leer)

`soccer/ger.w.1`, `soccer/kor.1`, `soccer/egy.1`, `soccer/fra.coupe`,
`soccer/concacaf.leagues`, `racing/motogp`, `basketball/esp.1`,
`basketball/ger.1`, `baseball/jpn.1`, `hockey/rus.1`, `racing/indycar`,
`racing/nascar`, `golf/dpwt` (→ stattdessen `golf/eur`), `boxing/boxing`,
`tennis/itf`, `hockey/olympics`, `basketball/olympics`.

### 3.6 Verifikationsmethode (wiederverwendbar)

Scoreboard: `GET https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard`
Standings: `GET https://site.web.api.espn.com/apis/v2/sports/{sport}/{league}/standings`
(Standings **nur** auf dem `site.web`-Host; der `site`-Host liefert `{}`.)

Getestet mit `curl.exe` vom PC (User-Agent Mozilla). Erfolg = HTTP 200 +
`leagues[0].name` gesetzt. Standings-Erfolg = `children[].standings.entries[]`.
Die Emulator-/Box-Netzwerkprüfung: Emulator hat Internet (DNS + TCP 80/443 grün,
Default-Route via `10.0.2.2`).

---

## 4. Datenmodell-Änderungen (`SportsModels.kt`)

### 4.1 `SportLeague`
Siehe §3.1: + `label`, `region`, `kind`, `defaultActive`.

### 4.2 `Fixture`
```kotlin
enum class FixtureFormat { VERSUS, LEADERBOARD }

data class FixtureEntry(
    val id: String? = null,        // ESPN competitor id — Follow-Key (Tennis/MMA)
    val name: String,
    val order: Int?,
    val detail: String?,   // Score/Zeit/„-3"
    val logoUrl: String?,
    val winner: Boolean = false,
)

data class Fixture(
    val id: String,
    val leagueId: String,
    val dateMs: Long,
    val state: FixtureState,
    val statusLine: String,
    val format: FixtureFormat = FixtureFormat.VERSUS,
    // Teamsport: gesetzt; Einzelsport: null
    val home: SportTeam?,
    val away: SportTeam?,
    // Einzelsport: sortierte Einträge (Golf/Racing Leaderboard, MMA/Tennis Spieler)
    val entries: List<FixtureEntry> = emptyList(),
    val broadcasts: List<String>,
    val venue: String?,
)
```

- **Nullable `home`/`away` ist der riskanteste Teil**: alle Aufrufer prüfen.
  Betroffen: `EspnApi.parseEvent`, `SportsMatcher.match` (Team-Queries),
  `SportsViewModel.myTeamFixtures`, `SportsScreen` (`FixtureCard`,
  `FixtureDetail`, `FixtureChip`, `TeamScoreRow`, Team-Follow-Buttons).
- Bestehende Team-Pfade können mit `fixture.home != null && fixture.away != null`
  guarden; Team-Follow nur für `format == VERSUS`.

---

## 5. Prefs (`SportsPrefs.kt`)

- `followedLeagues` → semantisch „**aktive Ligen**".
  **Default ändern** von „alle IDs" auf „IDs mit `defaultActive`" (die 16).
- Key `followed_leagues` **beibehalten** (Backward-kompatibel; wer die Auswahl
  schon getoggelt hat, behält sie).
- `followedTeams` unverändert.
- **`followedPlayers`** (Nachtrag): Set gefolgter Einzelathleten (Tennis/MMA),
  Key `playerKey(leagueId, playerId)` = `leagueId|playerId`. Gespeichert unter
  `followed_players`; F1/Golf/Racing nutzen es nie.
- Optional neuer Helfer `activeLeagues(default: Set<String>)`.
- Hinweis: `SportsSettingsDialog` nutzt aktuell `followedLeagues`; wird durch den
  Katalog-Picker ersetzt (§7.2).

---

## 6. ViewModel (`SportsViewModel.kt`)

- `activeLeagues: StateFlow<Set<String>>` (aus Prefs, gefiltert auf Katalog-IDs).
- `refresh()` lädt **nur aktive** Ligen parallel (`supervisorScope`), plus die
  aktuell gewählte; TTL-Logik (`BoardTtlMs`, `boardAt`) unverändert.
  - **Phase 4:** geladen wird `activeLeagues ∪ followedTeamLeagues` (Ligen, in denen
    ein gefolgtes Team spielt), damit Favourites auch Teams aus deaktivierten Ligen
    zeigt; die Rail bleibt auf `activeLeagues`.
- `deactivateLeague(id)`: Board + Standings aus Cache entfernen.
- Neuer Tab-State:
  ```kotlin
  enum class FixtureTab { LIVE, UPCOMING, PAST }
  private val _tab = MutableStateFlow(FixtureTab.UPCOMING)
  val tab: StateFlow<FixtureTab>
  ```
  Bei Quellenwechsel (`selectLeague` / Favourites): Auto-Select
  `LIVE` wenn `liveCount > 0`, sonst `UPCOMING`.
- `Favourites`-Aggregat: `combine(boards, activeLeagues, followedTeams)` →
  Fixtures aller aktiven Boards, die (a) zur aktivierten Liga gehören **oder**
  (b) ein gefolgtes Team enthalten; live-first, dann Datum.
  Pseudo-Sentinel statt leagueId, z. B. `FavouritesId = "__favourites__"`.
- `leagues`-Rail-Liste: nur aktive Ligen, gefolgte/aktive egal (alle aktiv sind
  gleichwertig) → Katalog-Reihenfolge `SportsLeagues` (sie ist kuratiert).
- Standings weiter lazy bei `selectLeague`; für Favourites nur auf Klick eines
  Fixtures (Liga des Fixtures).

---

## 7. UI (`SportsScreen.kt`)

Layout bleibt 3-spaltig (Rail · Fixtures · Detail).

### 7.1 Spalte 1 (Rail)
Reihenfolge:
1. `Sport`-Titel, Label „Ligen".
2. **Favourites**-Eintrag (Star-Icon `OwnTVIcon.STAR`), Sentinel-Auswahl.
3. Header „Ligen".
4. Aktive Ligen (`LazyColumn`).
5. **„Add leagues"**-Zeile ganz unten (Icon `OwnTVIcon.ADD`) → öffnet Picker (§7.2).
   ✅ umgesetzt (Phase 2); Favourites-Eintrag ✅ (Phase 4).

- D-Pad: Favourites/Ligen/„Add leagues" in einer Focus-Gruppe; Long-Press auf
  einer Liga = deaktivieren (mit Bestätigung?) — oder Deaktivieren nur im Picker.

### 7.2 Picker-Dialog (`Add leagues`) ✅ umgesetzt (Phase 2)
- Ersetzt/ergänzt `SportsSettingsDialog`.
- Gruppiert nach `LeagueRegion` (Germany, Europe, International, Americas, Rest),
  `StageMenuHeader` je Region, `StageMenuItem` mit `checked = league.id in active`.
- Toggle schreibt in `followedLeagues` (`setLeagueFollowed`).
- Bei ≥1 aktiv bleibt; 0 aktive Ligen erlaubt (leere Rail + Favourites).
- Region-Header als Überschrift; optional Zähler „3/17".
- Fokus-Trap wie bestehender Dialog (`trapAllFocusExit` + `BackHandler`).

### 7.3 Spalte 2: Tabs ✅ umgesetzt (Phase 3)
- Oben eine Tab-Row (`StageSurface`-Pills im gleichen Stil wie die
  Settings/Retry-Buttons): `Live`, `Demnächst`, `Vergangen`.
- Darunter die LazyColumn, gefiltert nach `tab`:
  - LIVE → `state == LIVE`
  - UPCOMING → `state == UPCOMING` (inkl. heute), Tages-Untertitel
    (`sport_today_word`/`sport_tomorrow_word`/Datum) bleiben
  - PAST → `state == FINISHED`, neueste zuerst (wie heute `take(30)`)
- Empty-States je Tab (`sport_no_live_games` etc.).
- Default-Select wie §6.
- Der bisherige `buildRows`-Header-Ansatz entfällt/vereinfacht sich.

### 7.4 Favourites-Ansicht ✅ umgesetzt (Phase 4)
- Auswahl `FavouritesId` → Spalte 2 zeigt Aggregat (gleiche Tab-Row,
  Header „Favourites").
- Zusätzlich Team-Kürzel-Chips (wie bisher „My teams") als Kopfzeile, damit man
  die gefolgten Teams sieht.
- Die bisherige „My teams"-LazyRow über allen Ligien **entfällt** (geht auf).
- Detail-Pane unverändert; auf ein Fixture tippen zeigt dessen Details.

### 7.5 Detail-Pane / Non-Team
- `format == VERSUS` ohne Teams (MMA/Tennis): statt `TeamScoreRow` zwei
  `FixtureEntry`-Zeilen.
- `format == LEADERBOARD` (Golf/Racing/NHRA): Rangliste (`rank · Name · Wert`),
  `entries.take(10)`.
- Kanäle/Standings: bei F1/Golf ohne Gegnernamen bleibt Channel-Matching meist
  leer → Hinweiszeile (bereits `sport_no_channel`). Tennis/MMA-Duelle matchen seit 2026-10-13
  (Spieler + Turnier, siehe Nachtrag im Umsetzungsstand).

### 7.6 Companion-Phone-UI (QR) für Ligen-/Team-Verwaltung

**Ziel:** Am Handy im selben WLAN eine Webseite öffnen (QR-Code am TV), dort
alle Ligen (später auch Teams) bequem per Checkbox + Suche auswählen; die
Auswahl kommt zurück auf den TV und schreibt in `SportsPrefs`.

**Bestehende Infrastruktur (Core, bereits vorhanden und wiederverwendbar):**

| Baustein | Rolle |
|---|---|
| `CompanionHttpServer` | eigener HTTP/1.1-Listener, PIN-Gate, Modus-abhängige Seite |
| `CompanionController` | Kotlin-`single`; `startFor…(port, …)` + Flows (`backups`, `tmdbKeys`, …) |
| `CompanionMode` | `ADD_SOURCE, BACKUP_RESTORE/DOWNLOAD, IMAGE_UPLOAD, TMDB_KEY/CONFIG, OPEN_SUBTITLES_CONFIG, LOCAL_SYNC` |
| `CompanionHtml` | HTML je Modus (mobil, OwnTV-Theme, eingebettete Lora-Schrift) |
| `CompanionLink` | `lanUrl(port)` / `lanUrls` + `renderQr(url)` |
| App `RemoteSetupScreen` + `StageCompanionStatus` | Vorbild: PIN-Gate, QR anzeigen |

Ablauf eines Browser-Besuchs (unverändert nutzbar): `GET /` → PIN-Gate;
`POST /` mit PIN → Modus-Seite; danach POSTs mit `?pin=`/`X-Companion-Pin`.
6-stellige PIN pro Start, Lockout nach N Fehlversuchen, QR enthält **nur** die
URL (PIN wird am Telefon getippt).

**Entwurf — generischer Core-Modus (bewusst Sport-agnostisch):**

- Neuer `CompanionMode.SELECT_ITEMS` (Name generisch, nicht „SPORT").
- Neues Datenobjekt:
  ```kotlin
  data class CompanionSelectItem(
      val id: String,       // z.B. "soccer.ger.2"
      val label: String,    // z.B. "2. Bundesliga"
      val group: String,    // z.B. "Germany"
      val selected: Boolean,
  )
  ```
- `CompanionHtml.selectItemsPage(context, pin, items, title)`:
  Gruppen-Überschriften, Checkbox-Zeilen, Suchfeld (clientseitig filtert),
  optional „Alle / Nur Auswahl"-Schnellwahl, Submit `POST /select` mit
  wiederholtem Feld `item=<id>`.
- `CompanionHttpServer`: Route `POST /select` (PIN nötig) → `parseQuery` →
  Menge der gewählten IDs → `onSelection(Set<String>)`; `authedPage`-Branch;
  Body-Limit (82 IDs unkritisch).
- `CompanionController`: `startForSelectItems(port, title, items, onSelection)`
  + `selections: SharedFlow<Set<String>>` (analog `backups`/`tmdbKeys`).
- Keine Credentials → kein Passwort-Logging-Risiko; PIN-Schutz wie gehabt.

**App-Seite:**

- `SportsPrefs`: neuer Massen-Setter `setActiveLeagues(ids: Set<String>)`
  (plus bestehendes `setTeamFollowed`).
- „Manage on phone"-Flow (Composable/Dialog):
  1. `companion.startForSelectItems(DEFAULT_PORT, title = sport_picker_title,
     items = Katalog → CompanionSelectItem(id, label, region, selected = id in active),
     onSelection = ::apply)`.
  2. QR + PIN anzeigen (`StageCompanionStatus`, State aus `companion.state`).
  3. `companion.selections` sammeln → `prefs.setActiveLeagues(it)` →
     Bestätigung → `companion.stop()`.
- Einstieg: Button **„Manage on phone"** im `SportPickerDialog` (§7.2).
  (Bewusst nicht als eigene Rail-Zeile, um die Rail nicht zu überladen.)
- Fallback: Wer kein Handy mag, nutzt den TV-Picker; beide schreiben dieselben
  Prefs.

**Teams (Stufe 2 des Handy-Features):**

- Team-Listen pro Liga holt `EspnApi` über
  `…/{sport}/{league}/teams` → `sports[0].leagues[0].teams[].team`
  (`abbreviation`, `displayName`, `logo`). Verifiziert: `soccer/ger.1` = 18,
  `basketball/nba` = 30 Teams.
- Die Phone-Seite bekommt dann zwei Stufen: Liga antippen → deren Teams
  anhaken; Auswahl schreibt `followed_teams` (`SportsPrefs.teamKey`).
- Realisierung: entweder `CompanionSelectItem.group` = Liga und die Seite
  gruppiert + zeigt Teams als zweite Liste, oder ein zweiter Modus
  `SELECT_ITEMS`-Aufruf mit der Team-Liste der gewählten Liga. Empfehlung:
  **eine** Seite mit zwei Bereichen (Ligen oben, Teams der markierten Liga
  unten), damit ein einziger Submit reicht.

**Core-/PR-Hinweis:**

- Der Modus ist generisch und gehört als eigene **Core-Fork-Änderung** nach
  `HANDOFF.md` (Punkt „Core-Fork-Änderungen").
- Falls upstream gewünscht: separater Core-PR; **nicht** in den
  Sports-Hub-App-PR mischen.
- Core-i18n: neue `companion_*`-Strings (Titel, Suchen, Speichern, Leer,
  Fehler) in die Core-Locales; i18n-Gate im Core-Repo
  (`OwnTV_Core-main/tools/i18n`).

### 7.7 Companion-Phone-UI v2 — Multilevel-Layout ✅ umgesetzt (2026-10-13)

**Problem (gelöst):** `CompanionHtml.selectItemsPage` rendert alle Items als *eine* Liste
(`<h2>` je `group` + Checkbox-Matrix + Suche). Auf dem Testgerät 493 Zeilen
(81 Ligen, 69 Teams, 343 Spieler): Teams/Spieler einer Liga stehen irgendwo
zwischen hunderten Zeilen und sind ohne Suche praktisch unerreichbar.

**Umgesetztes Layout (Handy, drei Ebenen, ein `<form>`):**

| Ebene | Route (`location.hash`) | Inhalt |
|---|---|---|
| 1 „Start" | `#/` | nur Zeilen **mit Kindern** (= aktive Ligen mit Teams/Spielern) als Drilldown-Zeilen mit Zähler-Badge; Zeile „Alle verwalten (5/15)" → Ebene 3; Leerzustand, wenn nichts da ist |
| 2 „Liga" | `#/p/<id>` | Zurück-Pfeil, Liganame, Tabs **Teams / Spieler** (nur bei >1 Gruppe) mit lokaler Suche und „Alle auswählen"/„Auswahl aufheben", Checkbox-Zeilen |
| 3 „Alle verwalten" | `#/all` | alle Top-Level-Zeilen als Checkbox, nach Region als `<details>`-Akkordeons, Suche; Gruppen mit Auswahl starten offen |

- Alle Ebenen liegen in einem Dokument und einem `<form>`; inaktive Ebenen sind nur
  `hidden`. Ein Submit an `POST /select` trägt weiterhin den kompletten Zustand —
  Protokoll und `applyPhoneSelection` (Präfixe `team:`/`player:`) unverändert.
- Sticky Bottom-Bar mit Zähler-Badge (`(3)`) für ungespeicherte Änderungen;
  Zurück-Pfeil nutzt `history.back()`, Hash-Routing lässt die Android-Zurück-Geste
  die Ebenen ablaufen (beim Laden wird Ebene 1 als Basis-History-Eintrag gesetzt).
- Suche je Ebene filtert clientseitig; Enter im Suchfeld wird abgefangen, damit es
  die Auswahl nicht abschickt.

**Datenmodell (Core):** `CompanionSelectItem.parentId: String? = null` — `null` =
Ebene 1, sonst Kind dieser Zeile. Innerhalb einer Elternseite ist `group` das
Tab-Label (App liefert „Teams"/„Spieler").

**Umsetzung:**

| Datei | Änderung |
|---|---|
| Core `companion/CompanionMode.kt` | `CompanionSelectItem.parentId` (Default `null`, source-kompatibel) |
| Core `companion/CompanionSelectHtml.kt` (neu) | komplette Body-Renderung (Ebenen, Gruppen, Escaping) + CSS-Klassen + JS (Hash-Router, Tabs, Filter, Badges) — `Context`-frei, Labels kommen als `Labels`-Datenklasse |
| Core `companion/CompanionHtml.kt` | CSS um Picker-Regeln erweitert (Sticky-Bars, Zeilen, Akkordeon, Tabs); `selectItemsPage` delegiert an `CompanionSelectHtml`; neue `Copy`-Felder |
| Core `res/values(+de)/strings_setup.xml` | `companion_select_back/-manage_all/-all/-none/-empty` |
| Core-Test `CompanionSelectHtmlTest.kt` (neu) | 7 JVM-Tests: Ebenen-Split, Kinder-Zuordnung, Tabs, Escaping, ein-Checkbox-pro-Zeile, Zähler, Leerzustand |
| App `SportsViewModel.kt` | Team-/Spieler-Items mit `parentId = league.id`; `group` = Segment-Label (`teamGroup`/`playerGroup`) |
| App `SportsScreen.kt` | neue Labels auflösen und durchreichen; `SportsPhoneDialog` (QR/PIN) unverändert |
| App `values(+de)/strings.xml` | `sport_phone_teams/-players`; `sport_phone_title` → „Sport verwalten"/„Manage sports" |

Abweichung zum Entwurf: statt `companion_select_my_leagues/-manage_leagues/-changes`
generischere Keys (`manage_all`, Zähler als reine Zahl im Badge ohne Plural-String),
damit der Core-Modus sport-agnostisch bleibt; Ebene 1 listet nur Zeilen mit Kindern
(Zeilen ohne Kinder haben nichts zu konfigurieren und stehen nur in Ebene 3).

**Akzeptanz (2026-10-13):** Core-`testDebugUnitTest` inkl. 7 neuer Tests grün; App
`:app:testStandardDebugUnitTest` + beide Debug-APKs + `lintStandardDebug` grün;
i18n-`verify --bootstrap` in beiden Repos grün (Core 249 Baseline unverändert,
+20 safe-Einträge). Layout/JS zusätzlich im Desktop-Browser gegen die echte
CSS/HTML-Ausgabe geprüft (Ebenenwechsel, Tab-Umschaltung, Suche je Ebene, Badges,
Zähler, Zurück-Pfeil und `history.back()`, Enter im Suchfeld) — der Check fand
einen echten Bug (`(' '+el.className+' ').indexOf(' lv ') > 0` matcht bei
Einzel-Klassen nie, Treffer liegt auf Index 0 → jetzt `>= 0`).

**Offen:** Emulator-Ende-zu-Ende (TV-UI → QR/PIN → Handy-Seite). Auf dem
Test-AVD liegt ein release-signiertes `tv.sportio.app` (v1.0.2) mit Daten; das
Debug-APK lässt sich nur nach Deinstallation (Datenverlust) darüber installieren,
und lokal fehlen die Release-Keystore-Zugangsdaten für ein signiertes
Release-APK. Der Klickpfad wurde deshalb im Desktop-Browser gegen die echte
Renderer-Ausgabe gefahren.

**Risiken / Phase B (weiter offen):**

1. Neu aktivierte Liga hat keine Kinder (Teams/Spieler werden beim Öffnen nur für die
   *vorher* aktiven Ligen geladen) → deren Detailseite fehlt bis zum nächsten Öffnen.
   Phase B: Core-Route `GET /select/items?parent=<id>` + Provider-Callback +
   Lazy-Loading in der App.
2. Payload bleibt ~500 Items; DOM unkritisch, aber der Vorab-Fetch beim Öffnen
   bleibt (Phase B löst beides, vgl. Risiko 10 in §13).
3. Die Core-Änderung ist eine Fork-Änderung → in `docs/ARCHITECTURE.md`
   (Core-Fork-Liste) ergänzt.

**Nicht-Ziele:** keine neuen Endpunkte/Mode-Enums, kein Server-Zustand, kein
Umbau am TV-Picker (`SportPickerDialog`), kein Live-Refresh der Handy-Seite.

---

## 8. Einzelsport-Renderer (Parse + UI) ✅ umgesetzt (Phase 5)

- `EspnApi.parseEvent`: Wenn kein `home`/`away` gefunden wird **und**
  `league.kind == EVENT` → nicht mehr `null` zurückgeben, sondern
  `Fixture(format = LEADERBOARD/VERSUS, home = null, away = null, entries = …)`.
- `entries` aus `competitions[0].competitors` in `order`-Reihenfolge:
  - `athlete.displayName` bzw. `team.displayName`
  - `score`/`statistics` → `detail`
  - `logo`/`athlete.headshot` → `logoUrl`
- `format`:
  - Golf/`racing`/`nhra` → `LEADERBOARD`
  - `mma` → `VERSUS` (2 Einträge)
  - `tennis` → `VERSUS`
  - `cricket` → wie Teamsport versuchen (competitors mit homeAway prüfen),
    sonst `VERSUS`-Fallback
- Kanal-Matching (§`SportsMatcher`): für `EVENT` keine Team-Queries; stattdessen
  Broadcast-/Titel-Matching; Ergebnis bleibt optional.
- Neuer `EspnApi.teams(league): List<TeamRef>` für die Team-Auswahl am Handy
  (§7.6): `…/{sport}/{league}/teams` → `sports[0].leagues[0].teams[].team`
  (`id`, `abbreviation`, `displayName`, `logo`). Verifiziert für `soccer/ger.1`
  (18) und `basketball/nba` (30).
- **Tennis als Einzel-Matches** (Nachtrag 2026-10-12): Ein Turnier-Event wird in
  **je ein Fixture pro Match** zerlegt (`groupings[].competitions[]`),
  `Fixture.id = <league>/<eventId>/<matchId>`. Damit sind **alle** Spiele gelistet
  (ATP 135, WTA 243); Tabs/Datum filtern.
- **Follow-Regeln** (Nachtrag): `FixtureEntry` trägt `id` + `winner`. Follow-Buttons
  gibt es nur für **Duelle** (`format == VERSUS` ohne home/away → Tennis/MMA);
  **Leaderboards** (F1/Golf/Racing) haben **kein** Follow — einem einzelnen Fahrer
  oder Golfer zu folgen ergibt keinen Sinn. Eigenes Pref-Set `followed_players`
  (`leagueId|playerId`), in Favourites und „My teams" eingebunden.
- **F1/Racing = `FixtureFormat.EVENT`** (Nachtrag): kein Fahrerfeld; `Fixture.title` (Rennen) +
  `Fixture.venue` (Circuit aus `event.circuit`). `SportLeague.extraChannelRes` liefert einen
  Kanal-Suchbegriff (F1 → „Sky Sports Main Event"), den `SportsMatcher` **zusätzlich** zum
  normalen Matching als ganze Phrase sucht (`Fixture.channelHints`).
  (Veraltet 2026-10-25: `channelHints` bleibt leer für F1; das Matchen läuft über den
  Event-Titel gegen den Web-Guide und die EPG-Zeilen — kein fest kodierter Kanalname.)

---

## 9. Strings & i18n-Gate

Neue `sport_*`-Keys (in `values` **und** `values-de`):

| Key | en | de |
|---|---|---|
| `sport_tab_live` | Live | Live |
| `sport_tab_upcoming` | Upcoming | Demnächst |
| `sport_tab_past` | Past | Vergangen |
| `sport_favourites` | Favourites | Favoriten |
| `sport_add_leagues` | Add leagues | Ligen hinzufügen |
| `sport_picker_title` | Leagues | Ligen |
| `sport_picker_subtitle` | Pick the leagues shown and loaded | Ligen auswählen, die angezeigt und geladen werden |
| `sport_no_live_games` | No live games | Keine Live-Spiele |
| `sport_no_upcoming_games` | No upcoming games | Keine kommenden Spiele |
| `sport_no_past_games` | No results | Keine Ergebnisse |
| `sport_region_germany` | Germany | Deutschland |
| `sport_region_europe` | Europe | Europa |
| `sport_region_international` | International | International |
| `sport_region_americas` | Americas | Amerika |
| `sport_region_rest` | Other | Sonstige |
| `sport_manage_on_phone` | Manage on phone | Am Handy verwalten |
| `sport_phone_title` | Choose leagues | Ligen auswählen |
| `sport_phone_saved` | Selection saved | Auswahl gespeichert |

- **Core-Companion-Strings** (neuer Modus, §7.6) liegen im Core-Repo:
  `companion_select_title`, `companion_select_search`, `companion_select_save`,
  `companion_select_empty`, plus Fehlermeldungen — in alle Core-Locales und
  durch das Core-i18n-Gate.

- Bestehende `sport_group_live/today/upcoming/results` können bleiben (Tages-/
  Untertitel) oder teilweise entfallen → dann `prune-safe`.
- **Liga-Labels (umgesetzt):** proper nouns („Bundesliga", „NBA", „Süper Lig") liegen als
  nicht-übersetzbare Ressourcen in `res/values/donottranslate.xml`
  (`sport_league_<id>`, z. B. `sport_league_soccer_ger_1`); `SportLeague.labelRes`
  referenziert sie. Damit bleiben sie aus dem i18n-Gate und folgen der Projektregel
  „UI-Text → Resources".
  - **Gotcha:** Apostrophe in String-Ressourcen müssen als `\'` escaped werden
    (z. B. `Women\'s Super League`), sonst schlägt AAPT mit „Invalid unicode escape
    sequence in string" fehl.
  - Die **Slug-Literale** (ids, `sport`, `league`) sind weiterhin Kotlin-Literale und
    als `uri` im Gate klassifiziert (`safe_literals.txt`, Kategorie `uri`).
  - Regionsnamen sind übersetzbar und gehören als `sport_region_*` in
    `strings.xml` + `values-de` (folgt in Phase 2/3).
- Gate-Workflow:
  `tools-setup\python-embed\python.exe tools\i18n\check_hardcoded_strings.py verify --bootstrap`
  Neue Literale: `classify-safe --path <rel> --text '<exakt>' --category …`.
  Gelöschte: `prune-safe`.

---

## 10. Performance / Lade-Strategie

- **Nur aktive Ligen laden** (§6) — verhindert 80 parallele Requests.
- 15-min-TTL je Liga bleibt; Refresh-Button (`refreshSelected`) lädt die
  **aktiven** Ligen neu (nicht alle 82).
- Live-Zähler in der Rail nur für geladene Boards (nicht aktivierte = 0/kein Dot).
- Standings-Cache pro Liga (nur bei Auswahl) bleibt.
- OkHttp: eine Client-Instanz (DI, `singleOf(EspnApi)`) — unverändert.
- Optional: bei vielen aktiven Ligen Requests sequenziell/chunked statt alle
  gleichzeitig (OkHttp-Dispatcher-Limits beachten). Als Feinschliff notiert.

---

## 11. Phasen & Aufgaben (Checklisten)

### Phase 1 – Katalog + Modelle + Lade-Strategie  ✅ erledigt (2026-10-12)
- [x] `LeagueRegion`, `LeagueKind`, `SportLeague` (+`labelRes`/`region`/`kind`/`defaultActive`) erweitern.
- [x] Katalog (§3.3/§3.4) eintragen, `defaultActive` für die 16. → **81 Ligen, 16 aktiv**.
- [x] `Fixture.format`/`entries`, `home`/`away` nullable; Aufrufer (Matcher, Cards,
      Detail, `myTeamFixtures`) gefixt; EVENT-Zweige als Platzhalter (voll in Phase 5).
- [x] `SportsPrefs`-Default auf `defaultActive`-Set; `setActiveLeagues`.
- [x] `SportsViewModel`: nur aktive laden (reaktiv, purgt deaktivierte), `activeLeagues`.
- [x] Labels in `donottranslate.xml`; Slug-Literale als `uri` im Gate.
- [x] Akzeptanz erfüllt: Emulator, Hub öffnet → genau **16** `SportsHub: board ok`; Rail = Default-Satz;
      i18n-Gate grün; beide APKs grün.

### Phase 2 – Picker + „Add leagues"  ✅ erledigt (2026-10-12)
- [x] `SportPickerDialog`: Region-Gruppen (Germany/Europe/International/Americas/Other),
      Haken, Titel „Leagues" + Untertitel; ersetzt den alten Settings-Dialog.
- [x] Rail-Zeile „Add leagues" (`OwnTVIcon.ADD`) am Ende der Liga-Liste, mit Fokus.
- [x] Toggle aktiviert/deaktiviert; Cache-Purge + Rail-Update live.
- [x] Region-Strings `sport_region_*`, `sport_add_leagues`, `sport_picker_title/-subtitle`
      (strings + values-de).
- [x] Akzeptanz erfüllt (Emulator): „2. Bundesliga" aktivieren → Rail zeigt es sofort +
      **nur** `SportsHub: board ok soccer.ger.2`; deaktivieren → Zelle verschwindet wieder.
- Hinweis: Der Zahnrad-Button in Spalte 2 öffnet denselben Picker (bewusst).

### Phase 3 – Tabs Spalte 2  ✅ erledigt (2026-10-12)
- [x] `SportsViewModel.FixtureTab { LIVE, UPCOMING, PAST }` + `_tab`/`setTab`; Default beim
      Ligawechsel: Live, wenn Live-Spiele vorhanden, sonst Upcoming (manuelle Wahl bleibt
      bis zum Ligawechsel).
- [x] Tab-Row (`FixtureTabPill`) über der Liste; Filter `fixturesFor(...)` (Live nach Zeit,
      Upcoming nach Zeit, Past neueste zuerst `take(30)`); Tages-Untertitel bleiben an der
      Karte; Empty-States je Tab.
- [x] Strings `sport_tab_live/-upcoming/-past`, `sport_no_live_games/-upcoming/-past`
      (strings + values-de); alte `sport_group_*` entfernt.
- [x] `buildRows`/`SportsRow`/`isToday` entfernt.
- [x] Akzeptanz erfüllt (Emulator): Bundesliga → Default Upcoming (leer), Live → „No live
      games", Past → 2 Ergebniskarten; D-Pad: Settings → ▼ = Live-Tab → ▶▶ = Past → OK.

### Phase 4 – Favourites  ✅ erledigt (2026-10-12)
- [x] Sentinel `SportsViewModel.FavouritesId`; Rail-Eintrag „Favourites" (Star)
      über dem Header „Ligen"; Header/Ansicht zeigen „Favourites".
- [x] Aggregat `favourites` = alle Fixtures aktiver Ligen **plus** Spiele gefolgter Teams,
      live-first; `currentFixtures` speist Anzeige (Liga ODER Aggregat).
- [x] Lade-Set = aktive Ligen **∪** Ligen gefolgter Teams (`followedTeamLeagues`), damit
      ein gefolgtes Team aus einer deaktivierten Liga in Favourites auftaucht.
- [x] „My teams"-Chips nur noch in Favourites; aus der Liga-Ansicht entfernt.
- [x] Standings im Detail folgen der Liga des Fixtures (wichtig im Aggregat).
- [x] Akzeptanz erfüllt (Emulator): Favourites listet mehrere Ligen gemischt; „Follow"
      auf SCF → Chip + Spiele; **Bundesliga deaktiviert → Rail ohne Bundesliga, SCF
      bleibt in Favourites.**

### Phase 5 – Einzelsport-Renderer  ✅ erledigt (2026-10-12)
- [x] `EspnApi.parseEvent`: fällt bei fehlendem home/away auf einen **Entries**-Zweig zurück
      (Athlet/Team, `order`=Rang, `score`, Logo); `format = LEADERBOARD` für `golf`/`racing`,
      sonst `VERSUS`. Tennis: Matches liegen unter `groupings[].competitions[]` (erster Match
      als Kopf-Duell). Cricket hat homeAway/team → normaler Team-Pfad.
- [x] `FixtureCard`/`FixtureDetail`-Zweige (aus Phase 1) rendern Leaderboard bzw. Duell.
- [~] Kanal-Matching-Fallback (Broadcast/Titel) für Einzelsport **nicht** umgesetzt: EVENT-
      Fixtures zeigen „No matching channel" (bewusst, optional). **Überholt 2026-10-13 für
      Tennis/MMA-Duelle** (Spieler-/Turnier-Tiers, siehe Nachtrag oben); F1/Golf weiter ohne.
- [x] Akzeptanz erfüllt (Emulator): PGA → Leaderboard (Brennan -16, Mitchell -15 …);
      UFC → Duell (Asher vs. Foster); F1 → 22 Einträge; ATP/WTA → Duells.

### Phase 6 – Companion-Phone-UI (QR) — **Stufe 1 (Ligen) ✅ erledigt (2026-10-12)**
- [x] **Core**: `CompanionMode.SELECT_ITEMS`, `CompanionSelectItem`,
      `CompanionHtml.selectItemsPage`/`selectItemsSentPage`, Route `POST /select`,
      `CompanionHttpProtocol.parseQueryValues`, `CompanionController.startForSelectItems`
      + `selections`-Flow.
- [x] **Core**: `companion_select_*`-Strings (values + values-de); Core-i18n-Gate grün
      (neue Companion-Literale als `technical`/`empty`); in `HANDOFF.md` als
      Core-Fork-Änderung notiert.
- [x] **App**: `SportsPrefs.setActiveLeagues`; „Manage on phone"-Eintrag im Picker →
      `SportsPhoneDialog` (QR via `StageCompanionStatus`); `SportsViewModel.startPhonePicker`
      baut die Items, wendet `selections` an und stoppt den Server nach 1,5 s.
- [x] **App (Stufe 2)**: `EspnApi.teams(league)` (`…/teams`); die Handy-Seite listet jetzt
      **Ligen + Teams der aktiven Ligen + Spieler/Kämpfer** (aus den geladenen Duellen). Ids mit
      Präfix (`team:` / `player:`), ein Submit gleicht aktive Ligen + gefolgte Teams/Spieler ab
      (gefolgte Einträge anderer Ligen bleiben). `SportsPrefs.setFollowedTeams/setFollowedPlayers`.
- [x] Akzeptanz Stufe 2 (Emulator): Handy-Seite 493 Checkboxen (69 Teams, 343 Spieler, Ligen);
      Wahl `team:soccer.ger.1|FCU` + `player:tennis.atp|3093` → TV: Rail wechselt, Favourites-
      Chip „Adam Walton vs Te Rigele"; nach Neuladen sind beide angehakt. Platzhalter
      (negative Ids/„TBD") gefiltert.
- [x] Akzeptanz erfüllt (Emulator): Panel zeigt PIN+QR (`http://10.0.2.15:8089`, PIN);
      `adb forward tcp:8089` + curl: `POST /` (PIN) liefert 81 Checkboxen;
      `POST /select` → Seite „Saved", TV-Rail wechselt **live** auf die Auswahl,
      neue Ligen laden (`ger.2`, `eng.2`); Server stoppt danach.

### Phase 7 – Abschluss  ✅ erledigt (2026-10-12)
- [x] i18n-Gate beide grün: App `45 baseline + 1594 safe`, Core `249 baseline + 5248 safe`.
- [x] `:app:assembleStandardDebug` + `:app:assembleX86_64Debug` grün (mit `-x lint`).
- [x] `:app:lintStandardDebug` (abortOnError) grün + `:app:testStandardDebugUnitTest`
      + `:OwnTV_Core-main:core:testDebugUnitTest` grün.
- [x] Emulator-Smoke-Test (Hub öffnet, Rail, Tabs, Detail, Kanäle) + Screenshots;
      Box-Test bewusst zurückgestellt (User: nur Emulator testen).
- [x] `HANDOFF.md` + dieses Dokument aktualisiert.
- **Signing/Updates vorhanden:** `keystore/sportio-release.jks` (Alias `sportio`); `:app:assembleRelease`
  baut signiert (verifiziert CN=Sportio). `releaseRepo = trenntl/SportioTV`,
  `updateCheckOnStart` an; Repo-Release `v1.0.0` (`Sportio.apk`, `Sportio-x86_64-v1.0.0.apk`).

---

## 12. Testplan

- Build: beide APKs, `-x lint`, Unit-Tests, i18n (`verify --bootstrap`).
- Emulator (`SportioTV`, x86_64): Start, Hub öffnen → nur 16 Ligen +
  ihre Requests; Liga aktivieren (ger.2) → erscheint + lädt; Tabs prüfen;
  Favourites mit gefolgtem Team prüfen; Golf-Liga aktivieren → Leaderboard.
- Box (Android TV, ARM-APK): gleicher Flow mit echter Quelle; Kanal-Start aus
  Favourites/Detail prüfen (Regressionstest für den 12.10.-Fix).
- Logcat-Marker: `SportsHub board ok/failed`, `nodate`, `noteams`.
- Kein `refreshAll()`-Sturm: Requests im Logcat/Netz zählen (nur aktive).
- Handy-Flow (§7.6): „Manage on phone" öffnen, Companion-URL im PC-Browser
  aufrufen, PIN tippen, Ligen ab-/wählen, speichern → Rail/Favourites ohne
  Neustart prüfen. **Emulator-Trick**: die LAN-URL (`10.0.2.15`) ist vom Host
  nicht direkt erreichbar → `adb forward tcp:<port> tcp:<port>` und dann
  `http://localhost:<port>` im Browser öffnen. Am echten TV/Box normal per QR.

---

## 13. Risiken & offene Punkte

1. **Nullable `home`/`away`** berührt mehrere Dateien → in Phase 1 sauber
   durchziehen, sonst Compile-Fehler-Kaskade.
2. **DataStore-Default**: Auf dem Dev-Gerät kann `followed_leagues` bereits
   geschrieben sein → neue Auswahl gewinnt. Gewollt, aber beim Testen beachten
   (ggf. App-Daten löschen für „wie neu").
3. **Rail-Länge**: durch Aktivierung begrenzt; ohne Aktivierung nur 16.
4. **Turnier-Ligen** (World Cup/Euro/Copa) sind außerhalb der Saison leer —
   Empty-States müssen sauber sein.
5. **Cricket-Shape** unbekannt (hat ggf. kein home/away) → Fallback testen.
6. **Kanal-Matching Einzelsport** liefert selten Treffer → UX-Hinweis nötig.
7. **i18n**: Katalog-Labels als Code-Literale müssen klassifiziert werden.
8. **Upstream-PR** (`feature/sports-hub`): Diese Erweiterungen bewusst **nicht**
   in die offenen PRs mischen; sie bleiben im Sportio-Fork.
9. **Companion-Modus**: Der neue `SELECT_ITEMS`-Modus ist eine Core-Änderung →
   getrennt halten (Fork-Changelog + ggf. separater Core-PR). Der Server ist ein
   app-weites `single`: startet der Sport-Picker, stoppt ein evtl. laufender
   Add-Source-/Backup-Flow (gewollt, aber im UI sauber kommunizieren).
10. **Phone-Team-Listen**: 82 Ligen × Teams können den Payload groß machen →
    Team-Listen erst auf Anfrage (gewählte Liga) an das Handy liefern.
11. **PIN/QR**: gleiche Sicherheit wie der Add-Source-Flow; der QR-Code enthält
    nie die PIN.

---

## 14. Referenzen

- Aktuelle Implementierung: `app/src/main/java/tv/own/owntv/features/sport/`
- Fix-Historie/Umgebung: `HANDOFF.md`
- Produkt-/Build-Doku: `docs/BUILD.md`
- ESPN-API:
  - Scoreboard: `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard`
  - Standings: `https://site.web.api.espn.com/apis/v2/sports/{sport}/{league}/standings`
  - Teams: `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/teams`
- Companion-Server (Core): `OwnTV_Core-main/core/src/main/java/tv/own/owntv/core/companion/`
  - Modus: `CompanionMode.kt` · Router: `CompanionHttpServer.kt` ·
    Lifecycle: `CompanionController.kt` · Seiten: `CompanionHtml.kt` ·
    LAN/QR: `CompanionLink.kt`
  - App-Vorbilder: `features/setup/RemoteSetupScreen.kt`,
    `ui/components/BackgroundImageChooserDialog.kt` (`StageCompanionStatus`)
- i18n-Gate: `tools/i18n/check_hardcoded_strings.py`
- Lume (Vorbild-Schema): https://github.com/bilipp/Lume
