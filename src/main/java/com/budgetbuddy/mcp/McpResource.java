package com.budgetbuddy.mcp;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP resource — an addressable, read-only snapshot of user data the
 * AI client can pin into the conversation via {@code resources/read}.
 *
 * <p>Resources differ from tools: tools accept arguments and may
 * mutate state; resources are pure addressable reads (e.g.
 * {@code bb://budgets/current}) and are cheap enough that the client
 * can fetch them on every turn to refresh context.
 */
public interface McpResource {

    /** Stable URI — must start with {@code bb://}. */
    String uri();

    String name();

    String description();

    /** MIME type — almost always {@code application/json}. */
    default String mimeType() {
        return "application/json";
    }

    /** Build the snapshot for the supplied user. */
    JsonNode read(UserTable user) throws Exception;
}
