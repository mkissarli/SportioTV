# docs/SPORT-DETERMINISTIC-MATCHING.md — EPG-unabhängiges Sender-Matching (Stand: 2026-11-01)

> Zweck: deterministische Sender-Identität, externe Broadcast-Evidenz, Tier-/Konfliktregeln
> und Messwerte des neuen Sport-Hub-Matchings. Kein KI-Pfad.

## 1. Ausgangslage

Vorher gab es zwei getrennte Wege:

- **Classic** (`SportsMatcher`): Kanalname + EPG (`EpgDao.searchTitles`/`searchGuide`) +
  Ligen-Hints/Priors. Ohne brauchbares EPG blieb oft nur ein Name-Treffer oder gar nichts.
- **DeepSeek-Match** (`AiBoardMatcher`): nutzte den Web-Guide (`BroadcastGuideApi`) als
  Evidenz, war aber an KI-Schalter und API-Key gebunden.

Der Web-Guide wurde also bereits geladen (30-min-Snapshot, Hintergrund-Refresh), aber
**nicht** vom deterministischen Matcher verwendet. Genau dort setzt diese Änderung an:
dieselbe, bereits vorhandene, keylose Quelle wird jetzt auch ohne KI und ohne EPG
ausgewertet.

Core bleibt unverändert (`tv.own.owntv:core` 1.0.71, `SportsMatcher` liegt in der App).
Keine neuen Tabellen, keine Migration, keine neue Bibliothek.

## 2. Pipeline

```
Fixture
  │
  ├─ A  Kandidaten-Discovery (bestehend)
  │     Kanalname (FTS, ein Scan) + EPG-Titel/Beschreibung (SQL, Zeitfenster)
  │
  ├─ B  Externe Broadcast-Listings (neu, deterministisch)
  │     BroadcastGuideApi-Snapshot → BroadcastListing
  │     → ChannelIdentityResolver (Playlist-Zeile ↔ Listing-Kanal)
  │     → BroadcastEvidence pro Treffer
  │
  ├─ C  Fusion
  │     EPG/Name-Kandidaten + externe Evidenz + akzeptierte KI-Zeilen
  │     → Evidenz-Tier (Supported/Likely/Possible/Conflicting)
  │     → Score, Dedup, Sprach-/Länderfilter, Cap
  │
  └─ D  Fallback (nur wenn C leer ist)
        Specialist-Channel → Possible (nie Supported, nur Teamsport)
```

Der Netzwerkzugriff bleibt vollständig im bestehenden `BroadcastGuideApi.refresh()`.
`match()` liest nur den letzten Snapshot: **kein Netzwerk auf dem UI-Pfad**, keine
Wartezeit beim Öffnen eines Fixtures. Ein neuer Snapshot bumpt `version`; der
`SportsViewModel` matcht das offene Fixture dann erneut.

## 3. Kanal-Identität (`ChannelIdentity.kt`)

`ChannelIdentityResolver.resolve(name)` liefert:

- `key` (`broadcaster|channel` oder `channel:<channel>`),
- kanonischen Broadcaster und Kanal,
- Territorium (führendes Tag oder Länder-Suffix),
- `SportClass`, `specialist`, `ChannelType` (Linear/PPV/Event/Regional),
- `IdentityBasis` (`EXACT`/`ALIAS`) und einen kurzen Grund.

Beispiele:

| Provider-Name | Key |
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

Regeln:

- Unicode-/Akzent-/Case-Faltung und das bestehende `ChannelDedup` entfernen Qualität,
  fps, Codec und Provider-Tags.
- Broadcaster werden als ganze Tokens erkannt (`BT Sport` → `TNT`), inkl. Phrasen
  (`Premier Sports`, `Prime Video`, `Canal Plus`).
- `+` bleibt ein Token (`Sky Sports+` ≠ `Sky Sports`).
- `sports` unterscheidet nur bei Sky/TNT die mehrdeutigen Namen (`News`, Nummern);
  bei eindeutigen Sportnamen (`Main Event`, `F1`, `Football`) ist es Kürzel.
- Keine Substring-/Präfix-Fuzzy-Regel: `Sky Sports 1` matcht nie `Sky Sports 2`.
- Ein Netzwerkname (`Sky Sports` → `sky|network`) matcht nie einen Einzelkanal.
- Fehlt einer Seite der Broadcaster (`Main Event`), ist die Beziehung `INFERRED`,
  nicht `EXACT`.

Listenings werden über dieselbe Identität aufgelöst. Zuerst exakte Keys aus einem
kleinen In-Memory-Index (nur ≤ 8.000 Kandidatenzeilen), dann eine begrenzte
FTS-Abfrage pro Listing und immer die Identitätsprüfung. Ein Listing kann damit
keinen fremden Sender „borgen“.

## 4. Broadcast-Listings und Evidenz

