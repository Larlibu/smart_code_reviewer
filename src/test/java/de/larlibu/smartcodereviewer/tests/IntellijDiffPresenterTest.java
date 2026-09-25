package de.larlibu.smartcodereviewer.tests;

import de.larlibu.smartcodereviewer.api.DiffViewerLauncher;
import de.larlibu.smartcodereviewer.model.SolutionSuggestion;
import de.larlibu.smartcodereviewer.pipeline.IntellijDiffPresenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;

public class IntellijDiffPresenterTest {

    private IntellijDiffPresenter presenter;

    @TempDir
    Path tempDir;

    // No-Op-Launcher verhindert, dass IntelliJ im Test tatsaechlich geoeffnet wird
    private static final DiffViewerLauncher NO_OP_LAUNCHER = (left, right) -> false;

    @BeforeEach
    void setUp() {
        presenter = new IntellijDiffPresenter(NO_OP_LAUNCHER);
    }

    @Test
    void present_withEmptySuggestions_doesNotThrow() throws IOException {
        Path targetFile = tempDir.resolve("Foo.java");
        Files.writeString(targetFile, "public class Foo {}");

        assertThatCode(() ->
                presenter.present(List.of(), targetFile, "public class Foo {}", "")
        ).doesNotThrowAnyException();
    }

    @Test
    void present_withSuggestionsButNoIntelliJ_doesNotThrow() throws IOException {
        Path targetFile = tempDir.resolve("Foo.java");
        String originalCode = "public class Foo {}";
        Files.writeString(targetFile, originalCode);

        SolutionSuggestion suggestion = new SolutionSuggestion(
                "KI",
                "Besser benennen",
                "Aussagekraeftigerer Klassenname",
                "public class OrderManager {}"
        );

        assertThatCode(() ->
                presenter.present(List.of(suggestion), targetFile, originalCode, "Summary: Test")
        ).doesNotThrowAnyException();
    }
}
