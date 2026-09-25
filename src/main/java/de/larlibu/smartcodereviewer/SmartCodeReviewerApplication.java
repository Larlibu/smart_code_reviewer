package de.larlibu.smartcodereviewer;

import de.larlibu.smartcodereviewer.api.AiReviewer;
import de.larlibu.smartcodereviewer.api.ReviewPresenter;
import de.larlibu.smartcodereviewer.api.SyntaxAnalyzer;
import de.larlibu.smartcodereviewer.pipeline.CheckstyleFixer;
import de.larlibu.smartcodereviewer.pipeline.CheckstyleSyntaxAnalyzer;
import de.larlibu.smartcodereviewer.pipeline.GeminiAiReviewer;
import de.larlibu.smartcodereviewer.pipeline.IntellijDiffPresenter;
import de.larlibu.smartcodereviewer.model.AiReviewResult;
import de.larlibu.smartcodereviewer.model.SolutionSuggestion;
import de.larlibu.smartcodereviewer.model.SyntaxCheckResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Einstiegspunkt und Kompositionswurzel der Smart-Code-Reviewer-Pipeline.
 *
 * <p>Orchestriert die drei Pipeline-Schritte und verdrahtet die Implementierungen
 * hinter ihren Interfaces. Keine fachliche Logik — nur CLI-Parsing und Ablaufsteuerung.</p>
 */
@SpringBootApplication
public class SmartCodeReviewerApplication {

    private static final Logger LOGGER = LogManager.getLogger(SmartCodeReviewerApplication.class);

    public static void main(String[] args) throws Exception {
        // Windows-Zertifikatsspeicher als TrustStore nutzen, damit HTTPS zu Google-APIs funktioniert
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
        }

        if (hasArg(args, "--help") || hasArg(args, "-h")) {
            LOGGER.info("\n{}", usage());
            return;
        }

        Path targetFile = parseFileArg(args)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .orElse(null);

        if (targetFile == null) {
            LOGGER.error("Keine Eingabedatei uebergeben.");
            LOGGER.info("\n{}", usage());
            return;
        }

        if (!Files.exists(targetFile)) {
            LOGGER.error("Datei nicht gefunden: {}", targetFile);
            return;
        }
        if (!Files.isRegularFile(targetFile)) {
            LOGGER.error("Kein regulaeres File (z. B. Verzeichnis): {}", targetFile);
            return;
        }

        String javaCode = Files.readString(targetFile, StandardCharsets.UTF_8);
        LOGGER.info("=== Starte KI-Review Pipeline ===");
        LOGGER.info("Eingabedatei: {}", targetFile);

        Properties appProperties = loadApplicationProperties();
        SyntaxAnalyzer syntaxAnalyzer = new CheckstyleSyntaxAnalyzer();
        CheckstyleFixer checkstyleFixer = new CheckstyleFixer();
        AiReviewer aiReviewer = new GeminiAiReviewer(appProperties);
        ReviewPresenter presenter = new IntellijDiffPresenter();

        SyntaxCheckResult syntaxCheckResult = syntaxAnalyzer.analyze(targetFile, javaCode);
        if (!syntaxCheckResult.syntaxValid()) {
            LOGGER.error("STOPP: Syntaxfehler gefunden. Review abgebrochen, um API-Kosten zu sparen.");
            return;
        }

        List<SolutionSuggestion> suggestions = new ArrayList<>(
                checkstyleFixer.buildSuggestions(javaCode, syntaxCheckResult.checkstyleOutcome())
        );

        LOGGER.info("Syntax OK. Sende Code an Gemini API...");
        AiReviewResult aiReview = aiReviewer.review(javaCode);
        LOGGER.info("KI FEEDBACK:\n{}", aiReview.feedback());
        suggestions.addAll(aiReview.suggestions());

        presenter.present(suggestions, targetFile, javaCode, aiReview.feedback());
    }

    private static String usage() {
        return """
                SmartCodeReviewerApplication

                CLI:
                  PATH                  Fuehrt die Pipeline fuer eine Datei aus (liest und zeigt Diff-Vorschlaege)
                  --file=PATH           wie oben
                  --help                Hilfe

                Gemini:
                  SCR_GEMINI_API_KEY    API-Key (alternativ: GEMINI_API_KEY oder GOOGLE_API_KEY)
                  SCR_GEMINI_MODEL      Default: gemini-2.5-flash (mit Auto-Fallback)
                  SCR_GEMINI_API_URL    Default: https://generativelanguage.googleapis.com/v1 (mit Auto-Fallback auf v1beta)
                  scr.gemini.api-key    Fallback aus application.properties
                  scr.gemini.model      Fallback aus application.properties
                  scr.gemini.api-url    Fallback aus application.properties

                Diff:
                  SCR_INTELLIJ_LAUNCHER Optionaler IntelliJ-Launcher (z. B. idea64.exe)
                """;
    }

    private static boolean hasArg(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static Optional<Path> parseFileArg(String[] args) {
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (arg.startsWith("--file=")) {
                return Optional.of(Path.of(arg.substring("--file=".length())));
            }
        }
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (!arg.startsWith("-")) {
                return Optional.of(Path.of(arg));
            }
        }
        return Optional.empty();
    }

    private static Properties loadApplicationProperties() {
        Properties properties = new Properties();
        try (InputStream in = SmartCodeReviewerApplication.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            LOGGER.warn("application.properties konnte nicht geladen werden: {}", e.getMessage());
        }
        return properties;
    }
}
