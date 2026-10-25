# docs/AI-SPORTS-MATCHING.md — DeepSeek-Senderabgleich (Stand: 2026-10-25)

> Zweck: beschreibt den evidenzbasierten DeepSeek-Abgleich der Sport-Hub-Spiele mit der
> eigenen Playlist — Suche, Kandidaten, Prüfung, Cache und Grenzen.

## Status

Umgesetzt 2026-10-25 als dritte Kanalspalte **DeepSeek Match** neben **Classic** und
**AI Suggest**. Der Abgleich läuft nur, wenn die KI-Funktionen und **KI-Senderabgleich für
Sport** an sind und ein DeepSeek-Schlüssel hinterlegt ist. Ohne Schlüssel oder bei
ausgeschaltetem Schalter bleibt Classic unverändert und es geht kein Request raus.

Classic und AI Suggest bleiben unabhängig. DeepSeek-Zeilen werden nie in Classic gemischt;
Classic-Zeilen werden nie in DeepSeek gemischt.

## Ablauf

1. Der Nutzer öffnet ein Fixture und wechselt auf **DeepSeek Match**. Erst dann startet der
   Lauf; der ganze Spieltag wird nicht im Hintergrund adjudiziert.
2. **Phase 1 — Discovery:** ein kompakter Request für dieses Fixture (ID, Sport,
   Wettbewerb, Saison, Teams/Kürzel, Titel, UTC-Anstoss, Session, ESPN-Broadcast-Namen,
   höchstens sechs Länder). Keine Playlist-Daten. Das Modell nennt Broadcaster,
   Kanal-Aliase, Markt und eine kurze Begründungskategorie.
3. **Lokal:** ein Index über **jede** Live-Playlist-Zeile und breite EPG-Probes im
   Anstossfenster. Kandidaten entstehen aus Teilnehmer-/Event-/Wettbewerbsnamen,
   Phase-1-Aliasen, EPG-Programmen, Web-Guide, Broadcaster-Priors und Gruppenkontext.
   Opaque Namen (`PPV`, `Event 1`) bleiben nur mit EPG-, Gruppen- oder anderem Kontext.
4. **Suche:** eine DeepSeek-Search-Anfrage pro geöffnetem Fixture, mit einer kurzen
   Fallback-Query nur bei leerem Ergebnis. Danach extrahiert ein kleiner JSON-Call
   Broadcaster-Namen **aus den Suchergebnissen**. Jeder Name muss mit `sourceIndex` und
   einem wörtlichen `excerpt` belegt sein; erfundene Namen fallen raus. Die Namen werden
   gegen den vollständigen Playlist-Index gematcht und als `SEARCH`-Kandidaten ergänzt.
   Damit kann Suche eine Zeile finden, die Phase 1 und EPG verpasst haben.
5. **Phase 2 — Adjudication:** ein Request mit dem Fixture, den Kandidaten (temporäre ID,
   Anzeigename, Territorium, Gruppe, EPG-Fenster, Flags) und den Suchergebnissen. Das
   Modell wählt Kandidaten-IDs und liefert Evidenz-Typ, Quelle, `sourceIndex`/`excerpt`
   oder `rights`, Konfidenz und Grund. Leere Auswahl ist gültig.
6. **App-Prüfung:** eine Modellbehauptung ist nicht die Bestätigung.
   - `SEARCH`: `sourceIndex` muss auf ein echtes Ergebnis zeigen; der Excerpt muss im
     Resultattext vorkommen. Sonst wird `supported` zu `likely`.
   - `EPG`: die App braucht ein exakt passendes Programm, das sie selbst im Fenster
     gefunden hat. Ohne das wird `supported` zu `likely`.
   - `RIGHTS`: kann nie `supported` werden, höchstens `likely`.
   - `CONFLICTING`: bleibt widersprüchlich und wird nicht als bestätigt gezählt.
7. **Rendering:** akzeptierte IDs werden erneut über `ChannelDao.getById` gelesen; fehlt die
   Zeile, hat sie einen anderen Namen oder gehört sie nicht mehr zu den Live-Quellen, fällt
   sie raus. Die Spalte sortiert `Supported` → `Likely` → `Possible` → `Conflicting`.
   `Possible` und `Conflicting` stehen unter eigenen Überschriften.

