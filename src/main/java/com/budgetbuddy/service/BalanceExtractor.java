package com.budgetbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprehensive balance extraction service for global bank statements
 * 
 * Features:
 * - Multi-language balance label support
 * - Multiple number format support (US, European, Indian, etc.)
 * - Multiple currency symbol handling
 * - Edge case handling (multiple balances, CR/DR indicators, negative formats)
 * - Validation and error handling
 * 
 * Supports formats from:
 * - North America (US, Canada)
 * - Europe (UK, Germany, France, Spain, Italy, etc.)
 * - Asia (India, Japan, China, Singapore, etc.)
 * - Australia, New Zealand
 * - Latin America
 */
@Component
public class BalanceExtractor {
    
    private static final Logger logger = LoggerFactory.getLogger(BalanceExtractor.class);
    
    // Maximum reasonable balance amount (prevents parsing errors from dates or other numbers)
    private static final BigDecimal MAX_REASONABLE_BALANCE = new BigDecimal("999999999999.99");
    private static final BigDecimal MIN_REASONABLE_BALANCE = new BigDecimal("-999999999999.99");
    
    // Credit card balance labels (English and international)
    private static final List<String> CREDIT_CARD_BALANCE_LABELS = Arrays.asList(
        // English
        "new balance", "newbalance", "newbal", "current balance", "balance due",
        "total balance", "outstanding balance", "unpaid balance", "amount due",
        "statement balance", "closing balance", "ending balance", "final balance",
        // Rewards balance labels
        "cash back rewards balance", "cash back balance", "rewards balance",
        "cashback rewards balance", "cashback balance",
        // Spanish
        "nuevo saldo", "saldo nuevo", "saldo actual", "saldo pendiente", "saldo total",
        "saldo vencido", "saldo al cierre", "saldo final",
        // French
        "nouveau solde", "solde nouveau", "solde actuel", "solde dû", "solde total",
        "solde impayé", "solde de clôture", "solde final",
        // German
        "neuer saldo", "saldo neu", "aktueller saldo", "ausstehender saldo", "gesamtsaldo",
        "abschluss saldo", "endsaldo", "schluss saldo",
        // Italian
        "nuovo saldo", "saldo nuovo", "saldo corrente", "saldo dovuto", "saldo totale",
        "saldo finale", "saldo di chiusura",
        // Portuguese
        "novo saldo", "saldo novo", "saldo atual", "saldo devido", "saldo total",
        "saldo final", "saldo de fechamento",
        // Japanese (Romaji)
        "atarashii zandaka", "zandaka", "tsuki zandaka", "shūryō zandaka",
        // Chinese (Pinyin)
        "xin yue", "yue", "dangqian yue", "yingfu yue",
        // Hindi (Transliteration)
        "naya shesh", "shesh", "vartamaan shesh", "baki raashi"
    );
    
    // Depository account balance labels (English and international)
    private static final List<String> DEPOSITORY_BALANCE_LABELS = Arrays.asList(
        // English
        "balance", "current balance", "available balance", "ledger balance", "account balance",
        "closing balance", "ending balance", "final balance", "statement balance", "balance forward",
        // Spanish
        "saldo", "saldo actual", "saldo disponible", "saldo contable", "saldo de cuenta",
        "saldo al cierre", "saldo final",
        // French
        "solde", "solde actuel", "solde disponible", "solde comptable", "solde de compte",
        "solde de clôture", "solde final",
        // German
        "saldo", "aktueller saldo", "verfügbarer saldo", "buchsaldo", "kontostand",
        "abschluss saldo", "endsaldo",
        // Italian
        "saldo", "saldo corrente", "saldo disponibile", "saldo contabile", "saldo conto",
        "saldo finale",
        // Portuguese
        "saldo", "saldo atual", "saldo disponível", "saldo contábil", "saldo da conta",
        "saldo final",
        // Japanese (Romaji)
        "zandaka", "genzai zandaka", "riyō kanō zandaka", "zandaka meisai",
        // Chinese (Pinyin)
        "yue", "dangqian yue", "keyong yue", "zhanghu yue",
        // Hindi (Transliteration)
        "shesh", "vartamaan shesh", "upalabdh shesh", "khata shesh"
    );
    
