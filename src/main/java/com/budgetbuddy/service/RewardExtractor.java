package com.budgetbuddy.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Comprehensive reward extraction service for global bank statements
 *
 * <p>Features: - Multi-language reward label support - Multiple reward types (points, cash back,
 * miles, etc.) - Flexible pattern matching for different banks/cards - Edge case handling (missing
 * values, invalid formats, etc.) - Extensible pattern registry
 *
 * <p>Supports formats from: - North America (US, Canada) - Europe (UK, Germany, France, Spain,
 * Italy, etc.) - Asia (India, Japan, China, Singapore, etc.) - Australia, New Zealand - Latin
 * America
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.DataClass",
    "PMD.OnlyOneReturn"
})
@Component
public class RewardExtractor {

    private static final String D_1_2_D_1_2_D_2_4 = "(?![\\d/]{1,2}/[\\d/]{1,2}/[\\d]{2,4})";

    private static final String S_S_S_D_S_S =
            "([\\$€£¥₹]?\\s*(?:\\(\\s*[\\$€£¥₹]?\\s*)?[\\d\\s,.]+(?:\\s*\\))?)";

    private static final String D_1_7_D_3 = "(\\d{1,7}(?:,\\d{3})*)";

    private static final String EXTRACTED_CASH_BACK_BALANCE_PATTERN =
            "✓ Extracted cash back balance: {} (pattern: '{}')";

    private static final Logger LOGGER = LoggerFactory.getLogger(RewardExtractor.class);

    // Maximum reasonable reward values
    private static final long MAX_REASONABLE_POINTS = 100_000_000L; // 100 million points
    private static final BigDecimal MAX_REASONABLE_CASH_BACK = new BigDecimal("999999.99");

    // Pre-compiled patterns shared by the multi-line points scan. Compile-once avoids the
    // per-call allocation that used to live inside extractRewardPoints.
    //
    // MULTI_LINE_REWARD_LINE1 must reference points/rewards/miles. The previous
    // version also accepted bare "available" or "total" — far too loose: it
    // fires on every "Total Payments and Credits ..." line which sits right
    // above a dollar amount, producing nonsense like rewardPoints=1396 on a
    // cash-back card with no points printed. Require a points-domain keyword.
    private static final Pattern MULTI_LINE_REWARD_LINE1 =
            Pattern.compile(
                    "(?i).*(?:"
                            + "points|pts|rewards|miles"
                            + "|available\\s+(?:points|rewards|miles)"
                            + "|total\\s+(?:points|rewards|miles)"
                            + ").*");
    private static final Pattern MULTI_LINE_ACCOUNT_HINT =
            Pattern.compile("(?i).*(?:account|as\\s+of).*");
    private static final Pattern DATE_IN_LINE = Pattern.compile(".*\\d{1,2}/\\d{1,2}/\\d{2,4}.*");
    private static final Pattern ACCOUNT_NUMBER_TAIL = Pattern.compile(".*\\d{4}\\s*$");
    private static final Pattern NUMBER_WITH_COMMA = Pattern.compile("(\\d{1,7}(?:,\\d{3})+)");
    // Currency-bearing lines aren't point values — reject when probing for a
    // raw integer points total. Catches "Payments -$1,396.00" and "$1,461.05".
    private static final Pattern HAS_CURRENCY_SYMBOL =
            Pattern.compile(".*[\\$£€₹¥].*");

    /** Reward pattern definition */
    public static class RewardPattern {
        private final String name;
        private final Pattern pattern;
        private final RewardType type;
        private final int priority; // Higher priority = checked first

        public RewardPattern(
                final String name,
                final Pattern pattern,
                final RewardType type,
                final int priority) {
            this.name = name;
            this.pattern = pattern;
            this.type = type;
            this.priority = priority;
        }

        public String getName() {
            return name;
        }

        public Pattern getPattern() {
            return pattern;
        }

        public RewardType getType() {
            return type;
        }

        public int getPriority() {
            return priority;
        }
    }

    /** Reward type enumeration */
    public enum RewardType {
        POINTS, // Points-based rewards (e.g., 50,519 points)
        CASH_BACK, // Cash back rewards (e.g., $488.97)
        MILES, // Miles/airline rewards
        UNKNOWN // Unknown type
    }

    /** Reward extraction result */
    public static class RewardResult {
        private final RewardType type;
        private final Long points;
        private final BigDecimal cashBack;
        private final String label;
        private final int position;

        public RewardResult(
                final RewardType type,
                final Long points,
                final BigDecimal cashBack,
                final String label,
                final int position) {
            this.type = type;
            this.points = points;
            this.cashBack = cashBack;
            this.label = label;
            this.position = position;
        }

        public RewardType getType() {
            return type;
        }

