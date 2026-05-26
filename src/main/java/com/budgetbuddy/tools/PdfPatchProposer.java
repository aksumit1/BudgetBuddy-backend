package com.budgetbuddy.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CLI that turns a captured diagnostic blob (from Layer 2) into a proposed YAML
 * template patch (Layer 3). Designed to run offline / in batch — no live
 * production traffic depends on it.
 *
 * <h3>Usage</h3>
 * <pre>
 *   mvn exec:java \
 *       -Dexec.mainClass=com.budgetbuddy.tools.PdfPatchProposer \
 *       -Dexec.args="/tmp/pdf-diagnostics/citi/2026-05/abc.json"
 * </pre>
 *
 * <p>With {@code -Dexec.args="--all /tmp/pdf-diagnostics/"} it processes every
 * blob in the tree.
 *
 * <h3>API</h3>
 * Requires {@code ANTHROPIC_API_KEY} environment variable. The CLI prints the
 * proposed patch to stdout and also writes it to a sibling
 * {@code patch_proposal.md} so a human can review and open a PR.
 *
 * <p>If the env var is missing, the CLI prints the prompt it WOULD have sent
 * and exits — useful for inspecting the prompt format and for offline dev.
 *
 * <h3>Output shape</h3>
 * The LLM is asked to return JSON like:
 * <pre>
 *   {
 *     "strategy": "yaml-template" | "regex-tweak" | "denylist-add" | "no-action",
 *     "summary": "one-line description",
 *     "patch": "full YAML or regex content",
 *     "rationale": "why this fixes the diagnostic",
 *     "test_input": "minimal input that reproduces the original failure",
 *     "test_expected": "expected parsed rows after the patch"
 *   }
 * </pre>
 */
public final class PdfPatchProposer {

    private static final String ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-opus-4-5";
    private static final int MAX_TOKENS = 4_096;

    private PdfPatchProposer() {}

    public static void main(final String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println(
                    "Usage:\n"
                    + "  PdfPatchProposer <diagnostic-json-path>\n"
                    + "  PdfPatchProposer --all <directory-of-diagnostics>\n"
                    + "  PdfPatchProposer --dry-run <diagnostic-json-path>\n");
            System.exit(2);
        }

        final boolean dryRun = arrayContains(args, "--dry-run");
        final boolean batch = arrayContains(args, "--all");

        final List<Path> diagnostics;
        if (batch) {
            final Path root = Paths.get(args[1]);
            diagnostics = new java.util.ArrayList<>();
            Files.walk(root)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(diagnostics::add);
        } else {
            diagnostics = List.of(Paths.get(args[args.length - 1]));
        }

        if (diagnostics.isEmpty()) {
            System.err.println("No diagnostic files to process");
            System.exit(1);
        }

        final ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        int proposed = 0;
        int skipped = 0;
        for (final Path diagPath : diagnostics) {
            System.out.println("=== " + diagPath + " ===");
            final String diagJson = Files.readString(diagPath, StandardCharsets.UTF_8);
            final String prompt = buildPrompt(diagJson);

            if (dryRun) {
                System.out.println(prompt);
                skipped++;
                continue;
            }

            final String apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                System.err.println(
                        "ANTHROPIC_API_KEY not set — printing the prompt that "
                        + "would have been sent and exiting.");
                System.out.println();
                System.out.println(prompt);
                skipped++;
                continue;
            }