## Antwortverträge

Phase 1 (`schemaVersion` 1):

```json
{"schemaVersion":1,"fixtures":[
  {"fixtureId":"soccer.eng.1#123","suggestions":[
    {"country":"GB","broadcaster":"TNT Sports","channelAliases":["TNT Sports 1"],"reason":"competition_rights"}
  ]}
]}
```

Such-Extraktor:

```json
{"schemaVersion":1,"channels":[
  {"name":"Sport Klub 5","aliases":["Sport Klub"],"country":"HR","sourceIndex":2,"excerpt":"Sport Klub 5"}
]}
```

Phase 2 (`sourceIndex` nur bei `source:"search"`):

```json
{"schemaVersion":1,"fixtures":[
  {"fixtureId":"soccer.eng.1#123","channels":[
    {"candidateId":"C00012","evidence":"supported","source":"search","sourceIndex":0,
     "excerpt":"Premier Sports 1 ... 20:00 UTC","confidence":0.95,"reason":"listing"}
  ]}
]}
```

Erlaubte Tiers: `supported`, `likely`, `possible`, `conflicting`. Erlaubte Quellen:
`search`, `epg`, `rights`. `0.00` und `EXCLUDED`-Zeilen sind ungültig; hart
ausgeschlossene Sender fehlen ganz. Beide Parser sind versioniert, einheitentestbar und
entry-wise tolerant: unbekannte Fixture-/Kandidaten-IDs, ungültige Konfidenzen und
Duplikate werden ignoriert. Eine ungültige Antwort fügt keinen Sender hinzu.

## Evidenzregeln

- `Supported`: exakte Suche oder exaktes EPG-Programm für Event, Datum, Session und
  dieselbe Kanalidentität. Markt und Feed-Version müssen passen.
- `Likely`: generisches EPG-/Wettbewerbsfenster oder Rechtewissen ohne exakten Beleg.
- `Possible`: kein event-spezifischer Beleg; standardmäßig enthalten, außer hart
  ausgeschlossen.
- `Conflicting`: glaubwürdige Quellen widersprechen sich für denselben Kanal/Zeitpunkt.
  Dann nicht `supported`.
- Eine konkrete Suche kann ein generisches EPG überstimmen. Widerspricht sie einem
  konkreten EPG-Programm desselben Kanals/Zeitpunkts, wird das als `conflicting` markiert,
  nicht automatisch überschrieben.
- Verschiedene Märkte/Versionen sind getrennte Zeilen, kein Widerspruch. Die Zeile behält
  ihren tatsächlichen Markt; ein US-Sender macht aus einer UK-Zeile keine US-Zeile.
- `Supported` beschreibt nur die Broadcast-Evidenz — nicht, ob der Stream in der Region
  läuft oder abspielbar ist.

## Suche

- `POST https://api.deepseek.com/v1/tools/search`, bearer Auth, `count=5`,
  `need_content=true`, Timeout 30 s Read/45 s Call.
- Query 1–128 Zeichen. Der Builder kürzt zuerst den Wettbewerb, dann das Event; das Datum
  bleibt erhalten. Eine Fallback-Query wird nur bei leerem Ergebnis gesucht.
- Authority/Rerank sind Sortierhinweise, keine harten Filter. Spezialisierte
  TV-Listings bleiben erhalten.
- `need_content` kann leer sein; fehlender Inhalt ist fehlende Evidenz, kein Beleg.
- `POST /v1/tools/fetch` ist als optionaler Fallback vorhanden, aber nicht der Normalfall.
- Suchergebnisse werden lokal als Evidenz gehalten. URLs gehen nie an den Player.

## Cache und Kosten

- Phase 1: 6 h pro Fixture-Fingerprint.
- Suche: 30 min pro Query.
- Extraktor: 6 h pro Fixture + Suchergebnis-Hash.
- Phase 2: 6 h pro Fixture + Playlist-/Guide-/Such-Hash; ein HTTP-/Parse-Fehler wird nicht
  als dauerhaftes „keine Sender“ gespeichert.
- Ein Fixture liegt in genau einem Phase-2-Request. Keine Wiederholung bei Recomposition,
  Fokuswechsel oder Rückkehr aus dem Vollbild; der Lauf hängt an Fixture, Schaltern,
  Schlüssel, Ländern, Guide-Version und ist entprellt.
