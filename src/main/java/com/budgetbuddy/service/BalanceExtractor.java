package com.budgetbuddy.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Comprehensive balance extraction service for global bank statements
 *
 * <p>Features: - Multi-language balance label support - Multiple number format support (US,
 * European, Indian, etc.) - Multiple currency symbol handling - Edge case handling (multiple
 * balances, CR/DR indicators, negative formats) - Validation and error handling
 *
 * <p>Supports formats from: - North America (US, Canada) - Europe (UK, Germany, France, Spain,
 * Italy, etc.) - Asia (India, Japan, China, Singapore, etc.) - Australia, New Zealand - Latin
 * America
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class BalanceExtractor {

    private static final String DEBET = "debet";

    private static final String SALDO = "saldo";

    private static final String SALDO_FINAL = "saldo final";
    private static final String I = "(?i)";

    private static final Logger LOGGER = LoggerFactory.getLogger(BalanceExtractor.class);

    // Maximum reasonable balance amount (prevents parsing errors from dates or other numbers)
    private static final BigDecimal MAX_REASONABLE_BALANCE = new BigDecimal("999999999999.99");
    private static final BigDecimal MIN_REASONABLE_BALANCE = new BigDecimal("-999999999999.99");

    // Credit card balance labels (English and international)
    private static final List<String> CREDIT_CARD_BALANCE_LABELS =
            Arrays.asList(
                    // English
                    "new balance",
                    "newbalance",
                    "newbal",
                    "current balance",
                    "balance due",
                    "total balance",
                    "outstanding balance",
                    "unpaid balance",
                    "amount due",
                    "statement balance",
                    "closing balance",
                    "ending balance",
                    "final balance",
                    // Rewards balance labels
                    "cash back rewards balance",
                    "cash back balance",
                    "rewards balance",
                    "cashback rewards balance",
                    "cashback balance",
                    // Spanish
                    "nuevo saldo",
                    "saldo nuevo",
                    "saldo actual",
                    "saldo pendiente",
                    "saldo total",
                    "saldo vencido",
                    "saldo al cierre",
                    SALDO_FINAL,
                    // French
                    "nouveau solde",
                    "solde nouveau",
                    "solde actuel",
                    "solde dû",
                    "solde total",
                    "solde impayé",
                    "solde de clôture",
                    "solde final",
                    // German
                    "neuer saldo",
                    "saldo neu",
                    "aktueller saldo",
                    "ausstehender saldo",
                    "gesamtsaldo",
                    "abschluss saldo",
                    "endsaldo",
                    "schluss saldo",
                    // Italian
                    "nuovo saldo",
                    "saldo nuovo",
                    "saldo corrente",
                    "saldo dovuto",
                    "saldo totale",
                    "saldo finale",
                    "saldo di chiusura",
                    // Portuguese
                    "novo saldo",
                    "saldo novo",
                    "saldo atual",
                    "saldo devido",
                    "saldo total",
                    SALDO_FINAL,
                    "saldo de fechamento",
                    // Japanese (Romaji)
                    "atarashii zandaka",
                    "zandaka",
                    "tsuki zandaka",
                    "shūryō zandaka",
                    // Chinese (Pinyin)
                    "xin yue",
                    "yue",
                    "dangqian yue",
                    "yingfu yue",
                    // Hindi (Transliteration)
                    "naya shesh",
                    "shesh",
                    "vartamaan shesh",
                    "baki raashi");

    // Depository account balance labels (English and international)
    private static final List<String> DEPOSITORY_BALANCE_LABELS =
            Arrays.asList(
                    // English
                    "balance",
                    "current balance",
                    "available balance",
                    "ledger balance",
                    "account balance",
                    "closing balance",
                    "ending balance",
                    "final balance",
                    "statement balance",
                    "balance forward",
                    // Spanish
                    SALDO,
                    "saldo actual",
                    "saldo disponible",
                    "saldo contable",
                    "saldo de cuenta",
                    "saldo al cierre",
                    SALDO_FINAL,
                    // French
                    "solde",
                    "solde actuel",
                    "solde disponible",
                    "solde comptable",
                    "solde de compte",
                    "solde de clôture",
                    "solde final",
                    // German
                    SALDO,
                    "aktueller saldo",
                    "verfügbarer saldo",
                    "buchsaldo",
                    "kontostand",
                    "abschluss saldo",
                    "endsaldo",
                    // Italian
                    SALDO,
                    "saldo corrente",
                    "saldo disponibile",
                    "saldo contabile",
                    "saldo conto",
                    "saldo finale",
                    // Portuguese
                    SALDO,
                    "saldo atual",
                    "saldo disponível",
                    "saldo contábil",
                    "saldo da conta",
                    SALDO_FINAL,
                    // Japanese (Romaji)
                    "zandaka",
                    "genzai zandaka",
                    "riyō kanō zandaka",
                    "zandaka meisai",
                    // Chinese (Pinyin)
                    "yue",
                    "dangqian yue",
                    "keyong yue",
                    "zhanghu yue",
                    // Hindi (Transliteration)
                    "shesh",
                    "vartamaan shesh",
                    "upalabdh shesh",
                    "khata shesh");

    // Currency symbols (comprehensive list)
    private static final List<String> CURRENCY_SYMBOLS =
            Arrays.asList(
                    "$", "€", "£", "¥", "₹", "¥", "₽", "₩", "R$", "A$", "NZ$", "C$", "CHF", "kr",
                    "Kč", "zł", "Ft", "lei", "лв", "ден", "kn", "din", "KM");

    // Credit/debit indicators
    private static final List<String> CREDIT_INDICATORS =
            Arrays.asList(
                    "cr",
                    "credit",
                    "crédit",
                    "crédito",
                    "guthaben",
                    "credito",
                    "krediet",
                    "kredit",
                    "lån",
                    "負債",
                    "债务");

    private static final List<String> DEBIT_INDICATORS =
            Arrays.asList(
                    "dr", "debit", "débit", "débito", "schuld", "debito", "debito", DEBET, DEBET,
                    "lån", "資産", "资产", DEBET);

    /**
     * Extract balance from headers/text based on account type
     *
     * @param headers List of header strings
     * @param accountType Account type (creditCard, checking, savings, etc.)
     * @return Extracted balance or null if not found
     */
    public BigDecimal extractBalanceFromHeaders(
            final List<String> headers, final String accountType) {
        if (headers == null || headers.isEmpty()) {
            LOGGER.debug("extractBalanceFromHeaders: Headers are null or empty");
            return null;
        }

        final String headersText = String.join(" ", headers);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "extractBalanceFromHeaders: Searching for balance in headers (accountType: {}, text length: {})",
                    accountType,
                    headersText.length());
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "extractBalanceFromHeaders: First 500 chars of headers: {}",
                    headersText.length() > 500 ? headersText.substring(0, 500) : headersText);
        }

        // For credit cards: Look for credit card balance patterns
        if (accountType != null && isCreditCardType(accountType)) {
            final BigDecimal result = extractCreditCardBalance(headersText);
            if (result != null) {
                LOGGER.info("✓ Extracted credit card balance from headers: {}", result);
            } else {
                LOGGER.debug(
                        "⚠️ No credit card balance found in headers (accountType: {})",
                        accountType);
            }
            return result;
        }

        // For depository accounts: Look for depository balance patterns
        if (accountType != null && isDepositoryType(accountType)) {
            final BigDecimal result = extractDepositoryBalance(headersText);
            if (result != null) {
                LOGGER.info("✓ Extracted depository balance from headers: {}", result);
            } else {
                LOGGER.debug(
                        "⚠️ No depository balance found in headers (accountType: {})", accountType);
            }
            return result;
        }

        // Generic balance detection if account type not specified
        LOGGER.debug(
                "extractBalanceFromHeaders: Account type not specified, trying generic detection");
        final BigDecimal creditCardBalance = extractCreditCardBalance(headersText);
        if (creditCardBalance != null) {
            LOGGER.info(
                    "✓ Extracted generic credit card balance from headers: {}", creditCardBalance);
            return creditCardBalance;
        }

        final BigDecimal depositoryBalance = extractDepositoryBalance(headersText);
        if (depositoryBalance != null) {
            LOGGER.info(
                    "✓ Extracted generic depository balance from headers: {}", depositoryBalance);
        } else {
            LOGGER.debug("⚠️ No balance found in headers with generic detection");
        }
        return depositoryBalance;
    }

    /**
     * Extract credit card balance from text Handles multiple languages, formats, and edge cases
     *
     * <p>Special handling for Discover cards: "New Balance:" followed by amount, then repeated
     * keywords (New Balance, Minimum Payment Due, Payment Due Date) that should be ignored.
     */
    public BigDecimal extractCreditCardBalance(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // CRITICAL: Special handling for Discover card format
        // Discover has: "New Balance: $5.66" followed by repeated keywords and values
        // We need to extract ONLY the first "New Balance:" value and ignore subsequent patterns
        final BigDecimal discoverBalance = extractDiscoverBalance(text);
        if (discoverBalance != null) {
            LOGGER.info("✓ Extracted Discover credit card balance: {}", discoverBalance);
            return discoverBalance;
        }

        final List<BalanceMatch> matches = new ArrayList<>();

        // Try each balance label pattern
        for (final String label : CREDIT_CARD_BALANCE_LABELS) {
            final List<BalanceMatch> labelMatches = findBalanceMatches(text, label, true);
            matches.addAll(labelMatches);
        }

        // Select the best match
        final BalanceMatch bestMatch = selectBestMatch(matches);
        if (bestMatch != null) {
            LOGGER.info(
                    "✓ Extracted credit card balance: {} (label: '{}', position: {})",
                    bestMatch.balance,
                    bestMatch.label,
                    bestMatch.position);
            return bestMatch.balance;
        }

        return null;
    }

    /**
     * Extract balance specifically for Discover card format Format: "New Balance: $5.66" followed
     * by repeated keywords and values We extract ONLY the first "New Balance:" value and stop there
     *
     * <p>Example: "New Balance: $5.66, 5.66 5.66 11/13/2025, 5.66, 5.66, 11/13/2025" Should
     * extract: $5.66 (only the first one after "New Balance:")
     *
     * @param text Text to search
     * @return Balance if found, null otherwise
     */
    private BigDecimal extractDiscoverBalance(final String text) {
        // Pattern: "New Balance:" (case-insensitive) followed by optional whitespace,
        // optional currency symbol, and then a single monetary amount
        // CRITICAL: Must stop at the FIRST amount and not concatenate repeated values
        //
        // Pattern breakdown:
        // - (?i)new\s+balance\s*[:：] - "New Balance:" with optional whitespace
        // - \s* - optional whitespace after colon
        // - (?:[$€£¥₹]\s*)? - optional currency symbol BEFORE number (not in parentheses)
        // - (?:\( - OR optional opening parenthesis for negative
        // - (?:[$€£¥₹]\s*)? - optional currency symbol inside parentheses
        // - )? - end of optional parentheses group
        // - ([\d,]+(?:\.\d{1,2})?) - capture the amount (digits, optional comma thousands, optional
        // decimal)
        // - (?:\))? - optional closing parenthesis
        // - (?:[,;]|\s|$) - stop at comma, semicolon, whitespace, or end of string
        //
        // This pattern ensures we match ONLY the first amount after "New Balance:" and stop
        // before any subsequent numbers (like "5.66 5.66 11/13/2025")
        final Pattern discoverPattern =
                Pattern.compile(
                        "(?i)new\\s+balance\\s*[:：]\\s*(?:[\\$€£¥₹]\\s*)?(?:\\([\\$€£¥₹]?\\s*)?([\\d,]+(?:\\.[\\d]{1,2})?)(?:\\s*[\\$€£¥₹]?\\))?(?:[,;]|\\s|$)",
                        Pattern.CASE_INSENSITIVE);

        final Matcher matcher = discoverPattern.matcher(text);
        if (matcher.find()) {
            // Found "New Balance:" - extract the amount immediately after it
            final String amountStr = matcher.group(1);
            if (amountStr != null && amountStr.trim().length() > 0) {
                // Check if the text after "New Balance:" has parentheses around the amount
                // Look at the matched portion to see if it contains parentheses
                final String matchedText = text.substring(matcher.start(), matcher.end());
                // Find the position after "New Balance:" or "New Balance："
                int colonPos = matchedText.indexOf(':');
                if (colonPos == -1) {
                    colonPos = matchedText.indexOf('：'); // Unicode colon
                }
                boolean isNegativeFromParentheses = false;
                if (colonPos >= 0) {
                    final String afterColon = matchedText.substring(colonPos + 1).trim();
                    // Check if amount is wrapped in parentheses
                    isNegativeFromParentheses =
                            afterColon.startsWith("(") && afterColon.endsWith(")");
                }

                final BigDecimal balance = parseAmount(amountStr, isNegativeFromParentheses);

                if (balance != null && isValidBalance(balance)) {
                    LOGGER.debug("✓ Discover balance extracted: {} from '{}'", balance, amountStr);
                    return balance;
                } else {
                    LOGGER.debug(
                            "⚠️ Discover balance found but invalid: '{}' (parsed from '{}')",
                            balance,
                            amountStr);
                }
            }
        }

        return null;
    }

    /**
     * Extract depository account balance from text Looks for balance in first portion of text (top
     * of statement)
     */
    public BigDecimal extractDepositoryBalance(final String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Search in first 2000 characters (top of statement)
        // Increased from 1000 to handle multi-line headers
        final String searchText = text.length() > 2000 ? text.substring(0, 2000) : text;

        final List<BalanceMatch> matches = new ArrayList<>();

        // Try each balance label pattern
        for (final String label : DEPOSITORY_BALANCE_LABELS) {
            final List<BalanceMatch> labelMatches = findBalanceMatches(searchText, label, false);
            matches.addAll(labelMatches);
        }

        // Prefer matches that appear earlier in the text (top of statement)
        final BalanceMatch bestMatch = selectBestMatch(matches);
        if (bestMatch != null) {
            LOGGER.info(
                    "✓ Extracted depository balance: {} (label: '{}', position: {})",
                    bestMatch.balance,
                    bestMatch.label,
                    bestMatch.position);
            return bestMatch.balance;
        }

        return null;
    }

    /**
     * Extract balance from transaction value (from balance column) Handles various formats
     * including parentheses, CR/DR indicators
     */
    public BigDecimal extractBalanceFromTransactionValue(final String balanceValue) {
        if (balanceValue == null || balanceValue.isBlank()) {
            return null;
        }

        String cleaned = balanceValue.trim();

        // Check for CR/DR indicators
        boolean isCredit = false;
        boolean isDebit = false;
        final String upperCleaned = cleaned.toUpperCase(Locale.ROOT);

        for (final String creditIndicator : CREDIT_INDICATORS) {
            if (upperCleaned.contains(creditIndicator.toUpperCase(Locale.ROOT))) {
                isCredit = true;
                cleaned = cleaned.replaceAll(I + Pattern.quote(creditIndicator), "").trim();
                break;
            }
        }

        for (final String debitIndicator : DEBIT_INDICATORS) {
            if (upperCleaned.contains(debitIndicator.toUpperCase(Locale.ROOT))) {
                isDebit = true;
                cleaned = cleaned.replaceAll(I + Pattern.quote(debitIndicator), "").trim();
                break;
            }
        }

        // Parse the amount (handle parentheses in cleaned string first)
        final boolean isNegativeFromParentheses =
                cleaned.trim().startsWith("(") && cleaned.trim().endsWith(")");
        if (isNegativeFromParentheses) {
            cleaned = cleaned.trim().substring(1, cleaned.trim().length() - 1).trim();
        }
        final BigDecimal amount = parseAmount(cleaned, isNegativeFromParentheses);
        if (amount == null) {
            return null;
        }

        // Apply CR/DR logic: CR typically means positive (credit balance), DR means negative (debit
        // balance)
        // However, for accounts, CR might indicate a credit to the account (positive),
        // while DR indicates a debit (negative). This depends on account type.
        // For now, we'll treat CR as positive and DR as negative if the amount itself isn't already
        // negative.
        if (isCredit && amount.compareTo(BigDecimal.ZERO) >= 0) {
            // CR with positive amount = credit balance (positive)
            return amount;
        } else if (isDebit && amount.compareTo(BigDecimal.ZERO) >= 0) {
            // DR with positive amount = debit balance (negative for liability accounts, but we'll
            // keep as positive for now)
            // Actually, for balance columns, DR typically means the balance is negative
            return amount.negate();
        }

        return amount;
    }

    /** Find balance matches for a given label */
    private List<BalanceMatch> findBalanceMatches(
            final String text, final String label, final boolean allowAnywhere) {
        final List<BalanceMatch> matches = new ArrayList<>();

        // Build pattern: label followed by optional separator and amount
        // Use simpler pattern first - match label, then look for amount pattern after it
        final String labelPattern = Pattern.quote(label);
        // Simple pattern: label : $amount or label amount (amount can have various formats)
        // Match amount that may be in parentheses (for negatives) or have currency symbols
        // Pattern: Handle parentheses with currency symbols - currency can be inside or outside
        // parentheses
        // Examples: ($1,234.56), $1,234.56, (1,234.56), -$1,234.56
        final String simpleAmountPattern =
                "((?:\\([\\$€£¥₹]?\\s*)?[\\d\\s,.$€£¥₹+-]+(?:\\s*[\\$€£¥₹]?\\))?)"; // Match
        // amount-like
        // strings
        // including
        // parentheses
        // with currency
        // symbols

        // Try multiple patterns with different currency handling
        final List<String> patternsToTry =
                Arrays.asList(
                        // Pattern 1: With common currency symbols before amount (optional)
                        I + labelPattern + "[:：\\s]+[\\$€£¥₹]?\\s*" + simpleAmountPattern,
                        // Pattern 2: Amount may contain currency symbols anywhere (removed during
                        // parsing)
                        I + labelPattern + "[:：\\s]+" + simpleAmountPattern);

        for (final String patternStr : patternsToTry) {
            try {
                final Pattern pattern = Pattern.compile(patternStr);
                final Matcher matcher = pattern.matcher(text);

                while (matcher.find()) {
                    final String amountStr = matcher.group(1);

                    if (amountStr != null && amountStr.trim().length() > 0) {
                        // CRITICAL: Check for parentheses - handle both ($1,234.56) and (1,234.56)
                        // formats
                        String trimmedAmount = amountStr.trim();
                        final boolean isNegativeFromParentheses =
                                trimmedAmount.startsWith("(") && trimmedAmount.endsWith(")");
                        // If parentheses present, strip them before parsing (parseAmount expects
                        // parentheses to be stripped or handled separately)
                        if (isNegativeFromParentheses) {
                            trimmedAmount =
                                    trimmedAmount.substring(1, trimmedAmount.length() - 1).trim();
                        }
                        final BigDecimal balance =
                                parseAmount(trimmedAmount, isNegativeFromParentheses);

                        if (balance != null && isValidBalance(balance)) {
                            matches.add(new BalanceMatch(balance, label, matcher.start()));
                            break; // Found a match with this pattern, don't try others
                        }
                    }
                }
                if (!matches.isEmpty()) {
                    break; // Found matches, stop trying patterns
                }
            } catch (Exception e) {
                LOGGER.debug("Pattern compilation failed: {}", patternStr, e);
            }
        }

        return matches;
    }

    /**
     * Parse amount string to BigDecimal Handles multiple number formats
     *
     * @param amountStr Amount string to parse
     * @param isNegativeFromParentheses True if amount was in parentheses (negative)
     */
    private BigDecimal parseAmount(
            final String amountStr, final boolean isNegativeFromParentheses) {
        if (amountStr == null || amountStr.isBlank()) {
            return null;
        }

        String cleaned = amountStr.trim();
        boolean isNegative = isNegativeFromParentheses;

        // Remove currency symbols
        for (final String symbol : CURRENCY_SYMBOLS) {
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
            LOGGER.debug("Failed to parse amount: {}", amountStr);
            return null;
        }
    }

    /**
     * Normalize number format to standard (US format: period decimal, no thousands separator)
     * Handles European (1.234,56), Indian (12,34,567.89), and other formats
     */
    private String normalizeNumberFormat(final String numberStr) {
        if (numberStr == null || numberStr.isBlank()) {
            return "";
        }

        String cleaned = numberStr.trim();

        // Remove all whitespace
        cleaned = cleaned.replaceAll("\\s+", "");

        // Determine format based on separators
        final boolean hasComma = cleaned.contains(",");
        final boolean hasPeriod = cleaned.contains(".");

        if (!hasComma && !hasPeriod) {
            // No separators - simple integer or decimal without separators
            return cleaned;
        }

        if (hasComma && !hasPeriod) {
            // European format: 1234,56 (comma as decimal separator)
            // But could also be Indian: 12,34,567 (commas as thousands)
            // Heuristic: If there's only one comma near the end (last 3 chars), it's likely decimal
            final int lastCommaIndex = cleaned.lastIndexOf(",");
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
            final int lastPeriodIndex = cleaned.lastIndexOf(".");
            if (lastPeriodIndex >= cleaned.length() - 3) {
                // Decimal separator
                return cleaned; // Already in correct format
            } else {
                // Thousands separator (European style with period thousands)
                return cleaned.replace(".", "");
            }
        }

        // Both comma and period present
        final int lastCommaIndex = cleaned.lastIndexOf(",");
        final int lastPeriodIndex = cleaned.lastIndexOf(".");

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
     * Select the best balance match from multiple candidates Prefers matches that: 1. Appear
     * earlier in the text (for depository accounts) 2. Have more common/standard labels 3. Have
     * reasonable values
     */
    private BalanceMatch selectBestMatch(final List<BalanceMatch> matches) {
        if (matches.isEmpty()) {
            return null;
        }

        if (matches.size() == 1) {
            return matches.get(0);
        }

        // Sort by position (earlier is better), then by label preference
        matches.sort(
                (a, b) -> {
                    // First, prefer earlier positions
                    final int positionCompare = Integer.compare(a.position, b.position);
                    if (positionCompare != 0) {
                        return positionCompare;
                    }

                    // Then prefer standard English labels
                    final boolean aIsEnglish = a.label.matches("(?i)(new )?balance");
                    final boolean bIsEnglish = b.label.matches("(?i)(new )?balance");
                    if (aIsEnglish && !bIsEnglish) {
                        return -1;
                    }
                    if (!aIsEnglish && bIsEnglish) {
                        return 1;
                    }

                    return 0;
                });

        // Return the first (best) match
        return matches.get(0);
    }

    /** Validate that balance is within reasonable bounds */
    private boolean isValidBalance(final BigDecimal balance) {
        if (balance == null) {
            return false;
        }

        return balance.compareTo(MIN_REASONABLE_BALANCE) >= 0
                && balance.compareTo(MAX_REASONABLE_BALANCE) <= 0;
    }

    /** Check if account type is credit card */
    private boolean isCreditCardType(final String accountType) {
        if (accountType == null) {
            return false;
        }
        final String lower = accountType.toLowerCase(Locale.ROOT);
        return "creditcard".equals(lower)
                || "credit_card".equals(lower)
                || "credit".equals(lower)
                || lower.contains("card");
    }

    /** Check if account type is depository */
    private boolean isDepositoryType(final String accountType) {
        if (accountType == null) {
            return false;
        }
        final String lower = accountType.toLowerCase(Locale.ROOT);
        return "checking".equals(lower)
                || "savings".equals(lower)
                || "moneymarket".equals(lower)
                || "money_market".equals(lower)
                || "depository".equals(lower)
                || "bank".equals(lower);
    }

    /** Internal class to represent a balance match */
    private static class BalanceMatch {
        /* default */ final BigDecimal balance;
        /* default */ final String label;
        /* default */ final int position;

        /* default */ BalanceMatch(final BigDecimal balance, final String label, final int position) {
            this.balance = balance;
            this.label = label;
            this.position = position;
        }
    }
}