`BroadcastListing` (normalisiert): Event, Sport, Wettbewerb, Teilnehmer, Event- und
Coverage-Zeit, Broadcaster/Kanal, Territorium, Sprache, `CoverageType`, Quelle,
Abrufzeit, Confidence, `direct`. Fehlende Felder sind fehlende Evidenz, kein Match.

`CoverageType` trennt:

- `FIXTURE`/`SESSION`/`EVENT_CARD` → direkt,
- `TOURNAMENT` → nie direkt (ATP/WTA-Tageszeile).

`BroadcastEvidence` trägt zusätzlich `underlyingKey`. Damit wird dieselbe
Broadcast-Information aus zwei Feeds oder abgeleiteten Pfaden nur einmal gezählt.

### Tier-Regeln (`EvidenceFusion`)

| Evidenz | Tier |
|---|---|
| frisches, direktes Fixture-/Session-Listing | `Supported` |
| direktes Listing, aber älter als 30 min | `Likely` |
| Turnier-Ebene + Specialist-Kanal | `Likely` |
| Turnier-Ebene + allgemeiner Kanal | `Possible` |
| Specialist-Identität, Rechte-Prior, Playlist-Kontext allein | `Possible` |
| zwei direkte Quellen, gleicher Kanal, verschiedene Events, überlappende Zeit | `Conflicting` |

Ein Broadcaster-Recht oder eine Specialist-Klassifikation erreicht **nie**
`Supported`. Directe externe Evidenz kann ohne EPG `Supported` werden. Stale Evidenz
überschreibt keine frische direkte Evidenz; beide bleiben im Datensatz und der
Konflikt wird erklärt (`EvidenceFusion.explain`).

Score = Summe der Evidenzstärken × Freshness, dedupliziert, auf 300 begrenzt.
Nutzerpräferenzen (Land, Sprache, Playlist) kommen erst danach als Sortier-/Filter-
Signale dazu und erzeugen keine Match-Confidence. Eine **direkte externe** Zeile
(`directEvidence`) überlebt den bevorzugten Sprachfilter — eine Präferenz darf einen
Sender, der das Event nachweislich zeigt, nicht verstecken. Für EPG/Name-Zeilen
bleibt die bisherige Sprachfilter-Regel unverändert (bestehende Tests).

### Deterministische EPG/Name-Tiers

- Beide Teams/Athleten im EPG-Titel oder verteilt in einem Programm → `Supported`.
- Beide im Kanalnamen (`isExact`) oder nur Turnier/Ein-Athlet-Kontext im EPG →
  `Likely`.
- Event-Hint allein (F1/Golf) → `Possible`.
- Diese Tiers geben **keinen** zusätzlichen Klassen-Bonus; die historische
  Score-Reihenfolge (Name 4 vor Beschreibungs-EPG 3) bleibt erhalten. Nur externe
  Guide-Zeilen und akzeptierte KI-Zeilen bekommen den Evidenz-Bonus.

## 5. Sport-spezifisches Verhalten

- **Teamsport** (Fußball, NFL/College, NBA, MLB, NHL, Rugby, Cricket): externe
  Zeile muss beide Seiten nennen (`GuideMatch.bothSidesInRow`), Zeitfenster 90 min.
- **Tennis/Duelle**: beide Athleten in einer Zeile → direkt. Die ATP/WTA-Tageszeile
  ist `TOURNAMENT`, also höchstens `Likely` (Specialist) und nie `Supported`.
- **F1**: Session-Wörter müssen passen; ein Practice-Fixture erbt nie die Renn-Zeile.
  Sky-Sports-F1-Identität bleibt Specialist.
- **Golf**: Event-Titel-Token; Turnier-/Rundenzeilen bleiben von Einzelgruppen
  getrennt (keine Gruppen-Fantasie).
- **UFC/Boxing**: Event-Card, PPV bleibt PPV (kein Team-PPV-Demote).
- **Andere Sportarten**: `SportClass` deckt Cricket, Rugby, Cycling, Athletics,
  MotoGP ab; neue Sportarten brauchen nur eine Liga-Zuordnung und ggf. Suchbegriffe.

## 6. Externe Quellen: Bewertung

| Quelle | Status | Begründung |
|---|---|---|
| Where's The Match (`BroadcastGuideApi`) | **integriert** | keylos, ein Seitenabruf für alle Sportarten, exakte UTC-Zeiten, Senderzeilen, 30-min-Cache, defensiver Parser |
| Live Football On TV | nicht integriert | keine strukturierte Schnittstelle, gleiche Abdeckung wie WTM für Fußball, zusätzlicher Scraper ohne Messvorteil |
| Offizielle Broadcaster-Pläne | nicht integriert | pro Sender eigene HTML-Struktur, Terms/Rate-Limits, hoher Wartungsaufwand; nur als späterer, quellenspezifischer Adapter sinnvoll |
| Jolpica/F1-Kalender | bereits vorhanden (`F1ScheduleApi`) | liefert Fixtures, keine Sender; bleibt getrennt |
| Paid APIs/API-Keys | abgelehnt | nicht erforderlich, widerspricht den Projektregeln |