        public Long getPoints() {
            return points;
        }

        public BigDecimal getCashBack() {
            return cashBack;
        }

        public String getLabel() {
            return label;
        }

        public int getPosition() {
            return position;
        }

        public boolean isValid() {
            return (points != null && points >= 0)
                    || (cashBack != null && cashBack.compareTo(BigDecimal.ZERO) >= 0);
        }
    }

    // Pattern registry - ordered by priority (highest first)
    private final List<RewardPattern> rewardPatterns;

    public RewardExtractor() {
        this.rewardPatterns = initializePatterns();
    }

    /**
     * Initialize reward patterns from different banks/cards Patterns are ordered by specificity
     * (most specific first)
     */
    private List<RewardPattern> initializePatterns() {
        final List<RewardPattern> patterns = new ArrayList<>();

        // ===== POINTS PATTERNS (Priority: High to Low) =====

        // Pattern 1: "Points as of MM/DD/YYYY: 5,000" (most specific)
        patterns.add(
                new RewardPattern(
                        "points_as_of_date",
                        Pattern.compile(
                                "(?i)(?:points|pts|rewards\\s+points|membership\\s+rewards\\s+points|thank\\s+you\\s+points|citi\\s+thank\\s+you\\s+points)"
                                        + "\\s+as\\s+of\\s+\\d{1,2}/\\d{1,2}/\\d{2,4}[\\s:]+"
                                        + D_1_7_D_3),
                        RewardType.POINTS,
                        100));

        // Pattern 2: "Total points transferred to [Partner] 8,733"
        patterns.add(
                new RewardPattern(
                        "points_transferred_to",
                        Pattern.compile(
                                "(?i)total\\s+points\\s+transferred\\s+to\\s+[a-z\\s]+\\s+"
                                        + D_1_7_D_3),
                        RewardType.POINTS,
                        95));

        // Pattern 3: "Total points available for redemption 50,519"
        patterns.add(
                new RewardPattern(
                        "points_available_for_redemption",
                        Pattern.compile(
                                "(?i)total\\s+points\\s+available\\s+for\\s+(?:redemption|redeeming|use)\\s+"
                                        + D_1_7_D_3),
                        RewardType.POINTS,
                        90));

        // Pattern 4: "Points available: 25,000" or "Available points: 25,000"
        patterns.add(
                new RewardPattern(
                        "points_available",
                        Pattern.compile(
                                "(?i)(?:points|pts|rewards\\s+points)\\s+available[:\\s]+"
                                        + D_1_2_D_1_2_D_2_4
                                        + // Negative lookahead: don't match dates
                                        D_1_7_D_3),
                        RewardType.POINTS,
                        85));

        // Pattern 5: "Available points: 25,000"
        patterns.add(
                new RewardPattern(
                        "available_points",
                        Pattern.compile(
                                "(?i)available\\s+(?:points|pts|rewards\\s+points)[:\\s]+"
                                        + D_1_2_D_1_2_D_2_4
                                        + D_1_7_D_3),
                        RewardType.POINTS,
                        80));

        // Pattern 6: "Points: 5,000" or "Points 5,000" (standard format)
        patterns.add(
                new RewardPattern(
                        "points_standard",
                        Pattern.compile(
                                "(?i)(?:membership\\s+rewards\\s+points|thank\\s+you\\s+points|citi\\s+thank\\s+you\\s+points|"
                                        + "rewards\\s+points|points|pts)[\\s:]+"
                                        + D_1_2_D_1_2_D_2_4
                                        + D_1_7_D_3),
                        RewardType.POINTS,
                        70));

        // Pattern 7: "Points balance: 30,000"
        patterns.add(
                new RewardPattern(
                        "points_balance",
                        Pattern.compile(
                                "(?i)(?:points|pts|rewards\\s+points)\\s+balance[:\\s]+"
                                        + D_1_7_D_3),
                        RewardType.POINTS,
                        65));

        // Pattern 8: Simple "Points" or "Pts" followed by number
        patterns.add(
                new RewardPattern(
                        "points_simple",
                        Pattern.compile(
                                "(?i)(?:points|pts)[\\s:]+" + D_1_2_D_1_2_D_2_4 + D_1_7_D_3),
                        RewardType.POINTS,
                        60));

        // ===== CASH BACK PATTERNS =====

        // Pattern 9: "Cash Back Rewards Balance: $488.97" or "Cash Back Rewards Balance: ($100.00)"
        // (with optional newline)
        patterns.add(
                new RewardPattern(
                        "cash_back_rewards_balance",
                        Pattern.compile(
                                "(?i)cash\\s+back\\s+rewards?\\s+balance[:\\s]*[\\n\\r\\s]*"
                                        + S_S_S_D_S_S),
                        RewardType.CASH_BACK,
                        90));

        // Pattern 10: "Cash Back Balance: $100.00" or "Cash Back Balance: ($100.00)" (with optional
        // newline)
        patterns.add(
                new RewardPattern(
                        "cash_back_balance",
                        Pattern.compile(
                                "(?i)cash\\s+back\\s+balance[:\\s]*[\\n\\r\\s]*" + S_S_S_D_S_S),
                        RewardType.CASH_BACK,
                        85));

        // Pattern 11: "Rewards Balance: $100.00" or "Rewards Balance: ($100.00)" (with optional
        // newline)
        patterns.add(
                new RewardPattern(
                        "rewards_balance",
                        Pattern.compile("(?i)rewards?\\s+balance[:\\s]*[\\n\\r\\s]*" + S_S_S_D_S_S),
                        RewardType.CASH_BACK,
                        80));

        // ===== MILES PATTERNS =====

        // Pattern 12: "Miles: 50,000" or "Available miles: 50,000" (treated as points)
        patterns.add(
                new RewardPattern(
                        "miles",
                        Pattern.compile(
                                "(?i)(?:available\\s+)?miles?[:\\s]+"
                                        + D_1_2_D_1_2_D_2_4
                                        + D_1_7_D_3),
                        RewardType.POINTS, // Miles are treated as points internally
                        75));

        // Sort by priority (highest first)
        patterns.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

        return patterns;
    }

