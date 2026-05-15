package com.budgetbuddy.model.dynamodb;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * DynamoDB table for Accounts CRITICAL: @JsonInclude ensures null fields are included in JSON
 * responses for iOS
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances")
@DynamoDbBean
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AccountTable {
    private static final String UTC = "UTC";
    private static final String YYYY_MM_DD_T_HH_MM_SS_SSS_Z = "yyyy-MM-dd\'T\'HH:mm:ss.SSS\'Z\'";

    private String accountId; // Partition key
    private String userId; // GSI partition key
    private String accountName;
    private String institutionName;
    private String accountType;
    private String accountSubtype;
    private BigDecimal balance;
    private LocalDate
            balanceDate; // Date of the transaction from which balance was extracted (for date
    // comparison)
    private String currencyCode;
    private String plaidAccountId; // GSI for Plaid lookup
    private String plaidItemId; // GSI for Plaid item lookup
    private String accountNumber; // Account number/mask (last 4 digits) for deduplication
    private Boolean active;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    /** Optimistic-concurrency counter — see {@code BudgetTable.version}. */
    private Long version;

    /**
     * User-overridden account name flag. Set to {@code true} whenever the user renames an account
     * via the Account Details screen. When set, the Plaid sync path will not overwrite {@code
     * accountName} even if Plaid reports a different official name — otherwise the user's rename
     * gets reverted every sync cycle. Null / false = Plaid is authoritative.
     */
    private Boolean accountNameOverridden;

    // Credit card statement metadata (from PDF imports)
    private LocalDate paymentDueDate; // Latest payment due date from statements
    private BigDecimal
            minimumPaymentDue; // Minimum payment due (from statement with latest payment due date)
    private Long
            rewardPoints; // Reward points (from statement with latest payment due date, 0 to 10
    // million)

    // Flow 8 — card advisor fields. Optional; populated by Plaid enrichment, PDF
    // parsing, or the user. Non-credit accounts leave these null.
    private BigDecimal creditLimit;
    private BigDecimal availableCredit;

    /** Purchase APR as a percent (21.99 = 21.99 %). */
    private BigDecimal aprPercent;

    /** Foreign transaction fee as a percent. 0 = no-foreign-fee card. */
    private BigDecimal foreignTxFeePercent;

    /** Category → multiplier (e.g. {"dining": 3.0, "default": 1.0}). */
    private java.util.Map<String, BigDecimal> rewardMultipliers;

    /** "cash_back" | "points" | "miles". */
    private String rewardType;

    // TODO: Customer ID of the bank
    // TODO: Amount of Money I have reserved for the gaosl, unless it pulls from goals or goal pulls
    // from Account

    @DynamoDbPartitionKey
    @DynamoDbAttribute("accountId")
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdIndex", "UserIdUpdatedAtIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidAccountIdIndex")
    @DynamoDbAttribute("plaidAccountId")
    public String getPlaidAccountId() {
        return plaidAccountId;
    }

    public void setPlaidAccountId(final String plaidAccountId) {
        this.plaidAccountId = plaidAccountId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidItemIdIndex")
    @DynamoDbAttribute("plaidItemId")
    public String getPlaidItemId() {
        return plaidItemId;
    }

    public void setPlaidItemId(final String plaidItemId) {
        this.plaidItemId = plaidItemId;
    }

    @DynamoDbAttribute("accountName")
    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(final String accountName) {
        this.accountName = accountName;
    }

    @DynamoDbAttribute("institutionName")
    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(final String institutionName) {
        this.institutionName = institutionName;
    }

    @DynamoDbAttribute("accountType")
    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(final String accountType) {
        this.accountType = accountType;
    }

    @DynamoDbAttribute("accountSubtype")
    public String getAccountSubtype() {
        return accountSubtype;
    }

    public void setAccountSubtype(final String accountSubtype) {
        this.accountSubtype = accountSubtype;
    }

    @DynamoDbAttribute("balance")
    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(final BigDecimal balance) {
        this.balance = balance;
    }

    @DynamoDbAttribute("balanceDate")
    public LocalDate getBalanceDate() {
        return balanceDate;
    }

    public void setBalanceDate(final LocalDate balanceDate) {
        this.balanceDate = balanceDate;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbAttribute("accountNumber")
    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(final String accountNumber) {
        this.accountNumber = accountNumber;
    }

    @DynamoDbAttribute("active")
    public Boolean getActive() {
        return active;
    }

    public void setActive(final Boolean active) {
        this.active = active;
    }

    @DynamoDbAttribute("lastSyncedAt")
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            timezone = UTC)
    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(final Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    @DynamoDbAttribute("createdAt")
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            timezone = UTC)
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Instant createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("updatedAt")
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            timezone = UTC)
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final Instant updatedAt) {
        this.updatedAt = updatedAt;
        // Auto-populate timestamp for GSI sort key
        this.updatedAtTimestamp = updatedAt != null ? updatedAt.getEpochSecond() : null;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdUpdatedAtIndex")
    @DynamoDbAttribute("updatedAtTimestamp")
    public Long getUpdatedAtTimestamp() {
        return updatedAtTimestamp;
    }

    public void setUpdatedAtTimestamp(final Long updatedAtTimestamp) {
        this.updatedAtTimestamp = updatedAtTimestamp;
    }

    @DynamoDbAttribute("paymentDueDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include in JSON, even if null
    public LocalDate getPaymentDueDate() {
        return paymentDueDate;
    }

    public void setPaymentDueDate(final LocalDate paymentDueDate) {
        this.paymentDueDate = paymentDueDate;
    }

    @DynamoDbAttribute("minimumPaymentDue")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include in JSON, even if null
    public BigDecimal getMinimumPaymentDue() {
        return minimumPaymentDue;
    }

    public void setMinimumPaymentDue(final BigDecimal minimumPaymentDue) {
        this.minimumPaymentDue = minimumPaymentDue;
    }

    @DynamoDbAttribute("rewardPoints")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include in JSON, even if null
    public Long getRewardPoints() {
        return rewardPoints;
    }

    public void setRewardPoints(final Long rewardPoints) {
        this.rewardPoints = rewardPoints;
    }

    @DynamoDbAttribute("creditLimit")
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(final BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    @DynamoDbAttribute("availableCredit")
    public BigDecimal getAvailableCredit() {
        return availableCredit;
    }

    public void setAvailableCredit(final BigDecimal availableCredit) {
        this.availableCredit = availableCredit;
    }

    @DynamoDbAttribute("aprPercent")
    public BigDecimal getAprPercent() {
        return aprPercent;
    }

    public void setAprPercent(final BigDecimal aprPercent) {
        this.aprPercent = aprPercent;
    }

    @DynamoDbAttribute("foreignTxFeePercent")
    public BigDecimal getForeignTxFeePercent() {
        return foreignTxFeePercent;
    }

    public void setForeignTxFeePercent(final BigDecimal foreignTxFeePercent) {
        this.foreignTxFeePercent = foreignTxFeePercent;
    }

    @DynamoDbAttribute("rewardMultipliers")
    public java.util.Map<String, BigDecimal> getRewardMultipliers() {
        return rewardMultipliers;
    }

    public void setRewardMultipliers(final java.util.Map<String, BigDecimal> rewardMultipliers) {
        this.rewardMultipliers = rewardMultipliers;
    }

    @DynamoDbAttribute("rewardType")
    public String getRewardType() {
        return rewardType;
    }

    public void setRewardType(final String rewardType) {
        this.rewardType = rewardType;
    }

    @DynamoDbAttribute("version")
    public Long getVersion() {
        return version;
    }

    public void setVersion(final Long version) {
        this.version = version;
    }

    @DynamoDbAttribute("accountNameOverridden")
    public Boolean getAccountNameOverridden() {
        return accountNameOverridden;
    }

    public void setAccountNameOverridden(final Boolean accountNameOverridden) {
        this.accountNameOverridden = accountNameOverridden;
    }

    /**
     * User-toggled visibility flag. Hidden accounts remain in the list but their transactions are
     * excluded from default reports / totals. Reversible — flipping back to false restores the
     * account fully. Null treated as false. See AccountController PATCH /{id} for the toggle
     * endpoint.
     */
    private Boolean isHidden;

    @DynamoDbAttribute("isHidden")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Boolean getIsHidden() {
        return isHidden;
    }

    public void setIsHidden(final Boolean isHidden) {
        this.isHidden = isHidden;
    }

    /**
     * Soft-delete timestamp. Set when the user deletes an account from the iOS UI; the account row
     * is kept around briefly so an undo can restore it, but transactions belonging to it are
     * hard-deleted at the same time (the iOS list filters deleted accounts out via
     * visibleAccountIds). Null = active. See AccountController DELETE /{id}.
     */
    private Instant deletedAt;

    @DynamoDbAttribute("deletedAt")
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            timezone = UTC)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(final Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    // ========================================================================
    //  PDF statement-summary persistence (added so the metadata surfaced in
    //  the import-preview response survives past the preview screen). All
    //  nullable — populated only on accounts that have been touched by a PDF
    //  import. Older rows will read these as null with no migration needed
    //  (DynamoDB is schemaless).
    //
    //  All time-sensitive fields use the "latest statement wins" rule
    //  enforced in TransactionController.updateAccountMetadataFromPDFImport:
    //  an import only overwrites these when its paymentDueDate is later than
    //  what we already have on the row. That makes a re-import of the SAME
    //  statement idempotent and an out-of-order import (older statement
    //  uploaded after a newer one) a no-op.
    // ========================================================================

    private BigDecimal newBalance;
    private BigDecimal previousBalance;
    private BigDecimal pastDueAmount;
    private LocalDate statementDate;
    private Integer billingDays;

    // Section totals.
    private BigDecimal purchasesTotal;
    private BigDecimal paymentsAndCreditsTotal;
    private BigDecimal cashAdvancesTotal;
    private BigDecimal balanceTransfersTotal;
    private BigDecimal feesChargedTotal;
    private BigDecimal interestChargedTotal;

    // APR splits — the existing `aprPercent` field (above) stays as the canonical
    // purchase APR for backwards compat. These add the other three rate types.
    private BigDecimal cashAdvanceApr;
    private BigDecimal balanceTransferApr;
    private BigDecimal penaltyApr;

    // Cash-advance secondary limits.
    private BigDecimal cashAccessLine;
    private BigDecimal availableForCash;

    // Annual membership fee + upcoming billing date.
    private BigDecimal annualMembershipFee;
    private LocalDate annualMembershipFeeDueDate;

    // AutoPay status + next scheduled deduction.
    private Boolean autoPayEnabled;
    private BigDecimal nextAutoPayAmount;

    // Points split. The existing `rewardPoints` field stays — these add
    // specificity (earned-this-period vs. cumulative balance vs. prior cycle).
    private Long pointsEarnedThisPeriod;
    private Long pointsBalance;
    // Chase Amazon Visa-style "Previous points balance" — explicit prior-cycle value
    // that lets UIs show "earned X since last cycle" deltas without recomputing.
    private Long previousPointsBalance;

    // YTD totals from the most-recent statement. Reset each January when the issuer
    // ticks the YTD counter. Null when the statement doesn't print the YTD row.
    private BigDecimal ytdFeesCharged;
    private BigDecimal ytdInterestCharged;

    // Chase Freedom rotating-bonus tier (the active one this statement cycle).
    // Three scalar columns rather than a nested struct to keep DynamoDB+iOS-Codable
    // mapping simple. All three are set together or none of them; null = card has
    // no rotating bonus active.
    private String currentQuarterBonusQuarter;  // "1Q" – "4Q"
    private BigDecimal currentQuarterBonusRate; // e.g. 5
    private String currentQuarterBonusCategory; // e.g. "Grocery Stores"

    // Next-quarter activation window (Chase Freedom only). Lets the iOS app remind
    // users to activate before the new quarter opens.
    private BigDecimal nextQuarterBonusRate;
    private BigDecimal nextQuarterBonusCap;     // typically $1,500 cap on bonus spend
    private LocalDate nextQuarterBonusStart;
    private LocalDate nextQuarterBonusEnd;

    @DynamoDbAttribute("newBalance")
    public BigDecimal getNewBalance() { return newBalance; }
    public void setNewBalance(final BigDecimal v) { this.newBalance = v; }

    @DynamoDbAttribute("previousBalance")
    public BigDecimal getPreviousBalance() { return previousBalance; }
    public void setPreviousBalance(final BigDecimal v) { this.previousBalance = v; }

    @DynamoDbAttribute("pastDueAmount")
    public BigDecimal getPastDueAmount() { return pastDueAmount; }
    public void setPastDueAmount(final BigDecimal v) { this.pastDueAmount = v; }

    @DynamoDbAttribute("statementDate")
    public LocalDate getStatementDate() { return statementDate; }
    public void setStatementDate(final LocalDate v) { this.statementDate = v; }

    @DynamoDbAttribute("billingDays")
    public Integer getBillingDays() { return billingDays; }
    public void setBillingDays(final Integer v) { this.billingDays = v; }

    @DynamoDbAttribute("purchasesTotal")
    public BigDecimal getPurchasesTotal() { return purchasesTotal; }
    public void setPurchasesTotal(final BigDecimal v) { this.purchasesTotal = v; }

    @DynamoDbAttribute("paymentsAndCreditsTotal")
    public BigDecimal getPaymentsAndCreditsTotal() { return paymentsAndCreditsTotal; }
    public void setPaymentsAndCreditsTotal(final BigDecimal v) { this.paymentsAndCreditsTotal = v; }

    @DynamoDbAttribute("cashAdvancesTotal")
    public BigDecimal getCashAdvancesTotal() { return cashAdvancesTotal; }
    public void setCashAdvancesTotal(final BigDecimal v) { this.cashAdvancesTotal = v; }

    @DynamoDbAttribute("balanceTransfersTotal")
    public BigDecimal getBalanceTransfersTotal() { return balanceTransfersTotal; }
    public void setBalanceTransfersTotal(final BigDecimal v) { this.balanceTransfersTotal = v; }

    @DynamoDbAttribute("feesChargedTotal")
    public BigDecimal getFeesChargedTotal() { return feesChargedTotal; }
    public void setFeesChargedTotal(final BigDecimal v) { this.feesChargedTotal = v; }

    @DynamoDbAttribute("interestChargedTotal")
    public BigDecimal getInterestChargedTotal() { return interestChargedTotal; }
    public void setInterestChargedTotal(final BigDecimal v) { this.interestChargedTotal = v; }

    @DynamoDbAttribute("cashAdvanceApr")
    public BigDecimal getCashAdvanceApr() { return cashAdvanceApr; }
    public void setCashAdvanceApr(final BigDecimal v) { this.cashAdvanceApr = v; }

    @DynamoDbAttribute("balanceTransferApr")
    public BigDecimal getBalanceTransferApr() { return balanceTransferApr; }
    public void setBalanceTransferApr(final BigDecimal v) { this.balanceTransferApr = v; }

    @DynamoDbAttribute("penaltyApr")
    public BigDecimal getPenaltyApr() { return penaltyApr; }
    public void setPenaltyApr(final BigDecimal v) { this.penaltyApr = v; }

    @DynamoDbAttribute("cashAccessLine")
    public BigDecimal getCashAccessLine() { return cashAccessLine; }
    public void setCashAccessLine(final BigDecimal v) { this.cashAccessLine = v; }

    @DynamoDbAttribute("availableForCash")
    public BigDecimal getAvailableForCash() { return availableForCash; }
    public void setAvailableForCash(final BigDecimal v) { this.availableForCash = v; }

    @DynamoDbAttribute("annualMembershipFee")
    public BigDecimal getAnnualMembershipFee() { return annualMembershipFee; }
    public void setAnnualMembershipFee(final BigDecimal v) { this.annualMembershipFee = v; }

    @DynamoDbAttribute("annualMembershipFeeDueDate")
    public LocalDate getAnnualMembershipFeeDueDate() { return annualMembershipFeeDueDate; }
    public void setAnnualMembershipFeeDueDate(final LocalDate v) { this.annualMembershipFeeDueDate = v; }

    @DynamoDbAttribute("autoPayEnabled")
    public Boolean getAutoPayEnabled() { return autoPayEnabled; }
    public void setAutoPayEnabled(final Boolean v) { this.autoPayEnabled = v; }

    @DynamoDbAttribute("nextAutoPayAmount")
    public BigDecimal getNextAutoPayAmount() { return nextAutoPayAmount; }
    public void setNextAutoPayAmount(final BigDecimal v) { this.nextAutoPayAmount = v; }

    @DynamoDbAttribute("pointsEarnedThisPeriod")
    public Long getPointsEarnedThisPeriod() { return pointsEarnedThisPeriod; }
    public void setPointsEarnedThisPeriod(final Long v) { this.pointsEarnedThisPeriod = v; }

    @DynamoDbAttribute("pointsBalance")
    public Long getPointsBalance() { return pointsBalance; }
    public void setPointsBalance(final Long v) { this.pointsBalance = v; }

    @DynamoDbAttribute("previousPointsBalance")
    public Long getPreviousPointsBalance() { return previousPointsBalance; }
    public void setPreviousPointsBalance(final Long v) { this.previousPointsBalance = v; }

    @DynamoDbAttribute("ytdFeesCharged")
    public BigDecimal getYtdFeesCharged() { return ytdFeesCharged; }
    public void setYtdFeesCharged(final BigDecimal v) { this.ytdFeesCharged = v; }

    @DynamoDbAttribute("ytdInterestCharged")
    public BigDecimal getYtdInterestCharged() { return ytdInterestCharged; }
    public void setYtdInterestCharged(final BigDecimal v) { this.ytdInterestCharged = v; }

    @DynamoDbAttribute("currentQuarterBonusQuarter")
    public String getCurrentQuarterBonusQuarter() { return currentQuarterBonusQuarter; }
    public void setCurrentQuarterBonusQuarter(final String v) { this.currentQuarterBonusQuarter = v; }

    @DynamoDbAttribute("currentQuarterBonusRate")
    public BigDecimal getCurrentQuarterBonusRate() { return currentQuarterBonusRate; }
    public void setCurrentQuarterBonusRate(final BigDecimal v) { this.currentQuarterBonusRate = v; }

    @DynamoDbAttribute("currentQuarterBonusCategory")
    public String getCurrentQuarterBonusCategory() { return currentQuarterBonusCategory; }
    public void setCurrentQuarterBonusCategory(final String v) { this.currentQuarterBonusCategory = v; }

    @DynamoDbAttribute("nextQuarterBonusRate")
    public BigDecimal getNextQuarterBonusRate() { return nextQuarterBonusRate; }
    public void setNextQuarterBonusRate(final BigDecimal v) { this.nextQuarterBonusRate = v; }

    @DynamoDbAttribute("nextQuarterBonusCap")
    public BigDecimal getNextQuarterBonusCap() { return nextQuarterBonusCap; }
    public void setNextQuarterBonusCap(final BigDecimal v) { this.nextQuarterBonusCap = v; }

    @DynamoDbAttribute("nextQuarterBonusStart")
    public LocalDate getNextQuarterBonusStart() { return nextQuarterBonusStart; }
    public void setNextQuarterBonusStart(final LocalDate v) { this.nextQuarterBonusStart = v; }

    @DynamoDbAttribute("nextQuarterBonusEnd")
    public LocalDate getNextQuarterBonusEnd() { return nextQuarterBonusEnd; }
    public void setNextQuarterBonusEnd(final LocalDate v) { this.nextQuarterBonusEnd = v; }
}
