# docs/SPORT-AI.md — KI-Empfehlung im Sport-Hub (Stand: 2026-10-20)

> Zweck: DeepSeek-gestützte Sender-Empfehlung für ein Spiel, lokal gegen die eigenen Playlists geprüft.

## Status

Umgesetzt 2026-10-18, erweitert 2026-10-19 und 2026-10-20. Erster Provider: DeepSeek (`deepseek-chat`).
Die KI liefert nur **Sender-Namen**; abgespielt werden kann ausschließlich ein Kanal aus dem
lokalen Katalog. Die Treffer sind nach Land gruppiert, hart ländergefiltert und nach
Qualität/Dubletten sortiert. Seit 2026-10-20 gibt es einen **Schalter für die KI-Funktionen**;
seit 2026-10-25 steht er standardmäßig auf **an**, die manuellen Aktionen brauchen aber weiterhin
einen Klick. Der automatische Board-Abgleich hat einen eigenen Schalter
(`AI-SPORTS-MATCHING.md`).

## Änderungen 2026-10-20

- **KI-Schalter:** Einstellungen → Gruppe **App** → **KI-Funktionen** (Schalter, Chip *Ein* /
  *Aus*). **Ein ist der Standard**; die manuellen KI-Aktionen brauchen weiterhin einen Klick
  bzw. eine Einwilligung, der automatische Board-Abgleich steht zusätzlich unter
  `AI-SPORTS-MATCHING.md`. Der Schalter gilt für beide alten Funktionen (Sport-Hub-Empfehlung
  und KI-Auswertung beim Playlist-Import).
- **Der DeepSeek-Schlüssel ist jetzt auch in den normalen Einstellungen erreichbar:**
  Einstellungen → Gruppe **App** → Zeile **DeepSeek-API-Schlüssel** (Chip: *Hinterlegt* /
  *Nicht hinterlegt*) öffnet denselben Schlüssel-Dialog wie der Sport-Hub. Beide Wege
  schreiben denselben Key in `SportsPrefs.deepSeekApiKey` (DataStore `sports`).
  Die Key-Zeile ist nur sichtbar, wenn die KI-Funktionen eingeschaltet sind.
  Zusätzlich sind beide Zeilen über die Einstellungssuche auffindbar.
- Der Dialog der normalen Einstellungen kennt nur das Textfeld (kein „Am Handy eingeben“);
  diese Option bleibt dem Sport-Hub vorbehalten.
- **Sport-Hub bei ausgeschalteter KI:** der Tab **KI** in der Kanalliste existiert nicht mehr,
  die klassische Liste steht allein da. Der ViewModel verweigert zusätzlich jeden Aufruf
  (`AiFailure.DISABLED`), auch wenn die UI noch einen alten Stand zeigt.
- **Playlist-Import bei ausgeschalteter KI:** `SourceMessageAnalyzer` fragt nicht und meldet
  `DISABLED` — die Seite sagt „Die KI-Funktionen sind am TV ausgeschaltet …“ und zeigt die
  Werte der lokalen Regeln. Auch der Cache wird nicht mehr als KI-Ergebnis ausgegeben.
  Dafür gibt es in Core 1.0.71 den neuen Zustand `SourceAnalysisAiState.DISABLED`.
- Ein gespeicherter Key bleibt beim Abschalten erhalten; abgeschaltet wird nur die **Nutzung**.

## Änderungen 2026-10-19

- **Classic | AI** als Tabs im Kanäle-Bereich; die AI-Treffer erscheinen als normale
  vertikale Liste, nicht mehr als horizontale Leiste.
- **Harter Länderfilter:** `CA: DAZN 1` oder `HK: PREMIER SPORTS 1` fallen aus einer
  deutschen Anfrage heraus. Kanäle ohne Ländertag bleiben erlaubt.
- **Qualitätsranking:** 4K/UHD > FHD/1080 > HD/720 > unbekannt > SD/480/576;
  `MOBILE`/`LOW` stark abgewertet, `RAW` leicht, `TEST`/`BACKUP`/`24/7` verworfen.
- **Dubletten:** Qualitätsvarianten desselben Feeds belegen nur einen Platz; der beste
  Score gewinnt (`DAZN 1` vs. `DAZN 1 HD` vs. `DAZN 1 MOBILE`).
