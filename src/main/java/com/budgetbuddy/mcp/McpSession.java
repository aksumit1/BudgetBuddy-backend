package com.budgetbuddy.mcp;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session state for an MCP connection. The MCP transport layer
 * generates a session ID on {@code initialize} and the client echoes
 * it back on every subsequent call via the {@code Mcp-Session-Id}
 * header. This object holds the ephemeral state we keep server-side
 * for that lifetime.
 *
 * <p>Threading: every field is read by the controller dispatch
 * thread; the consent flag is set by tool calls. Atomic types so a
 * mid-flight consent toggle is visible to a concurrent tool invocation
 * on the same session.
 */
public final class McpSession {

    private final String sessionId;
    private final String userId;
    private final Instant createdAt;
    /**
     * Client identity reported on {@code initialize.params.clientInfo}.
     * Defaulted to "unknown" so an MCP client that omits it still
     * renders a sensible row in the iOS session list. Mutable so the
     * controller can set it after the registry hands out the session.
     */
    private volatile String clientName = "unknown";
    private volatile String clientVersion = "";
    /**
     * Money-moving consent: starts false. The
     * {@code enable_money_moving_consent} tool flips it true after the
     * AI surfaces the request to the user and the user explicitly
     * approves via an iOS app dialog. Stays true for the lifetime of
     * the session; never persisted across reconnects.
     */
    private final AtomicBoolean moneyMovingConsent = new AtomicBoolean(false);
    private final AtomicLong toolCallCount = new AtomicLong();

    public McpSession(final String userId) {
        this.sessionId = UUID.randomUUID().toString();
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public String sessionId() {
        return sessionId;
    }

    public String userId() {
        return userId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public boolean moneyMovingConsent() {
        return moneyMovingConsent.get();
    }

    public void grantMoneyMovingConsent() {
        moneyMovingConsent.set(true);
    }

    public void revokeMoneyMovingConsent() {
        moneyMovingConsent.set(false);
    }

    public long incrementToolCallCount() {
        return toolCallCount.incrementAndGet();
    }

    public long toolCallCount() {
        return toolCallCount.get();
    }

    public String clientName() {
        return clientName;
    }

    public String clientVersion() {
        return clientVersion;
    }

    public void setClientInfo(final String name, final String version) {
        if (name != null && !name.isBlank()) {
            this.clientName = name;
        }
        if (version != null) {
            this.clientVersion = version;
        }
    }
}
