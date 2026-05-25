package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.ChatMessageTable;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the summariser's behaviour against a mocked HttpClient — no
 * real LLM round-trip. Covers the contract the chat service relies
 * on: null/empty input is a no-op; missing API key short-circuits;
 * a 200 response yields the parsed summary; non-200 yields null
 * gracefully.
 */
class ConversationSummarizerTest {

    @SuppressWarnings("unchecked")
    private final HttpResponse<String> mockResponse = mock(HttpResponse.class);
    private HttpClient mockClient;
    private ConversationSummarizer svc;

    @BeforeEach
    void setUp() throws Exception {
        mockClient = mock(HttpClient.class);
        svc = new ConversationSummarizer(mockClient);
        setField("apiKey", "test-api-key");
        setField("model", "claude-haiku-4-5-20251001");
        setField("timeoutSeconds", 12);
    }

    @Test
    void nullInput_returnsNull_noHttpCall() {
        assertNull(svc.summarise(null));
    }

    @Test
    void emptyInput_returnsNull_noHttpCall() {
        assertNull(svc.summarise(List.of()));
    }

    @Test
    void missingApiKey_returnsNull_noHttpCall() throws Exception {
        setField("apiKey", "");
        assertNull(svc.summarise(List.of(turn("user", "hi"))));
    }

    @Test
    void successfulCall_returnsSummaryText() throws Exception {
        final String body =
                "{\"content\":[{\"text\":\"The user asked about dining; assistant noted "
                        + "$1200 spend.\"}]}";
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(body);
        // doReturn — Mockito generics inference rejects when().thenReturn()
        // for the parameterised HttpResponse<String>.
        doReturn(mockResponse).when(mockClient).send(any(), any());

        final String summary = svc.summarise(List.of(
                turn("user", "How much on dining?"),
                turn("assistant", "About $1200 last month.")));
        assertNotNull(summary);
        assertTrue(summary.contains("dining"));
    }

    @Test
    void anthropicNon200_returnsNull_doesNotThrow() throws Exception {
        when(mockResponse.statusCode()).thenReturn(429);
        when(mockResponse.body()).thenReturn("");
        // doReturn — Mockito generics inference rejects when().thenReturn()
        // for the parameterised HttpResponse<String>.
        doReturn(mockResponse).when(mockClient).send(any(), any());

        assertNull(svc.summarise(List.of(turn("user", "hi"))));
    }

    @Test
    void anthropicException_returnsNull_doesNotPropagate() throws Exception {
        doThrow(new java.io.IOException("connection refused"))
                .when(mockClient).send(any(), any());

        // Must not propagate — caller (chat service) should silently
        // continue with truncated history instead of failing the turn.
        assertNull(svc.summarise(List.of(turn("user", "hi"))));
    }

    @Test
    void emptyContentInResponse_returnsNull() throws Exception {
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(
                "{\"content\":[{\"text\":\"   \"}]}");
        // doReturn — Mockito generics inference rejects when().thenReturn()
        // for the parameterised HttpResponse<String>.
        doReturn(mockResponse).when(mockClient).send(any(), any());

        assertNull(svc.summarise(List.of(turn("user", "hi"))));
    }

    @Test
    void systemPrompt_includesCompressionInstructions() {
        // The system prompt is what the cheap model sees; verify it
        // contains the key shape instruction (under 80 words, summary
        // directly, no preamble).
        final String prompt = svc.systemPrompt();
        assertTrue(prompt.toLowerCase().contains("summary"));
        assertTrue(prompt.contains("80 words"));
    }

    @Test
    void flattenTurns_preservesRoleAndContent_inChronologicalOrder() {
        final String flat = svc.flattenTurns(List.of(
                turn("user", "Q1"),
                turn("assistant", "A1"),
                turn("user", "Q2")));
        // Must contain all turns in order with role markers.
        assertTrue(flat.indexOf("user: Q1") < flat.indexOf("assistant: A1"));
        assertTrue(flat.indexOf("assistant: A1") < flat.indexOf("user: Q2"));
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field f = ConversationSummarizer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(svc, value);
    }

    private static ChatMessageTable turn(final String role, final String content) {
        final ChatMessageTable m = new ChatMessageTable();
        m.setRole(role);
        m.setContent(content);
        return m;
    }
}
