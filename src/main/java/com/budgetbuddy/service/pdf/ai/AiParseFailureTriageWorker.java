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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily triage job that reads recent parse failures from the diagnostic
 * store, pulls the matching raw PDFs from the archive, and asks Claude
 * to propose root cause + suggested fix. Output goes to a per-failure
 * markdown file in {@code _triage-reports/}.
 *
 * <p>The reports are advisory — they don't auto-apply fixes. They just
 * cut the manual-triage tax: instead of opening every failed PDF and
 * diffing against neighboring templates, the dev team sees a focused
 * "here's what changed" summary by morning.
 *
 * <p>Activation: {@code app.pdf.ai-triage.enabled=true} +
 * {@code ANTHROPIC_API_KEY} env var. Off by default.
 *
 * <p>Cron: daily at 04:00 local — overlaps with SelfLearningWorker's
 * 03:30 LLM-suggestion run so daily Anthropic spend lands in one window.
 */
@Service
@ConditionalOnProperty(
        name = "app.pdf.ai-triage.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AiParseFailureTriageWorker {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiParseFailureTriageWorker.class);
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${app.pdf.ai-triage.api-key:}")
    private String apiKey;

    @Value("${app.pdf.ai-triage.model:claude-sonnet-4-6}")
    private String model;

    @Value("${app.pdf.ai-triage.diagnostic-root:/tmp/pdf-diagnostics}")
    private String diagnosticRoot;

    @Value("${app.pdf.ai-triage.archive-root:/tmp/pdf-archive}")
    private String archiveRoot;

    @Value("${app.pdf.ai-triage.output-dir:/tmp/pdf-archive/_triage-reports}")
    private String outputDir;

    @Value("${app.pdf.ai-triage.max-reports-per-run:10}")
    private int maxReportsPerRun;

    @Value("${app.pdf.ai-triage.timeout-seconds:60}")
    private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Scheduled(cron = "${app.pdf.ai-triage.cron:0 0 4 * * *}")
    public void runTriage() {
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.warn("AiParseFailureTriageWorker: api-key not set, skipping run");
            return;
        }
        final Path diagDir = Paths.get(diagnosticRoot);
        if (!Files.isDirectory(diagDir)) {
            LOGGER.info("AiParseFailureTriageWorker: no diagnostic dir at {}, nothing to triage",
                    diagDir);
            return;
        }
        try (var stream = Files.walk(diagDir)) {
            final var failures = stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(this::isRecentAndUntriaged)
                    .limit(maxReportsPerRun)
                    .toList();
            for (final Path failure : failures) {
                try {
                    triageOne(failure);
                } catch (final Exception e) {
                    LOGGER.warn("triage failed for {}: {}", failure, e.getMessage());
                }
            }
            LOGGER.info("AiParseFailureTriageWorker: processed {} failures", failures.size());
        } catch (final Exception e) {
            LOGGER.warn("AiParseFailureTriageWorker run failed: {}", e.getMessage());
        }
    }

    private boolean isRecentAndUntriaged(final Path diagnostic) {
        try {
            // Only process diagnostics from the last 24h. Older failures
            // were either already triaged or are no longer actionable.
            final var attrs = Files.readAttributes(diagnostic,
                    java.nio.file.attribute.BasicFileAttributes.class);
            final long ageMs = System.currentTimeMillis() - attrs.creationTime().toMillis();
            if (ageMs > 24L * 60 * 60 * 1000) return false;
            // Skip if a triage report already exists for this hash.
            final String hash = diagnostic.getFileName().toString().replace(".json", "");
            final Path report = Paths.get(outputDir, hash + ".md");
            return !Files.exists(report);
        } catch (final Exception e) {
            return false;
        }
    }

    private void triageOne(final Path diagnostic) throws Exception {
        final String hash = diagnostic.getFileName().toString().replace(".json", "");
        // Diagnostic path: <root>/<institution>/<yyyy-MM>/<hash>.json
        // Locate raw PDF: <archiveRoot>/<institution>/<yyyy-MM>/<hash>.pdf
        // (same path scheme thanks to PdfDiagnosticCorrelation)
        final Path relative = Paths.get(diagnosticRoot).relativize(diagnostic);
        final Path pdfPath = Paths.get(archiveRoot,
                relative.toString().replace(".json", ".pdf"));

        final String diagJson = Files.readString(diagnostic, StandardCharsets.UTF_8);
        // We DON'T send the raw PDF bytes (PII + size). The diagnostic
        // JSON already contains the redacted text excerpt that's enough
        // for root-cause analysis.
        final String prompt = buildTriagePrompt(diagJson,
                Files.exists(pdfPath) ? pdfPath.toString() : null);
        final String report = callAnthropic(prompt);
        if (report == null || report.isBlank()) return;
        writeReport(hash, report);
    }

    private String buildTriagePrompt(final String diagnosticJson, final String pdfPath) {
        return "You're triaging a BudgetBuddy PDF import failure. "
                + "Given the redacted diagnostic JSON below, identify:\n"
                + "  1. Root cause (most likely): regex mismatch / new format / "
                + "issuer change / OCR garbage / etc.\n"
                + "  2. Confidence: high / medium / low\n"
                + "  3. Suggested fix: a specific YAML-rule diff, "
                + "extractor-code change, or 'needs manual review'\n"
                + "  4. Severity: blocks all this issuer's imports, "
                + "this one file only, or single field on this file\n\n"
                + "Diagnostic JSON:\n```\n"
                + diagnosticJson + "\n```\n\n"
                + (pdfPath == null ? "Raw PDF: not archived\n"
                        : "Raw PDF available at: " + pdfPath + "\n")
                + "\nFormat: markdown, ≤300 words. "
                + "This goes to a human reviewer who will apply the fix.";
    }

    private String callAnthropic(final String prompt) throws Exception {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 800);
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
            LOGGER.warn("triage: Anthropic returned {}", resp.statusCode());
            return null;
        }
        final JsonNode root = mapper.readTree(resp.body());
        return root.path("content").path(0).path("text").asText("");
    }

    private void writeReport(final String hash, final String content) throws Exception {
        final Path dir = Paths.get(outputDir);
        Files.createDirectories(dir);
        final Path out = dir.resolve(hash + ".md");
        final String header = "# Triage report: " + hash + "\n"
                + "_Generated: " + LocalDate.now() + " by AiParseFailureTriageWorker_\n\n";
        Files.writeString(out, header + content, StandardCharsets.UTF_8);
        LOGGER.info("Triage report written: {}", out);
    }
}
