package com.budgetbuddy.mcp;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Single MCP tool the server exposes. Every implementation is a thin
 * adapter over an existing application service — MCP is purely a
 * second access surface on top of code we've already built and tested.
 *
 * <p>Contract:
 *
 * <ul>
 *   <li>{@link #name()} — exact MCP tool name (snake_case, ≤ 64 chars).
 *       Returned in {@code tools/list} and matched in {@code tools/call}.
 *   <li>{@link #description()} — one-sentence description shown to the
 *       calling AI. Keep it action-oriented ("Return the user's
 *       active subscriptions.") rather than implementation-oriented.
 *   <li>{@link #inputSchema()} — JSON Schema object describing the
 *       call's args. Used by the AI to plan tool calls correctly.
 *   <li>{@link #category()} — drives the consent gate. {@code READ}
 *       tools run with no extra consent. {@code WRITE} tools refuse
 *       until the user has granted money-moving consent for the
 *       session via {@code enable_money_moving_consent}.
 * </ul>
 */
public interface McpTool {

    String name();

    String description();

    ObjectNode inputSchema();

    Category category();

    /**
     * Execute the tool. Args are pre-validated against the schema by
     * the protocol layer; implementations can assume types but should
     * still null-check optional fields.
     *
     * @param args raw JSON object of arguments
     * @param user the authenticated user — never null
     * @param session per-session state (consent flags, etc.)
     * @return JSON result object that becomes the body of the
     *     {@code content} field in {@code tools/call} response
     */
    JsonNode call(JsonNode args, UserTable user, McpSession session) throws Exception;

    /** Classification used by the consent layer + telemetry. */
    enum Category {
        /** Read-only: no consent gate. */
        READ,
        /**
         * Modifies user data but doesn't move money or delete records
         * (e.g. dismiss anomaly, mark goal complete). Requires
         * money-moving consent.
         */
        WRITE,
        /**
         * Money-moving or destructive: creates/updates/deletes a
         * budget/goal/subscription/transaction. Same consent gate as
         * WRITE for v1 — future revision can split into stricter
         * per-call confirmation.
         */
        MONEY_MOVING
    }
}
