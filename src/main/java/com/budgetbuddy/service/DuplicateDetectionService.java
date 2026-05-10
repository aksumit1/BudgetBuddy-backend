package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Duplicate Detection Service Detects potential duplicate transactions using fuzzy matching Mirrors
 * the iOS app's DuplicateDetectionService functionality
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.DataClass",
    "PMD.OnlyOneReturn"
})
@Service
public class DuplicateDetectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DuplicateDetectionService.class);
    private static final double SIMILARITY_THRESHOLD = 0.90;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;

    public DuplicateDetectionService(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /**
     * Detects potential duplicates for new transactions against existing transactions in database
     *
     * @param userId User ID to query existing transactions
     * @param newTransactions List of new transactions to check
     * @return Map of transaction index to list of duplicate matches
     */
    public Map<Integer, List<DuplicateMatch>> detectDuplicates(
            final String userId, final List<ParsedTransaction> newTransactions) {

        final Map<Integer, List<DuplicateMatch>> duplicates = new HashMap<>();

        if (newTransactions == null || newTransactions.isEmpty()) {
            return duplicates;
        }

        // Fetch existing transactions from database (optimized query)
        // Only fetch transactions from the date range of new transactions to minimize data transfer
        final LocalDate minDate =
                newTransactions.stream()
                        .map(ParsedTransaction::getDate)
                        .min(LocalDate::compareTo)
                        .orElse(LocalDate.now().minusMonths(3)); // Default to 3 months if empty

        final LocalDate maxDate =
                newTransactions.stream()
                        .map(ParsedTransaction::getDate)
                        .max(LocalDate::compareTo)
                        .orElse(LocalDate.now());

        // Expand date range by 35 days on each side to catch recurring transactions
        final LocalDate queryStartDate = minDate.minusDays(35);
        final LocalDate queryEndDate = maxDate.plusDays(35);

        final List<TransactionTable> existingTransactions =
                transactionRepository.findByUserIdAndDateRange(
                        userId, queryStartDate.toString(), queryEndDate.toString());

        LOGGER.info(
                "Checking {} new transactions against {} existing transactions (date range: {} to {})",
                newTransactions.size(),
                existingTransactions.size(),
                queryStartDate,
                queryEndDate);

        if (existingTransactions.isEmpty()) {
            LOGGER.info("No existing transactions found - skipping duplicate detection");
            return duplicates;
        }

        // Check each new transaction against existing ones
        for (int i = 0; i < newTransactions.size(); i++) {
            final ParsedTransaction newTransaction = newTransactions.get(i);
            final List<DuplicateMatch> matches = new ArrayList<>();
            boolean shouldSkip =
                    false; // Track if this transaction should be skipped (exact match, same ID,
            // etc.)

            for (final TransactionTable existingTransaction : existingTransactions) {
                // CRITICAL FIX: Same transaction ID should be skipped (definite duplicate,
                // auto-filtered)
                if (newTransaction.getTransactionId() != null
                        && existingTransaction.getTransactionId() != null
                        && newTransaction
                                .getTransactionId()
                                .equalsIgnoreCase(existingTransaction.getTransactionId())) {
                    // Same transaction ID = definite duplicate, skip (don't report to user)
                    LOGGER.debug(
                            "Same transaction ID found for transaction {}: {} - skipping (auto-filtered)",
                            i + 1,
                            newTransaction.getTransactionId());
                    // Mark this transaction to be skipped
                    shouldSkip = true;
                    matches.clear(); // Clear any matches we might have added
                    break; // Break out of inner loop since we found a match
                }

                // CRITICAL FIX: Same Plaid transaction ID should be skipped (definite duplicate,
                // auto-filtered)
                if (newTransaction.getPlaidTransactionId() != null
                        && existingTransaction.getPlaidTransactionId() != null
                        && newTransaction
                                .getPlaidTransactionId()
                                .equals(existingTransaction.getPlaidTransactionId())) {
                    // Same Plaid transaction ID = definite duplicate, skip (don't report to user)
                    LOGGER.debug(
                            "Same Plaid transaction ID found for transaction {}: {} - skipping (auto-filtered)",
                            i + 1,
                            newTransaction.getPlaidTransactionId());
                    // Mark this transaction to be skipped
                    shouldSkip = true;
                    matches.clear(); // Clear any matches we might have added
                    break; // Break out of inner loop since we found a match
                }

                // CRITICAL FIX: Exact matches (description, amount, date) should be skipped
                // These are definite duplicates and should be automatically filtered out, not
                // reported
                // The user doesn't need to review exact matches - they're definitely duplicates
                if (isExactMatch(newTransaction, existingTransaction)) {
                    LOGGER.debug(
                            "Exact match found for transaction {}: new='{}' (date={}, amount={}) matches existing='{}' (date={}, amount={}) - skipping (auto-filtered)",
                            i + 1,
                            newTransaction.getDescription(),
                            newTransaction.getDate(),
                            newTransaction.getAmount(),
                            existingTransaction.getDescription(),
                            existingTransaction.getTransactionDate(),
                            existingTransaction.getAmount());
                    // Mark this transaction to be skipped
                    shouldSkip = true;
                    matches.clear(); // Clear any matches we might have added
                    break; // Break out of inner loop - exact match found, skip this transaction
                }

                final double similarity = calculateSimilarity(newTransaction, existingTransaction);

                if (similarity >= SIMILARITY_THRESHOLD) {
                    LOGGER.debug(
                            "Found potential duplicate with similarity {}: new='{}' (date={}, amount={}) vs existing='{}' (date={}, amount={})",
                            similarity,
                            newTransaction.getDescription(),
                            newTransaction.getDate(),
                            newTransaction.getAmount(),
                            existingTransaction.getDescription(),
                            existingTransaction.getTransactionDate(),
                            existingTransaction.getAmount());
                    matches.add(
                            new DuplicateMatch(
                                    existingTransaction,
                                    similarity,
                                    generateMatchReason(
                                            newTransaction, existingTransaction, similarity)));
                }
            }

            // CRITICAL: Add to duplicates map if:
            // 1. We have fuzzy matches (similarity >= threshold), OR
            // 2. We should skip this transaction (exact match, same ID, same Plaid ID)
            // For skipped transactions, we add an empty list as a marker so processBatchImport can
            // detect them
            if (shouldSkip) {
                // Add empty list as marker for skipped transactions (exact matches, same ID, etc.)
                duplicates.put(i, new ArrayList<>());
                LOGGER.debug(
                        "Transaction {} was marked as skipped (exact match, same ID, or same Plaid ID)",
                        i + 1);
            } else if (!matches.isEmpty()) {
                // Sort by similarity (highest first)
                matches.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
                duplicates.put(i, matches);
                LOGGER.info(
                        "Found {} potential duplicate(s) for transaction {}",
                        matches.size(),
                        i + 1);
            }
        }

        LOGGER.info(
                "Detected duplicates for {} out of {} transactions",
                duplicates.size(),
                newTransactions.size());
        return duplicates;
    }

    /** Checks if two transactions are exact matches */
    private boolean isExactMatch(final ParsedTransaction t1, final TransactionTable t2) {
        // Compare amounts - check if they match or are negatives of each other
        final BigDecimal diff1 = t1.getAmount().subtract(t2.getAmount());
        final BigDecimal diff2 = t1.getAmount().add(t2.getAmount());
        final boolean amountMatch =
                diff1.compareTo(BigDecimal.valueOf(0.01)) < 0
                                && diff1.compareTo(BigDecimal.valueOf(-0.01)) > 0
                        || diff2.compareTo(BigDecimal.valueOf(0.01)) < 0
                                && diff2.compareTo(BigDecimal.valueOf(-0.01)) > 0;

        // CRITICAL FIX: Parse date from TransactionTable and compare with LocalDate from
        // ParsedTransaction
        // Ensure both dates are parsed consistently to avoid timezone/format issues
        final LocalDate t2Date = parseDate(t2.getTransactionDate());
        boolean dateMatch = false;
        if (t1.getDate() != null && t2Date != null) {
            dateMatch = t1.getDate().equals(t2Date);
            if (!dateMatch) {
                // Log date mismatch for debugging
                LOGGER.debug(
                        "isExactMatch: Date mismatch - t1.getDate()={}, t2.getTransactionDate()={}, parsed t2Date={}",
                        t1.getDate(),
                        t2.getTransactionDate(),
                        t2Date);
            }
        } else if (t1.getDate() == null && t2Date == null) {
            dateMatch = true; // Both null = match
        } else {
            LOGGER.debug(
                    "isExactMatch: One date is null - t1.getDate()={}, t2Date={}",
                    t1.getDate(),
                    t2Date);
        }

        final String desc1 = normalizeDescription(t1.getDescription());
        final String desc2 = normalizeDescription(t2.getDescription());
        final boolean descriptionMatch = desc1.equals(desc2);

        LOGGER.debug(
                "isExactMatch: amountMatch={}, dateMatch={} (t1: {}, t2: {}), descriptionMatch={}",
                amountMatch,
                dateMatch,
                t1.getDate(),
                t2Date,
                descriptionMatch);

        return amountMatch && dateMatch && descriptionMatch;
    }

    /** Calculates similarity between two transactions (0.0 to 1.0) */
    private double calculateSimilarity(final ParsedTransaction t1, final TransactionTable t2) {
        final String desc1 = normalizeDescription(t1.getDescription());
        final String desc2 = normalizeDescription(t2.getDescription());
        final boolean descriptionsMatch = desc1.equals(desc2);

        // Compare amounts - check if they match or are negatives of each other
        final BigDecimal diff1 = t1.getAmount().subtract(t2.getAmount());
        final BigDecimal diff2 = t1.getAmount().add(t2.getAmount());
        final boolean amountMatch =
                diff1.compareTo(BigDecimal.valueOf(0.01)) < 0
                                && diff1.compareTo(BigDecimal.valueOf(-0.01)) > 0
                        || diff2.compareTo(BigDecimal.valueOf(0.01)) < 0
                                && diff2.compareTo(BigDecimal.valueOf(-0.01)) > 0;

        // CRITICAL FIX: Parse date from TransactionTable and compare with LocalDate from
        // ParsedTransaction
        // Ensure both dates are parsed consistently to avoid timezone/format issues
        final LocalDate t2Date = parseDate(t2.getTransactionDate());
        boolean dateMatch = false;
        if (t1.getDate() != null && t2Date != null) {
            dateMatch = t1.getDate().equals(t2Date);
        } else if (t1.getDate() == null && t2Date == null) {
            dateMatch = true; // Both null = match
        }

        // If description, amount, and date all match exactly, it's almost certainly a duplicate
        if (descriptionsMatch && amountMatch && dateMatch) {
            return 0.95;
        }

        // CRITICAL: Recurring transactions (mortgage, rent, subscriptions) have same description
        // and amount
        // but different dates. If description and amount match but dates differ significantly,
        // it's likely a recurring transaction, not a duplicate.
        //
        // IMPORTANT: If description and amount match exactly, and dates differ by more than 7 days,
        // it's almost certainly a recurring transaction. True duplicates occur on the same day
        // (or within 1-2 days due to processing delays).
        if (descriptionsMatch && amountMatch && !dateMatch) {
            long daysDiff = ChronoUnit.DAYS.between(t1.getDate(), t2Date);
            if (daysDiff < 0) {
                daysDiff = -daysDiff;
            }

            // If dates are more than 7 days apart, it's likely a recurring transaction
            // This catches all recurring patterns: weekly, bi-weekly, monthly, quarterly, etc.
            // The key insight: if description and amount match exactly but dates differ by >7 days,
            // it's almost certainly recurring, not a duplicate
            if (daysDiff > 0) {
                // Check if the date difference matches a recurring pattern
                final boolean isDaily = daysDiff >= 1 && daysDiff <= 5;
                final boolean isWeekly = daysDiff >= 6 && daysDiff <= 8;
                final boolean isBiWeekly = daysDiff >= 13 && daysDiff <= 15;
                final boolean isMonthly = daysDiff >= 25 && daysDiff <= 31;
                final boolean isQuarterly = daysDiff >= 88 && daysDiff <= 93;
                final boolean isSemiAnnual = daysDiff >= 180 && daysDiff <= 186;
                final boolean isAnnual = daysDiff >= 365 && daysDiff <= 366;

                // CRITICAL: Catch any date difference between 7-60 days if description and amount
                // match exactly
                // Expanded from 35 to 60 days to catch bi-monthly payments and other irregular
                // recurring patterns
                // This ensures we don't flag recurring income/expenses as duplicates
                final boolean isLikelyRecurring = daysDiff >= 7 && daysDiff <= 60;

                if (isDaily
                        || isWeekly
                        || isBiWeekly
                        || isMonthly
                        || isQuarterly
                        || isSemiAnnual
                        || isAnnual
                        || isLikelyRecurring) {
                    // This is likely a recurring transaction, not a duplicate
                    LOGGER.debug(
                            "Recurring transaction detected: same description and amount, date difference: {} days",
                            daysDiff);
                    return 0.30; // Low similarity - not a duplicate (below 0.85 threshold)
                }
            }
        }

        final List<Double> scores = new ArrayList<>();

        // 1. Amount similarity (exact match = 1.0, within 1% = 0.9, etc.)
        final double amountScore = calculateAmountSimilarity(t1.getAmount(), t2.getAmount());
        scores.add(amountScore * 0.3); // 30% weight

        // 2. Date similarity (same day = 1.0, within 3 days = 0.8, within 7 days = 0.5, etc.)
        final double dateScore = calculateDateSimilarity(t1.getDate(), t2Date);
        scores.add(dateScore * 0.2); // 20% weight

        // 3. Description similarity (fuzzy string matching)
        final double descriptionScore = calculateStringSimilarity(desc1, desc2);
        // CRITICAL FIX: For imported transactions, require very high description similarity to
        // avoid false positives
        // Sequential transaction numbers (e.g., "Transaction 92" vs "Transaction 91") should not be
        // considered duplicates
        // Only add description score if it's very similar (>= 0.95) - near exact match
        // This prevents false positives for transactions with sequential numbers or similar
        // patterns
        if (descriptionScore >= 0.95) {
            scores.add(descriptionScore * 0.3); // 30% weight only if very similar
        } else if (descriptionScore >= 0.5) {
            // Moderately similar descriptions - give very low weight to prevent false positives
            scores.add(descriptionScore * 0.05); // Only 5% weight to reduce false positives
        } else {
            // If description is too different, reduce the overall score significantly
            scores.add(descriptionScore * 0.01); // Only 1% weight if not similar
        }

        // 4. Merchant name similarity (if available)
        final String m1 = t1.getMerchantName();
        final String m2 = t2.getMerchantName();
        if (m1 != null && !m1.isEmpty() && m2 != null && !m2.isEmpty()) {
            final double merchantScore =
                    calculateStringSimilarity(
                            m1.toLowerCase(Locale.ROOT).trim(), m2.toLowerCase(Locale.ROOT).trim());
            scores.add(merchantScore * 0.2); // 20% weight
        }

        // Return weighted sum (max 1.0 if all fields match perfectly)
        final double total = scores.stream().mapToDouble(Double::doubleValue).sum();
        return Math.min(1.0, total);
    }

    /** Calculates amount similarity */
    private double calculateAmountSimilarity(final BigDecimal a1, final BigDecimal a2) {
        // Compare amounts - handle cases where one amount is negative and the other is positive
        final BigDecimal diff1 = a1.subtract(a2);
        final BigDecimal diff;

        // If both amounts have the same sign (both positive or both negative), use diff1
        // If one is positive and one is negative, use sum (which represents the absolute
        // difference)
        final boolean a1Positive = a1.compareTo(BigDecimal.ZERO) >= 0;
        final boolean a2Positive = a2.compareTo(BigDecimal.ZERO) >= 0;

        if (a1Positive == a2Positive) {
            // Same sign - use absolute difference
            diff = diff1.abs();
        } else {
            // Opposite signs - use sum (one is negative of the other)
            // This should return a small value as one transaction is a return of another
            // diff = diff2.abs();
            return 0.3;
        }

        BigDecimal avg = a1.add(a2).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        if (avg.compareTo(BigDecimal.ZERO) < 0) {
            avg = avg.negate();
        }

        if (avg.compareTo(BigDecimal.ZERO) == 0) {
            return a1.compareTo(a2) == 0 ? 1.0 : 0.0;
        }

        final double percentDiff = diff.divide(avg, 4, RoundingMode.HALF_UP).doubleValue();

        if (percentDiff < 0.001) { // Exact match (within 0.1%)
            return 1.0;
        } else if (percentDiff < 0.005) { // Within .5%
            return 0.9;
        } else if (percentDiff < 0.01) { // Within 1%
            return 0.7;
        } else if (percentDiff < 0.05) { // Within 5%
            return 0.5;
        } else {
            return Math.max(0.0, 1.0 - percentDiff);
        }
    }

    /** Calculates date similarity */
    private double calculateDateSimilarity(final LocalDate d1, final LocalDate d2) {
        long daysDiff = ChronoUnit.DAYS.between(d1, d2);
        if (daysDiff < 0) {
            daysDiff = -daysDiff;
        }

        if (daysDiff == 0) {
            return 1.0;
        } else if (daysDiff <= 1) {
            return 0.9;
        } else if (daysDiff <= 3) {
            return 0.8;
        } else if (daysDiff <= 7) {
            return 0.5;
        } else if (daysDiff <= 30) {
            return 0.3;
        } else {
            return 0.0;
        }
    }

    /** Calculates string similarity using Levenshtein distance */
    private double calculateStringSimilarity(final String s1, final String s2) {
        if (s1 == null || s2 == null) {
            return 0.0;
        }

        if (s1.equals(s2)) {
            return 1.0;
        }

        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }

        final int distance = levenshteinDistance(s1, s2);
        final int maxLength = Math.max(s1.length(), s2.length());

        if (maxLength == 0) {
            return 1.0;
        }

        return 1.0 - ((double) distance / maxLength);
    }

    /** Calculates Levenshtein distance between two strings */
    private int levenshteinDistance(final String s1, final String s2) {
        final int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(Math.min(dp[i - 1][j], dp[i][j - 1]), dp[i - 1][j - 1]);
                }
            }
        }

        return dp[s1.length()][s2.length()];
    }

    /** Parses date string to LocalDate */
    private LocalDate parseDate(final String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            LOGGER.warn("Failed to parse date: {}", dateStr);
            return LocalDate.now();
        }
    }

    /** Normalizes description for comparison */
    private String normalizeDescription(final String description) {
        if (description == null) {
            return "";
        }
        // Collapse internal whitespace runs to single spaces — TransactionService
        // does the same on the persisted side via removeNamesFromText, so a
        // re-parsed CSV row ("PUGET SOUND ENER BILLPAY                    PPD ID: …")
        // would not equal its stored form ("PUGET SOUND ENER BILLPAY PPD ID: …")
        // without this. Bank statements are full of these padded ACH descriptions.
        return description.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }

    /** Generates a human-readable match reason */
    private String generateMatchReason(
            final ParsedTransaction newTransaction,
            final TransactionTable existingTransaction,
            final double similarity) {
        final List<String> reasons = new ArrayList<>();

        // Compare amounts - check if they match or are negatives of each other
        final BigDecimal diff1 =
                newTransaction.getAmount().subtract(existingTransaction.getAmount());
        final BigDecimal diff2 = newTransaction.getAmount().add(existingTransaction.getAmount());
        BigDecimal amountDiff = diff1;
        if (diff2.compareTo(diff1) < 0
                || (diff1.compareTo(BigDecimal.ZERO) < 0
                        && diff2.compareTo(BigDecimal.ZERO) >= 0)) {
            amountDiff = diff2;
        }
        if (amountDiff.compareTo(BigDecimal.ZERO) < 0) {
            amountDiff = amountDiff.negate();
        }
        if (amountDiff.compareTo(BigDecimal.valueOf(0.01)) < 0) {
            reasons.add("same amount");
        }

        final LocalDate existingDate = parseDate(existingTransaction.getTransactionDate());
        if (newTransaction.getDate().equals(existingDate)) {
            reasons.add("same date");
        }

        final String desc1 = normalizeDescription(newTransaction.getDescription());
        final String desc2 = normalizeDescription(existingTransaction.getDescription());
        if (desc1.equals(desc2)) {
            reasons.add("same description");
        } else {
            final double descSimilarity = calculateStringSimilarity(desc1, desc2);
            if (descSimilarity > 0.8) {
                reasons.add("similar description");
            }
        }

        return String.join(", ", reasons);
    }

    /** Represents a duplicate match */
    public static class DuplicateMatch {
        private final TransactionTable transaction;
        private final double similarity;
        private final String matchReason;

        public DuplicateMatch(
                final TransactionTable transaction,
                final double similarity,
                final String matchReason) {
            this.transaction = transaction;
            this.similarity = similarity;
            this.matchReason = matchReason;
        }

        public TransactionTable getTransaction() {
            return transaction;
        }

        public double getSimilarity() {
            return similarity;
        }

        public String getMatchReason() {
            return matchReason;
        }
    }

    /** Represents a parsed transaction (from CSV) */
    public static class ParsedTransaction {
        private String transactionId;
        private String plaidTransactionId;
        private LocalDate date;
        private BigDecimal amount;
        private String description;
        private String merchantName;

        public ParsedTransaction(
                final LocalDate date,
                final BigDecimal amount,
                final String description,
                final String merchantName) {
            this.date = date;
            this.amount = amount;
            this.description = description;
            this.merchantName = merchantName;
        }

        // Getters and setters
        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(final String transactionId) {
            this.transactionId = transactionId;
        }

        public String getPlaidTransactionId() {
            return plaidTransactionId;
        }

        public void setPlaidTransactionId(final String plaidTransactionId) {
            this.plaidTransactionId = plaidTransactionId;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(final LocalDate date) {
            this.date = date;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(final BigDecimal amount) {
            this.amount = amount;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public void setMerchantName(final String merchantName) {
            this.merchantName = merchantName;
        }
    }
}
