# SmartCodeReviewer in IntelliJ IDEA einbinden

Diese Anleitung beschreibt, wie SmartCodeReviewer als **External Tool** in IntelliJ IDEA eingebunden wird, sodass man eine Java-Datei per Rechtsklick (oder Shortcut) direkt gegen die Review-Pipeline laufen lassen kann — ohne Terminal.

## Voraussetzungen

- JDK 26 installiert und in IntelliJ als Projekt-SDK ausgewählt.
- Windows + PowerShell (die Skripte sind Windows-spezifisch, siehe `CLAUDE.md`).
- Ausführung von PowerShell-Skripten erlaubt (die Skripte laufen mit `-ExecutionPolicy Bypass`, es ist also keine globale Policy-Änderung nötig).
- `SCR_GEMINI_API_KEY` (oder `GEMINI_API_KEY`/`GOOGLE_API_KEY`) gesetzt, sonst läuft die Pipeline ohne echte KI-Vorschläge (siehe `GeminiAiReviewer`).

## Was die Wrapper-Skripte tun

| Datei | Rolle |
|---|---|
| `bin/smartcodereviewer.cmd` | Dünner Einstiegspunkt **speziell für IntelliJ External Tools**: nimmt den von IntelliJ übergebenen Dateipfad entgegen und reicht ihn an das PowerShell-Skript weiter. |
| `bin/smartcodereviewer.ps1` | Eigentliche Logik: erkennt, ob Quellcode/Ressourcen neuer sind als die gebaute Jar, baut bei Bedarf automatisch neu (`mvnw.cmd package -DskipTests`) und startet dann `java -jar ... <Dateipfad>`. |

