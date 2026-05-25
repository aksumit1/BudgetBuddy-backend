package com.budgetbuddy.mcp;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * MCP prompt — a parameterised, server-curated instruction the AI
 * client can fetch and render in its conversation. Prompts are the
 * "ready-made workflows" surface alongside tools.
 *
 * <p>Each prompt declares:
 *
 * <ul>
 *   <li>a stable {@code name} the client invokes with {@code prompts/get}
 *   <li>a human-readable {@code description}
 *   <li>a list of arguments it accepts (each {@link Argument} carries a
 *       name, description, and required flag)
 *   <li>a {@link #render} method that produces one or more chat-style
 *       messages, optionally interpolating per-user state (so e.g.
 *       "weekly review" can include the user's current budget summary)
 * </ul>
 */
public interface McpPrompt {

    String name();

    String description();

    List<Argument> arguments();

    /**
     * Build the prompt content. The result is a list of {role, content}
     * pairs that the AI client treats as a head-start in the chat —
     * usually one "user" turn with rich context, sometimes one "system"
     * turn first.
     */
    List<ObjectNode> render(JsonNode args, UserTable user);

    /** Prompt argument declaration. */
    record Argument(String name, String description, boolean required) {}
}
