# SmartCodeReviewer — Benutzerhandbuch

## 1. Was ist SmartCodeReviewer?

Ein CLI-Werkzeug, das eine einzelne Java-Datei automatisiert begutachtet: Syntaxprüfung → automatische Checkstyle-Fixes → KI-Review (Google Gemini) → Anzeige aller Vorschläge im IntelliJ-Diff-Viewer. Es verändert die Originaldatei **nie automatisch** — du entscheidest im Diff-Viewer selbst, was du übernimmst.

Eine detaillierte Architekturbeschreibung findest du in [`docs/arc42.md`](arc42.md), die generierte Code-Referenz in [`docs/javadoc/index.html`](javadoc/index.html).

## 2. Voraussetzungen

- JDK 26 installiert
- Maven-Wrapper (`mvnw.cmd`) im Projekt — kein separates Maven nötig
- Ein Gemini-API-Key (siehe Kapitel 4)
- Optional: IntelliJ IDEA lokal installiert, wenn der Diff-Viewer genutzt werden soll

## 3. Erste Schritte

```powershell
# Einmalig bauen
./mvnw.cmd package -DskipTests

# Direkt ausführen
java -jar target/SmartCodeReviewer-0.0.1-SNAPSHOT-boot.jar Pfad\zu\MeineKlasse.java

# Oder über den Wrapper (baut bei Bedarf automatisch neu)
bin/smartcodereviewer.ps1 -FilePath Pfad\zu\MeineKlasse.java
```

## 4. Konfiguration

| Variable | Fallback-Reihenfolge | Zweck |
|---|---|---|
| `SCR_GEMINI_API_KEY` | → `GEMINI_API_KEY` → `GOOGLE_API_KEY` → `scr.gemini.api-key` in `application.properties` | Gemini-API-Key |
| `SCR_GEMINI_MODEL` | → `scr.gemini.model` → `gemini-2.5-flash` | zu verwendendes Modell |
| `SCR_GEMINI_API_URL` | → `scr.gemini.api-url` → `https://generativelanguage.googleapis.com/v1` | API-Basis-URL |
| `SCR_INTELLIJ_LAUNCHER` | → `IDEA_LAUNCHER` → automatische Suche | Pfad zu `idea64.exe` |

**Hinweis:** `application.properties` kann einen echten API-Key enthalten und darf nicht committet werden.

## 5. Was während eines Laufs passiert

1. **Syntax-Check** — Checkstyle prüft auf ungenutzte Imports und Stern-Importe; bei echten Syntaxfehlern bricht die Pipeline sofort ab (spart API-Kosten).
2. **Automatische Fixes** — ungenutzte Imports werden als fertiger Vorschlag entfernt (ohne KI).
3. **KI-Review** — der Code wird an Gemini geschickt; Antwort liefert eine Zusammenfassung, Findings und bis zu 3 Verbesserungsvorschläge.
4. **Diff-Anzeige** — jeder Vorschlag öffnet einen eigenen IntelliJ-Diff-Tab (links: dein Original, rechts: Vorschlag mit erklärenden Inline-Kommentaren). Es wird nichts automatisch übernommen.

## 6. Nutzung in IntelliJ

Am komfortabelsten über ein External Tool, das `bin/smartcodereviewer.cmd` mit der aktuell offenen Datei aufruft. Eine fertige Konfiguration liegt bereits unter `.idea/tools/External Tools.xml` im Projekt und wird von IntelliJ automatisch erkannt — Schritt-für-Schritt-Anleitung (inkl. manueller Alternative und Tastenkürzel) in [`docs/intellij-integration.md`](intellij-integration.md).

## 7. Problemlösung

| Symptom | Ursache | Verhalten |
|---|---|---|
| „Kein API Key gesetzt" | Keine der vier Konfigurationsquellen liefert einen Key | Pipeline läuft weiter, aber ohne KI-Vorschläge |
| Wiederholte Wartezeit vor Antwort | Gemini antwortet mit 429/5xx | Automatische Retries mit Backoff, danach Wechsel auf alternative Modelle/URLs |
| „Kein IntelliJ-Launcher gefunden" | IntelliJ nicht an einem der Standardpfade gefunden | `SCR_INTELLIJ_LAUNCHER` manuell setzen |
| Pipeline bricht sofort ab | Datei enthält echte Syntaxfehler | Datei zuerst syntaktisch korrigieren |

## 8. Weiterführende Dokumentation

| Dokument | Inhalt |
|---|---|
| `CLAUDE.md` | Technische Kurzreferenz für Entwicklung (Build, Architektur, Konfiguration) |
| `docs/arc42.md` | Vollständige Architekturdokumentation (Kontext, Bausteine, Laufzeitsicht, Entscheidungen, Risiken) |
| `docs/intellij-integration.md` | Einrichtung als IntelliJ External Tool |
| `docs/javadoc/index.html` | Generierte API-Dokumentation aus dem Quellcode |
