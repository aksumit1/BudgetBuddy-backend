package com.budgetbuddy.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests for the prompt-building / markdown formatting parts of the proposer.
 * The HTTP call is integration-only and exercised manually via
 * {@code -Dexec.mainClass=com.budgetbuddy.tools.PdfPatchProposer}.
 */
class PdfPatchProposerTest {

    @Test
    void promptContainsCriticalFraming() {
        final String diagJson = "{\"reconciliation\":[{\"bucket\":\"debit\",\"delta\":-13.06}]}";
        final String prompt = PdfPatchProposer.buildPrompt(diagJson);

        assertTrue(prompt.contains("regex and YAML template expert"),
                "Prompt should establish expertise framing");
        assertTrue(prompt.contains("yaml-template"),
                "Prompt should list available strategies");
        assertTrue(prompt.contains("Return JSON only"),
                "Prompt should request structured output");
        assertTrue(prompt.contains(diagJson),
                "Prompt should embed the diagnostic blob verbatim");
        assertTrue(prompt.contains("conservative"),
                "Prompt should warn against overfitting");
        assertTrue(prompt.contains("test_input") && prompt.contains("test_expected"),
                "Prompt should ask for a reproduction case");
    }

    @Test
    void markdownIncludesReviewerChecklist() {
        final String md = PdfPatchProposer.formatPatchMarkdown(
                "{\"strategy\":\"yaml-template\",\"summary\":\"add Chase FX layout\"}",
                Path.of("/tmp/diag.json"));

        assertTrue(md.contains("PDF Parser Patch Proposal"));
        assertTrue(md.contains("Reviewer checklist"));
        assertTrue(md.contains("42-PDF audit"),
                "Markdown should remind reviewer to run full corpus audit");
        assertTrue(md.contains("/tmp/diag.json"),
                "Markdown should reference the source diagnostic");
    }
}