IntelliJ ruft External Tools grundsätzlich lieber über eine `.exe`/`.cmd`/`.bat` als direkt über `powershell.exe` mit komplexen Argumenten auf — daher der `.cmd`-Zwischenschritt. Beide Skripte bleiben unverändert im `bin/`-Verzeichnis (siehe [Vorschlag](#vorschlag-geteilte-konfiguration-statt-manueller-einrichtung) unten, warum ein Verschieben nicht nötig ist).

## Option A: Manuelle Einrichtung (pro IDE-Installation)

1. **Settings/Preferences → Tools → External Tools → `+`**
2. Werte eintragen:

   | Feld | Wert |
   |---|---|
   | Name | `SmartCodeReviewer` |
   | Description | *(optional)* `Checkstyle + Gemini-Review der aktuellen Java-Datei` |
   | Program | `$ProjectFileDir$\bin\smartcodereviewer.cmd` |
   | Arguments | `$FilePath$` |
   | Working directory | `$ProjectFileDir$` |

3. Unter **Advanced Options**:
   - ☑ *Open console for tool output* — damit man KI-Feedback/Fehler sofort sieht.
   - ☑ *Make console active on message in stdout/stderr* — optional, holt die Konsole automatisch nach vorn.
   - ☑ *Synchronize files after execution* — aktualisiert die IntelliJ-Ansicht, falls Dateien im Projektverzeichnis entstanden sind.
4. **OK** / **Apply**.

`$FilePath$` ist genau das Makro, das IntelliJ durch den absoluten Pfad der aktuell ausgewählten/geöffneten Datei ersetzt — passend zum `-FilePath`-Parameter, den `smartcodereviewer.ps1` erwartet.

## Option B: Geteilte Konfiguration (bereits im Projekt hinterlegt)

Damit nicht jede Person das Tool manuell nach Option A anlegen muss, liegt eine fertige Definition unter [`.idea/tools/External Tools.xml`](../.idea/tools/External%20Tools.xml). IntelliJ liest projektbezogene External-Tools-Konfigurationen aus `.idea/tools/*.xml` automatisch beim Öffnen des Projekts ein — das Tool `SmartCodeReviewer` erscheint dann direkt in **Tools → External Tools**, ohne dass Option A nötig ist.

Details dazu im [Vorschlag](#vorschlag-geteilte-konfiguration-statt-manueller-einrichtung) unten.

## Ausführen

Sobald das Tool (per A oder B) eingerichtet ist, gibt es mehrere Wege, es auf einer `.java`-Datei aufzurufen:

- **Editor-Tab / Datei im Project-Tool-Window** → Rechtsklick → **External Tools → SmartCodeReviewer**
- **Menü** → **Tools → External Tools → SmartCodeReviewer**
- **Suche überall** (`Ctrl` `Ctrl` bzw. `Shift Shift`) → „SmartCodeReviewer“ eingeben
- **Eigener Shortcut**: **Settings → Keymap** → nach „SmartCodeReviewer“ suchen → Rechtsklick → **Add Keyboard Shortcut** (z. B. `Ctrl+Alt+Shift+R`)

Wichtig: Die Datei muss beim Aufruf **aktiv im Editor geöffnet oder im Project-Tool-Window ausgewählt** sein, da `$FilePath$` sich darauf bezieht.

Nach dem Lauf öffnet die Pipeline für jeden gefundenen Vorschlag einen eigenen Diff-Tab (Original links, Vorschlag rechts, siehe [`docs/arc42.md`](arc42.md) Kapitel 6). Es wird **nichts automatisch übernommen** — Übernahme erfolgt wie gewohnt manuell im Diff-Viewer.

## Vorschlag: Geteilte Konfiguration statt manueller Einrichtung

**Empfehlung:** Die Skripte bleiben in `bin/` (kein Verschieben nötig — `bin/smartcodereviewer.cmd` ist laut `CLAUDE.md` explizit für genau diesen Zweck gebaut), und die External-Tool-Definition wird als Projektdatei mitgeliefert statt als reine „jede:r richtet sich das lokal ein“-Anleitung.

Umgesetzt habe ich dazu zwei Änderungen:

1. **`.idea/tools/External Tools.xml`** — enthält die Tool-Definition aus Option A (Programm, Argument `$FilePath$`, Arbeitsverzeichnis), maschinenunabhängig durch die IntelliJ-Makros statt fester Pfade.
2. **`.gitignore`-Anpassung** — bisher war `.idea` komplett ausgeschlossen (Standard-Spring-Initializr-Vorlage). Ich habe das auf `.idea/*` mit einer expliziten Ausnahme für `.idea/tools/` umgestellt:

   ```gitignore
   ### IntelliJ IDEA ###
   .idea/*
   !.idea/tools/
   !.idea/tools/**
   ```

   Dadurch bleibt alles andere in `.idea/` weiterhin ignoriert (`workspace.xml`, `misc.xml`, `inspectionProfiles/`, `claudeCodeEditorTabs.xml`, `jarRepositories.xml`, `compiler.xml`, `encodings.xml` — alles Nutzer- bzw. Maschinen-spezifisch), nur `.idea/tools/` wird künftig versioniert.

**Vorteile:**
- Neue Klon-/Checkout-Situation: External Tool ist sofort da, kein manuelles Nachpflegen der Anleitung nötig.
- Die Anleitung in diesem Dokument (Option A) bleibt trotzdem relevant — z. B. für andere Maschinen/Editoren oder falls jemand `.idea/tools/` lokal überschreiben will.
- Änderungen am Tool (z. B. andere Konsolen-Optionen) sind über Diffs nachvollziehbar, statt in `workspace.xml` verloren zu gehen.

**Zu beachten:**
- IntelliJ lädt `.idea/tools/*.xml` beim Öffnen des Projekts; ist das Projekt bereits offen, hilft **File → Invalidate Caches** oder ein Neuladen des Projekts, falls das Tool nicht sofort auftaucht.
- Falls mehrere External Tools künftig dazukommen, landen sie ebenfalls in `.idea/tools/External Tools.xml` (Standard-Gruppenname „External Tools“) — die Datei kann einfach erweitert werden, ein neuer `<tool>`-Eintrag pro Werkzeug.