- **Qualitätsbadge:** Rechts in der Zeile steht `4K`, `FHD`, `HD`, `SD`, `MOBILE` oder
  `RAW`; bei unbekannter Qualität `Play`.
- **Länder-Gruppen:** Germany, Austria, United Kingdom, United States usw. getrennt;
  innerhalb jeder Gruppe bester Treffer zuerst.
- Die gemeldeten Fälle Real Madrid vs. Sevilla (`DAZN 1 MOBILE`, `CA: DAZN 1`,
  `HK: PREMIER SPORTS 1`) sind als Unit-Tests abgedeckt.

## Ablauf

Der AI-Tab existiert nur, wenn die KI-Funktionen eingeschaltet sind (Standard: **an** —
siehe Einstellungen).

1. Im Sport-Hub ein Spiel auswählen.
2. Im Kanäle-Bereich oben zwischen **Classic** und **AI** wählen.
3. Im AI-Tab auf **KI-Empfehlung** drücken und Länder im Popup auswählen: zweispaltiges
   Raster, **Search/Cancel fest rechts** statt OK am Ende einer langen Liste.
4. DeepSeek nennt für jedes Land wahrscheinliche Live-Sender des Spiels.
5. `AiChannelMatcher` sucht diese Namen in den Live-Playlists des aktiven Profils.
6. Die Treffer erscheinen als **normale vertikale Liste** im AI-Tab, nach Land gruppiert
   (z. B. Germany, Austria, United Kingdom, United States) und innerhalb jeder Gruppe nach
   Qualität/Match sortiert. Der Fokus aktualisiert die Vorschau oben; OK startet die Wiedergabe.
7. Beim Zurückkommen aus dem Vollbild bleiben Vorschläge und Vorschau erhalten; die Suche
   wird aus dem Cache geladen und **nicht** erneut bezahlt.
8. Im Vollbild zappt CH+/Hoch/Runter durch dieselbe KI-Liste (der Zap-Kontext wird mit den
   Vorschlägen befüllt). Links/Rechts bleibt dort Spulen.

Die Zahl der Vorschläge ist in den Sport-Einstellungen unter **KI** einstellbar
(5–50, Default 20). Die zuletzt gewählten Länder werden gespeichert. Ein Cache-Schlüssel
ist `Fixture + Länder`; ein neuer Request entsteht nur für eine neue Kombination.

## Einstellungen

- **KI-Funktionen (Schalter)**: Einstellungen → Gruppe **App** → **KI-Funktionen**.
  **Aus ist der Standard** und schaltet beide KI-Funktionen ab: die Empfehlung im Sport-Hub
  (der Tab *KI* verschwindet) und das KI-Lesen einer Provider-Nachricht beim Import (dort
  zählen nur die lokalen Regeln). Key `ai_enabled` in `SportsPrefs` (DataStore `sports`).
  Der gespeicherte Schlüssel bleibt erhalten — abgeschaltet wird nur die Nutzung.
- **DeepSeek API-Schlüssel**: Sport-Einstellungen → Tab **KI** → API-Schlüssel.
  Derselbe Key ist auch in den normalen Einstellungen erreichbar: Einstellungen → Gruppe
  **App** → **DeepSeek-API-Schlüssel** (nur sichtbar, wenn die KI-Funktionen an sind).
  Der Wert liegt im app-eigenen DataStore `sports` (`SportsPrefs.deepSeekApiKey`), nicht in
  den Core-Settings. Leerzeichen werden getrimmt; ein leerer Wert deaktiviert die Funktion.
- **Schlüssel am Handy eingeben**: Der Schlüssel-Dialog hat unter dem Feld **Am Handy eingeben**.
  Das öffnet QR + PIN der Companion-Seite (`CompanionMode.AI_KEY`, Core, Route `/apikey`); der
  Key wird am Handy eingefügt und erscheint danach im TV-Feld. Die Seite **füllt nur** —
  gespeichert wird erst mit **Speichern** am TV.
- **Vorschläge pro Anfrage**: eigener Key `ai_suggestion_limit`, unabhängig von
  `channel_limit` der normalen Kanalliste.
- **Länder**: `ai_countries` (ISO-Codes), Default `DE`.

Ohne Schlüssel bleibt der Button sichtbar; ein Klick erklärt das Fehlen und öffnet direkt
den KI-Tab der Sport-Einstellungen.

## Datenschutz und Netzwerk

