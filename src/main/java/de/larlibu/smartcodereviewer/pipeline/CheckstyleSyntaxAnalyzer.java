package de.larlibu.smartcodereviewer.pipeline;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import com.sun.source.util.JavacTask;
import de.larlibu.smartcodereviewer.api.SyntaxAnalyzer;
import de.larlibu.smartcodereviewer.model.CheckstyleFinding;
import de.larlibu.smartcodereviewer.model.CheckstyleOutcome;
import de.larlibu.smartcodereviewer.model.SyntaxCheckResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xml.sax.InputSource;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Schritt 1 der Pipeline: prueft, ob eine Java-Datei syntaktisch gueltig ist, bevor
 * teure API-Aufrufe an die KI gemacht werden.
 *
 * <p>Primaer wird Checkstyle mit einer minimalen, inline definierten Konfiguration
 * ausgefuehrt (nur {@code UnusedImports} und {@code AvoidStarImport}). Checkstyle-Verstoesse
 * allein fuehren nicht zum Abbruch — sie werden lediglich als {@link CheckstyleFinding}s
 * gesammelt und spaeter von {@link CheckstyleFixer} fuer Auto-Fix-Vorschlaege genutzt.
 * Schlaegt Checkstyle selbst fehl (z. B. weil die Datei nicht parsebar ist), wird auf einen
 * reinen javac-Parse-Check zurueckgefallen, der nur die Syntax prueft, ohne zu kompilieren.</p>
 */
public class CheckstyleSyntaxAnalyzer implements SyntaxAnalyzer {

    private static final Logger LOGGER = LogManager.getLogger(CheckstyleSyntaxAnalyzer.class);

    private static final String CHECKSTYLE_CONFIG_XML = """
            <!DOCTYPE module PUBLIC
                "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
                "https://checkstyle.org/dtds/configuration_1_3.dtd">
            <module name="Checker">
              <module name="TreeWalker">
                <module name="UnusedImports"/>
                <module name="AvoidStarImport"/>
              </module>
            </module>
            """;

    /**
     * Fuehrt Checkstyle gegen {@code file} aus und faellt bei Nicht-.java-Dateien oder
     * Checkstyle-Fehlern auf einfachere Pruefungen zurueck (siehe Klassen-Javadoc).
     * {@code code} wird dabei nur fuer den Nicht-.java-Fallback herangezogen.
     */
    @Override
    public SyntaxCheckResult analyze(Path file, String code) {
        LOGGER.info("[Step 1] Fuehre Checkstyle-Analyse aus...");

        boolean isJava = file.toString().toLowerCase(Locale.ROOT).endsWith(".java");
        if (!isJava) {
            LOGGER.warn("Datei ist keine .java-Datei. Nutze vereinfachten Syntax-Check.");
            boolean ok = !code.contains("invalid_syntax");
            return new SyntaxCheckResult(ok, new CheckstyleOutcome(true, List.of(), List.of()));
        }

        try {
            CheckstyleOutcome out = runCheckstyle(file);
            if (!out.ok()) {
                for (String line : out.messages()) {
                    LOGGER.error(line);
                }
                LOGGER.warn("Checkstyle hat Verstosse gefunden. KI-Review wird trotzdem fortgesetzt, wenn Syntax gueltig ist.");
                boolean syntaxValid = javacParseOk(file);
                return new SyntaxCheckResult(syntaxValid, out);
            }
            return new SyntaxCheckResult(true, out);
        } catch (Exception e) {
            LOGGER.warn("Checkstyle konnte nicht ausgefuehrt werden: {}", e.getMessage());
            LOGGER.info("Fallback auf javac-Parsing.");
            return new SyntaxCheckResult(
                    javacParseOk(file),
                    new CheckstyleOutcome(true, List.of("Checkstyle-Analyse nicht verfuegbar: " + e.getMessage()), List.of())
            );
        }
    }

    /**
     * Laedt die inline definierte Checkstyle-Konfiguration und laesst sie ueber {@code javaFile}
     * laufen. Ergebnis und Findings werden ueber {@link CollectingAuditListener} gesammelt.
     */
    private CheckstyleOutcome runCheckstyle(Path javaFile) throws CheckstyleException {
        Configuration config = ConfigurationLoader.loadConfiguration(
                new InputSource(new StringReader(CHECKSTYLE_CONFIG_XML)),
                new PropertiesExpander(System.getProperties()),
                ConfigurationLoader.IgnoredModulesOptions.OMIT
        );

        CollectingAuditListener listener = new CollectingAuditListener();
        Checker checker = new Checker();
        checker.setModuleClassLoader(Checker.class.getClassLoader());
        checker.configure(config);
        checker.addListener(listener);

        int errors;
        try {
            errors = checker.process(List.of(javaFile.toFile()));
        } finally {
            checker.destroy();
        }

        boolean ok = errors == 0 && listener.exceptionCount == 0;
        return new CheckstyleOutcome(ok, listener.messages, listener.findings);
    }

    /**
     * Es wird bewusst nur geparst, nicht kompiliert.
     */
    private boolean javacParseOk(Path javaFile) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            LOGGER.warn("Kein JavaCompiler verfuegbar (JRE statt JDK). Ueberspringe javac-Parsing.");
            return true;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(javaFile.toFile());
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, units);
            task.parse();
        } catch (Exception e) {
            LOGGER.error("javac-Parsing fehlgeschlagen: {}", e.getMessage());
            return false;
        }

        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                LOGGER.error("Syntaxfehler: {}", diagnostic.getMessage(Locale.ROOT));
                return false;
            }
        }
        return true;
    }

    /**
     * Sammelt Checkstyle-Ergebnisse ein, statt sie sofort auszugeben: sowohl als
     * formatierte Log-Zeilen ({@code messages}) als auch als strukturierte
     * {@link CheckstyleFinding}s fuer die programmatische Weiterverarbeitung durch
     * {@link CheckstyleFixer}.
     */
    private static final class CollectingAuditListener implements AuditListener {
        final List<String> messages = new ArrayList<>();
        final List<CheckstyleFinding> findings = new ArrayList<>();
        int exceptionCount = 0;

        @Override
        public void auditStarted(AuditEvent event) {}

        @Override
        public void auditFinished(AuditEvent event) {}

        @Override
        public void fileStarted(AuditEvent event) {}

        @Override
        public void fileFinished(AuditEvent event) {}

        @Override
        public void addError(AuditEvent event) {
            String msg = "%s:%d:%d: %s".formatted(
                    shortFileName(event.getFileName()),
                    event.getLine(),
                    event.getColumn(),
                    event.getMessage()
            );
            messages.add(msg);
            findings.add(new CheckstyleFinding(
                    shortFileName(event.getFileName()),
                    event.getLine(),
                    event.getColumn(),
                    event.getMessage(),
                    event.getSourceName()
            ));
        }

        @Override
        public void addException(AuditEvent event, Throwable throwable) {
            exceptionCount++;
            String where = event == null ? "" : shortFileName(event.getFileName()) + ": ";
            String throwableMessage = throwable == null ? "<unknown>" : throwable.getMessage();
            messages.add(where + "Exception: " + throwableMessage);
        }

        private static String shortFileName(String fileName) {
            if (fileName == null) {
                return "<?>";
            }
            int idx = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
            return idx >= 0 ? fileName.substring(idx + 1) : fileName;
        }
    }
}
