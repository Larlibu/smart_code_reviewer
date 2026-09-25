package de.larlibu.smartcodereviewer.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.larlibu.smartcodereviewer.api.AiReviewer;
import de.larlibu.smartcodereviewer.model.AiReviewResult;
import de.larlibu.smartcodereviewer.model.SolutionSuggestion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Schritt 3 der Pipeline: schickt den Java-Code an die Gemini-API und wandelt die
 * JSON-Antwort in ein {@link AiReviewResult} (Feedback-Text + Liste von
 * {@link SolutionSuggestion}s) um.
 *
 * <p>Konfiguration (API-Key, Modell, Basis-URL) wird ueber Umgebungsvariablen bzw.
 * {@code application.properties} aufgeloest (siehe {@link #getGeminiApiKey()} und
 * {@link #callGemini(String, String)}). Ist kein API-Key vorhanden oder schlaegt der
 * Aufruf endgueltig fehl, wird kein Fehler geworfen, sondern ein {@link AiReviewResult}
 * mit erklaerendem Feedback und leerer Vorschlagsliste zurueckgegeben — die Pipeline
 * laeuft dann ohne KI-Vorschlaege weiter (siehe {@code CheckstyleFixer} als unabhaengige
 * Vorschlagsquelle).</p>
 */
public class GeminiAiReviewer implements AiReviewer {

    private static final Logger LOGGER = LogManager.getLogger(GeminiAiReviewer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";
    private static final int GEMINI_MAX_RETRY_ATTEMPTS = 3;
    private static final long GEMINI_RETRY_BASE_DELAY_MS = 1200L;
    private static final long GEMINI_RETRY_MAX_DELAY_MS = 8000L;
    private static final List<String> GEMINI_MODEL_FALLBACKS = List.of(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-flash"
    );

    private final Properties appProperties;

    /**
     * @param appProperties geladene {@code application.properties}, dient als unterste
     *                       Fallback-Ebene fuer API-Key, Modell und Basis-URL
     *                       (Umgebungsvariablen haben Vorrang, siehe {@link #getGeminiApiKey()}).
     */
    public GeminiAiReviewer(Properties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Fuehrt den KI-Review fuer {@code code} aus. Gibt bei fehlendem API-Key oder
     * nicht erreichbarer API ein {@link AiReviewResult} mit erklaerendem Feedback und
     * leerer Vorschlagsliste zurueck, statt eine Exception zu werfen.
     */
    @Override
    public AiReviewResult review(String code) {
        LOGGER.info("[Step 2] Analysiere Logik und Sicherheitsluecken...");

        String apiKey = getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.warn("Kein API Key gesetzt. Es koennen keine echten Vorschlaege erzeugt werden.");
            return new AiReviewResult(
                    "Kein API Key uebergeben. Setze SCR_GEMINI_API_KEY oder scr.gemini.api-key in application.properties.",
                    List.of()
            );
        }

        try {
            String rawResponse = callGemini(apiKey, code);
            return parseAiReview(rawResponse);
        } catch (Exception e) {
            LOGGER.error("Gemini API konnte nicht aufgerufen werden: {}", e.getMessage());
            return new AiReviewResult("Gemini API konnte nicht aufgerufen werden: " + e.getMessage(), List.of());
        }
    }

    private String getGeminiApiKey() {
        return firstNonBlank(
                System.getenv("SCR_GEMINI_API_KEY"),
                System.getenv("GEMINI_API_KEY"),
                System.getenv("GOOGLE_API_KEY"),
                appProperties.getProperty("scr.gemini.api-key")
        );
    }

    /**
     * Implementiert einen zweistufigen Fallback-Mechanismus: Zuerst werden verschiedene
     * API-URLs probiert (v1/v1beta), innerhalb jeder URL werden Modellkandidaten in
     * Prioritaetsreihenfolge versucht. Transiente Fehler (429, 5xx) werden mit
     * exponentiellem Backoff wiederholt.
     */
    private String callGemini(String apiKey, String code) throws Exception {
        String configuredApiUrl = firstNonBlank(
                System.getenv("SCR_GEMINI_API_URL"),
                appProperties.getProperty("scr.gemini.api-url"),
                DEFAULT_GEMINI_API_URL
        );
        String configuredModel = normalizeModelName(firstNonBlank(
                System.getenv("SCR_GEMINI_MODEL"),
                appProperties.getProperty("scr.gemini.model"),
                DEFAULT_GEMINI_MODEL
        ));

        String prompt = """
                Du bist ein Senior Java Code Reviewer.
                Analysiere den folgenden Java-Code auf Logik, Security und Bad Practices.
                Antworte AUSSCHLIESSLICH als gueltiges JSON (kein Markdown, keine Backticks):
                {
                  "summary": "kurze Zusammenfassung",
                  "findings": [
                    {
                      "problem": "was ist falsch",
                      "risk": "warum ist das kritisch",
                      "fix": "konkreter Fix"
                    }
                  ],
                  "suggestions": [
                    {
                      "title": "kurzer Titel",
                      "rationale": "warum dieser Vorschlag sinnvoll ist",
                      "code": "vollstaendiger verbesserter Java-Code fuer die Datei"
                    }
                  ]
                }

                Regeln:
                - suggestions darf leer sein, wenn kein sicherer Fix moeglich ist.
                - code muss vollstaendig sein und die bestehende Klasse weiterhin enthalten.
                - Gib maximal 3 suggestions.

                Code:
                %s
                """.formatted(code);

        String body = """
                {
                  "contents": [
                    { "role": "user", "parts": [ { "text": %s } ] }
                  ],
                  "generationConfig": { "temperature": 0.2 }
                }
                """.formatted(OBJECT_MAPPER.writeValueAsString(prompt));

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        List<String> attemptErrors = new ArrayList<>();
        for (String apiUrlCandidate : buildApiUrlCandidates(configuredApiUrl)) {
            List<String> discoveredModels = listGenerateContentModels(httpClient, apiKey, apiUrlCandidate);
            List<String> modelCandidates = buildModelCandidates(configuredModel, discoveredModels);

            for (String modelCandidate : modelCandidates) {
                String endpoint = apiUrlCandidate + "/models/" + modelCandidate + ":generateContent?key=" + apiKey;
                LOGGER.debug("Gemini Endpoint: {}", apiUrlCandidate + "/models/" + modelCandidate + ":generateContent?key=***");
                int retryAttempt = 0;
                // while(true) steuert den Retry-Loop; break verlässt ihn beim Modell-Wechsel
                while (true) {
                    try {
                        return executeGenerateContentRequest(httpClient, endpoint, body);
                    } catch (GeminiHttpException e) {
                        attemptErrors.add(apiUrlCandidate + "/models/" + modelCandidate + " -> HTTP " + e.statusCode());
                        if (e.statusCode() == 404) {
                            LOGGER.warn("Gemini-Model/API nicht verfuegbar ({} auf {}). Versuche Fallback.", modelCandidate, apiUrlCandidate);
                            break;
                        }
                        if (isRetryableStatus(e.statusCode()) && retryAttempt < GEMINI_MAX_RETRY_ATTEMPTS) {
                            retryAttempt++;
                            long backoffMs = computeRetryBackoffMillis(retryAttempt);
                            LOGGER.warn(
                                    "Temporarer Gemini-Fehler HTTP {} bei {}. Retry {}/{} in {} ms.",
                                    e.statusCode(), modelCandidate, retryAttempt, GEMINI_MAX_RETRY_ATTEMPTS, backoffMs
                            );
                            sleepForRetry(backoffMs);
                            continue;
                        }
                        if (isRetryableStatus(e.statusCode())) {
                            LOGGER.warn("Gemini bleibt temporaer nicht verfuegbar (HTTP {}). Wechsle auf naechsten Kandidaten.", e.statusCode());
                            break;
                        }
                        throw e;
                    }
                }
            }
        }

        throw new IOException("Kein kompatibles Gemini-Modell gefunden. Versucht: " + String.join(", ", attemptErrors));
    }

    private String executeGenerateContentRequest(HttpClient httpClient, String endpoint, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String details = response.body() == null ? "" : response.body().trim();
            if (details.length() > 800) {
                details = details.substring(0, 800) + "...";
            }
            throw new GeminiHttpException(response.statusCode(), details);
        }

        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        return text.isBlank() ? response.body() : text;
    }

    /**
     * 429 (Rate Limit) und 500/502/503/504 gelten als transient.
     * 4xx ausser 429 (z. B. 401, 403, 404) sind permanent und werden nicht wiederholt.
     */
    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private long computeRetryBackoffMillis(int retryAttempt) {
        // Bit-Shift verdoppelt die Basisverzoegerung mit jedem Versuch: 1200, 2400, 4800, ...
        long exponentialDelay = GEMINI_RETRY_BASE_DELAY_MS * (1L << Math.max(0, retryAttempt - 1));
        long cappedDelay = Math.min(exponentialDelay, GEMINI_RETRY_MAX_DELAY_MS);
        // Jitter im Bereich [200, 800] ms verhindert synchronisierte Retry-Stuerme
        long jitter = ThreadLocalRandom.current().nextLong(200L, 801L);
        return cappedDelay + jitter;
    }

    private void sleepForRetry(long delayMillis) throws IOException {
        try {
            Thread.sleep(Math.max(0L, delayMillis));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Retry unterbrochen.", interrupted);
        }
    }

    private List<String> buildApiUrlCandidates(String configuredApiUrl) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        String normalized = configuredApiUrl == null ? "" : configuredApiUrl.trim().replaceAll("/+$", "");
        if (!normalized.isBlank()) {
            urls.add(normalized);
            if (normalized.endsWith("/v1")) {
                urls.add(normalized.substring(0, normalized.length() - 3) + "/v1beta");
            } else if (normalized.endsWith("/v1beta")) {
                urls.add(normalized.substring(0, normalized.length() - 7) + "/v1");
            }
        }
        urls.add(DEFAULT_GEMINI_API_URL);
        urls.add("https://generativelanguage.googleapis.com/v1beta");
        return new ArrayList<>(urls);
    }

    private List<String> buildModelCandidates(String configuredModel, List<String> discoveredModels) {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (configuredModel != null && !configuredModel.isBlank()) {
            models.add(configuredModel);
        }
        models.addAll(GEMINI_MODEL_FALLBACKS);
        for (String discoveredModel : discoveredModels) {
            String normalized = normalizeModelName(discoveredModel);
            if (!normalized.isBlank()) {
                models.add(normalized);
            }
        }
        return new ArrayList<>(models);
    }

    private List<String> listGenerateContentModels(HttpClient httpClient, String apiKey, String apiUrl) {
        String url = apiUrl + "/models?key=" + apiKey;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }

            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            JsonNode modelsNode = root.path("models");
            if (!modelsNode.isArray()) {
                return List.of();
            }

            List<String> discoveredModels = new ArrayList<>();
            for (JsonNode modelNode : modelsNode) {
                boolean supportsGenerateContent = false;
                JsonNode methodsNode = modelNode.path("supportedGenerationMethods");
                if (methodsNode.isArray()) {
                    for (JsonNode methodNode : methodsNode) {
                        if ("generateContent".equals(methodNode.asText())) {
                            supportsGenerateContent = true;
                            break;
                        }
                    }
                }
                if (!supportsGenerateContent) {
                    continue;
                }
                String modelName = normalizeModelName(modelNode.path("name").asText(""));
                if (!modelName.isBlank()) {
                    discoveredModels.add(modelName);
                }
            }
            return discoveredModels;
        } catch (Exception e) {
            LOGGER.debug("Konnte Modellliste fuer {} nicht abrufen: {}", apiUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * Die Gemini-API liefert Modellnamen im Format {@code models/gemini-2.5-flash},
     * der Endpunkt erwartet jedoch nur {@code gemini-2.5-flash}.
     */
    private String normalizeModelName(String modelName) {
        if (modelName == null) {
            return "";
        }
        String normalized = modelName.trim();
        if (normalized.startsWith("models/")) {
            normalized = normalized.substring("models/".length());
        }
        return normalized;
    }

    private AiReviewResult parseAiReview(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return new AiReviewResult("Leere Antwort von der KI.", List.of());
        }

        String jsonPayload = extractJsonPayload(rawResponse);
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonPayload);
            String feedback = buildFeedbackText(root);
            List<SolutionSuggestion> suggestions = parseSuggestions(root.path("suggestions"));
            if (feedback.isBlank()) {
                feedback = rawResponse;
            }
            return new AiReviewResult(feedback, suggestions);
        } catch (Exception parseError) {
            LOGGER.warn("KI-Antwort war kein gueltiges JSON. Nutze Rohtext als Feedback.");
            return new AiReviewResult(rawResponse, List.of());
        }
    }

    private String buildFeedbackText(JsonNode root) {
        StringBuilder builder = new StringBuilder();

        String summary = root.path("summary").asText("");
        if (!summary.isBlank()) {
            builder.append("Summary: ").append(summary).append(System.lineSeparator());
        }

        JsonNode findings = root.path("findings");
        if (findings.isArray() && !findings.isEmpty()) {
            builder.append("Findings:").append(System.lineSeparator());
            for (JsonNode finding : findings) {
                builder.append("- Problem: ").append(finding.path("problem").asText("")).append(System.lineSeparator());
                builder.append("  Risiko: ").append(finding.path("risk").asText("")).append(System.lineSeparator());
                builder.append("  Fix: ").append(finding.path("fix").asText("")).append(System.lineSeparator());
            }
        }

        return builder.toString().trim();
    }

    /**
     * Vorschlaege ohne Code-Inhalt werden uebersprungen, da sie im Diff-Viewer nutzlos waeren.
     */
    private List<SolutionSuggestion> parseSuggestions(JsonNode suggestionsNode) {
        List<SolutionSuggestion> suggestions = new ArrayList<>();
        if (!suggestionsNode.isArray()) {
            return suggestions;
        }

        int index = 1;
        for (JsonNode node : suggestionsNode) {
            String code = stripCodeFences(node.path("code").asText("").trim());
            if (code.isBlank()) {
                continue;
            }
            String title = node.path("title").asText("").trim();
            if (title.isBlank()) {
                title = "Vorschlag " + index;
            }
            suggestions.add(new SolutionSuggestion("KI", title, node.path("rationale").asText("").trim(), code));
            index++;
        }
        return suggestions;
    }

    /**
     * Trotz der Anweisung "kein Markdown" umhuellt das Modell die Antwort gelegentlich
     * mit Backtick-Fences. Diese werden hier entfernt.
     */
    private String extractJsonPayload(String rawResponse) {
        String text = rawResponse.trim();
        if (text.startsWith("```")) {
            int firstLineBreak = text.indexOf('\n');
            if (firstLineBreak >= 0) {
                text = text.substring(firstLineBreak + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String stripCodeFences(String code) {
        String result = code;
        if (result.startsWith("```")) {
            int firstLineBreak = result.indexOf('\n');
            if (firstLineBreak >= 0) {
                result = result.substring(firstLineBreak + 1);
            }
        }
        if (result.endsWith("```")) {
            result = result.substring(0, result.length() - 3);
        }
        return result.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Traegt den HTTP-Statuscode einer fehlgeschlagenen Gemini-Antwort, damit
     * {@link #callGemini(String, String)} zwischen retry-faehigen (429, 5xx) und
     * permanenten Fehlern unterscheiden kann (siehe {@link #isRetryableStatus(int)}).
     */
    private static final class GeminiHttpException extends IOException {
        private final int statusCode;

        private GeminiHttpException(int statusCode, String details) {
            super("HTTP " + statusCode + (details == null || details.isBlank() ? "" : ": " + details));
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }
}
