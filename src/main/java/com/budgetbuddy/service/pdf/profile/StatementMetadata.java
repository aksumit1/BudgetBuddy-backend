package com.budgetbuddy.service.pdf.profile;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable value object that aggregates every statement-summary field a profile can
 * populate. Lets callers receive ONE object rather than threading 23 fields through the
 * argument list. The builder is the only mutator — instances are immutable once built.
 *
 * <p>Currently the profile interface returns individual values (one method per field)
 * because the orchestrator code path consumes them that way. {@link StatementMetadata} is the
 * forward-looking shape: once all extractors live in profiles, the registry returns a
 * single {@code StatementMetadata} and callers stop touching individual extractor
 * methods. For now it's surface area for the registry's diagnostic + reconciliation
 * tooling.
 */
public final class StatementMetadata {

    private final String issuerId;
    private final String brand;
    private final BigDecimal newBalance;
    private final BigDecimal previousBalance;
    private final BigDecimal creditLimit;
    private final BigDecimal availableCredit;
    private final BigDecimal pastDueAmount;
    private final BigDecimal purchasesTotal;
    private final BigDecimal paymentsAndCreditsTotal;
    private final BigDecimal feesChargedTotal;
    private final BigDecimal interestChargedTotal;
    private final BigDecimal purchaseApr;
    private final BigDecimal cashAdvanceApr;
    private final BigDecimal balanceTransferApr;
    private final BigDecimal penaltyApr;
    private final Integer billingDays;
    private final LocalDate statementDate;
    private final Boolean autoPayEnabled;
    private final BigDecimal nextAutoPayAmount;
    private final Long pointsBalance;
    private final Long pointsEarnedThisPeriod;
    private final BigDecimal cashBackBalance;
    private final BigDecimal ytdFeesCharged;
    private final BigDecimal ytdInterestCharged;

