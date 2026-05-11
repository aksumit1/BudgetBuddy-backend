package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enhanced Pattern Matcher with fuzzy matching capabilities Designed to be robust, flexible,
 * extensible, and less fragile Handles variations in spacing, formatting, missing fields, and
 * malformed data
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Component
public class EnhancedPatternMatcher {

    private static final String FUZZY_MATCH = "FuzzyMatch";

    private static final String PATTERN1 = "Pattern1";

    private static final String PATTERN2 = "Pattern2";

    private static final String AMOUNT = "amount";

    private static final String DESCRIPTION = "description";

    private static final String INTERNATIONAL = "international";

    private static final String PAY_BY_PHONE = "pay by phone";

    private static final Logger LOGGER = LoggerFactory.getLogger(EnhancedPatternMatcher.class);

    // Flexible whitespace pattern - matches 1 or more spaces, tabs, or other whitespace
    private static final String FLEXIBLE_WHITESPACE = "\\s+";

    // Flexible date patterns - handles various separators and formats
    private static final List<Pattern> DATE_PATTERNS =
            Arrays.asList(
                    Pattern.compile(
                            "(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?"), // MM/DD/YY, MM-DD-YY
                    Pattern.compile(
                            "(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?"), // MM.DD.YY (European
                    // style)
                    Pattern.compile(
                            "(\\d{4})[/-](\\d{1,2})[/-](\\d{1,2})"), // YYYY/MM/DD, YYYY-MM-DD
                    Pattern.compile(
                            "(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{2,4})",
                            Pattern.CASE_INSENSITIVE) // DD Mon YYYY
                    );

    // Known valid prefix patterns for Pattern 2 transactions (transactions with prefix text before
    // date)
    // These prefixes indicate legitimate transactions even when date is not at the start of line
    // Examples: "1% Cashback Bonus", "Some prefix text"
    private static final List<String> VALID_PREFIX_PATTERNS =
            Arrays.asList(
                    "1%", // Cashback percentages: "1% Cashback Bonus"
                    "2%", // Cashback percentages
                    "3%", // Cashback percentages
                    "4%", // Cashback percentages
                    "5%", // Cashback percentages
                    "cashback", // Cashback keywords
                    "cash back", // Cashback keywords (space)
                    "reward", // Reward keywords
                    "bonus" // Bonus keywords
                    );

    // Enhanced amount patterns - very flexible
    // Uses non-capturing groups internally, will be wrapped in capturing group by AMOUNT_COMPONENT
    // IMPORTANT: Last alternative requires at least 3 digits or a decimal point to avoid matching
    // date fragments
    // Note: Changed from \\d{1,3} to \\d{1,9} in most places to allow amounts without thousands
    // separators (e.g., 1234.56)
    private static final String AMOUNT_PATTERN =
            "(?:"
                    +
                    // Parentheses format (negative) - must be first
                    // Allow flexible digit grouping: digits with optional thousands separators,
                    // require decimal
                    "\\(\\s*[\\$€£¥₹]?\\s*\\d{1,9}(?:[,\\s]\\d{3})*\\.\\d{1,2}\\s*(?:CR|DR|BF|CREDIT|DEBIT)?\\s*\\)|"
                    +
                    // Negative sign before currency (e.g., -$458.40, -$1,624.59, -$1234.56) - must
                    // come before standard format
                    // Allow spaces after sign and around currency symbol
                    "[-+]\\s*[\\$€£¥₹]\\s*\\d{1,9}(?:[,\\s]\\d{3})*\\.\\d{1,2}\\s*(?:CR|DR|BF|CREDIT|DEBIT)?|"
                    +
                    // Standard format with currency and optional sign/indicators (e.g., $458.40,
                    // $458.40 CR, $1 234.56, $1234.56)
                    // Note: For negative amounts, prefer the pattern above (-$458.40) to this one
                    // Allow spaces after currency symbol and within the number (e.g., "$1 234.56")
                    // Currency symbol is required, so this won't match date fragments
                    // Allow flexible digit grouping: digits with optional thousands separators,
                    // require decimal
                    "[\\$€£¥₹]\\s*[-+]?\\s*\\d{1,9}(?:[,\\s]\\d{3})*\\.\\d{1,2}\\s*(?:CR|DR|BF|CREDIT|DEBIT)?|"
                    +
                    // Amount with explicit sign but no currency (e.g., -458.40, +458.40, -1234.56,
                    // -800, -800.00)
                    // Note: [-+] without ? to require the sign
                    // Allow flexible digit grouping: any digits with optional thousands separators
                    // Decimal is optional for signed numbers (sign is strong indicator it's an
                    // amount, not phone number)
                    "[-+]\\s*(?:\\d{1,9}(?:[,\\s]\\d{3})*|\\d+)(?:\\.\\d{1,2})?|"
                    +
                    // Amount without currency or sign - must have decimal point to avoid matching
                    // phone number fragments like "800" from "800-544-0422"
                    // Allow flexible digit grouping: any digits with optional thousands separators,
                    // require decimal
                    // This ensures we don't match "11" from "11/09" or "800" from "800-544-0422",
                    // but we do match "1234.56" and "12345678.23"
                    "(?:\\d{1,9}(?:[,\\s]\\d{3})*|\\d+)\\.\\d{1,2}"
                    + // Must have decimal: "458.40", "1,234.56", "1234.56", "12345678.23"
                    ")";

