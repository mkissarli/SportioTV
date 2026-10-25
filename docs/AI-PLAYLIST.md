# docs/AI-PLAYLIST.md — Voll-Playlist-Prüfung per DeepSeek (Stand: 2026-10-25)

> Zweck: beschreibt die opt-in-Senderprüfung, die das komplette Live-Playlist-Katalog an DeepSeek
> gibt und nur validierte, lokal abspielbare Zeilen zurückzeigt.

## Status

Umgesetzt 2026-10-25. Zusätzlich pro Fixture nur nach ausdrücklicher Bestätigung; der
KI-Schalter steht seit 2026-10-25 standardmässig auf **an**, die Voll-Playlist-Prüfung bleibt
aber eine ausdrückliche Aktion. Der alte KI-Tab (Länder-Empfehlung, `SPORT-AI.md`) bleibt
unabhängig bestehen; die Voll-Playlist-Prüfung ist eine eigene Aktion in der Klassisch-Liste.

## Ablauf

1. Nutzer wählt ein Fixture und drückt **Ganze Playlist prüfen** (nur sichtbar, wenn die
   KI-Funktionen an sind).
2. Ein Dialog erklärt die Datenübertragung; erst **Senden und prüfen** startet den Request.
3. Der Analyst baut einen Katalog über **jede** abspielbare Zeile der Live-Quellen des aktiven
   Profils — inklusive `PPV`, `PPV 1`, `Event 1`, `Web Channel` und Zeilen ohne EPG.
4. Das Modell bekommt Eventfelder, relevanten Guide-/EPG-Text, Prior-Kontext und den Katalog.
   Es antwortet nur mit temporären IDs und einer Konfidenz.
5. Die App validiert die Antwort, mappt IDs auf die ursprünglichen Playlist-Zeilen, prüft, dass
   die Zeile noch existiert und denselben Namen hat, und vereinigt die Treffer mit Guide/EPG.

## Katalog und IDs

Eine Zeile pro Request-Kandidat:

```
C00012 | UK| TNT SPORTS 1 HD | territory=UK | epg=17:30-19:30 Chelsea v Tottenham Hotspur | prior=TNT Sports 1
```

- `C00001…` werden über den **kompletten** Katalog vor dem Batching vergeben; dieselbe ID bedeutet
  in jedem Batch dieselbe Zeile, und keine Zeile fällt durch ein Vorab-Filter weg.
- Der Anzeigename wird unverändert übernommen (nur Zeilenumbrüche/Tabs werden zu Leerzeichen).
- `territory` kommt aus dem bestehenden Provider-Tag, `epg` aus `programmeSummariesForChannels`
  im Fenster Anstoss −3 h … +4 h (max. zwei Programme pro Sender), `prior` nur wenn ein
  Prior-Alias den Namen nennt.
- **Nie** im Request: Stream-URLs, Zugangsdaten, Token, MAC-Adressen, Roh-M3U/XMLTV.

## Request-Strategie

- Modell: `deepseek-flash` (offizielle Doku, 1M-Token-Kontext). Nicht der alte Alias
  `deepseek-chat`.
- **Ein Request** mit dem ganzen Katalog, wenn die gemessene Kataloggrösse plus Prompt-Boilerplate
  ≤ 700 000 Zeichen ist (~230k Tokens bei konservativ 3 Zeichen/Token).
- Sonst **Fallback-Batches**: jede Zeile landet in genau einem Batch (≤ 400 000 Zeichen), nie in
  einer Stichwort-Shortlist. Danach werden die Batch-Antworten lokal vereinigt (Row-ID, beste
  Konfidenz zuerst, max. 10).
- JSON-Mode ist gesetzt; die Schema-Prüfung läuft trotzdem lokal.

## Antwortvertrag und Ablehnung

```json
{"decision":"select","channels":[{"id":"C00012","confidence":0.91,"reason":"…"}]}
{"decision":"none","channels":[],"reason":"Insufficient evidence"}
```

Abgelehnt wird die **ganze** Antwort bei: kaputtem/abgeschnittenem JSON, unbekannter `decision`,
mehr als 10 Treffern, doppelten IDs, einer ID ausserhalb **dieses** Batches, fehlender/nicht
numerischer Konfidenz oder Konfidenz ausserhalb 0…1. Eine abgelehnte Antwort fügt keine
KI-Sender hinzu. Ein gültiges `none` löscht keine Guide-/EPG-Treffer.

## Lokale Abbildung

