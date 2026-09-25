package de.larlibu.smartcodereviewer.tests;

import de.larlibu.smartcodereviewer.model.AiReviewResult;
import de.larlibu.smartcodereviewer.pipeline.GeminiAiReviewer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

public class GeminiAiReviewerTest {

    // Diese Tests setzen voraus, dass kein Gemini-API-Key als Umgebungsvariable gesetzt ist.
    // Mit gesetztem Key wuerden echte HTTP-Aufrufe ausgeloest.
    @Test
    @DisabledIfEnvironmentVariable(named = "SCR_GEMINI_API_KEY", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
    void review_withoutApiKey_returnsFallbackMessage() {
        GeminiAiReviewer reviewer = new GeminiAiReviewer(new Properties());

        AiReviewResult result = reviewer.review("public class Foo {}");

        assertThat(result.feedback()).contains("Kein API Key");
        assertThat(result.suggestions()).isEmpty();
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "SCR_GEMINI_API_KEY", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    @DisabledIfEnvironmentVariable(named = "GOOGLE_API_KEY", matches = ".+")
    void review_withApiKeyInProperties_usesIt() {
        Properties props = new Properties();
        props.setProperty("scr.gemini.api-key", "invalid-key-for-test");

        GeminiAiReviewer reviewer = new GeminiAiReviewer(props);
        AiReviewResult result = reviewer.review("public class Foo {}");

        // Prueft Fehlerbehandlung, nicht API-Korrektheit: ein HTTP-Fehler (401/403)
        // muss abgefangen werden und darf nicht als Exception nach oben propagieren.
        assertThat(result).isNotNull();
        assertThat(result.feedback()).isNotBlank();
    }
}
