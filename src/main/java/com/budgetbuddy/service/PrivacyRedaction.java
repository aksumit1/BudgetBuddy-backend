package com.budgetbuddy.service;


import java.util.Locale;
/**
 * Helpers for redacting sensitive identifiers before logging or serialising.
 *
 * <p>Today the only sensitive identifier that escapes the import pipeline is the raw account
 * number. Other surfaces (auth tokens, cookies, passwords) go through their own redaction layers.
 */
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
}
