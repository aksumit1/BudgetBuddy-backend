package com.budgetbuddy.model.dynamodb;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/** DynamoDB table for Transactions Optimized with GSI for user queries and date range filtering */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@DynamoDbBean
public class TransactionTable {

    private String transactionId; // Partition key
    private String userId; // GSI partition key
    private String accountId;
    private BigDecimal amount;
    private String description;
    private String merchantName;
    private String location; // Optional location extracted from description/import
    private String userName; // Card/account user name (family member who made the transaction)
    private String categoryPrimary; // Primary category (internal, always used for display)
    private String categoryDetailed; // Detailed category (internal, always used for display)
    private String
            importerCategoryPrimary; // Importer's original primary category (Plaid, CSV parser,
    // etc.)
    private String
            importerCategoryDetailed; // Importer's original detailed category (Plaid, CSV parser,
    // etc.)
    private Boolean categoryOverridden; // Whether user has overridden the category
    private Boolean
            transactionTypeOverridden; // Whether user has explicitly overridden transactionType
    // (prevents Plaid sync from recalculating)
    private String transactionDate; // GSI sort key (YYYY-MM-DD format)
    private String currencyCode;
    private String plaidTransactionId; // GSI for deduplication
    private Boolean pending;

    /** See {@link #getPendingAmount()} — audit trail for pending→posted drift. */
    private BigDecimal pendingAmount;

    private String paymentChannel; // online, in_store, ach, etc.
    private String notes; // User notes for the transaction
    private String reviewStatus; // Review status: "none", "flagged", "reviewed", "error"
    private Boolean isHidden; // Whether transaction is hidden from view
    private String transactionType; // Transaction type: INCOME, INVESTMENT, PAYMENT, or EXPENSE
    private String importSource; // Import source: "CSV", "EXCEL", "PDF", "PLAID", "MANUAL"
    private String importBatchId; // UUID for grouping imports
    private String importFileName; // Original file name for imports
    private Instant importedAt; // When transaction was imported
    private String goalId; // Optional: Goal this transaction contributes to
    private String
            linkedTransactionId; // Optional: ID of linked transaction (e.g., credit card payment
    // linked to checking payment)
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    /**
     * Flow 4 / O9 soft-delete. When non-null the row is hidden from every normal query but kept
     * around for the undo window / purge job. Missing attribute = active row.
     */
    private Instant deletedAt;

    /**
     * Flow 6 / O6 — if this transaction was generated from a round-up of an expense, stores the
     * source transaction id. Prevents re-rounding the same row twice.
     */
    private String roundUpSourceTransactionId;