- An DeepSeek gehen nur: Spielname, Wettbewerb, Anstosszeit, Spielort, die von ESPN
  gelieferten Broadcast-Namen (max. 8) und die gewählten Länder.
- **Nicht** gesendet werden (Länder-Empfehlung): Playlist-Namen, Kanalnamen, Kanal-IDs,
  Stream-URLs, EPG-Daten, Profil- oder Gerätekennungen.
  **Ausnahme (seit 2026-10-25):** die separate, pro Fixture ausdrücklich bestätigte
  **Voll-Playlist-Prüfung** sendet Eventdaten, relevanten Guide-/EPG-Text und die
  Anzeigenamen **aller** Playlist-Sender. Sie läuft nur nach dem Einwilligungsdialog
  („Ganze Playlist prüfen"), nie automatisch. Stream-URLs, Zugangsdaten und Token
  bleiben auch dort ausgeschlossen. Details: `AI-PLAYLIST.md`.
  **Zweite Ausnahme (seit 2026-10-25):** der automatische Board-Abgleich
  (`AI-SPORTS-MATCHING.md`) sendet dieselbe Art Metadaten (Fixtures, Kandidaten-Anzeigenamen,
  Territorium/Gruppe, passende EPG-Ausschnitte), aber nur wenn der eigene Schalter
  **KI-Senderabgleich für Sport** an ist; der Beschreibungstext ist die Offenlegung.
- Der API-Key wird ausschließlich als `Authorization: Bearer …`-Header übertragen, nie in
  einer URL und nie in einem Log.
- Prompt: `app/src/main/res/raw/sports_ai_prompt.txt` (technisch, nicht übersetzt).
- Die Antwort wird als JSON erwartet (`response_format: json_object`), zusätzlich werden
  Code-Fences und ein nacktes JSON-Array toleriert (`AiResponseParser`).

## Ausnahme: Playlist-Import

Beim Import einer Provider-Nachricht (siehe `docs/PLAYLIST-IMPORT.md`) gehen **Zugangsdaten** an
DeepSeek: Server, Nutzer und Passwort stehen in der Nachricht, die der TV auslesen lässt. Die
Regel oben — keine URLs, Playlist-Namen oder Zugangsdaten — gilt also ausdrücklich **nur** für
den Sport-Hub, nicht für diesen einen Fall. Dafür gilt dort:

- **Einwilligung:** Die Nachricht geht nur raus, wenn der Nutzer das auf der Companion-Seite
  bestätigt hat. Ohne das Häkchen wird nichts gesendet und nichts gefragt — auch nicht die
  lokalen Regeln (die Analyse ist ein Aufruf, der beides kombiniert).
- **Der Schlüssel** bleibt ein `Authorization: Bearer …`-Header, nie in einer URL, nie in einem Log.
- **Die Nachricht** wird nicht gespeichert und nicht geloggt; sie steckt nur im POST-Body der
  Companion-Seite und in deren Zwischenspeicher (eine Nachricht, bis der Import durch ist).
- **Einmalig:** Eine erfolgreiche Auswertung wird für diese Nachricht behalten (kein zweiter
  Aufruf, kein zweiter Preis). Ein **Fehlschlag** wird erneut versucht — der Schlüssel kann
  inzwischen gesetzt sein.
- **Nach der Auswertung** zeigt die Seite, was gelesen hat: die KI, die eigenen Regeln des TV,
  einen geratenen Wert oder den Fehlergrund (kein Schlüssel, Schlüssel abgelehnt, kein
  Guthaben, nicht erreichbar).

## Lokaler Katalog-Abgleich

`AiChannelMatcher` arbeitet nur auf den Live-Quellen des aktiven Profils
(`LiveSourceIdsProvider`):

1. Suche zuerst nach dem vollen Sender-Namen (`ChannelDao.searchList`).
2. Nur wenn das nichts findet: einzelne signifikante Tokens suchen und die Kandidaten
   gemeinsam bewerten.
3. Bewertung: exakter normalisierter Name (100), volle Phrase im Kanalnamen (90),
   Wort-Teilmenge (85), Token-Abdeckung ≥ 80 % (70+), schwache Abdeckung ≥ 60 % mit
   mindestens zwei Treffern (55).
4. **Harter Länderfilter:** Ein erkannter ISO-Ländertag muss zum KI-Land passen
   (`DE:` für Germany, `UK`/`GB` für United Kingdom). `CA: DAZN 1` oder
   `HK: PREMIER SPORTS 1` fallen aus einer deutschen Anfrage heraus. Kanäle ohne
   Ländertag bleiben erlaubt; Provider-Präfixe, die kein ISO-Land sind, zählen nicht.
5. **Qualitätsstufe aus dem Namen:** 4K/UHD > FHD/1080 > HD/720 > unbekannt > SD/480/576.
   `MOBILE`/`LOW` gibt eine starke Abwertung, `RAW` eine kleine. `TEST`, `BACKUP` und
   `24/7` werden ganz verworfen. Die Stufe steht als `4K`/`FHD`/`HD`/`SD`/`MOBILE`/`RAW`
   rechts in der Zeile.
6. **Dubletten zusammenfassen:** Qualitätsvarianten desselben Feeds (`DAZN 1`,
   `DAZN 1 HD`, `DAZN 1 MOBILE`) belegen nur einen Platz; der beste Score gewinnt.
7. Pro KI-Sender höchstens 4 Kanalvarianten. Die Auswahl wird rundenweise über die Länder
   verteilt und danach nach Land gruppiert, damit kein Land die Liste monopolisiert.
8. **Kategorie-Marker** wie `#### SKY SPORT ####` oder `### NEWS ###` werden verworfen:
   drei oder mehr `#` oder ein Name ohne Buchstabe/Ziffer ist kein Sender.
9. Sprachen aus **Immer ausblenden** bleiben ein hartes Nein, auch bei explizit gewähltem Land.

Damit werden typische IPTV-Schreibweisen zusammengeführt, ohne dass „Sky Sport Bundesliga“
auf „Sky Sport News“ passt. Der KI-Name allein erzeugt nie einen abspielbaren Eintrag.

## Gruppen und Reihenfolge

- Die Gruppen folgen der Länderreihenfolge des Katalogs (`AiCountry.entries`), aktuell
  Deutschland, Österreich, Schweiz, United Kingdom, Irland, USA, Kanada, …
- Bei den üblichen vier Ländern also: **Germany → Austria → United Kingdom → United States**.
- Innerhalb einer Gruppe sortiert der Score: Namensnähe, Ländertag-Bonus, Qualitätsstufe.
- Die Auswahl wird rundenweise über die Länder verteilt, damit kein Land das Limit allein
  verbraucht; danach wird die Liste nach Land gruppiert.
- Ein **Länderfilter** (nur ein Land gleichzeitig anzeigen) ist noch nicht eingebaut; die
  Gruppen sind der aktuelle Kompromiss. Eine andere Gruppenreihenfolge ist eine reine
  UI-Entscheidung.

## Bewusst nicht eingebaut

- **Kein zweiter KI-Pass im alten KI-Tab.** Die gemeldeten Fehler lassen sich lokal über
  Ländertag, Qualität und Dubletten lösen. Ein zweiter DeepSeek-Aufruf pro Fixture würde
  zusätzlich Geld und Zeit kosten. Der boardweite zweite Pass lebt getrennt im automatischen
  Abgleich (`AI-SPORTS-MATCHING.md`) und ersetzt diesen Tab nicht.
- **Keine echte Bitraten-/Auflösungsmessung.** Die Qualitätsstufe kommt aus dem
  Provider-Namen. Ein `HD`-Tag kann falsch sein; nur eine Probe oder Wiedergabe würde die
  echte Qualität zeigen. Eine solche Probe ist teuer, langsam und verbraucht Verbindungen.
- **Keine EPG-Bestätigung für AI-Kandidaten.** Der normale `SportsMatcher` prüft das EPG;
  der AI-Matcher nutzt bisher nur Name, Ländertag und Qualität. Ein Sender im richtigen
  Land kann also trotzdem nicht das Spiel zeigen. EPG-Bestätigung wäre der nächste lokale
  Genauigkeitsgewinn.
- **Ungetaggte Kanäle** bleiben erlaubt, weil viele Provider ihre Feeds nicht mit einem
  Ländercode versehen. Sie können daher weiterhin aus einem anderen Land stammen.

## Fehlerfälle

`AiRequestException` trägt einen typisierten `AiFailure`; die UI übersetzt ihn:

| Fall | HTTP / Ursache | Anzeige |
|---|---|---|
| `NO_KEY` | kein Schlüssel | Hinweis + Einstellungen öffnen |
| `INVALID_KEY` | 401/403 | Schlüssel abgelehnt |
| `INSUFFICIENT_CREDIT` | 402 | kein Guthaben |
| `RATE_LIMITED` | 429 | ausgelastet, erneut versuchen |
| `NETWORK` | IO / 5xx | nicht erreichbar |
| `EMPTY` | keine Sender im JSON | keine Sender geliefert |
| `DISABLED` | KI-Funktionen ausgeschaltet | nichts gesendet, kein Request |
| `UNKNOWN` | Parse-/Sonstiges | allgemeiner Fehler |

Die KI-Antwort ist eine **Empfehlung**, keine Garantie: DeepSeek hat keinen Live-Zugriff
auf Senderdaten und kann bei unbekannten Ansetzungen das übliche Wettbewerbsprogramm nennen.

## Dateien

- `DeepSeekClient.kt` — HTTP, Prompt-Aufbau, `AiResponseParser`.
- `AiChannelMatcher.kt` — Katalogsuche, Scoring, harter Ländertag, Qualität/Dubletten,
  Länder-Round-Robin und Gruppierung.
- `SportsPrefs.kt` — Schlüssel, Limit, zuletzt gewählte Länder, KI-Schalter (`ai_enabled`).
- `SportsViewModel.kt` — `AiState`, Request-Abbruch, Fixture+Länder-Cache, Classic/AI-Modus,
  `playAiSuggestion`, `aiEnabled`-Gate vor jedem Request.
- `SportsAiModels.kt` — `AiCountry`, `AiBroadcast`, `AiSuggestion`, `AiFailure` (inkl. `DISABLED`).
- `SportsScreen.kt` — Classic/AI-Tabs, Länder-Raster, vertikale AI-Liste, Vorschau-Fokus, KI-Tab,
  `DeepSeekKeyDialog` (Passwortfeld + „Am Handy eingeben") und `ApiKeyPhoneDialog` (QR/PIN).
- `features/shell/components/SettingsScreen.kt` — Schalter **KI-Funktionen** und zweite
  Fundstelle für den Schlüssel: Zeile in der App-Gruppe + Dialog (`TextInputDialog`), schreibt
  in dieselbe `SportsPrefs`.
- `features/setup/SourceMessageAnalyzer.kt` — KI-Gate: bei ausgeschaltetem Schalter wird nicht
  gefragt (`NOT_ASKED`), auch nicht aus dem Cache.
- Core-Fork: `CompanionMode.AI_KEY`, Route `/apikey`, `CompanionHtml.aiKeyPage` und
  `CompanionController.apiKeys` — die Gegenstelle der Handy-Eingabe.
- Playlist-Import (`docs/PLAYLIST-IMPORT.md`): derselbe Schlüssel wird für das Auslesen einer
  Provider-Nachricht benutzt — dort gehen (nach Einwilligung) Zugangsdaten an DeepSeek.

## Tests / Gates

- `SportsAiTest` (13 Fälle): JSON-Shape, Fences, Dedupe, voller Name, harter Ländertag,
  ungetaggte Kanäle, gemeldete CA/HK-Fehler, Qualitätsvarianten, Mobile-Fallback,
  Token-Fallback, Kurzmarke (ARD), Kategorie-Marker, Länder-Gruppierung, leere Live-Quellen.
- Abgedeckte Praxisfälle:
  - `DE: DAZN 1 HD` schlägt `DE: DAZN 1 MOBILE`.
  - `CA: DAZN 1` und `HK: PREMIER SPORTS 1` erscheinen bei einer deutschen Anfrage nicht.
  - Nur `DE: DAZN 1 MOBILE` vorhanden: bleibt als letzte Option mit `MOBILE`-Badge.
  - `DE: DAZN 1 4K` schlägt SD/Mobile-Varianten desselben Feeds.
- `:app:testStandardDebugUnitTest`
- `SourceMessageAnalyzerTest` — der abgeschaltete KI-Zustand wird zu `NOT_ASKED`.
- i18n `verify --bootstrap` (App) und `check_text_overflow.py`
- Core-Fork: `:core:testDebugUnitTest` und `tools/i18n/check_hardcoded_strings.py verify --bootstrap`
  (neue Literale in `safe_literals.txt`, `SupportedLocales.kt` regeneriert).
