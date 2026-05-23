package com.budgetbuddy.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Subscription entity representing a recurring subscription Tracks subscriptions that occur on a
 * regular basis (monthly, quarterly, semi-annually, annually)
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class Subscription implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private String subscriptionId;
    private String userId;
    private String accountId;
    private String merchantName; // Company/service name
    private String description; // Transaction description
    private BigDecimal amount; // Subscription amount (always positive)
    private SubscriptionFrequency frequency; // monthly, quarterly, semi-annual, annual
    private LocalDate startDate; // First payment date
    private LocalDate nextPaymentDate; // Next expected payment date
    private LocalDate lastPaymentDate; // Last payment date
    private String category; // Transaction category (e.g., "subscriptions")
    private String subscriptionType; // Type of subscription: "streaming", "software", "membership",
    // "cloud_storage", "other"
    private String subscriptionCategory; // High-level category: "subscription" (merchant-based like
    // Netflix) or "recurring" (bills like mortgage, utilities)
    private String originalCategoryPrimary; // Original transaction categoryPrimary (for context)
    private String originalCategoryDetailed; // Original transaction categoryDetailed (for context)
    private Boolean active; // Whether subscription is still active
    private String plaidTransactionId; // Reference to a Plaid transaction
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Structured price-change history. Populated by
     * {@code SubscriptionService.consolidateMultiPriceSubscriptions} when a
     * single merchant's amount-groups collapse into one subscription via
     * the price-change merge rule. The current {@link #amount} is always
     * the LATEST price; this list holds older amounts in chronological
     * order so iOS can render a price chart without parsing the
     * description text. Empty (not null) means "no observed price change".
     */
    private java.util.List<PriceHistoryEntry> priceHistory = new java.util.ArrayList<>();

    /**
     * Coarse-grained lifecycle state used by iOS for badge rendering.
     * Derived from billing history, NOT the user's manual action. Distinct
     * from {@link #active} (which is a hard cancelled vs not-cancelled
     * boolean) — lifecycle conveys "what's the health" without the user
     * having to read date math themselves.
     */
    private LifecycleState lifecycleState = LifecycleState.ACTIVE;

    public enum LifecycleState {
        /** Within expected billing cadence; no concern. */
        ACTIVE,
        /** No payment since one expected cycle ago. UI: yellow badge. */
        UNUSED_1_CYCLE,
        /** No payment since two expected cycles ago. UI: orange badge. */
        UNUSED_2_CYCLES,
        /** Stale enough that we treat it as cancelled. {@link #active} is also flipped to false. */
        PRESUMED_CANCELLED,
        /** Explicit user action — won't auto-reactivate on a new payment. */
        USER_CANCELLED
    }

    public LifecycleState getLifecycleState() {
        return lifecycleState == null ? LifecycleState.ACTIVE : lifecycleState;
    }

    public void setLifecycleState(final LifecycleState v) {
        this.lifecycleState = v == null ? LifecycleState.ACTIVE : v;
    }

    /**
     * For variable-priced subscriptions (cell bills, electric, water),
     * the expected next-bill amount. Computed as the median of the
     * recent 3-6 charges. Null for fixed-price subscriptions where
     * {@link #amount} is exact. iOS uses this to render "expected:
     * $187 / range: $172–$230" instead of pretending the last-seen
     * price will repeat.
     */
    private BigDecimal predictedNextAmount;

    public BigDecimal getPredictedNextAmount() {
        return predictedNextAmount;
    }

    public void setPredictedNextAmount(final BigDecimal v) {
        this.predictedNextAmount = v;
    }

    /**
     * When this subscription's free trial converts to paid billing.
     * Populated by {@link com.budgetbuddy.service.LlmTrialEndExtractor}
     * during transaction import when the description carries "FREE TRIAL
     * ENDS …" / "first charge …" phrasing. Null when no trial signal.
     * iOS renders a "Trial ending in N days" banner when this is within
     * the next ~14 days.
     */
    private LocalDate trialEndsAt;

    public LocalDate getTrialEndsAt() {
        return trialEndsAt;
    }

    public void setTrialEndsAt(final LocalDate v) {
        this.trialEndsAt = v;
    }

    /** One historical price observation. */
    public static final class PriceHistoryEntry implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private BigDecimal amount;
        private LocalDate observedAt;

        public PriceHistoryEntry() {}

        public PriceHistoryEntry(final BigDecimal amount, final LocalDate observedAt) {
            this.amount = amount;
            this.observedAt = observedAt;
        }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(final BigDecimal v) { this.amount = v; }
        public LocalDate getObservedAt() { return observedAt; }
        public void setObservedAt(final LocalDate v) { this.observedAt = v; }
    }

    public java.util.List<PriceHistoryEntry> getPriceHistory() {
        return priceHistory == null ? new java.util.ArrayList<>() : priceHistory;
    }

    public void setPriceHistory(final java.util.List<PriceHistoryEntry> v) {
        this.priceHistory = v == null ? new java.util.ArrayList<>() : v;
    }

    public enum SubscriptionFrequency {
        DAILY, // Every day
        WEEKLY, // Every week
        BI_WEEKLY, // Every 2 weeks
        MONTHLY, // Every month
        QUARTERLY, // Every 3 months
        SEMI_ANNUAL, // Every 6 months
        ANNUAL // Every year
    }

    // Constructors
    public Subscription() {}

    public Subscription(
            final String userId,
            final String accountId,
            final String merchantName,
            final BigDecimal amount,
            final SubscriptionFrequency frequency,
            final LocalDate startDate) {
        this.userId = userId;
        this.accountId = accountId;
        this.merchantName = merchantName;
        this.amount = amount;
        this.frequency = frequency;
        this.startDate = startDate;
        this.nextPaymentDate = calculateNextPaymentDate(startDate, frequency);
        this.active = true;
    }

    /** Calculates the next payment date based on frequency */
    private LocalDate calculateNextPaymentDate(
            final LocalDate startDate, final SubscriptionFrequency frequency) {
        if (startDate == null || frequency == null) {
            return null;
        }

        return switch (frequency) {
            case DAILY -> startDate.plusDays(1);
            case WEEKLY -> startDate.plusWeeks(1);
            case BI_WEEKLY -> startDate.plusWeeks(2);
            case MONTHLY -> startDate.plusMonths(1);
            case QUARTERLY -> startDate.plusMonths(3);
            case SEMI_ANNUAL -> startDate.plusMonths(6);
            case ANNUAL -> startDate.plusYears(1);
        };
    }

    // Getters and Setters
    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(final String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(final BigDecimal amount) {
        this.amount = amount;
    }

    public SubscriptionFrequency getFrequency() {
        return frequency;
    }

    public void setFrequency(final SubscriptionFrequency frequency) {
        this.frequency = frequency;
        // Recalculate next payment date when frequency changes
        if (this.startDate != null && frequency != null) {
            this.nextPaymentDate = calculateNextPaymentDate(this.startDate, frequency);
        }
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(final LocalDate startDate) {
        this.startDate = startDate;
        // Recalculate next payment date when start date changes
        if (startDate != null && this.frequency != null) {
            this.nextPaymentDate = calculateNextPaymentDate(startDate, this.frequency);
        }
    }

    public LocalDate getNextPaymentDate() {
        return nextPaymentDate;
    }

    public void setNextPaymentDate(final LocalDate nextPaymentDate) {
        this.nextPaymentDate = nextPaymentDate;
    }

    public LocalDate getLastPaymentDate() {
        return lastPaymentDate;
    }

    public void setLastPaymentDate(final LocalDate lastPaymentDate) {
        this.lastPaymentDate = lastPaymentDate;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(final String category) {
        this.category = category;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(final Boolean active) {
        this.active = active;
    }

    public String getPlaidTransactionId() {
        return plaidTransactionId;
    }

    public void setPlaidTransactionId(final String plaidTransactionId) {
        this.plaidTransactionId = plaidTransactionId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSubscriptionType() {
        return subscriptionType;
    }

    public void setSubscriptionType(final String subscriptionType) {
        this.subscriptionType = subscriptionType;
    }

    public String getSubscriptionCategory() {
        return subscriptionCategory;
    }

    public void setSubscriptionCategory(final String subscriptionCategory) {
        this.subscriptionCategory = subscriptionCategory;
    }

    public String getOriginalCategoryPrimary() {
        return originalCategoryPrimary;
    }

    public void setOriginalCategoryPrimary(final String originalCategoryPrimary) {
        this.originalCategoryPrimary = originalCategoryPrimary;
    }

    public String getOriginalCategoryDetailed() {
        return originalCategoryDetailed;
    }

    public void setOriginalCategoryDetailed(final String originalCategoryDetailed) {
        this.originalCategoryDetailed = originalCategoryDetailed;
    }

    /** Updates the next payment date after a payment is made */
    public void recordPayment(final LocalDate paymentDate) {
        this.lastPaymentDate = paymentDate;
        if (this.nextPaymentDate != null
                && paymentDate.isAfter(this.nextPaymentDate.minusDays(5))) {
            // Payment was made (within 5 days of expected date), update next payment date
            this.nextPaymentDate = calculateNextPaymentDate(paymentDate, this.frequency);
        }
    }
}