    /**
     * Optimistic-concurrency counter. See {@code BudgetTable.version}. Protects user category/notes
     * edits from being silently clobbered by a racing Plaid sync that re-ingests the same
     * transaction (and vice-versa for pending→posted reconciliation racing a user delete).
     */
    private Long version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(final String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbSecondaryPartitionKey(
            indexNames = {"UserIdDateIndex", "UserIdUpdatedAtIndex", "UserIdGoalIdIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdDateIndex")
    @DynamoDbAttribute("transactionDate")
    public String getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(final String transactionDate) {
        this.transactionDate = transactionDate;
    }

    @DynamoDbAttribute("accountId")
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    @DynamoDbAttribute("amount")
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(final BigDecimal amount) {
        this.amount = amount;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @DynamoDbAttribute("merchantName")
    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    @DynamoDbAttribute("location")
    public String getLocation() {
        return location;
    }

    public void setLocation(final String location) {
        this.location = location;
    }

    @DynamoDbAttribute("userName")
    public String getUserName() {
        return userName;
    }

    public void setUserName(final String userName) {
        this.userName = userName;
    }

    /**
     * Last-4 of the card used for THIS specific transaction. Distinct from
     * the account's accountNumber: on family-card statements (Amex Blue
     * Business Cash w/ employee cards) different rows carry different
     * last-4s. iOS already decodes this field; pre-fix it was extracted by
     * the parser but never persisted to DDB — silently dropped during the
     * createTransaction → updateTransaction roundtrip in
     * processPDFBatchImport. Now persisted via the new
     * createTransactionFromParsedPdf path.
     */
    private String cardLastFour;

    @DynamoDbAttribute("cardLastFour")
    public String getCardLastFour() {
        return cardLastFour;
    }

    public void setCardLastFour(final String cardLastFour) {
        this.cardLastFour = cardLastFour;
    }

    @DynamoDbAttribute("categoryPrimary")
    public String getCategoryPrimary() {
        return categoryPrimary;
    }

    public void setCategoryPrimary(final String categoryPrimary) {
        this.categoryPrimary = categoryPrimary;
    }

    @DynamoDbAttribute("categoryDetailed")
    public String getCategoryDetailed() {
        return categoryDetailed;
    }

    public void setCategoryDetailed(final String categoryDetailed) {
        this.categoryDetailed = categoryDetailed;
    }

    /**
     * Get category for backward compatibility with iOS app Returns categoryPrimary if available,
     * otherwise categoryDetailed
     */
    @JsonProperty("category")
    public String getCategory() {
        if (categoryPrimary != null && !categoryPrimary.isEmpty()) {
            return categoryPrimary;
        }
        return categoryDetailed;
    }

    @DynamoDbAttribute("importerCategoryPrimary")
    public String getImporterCategoryPrimary() {
        return importerCategoryPrimary;
    }

    public void setImporterCategoryPrimary(final String importerCategoryPrimary) {
        this.importerCategoryPrimary = importerCategoryPrimary;
    }

    @DynamoDbAttribute("importerCategoryDetailed")
    public String getImporterCategoryDetailed() {
        return importerCategoryDetailed;
    }

    public void setImporterCategoryDetailed(final String importerCategoryDetailed) {
        this.importerCategoryDetailed = importerCategoryDetailed;
    }

    @DynamoDbAttribute("categoryOverridden")
    public Boolean getCategoryOverridden() {
        return categoryOverridden;
    }

    public void setCategoryOverridden(final Boolean categoryOverridden) {
        this.categoryOverridden = categoryOverridden;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidTransactionIdIndex")
    @DynamoDbAttribute("plaidTransactionId")
    public String getPlaidTransactionId() {
        return plaidTransactionId;
    }

    public void setPlaidTransactionId(final String plaidTransactionId) {
        // Normalize on store so dedup queries don't miss because of
        // case differences. Plaid IDs are case-sensitive in the source,
        // but our deduplication semantic treats them as identifiers,
        // not strings — same logical row regardless of letter case.
        this.plaidTransactionId = plaidTransactionId == null
                ? null
                : com.budgetbuddy.util.IdGenerator.normalizeUUID(plaidTransactionId);
    }

    @DynamoDbAttribute("pending")
    public Boolean getPending() {
        return pending;
    }

    public void setPending(final Boolean pending) {
        this.pending = pending;
    }

    /**
     * Snapshot of the {@code amount} at the time the transaction was still pending, persisted so
     * the UI can show "Pending was $100 → Posted $99" after reconciliation. Null for transactions
     * that never went through a pending phase (CSV imports, manual adds, and Plaid transactions
     * that arrived already posted).
     */
    @DynamoDbAttribute("pendingAmount")
    public BigDecimal getPendingAmount() {
        return pendingAmount;
    }

    public void setPendingAmount(final BigDecimal pendingAmount) {
        this.pendingAmount = pendingAmount;
    }

    @DynamoDbAttribute("paymentChannel")
    public String getPaymentChannel() {
        return paymentChannel;
    }

    public void setPaymentChannel(final String paymentChannel) {
        this.paymentChannel = paymentChannel;
    }

    @DynamoDbAttribute("notes")
    public String getNotes() {
        return notes;
    }

    public void setNotes(final String notes) {
        this.notes = notes;
    }

    @DynamoDbAttribute("reviewStatus")
    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(final String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    @DynamoDbAttribute("isHidden")
    public Boolean getIsHidden() {
        return isHidden;
    }

    public void setIsHidden(final Boolean isHidden) {
        this.isHidden = isHidden;
    }

    @DynamoDbAttribute("transactionType")
    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(final String transactionType) {
        this.transactionType = transactionType;
    }

    @DynamoDbAttribute("transactionTypeOverridden")
    public Boolean getTransactionTypeOverridden() {
        return transactionTypeOverridden;
    }

    public void setTransactionTypeOverridden(final Boolean transactionTypeOverridden) {
        this.transactionTypeOverridden = transactionTypeOverridden;
    }

    @DynamoDbAttribute("importSource")
    public String getImportSource() {
        return importSource;
    }

    public void setImportSource(final String importSource) {
        this.importSource = importSource;
    }

    @DynamoDbAttribute("importBatchId")
    public String getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(final String importBatchId) {
        this.importBatchId = importBatchId;
    }

    @DynamoDbAttribute("importFileName")
    public String getImportFileName() {
        return importFileName;
    }

    public void setImportFileName(final String importFileName) {
        this.importFileName = importFileName;
    }

    @DynamoDbAttribute("importedAt")
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            timezone = "UTC")
    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(final Instant importedAt) {
        this.importedAt = importedAt;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdGoalIdIndex")
    @DynamoDbAttribute("goalId")
    public String getGoalId() {
        return goalId;
    }

    public void setGoalId(final String goalId) {
        this.goalId = goalId;
    }

    @DynamoDbAttribute("linkedTransactionId")
    public String getLinkedTransactionId() {
        return linkedTransactionId;
    }

    public void setLinkedTransactionId(final String linkedTransactionId) {
        this.linkedTransactionId = linkedTransactionId;
    }

    @DynamoDbAttribute("createdAt")
    @JsonFormat(
            shape = JsonFormat.Shape.STRING,
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            timezone = "UTC")
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
            timezone = "UTC")
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

    @DynamoDbAttribute("deletedAt")
    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(final Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    @DynamoDbAttribute("roundUpSourceTransactionId")
    public String getRoundUpSourceTransactionId() {
        return roundUpSourceTransactionId;
    }

    public void setRoundUpSourceTransactionId(final String roundUpSourceTransactionId) {
        this.roundUpSourceTransactionId = roundUpSourceTransactionId;
    }

    public void setUpdatedAtTimestamp(final Long updatedAtTimestamp) {
        this.updatedAtTimestamp = updatedAtTimestamp;
    }

    @DynamoDbAttribute("version")
    public Long getVersion() {
        return version;
    }

    public void setVersion(final Long version) {
        this.version = version;
    }

    // ========================================================================
    //  Foreign-currency original-amount block
    //
    //  Persisted from PDFImportService when a Chase-style "(EXCHG RATE)" block
    //  was attached to the parent transaction during PDF parsing. All four
    //  fields are nullable; a domestic USD purchase carries no FX context.
    //
    //  These supplement (don't replace) the existing `currencyCode` field,
    //  which is the SETTLEMENT currency (USD on a US card). originalCurrencyCode
    //  is what the merchant charged before conversion (INR, CHF, EUR, etc.).
    //
    //  DynamoDB schemaless — no CFN change needed for these attributes.
    // ========================================================================

    /** ISO 4217 code of the original-currency charge (e.g. "INR", "CHF"). */
    private String originalCurrencyCode;

    /** Issuer-printed currency name (e.g. "INDIAN RUPEE"). Kept alongside the code
     *  because some less-common currencies fall back to display name when no
     *  mapping is known. */
    private String originalCurrencyDisplay;

    /** Pre-conversion amount in the original currency (e.g. 14543.50 INR). */
    private BigDecimal originalAmount;

    /** Exchange rate applied to convert original → settlement (multiply by this
     *  to go from original currency to USD). Stored with full precision (Chase
     *  prints 9–10 decimal places). */
    private BigDecimal exchangeRate;

    @DynamoDbAttribute("originalCurrencyCode")
    public String getOriginalCurrencyCode() {
        return originalCurrencyCode;
    }

    public void setOriginalCurrencyCode(final String v) {
        this.originalCurrencyCode = v;
    }

    @DynamoDbAttribute("originalCurrencyDisplay")
    public String getOriginalCurrencyDisplay() {
        return originalCurrencyDisplay;
    }

    public void setOriginalCurrencyDisplay(final String v) {
        this.originalCurrencyDisplay = v;
    }

    @DynamoDbAttribute("originalAmount")
    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(final BigDecimal v) {
        this.originalAmount = v;
    }

    @DynamoDbAttribute("exchangeRate")
    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    public void setExchangeRate(final BigDecimal v) {
        this.exchangeRate = v;
    }

    // ========================================================================
    //  Wallet provider — detected from the merchant description prefix.
    //  Examples: "APL*STARBUCKS" → apple-pay, "PYPL *EBAY" → paypal.
    //  Powers wallet-segmented analytics ("70% of dining via Apple Pay") and
    //  helps separate physical-card swipes from tokenized payments for fraud
    //  monitoring. Nullable: legacy rows or unrecognized merchant prefixes
    //  stay null — physical card is the implicit default.
    //
    //  Wire format is the stable string from WalletProviderDetector.WalletProvider
    //  .wireName() so the enum constant name can evolve without breaking
    //  DynamoDB rows.
    // ========================================================================
    private String walletProvider;

    @DynamoDbAttribute("walletProvider")
    public String getWalletProvider() {
        return walletProvider;
    }

    public void setWalletProvider(final String v) {
        this.walletProvider = v;
    }

    // ========================================================================
    //  Structured geo fields — split out of the single human-readable
    //  `location` string by PDFImportService.geoEnrichV2Transaction so
    //  downstream consumers (analytics, map view, "trips abroad" detection,
    //  geo-aggregated insights) can query by stable components.
    //
    //  All fields are nullable; the parser only sets a field when it has
    //  high-confidence evidence (last-token US state code, alpha-2/alpha-3
    //  country, spelled-out country name, USPS street-suffix pattern, etc.).
    //  Legacy rows imported before this commit will return null — clients
    //  must fall back to the `location` field for display in that case.
    //
    //  DynamoDB schemaless — no CFN change required.
    // ========================================================================
    private String city;
    private String state;
    private String country;
    private String postalCode;
    private String phoneNumber;
    private String streetAddress;

    @DynamoDbAttribute("city")
    public String getCity() {
        return city;
    }

    public void setCity(final String v) {
        this.city = v;
    }

    @DynamoDbAttribute("state")
    public String getState() {
        return state;
    }

    public void setState(final String v) {
        this.state = v;
    }

    @DynamoDbAttribute("country")
    public String getCountry() {
        return country;
    }

    public void setCountry(final String v) {
        this.country = v;
    }

    @DynamoDbAttribute("postalCode")
    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(final String v) {
        this.postalCode = v;
    }

    @DynamoDbAttribute("phoneNumber")
    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(final String v) {
        this.phoneNumber = v;
    }

    @DynamoDbAttribute("streetAddress")
    public String getStreetAddress() {
        return streetAddress;
    }

    public void setStreetAddress(final String v) {
        this.streetAddress = v;
    }
}