Der Snapshot wird bei Fehlern **nicht** geleert: ein alter Snapshot ist bessere,
klar als stale markierte Evidenz als „kein Sender“. Gated WTM-Zeilen sind fehlende
Sender-Evidenz, kein „läuft nicht“.

## 7. Performance und Fire TV

- Kein neuer Voll-Scan pro Fixture: Kandidaten kommen aus FTS/EPG; externe Listings
  werden per FTS + Identitätsprüfung aufgelöst (Cap: 12 Listings, 120 Kanäle).
- Der In-Memory-Identitätsindex wird nur für ≤ 8.000 Kandidatenzeilen gebaut.
- Der bestehende 30-s-Scan-Cache (≤ 20.000 Zeilen) bleibt unverändert; größere
  Playlists werden bewusst nicht im RAM gehalten.
- Kein neues DB-Schema, keine neue DB-Engine, keine Bildverarbeitung, kein
  Hintergrund-Polling.
- Guide-Netzwerk läuft weiterhin nur im vorhandenen Hintergrund-Refresh.

JVM-Smoke-Benchmark (`DeterministicMatchingBenchmarkTest`, Windows-Desktop, kein
Fire-TV-Gerät):

| Fall | Messwert |
|---|---|
| 10.000 Namen identifizieren | 156 ms |
| 30.000 Namen identifizieren | 196 ms |
| 60.000 Namen identifizieren | 234 ms |
| 1.000 Identitäts-Lookups (60k-Set) | 4 ms |
| Fixture-Match, 30.000 Kanäle, Guide-Treffer | 200 ms |

Das sind JVM-/CI-Zahlen, **keine** Gerätezahlen. Die geforderten P95-Budgets
(< 250 ms cached match, < 10 ms Identitäts-Lookup, < 500 ms erste Darstellung)
sind auf dem Desktop eingehalten; ein Lauf auf Fire TV Stick / 4K Max steht aus
(kein Gerät in dieser Umgebung). `minSdk` ist 26, keine neuen Abhängigkeiten.

## 8. Tests

- `ChannelIdentityTest` — Beispielnamen, `Sky Sports+`/Nummern/News-Abgrenzung,
  generische Wörter, Territorium/Sport/PPV, Rebrandings, Suchterme.
- `BroadcastEvidenceTest` — Tier-Regeln, Stale-Downgrade, Konflikt, Dedup,
  Score-Deckel.
- `SportsMatcherTest` — Guide ohne EPG → `Supported`; Tennis-Turnierzeile →
  `Likely`; allgemeiner Kanal → `Possible`; EPG+Guide-Merge; kein Borrowing;
  Länderpräferenz versteckt starke Evidenz nicht; Fallback nur `Possible`.
- `DeterministicMatchingBenchmarkTest` — 10k/30k/60k-Identitäten und 30k-Match.
- `SportMatchingQualityTest` — 8 gelabelte Szenarien (EPG, Guide ohne EPG,
  Specialist-Fallback, Tennis-Turnierzeile, Sprachfilter, F1-Rennen/Practice,
  Ein-Team-EPG): **Precision@1 6/6**, **No-Result 2/2**. Der Vergleich zum alten
  Pfad ist dort direkt sichtbar: ohne Guide/EPG liefert das Szenario „direct guide
  without EPG“ früher nichts, jetzt den richtigen Sender; das Practice-Szenario
  bleibt bewusst leer (kein Borrowing).

Ausführen:

```
./gradlew :app:testStandardDebugUnitTest
```

## 9. Grenzen und Follow-up

- **Nur eine externe Quelle.** Weitere Quellen erst nach messbarem Precision-Gewinn
  und mit quellenspezifischem Adapter; keine unkontrollierten Scraper.
- **Playlist-Clustering** (Nachbarschaft, Gruppe, Logo, Sequenz) ist bewusst nicht
  implementiert: schwache Signale, kein belegter Precision-Gewinn, teils teure
  Bildverarbeitung. Die Identitäts-Key-Idee ist die Voraussetzung dafür.
- **Historisches Feedback** ist nicht verdrahtet. „Stream geöffnet“ ist kein Beweis;
  nur eine explizite Nutzerbestätigung dürfte Evidenz erzeugen. Der Datenspeicher
  dafür gehört in die bestehende `SportsPrefs`-DataStore-Datei (Backup-kompatibel).
- **Fire-TV-Hardwaremessung** und ein echter Precision-/Recall-Lauf gegen mehrere
  reale Provider stehen aus. Der Benchmark ist deterministisch, aber synthetisch.
- **Konflikt-Erkennung** deckt aktuell widersprüchliche direkte Listings ab. Ein
  EPG-Programm, das eine andere Veranstaltung nennt, wird noch nicht als Konflikt
  modelliert (nur als fehlende EPG-Evidenz).
- **Kein UI-Umbau.** Die bestehende Evidenzzeile zeigt jetzt zusätzlich
  `TV guide`/`Channel identity`; D-Pad-Navigation und Layout sind unverändert.
