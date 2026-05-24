package com.budgetbuddy.mcp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of active MCP sessions. Sessions are created by
 * the {@code initialize} request and looked up by the
 * {@code Mcp-Session-Id} header on every subsequent JSON-RPC call.
 *
 * <p>Cap: 10k concurrent sessions, oldest evicted first when full.
 * Idle eviction at 30 minutes. The MCP spec lets clients hold a
 * session indefinitely, but a stale process holding open an unused
 * session can't be allowed to consume memory forever.
 *
 * <p>This is intentionally NOT durable across pod restarts — the
 * client is expected to re-initialize on a 404. That mirrors how
 * Anthropic's own MCP servers behave.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Spring bean — exposed state is intentionally shared")
@Component
public class McpSessionRegistry {

    private static final int MAX_SESSIONS = 10_000;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, McpSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastUsedAt = new ConcurrentHashMap<>();

    public McpSession create(final String userId) {
        evictIfFull();
        final McpSession s = new McpSession(userId);
        sessions.put(s.sessionId(), s);
        lastUsedAt.put(s.sessionId(), Instant.now());
        return s;
    }

    public Optional<McpSession> get(final String sessionId) {
        if (sessionId == null) return Optional.empty();
        final McpSession s = sessions.get(sessionId);
        if (s == null) return Optional.empty();
        lastUsedAt.put(sessionId, Instant.now());
        return Optional.of(s);
    }

    public void terminate(final String sessionId) {
        if (sessionId == null) return;
        sessions.remove(sessionId);
        lastUsedAt.remove(sessionId);
    }

    private void evictIfFull() {
        final Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        lastUsedAt.entrySet().removeIf(e -> {
            if (e.getValue().isBefore(cutoff)) {
                sessions.remove(e.getKey());
                return true;
            }
            return false;
        });
        if (sessions.size() < MAX_SESSIONS) return;
        // Hard cap — drop the oldest session.
        lastUsedAt.entrySet().stream()
                .min(Comparator.comparing(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .ifPresent(id -> {
                    sessions.remove(id);
                    lastUsedAt.remove(id);
                });
    }
}
