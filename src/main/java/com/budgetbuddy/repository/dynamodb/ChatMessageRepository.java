package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.ChatMessageTable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Persistence for AI chat messages. Backed by a single DynamoDB table
 * partitioned on {@code conversationId}; the per-conversation message
 * list is naturally sorted by the {@code createdAt} sort key.
 *
 * <p>{@link #loadConversation(String)} returns messages in
 * chronological order (oldest first) — that's the shape the LLM
 * expects when reconstructing context.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Repository
public class ChatMessageRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatMessageRepository.class);
    private static final String TABLE_NAME = "ChatMessages";

    private final DynamoDbTable<ChatMessageTable> table;

    public ChatMessageRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.table = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(ChatMessageTable.class));
    }

    /** Save a new message; throws on persistence failure (caller decides retry). */
    public void save(final ChatMessageTable message) {
        table.putItem(message);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Persisted chat message conversationId={} role={} length={}",
                    message.getConversationId(),
                    message.getRole(),
                    message.getContent() == null ? 0 : message.getContent().length());
        }
    }

    /**
     * Load the full message history for one conversation in
     * chronological order. Returns empty when the conversation
     * doesn't exist (e.g. first turn).
     */
    public List<ChatMessageTable> loadConversation(final String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return new ArrayList<>();
        }
        final List<ChatMessageTable> out = new ArrayList<>();
        table.query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(conversationId).build()))
                .items()
                .forEach(out::add);
        out.sort(Comparator.comparing(ChatMessageTable::getCreatedAt));
        return out;
    }

    /**
     * List a user's conversations, most-recent first. Each entry is
     * the FIRST message of a conversation paired with a count + the
     * latest message preview. Implemented by querying the
     * {@code UserIdConversationIndex} GSI and grouping in memory.
     *
     * <p>For users with very many conversations the result is capped
     * at {@code maxConversations}; future pagination is a follow-up.
     */
    public List<ConversationSummary> listConversations(
            final String userId, final int maxConversations) {
        if (userId == null || userId.isBlank()) {
            return new ArrayList<>();
        }
        final java.util.LinkedHashMap<String, ConversationAccumulator> byConv =
                new java.util.LinkedHashMap<>();
        table.index("UserIdConversationIndex")
                .query(QueryConditional.keyEqualTo(
                        Key.builder().partitionValue(userId).build()))
                .stream()
                .flatMap(p -> p.items().stream())
                .forEach(m -> byConv.computeIfAbsent(
                                m.getConversationId(), k -> new ConversationAccumulator())
                        .observe(m));

        final List<ConversationSummary> out = new ArrayList<>(byConv.size());
        for (final var e : byConv.entrySet()) {
            out.add(e.getValue().toSummary(e.getKey()));
        }
        // Newest first by lastMessageEpochMillis.
        out.sort(Comparator.comparingLong(ConversationSummary::lastMessageEpochMillis).reversed());
        if (out.size() > maxConversations) {
            return out.subList(0, maxConversations);
        }
        return out;
    }

    /** Conversation list-view entry. */
    public record ConversationSummary(
            String conversationId,
            long firstMessageEpochMillis,
            long lastMessageEpochMillis,
            int messageCount,
            String lastMessagePreview) {}

    private static final class ConversationAccumulator {
        long firstMillis = Long.MAX_VALUE;
        long lastMillis = Long.MIN_VALUE;
        int count;
        String lastContent = "";

        void observe(final ChatMessageTable m) {
            final long t = m.getCreatedAt() == null ? 0L : m.getCreatedAt();
            if (t < firstMillis) {
                firstMillis = t;
            }
            if (t > lastMillis) {
                lastMillis = t;
                lastContent = m.getContent() == null ? "" : m.getContent();
            }
            count++;
        }

        ConversationSummary toSummary(final String convId) {
            final String preview = lastContent.length() > 140
                    ? lastContent.substring(0, 137) + "..."
                    : lastContent;
            return new ConversationSummary(
                    convId,
                    firstMillis == Long.MAX_VALUE ? 0L : firstMillis,
                    lastMillis == Long.MIN_VALUE ? 0L : lastMillis,
                    count, preview);
        }
    }
}
