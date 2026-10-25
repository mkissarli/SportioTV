# HANDOFF — nur Status-Zeiger (Stand: 2026-10-20)

> Diese Datei ist kein Wissensspeicher mehr. Verbindlich: `AGENTS.md` + `docs/INDEX.md`.
> Neues Wissen gehört nach `docs/`, nie hierher.

## Wo was steht

- Regeln/Gates/Verbote: `AGENTS.md`
- Doku-Landkarte: `docs/INDEX.md`
- Bauen/Emulator/Box: `docs/BUILD.md`
- Architektur/Core-Fork/TV-only: `docs/ARCHITECTURE.md`
- Naming-Freeze: `docs/BRANDING.md`
- Sport-Hub + Matching: `docs/SPORT-HUB.md` (Details: `sport-hub-plan.md`, `sport-channel-matching.md`)
- KI-Empfehlung: `docs/SPORT-AI.md`
- i18n: `docs/i18n-gate.md` (Guide: `docs/i18n.md`, Plan: `docs/internationalization.md`)
- Release/Signing: `docs/RELEASE.md`

## Status

- Sport-Hub Phasen 1–7 fertig (2026-10-12). App v1.0.1 / Core 1.0.68 released.
- v1.0.2 (2026-10-13): released — CI success, APKs live. Lokaler Core-Pin → 1.0.68 mitgezogen.
- v1.0.3 (2026-10-13): released — CI success, APKs live, Matching in APK verifiziert (sports_matching.json + Sprachfilter-Keys im Dex).
- Doku (2026-10-13): Release-Kette + Fork-Schutz + API-Fallen nach `docs/RELEASE.md`, Downgrade/Box-Ports nach `docs/BUILD.md`. Leak-Audit beider Repos: keine Secrets/Credentials/Keystores; Commits laufen nur unter Noreply-Identität.
- Umzug auf `trenntl` (2026-10-13): beide Repos mit Noreply-Identität neu aufgesetzt, Referenzen umgestellt, Core 1.0.69 neu publisht, App v1.0.5 live. Offen: alte Repos archivieren/löschen, Upstream-PRs (#254/#30) schließen.
- Handy-Verwaltung (Companion `/select`) seit 2026-10-13 dreistufig: Start (aktive Ligen
  mit Zähler) → Liga mit Tabs Teams/Spieler → „Alle verwalten" (Region-Akkordeons, Suche);
  ein Submit. Details: `docs/sport-hub-plan.md` §7.7.
- v1.0.6 (2026-10-13): released — Core 1.0.70 (Maven + Release) und App v1.0.6 (APKs live).
  CI beider Repos grün; Update-Kette gegen v1.0.5 geprüft: gleiche Signatur
  (CN=Sportio, SHA-256 identisch), versionCode 10005 → 10006.
- Arbeitsstand: Ordner hängt an `trenntl/SportioTV` (Backup-Branches vorhanden), Core frisch geklont (Build grün), READMEs + User Guide als Sportio neu, Naming-Freeze in `docs/BRANDING.md`.
- KI-Empfehlung (2026-10-19): Hybrid, Classic/AI-Tabs, harter Ländertag, Qualitäts-/
  Dubletten-Ranking und Länder-Gruppen; kein zweiter KI-Pass. Details: `docs/SPORT-AI.md`.
- Playlist-Import (2026-10-19): Provider-Nachricht aus dem Chat einfügen — KI liest sie,
  Bestätigungsseite am Handy, Einwilligung vor dem Senden. Details: `docs/PLAYLIST-IMPORT.md`.
- v1.0.8 (2026-10-20): released — Core 1.0.71 (Maven + Release) und App v1.0.8 (APKs live).
  KI-Empfehlung und Provider-Import sind hinter dem KI-Schalter (Standard aus); Key in den
  Einstellungen oder am Handy. CI beider Repos grün.
- v1.0.9 (2026-10-20): released — App-only, Core-Pin bleibt 1.0.71. `CountryTags` liest
  Provider-Tags in Balkenform (`┃DE┃`) und gemischtes Vokabular; Badges und Live-Filter teilen
  dieselbe Deutung, 37 Sprachen dazu, „All languages" ist ein Schalter. APKs live, CI grün.
- Sports-Hub-Matching (2026-10-25): Web-Guide ist nur noch Where's The Match (Day-Window,
  alle Sportarten), Beide-Teams-/Eventtitel-Matching, Broadcast-/Hint-Wörter nur noch Bonus,
  Regel-4-Dedup (`ChannelDedup`). F1-Hint („Sky Sports Main Event") entfernt — Kanäle kommen
  dynamisch aus Guide + Eventtitel. Details: `docs/SPORT-HUB.md`, `docs/sport-channel-matching.md` §13.
