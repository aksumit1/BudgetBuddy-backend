package com.budgetbuddy.service.pdf.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI-assisted YAML template author. When a new bank's first PDF arrives
 * and no V2 template exists, this service feeds the raw text plus 3
 * example templates to Claude and returns a draft YAML for human review.
 *
 * <p>Workflow:
 * <pre>
 *   1. ParseFailureTracker detects "no matching template for institution X"
 *   2. This service is invoked with (institution, raw PDF text, sample size)
 *   3. The draft YAML is written to:
 *      ${pdf.archive.root}/_draft-templates/&lt;institution&gt;-&lt;yyyymmdd&gt;.yaml
 *   4. The dev team reviews + promotes into pdf-templates-v2/&lt;issuer&gt;.yaml
 * </pre>
 *
 * <p>NEVER auto-promotes. Drafts are advisory only — the V2 YAML system
 * is load-bearing, so a bad template could silently misparse hundreds of
 * historical statements. Human-in-the-loop is by design.
 *
 * <p>Activation: {@code app.pdf.ai-yaml-author.enabled=true} +
 * {@code ANTHROPIC_API_KEY} env var. Off by default.
 */
@Service
@ConditionalOnProperty(
        name = "app.pdf.ai-yaml-author.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AiYamlTemplateAuthor {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiYamlTemplateAuthor.class);
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${app.pdf.ai-yaml-author.api-key:}")
    private String apiKey;

    @Value("${app.pdf.ai-yaml-author.model:claude-sonnet-4-6}")
    private String model;

    @Value("${app.pdf.ai-yaml-author.example-templates-dir:classpath:pdf-templates-v2}")
    private String exampleTemplatesDir;

    @Value("${app.pdf.ai-yaml-author.draft-output-dir:/tmp/pdf-archive/_draft-templates}")
    private String draftOutputDir;

    @Value("${app.pdf.ai-yaml-author.timeout-seconds:60}")
    private int timeoutSeconds;

    @Value("${app.pdf.ai-yaml-author.max-text-chars:30000}")
    private int maxTextChars;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Generate a draft YAML template for an unknown issuer and persist it.
     * Returns the path to the draft file on success, null on any failure
     * (caller treats null as "AI suggestion unavailable", no user impact).
     */
    public Path generateDraft(final String institution, final String pdfText) {
        if (apiKey == null || apiKey.isBlank() || institution == null || pdfText == null) {
            return null;
        }
        try {
            final String prompt = buildPrompt(institution, pdfText);
            final String yamlContent = callAnthropic(prompt);
            if (yamlContent == null || yamlContent.isBlank()) return null;
            return writeDraft(institution, yamlContent);
        } catch (final Exception e) {
            LOGGER.warn("AiYamlTemplateAuthor failed for {}: {}", institution, e.getMessage());
            return null;
        }
    }

    private String buildPrompt(final String institution, final String pdfText) throws Exception {
        // Load 3 existing templates as examples for the model. They're
        // packaged in src/main/resources/pdf-templates-v2/ — read directly
        // since this code runs server-side with classpath access.
        final List<String> examples = loadExampleTemplates();
        final StringBuilder sb = new StringBuilder(8192);
        sb.append("You are authoring a draft YAML template for the BudgetBuddy ")
          .append("PDF import system. The system parses bank/credit-card ")
          .append("statements into transactions using YAML-defined extraction rules.\n\n");
        sb.append("ISSUER: ").append(institution).append("\n\n");
        sb.append("EXAMPLES OF EXISTING TEMPLATES:\n\n");
        for (int i = 0; i < examples.size(); i++) {
            sb.append("--- Example ").append(i + 1).append(" ---\n");
            sb.append(examples.get(i)).append("\n\n");
        }
        sb.append("--- PDF TEXT (first ")
          .append(maxTextChars).append(" chars) ---\n");
        sb.append(pdfText.substring(0, Math.min(maxTextChars, pdfText.length())))
          .append("\n\n");
        sb.append("Produce a YAML template that:\n");
        sb.append("  - Detects this issuer's card via card_detection.institution_patterns\n");
        sb.append("  - Extracts statement metadata (newBalance, previousBalance, etc.)\n");
        sb.append("  - Extracts transactions via a transactions: block\n");
        sb.append("  - Uses extends: [common] when the patterns overlap\n");
        sb.append("Output ONLY the YAML — no preface, no markdown fences, ");
        sb.append("no commentary. The output goes straight to a .yaml file.\n");
        return sb.toString();
    }

    private List<String> loadExampleTemplates() throws Exception {
        // Pick a representative set: a credit-card (chase), a checking
        // (chase-checking), and a card with family-card support (american-express).
        final org.springframework.core.io.support.PathMatchingResourcePatternResolver resolver =
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        final org.springframework.core.io.Resource[] resources = resolver.getResources(
                exampleTemplatesDir + "/*.yaml");
        final List<String> picked = new java.util.ArrayList<>();
        for (final org.springframework.core.io.Resource r : resources) {
            final String name = r.getFilename();
            if (name == null) continue;
            if (name.equals("chase.yaml") || name.equals("chase-checking.yaml")
                    || name.equals("american-express.yaml")) {
                try (var in = r.getInputStream()) {
                    picked.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            if (picked.size() >= 3) break;
        }
        return picked;
    }

    private String callAnthropic(final String prompt) throws Exception {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 4000);
        final ArrayNode messages = body.putArray("messages");
        final ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                        StandardCharsets.UTF_8))
                .build();

        final HttpResponse<String> resp = client.send(
                request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            LOGGER.warn("AiYamlTemplateAuthor: Anthropic returned {}: {}",
                    resp.statusCode(),
                    resp.body() == null ? "" : resp.body().substring(
                            0, Math.min(200, resp.body().length())));
            return null;
        }
        final JsonNode root = mapper.readTree(resp.body());
        final JsonNode content = root.path("content");
        if (!content.isArray() || content.size() == 0) return null;
        final JsonNode firstBlock = content.get(0);
        final String text = firstBlock.path("text").asText("");
        // Strip markdown fences if the model added them despite the prompt.
        return text.replaceAll("```ya?ml\\s*\\n", "")
                .replaceAll("```\\s*$", "")
                .trim();
    }

    private Path writeDraft(final String institution, final String yamlContent) throws Exception {
        final Path dir = Paths.get(draftOutputDir);
        Files.createDirectories(dir);
        final String fname = institution.toLowerCase().replaceAll("[^a-z0-9]+", "-")
                + "-" + LocalDate.now() + ".draft.yaml";
        final Path out = dir.resolve(fname);
        Files.writeString(out, yamlContent, StandardCharsets.UTF_8);
        LOGGER.info("AiYamlTemplateAuthor draft written: {}", out);
        return out;
    }
}