- Search kostet laut DeepSeek $0.75 pro 1.000 erfolgreiche Anfragen; Modell-Tokens kommen
  dazu.

## Datenschutz

An DeepSeek gehen:

- Fixture-Metadaten (Teams, Kürzel, Wettbewerb, Saison, Anstoss, Session, ESPN-Broadcast,
  gewünschte Länder),
- Anzeigenamen der Playlist-Kandidaten, Territorium/Gruppe und passende
  EPG-Titel/Zeiten/kurze Beschreibungen,
- bei der Suche nur die Fixture-Query, keine Playlist.

**Nicht** gesendet werden Stream-URLs, M3U-/XMLTV-Rohdaten, Zugangsdaten, Token,
MAC-Adressen, Xtream-Login-Daten oder Kontodaten. Der API-Key bleibt ein
`Authorization: Bearer …`-Header, nie in URL oder Log.

## Einstellungen

- **KI-Senderabgleich für Sport:** Einstellungen → **App**, Standard **an**. Der Text nennt
  die übertragenen Daten.
- **KI-Funktionen:** Master-Schalter, ebenfalls in **App**; Standard **an**.
- **DeepSeek-API-Schlüssel:** in **App** und Sport-Einstellungen → KI.
- **Länder:** die bestehenden `ai_countries` plus das bevorzugte Land; Phase 1 kappt auf
  sechs Märkte.

## Dateien

- `AiBoardMatching.kt` — Policy, Phase-1-/Phase-2-Prompts, Parser, Planner, Evidenz-Tiers.
- `AiBoardMatcher.kt` — Playlist-/EPG-Index, Kandidatensuche, Suche, Extraktor-Merge,
  App-seitige Evidenzprüfung, Concurrency, Cache, ID-Verifikation.
- `SportsSearch.kt` — Query-Builder (128 Zeichen), Search-Hit-Modell,
  Extraktor-Prompt/-Parser, Excerpt-Grounding.
- `DeepSeekClient.kt` — Chat, Search und Fetch; getrennte Timeouts und Fehler-Mapping.
- `SportsViewModel.kt` — Lazy-Lauf für das geöffnete Fixture, Treffer-Map, Merge in
  `SportMatchOptions.aiChannels`.
- `SportsMatcher.kt` — Classic-Evidenz, Classic-Reihenfolge, Dedupe, AI-only-Pfad.
- `SportsScreen.kt` — drei Tabs, Evidenz-Labels, Possible-/Conflicting-Überschriften.
- `SportsPrefs.kt` — `ai_sports_matching` (Default an).

## Tests

- `AiBoardMatchingTest` — Discovery-/Adjudication-Parser, Suche-zu-Playlist-Merge,
  Excerpt-Grounding, `supported`-Downgrade bei erfundenem Excerpt, Rechte-only,
  exaktes EPG, Batching, opake Kandidaten, Jugend-Firewall, End-to-End-ID-Mapping, Cache.
- `SportsSearchTest` — 128-Zeichen-Query mit erhaltenem Datum, Tennis-Teilnehmer,
  Extraktor-Grounding und ungültige `sourceIndex`/Excerpts.
- `SportsMatcherTest`, `PlaylistEvaluationTest`, `ChannelDedupTest` bleiben die
  Absicherung des deterministischen Classic-Pfads.

## Bekannte Lücken

- Ein echter DeepSeek-Lauf mit Key und die Precision-/Recall-Messung gegen Classic und
  AI Suggest stehen noch aus. Testset: Fußball, Tennis, F1 (Rennen/Qualifying/Practice).
- Der Extraktor prüft, dass ein Name im Suchergebnis vorkommt; er prüft nicht, ob die
  Quelle den Stream in der Region tatsächlich anbietet.
- Sehr große Playlists kosten weiterhin einen vollständigen Index-Scan pro geöffnetem
  Fixture (einmal normalisiert). Der Cache verhindert Wiederholungen.
- Ein Suchtreffer kann als `possible` erscheinen, wenn der Extraktor keinen belastbaren
  Namen liefert; dann bleibt die Zeile eine schwache Empfehlung.