    /**
     * Extract reward points from text lines
     *
     * @param lines Array of text lines from PDF
     * @return Extracted points or null if not found
     */
    public Long extractRewardPoints(final String[] lines) {
        if (lines == null || lines.length == 0) {
            return null;
        }
        final Long single = extractPointsSingleLine(lines);
        if (single != null) {
            return single;
        }
        return extractPointsMultiLine(lines);
    }

    /** Walk each line and try {@link #extractRewardFromLine} for a points hit. */
    private Long extractPointsSingleLine(final String[] lines) {
        for (final String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            final RewardResult result = extractRewardFromLine(line.trim());
            if (result != null
                    && result.getType() == RewardType.POINTS
                    && result.getPoints() != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "✓ Extracted reward points: {} (pattern: '{}')",
                            result.getPoints(),
                            result.getLabel());
                }
                return result.getPoints();
            }
        }
        return null;
    }

    /**
     * Sliding 3-line window: when a line mentions reward keywords, check the following one or two
     * lines for the actual number.
     */
    private Long extractPointsMultiLine(final String[] lines) {
        for (int i = 0; i < lines.length - 1; i++) {
            final String line1 = safeTrim(lines, i);
            if (!MULTI_LINE_REWARD_LINE1.matcher(line1).matches()) {
                continue;
            }
            final String line2 = safeTrim(lines, i + 1);
            final String line3 = safeTrim(lines, i + 2);

            final Long fromLine2 = parsePointsIfNotDateOrAccount(line2, "line2");
            if (fromLine2 != null) {
                return fromLine2;
            }
            if (MULTI_LINE_ACCOUNT_HINT.matcher(line2).matches()) {
                final Long fromLine3 = parsePointsIfNotDateOrAccount(line3, "line3");
                if (fromLine3 != null) {
                    return fromLine3;
                }
            }
        }
        return null;
    }

    /** Trim {@code lines[idx]} if in range, else return empty string. */
    private static String safeTrim(final String[] lines, final int idx) {
        if (idx < 0 || idx >= lines.length || lines[idx] == null) {
            return "";
        }
        return lines[idx].trim();
    }

    /**
     * Pull the first comma-grouped number out of {@code line} as long as the line doesn't look like
     * a date or a 4-digit account number suffix.
     */
    private Long parsePointsIfNotDateOrAccount(final String line, final String origin) {
        if (DATE_IN_LINE.matcher(line).matches()
                || ACCOUNT_NUMBER_TAIL.matcher(line).matches()
                || HAS_CURRENCY_SYMBOL.matcher(line).matches()) {
            return null;
        }
        final Matcher matcher = NUMBER_WITH_COMMA.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        try {
            final long points = Long.parseLong(matcher.group(1).replaceAll(",", ""));
            if (points >= 0 && points <= MAX_REASONABLE_POINTS) {
                LOGGER.debug("✓ Extracted reward points from multi-line: {} ({})", points, origin);
                return points;
            }
        } catch (NumberFormatException e) {
            // fall through to null
        }
        return null;
    }

    /**
     * Extract reward from a single line using pattern matching
     *
     * @param line Text line to search
     * @return RewardResult or null if not found
     */
    private RewardResult extractRewardFromLine(final String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        // Try each pattern in priority order
        for (final RewardPattern pattern : rewardPatterns) {
            final Matcher matcher = pattern.getPattern().matcher(line);
            if (matcher.find()) {
                try {
                    final String valueStr = matcher.group(1);
                    if (valueStr == null || valueStr.isBlank()) {
                        continue;
                    }

                    if (pattern.getType() == RewardType.POINTS
                            || pattern.getType() == RewardType.MILES) {
                        // Extract points/miles (miles are treated as points internally)
                        final String pointsStr = valueStr.replaceAll(",", "").trim();
                        final long points = Long.parseLong(pointsStr);
                        if (points >= 0 && points <= MAX_REASONABLE_POINTS) {
                            // Always return POINTS type (miles are converted to points)
                            return new RewardResult(
                                    RewardType.POINTS,
                                    points,
                                    null,
                                    pattern.getName(),
                                    matcher.start());
                        }
                    } else if (pattern.getType() == RewardType.CASH_BACK) {
                        // Extract cash back amount (can be negative for accounting format)
                        final BigDecimal cashBack = parseCashBackAmount(valueStr);
                        if (cashBack != null
                                && cashBack.abs().compareTo(MAX_REASONABLE_CASH_BACK) <= 0) {
                            return new RewardResult(
                                    pattern.getType(),
                                    null,
                                    cashBack,
                                    pattern.getName(),
                                    matcher.start());
                        }
                    }
                } catch (NumberFormatException e) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Failed to parse reward value from pattern '{}': {}",
                                pattern.getName(),
                                e.getMessage());
                    }
                    continue; // Try next pattern
                } catch (Exception e) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Error extracting reward with pattern '{}': {}",
                                pattern.getName(),
                                e.getMessage());
                    }
                    continue;
                }
            }
        }

        return null;
    }

    /**
     * Parse cash back amount from string Handles various formats: $488.97, (488.97), -$100.00, etc.
     */
    private BigDecimal parseCashBackAmount(final String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            return null;
        }

        String cleaned = amountStr.trim();
        boolean isNegative = cleaned.startsWith("(") && cleaned.endsWith(")");

        if (isNegative) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }

        // Remove currency symbols
        cleaned = cleaned.replaceAll("[\\$€£¥₹]", "").trim();

        // Remove whitespace
        cleaned = cleaned.replaceAll("\\s+", "");

        // Handle negative sign
        if (cleaned.startsWith("-")) {
            isNegative = true;
            cleaned = cleaned.substring(1);
        }

        try {
            // Normalize number format (handle comma thousands separators)
            cleaned = cleaned.replace(",", "");
            BigDecimal amount = new BigDecimal(cleaned);
            if (isNegative) {
                amount = amount.negate();
            }
            return amount;
        } catch (NumberFormatException e) {
            LOGGER.debug("Failed to parse cash back amount: {}", amountStr);
            return null;
        }
    }

    /**
     * Extract cash back rewards balance from text
     *
     * @param text Text to search (can contain newlines)
     * @return Extracted cash back amount or null if not found
     */
    public BigDecimal extractCashBackBalance(final String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // Normalize newlines to spaces for pattern matching
        final String normalizedText =
                text.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();

        // Try full normalized text first (handles newlines within pattern)
        RewardResult result = extractRewardFromLine(normalizedText);
        if (result != null
                && result.getType() == RewardType.CASH_BACK
                && result.getCashBack() != null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        EXTRACTED_CASH_BACK_BALANCE_PATTERN,
                        result.getCashBack(),
                        result.getLabel());
            }
            return result.getCashBack();
        }

        // Fallback: try line by line (in case pattern spans multiple lines differently)
        final String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i] != null ? lines[i].trim() : "";
            if (line.isEmpty()) {
                continue;
            }

            // Try current line
            result = extractRewardFromLine(line);
            if (result != null
                    && result.getType() == RewardType.CASH_BACK
                    && result.getCashBack() != null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            EXTRACTED_CASH_BACK_BALANCE_PATTERN,
                            result.getCashBack(),
                            result.getLabel());
                }
                return result.getCashBack();
            }

            // Try combining with next line (for newline-separated amounts)
            if (i + 1 < lines.length) {
                final String nextLine = lines[i + 1] != null ? lines[i + 1].trim() : "";
                final String combined = line + " " + nextLine;
                result = extractRewardFromLine(combined);
                if (result != null
                        && result.getType() == RewardType.CASH_BACK
                        && result.getCashBack() != null) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                EXTRACTED_CASH_BACK_BALANCE_PATTERN,
                                result.getCashBack(),
                                result.getLabel());
                    }
                    return result.getCashBack();
                }
            }
        }

        return null;
    }

    /** Get all reward patterns (for testing/debugging) */
    public List<RewardPattern> getRewardPatterns() {
        return new ArrayList<>(rewardPatterns);
    }
}
