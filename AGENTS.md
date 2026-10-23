# AGENTS.md — verbindlich für alle Agents

Matchday (persönlicher SportioTV-Fork, TV + Sports). Sprache mit dem User: Englisch.
Antworten kurz, keine Emojis, keine Superlative. Fakten statt Vermutungen.

## 1. Lesereihenfolge (Pflicht vor jedem Task)

1. Diese Datei.
2. `docs/INDEX.md` — Doku-Landkarte, nur die dort verlinkte Datei lesen.
3. Erst dann Code anfassen.

`HANDOFF.md` ist kein Wissensspeicher mehr, nur Status-Zeiger.
Neues Wissen gehört nach `docs/`, nie nach `HANDOFF.md` oder in den Root.

## 2. Naming-Freeze (beschlossen, nicht verhandelbar)

- User-sichtbar ist **Matchday**: `applicationId dev.matchday.tv`
  (`app/build.gradle.kts`), `app_name`, Branding. Das `sportio://`-Scheme
  bleibt (Core erzeugt diese Links), ebenso die Sportio-Palette.
- Technisch bleibt **tv.own.owntv** eingefroren: Kotlin-Pakete,
  `android.namespace`, Core-Group `tv.own.owntv`, `OwnTVDatabase`,
  `OwnTV*`-Klassen/Dateien (z. B. `OwnTVApp.kt`, `OwnTVShell.kt`,
  `OwnTVIcon.kt`), DataStore/Backup (`.own`, Ordner `OwnTV`).
- Kein Rename von Paketen, Klassen, Dateien oder Core-Group ohne
  expliziten User-Auftrag. Begründung steht in `docs/BRANDING.md` (Upstream).
- Interne `OwnTV`-Namen in neuem Code nicht weiterverbreiten, aber
  bestehende nicht umbenennen. Neuen user-sichtbaren Text als `sport_*`
  nach `res/values/strings.xml` + `values-de/`.

## 3. Doku-Regeln

- Root-`.md` ausser `README.md` / `CHANGELOG.md`: verboten.
  Neue Doku nur unter `docs/` + Eintrag in `docs/INDEX.md`.
- Altes, aber aufzuhebendes nach `docs/archive/`, nie löschen.
- Jede Doku-Datei: oben 1-Satz-Zweck + Stand-Datum.
- `docs/INDEX.md`-Status beachten: `aktiv` vs. `archiv`.

## 4. Toolchain (Windows PowerShell)


- JDK: `$env:JAVA_HOME="C:\Users\<user>\OwnTV\tools-setup\jdk17-portable\jdk-17.0.11+9"`
- SDK: `$env:ANDROID_HOME="C:\Users\<user>\Android\Sdk"`
- i18n-Gate via `tools-setup\python-embed\python.exe`, nie PATH-Python:
  `tools/i18n/check_hardcoded_strings.py verify --bootstrap`
  (App hier, Core in `OwnTV_Core-main/`).
- Neue Kotlin-Literale klassifizieren (`classify-safe`) oder in
  `strings.xml` auslagern; gelöschte via `prune-safe` aufräumen.
- Jedes `Text(..., maxLines = N)` braucht `overflow = TextOverflow.Ellipsis`
  (`tools/i18n/check_text_overflow.py`).

## 5. Gates (vor jedem Push/Release grün)

- `:app:assembleStandardDebug` + `:app:assembleX86_64Debug`
- `:app:testStandardDebugUnitTest`
- `lintStandardDebug` (`abortOnError = true`)
- i18n-`verify --bootstrap` in App- UND Core-Repo
- Core-Pfad: `owntv.corePath=C:/Users/<user>/OwnTV/OwnTV_Core-main`
  in `C:\Users\<user>\.gradle\gradle.properties` (Composite-Build).
  `~/.gradle/gradle.properties` nie anfassen.

## 6. Verbote

- Keine Secrets/Token auf Platte oder in URLs. GitHub-Auth nur per
  `-c http.extraHeader=Authorization: Basic <base64(x-access-token:TOKEN)>`.
- Keine Build-Artefakte committen (APK, ZIP, `build/`, `tools-setup/`,
  `OwnTV_Core-main/` — siehe `.gitignore`).
- Keine echten Provider-Credentials in Logs/Doku committen.
- Kein `Unit`-Dispose, das den laufenden Player killt
  (vgl. `docs/SPORT-HUB.md`: `stopPreview`-Falle beim Fullscreen-Wechsel).
- Guide-Discovery-Guard (`out.size < CandidateCap` vor EPG-Pfad)
  nie wieder einbauen — Details in `docs/SPORT-HUB.md`.
