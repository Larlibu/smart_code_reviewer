package de.larlibu.smartcodereviewer.tests;

import de.larlibu.smartcodereviewer.model.SyntaxCheckResult;
import de.larlibu.smartcodereviewer.pipeline.CheckstyleSyntaxAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class CheckstyleSyntaxAnalyzerTest {

    private CheckstyleSyntaxAnalyzer analyzer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        analyzer = new CheckstyleSyntaxAnalyzer();
    }

    @Test
    void analyze_withValidJavaFile_returnsSyntaxValid() throws IOException {
        Path file = writeJava("Valid.java", """
                public class Valid {
                    public void bar() {}
                }
                """);

        SyntaxCheckResult result = analyzer.analyze(file, Files.readString(file));

        assertThat(result.syntaxValid()).isTrue();
    }

    @Test
    // Checkstyle wirft eine Exception auf unparsebaren Dateien, weshalb der Analyzer
    // auf javacParseOk zurueckfaellt. Javac erkennt die fehlende schliessende Klammer.
    void analyze_withBrokenSyntax_returnsSyntaxInvalid() throws IOException {
        Path file = writeJava("Broken.java", """
                public class Broken {
                    public void missingClosingBrace() {
                """);

        SyntaxCheckResult result = analyzer.analyze(file, Files.readString(file));

        assertThat(result.syntaxValid()).isFalse();
    }

    @Test
    void analyze_withNonJavaFile_returnsSyntaxValid() throws IOException {
        Path file = tempDir.resolve("config.txt");
        Files.writeString(file, "some config content");

        SyntaxCheckResult result = analyzer.analyze(file, "some config content");

        assertThat(result.syntaxValid()).isTrue();
    }

    @Test
    void analyze_withUnusedImport_checkstyleReportsViolation() throws IOException {
        Path file = writeJava("WithUnusedImport.java", """
                import java.util.ArrayList;

                public class WithUnusedImport {
                    public void bar() {}
                }
                """);

        SyntaxCheckResult result = analyzer.analyze(file, Files.readString(file));

        assertThat(result.checkstyleOutcome().ok()).isFalse();
        assertThat(result.checkstyleOutcome().findings()).isNotEmpty();
        assertThat(result.checkstyleOutcome().findings().get(0).sourceName())
                .endsWith("UnusedImportsCheck");
    }

    @Test
    // Checkstyle-Verstoss bedeutet nicht zwingend ungueltige Syntax: syntaxValid bleibt
    // true, weil Stern-Imports syntaktisch erlaubt sind. Nur checkstyleOutcome.ok() ist false.
    void analyze_withStarImport_checkstyleReportsViolation() throws IOException {
        Path file = writeJava("WithStarImport.java", """
                import java.util.*;

                public class WithStarImport {
                    private List<String> items;
                }
                """);

        SyntaxCheckResult result = analyzer.analyze(file, Files.readString(file));

        assertThat(result.checkstyleOutcome().ok()).isFalse();
        assertThat(result.checkstyleOutcome().findings()).isNotEmpty();
        assertThat(result.checkstyleOutcome().findings().get(0).sourceName())
                .endsWith("AvoidStarImportCheck");
    }

    private Path writeJava(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }
}