    // Use word boundaries to prevent matching fragments from phone numbers or account numbers
    // (?<!\\w) ensures match is not preceded by word character, (?!\\w) ensures not followed by
    // word character
    private static final Pattern AMOUNT_PATTERN_COMPILED =
            Pattern.compile("(?<!\\w)" + AMOUNT_PATTERN + "(?!\\w)", Pattern.CASE_INSENSITIVE);

    // Transaction pattern components
    private static final String DATE_COMPONENT = "(\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?)";
    private static final String DESCRIPTION_COMPONENT = "(.+?)"; // Non-greedy match
    // AMOUNT_COMPONENT wraps AMOUNT_PATTERN in a capturing group for extraction
    // Use word boundaries to prevent matching fragments from phone numbers or account numbers
    // Note: Used in structured patterns (with ^ and $ anchors), but word boundaries add extra
    // safety
    private static final String AMOUNT_COMPONENT = "(?<!\\w)(" + AMOUNT_PATTERN + ")(?!\\w)";

    /** Flexible pattern matching that tries multiple patterns and returns the best match */
    public static class MatchResult {
        private final boolean matched;
        private final Map<String, String> fields;
        private final double confidence;
        private final String patternUsed;

        public MatchResult(
                final boolean matched,
                final Map<String, String> fields,
                final double confidence,
                final String patternUsed) {
            this.matched = matched;
            this.fields = fields != null ? new HashMap<>(fields) : new HashMap<>();
            this.confidence = confidence;
            this.patternUsed = patternUsed;
        }

        public boolean isMatched() {
            return matched;
        }

        public Map<String, String> getFields() {
            return fields;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getPatternUsed() {
            return patternUsed;
        }
    }

