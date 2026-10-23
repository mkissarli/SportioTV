# Sport-Hub: Sender-Matching (fixture → Kanal)

Stand: 2026-10-13. Gilt für `app/src/main/java/tv/own/owntv/features/sport/` (Sportio-App-Fork).

Diese Datei erklärt **wie ein ESPN-Fixture auf die Sender des Nutzers gematcht wird**, warum es so
gebaut ist, und was als Nächstes geplant ist. Referenz-Implementierung ist Lume
(<https://github.com/bilipp/Lume>, AGPL-3.0, `Lume/Services/Sports/SportsChannelResolver*.swift` +
`SportsMatcher.swift`).

## 1. Das Problem (Ausgangslage)

Gleiche Xtream-Quelle, gleiches EPG: Lume schlug für ein Premier-League-Spiel ~14 Sender vor,
Sportio nur ~4. Ursache war **nicht** das Scoring, sondern die **Kandidaten-Findung (Discovery)**:

- Der alte Matcher suchte Kandidaten **nur im Kanalnamen** (`ChannelDao.searchList` →
  `name LIKE '%token%'`). Das EPG wurde danach nur benutzt, um einen **bereits per Name gefundenen**
  Kanal zu *bewerten*.
- Ein Sender wie `DE: SKY SPORT AUSTRIA 1 HD`, dessen Name keine Team-/Broadcast-Spur trägt, dessen
  Guide aber „Coventry City v Newcastle United" sagt, wurde dadurch **nie Kandidat** — bei Lume
  schon.

Lume macht es umgekehrt: es lädt **alle** sichtbaren Kanäle, indexiert **Kanalname UND Guide-Text**
(Titel/Untertitel/Beschreibung) und zieht daraus die Kandidaten.

## 2. Die Lösung: zwei Discovery-Quellen

`SportsMatcher.match(fixture)` ist zweistufig:

```
match(fixture, languages, limit)
  │
  ├─ Pass 1  discoverCandidates()
  │    A) Kanal-Namen   channelDao.searchList(token)              (alt, erweitert)
  │    B) Guide-Titel   epgDao.searchTitles(token, t-2h, t+4h)    (Titel, breite Tokens)
  │                      + epgDao.searchGuide(fullName, …)         (NEU: Titel ODER Beschreibung)
  │                     → epgChannelId-Set
  │                     → channelDao.byEpgKeys(sourceIds, set) (NEU)
  │    = Union, dedupliziert nach channel.id, gecappt auf CandidateCap
  │
  └─ Pass 2  scoreChannel() je Kandidat (Lume-Modell: beide Teams Pflicht)
             Name-Treffer + Broadcast-Treffer + Hint + EPG-Treffer
             → ChannelMatch nur bei score > 0, absteigend
             → Sprachen filtern (vgl. Abschnitt 10), DANN take(limit)
```

Der Schlüssel ist **Pass 1 B**: `EpgDao.searchTitles` (schon vorhanden, wird von der globalen Suche
genutzt) macht `title LIKE '%token%'` **im Zeitfenster** über den `(startMs, stopMs)`-Index.
`ChannelDao.byEpgKeys` löst die gefundenen `epgChannelId`s zu Kanälen auf (normalisiert via
`LOWER(TRIM(...))`).

