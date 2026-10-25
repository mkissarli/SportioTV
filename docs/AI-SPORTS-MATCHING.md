# docs/AI-SPORTS-MATCHING.md — zweiphasiger KI-Senderabgleich (Stand: 2026-10-25)

> Zweck: beschreibt den automatischen, boardweiten KI-Abgleich der Sport-Hub-Spiele mit der
> eigenen Playlist — Ablauf, Datenschutz, Cache, Grenzen.

## Status

Umgesetzt 2026-10-25. Der Schalter **KI-Senderabgleich für Sport** steht in
Einstellungen → Gruppe **App** und ist für eine frische Installation **an**. Er läuft nur,
wenn zusätzlich die **KI-Funktionen** an sind und ein DeepSeek-Schlüssel hinterlegt ist.
Ohne Schlüssel oder bei ausgeschaltetem Schalter bleibt der deterministische Guide-/EPG-Pfad
unverändert und es geht kein Request raus.

Der alte KI-Tab (`SPORT-AI.md`) und die opt-in-Voll-Playlist-Prüfung pro Fixture
(`AI-PLAYLIST.md`) bleiben unabhängig bestehen. Der neue Abgleich ist keine Consent-Abfrage
pro Fixture; die Datenübertragung steht im Beschreibungstext des Schalters.

## Ablauf

1. Der Sport-Hub lädt die Bretter der aktiven Ligen. Sobald die Spiele des **lokalen Tages**
   (Gerätezeitzone) vorliegen, startet der Abgleich im Hintergrund; die UI wartet nie darauf.
2. **Phase 1 — Sender-Discovery:** ein kompakter Request mit allen Fixtures des Tages
   (ID, Sport, Wettbewerb, Saison, Teams/Kürzel, Titel, UTC-Anstoss, Session,
   ESPN-Broadcast-Namen, gewünschte Länder). Keine Playlist-Daten. Das Modell nennt
   Broadcaster, Kanal-Aliase, Markt und eine kurze Begründungskategorie.
3. **Lokal, parallel:** ein Index über **jede** Playlist-Zeile (einmal normalisiert) und
   breite EPG-Probes über das ganze Board-Fenster. Kandidaten entstehen aus der Vereinigung
   von Teilnehmer-/Event-/Wettbewerbsnamen, Phase-1-Aliasen, EPG-Programmen, Web-Guide,
   Broadcaster-Priors und Gruppen-/Kategorie-Kontext. Opaque Namen (`PPV`, `PPV 1`,
   `Event 1`, `Web Channel`) bleiben drin, wenn EPG, Gruppe oder ein anderer Kontext sie
   plausibel macht; ohne jeden Kontext werden sie nicht geraten.
4. **Phase 2 — Adjudication:** die Fixtures werden nach Anstossfenstern gruppiert und in
   wenigen, grössenbegrenzten Requests zusammen mit den Kandidaten (temporäre IDs,
   Anzeigename, Territorium, Gruppe, EPG-Fenster, Evidenz-Flags) verschickt. Das Modell
   wählt pro Fixture null oder mehr Kandidaten-IDs, beste zuerst. Leere Auswahl ist gültig.
5. **Rendering:** akzeptierte IDs werden erneut über `ChannelDao.getById` gelesen; fehlt die
   Zeile, hat sie einen anderen Namen oder gehört sie nicht mehr zu den Live-Quellen, fällt
   sie raus. Die Treffer gehen als zusätzliche Evidenz in `SportsMatcher.match`, der Guide >
   EPG/Name > KI-only sortiert, Länder/Priors als Tie-Breaker nutzt und Auflösungsvarianten
   dedupliziert. Die Fixture-Karte zeigt asynchron einen KI-Zähler.

## Antwortverträge

Phase 1 (`schemaVersion` 1):

```json
{"schemaVersion":1,"fixtures":[
  {"fixtureId":"soccer.eng.1#123","suggestions":[
    {"country":"GB","broadcaster":"TNT Sports","channelAliases":["TNT Sports 1"],"reason":"competition_rights"}
  ]}
]}
```

Phase 2 (request-scoped IDs):

```json
{"schemaVersion":1,"fixtures":[
  {"fixtureId":"soccer.eng.1#123","channels":[
    {"candidateId":"C00012","confidence":0.91,"reason":"exact fixture in EPG"}
  ]}
]}
```

Beide Parser sind versioniert, einheitentestbar und **entry-wise** tolerant: unbekannte
Fixture-IDs, unbekannte Kandidaten-IDs, ungültige Konfidenzen und doppelte Einträge werden
ignoriert. Eine ungültige Antwort fügt keinen Sender hinzu; sie kann keine Zeile erfinden.

## Grenzen und Strategie