    // Currency symbols (comprehensive list)
    private static final List<String> CURRENCY_SYMBOLS = Arrays.asList(
        "$", "€", "£", "¥", "₹", "¥", "₽", "₩", "R$", "A$", "NZ$", "C$", "CHF",
        "kr", "Kč", "zł", "Ft", "lei", "лв", "ден", "kn", "din", "KM"
    );
    
    // Credit/debit indicators
    private static final List<String> CREDIT_INDICATORS = Arrays.asList(
        "cr", "credit", "crédit", "crédito", "guthaben", "credito", "krediet",
        "kredit", "lån", "負債", "债务"
    );
    
    private static final List<String> DEBIT_INDICATORS = Arrays.asList(
        "dr", "debit", "débit", "débito", "schuld", "debito", "debito", "debet",
        "debet", "lån", "資産", "资产", "debet"
    );
    
    /**
     * Extract balance from headers/text based on account type
     * 
     * @param headers List of header strings
     * @param accountType Account type (creditCard, checking, savings, etc.)
     * @return Extracted balance or null if not found
     */
    public BigDecimal extractBalanceFromHeaders(List<String> headers, String accountType) {
        if (headers == null || headers.isEmpty()) {
            logger.debug("extractBalanceFromHeaders: Headers are null or empty");
            return null;
        }
        
        String headersText = String.join(" ", headers);
        logger.debug("extractBalanceFromHeaders: Searching for balance in headers (accountType: {}, text length: {})", 
                accountType, headersText.length());
        logger.debug("extractBalanceFromHeaders: First 500 chars of headers: {}", 
                headersText.length() > 500 ? headersText.substring(0, 500) : headersText);
        
        // For credit cards: Look for credit card balance patterns
        if (accountType != null && isCreditCardType(accountType)) {
            BigDecimal result = extractCreditCardBalance(headersText);
            if (result != null) {
                logger.info("✓ Extracted credit card balance from headers: {}", result);
            } else {
                logger.debug("⚠️ No credit card balance found in headers (accountType: {})", accountType);
            }
            return result;
        }
        
        // For depository accounts: Look for depository balance patterns
        if (accountType != null && isDepositoryType(accountType)) {
            BigDecimal result = extractDepositoryBalance(headersText);
            if (result != null) {
                logger.info("✓ Extracted depository balance from headers: {}", result);
            } else {
                logger.debug("⚠️ No depository balance found in headers (accountType: {})", accountType);
            }
            return result;
        }
        
        // Generic balance detection if account type not specified
        logger.debug("extractBalanceFromHeaders: Account type not specified, trying generic detection");
        BigDecimal creditCardBalance = extractCreditCardBalance(headersText);
        if (creditCardBalance != null) {
            logger.info("✓ Extracted generic credit card balance from headers: {}", creditCardBalance);
            return creditCardBalance;
        }
        
        BigDecimal depositoryBalance = extractDepositoryBalance(headersText);
        if (depositoryBalance != null) {
            logger.info("✓ Extracted generic depository balance from headers: {}", depositoryBalance);
        } else {
            logger.debug("⚠️ No balance found in headers with generic detection");
        }
        return depositoryBalance;
    }
    
