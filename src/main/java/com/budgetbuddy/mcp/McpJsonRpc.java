package com.budgetbuddy.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 envelope helpers — shared by request parsing and
 * response building. JSON-RPC's shape is small enough that bringing
 * in a dedicated library would be overkill; this helper keeps the
 * field names + error codes in one place.
 *
 * <p>Error codes match the JSON-RPC spec:
 *
 * <ul>
 *   <li>-32700 — parse error
 *   <li>-32600 — invalid request
 *   <li>-32601 — method not found
 *   <li>-32602 — invalid params
 *   <li>-32603 — internal error
 * </ul>
 *
 * <p>MCP additionally defines:
 *
 * <ul>
 *   <li>-32000 — tool execution failed
 *   <li>-32001 — consent required (our custom; documented in the README)
 * </ul>
 */
public final class McpJsonRpc {

    public static final String VERSION = "2.0";

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    public static final int TOOL_EXECUTION_FAILED = -32000;
    public static final int CONSENT_REQUIRED = -32001;

    private McpJsonRpc() {}

    public static ObjectNode success(
            final ObjectMapper mapper, final JsonNode id, final JsonNode result) {
        final ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", VERSION);
        if (id != null && !id.isNull()) out.set("id", id);
        out.set("result", result == null ? mapper.nullNode() : result);
        return out;
    }

    public static ObjectNode error(
            final ObjectMapper mapper, final JsonNode id, final int code, final String message) {
        final ObjectNode out = mapper.createObjectNode();
        out.put("jsonrpc", VERSION);
        if (id != null && !id.isNull()) out.set("id", id);
        final ObjectNode err = out.putObject("error");
        err.put("code", code);
        err.put("message", message == null ? "" : message);
        return out;
    }
}
