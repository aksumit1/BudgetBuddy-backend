package com.budgetbuddy.service.pdf.profile;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects the tokenized-wallet provider for a transaction by inspecting the merchant
 * description prefix. Issuers print a recognizable prefix when a transaction was
 * authorized via Apple Pay / Google Pay / Samsung Pay / PayPal / Square so we can
 * distinguish physical-card swipes from wallet-authenticated transactions.
 *
 * <h2>Detection conventions (US issuers)</h2>
 *
 * <ul>
 *   <li><b>Apple Pay</b>: "APL*" prefix, or "Apple Pay - ", or sometimes the merchant
 *       appears as-is and the wallet is encoded out-of-band — best-effort only.
 *   <li><b>Google Pay</b>: "GOOGLE *", "GPAY*", "GOOGLE *MERCHANT".
 *   <li><b>Samsung Pay</b>: "SAMSUNG *", "SP *".
 *   <li><b>PayPal</b>: "PYPL", "PAYPAL *", "PYPAL*", "PAYPAL DES".
 *   <li><b>Square</b> (Cash App + Square Reader): "SQ *", "SQU*", "*SQ MERCHANT".
 *   <li><b>Venmo</b>: "VENMO *", "VEN*".
 *   <li><b>Zelle</b>: "ZELLE TO", "ZELLE FROM".
 *   <li><b>Cash App</b>: "CASH APP*", "CASH APP".
 * </ul>
 *
 * <p>Returns {@code Optional.empty()} when none match — that's the "physical card" case.
 * Insights that segment by wallet (e.g. "70% of dining is Apple Pay") can use this
 * to filter / partition transactions.
 *
 * <p>Why a separate class instead of a method on PDFImportService: detection is purely
 * a function of the description string — it has no PDF-extraction state, so isolating
 * it makes the logic trivial to unit-test and reusable from CSV-import flows.
 */
public final class WalletProviderDetector {

    /** Enumeration of wallet providers we recognize. */
    public enum WalletProvider {
        APPLE_PAY("apple-pay"),
        GOOGLE_PAY("google-pay"),
        SAMSUNG_PAY("samsung-pay"),
        PAYPAL("paypal"),
        SQUARE("square"),
        VENMO("venmo"),
        ZELLE("zelle"),
        CASH_APP("cash-app");

        private final String wireName;

        WalletProvider(final String wireName) {
            this.wireName = wireName;
        }

        /**
         * Stable string identifier for JSON serialization and DynamoDB storage — keeps the
         * wire format independent of the enum constant name.
         */
        public String wireName() {
            return wireName;
        }
    }

    // Order matters: more-specific prefixes first so a generic prefix doesn't shadow.
    // Each pattern is anchored at the START of the trimmed description to keep
    // detection unambiguous.
    private static final Pattern APPLE_PAY =
            Pattern.compile("(?i)^(?:apl\\*|apple\\s+pay\\s*[-—:]|apple\\s+pay\\s+)");
    private static final Pattern GOOGLE_PAY =
            Pattern.compile("(?i)^(?:gpay\\*|google\\s*\\*|google\\s+pay\\b)");
    private static final Pattern SAMSUNG_PAY =
            Pattern.compile("(?i)^(?:samsung\\s*\\*|samsung\\s+pay\\b|sp\\s*\\*\\s*samsung)");
    private static final Pattern PAYPAL =
            Pattern.compile("(?i)^(?:pypl\\s*\\*?|paypal\\s*\\*|pypal\\s*\\*|paypal\\s+des\\b|paypal\\s*-\\s)");
    private static final Pattern SQUARE =
            Pattern.compile("(?i)^(?:sq\\s*\\*|squ\\s*\\*|tst\\s*\\*\\s*[a-z]|sq\\s+[a-z])");
    private static final Pattern VENMO =
            Pattern.compile("(?i)^(?:venmo\\s*\\*|ven\\s*\\*\\s*venmo)");
    private static final Pattern ZELLE =
            Pattern.compile("(?i)^zelle\\s+(?:to|from|payment|transfer)\\b");
    private static final Pattern CASH_APP =
            Pattern.compile("(?i)^cash\\s+app\\b");

    /**
     * Returns the detected wallet provider, or {@link Optional#empty()} when the
     * description doesn't match any known prefix.
     */
    public static Optional<WalletProvider> detect(final String description) {
        if (description == null || description.isBlank()) {
            return Optional.empty();
        }
        final String trimmed = description.stripLeading();
        if (APPLE_PAY.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.APPLE_PAY);
        }
        if (GOOGLE_PAY.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.GOOGLE_PAY);
        }
        if (SAMSUNG_PAY.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.SAMSUNG_PAY);
        }
        if (PAYPAL.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.PAYPAL);
        }
        if (SQUARE.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.SQUARE);
        }
        if (VENMO.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.VENMO);
        }
        if (ZELLE.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.ZELLE);
        }
        if (CASH_APP.matcher(trimmed).find()) {
            return Optional.of(WalletProvider.CASH_APP);
        }
        return Optional.empty();
    }

    /**
     * Convenience for serialization paths: returns the wire-format string, or null when
     * no wallet was detected.
     */
    public static String detectName(final String description) {
        return detect(description).map(WalletProvider::wireName).orElse(null);
    }

    private WalletProviderDetector() {
        // static utility
    }
}