            final String response = callAnthropic(prompt, apiKey);
            final Path outDir = diagPath.getParent();
            final String stem = diagPath.getFileName().toString().replace(".json", "");
            final Path patchPath = outDir.resolve(stem + ".patch_proposal.md");
            Files.writeString(patchPath, formatPatchMarkdown(response, diagPath),
                    StandardCharsets.UTF_8);
            System.out.println("Patch proposal written to: " + patchPath);
            proposed++;
        }

        System.out.println();
        System.out.printf("Done. Proposed: %d  Skipped: %d  Total: %d%n",
                proposed, skipped, diagnostics.size());
    }

    private static boolean arrayContains(final String[] arr, final String needle) {
        for (final String a : arr) if (needle.equals(a)) return true;
        return false;
    }

    /**
     * Build the structured prompt sent to the LLM. We intentionally include
     * (a) the redacted excerpt so the model can see the PDF layout, (b) the
     * declared vs parsed totals so it knows the gap shape, (c) the existing
     * YAML schema so it can mimic patterns, and (d) clear output constraints
     * so we get back machine-applicable patches.
     */
    static String buildPrompt(final String diagJson) {
        return ""
            + "You are a regex and YAML template expert helping a financial-PDF parser self-heal.\n"
            + "Below is a redacted diagnostic from a failed parse: the PDF excerpt, the issuer's\n"
            + "printed section totals, the parsed transactions, and the reconciliation deltas\n"
            + "between expected and actual sums.\n\n"
            + "Your job: propose ONE patch that closes the gap. The patch can be:\n"
            + "  - strategy=yaml-template : a new or updated YAML template entry in `pdf-templates/`\n"
            + "  - strategy=regex-tweak   : a regex modification to an existing template's layout\n"
            + "  - strategy=denylist-add  : add a token to NON_NAME_BUSINESS_TOKENS or similar\n"
            + "  - strategy=no-action     : the diagnostic is a parser-correct failure (false positive)\n\n"
            + "Rules:\n"
            + "  - Be conservative: a patch that overfits the diagnostic will break the 42-PDF corpus.\n"
            + "  - Prefer YAML over regex tweaks; prefer regex tweaks over denylist additions.\n"
            + "  - Provide test_input that reproduces the failure (extract from the excerpt).\n"
            + "  - Provide test_expected as the array of (date, amount, description) tuples that\n"
            + "    SHOULD have been extracted.\n\n"
            + "Output STRICT JSON only, no markdown fences. Schema:\n"
            + "  {\n"
            + "    \"strategy\": \"yaml-template|regex-tweak|denylist-add|no-action\",\n"
            + "    \"summary\": \"one-line description\",\n"
            + "    \"patch\": \"the patch contents — full YAML if yaml-template, diff if regex-tweak\",\n"
            + "    \"target_file\": \"e.g. pdf-templates/chase.yaml\",\n"
            + "    \"rationale\": \"why this closes the reconciliation gap\",\n"
            + "    \"test_input\": \"verbatim text lines that exercised the bug\",\n"
            + "    \"test_expected\": [{\"date\":\"YYYY-MM-DD\",\"amount\":\"D.CC\",\"description\":\"...\"}]\n"
            + "  }\n\n"
            + "Diagnostic blob (redacted):\n"
            + "------\n"
            + diagJson
            + "\n------\n"
            + "Return JSON only.\n";
    }

    /**
     * Calls Anthropic's Messages API. Uses Java's built-in HTTP client to avoid
     * adding a new dependency just for this tool.
     */
    private static String callAnthropic(final String prompt, final String apiKey)
            throws IOException, InterruptedException {
        final ObjectMapper mapper = new ObjectMapper();
        final Map<String, Object> body = new HashMap<>();
        body.put("model", System.getenv().getOrDefault("ANTHROPIC_MODEL", DEFAULT_MODEL));
        body.put("max_tokens", MAX_TOKENS);
        body.put("messages", List.of(
                Map.of("role", "user", "content", prompt)));
        final String json = mapper.writeValueAsString(body);

        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_ENDPOINT))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> resp = client.send(
                    req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IOException(
                        "Anthropic API HTTP " + resp.statusCode() + ": " + resp.body());
            }
            // Extract the content[].text from the response — Anthropic returns
            // an object with content as an array of typed blocks.
            final Map<String, Object> obj = mapper.readValue(
                    resp.body(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            final Object content = obj.get("content");
            if (content instanceof List<?> list && !list.isEmpty()) {
                final Object block = list.getFirst();
                if (block instanceof Map<?, ?> bm) {
                    final Object text = bm.get("text");
                    if (text != null) return text.toString();
                }
            }
            return resp.body();
        }
    }

    static String formatPatchMarkdown(final String llmResponse, final Path diagPath) {
        return "# PDF Parser Patch Proposal\n\n"
                + "Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME) + "\n\n"
                + "Diagnostic source: `" + diagPath + "`\n\n"
                + "## LLM Output\n\n"
                + "```json\n"
                + llmResponse + "\n"
                + "```\n\n"
                + "## Reviewer checklist\n"
                + "- [ ] Patch applies cleanly to current head\n"
                + "- [ ] Diagnostic's excerpt now parses to `test_expected` after patch\n"
                + "- [ ] Full 42-PDF audit (`PdfFullAuditTest`) still passes\n"
                + "- [ ] All existing unit/integration tests pass\n"
                + "- [ ] Auto-generated regression test added to `src/test/java/.../diagnostics/`\n";
    }
}
