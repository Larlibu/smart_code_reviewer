# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```powershell
# Build (skipping tests)
./mvnw.cmd package -DskipTests

# Build and run tests
./mvnw.cmd test

# Run directly after build
java -jar target/SmartCodeReviewer-0.0.1-SNAPSHOT-boot.jar <path-to-java-file>

# Run via wrapper (auto-rebuilds if sources changed)
bin/smartcodereviewer.ps1 -FilePath <path-to-java-file>
```

The wrapper scripts (`bin/smartcodereviewer.cmd`, `bin/smartcodereviewer.ps1`) are designed for use as IntelliJ External Tools — they detect source changes and trigger `mvn package` automatically before running. Setup instructions: `docs/intellij-integration.md`. A ready-made tool definition ships in `.idea/tools/External Tools.xml`.

## Architecture

The application is split into three packages under `src/main/java/de/larlibu/smartcodereviewer/`:

| Package | Role |
|---------|------|
| `api/` | Interfaces the pipeline steps are programmed against: `SyntaxAnalyzer`, `AiReviewer`, `ReviewPresenter`, `DiffViewerLauncher` |
| `model/` | Immutable records shared between steps: `SolutionSuggestion`, `AiReviewResult`, `SyntaxCheckResult`, `CheckstyleOutcome`, `CheckstyleFinding` |
| `pipeline/` | The concrete implementations (see below) |

`SmartCodeReviewerApplication` (top-level package) is the **composition root**: it does CLI-arg parsing only, wires the interface implementations together, and runs the three-step pipeline. It contains no business logic itself.

`@SpringBootApplication` is declared but the Spring context is never started — `main()` runs the pipeline directly as a CLI tool.

### Four-step review pipeline

1. **Syntax check** (`pipeline/CheckstyleSyntaxAnalyzer`, implements `SyntaxAnalyzer`): Runs Checkstyle (inline XML config — only `UnusedImports` and `AvoidStarImport`), collecting findings via the private `CollectingAuditListener` (`AuditListener` implementation). On Checkstyle failure or error, falls back to parse-only javac checking. If syntax is invalid, the pipeline aborts to save API costs.

2. **Checkstyle auto-fix** (`pipeline/CheckstyleFixer`): Turns Checkstyle findings into ready-to-apply `SolutionSuggestion`s, independent of the AI. Currently only removes unused-import lines (`UnusedImportsCheck`). Star-import resolution (`AvoidStarImportCheck` → explicit imports) was **removed**: the old implementation resolved externally-derived type names via `Class.forName` against this application's own classpath, which was both a security risk (loading/initializing classes by name extracted from untrusted source code) and factually wrong (wrong classpath). See the class Javadoc for details; a correct fix needs a real Java parser/symbol resolver.

3. **AI review** (`pipeline/GeminiAiReviewer`, implements `AiReviewer`): POSTs the source code to the Gemini API with a structured JSON prompt. Implements two-level fallback: multiple API base URLs (`v1` ↔ `v1beta`) × multiple model candidates (configured → static list → dynamically discovered). Transient HTTP errors (429, 5xx) trigger exponential backoff with jitter (up to 3 retries), signaled internally via the private `GeminiHttpException`.

4. **Diff viewer** (`pipeline/IntellijDiffPresenter`, implements `ReviewPresenter`): Opens IntelliJ's diff viewer with the original file on the left and the suggested code on the right, combining Checkstyle-fix suggestions and AI suggestions. Inline comments are injected into the suggestion at change-block boundaries using a private LCS-based diff (`DiffLine` record / `DiffType` enum). IntelliJ launcher is auto-discovered from Toolbox, `Program Files`, and `LOCALAPPDATA` paths; can be overridden via `SCR_INTELLIJ_LAUNCHER`.

### Configuration / environment variables

| Variable | Fallback | Purpose |
|----------|----------|---------|
| `SCR_GEMINI_API_KEY` | `GEMINI_API_KEY` → `GOOGLE_API_KEY` → `scr.gemini.api-key` in `application.properties` | Gemini API key |
| `SCR_GEMINI_MODEL` | `scr.gemini.model` → `gemini-2.5-flash` | Gemini model |
| `SCR_GEMINI_API_URL` | `scr.gemini.api-url` → `https://generativelanguage.googleapis.com/v1` | Gemini base URL |
| `SCR_INTELLIJ_LAUNCHER` | `IDEA_LAUNCHER` → auto-discovered `idea64.exe` | IntelliJ executable |

**Note**: `application.properties` currently contains a real API key — do not commit it.

## Static analysis

`qodana.yaml` configures JetBrains Qodana (`jetbrains/qodana-jvm:2025.3` linter, `qodana.starter` profile, JDK 26) for CI/CD-driven static analysis, separate from the Checkstyle checks run inside the pipeline itself.

## Logging

Log4j2 is used directly (Spring Boot's default logging starter is excluded in `pom.xml`). Config is in `src/main/resources/log4j2.xml`. All pipeline output goes through `LOGGER` — there are no `System.out` calls.

## Tests

`src/test/java/de/larlibu/smartcodereviewer/`:

- `SmartCodeReviewerApplicationTests` — Spring Boot context load test only.
- `tests/CheckstyleFixerTest`, `tests/CheckstyleSyntaxAnalyzerTest`, `tests/GeminiAiReviewerTest`, `tests/IntellijDiffPresenterTest` — unit tests for the respective `pipeline/` implementations.


