package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.model.dynamodb.ChatMessageTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Compresses the older turns of a chat conversation into a short
 * narrative when the running history would otherwise blow past the
 * model's effective context window (or just become wastefully large).
 *
 * <p>How it's used:
 * <ol>
 *   <li>{@link InsightsChatService} loads full history.</li>
 *   <li>If history.size() exceeds the keep-raw cap, the oldest portion
 *       is sent here for summarisation.</li>
 *   <li>The summary comes back as a single synthetic system message
 *       that the chat service prepends to the recent raw turns.</li>
 * </ol>
 *
 * <p>The summariser uses a cheaper/faster model than the main chat
 * (Haiku by default) so the cost is bounded — it's a single short
 * call per long-conversation turn, not per token.
 *
 * <p>When the summariser is misconfigured (no API key) it returns
 * {@code null} and the caller drops the oldest turns instead. That
 * loses fidelity but keeps the chat working.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class ConversationSummarizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationSummarizer.class);
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${app.insights.chat.api-key:}")
    private String apiKey;

    @Value("${app.insights.chat.summariser-model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${app.insights.chat.summariser-timeout-seconds:12}")
    private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;

    /** Spring uses this constructor. Tests use {@link #ConversationSummarizer(HttpClient)}. */
    public ConversationSummarizer() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    }

    /** Test seam: inject a mocked / fake HttpClient. */
    public ConversationSummarizer(final HttpClient client) {
        this.client = client;
    }

    /**
     * Summarise a chronological run of turns into one short paragraph
     * that captures: topics discussed, decisions/preferences the user
     * expressed, and any specific numbers the assistant cited that
     * would be needed for continuity.
     *
     * @return summary text suitable as a {@code system} message, or
     *         {@code null} when the summariser is disabled / failed.
     */
    public String summarise(final List<ChatMessageTable> oldTurns) {
        if (oldTurns == null || oldTurns.isEmpty()) {
            return null;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        try {
            final ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 250);
            body.put("system", systemPrompt());
            final ArrayNode messages = body.putArray("messages");
            final ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", flattenTurns(oldTurns));

            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ANTHROPIC_API))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            body.toString(), StandardCharsets.UTF_8))
                    .build();

            final HttpResponse<String> resp =
                    client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOGGER.warn("Summariser: Anthropic returned HTTP {}", resp.statusCode());
                return null;
            }
            final String summary = mapper.readTree(resp.body())
                    .path("content").path(0).path("text").asText("").trim();
            return summary.isEmpty() ? null : summary;
        } catch (final Exception e) {
            LOGGER.warn("Summariser failed: {}", e.getMessage());
            return null;
        }
    }

    String systemPrompt() {
        return "You compress chat history. Read the user-assistant turns below and produce "
                + "a SHORT summary (under 80 words) capturing: (1) the topics discussed, "
                + "(2) any decisions / preferences the user expressed, (3) any specific "
                + "numbers the assistant quoted that would be needed for context. No "
                + "preamble; output the summary directly.";
    }

    String flattenTurns(final List<ChatMessageTable> turns) {
        final StringBuilder sb = new StringBuilder();
        for (final ChatMessageTable t : turns) {
            sb.append(t.getRole() == null ? "?" : t.getRole())
                    .append(": ")
                    .append(t.getContent() == null ? "" : t.getContent())
                    .append("\n");
        }
        return sb.toString();
    }
}
