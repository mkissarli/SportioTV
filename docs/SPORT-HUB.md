# docs/SPORT-HUB.md — Einstieg (Stand: 2026-10-19)

> Zweck: Einstiegsseite für den Sports-Hub; Detailspecs bleiben in den
> referenzierten Dateien, damit nichts doppelt gepflegt wird.

## Status: alle Phasen 1–7 fertig (2026-10-12)

- Katalog: 81 ESPN-Ligen, 16 default aktiv; nur aktive Ligen werden geladen
  (`SportsViewModel.activeLeagues`, reaktiv, purgt deaktivierte).
- „Add leagues"-Zeile + gruppiertes Picker-Dialog (Region-Gruppen) + Zahnrad-Button.
- 3 Tabs in Spalte 2 (Live/Demnächst/Vergangen, lokalisiert; Default Live-wenn-vorhanden).
- Rail-Eintrag **Favourites** (Star) über dem Ligen-Header: Aggregat aktiver Ligen +
  gefolgte Teams (live-first); Lade-Set = aktive ∪ Ligen gefolgter Teams.
- Einzelsport-Renderer: Tennis als einzelne Matches (ATP 135 / WTA 243 statt 1),
  F1 nur Rennen + Strecke (`FixtureFormat.EVENT`, keine Fahrer), Golf-Leaderboard,
  UFC/ATP/WTA-Duelle. Follow nur bei Duellen (`followed_players`), Leaderboards bewusst ohne.
- Handy-Verwaltung per QR (`CompanionMode.SELECT_ITEMS`, Core): Ligen + Teams je
  aktiver Liga + Spieler/Kämpfer aus geladenen Duellen, ein Submit
  (`team:`/`player:`-Präfixe). Seite seit 2026-10-13 dreistufig: Start (aktive
  Ligen mit Zähler) → Liga mit Tabs Teams/Spieler → „Alle verwalten"
  (Region-Akkordeons, Suche); Details §7.7 in `sport-hub-plan.md`.
- Sprachfilter: bis zu 20 Sender pro Spiel (`DefaultLimit = 20`), Filter nach
  bevorzugter Sprache aus Länderkürzel am Kanalnamen (`DE:`/`UK|`/`FR/`/`┃DE┃`),
  einmaliger Dialog beim ersten Fixture mit Treffern, danach Sport-Einstellungen.
  Ort: im Matcher VOR dem Cap (`match(fixture, languages, limit)`). Exakte
  Event-Kanäle (Name nennt beide Teams/Spieler, `ChannelMatch.isExact`) passieren
  immer — sie sind das Event selbst, kein Fremd-Feed davon.
