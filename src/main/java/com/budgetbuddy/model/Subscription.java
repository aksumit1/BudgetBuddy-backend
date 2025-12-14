package com.budgetbuddy.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Subscription entity representing a recurring subscription
 * Tracks subscriptions that occur on a regular basis (monthly, quarterly, semi-annually, annually)
 */
public class Subscription implements java.io.Serializable {
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
    private Boolean active; // Whether subscription is still active
    private String plaidTransactionId; // Reference to a Plaid transaction
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public enum SubscriptionFrequency {
        MONTHLY,      // Every month
        QUARTERLY,    // Every 3 months
        SEMI_ANNUAL, // Every 6 months
        ANNUAL        // Every year
    }

    // Constructors
    public Subscription() {
    }

    public Subscription(final String userId, final String accountId, final String merchantName,
                       final BigDecimal amount, final SubscriptionFrequency frequency,
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

    /**
     * Calculates the next payment date based on frequency
     */
    private LocalDate calculateNextPaymentDate(final LocalDate startDate, final SubscriptionFrequency frequency) {
        if (startDate == null || frequency == null) {
            return null;
        }
        
        return switch (frequency) {
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

    /**
     * Updates the next payment date after a payment is made
     */
    public void recordPayment(final LocalDate paymentDate) {
        this.lastPaymentDate = paymentDate;
        if (this.nextPaymentDate != null && paymentDate.isAfter(this.nextPaymentDate.minusDays(5))) {
            // Payment was made (within 5 days of expected date), update next payment date
            this.nextPaymentDate = calculateNextPaymentDate(paymentDate, this.frequency);
        }
    }
}

