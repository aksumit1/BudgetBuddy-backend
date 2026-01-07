package com.budgetbuddy.util;

/**
 * String utility methods for transaction processing
 */
public final class StringUtils {
    
    private StringUtils() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Normalize merchant name by removing payment processor prefixes and common suffixes
     * @param merchantName Original merchant name
     * @return Normalized merchant name (uppercase, trimmed, with prefixes/suffixes removed)
     */
    public static String normalizeMerchantName(String merchantName) {
        if (merchantName == null || merchantName.trim().isEmpty()) {
            return merchantName;
        }
        
        String normalized = merchantName.trim().toUpperCase();
        
        // Remove payment processor prefixes (lenient - allow variations)
        normalized = normalized.replaceAll("^ACH\\s+", "");
        normalized = normalized.replaceAll("^PPD\\s+", "");
        normalized = normalized.replaceAll("^WEB\\s+ID:\\s*", "");
        normalized = normalized.replaceAll("^ID:\\s*", "");
        normalized = normalized.replaceAll("^POS\\s+", "");
        normalized = normalized.replaceAll("^DEBIT\\s+CARD\\s+", "");
        normalized = normalized.replaceAll("^CREDIT\\s+CARD\\s+", "");
        
        // Remove common suffixes (lenient - allow with/without periods)
        normalized = normalized.replaceAll("\\.COM$", "");
        normalized = normalized.replaceAll("\\.NET$", "");
        normalized = normalized.replaceAll("\\.ORG$", "");
        normalized = normalized.replaceAll("\\.CO\\.UK$", "");
        normalized = normalized.replaceAll("\\.CO$", "");
        normalized = normalized.replaceAll("\\s+INC\\.?$", "");
        normalized = normalized.replaceAll("\\s+LLC\\.?$", "");
        normalized = normalized.replaceAll("\\s+CORP\\.?$", "");
        normalized = normalized.replaceAll("\\s+CORPORATION$", "");
        normalized = normalized.replaceAll("\\s+COMPANY$", "");
        normalized = normalized.replaceAll("\\s+LTD\\.?$", "");
        normalized = normalized.replaceAll("\\s+LIMITED$", "");
        
        // Remove store numbers and IDs (lenient - allow various formats)
        // CRITICAL FIX: Handle # with or without space before it (e.g., "SAFEWAY#1444" or "SAFEWAY #1444")
        normalized = normalized.replaceAll("#\\s*\\d+", ""); // Remove #123, # 123, etc. (with or without space)
        normalized = normalized.replaceAll("\\s+STORE\\s+#\\s*\\d+", "");
        normalized = normalized.replaceAll("\\s+STORE\\s*\\d+", "");
        normalized = normalized.replaceAll("\\s+ST\\s+#\\s*\\d+", "");
        normalized = normalized.replaceAll("\\s+LOC\\s+#\\s*\\d+", "");
        // CRITICAL: Only remove standalone 4+ digit numbers that are NOT part of a word
        // This prevents removing years or legitimate numbers, but removes store IDs
        normalized = normalized.replaceAll("\\s+\\d{4,}(?=\\s|$)", ""); // Remove long numeric IDs (4+ digits) at end or followed by space
        
        // Normalize whitespace (multiple spaces to single space)
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replaceAll("\\s+-\\s+", " ");
        normalized = normalized.replaceAll("\\s+\\|\\s+", " ");
        normalized = normalized.replaceAll("\\s+/\\s+", " ");
        normalized = normalized.replaceAll("\\s+_\\s+", " ");
        
        // Trim and return
        normalized = normalized.trim();
        
        // If normalization resulted in empty string, return original
        if (normalized.isEmpty()) {
            return merchantName.trim();
        }
        
        return normalized;
    }
}
