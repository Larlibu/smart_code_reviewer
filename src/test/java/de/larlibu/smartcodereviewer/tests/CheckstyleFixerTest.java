package de.larlibu.smartcodereviewer.tests;

import de.larlibu.smartcodereviewer.model.CheckstyleFinding;
import de.larlibu.smartcodereviewer.model.CheckstyleOutcome;
import de.larlibu.smartcodereviewer.model.SolutionSuggestion;
import de.larlibu.smartcodereviewer.pipeline.CheckstyleFixer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class CheckstyleFixerTest {

    private static final String UNUSED_IMPORTS_CHECK = "com.puppycrawl.tools.checkstyle.checks.imports.UnusedImportsCheck";
    private static final String AVOID_STAR_IMPORT_CHECK = "com.puppycrawl.tools.checkstyle.checks.imports.AvoidStarImportCheck";

    private CheckstyleFixer fixer;

    @BeforeEach
    void setUp() {
        fixer = new CheckstyleFixer();
    }

    @Test
    void buildSuggestions_withNullOutcome_returnsEmpty() {
        assertThat(fixer.buildSuggestions("code", null)).isEmpty();
    }

    @Test
    void buildSuggestions_withNoFindings_returnsEmpty() {
        CheckstyleOutcome outcome = new CheckstyleOutcome(true, List.of(), List.of());
        assertThat(fixer.buildSuggestions("code", outcome)).isEmpty();
    }

    @Test
    void buildSuggestions_withUnrelatedFinding_returnsEmpty() {
        CheckstyleFinding finding = new CheckstyleFinding(
                "Foo.java", 5, 1,
                "Line is longer than 80 characters",
                "com.puppycrawl.tools.checkstyle.checks.sizes.LineLengthCheck"
        );
        CheckstyleOutcome outcome = new CheckstyleOutcome(false, List.of(), List.of(finding));

        assertThat(fixer.buildSuggestions("some code", outcome)).isEmpty();
    }

    @Test
    void buildSuggestions_withUnusedImport_removesImportLine() {
        String code = """
                package com.example;

                import java.util.List;
                import java.util.ArrayList;

                public class Foo {
                    public void bar() {}
                }
                """;

        CheckstyleFinding finding = new CheckstyleFinding(
                "Foo.java", 4, 1,
                "Unused import - java.util.ArrayList",
                UNUSED_IMPORTS_CHECK
        );
        CheckstyleOutcome outcome = new CheckstyleOutcome(false, List.of(), List.of(finding));

        List<SolutionSuggestion> result = fixer.buildSuggestions(code, outcome);

        assertThat(result).hasSize(1);
        SolutionSuggestion suggestion = result.get(0);
        assertThat(suggestion.origin()).isEqualTo("Checkstyle");
        assertThat(suggestion.code()).doesNotContain("import java.util.ArrayList");
        assertThat(suggestion.code()).contains("import java.util.List");
        assertThat(suggestion.code()).contains("public class Foo");
    }

    @Test
    // CheckstyleFixer entfernt Zeilen von unten nach oben (TreeSet reverseOrder), damit
    // Zeilenindizes durch vorherige Loeschungen nicht verschoben werden.
        void buildSuggestions_withMultipleUnusedImports_removesAll() {
        String code = """
                package com.example;

                import java.util.List;
                import java.util.ArrayList;
                import java.util.Map;

                public class Foo {}
                """;

        List<CheckstyleFinding> findings = List.of(
                new CheckstyleFinding("Foo.java", 3, 1, "Unused import - java.util.List", UNUSED_IMPORTS_CHECK),
                new CheckstyleFinding("Foo.java", 4, 1, "Unused import - java.util.ArrayList", UNUSED_IMPORTS_CHECK),
                new CheckstyleFinding("Foo.java", 5, 1, "Unused import - java.util.Map", UNUSED_IMPORTS_CHECK)
        );
        CheckstyleOutcome outcome = new CheckstyleOutcome(false, List.of(), findings);

        List<SolutionSuggestion> result = fixer.buildSuggestions(code, outcome);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).code())
                .doesNotContain("import java.util.List")
                .doesNotContain("import java.util.ArrayList")
                .doesNotContain("import java.util.Map")
                .contains("public class Foo");
    }

    @Test
    // Stern-Import-Aufloesung wurde entfernt (Sicherheitsrisiko: Class.forName mit aus
    // Fremdcode extrahierten Typ-Namen, siehe Klassen-Javadoc). AvoidStarImportCheck-Findings
    // duerfen deshalb zu keinem automatischen Fix-Vorschlag mehr fuehren.
    void buildSuggestions_withStarImport_producesNoSuggestion() {
        String code = """
                import java.util.*;

                public class Foo {
                    private List<String> items = new ArrayList<>();
                }
                """;

        CheckstyleFinding finding = new CheckstyleFinding(
                "Foo.java", 1, 1,
                "Using the '.*' form of import should be avoided - java.util.*",
                AVOID_STAR_IMPORT_CHECK
        );
        CheckstyleOutcome outcome = new CheckstyleOutcome(false, List.of(), List.of(finding));

        assertThat(fixer.buildSuggestions(code, outcome)).isEmpty();
    }
}
