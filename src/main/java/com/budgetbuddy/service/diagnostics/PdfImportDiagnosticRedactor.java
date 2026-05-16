package com.budgetbuddy.service.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic PII-scrubbing for diagnostic blobs. Every rule has a name so
 * callers can record which rules fired in the blob's {@code redaction_applied}
 * audit trail.
 *
 * <p>Rules are intentionally conservative: false-positives (over-redaction) are
 * acceptable, false-negatives (PII leaks) are not. When in doubt, redact.
 */
public final class PdfImportDiagnosticRedactor {

    /** Rule that runs against a single line of text. */
    private static final class Rule {
        final String name;
        final Pattern pattern;
        final String replacement;
        Rule(final String n, final String p, final String r) {
            this.name = n;
            this.pattern = Pattern.compile(p);
            this.replacement = r;
        }
    }

    private static final List<Rule> RULES = List.of(
            // Full 16-digit card numbers (with or without spaces / hyphens).
            new Rule("card-number-16",
                    "\\b(\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4})\\b",
                    "[REDACTED-CARD]"),
            // Account numbers ending in 4-5 digits — keep last 4 visible (masked form).
            new Rule("account-ending",
                    "(?i)(account\\s+(?:ending|number)[\\s:#xX*-]+)(\\d+)",
                    "$1****"),
            // SSN-shaped
            new Rule("ssn",
                    "\\b\\d{3}-\\d{2}-\\d{4}\\b",
                    "[REDACTED-SSN]"),
            // US phone numbers in common shapes
            new Rule("us-phone-1",
                    "\\b\\+?1?[\\s.-]?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b",
                    "[PHONE]"),
            // Email addresses
            new Rule("email",
                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                    "[EMAIL]"),
            // Street addresses (very loose — number + street name + suffix)
            new Rule("street-address",
                    "\\b\\d{1,6}\\s+[A-Z][A-Za-z\\s]{2,30}\\s+"
                            + "(?:ST|STREET|RD|ROAD|AVE|AVENUE|BLVD|BOULEVARD|DR|DRIVE|"
                            + "LN|LANE|CT|COURT|WAY|PL|PLACE|PKWY|PARKWAY)\\b\\.?",
                    "[ADDRESS]"),
            // 5-digit zip codes (only when standalone, to avoid eating amounts)
            new Rule("us-zip",
                    "\\b\\d{5}-\\d{4}\\b",
                    "[ZIP]"));

    /** Redact one text blob; return text + the names of rules that fired. */
    public Result redact(final String input) {
        if (input == null || input.isEmpty()) {
            return new Result("", List.of());
        }
        String text = input;
        final List<String> fired = new ArrayList<>();
        for (final Rule rule : RULES) {
            final Matcher m = rule.pattern.matcher(text);
            if (m.find()) {
                text = m.replaceAll(rule.replacement);
                fired.add(rule.name);
            }
        }
        return new Result(text, fired);
    }

    /**
     * Redact a NAME that comes from a known field. Account-holder names need
     * full anonymisation — we collapse to "[HOLDER]" rather than try to preserve
     * structure. This is called explicitly on the holder field, never auto.
     */
    public String redactHolderName(final String name) {
        if (name == null || name.isBlank()) return null;
        return "[HOLDER]";
    }

    /** Mask all but the last 4 digits of a card / account number. */
    public String maskLastFour(final String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) return null;
        final String digits = accountNumber.replaceAll("\\D", "");
        if (digits.length() <= 4) return "****" + digits;
        return "****" + digits.substring(digits.length() - 4);
    }

    public static final class Result {
        private final String text;
        private final List<String> rulesFired;
        Result(final String text, final List<String> rulesFired) {
            this.text = text;
            this.rulesFired = rulesFired;
        }
        public String getText() { return text; }
        public List<String> getRulesFired() { return rulesFired; }
    }
}