    /**
     * Extract credit card balance from text
     * Handles multiple languages, formats, and edge cases
     * 
     * Special handling for Discover cards: "New Balance:" followed by amount,
     * then repeated keywords (New Balance, Minimum Payment Due, Payment Due Date)
     * that should be ignored.
     */
    public BigDecimal extractCreditCardBalance(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // CRITICAL: Special handling for Discover card format
        // Discover has: "New Balance: $5.66" followed by repeated keywords and values
        // We need to extract ONLY the first "New Balance:" value and ignore subsequent patterns
        BigDecimal discoverBalance = extractDiscoverBalance(text);
        if (discoverBalance != null) {
            logger.info("✓ Extracted Discover credit card balance: {}", discoverBalance);
            return discoverBalance;
        }
        
        List<BalanceMatch> matches = new ArrayList<>();
        
        // Try each balance label pattern
        for (String label : CREDIT_CARD_BALANCE_LABELS) {
            List<BalanceMatch> labelMatches = findBalanceMatches(text, label, true);
            matches.addAll(labelMatches);
        }
        
        // Select the best match
        BalanceMatch bestMatch = selectBestMatch(matches);
        if (bestMatch != null) {
            logger.info("✓ Extracted credit card balance: {} (label: '{}', position: {})", 
                bestMatch.balance, bestMatch.label, bestMatch.position);
            return bestMatch.balance;
        }
        
        return null;
    }
    
