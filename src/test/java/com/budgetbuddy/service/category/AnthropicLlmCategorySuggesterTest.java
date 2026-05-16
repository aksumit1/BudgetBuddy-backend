package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the parser side of {@link AnthropicLlmCategorySuggester} —
 * the part that consumes whatever the LLM actually returns. We don't
 * hit the real Anthropic endpoint here (that's an integration concern);
 * we lock in the response-body parsing contract:
 *
 * <ul>
 *   <li>Clean JSON object → CategoryResult with category + confidence + source.
 *   <li>JSON wrapped in prose → still parsed (we extract by brace boundaries).
 *   <li>Hallucinated category not in allow-list → null.
 *   <li>Confidence ≤0 or &gt;1 → null (don't trust malformed numerics).
 *   <li>Empty content block → null, no crash.
 *   <li>Malformed JSON → null, no crash.
 *   <li>Multiple text blocks → first one wins, not crash.
 * </ul>
 */
class AnthropicLlmCategorySuggesterTest {

    private final AnthropicLlmCategorySuggester suggester =
            new AnthropicLlmCategorySuggester(
                    "https://api.anthropic.com/v1/messages",
                    "test-key",
                    "claude-haiku-4-5-20251001",
                    10);

    @Test
    @DisplayName("Clean JSON response → CategoryResult")
    void cleanJsonParsed() {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"{\\\"category\\\":\\\"dining\\\","
                        + "\\\"confidence\\\":0.92,"
                        + "\\\"reasoning\\\":\\\"Thai restaurant in Bellevue\\\"}\""
                        + "}]}";
        CategoryResult r = suggester.parseSuggestion(body, "Mystery Thai");
        assertEquals("dining", r.getCategoryPrimary());
        assertEquals(0.92, r.getConfidence(), 0.001);
        assertTrue(r.getSource().startsWith("LLM_ANTHROPIC"));
    }

    @Test
    @DisplayName("JSON wrapped in prose still parses")
    void jsonInProseStillParses() {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"Here is the categorisation:\\n"
                        + "{\\\"category\\\":\\\"groceries\\\","
                        + "\\\"confidence\\\":0.88,"
                        + "\\\"reasoning\\\":\\\"Indian grocery store\\\"}\\n"
                        + "Hope that helps!\""
                        + "}]}";
        CategoryResult r = suggester.parseSuggestion(body, "Mayuri Foods");
        assertEquals("groceries", r.getCategoryPrimary());
        assertEquals(0.88, r.getConfidence(), 0.001);
    }

    @Test
    @DisplayName("Hallucinated category not in allow-list → null")
    void hallucinatedCategoryRejected() {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"{\\\"category\\\":\\\"esoteric_minerals\\\","
                        + "\\\"confidence\\\":0.95,"
                        + "\\\"reasoning\\\":\\\"unique merchant\\\"}\""
                        + "}]}";
        assertNull(suggester.parseSuggestion(body, "Mystery"));
    }

    @Test
    @DisplayName("Confidence > 1.0 → null (defends against hallucinated scores)")
    void confidenceOverOneRejected() {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"{\\\"category\\\":\\\"dining\\\","
                        + "\\\"confidence\\\":1.5,"
                        + "\\\"reasoning\\\":\\\"x\\\"}\""
                        + "}]}";
        assertNull(suggester.parseSuggestion(body, "X"));
    }

    @Test
    @DisplayName("Confidence ≤ 0 → null")
    void confidenceZeroRejected() {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"{\\\"category\\\":\\\"dining\\\","
                        + "\\\"confidence\\\":0,"
                        + "\\\"reasoning\\\":\\\"x\\\"}\""
                        + "}]}";
        assertNull(suggester.parseSuggestion(body, "X"));
    }

    @Test
    @DisplayName("Empty content array → null")
    void emptyContentReturnsNull() {
        String body = "{\"content\":[]}";
        assertNull(suggester.parseSuggestion(body, "Mystery"));
    }

    @Test
    @DisplayName("Malformed JSON in text block → null, no crash")
    void malformedJsonReturnsNull() {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"not actually json\""
                        + "}]}";
        assertNull(suggester.parseSuggestion(body, "Mystery"));
    }

    @Test
    @DisplayName("Multi-line / multi-category category names work")
    void multiWordCategoryWorks() {
        String body =
                "{\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"{\\\"category\\\":\\\"home improvement\\\","
                        + "\\\"confidence\\\":0.91,"
                        + "\\\"reasoning\\\":\\\"hardware store\\\"}\""
                        + "}]}";
        CategoryResult r = suggester.parseSuggestion(body, "Local Hardware Co");
        assertEquals("home improvement", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("Non-text block type ignored")
    void nonTextBlocksIgnored() {
        String body =
                "{\"content\":[{\"type\":\"tool_use\"},"
                        + "{\"type\":\"text\",\"text\":"
                        + "\"{\\\"category\\\":\\\"travel\\\","
                        + "\\\"confidence\\\":0.9,"
                        + "\\\"reasoning\\\":\\\"hotel\\\"}\""
                        + "}]}";
        CategoryResult r = suggester.parseSuggestion(body, "Some Hotel");
        assertEquals("travel", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("Missing API key returns null without HTTP call")
    void missingApiKeyShortCircuits() {
        AnthropicLlmCategorySuggester noKey = new AnthropicLlmCategorySuggester(
                "https://api.anthropic.com/v1/messages", "", "claude-haiku-4-5-20251001", 10);
        CategoryResult r = noKey.suggest(new LlmCategorySuggester.SuggestionContext(
                "Test", "Test", null, null, null, null, null, null));
        assertNull(r);
    }
}