**Neu: Beschreibungs-Discovery (Core-Methode `EpgDao.searchGuide`).** Viele Fremd-Feeds nennen die
Teams **nicht im Titel**, sondern nur im Body — z. B. `DE: SKY SPORT PREMIER LEAGUE` mit Titel
„Fußball: England, Premier League" und „… Coventry City …" in der Beschreibung. `searchTitles` findet
solche Sender nie. `searchGuide` matcht `title LIKE OR description LIKE` im selben Zeitfenster
(derselbe `(startMs, stopMs)`-Index, also kein Full Scan). Damit das die Liste nicht flutet, wird die
Beschreibung **nur** mit **mehrwortigen** Teamnamen (`epgDescriptionPhrases`, z. B. „Coventry City",
„Newcastle United") abgefragt — einzelne Wörter wie „Newcastle"/„City" bleiben Titel-only, weil sie in
Prosa (Serien, Filme) viel zu häufig sind. Das ist ein **Core-Fork-Punkt** (`core/…/dao/EpgDao.kt`).

> **Wichtig:** Wenn du an der Discovery schraubst, halte die beiden Pfade getrennt:
> - **Discovery** = rohe, akzentbehaftete Tokens für SQL `LIKE`. Die DB speichert die Schreibweise
>   des Feeds; `LIKE '%Munchen%'` verpasst ein gespeichertes „München". Deshalb
>   `SportNameMatch.discoveryPhrases(...)`.
> - **Scoring** = normalisierte Tokens in-memory auf beiden Seiten, damit Akzente/Casing nie
>   entscheiden. Deshalb `SportNameMatch.tokensForName(...)` / `containsWord(...)`.

## 3. Dateien

| Datei | Rolle |
| --- | --- |
| `features/sport/SportsMatcher.kt` | Discovery + Scoring. Einstiegspunkt `match(fixture, languages, limit)`. |
| `features/sport/SportNameMatch.kt` | **Pure** Text-Helfer: `normalize`, `containsWord`, `containsPhrase`, `tokensForName`, `discoveryPhrases`, `mentionsSport`. Kein Android, kein DAO → ohne Gerät testbar. |
| `features/sport/SportsVocab.kt` | Lädt das statische Vokabular aus `res/raw/sports_matching.json` (Aliase/Exonyme, Stopwörter, Sportwörter). Degradiert zu `EMPTY` statt zu crashen. |
| `features/sport/SportLanguages.kt` | **Pure** Sprachlogik: Länderkürzel aus dem Kanalnamen lesen, Land → Sprache abbilden, Treffer nach bevorzugten Sprachen filtern. Siehe Abschnitt 10. |
| `features/sport/SportsSources.kt` | `LiveSourceIdsProvider` — die Naht für den Live-Playlist-Lookup (Testbarkeit ohne Android) + `ProfileLiveSourceIdsProvider` (Produktion). Siehe Abschnitt 9. |
| `res/raw/sports_matching.json` | Das Vokabular als **Daten** (JSON), damit es nicht durch das i18n-Gate muss und ohne Codeänderung erweiterbar ist. |
| `di/AppModule.kt` | `single { SportsVocab.load(androidContext()) }` + `single<LiveSourceIdsProvider> { ProfileLiveSourceIdsProvider(get(), get()) }` + `singleOf(::SportsMatcher)`. |
| **Core** `core/…/dao/EpgDao.kt` | `searchGuide(query, from, to, limit)` — Guide-Suche über **Titel ODER Beschreibung** im Zeitfenster. Core-Fork-Punkt (siehe `HANDOFF.md`). |

`SportsMatcher` bekommt `SportsVocab` und den `LiveSourceIdsProvider` als Konstruktor-Parameter; Koin
injiziert beide automatisch (analog zu `EspnApi`, das `Context` bekommt). Der Provider ersetzt die
früheren `SettingsRepository`/`SourceRepository`-Abhängigkeiten des Matchers — siehe Abschnitt 9.

## 4. Scoring (Lume-Modell, seit 12.10.)

Ein Kandidat qualifiziert sich **nur**, wenn **beide** Teams belegt sind — im Kanalnamen oder
gemeinsam in **einem** EPG-Programm. Ein Ein-Team-Treffer ist ein Nachrichten-Ticker, eine Serie in
der Stadt des Vereins oder ein Film-Blurb, nicht das Spiel. Das ist Lumes
`ResolvedChannelSource`-Modell (`epgTitleSubtitle` > `epgSingleField` > `epgDescription` >
`channelName`), additiv gewichtet statt tier-typisiert.

| Signal | Gewicht | Konstante |
| --- | --- | --- |
| **Beide** Teams im Kanalnamen | +4 | `NameBothTeamsScore` |
| **Beide** Athleten im Kanalnamen (Duell) | +4 | `NameBothTeamsScore` |
| Volle Broadcast-Phrase im Kanalnamen | +3 | `BroadcastPhraseScore` |
| Broadcast-Wort im Kanalnamen | +3 | `BroadcastWordScore` |
| Channel-Hint im Kanalnamen (F1: „Sky Sports Main Event", Tennis: „Tennis") | +6 | `HintScore` |
| **Beide** Teams/Athleten im EPG-Titel | +10 | `EpgBothTeamsScore` |
| Beide über Titel/Beschreibung (nur eins im Titel) | +3 | `EpgDescriptionBothScore` |
| Ein Athlet + Tennis-Kontext im selben Programm (Duell-Recall) | +2 | `EpgSingleWithContextScore` |
| Nur das Turnier im Programm (Duell-Recall) | +1 | `EpgTournamentScore` |
| EPG-`categories` nennt eine Sportart | +1 | `SportCategoryScore` |

**Entfernt (12.10.):** die alten Ein-Team-Gewichte `AbbreviationNameScore`/`FullNameScore`/
`AliasNameScore` (+2 je Ein-Team) und `EpgOneTeamScore`/`EpgDescriptionOneScore` (+5/+1), sowie der
**Legacy-Roh-Substring-Test** `legacyEpgHit`. Grund: `NEW` matchte „**New** Girl", `COV` matchte
„**Cov**enant", und Ein-Team-Beschreibungen zogen News-/Film-Sender herein. Das war bewusst eine
Verhaltensänderung (Lume-Treue), keine additive Erweiterung.

### `EpgMinTokenLength = 4`
Kurze Kürzel (`NEW`, `COV`) zählen nur im **Kanalnamen**, nicht im Guide-Text — als Ganzwort sind sie
in Prosa zu häufig.

### Spitznamen sind Pflicht für Feeds mit generischem Titel
`DE: SKY SPORT PREMIER LEAGUE` nennt im Body nur „Coventry City" und „die **Magpies**". Erst der
Alias `Newcastle United → Magpies` in `res/raw/sports_matching.json` liefert das zweite Team, sodass
der Feed die Beide-Teams-Regel erfüllt.

### Tunables (in `SportsMatcher.Companion`)
`DefaultLimit=20` (war 6, dann 8, dann 12), `MatchSearchLimit=30` (war 20), `NameCandidateCap=120` (war 60),
`EpgMatchLimit=40`, `EpgTokenCap=24`, `EpgDescriptionScanChars=400`,
`EpgBeforeMs=2h`, `EpgAfterMs=4h`, `MinHintLength=4`, `MinTokenLength=3`, `EpgMinTokenLength=4`.

### Reihenfolge in der Namens-Discovery (seit 13.10.)
Liga-Hints laufen **vor** den Turnier-Termen: ein Hint („Tennis") benennt die ligaeigenen Kanäle,
während ein Turnierwort („Masters") auch die Kanäle anderer Sportarten zieht und sonst den
`NameCandidateCap` zuerst füllt — der Hint käme nie dran. Verifiziert am echten Provider
(1146 Tennis-/PPV-Namen): ohne die Reihenfolge fiel die `Tennis`-Abfrage aus dem Cap.

### Fallstrick: EPG-Discovery darf NICHT am Namens-Cap hängen
`discoverCandidates` füllt zuerst Kanalnamen-Kandidaten bis `NameCandidateCap`. Die generischen
Teamwörter („City", „United") füllen diesen Cap schon nach wenigen Queries. Ein früherer Guard
`if (epgKeys.isNotEmpty() && out.size < CandidateCap)` **übersprang dann die komplette
EPG-Discovery** — und damit genau die premiumsender, die das Feature hervorholen soll.

Am 2026-10-12 am echten 57.360-Kanal-Xtream-Provider verifiziert: die Namens-Discovery erreichte
126 Kandidaten (Cap 120), EPG-Discovery wurde übersprungen, und das Spiel Coventry v Newcastle zeigte
nur die 6 Sender, deren **eigener Name** das Fixture trug (`AU (STAN 01) | Coventry City v
Newcastle United …`). Das Guide führte dasselbe Spiel auf ~57 Sendern ohne Teamwort im Namen
(`IT: SKY SPORT ARENA`, `CANAL+ FOOT`, `beIN SPORTS`, …) — exakt das, was Lume zeigt.

**Regel:** B (Guide) läuft **immer** und ist **nicht** durch A's Cap begrenzt. Home/away-Queries
sind verschränkt, damit der Cap beide Seiten gleich behandelt. Diesen Cap-Guard nicht wieder
einbauen.

## 5. Das Vokabular (`res/raw/sports_matching.json`)

Vier Schlüssel:

- `aliases` — gefalteter Team-Name → Alternativschreibungen, **deutsche Exonyme** und **Spitznamen**
  (Dortmund→BVB, Tottenham→Spurs, Barcelona→Barca, Napoli→Neapel, Milan→Mailand, Köln→Cologne,
  **Newcastle United→Magpies**, Man Utd→Red Devils …). Schlüssel werden über
  `SportsVocab.foldKey` = `SportNameMatch.normalize(...).trim()` gefaltet, Lookups sind also
  akzent-/case-insensitiv. Spitznamen sind für die Beide-Teams-Regel wichtig: ein Feed, der im Body
  nur den Spitznamen nennt (`… gegen die „Magpies"`), braucht den Alias, um beide Teams zu erfüllen.
- `teamStopwords` — generische Vereinsaffixe (fc, club, calcio), die aus Team-Tokens fallen.
- `broadcastStopwords` — Füllwörter (sky, sport, hd, tv), die beim Senderprobieren fallen, damit
  ein Broadcast wie „Sky Sport Bundesliga 1" nur „Bundesliga" als diskriminierendes Wort liefert.
- `sportWords` — Begriffe, die in einem EPG-`<category>` eine Sportart bezeichnen.

**Vokabular erweitern:** nur das JSON bearbeiten. Kein Code, keine Übersetzung. Neuen Alias einfach
als Schlüssel (ESPN-Name) → Liste hinzufügen.

## 6. Verhältnis zu Lume (Mappings)

| Lume | Sportio | Status |
| --- | --- | --- |
| `SportsMatcher.normalize` | `SportNameMatch.normalize` | portiert |
| `SportsTeamAliases.json` | `res/raw/sports_matching.json` → `aliases` | portiert (kleiner) |
| `tokens(forName:)` | `SportNameMatch.tokensForName` | portiert |
| Guide-Index über Titel/Untertitel/Beschreibung | `searchTitles` (Titel) + `searchGuide` (Titel **oder** Beschreibung) + `programmesForChannel` | portiert |
| `ResolvedChannelSource`-Tiers | **beide Teams Pflicht**; Titel-Treffer +10, Beschreibung +3, Name +4 (additiv statt Tiers) | angeglichen |
| `SportsChannelPicks` (gemerkter Sender) | — | Phase 3 |
| `epgCompetition`-Fallback (Tennis/F1/Golf) | `channelHints`/`extraChannelRes` (grob) | Phase 3 |

Ein Unterschied bleibt: Lume sortiert nach **Tiers** (`ResolvedChannelSource.rank`) und markiert nur
den alleinigen Top-Treffer als „confident" (Ein-Tap). Sportio bleibt bei **additiven Gewichten** und
einem Ein-Tap pro Kanalzeile. Die Beide-Teams-Pflicht ist seit 12.10. identisch.

## 7. Grenzen / bekannte Ecken

- **Team-Sportarten + Duelle** nutzen den Guide-Pfad (Tennis/MMA-Duelle seit 2026-10-13:
  beide Athleten wie beide Teams, darunter ein schwacher Recall-Fallback — ein Athlet mit
  Tennis-Kontext +2, Turnier allein +1; ein einzelner Nachname ohne Kontext zählt nie).
  Reine Event-Ligen ohne Gegner (F1/Golf) überspringen ihn weiter und laufen über
  Broadcasts/Hints. Siehe Phase 3.
- **Beschreibungs-Discovery (erledigt 12.10.).** Fremd-Feeds mit generischem Titel nennen die Teams
  nur im Body. `EpgDao.searchGuide` (`title LIKE OR description LIKE`, zeitfenster-begrenzt) findet
  sie; abgefragt werden nur **mehrwortige** Teamnamen (`epgDescriptionPhrases`), damit Einzelwörter
  die Prosa nicht fluten. Das war ein **Core-Fork-Punkt** (`core/…/dao/EpgDao.kt`).
- **Präzision statt Recall.** Die Beide-Teams-Pflicht (Lume) entfernt News-/Film-Sender, kostet aber
  Treffer: nennt ein Guide-Text nur ein Team (und keinen bekannten Spitznamen), fällt der Sender weg.
  Für Feeds mit generischem Titel ist ein gepflegter Alias (Spitzname/Exonym) damit entscheidend.
- **`LIKE`-Wildcards:** `searchTitles`/`searchGuide` binden den Token als Parameter in
  `'%' || :query || '%'`. Enthält ein Token `%` oder `_`, wirkt es als Wildcard. Team-/Broadcast-Namen
  tun das praktisch nie; wer das Vokabular erweitert, sollte es im Hinterkopf behalten.
- **Kein Sender-Pick gemerkt:** der zuletzt gewählte Sender pro Wettbewerb wird (noch) nicht nach
  oben sortiert.

## 8. Offen (Phase 3)

1. **`SportsChannelPicks`** — gewählter Sender pro Wettbewerb in `SportsPrefs`
   (`channel_picks`), Rank 0 (Basis 1000, wie Lume `pickScoreBase`). `channelKey` =
   `epgChannelId` sonst normalisierter Name (übersteht Re-Sync). `SportsViewModel.playMatch`
   schreibt den Pick; `match` sortiert ihn nach oben.
2. **Event-/Wettbewerbs-Fallback** — `SportLeague.competitionRes` (proper noun in
   `donottranslate.xml`) + Racing-Serie/Session; Programm on-air zum Anstoß, das die Phrase nennt,
   als unterster Tier (nie Ein-Tap). Deckt Tennis/F1/Golf ab. Lume löst das mit dem
   `epgCompetition`-Tier (`SportsChannelResolver+Competition.swift`). **Teils erledigt 2026-10-13:**
   Tennis/MMA-Duelle haben den Turnier-Tier (+1, `Fixture.tournament` aus dem ESPN-Event-Namen)
   und den Liga-Hint (`extraChannelRes`, Tennis → „Tennis"); F1/Golf weiter nur Hints.
3. **Beschreibungs-Discovery** — **erledigt 12.10.** (`EpgDao.searchGuide`, siehe Abschnitt 2/7).
4. **Beide-Teams-Pflicht** — **erledigt 12.10.** (Lume-Modell, siehe Abschnitt 4).

## 9. Tests / Verifikation

- **Pure Logik:** `app/src/test/java/tv/own/owntv/features/sport/SportNameMatchTest.kt` (15 Tests,
  grün) — Akzentfaltung, Ganzwort-Grenzen, Stopwort-Filter, `discoveryPhrases` (rohe Akzente),
  Alias-Lookup. Läuft ohne Android:
  `:app:testStandardDebugUnitTest --tests "tv.own.owntv.features.sport.SportNameMatchTest"`.
- **Matcher — erledigt 12.10., Duell-Tiers + Exact-Bypass 13.10.** `app/src/test/java/tv/own/owntv/features/sport/SportsMatcherTest.kt`
  (16 Tests, grün) nagelt die Regeln fest: (a) beide Teams im EPG-**Titel** → Treffer (10);
  (b) beide Teams über Titel/Beschreibung verteilt → Treffer (3); (c) **nur ein Team** → **kein**
  Treffer (das News-Channel-Regressionsnetz); (d) generischer Titel + Spitznamen-Alias (Magpies) →
  Treffer; (e) Kanalname mit beiden Teams → Treffer (4); (f) Sprachfilter **vor** dem Cap schneidet
  einen besser scorenden Fremdsprachen-Kanal weg; (g–n) Tennis-Duelle: beide Athleten im Titel (10),
  verteilt (3), ein Athlet ohne Kontext → kein Treffer, mit Tennis-Kontext (2), nur Turnier (1),
  Hint „Tennis" auf `US TENNIS PPV` ohne Guide (6), Doppel-Roster „A / B" (10), TBD-Seite mit
  Turnier-Fallback (1); (o–p) exakte Event-Kanäle (Duell wie Team) überleben einen fremden
  Sprachfilter (`isExact`). Läuft ohne Emulator:
  `:app:testStandardDebugUnitTest --tests "tv.own.owntv.features.sport.SportsMatcherTest"`.
  **Naht (Produktivcode, verhaltensneutral):** `SportsMatcher` holte seine Quellen über
  `SettingsRepository`/`SourceRepository` — **konkrete Klassen mit Android-`Context`** —, und das
  Projekt hat **kein** MockK/Mockito/Robolectric. Der Live-Playlist-Lookup (`liveSourceIds()`) liegt
  daher jetzt hinter `LiveSourceIdsProvider` (`features/sport/SportsSources.kt`): `SportsMatcher`
  nimmt diesen Provider statt der Repositories, Koin bindet `ProfileLiveSourceIdsProvider`
  (`di/AppModule.kt`), und der Test reicht eine Lambda hinein. `ChannelDao`/`EpgDao` bleiben
  Interfaces; der Test faked sie über einen `java.lang.reflect.Proxy` (nur die tatsächlich
  aufgerufenen Queries belegt, der Rest liefert „keine Zeilen").
- **Emulator (SportioTV):** gegen die echte Quelle öffnen; für das gewählte Spiel müssen die
  Fremdsprachen-Sender und die News-/Film-Fehltreffer fehlen, die Feeds der gewählten Sprache mit
  EPG-Titelzeile („20:55 · …") erscheinen.
- **i18n-Gate:** neue Kotlin-Literale (Regex, Log-Tag, Padding) via `classify-safe` eintragen; das
  JSON ist eine `raw`-Ressource und braucht nichts. Stand: `verify --bootstrap` grün.

**Verifikation 2026-10-12 (Emulator `SportioTV`, echte Xtream-Quelle, 57.360 Kanäle, 132.210 EPG-Programme):**
Für das Referenzspiel **Coventry City v Newcastle United** (Premier League, live) liefert die App nach
dem Fix **bis zu 20 Sender** im Detail-Pane (DefaultLimit), u.a. `SG: HUB PREMIER 1`, `IT: SKY SPORT ARENA`,
`FR: CANAL+ LIVE 1`, `FR: CANAL+ FOOT`, `FR: CANAL+ PREMIER LEAGUE`, `AL: SUPER SPORT 3`,
`SR: ARENA PREMIUM 1` — jeweils mit EPG-Titelzeile („Coventry City - Newcastle"). Vor dem Fix waren es
nur 4–6 Sender, deren **Name** das Fixture trug. Die In-Pane-Vorschau spielt live.
Kompilierstand: `:app:compileStandardDebugKotlin` + `:app:compileX86_64DebugKotlin` grün,
`:app:testStandardDebugUnitTest` (SportNameMatchTest) grün, i18n-`verify --bootstrap` grün.

**Verifikation 2026-10-12, Nachtrag (Lume-Modell + Beschreibungs-Discovery + Sprachfilter):** Gegen
dieselbe Quelle. Coventry v Newcastle, ohne Filter: die Top-Treffer sind die Titel-Treffer (SG/UK, `=10`);
darunter die Body-Treffer `DE: SKY SPORT PREMIER LEAGUE` und `DE: SKY SPORT TOP EVENT` (`=3`, beide Teams
über den `Magpies`-Alias). `DE: SKY SPORT NEWS`, `DE: SYFY`, `DE: DISNEY CHANNEL`, `DE: WARNER TV`,
`CH: Tele Zueri` sind **weg** — sie nennen nur ein Team (Beide-Teams-Pflicht). Bundesliga
Freiburg–Schalke: nur noch der echte `Freiburg vs Schalke …`-Feed (`=4`), `BREAKING BAD S04` weg.
Log-gestützt (`SportsHub`-Tag) geprüft.

## 10. Sprachfilter (2026-10-12)

Ein Fixture liefert Kanäle aus vielen Ländern (siehe Abschnitt 9: `IT`, `FR`, `AL`, `SR`, `SG`, …).
Der Nutzer versteht aber nur einen Teil davon. Deshalb trägt jede Kanalzeile im Detail-Pane jetzt
einen **Sprachfilter**, der aus dem **Länderkürzel am Namensanfang** abgeleitet wird
(`DE: SKY SPORT…`, `UK| SKY SPORTS…`, `FR/CANAL+…`) — kein EPG-Metadatum, nur der Name.

- **Abfrage:** Beim ersten Mal, sobald für ein Fixture Kanäle vorliegen, öffnet sich der
  Sprachdialog (`SportLanguageDialog`), sonst nie wieder. Erreichbar bleibt er über
  **Sport-Einstellungen → Sprache**. Die Wahl liegt in `SportsPrefs` (`preferred_languages`,
  zusammen mit `language_asked`), Default = Deutsch, leerer Satz = „Alle Sprachen".
- **Mapping** (`SportLanguages.CountryLanguages`): AT/DE/CH → Deutsch, UK/GB/US/SG/IE/AU/NZ/CA/ZA →
  Englisch, ES/MX/AR/CO/CL/PE/VE/UY → Spanisch, PT/BR → Portugiesisch, FR/BE → Französisch. Weitere
  Länder einfach in der Map ergänzen.
- **Filterregel** (`SportLanguages.filter`): Bei aktiver Auswahl bleibt **nur** ein Kanal, dessen
  Kürzel eine gewählte Sprache ist — mit einer Ausnahme: **exakte Event-Kanäle**
  (`ChannelMatch.isExact`, der Sendername nennt beide Teams/Spieler, z. B. ein Tennis-PPV
  für genau dieses Duell) passieren den Filter immer. Sie sind das Event selbst, kein
  fremdsprachiger Feed davon. Ein Kürzel einer anderen Sprache **und** ein Kanal mit
  unbekanntem/keinem Kürzel fallen weg — sonst füllen un-getaggte Fremdfeeds (`VIP: CANAL+…`,
  `SAT: ARENA…`, `8K: beIN…`) die knappen Plätze vor den echten Feeds. Ist das Ergebnis leer, wird
  die volle Liste gezeigt — der Pane ist nie leer.
- **Ort:** die Filterung passiert **im Matcher vor dem Cap** (`SportsMatcher.match(fixture,
  languages, limit)`), nicht mehr erst in der UI. Grund: die deutschen Feeds scoren niedrig (nur
  Beschreibungs-Treffer), die `take(20)`-Grenze ist aber schon mit Fremdsprachen-10ern gefüllt — ein
  Post-Cap-Filter sähe sie nie. `SportsViewModel` übergibt `preferredLanguages.value` und leert bei
  Sprachwechsel `matchesCache` + matcht das gewählte Fixture neu.
- **i18n:** neue Literale (`SportLanguages`-Länderkürzel + Regex, `SportsPrefs`-Keys) stehen in
  `tools/i18n/safe_literals.txt`.

Tests: `app/src/test/java/tv/own/owntv/features/sport/SportLanguagesTest.kt` (pure, ohne Android).

## 11. Sanfte Länder-Bevorzugung (Sky Sports vor US-Feeds)

Zusätzlich zum harten Sprachfilter gibt es eine **weiche** Rang-Präferenz
(„Prefer channels from", Sport-Einstellungen → Sprachen): die Sender des gewählten Landes sortieren
nach oben, alle anderen bleiben darunter gelistet — nichts wird ausgeblendet.

- **Ort:** `SportLanguages.isFromCountry(name, code)` + `SportsMatcher` wendet
  `SportsMatcher.PreferenceBoost` (+50, über jedem natürlichen Score) in `applyFilter` **vor** den
  Filter-Pässen an, aus demselben Grund wie der Sprachfilter: die bevorzugten Sender sollen die
  fixen Plätze gewinnen.
- **Signal:** zuerst das Länderkürzel des Providers (`UK| SKY SPORTS`); ein Kürzel eines *anderen*
  bekannten Landes blockiert den Marken-Fallback (`AT: SKY SPORT AUSTRIA` zählt nie als UK). Ohne
  Kürzel entscheiden Markenwörter (UK: `sky sport`/`tnt sport`/`bt sport`/`itv`/`bbc`;
  US: `espn`/`fox sport`/`cbs sport`/…; FR: `canal`/`rmc sport`/`bein`; AR: `bein`/`osn`/`ssc`/
  `alkass`/…; DE: `dazn`).
- **Optionen:** `SportLanguages.preferredCountryOptions` = UK, US, FR, AR, DE (+ keine). Default UK
  (`SportsPrefs.DefaultPreferredCountry`), weil das Matchday-Publikum Sky Sports vor ESPN+ sehen will.
- **Wirkung auf die Sprache:** keine — die Präferenz sortiert nur innerhalb dessen, was der
  Sprachfilter übrig lässt (Englisch-Filter + UK-Präferenz heben Sky Sports vor ESPN+; ein
  Deutsch-Filter lässt englische Sender weiterhin weg).
- Tests: `SportLanguagesTest` (Kürzel, Fremd-Kürzel-Blockade, Marken-Fallback, unbekannter Code)
  und `SportsMatcherTest.thePreferredCountryBoostsItsChannelsAboveTheRest` (UK über US, beide +10).