    private StatementMetadata(final Builder b) {
        this.issuerId = b.issuerId;
        this.brand = b.brand;
        this.newBalance = b.newBalance;
        this.previousBalance = b.previousBalance;
        this.creditLimit = b.creditLimit;
        this.availableCredit = b.availableCredit;
        this.pastDueAmount = b.pastDueAmount;
        this.purchasesTotal = b.purchasesTotal;
        this.paymentsAndCreditsTotal = b.paymentsAndCreditsTotal;
        this.feesChargedTotal = b.feesChargedTotal;
        this.interestChargedTotal = b.interestChargedTotal;
        this.purchaseApr = b.purchaseApr;
        this.cashAdvanceApr = b.cashAdvanceApr;
        this.balanceTransferApr = b.balanceTransferApr;
        this.penaltyApr = b.penaltyApr;
        this.billingDays = b.billingDays;
        this.statementDate = b.statementDate;
        this.autoPayEnabled = b.autoPayEnabled;
        this.nextAutoPayAmount = b.nextAutoPayAmount;
        this.pointsBalance = b.pointsBalance;
        this.pointsEarnedThisPeriod = b.pointsEarnedThisPeriod;
        this.cashBackBalance = b.cashBackBalance;
        this.ytdFeesCharged = b.ytdFeesCharged;
        this.ytdInterestCharged = b.ytdInterestCharged;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String issuerId() { return issuerId; }
    public String brand() { return brand; }
    public BigDecimal newBalance() { return newBalance; }
    public BigDecimal previousBalance() { return previousBalance; }
    public BigDecimal creditLimit() { return creditLimit; }
    public BigDecimal availableCredit() { return availableCredit; }
    public BigDecimal pastDueAmount() { return pastDueAmount; }
    public BigDecimal purchasesTotal() { return purchasesTotal; }
    public BigDecimal paymentsAndCreditsTotal() { return paymentsAndCreditsTotal; }
    public BigDecimal feesChargedTotal() { return feesChargedTotal; }
    public BigDecimal interestChargedTotal() { return interestChargedTotal; }
    public BigDecimal purchaseApr() { return purchaseApr; }
    public BigDecimal cashAdvanceApr() { return cashAdvanceApr; }
    public BigDecimal balanceTransferApr() { return balanceTransferApr; }
    public BigDecimal penaltyApr() { return penaltyApr; }
    public Integer billingDays() { return billingDays; }
    public LocalDate statementDate() { return statementDate; }
    public Boolean autoPayEnabled() { return autoPayEnabled; }
    public BigDecimal nextAutoPayAmount() { return nextAutoPayAmount; }
    public Long pointsBalance() { return pointsBalance; }
    public Long pointsEarnedThisPeriod() { return pointsEarnedThisPeriod; }
    public BigDecimal cashBackBalance() { return cashBackBalance; }
    public BigDecimal ytdFeesCharged() { return ytdFeesCharged; }
    public BigDecimal ytdInterestCharged() { return ytdInterestCharged; }

    /**
     * Returns the field-population ratio (0.0-1.0) — count of non-null core fields
     * divided by total core fields. Used by the registry to choose between the profile
     * result and the generic fallback: if a profile fills < 30% of fields, the
     * fallback's union is preferred.
     */
    public double populationRatio() {
        final Object[] core = {
            newBalance, previousBalance, creditLimit, availableCredit,
            purchasesTotal, paymentsAndCreditsTotal, purchaseApr,
            billingDays, statementDate, autoPayEnabled,
        };
        int filled = 0;
        for (final Object v : core) {
            if (v != null) {
                filled++;
            }
        }
        return (double) filled / core.length;
    }

    public static final class Builder {
        private String issuerId;
        private String brand;
        private BigDecimal newBalance;
        private BigDecimal previousBalance;
        private BigDecimal creditLimit;
        private BigDecimal availableCredit;
        private BigDecimal pastDueAmount;
        private BigDecimal purchasesTotal;
        private BigDecimal paymentsAndCreditsTotal;
        private BigDecimal feesChargedTotal;
        private BigDecimal interestChargedTotal;
        private BigDecimal purchaseApr;
        private BigDecimal cashAdvanceApr;
        private BigDecimal balanceTransferApr;
        private BigDecimal penaltyApr;
        private Integer billingDays;
        private LocalDate statementDate;
        private Boolean autoPayEnabled;
        private BigDecimal nextAutoPayAmount;
        private Long pointsBalance;
        private Long pointsEarnedThisPeriod;
        private BigDecimal cashBackBalance;
        private BigDecimal ytdFeesCharged;
        private BigDecimal ytdInterestCharged;

        public Builder issuerId(final String v) { this.issuerId = v; return this; }
        public Builder brand(final String v) { this.brand = v; return this; }
        public Builder newBalance(final BigDecimal v) { this.newBalance = v; return this; }
        public Builder previousBalance(final BigDecimal v) { this.previousBalance = v; return this; }
        public Builder creditLimit(final BigDecimal v) { this.creditLimit = v; return this; }
        public Builder availableCredit(final BigDecimal v) { this.availableCredit = v; return this; }
        public Builder pastDueAmount(final BigDecimal v) { this.pastDueAmount = v; return this; }
        public Builder purchasesTotal(final BigDecimal v) { this.purchasesTotal = v; return this; }
        public Builder paymentsAndCreditsTotal(final BigDecimal v) { this.paymentsAndCreditsTotal = v; return this; }
        public Builder feesChargedTotal(final BigDecimal v) { this.feesChargedTotal = v; return this; }
        public Builder interestChargedTotal(final BigDecimal v) { this.interestChargedTotal = v; return this; }
        public Builder purchaseApr(final BigDecimal v) { this.purchaseApr = v; return this; }
        public Builder cashAdvanceApr(final BigDecimal v) { this.cashAdvanceApr = v; return this; }
        public Builder balanceTransferApr(final BigDecimal v) { this.balanceTransferApr = v; return this; }
        public Builder penaltyApr(final BigDecimal v) { this.penaltyApr = v; return this; }
        public Builder billingDays(final Integer v) { this.billingDays = v; return this; }
        public Builder statementDate(final LocalDate v) { this.statementDate = v; return this; }
        public Builder autoPayEnabled(final Boolean v) { this.autoPayEnabled = v; return this; }
        public Builder nextAutoPayAmount(final BigDecimal v) { this.nextAutoPayAmount = v; return this; }
        public Builder pointsBalance(final Long v) { this.pointsBalance = v; return this; }
        public Builder pointsEarnedThisPeriod(final Long v) { this.pointsEarnedThisPeriod = v; return this; }
        public Builder cashBackBalance(final BigDecimal v) { this.cashBackBalance = v; return this; }
        public Builder ytdFeesCharged(final BigDecimal v) { this.ytdFeesCharged = v; return this; }
        public Builder ytdInterestCharged(final BigDecimal v) { this.ytdInterestCharged = v; return this; }

        public StatementMetadata build() {
            return new StatementMetadata(this);
        }
    }
}