- Web-Guide (Where's The Match, `BroadcastGuideApi`) ist **eine** unabhängige Quelle für
  „wer zeigt das Spiel": ein Day-Window-Request
  (`live-sport-on-tv/?showdatestart=…&showdateend=…`, 30-min-Cache, ein Retry;
  drei 3-Tage-Fenster ab **heute** — ein Start in der Vergangenheit lässt die Seite
  nur die Reste von heute liefern, gemessen 2026-10-25). Treffer: Teams/Duelle per
  beiden Namen im Row-Text (generische Club-Wörter wie „united"/„city" zählen nicht;
  feste Kurzformen wie „Tottenham" für „Tottenham Hotspur"), F1/Events per
  Titel-Tokens plus **gleiche Session** (Practice erbt nie das Rennen). Eine
  ATP/WTA-Tour-Zeile pro Tag ist **kein** Beweis für ein einzelnes Match — sie geht
  nur als Kontext an die KI. Gated rows (`unlock-premier-listing`) sind fehlende
  Sender-Evidenz, kein „läuft nicht". Die gefundenen Sender werden gegen die Playlist
  aufgelöst (Präfix/Suffix/Plural-tolerant), in Guide-Reihenfolge gezeigt und per
  Regel-4-Identität dedupliziert (`ChannelDedup`: Länder-/Quellen-Tag bleibt Identität,
  Auflösungs-/Codec-Wörter fliegen, beste Auflösung gewinnt, „Main Event" wird nie
  gestrippt).
- **Guide, EPG und KI werden unabhängig ausgewertet und vereinigt** (`SportsMatcher.
  mergeEvidence`): ein nicht-leerer Guide unterdrückt weder die EPG-Suche noch eine
  bestätigte Voll-Playlist-KI-Antwort. Evidenzklassen sortieren Guide > EPG/Name >
  KI-only; Priors und Länderwunsch sind nur Tie-Breaker. Details: `AI-PLAYLIST.md`.
- KI-Empfehlung: DeepSeek nennt Sender je gewähltem Land, `AiChannelMatcher`
  prüft sie gegen die eigenen Live-Playlists. Der Kanäle-Bereich hat oben die Tabs
  **Classic | AI**; im AI-Tab stehen die Treffer als normale vertikale Liste, nach
  Land gruppiert und mit hartem Ländertag-Filter plus Qualitäts-/Dubletten-Ranking.
  Ein Fixture+Länder-Cache verhindert bezahlte Wiederholungen. API-Key (direkt oder
  per QR/Handy über `CompanionMode.AI_KEY`) und
  Vorschlagszahl (Default 20) stehen im KI-Tab. Details: `SPORT-AI.md`.

## Matching-Modell (Lume-Modell, Beide-Teams-Pflicht)

- Ein Kandidat zählt nur mit **beiden** Teams — im Kanalnamen (Score 4) oder
  gemeinsam in **einem** EPG-Programm (Titel 10, Beschreibung 3).
- Ein-Team-Gewichte und Legacy-Roh-Substring-Test sind entfernt
  (sonst `NEW`→„New Girl"-False-Positives).
- Zwei Discovery-Pfade: A) Kanalnamen (Alias-Phrasen + volle Broadcast-Phrase),
  B) Guide via `EpgDao.searchTitles` + `byEpgKeys` + `EpgDao.searchGuide`
  (Titel ODER Beschreibung). **B läuft immer** — nie per Namens-Cap guard-en
  (hat „Coventry v Newcastle" auf 6 statt 12 Sender reduziert).
- Discovery = rohe akzentbehaftete Tokens für SQL `LIKE`; Scoring = normalisierte
  Tokens in-memory. Nicht vermischen (verliert „München").
- Der Kanalname-/EPG-Pfad läuft **nur**, wenn der Web-Guide für das Fixture
  nichts liefert. Reine Events (F1/Golf) nehmen dort ihren Titel als
  Wettbewerbs-Text (`united states grand prix` — kein fest kodierter Kanal-Hint
  mehr). PGA/IRL haben keinen Hint.
- Duelle (`VERSUS` mit 2 Entries, Tennis/MMA) laufen seit 2026-10-13 denselben
  Pfad mit Spielernamen: beide Athleten im Titel +10 / verteilt +3, ein Athlet
  mit Tennis-Kontext +2, Turnier allein +1. Doppel-Roster („A / B") liefert beide
  Spieler je Seite, „TBD" fällt auf den Turnier-Fallback zurück. Tennis-Ligen
  haben den Kanal-Hint „Tennis" (`sport_channel_tennis`), damit auch Fixtures
  ohne Guide-Daten ihre Tenniskanäle finden.
- Trade-off: Recall sinkt, wenn ein Feed im Guide nur ein Team nennt —
  Alias-Pflege in `res/raw/sports_matching.json` ist wichtiger als vorher.

## Nicht-wieder-einbauen (verifizierte Fallen)

1. `DisposableEffect(...){ liveVm.stopPreview() }` in `SportsScreen` — killt beim
   Fullscreen-Gehen den gerade gestarteten Sender (Shell disposed Browse-UI).
   Nur bei Spiel-Wechsel stoppen (`LaunchedEffect(selectedFixture?.id)`).
2. Guard, der EPG-Discovery bei vollem Namens-Cap überspringt.
3. Post-Cap-Sprachfilter in der UI (deutsche Feeds scoren niedrig und wären
   nach `take(20)` unsichtbar) — Filter gehört in den Matcher vor den Cap.

## Detailreferenzen (nicht duplizieren)

- `sport-hub-plan.md` — Phasen-Spec, ESPN-API (Scoreboard/Standings),
  `normalizeEspnDate`-Gotcha, Verifikationsprotokolle.
- `sport-channel-matching.md` — Flow, Gewichte, Lume-Mappings, Tests
  (`SportNameMatchTest`, `SportLanguagesTest`, `SportsMatcherTest` mit
  `LiveSourceIdsProvider`-Naht + Proxy-gefakten DAOs).

## Daten

- ESPN Site-API (keyless): `https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard`.
- Standings nur unter `https://site.web.api.espn.com/apis/v2/sports/…/standings`.
- 403 über Bot-Netze möglich; Fire TV mit normalem Netz: 200.
