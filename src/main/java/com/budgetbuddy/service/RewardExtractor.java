package com.budgetbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Comprehensive reward extraction service for global bank statements
 * 
 * Features:
 * - Multi-language reward label support
 * - Multiple reward types (points, cash back, miles, etc.)
 * - Flexible pattern matching for different banks/cards
 * - Edge case handling (missing values, invalid formats, etc.)
 * - Extensible pattern registry
 * 
 * Supports formats from:
 * - North America (US, Canada)
 * - Europe (UK, Germany, France, Spain, Italy, etc.)
 * - Asia (India, Japan, China, Singapore, etc.)
 * - Australia, New Zealand
 * - Latin America
 */
@Component
public class RewardExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(RewardExtractor.class);
    
    // Maximum reasonable reward values
    private static final long MAX_REASONABLE_POINTS = 100_000_000L; // 100 million points
    private static final BigDecimal MAX_REASONABLE_CASH_BACK = new BigDecimal("999999.99");
    
    /**
     * Reward pattern definition
     */
    public static class RewardPattern {
        private final String name;
        private final Pattern pattern;
        private final RewardType type;
        private final int priority; // Higher priority = checked first
        
        public RewardPattern(String name, Pattern pattern, RewardType type, int priority) {
            this.name = name;
            this.pattern = pattern;
            this.type = type;
            this.priority = priority;
        }
        
        public String getName() { return name; }
        public Pattern getPattern() { return pattern; }
        public RewardType getType() { return type; }
        public int getPriority() { return priority; }
    }
    
    /**
     * Reward type enumeration
     */
    public enum RewardType {
        POINTS,      // Points-based rewards (e.g., 50,519 points)
        CASH_BACK,   // Cash back rewards (e.g., $488.97)
        MILES,       // Miles/airline rewards
        UNKNOWN      // Unknown type
    }
    
    /**
     * Reward extraction result
     */
    public static class RewardResult {
        private final RewardType type;
        private final Long points;
        private final BigDecimal cashBack;
        private final String label;
        private final int position;
        
        public RewardResult(RewardType type, Long points, BigDecimal cashBack, String label, int position) {
            this.type = type;
            this.points = points;
            this.cashBack = cashBack;
            this.label = label;
            this.position = position;
        }
        
        public RewardType getType() { return type; }
        public Long getPoints() { return points; }
        public BigDecimal getCashBack() { return cashBack; }
        public String getLabel() { return label; }
        public int getPosition() { return position; }
        
        public boolean isValid() {
            return (points != null && points >= 0) || (cashBack != null && cashBack.compareTo(BigDecimal.ZERO) >= 0);
        }
    }
    
    // Pattern registry - ordered by priority (highest first)
    private final List<RewardPattern> rewardPatterns;
    
    public RewardExtractor() {
        this.rewardPatterns = initializePatterns();
    }
    
    /**
     * Initialize reward patterns from different banks/cards
     * Patterns are ordered by specificity (most specific first)
     */
    private List<RewardPattern> initializePatterns() {
        List<RewardPattern> patterns = new ArrayList<>();
        
        // ===== POINTS PATTERNS (Priority: High to Low) =====
        
        // Pattern 1: "Points as of MM/DD/YYYY: 5,000" (most specific)
        patterns.add(new RewardPattern(
            "points_as_of_date",
            Pattern.compile(
                "(?i)(?:points|pts|rewards\\s+points|membership\\s+rewards\\s+points|thank\\s+you\\s+points|citi\\s+thank\\s+you\\s+points)" +
                "\\s+as\\s+of\\s+\\d{1,2}/\\d{1,2}/\\d{2,4}[\\s:]+" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            100
        ));
        
        // Pattern 2: "Total points transferred to [Partner] 8,733"
        patterns.add(new RewardPattern(
            "points_transferred_to",
            Pattern.compile(
                "(?i)total\\s+points\\s+transferred\\s+to\\s+[a-z\\s]+\\s+" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            95
        ));
        
        // Pattern 3: "Total points available for redemption 50,519"
        patterns.add(new RewardPattern(
            "points_available_for_redemption",
            Pattern.compile(
                "(?i)total\\s+points\\s+available\\s+for\\s+(?:redemption|redeeming|use)\\s+" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            90
        ));
        
        // Pattern 4: "Points available: 25,000" or "Available points: 25,000"
        patterns.add(new RewardPattern(
            "points_available",
            Pattern.compile(
                "(?i)(?:points|pts|rewards\\s+points)\\s+available[:\\s]+" +
                "(?![\\d/]{1,2}/[\\d/]{1,2}/[\\d]{2,4})" + // Negative lookahead: don't match dates
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            85
        ));
        
        // Pattern 5: "Available points: 25,000"
        patterns.add(new RewardPattern(
            "available_points",
            Pattern.compile(
                "(?i)available\\s+(?:points|pts|rewards\\s+points)[:\\s]+" +
                "(?![\\d/]{1,2}/[\\d/]{1,2}/[\\d]{2,4})" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            80
        ));
        
        // Pattern 6: "Points: 5,000" or "Points 5,000" (standard format)
        patterns.add(new RewardPattern(
            "points_standard",
            Pattern.compile(
                "(?i)(?:membership\\s+rewards\\s+points|thank\\s+you\\s+points|citi\\s+thank\\s+you\\s+points|" +
                "rewards\\s+points|points|pts)[\\s:]+" +
                "(?![\\d/]{1,2}/[\\d/]{1,2}/[\\d]{2,4})" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            70
        ));
        
        // Pattern 7: "Points balance: 30,000"
        patterns.add(new RewardPattern(
            "points_balance",
            Pattern.compile(
                "(?i)(?:points|pts|rewards\\s+points)\\s+balance[:\\s]+" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            65
        ));
        
        // Pattern 8: Simple "Points" or "Pts" followed by number
        patterns.add(new RewardPattern(
            "points_simple",
            Pattern.compile(
                "(?i)(?:points|pts)[\\s:]+" +
                "(?![\\d/]{1,2}/[\\d/]{1,2}/[\\d]{2,4})" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS,
            60
        ));
        
        // ===== CASH BACK PATTERNS =====
        
        // Pattern 9: "Cash Back Rewards Balance: $488.97" or "Cash Back Rewards Balance: ($100.00)" (with optional newline)
        patterns.add(new RewardPattern(
            "cash_back_rewards_balance",
            Pattern.compile(
                "(?i)cash\\s+back\\s+rewards?\\s+balance[:\\s]*[\\n\\r\\s]*" +
                "([\\$€£¥₹]?\\s*(?:\\(\\s*[\\$€£¥₹]?\\s*)?[\\d\\s,.]+(?:\\s*\\))?)"
            ),
            RewardType.CASH_BACK,
            90
        ));
        
        // Pattern 10: "Cash Back Balance: $100.00" or "Cash Back Balance: ($100.00)" (with optional newline)
        patterns.add(new RewardPattern(
            "cash_back_balance",
            Pattern.compile(
                "(?i)cash\\s+back\\s+balance[:\\s]*[\\n\\r\\s]*" +
                "([\\$€£¥₹]?\\s*(?:\\(\\s*[\\$€£¥₹]?\\s*)?[\\d\\s,.]+(?:\\s*\\))?)"
            ),
            RewardType.CASH_BACK,
            85
        ));
        
        // Pattern 11: "Rewards Balance: $100.00" or "Rewards Balance: ($100.00)" (with optional newline)
        patterns.add(new RewardPattern(
            "rewards_balance",
            Pattern.compile(
                "(?i)rewards?\\s+balance[:\\s]*[\\n\\r\\s]*" +
                "([\\$€£¥₹]?\\s*(?:\\(\\s*[\\$€£¥₹]?\\s*)?[\\d\\s,.]+(?:\\s*\\))?)"
            ),
            RewardType.CASH_BACK,
            80
        ));
        
        // ===== MILES PATTERNS =====
        
        // Pattern 12: "Miles: 50,000" or "Available miles: 50,000" (treated as points)
        patterns.add(new RewardPattern(
            "miles",
            Pattern.compile(
                "(?i)(?:available\\s+)?miles?[:\\s]+" +
                "(?![\\d/]{1,2}/[\\d/]{1,2}/[\\d]{2,4})" +
                "(\\d{1,7}(?:,\\d{3})*)"
            ),
            RewardType.POINTS, // Miles are treated as points internally
            75
        ));
        
        // Sort by priority (highest first)
        patterns.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        
        return patterns;
    }
    
    /**
     * Extract reward points from text lines
     * @param lines Array of text lines from PDF
     * @return Extracted points or null if not found
     */
    public Long extractRewardPoints(String[] lines) {
        if (lines == null || lines.length == 0) {
            return null;
        }
        
        // Try single-line extraction first
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()) {
                continue;
            }
            
            RewardResult result = extractRewardFromLine(line.trim());
            if (result != null && result.getType() == RewardType.POINTS && result.getPoints() != null) {
                logger.debug("✓ Extracted reward points: {} (pattern: '{}')", result.getPoints(), result.getLabel());
                return result.getPoints();
            }
        }
        
        // Try multi-line extraction (check current line + next 2 lines)
        for (int i = 0; i < lines.length - 1; i++) { // Changed to -1 to allow checking line2 when only 2 lines exist
            String line1 = lines[i] != null ? lines[i].trim() : "";
            String line2 = i + 1 < lines.length ? (lines[i + 1] != null ? lines[i + 1].trim() : "") : "";
            String line3 = i + 2 < lines.length ? (lines[i + 2] != null ? lines[i + 2].trim() : "") : "";
            
            // Check if line1 contains reward keywords (including "available", "total", etc.)
            if (line1.matches("(?i).*(?:points|pts|rewards|miles|available|total).*")) {
                // Check line2 for number (skip if it looks like a date or account number)
                if (!line2.matches(".*\\d{1,2}/\\d{1,2}/\\d{2,4}.*") && // Not a date
                    !line2.matches(".*\\d{4}\\s*$")) { // Not a 4-digit account number at end
                    Pattern numberPattern = Pattern.compile("(\\d{1,7}(?:,\\d{3})+)"); // Must have comma (thousands separator)
                    Matcher matcher = numberPattern.matcher(line2);
                    if (matcher.find()) {
                        String pointsStr = matcher.group(1).replaceAll(",", "");
                        try {
                            long points = Long.parseLong(pointsStr);
                            if (points >= 0 && points <= MAX_REASONABLE_POINTS) {
                                logger.debug("✓ Extracted reward points from multi-line: {} (line2)", points);
                                return points;
                            }
                        } catch (NumberFormatException e) {
                            // Continue to line3
                        }
                    }
                }
                
                // Check line3 for number (if line2 had account details)
                if (line2.matches("(?i).*(?:account|as\\s+of).*")) {
                    if (!line3.matches(".*\\d{1,2}/\\d{1,2}/\\d{2,4}.*") && // Not a date
                        !line3.matches(".*\\d{4}\\s*$")) { // Not a 4-digit account number at end
                        Pattern numberPattern = Pattern.compile("(\\d{1,7}(?:,\\d{3})+)"); // Must have comma
                        Matcher matcher = numberPattern.matcher(line3);
                        if (matcher.find()) {
                            String pointsStr = matcher.group(1).replaceAll(",", "");
                            try {
                                long points = Long.parseLong(pointsStr);
                                if (points >= 0 && points <= MAX_REASONABLE_POINTS) {
                                    logger.debug("✓ Extracted reward points from multi-line: {} (line3)", points);
                                    return points;
                                }
                            } catch (NumberFormatException e) {
                                // Continue
                            }
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extract reward from a single line using pattern matching
     * @param line Text line to search
     * @return RewardResult or null if not found
     */
    private RewardResult extractRewardFromLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        
        // Try each pattern in priority order
        for (RewardPattern pattern : rewardPatterns) {
            Matcher matcher = pattern.getPattern().matcher(line);
            if (matcher.find()) {
                try {
                    String valueStr = matcher.group(1);
                    if (valueStr == null || valueStr.trim().isEmpty()) {
                        continue;
                    }
                    
                    if (pattern.getType() == RewardType.POINTS || pattern.getType() == RewardType.MILES) {
                        // Extract points/miles (miles are treated as points internally)
                        String pointsStr = valueStr.replaceAll(",", "").trim();
                        long points = Long.parseLong(pointsStr);
                        if (points >= 0 && points <= MAX_REASONABLE_POINTS) {
                            // Always return POINTS type (miles are converted to points)
                            return new RewardResult(RewardType.POINTS, points, null, pattern.getName(), matcher.start());
                        }
                    } else if (pattern.getType() == RewardType.CASH_BACK) {
                        // Extract cash back amount (can be negative for accounting format)
                        BigDecimal cashBack = parseCashBackAmount(valueStr);
                        if (cashBack != null && 
                            cashBack.abs().compareTo(MAX_REASONABLE_CASH_BACK) <= 0) {
                            return new RewardResult(pattern.getType(), null, cashBack, pattern.getName(), matcher.start());
                        }
                    }
                } catch (NumberFormatException e) {
                    logger.debug("Failed to parse reward value from pattern '{}': {}", pattern.getName(), e.getMessage());
                    continue; // Try next pattern
                } catch (Exception e) {
                    logger.debug("Error extracting reward with pattern '{}': {}", pattern.getName(), e.getMessage());
                    continue;
                }
            }
        }
        
        return null;
    }
    
    /**
     * Parse cash back amount from string
     * Handles various formats: $488.97, (488.97), -$100.00, etc.
     */
    private BigDecimal parseCashBackAmount(String amountStr) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
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
            logger.debug("Failed to parse cash back amount: {}", amountStr);
            return null;
        }
    }
    
    /**
     * Extract cash back rewards balance from text
     * @param text Text to search (can contain newlines)
     * @return Extracted cash back amount or null if not found
     */
    public BigDecimal extractCashBackBalance(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        
        // Normalize newlines to spaces for pattern matching
        String normalizedText = text.replaceAll("\\r?\\n", " ").replaceAll("\\s+", " ").trim();
        
        // Try full normalized text first (handles newlines within pattern)
        RewardResult result = extractRewardFromLine(normalizedText);
        if (result != null && result.getType() == RewardType.CASH_BACK && result.getCashBack() != null) {
            logger.debug("✓ Extracted cash back balance: {} (pattern: '{}')", result.getCashBack(), result.getLabel());
            return result.getCashBack();
        }
        
        // Fallback: try line by line (in case pattern spans multiple lines differently)
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] != null ? lines[i].trim() : "";
            if (line.isEmpty()) {
                continue;
            }
            
            // Try current line
            result = extractRewardFromLine(line);
            if (result != null && result.getType() == RewardType.CASH_BACK && result.getCashBack() != null) {
                logger.debug("✓ Extracted cash back balance: {} (pattern: '{}')", result.getCashBack(), result.getLabel());
                return result.getCashBack();
            }
            
            // Try combining with next line (for newline-separated amounts)
            if (i + 1 < lines.length) {
                String nextLine = lines[i + 1] != null ? lines[i + 1].trim() : "";
                String combined = line + " " + nextLine;
                result = extractRewardFromLine(combined);
                if (result != null && result.getType() == RewardType.CASH_BACK && result.getCashBack() != null) {
                    logger.debug("✓ Extracted cash back balance: {} (pattern: '{}')", result.getCashBack(), result.getLabel());
                    return result.getCashBack();
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get all reward patterns (for testing/debugging)
     */
    public List<RewardPattern> getRewardPatterns() {
        return new ArrayList<>(rewardPatterns);
    }
}

