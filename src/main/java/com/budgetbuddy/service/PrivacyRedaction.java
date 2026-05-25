package com.budgetbuddy.service;

import java.util.Locale;

/**
 * Helpers for redacting sensitive identifiers before logging or serialising.
 *
 * <p>Today the only sensitive identifier that escapes the import pipeline is the raw account
 * number. Other surfaces (auth tokens, cookies, passwords) go through their own redaction layers.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class PrivacyRedaction {

    private PrivacyRedaction() {
        /* utility class */
    }

    /**
     * Mask all but the trailing 4 characters of an account number. Examples: "1234567890123456" →
     * "XXXXXXXXXXXX3456" "XXXX-XXXX-XXXX-1234" → "XXXX-XXXX-XXXX-1234" (already masked, kept as-is)
     * "1234" → "1234" (already last-4 only) null / empty → returned unchanged.
     *
     * <p>This is a one-way transformation for display + logging. The unmasked form is retained in
     * memory by {@link AccountDetectionService.DetectedAccount#getFullAccountNumber()} and never
     * serialised through the API by default — the controller surfaces only the masked form,
     * unmasking only on explicit admin request (future hook; the raw field is already available).
     */
    public static String maskAccountNumber(final String raw) {
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }
        // If the caller already masked it (e.g. "XXXX-XXXX-XXXX-1234"), leave it alone.
        if (trimmed.toLowerCase(Locale.ROOT).contains("x") || trimmed.contains("*")) {
            return trimmed;
        }
        // Preserve length so a glance at the mask hints at card vs. account vs. IBAN.
        final int total = trimmed.length();
        final StringBuilder sb = new StringBuilder(total);
        for (int i = 0; i < total - 4; i++) {
            sb.append('X');
        }
        sb.append(trimmed, total - 4, total);
        return sb.toString();
    }

    /**
     * Mask an email for logging. Keeps the first character of the local
     * part + the domain, replaces the rest of the local part with
     * asterisks. Examples:
     * <ul>
     *   <li>{@code "alice@example.com"} → {@code "a****@example.com"}</li>
     *   <li>{@code "a@x.com"} → {@code "a@x.com"} (single-char local part
     *       can't be further redacted without becoming useless)</li>
     *   <li>{@code "bad-no-at-sign"} → {@code "<masked>"} (defensive)</li>
     *   <li>null / blank → returned unchanged</li>
     * </ul>
     *
     * <p>The goal is operational debuggability (you can spot patterns
     * like "every alice@... fails") without exposing the full address in
     * CloudWatch / Splunk / wherever logs land. Compliance teams treat
     * full emails as PII; this is the cheap fix.
     */
    public static String maskEmail(final String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        final int at = raw.indexOf('@');
        if (at <= 0 || at == raw.length() - 1) {
            // Either no '@', or '@' at start/end — can't structurally mask.
            return "<masked>";
        }
        final String local = raw.substring(0, at);
        final String domain = raw.substring(at);
        if (local.length() <= 1) {
            return raw;
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        sb.append(local.charAt(0));
        for (int i = 1; i < local.length(); i++) {
            sb.append('*');
        }
        sb.append(domain);
        return sb.toString();
    }
}
