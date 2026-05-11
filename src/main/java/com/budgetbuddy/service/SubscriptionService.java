package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import com.budgetbuddy.service.ml.FuzzyMatchingService;
import com.budgetbuddy.util.IdGenerator;
import com.budgetbuddy.util.StringUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for detecting and managing subscriptions Identifies recurring transactions based on
 * amount, merchant, and date patterns
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class SubscriptionService {

    private static final String MEMBERSHIP = "membership";
    private static final String SUBSCRIPTION = "subscription";
    private static final String RECURRING = "recurring";
    private static final String SUBSCRIPTIONS = "subscriptions";
    private static final String INSURANCE = "insurance";
    private static final String STREAMING = "streaming";
    private static final String SOFTWARE = "software";

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionService.class);
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SubscriptionRepository subscriptionRepository;
    private final TransactionRepository transactionRepository;
    private final EnhancedCategoryDetectionService enhancedCategoryDetectionService;
    private final FuzzyMatchingService fuzzyMatchingService;

    // Subscription-related categories from merchant database
    private static final Set<String> SUBSCRIPTION_CATEGORIES =
            Set.of(
                    SUBSCRIPTIONS,
                    STREAMING,
                    SOFTWARE,
                    MEMBERSHIP,
                    "cloud_storage",
                    "tech",
                    "entertainment",
                    "health",
                    INSURANCE,
                    "education");

    // HARD EXCLUSIONS — Plaid taxonomies that look RECURRING because the
    // user pays them every month, but are NOT subscriptions in any
    // user-facing sense (you can't cancel, downgrade, or substitute
    // them). Detector skips these before any inclusion logic runs.
    private static final Set<String> NON_SUBSCRIPTION_PRIMARY =
            Set.of(
                    "loan_payments",
                    "transfer_in",
                    "transfer_out",
                    "transfer",
                    "bank_fees",
                    "investment", // CD deposits, brokerage contributions etc. are
                    "investments", // recurring but not subscriptions
                    "income" // payroll / rent income re-categorised loosely
                    );

    private static final Set<String> NON_SUBSCRIPTION_DETAILED_TOKENS =
            Set.of(
                    "credit_card_payment",
                    "loan_payment",
                    "mortgage",
                    "student_loan",
                    "auto_payment",
                    "personal_loan",
                    "transfer",
                    "withdrawal",
                    "deposit",
                    "investment",
                    "brokerage",
                    "retirement",
                    "401k",
                    "ira",
                    "cd_deposit",
                    "treasury");

    private static final Set<String> NON_SUBSCRIPTION_MERCHANT_TOKENS =
            Set.of(
                    "payment thank you",
                    "credit card payment",
                    "card payment",
                    "autopay",
                    "ach payment",
                    "loan payment",
                    "mortgage",
                    "transfer",
                    "online payment",
                    "bill payment",
                    "cd deposit",
                    "brokerage deposit",
                    "401k contribution",
                    "ira contribution",
                    "investment purchase");

    /**
     * Checks whether a transaction is a recurring movement of money the user shouldn't see in a
     * SUBSCRIPTIONS list — credit-card payments, loan repayments, account transfers. We OR three
     * signals (primary category, detailed category, merchant name) because each source can be
     * missing or generic on its own. Returning {@code true} here removes the candidate from
     * subscription detection entirely.
     */
    private boolean isNonSubscriptionRecurringMovement(
            final String categoryPrimary,
            final String categoryDetailed,
            final String merchantName,
            final String description) {
        if (categoryPrimary != null) {
            final String primaryLower = categoryPrimary.toLowerCase(Locale.ROOT);
            if (NON_SUBSCRIPTION_PRIMARY.contains(primaryLower)) {
                return true;
            }
            // Catch variants like "TRANSFER OUT" or "Loan Payments"
            if (primaryLower.contains("transfer") || primaryLower.contains("loan_payment")) {
                return true;
            }
        }
        if (categoryDetailed != null) {
            final String detailedLower = categoryDetailed.toLowerCase(Locale.ROOT);
            for (final String token : NON_SUBSCRIPTION_DETAILED_TOKENS) {
                if (detailedLower.contains(token)) {
                    return true;
                }
            }
        }
        final String haystack =
                ((merchantName == null ? "" : merchantName)
                                + " "
                                + (description == null ? "" : description))
                        .toLowerCase(Locale.ROOT);
        for (final String token : NON_SUBSCRIPTION_MERCHANT_TOKENS) {
            if (haystack.contains(token)) {
                return true;
            }
        }
        return false;
    }

    public SubscriptionService(
            final SubscriptionRepository subscriptionRepository,
            final TransactionRepository transactionRepository,
            final EnhancedCategoryDetectionService enhancedCategoryDetectionService,
            final FuzzyMatchingService fuzzyMatchingService) {
        this.subscriptionRepository = subscriptionRepository;
        this.transactionRepository = transactionRepository;
        this.enhancedCategoryDetectionService = enhancedCategoryDetectionService;
        this.fuzzyMatchingService = fuzzyMatchingService;
    }

    /*
     * Detects subscriptions from user's transactions Groups transactions by merchant and amount,
     * then identifies recurring patterns
     */
    /**
     * Detects subscriptions using existing merchant database, fuzzy matching, and pattern detection
     * Rule: 3+ transactions with same amount pattern and recurring frequency = subscription
     */
    public List<Subscription> detectSubscriptions(final String userId) {
        LOGGER.info("Detecting subscriptions for user: {}", userId);

        // Get all expense transactions
        final List<TransactionTable> allExpenses =
                transactionRepository.findByUserId(userId, 0, 10_000).stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .collect(Collectors.toList());

        // CRITICAL FIX: Pre-filter transactions by category to focus on subscription-related
        // expenses
        // This reduces noise from one-time payments and improves detection accuracy
        final List<TransactionTable> subscriptionCandidates =
                allExpenses.stream()
                        .filter(
                                tx -> {
                                    final String categoryPrimary = tx.getCategoryPrimary();
                                    final String categoryDetailed = tx.getCategoryDetailed();
                                    final String merchantName = tx.getMerchantName();
                                    final String description = tx.getDescription();

                                    // HARD EXCLUSION first: transfers / loan payments / credit
                                    // card payments are recurring by nature but are NOT
                                    // subscriptions — they're movements of the user's own
                                    // money. Without this check, monthly "AMEX PAYMENT" or
                                    // "MORTGAGE PAYMENT" rows show up in the user's
                                    // subscription list and look like cancellable services.
                                    if (isNonSubscriptionRecurringMovement(
                                            categoryPrimary,
                                            categoryDetailed,
                                            merchantName,
                                            description)) {
                                        return false;
                                    }

                                    // Include transactions that match subscription indicators
                                    // 1. Already categorized as subscription
                                    if ((SUBSCRIPTIONS.equalsIgnoreCase(categoryPrimary))
                                            || (SUBSCRIPTIONS.equalsIgnoreCase(categoryDetailed))) {
                                        return true;
                                    }

                                    // 2. Category matches subscription-related categories
                                    if (categoryPrimary != null
                                            && SUBSCRIPTION_CATEGORIES.contains(
                                                    categoryPrimary.toLowerCase(Locale.ROOT))) {
                                        return true;
                                    }

                                    // 3. Known subscription merchant (from merchant database)
                                    if (isKnownSubscriptionMerchant(merchantName, description)) {
                                        return true;
                                    }

                                    // 4. Category detailed contains subscription keywords
                                    if (categoryDetailed != null) {
                                        final String detailedLower =
                                                categoryDetailed.toLowerCase(Locale.ROOT);
                                        if (detailedLower.contains(SUBSCRIPTION)
                                                || detailedLower.contains(MEMBERSHIP)
                                                || detailedLower.contains(RECURRING)
                                                || detailedLower.contains(STREAMING)
                                                || detailedLower.contains(SOFTWARE)
                                                || detailedLower.contains("cloud")) {
                                            return true;
                                        }
                                    }

                                    // 5. Include description-driven recurring matches
                                    // (e.g. "Netflix subscription monthly" with no category set
                                    // by the importer). The previous broader "any expense
                                    // <$1000" catch-all picked up everyday charges (rides,
                                    // groceries, gas) that happened to fall on a regular
                                    // cadence and was the dominant source of false positives.
                                    if (description != null) {
                                        final String descLower =
                                                description.toLowerCase(Locale.ROOT);
                                        if (descLower.contains(SUBSCRIPTION)
                                                || descLower.contains(MEMBERSHIP)
                                                || descLower.contains(RECURRING)
                                                || descLower.contains("monthly")
                                                || descLower.contains("annual")
                                                || descLower.contains("yearly")) {
                                            return true;
                                        }
                                    }

                                    return false;
                                })
                        .collect(Collectors.toList());

        LOGGER.debug(
                "Pre-filtered {} subscription candidates from {} total expenses",
                subscriptionCandidates.size(),
                allExpenses.size());

        // Group transactions by similar merchant (using fuzzy matching) and amount
        final Map<String, List<TransactionTable>> transactionsByMerchant =
                groupTransactionsByMerchant(subscriptionCandidates);

        final List<Subscription> detectedSubscriptions = new ArrayList<>();

        for (final Map.Entry<String, List<TransactionTable>> entry :
                transactionsByMerchant.entrySet()) {
            final String merchant = entry.getKey();
            final List<TransactionTable> merchantTransactions = entry.getValue();

            // REQUIREMENT: Need at least 2 transactions to detect a subscription (lowered from 3)
            // This allows detection of newer subscriptions, but frequency validation ensures
            // quality
            if (merchantTransactions.size() < 2) {
                LOGGER.debug(
                        "Skipping merchant '{}': only {} transactions (need at least 2)",
                        merchant,
                        merchantTransactions.size());
                continue;
            }

            // Group by amount (within 5% tolerance)
            final Map<BigDecimal, List<TransactionTable>> transactionsByAmount =
                    groupByAmount(merchantTransactions);

            for (final Map.Entry<BigDecimal, List<TransactionTable>> amountEntry :
                    transactionsByAmount.entrySet()) {
                final BigDecimal amount = amountEntry.getKey();
                final List<TransactionTable> sameAmountTransactions = amountEntry.getValue();

                // REQUIREMENT: Need at least 2 transactions with same amount pattern (lowered from
                // 3)
                // Frequency validation will ensure it's truly recurring
                if (sameAmountTransactions.size() < 2) {
                    LOGGER.debug(
                            "Skipping amount group {} for merchant '{}': only {} transactions",
                            amount,
                            merchant,
                            sameAmountTransactions.size());
                    continue;
                }

                // Sort by date
                sameAmountTransactions.sort(
                        (a, b) -> {
                            final LocalDate dateA = parseDate(a.getTransactionDate());
                            final LocalDate dateB = parseDate(b.getTransactionDate());
                            if (dateA == null || dateB == null) {
                                return 0;
                            }
                            return dateA.compareTo(dateB);
                        });

                // Detect frequency
                final Subscription.SubscriptionFrequency frequency =
                        detectFrequency(sameAmountTransactions);

                if (frequency != null) {
                    final TransactionTable firstTransaction = sameAmountTransactions.get(0);
                    final LocalDate startDate = parseDate(firstTransaction.getTransactionDate());

                    if (startDate != null) {
                        final Subscription subscription = new Subscription();

                        // CRITICAL FIX: Use actual merchantName from transaction, not group key
                        // Group key might be normalized or from description - always use real
                        // merchantName
                        String actualMerchantName = firstTransaction.getMerchantName();
                        if (actualMerchantName == null || actualMerchantName.isBlank()) {
                            // Only use description if merchantName is truly missing
                            actualMerchantName =
                                    firstTransaction.getDescription() != null
                                            ? firstTransaction.getDescription()
                                            : merchant;
                        }

                        // CRITICAL FIX: Normalize generated subscription ID to lowercase for
                        // consistency
                        // Use actual merchant name for ID generation (for uniqueness)
                        final String idMerchantKey =
                                actualMerchantName != null
                                        ? StringUtils.normalizeMerchantName(actualMerchantName)
                                        : merchant;
                        final String generatedId =
                                IdGenerator.generateSubscriptionId(userId, idMerchantKey, amount);
                        final String normalizedId = IdGenerator.normalizeUUID(generatedId);
                        subscription.setSubscriptionId(normalizedId);
                        subscription.setUserId(userId);
                        subscription.setAccountId(firstTransaction.getAccountId());

                        // CRITICAL FIX: Always use actual merchantName, never use description as
                        // merchant
                        subscription.setMerchantName(actualMerchantName);

                        // Use description from first transaction (most representative)
                        // If description is same as merchantName, keep it empty to avoid
                        // duplication
                        final String description = firstTransaction.getDescription();
                        if (description != null && !description.isBlank()) {
                            final String normalizedDesc =
                                    StringUtils.normalizeMerchantName(description);
                            final String normalizedMerchant =
                                    StringUtils.normalizeMerchantName(actualMerchantName);
                            // Only set description if it's different from merchantName
                            if (!normalizedDesc.equals(normalizedMerchant)
                                    && !normalizedDesc.contains(normalizedMerchant)
                                    && !normalizedMerchant.contains(normalizedDesc)) {
                                subscription.setDescription(description);
                            } else {
                                // Description is same as merchant - don't duplicate
                                subscription.setDescription(null);
                            }
                        } else {
                            subscription.setDescription(null);
                        }
                        subscription.setAmount(
                                amount); // Store amount as-is (negative for expenses)
                        subscription.setFrequency(frequency);
                        subscription.setStartDate(startDate);

                        // CRITICAL FIX: Calculate nextPaymentDate from lastPaymentDate if available
                        // This ensures active subscriptions show up correctly
                        final TransactionTable lastTransaction =
                                sameAmountTransactions.get(sameAmountTransactions.size() - 1);
                        final LocalDate lastPaymentDate =
                                parseDate(lastTransaction.getTransactionDate());
                        if (lastPaymentDate != null) {
                            subscription.setLastPaymentDate(lastPaymentDate);
                            // Calculate nextPaymentDate from lastPaymentDate (more accurate for
                            // active subscriptions)
                            final LocalDate nextPaymentDate =
                                    calculateNextPaymentDate(lastPaymentDate, frequency);
                            if (nextPaymentDate != null) {
                                subscription.setNextPaymentDate(nextPaymentDate);
                            } else {
                                // Fallback: calculate from startDate
                                subscription.setNextPaymentDate(
                                        calculateNextPaymentDate(startDate, frequency));
                            }
                            subscription.recordPayment(lastPaymentDate);
                        } else {
                            // No last payment date - calculate from startDate
                            subscription.setNextPaymentDate(
                                    calculateNextPaymentDate(startDate, frequency));
                        }

                        // Preserve original category information
                        final String originalCategoryPrimary =
                                firstTransaction.getCategoryPrimary();
                        final String originalCategoryDetailed =
                                firstTransaction.getCategoryDetailed();
                        subscription.setOriginalCategoryPrimary(originalCategoryPrimary);
                        subscription.setOriginalCategoryDetailed(originalCategoryDetailed);

                        // Set category - use detailed if it's SUBSCRIPTIONS, otherwise use
                        // SUBSCRIPTIONS as default
                        // CRITICAL FIX: Even if categorized as "education" (like Barrons), still
                        // mark as subscription
                        // The category field is for transaction categorization, but subscription
                        // detection is independent
                        if (SUBSCRIPTIONS.equalsIgnoreCase(originalCategoryDetailed)) {
                            subscription.setCategory(originalCategoryDetailed);
                        } else if (SUBSCRIPTIONS.equalsIgnoreCase(originalCategoryPrimary)) {
                            subscription.setCategory(originalCategoryPrimary);
                        } else {
                            subscription.setCategory(SUBSCRIPTIONS);
                        }

                        // Infer subscription type from categoryDetailed and merchant
                        final String subscriptionType = inferSubscriptionType(firstTransaction);
                        subscription.setSubscriptionType(subscriptionType);

                        // CRITICAL FIX: Determine subscriptionCategory: SUBSCRIPTION vs
                        // RECURRING
                        // SUBSCRIPTION = merchant-based recurring payments (Netflix, Spotify, gym
                        // memberships)
                        // RECURRING = bills, loans, mortgage, utilities (contract-based or
                        // necessary expenses)
                        final String subscriptionCategory =
                                determineSubscriptionCategory(
                                        firstTransaction, actualMerchantName, subscriptionType);
                        subscription.setSubscriptionCategory(subscriptionCategory);

                        subscription.setActive(true);
                        subscription.setPlaidTransactionId(
                                firstTransaction.getPlaidTransactionId());

                        detectedSubscriptions.add(subscription);
                        LOGGER.info(
                                "Detected subscription: {} - {} - {} ({}) - Type: {} - NextPayment: {}",
                                merchant,
                                amount,
                                frequency,
                                startDate,
                                subscriptionType,
                                subscription.getNextPaymentDate());
                    }
                }
            }
        }

        LOGGER.info("Detected {} subscriptions for user: {}", detectedSubscriptions.size(), userId);
        return detectedSubscriptions;
    }

    /**
     * Groups transactions by merchant using fuzzy matching and merchant database CRITICAL FIX:
     * Never mix merchantName and description - use merchantName exclusively Only use description as
     * fallback if merchantName is truly null/empty
     */
    private Map<String, List<TransactionTable>> groupTransactionsByMerchant(
            final List<TransactionTable> transactions) {
        final Map<String, List<TransactionTable>> grouped = new HashMap<>();

        for (final TransactionTable tx : transactions) {
            final String merchantName = tx.getMerchantName();
            final String description = tx.getDescription();

            // CRITICAL FIX: Always use merchantName as primary identifier
            // Only fall back to description if merchantName is completely missing
            final String groupKey;

            if (merchantName != null && !merchantName.isBlank()) {
                // Use merchantName exclusively - normalize it
                final String normalizedMerchant = StringUtils.normalizeMerchantName(merchantName);

                // Use normalized merchant name as group key (consistent grouping)
                groupKey = normalizedMerchant;

                // CRITICAL: Log if description differs significantly from merchantName
                // This helps debug merchant/description mixing issues
                if (description != null
                        && !description.isBlank()
                        && !normalizedMerchant
                                .toLowerCase(Locale.ROOT)
                                .contains(
                                        StringUtils.normalizeMerchantName(description)
                                                .toLowerCase(Locale.ROOT))
                        && !StringUtils.normalizeMerchantName(description)
                                .toLowerCase(Locale.ROOT)
                                .contains(normalizedMerchant.toLowerCase(Locale.ROOT))) {
                    LOGGER.debug(
                            "Subscription detection: Merchant '{}' has different description '{}' - using merchant only",
                            merchantName,
                            description);
                }
            } else {
                // Only use description if merchantName is truly missing
                // CRITICAL: Never mix description from one transaction with merchantName from
                // another
                if (description != null && !description.isBlank()) {
                    groupKey = StringUtils.normalizeMerchantName(description);
                    LOGGER.debug(
                            "Subscription detection: Using description '{}' as merchant (merchantName missing)",
                            description);
                } else {
                    groupKey = "unknown";
                    LOGGER.debug(
                            "Subscription detection: Both merchantName and description missing for transaction");
                }
            }

            // Group by this key - each transaction gets its own merchant-based group
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(tx);
        }

        // Apply fuzzy matching to merge similar merchant groups
        // CRITICAL FIX: Be more strict with fuzzy matching to avoid false merges
        final Map<String, List<TransactionTable>> merged = new HashMap<>();
        final Set<String> processed = new HashSet<>();

        for (final Map.Entry<String, List<TransactionTable>> entry : grouped.entrySet()) {
            final String key = entry.getKey();
            if (processed.contains(key)) {
                continue;
            }

            final List<TransactionTable> group = new ArrayList<>(entry.getValue());
            processed.add(key);

            // Find similar groups using fuzzy matching - but be stricter
            for (final Map.Entry<String, List<TransactionTable>> otherEntry : grouped.entrySet()) {
                final String otherKey = otherEntry.getKey();
                if (processed.contains(otherKey) || key.equals(otherKey)) {
                    continue;
                }

                // CRITICAL FIX: Only merge if merchants are truly similar
                // Check that both groups are from merchantName (not mixed with description-based
                // groups)
                final boolean bothFromMerchant =
                        entry.getValue().stream()
                                        .allMatch(
                                                tx ->
                                                        tx.getMerchantName() != null
                                                                && !tx.getMerchantName()
                                                                        .trim()
                                                                        .isEmpty())
                                && otherEntry.getValue().stream()
                                        .allMatch(
                                                tx ->
                                                        tx.getMerchantName() != null
                                                                && !tx.getMerchantName()
                                                                        .trim()
                                                                        .isEmpty());

                // Only merge if both are merchantName-based OR fuzzy match is very high
                if (bothFromMerchant && areMerchantsSimilar(key, otherKey)) {
                    group.addAll(otherEntry.getValue());
                    processed.add(otherKey);
                    LOGGER.debug(
                            "Subscription detection: Merged merchant groups '{}' and '{}' (fuzzy match)",
                            key,
                            otherKey);
                }
            }

            merged.put(key, group);
        }

        return merged;
    }

    /**
     * Checks if two merchant names are similar using fuzzy matching CRITICAL FIX: Improved matching
     * for merchant name variations (e.g., "DJ*Barrons" vs "D J*BARRONS")
     */
    private boolean areMerchantsSimilar(final String merchant1, final String merchant2) {
        if (merchant1 == null || merchant2 == null) {
            return false;
        }

        // Normalize both merchant names for comparison
        final String normalized1 = StringUtils.normalizeMerchantName(merchant1);
        final String normalized2 = StringUtils.normalizeMerchantName(merchant2);

        // Exact match after normalization
        if (normalized1.equals(normalized2)) {
            return true;
        }

        // Significant-token overlap. Groups merchants like "AMAZON PRIME" /
        // "AMAZON.COM PRIME" / "AMZN.COM/PRIME" that share the same brand
        // despite different TLDs, payment-processor prefixes, or store IDs.
        // Each token is further stripped of .com/.net/.co/.org suffixes and
        // compared to its counterparts; a prefix-of-length-≥4 match counts
        // as the same brand token ("amazon" ≈ "amzn" via ≥4-char prefix).
        final String normalized1Lower = normalized1.toLowerCase(Locale.ROOT);
        final String normalized2Lower = normalized2.toLowerCase(Locale.ROOT);
        final java.util.Set<String> tokens1 = brandTokens(normalized1Lower);
        final java.util.Set<String> tokens2 = brandTokens(normalized2Lower);
        int commonTokens = 0;
        for (final String a : tokens1) {
            for (final String b : tokens2) {
                if (tokensMatch(a, b)) {
                    commonTokens++;
                    break;
                }
            }
        }
        if (commonTokens >= 2) {
            return true;
        }
        if (commonTokens >= 1 && (tokens1.size() <= 1 || tokens2.size() <= 1)) {
            return true;
        }

        // Use fuzzy matching service (category-based, uses Map interface)
        // Create a temporary map for fuzzy matching
        FuzzyMatchingService.MatchResult match =
                fuzzyMatchingService.findBestMatch(normalized2, List.of(normalized1));
        if (match != null && match.combinedScore >= 0.90) {
            return true;
        }

        // Also check reverse
        match = fuzzyMatchingService.findBestMatch(normalized1, List.of(normalized2));
        return match != null && match.combinedScore >= 0.90;
    }

    /**
     * Split a merchant name into brand-significant tokens. Each token is lowercased, stripped of
     * any ".com", ".net", ".org", ".co" suffix, and only tokens of length ≥ 3 are kept. Slash and
     * asterisk are treated as separators so "AMZN.COM/PRIME" yields {amzn, prime}.
     */
    private static java.util.Set<String> brandTokens(final String s) {
        final java.util.Set<String> out = new java.util.HashSet<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        final String[] raw = s.split("[\\s/*]+");
        for (final String t : raw) {
            final String cleaned =
                    t.replaceAll("\\.com$", "")
                            .replaceAll("\\.net$", "")
                            .replaceAll("\\.org$", "")
                            .replaceAll("\\.co$", "");
            if (cleaned.length() >= 3) {
                out.add(cleaned);
            }
        }
        return out;
    }

    /**
     * Two brand tokens match when they are equal OR one is a prefix of the other with at least 4
     * characters in common. The prefix allowance captures "amazon" vs "amzn" and "microsoft" vs
     * "microsoft365".
     */
    private static boolean tokensMatch(final String a, final String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        if (a.length() >= 4
                && b.length() >= 4
                && (a.startsWith(b.substring(0, Math.min(4, b.length())))
                        || b.startsWith(a.substring(0, Math.min(4, a.length()))))) {
            return true;
        }
        return false;
    }

    /** Saves or updates subscriptions for a user */
    public void saveSubscriptions(final String userId, final List<Subscription> subscriptions) {
        for (final Subscription subscription : subscriptions) {
            final SubscriptionTable table = toSubscriptionTable(subscription);
            subscriptionRepository.save(table);
        }
        LOGGER.info("Saved {} subscriptions for user: {}", subscriptions.size(), userId);

        // Flow 5 / O9 — if a user has detected subscriptions but no Subscriptions budget,
        // seed one from the sum of detected monthly-equivalent amounts (with a 10% cushion
        // for price changes). One-shot: we only auto-seed when no budget exists. If the
        // user later edits or deletes it, we won't re-create it.
        try {
            if (subscriptionSeeder != null && !subscriptions.isEmpty()) {
                subscriptionSeeder.seedSubscriptionsBudgetIfMissing(userId, subscriptions);
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to seed Subscriptions budget for user {}: {}", userId, e.getMessage());
        }
    }

    // Injected lazily to avoid a circular bean graph (SubscriptionService ↔ BudgetService).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SubscriptionsBudgetSeeder subscriptionSeeder;

    /**
     * Gets all subscriptions for a user CRITICAL FIX: Filters out null subscriptions that may
     * result from invalid data conversion
     */
    public List<Subscription> getSubscriptions(final String userId) {
        final List<SubscriptionTable> tables = subscriptionRepository.findByUserId(userId);
        return tables.stream()
                .map(this::toSubscription)
                .filter(subscription -> subscription != null) // Filter out null subscriptions
                .filter(
                        subscription ->
                                subscription.getSubscriptionId() != null) // Ensure ID is not null
                .filter(
                        subscription ->
                                subscription.getStartDate()
                                        != null) // Ensure startDate is not null (required)
                .filter(
                        subscription ->
                                subscription.getNextPaymentDate()
                                        != null) // Ensure nextPaymentDate is not null (required)
                .filter(
                        subscription ->
                                subscription.getFrequency()
                                        != null) // Ensure frequency is not null (required)
                .collect(Collectors.toList());
    }

    /**
     * Gets active subscriptions for a user CRITICAL FIX: Filters out null subscriptions and checks
     * if subscription is actually active A subscription is active if: - active flag is true AND -
     * nextPaymentDate is in the future (or within 30 days grace period for overdue payments)
     */
    public List<Subscription> getActiveSubscriptions(final String userId) {
        final List<SubscriptionTable> tables = subscriptionRepository.findActiveByUserId(userId);
        final LocalDate now = LocalDate.now();

        return tables.stream()
                .map(this::toSubscription)
                .filter(subscription -> subscription != null) // Filter out null subscriptions
                .filter(
                        subscription ->
                                subscription.getSubscriptionId() != null) // Ensure ID is not null
                .filter(
                        subscription ->
                                subscription.getStartDate()
                                        != null) // Ensure startDate is not null (required)
                .filter(
                        subscription ->
                                subscription.getNextPaymentDate()
                                        != null) // Ensure nextPaymentDate is not null (required)
                .filter(
                        subscription ->
                                subscription.getFrequency()
                                        != null) // Ensure frequency is not null (required)
                .filter(
                        subscription -> {
                            // CRITICAL FIX: Check if subscription is actually active based on
                            // nextPaymentDate
                            // Allow grace period for subscriptions that are slightly overdue (up to
                            // 30 days)
                            final LocalDate nextPayment = subscription.getNextPaymentDate();
                            // Subscription is active if:
                            // 1. nextPaymentDate is in the future, OR
                            // 2. nextPaymentDate is in the past but within 30 days (grace period
                            // for overdue payments)
                            if (nextPayment.isAfter(now)) {
                                return true; // Future payment - definitely active
                            }
                            // Check if within grace period (not more than 30 days overdue)
                            final LocalDate gracePeriodStart = now.minusDays(30);
                            return nextPayment.isAfter(gracePeriodStart)
                                    || nextPayment.isEqual(gracePeriodStart);
                        })
                .collect(Collectors.toList());
    }

    /** Deletes a subscription */
    public void deleteSubscription(final String subscriptionId) {
        subscriptionRepository.delete(subscriptionId);
        LOGGER.info("Deleted subscription: {}", subscriptionId);
    }

    /**
     * Checks if two merchant/description strings are similar (fuzzy match) Uses simple similarity
     * check - can be enhanced with Levenshtein distance
     */
    private boolean isSimilarMerchant(final String merchant1, final String merchant2) {
        if (merchant1 == null || merchant2 == null) {
            return false;
        }

        final String m1 = merchant1.toLowerCase(Locale.ROOT).trim();
        final String m2 = merchant2.toLowerCase(Locale.ROOT).trim();

        // Exact match
        if (m1.equals(m2)) {
            return true;
        }

        // Check if one contains the other (for cases like "OPENAI *CHATGPT" vs "OPENAI CHATGPT")
        if (m1.length() > 10 && m2.length() > 10) {
            // Extract key words (remove special chars, numbers)
            final String m1Words = m1.replaceAll("[^a-z\\s]", " ").trim();
            final String m2Words = m2.replaceAll("[^a-z\\s]", " ").trim();

            // Check word overlap (at least 2 common significant words)
            final String[] words1 = m1Words.split("\\s+");
            final String[] words2 = m2Words.split("\\s+");

            int commonWords = 0;
            for (final String w1 : words1) {
                if (w1.length() > 3) { // Only significant words
                    for (final String w2 : words2) {
                        if (w2.length() > 3 && w1.equals(w2)) {
                            commonWords++;
                            break;
                        }
                    }
                }
            }

            // If at least 2 significant words match, consider similar
            if (commonWords >= 2) {
                return true;
            }
        }

        // Check substring match (one contains significant portion of the other)
        if (m1.length() > 5 && m2.length() > 5) {
            final int minLen = Math.min(m1.length(), m2.length());
            final int matchLen = minLen * 2 / 3; // At least 2/3 of shorter string should match

            if (m1.length() >= matchLen && m2.length() >= matchLen) {
                // Check if a significant substring matches
                for (int i = 0; i <= m1.length() - matchLen; i++) {
                    final String substring = m1.substring(i, i + matchLen);
                    if (m2.contains(substring)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** Detects frequency from transaction dates */
    private Subscription.SubscriptionFrequency detectFrequency(
            final List<TransactionTable> transactions) {
        if (transactions.size() < 2) {
            return null;
        }

        final List<LocalDate> dates =
                transactions.stream()
                        .map(tx -> parseDate(tx.getTransactionDate()))
                        .filter(date -> date != null)
                        .sorted()
                        .collect(Collectors.toList());

        if (dates.size() < 2) {
            return null;
        }

        // Calculate average days between transactions
        long totalDays = 0;
        int intervals = 0;
        for (int i = 1; i < dates.size(); i++) {
            final long days =
                    java.time.temporal.ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
            totalDays += days;
            intervals++;
        }

        if (intervals == 0) {
            return null;
        }

        final double averageDays = (double) totalDays / intervals;

        // ENHANCED: Determine frequency based on average days with expanded patterns
        // Daily subscriptions (1-2 days)
        if (averageDays >= 0.5 && averageDays <= 2.5) {
            return Subscription.SubscriptionFrequency.DAILY;
        }
        // Weekly subscriptions (6-8 days)
        else if (averageDays >= 6 && averageDays <= 8) {
            return Subscription.SubscriptionFrequency.WEEKLY;
        }
        // Bi-weekly subscriptions (13-15 days)
        else if (averageDays >= 13 && averageDays <= 15) {
            return Subscription.SubscriptionFrequency.BI_WEEKLY;
        }
        // Monthly subscriptions (25-35 days)
        else if (averageDays >= 25 && averageDays <= 35) {
            return Subscription.SubscriptionFrequency.MONTHLY;
        }
        // Quarterly subscriptions (85-95 days)
        else if (averageDays >= 85 && averageDays <= 95) {
            return Subscription.SubscriptionFrequency.QUARTERLY;
        }
        // Semi-annual subscriptions (175-185 days)
        else if (averageDays >= 175 && averageDays <= 185) {
            return Subscription.SubscriptionFrequency.SEMI_ANNUAL;
        }
        // Annual subscriptions (360-370 days)
        else if (averageDays >= 360 && averageDays <= 370) {
            return Subscription.SubscriptionFrequency.ANNUAL;
        }

        // ENHANCED: Check for day-of-month patterns (1st, 15th, last day of month)
        // This handles cases where transactions occur on specific days each month
        final Subscription.SubscriptionFrequency dayOfMonthFrequency =
                detectDayOfMonthPattern(dates);
        if (dayOfMonthFrequency != null) {
            return dayOfMonthFrequency;
        }

        return null;
    }

    /**
     * Detects day-of-month patterns (1st, 15th, last day of month) This handles subscriptions that
     * occur on specific days each month
     */
    private Subscription.SubscriptionFrequency detectDayOfMonthPattern(
            final List<LocalDate> dates) {
        if (dates.size() < 3) {
            return null; // Need at least 3 transactions to detect pattern
        }

        // Check if transactions occur on same day of month (within 2 days tolerance)
        final Map<Integer, Integer> dayOfMonthCounts = new HashMap<>();
        for (final LocalDate date : dates) {
            final int dayOfMonth = date.getDayOfMonth();
            // Group days: 1-3 (1st), 14-16 (15th), last 3 days of month
            final int dayGroup;
            if (dayOfMonth <= 3) {
                dayGroup = 1; // 1st of month
            } else if (dayOfMonth >= 14 && dayOfMonth <= 16) {
                dayGroup = 15; // 15th of month
            } else if (dayOfMonth >= date.lengthOfMonth() - 2) {
                dayGroup = -1; // Last day of month
            } else {
                continue; // Not a common subscription day
            }

            dayOfMonthCounts.put(dayGroup, dayOfMonthCounts.getOrDefault(dayGroup, 0) + 1);
        }

        // If at least 70% of transactions occur on same day group, it's a monthly subscription
        final int totalTransactions = dates.size();
        for (final Map.Entry<Integer, Integer> entry : dayOfMonthCounts.entrySet()) {
            if (entry.getValue() >= (totalTransactions * 0.7)) {
                return Subscription.SubscriptionFrequency.MONTHLY;
            }
        }

        return null;
    }

    /** Groups transactions by amount (within 5% tolerance) */
    private Map<BigDecimal, List<TransactionTable>> groupByAmount(
            final List<TransactionTable> transactions) {
        final Map<BigDecimal, List<TransactionTable>> grouped = new HashMap<>();

        for (final TransactionTable tx : transactions) {
            final BigDecimal amount = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;

            // Find existing group within 5% tolerance
            BigDecimal matchingAmount = null;
            for (final BigDecimal existingAmount : grouped.keySet()) {
                BigDecimal difference = amount.subtract(existingAmount);
                if (difference.compareTo(BigDecimal.ZERO) < 0) {
                    difference = difference.negate();
                }
                // Use absolute value of existingAmount for tolerance calculation to handle negative
                // amounts correctly
                final BigDecimal absExistingAmount =
                        existingAmount.compareTo(BigDecimal.ZERO) < 0
                                ? existingAmount.negate()
                                : existingAmount;
                final BigDecimal tolerance = absExistingAmount.multiply(new BigDecimal("0.05"));
                if (difference.compareTo(tolerance) <= 0) {
                    matchingAmount = existingAmount;
                    break;
                }
            }

            if (matchingAmount != null) {
                grouped.get(matchingAmount).add(tx);
            } else {
                grouped.put(amount, new ArrayList<>(List.of(tx)));
            }
        }

        return grouped;
    }

    /**
     * @deprecated Use merchant database instead via isSubscriptionTransaction Checks if merchant is
     *     a known subscription service using merchant database
     */
    @Deprecated
    private boolean isKnownSubscriptionMerchant(
            final String merchantName, final String description) {
        return false;
    }

    /** Infers subscription type from transaction category and merchant */
    // ---- inferSubscriptionType: keyword tables, one per detector ----
    // Each block is matched IFF every keyword check is `contains()` on the
    // lowercase merchant+description string. Order of detectors matters: AI
    // services must run before generic software so "Cursor" / "ChatGPT" don't
    // get bucketed as SOFTWARE.

    private static final String[] STREAMING_KEYWORDS = {
        "netflix", "hulu", "disney", "hbo", "paramount", "peacock", "spotify",
        "apple music", "youtube premium", "youtube tv", "youtube music",
        "youtubemusic", "amazon prime", "prime video", "showtime", "starz",
        "crunchyroll", "funimation"
    };

    private static final String[] AI_SERVICE_KEYWORDS = {
        "openai", "chatgpt", "cursor", "anthropic", "meta ai", "claude"
    };

    private static final String[] SOFTWARE_KEYWORDS = {
        "adobe", "microsoft 365", "office 365", "github", "canva", "grammarly",
        "notion", "evernote"
    };

    private static final String[] NEWS_MEDIA_KEYWORDS = {
        "barrons", "wsj", "wall street journal", "ny times", "new york times",
        "nytimes", "financial times", "ft.com", "moneycontrol", "marketwatch",
        "consumer reports", "health magazine", "the economist", "forbes",
        "time magazine", "the atlantic"
    };

    private static final String[] RETAIL_MEMBERSHIP_KEYWORDS = {
        "costco", "sam's club", "sams club", "bjs", "bj's", "walmart plus",
        "wmt plus", "target circle", "best buy totaltech", "best buy membership"
    };

    private static final String[] PARKING_KEYWORDS = {
        "parking", "spothero", "parkmobile", "parkwhiz"
    };

    private static final String[] FITNESS_KEYWORDS = {
        "gym", "fitness", "health club", "sports club", "planet fitness",
        "equinox", "lifetime fitness", "peloton", "classpass", "orange theory",
        "crossfit", "yoga", "pilates", "barre"
    };

    private static final String[] CLOUD_STORAGE_KEYWORDS = {
        "dropbox", "icloud", "google drive", "google one", "onedrive", "box", "pcloud"
    };

    private static final String[] VPN_KEYWORDS = {
        "nordvpn", "expressvpn", "surfshark"
    };

    private static final String[] RIDESHARE_MEMBERSHIP_KEYWORDS = {
        "lyft pink", "uber one"
    };

    private static boolean anyContains(final String haystack, final String[] needles) {
        for (final String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private String inferSubscriptionType(final TransactionTable tx) {
        final String categoryPrimary = tx.getCategoryPrimary();
        final String categoryDetailed = tx.getCategoryDetailed();
        final String merchant = tx.getMerchantName();
        final String description = tx.getDescription();
        final String combined =
                ((merchant != null ? merchant : "")
                                + " "
                                + (description != null ? description : ""))
                        .toLowerCase(Locale.ROOT);

        String type = detectStreaming(categoryPrimary, categoryDetailed, combined);
        if (type != null) return type;

        type = detectAiService(combined);
        if (type != null) return type;

        type = detectSoftware(categoryPrimary, categoryDetailed, combined);
        if (type != null) return type;

        type = detectNewsMedia(combined);
        if (type != null) return type;

        type = detectRetailMembership(combined);
        if (type != null) return type;

        type = detectInsurance(categoryPrimary, combined);
        if (type != null) return type;

        type = detectParking(combined);
        if (type != null) return type;

        type = detectFitness(categoryPrimary, categoryDetailed, combined);
        if (type != null) return type;

        type = detectCloudStorage(categoryDetailed, combined);
        if (type != null) return type;

        type = detectVpn(combined);
        if (type != null) return type;

        type = detectRideshareMembership(combined);
        if (type != null) return type;

        return "other";
    }

    private String detectStreaming(
            final String categoryPrimary, final String categoryDetailed, final String combined) {
        final boolean categoryMatches =
                "entertainment".equalsIgnoreCase(categoryPrimary)
                        || (categoryDetailed != null
                                && categoryDetailed.toLowerCase(Locale.ROOT).contains(STREAMING));
        if (categoryMatches && anyContains(combined, STREAMING_KEYWORDS)) {
            return STREAMING;
        }
        // YouTube Music is treated as streaming even when category doesn't match
        if (combined.contains("youtube music") || combined.contains("youtubemusic")) {
            return STREAMING;
        }
        return null;
    }

    private String detectAiService(final String combined) {
        return anyContains(combined, AI_SERVICE_KEYWORDS) ? "ai_service" : null;
    }

    private String detectSoftware(
            final String categoryPrimary, final String categoryDetailed, final String combined) {
        final boolean categoryMatches =
                "tech".equalsIgnoreCase(categoryPrimary)
                        || (categoryDetailed != null
                                && (categoryDetailed.toLowerCase(Locale.ROOT).contains(SOFTWARE)
                                        || categoryDetailed
                                                .toLowerCase(Locale.ROOT)
                                                .contains("saas")));
        return categoryMatches && anyContains(combined, SOFTWARE_KEYWORDS) ? SOFTWARE : null;
    }

    private String detectNewsMedia(final String combined) {
        return anyContains(combined, NEWS_MEDIA_KEYWORDS) ? "news_media" : null;
    }

    private String detectRetailMembership(final String combined) {
        return anyContains(combined, RETAIL_MEMBERSHIP_KEYWORDS) ? MEMBERSHIP : null;
    }

    private String detectInsurance(final String categoryPrimary, final String combined) {
        final boolean mentions =
                combined.contains(INSURANCE)
                        || combined.contains("premium")
                        || (categoryPrimary != null
                                && categoryPrimary.toLowerCase(Locale.ROOT).contains(INSURANCE));
        // Insurance is its own subscriptionCategory, not a "type"; explicitly
        // return "other" so downstream code routes it correctly.
        return mentions ? "other" : null;
    }

    private String detectParking(final String combined) {
        return anyContains(combined, PARKING_KEYWORDS) ? MEMBERSHIP : null;
    }

    private String detectFitness(
            final String categoryPrimary, final String categoryDetailed, final String combined) {
        if (anyContains(combined, FITNESS_KEYWORDS)) {
            return MEMBERSHIP;
        }
        // Category-gated subset (kept for compatibility with original logic)
        final boolean healthCategory =
                "health".equalsIgnoreCase(categoryPrimary)
                        || (categoryDetailed != null
                                && categoryDetailed.toLowerCase(Locale.ROOT).contains("fitness"));
        if (healthCategory
                && (combined.contains("gym")
                        || combined.contains("fitness")
                        || combined.contains("peloton")
                        || combined.contains("classpass"))) {
            return MEMBERSHIP;
        }
        return null;
    }

    private String detectCloudStorage(final String categoryDetailed, final String combined) {
        if (categoryDetailed == null
                || !categoryDetailed.toLowerCase(Locale.ROOT).contains("cloud")) {
            return null;
        }
        return anyContains(combined, CLOUD_STORAGE_KEYWORDS) ? "cloud_storage" : null;
    }

    private String detectVpn(final String combined) {
        return anyContains(combined, VPN_KEYWORDS) ? SOFTWARE : null;
    }

    private String detectRideshareMembership(final String combined) {
        return anyContains(combined, RIDESHARE_MEMBERSHIP_KEYWORDS) ? MEMBERSHIP : null;
    }

    /**
     * Determines subscriptionCategory: SUBSCRIPTION vs RECURRING SUBSCRIPTION = merchant-based
     * recurring payments (Netflix, Spotify, gym memberships, software) RECURRING = bills, loans,
     * mortgage, utilities (contract-based or necessary expenses)
     */
    private String determineSubscriptionCategory(
            final TransactionTable tx, final String merchantName, final String subscriptionType) {
        final String categoryPrimary = tx.getCategoryPrimary();
        final String categoryDetailed = tx.getCategoryDetailed();
        final String description = tx.getDescription();
        final BigDecimal amount = tx.getAmount();
        final String combined =
                ((merchantName != null ? merchantName : "")
                                + " "
                                + (description != null ? description : ""))
                        .toLowerCase(Locale.ROOT);

        // 1. Known subscription merchants (Netflix, Spotify, etc.) = SUBSCRIPTION
        if (isKnownSubscriptionMerchant(merchantName, description)) {
            return SUBSCRIPTION;
        }

        // 2. Subscription-related categories = SUBSCRIPTION
        if (SUBSCRIPTIONS.equalsIgnoreCase(categoryDetailed)) {
            return SUBSCRIPTION;
        }
        if (categoryPrimary != null
                && SUBSCRIPTION_CATEGORIES.contains(categoryPrimary.toLowerCase(Locale.ROOT))) {
            // But exclude insurance and loans
            if (!INSURANCE.equalsIgnoreCase(categoryPrimary)
                    && !"loans".equalsIgnoreCase(categoryPrimary)
                    && !"mortgage".equalsIgnoreCase(categoryPrimary)) {
                return SUBSCRIPTION;
            }
        }

        // 3. Subscription type indicates subscription (not "other" for unknown)
        if (subscriptionType != null && !"other".equalsIgnoreCase(subscriptionType)) {
            // If we identified it as streaming, software, membership, cloud_storage, it's a
            // subscription
            return SUBSCRIPTION;
        }

        // 4. Large recurring payments (likely mortgage, loans) = RECURRING
        if (amount != null && amount.abs().compareTo(new BigDecimal("500")) > 0) {
            // Check if it's from a bank/financial institution (likely loan/mortgage)
            if (combined.contains("mortgage")
                    || combined.contains("loan")
                    || combined.contains("auto finance")
                    || combined.contains("car payment")
                    || combined.contains("student loan")
                    || combined.contains("credit card")
                    || categoryPrimary != null
                            && ("loans".equalsIgnoreCase(categoryPrimary)
                                    || "mortgage".equalsIgnoreCase(categoryPrimary)
                                    || "payment".equalsIgnoreCase(categoryPrimary))) {
                return RECURRING;
            }
        }

        // 5. Utilities and bills = RECURRING
        if (categoryPrimary != null
                && ("utilities".equalsIgnoreCase(categoryPrimary)
                        || "bills".equalsIgnoreCase(categoryPrimary)
                        || INSURANCE.equalsIgnoreCase(categoryPrimary))) {
            return RECURRING;
        }

        if (combined.contains("electric")
                || combined.contains("gas")
                || combined.contains("water")
                || combined.contains("sewer")
                || combined.contains("trash")
                || combined.contains("internet")
                || combined.contains("phone")
                || combined.contains("cable")
                || combined.contains(INSURANCE)
                || combined.contains("premium")) {
            return RECURRING;
        }

        // 6. Default: If subscriptionType was identified (not "other"), it's likely a subscription
        // Otherwise, treat as recurring (safer default)
        if (subscriptionType != null && !"other".equalsIgnoreCase(subscriptionType)) {
            return SUBSCRIPTION;
        }

        // Default to RECURRING for unknown patterns (safer than assuming subscription)
        return RECURRING;
    }

    /**
     * Calculates next payment date based on frequency CRITICAL FIX: Helper method to calculate
     * nextPaymentDate (matches Subscription.calculateNextPaymentDate logic)
     */
    private LocalDate calculateNextPaymentDate(
            final LocalDate baseDate, final Subscription.SubscriptionFrequency frequency) {
        if (baseDate == null || frequency == null) {
            return null;
        }

        return switch (frequency) {
            case DAILY -> baseDate.plusDays(1);
            case WEEKLY -> baseDate.plusWeeks(1);
            case BI_WEEKLY -> baseDate.plusWeeks(2);
            case MONTHLY -> baseDate.plusMonths(1);
            case QUARTERLY -> baseDate.plusMonths(3);
            case SEMI_ANNUAL -> baseDate.plusMonths(6);
            case ANNUAL -> baseDate.plusYears(1);
        };
    }

    /** Parses date string to LocalDate */
    private LocalDate parseDate(final String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateString, DATE_FORMATTER);
        } catch (Exception e) {
            LOGGER.debug("Failed to parse date: {}", dateString);
            return null;
        }
    }

    /** Converts Subscription to SubscriptionTable */
    private SubscriptionTable toSubscriptionTable(final Subscription subscription) {
        final SubscriptionTable table = new SubscriptionTable();
        // CRITICAL FIX: Normalize subscription ID to lowercase when saving to match lookup behavior
        final String normalizedId =
                com.budgetbuddy.util.IdGenerator.normalizeUUID(subscription.getSubscriptionId());
        table.setSubscriptionId(normalizedId);
        table.setUserId(subscription.getUserId());
        table.setAccountId(subscription.getAccountId());
        table.setMerchantName(subscription.getMerchantName());
        table.setDescription(subscription.getDescription());
        table.setAmount(subscription.getAmount());
        table.setFrequency(
                subscription.getFrequency() != null ? subscription.getFrequency().name() : null);
        table.setStartDate(
                subscription.getStartDate() != null
                        ? subscription.getStartDate().format(DATE_FORMATTER)
                        : null);
        table.setNextPaymentDate(
                subscription.getNextPaymentDate() != null
                        ? subscription.getNextPaymentDate().format(DATE_FORMATTER)
                        : null);
        table.setLastPaymentDate(
                subscription.getLastPaymentDate() != null
                        ? subscription.getLastPaymentDate().format(DATE_FORMATTER)
                        : null);
        table.setCategory(subscription.getCategory());
        table.setSubscriptionType(subscription.getSubscriptionType());
        table.setSubscriptionCategory(subscription.getSubscriptionCategory());
        table.setOriginalCategoryPrimary(subscription.getOriginalCategoryPrimary());
        table.setOriginalCategoryDetailed(subscription.getOriginalCategoryDetailed());
        table.setActive(subscription.getActive());
        table.setPlaidTransactionId(subscription.getPlaidTransactionId());
        if (subscription.getCreatedAt() != null) {
            table.setCreatedAt(
                    subscription
                            .getCreatedAt()
                            .atZone(java.time.ZoneId.systemDefault())
                            .toInstant());
        } else {
            table.setCreatedAt(java.time.Instant.now());
        }
        table.setUpdatedAt(java.time.Instant.now());
        return table;
    }

    /**
     * Converts SubscriptionTable to Subscription CRITICAL FIX: Returns null if required fields are
     * missing or invalid to prevent null subscriptions in API responses
     */
    private Subscription toSubscription(final SubscriptionTable table) {
        if (table == null) {
            LOGGER.warn("Cannot convert null SubscriptionTable to Subscription");
            return null;
        }

        // CRITICAL: Validate required fields before creating subscription
        if (table.getSubscriptionId() == null || table.getSubscriptionId().isBlank()) {
            LOGGER.warn("SubscriptionTable has null or empty subscriptionId, skipping conversion");
            return null;
        }

        if (table.getStartDate() == null || table.getStartDate().isBlank()) {
            LOGGER.warn(
                    "SubscriptionTable {} has null or empty startDate, skipping conversion",
                    table.getSubscriptionId());
            return null;
        }

        if (table.getNextPaymentDate() == null || table.getNextPaymentDate().isBlank()) {
            LOGGER.warn(
                    "SubscriptionTable {} has null or empty nextPaymentDate, skipping conversion",
                    table.getSubscriptionId());
            return null;
        }

        final LocalDate startDate = parseDate(table.getStartDate());
        final LocalDate nextPaymentDate = parseDate(table.getNextPaymentDate());

        if (startDate == null) {
            LOGGER.warn(
                    "SubscriptionTable {} has invalid startDate format: {}, skipping conversion",
                    table.getSubscriptionId(),
                    table.getStartDate());
            return null;
        }

        if (nextPaymentDate == null) {
            LOGGER.warn(
                    "SubscriptionTable {} has invalid nextPaymentDate format: {}, skipping conversion",
                    table.getSubscriptionId(),
                    table.getNextPaymentDate());
            return null;
        }

        // Validate frequency
        Subscription.SubscriptionFrequency frequency = null;
        if (table.getFrequency() != null && !table.getFrequency().isBlank()) {
            try {
                frequency = Subscription.SubscriptionFrequency.valueOf(table.getFrequency());
            } catch (IllegalArgumentException e) {
                LOGGER.warn(
                        "SubscriptionTable {} has invalid frequency: {}, skipping conversion",
                        table.getSubscriptionId(),
                        table.getFrequency());
                return null;
            }
        } else {
            LOGGER.warn(
                    "SubscriptionTable {} has null or empty frequency, skipping conversion",
                    table.getSubscriptionId());
            return null;
        }

        // All required fields validated, create subscription
        try {
            final Subscription subscription = new Subscription();
            subscription.setSubscriptionId(table.getSubscriptionId());
            subscription.setUserId(table.getUserId());
            subscription.setAccountId(table.getAccountId());
            subscription.setMerchantName(table.getMerchantName());
            subscription.setDescription(table.getDescription());
            subscription.setAmount(table.getAmount());
            subscription.setFrequency(frequency);
            subscription.setStartDate(startDate);
            subscription.setNextPaymentDate(nextPaymentDate);
            subscription.setLastPaymentDate(
                    parseDate(table.getLastPaymentDate())); // Optional field
            subscription.setCategory(table.getCategory());
            subscription.setSubscriptionType(table.getSubscriptionType());
            subscription.setSubscriptionCategory(
                    table.getSubscriptionCategory()); // May be null for existing records
            subscription.setOriginalCategoryPrimary(table.getOriginalCategoryPrimary());
            subscription.setOriginalCategoryDetailed(table.getOriginalCategoryDetailed());
            subscription.setActive(
                    table.getActive() != null
                            ? table.getActive()
                            : true); // Default to true if null
            subscription.setPlaidTransactionId(table.getPlaidTransactionId());

            if (table.getCreatedAt() != null) {
                subscription.setCreatedAt(
                        java.time.LocalDateTime.ofInstant(
                                table.getCreatedAt(), java.time.ZoneId.systemDefault()));
            }
            if (table.getUpdatedAt() != null) {
                subscription.setUpdatedAt(
                        java.time.LocalDateTime.ofInstant(
                                table.getUpdatedAt(), java.time.ZoneId.systemDefault()));
            }

            return subscription;
        } catch (Exception e) {
            LOGGER.error(
                    "Error converting SubscriptionTable {} to Subscription: {}",
                    table.getSubscriptionId(),
                    e.getMessage(),
                    e);
            return null;
        }
    }
}
