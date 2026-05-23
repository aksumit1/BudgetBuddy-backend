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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final String MORTGAGE = "mortgage";
    private static final String OTHER = "other";
    private static final String TRANSFER = "transfer";

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

    // HARD EXCLUSIONS — categories that are RECURRING (often monthly) but are
    // NOT subscriptions in any user-facing sense (you can't cancel,
    // downgrade, or substitute them). Detector skips these before any
    // inclusion logic runs. Kept intentionally narrow: a category like
    // "transportation" or "travel" can legitimately host a subscription
    // (parking pass, transit pass, hotel-loyalty fee), so we filter on
    // recurrence STRUCTURE — cadence + occurrence count + amount stability
    // — rather than blanket-banning by category.
    private static final Set<String> NON_SUBSCRIPTION_PRIMARY =
            Set.of(
                    "loan_payments",
                    "transfer_in",
                    "transfer_out",
                    TRANSFER,
                    "bank_fees",
                    "investment", // CD deposits, brokerage contributions etc. are
                    "investments", // recurring but not subscriptions
                    "income" // payroll / rent income re-categorised loosely
                    );

    private static final Set<String> NON_SUBSCRIPTION_DETAILED_TOKENS =
            Set.of(
                    "credit_card_payment",
                    "loan_payment",
                    MORTGAGE,
                    "student_loan",
                    "auto_payment",
                    "personal_loan",
                    TRANSFER,
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
                    MORTGAGE,
                    TRANSFER,
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
            if (primaryLower.contains(TRANSFER) || primaryLower.contains("loan_payment")) {
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
    /**
     * Per-user cooldown for /detect. The endpoint runs a full transaction
     * scan, frequency detection, and consolidation — it's NOT cheap. A
     * chatty client hitting it 5x per second would pin CPU. The cooldown
     * returns the cached result instead. 60s is short enough that new
     * imports become visible quickly; long enough that screen-refresh
     * loops don't cause harm.
     *
     * <p>Capacity is bounded to avoid an unbounded heap when many users
     * cycle through (e.g. integration tests, churn): a synchronizedMap
     * around a LinkedHashMap in access-order evicts the LRU entry once
     * the cap is reached. Cap of 10_000 = ~1MB of references at one
     * Subscription per slot, fine for one pod.
     */
    private static final long DETECT_COOLDOWN_MS = 60_000;
    private static final int DETECT_CACHE_CAPACITY = 10_000;
    private final java.util.Map<String, DetectCacheEntry> detectionCache =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, DetectCacheEntry>(
                            16, 0.75f, /*accessOrder=*/true) {
                        private static final long serialVersionUID = 1L;
                        @Override
                        protected boolean removeEldestEntry(
                                final java.util.Map.Entry<String, DetectCacheEntry> eldest) {
                            return size() > DETECT_CACHE_CAPACITY;
                        }
                    });

    private static final class DetectCacheEntry {
        final long expiresAt;
        final List<Subscription> result;
        DetectCacheEntry(final long expiresAt, final List<Subscription> result) {
            this.expiresAt = expiresAt;
            this.result = result;
        }
    }

    public List<Subscription> detectSubscriptions(final String userId) {
        final DetectCacheEntry hit = detectionCache.get(userId);
        final long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt > now) {
            LOGGER.debug("Subscription detection cooldown hit for user {} (cached {}ms ago)",
                    userId, DETECT_COOLDOWN_MS - (hit.expiresAt - now));
            return hit.result;
        }
        LOGGER.info("Detecting subscriptions for user: {}", userId);

        // Get all expense transactions. The 10_000 cap is a safety brake
        // — for any user who's hit it, the detection set is incomplete and
        // we WARN so the accuracy regression is visible. The right fix is
        // pagination + streaming, tracked separately. For now: surface the
        // truncation so on-call doesn't debug "why didn't my Hulu show up"
        // for two days before noticing the cap.
        final List<TransactionTable> rawTransactions =
                transactionRepository.findByUserId(userId, 0, 10_000);
        if (rawTransactions.size() >= 10_000) {
            LOGGER.warn(
                    "Subscription detection truncated: user={} has 10_000+ transactions and we read only the first page — detection accuracy will be incomplete. Implement pagination.",
                    userId);
        }
        final List<TransactionTable> allExpenses =
                rawTransactions.stream()
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

                                    // (the old "Known subscription merchant" check was deleted
                                    // — its implementation was a hardcoded `return false` stub
                                    // marked deprecated. The real "known subscription" signal
                                    // now comes from L3_MERCHANT_DB during cascade
                                    // categorisation, which writes `categoryPrimary=subscriptions`
                                    // for these merchants, caught by checks 1+2 above.)

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

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Pre-filtered {} subscription candidates from {} total expenses",
                    subscriptionCandidates.size(),
                    allExpenses.size());
        }

        // PATTERN-BASED PASS — augment the keyword-based candidates above with
        // transactions whose CADENCE + AMOUNT STABILITY look like a subscription
        // even though the merchant isn't in any known-subscription list. The
        // downstream frequency detector + amount-grouping filter quality, so
        // anything that survives is structurally subscription-shaped.
        //
        // Rationale: a brand-new subscription (e.g. Anthropic, Cursor, a niche
        // newsletter) gets the right charges on the right cadence long before
        // it shows up in our hand-curated brand list. Pattern + cadence catch
        // those without us having to add the name first.
        final java.util.Set<String> existingIds = new java.util.HashSet<>();
        for (final TransactionTable tx : subscriptionCandidates) {
            existingIds.add(tx.getTransactionId());
        }
        final List<TransactionTable> patternCandidates =
                findPatternBasedSubscriptionCandidates(allExpenses, existingIds);
        if (!patternCandidates.isEmpty()) {
            subscriptionCandidates.addAll(patternCandidates);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Pattern-based pass added {} candidates "
                                + "(total candidates now {} from {} expenses)",
                        patternCandidates.size(),
                        subscriptionCandidates.size(),
                        allExpenses.size());
            }
        }

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
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Skipping merchant '{}': only {} transactions (need at least 2)",
                            merchant,
                            merchantTransactions.size());
                }
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
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Skipping amount group {} for merchant '{}': only {} transactions",
                                amount,
                                merchant,
                                sameAmountTransactions.size());
                    }
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

                // Cadence-aware minimum-occurrence gate. A "subscription" must
                // have repeated long enough on its own cadence to be a real
                // pattern, not a coincidence. Thresholds:
                //   DAILY      → 35+ occurrences  (>5 weeks of daily charges)
                //   WEEKLY     → 6+  occurrences  (~6 weeks)
                //   BI_WEEKLY  → 4+  occurrences  (~2 months)
                //   MONTHLY    → 4+  occurrences  (~4 months)
                //   QUARTERLY  → 3+  occurrences  (~9 months)
                //   SEMI_ANNUAL→ 2+  occurrences  (~1 year)
                //   ANNUAL     → 2+  occurrences  (2 years)
                // Below threshold means the cadence might exist but isn't
                // established yet — skip rather than mis-flag everyday
                // recurring spend (weekly groceries, daily commute parking).
                if (frequency != null
                        && !meetsMinOccurrenceThreshold(frequency, sameAmountTransactions.size())) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Skipping merchant '{}' amount {}: frequency {} requires more occurrences than {}",
                                merchant,
                                amount,
                                frequency,
                                sameAmountTransactions.size());
                    }
                    continue;
                }

                // Date-regularity gate: the SAME date/day/month must repeat
                // for the detected cadence — otherwise an irregular gas-pump
                // pattern can satisfy the count threshold without being a
                // structured subscription.
                if (frequency != null
                        && !hasRegularDatePattern(frequency, sameAmountTransactions)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Skipping merchant '{}' amount {}: frequency {} occurrences are not date-regular",
                                merchant,
                                amount,
                                frequency);
                    }
                    continue;
                }

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
                        if (LOGGER.isInfoEnabled()) {
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
        }

        // POST-PROCESSING: apply the three remaining rules that operate
        // across amount-groups of the same merchant, not within one group:
        //   1. Variable-but-bounded: same merchant, 2 amount-groups within
        //      1.5× of each other → ONE subscription, history captures the
        //      older price (Walmart+ $14.27 → $14.28).
        //   2. Price-change chain: same merchant, same cadence, two
        //      non-overlapping date ranges → ONE subscription, current
        //      price is the most recent (Republic Services $116.59 →
        //      $106.59).
        //   3. Usage-billed exclusion: 3+ distinct same-cadence amounts
        //      means this isn't a fixed subscription — drop the entries
        //      (Bird scooter, Canteen Vending, Xfinity Mobile when usage-
        //      driven).
        // Done as a post-pass so the existing per-amount-group detection
        // loop above stays untouched.
        final List<Subscription> consolidated =
                consolidateMultiPriceSubscriptions(detectedSubscriptions);

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Detected {} subscriptions for user: {} (consolidated from {} amount-group entries)",
                    consolidated.size(),
                    userId,
                    detectedSubscriptions.size());
        }
        // Cache for the per-user cooldown window.
        detectionCache.put(userId,
                new DetectCacheEntry(System.currentTimeMillis() + DETECT_COOLDOWN_MS, consolidated));
        return consolidated;
    }

    // ------------------------------------------------------------------
    // Multi-price consolidation: applies merchant aliases, variable-bounded
    // collapsing, price-change merging, and the "3+ prices = usage" drop.
    // ------------------------------------------------------------------

    /**
     * Maximum max/min amount ratio for which we still call a merchant's
     * recurring spend "the same subscription with small variation"
     * (e.g. cell bills that drift month-to-month). Above this we treat the
     * merchant as variable-priced usage and exclude.
     */
    private static final double VARIABLE_SPREAD_LIMIT = 1.5;

    /**
     * Lowercase substring patterns of merchants that should NEVER appear
     * as a subscription, no matter how regular their cadence. Loaded
     * lazily from {@code non-subscription-merchants.yaml} on the classpath.
     * See that file for entries + reasoning.
     */
    private static final java.util.List<String> NON_SUBSCRIPTION_MERCHANT_PATTERNS =
            loadNonSubscriptionMerchantPatterns();

    @SuppressWarnings("unchecked")
    private static java.util.List<String> loadNonSubscriptionMerchantPatterns() {
        final java.util.List<String> out = new java.util.ArrayList<>();
        try (java.io.InputStream in =
                SubscriptionService.class.getResourceAsStream("/non-subscription-merchants.yaml")) {
            if (in == null) {
                // Absent file = "no allowlist configured", which is a valid
                // state (the filter is opt-in). Distinct from a malformed
                // file, which is a bug we want to surface loudly.
                LOGGER.info("non-subscription-merchants.yaml not on classpath; allowlist disabled");
                return out;
            }
            final Object root = new org.yaml.snakeyaml.Yaml().load(in);
            if (!(root instanceof java.util.Map)) {
                throw new IllegalStateException(
                        "non-subscription-merchants.yaml: top-level must be a mapping, got "
                                + (root == null ? "null" : root.getClass().getSimpleName()));
            }
            final Object drops = ((java.util.Map<String, Object>) root).get("drops");
            if (!(drops instanceof java.util.List)) {
                throw new IllegalStateException(
                        "non-subscription-merchants.yaml: 'drops' key must be a list");
            }
            for (final Object dropObj : (java.util.List<Object>) drops) {
                if (!(dropObj instanceof java.util.Map)) continue;
                final Object pats = ((java.util.Map<String, Object>) dropObj).get("patterns");
                if (!(pats instanceof java.util.List)) continue;
                for (final Object p : (java.util.List<Object>) pats) {
                    if (p == null) continue;
                    out.add(p.toString().toLowerCase(java.util.Locale.ROOT));
                }
            }
            LOGGER.info(
                    "non-subscription-merchants.yaml: loaded {} drop patterns",
                    out.size());
        } catch (java.io.IOException ex) {
            // I/O errors during file read are non-fatal — log and continue
            // with empty allowlist.
            LOGGER.error(
                    "I/O error reading non-subscription-merchants.yaml: {}", ex.getMessage());
        }
        // Note: parse errors (the IllegalStateExceptions above) propagate
        // and abort startup. That's intentional — a typo in the YAML
        // would otherwise silently disable the filter and let bad
        // subscriptions ship.
        return out;
    }

    private boolean isNonSubscriptionMerchant(final String merchantName) {
        if (merchantName == null || NON_SUBSCRIPTION_MERCHANT_PATTERNS.isEmpty()) {
            return false;
        }
        final String lower = merchantName.toLowerCase(java.util.Locale.ROOT);
        for (final String p : NON_SUBSCRIPTION_MERCHANT_PATTERNS) {
            if (lower.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merchant alias map loaded from {@code merchant-aliases.yaml} on the
     * classpath. Externalized so ops can extend / fix without code change
     * or re-deploy. {@link LinkedHashMap} preserves insertion order so
     * the YAML can place longest-prefix entries first to win
     * startsWith() against shorter overlaps.
     */
    private static final Map<String, String> MERCHANT_ALIASES = loadMerchantAliases();

    @SuppressWarnings("unchecked")
    private static Map<String, String> loadMerchantAliases() {
        final java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        try (java.io.InputStream in =
                SubscriptionService.class.getResourceAsStream("/merchant-aliases.yaml")) {
            if (in == null) {
                LOGGER.info("merchant-aliases.yaml not on classpath; alias map empty");
                return out;
            }
            final Object root = new org.yaml.snakeyaml.Yaml().load(in);
            if (!(root instanceof Map)) {
                throw new IllegalStateException(
                        "merchant-aliases.yaml: top-level must be a mapping, got "
                                + (root == null ? "null" : root.getClass().getSimpleName()));
            }
            final Object aliases = ((Map<String, Object>) root).get("aliases");
            if (!(aliases instanceof java.util.List)) {
                throw new IllegalStateException(
                        "merchant-aliases.yaml: 'aliases' key must be a list");
            }
            for (final Object groupObj : (java.util.List<Object>) aliases) {
                if (!(groupObj instanceof Map)) continue;
                final Map<String, Object> group = (Map<String, Object>) groupObj;
                final Object canonical = group.get("canonical");
                final Object prefixes = group.get("prefixes");
                if (!(canonical instanceof String) || !(prefixes instanceof java.util.List)) continue;
                for (final Object pre : (java.util.List<Object>) prefixes) {
                    if (pre == null) continue;
                    out.put(pre.toString().toLowerCase(Locale.ROOT), canonical.toString());
                }
            }
            LOGGER.info(
                    "merchant-aliases.yaml: loaded {} prefix mappings", out.size());
        } catch (java.io.IOException ex) {
            LOGGER.error("I/O error reading merchant-aliases.yaml: {}", ex.getMessage());
        }
        // Parse errors propagate and abort startup — a typo here would
        // silently break the price-change-merge consolidation otherwise.
        return out;
    }

    private String canonicalMerchantKey(final String rawName) {
        if (rawName == null) {
            return "";
        }
        final String lower = rawName.toLowerCase(Locale.ROOT).trim();
        for (final Map.Entry<String, String> entry : MERCHANT_ALIASES.entrySet()) {
            if (lower.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return lower;
    }

    private List<Subscription> consolidateMultiPriceSubscriptions(
            final List<Subscription> raw) {
        if (raw == null) {
            return new ArrayList<>();
        }
        // Phase 0 — drop allowlisted non-subscription merchants (tax
        // filings, CC service fees, toll-account refills, etc.). Runs
        // even on size==1 input so a single annual tax payment doesn't
        // slip through.
        final List<Subscription> filtered = new ArrayList<>(raw.size());
        for (final Subscription s : raw) {
            if (isNonSubscriptionMerchant(s.getMerchantName())) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Subscription dropped (non-subscription-merchants allowlist): merchant='{}'",
                            s.getMerchantName());
                }
                continue;
            }
            filtered.add(s);
        }
        if (filtered.size() < 2) {
            return filtered;
        }

        // Group by canonical merchant + frequency
        final Map<String, List<Subscription>> byMerchantCadence = new HashMap<>();
        for (final Subscription s : filtered) {
            final String key =
                    canonicalMerchantKey(s.getMerchantName())
                            + "|"
                            + (s.getFrequency() == null ? "" : s.getFrequency().name());
            byMerchantCadence.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        final List<Subscription> result = new ArrayList<>();
        for (final List<Subscription> group : byMerchantCadence.values()) {
            if (group.size() == 1) {
                result.add(group.get(0));
                continue;
            }
            // Distinct amounts on this merchant + cadence
            final List<BigDecimal> distinctAmounts = group.stream()
                    .map(Subscription::getAmount)
                    .filter(a -> a != null)
                    .map(BigDecimal::abs)
                    .distinct()
                    .sorted()
                    .collect(Collectors.toList());

            // Compute amount spread (max/min ratio) — bounded variation
            // distinguishes a real subscription with monthly drift (a cell
            // bill at $172–$230) from per-use spend (Bird scooter rides at
            // $2–$11). VARIABLE_SPREAD_LIMIT separates the two.
            final BigDecimal min = distinctAmounts.get(0);
            final BigDecimal max = distinctAmounts.get(distinctAmounts.size() - 1);
            final double spread =
                    min.signum() > 0
                            ? max.divide(min, 4, java.math.RoundingMode.HALF_UP).doubleValue()
                            : Double.MAX_VALUE;

            // Usage-billed drop: 3+ distinct prices AND spread too wide.
            // A cell bill with 3 prices in a tight 1.34× band is still ONE
            // subscription. Dropping based on count alone was wrong.
            if (distinctAmounts.size() >= 3 && spread > VARIABLE_SPREAD_LIMIT) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Subscription dropped (variable usage billing): merchant='{}' had {} distinct prices, spread {}× exceeds {}×",
                            group.get(0).getMerchantName(),
                            distinctAmounts.size(),
                            String.format("%.2f", spread),
                            VARIABLE_SPREAD_LIMIT);
                }
                continue;
            }

            // Sort by start date (oldest → newest)
            group.sort((a, b) -> {
                if (a.getStartDate() == null && b.getStartDate() == null) return 0;
                if (a.getStartDate() == null) return 1;
                if (b.getStartDate() == null) return -1;
                return a.getStartDate().compareTo(b.getStartDate());
            });

            // Rule 1 / 2: collapse same-cadence groups into ONE subscription
            // whose `amount` is the latest price. Older amounts go into the
            // structured `priceHistory` list — iOS can render a chart
            // without parsing free-text. The description still gets the
            // human-readable summary for backward compat with the existing
            // UI surface; can be removed once the iOS migration ships.
            final Subscription latest = group.get(group.size() - 1);
            final java.util.List<Subscription.PriceHistoryEntry> history = new java.util.ArrayList<>();
            for (int i = 0; i < group.size() - 1; i++) {
                final Subscription older = group.get(i);
                if (older.getAmount() != null
                        && older.getAmount().compareTo(latest.getAmount()) != 0) {
                    history.add(new Subscription.PriceHistoryEntry(
                            older.getAmount(),
                            older.getLastPaymentDate() != null
                                    ? older.getLastPaymentDate()
                                    : older.getStartDate()));
                }
            }
            if (!history.isEmpty()) {
                latest.setPriceHistory(history);
                final String historyNote =
                        "price-change: "
                                + history.stream()
                                        .map(Subscription.PriceHistoryEntry::getAmount)
                                        .map(BigDecimal::abs)
                                        .map(b -> "$" + b.toPlainString())
                                        .collect(Collectors.joining(" → "))
                                + " → $"
                                + latest.getAmount().abs().toPlainString();
                final String desc = latest.getDescription();
                latest.setDescription(
                        (desc == null || desc.isBlank()) ? historyNote : desc + " | " + historyNote);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Merged price-change subscription: merchant='{}' frequency={} {}",
                            latest.getMerchantName(),
                            latest.getFrequency(),
                            historyNote);
                }

                // PREDICTED NEXT AMOUNT for variable subs. Median of all
                // observed amounts (history + current) is robust against
                // outliers — a single high overage month won't shift the
                // prediction. iOS shows this as "expected: $X".
                final java.util.List<BigDecimal> allAmounts = new java.util.ArrayList<>();
                for (final Subscription.PriceHistoryEntry h : history) {
                    if (h.getAmount() != null) allAmounts.add(h.getAmount().abs());
                }
                if (latest.getAmount() != null) allAmounts.add(latest.getAmount().abs());
                if (allAmounts.size() >= 2) {
                    java.util.Collections.sort(allAmounts);
                    final BigDecimal median;
                    final int mid = allAmounts.size() / 2;
                    if (allAmounts.size() % 2 == 1) {
                        median = allAmounts.get(mid);
                    } else {
                        median = allAmounts.get(mid - 1).add(allAmounts.get(mid))
                                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);
                    }
                    latest.setPredictedNextAmount(median);
                }
            }
            result.add(latest);
        }
        return result;
    }

    /**
     * Pattern-based candidate finder. Identifies expense transactions whose
     * <strong>cadence</strong> and <strong>amount stability</strong> structurally
     * look like a subscription, even when the merchant name isn't on any
     * known-subscription list.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Group expenses by normalized merchant key.
     *   <li>Within each merchant, group by amount bucket (rounded to whole
     *       dollar — wiggles from taxes/proration collapse together).
     *   <li>For each (merchant, amount) cohort with ≥3 transactions, measure
     *       the gap (in days) between consecutive charges.
     *   <li>If the gaps cluster around a recognised cadence (14, 30, 90, 365
     *       days ±5), and the per-charge amounts are within ±10% of the mean,
     *       the cohort qualifies.
     * </ol>
     *
     * <p>Only transactions NOT already in {@code existingIds} are returned, so
     * this augments the keyword-based pass without duplicates.
     */
    private List<TransactionTable> findPatternBasedSubscriptionCandidates(
            final List<TransactionTable> allExpenses,
            final java.util.Set<String> existingIds) {
        final Map<String, List<TransactionTable>> byMerchant = new HashMap<>();
        for (final TransactionTable tx : allExpenses) {
            // Exclude transfers / loan payments / card payments — those are
            // recurring by nature but not subscriptions.
            if (isNonSubscriptionRecurringMovement(
                    tx.getCategoryPrimary(),
                    tx.getCategoryDetailed(),
                    tx.getMerchantName(),
                    tx.getDescription())) {
                continue;
            }
            final String key = patternKeyForTransaction(tx);
            if (key == null) {
                continue;
            }
            byMerchant.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        final List<TransactionTable> qualified = new ArrayList<>();
        for (final Map.Entry<String, List<TransactionTable>> entry : byMerchant.entrySet()) {
            final List<TransactionTable> txs = entry.getValue();
            if (txs.size() < 3) {
                continue;
            }
            // Bucket by integer dollar amount
            final Map<Long, List<TransactionTable>> byAmt = new HashMap<>();
            for (final TransactionTable tx : txs) {
                if (tx.getAmount() == null) {
                    continue;
                }
                final long bucket = tx.getAmount().abs().setScale(0, RoundingMode.HALF_UP).longValueExact();
                byAmt.computeIfAbsent(bucket, k -> new ArrayList<>()).add(tx);
            }
            for (final List<TransactionTable> cohort : byAmt.values()) {
                if (cohort.size() < 3) {
                    continue;
                }
                if (cohortMatchesSubscriptionCadence(cohort)) {
                    for (final TransactionTable tx : cohort) {
                        if (existingIds.add(tx.getTransactionId())) {
                            qualified.add(tx);
                        }
                    }
                }
            }
        }
        return qualified;
    }

    /** Normalize a transaction's merchant for pattern grouping. */
    private String patternKeyForTransaction(final TransactionTable tx) {
        final String merchant = tx.getMerchantName();
        if (merchant != null && !merchant.isBlank()) {
            return merchant.trim().toLowerCase(Locale.ROOT);
        }
        final String desc = tx.getDescription();
        if (desc != null && !desc.isBlank()) {
            // Strip trailing digits / store-id noise; keep first 30 chars
            String norm =
                    desc.trim()
                            .toLowerCase(Locale.ROOT)
                            .replaceAll("\\b\\d+\\b", "")
                            .replaceAll("\\s+", " ")
                            .trim();
            if (norm.length() > 30) {
                norm = norm.substring(0, 30);
            }
            return norm;
        }
        return null;
    }

    /**
     * True iff the cohort's per-charge gaps cluster around a known subscription
     * cadence (14d, 30d, 90d, 365d ±5d tolerance) AND amounts are within ±10%
     * of mean. Cohort must be size ≥3.
     */
    private boolean cohortMatchesSubscriptionCadence(final List<TransactionTable> cohort) {
        if (cohort.size() < 3) {
            return false;
        }
        // Sort by date
        final List<TransactionTable> sorted = new ArrayList<>(cohort);
        sorted.sort(
                Comparator.comparing(
                        t -> parseDate(t.getTransactionDate()),
                        Comparator.nullsLast(Comparator.naturalOrder())));
        // Compute gaps
        final List<Long> gaps = new ArrayList<>();
        LocalDate prev = null;
        for (final TransactionTable tx : sorted) {
            final LocalDate d = parseDate(tx.getTransactionDate());
            if (d == null) {
                continue;
            }
            if (prev != null) {
                gaps.add(ChronoUnit.DAYS.between(prev, d));
            }
            prev = d;
        }
        if (gaps.size() < 2) {
            return false;
        }
        final double avgGap = gaps.stream().mapToLong(Long::longValue).average().orElse(0);
        // Recognised cadences
        final int[] cadences = {7, 14, 15, 30, 31, 60, 90, 180, 365};
        boolean cadenceOk = false;
        for (final int c : cadences) {
            if (Math.abs(avgGap - c) <= 5) {
                cadenceOk = true;
                break;
            }
        }
        if (!cadenceOk) {
            return false;
        }
        // Amount stability within ±10% of mean
        final List<BigDecimal> amts = new ArrayList<>();
        for (final TransactionTable tx : sorted) {
            if (tx.getAmount() != null) {
                amts.add(tx.getAmount().abs());
            }
        }
        if (amts.isEmpty()) {
            return false;
        }
        final double mean = amts.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        if (mean < 0.01) {
            return false;
        }
        final double maxDev = amts.stream()
                .mapToDouble(a -> Math.abs(a.doubleValue() - mean) / mean)
                .max()
                .orElse(0);
        return maxDev <= 0.10;
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
            // Detection re-runs (manual rescans, hourly sync) hand us a fresh Subscription model
            // whose createdAt is null even when an existing row already lives in DynamoDB. If we
            // let toSubscriptionTable fall through to its `Instant.now()` branch the original
            // creation timestamp gets clobbered on every re-detection — audit trails break and
            // "discovered N days ago" UI lies. Reload the existing row first and graft its
            // createdAt onto the incoming model.
            if (subscription.getCreatedAt() == null && subscription.getSubscriptionId() != null) {
                subscriptionRepository
                        .findById(subscription.getSubscriptionId())
                        .map(SubscriptionTable::getCreatedAt)
                        .filter(java.util.Objects::nonNull)
                        .ifPresent(
                                existing ->
                                        subscription.setCreatedAt(
                                                java.time.LocalDateTime.ofInstant(
                                                        existing,
                                                        java.time.ZoneId.systemDefault())));
            }
            final SubscriptionTable table = toSubscriptionTable(subscription);
            subscriptionRepository.save(table);
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Saved {} subscriptions for user: {}", subscriptions.size(), userId);
        }

        // Flow 5 / O9 — if a user has detected subscriptions but no Subscriptions budget,
        // seed one from the sum of detected monthly-equivalent amounts (with a 10% cushion
        // for price changes). One-shot: we only auto-seed when no budget exists. If the
        // user later edits or deletes it, we won't re-create it.
        try {
            if (subscriptionSeeder != null && !subscriptions.isEmpty()) {
                subscriptionSeeder.seedSubscriptionsBudgetIfMissing(userId, subscriptions);
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to seed Subscriptions budget for user {}: {}",
                        userId,
                        e.getMessage());
            }
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

    /**
     * Telemetry: when a user deletes a subscription that the detector
     * created, that's a vote of "false positive". Count both total
     * deletes and per-merchant deletes so detection-accuracy regressions
     * are visible. Read via {@link #getDetectionTelemetry()}.
     */
    private final java.util.concurrent.atomic.AtomicLong totalDeletes =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>
            deletesByMerchant = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.Map<String, Long> getDetectionTelemetry() {
        final java.util.Map<String, Long> m = new java.util.LinkedHashMap<>();
        m.put("subscriptions.deletes.total", totalDeletes.get());
        deletesByMerchant.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .limit(10)
                .forEach(e -> m.put("subscriptions.deletes.byMerchant[" + e.getKey() + "]",
                        e.getValue().get()));
        return m;
    }

    /** Deletes a subscription */
    public void deleteSubscription(final String subscriptionId) {
        // Record telemetry BEFORE delete so we can capture the merchant name
        // even on retry / re-delete paths.
        try {
            final SubscriptionTable t = subscriptionRepository.findById(subscriptionId).orElse(null);
            if (t != null && t.getMerchantName() != null) {
                deletesByMerchant
                        .computeIfAbsent(
                                t.getMerchantName().toLowerCase(Locale.ROOT),
                                k -> new java.util.concurrent.atomic.AtomicLong())
                        .incrementAndGet();
            }
        } catch (Exception ignore) {
            // Telemetry is best-effort; never fail a delete because of it.
        }
        totalDeletes.incrementAndGet();
        subscriptionRepository.delete(subscriptionId);
        LOGGER.info("Deleted subscription: {} (total-deletes={})",
                subscriptionId, totalDeletes.get());
    }

    /**
     * Minimum-occurrence thresholds per cadence to qualify as a subscription.
     * A pattern below threshold is "could be recurring" but not yet enough
     * evidence to flag — keeps everyday spend (weekly groceries, daily
     * parking) out of the user's subscription list.
     */
    private boolean meetsMinOccurrenceThreshold(
            final Subscription.SubscriptionFrequency frequency, final int count) {
        if (frequency == null) {
            return false;
        }
        switch (frequency) {
            case DAILY:
                return count >= 35;
            case WEEKLY:
                return count >= 6;
            case BI_WEEKLY:
                return count >= 4;
            case MONTHLY:
                return count >= 4;
            case QUARTERLY:
                return count >= 3;
            case SEMI_ANNUAL:
            case ANNUAL:
                return count >= 2;
            default:
                return count >= 4;
        }
    }

    /**
     * Verifies that occurrences land on the SAME date / day-of-week /
     * day-of-month / month-of-year for their cadence — the structural
     * stability that distinguishes a subscription from a coincidentally
     * recurring expense.
     *
     * <ul>
     *   <li>DAILY → no extra check (the daily gap from {@link #detectFrequency} already establishes it)
     *   <li>WEEKLY / BI_WEEKLY → same day-of-week for &ge;80% of occurrences
     *   <li>MONTHLY / QUARTERLY / SEMI_ANNUAL → same day-of-month (±2 day window) for &ge;80%
     *   <li>ANNUAL → same month-of-year (±1 month window) for &ge;80%
     * </ul>
     */
    private boolean hasRegularDatePattern(
            final Subscription.SubscriptionFrequency frequency,
            final List<TransactionTable> transactions) {
        if (frequency == null || transactions == null || transactions.size() < 2) {
            return false;
        }
        final List<LocalDate> dates =
                transactions.stream()
                        .map(tx -> parseDate(tx.getTransactionDate()))
                        .filter(d -> d != null)
                        .sorted()
                        .collect(Collectors.toList());
        if (dates.size() < 2) {
            return false;
        }
        final int total = dates.size();
        final double minMatchRatio = 0.8;

        switch (frequency) {
            case DAILY:
                return true; // daily cadence implicit in gap detection
            case WEEKLY:
            case BI_WEEKLY: {
                final Map<java.time.DayOfWeek, Long> counts =
                        dates.stream()
                                .collect(
                                        Collectors.groupingBy(
                                                LocalDate::getDayOfWeek, Collectors.counting()));
                final long maxOnSameDayOfWeek =
                        counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
                return (double) maxOnSameDayOfWeek / total >= minMatchRatio;
            }
            case MONTHLY:
            case QUARTERLY:
            case SEMI_ANNUAL: {
                int bestMatchCount = 0;
                for (final LocalDate anchor : dates) {
                    final int anchorDom = anchor.getDayOfMonth();
                    int near = 0;
                    for (final LocalDate d : dates) {
                        if (Math.abs(d.getDayOfMonth() - anchorDom) <= 2) {
                            near++;
                        }
                    }
                    bestMatchCount = Math.max(bestMatchCount, near);
                }
                return (double) bestMatchCount / total >= minMatchRatio;
            }
            case ANNUAL: {
                int bestMatchCount = 0;
                for (final LocalDate anchor : dates) {
                    final int anchorMonth = anchor.getMonthValue();
                    int near = 0;
                    for (final LocalDate d : dates) {
                        final int diff = Math.abs(d.getMonthValue() - anchorMonth);
                        // wrap-around (Dec/Jan)
                        if (diff <= 1 || diff >= 11) {
                            near++;
                        }
                    }
                    bestMatchCount = Math.max(bestMatchCount, near);
                }
                return (double) bestMatchCount / total >= minMatchRatio;
            }
            default:
                return true;
        }
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

        // MEDIAN gap between transactions, not mean. One missed billing
        // cycle (a 60-day gap in an otherwise 30-day series) would skew
        // the mean upward by 6+ days and kick a real MONTHLY into the
        // QUARTERLY-or-no-match band. The median is unaffected by single
        // outliers, which is exactly the right behavior for "is this a
        // regular cadence?".
        final java.util.List<Long> gaps = new java.util.ArrayList<>(dates.size());
        for (int i = 1; i < dates.size(); i++) {
            gaps.add(java.time.temporal.ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i)));
        }
        if (gaps.isEmpty()) {
            return null;
        }
        java.util.Collections.sort(gaps);
        final double averageDays;
        final int mid = gaps.size() / 2;
        if (gaps.size() % 2 == 1) {
            averageDays = gaps.get(mid);
        } else {
            averageDays = (gaps.get(mid - 1) + gaps.get(mid)) / 2.0;
        }

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
    /**
     * Groups transactions by EXACT amount (compareTo == 0 on the BigDecimal,
     * scaled to 2 decimal places to ignore representational quirks like
     * "-15.99" vs "-15.990"). A subscription is by definition the SAME price
     * each period — a price change is its own event (handled separately by
     * the price-change detector), not a wider tolerance band. This prevents
     * variable-amount recurring spend (groceries within 5% of each other,
     * rent paid in slightly different installments) from being lumped into
     * one "subscription" group.
     */
    private Map<BigDecimal, List<TransactionTable>> groupByAmount(
            final List<TransactionTable> transactions) {
        final Map<BigDecimal, List<TransactionTable>> grouped = new HashMap<>();
        for (final TransactionTable tx : transactions) {
            final BigDecimal raw = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;
            final BigDecimal key =
                    raw.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }
        return grouped;
    }

    // isKnownSubscriptionMerchant removed — was a @Deprecated stub
    // returning false. All callers are now gone. Known-subscription
    // detection lives in the cascade's L3_MERCHANT_DB, which writes the
    // `subscriptions` categoryPrimary; the candidate-filter checks for
    // that string directly.

    /** Infers subscription type from transaction category and merchant */
    // ---- inferSubscriptionType: keyword tables, one per detector ----
    // Each block is matched IFF every keyword check is `contains()` on the
    // lowercase merchant+description string. Order of detectors matters: AI
    // services must run before generic software so "Cursor" / "ChatGPT" don't
    // get bucketed as SOFTWARE.

    private static final String[] STREAMING_KEYWORDS = {
        "netflix",
        "hulu",
        "disney",
        "hbo",
        "paramount",
        "peacock",
        "spotify",
        "apple music",
        "youtube premium",
        "youtube tv",
        "youtube music",
        "youtubemusic",
        "amazon prime",
        "prime video",
        "showtime",
        "starz",
        "crunchyroll",
        "funimation"
    };

    private static final String[] AI_SERVICE_KEYWORDS = {
        "openai", "chatgpt", "cursor", "anthropic", "meta ai", "claude"
    };

    private static final String[] SOFTWARE_KEYWORDS = {
        "adobe", "microsoft 365", "office 365", "github", "canva", "grammarly", "notion", "evernote"
    };

    private static final String[] NEWS_MEDIA_KEYWORDS = {
        "barrons",
        "wsj",
        "wall street journal",
        "ny times",
        "new york times",
        "nytimes",
        "financial times",
        "ft.com",
        "moneycontrol",
        "marketwatch",
        "consumer reports",
        "health magazine",
        "the economist",
        "forbes",
        "time magazine",
        "the atlantic"
    };

    private static final String[] RETAIL_MEMBERSHIP_KEYWORDS = {
        "costco",
        "sam's club",
        "sams club",
        "bjs",
        "bj's",
        "walmart plus",
        "wmt plus",
        "target circle",
        "best buy totaltech",
        "best buy membership"
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

    private static final String[] VPN_KEYWORDS = {"nordvpn", "expressvpn", "surfshark"};

    private static final String[] RIDESHARE_MEMBERSHIP_KEYWORDS = {"lyft pink", "uber one"};

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

        return OTHER;
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
        // return OTHER so downstream code routes it correctly.
        return mentions ? OTHER : null;
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

        // (Old check 1 — isKnownSubscriptionMerchant — was a deprecated
        //  stub returning false. Removed. Known subscription merchants now
        //  arrive here with categoryPrimary=subscriptions from L3 of the
        //  cascade, caught by check 2 below.)

        // 2. Subscription-related categories = SUBSCRIPTION
        if (SUBSCRIPTIONS.equalsIgnoreCase(categoryDetailed)) {
            return SUBSCRIPTION;
        }
        if (categoryPrimary != null
                && SUBSCRIPTION_CATEGORIES.contains(categoryPrimary.toLowerCase(Locale.ROOT))) {
            // But exclude insurance and loans
            if (!INSURANCE.equalsIgnoreCase(categoryPrimary)
                    && !"loans".equalsIgnoreCase(categoryPrimary)
                    && !MORTGAGE.equalsIgnoreCase(categoryPrimary)) {
                return SUBSCRIPTION;
            }
        }

        // 3. Subscription type indicates subscription (not OTHER for unknown)
        if (subscriptionType != null && !OTHER.equalsIgnoreCase(subscriptionType)) {
            // If we identified it as streaming, software, membership, cloud_storage, it's a
            // subscription
            return SUBSCRIPTION;
        }

        // 4. Large recurring payments (likely mortgage, loans) = RECURRING
        if (amount != null && amount.abs().compareTo(new BigDecimal("500")) > 0) {
            // Check if it's from a bank/financial institution (likely loan/mortgage)
            if (combined.contains(MORTGAGE)
                    || combined.contains("loan")
                    || combined.contains("auto finance")
                    || combined.contains("car payment")
                    || combined.contains("student loan")
                    || combined.contains("credit card")
                    || categoryPrimary != null
                            && ("loans".equalsIgnoreCase(categoryPrimary)
                                    || MORTGAGE.equalsIgnoreCase(categoryPrimary)
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

        // 6. Default: If subscriptionType was identified (not OTHER), it's likely a subscription
        // Otherwise, treat as recurring (safer default)
        if (subscriptionType != null && !OTHER.equalsIgnoreCase(subscriptionType)) {
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
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "SubscriptionTable {} has null or empty startDate, skipping conversion",
                        table.getSubscriptionId());
            }
            return null;
        }

        if (table.getNextPaymentDate() == null || table.getNextPaymentDate().isBlank()) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "SubscriptionTable {} has null or empty nextPaymentDate, skipping conversion",
                        table.getSubscriptionId());
            }
            return null;
        }

        final LocalDate startDate = parseDate(table.getStartDate());
        final LocalDate nextPaymentDate = parseDate(table.getNextPaymentDate());

        if (startDate == null) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "SubscriptionTable {} has invalid startDate format: {}, skipping conversion",
                        table.getSubscriptionId(),
                        table.getStartDate());
            }
            return null;
        }

        if (nextPaymentDate == null) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "SubscriptionTable {} has invalid nextPaymentDate format: {}, skipping conversion",
                        table.getSubscriptionId(),
                        table.getNextPaymentDate());
            }
            return null;
        }

        // Validate frequency
        Subscription.SubscriptionFrequency frequency = null;
        if (table.getFrequency() != null && !table.getFrequency().isBlank()) {
            try {
                frequency = Subscription.SubscriptionFrequency.valueOf(table.getFrequency());
            } catch (IllegalArgumentException e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "SubscriptionTable {} has invalid frequency: {}, skipping conversion",
                            table.getSubscriptionId(),
                            table.getFrequency());
                }
                return null;
            }
        } else {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "SubscriptionTable {} has null or empty frequency, skipping conversion",
                        table.getSubscriptionId());
            }
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
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error converting SubscriptionTable {} to Subscription: {}",
                        table.getSubscriptionId(),
                        e.getMessage(),
                        e);
            }
            return null;
        }
    }
}