    /**
     * Try to match a line against multiple patterns and return the best match This is the main
     * entry point for pattern matching
     */
    public MatchResult matchTransactionLine(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        if (line == null || line.isBlank()) {
            return new MatchResult(false, null, 0.0, "empty");
        }

        // Early filter: Reject lines that are clearly section headers, not transactions
        // This must happen BEFORE pattern matching to prevent false positives
        // Be careful: "account ending" alone is OK, only filter when combined with header keywords
        final String lineLower = line.toLowerCase(Locale.ROOT).trim();
        if (lineLower.contains("closing date")
                || lineLower.contains("statement date")
                || (lineLower.matches(".*\\b(closing|statement)\\s+date.*")
                        && lineLower.contains("account ending"))
                ||
                // Filter phone number lines with informational keywords
                (lineLower.contains(PAY_BY_PHONE)
                        && lineLower.matches(".*\\d{1,3}-\\d{3}-\\d{3}-\\d{4}.*"))
                || // "Pay by phone 1-800-436-7958"
                (lineLower.contains(PAY_BY_PHONE)
                        && lineLower.matches(".*\\d{1,3}-\\d{3}-\\d{4}-\\d{4}.*"))
                || // "Pay by phone 1-302-594-8200"
                (lineLower.contains(PAY_BY_PHONE) && lineLower.matches(".*\\d{3}-\\d{3}-\\d{4}.*"))
                || // "Pay by phone 800-436-7958"
                (lineLower.contains(INTERNATIONAL)
                        && lineLower.matches(".*\\d{1,3}-\\d{3}-\\d{3}-\\d{4}.*"))
                || // "International 1-800-436-7958"
                (lineLower.contains(INTERNATIONAL)
                        && lineLower.matches(".*\\d{1,3}-\\d{3}-\\d{4}-\\d{4}.*"))
                || // "International 1-302-594-8200"
                (lineLower.contains(INTERNATIONAL) && lineLower.matches(".*\\d{3}-\\d{3}-\\d{4}.*"))
                || // "International 800-436-7958"
                lineLower.contains("operator relay")
                || lineLower.contains("we accept")
                || lineLower.contains("customer service")
                || // Customer service lines (e.g., "24-hour customer service: 1-866-229-6633")
                lineLower.contains("relay calls")
                || lineLower.contains("relay call")
                || // Relay calls lines (e.g., "we accept all relay calls, including 711")
                lineLower.contains("agreement for details")
                || // Agreement reference lines (e.g., "Cardmember Agreement for details")
                lineLower.contains("cardmember agreement")
                || // Cardmember agreement lines
                lineLower.contains("cardholder agreement")
                || // Cardholder agreement lines
                // Only reject if line is ONLY a phone number (standalone informational line)
                // Allow phone numbers in merchant descriptions (e.g., "WWW COSTCO COM 800-955-2292
                // WA")
                lineLower.trim().matches("^\\d{1,3}-\\d{3}-\\d{3}-\\d{4}$")
                || // Standalone: "1-800-436-7958"
                lineLower.trim().matches("^\\d{1,3}-\\d{3}-\\d{4}-\\d{4}$")
                || // Standalone: "1-302-594-8200"
                lineLower.trim().matches("^\\d{3}-\\d{3}-\\d{4}$")
                || // Standalone: "800-436-7958"
                lineLower.contains("send general inquiries")
                || lineLower.contains("general inquiries")
                || lineLower.contains("po box")
                || lineLower.matches(".*\\bpo box\\s+\\d+.*")
                || // PO Box addresses
                ("payment".equals(lineLower))
                || // Standalone "Payment"
                lineLower.contains("account ending in")
                || (lineLower.contains("account ending")
                        && !lineLower.contains("fees")
                        && !lineLower.contains(AMOUNT))
                || lineLower.contains("statement period")
                || lineLower.matches(".*statement period.*to.*")
                || // Statement Period with date range
                lineLower.matches(".*page\\s+\\d+\\s+of\\s+\\d+.*")
                || // Page X of Y
                lineLower.contains("rewards balance")
                || lineLower.contains("balance as of")
                || (lineLower.contains("rewards") && lineLower.contains("balance"))
                || // Rewards balance lines
                // Filter points balance lines: number with comma followed directly by date (e.g.,
                // "Marriott 8,73312/14/25")
                lineLower.matches(".*\\d{1,3}(?:,\\d{3})+\\s*\\d{1,2}/\\d{1,2}/\\d{2,4}.*")
                || lineLower.matches("^as of\\s+\\d{1,2}/\\d{1,2}/\\d{2,4}.*")
                || // "AS OF 10/10/25" pattern
                // Filter date ranges (e.g., "09/17/2025 - 10/16/2025", "10/1/25 through 12/31/25",
                // "10/1/25 to 12/31/25")
                // Allow if it has $ (could be a valid transaction with date range in description)
                (lineLower.matches(
                                ".*\\d{1,2}/\\d{1,2}/\\d{2,4}\\s+(through|to|\\-)\\s+\\d{1,2}/\\d{1,2}/\\d{2,4}.*")
                        && !lineLower.matches(".*\\$.*\\d{1,2}/\\d{1,2}/\\d{2,4}.*"))
                || // Date range: "09/17/2025 - 10/16/2025"
                lineLower.contains("late payment warning")
                || lineLower.contains("late payment")
                || // Late Payment Warning
                lineLower.contains("new balance")
                || lineLower.matches(".*new balance:.*")
                || // "New Balance: $5.66"
                lineLower.matches(".*\\b(\\d+\\.\\d{2})\\s+\\1\\s+\\d{1,2}/\\d{1,2}/\\d{2,4}.*")
                || // Repeated number pattern: "5.66 5.66 11/13/2025"
                lineLower.contains("open to close date")
                || lineLower.matches(".*open.*to.*close.*date.*")
                || // "OPEN TO CLOSE DATE: 09/17/2025 - 10/16/2025"
                // Filter lines that start with a number (reference/account number) followed by date
                // and name (no amount)
                // Pattern: "776 10/10/2025agarwal"
                lineLower.matches("^\\d{3,}\\s+\\d{1,2}/\\d{1,2}/\\d{2,4}[a-z]+$")
                ||
                // Filter repeated numbers (e.g., "776 776")
                lineLower.matches("^\\d{3,}\\s+\\d{3,}$")
                ||
                // Filter informational text about charts/statements
                lineLower.contains("chart will be shown")
                || (lineLower.contains("every")
                        && lineLower.contains("statement")
                        && lineLower.contains("months"))
                ||
                // Filter section headers that might have dates and amounts (credit card statement
                // headers)
                lineLower.contains("pay over time")
                || lineLower.contains("cash advances")
                || lineLower.contains("cash advance")
                || lineLower.contains("balance transfers")
                || lineLower.contains("balance transfer")
                || lineLower.contains("interest charges")
                || lineLower.contains("interest charge")
                || lineLower.contains("fees charged")
                || lineLower.contains("fee charged")
                || lineLower.contains("minimum payment")
                || lineLower.contains("credit limit")
                || lineLower.contains("available credit")
                || lineLower.contains("payment information")
                || lineLower.contains("account summary")
                || lineLower.contains("transaction details")
                || lineLower.contains("rewards summary")
                || lineLower.contains("statement period")
                || lineLower.contains("billing period")) {
            LOGGER.debug(
                    "EnhancedPatternMatcher: Filtering out informational/header line: {}", line);
            return new MatchResult(false, null, 0.0, "header_filter");
        }

        final String normalizedLine = normalizeLine(line);
        final List<MatchResult> candidates = new ArrayList<>();

        // Try Pattern 1: Date Description Amount (most common)
        candidates.add(tryPattern1(normalizedLine, inferredYear, isUSLocale));

        // Try Pattern 2: Prefix Date Description Amount
        candidates.add(tryPattern2(normalizedLine, inferredYear, isUSLocale));

        // Try Pattern 3: Date Date Description Amount (two dates)
        candidates.add(tryPattern3(normalizedLine, inferredYear, isUSLocale));

        // Try Pattern 4: Card Date Date ID Description Location Amount
        candidates.add(tryPattern4(normalizedLine, inferredYear, isUSLocale));

        // Try Pattern 5: Date Date Merchant Location Amount
        candidates.add(tryPattern5(normalizedLine, inferredYear, isUSLocale));

        // Try fuzzy matching as fallback
        candidates.add(tryFuzzyMatch(normalizedLine, inferredYear, isUSLocale));

        // Return the best match (highest confidence)
        final MatchResult bestMatch =
                candidates.stream()
                        .filter(MatchResult::isMatched)
                        .max(Comparator.comparingDouble(MatchResult::getConfidence))
                        .orElse(new MatchResult(false, null, 0.0, "none"));

        if (bestMatch.isMatched()) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Matched line with pattern '{}' (confidence: {})",
                        bestMatch.getPatternUsed(),
                        bestMatch.getConfidence());
            }
        }

        return bestMatch;
    }

    /** Normalize line for better matching - remove extra whitespace, normalize characters */
    private String normalizeLine(final String line) {
        if (line == null) {
            return "";
        }
        // Replace multiple whitespace with single space
        String normalized = line.replaceAll("\\s+", " ").trim();
        // Normalize special characters
        normalized = normalized.replaceAll("[\\u00A0]", " "); // Non-breaking space
        normalized = normalized.replaceAll("[\\u2000-\\u200B]", " "); // Various spaces
        return normalized;
    }

    /** Pattern 1: Date Description Amount Example: "11/09 AUTOMATIC PAYMENT - THANK YOU -458.40" */
    private MatchResult tryPattern1(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        // Flexible pattern: date (flexible whitespace) description (flexible whitespace) amount
        final Pattern pattern =
                Pattern.compile(
                        "^"
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + DESCRIPTION_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + AMOUNT_COMPONENT
                                + "$",
                        Pattern.CASE_INSENSITIVE);

        final Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
            final Map<String, String> fields = new HashMap<>();
            final String dateStr = matcher.group(1);
            final String description = matcher.group(2);
            final String amountStr = matcher.group(3);

            // Validate amount string - must contain $ or be in parentheses or have sign to avoid
            // matching dates
            if (amountStr == null || amountStr.isBlank()) {
                return new MatchResult(false, null, 0.0, PATTERN1);
            }
            final String trimmedAmount = amountStr.trim();
            // Must have currency symbol, parentheses, or explicit sign to be a valid amount (not a
            // date fragment)
            if (!trimmedAmount.contains("$")
                    && !trimmedAmount.startsWith("(")
                    && !trimmedAmount.startsWith("-")
                    && !trimmedAmount.startsWith("+")
                    && !trimmedAmount.endsWith("CR")
                    && !trimmedAmount.endsWith("DR")
                    && !trimmedAmount.endsWith("BF")) {
                // This might be a date fragment, skip it
                return new MatchResult(false, null, 0.0, PATTERN1);
            }

            // Validate and parse
            final LocalDate date = parseDateFlexible(dateStr, inferredYear, isUSLocale);
            final BigDecimal amount = parseAmountFlexible(amountStr);

            // LOGGER.info("MATCHING PATTERN 1: SUM AMEX: DATE = {}, AMOUNT = {}, DESCRIPTION = {}",
            // date, amount, description);
            if (date != null && amount != null && isValidDescription(description)) {
                fields.put("date", dateStr);
                fields.put(DESCRIPTION, description.trim());
                fields.put(AMOUNT, trimmedAmount);
                final double confidence = calculateConfidence(date, amount, description);
                return new MatchResult(true, fields, confidence, PATTERN1);
            }
        }

        return new MatchResult(false, null, 0.0, PATTERN1);
    }

    /**
     * Pattern 2: Prefix Date Description Amount Example: "1% Cashback Bonus +$0.0610/06 DIRECTPAY
     * FULL BALANCE -$11.74"
     */
    private MatchResult tryPattern2(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        // Pattern with prefix: .*? date description amount
        final Pattern pattern =
                Pattern.compile(
                        ".*?"
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + DESCRIPTION_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + AMOUNT_COMPONENT
                                + "$",
                        Pattern.CASE_INSENSITIVE);

        final Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            final Map<String, String> fields = new HashMap<>();
            final String dateStr = matcher.group(1);
            final String description = matcher.group(2);
            final String amountStr = matcher.group(3);

            // Skip if line contains percentage (informational)
            if (line.contains("%")) {
                return new MatchResult(false, null, 0.0, PATTERN2);
            }

            final LocalDate date = parseDateFlexible(dateStr, inferredYear, isUSLocale);
            final BigDecimal amount = parseAmountFlexible(amountStr);
            // LOGGER.info("MATCHING PATTERN 2: SUM AMEX: DATE = {}, AMOUNT = {}, DESCRIPTION = {}",
            // date, amount, description);
            if (date != null && amount != null && isValidDescription(description)) {
                fields.put("date", dateStr);
                fields.put(DESCRIPTION, description.trim());
                fields.put(AMOUNT, amountStr.trim());
                final double confidence =
                        calculateConfidence(date, amount, description)
                                * 0.9; // Slightly lower confidence for prefix patterns
                return new MatchResult(true, fields, confidence, PATTERN2);
            }
        }

        return new MatchResult(false, null, 0.0, PATTERN2);
    }

    /** Pattern 3: Date Date Description Amount (two dates) */
    private MatchResult tryPattern3(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        final Pattern pattern =
                Pattern.compile(
                        "^"
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + DESCRIPTION_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + AMOUNT_COMPONENT
                                + "$",
                        Pattern.CASE_INSENSITIVE);

        final Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
            final Map<String, String> fields = new HashMap<>();
            // date1Str is the transaction date, date2Str is the posting date (we use posting date)
            final String date2Str = matcher.group(2);
            final String description = matcher.group(3);
            final String amountStr = matcher.group(4);

            // Use the second date (posting date)
            final LocalDate date = parseDateFlexible(date2Str, inferredYear, isUSLocale);
            final BigDecimal amount = parseAmountFlexible(amountStr);
            // LOGGER.info("MATCHING PATTERN 3: SUM AMEX: DATE = {}, AMOUNT = {}, DESCRIPTION = {}",
            // date, amount, description);
            if (date != null && amount != null && isValidDescription(description)) {
                fields.put("date", date2Str);
                fields.put(DESCRIPTION, description.trim());
                fields.put(AMOUNT, amountStr.trim());
                final double confidence = calculateConfidence(date, amount, description) * 0.95;
                return new MatchResult(true, fields, confidence, "Pattern3");
            }
        }

        return new MatchResult(false, null, 0.0, "Pattern3");
    }

    /** Pattern 4: Card Date Date ID Description Location Amount */
    private MatchResult tryPattern4(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        final Pattern pattern =
                Pattern.compile(
                        "^(\\d{4})"
                                + FLEXIBLE_WHITESPACE
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + "([A-Z0-9]+)"
                                + FLEXIBLE_WHITESPACE
                                + DESCRIPTION_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + "([A-Z][A-Z\\s]{1,20})"
                                + FLEXIBLE_WHITESPACE
                                + AMOUNT_COMPONENT
                                + "$",
                        Pattern.CASE_INSENSITIVE);

        final Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
            final Map<String, String> fields = new HashMap<>();
            final String dateStr = matcher.group(2); // Use posting date
            String description = matcher.group(4);
            // Clean up description: remove any leading date patterns that might have been captured
            description =
                    description
                            .replaceFirst("^\\s*\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\s+", "")
                            .trim();
            final String location = matcher.group(5);
            final String amountStr = matcher.group(6);

            final LocalDate date = parseDateFlexible(dateStr, inferredYear, isUSLocale);
            final BigDecimal amount = parseAmountFlexible(amountStr);
            // LOGGER.info("MATCHING PATTERN 4: SUM AMEX: DATE = {}, AMOUNT = {}, DESCRIPTION = {}",
            // date, amount, description);
            if (date != null && amount != null && isValidDescription(description)) {
                final String fullDescription = (description + " " + location).trim();
                fields.put("date", dateStr);
                fields.put(DESCRIPTION, fullDescription);
                fields.put(AMOUNT, amountStr.trim());
                final double confidence = calculateConfidence(date, amount, fullDescription) * 0.9;
                return new MatchResult(true, fields, confidence, "Pattern4");
            }
        }

        return new MatchResult(false, null, 0.0, "Pattern4");
    }

    /** Pattern 5: Date Date Merchant Location Amount */
    private MatchResult tryPattern5(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        final Pattern pattern =
                Pattern.compile(
                        "^"
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + DATE_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + DESCRIPTION_COMPONENT
                                + FLEXIBLE_WHITESPACE
                                + "([A-Z][A-Z\\s]{1,20})"
                                + FLEXIBLE_WHITESPACE
                                + AMOUNT_COMPONENT
                                + "$",
                        Pattern.CASE_INSENSITIVE);

        final Matcher matcher = pattern.matcher(line);
        if (matcher.matches()) {
            final Map<String, String> fields = new HashMap<>();
            final String dateStr = matcher.group(2); // Use posting date
            String merchant = matcher.group(3);
            // Clean up merchant description: remove any leading date patterns that might have been
            // captured
            merchant =
                    merchant.replaceFirst("^\\s*\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\s+", "")
                            .trim();
            final String location = matcher.group(4);
            final String amountStr = matcher.group(5);

            final LocalDate date = parseDateFlexible(dateStr, inferredYear, isUSLocale);
            final BigDecimal amount = parseAmountFlexible(amountStr);
            // LOGGER.info("MATCHING PATTERN 5: SUM AMEX: DATE = {}, AMOUNT = {}, MERCHANT = {},
            // LOCATION = {}", date, amount, merchant, location);
            if (date != null && amount != null && isValidDescription(merchant)) {
                final String fullDescription = (merchant + " " + location).trim();
                fields.put("date", dateStr);
                fields.put(DESCRIPTION, fullDescription);
                fields.put(AMOUNT, amountStr.trim());
                final double confidence = calculateConfidence(date, amount, fullDescription) * 0.9;
                return new MatchResult(true, fields, confidence, "Pattern5");
            }
        }

        return new MatchResult(false, null, 0.0, "Pattern5");
    }

    /** Fuzzy matching fallback - extract components independently */
    private MatchResult tryFuzzyMatch(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        final Map<String, String> fields = new HashMap<>();
        double confidence = 0.0;

        // Try to find date
        final String dateStr = findDateFuzzy(line, inferredYear, isUSLocale);
        final LocalDate date =
                dateStr != null ? parseDateFlexible(dateStr, inferredYear, isUSLocale) : null;

        // Try to find amount
        final String amountStr = findAmountFuzzy(line);
        final BigDecimal amount = amountStr != null ? parseAmountFlexible(amountStr) : null;

        // Position-based validation: Amount must be after date and within last 50 chars
        // This filters out informational lines like "$0 - $612.54 will be deducted... on 01/12/26"
        if (dateStr != null && amountStr != null) {
            final int datePos = line.indexOf(dateStr);
            final int amountPos = line.indexOf(amountStr);
            final int amountEnd = amountPos + amountStr.length();
            final int lineEnd = line.length();

            // Amount must be after date (strong signal for valid transaction)
            // Amount should be within last 50 chars (generous threshold for trailing text like "-
            // THANK YOU")
            if (amountPos <= datePos || amountEnd < (lineEnd - 50)) {
                LOGGER.debug(
                        "FuzzyMatch: Rejected due to amount position validation - date at {}, amount at {} (end: {}), line length: {}",
                        datePos,
                        amountPos,
                        amountEnd,
                        lineEnd);
                return new MatchResult(false, null, 0.0, FUZZY_MATCH);
            }
        }

        // Date position validation: Date must be at start of line OR have a valid prefix
        // This allows Pattern 2 transactions (with prefix text like "1% Cashback") while rejecting
        // informational lines
        if (dateStr != null) {
            final int datePos = line.indexOf(dateStr);

            // If date is not at the start (position > 0), check for valid prefix
            if (datePos > 0) {
                final String prefix = line.substring(0, datePos).trim().toLowerCase(Locale.ROOT);
                boolean hasValidPrefix = false;

                // Check for valid prefixes - if found, allow the match
                for (final String validPattern : VALID_PREFIX_PATTERNS) {
                    if (prefix.contains(validPattern.toLowerCase(Locale.ROOT))) {
                        hasValidPrefix = true;
                        LOGGER.debug(
                                "FuzzyMatch: Date not at start (position {}) but has valid prefix '{}'",
                                datePos,
                                validPattern);
                        break; // Found valid prefix, allow the match
                    }
                }

                // If date is not at start and no valid prefix found, reject
                if (!hasValidPrefix) {
                    LOGGER.debug(
                            "FuzzyMatch: Rejected - date not at start (position {}) and no valid prefix found",
                            datePos);
                    return new MatchResult(false, null, 0.0, FUZZY_MATCH);
                }
            }
            // If date is at start (position 0), no prefix check needed - allow the match
        }

        // Try to extract description (everything else)
        final String description = extractDescriptionFuzzy(line, dateStr, amountStr);
        LOGGER.debug(
                "SUM: MATCHING PATTERN FUZZY: DATE = {}, AMOUNT = {}, DESCRIPTION = {}",
                date,
                amount,
                description);
        if (date != null
                && amount != null
                && amountStr != null
                && isValidDescription(description)) {
            fields.put("date", dateStr);
            fields.put(DESCRIPTION, description.trim());
            fields.put(AMOUNT, amountStr.trim());

            // Calculate confidence based on how well we matched
            confidence = 0.6; // Base confidence for fuzzy match
            if (date != null) {
                confidence += 0.1;
            }
            if (amount != null) {
                confidence += 0.1;
            }
            if (isValidDescription(description)) {
                confidence += 0.1;
            }

            return new MatchResult(true, fields, confidence, FUZZY_MATCH);
        }

        return new MatchResult(false, null, 0.0, FUZZY_MATCH);
    }

    /** Find date using fuzzy matching */
    private String findDateFuzzy(
            final String line, final Integer inferredYear, final boolean isUSLocale) {
        for (final Pattern datePattern : DATE_PATTERNS) {
            final Matcher matcher = datePattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(0);
            }
        }
        return null;
    }

    /** Find amount using fuzzy matching */
    private String findAmountFuzzy(final String line) {
        final Matcher matcher = AMOUNT_PATTERN_COMPILED.matcher(line);
        if (matcher.find()) {
            return matcher.group(0).trim();
        }
        return null;
    }

    /** Extract description by removing date and amount */
    private String extractDescriptionFuzzy(
            final String line, final String dateStr, final String amountStr) {
        String description = line;
        // LOGGER.info("SUM Description before extraction: {}", description);
        if (dateStr != null && !dateStr.isBlank()) {
            // Remove date, being careful to match the exact date string
            final String escapedDate = Pattern.quote(dateStr.trim());
            description = description.replaceFirst(escapedDate, "").trim();
        }
        if (amountStr != null && !amountStr.isBlank()) {
            // Escape special regex characters in amount and remove from end
            final String escapedAmount = Pattern.quote(amountStr.trim());
            // Remove from the end of the string to avoid partial matches
            if (description.endsWith(amountStr.trim())) {
                description =
                        description
                                .substring(0, description.length() - amountStr.trim().length())
                                .trim();
            } else {
                description = description.replaceFirst(escapedAmount, "").trim();
            }
        }
        // Clean up extra whitespace and remove any remaining date fragments
        description = description.replaceAll("\\s+", " ").trim();
        // Remove any remaining date-like patterns (e.g., "11" from "11/09")
        description =
                description
                        .replaceAll("^\\d{1,2}\\s+", "")
                        .trim(); // Remove leading single/double digit
        description =
                description
                        .replaceAll("\\s+\\d{1,2}$", "")
                        .trim(); // Remove trailing single/double digit
        // LOGGER.info("SUM Description after extraction: {}", description);
        return description;
    }

    /** Flexible date parsing with multiple format attempts */
    private LocalDate parseDateFlexible(
            final String dateStr, final Integer inferredYear, final boolean isUSLocale) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }

        final String trimmed = dateStr.trim();

        // Try standard formatters first
        final List<DateTimeFormatter> formatters =
                isUSLocale
                        ? Arrays.asList(
                                DateTimeFormatter.ofPattern("M/d/yyyy"),
                                DateTimeFormatter.ofPattern("MM/dd/yyyy"),
                                DateTimeFormatter.ofPattern("M/d/yy"),
                                DateTimeFormatter.ofPattern("MM/dd/yy"),
                                DateTimeFormatter.ofPattern("MM-dd-yy"),
                                DateTimeFormatter.ofPattern("M-d-yy"))
                        : Arrays.asList(
                                DateTimeFormatter.ofPattern("d/M/yyyy"),
                                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                                DateTimeFormatter.ofPattern("d/M/yy"),
                                DateTimeFormatter.ofPattern("dd/MM/yy"));

        for (final DateTimeFormatter formatter : formatters) {
            try {
                return LocalDate.parse(trimmed, formatter);
            } catch (DateTimeParseException e) {
                // Continue
            }
        }

        // Try MM/DD or DD/MM format
        final Pattern mmddPattern =
                Pattern.compile("^(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?$");
        final Matcher mmddMatcher = mmddPattern.matcher(trimmed);

        if (mmddMatcher.matches()) {
            try {
                final int firstNum = Integer.parseInt(mmddMatcher.group(1));
                final int secondNum = Integer.parseInt(mmddMatcher.group(2));
                final String yearGroup = mmddMatcher.group(3);

                int month, day, year;

                if (isUSLocale) {
                    month = firstNum;
                    day = secondNum;
                } else {
                    month = secondNum;
                    day = firstNum;
                }

                if (yearGroup != null) {
                    year = Integer.parseInt(yearGroup);
                    if (year < 100) {
                        year += 2000;
                    }
                } else if (inferredYear != null) {
                    year = inferredYear;
                } else {
                    year = LocalDate.now().getYear();
                }

                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    return LocalDate.of(year, month, day);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to parse date: {}", trimmed);
            }
        }

        return null;
    }

    /** Flexible amount parsing - handles various formats */
    private BigDecimal parseAmountFlexible(final String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            return null;
        }

        String trimmed = amountStr.trim();
        boolean isNegative = false;
        boolean isCredit = false;
        boolean isDebit = false;
        boolean hasParentheses = false;

        // Check for CR/DR/BF indicators
        final String upper = trimmed.toUpperCase(Locale.ROOT);
        if (upper.endsWith(" CR") || upper.endsWith(" CREDIT")) {
            isCredit = true;
            trimmed =
                    trimmed.substring(0, trimmed.length() - (upper.endsWith(" CREDIT") ? 7 : 3))
                            .trim();
        } else if (upper.endsWith(" DR") || upper.endsWith(" DEBIT")) {
            isDebit = true;
            trimmed =
                    trimmed.substring(0, trimmed.length() - (upper.endsWith(" DEBIT") ? 6 : 3))
                            .trim();
        } else if (upper.endsWith(" BF")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }

        // Check for parentheses
        if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
            hasParentheses = true;
            isNegative = true;
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }

        // Check for explicit +/- signs
        if (trimmed.startsWith("-")) {
            isNegative = true;
            trimmed = trimmed.substring(1).trim();
        } else if (trimmed.startsWith("+")) {
            trimmed = trimmed.substring(1).trim();
        }

        // Remove currency symbols, thousands separators, and spaces
        final String cleanAmount = trimmed.replaceAll("[\\$€£¥₹,\\s]", "").trim();

        if (cleanAmount.isEmpty()) {
            return null;
        }

        try {
            BigDecimal amount = new BigDecimal(cleanAmount);

            // Apply sign. isCredit deliberately has no branch — credits are
            // already positive — so collapse the chain instead of leaving an
            // empty else-if (Checkstyle EmptyBlock).
            if (isDebit || hasParentheses || (isNegative && !isCredit)) {
                amount = amount.negate();
            }

            return amount;
        } catch (NumberFormatException e) {
            LOGGER.debug("Invalid amount format: {}", amountStr);
            return null;
        }
    }

    /**
     * Validate description - Minimal filtering approach
     *
     * <p>Strategy: Keep only filters for guaranteed false positives that slipped through early
     * filtering. Most filtering happens at line-level (early filtering) and pattern matching
     * already requires valid date + amount patterns. This reduces false negatives while maintaining
     * data quality.
     */
    private boolean isValidDescription(final String description) {
        if (description == null) {
            return false;
        }
        final String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.matches("^\\s+$")) {
            return false;
        }

        // Minimal filtering: Only reject guaranteed false positives that somehow passed early
        // filtering
        final String lower = trimmed.toLowerCase(Locale.ROOT);

        // Only reject if description is ONLY a phone number (standalone informational line)
        // Allow phone numbers in merchant descriptions (e.g., "WWW COSTCO COM 800-955-2292 WA")
        if (lower.trim().matches("^\\d{1,3}-\\d{3}-\\d{3}-\\d{4}$")
                || // Standalone: "1-800-436-7958"
                lower.trim().matches("^\\d{1,3}-\\d{3}-\\d{4}-\\d{4}$")
                || // Standalone: "1-302-594-8200"
                lower.trim().matches("^\\d{3}-\\d{3}-\\d{4}$")) { // Standalone: "800-436-7958"
            return false;
        }

        // Page numbers (always footers)
        if (lower.matches(".*page\\s+\\d+\\s+of\\s+\\d+.*")) {
            return false;
        }

        // Very specific header patterns that are guaranteed false positives
        // (These should be caught by early filtering, but kept as safety net)
        if (lower.contains("open to close date") || lower.matches(".*open.*to.*close.*date.*")) {
            return false;
        }

        // Filter agreement-related informational lines (e.g., "Cardmember Agreement for details")
        if (lower.contains("agreement for details")
                || lower.contains("cardmember agreement")
                || lower.contains("cardholder agreement")) {
            return false;
        }

        // All other descriptions are accepted - rely on pattern matching and confidence scores
        // Description is valid: {}", description);
        return true;
    }

    /** Calculate confidence score for a match */
    private double calculateConfidence(
            final LocalDate date, final BigDecimal amount, final String description) {
        double confidence = 1.0;
        // LOGGER.info("SUMCalculateCONFIDENCE desc: {}, date:{}, amount:{}", description, date,
        // amount);

        // Reduce confidence if date is far in the future or past
        if (date != null) {
            final LocalDate now = LocalDate.now();
            final long daysDiff = java.time.temporal.ChronoUnit.DAYS.between(date, now);
            // Check if date is far in the past or future (more than 5 years)
            if (daysDiff > 365 * 5 || daysDiff < -365 * 5) {
                confidence *= 0.8;
            }
        }

        // Reduce confidence if amount is zero or very small
        if (amount != null
                && (amount.compareTo(new BigDecimal("0.01")) < 0
                        && amount.compareTo(new BigDecimal("-0.01")) > 0)) {
            confidence *= 0.5;
        }

        // Reduce confidence if description is very short or very long
        if (description != null) {
            final int len = description.strip().length();
            if (len < 3) {
                confidence *= 0.7;
            } else if (len > 200) {
                confidence *= 0.8;
            }
        }

        // LOGGER.info("SUMCalculateCONFIDENCE desc: {}, date:{}, amount:{}, confidence:{}",
        // description, date, amount, confidence);

        return Math.min(1.0, confidence);
    }
}