`temporary candidate ID → playlist row ID → ChannelEntity`. Nach der Antwort wird jede gewählte
Zeile erneut über `ChannelDao.getById` gelesen; fehlt sie oder hat sie einen anderen Namen, wird
sie verworfen. Der `SportsMatcher` prüft die IDs zusätzlich beim Rendern gegen den aktuellen
Playlist-Scan. Angezeigt wird nur, was als Zeile existiert — keine Modell-URL, kein Modell-Name.

## Einwilligung, Cache, Fehler

- Kein Request ohne den expliziten Dialog. Der gespeicherte API-Key allein ist **keine**
  Einwilligung, alle Playlist-Namen zu senden.
- Erfolgreiche Auswahl **und** gültiges `none` werden gecacht. Schlüssel: Fixture-ID, Anstoss,
  Liga, Titel/Teams, Playlist-Fingerprint, Katalog-Hash (Namen + EPG), Guide-Hash, Guide-Version,
  Prior-Dataset-Version, Markt, Modell und Prompt-Version.
- Ein HTTP-/Timeout-Fehler wird **nicht** gecacht. Rate-Limit, Guthaben, Key, Netz und Serverfehler
  werden als typisierte `AiFailure` angezeigt; Guide-/EPG-Treffer bleiben stehen.
- Timeout: 120 s Read, 180 s Call pro Request. Der Aufruf läuft auf `Dispatchers.IO`.

## Ranking und Merge

- Evidenzklassen: Guide (eigene Reihenfolge) > EPG/Name (beide Teilnehmer bzw. Event-Session) >
  akzeptierte KI-only-Treffer.
- Innerhalb einer Klasse: Evidenz-Score, dann Prior-Reihenfolge, dann Länderwunsch (schwach).
- PPV-Demotion gilt weiter nur für Teamsport; bei Tennis/Events sind PPV-Feeds oft die Träger.
- Dedup: Auflösungsvarianten derselben Quelle verschmelzen, die beste Auflösung gewinnt.
  Quell-ID und Territorium bleiben Teil der Identität — gleichnamige Feeds verschiedener Provider
  werden nie zusammengelegt. `PPV`, `PPV 1`, `Event 1` bleiben eigenständig.

## Broadcaster-Priors

`res/raw/sports_broadcaster_priors.json` (Schema 1, Dataset-Version). Einträge nennen
`competitionId`, `aliases`, `seasons`, `markets` und eine geordnete Carrier-Liste. Ein Prior:

1. darf den Prompt-Kontext und das Ranking beeinflussen,
2. darf einen bereits bestätigten Sender nach oben ziehen,
3. darf nie Kanäle ausschliessen,
4. ist nie eine Bestätigung,
5. erzeugt ohne direkte Evidenz und ohne KI **keine** Zeile.

## Tests und Messung

- `AiPlaylistTest` — Katalog inkl. opaker Namen, Schema-Ablehnungen, Batching, ID-Mapping,
  Cache-Treffer/-Invalidierung, `none`, API-Fehler, Prior-Scope.
- `BroadcastGuideTest` — Guide-Parser, gated rows, Kurzformen (Tottenham/Brighton/M'gladbach),
  falsche Anstosszeit, ATP-Tour-Zeile, F1-Session, Fetch-Fenster ab heute.
- `SportsMatcherTest` — unabhängiger Guide+EPG-Merge, Prior-only leer, KI-Mapping, Evidenz-Ranking,
  EPG-Session-Guard.
- `ChannelDedupTest` — Quellen-/Territorium-Identität.
- `PlaylistEvaluationTest` — 115 synthetische, gelabelte Fixtures. Deterministisch: Precision 1.00,
  Recall 55/65 = 0.846, 0 FPs auf 60 leeren Labels. Mit **gestubbter perfekter** KI-Antwort für
  10 opake PPV-Fälle: Precision 1.00, Recall 1.00. Das prüft den Merge/ID-Pfad, nicht die
  Live-Modellgüte.

## Bekannte Lücken

- Ein echter DeepSeek-Lauf mit API-Key wurde hier **nicht** gemessen; die Release-Gates für
  Precision/Recall gegen die Live-KI stehen noch aus. Bis dahin bleibt die Funktion opt-in und
  zeigt KI-only-Zeilen nur nach expliziter Aktion.
- 60k-Sender-Playlists erzeugen im Batch-Fallback mehrere kostenpflichtige Requests; es gibt keinen
  automatischen Hintergrundlauf.
- Der Katalog sendet keine Bitrate/Codec-Daten; Qualität stammt weiter aus dem Provider-Namen.
- Es gibt keine serverseitige Token-/Kostenanzeige; geloggt werden nur Kandidatenzahl, Requestzahl,
  Gültigkeit, Zeichenzahl und Laufzeit.