- **Ein Discovery-Request** pro Board und Tag, danach nur bei geänderten Eingaben oder
  abgelaufenem Cache (6 h). Der Key besteht aus Tag, Fixture-Fingerprint, Sportarten,
  Ländern, Modell und Prompt-Version — ohne Playlist.
- **Phase 2:** Standardziel 5 Requests, höchstens 2 gleichzeitig, 180 000 Zeichen pro
  Request, 10 Fixtures und 400 Kandidaten pro Request. Ein Fixture liegt in genau einem
  Batch; Kandidaten werden runde(n)weise über die Fixtures verteilt, damit keins die Liste
  monopolisiert. Der Planner schätzt die Zeichen vor dem Senden und teilt zu grosse Fenster.
- **Cache:** Phase 2 hängt zusätzlich am Playlist-Fingerprint (ID, Name, EPG-Key), am
  Guide-Hash und an der Adjudication-Prompt-Version. Ein HTTP-/Parse-Fehler wird nicht als
  dauerhaftes „keine Sender“ gespeichert.
- **Keine Wiederholung** bei Recomposition, Fokuswechsel oder Rückkehr aus dem Vollbild;
  der Board-Lauf hängt an Brettern, Schaltern, Schlüssel, Ländern und Guide-Version und ist
  entprellt.

## Datenschutz

An DeepSeek gehen beim neuen Abgleich:

- Fixture-Metadaten (Teams, Kürzel, Wettbewerb, Saison, Anstoss, Session, ESPN-Broadcast,
  gewünschte Länder),
- Anzeigenamen der Playlist-Kandidaten, Territorium/Gruppe und die passenden
  EPG-Titel/Zeiten/kurzen Beschreibungen.

**Nicht** gesendet werden Stream-URLs, M3U-/XMLTV-Rohdaten, Zugangsdaten, Token,
MAC-Adressen, Xtream-Login-Daten oder Kontodaten. Der API-Key bleibt ein
`Authorization: Bearer …`-Header, nie in URL oder Log.

## Einstellungen

- **KI-Senderabgleich für Sport:** Einstellungen → **App**, Standard **an**. Der Text nennt
  die übertragenen Daten.
- **KI-Funktionen:** Master-Schalter, ebenfalls in **App**; Standard jetzt **an**
  (keine automatische Anfrage ohne Schlüssel, die manuellen Aktionen brauchen weiterhin
  einen Klick bzw. eine Einwilligung).
- **DeepSeek-API-Schlüssel:** unverändert in **App** und Sport-Einstellungen → KI.
- **Länder:** die bestehenden `ai_countries` plus das bevorzugte Land (`preferred_country`).

## Dateien

- `AiBoardMatching.kt` — Policy, Phase-1-/Phase-2-Prompts, Parser, Planner.
- `AiBoardMatcher.kt` — Playlist-/EPG-Index, breite Kandidatensuche, Concurrency, Cache,
  ID-Verifikation.
- `DeepSeekClient.kt` — `completeBoardMatching` (8 000 Max-Tokens, 120 s Read/180 s Call).
- `SportsViewModel.kt` — Tages-Fixtures, Hintergrundlauf, Treffer-Map, Merge in
  `SportMatchOptions.aiChannels`.
- `SportsPrefs.kt` — `ai_sports_matching` (Default an).
- `SettingsScreen.kt` — Schalter + Suchtreffer.
- `SportsScreen.kt` — KI-Zähler auf der Fixture-Karte.

## Tests

- `AiBoardMatchingTest` (12 Fälle): Discovery-/Adjudication-Parser inkl. unbekannter IDs und
  ungültiger Konfidenz, leere Auswahl, Batching/Request-Budget, Sport-Verteilung beim Tages-Cap,
  opake Kandidaten mit/ohne Kontext, URL-/Pipe-Bereinigung, Jugend-Firewall, End-to-End-ID-Mapping,
  Cache-Wiederverwendung, fehlender Schlüssel.
- Die bestehenden `SportsMatcherTest`, `PlaylistEvaluationTest` und `ChannelDedupTest`
  bleiben unverändert die Absicherung des deterministischen Pfads.

## Bekannte Lücken

- Ein echter DeepSeek-Lauf mit Key wurde hier nicht gemessen; die Gates für Precision/Recall
  gegen die Live-KI stehen aus.
- Sehr grosse Playlists (60k) kosten weiterhin einen vollständigen Index-Scan pro Board-Lauf
  (einmal normalisiert, nicht pro Fixture). Der Cache verhindert Wiederholungen.
- Die KI-only-Zeilen bleiben unter Guide und EPG; ein falscher Modelltreffer kann die Liste
  daher nie anführen, aber er kann als zusätzliche Zeile erscheinen.