    /**
     * Extract balance specifically for Discover card format
     * Format: "New Balance: $5.66" followed by repeated keywords and values
     * We extract ONLY the first "New Balance:" value and stop there
     * 
     * Example: "New Balance: $5.66, 5.66 5.66 11/13/2025, 5.66, 5.66, 11/13/2025"
     * Should extract: $5.66 (only the first one after "New Balance:")
     * 
     * @param text Text to search
     * @return Balance if found, null otherwise
     */
    private BigDecimal extractDiscoverBalance(String text) {
        // Pattern: "New Balance:" (case-insensitive) followed by optional whitespace,
        // optional currency symbol, and then a single monetary amount
        // Use a simpler, more reliable pattern that stops at the first comma or whitespace boundary
        // 
        // Pattern breakdown:
        // - (?i)new\s+balance\s*[:：] - "New Balance:" with optional whitespace
        // - \s* - optional whitespace after colon
        // - ([$€£¥₹]?\s* - optional currency symbol and whitespace (captured)
        // - \(? - optional opening parenthesis for negative
        // - [\d,]+ - digits and commas (for thousands separators)
        // - (?:\.\d{1,2})? - optional decimal point with 1-2 digits
        // - \)? - optional closing parenthesis
        // - ) - end of capture group
        // - (?:[,;]|\s|$) - stop at comma, semicolon, whitespace, or end of string
        Pattern discoverPattern = Pattern.compile(
            "(?i)new\\s+balance\\s*[:：]\\s*((?:\\([\\$€£¥₹]?\\s*)?[\\d,]+(?:\\.\\d{1,2})?(?:\\s*[\\$€£¥₹]?\\))?)(?:[,;]|\\s|$)",
            Pattern.CASE_INSENSITIVE
        );
        
        Matcher matcher = discoverPattern.matcher(text);
        if (matcher.find()) {
            // Found "New Balance:" - extract the amount immediately after it
            String amountStr = matcher.group(1);
            if (amountStr != null && amountStr.trim().length() > 0) {
                // Clean up the amount string - remove trailing whitespace
                amountStr = amountStr.trim();
                
                // Check if amount is in parentheses (negative) - handle both ($1,234.56) and (1,234.56) formats
                boolean isNegativeFromParentheses = amountStr.startsWith("(") && amountStr.endsWith(")");
                // If parentheses present, strip them before parsing (parseAmount expects parentheses to be stripped or handled separately)
                if (isNegativeFromParentheses) {
                    amountStr = amountStr.substring(1, amountStr.length() - 1).trim();
                }
                BigDecimal balance = parseAmount(amountStr, isNegativeFromParentheses);
                
                if (balance != null && isValidBalance(balance)) {
                    logger.debug("✓ Discover balance extracted: {} from '{}'", balance, amountStr);
                    return balance;
                } else {
                    logger.debug("⚠️ Discover balance found but invalid: '{}' (parsed from '{}')", balance, amountStr);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Extract depository account balance from text
     * Looks for balance in first portion of text (top of statement)
     */
    public BigDecimal extractDepositoryBalance(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        
        // Search in first 2000 characters (top of statement)
        // Increased from 1000 to handle multi-line headers
        String searchText = text.length() > 2000 ? text.substring(0, 2000) : text;
        
        List<BalanceMatch> matches = new ArrayList<>();
        
        // Try each balance label pattern
        for (String label : DEPOSITORY_BALANCE_LABELS) {
            List<BalanceMatch> labelMatches = findBalanceMatches(searchText, label, false);
            matches.addAll(labelMatches);
        }
        
        // Prefer matches that appear earlier in the text (top of statement)
        BalanceMatch bestMatch = selectBestMatch(matches);
        if (bestMatch != null) {
            logger.info("✓ Extracted depository balance: {} (label: '{}', position: {})", 
                bestMatch.balance, bestMatch.label, bestMatch.position);
            return bestMatch.balance;
        }
        
        return null;
    }
    
    /**
     * Extract balance from transaction value (from balance column)
     * Handles various formats including parentheses, CR/DR indicators
     */
    public BigDecimal extractBalanceFromTransactionValue(String balanceValue) {
        if (balanceValue == null || balanceValue.trim().isEmpty()) {
            return null;
        }
        
        String cleaned = balanceValue.trim();
        
        // Check for CR/DR indicators
        boolean isCredit = false;
        boolean isDebit = false;
        String upperCleaned = cleaned.toUpperCase();
        
        for (String creditIndicator : CREDIT_INDICATORS) {
            if (upperCleaned.contains(creditIndicator.toUpperCase())) {
                isCredit = true;
                cleaned = cleaned.replaceAll("(?i)" + Pattern.quote(creditIndicator), "").trim();
                break;
            }
        }
        
        for (String debitIndicator : DEBIT_INDICATORS) {
            if (upperCleaned.contains(debitIndicator.toUpperCase())) {
                isDebit = true;
                cleaned = cleaned.replaceAll("(?i)" + Pattern.quote(debitIndicator), "").trim();
                break;
            }
        }
        
        // Parse the amount (handle parentheses in cleaned string first)
        boolean isNegativeFromParentheses = cleaned.trim().startsWith("(") && cleaned.trim().endsWith(")");
        if (isNegativeFromParentheses) {
            cleaned = cleaned.trim().substring(1, cleaned.trim().length() - 1).trim();
        }
        BigDecimal amount = parseAmount(cleaned, isNegativeFromParentheses);
        if (amount == null) {
            return null;
        }
        
        // Apply CR/DR logic: CR typically means positive (credit balance), DR means negative (debit balance)
        // However, for accounts, CR might indicate a credit to the account (positive), 
        // while DR indicates a debit (negative). This depends on account type.
        // For now, we'll treat CR as positive and DR as negative if the amount itself isn't already negative.
        if (isCredit && amount.compareTo(BigDecimal.ZERO) >= 0) {
            // CR with positive amount = credit balance (positive)
            return amount;
        } else if (isDebit && amount.compareTo(BigDecimal.ZERO) >= 0) {
            // DR with positive amount = debit balance (negative for liability accounts, but we'll keep as positive for now)
            // Actually, for balance columns, DR typically means the balance is negative
            return amount.negate();
        }
        
        return amount;
    }
    
    /**
     * Find balance matches for a given label
     */
    private List<BalanceMatch> findBalanceMatches(String text, String label, boolean allowAnywhere) {
        List<BalanceMatch> matches = new ArrayList<>();
        
        // Build pattern: label followed by optional separator and amount
        // Use simpler pattern first - match label, then look for amount pattern after it
        String labelPattern = Pattern.quote(label);
        // Simple pattern: label : $amount or label amount (amount can have various formats)
        // Match amount that may be in parentheses (for negatives) or have currency symbols
        // Pattern: Handle parentheses with currency symbols - currency can be inside or outside parentheses
        // Examples: ($1,234.56), $1,234.56, (1,234.56), -$1,234.56
        String simpleAmountPattern = "((?:\\([\\$€£¥₹]?\\s*)?[\\d\\s,.$€£¥₹+-]+(?:\\s*[\\$€£¥₹]?\\))?)"; // Match amount-like strings including parentheses with currency symbols
        
        // Try multiple patterns with different currency handling
        List<String> patternsToTry = Arrays.asList(
            // Pattern 1: With common currency symbols before amount (optional)
            "(?i)" + labelPattern + "[:：\\s]+[\\$€£¥₹]?\\s*" + simpleAmountPattern,
            // Pattern 2: Amount may contain currency symbols anywhere (removed during parsing)
            "(?i)" + labelPattern + "[:：\\s]+" + simpleAmountPattern
        );
        
        for (String patternStr : patternsToTry) {
            try {
                Pattern pattern = Pattern.compile(patternStr);
                Matcher matcher = pattern.matcher(text);
                
                while (matcher.find()) {
                    String amountStr = matcher.group(1);
                    
                    if (amountStr != null && amountStr.trim().length() > 0) {
                        String fullMatch = matcher.group(0);
                        // CRITICAL: Check for parentheses - handle both ($1,234.56) and (1,234.56) formats
                        String trimmedAmount = amountStr.trim();
                        boolean isNegativeFromParentheses = trimmedAmount.startsWith("(") && trimmedAmount.endsWith(")");
                        // If parentheses present, strip them before parsing (parseAmount expects parentheses to be stripped or handled separately)
                        if (isNegativeFromParentheses) {
                            trimmedAmount = trimmedAmount.substring(1, trimmedAmount.length() - 1).trim();
                        }
                        BigDecimal balance = parseAmount(trimmedAmount, isNegativeFromParentheses);
                        
                        if (balance != null && isValidBalance(balance)) {
                            matches.add(new BalanceMatch(balance, label, matcher.start()));
                            break; // Found a match with this pattern, don't try others
                        }
                    }
                }
                if (!matches.isEmpty()) break; // Found matches, stop trying patterns
            } catch (Exception e) {
                logger.debug("Pattern compilation failed: {}", patternStr, e);
            }
        }
        
        return matches;
    }
    
    /**
     * Build regex pattern for amount matching
     * Handles multiple formats: US (1,234.56), European (1.234,56), Indian (12,34,567.89), etc.
     */
    private String buildAmountPattern() {
        // Pattern components:
        // 1. Optional currency symbol
        // 2. Optional sign (+/-)
        // 3. Optional whitespace
        // 4. Amount with various separators
        
        // Build currency symbol pattern using alternation (not character class, to handle multi-char symbols)
        // Single-char symbols: $, €, £, ¥, ₹, etc.
        // Multi-char symbols: R$, A$, NZ$, C$, CHF, kr, etc.
        List<String> allSymbols = new ArrayList<>();
        
        for (String symbol : CURRENCY_SYMBOLS) {
            // Escape special regex characters for each symbol
            allSymbols.add(Pattern.quote(symbol));
        }
        
        // Use alternation for all symbols (simpler and handles both single and multi-char)
        String currencyPattern = "(?:(?:" + String.join("|", allSymbols) + ")\\s*)?";
        
        String signPart = "[+-]?";
        
        // Amount formats:
        // - US: 1,234.56 or 1234.56
        // - European: 1.234,56 or 1234,56
        // - Indian: 12,34,567.89
        // - Japanese/Chinese: 1234.56 or 1234,56 (no thousands separator sometimes)
        // - Scientific notation (rare but possible)
        
        // Build amount pattern - match complete monetary amounts
        // Order matters: more specific patterns first (they're tried in order)
        String amountPart = "(" +
            // Pattern 1: US format with comma thousands and period decimal (e.g., 1,234.56)
            "\\d{1,3}(?:[,\\s]\\d{3})+(?:\\.\\d{1,2})?" +
            "|" +
            // Pattern 2: European format with period thousands and comma decimal (e.g., 1.234,56)
            "\\d{1,3}(?:\\.\\d{3})+(?:,\\d{1,2})?" +
            "|" +
            // Pattern 3: Indian format (e.g., 12,34,567.89)
            "\\d{1,2}(?:,\\d{2}){2,}(?:\\.\\d{1,2})?" +
            "|" +
            // Pattern 4: Number with decimal but no thousands (e.g., 1234.56 or 1234,56)
            "\\d{4,}(?:[.,]\\d{1,2})" +
            "|" +
            // Pattern 5: Integer with thousands separators (e.g., 1,234 or 1.234)
            "\\d{1,3}(?:[,\\s]\\d{3})+" +
            "|" +
            // Pattern 6: Integer with period thousands (e.g., 1.234)
            "\\d{1,3}(?:\\.\\d{3})+" +
            "|" +
            // Pattern 7: Simple integer (at least 3 digits to avoid matching dates/account numbers in headers)
            "\\d{3,}" +
            ")";
        
        // Handle parentheses for negative (US accounting format)
        String parenthesesPattern = "\\(" + amountPart + "\\)";
        
        // Return pattern: parentheses match or regular match
        return currencyPattern + signPart + "(?:" + parenthesesPattern + "|" + amountPart + ")";
    }
    
    /**
     * Parse amount string to BigDecimal
     * Handles multiple number formats
     * 
     * @param amountStr Amount string to parse
     * @param isNegativeFromParentheses True if amount was in parentheses (negative)
     */
    private BigDecimal parseAmount(String amountStr, boolean isNegativeFromParentheses) {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            return null;
        }
        
        String cleaned = amountStr.trim();
        boolean isNegative = isNegativeFromParentheses;
        
        // Remove currency symbols
        for (String symbol : CURRENCY_SYMBOLS) {
            cleaned = cleaned.replace(symbol, "").trim();
        }
        
        // Handle explicit sign
        if (cleaned.startsWith("-")) {
            isNegative = true;
            cleaned = cleaned.substring(1).trim();
        } else if (cleaned.startsWith("+")) {
            cleaned = cleaned.substring(1).trim();
        }
        
        // Detect format and normalize
        cleaned = normalizeNumberFormat(cleaned);
        
        if (cleaned.isEmpty()) {
            return null;
        }
        
        try {
            BigDecimal amount = new BigDecimal(cleaned);
            if (isNegative) {
                amount = amount.negate();
            }
            return amount;
        } catch (NumberFormatException e) {
            logger.debug("Failed to parse amount: {}", amountStr);
            return null;
        }
    }
    
    /**
     * Parse amount string to BigDecimal (overload without parentheses flag)
     */
    private BigDecimal parseAmount(String amountStr) {
        return parseAmount(amountStr, false);
    }
    
    /**
     * Normalize number format to standard (US format: period decimal, no thousands separator)
     * Handles European (1.234,56), Indian (12,34,567.89), and other formats
     */
    private String normalizeNumberFormat(String numberStr) {
        if (numberStr == null || numberStr.trim().isEmpty()) {
            return "";
        }
        
        String cleaned = numberStr.trim();
        
        // Remove all whitespace
        cleaned = cleaned.replaceAll("\\s+", "");
        
        // Determine format based on separators
        boolean hasComma = cleaned.contains(",");
        boolean hasPeriod = cleaned.contains(".");
        
        if (!hasComma && !hasPeriod) {
            // No separators - simple integer or decimal without separators
            return cleaned;
        }
        
        if (hasComma && !hasPeriod) {
            // European format: 1234,56 (comma as decimal separator)
            // But could also be Indian: 12,34,567 (commas as thousands)
            // Heuristic: If there's only one comma near the end (last 3 chars), it's likely decimal
            int lastCommaIndex = cleaned.lastIndexOf(",");
            if (lastCommaIndex >= cleaned.length() - 3 && cleaned.length() - lastCommaIndex <= 3) {
                // Likely European decimal format
                return cleaned.replace(",", ".");
            } else {
                // Likely thousands separators (European with thousands but no decimal, or Indian)
                // Remove commas and treat as integer
                return cleaned.replace(",", "");
            }
        }
        
        if (!hasComma && hasPeriod) {
            // US format or European thousands with period decimal (unlikely but possible)
            // If period is near the end (last 3 chars), treat as decimal
            int lastPeriodIndex = cleaned.lastIndexOf(".");
            if (lastPeriodIndex >= cleaned.length() - 3) {
                // Decimal separator
                return cleaned; // Already in correct format
            } else {
                // Thousands separator (European style with period thousands)
                return cleaned.replace(".", "");
            }
        }
        
        // Both comma and period present
        int lastCommaIndex = cleaned.lastIndexOf(",");
        int lastPeriodIndex = cleaned.lastIndexOf(".");
        
        if (lastCommaIndex > lastPeriodIndex) {
            // European format: 1.234,56 (period thousands, comma decimal)
            cleaned = cleaned.replace(".", ""); // Remove thousands separator
            cleaned = cleaned.replace(",", "."); // Convert decimal separator
            return cleaned;
        } else {
            // US or Indian format: 1,234.56 or 12,34,567.89
            // Remove all commas (thousands separators)
            return cleaned.replace(",", "");
        }
    }
    
    /**
     * Select the best balance match from multiple candidates
     * Prefers matches that:
     * 1. Appear earlier in the text (for depository accounts)
     * 2. Have more common/standard labels
     * 3. Have reasonable values
     */
    private BalanceMatch selectBestMatch(List<BalanceMatch> matches) {
        if (matches.isEmpty()) {
            return null;
        }
        
        if (matches.size() == 1) {
            return matches.get(0);
        }
        
        // Sort by position (earlier is better), then by label preference
        matches.sort((a, b) -> {
            // First, prefer earlier positions
            int positionCompare = Integer.compare(a.position, b.position);
            if (positionCompare != 0) {
                return positionCompare;
            }
            
            // Then prefer standard English labels
            boolean aIsEnglish = a.label.matches("(?i)(new )?balance");
            boolean bIsEnglish = b.label.matches("(?i)(new )?balance");
            if (aIsEnglish && !bIsEnglish) return -1;
            if (!aIsEnglish && bIsEnglish) return 1;
            
            return 0;
        });
        
        // Return the first (best) match
        return matches.get(0);
    }
    
    /**
     * Validate that balance is within reasonable bounds
     */
    private boolean isValidBalance(BigDecimal balance) {
        if (balance == null) {
            return false;
        }
        
        return balance.compareTo(MIN_REASONABLE_BALANCE) >= 0 && 
               balance.compareTo(MAX_REASONABLE_BALANCE) <= 0;
    }
    
    /**
     * Check if account type is credit card
     */
    private boolean isCreditCardType(String accountType) {
        if (accountType == null) {
            return false;
        }
        String lower = accountType.toLowerCase();
        return lower.equals("creditcard") || lower.equals("credit_card") || 
               lower.equals("credit") || lower.contains("card");
    }
    
    /**
     * Check if account type is depository
     */
    private boolean isDepositoryType(String accountType) {
        if (accountType == null) {
            return false;
        }
        String lower = accountType.toLowerCase();
        return lower.equals("checking") || lower.equals("savings") || 
               lower.equals("moneymarket") || lower.equals("money_market") ||
               lower.equals("depository") || lower.equals("bank");
    }
    
    /**
     * Internal class to represent a balance match
     */
    private static class BalanceMatch {
        final BigDecimal balance;
        final String label;
        final int position;
        
        BalanceMatch(BigDecimal balance, String label, int position) {
            this.balance = balance;
            this.label = label;
            this.position = position;
        }
    }
}

