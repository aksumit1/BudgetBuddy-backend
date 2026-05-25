package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * One message in an AI chat conversation. Partition key is the
 * {@code conversationId}; sort key is the {@code createdAt} epoch
 * milliseconds so message lists naturally return in chronological
 * order. A GSI on {@code userId} lets us list all conversations a
 * user has across devices.
 *
 * <p>The {@code content} field stores ONLY user-facing text — never
 * include the LLM's tool-use payload or raw aggregate data. Tool-use
 * trace data is reconstructed from per-request context, not persisted
 * (avoids storing PII-adjacent aggregate stats with the chat history).
 *
 * <p>TTL: {@code ttl} is an epoch-second value 90 days in the future;
 * DynamoDB will delete expired messages automatically. Chat history is
 * convenience, not a permanent record.
 */
@DynamoDbBean
public class ChatMessageTable {

    /** "user" | "assistant" | "system". */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    private String conversationId;
    private Long createdAt; // epoch millis — sort key
    private String messageId;
    private String userId;
    private String role;
    private String content;
    private Long ttl; // epoch seconds (DynamoDB TTL attribute)

    @DynamoDbPartitionKey
    @DynamoDbAttribute("conversationId")
    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(final String conversationId) {
        this.conversationId = conversationId;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("createdAt")
    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Long createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("messageId")
    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(final String messageId) {
        this.messageId = messageId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdConversationIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdConversationIndex")
    @DynamoDbAttribute("conversationStart")
    public Long getConversationStart() {
        // GSI sort key — we want most-recent conversations first.
        // Store as -createdAt so a default ascending scan yields newest first.
        return createdAt == null ? null : -createdAt;
    }

    public void setConversationStart(final Long ignored) {
        // Derived from createdAt; setter required by DynamoDB enhanced
        // mapper but the value is recomputed on write.
    }

    @DynamoDbAttribute("role")
    public String getRole() {
        return role;
    }

    public void setRole(final String role) {
        this.role = role;
    }

    @DynamoDbAttribute("content")
    public String getContent() {
        return content;
    }

    public void setContent(final String content) {
        this.content = content;
    }

    @DynamoDbAttribute("ttl")
    public Long getTtl() {
        return ttl;
    }

    public void setTtl(final Long ttl) {
        this.ttl = ttl;
    }
}
