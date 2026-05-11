package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.category.strategy.CategoryDetectionManager;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import com.budgetbuddy.util.StringUtils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * CSV Import Service Mirrors the iOS app's CSVImportService functionality Parses CSV files from
 * banks/credit cards and converts them to transaction data
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class CSVImportService {

    private static final String DATE = "date";
    private static final String AMOUNT = "amount";
    private static final String DESCRIPTION = "description";
    private static final String MEMO = "memo";
    private static final String OTHER = "other";
    private static final String TRANSPORTATION = "transportation";
    private static final String MERCHANT = "merchant";
    private static final String TRANSFER = "transfer";
    private static final String DINING = "dining";
    private static final String UTILITIES = "utilities";
    private static final String DETAILS = "details";
    private static final String EDUCATION = "education";
    private static final String FEE = "fee";
    private static final String DEPOSIT = "deposit";
    private static final String TYPE = "type";
    private static final String NAME = "name";
    private static final String AMEX = "amex";
    private static final String CHECKING = "checking";
    private static final String CREDITCARD = "creditcard";
    private static final String SALARY = "salary";
    private static final String GROCERIES = "groceries";
    private static final String DEPOSITORY = "depository";
    private static final String ERACTOLL = "eractoll";
    private static final String EPAYMENT = "epayment";

    /**
     * School type keywords used by detectCategoryFromDescription's school + charity passes. Lifted
     * to class scope so the charity fallback can reuse the same list without re-declaring it.
     */
    private static final String[] SCHOOL_TYPES = {
        "middle school",
        "middleschool",
        "high school",
        "highschool",
        "elementary school",
        "elementaryschool",
        "elementary",
        "secondary school",
        "secondaryschool",
        "senior secondary school",
        "seniorschool",
        "college",
        "university",
        "phd",
        "ph.d",
        "ph.d.",
        "doctorate",
        "graduate school",
        "graduateschool",
        "school district",
        "schooldistrict",
        "bellevue school district",
        "bellevueschooldistrict",
        "tyee middle school",
        "tyeemiddleschool"
    };

    private static final String E_PAYMENT = "e-payment";
    private static final String DIVIDEND = "dividend";
    private static final String IRA = "ira";
    private static final String INTEREST = "interest";
    private static final String FROM = "from";
    private static final String SUBSCRIPTIONS = "subscriptions";
    private static final String TRAVEL = "travel";
    private static final String CHARITY = "charity";
    private static final String STORE = "store";
    private static final String RECIPIENT = "recipient";
    private static final String SENDER = "sender";
    private static final String DATUM = "datum";
    private static final String HEALTH = "health";
    private static final String ACTION = "action";
    private static final String DATETIME = "datetime";
    private static final String TIME = "time";
    private static final String CARD = "card";
    private static final String SAVINGS = "savings";
    private static final String FUEL = "fuel";
    private static final String TOTAL = "total";
    private static final String SHOPPING = "shopping";
    private static final String RENT = "rent";
    private static final String HEALTHCARE = "healthcare";
    private static final String SYMBOL = "symbol";
    private static final String LOAN = "loan";

    private static final Logger LOGGER = LoggerFactory.getLogger(CSVImportService.class);

    private final CategoryDetectionManager categoryDetectionManager;

    private final AccountDetectionService accountDetectionService;
    private final EnhancedCategoryDetectionService enhancedCategoryDetection;
    private final ImportCategoryParser importCategoryParser;

    public CSVImportService(
            final AccountDetectionService accountDetectionService,
            final EnhancedCategoryDetectionService enhancedCategoryDetection,
            final ImportCategoryParser importCategoryParser,
            final CategoryDetectionManager categoryDetectionManager) {
        this.accountDetectionService = accountDetectionService;
        this.enhancedCategoryDetection = enhancedCategoryDetection;
        this.categoryDetectionManager = categoryDetectionManager;
        this.importCategoryParser = importCategoryParser;
    }

    // Date formatters matching iOS app - supports global formats
    // CRITICAL FIX: Prioritize unambiguous formats and use smart detection for ambiguous formats
    // US: MM/dd/yyyy, M/d/yyyy
    // Europe/India: dd/MM/yyyy, dd.MM.yyyy, dd-MM-yyyy
    // ISO: yyyy-MM-dd
    // Asia: yyyy/MM/dd (Japan, China)
    private static final List<DateTimeFormatter> DATE_FORMATTERS =
            Arrays.asList(
                    // ISO format first (most unambiguous - no ambiguity)
                    DateTimeFormatter.ISO_LOCAL_DATE, // yyyy-MM-dd
                    DateTimeFormatter.ofPattern(
                            "yyyy/MM/dd"), // Asian format (yyyy/MM/dd) - unambiguous
                    // Formats with separators that make them unambiguous
                    DateTimeFormatter.ofPattern(
                            "dd.MM.yyyy"), // Germany, Austria, Switzerland (DD.MM.YYYY) - dot
                    // separator is unambiguous
                    DateTimeFormatter.ofPattern(
                            "dd-MM-yyyy"), // Netherlands, Belgium - dash separator is unambiguous
                    // Text formats (unambiguous due to month names)
                    DateTimeFormatter.ofPattern(
                            "MMM dd, yyyy"), // US text format (e.g., "Dec 01, 2024")
                    DateTimeFormatter.ofPattern(
                            "dd MMM yyyy"), // UK text format (e.g., "01 Dec 2024")
                    // CRITICAL FIX: For ambiguous formats (MM/dd vs dd/MM), use smart detection
                    // Try US format first for dates where first number > 12 (unambiguous)
                    // Then try European format for dates where second number > 12 (unambiguous)
                    // This prevents dates like "12/1/2024" from being misinterpreted
                    DateTimeFormatter.ofPattern(
                            "MM/dd/yyyy"), // US format - try before European for better US
                    // compatibility
                    DateTimeFormatter.ofPattern("M/d/yyyy"), // US format (single digit month/day)
                    DateTimeFormatter.ofPattern("MM-dd-yyyy"), // US format with dash
                    DateTimeFormatter.ofPattern(
                            "dd/MM/yyyy"), // UK, India (DD/MM/YYYY) - after US to avoid ambiguity
                    DateTimeFormatter.ISO_LOCAL_DATE);

    /** Parsed CSV row data */
    public static class ParsedRow {
        private final Map<String, String> fields = new HashMap<>();

        public void put(final String key, final String value) {
            fields.put(key.toLowerCase(Locale.ROOT).trim(), value != null ? value.trim() : "");
        }

        public String get(final String key) {
            return fields.get(key.toLowerCase(Locale.ROOT).trim());
        }

        public String findField(final String... keys) {
            for (final String key : keys) {
                final String value = get(key);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return null;
        }

        /**
         * True iff any of the given header names is present in this row's schema, regardless of
         * whether the value is empty for the current row.
         */
        public boolean hasHeader(final String... keys) {
            for (final String key : keys) {
                if (fields.containsKey(key.toLowerCase(Locale.ROOT).trim())) {
                    return true;
                }
            }
            return false;
        }

        /** Copy of the header names (keys) known to this row. */
        public java.util.Set<String> headerNames() {
            return new java.util.HashSet<>(fields.keySet());
        }
    }

    /** Import result containing parsed transactions */
    public static class ImportResult {
        private final List<ParsedTransaction> transactions = new ArrayList<>();
        private int successCount = 0;
        private int failureCount = 0;
        private final List<String> errors = new ArrayList<>();
        private AccountDetectionService.DetectedAccount
                detectedAccount; // Detected account from import
        private String matchedAccountId; // Matched existing account ID (if found)

        public void addTransaction(final ParsedTransaction transaction) {
            transactions.add(transaction);
            successCount++;
        }

        public void addError(final String error) {
            errors.add(error);
            failureCount++;
        }

        /**
         * Add an informational message without incrementing failure count Used for cases like empty
         * files where it's not a failure, just no data
         */
        public void addInfo(final String message) {
            errors.add(message);
            // Don't increment failureCount - this is informational, not a failure
        }

        public List<ParsedTransaction> getTransactions() {
            return transactions;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public List<String> getErrors() {
            return errors;
        }

        public AccountDetectionService.DetectedAccount getDetectedAccount() {
            return detectedAccount;
        }

        public void setDetectedAccount(
                final AccountDetectionService.DetectedAccount detectedAccount) {
            this.detectedAccount = detectedAccount;
        }

        public String getMatchedAccountId() {
            return matchedAccountId;
        }

        public void setMatchedAccountId(final String matchedAccountId) {
            this.matchedAccountId = matchedAccountId;
        }
    }

    /** Parsed transaction data ready for database creation */
    public static class ParsedTransaction {
        private LocalDate date;
        private BigDecimal amount;
        private String description;
        private String merchantName;
        private String location;
        private String categoryPrimary; // Internal category (for display)
        private String categoryDetailed; // Internal category (for display)
        private String importerCategoryPrimary; // Importer's original category (from parser)
        private String importerCategoryDetailed; // Importer's original category (from parser)
        private String paymentChannel;
        private String transactionType;
        private String
                transactionTypeIndicator; // DEBIT/CREDIT indicator from CSV (for recalculation)
        private String transactionId; // Optional: Transaction ID provided by iOS for consistency
        private String currencyCode; // Detected currency code (USD, INR, etc.)
        private String accountId; // Detected or matched account ID

        /** Explicit DEBIT/CREDIT direction. See FlowDirection javadoc. */
        private FlowDirection flowDirection;

        /**
         * Card last-4 if the CSV row/column carries one (rare but seen on corporate-card exports
         * that itemise by card number).
         */
        private String cardLastFour;

        public FlowDirection getFlowDirection() {
            return flowDirection;
        }

        public void setFlowDirection(final FlowDirection flowDirection) {
            this.flowDirection = flowDirection;
        }

        public String getCardLastFour() {
            return cardLastFour;
        }

        public void setCardLastFour(final String cardLastFour) {
            this.cardLastFour = cardLastFour;
        }

        // Getters and setters
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

        public String getLocation() {
            return location;
        }

        public void setLocation(final String location) {
            this.location = location;
        }

        public String getCategoryPrimary() {
            return categoryPrimary;
        }

        public void setCategoryPrimary(final String categoryPrimary) {
            this.categoryPrimary = categoryPrimary;
        }

        public String getCategoryDetailed() {
            return categoryDetailed;
        }

        public void setCategoryDetailed(final String categoryDetailed) {
            this.categoryDetailed = categoryDetailed;
        }

        public String getImporterCategoryPrimary() {
            return importerCategoryPrimary;
        }

        public void setImporterCategoryPrimary(final String importerCategoryPrimary) {
            this.importerCategoryPrimary = importerCategoryPrimary;
        }

        public String getImporterCategoryDetailed() {
            return importerCategoryDetailed;
        }

        public void setImporterCategoryDetailed(final String importerCategoryDetailed) {
            this.importerCategoryDetailed = importerCategoryDetailed;
        }

        public String getPaymentChannel() {
            return paymentChannel;
        }

        public void setPaymentChannel(final String paymentChannel) {
            this.paymentChannel = paymentChannel;
        }

        public String getTransactionType() {
            return transactionType;
        }

        public void setTransactionType(final String transactionType) {
            this.transactionType = transactionType;
        }

        public String getTransactionTypeIndicator() {
            return transactionTypeIndicator;
        }

        public void setTransactionTypeIndicator(final String transactionTypeIndicator) {
            this.transactionTypeIndicator = transactionTypeIndicator;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(final String transactionId) {
            this.transactionId = transactionId;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(final String currencyCode) {
            this.currencyCode = currencyCode;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(final String accountId) {
            this.accountId = accountId;
        }
    }

    /**
     * Parse CSV file and return import result
     *
     * @param inputStream The input stream containing CSV data
     * @param filename The filename (used for account detection)
     * @param userId The user ID (used for account matching)
     * @param password Optional password for password-protected files (ZIP archives)
     */
    // HIGH PRIORITY FIX: Transaction count limit (10,000 per file)
    private static final int MAX_TRANSACTIONS_PER_FILE = 10_000;

    // CRITICAL: Static category map to avoid recreating on every parseCategory call (performance
    // optimization)
    private static final Map<String, String> CATEGORY_MAP = initializeCategoryMap();

    private static Map<String, String> initializeCategoryMap() {
        final Map<String, String> map = new HashMap<>();
        // Expenses - Common
        map.put(GROCERIES, GROCERIES);
        map.put("grocery", GROCERIES);
        map.put("food", GROCERIES);
        map.put("supermarket", GROCERIES);
        map.put(DINING, DINING);
        map.put("restaurant", DINING);
        map.put("food & dining", DINING);
        map.put("fast food", DINING);
        map.put(RENT, RENT);
        map.put("housing", RENT);
        map.put("mortgage", RENT);
        map.put(UTILITIES, UTILITIES);
        map.put("utility", UTILITIES);
        map.put("electric", UTILITIES);
        map.put("gas", UTILITIES);
        map.put("water", UTILITIES);
        map.put("internet", UTILITIES);
        map.put("phone", UTILITIES);
        map.put(TRANSPORTATION, TRANSPORTATION);
        map.put(FUEL, TRANSPORTATION);
        map.put("gas & fuel", TRANSPORTATION);
        map.put("auto", TRANSPORTATION);
        map.put("car", TRANSPORTATION);
        map.put("parking", TRANSPORTATION);
        map.put("entertainment", "entertainment");
        map.put(SHOPPING, SHOPPING);
        map.put("retail", SHOPPING);
        map.put(TRAVEL, TRAVEL);
        map.put("hotel", TRAVEL);
        map.put("airline", TRAVEL);
        map.put("subscription", SUBSCRIPTIONS);
        map.put(SUBSCRIPTIONS, SUBSCRIPTIONS);
        map.put("streaming", SUBSCRIPTIONS);
        map.put(HEALTHCARE, HEALTHCARE);
        map.put("medical", HEALTHCARE);
        map.put("pharmacy", HEALTHCARE);
        map.put("payment", "payment");
        map.put(TRANSFER, TRANSFER); // CRITICAL: Account transfers are separate from payments
        map.put("payment/transfer", "payment"); // Combined payment/transfer stays as payment
        map.put("cash", "cash"); // Cash withdrawals
        map.put("atm", "cash"); // ATM withdrawals
        map.put("withdrawal", "cash"); // Cash withdrawals
        map.put("cash withdrawal", "cash"); // Cash withdrawals
        // Investment categories
        map.put("investment", "investment");
        map.put("buy", "investment");
        map.put("sell", "investment");
        map.put("purchase", "investment");
        map.put("sale", "investment");
        map.put(DIVIDEND, DIVIDEND);
        map.put(INTEREST, INTEREST);
        map.put("capital gains", "investment");
        map.put("contribution", "investment");
        map.put("withdrawal", "investment");
        map.put("distribution", "investment");
        map.put("rollover", "investment");
        map.put("transfer in", "investment");
        map.put("transfer out", "investment");
        map.put("stocks", "investment");
        map.put("equity", "investment");
        map.put("mutual fund", "investment");
        map.put("mf", "investment");
        map.put("bonds", "investment");
        map.put("etf", "investment");
        map.put("cd", "investment");
        map.put("certificate of deposit", "investment");
        map.put("buy order", "investment");
        map.put("sell order", "investment");
        map.put("ipo", "investment");
        map.put("sip", "investment");
        map.put("systematic investment plan", "investment");
        map.put("401k contribution", "investment");
        map.put("employer contribution", "investment");
        map.put("employer match", "investment");
        // Income
        map.put(SALARY, SALARY);
        map.put("income", "income");
        map.put("payroll", SALARY);
        map.put("wages", SALARY);
        map.put("dividends", DIVIDEND);
        map.put("capital gain", "investment");
        map.put("reinvestment", "investment");
        map.put("principal", "payment");
        map.put("escrow", UTILITIES);
        map.put("loan payment", "payment");
        map.put("mortgage payment", "payment");
        map.put("auto loan", "payment");
        map.put("student loan", "payment");
        map.put("cash advance", OTHER);
        map.put("balance transfer", "payment");
        map.put("annual fee", OTHER);
        map.put("late fee", OTHER);
        map.put("overlimit fee", OTHER);
        map.put("returned payment fee", OTHER);
        map.put("hsa contribution", OTHER);
        map.put("hsa distribution", HEALTHCARE);
        map.put("refund", "payment");
        map.put(DEPOSIT, DEPOSIT);
        map.put(FEE, FEE);
        map.put("fee_transaction", FEE);
        map.put("fee transaction", FEE);
        map.put("charge", OTHER);
        map.put("request", OTHER);
        map.put("cash back", "income");
        map.put("cashback", "income");
        map.put("receive", "income");
        map.put("send", "payment");
        map.put("upi", "payment");
        map.put("neft", "payment");
        map.put("rtgs", "payment");
        map.put("imps", "payment");
        map.put("purchase", OTHER);
        map.put("captured", "payment");
        map.put("debit", OTHER);
        map.put("credit", "income");
        map.put(CHARITY, CHARITY);
        map.put("charitable", CHARITY);
        map.put("donation", CHARITY);
        map.put("donate", CHARITY);
        map.put("tuition", OTHER);
        map.put(EDUCATION, EDUCATION);
        map.put("school fee", OTHER);
        map.put("university fee", OTHER);
        map.put("college fee", OTHER);
        map.put("cpa", OTHER);
        map.put("tax preparer", OTHER);
        map.put("tax preparation", OTHER);
        map.put("accounting service", OTHER);
        map.put("dmv", OTHER);
        map.put("vehicle registration", OTHER);
        map.put("license renewal", OTHER);
        map.put("driver license", OTHER);
        map.put("property tax", OTHER);
        map.put("real estate tax", OTHER);
        map.put("state tax", OTHER);
        map.put("local tax", OTHER);
        map.put(OTHER, OTHER);
        map.put("misc", OTHER);
        map.put("miscellaneous", OTHER);
        return Collections.unmodifiableMap(map);
    }

    public ImportResult parseCSV(
            final InputStream inputStream,
            final String filename,
            final String userId,
            final String password) {
        return parseCSV(inputStream, filename, userId, password, null, null);
    }

    /**
     * Parse CSV file with optional preview categories preservation
     *
     * @param inputStream CSV file input stream
     * @param filename Original filename (for account detection)
     * @param userId User ID
     * @param password Optional password for encrypted files
     * @param previewCategories Optional list of preview categories (indexed by transaction order)
     * @param previewAccountId Optional account ID used during preview (for validation)
     * @return ImportResult with parsed transactions
     */
    public ImportResult parseCSV(
            final InputStream inputStream,
            String filename,
            final String userId,
            String password,
            List<com.budgetbuddy.api.ImportCategoryPreservationRequest.PreviewCategory>
                    previewCategories,
            final String previewAccountId) {
        // Handle null filename
        if (filename == null) {
            filename = "unknown.csv";
        }
        final ImportResult result = new ImportResult();

        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                detectEncodedStream(inputStream), StandardCharsets.UTF_8))) {

            // Read header row. We use readLogicalLine rather than reader.readLine()
            // so a quoted field containing an embedded newline (RFC 4180-valid,
            // common in Wells Fargo / Chase exports) doesn't split one logical
            // row across multiple physical lines and corrupt the column count.
            String headerLine = readLogicalLine(reader);
            if (headerLine == null || headerLine.isBlank()) {
                // CRITICAL: Return gracefully for empty files - not a failure, just no data
                LOGGER.info("CSV file is empty - no data to process");
                result.addInfo("CSV file is empty - no data to process");
                return result;
            }

            // CRITICAL: Strip UTF-8 BOM if present (EF BB BF)
            // BOM can appear at the start of the first line
            if (headerLine.length() > 0 && headerLine.charAt(0) == '\uFEFF') {
                headerLine = headerLine.substring(1);
                LOGGER.debug("Stripped UTF-8 BOM from header line");
            }
            // Also check for byte-order mark in first 3 bytes (EF BB BF)
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
                LOGGER.debug("Stripped UTF-8 BOM marker from header line");
            }

            final List<String> headers = parseCSVLine(headerLine);
            // CRITICAL: Check if headers are empty or only whitespace
            // Headers are considered empty if the list is empty OR all headers are empty/whitespace
            final boolean headersEmpty =
                    headers.isEmpty() || headers.stream().allMatch(h -> h == null || h.isBlank());
            if (headersEmpty) {
                // CRITICAL: Return gracefully for files with no headers - not a failure, just no
                // data
                LOGGER.info("CSV file has no headers - cannot parse data");
                result.addInfo("CSV file has no headers - cannot parse data");
                return result;
            }

            // Normalize headers (lowercase, trim)
            final List<String> normalizedHeaders = new ArrayList<>();
            final Map<String, Integer> headerCounts = new HashMap<>();

            // Handle duplicate headers by making them unique
            // This prevents duplicate key errors when creating row dictionaries
            for (int i = 0; i < headers.size(); i++) {
                final String header = headers.get(i);
                final String normalized = header.toLowerCase(Locale.ROOT).trim();
                final String baseHeader = normalized.isEmpty() ? "column" + (i + 1) : normalized;

                final int count = headerCounts.getOrDefault(baseHeader, 0);
                headerCounts.put(baseHeader, count + 1);

                if (count == 0) {
                    // First occurrence - use as-is
                    normalizedHeaders.add(baseHeader);
                } else {
                    // Duplicate - append number to make it unique
                    final String uniqueHeader = baseHeader + "_" + (count + 1);
                    normalizedHeaders.add(uniqueHeader);
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Duplicate header '{}' at column {} - renamed to '{}'",
                                header,
                                i + 1,
                                uniqueHeader);
                    }
                }
            }

            if (headerCounts.values().stream().anyMatch(count -> count > 1)) {
                LOGGER.info("Detected and resolved duplicate headers in CSV file");
            }

            // Detect account information from filename and headers
            AccountDetectionService.DetectedAccount detectedAccount = null;
            String matchedAccountId = null;
            boolean isTransactionTable = false;

            // Find balance column index for extracting balance from last transaction
            Integer balanceColumnIndex = null;
            for (int i = 0; i < normalizedHeaders.size(); i++) {
                final String header = normalizedHeaders.get(i);
                if (header != null && "balance".equals(header.toLowerCase(Locale.ROOT).trim())) {
                    balanceColumnIndex = i;
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("✓ Found balance column at index {}: '{}'", i, headers.get(i));
                    }
                    break;
                }
            }
            // Track balance from row with latest date (not necessarily last row)
            String latestDateBalanceValue = null;
            LocalDate latestDateForBalance = null;
            if (userId != null) {
                try {
                    // Check if headers are transaction table headers
                    isTransactionTable =
                            accountDetectionService.isTransactionTableHeaders(normalizedHeaders);
                    if (isTransactionTable) {
                        LOGGER.info(
                                "⚠️ CSV headers are transaction table headers - will skip extracting account info from transaction data");
                    }

                    // Detect from headers first
                    final AccountDetectionService.DetectedAccount fromHeaders =
                            accountDetectionService.detectFromHeaders(normalizedHeaders, filename);

                    // Match to existing account
                    if (fromHeaders != null) {
                        try {
                            matchedAccountId =
                                    accountDetectionService.matchToExistingAccount(
                                            userId, fromHeaders);
                            if (matchedAccountId != null) {
                                detectedAccount = fromHeaders;
                                if (LOGGER.isInfoEnabled()) {
                                    LOGGER.info(
                                            "Matched CSV import to existing account: {} (accountId: {}, accountNumber: {})",
                                            detectedAccount.getAccountName(),
                                            matchedAccountId,
                                            detectedAccount.getAccountNumber() != null
                                                    ? detectedAccount.getAccountNumber()
                                                    : "N/A");
                                }
                            } else {
                                // Enhanced logging with account number and other details
                                final String accountName =
                                        fromHeaders.getAccountName() != null
                                                ? fromHeaders.getAccountName()
                                                : "Unknown";
                                final String accountNumber =
                                        fromHeaders.getAccountNumber() != null
                                                ? fromHeaders.getAccountNumber()
                                                : "N/A";
                                final String institution =
                                        fromHeaders.getInstitutionName() != null
                                                ? fromHeaders.getInstitutionName()
                                                : "N/A";
                                if (LOGGER.isInfoEnabled()) {
                                    LOGGER.info(
                                            "Detected account from CSV but no match found - Name: {}, AccountNumber: {}, Institution: {}, Type: {}",
                                            accountName,
                                            accountNumber,
                                            institution,
                                            fromHeaders.getAccountType() != null
                                                    ? fromHeaders.getAccountType()
                                                    : "N/A");
                                }
                                detectedAccount = fromHeaders;
                            }
                        } catch (Exception e) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn(
                                        "Error during account matching for CSV import: {}",
                                        e.getMessage());
                            }
                            // Continue without account matching - user can select account in UI
                            detectedAccount = fromHeaders;
                        }
                    }
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Error during account detection for CSV import: {}",
                                e.getMessage());
                    }
                    // Continue without account detection - user can select account in UI
                }
            }

            // Parse data rows
            String line;
            int rowNumber = 2; // Start at 2 (header is row 1)
            int mismatchCount = 0;
            int rowsReadForAccountDetection = 0;
            final int MAX_ROWS_FOR_ACCOUNT_DETECTION =
                    20; // Check first 20 rows for account info (increased for better detection)

            // Track transaction patterns for account type inference (use arrays to allow
            // modification)
            final int[] debitCount = {0};
            final int[] creditCount = {0};
            final int[] checkCount = {0};
            final int[] achCount = {0};
            final int[] atmCount = {0};
            final int[] transferCount = {0};

            while ((line = readLogicalLine(reader)) != null) {
                if (line.isBlank()) {
                    continue; // Skip empty lines
                }

                List<String> values = parseCSVLine(line);

                // Remove ONLY truly trailing empty fields beyond the expected header count
                // This handles cases like ",," at the end while preserving legitimate empty columns
                while (values.size() > headers.size() && values.get(values.size() - 1).isBlank()) {
                    values.remove(values.size() - 1);
                }

                // Handle column count mismatch - use header count as truth
                if (values.size() != headers.size()) {
                    mismatchCount++;

                    if (values.size() > headers.size()) {
                        // Too many columns - truncate to header count
                        // Only log first few mismatches to avoid spam
                        if (mismatchCount <= 3) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn(
                                        "Row {}: Expected {} columns, found {}. Truncating to match header.",
                                        rowNumber,
                                        headers.size(),
                                        values.size());
                            }
                        }
                        values = values.subList(0, headers.size());
                    } else {
                        // Too few columns - pad with empty strings
                        if (mismatchCount <= 3) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn(
                                        "Row {}: Expected {} columns, found {}. Padding with empty values.",
                                        rowNumber,
                                        headers.size(),
                                        values.size());
                            }
                        }
                        while (values.size() < headers.size()) {
                            values.add("");
                        }
                    }
                }

                // Create parsed row
                final ParsedRow row = new ParsedRow();
                for (int i = 0; i < headers.size(); i++) {
                    if (i < values.size()) {
                        row.put(headers.get(i), values.get(i));
                    } else {
                        row.put(headers.get(i), "");
                    }
                }

                // CRITICAL FIX: Extract account information from data rows
                // For transaction tables, skip extracting institution names and account types from
                // transaction data
                // (they're payment recipients/transaction types, not account metadata)
                // Only extract account numbers from dedicated account number columns
                if (userId != null
                        && detectedAccount != null
                        && rowsReadForAccountDetection < MAX_ROWS_FOR_ACCOUNT_DETECTION) {
                    rowsReadForAccountDetection++;
                    LOGGER.debug("Analyzing data row {} for account information", rowNumber);

                    // Check each field value for account information
                    // CRITICAL: Add bounds checking to prevent IndexOutOfBoundsException
                    final int maxColIndex = Math.min(values.size(), headers.size());
                    for (int colIndex = 0; colIndex < maxColIndex; colIndex++) {
                        String value = null;
                        String header = null;

                        try {
                            value = colIndex < values.size() ? values.get(colIndex) : null;
                            header = colIndex < headers.size() ? headers.get(colIndex) : null;
                        } catch (IndexOutOfBoundsException e) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn(
                                        "Index out of bounds accessing column {} (values: {}, headers: {})",
                                        colIndex,
                                        values.size(),
                                        headers.size());
                            }
                            continue; // Skip this column
                        }

                        if (value != null && !value.isBlank() && header != null) {
                            final String headerLower =
                                    header != null ? header.toLowerCase(Locale.ROOT).trim() : "";

                            // Extract account number from data (only from dedicated account number
                            // columns)
                            if (detectedAccount.getAccountNumber() == null) {
                                // Check if this column header matches account number keywords
                                boolean isAccountNumberColumn = false;
                                for (final String keyword :
                                        accountDetectionService.getAccountNumberKeywords()) {
                                    if (headerLower.contains(keyword)) {
                                        isAccountNumberColumn = true;
                                        break;
                                    }
                                }

                                if (isAccountNumberColumn) {
                                    // Extract account number from value
                                    final String accountNum = extractAccountNumberFromValue(value);
                                    if (accountNum != null) {
                                        detectedAccount.setAccountNumber(accountNum);
                                        LOGGER.info(
                                                "✓ Extracted account number from row {} column '{}': {}",
                                                rowNumber,
                                                header,
                                                accountNum);
                                    }
                                }
                                // CRITICAL: Don't try pattern matching in transaction tables -
                                // dates and other numbers will be mistaken for account numbers
                            }

                            // CRITICAL: Skip extracting institution names from transaction data
                            // when it's a transaction table
                            // Transaction descriptions contain payment recipients (e.g., "CITI
                            // AUTOPAY"), not the account's bank
                            if (!isTransactionTable) {
                                // Extract institution/product name from data (only for
                                // non-transaction tables)
                                if (detectedAccount.getInstitutionName() == null
                                        || "Unknown".equals(detectedAccount.getInstitutionName())) {
                                    // Check if this column header matches institution/product
                                    // keywords
                                    boolean isInstitutionColumn = false;
                                    for (final String keyword :
                                            accountDetectionService.getInstitutionKeywords()) {
                                        if (headerLower.contains(keyword)) {
                                            isInstitutionColumn = true;
                                            break;
                                        }
                                    }

                                    if (isInstitutionColumn) {
                                        // Use value as institution name
                                        detectedAccount.setInstitutionName(value.trim());
                                        if (LOGGER.isInfoEnabled()) {
                                            LOGGER.info(
                                                    "✓ Extracted institution name from row {} column '{}': {}",
                                                    rowNumber,
                                                    header,
                                                    value.trim());
                                        }
                                    } else {
                                        // Try pattern matching for product names
                                        final String institution =
                                                extractInstitutionFromValue(value);
                                        if (institution != null) {
                                            detectedAccount.setInstitutionName(institution);
                                            LOGGER.info(
                                                    "✓ Extracted institution/product name from row {}: {}",
                                                    rowNumber,
                                                    institution);
                                        }
                                    }
                                }
                            } else {
                                LOGGER.debug(
                                        "⚠️ Skipping institution name extraction from transaction data row {} (transaction table)",
                                        rowNumber);
                            }

                            // CRITICAL: Skip extracting account type from transaction data when
                            // it's a transaction table
                            // Transaction TYPE columns refer to transaction types (debit/credit),
                            // not account types
                            if (!isTransactionTable) {
                                // Extract account type from data (only for non-transaction tables)
                                if (detectedAccount.getAccountType() == null) {
                                    // Check if this column header matches account type keywords
                                    boolean isAccountTypeColumn = false;
                                    for (final String keyword :
                                            accountDetectionService.getAccountTypeKeywords()) {
                                        if (headerLower.contains(keyword)) {
                                            isAccountTypeColumn = true;
                                            break;
                                        }
                                    }

                                    if (isAccountTypeColumn) {
                                        // Extract account type from value
                                        final String accountType =
                                                extractAccountTypeFromValue(value);
                                        if (accountType != null) {
                                            detectedAccount.setAccountType(accountType);
                                            LOGGER.info(
                                                    "✓ Extracted account type from row {} column '{}': {}",
                                                    rowNumber,
                                                    header,
                                                    accountType);
                                        }
                                    }
                                }
                            } else {
                                LOGGER.debug(
                                        "⚠️ Skipping account type extraction from transaction data row {} (transaction table - will infer from patterns)",
                                        rowNumber);
                            }

                            // DEEP ANALYSIS: Analyze transaction details/type for account type
                            // inference
                            // Look for keywords in description, transaction type, details columns
                            final boolean isTransactionDetailsColumn =
                                    headerLower.contains(DESCRIPTION)
                                            || headerLower.contains(DETAILS)
                                            || headerLower.contains(MEMO)
                                            || headerLower.contains("transaction type")
                                            || headerLower.contains(TYPE)
                                            || headerLower.contains("category");

                            if (isTransactionDetailsColumn) {
                                // Analyze transaction details for account type clues
                                analyzeTransactionForAccountType(
                                        value,
                                        debitCount,
                                        creditCount,
                                        checkCount,
                                        achCount,
                                        atmCount,
                                        transferCount);
                            }
                        }
                    }
                }

                // After analyzing multiple rows, infer account type from transaction patterns
                // CRITICAL: For transaction tables, prioritize pattern-based inference over data
                // extraction
                // This ensures we infer CHECKING from debit/credit/check patterns, not from
                // transaction data
                // CRITICAL: Lower threshold to 3 rows for better test coverage and faster inference
                if (rowsReadForAccountDetection >= 3 && detectedAccount != null) {
                    // For transaction tables, always infer from patterns (don't trust extracted
                    // account type from data)
                    if (isTransactionTable && detectedAccount.getAccountType() != null) {
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info(
                                    "⚠️ Transaction table detected - re-inferring account type from patterns (ignoring extracted type: {})",
                                    detectedAccount.getAccountType());
                        }
                        detectedAccount.setAccountType(
                                null); // Clear extracted type to force pattern inference
                    }

                    if (detectedAccount.getAccountType() == null) {
                        LOGGER.info(
                                "🔍 Attempting to infer account type from transaction patterns (rows analyzed: {}, debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})",
                                rowsReadForAccountDetection,
                                debitCount[0],
                                creditCount[0],
                                checkCount[0],
                                achCount[0],
                                atmCount[0],
                                transferCount[0]);
                        final String inferredType =
                                inferAccountTypeFromTransactionPatterns(
                                        debitCount[0],
                                        creditCount[0],
                                        checkCount[0],
                                        achCount[0],
                                        atmCount[0],
                                        transferCount[0]);
                        if (inferredType != null) {
                            detectedAccount.setAccountType(inferredType);
                            if (DEPOSITORY.equals(inferredType)) {
                                // Try to determine subtype (checking vs savings)
                                final String subtype =
                                        inferAccountSubtypeFromTransactionPatterns(
                                                debitCount[0],
                                                creditCount[0],
                                                checkCount[0],
                                                achCount[0],
                                                atmCount[0],
                                                transferCount[0]);
                                if (subtype != null) {
                                    detectedAccount.setAccountSubtype(subtype);
                                }
                            }
                            if (LOGGER.isInfoEnabled()) {
                                LOGGER.info(
                                        "✓ Inferred account type from transaction patterns: {} / {} (debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})",
                                        inferredType,
                                        detectedAccount.getAccountSubtype(),
                                        debitCount[0],
                                        creditCount[0],
                                        checkCount[0],
                                        achCount[0],
                                        atmCount[0],
                                        transferCount[0]);
                            }
                        } else {
                            LOGGER.warn(
                                    "⚠️ Could not infer account type from transaction patterns - user will need to select account type manually");
                        }
                    }
                } else if (rowsReadForAccountDetection < 3
                        && detectedAccount != null
                        && detectedAccount.getAccountType() == null) {
                    LOGGER.debug(
                            "⏳ Waiting for more transaction rows to infer account type (current: {}, need: 3)",
                            rowsReadForAccountDetection);
                }

                // HIGH PRIORITY FIX: Check transaction count limit
                if (result.getSuccessCount() >= MAX_TRANSACTIONS_PER_FILE) {
                    result.addError(
                            String.format(
                                    "Transaction limit exceeded. Maximum %d transactions per file. Stopping at row %d.",
                                    MAX_TRANSACTIONS_PER_FILE, rowNumber));
                    LOGGER.warn(
                            "Transaction limit reached: {} transactions. Stopping CSV parsing at row {}",
                            MAX_TRANSACTIONS_PER_FILE,
                            rowNumber);
                    break; // Stop parsing to prevent memory issues
                }

                // Parse transaction from row (pass header line for currency detection)
                try {
                    // CRITICAL: Get preview category for this transaction index if available
                    // Note: previewCategories are indexed by transaction order (0-based)
                    // For paginated imports, we only have preview categories for the first page
                    // For subsequent pages, previewCategories will be null or empty
                    com.budgetbuddy.api.ImportCategoryPreservationRequest.PreviewCategory
                            previewCategory = null;
                    String preserveAccountId = null;
                    if (previewCategories != null
                            && !previewCategories.isEmpty()
                            && result.getSuccessCount() < previewCategories.size()) {
                        previewCategory = previewCategories.get(result.getSuccessCount());
                        preserveAccountId = previewAccountId;
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "Using preview category for transaction {}: categoryPrimary='{}'",
                                    result.getSuccessCount(),
                                    previewCategory.getCategoryPrimary());
                        }
                    }

                    final ParsedTransaction transaction =
                            parseTransaction(
                                    row,
                                    rowNumber,
                                    headerLine,
                                    filename,
                                    previewCategory != null
                                            ? previewCategory.getCategoryPrimary()
                                            : null,
                                    previewCategory != null
                                            ? previewCategory.getCategoryDetailed()
                                            : null,
                                    previewCategory != null
                                            ? previewCategory.getImporterCategoryPrimary()
                                            : null,
                                    previewCategory != null
                                            ? previewCategory.getImporterCategoryDetailed()
                                            : null,
                                    preserveAccountId,
                                    result.getDetectedAccount());
                    if (transaction != null) {
                        // Set account ID if detected
                        if (matchedAccountId != null) {
                            transaction.setAccountId(matchedAccountId);
                        }
                        result.addTransaction(transaction);

                        // Track balance from row with latest date (for checking/savings/money
                        // market accounts)
                        if (balanceColumnIndex != null
                                && balanceColumnIndex < normalizedHeaders.size()
                                && transaction.getDate() != null) {
                            final String balanceHeader = normalizedHeaders.get(balanceColumnIndex);
                            final String balanceValue = row.get(balanceHeader);
                            if (balanceValue != null && !balanceValue.isBlank()) {
                                // Update if this row has a later date than the current latest
                                if (latestDateForBalance == null
                                        || transaction.getDate().isAfter(latestDateForBalance)) {
                                    latestDateBalanceValue = balanceValue.trim();
                                    latestDateForBalance = transaction.getDate();
                                    LOGGER.debug(
                                            "Tracked balance from transaction row {} (date: {}): {}",
                                            rowNumber,
                                            latestDateForBalance,
                                            latestDateBalanceValue);
                                }
                            }
                        }
                    } else {
                        result.addError(
                                String.format(
                                        "Row %d: Could not parse transaction (missing date or amount)",
                                        rowNumber));
                    }
                } catch (Exception e) {
                    result.addError(
                            String.format(
                                    "Row %d: Error parsing transaction - %s",
                                    rowNumber, e.getMessage()));
                }

                rowNumber++;
            }

            if (mismatchCount > 3) {
                LOGGER.info(
                        "Processed {} rows with column count mismatches (all handled successfully)",
                        mismatchCount);
            }

            // Final account type inference if we didn't reach 3 rows during parsing
            // This handles cases where files have fewer than 3 rows but still have transaction
            // patterns
            if (detectedAccount != null
                    && detectedAccount.getAccountType() == null
                    && rowsReadForAccountDetection > 0) {
                LOGGER.info(
                        "🔍 Final attempt to infer account type from transaction patterns (rows analyzed: {}, debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})",
                        rowsReadForAccountDetection,
                        debitCount[0],
                        creditCount[0],
                        checkCount[0],
                        achCount[0],
                        atmCount[0],
                        transferCount[0]);
                final String inferredType =
                        inferAccountTypeFromTransactionPatterns(
                                debitCount[0],
                                creditCount[0],
                                checkCount[0],
                                achCount[0],
                                atmCount[0],
                                transferCount[0]);
                if (inferredType != null) {
                    detectedAccount.setAccountType(inferredType);
                    if (DEPOSITORY.equals(inferredType)) {
                        final String subtype =
                                inferAccountSubtypeFromTransactionPatterns(
                                        debitCount[0],
                                        creditCount[0],
                                        checkCount[0],
                                        achCount[0],
                                        atmCount[0],
                                        transferCount[0]);
                        if (subtype != null) {
                            detectedAccount.setAccountSubtype(subtype);
                        }
                    }
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(
                                "✓ Inferred account type from transaction patterns (final): {} / {} (debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})",
                                inferredType,
                                detectedAccount.getAccountSubtype(),
                                debitCount[0],
                                creditCount[0],
                                checkCount[0],
                                achCount[0],
                                atmCount[0],
                                transferCount[0]);
                    }
                } else {
                    LOGGER.warn(
                            "⚠️ Could not infer account type from transaction patterns - user will need to select account type manually");
                }
            }

            // Extract balance from transaction with latest date if balance wasn't found in headers
            // This is for checking/savings/money market accounts where balance is in transaction
            // table
            if (detectedAccount != null
                    && detectedAccount.getBalance() == null
                    && latestDateBalanceValue != null
                    && balanceColumnIndex != null
                    && latestDateForBalance != null) {
                // Only extract from latest date transaction for depository accounts (checking,
                // savings, money market)
                final String accountType = detectedAccount.getAccountType();
                if (accountType != null
                        && (DEPOSITORY.equalsIgnoreCase(accountType)
                                || CHECKING.equalsIgnoreCase(accountType)
                                || SAVINGS.equalsIgnoreCase(accountType)
                                || "moneyMarket".equalsIgnoreCase(accountType)
                                || "money_market".equalsIgnoreCase(accountType))) {
                    final BigDecimal balance =
                            accountDetectionService.extractBalanceFromTransactionValue(
                                    latestDateBalanceValue);
                    if (balance != null) {
                        detectedAccount.setBalance(balance);
                        // Store the date of the balance for comparison with existing account
                        detectedAccount.setBalanceDate(latestDateForBalance);
                        LOGGER.info(
                                "✓ Extracted balance from transaction with latest date ({}): {}",
                                latestDateForBalance,
                                balance);
                    }
                }
            }

            // Set detected account and matched account ID in result
            if (detectedAccount != null) {
                result.setDetectedAccount(detectedAccount);
            }
            if (matchedAccountId != null) {
                result.setMatchedAccountId(matchedAccountId);
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Parsed CSV: {} successful, {} failed",
                        result.getSuccessCount(),
                        result.getFailureCount());
            }

        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error parsing CSV file: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to parse CSV file: " + e.getMessage());
        }

        return result;
    }

    /** Extract account number from a value using enhanced pattern matching */
    private String extractAccountNumberFromValue(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        // Try enhanced account number pattern
        final java.util.regex.Pattern accountNumPattern =
                java.util.regex.Pattern.compile(
                        "(?:(?:account|acct|card|credit\\s*card|debit\\s*card)\\s*(?:number|#|no\\.?)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*)?(?:digits?|numbers?)\\s*:?\\s*)?|(?:account|acct|card|credit\\s*card|debit\\s*card|number|#|no\\.?)\\s*:?\\s*)([*xX]{0,4}\\d{4}|\\d{4,19})",
                        java.util.regex.Pattern.CASE_INSENSITIVE);

        java.util.regex.Matcher matcher = accountNumPattern.matcher(value);
        if (matcher.find()) {
            try {
                String accountNum = matcher.group(1);
                if (accountNum != null) {
                    accountNum = accountNum.replaceAll("[*xX]", "");
                    if (accountNum.length() >= 4) {
                        // Extract last 4 digits
                        final int startIndex = Math.max(0, accountNum.length() - 4);
                        return accountNum.substring(startIndex);
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Error extracting account number from value '{}': {}",
                            value,
                            e.getMessage());
                }
            }
        }

        // Try simple 4+ digit pattern if no keyword match
        final java.util.regex.Pattern simplePattern =
                java.util.regex.Pattern.compile("(\\d{4,19})");
        matcher = simplePattern.matcher(value);
        if (matcher.find()) {
            final String accountNum = matcher.group(1);
            if (accountNum.length() >= 4) {
                // Extract last 4 digits
                final int startIndex = Math.max(0, accountNum.length() - 4);
                return accountNum.substring(startIndex);
            }
        }

        return null;
    }

    /** Extract institution name from a value */
    private String extractInstitutionFromValue(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        final String valueLower = value.toLowerCase(Locale.ROOT);

        // Check if value contains known institution keywords
        for (final String institution :
                Arrays.asList(
                        "citi",
                        AMEX,
                        "american express",
                        "chase",
                        "bofa",
                        "bank of america",
                        "wells fargo",
                        "capital one",
                        "discover",
                        "visa",
                        "mastercard",
                        "synchrony")) {
            if (valueLower.contains(institution) && value.length() > institution.length() + 5) {
                // Likely a product name - use the full value as institution name
                return value.trim();
            }
        }

        return null;
    }

    /** Extract account type from a value */
    private String extractAccountTypeFromValue(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        final String valueLower = value.toLowerCase(Locale.ROOT);

        // Map common account type values to our account types
        if (valueLower.contains(CHECKING) || valueLower.contains("check")) {
            return DEPOSITORY;
        } else if (valueLower.contains(SAVINGS) || valueLower.contains("saving")) {
            return DEPOSITORY;
        } else if (valueLower.contains("credit card")
                || valueLower.contains(CREDITCARD)
                || valueLower.contains(CARD)) {
            return "credit";
        } else if (valueLower.contains(LOAN) || valueLower.contains("mortgage")) {
            return LOAN;
        } else if (valueLower.contains("investment")
                || valueLower.contains("brokerage")
                || valueLower.contains(IRA)
                || valueLower.contains("401k")) {
            return "investment";
        }

        return null;
    }

    // Debit/Credit column-name hints, in priority order.
    // Kept small and conservative to avoid false matches — e.g. we don't
    // include bare "payment" because that's too ambiguous (could be a
    // separate "Payment Amount" column that isn't a money-in marker).
    private static final String[] DEBIT_HEADER_KEYS = {
        "debit",
        "debit amount",
        "debit_amount",
        "withdrawal",
        "withdrawals",
        "money out",
        "money_out",
        "paid out",
        "paid_out",
        "debet",
        "débit",
        "débito",
        "addebito"
    };
    private static final String[] CREDIT_HEADER_KEYS = {
        "credit",
        "credit amount",
        "credit_amount",
        DEPOSIT,
        "deposits",
        "money in",
        "money_in",
        "paid in",
        "paid_in",
        "crédit",
        "crédito",
        "accredito"
    };

    /**
     * If the row has separate debit + credit columns, parse each side and return a correctly-signed
     * BigDecimal. Returns null when either (a) the schema has only one of the two columns (so the
     * existing single-column logic is already correct) or (b) parsing both sides produces nothing
     * useful.
     */
    private BigDecimal applyDebitCreditSplit(
            final ParsedRow row,
            AmountParseResult priorResult,
            final String headerLine,
            final String filename) {
        final boolean hasDebitCol = row.hasHeader(DEBIT_HEADER_KEYS);
        final boolean hasCreditCol = row.hasHeader(CREDIT_HEADER_KEYS);
        if (!(hasDebitCol && hasCreditCol)) {
            return null; // single-column schema — prior parse is fine
        }

        final String debitStr = row.findField(DEBIT_HEADER_KEYS);
        final String creditStr = row.findField(CREDIT_HEADER_KEYS);
        final boolean hasDebit = debitStr != null && !debitStr.isEmpty();
        final boolean hasCredit = creditStr != null && !creditStr.isEmpty();

        if (!hasDebit && !hasCredit) {
            return null; // both empty — leave prior amount alone
        }

        if (hasCredit) {
            final AmountParseResult credit =
                    parseAmountWithCurrency(creditStr, headerLine, filename);
            if (credit.amount != null) {
                // Credit = money in = positive. Take absolute value so a file
                // that puts a minus sign in the credit column (rare but seen)
                // doesn't double-negate.
                return credit.amount.abs();
            }
        }

        if (hasDebit) {
            final AmountParseResult debit = parseAmountWithCurrency(debitStr, headerLine, filename);
            if (debit.amount != null) {
                // Debit = money out = negative.
                return debit.amount.abs().negate();
            }
        }

        return null;
    }

    /**
     * Reads a "logical" CSV line, concatenating physical lines until all double-quote characters
     * are balanced. RFC 4180 allows literal line breaks inside a quoted field (common in US bank
     * exports where the description wraps). The old `reader.readLine()` approach corrupted such
     * rows by splitting one logical row into multiple physical ones.
     *
     * <p>Cost: the common case (no embedded newline) does a single readLine() and one quick char
     * scan, so this is essentially free for well-formed CSVs. Cap the accumulation so a malformed
     * file with an unterminated quote doesn't swallow the rest of the document.
     */
    private static final int MAX_LOGICAL_LINE_PHYSICAL_LINES = 64;

    private String readLogicalLine(final BufferedReader reader) throws IOException {
        final String physical = reader.readLine();
        if (physical == null) {
            return null;
        }
        if (isQuoteBalanced(physical)) {
            return physical;
        }
        final StringBuilder logical = new StringBuilder(physical);
        int joined = 1;
        while (joined < MAX_LOGICAL_LINE_PHYSICAL_LINES) {
            final String next = reader.readLine();
            if (next == null) {
                // EOF mid-quote: return what we have. Downstream parsing will
                // still try; a warning will be logged when field counts mismatch.
                LOGGER.warn("CSV parse: reached EOF inside a quoted field — file may be truncated");
                break;
            }
            logical.append('\n').append(next);
            joined++;
            if (isQuoteBalanced(logical.toString())) {
                return logical.toString();
            }
        }
        if (joined >= MAX_LOGICAL_LINE_PHYSICAL_LINES) {
            LOGGER.warn(
                    "CSV parse: refused to join more than {} physical lines for one logical row — likely a malformed quote",
                    MAX_LOGICAL_LINE_PHYSICAL_LINES);
        }
        return logical.toString();
    }

    /** True when the double-quote count is even, i.e. no field is "open". */
    private static boolean isQuoteBalanced(final String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '"') {
                count++;
            }
        }
        return (count & 1) == 0;
    }

    /**
     * Normalise the incoming byte stream into something safe to decode as UTF-8.
     *
     * <p>We sniff the first 3 bytes for common Byte Order Marks. If we see UTF-16 LE/BE, we decode
     * via the right charset and re-encode as UTF-8. If we see a UTF-8 BOM, we skip it (the existing
     * `\uFEFF` handling in the header read is belt-and-braces — we'd rather strip at byte level
     * once).
     *
     * <p>Unknown encodings fall through as-is; downstream code still assumes UTF-8 but at least
     * BOM-marked files no longer produce "garbled first column".
     */
    private static InputStream detectEncodedStream(final InputStream in) throws IOException {
        final java.io.PushbackInputStream pb = new java.io.PushbackInputStream(in, 4);
        final byte[] bom = new byte[3];
        int read = 0;
        while (read < 3) {
            final int r = pb.read(bom, read, 3 - read);
            if (r < 0) {
                break;
            }
            read += r;
        }
        if (read < 3) {
            if (read > 0) {
                pb.unread(bom, 0, read);
            }
            return pb;
        }
        // UTF-8 BOM: EF BB BF — strip
        if ((bom[0] & 0xFF) == 0xEF && (bom[1] & 0xFF) == 0xBB && (bom[2] & 0xFF) == 0xBF) {
            return pb; // skip the BOM bytes
        }
        // UTF-16 BE BOM: FE FF
        if ((bom[0] & 0xFF) == 0xFE && (bom[1] & 0xFF) == 0xFF) {
            pb.unread(bom[2]);
            final byte[] rest = readAll(pb);
            final String decoded = new String(rest, StandardCharsets.UTF_16BE);
            return new java.io.ByteArrayInputStream(decoded.getBytes(StandardCharsets.UTF_8));
        }
        // UTF-16 LE BOM: FF FE
        if ((bom[0] & 0xFF) == 0xFF && (bom[1] & 0xFF) == 0xFE) {
            pb.unread(bom[2]);
            final byte[] rest = readAll(pb);
            final String decoded = new String(rest, StandardCharsets.UTF_16LE);
            return new java.io.ByteArrayInputStream(decoded.getBytes(StandardCharsets.UTF_8));
        }
        pb.unread(bom, 0, 3);
        return pb;
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Parse a single CSV line, handling quoted fields and commas */
    private List<String> parseCSVLine(final String line) {
        final List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean insideQuotes = false;
        Character previousChar = null;

        for (final char c : line.toCharArray()) {
            if (c == '"') {
                // Handle escaped quotes ("")
                if (previousChar != null && previousChar == '"' && insideQuotes) {
                    currentField.append('"');
                    previousChar = null;
                    continue;
                }
                insideQuotes = !insideQuotes;
            } else if (c == ',' && !insideQuotes) {
                fields.add(currentField.toString().trim());
                currentField = new StringBuilder();
            } else {
                currentField.append(c);
            }
            previousChar = c;
        }
        fields.add(currentField.toString().trim()); // Add last field

        return fields;
    }

    /**
     * Parse a single row into a ParsedTransaction
     *
     * @param row The parsed row data
     * @param rowNumber The row number (for error reporting)
     * @param headerLine The CSV header line (for currency detection)
     */
    ParsedTransaction parseTransaction(
            final ParsedRow row,
            final int rowNumber,
            final String headerLine,
            final String filename) {
        return parseTransaction(
                row, rowNumber, headerLine, filename, null, null, null, null, null, null);
    }

    /**
     * Parse a transaction from a CSV row
     *
     * @param row The parsed CSV row
     * @param rowNumber Row number (for error reporting)
     * @param headerLine Header line (for error reporting)
     * @param filename Filename (for account detection)
     * @param preserveCategoryPrimary Pre-parsed category primary (from preview) - if provided, skip
     *     category parsing
     * @param preserveCategoryDetailed Pre-parsed category detailed (from preview) - if provided,
     *     skip category parsing
     * @param preserveImporterCategoryPrimary Pre-parsed importer category primary (from preview) -
     *     if provided, skip category parsing
     * @param preserveImporterCategoryDetailed Pre-parsed importer category detailed (from preview)
     *     - if provided, skip category parsing
     * @param preserveAccountId Pre-parsed account ID (from preview) - used to determine if account
     *     changed
     * @param detectedAccount Detected account information (for transaction type determination)
     * @return ParsedTransaction object
     */
    ParsedTransaction parseTransaction(
            final ParsedRow row,
            final int rowNumber,
            final String headerLine,
            final String filename,
            final String preserveCategoryPrimary,
            final String preserveCategoryDetailed,
            final String preserveImporterCategoryPrimary,
            final String preserveImporterCategoryDetailed,
            String preserveAccountId,
            final AccountDetectionService.DetectedAccount detectedAccount) {
        final ParsedTransaction transaction = new ParsedTransaction();

        // Parse date (supports all major US financial institutions and account types)
        final String dateString =
                row.findField(
                        // Common formats (checking/savings/money market)
                        // Note: "trade date" is prioritized for investment accounts (checked later
                        // in investment-specific lists)
                        // CRITICAL: DETAILS is added for test compatibility - some banks use
                        // DETAILS column for dates
                        // It's checked early so date values are matched before description matching
                        DATE,
                        "transaction date",
                        "posting date",
                        "posted date",
                        "post date",
                        "trade date",
                        "settlement date",
                        DETAILS,
                        "transaction_date",
                        "posting_date",
                        "posted_date",
                        "post_date",
                        "settlement_date",
                        // Major Banks - Checking/Savings
                        "transaction date",
                        "transactiondate",
                        "posting date", // Chase
                        "date posted",
                        "dateposted",
                        "transaction date", // Bank of America
                        "transaction date",
                        DATE,
                        "posting date", // Wells Fargo
                        DATE,
                        "transaction date",
                        "posting date", // Citi (Citibank)
                        "transaction date",
                        "date posted",
                        "post date", // Capital One
                        "transaction date",
                        DATE,
                        "posting date", // US Bank
                        DATE,
                        "transaction date", // TD Bank
                        "transaction date",
                        DATE, // Chime
                        DATE,
                        "transaction date",
                        "posting date", // SoFi
                        DATE,
                        "transaction date", // Citizens Bank
                        "transaction date",
                        DATE, // Regions Bank
                        DATE,
                        "transaction date", // KeyBank
                        DATE,
                        "transaction date", // Navy Federal Credit Union
                        "transaction date",
                        DATE, // First Tech Federal
                        DATE,
                        "transaction date", // Synchrony Bank
                        // US Credit Unions
                        DATE,
                        "transaction date",
                        "posting date", // State Employees Credit Union
                        DATE,
                        "transaction date", // School First Federal Credit Union
                        DATE,
                        "transaction date",
                        "posting date", // PenFed Credit Union
                        DATE,
                        "transaction date", // Boeing Employees Credit Union
                        DATE,
                        "transaction date", // Alliant Credit Union
                        DATE,
                        "transaction date", // America First Credit Union
                        DATE,
                        "transaction date", // Golden 1 Credit Union
                        DATE,
                        "transaction date", // Mountain America Credit Union
                        DATE,
                        "transaction date", // Suncoast Credit Union
                        DATE,
                        "transaction date", // Randolph-Brooks Federal Credit Union
                        DATE,
                        "transaction date", // Lake Michigan Credit Union
                        DATE,
                        "transaction date", // Security Service Federal Credit Union
                        DATE,
                        "transaction date", // FourLeaf Federal Credit Union
                        DATE,
                        "transaction date", // Idaho Central Credit Union
                        DATE,
                        "transaction date", // Global Credit Union
                        DATE,
                        "transaction date", // Digital Federal Credit Union
                        DATE,
                        "transaction date", // GreenState Credit Union
                        // Credit Card formats (all major issuers - Global)
                        // US Credit Cards
                        "trans. date",
                        "trans date",
                        "trans_date",
                        "transaction date", // Discover, Amex
                        "post date",
                        "postdate",
                        "posted date",
                        "transaction date", // Most credit cards
                        "purchase date",
                        "purchase_date",
                        "transaction date", // Credit card purchases
                        "transaction date",
                        DATE, // Chase, Citi, Capital One credit cards
                        DATE,
                        "transaction date", // Amex, Discover, US Bank credit cards
                        DATE,
                        "transaction date", // Synchrony Bank credit cards
                        // European Credit Cards
                        DATUM,
                        DATE,
                        "transaction date",
                        "buchungsdatum",
                        "wertstellung", // Deutsche Bank, Commerzbank credit cards - prioritize
                        // DATUM over "wertstellung"
                        DATE,
                        "transaction date",
                        DATUM,
                        "valutadatum", // ING, Rabobank credit cards
                        DATE,
                        "transaction date",
                        "date de transaction",
                        "date de valeur", // BNP Paribas, Crédit Agricole credit cards
                        DATE,
                        "transaction date",
                        "fecha",
                        "fecha de valor", // BBVA, Santander credit cards
                        DATE,
                        "transaction date",
                        "data",
                        "data di valuta", // UniCredit, Intesa Sanpaolo credit cards
                        // Asian Credit Cards
                        DATE,
                        "transaction date",
                        "取引日",
                        "決済日", // Japanese credit cards (JCB, MUFG Card)
                        DATE,
                        "transaction date",
                        "交易日期",
                        "结算日期", // Chinese credit cards (UnionPay)
                        DATE,
                        "transaction date",
                        "거래일자",
                        "결제일자", // Korean credit cards (KB Card, Shinhan Card)
                        DATE,
                        "transaction date",
                        "วันที่ทำรายการ",
                        "วันที่ชำระ", // Thai credit cards
                        // Indian Credit Cards
                        DATE,
                        "transaction date",
                        "value date",
                        "posting date", // HDFC, ICICI, Axis credit cards
                        // Global Payment Networks
                        "transaction date",
                        DATE,
                        "posting date", // Mastercard, Visa (global)
                        "transaction date",
                        DATE, // American Express (global)
                        "transaction date",
                        DATE, // Discover (global)
                        "transaction date",
                        DATE, // JCB (Japan/Asia)
                        "transaction date",
                        DATE, // UnionPay (China/Asia)
                        // PayPal formats
                        DATE,
                        TIME,
                        DATETIME,
                        "transaction date", // PayPal
                        // Venmo formats
                        DATE,
                        "transaction date",
                        DATETIME,
                        TIME, // Venmo
                        // Zelle formats
                        DATE,
                        "transaction date",
                        "posted date",
                        "posting date", // Zelle (via banks)
                        // Apple Pay / Apple Card formats
                        DATE,
                        "transaction date",
                        "posted date",
                        "purchase date", // Apple Pay, Apple Card
                        // Google Pay formats
                        DATE,
                        "transaction date",
                        DATETIME,
                        TIME, // Google Pay
                        // PhonePe / UPI formats (India)
                        DATE,
                        "transaction date",
                        DATETIME,
                        TIME, // PhonePe, UPI transactions
                        // Paytm formats (India - wallet/payment service)
                        DATE,
                        "transaction date",
                        "transaction date & time",
                        DATETIME,
                        TIME, // Paytm
                        // Amazon Pay formats
                        DATE,
                        "transaction date",
                        "order date", // Amazon Pay
                        // Razor Pay formats (India)
                        DATE,
                        "transaction date",
                        "created at",
                        "created_at", // Razor Pay
                        // Indian Banks formats (DD/MM/YYYY date format)
                        DATE,
                        "transaction date",
                        "value date",
                        "posting date", // State Bank of India, ICICI, HDFC, etc.
                        "trade date",
                        DATE,
                        "transaction date",
                        "value date",
                        "settlement date", // ICICI Direct (brokerage) - prioritize "trade date"
                        // over "settlement date"
                        // European Banks formats (DD/MM/YYYY or DD.MM.YYYY date format)
                        DATE,
                        "transaction date",
                        "value date",
                        "posting date", // HSBC, Barclays, Lloyds (UK)
                        DATUM,
                        DATE,
                        "transaction date",
                        "buchungsdatum",
                        "wertstellung", // Deutsche Bank, Commerzbank (Germany) - prioritize DATUM
                        // over "wertstellung"
                        DATE,
                        "transaction date",
                        DATUM,
                        "valutadatum", // ING, Rabobank (Netherlands)
                        DATE,
                        "transaction date",
                        "date de transaction",
                        "date de valeur", // BNP Paribas, Crédit Agricole (France)
                        DATE,
                        "transaction date",
                        "fecha",
                        "fecha de valor", // BBVA, Santander (Spain)
                        DATE,
                        "transaction date",
                        "data",
                        "data di valuta", // UniCredit, Intesa Sanpaolo (Italy)
                        // Asian Banks formats
                        DATE,
                        "transaction date",
                        "取引日",
                        "決済日", // Japanese banks (MUFG, SMBC, Mizuho)
                        DATE,
                        "transaction date",
                        "交易日期",
                        "结算日期", // Chinese banks (ICBC, CCB, BOC)
                        DATE,
                        "transaction date",
                        "거래일자",
                        "결제일자", // Korean banks (KB, Shinhan, Hana)
                        DATE,
                        "transaction date",
                        "วันที่ทำรายการ",
                        "วันที่ชำระ", // Thai banks (Bangkok Bank, Kasikorn)
                        DATE,
                        "transaction date",
                        "tanggal transaksi",
                        "tanggal penyelesaian", // Indonesian banks (BCA, Mandiri)
                        DATE,
                        "transaction date",
                        "ngày giao dịch",
                        "ngày thanh toán", // Vietnamese banks (Vietcombank, BIDV)
                        // Payment network formats (Mastercard, Visa)
                        "transaction date",
                        DATE,
                        "posting date", // Mastercard, Visa (via issuing banks)
                        // Plaid (financial data aggregation - uses bank formats)
                        DATE,
                        "transaction date",
                        "authorized date",
                        "posted date", // Plaid
                        // Investment account formats (all major brokerages - Global)
                        // US Investment Platforms
                        "run date",
                        "rundate",
                        "run_date",
                        "transaction date", // Fidelity
                        "transaction date",
                        "trade date",
                        "settlement date",
                        "settlement_date", // Fidelity NetBenefits - prioritize transaction date
                        // over settlement
                        "transaction date",
                        "trade date",
                        "settlement date",
                        "settlement_date", // Vanguard, Schwab - prioritize transaction/trade date
                        // over settlement
                        "trade date",
                        "tradedate",
                        "trade_date",
                        "transaction date",
                        "settlement date", // TD Ameritrade, E*TRADE - prioritize trade date
                        DATE,
                        "transaction date",
                        "trade date",
                        "settlement date", // Robinhood - prioritize transaction/trade date
                        "transaction date",
                        "trade date",
                        "settlement date", // Morgan Stanley - prioritize transaction/trade date
                        DATE,
                        "transaction date",
                        "trade date",
                        "settlement date", // Goldman Sachs - prioritize transaction/trade date
                        "transaction date",
                        "trade date",
                        DATE,
                        "settlement date", // Generic US investment accounts - prioritize
                        // transaction/trade date
                        // European Investment Platforms
                        DATE,
                        "transaction date",
                        "trade date",
                        "settlement date", // Interactive Brokers (Europe)
                        DATE,
                        "transaction date",
                        "trade date",
                        "value date", // Degiro, eToro (Europe)
                        DATUM,
                        DATE,
                        "transaction date",
                        "buchungsdatum",
                        "wertstellung", // German brokerages (Comdirect, Consorsbank) - prioritize
                        // DATUM over "wertstellung"
                        DATE,
                        "transaction date",
                        DATUM,
                        "valutadatum", // Dutch brokerages (BinckBank, Lynx)
                        DATE,
                        "transaction date",
                        "date de transaction",
                        "date de valeur", // French brokerages (Boursorama, Binck)
                        DATE,
                        "transaction date",
                        "fecha",
                        "fecha de valor", // Spanish brokerages (SelfBank, Renta 4)
                        DATE,
                        "transaction date",
                        "data",
                        "data di valuta", // Italian brokerages (FinecoBank, Directa)
                        // Asian Investment Platforms
                        "trade date",
                        DATE,
                        "transaction date",
                        "settlement date", // ICICI Direct, HDFC Securities (India) - prioritize
                        // "trade date" over "settlement date"
                        DATE,
                        "transaction date",
                        "取引日",
                        "決済日", // Japanese brokerages (MUFG Securities, Nomura)
                        DATE,
                        "transaction date",
                        "交易日期",
                        "结算日期", // Chinese brokerages (CITIC Securities, Huatai Securities)
                        DATE,
                        "transaction date",
                        "거래일자",
                        "결제일자", // Korean brokerages (Samsung Securities, Mirae Asset)
                        DATE,
                        "transaction date",
                        "วันที่ทำรายการ",
                        "วันที่ชำระ", // Thai brokerages
                        // Global Investment Platforms
                        "trade date",
                        "transaction date",
                        "value date",
                        "settlement date", // ICICI Direct (brokerage - India)
                        "transaction date",
                        "trade date",
                        "settlement date", // Interactive Brokers (global)
                        DATE,
                        "transaction date",
                        "trade date", // eToro (global)
                        "transaction date",
                        DATE, // Generic investment accounts (global)
                        // Loan account formats (all loan types)
                        "payment date",
                        "payment_date",
                        "paymentdate", // Mortgages, auto loans, student loans
                        "due date",
                        "duedate",
                        "due_date",
                        "payment date", // Loan payments
                        "transaction date",
                        DATE, // Loan transactions
                        // HSA (Health Savings Account) formats
                        "transaction date",
                        DATE,
                        "posting date", // HSA accounts
                        "transaction date" // Generic fallback
                        );
        if (dateString == null || dateString.isEmpty()) {
            LOGGER.debug("parseTransaction Row {}: Date string is null or empty", rowNumber);
            return null; // Date is required
        }

        final LocalDate date = parseDate(dateString);
        if (date == null) {
            LOGGER.warn("Row {}: Could not parse date: {}", rowNumber, dateString);
            return null; // Date is required
        }
        transaction.setDate(date);

        // Parse amount (supports all major US financial institutions and account types)
        final String amountString =
                row.findField(
                        // Common formats (checking/savings/money market)
                        AMOUNT,
                        "transaction amount",
                        "debit",
                        "credit",
                        "transaction_amount",
                        "amount (usd)",
                        "amount(usd)",
                        "amount usd",
                        "amount_usd",
                        // Major Banks - Checking/Savings/Money Market
                        "amount (usd)",
                        "amount(usd)",
                        AMOUNT, // Chase
                        AMOUNT,
                        "debit/credit",
                        "debit",
                        "credit", // Bank of America
                        AMOUNT,
                        "debit",
                        "credit",
                        "transaction amount", // Wells Fargo
                        AMOUNT,
                        "debit amount",
                        "credit amount",
                        "transaction amount", // Citi
                        AMOUNT,
                        "transaction amount",
                        "debit",
                        "credit", // Capital One
                        AMOUNT,
                        "debit",
                        "credit", // US Bank
                        AMOUNT,
                        "debit",
                        "credit", // TD Bank
                        AMOUNT,
                        "transaction amount", // Chime
                        AMOUNT,
                        "debit",
                        "credit", // SoFi
                        AMOUNT,
                        "debit",
                        "credit", // Citizens Bank
                        AMOUNT,
                        "transaction amount", // Regions Bank
                        AMOUNT,
                        "debit",
                        "credit", // KeyBank
                        AMOUNT,
                        "transaction amount", // Navy Federal Credit Union
                        AMOUNT,
                        "debit",
                        "credit", // First Tech Federal
                        AMOUNT,
                        "debit",
                        "credit", // Synchrony Bank
                        // Credit Card formats (all major issuers - Global)
                        // US Credit Cards
                        AMOUNT,
                        "charge amount",
                        "charge_amount", // Credit cards
                        "purchase amount",
                        "purchase_amount",
                        AMOUNT, // Credit card purchases
                        "payment amount",
                        "payment_amount",
                        AMOUNT, // Credit card payments
                        "credit",
                        "debit",
                        AMOUNT, // Credit card transactions
                        AMOUNT,
                        "transaction amount", // Generic credit cards
                        // European Credit Cards
                        AMOUNT,
                        "transaction amount",
                        "betrag",
                        "soll",
                        "haben", // German credit cards
                        AMOUNT,
                        "transaction amount",
                        "bedrag",
                        "debet",
                        "credit", // Dutch credit cards
                        AMOUNT,
                        "transaction amount",
                        "montant",
                        "débit",
                        "crédit", // French credit cards
                        AMOUNT,
                        "transaction amount",
                        "importe",
                        "débito",
                        "crédito", // Spanish credit cards
                        AMOUNT,
                        "transaction amount",
                        "importo",
                        "addebito",
                        "accredito", // Italian credit cards
                        // Asian Credit Cards
                        AMOUNT,
                        "transaction amount",
                        "金額",
                        "取引金額", // Japanese credit cards
                        AMOUNT,
                        "transaction amount",
                        "金额",
                        "交易金额", // Chinese credit cards
                        AMOUNT,
                        "transaction amount",
                        "금액",
                        "거래금액", // Korean credit cards
                        AMOUNT,
                        "transaction amount",
                        "จำนวนเงิน",
                        "ยอดเงิน", // Thai credit cards
                        // Indian Credit Cards
                        AMOUNT,
                        "transaction amount",
                        "debit",
                        "credit", // HDFC, ICICI, Axis credit cards
                        "amount (inr)",
                        "amount(inr)",
                        "amount inr", // Indian credit cards with INR
                        // PayPal formats (prefer Net over Gross, as Net is the actual amount after
                        // fees)
                        "net",
                        "gross",
                        FEE,
                        AMOUNT,
                        "transaction amount", // PayPal (Net = actual amount, Gross = before fees)
                        // Venmo formats
                        AMOUNT,
                        "transaction amount",
                        "net amount",
                        TOTAL, // Venmo
                        // Zelle formats
                        AMOUNT,
                        "transaction amount",
                        "debit",
                        "credit", // Zelle (via banks)
                        // Apple Pay / Apple Card formats
                        AMOUNT,
                        "transaction amount",
                        "purchase amount", // Apple Pay, Apple Card
                        // Google Pay formats
                        AMOUNT,
                        "transaction amount",
                        "net amount", // Google Pay
                        // PhonePe / UPI formats (India)
                        AMOUNT,
                        "transaction amount",
                        "debit amount",
                        "credit amount", // PhonePe, UPI (may use INR)
                        // Paytm formats (India - wallet/payment service)
                        AMOUNT,
                        "transaction amount",
                        "transaction value",
                        "credit",
                        "debit", // Paytm (may use INR)
                        // Amazon Pay formats
                        AMOUNT,
                        "transaction amount",
                        "order amount", // Amazon Pay
                        // Razor Pay formats (India)
                        AMOUNT,
                        "transaction amount",
                        "amount paid",
                        "amount_paid", // Razor Pay
                        // Indian Banks formats (may use INR currency)
                        AMOUNT,
                        "transaction amount",
                        "debit",
                        "credit", // State Bank of India, ICICI, HDFC, etc.
                        "amount (inr)",
                        "amount(inr)",
                        "amount inr", // Indian banks with INR
                        "net amount",
                        "gross amount",
                        AMOUNT,
                        "transaction amount",
                        "credit",
                        "debit", // ICICI Direct (brokerage)
                        // European Banks formats (may use EUR, GBP, CHF, etc.)
                        AMOUNT,
                        "transaction amount",
                        "betrag",
                        "soll",
                        "haben", // German banks (Deutsche Bank, Commerzbank)
                        AMOUNT,
                        "transaction amount",
                        "bedrag",
                        "debet",
                        "credit", // Dutch banks (ING, Rabobank)
                        AMOUNT,
                        "transaction amount",
                        "montant",
                        "débit",
                        "crédit", // French banks (BNP Paribas, Crédit Agricole)
                        AMOUNT,
                        "transaction amount",
                        "importe",
                        "débito",
                        "crédito", // Spanish banks (BBVA, Santander)
                        AMOUNT,
                        "transaction amount",
                        "importo",
                        "addebito",
                        "accredito", // Italian banks (UniCredit, Intesa)
                        "amount (eur)",
                        "amount(eur)",
                        "amount eur",
                        "amount (gbp)",
                        "amount(gbp)",
                        "amount gbp", // European banks
                        // Asian Banks formats
                        AMOUNT,
                        "transaction amount",
                        "金額",
                        "取引金額", // Japanese banks
                        AMOUNT,
                        "transaction amount",
                        "金额",
                        "交易金额", // Chinese banks
                        AMOUNT,
                        "transaction amount",
                        "금액",
                        "거래금액", // Korean banks
                        AMOUNT,
                        "transaction amount",
                        "จำนวนเงิน",
                        "ยอดเงิน", // Thai banks
                        AMOUNT,
                        "transaction amount",
                        "jumlah",
                        "nominal", // Indonesian banks
                        AMOUNT,
                        "transaction amount",
                        "số tiền",
                        "giá trị", // Vietnamese banks
                        // Payment network formats (Mastercard, Visa)
                        AMOUNT,
                        "transaction amount",
                        "debit",
                        "credit", // Mastercard, Visa (via issuing banks)
                        // Plaid formats
                        AMOUNT,
                        "transaction amount",
                        "authorized amount", // Plaid
                        // Investment account formats (all major brokerages - Global)
                        // US Investment Platforms
                        "amount ($)",
                        "amount($)",
                        AMOUNT,
                        "net amount", // Fidelity, Vanguard
                        AMOUNT,
                        "net amount",
                        "net_amount",
                        TOTAL, // Schwab, TD Ameritrade
                        TOTAL,
                        "total amount",
                        "total_amount",
                        AMOUNT, // Investment transactions
                        "proceeds",
                        "cost basis",
                        "cost_basis",
                        AMOUNT, // Investment sales
                        AMOUNT,
                        "net amount", // Robinhood
                        AMOUNT,
                        "net amount",
                        TOTAL, // Morgan Stanley, Goldman Sachs
                        AMOUNT,
                        "net amount",
                        "transaction amount",
                        "contribution",
                        "withdrawal", // Fidelity NetBenefits
                        AMOUNT,
                        "transaction amount", // E*TRADE, generic US investments
                        // European Investment Platforms
                        AMOUNT,
                        "net amount",
                        "betrag",
                        "nettobetrag", // German brokerages
                        AMOUNT,
                        "net amount",
                        "bedrag",
                        "netto bedrag", // Dutch brokerages
                        AMOUNT,
                        "net amount",
                        "montant",
                        "montant net", // French brokerages
                        AMOUNT,
                        "net amount",
                        "importe",
                        "importe neto", // Spanish brokerages
                        AMOUNT,
                        "net amount",
                        "importo",
                        "importo netto", // Italian brokerages
                        "amount (eur)",
                        "amount(eur)",
                        "amount eur", // European brokerages with EUR
                        "amount (gbp)",
                        "amount(gbp)",
                        "amount gbp", // UK brokerages with GBP
                        "amount (chf)",
                        "amount(chf)",
                        "amount chf", // Swiss brokerages with CHF
                        // Asian Investment Platforms
                        AMOUNT,
                        "net amount",
                        "transaction amount",
                        "credit",
                        "debit", // ICICI Direct, HDFC Securities (India)
                        "amount (inr)",
                        "amount(inr)",
                        "amount inr", // Indian brokerages with INR
                        AMOUNT,
                        "net amount",
                        "金額",
                        "取引金額", // Japanese brokerages
                        AMOUNT,
                        "net amount",
                        "金额",
                        "交易金额", // Chinese brokerages
                        AMOUNT,
                        "net amount",
                        "금액",
                        "거래금액", // Korean brokerages
                        AMOUNT,
                        "net amount",
                        "จำนวนเงิน",
                        "ยอดเงิน", // Thai brokerages
                        // Global Investment Platforms
                        "net amount",
                        "gross amount",
                        AMOUNT,
                        "transaction amount", // ICICI Direct (brokerage - India)
                        AMOUNT,
                        "net amount",
                        TOTAL, // Interactive Brokers (global)
                        AMOUNT,
                        "transaction amount", // eToro (global)
                        AMOUNT,
                        "transaction amount", // Generic investment accounts (global)
                        // Loan account formats (all loan types)
                        "payment amount",
                        "payment_amount",
                        "payment", // Loan payments
                        "principal",
                        INTEREST,
                        AMOUNT, // Loan components
                        "total payment",
                        "total_payment",
                        "payment", // Total loan payment
                        "escrow",
                        "escrow payment",
                        "escrow_payment", // Mortgage escrow
                        "payment",
                        AMOUNT, // Generic loan payments
                        // HSA (Health Savings Account) formats
                        AMOUNT,
                        "transaction amount",
                        "debit",
                        "credit", // HSA accounts
                        AMOUNT // Generic fallback
                        );
        if (amountString == null || amountString.isEmpty()) {
            return null; // Amount is required
        }

        // Parse amount with currency detection (use header line and filename for better detection)
        final AmountParseResult amountResult =
                parseAmountWithCurrency(amountString, headerLine, filename);
        if (amountResult.amount == null) {
            LOGGER.warn("Row {}: Could not parse amount: {}", rowNumber, amountString);
            return null; // Amount is required
        }
        BigDecimal amount = amountResult.amount;

        // Debit/Credit split-column fix. Many bank exports (BoA, Wells Fargo,
        // older Chase, most UK/EU banks, UPI/PhonePe India) use two columns —
        // one for money out ("Debit"/"Withdrawal"/"Debit Amount") and one for
        // money in ("Credit"/"Deposit"/"Credit Amount") — with the amount
        // magnitude in whichever column applies and the sign *implicit*.
        //
        // The prior parser returned the first non-empty match from `findField`
        // without caring which column it came from, so a $5 coffee on a
        // checking account was parsed as +$5 (income-shaped) instead of -$5.
        //
        // If both a debit-shaped and a credit-shaped header exist in the
        // schema, we pull the debit and credit cells for THIS row and combine:
        //   debit non-empty → negate (money out)
        //   credit non-empty → keep positive (money in)
        //   both empty → already handled above
        //   both non-empty → credit wins (rare; usually a parse artifact)
        // We only override when it improves the signal — a single-column
        // "Amount" file is untouched.
        final BigDecimal splitAmount =
                applyDebitCreditSplit(row, amountResult, headerLine, filename);
        if (splitAmount != null) {
            amount = splitAmount;
        }

        // CRITICAL: Reverse sign for credit card accounts
        // Credit card imports typically have expenses as positive, but backend stores them as
        // negative
        // For credit card accounts: reverse the sign to match backend convention
        if (detectedAccount != null && detectedAccount.getAccountType() != null) {
            final String accountType = detectedAccount.getAccountType().toLowerCase(Locale.ROOT);
            if (accountType.contains("credit")
                    || CREDITCARD.equals(accountType)
                    || "credit_card".equals(accountType)) {
                amount = amount.negate();
                LOGGER.debug(
                        "Reversed sign for credit card account: {} → {}",
                        amountResult.amount,
                        amount);
            }
        }

        LOGGER.debug("Setting amount on parsed transaction: {}", amount);
        transaction.setAmount(amount);
        transaction.setCurrencyCode(amountResult.currencyCode);
        transaction.setFlowDirection(FlowDirection.fromSignedAmount(amount));

        // Parse description (supports all major US financial institutions and account types)
        final String description =
                row.findField(
                        // Common formats (checking/savings/money market)
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        MERCHANT,
                        "payee",
                        NAME,
                        "payee name",
                        "transaction description",
                        "transaction_description",
                        "transaction details",
                        // Major Banks - Checking/Savings/Money Market
                        DESCRIPTION,
                        DETAILS,
                        MEMO, // Chase
                        DESCRIPTION,
                        "payee",
                        MEMO, // Bank of America
                        DESCRIPTION,
                        MEMO,
                        DETAILS, // Wells Fargo
                        DESCRIPTION,
                        "transaction description",
                        MEMO, // Citi
                        DESCRIPTION,
                        MERCHANT,
                        MEMO, // Capital One
                        DESCRIPTION,
                        MEMO,
                        "transaction description", // US Bank
                        DESCRIPTION,
                        MEMO, // TD Bank
                        DESCRIPTION,
                        MERCHANT,
                        MEMO, // Chime
                        DESCRIPTION,
                        MEMO,
                        "transaction description", // SoFi
                        DESCRIPTION,
                        MEMO, // Citizens Bank
                        DESCRIPTION,
                        MEMO, // Regions Bank
                        DESCRIPTION,
                        MEMO, // KeyBank
                        DESCRIPTION,
                        MEMO, // Navy Federal Credit Union
                        DESCRIPTION,
                        MEMO, // First Tech Federal
                        DESCRIPTION,
                        MEMO, // Synchrony Bank
                        // US Credit Unions
                        DESCRIPTION,
                        MEMO, // State Employees Credit Union
                        DESCRIPTION,
                        MEMO, // School First Federal Credit Union
                        DESCRIPTION,
                        MEMO, // PenFed Credit Union
                        DESCRIPTION,
                        MEMO, // Boeing Employees Credit Union
                        DESCRIPTION,
                        MEMO, // Alliant Credit Union
                        DESCRIPTION,
                        MEMO, // America First Credit Union
                        DESCRIPTION,
                        MEMO, // Golden 1 Credit Union
                        DESCRIPTION,
                        MEMO, // Mountain America Credit Union
                        DESCRIPTION,
                        MEMO, // Suncoast Credit Union
                        DESCRIPTION,
                        MEMO, // Randolph-Brooks Federal Credit Union
                        DESCRIPTION,
                        MEMO, // Lake Michigan Credit Union
                        DESCRIPTION,
                        MEMO, // Security Service Federal Credit Union
                        DESCRIPTION,
                        MEMO, // FourLeaf Federal Credit Union
                        DESCRIPTION,
                        MEMO, // Idaho Central Credit Union
                        DESCRIPTION,
                        MEMO, // Global Credit Union
                        DESCRIPTION,
                        MEMO, // Digital Federal Credit Union
                        DESCRIPTION,
                        MEMO, // GreenState Credit Union
                        // Credit Card formats (all major issuers)
                        DESCRIPTION,
                        "merchant name",
                        "merchant_name", // Credit cards
                        MERCHANT,
                        "vendor",
                        STORE,
                        DESCRIPTION, // Credit card merchants
                        "transaction description",
                        "purchase description", // Credit card purchases
                        "category",
                        "transaction category",
                        DESCRIPTION, // Some credit cards put description in category
                        DESCRIPTION,
                        MERCHANT, // Generic credit cards
                        // PayPal formats
                        NAME,
                        DESCRIPTION,
                        TYPE,
                        "from email address",
                        "to email address",
                        "item title", // PayPal
                        "note",
                        MEMO,
                        DESCRIPTION, // PayPal notes
                        // Venmo formats
                        DESCRIPTION,
                        "note",
                        MEMO,
                        "what for",
                        "note to self", // Venmo
                        FROM,
                        "to",
                        NAME, // Venmo sender/recipient
                        // Zelle formats
                        DESCRIPTION,
                        MEMO,
                        "note",
                        "transaction description", // Zelle (via banks)
                        RECIPIENT,
                        SENDER,
                        NAME, // Zelle recipient/sender
                        // Apple Pay / Apple Card formats
                        DESCRIPTION,
                        MERCHANT,
                        "merchant name",
                        "transaction description", // Apple Pay, Apple Card
                        "location",
                        STORE, // Apple Pay location/store
                        // Google Pay formats
                        DESCRIPTION,
                        MERCHANT,
                        "merchant name",
                        "note", // Google Pay
                        RECIPIENT,
                        SENDER,
                        NAME, // Google Pay P2P
                        // PhonePe / UPI formats (India)
                        DESCRIPTION,
                        "remarks",
                        "note",
                        "transaction description", // PhonePe, UPI
                        "beneficiary name",
                        "payer name",
                        "upi id", // UPI transaction details
                        // Paytm formats (India - wallet/payment service)
                        DESCRIPTION,
                        "transaction description",
                        "merchant name",
                        MERCHANT, // Paytm
                        "note",
                        "remarks",
                        "transaction type",
                        "payment method", // Paytm transaction details
                        // Amazon Pay formats
                        DESCRIPTION,
                        MERCHANT,
                        "order description", // Amazon Pay
                        // Razor Pay formats (India)
                        DESCRIPTION,
                        "notes",
                        DESCRIPTION, // Razor Pay
                        // Indian Banks formats
                        DESCRIPTION,
                        "narration",
                        "particulars",
                        "transaction description", // State Bank of India, ICICI, HDFC, etc.
                        "beneficiary",
                        "payer",
                        "remarks", // Indian bank transaction details
                        // European Banks formats
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "verwendungszweck",
                        "zweck", // German banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "omschrijving",
                        "beschrijving", // Dutch banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "libellé",
                        "détails", // French banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "concepto",
                        "detalles", // Spanish banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "causale",
                        "dettagli", // Italian banks
                        // Asian Banks formats
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "摘要",
                        "备注", // Chinese banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "摘要",
                        "備考", // Japanese banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "내역",
                        "비고", // Korean banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "รายละเอียด",
                        "หมายเหตุ", // Thai banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "keterangan",
                        "catatan", // Indonesian banks
                        DESCRIPTION,
                        MEMO,
                        DETAILS,
                        "mô tả",
                        "ghi chú", // Vietnamese banks
                        // Payment network formats (Mastercard, Visa)
                        DESCRIPTION,
                        "merchant name",
                        MERCHANT, // Mastercard, Visa (via issuing banks)
                        // Plaid formats
                        DESCRIPTION,
                        "merchant name",
                        NAME,
                        "original description", // Plaid
                        // Investment account formats (all major brokerages)
                        "transaction description",
                        "transaction_description", // Fidelity, Vanguard
                        ACTION,
                        "transaction type",
                        "transaction_type", // Investment actions
                        "security description",
                        "security_description",
                        SYMBOL, // Investment securities
                        DESCRIPTION,
                        MEMO,
                        "transaction description", // Generic investment description
                        DESCRIPTION,
                        MEMO, // Robinhood, Morgan Stanley, Goldman Sachs
                        DESCRIPTION,
                        "transaction description", // E*TRADE, Schwab, TD Ameritrade
                        "transaction description",
                        DESCRIPTION,
                        "action description", // Fidelity NetBenefits
                        "security name",
                        "security",
                        SYMBOL,
                        "company name",
                        "transaction description", // Fidelity NetBenefits - prioritize "security
                        // name"
                        "particulars",
                        "transaction description",
                        DESCRIPTION,
                        "narration", // ICICI Direct (brokerage) - prioritize "particulars"
                        "particulars",
                        "security name",
                        SYMBOL,
                        "scrip name",
                        "company name", // ICICI Direct (brokerage) - prioritize "particulars"
                        // Loan account formats (all loan types)
                        DESCRIPTION,
                        "transaction description",
                        MEMO, // Loan transactions
                        "payment type",
                        "payment_type",
                        DESCRIPTION, // Loan payment types
                        MEMO,
                        "notes",
                        DESCRIPTION, // Loan notes
                        // HSA (Health Savings Account) formats
                        DESCRIPTION,
                        MEMO,
                        "transaction description", // HSA accounts
                        DESCRIPTION // Generic fallback
                        );
        transaction.setDescription(
                description != null && !description.isEmpty()
                        ? description
                        : "Imported Transaction");

        // Parse location (if provided separately)
        final String location =
                row.findField("location", STORE, "city", "state", "branch", "address");
        transaction.setLocation(location);

        // Parse merchant name (supports all account types from all institutions)
        final String merchantName =
                row.findField(
                        // Common formats (checking/savings/money market)
                        MERCHANT,
                        "merchant name",
                        "merchant_name",
                        "payee",
                        NAME,
                        "payee_name",
                        // Credit card specific (all issuers)
                        MERCHANT,
                        "vendor",
                        STORE,
                        "merchant name",
                        "merchant_name",
                        // Synchrony Bank
                        MERCHANT,
                        "merchant name",
                        DESCRIPTION,
                        // PayPal formats
                        NAME,
                        "from email address",
                        "to email address",
                        "item title", // PayPal
                        // Venmo formats
                        FROM,
                        "to",
                        NAME,
                        RECIPIENT,
                        SENDER, // Venmo
                        // Zelle formats
                        RECIPIENT,
                        SENDER,
                        NAME,
                        MERCHANT, // Zelle
                        // Apple Pay / Apple Card formats
                        MERCHANT,
                        "merchant name",
                        STORE,
                        "location", // Apple Pay, Apple Card
                        // Google Pay formats
                        MERCHANT,
                        "merchant name",
                        RECIPIENT,
                        SENDER, // Google Pay
                        // PhonePe / UPI formats (India)
                        "beneficiary name",
                        "payer name",
                        MERCHANT, // PhonePe, UPI
                        // Paytm formats (India - wallet/payment service)
                        "merchant name",
                        MERCHANT,
                        "shop name",
                        "store name", // Paytm
                        "business name",
                        RECIPIENT,
                        SENDER,
                        NAME, // Paytm P2P transactions
                        // Amazon Pay formats
                        MERCHANT,
                        "seller",
                        STORE, // Amazon Pay
                        // Razor Pay formats (India)
                        MERCHANT,
                        "customer", // Razor Pay
                        // Indian Banks formats
                        "beneficiary",
                        "payer",
                        MERCHANT, // State Bank of India, ICICI, HDFC, etc.
                        "beneficiary name",
                        "payer name",
                        "counter party", // ICICI Direct (brokerage)
                        // Payment network formats (Mastercard, Visa)
                        "merchant name",
                        MERCHANT,
                        "vendor", // Mastercard, Visa (via issuing banks)
                        // Plaid formats
                        "merchant name",
                        MERCHANT,
                        NAME, // Plaid
                        // Investment specific (all brokerages)
                        "security description",
                        "security_description",
                        SYMBOL,
                        "company name",
                        "security",
                        SYMBOL, // Fidelity, Vanguard, Schwab, etc.
                        "security name",
                        "security_name", // Fidelity NetBenefits - prioritize security name for
                        // merchant
                        // Generic
                        "payee",
                        NAME,
                        MERCHANT);
        // Normalize merchant name (lenient normalization for better matching)
        if (merchantName != null && !merchantName.isBlank()) {
            transaction.setMerchantName(StringUtils.normalizeMerchantName(merchantName));
        } else {
            transaction.setMerchantName(merchantName);
        }

        // Parse category (supports all account types from all institutions)
        final String categoryString =
                row.findField(
                        // Common formats (checking/savings/money market)
                        "category",
                        "transaction category",
                        TYPE,
                        "transaction_type",
                        // Credit card specific (all issuers)
                        "category",
                        "merchant category",
                        "merchant_category",
                        "mcc",
                        "mcc code",
                        "transaction category", // Chase, Amex, Discover, etc.
                        // PayPal specific
                        TYPE,
                        "category",
                        "transaction type", // PayPal transaction types
                        // Synchrony Bank specific
                        "category",
                        TYPE,
                        "transaction category", // Synchrony Bank
                        // Venmo specific
                        TYPE,
                        "category",
                        "transaction type", // Venmo transaction types
                        // Zelle specific
                        "category",
                        TYPE,
                        "transaction type", // Zelle (via banks)
                        // Apple Pay / Apple Card specific
                        "category",
                        "merchant category",
                        "transaction category", // Apple Pay, Apple Card
                        // Investment specific (all brokerages)
                        "transaction type",
                        "transaction_type",
                        ACTION,
                        "security type",
                        "security_type",
                        TYPE,
                        ACTION, // Fidelity, Vanguard, Schwab, Robinhood, etc.
                        "transaction type",
                        TYPE,
                        ACTION,
                        "contribution type",
                        "withdrawal type", // Fidelity NetBenefits
                        "transaction type",
                        TYPE,
                        ACTION,
                        "buy/sell",
                        "transaction code", // ICICI Direct (brokerage)
                        // Loan specific (all loan types)
                        "payment type",
                        "payment_type",
                        "transaction type",
                        // HSA specific
                        "category",
                        TYPE,
                        "transaction type",
                        // Generic
                        TYPE,
                        "category",
                        "transaction category");
        // Parse payment channel first (needed for enhanced category detection)
        final String paymentChannel =
                row.findField("payment channel", "payment type", "payment_channel", "payment_type");
        transaction.setPaymentChannel(paymentChannel);

        // CRITICAL: Parse debit/credit indicator from Details or Type column
        // This helps determine transaction type more accurately than just amount sign
        final String transactionTypeIndicator =
                row.findField(
                        DETAILS,
                        TYPE,
                        "transaction type",
                        "transaction_type",
                        "debit/credit",
                        "debit credit",
                        "dr/cr",
                        "dr cr",
                        "debit",
                        "credit",
                        "dr",
                        "cr",
                        // Account-specific indicators
                        "transaction code",
                        "transaction_code",
                        "txn code",
                        "txn_code",
                        // Investment account indicators
                        ACTION,
                        "transaction action",
                        "buy/sell",
                        // Loan account indicators
                        "payment type",
                        "payment_type");

        // Capture account type context for parsing only (type/category determination happens on
        // creation)
        String accountTypeString = null;
        String accountSubtypeString = null;
        if (detectedAccount != null) {
            accountTypeString = detectedAccount.getAccountType();
            accountSubtypeString = detectedAccount.getAccountSubtype();
        }

        // Preview-category preservation: when the client supplied a
        // pre-confirmed category for this row (from the import preview step
        // the user already reviewed), trust it and skip re-parsing. This
        // keeps GROCERIES rows the user accepted in preview from flipping
        // to a re-detected category during final ingest.
        String parsedCategory;
        final String parsedImporterCategory;
        final String parsedImporterCategoryDetailed;
        if (preserveCategoryPrimary != null && !preserveCategoryPrimary.isBlank()) {
            parsedCategory = preserveCategoryPrimary;
            parsedImporterCategory =
                    preserveImporterCategoryPrimary != null
                            ? preserveImporterCategoryPrimary
                            : preserveCategoryPrimary;
            parsedImporterCategoryDetailed =
                    preserveImporterCategoryDetailed != null
                            ? preserveImporterCategoryDetailed
                            : (preserveCategoryDetailed != null
                                    ? preserveCategoryDetailed
                                    : parsedImporterCategory);
        } else {
            parsedCategory =
                    importCategoryParser.parseCategory(
                            categoryString,
                            description,
                            merchantName,
                            amount,
                            paymentChannel,
                            transactionTypeIndicator,
                            null,
                            accountTypeString,
                            accountSubtypeString);
            if (parsedCategory == null || parsedCategory.isBlank()) {
                parsedCategory = OTHER;
            }
            parsedImporterCategory = parsedCategory;
            parsedImporterCategoryDetailed = parsedCategory;
        }

        transaction.setImporterCategoryPrimary(parsedImporterCategory);
        transaction.setImporterCategoryDetailed(parsedImporterCategoryDetailed);
        transaction.setCategoryPrimary(parsedCategory);
        transaction.setCategoryDetailed(
                preserveCategoryDetailed != null && !preserveCategoryDetailed.isBlank()
                        ? preserveCategoryDetailed
                        : parsedCategory);

        // CRITICAL: Store transactionTypeIndicator for preview response and recalculation
        transaction.setTransactionTypeIndicator(transactionTypeIndicator);

        return transaction;
    }

    /** Parse date string in various formats (mirrors iOS app) */
    private LocalDate parseDate(final String dateString) {
        if (dateString == null || dateString.isBlank()) {
            LOGGER.debug("parseDate: Input dateString is null or empty");
            return null;
        }

        final String trimmed = dateString.trim();
        LOGGER.debug("parseDate: Parsing date string '{}'", trimmed);

        // CRITICAL FIX: Smart detection for ambiguous MM/dd vs dd/MM formats
        // If date contains "/" and matches pattern like "MM/dd" or "dd/MM", use smart detection
        if (trimmed.contains("/") && trimmed.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
            final String[] parts = trimmed.split("/");
            if (parts.length == 3) {
                try {
                    final int first = Integer.parseInt(parts[0]);
                    final int second = Integer.parseInt(parts[1]);
                    final int year = Integer.parseInt(parts[2]);

                    // If first number > 12, it must be MM/dd/yyyy (US format)
                    if (first > 12 && second <= 12) {
                        LOGGER.debug(
                                "parseDate: Smart detection - first number {} > 12, using MM/dd/yyyy format",
                                first);
                        try {
                            final LocalDate parsed = LocalDate.of(year, first, second);
                            LOGGER.debug(
                                    "parseDate: Successfully parsed '{}' as MM/dd/yyyy -> {}",
                                    trimmed,
                                    parsed);
                            return parsed;
                        } catch (Exception e) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                        "parseDate: Failed to parse '{}' as MM/dd/yyyy: {}",
                                        trimmed,
                                        e.getMessage());
                            }
                        }
                    }
                    // If second number > 12, it must be dd/MM/yyyy (European format)
                    else if (second > 12 && first <= 12) {
                        LOGGER.debug(
                                "parseDate: Smart detection - second number {} > 12, using dd/MM/yyyy format",
                                second);
                        try {
                            final LocalDate parsed = LocalDate.of(year, second, first);
                            LOGGER.debug(
                                    "parseDate: Successfully parsed '{}' as dd/MM/yyyy -> {}",
                                    trimmed,
                                    parsed);
                            return parsed;
                        } catch (Exception e) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                        "parseDate: Failed to parse '{}' as dd/MM/yyyy: {}",
                                        trimmed,
                                        e.getMessage());
                            }
                        }
                    }
                    // If both numbers <= 12, it's ambiguous - prefer US format (MM/dd/yyyy) for
                    // US-based users
                    // This is a reasonable default for most users
                    else if (first <= 12 && second <= 12) {
                        LOGGER.debug(
                                "parseDate: Ambiguous date '{}' (both numbers <= 12), preferring MM/dd/yyyy (US format)",
                                trimmed);
                        // Try US format first
                        try {
                            final LocalDate parsed = LocalDate.of(year, first, second);
                            LOGGER.debug(
                                    "parseDate: Successfully parsed '{}' as MM/dd/yyyy (preferred) -> {}",
                                    trimmed,
                                    parsed);
                            return parsed;
                        } catch (Exception e) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug(
                                        "parseDate: Failed to parse '{}' as MM/dd/yyyy: {}",
                                        trimmed,
                                        e.getMessage());
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    LOGGER.debug(
                            "parseDate: Could not parse numbers from '{}', falling back to standard parsing",
                            trimmed);
                }
            }
        }

        // Try each formatter (fallback to standard parsing)
        for (final DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                final LocalDate parsed = LocalDate.parse(trimmed, formatter);
                LOGGER.debug(
                        "parseDate: Successfully parsed '{}' with formatter '{}' -> {}",
                        trimmed,
                        formatter,
                        parsed);
                return parsed;
            } catch (DateTimeParseException e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "parseDate: Failed to parse '{}' with formatter '{}': {}",
                            trimmed,
                            formatter,
                            e.getMessage());
                }
                // Continue to next formatter
            }
        }

        // Try ISO 8601 with time component (extract date part only)
        // Handle edge case: string might be shorter than 10 characters
        if (trimmed.length() >= 10) {
            try {
                final String datePart = trimmed.substring(0, 10);
                final LocalDate parsed =
                        LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE);
                LOGGER.debug(
                        "parseDate: Successfully parsed '{}' (extracted date part '{}') as ISO_LOCAL_DATE -> {}",
                        trimmed,
                        datePart,
                        parsed);
                return parsed;
            } catch (Exception e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "parseDate: Failed to parse '{}' as ISO_LOCAL_DATE: {}",
                            trimmed,
                            e.getMessage());
                }
                // Ignore and continue
            }
        }

        // Try to extract date from datetime strings (e.g., "19/12/2025 14:30" -> "19/12/2025")
        // Look for space or 'T' separator and try parsing the date part before it
        final int spaceIndex = trimmed.indexOf(' ');
        final int tIndex = trimmed.indexOf('T');
        final int separatorIndex =
                spaceIndex >= 0 && tIndex >= 0
                        ? Math.min(spaceIndex, tIndex)
                        : (spaceIndex >= 0 ? spaceIndex : tIndex);
        if (separatorIndex > 0) {
            final String datePart = trimmed.substring(0, separatorIndex);
            for (final DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    final LocalDate parsed = LocalDate.parse(datePart, formatter);
                    LOGGER.debug(
                            "parseDate: Successfully parsed '{}' (extracted date part '{}') with formatter '{}' -> {}",
                            trimmed,
                            datePart,
                            formatter,
                            parsed);
                    return parsed;
                } catch (DateTimeParseException e) {
                    // Continue to next formatter
                }
            }
        }

        LOGGER.warn("parseDate: Could not parse date string '{}' with any formatter", trimmed);
        return null;
    }

    /**
     * Parse amount string, handling various formats (mirrors iOS app) Returns a pair: [amount,
     * currencyCode]
     */
    private static class AmountParseResult {
        BigDecimal amount;
        String currencyCode;

        AmountParseResult(final BigDecimal amount, final String currencyCode) {
            this.amount = amount;
            this.currencyCode = currencyCode;
        }
    }

    /**
     * Detect currency from amount string or header Supports: USD ($), INR (₹, Rs, Rs.), EUR (€),
     * GBP (£), CAD (C$), AUD (A$), etc.
     */
    /**
     * Symbol-or-code → currency code lookup for the easy cases. Each pair is "any match in
     * amount/upper → return code". For ambiguous ¥ (CNY vs JPY) and the header-only fallback, use
     * the dedicated helpers below.
     */
    private static final List<CurrencySymbolRule> CURRENCY_SYMBOL_RULES =
            List.of(
                    new CurrencySymbolRule("INR", "₹", "RS", "INR"),
                    new CurrencySymbolRule("USD", "$", "USD"),
                    new CurrencySymbolRule("EUR", "€", "EUR"),
                    new CurrencySymbolRule("GBP", "£", "GBP"),
                    new CurrencySymbolRule("CAD", "C$", "CAD"),
                    new CurrencySymbolRule("AUD", "A$", "AUD"),
                    // ¥ (CNY vs JPY) handled separately by resolveYenCurrency
                    new CurrencySymbolRule("CHF", "Fr", "CHF"),
                    new CurrencySymbolRule("SEK", "kr", "SEK"),
                    new CurrencySymbolRule("NOK", "kr", "NOK"),
                    new CurrencySymbolRule("DKK", "kr", "DKK"),
                    new CurrencySymbolRule("PLN", "zł", "PLN"),
                    new CurrencySymbolRule("SGD", "S$", "SGD"),
                    new CurrencySymbolRule("HKD", "HK$", "HKD"),
                    new CurrencySymbolRule("KRW", "₩", "KRW"),
                    new CurrencySymbolRule("THB", "฿", "THB"),
                    new CurrencySymbolRule("IDR", "Rp", "IDR"),
                    new CurrencySymbolRule("VND", "₫", "VND"),
                    new CurrencySymbolRule("MYR", "RM", "MYR"),
                    new CurrencySymbolRule("PHP", "₱", "PHP"),
                    new CurrencySymbolRule("BRL", "R$", "BRL"),
                    new CurrencySymbolRule("MXN", "Mex$", "MXN"),
                    new CurrencySymbolRule("ZAR", "R", "ZAR"),
                    new CurrencySymbolRule("AUD", "A$", "AUD"),
                    new CurrencySymbolRule("NZD", "NZ$", "NZD"));

    /** Header-only fallback for when no symbol or code appeared in the amount string. */
    private static final List<CurrencySymbolRule> CURRENCY_HEADER_RULES =
            List.of(
                    new CurrencySymbolRule("INR", null, "INR", "RUPEES", "RS"),
                    new CurrencySymbolRule("USD", null, "USD", "DOLLARS"),
                    new CurrencySymbolRule("EUR", null, "EUR", "EUROS"),
                    new CurrencySymbolRule("GBP", null, "GBP", "POUNDS"),
                    new CurrencySymbolRule("CHF", null, "CHF", "FRANCS"),
                    new CurrencySymbolRule("JPY", null, "JPY", "YEN"),
                    new CurrencySymbolRule("CNY", null, "CNY", "YUAN"));

    /** Carrier: a currency code + optional symbol + 1+ upper-case codes/aliases to look for. */
    private record CurrencySymbolRule(String code, String symbol, String upper1, String... rest) {}

    private String detectCurrency(
            String amountString, final String headerLine, final String filename) {
        if (amountString == null) {
            amountString = "";
        }
        final String upper = amountString.toUpperCase(Locale.ROOT);
        final String headerUpper = headerLine != null ? headerLine.toUpperCase(Locale.ROOT) : "";
        final String filenameUpper = filename != null ? filename.toUpperCase(Locale.ROOT) : "";

        final String yen = resolveYenCurrency(amountString, upper, headerUpper, filenameUpper);
        if (yen != null) {
            return yen;
        }
        final String bySymbol = matchByAmountSymbolOrCode(amountString, upper);
        if (bySymbol != null) {
            return bySymbol;
        }
        final String byHeader = matchByHeader(headerUpper);
        if (byHeader != null) {
            return byHeader;
        }
        return "USD";
    }

    /**
     * Walk CURRENCY_SYMBOL_RULES in order — return the first rule whose symbol appears in the
     * amount string OR whose code (USD / EUR / ...) appears in the upper-cased amount.
     */
    private static String matchByAmountSymbolOrCode(final String amountString, final String upper) {
        for (final CurrencySymbolRule rule : CURRENCY_SYMBOL_RULES) {
            if (rule.symbol() != null && amountString.contains(rule.symbol())) {
                return rule.code();
            }
            if (upper.contains(rule.upper1())) {
                return rule.code();
            }
            for (final String alt : rule.rest()) {
                if (alt != null && upper.contains(alt)) {
                    return rule.code();
                }
            }
        }
        return null;
    }

    /** Header-only fallback: walk CURRENCY_HEADER_RULES and return the first matching code. */
    private static String matchByHeader(final String headerUpper) {
        for (final CurrencySymbolRule rule : CURRENCY_HEADER_RULES) {
            if (headerUpper.contains(rule.upper1())) {
                return rule.code();
            }
            for (final String alt : rule.rest()) {
                if (alt != null && headerUpper.contains(alt)) {
                    return rule.code();
                }
            }
        }
        return null;
    }

    /**
     * ¥ is ambiguous between Chinese yuan and Japanese yen. Disambiguate via filename / header
     * context (bank names, country names, CJK characters). Returns CNY, JPY, or null if there's no
     * ¥/CNY/JPY/YEN/YUAN signal at all.
     */
    private static String resolveYenCurrency(
            final String amountString,
            final String upper,
            final String headerUpper,
            final String filenameUpper) {
        final boolean hasYenSignal =
                amountString.contains("¥")
                        || upper.contains("CNY")
                        || upper.contains("JPY")
                        || upper.contains("YUAN")
                        || upper.contains("YEN");
        if (!hasYenSignal) {
            return null;
        }
        if (matchesChineseContext(upper, headerUpper, filenameUpper)) {
            return "CNY";
        }
        if (matchesJapaneseContext(upper, headerUpper, filenameUpper)) {
            return "JPY";
        }
        // Default to JPY — ¥ is more commonly used for yen.
        return "JPY";
    }

    private static boolean matchesChineseContext(
            final String upper, final String headerUpper, final String filenameUpper) {
        return upper.contains("CNY")
                || upper.contains("YUAN")
                || headerUpper.contains("CNY")
                || headerUpper.contains("YUAN")
                || headerUpper.contains("CHINA")
                || headerUpper.contains("CHINESE")
                || headerUpper.contains("CITIC")
                || headerUpper.contains("ICBC")
                || headerUpper.contains("CCB")
                || headerUpper.contains("BOC")
                || headerUpper.contains("ABC")
                || headerUpper.contains("UNIONPAY")
                || headerUpper.contains("交易")
                || headerUpper.contains("金额")
                || filenameUpper.contains("CITIC")
                || filenameUpper.contains("CHINA")
                || filenameUpper.contains("CHINESE")
                || filenameUpper.contains("UNIONPAY")
                || filenameUpper.contains("CNY")
                || filenameUpper.contains("YUAN");
    }

    private static boolean matchesJapaneseContext(
            final String upper, final String headerUpper, final String filenameUpper) {
        return upper.contains("JPY")
                || upper.contains("YEN")
                || headerUpper.contains("JPY")
                || headerUpper.contains("YEN")
                || headerUpper.contains("JAPAN")
                || headerUpper.contains("JAPANESE")
                || headerUpper.contains("MUFG")
                || headerUpper.contains("MIZUHO")
                || headerUpper.contains("SMBC")
                || headerUpper.contains("JCB")
                || headerUpper.contains("取引")
                || headerUpper.contains("金額")
                || filenameUpper.contains("JAPAN")
                || filenameUpper.contains("JAPANESE")
                || filenameUpper.contains("MUFG")
                || filenameUpper.contains("JCB")
                || filenameUpper.contains("JPY")
                || filenameUpper.contains("YEN");
    }

    /**
     * Parse amount string, handling various formats (mirrors iOS app) Returns AmountParseResult
     * with amount and detected currency
     */
    private AmountParseResult parseAmountWithCurrency(
            final String amountString, final String headerLine, final String filename) {
        if (amountString == null || amountString.isBlank()) {
            return new AmountParseResult(null, "USD");
        }

        // Detect currency before cleaning (with filename context)
        final String currencyCode = detectCurrency(amountString, headerLine, filename);

        // Remove currency symbols and whitespace (supports USD, INR, and other currencies)
        String cleaned =
                amountString
                        .replace("$", "")
                        .replace("₹", "") // Indian Rupee symbol
                        .replace("Rs", "")
                        .replace("Rs.", "")
                        .replace("INR", "")
                        .replace("inr", "")
                        .replace("USD", "")
                        .replace("usd", "")
                        .replace("EUR", "")
                        .replace("eur", "")
                        .replace("€", "")
                        .replace("GBP", "")
                        .replace("gbp", "")
                        .replace("£", "")
                        .replace("CAD", "")
                        .replace("cad", "")
                        .replace("C$", "")
                        .replace("AUD", "")
                        .replace("aud", "")
                        .replace("A$", "")
                        .replace("JPY", "")
                        .replace("jpy", "")
                        .replace("CNY", "")
                        .replace("cny", "")
                        .replace("¥", "")
                        .replace("Fr", "") // Swiss Franc
                        .replace("kr", "") // Nordic currencies (SEK, NOK, DKK)
                        .replace("zł", "") // Polish Zloty
                        .replace("S$", "") // Singapore Dollar
                        .replace("HK$", "") // Hong Kong Dollar
                        .replace("₩", "") // Korean Won
                        .replace("฿", "") // Thai Baht
                        .replace("Rp", "") // Indonesian Rupiah
                        .replace("₫", "") // Vietnamese Dong
                        .replace("RM", "") // Malaysian Ringgit
                        .replace("₱", "") // Philippine Peso
                        .replace("R$", "") // Brazilian Real
                        .replace("Mex$", "") // Mexican Peso
                        .replace(" ", "")
                        .trim();

        // Handle European number format (comma as decimal separator: 1.234,56)
        // Check if we have a comma followed by 2 digits at the end (likely decimal separator)
        final boolean isEuropeanFormat = cleaned.matches(".*\\d{1,3}(?:\\.\\d{3})*,\\d{1,2}$");
        if (isEuropeanFormat) {
            // European format: 1.234,56 -> 1234.56
            cleaned = cleaned.replace(".", ""); // Remove thousand separators (periods)
            cleaned = cleaned.replace(",", "."); // Convert comma to decimal point
        } else {
            // US/Asian format: 1,234.56 -> 1234.56
            cleaned = cleaned.replace(",", ""); // Remove thousand separators (commas)
        }

        // Handle negative amounts (parentheses or minus sign)
        final boolean isNegative =
                (cleaned.startsWith("(") && cleaned.endsWith(")")) || cleaned.startsWith("-");
        final String numericString = cleaned.replace("(", "").replace(")", "").replace("-", "");

        // Validate numeric string is not empty after cleaning
        if (numericString == null || numericString.isBlank()) {
            LOGGER.warn("Amount string is empty after cleaning: {}", amountString);
            return new AmountParseResult(null, currencyCode);
        }

        try {
            BigDecimal amount = new BigDecimal(numericString);

            // Validate amount is within reasonable bounds (prevent overflow/underflow)
            // Max: 999,999,999.99 (matches AmountValidator.MAX_AMOUNT)
            final BigDecimal maxAmount = new BigDecimal("999999999.99");
            final BigDecimal minAmount = maxAmount.negate();

            if (amount.compareTo(maxAmount) > 0) {
                LOGGER.warn("Amount exceeds maximum: {} (capped at {})", amount, maxAmount);
                amount = maxAmount;
            } else if (amount.compareTo(minAmount) < 0) {
                LOGGER.warn("Amount below minimum: {} (capped at {})", amount, minAmount);
                amount = minAmount;
            }

            // Round to 2 decimal places (standard for currency)
            amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);

            // CRITICAL: Preserve original sign - don't convert to absolute for positive amounts
            // This ensures debit/credit information is preserved
            final BigDecimal finalAmount = isNegative ? amount.negate() : amount;
            LOGGER.debug(
                    "Parsed amount: original='{}', isNegative={}, numeric={}, final={}",
                    amountString,
                    isNegative,
                    amount,
                    finalAmount);

            return new AmountParseResult(finalAmount, currencyCode);
        } catch (NumberFormatException e) {
            LOGGER.warn("Could not parse amount: {} (cleaned: {})", amountString, numericString);
            return new AmountParseResult(null, currencyCode);
        } catch (ArithmeticException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Arithmetic error parsing amount: {} - {}", amountString, e.getMessage());
            }
            return new AmountParseResult(null, currencyCode);
        }
    }

    /*
     * Parse category string to category name with enhanced RSU, ACH, Salary detection Supports
     * categories from checking accounts, credit cards, investment accounts, and loans
     *
     * @param categoryString Category string from CSV
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param amount Transaction amount
     * @param paymentChannel Payment channel (e.g., "ach")
     * @return Category name
     */
    /**
     * Parse category string to category name with sophisticated merchant name and description
     * detection Uses world's best practices: merchant name patterns, description analysis, fuzzy
     * matching Supports all account types: checking, savings, credit card, debit card, money
     * market, investment, loan Works globally: US, India, Europe, Asia, etc.
     *
     * <p>Public method for use by PDFImportService and ExcelImportService
     *
     * @param categoryString Category string from CSV (may be null/empty)
     * @param description Transaction description
     * @param merchantName Merchant name (normalized)
     * @param amount Transaction amount
     * @param paymentChannel Payment channel (e.g., "ach", "pos", "online")
     * @param transactionTypeIndicator Debit/credit indicator from CSV (e.g., "DEBIT", "CREDIT",
     *     "DR", "CR")
     * @return Detected category name
     */
    public String parseCategory(
            final String categoryString,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel,
            final String transactionTypeIndicator) {
        // Legacy method signature - delegate to new signature with null context
        return parseCategory(
                categoryString,
                description,
                merchantName,
                amount,
                paymentChannel,
                transactionTypeIndicator,
                null,
                null,
                null);
    }

    /**
     * Parse category from import file data with transaction type and account type context
     * SIMPLIFIED VERSION: Context-aware rules grouped by transaction type
     *
     * @param categoryString Category string from import file
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @param transactionTypeIndicator Transaction type indicator (DEBIT/CREDIT)
     * @param transactionType Transaction type (INCOME, EXPENSE, INVESTMENT, PAYMENT) - null if not
     *     yet determined
     * @param accountType Account type (depository, credit, loan, investment, etc.) - null if not
     *     available
     * @param accountSubtype Account subtype (checking, savings, credit card, etc.) - null if not
     *     available
     * @return Detected category name
     */
    public String parseCategory(
            final String categoryString,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel,
            final String transactionTypeIndicator,
            final String transactionType,
            final String accountType,
            final String accountSubtype) {
        // Use simplified version - delegate to parseCategorySimplified
        return parseCategorySimplified(
                categoryString,
                description,
                merchantName,
                amount,
                paymentChannel,
                transactionTypeIndicator,
                transactionType,
                accountType,
                accountSubtype);
    }

    /**
     * SIMPLIFIED category parser with context-aware rules grouped by transaction type Structure: 1.
     * Early unambiguous checks (transaction-type independent) 2. Context-aware rules by transaction
     * type (INCOME, EXPENSE, INVESTMENT, PAYMENT) 3. ML/Fuzzy matching (earlier in flow) 4.
     * Category string mapping fallback
     */
    private String parseCategorySimplified(
            final String categoryString,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel,
            final String transactionTypeIndicator,
            final String transactionType,
            final String accountType,
            final String accountSubtype) {
        // Input validation and normalization
        final String safeCategoryString = categoryString != null ? categoryString.trim() : null;
        final String safeDescription = description != null ? description.trim() : null;
        final String safeMerchantName = merchantName != null ? merchantName.trim() : null;
        final String safePaymentChannel = paymentChannel != null ? paymentChannel.trim() : null;
        final String safeAccountType =
                accountType != null ? accountType.trim().toLowerCase(Locale.ROOT) : null;

        // Validate amount
        BigDecimal safeAmount = amount;
        if (amount != null) {
            final BigDecimal maxAmount = BigDecimal.valueOf(1_000_000_000);
            final BigDecimal minAmount = BigDecimal.valueOf(-1_000_000_000);
            if (amount.compareTo(maxAmount) > 0 || amount.compareTo(minAmount) < 0) {
                LOGGER.warn(
                        "parseCategorySimplified: Amount out of reasonable range: {}, using null",
                        amount);
                safeAmount = null;
            }
        }

        // Check if investment account (used in multiple places)
        @SuppressWarnings("unused")
        final boolean isInvestmentAccount =
                safeAccountType != null
                        && (safeAccountType.contains("investment")
                                || safeAccountType.contains(IRA)
                                || safeAccountType.contains("401k")
                                || safeAccountType.contains("hsa")
                                || safeAccountType.contains("529"));

        // ========== STEP 1: Early Unambiguous Checks (Transaction-Type Independent) ==========

        // STEP 1a: Check for zero amount transactions FIRST (before any other checks)
        // CRITICAL: Zero amount transactions with fee descriptions should be 'other', not 'fee'
        // Even if category string is FEE, zero amounts should be 'other' if description contains
        // fee
        // NOTE: Zero amount handling continues later in STEP 7, so we don't return here for all
        // zero amounts
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) == 0) {
            // For zero amounts with fee/adjustment descriptions, return early
            if (safeDescription != null) {
                final String descLower = safeDescription.toLowerCase(Locale.ROOT);
                if (descLower.contains(FEE)
                        || descLower.contains("adjustment")
                        || descLower.contains("correction")) {
                    LOGGER.debug(
                            "parseCategorySimplified: Zero amount with fee/adjustment description → 'other'");
                    return OTHER;
                }
            }
            // For other zero amounts, continue to later steps (STEP 7 handles them)
        }

        // Continue with the rest of the logic - all steps from STEP 1b onwards
        // This logic is the same as parseCategoryLegacy but in simplified form
        // STEP 1b through STEP 10 are handled in parseCategoryLegacy method
        // For now, delegate to parseCategoryLegacy to avoid code duplication
        return parseCategoryLegacy(
                categoryString,
                description,
                merchantName,
                amount,
                paymentChannel,
                transactionTypeIndicator,
                transactionType,
                accountType,
                accountSubtype);
    }

    /**
     * LEGACY parseCategory implementation - kept for reference/testing This is the original complex
     * implementation with 480+ lines
     */
    @SuppressWarnings("unused")
    private String parseCategoryLegacy(
            final String categoryString,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel,
            final String transactionTypeIndicator,
            final String transactionType,
            final String accountType,
            final String accountSubtype) {
        // CRITICAL: Input validation and null safety
        // Normalize inputs to prevent null pointer exceptions
        final String safeCategoryString = categoryString != null ? categoryString.trim() : null;
        final String safeDescription = description != null ? description.trim() : null;
        final String safeMerchantName = merchantName != null ? merchantName.trim() : null;
        final String safePaymentChannel = paymentChannel != null ? paymentChannel.trim() : null;
        // NOTE: safeTransactionTypeIndicator is normalized but passed to determineTransactionType
        // which does its own normalization
        // However, it IS used in isCashWithdrawal and isCheckPayment checks below
        final String safeTransactionTypeIndicator =
                transactionTypeIndicator != null ? transactionTypeIndicator.trim() : null;
        final String safeTransactionType =
                transactionType != null ? transactionType.trim().toUpperCase(Locale.ROOT) : null;
        final String safeAccountType =
                accountType != null ? accountType.trim().toLowerCase(Locale.ROOT) : null;
        final String safeAccountSubtype =
                accountSubtype != null ? accountSubtype.trim().toLowerCase(Locale.ROOT) : null;

        // CRITICAL: Validate amount is reasonable (prevent overflow/underflow issues)
        BigDecimal safeAmount = amount;
        if (amount != null) {
            // Check for extreme values that might cause issues
            final BigDecimal maxAmount = BigDecimal.valueOf(1_000_000_000); // 1 billion
            final BigDecimal minAmount = BigDecimal.valueOf(-1_000_000_000);
            if (amount.compareTo(maxAmount) > 0 || amount.compareTo(minAmount) < 0) {
                LOGGER.warn(
                        "parseCategory: Amount out of reasonable range: {}, using null for safety",
                        amount);
                safeAmount = null;
            }
        }

        String result;
        result =
                parseCatLegacyStep01AchCreditDeposit(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep02FeesAndCashWithdrawal(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep03CheckPayment(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep04CableInternetPhone(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep05UtilityBillPayment(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep06InterestIncome(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep07DividendIncome(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep08CreditCardPayment(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep09LoanPaymentEscrow(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep10InvestmentTransfer(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep11InvestmentFees(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep12InvestmentPurchase(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep13InvestmentSold(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep14DepositFromInvestment(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep15AccountTransfer(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep16SalaryPayrollInterest(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep17DividendIncomeAgain(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep18ContextAwareIncome(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep19SalaryPayroll(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep20PropertyTax(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep21Rsu(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep22MerchantBasedDetection(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep23DescriptionBased(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep24EnhancedDetection(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep25ZeroAmount(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep26AchTransactions(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep27AchCreditSpecific(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }
        result =
                parseCatLegacyStep28StandardCategoryParse(
                        safeCategoryString,
                        safeDescription,
                        safeMerchantName,
                        safeAmount,
                        safePaymentChannel,
                        safeTransactionTypeIndicator,
                        safeTransactionType,
                        safeAccountType,
                        safeAccountSubtype);
        if (result != null) {
            return result;
        }

        LOGGER.debug("parseCategoryLegacy: No match — falling back to OTHER");
        return OTHER;
    }

    private String parseCatLegacyStep01AchCreditDeposit(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1a: Check for ACH_CREDIT deposits FIRST (before payment detection)
        // CRITICAL: Must come before payment checks to prevent ACH_CREDIT from being categorized as
        // payment
        if (safeCategoryString != null && safeAmount != null) {
            final String categoryLower = safeCategoryString.toLowerCase(Locale.ROOT);
            if ((categoryLower.contains("ach_credit") || categoryLower.contains("ach credit"))
                    && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                if (isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
                    LOGGER.debug("🏷️ parseCategory: ACH_CREDIT salary → 'salary'");
                    return SALARY;
                }
                LOGGER.debug("🏷️ parseCategory: ACH_CREDIT → 'deposit'");
                return DEPOSIT;
            }
        }
        return null;
    }

    private String parseCatLegacyStep02FeesAndCashWithdrawal(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1b: Check for fees (FEE_TRANSACTION, safe deposit box, etc.)
        if (safeCategoryString != null) {
            final String categoryLower = safeCategoryString.toLowerCase(Locale.ROOT);
            if (categoryLower.contains("fee_transaction")
                    || categoryLower.contains("fee transaction")) {
                LOGGER.debug("🏷️ parseCategory: FEE_TRANSACTION → 'fee'");
                return FEE;
            }
        }
        // Check for safe deposit box in description
        if (safeDescription != null) {
            final String descLower = safeDescription.toLowerCase(Locale.ROOT);
            if (descLower.contains("safe deposit box")
                    || descLower.contains("safedepositbox")
                    || descLower.contains("safe deposit")
                    || descLower.contains("safety deposit box")) {
                LOGGER.debug("🏷️ parseCategory: Safe deposit box → 'fee'");
                return FEE;
            }
        }

        // STEP 1c: Check for Cash Withdrawal - CRITICAL FIX: Must come before payment checks
        // STEP 0: Check for Airport Expenses (carts, chairs, parking, etc.) - CRITICAL: Must come
        // BEFORE utilities
        // "SEATTLEAP CART/CHAIR" (Seattle Airport cart) should be TRANSPORTATION, not UTILITIES
        if (safeDescription != null || safeMerchantName != null) {
            final String combined =
                    ((safeMerchantName != null ? safeMerchantName : "")
                                    + " "
                                    + (safeDescription != null ? safeDescription : ""))
                            .toLowerCase(Locale.ROOT)
                            .trim();
            // Airport cart/chair rentals
            if ((combined.contains("seattleap")
                            || combined.contains("seattle ap")
                            || combined.contains("seattle airport"))
                    && (combined.contains("cart") || combined.contains("chair"))) {
                LOGGER.debug("🏷️ parseCategory: Detected airport cart/chair → 'transportation'");
                return TRANSPORTATION;
            }
            // General airport cart/chair patterns
            if (combined.contains("airport")
                    && (combined.contains("cart") || combined.contains("chair"))) {
                LOGGER.debug("🏷️ parseCategory: Detected airport cart/chair → 'transportation'");
                return TRANSPORTATION;
            }
        }

        // Cash withdrawals should be type EXPENSE, category "cash"
        // CRITICAL: Also check for "withdrawal" keyword (standalone)
        if (isCashWithdrawal(
                safeDescription,
                safeMerchantName,
                safeCategoryString,
                safeTransactionTypeIndicator,
                safePaymentChannel)) {
            LOGGER.debug("🏷️ parseCategory: Detected cash withdrawal → 'cash'");
            return "cash";
        }

        // CRITICAL FIX: Check for standalone "withdrawal" keyword (not just "cash withdrawal")
        if (safeDescription != null
                && safeAmount != null
                && safeAmount.compareTo(BigDecimal.ZERO) < 0) {
            final String descLower = safeDescription.toLowerCase(Locale.ROOT);
            if (descLower.contains("withdrawal")
                    && !descLower.contains("investment")
                    && !descLower.contains(TRANSFER)
                    && !descLower.contains("payment")) {
                LOGGER.debug(
                        "🏷️ parseCategory: Detected withdrawal (negative safeAmount) → 'cash'");
                return "cash";
            }
        }
        return null;
    }

    private String parseCatLegacyStep03CheckPayment(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1b: Check for Check Payment - CRITICAL FIX: Must come before merchant name detection
        // Check payments should be "payment", not transportation (e.g., "CHECK 176" was matching
        // gas station "76")
        if (isCheckPayment(
                safeDescription,
                safeMerchantName,
                safeCategoryString,
                safeTransactionTypeIndicator)) {
            LOGGER.debug("🏷️ parseCategory: Detected check payment → 'payment'");
            return "payment";
        }
        return null;
    }

    private String parseCatLegacyStep04CableInternetPhone(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1a: Check for Cable/Internet/Phone Providers - CRITICAL: Must come BEFORE credit
        // card payment
        // Cable/internet/phone bills (e.g., "Comcast Payment", "Xfinity Mobile Payment") should be
        // UTILITIES, not "payment"
        // This must come before credit card payment to prevent "e payment" in "Mobile Payment" from
        // matching
        if (safeDescription != null || safeMerchantName != null) {
            final String combined =
                    ((safeMerchantName != null ? safeMerchantName : "")
                                    + " "
                                    + (safeDescription != null ? safeDescription : ""))
                            .toLowerCase(Locale.ROOT)
                            .trim();
            // Cable/Internet Providers
            final String[] cableInternetProviders = {
                "comcast",
                "xfinity",
                "xfinity mobile",
                "xfinitymobile",
                "spectrum",
                "charter",
                "charter spectrum",
                "cox",
                "cox communications",
                "optimum",
                "altice",
                "frontier",
                "frontier communications",
                "centurylink",
                "century link",
                "windstream",
                "suddenlink",
                "mediacom",
                "dish",
                "dish network",
                "directv",
                "direct tv",
                "att u-verse",
                "att uverse",
                "fios",
                "verizon fios"
            };
            for (final String provider : cableInternetProviders) {
                if (combined.contains(provider)) {
                    LOGGER.debug(
                            "🏷️ parseCategory: Detected cable/internet provider '{}' → 'utilities'",
                            provider);
                    return UTILITIES;
                }
            }
            // Phone/Mobile Providers (excluding Xfinity Mobile which is already covered above)
            final String[] phoneProviders = {
                "verizon wireless",
                "verizonwireless",
                "verizon",
                "at&t",
                "att",
                "at and t",
                "t-mobile",
                "tmobile",
                "t mobile",
                "sprint",
                "us cellular",
                "uscellular",
                "cricket",
                "cricket wireless",
                "boost mobile",
                "boostmobile",
                "metropcs",
                "metro pcs",
                "metropcs",
                "mint mobile",
                "mintmobile",
                "google fi",
                "googlefi",
                "visible",
                "straight talk",
                "straighttalk",
                "us mobile",
                "usmobile"
            };
            for (final String provider : phoneProviders) {
                if (combined.contains(provider)) {
                    LOGGER.debug(
                            "🏷️ parseCategory: Detected phone/mobile provider '{}' → 'utilities'",
                            provider);
                    return UTILITIES;
                }
            }
        }
        return null;
    }

    private String parseCatLegacyStep05UtilityBillPayment(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1b: Check for Utility Bill Payment - CRITICAL FIX: Must come BEFORE credit card
        // payment
        // Direct payments to utility companies (e.g., "PUGET SOUND ENER BILLPAY") should be
        // UTILITIES, not "payment"
        // This is different from credit card payments - utility bills are actual expenses, not
        // payments for expenses already counted
        if (isUtilityBillPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            LOGGER.debug("🏷️ parseCategory: Detected utility bill payment → 'utilities'");
            return UTILITIES;
        }
        return null;
    }

    private String parseCatLegacyStep06InterestIncome(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1c: Check for Interest Income - CRITICAL: Must come BEFORE credit card payment to
        // avoid false matches
        // Interest payments should be INTEREST (income) or "investmentInterest" (investment), not
        // "payment" (expense)
        if (isInterestTransaction(
                safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            // Context-aware: If transaction type is INVESTMENT or account is investment, use
            // investmentInterest
            if ("INVESTMENT".equals(safeTransactionType)
                    || (safeAccountType != null
                            && (safeAccountType.contains("investment")
                                    || safeAccountType.contains(IRA)
                                    || safeAccountType.contains("401k")
                                    || safeAccountType.contains("hsa")
                                    || safeAccountType.contains("529")))) {
                LOGGER.debug(
                        "parseCategory: Detected interest transaction (investment account) → 'investmentInterest'");
                return "investmentInterest";
            }
            LOGGER.debug("parseCategory: Detected interest transaction → 'interest'");
            return INTEREST;
        }
        return null;
    }

    private String parseCatLegacyStep07DividendIncome(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1c: Check for Dividend Income - CRITICAL: Must come BEFORE credit card payment to
        // avoid false matches
        // Dividend payments should be DIVIDEND (income) or "investmentDividend" (investment), not
        // "payment" (expense) or generic "investment"
        if (isDividendTransaction(
                safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            // Context-aware: If transaction type is INVESTMENT or account is investment, use
            // investmentDividend
            if ("INVESTMENT".equals(safeTransactionType)
                    || (safeAccountType != null
                            && (safeAccountType.contains("investment")
                                    || safeAccountType.contains(IRA)
                                    || safeAccountType.contains("401k")
                                    || safeAccountType.contains("hsa")
                                    || safeAccountType.contains("529")))) {
                LOGGER.debug(
                        "parseCategory: Detected dividend transaction (investment account) → 'investmentDividend'");
                return "investmentDividend";
            }
            LOGGER.debug("parseCategory: Detected dividend transaction → 'dividend'");
            return DIVIDEND;
        }
        return null;
    }

    private String parseCatLegacyStep08CreditCardPayment(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1d: Check for Credit Card Payment - CRITICAL FIX: Must come early, not just for ACH
        // Credit card payments should be "payment" (expense), not OTHER or DEPOSIT (income)
        // This includes ACH autopay, online payments, etc.
        // CRITICAL: Check this BEFORE merchant name detection to avoid false matches (e.g., "CHASE
        // CREDIT CRD" matching transportation)
        // CRITICAL: But AFTER interest/dividend check to avoid false positives (e.g., "Interest
        // payment" matching credit card)
        if (isCreditCardPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            LOGGER.debug("🏷️ parseCategory: Detected credit card payment → 'payment'");
            return "payment";
        } else {
            // Log why it wasn't detected for debugging
            LOGGER.debug(
                    "🏷️ parseCategory: Credit card payment check returned false for safeDescription='{}', merchant='{}', category='{}'",
                    safeDescription,
                    safeMerchantName,
                    safeCategoryString);
        }
        return null;
    }

    private String parseCatLegacyStep09LoanPaymentEscrow(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1e: Check for Loan Payment/Escrow/Bills - CRITICAL FIX: Must come early
        // Loan payments (mortgage, auto loan, student loan) should be "payment", "loanEscrow", or
        // "loanBills"
        if (isLoanPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            // Context-aware: Check if it's escrow or bills
            final String descLower =
                    safeDescription != null ? safeDescription.toLowerCase(Locale.ROOT) : "";
            final String merchantLower =
                    safeMerchantName != null ? safeMerchantName.toLowerCase(Locale.ROOT) : "";
            final String combinedText = (merchantLower + " " + descLower).trim();

            // Check for escrow keywords
            if (combinedText.contains("escrow")
                    || combinedText.contains("tax escrow")
                    || combinedText.contains("insurance escrow")
                    || combinedText.contains("property tax")
                    || combinedText.contains("homeowners insurance")
                    || combinedText.contains("hazard insurance")) {
                LOGGER.debug("🏷️ parseCategory: Detected loan escrow → 'loanEscrow'");
                return "loanEscrow";
            }

            // Check for bill payment keywords (utilities, etc. paid through loan)
            if (combinedText.contains("bill")
                    || combinedText.contains("utility bill")
                    || combinedText.contains("electric bill")
                    || combinedText.contains("water bill")
                    || combinedText.contains("cable bill")
                    || combinedText.contains("internet bill")) {
                LOGGER.debug("🏷️ parseCategory: Detected loan bills → 'loanBills'");
                return "loanBills";
            }

            LOGGER.debug("🏷️ parseCategory: Detected loan payment → 'payment'");
            return "payment";
        }
        return null;
    }

    private String parseCatLegacyStep10InvestmentTransfer(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1f: Check for Investment Transfer - CRITICAL FIX: Must come before regular account
        // transfer
        // Investment transfers (from/to investment firms like Morgan Stanley, Fidelity, etc.)
        // should be "investmentTransfer"
        // CRITICAL: Only for DEBITS (money going out). Credits should be DEPOSIT
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) < 0) {
            // Only check for investment transfer if safeAmount is negative (debit)
            if (isInvestmentTransfer(safeDescription, safeMerchantName, safeCategoryString)) {
                LOGGER.debug(
                        "🏷️ parseCategory: Detected investment transfer (debit) → 'investmentTransfer'");
                return "investmentTransfer";
            }
        }
        return null;
    }

    private String parseCatLegacyStep11InvestmentFees(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1f0: Check for Investment Fees - CRITICAL: Must come before other investment checks
        // Investment fees (negative amounts on investment accounts) should be "investmentFees"
        if (safeAmount != null
                && safeAmount.compareTo(BigDecimal.ZERO) < 0
                && ("INVESTMENT".equals(safeTransactionType)
                        || (safeAccountType != null
                                && (safeAccountType.contains("investment")
                                        || safeAccountType.contains(IRA)
                                        || safeAccountType.contains("401k")
                                        || safeAccountType.contains("hsa")
                                        || safeAccountType.contains("529"))))) {
            final String descLower =
                    safeDescription != null ? safeDescription.toLowerCase(Locale.ROOT) : "";
            final String merchantLower =
                    safeMerchantName != null ? safeMerchantName.toLowerCase(Locale.ROOT) : "";
            final String combinedText = (merchantLower + " " + descLower).trim();

            // Check for fee keywords
            if (combinedText.contains(FEE)
                    || combinedText.contains("management fee")
                    || combinedText.contains("advisory fee")
                    || combinedText.contains("custodian fee")
                    || combinedText.contains("account fee")
                    || combinedText.contains("administrative fee")
                    || combinedText.contains("expense ratio")
                    || combinedText.contains("expense fee")
                    || (combinedText.contains("annual") && combinedText.contains(FEE))) {
                LOGGER.debug("🏷️ parseCategory: Detected investment fee → 'investmentFees'");
                return "investmentFees";
            }
        }
        return null;
    }

    private String parseCatLegacyStep12InvestmentPurchase(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1f1: Check for Investment Purchase - CRITICAL: Negative amounts on investment
        // accounts
        // Investment purchases (negative amounts on investment accounts) should be
        // "investmentPurchase"
        if (safeAmount != null
                && safeAmount.compareTo(BigDecimal.ZERO) < 0
                && ("INVESTMENT".equals(safeTransactionType)
                        || (safeAccountType != null
                                && (safeAccountType.contains("investment")
                                        || safeAccountType.contains(IRA)
                                        || safeAccountType.contains("401k")
                                        || safeAccountType.contains("hsa")
                                        || safeAccountType.contains("529"))))) {
            final String descLower =
                    safeDescription != null ? safeDescription.toLowerCase(Locale.ROOT) : "";
            final String merchantLower =
                    safeMerchantName != null ? safeMerchantName.toLowerCase(Locale.ROOT) : "";
            final String combinedText = (merchantLower + " " + descLower).trim();

            // Check for purchase keywords (but not fees or transfers)
            if ((combinedText.contains("purchase")
                            || combinedText.contains("buy")
                            || combinedText.contains("contribution")
                            || combinedText.contains(DEPOSIT))
                    && !combinedText.contains(FEE)
                    && !combinedText.contains(TRANSFER)) {
                LOGGER.debug(
                        "🏷️ parseCategory: Detected investment purchase → 'investmentPurchase'");
                return "investmentPurchase";
            }
        }
        return null;
    }

    private String parseCatLegacyStep13InvestmentSold(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1f2: Check for Investment Sold - CRITICAL: Positive amounts on investment accounts
        // Investment sales (positive amounts on investment accounts) should be "investmentSold"
        if (safeAmount != null
                && safeAmount.compareTo(BigDecimal.ZERO) > 0
                && ("INVESTMENT".equals(safeTransactionType)
                        || (safeAccountType != null
                                && (safeAccountType.contains("investment")
                                        || safeAccountType.contains(IRA)
                                        || safeAccountType.contains("401k")
                                        || safeAccountType.contains("hsa")
                                        || safeAccountType.contains("529"))))) {
            final String descLower =
                    safeDescription != null ? safeDescription.toLowerCase(Locale.ROOT) : "";
            final String merchantLower =
                    safeMerchantName != null ? safeMerchantName.toLowerCase(Locale.ROOT) : "";
            final String combinedText = (merchantLower + " " + descLower).trim();

            // Check for sale keywords
            if (combinedText.contains("sale")
                    || combinedText.contains("sell")
                    || combinedText.contains("redemption")
                    || combinedText.contains("withdrawal")
                    || combinedText.contains("distribution")
                    || combinedText.contains("proceeds")) {
                LOGGER.debug("🏷️ parseCategory: Detected investment sale → 'investmentSold'");
                return "investmentSold";
            }
        }
        return null;
    }

    private String parseCatLegacyStep14DepositFromInvestment(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1f1: Check for Deposit from Investment Firm - CRITICAL FIX
        // Credits from investment firms (e.g., "Online transfer from Morgan Stanley" with positive
        // safeAmount) should be DEPOSIT
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
            final String descLower =
                    safeDescription != null ? safeDescription.toLowerCase(Locale.ROOT) : "";
            final String merchantLower =
                    safeMerchantName != null ? safeMerchantName.toLowerCase(Locale.ROOT) : "";
            final String[] investmentFirms = {
                "morgan stanley",
                "morganstanley",
                "fidelity",
                "vanguard",
                "schwab",
                "charles schwab",
                "td ameritrade",
                "etrade",
                "robinhood",
                "merrill lynch",
                "goldman sachs"
            };
            for (final String firm : investmentFirms) {
                if ((descLower.contains(firm) || merchantLower.contains(firm))
                        && (descLower.contains(TRANSFER)
                                || descLower.contains(FROM)
                                || descLower.contains(DEPOSIT))) {
                    LOGGER.debug(
                            "🏷️ parseCategory: Detected deposit from investment firm '{}' (credit) → 'deposit'",
                            firm);
                    return DEPOSIT;
                }
            }
        }
        return null;
    }

    private String parseCatLegacyStep15AccountTransfer(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 1g: Check for Account Transfer - CRITICAL FIX: Must come early
        // Account transfers (ACCT_XFER, Online Transfer to CHK, etc.) should be TRANSFER, not
        // OTHER
        // CRITICAL: Also check for wire transfers, international wires, money transfer services
        if (isAccountTransfer(safeDescription, safeMerchantName, safeCategoryString)) {
            LOGGER.debug("🏷️ parseCategory: Detected account transfer → 'transfer'");
            return TRANSFER;
        }

        // CRITICAL FIX: Check for wire transfers and international wires
        if (safeDescription != null) {
            final String descLower = safeDescription.toLowerCase(Locale.ROOT);
            if ((descLower.contains("wire") || descLower.contains("international wire"))
                    && (descLower.contains("debit") || descLower.contains(TRANSFER))) {
                LOGGER.debug("🏷️ parseCategory: Detected wire transfer → 'transfer'");
                return TRANSFER;
            }
        }
        return null;
    }

    private String parseCatLegacyStep16SalaryPayrollInterest(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 2: Check for Salary/Payroll (highest priority for income)
        if (isInterestTransaction(
                safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            LOGGER.debug("parseCategory: Detected interest transaction → 'interest'");
            return INTEREST;
        }
        return null;
    }

    private String parseCatLegacyStep17DividendIncomeAgain(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 3: Check for Dividend Income - CRITICAL: Must come before salary to catch dividend
        // payments
        if (isDividendTransaction(
                safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            LOGGER.debug("parseCategory: Detected dividend transaction → 'dividend'");
            return DIVIDEND;
        }
        return null;
    }

    private String parseCatLegacyStep18ContextAwareIncome(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 3: Context-aware Income Category Detection
        // For INCOME type transactions, determine specific income category (salary, deposit,
        // dividend, interest)
        if ("INCOME".equals(safeTransactionType)
                && safeAmount != null
                && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
            final String incomeCategory =
                    determineIncomeCategoryFromContext(
                            safeDescription,
                            safeMerchantName,
                            safeAmount,
                            safePaymentChannel,
                            safeAccountType,
                            safeAccountSubtype);
            if (incomeCategory != null) {
                LOGGER.debug(
                        "🏷️ parseCategory: Context-aware income category detected → '{}'",
                        incomeCategory);
                return incomeCategory;
            }
        }
        return null;
    }

    private String parseCatLegacyStep19SalaryPayroll(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 3a: Check for Salary/Payroll (highest priority for income)
        // CRITICAL FIX: Include Amazon.com SVCS Payroll and other payroll patterns
        if (isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
            LOGGER.debug("parseCategory: Detected salary transaction → 'salary'");
            return SALARY;
        }

        // CRITICAL FIX: Check for specific payroll patterns
        if (safeDescription != null
                && safeAmount != null
                && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
            final String descLower = safeDescription.toLowerCase(Locale.ROOT);
            final String merchantLower =
                    safeMerchantName != null ? safeMerchantName.toLowerCase(Locale.ROOT) : "";
            if ((descLower.contains("amazon.com svcs")
                            || descLower.contains("amazon.com services")
                            || merchantLower.contains("amazon.com svcs")
                            || merchantLower.contains("amazon.com services"))
                    && (descLower.contains("payroll")
                            || descLower.contains(SALARY)
                            || descLower.contains("pay"))) {
                LOGGER.debug("🏷️ parseCategory: Detected Amazon.com SVCS Payroll → 'salary'");
                return SALARY;
            }
        }
        return null;
    }

    private String parseCatLegacyStep20PropertyTax(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 3.5: Check for Property Tax - CRITICAL FIX
        // Property tax (e.g., Santa Clara DTAC) should be OTHER (no specific property tax
        // category)
        if (safeDescription != null || safeMerchantName != null) {
            final String descLower =
                    safeDescription != null ? safeDescription.toLowerCase(Locale.ROOT) : "";
            final String merchantLower =
                    safeMerchantName != null ? safeMerchantName.toLowerCase(Locale.ROOT) : "";
            if (descLower.contains("property tax")
                    || descLower.contains("santa clara dtac")
                    || merchantLower.contains("property tax")
                    || merchantLower.contains("santa clara dtac")
                    || descLower.contains("dtac")
                    || merchantLower.contains("dtac")) {
                LOGGER.debug("🏷️ parseCategory: Detected property tax → 'other'");
                return OTHER; // Property tax category - user can override if needed
            }
        }
        return null;
    }

    private String parseCatLegacyStep21Rsu(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 4: Check for RSU transactions
        if (isRSUTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            LOGGER.debug("parseCategory: Detected RSU transaction → 'rsu'");
            return "rsu";
        }
        return null;
    }

    private String parseCatLegacyStep22MerchantBasedDetection(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 4: Sophisticated Merchant Name-Based Detection (NEW - Global Scale)
        // This is the key enhancement - merchant names are more reliable than category strings
        // CRITICAL FIX: Skip merchant name detection when safeMerchantName is null/empty to save
        // resources
        String merchantCategory = null;
        if (safeMerchantName != null && !safeMerchantName.isBlank()) {
            merchantCategory = detectCategoryFromMerchantName(safeMerchantName, safeDescription);
            if (merchantCategory != null) {
                LOGGER.debug(
                        "🏷️ parseCategory: Detected category from merchant name '{}' → '{}'",
                        safeMerchantName,
                        merchantCategory);
                return merchantCategory;
            }
        } else {
            LOGGER.debug(
                    "🏷️ parseCategory: Skipping merchant name detection - safeMerchantName is null/empty");
        }
        return null;
    }

    private String parseCatLegacyStep23DescriptionBased(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 5: Description-Based Category Detection (NEW - Fallback when merchant name fails)
        final String descriptionCategory =
                detectCategoryFromDescription(safeDescription, safeMerchantName, safeAmount);
        if (descriptionCategory != null) {
            LOGGER.debug(
                    "🏷️ parseCategory: Detected category from safeDescription '{}' → '{}'",
                    safeDescription,
                    descriptionCategory);
            return descriptionCategory;
        }
        return null;
    }

    private String parseCatLegacyStep24EnhancedDetection(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 6: Enhanced Category Detection (ML + Fuzzy Matching) - CRITICAL FIX: This was
        // missing!
        // Only use if we have merchant name or safeDescription (ML needs some input)
        // NOTE: safeAmount is already validated at the beginning of the method
        if ((safeMerchantName != null && !safeMerchantName.isEmpty())
                || (safeDescription != null && !safeDescription.isEmpty())) {
            try {
                LOGGER.debug(
                        "🏷️ parseCategory: Attempting enhanced detection - merchant='{}', safeDescription='{}', safeAmount={}, channel='{}', category='{}'",
                        safeMerchantName,
                        safeDescription,
                        safeAmount,
                        safePaymentChannel,
                        safeCategoryString);

                final EnhancedCategoryDetectionService.DetectionResult enhancedResult =
                        enhancedCategoryDetection.detectCategory(
                                safeMerchantName,
                                safeDescription,
                                safeAmount,
                                safePaymentChannel,
                                safeCategoryString);

                // CRITICAL: Log the result for debugging
                if (enhancedResult == null) {
                    LOGGER.debug("🏷️ parseCategory: Enhanced detection returned null result");
                } else if (enhancedResult.category == null) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "🏷️ parseCategory: Enhanced detection returned null category (method: {}, confidence: {})",
                                enhancedResult.method,
                                String.format("%.2f", enhancedResult.confidence));
                    }
                } else {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "🏷️ parseCategory: Enhanced detection result - category='{}', confidence={}, method='{}', reason='{}'",
                                enhancedResult.category,
                                String.format("%.2f", enhancedResult.confidence),
                                enhancedResult.method,
                                enhancedResult.reason != null ? enhancedResult.reason : "N/A");
                    }
                }

                // CRITICAL: Use enhanced detection if confidence is reasonable
                // For FUZZY_MATCH on known merchants, use lower threshold (0.50) since these are
                // deterministic matches
                // For other methods (SEMANTIC_MATCH, ML_PREDICTION), use higher threshold (0.55)
                if (enhancedResult != null && enhancedResult.category != null) {
                    final double confidenceThreshold =
                            "FUZZY_MATCH".equals(enhancedResult.method) ? 0.50 : 0.55;

                    if (enhancedResult.confidence >= confidenceThreshold) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "🏷️ parseCategory: ✅ Enhanced detection (ML/Fuzzy) found: '{}' (confidence: {}, method: {}, threshold: {}) - RETURNING THIS CATEGORY",
                                    enhancedResult.category,
                                    String.format("%.2f", enhancedResult.confidence),
                                    enhancedResult.method,
                                    String.format("%.2f", confidenceThreshold));
                        }
                        // NOTE: Do NOT train model during preview - only during actual import
                        // Training happens in TransactionService when transaction is successfully
                        // created
                        return enhancedResult.category;
                    } else {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug(
                                    "🏷️ parseCategory: Enhanced detection found '{}' but confidence too low ({} < {}), continuing to fallback methods",
                                    enhancedResult.category,
                                    String.format("%.2f", enhancedResult.confidence),
                                    String.format("%.2f", confidenceThreshold));
                        }
                    }
                } else if (enhancedResult == null) {
                    LOGGER.debug(
                            "🏷️ parseCategory: Enhanced detection returned null - no match found");
                }
            } catch (Exception e) {
                // CRITICAL: Don't fail category detection if ML/fuzzy matching fails
                // Log error but continue to fallback methods
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "🏷️ parseCategory: Enhanced category detection failed (non-fatal): {} - stack trace: {}",
                            e.getMessage(),
                            e.getClass().getSimpleName());
                }
                LOGGER.debug("🏷️ parseCategory: Enhanced detection exception details", e);
            }
        } else {
            LOGGER.debug(
                    "🏷️ parseCategory: Skipping enhanced detection - both safeMerchantName and safeDescription are null/empty");
        }
        return null;
    }

    private String parseCatLegacyStep25ZeroAmount(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 7: Check for zero safeAmount transactions (boundary condition)
        // Zero amounts are ambiguous - use category/safeDescription to determine type
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) == 0) {
            LOGGER.debug(
                    "parseCategory: Zero safeAmount transaction detected, using category/safeDescription inference");
            // Zero amounts are typically fees, adjustments, or transfers
            // If we have a category hint, use it; otherwise default to OTHER
            if (safeCategoryString != null && !safeCategoryString.isBlank()) {
                final String lower = safeCategoryString.toLowerCase(Locale.ROOT);
                final String mapped = CATEGORY_MAP.get(lower);
                if (mapped != null) {
                    LOGGER.debug(
                            "parseCategory: Zero safeAmount with category hint '{}' → '{}'",
                            safeCategoryString,
                            mapped);
                    return mapped;
                }
            }
            // For zero amounts without clear category, check safeDescription for hints
            if (safeDescription != null && !safeDescription.isBlank()) {
                final String descLower = safeDescription.toLowerCase(Locale.ROOT);
                if (descLower.contains(FEE)
                        || descLower.contains("adjustment")
                        || descLower.contains("correction")) {
                    LOGGER.debug(
                            "parseCategory: Zero safeAmount with fee/adjustment safeDescription → 'other'");
                    return OTHER;
                }
                if (descLower.contains(TRANSFER)) {
                    LOGGER.debug(
                            "parseCategory: Zero safeAmount with transfer safeDescription → 'payment'");
                    return "payment";
                }
            }
            // Default for zero amounts
            LOGGER.debug("parseCategory: Zero safeAmount without clear category → 'other'");
            return OTHER;
        }
        return null;
    }

    private String parseCatLegacyStep26AchTransactions(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 8: Check for ACH transactions (after merchant/safeDescription checks)
        if (isACHTransaction(safeDescription, safePaymentChannel)) {
            // If ACH is positive and looks like salary, categorize as salary
            if (safeAmount != null
                    && safeAmount.compareTo(BigDecimal.ZERO) > 0
                    && isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
                LOGGER.debug("parseCategory: Detected ACH salary transaction → 'salary'");
                return SALARY;
            }
            // Otherwise, ACH positive transactions are typically deposits (but not credit card
            // payments - already handled)
            if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                LOGGER.debug("parseCategory: Detected ACH deposit transaction → 'deposit'");
                return DEPOSIT;
            }
        }
        return null;
    }

    private String parseCatLegacyStep27AchCreditSpecific(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 9: Check for ACH_CREDIT specifically (before category map lookup)
        // CRITICAL: ACH_CREDIT with positive safeAmount should be DEPOSIT, not "income" (unless
        // specifically identified as salary, interest, etc.)
        // This prevents "ACH_CREDIT" from matching "credit" → "income" in the category map
        if (safeCategoryString != null && safeAmount != null) {
            final String categoryLower = safeCategoryString.toLowerCase(Locale.ROOT);
            if ((categoryLower.contains("ach_credit") || categoryLower.contains("ach credit"))
                    && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                // Already checked for salary in STEP 8, so this is a generic deposit
                LOGGER.debug(
                        "🏷️ parseCategory: Detected ACH_CREDIT with positive safeAmount → 'deposit'");
                return DEPOSIT;
            }
        }
        return null;
    }

    private String parseCatLegacyStep28StandardCategoryParse(
            final String safeCategoryString,
            final String safeDescription,
            final String safeMerchantName,
            final BigDecimal safeAmount,
            final String safePaymentChannel,
            final String safeTransactionTypeIndicator,
            final String safeTransactionType,
            final String safeAccountType,
            final String safeAccountSubtype) {
        // STEP 10: Fall back to standard category string parsing (using static map for performance)
        if (safeCategoryString != null && !safeCategoryString.isEmpty()) {
            final String lower = safeCategoryString.toLowerCase(Locale.ROOT);

            // CRITICAL: First try exact match (most reliable)
            final String exactMatch = CATEGORY_MAP.get(lower);
            if (exactMatch != null) {
                LOGGER.debug(
                        "parseCategory: Exact category string match '{}' → '{}'",
                        safeCategoryString,
                        exactMatch);
                return exactMatch;
            }

            // Then try substring match (less reliable but handles variations)
            // CRITICAL: Use longest match first to avoid partial matches (e.g., "gas" matching "gas
            // station" before "gas station")
            // CRITICAL: Exclude "credit" from matching "ACH_CREDIT" to prevent false "income"
            // categorization
            final List<Map.Entry<String, String>> sortedEntries =
                    new ArrayList<>(CATEGORY_MAP.entrySet());
            sortedEntries.sort(
                    (a, b) ->
                            Integer.compare(
                                    b.getKey().length(),
                                    a.getKey().length())); // Sort by length descending

            for (final Map.Entry<String, String> entry : sortedEntries) {
                final String key = entry.getKey();
                // CRITICAL: Skip "credit" keyword if safeCategoryString contains "ACH_CREDIT" to
                // prevent false "income" match
                if (lower.contains("ach_credit") || lower.contains("ach credit")) {
                    if ("credit".equals(key)) {
                        continue; // Skip "credit" → "income" mapping for ACH_CREDIT
                    }
                }
                if (lower.contains(key)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "parseCategory: Matched category string '{}' → '{}' (substring match)",
                                safeCategoryString,
                                entry.getValue());
                    }
                    return entry.getValue();
                }
            }
        }

        // STEP 10: Final fallback - no category detected
        // CRITICAL: Always return a valid category (never null)
        LOGGER.debug(
                "🏷️ parseCategory: No category detected for merchant='{}', safeDescription='{}', falling back to 'other'",
                safeMerchantName,
                safeDescription);
        return OTHER;
    }

    /** Overloaded method for backward compatibility (without transactionTypeIndicator) */
    public String parseCategory(
            final String categoryString,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel) {
        return parseCategory(
                categoryString,
                description,
                merchantName,
                amount,
                paymentChannel,
                null,
                null,
                null,
                null);
    }

    /**
     * Determines income category from context (transaction type, account type, description) Returns
     * specific income category: salary, deposit, dividend, interest, stipend, rentIncome, tips,
     * otherIncome
     */
    private String determineIncomeCategoryFromContext(
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel,
            final String accountType,
            String accountSubtype) {
        if (description == null && merchantName == null) {
            return null;
        }

        final String descLower = description != null ? description.toLowerCase(Locale.ROOT) : "";
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String combinedText = (merchantLower + " " + descLower).trim();

        // Salary/Payroll - highest priority
        if (isSalaryTransaction(description, amount, paymentChannel)) {
            return SALARY;
        }

        // Interest (but not from investment accounts - those are handled separately)
        final boolean isInvestmentAccount =
                accountType != null
                        && (accountType.contains("investment")
                                || accountType.contains(IRA)
                                || accountType.contains("401k")
                                || accountType.contains("hsa")
                                || accountType.contains("529"));
        if (!isInvestmentAccount
                && isInterestTransaction(null, description, merchantName, amount)) {
            return INTEREST;
        }

        // Dividend (but not from investment accounts - those are investmentDividend)
        if (!isInvestmentAccount
                && isDividendTransaction(null, description, merchantName, amount)) {
            return DIVIDEND;
        }

        // Stipend
        if (combinedText.contains("stipend")
                || combinedText.contains("scholarship")
                || combinedText.contains("grant")
                || combinedText.contains("fellowship")
                || combinedText.contains("bursary")) {
            return "stipend";
        }

        // Rental Income
        if (combinedText.contains("rent received")
                || combinedText.contains("rental income")
                || combinedText.contains("property income")
                || combinedText.contains("rent payment received")
                || (combinedText.contains(RENT)
                        && (combinedText.contains("received")
                                || combinedText.contains("income")))) {
            return "rentIncome";
        }

        // Tips
        if (combinedText.contains("tip") || combinedText.contains("gratuity")) {
            return "tips";
        }

        // Default: deposit for ACH credits or generic deposits
        if ("ach".equalsIgnoreCase(paymentChannel)
                || combinedText.contains(DEPOSIT)
                || combinedText.contains("transfer from")
                || combinedText.contains("online transfer")) {
            return DEPOSIT;
        }

        return null; // Could not determine specific income category
    }

    /*
     * Normalize merchant name for better matching and consistency Lenient normalization - removes
     * common prefixes/suffixes but preserves merchant identity
     *
     * @param merchantName Raw merchant name from CSV
     * @return Normalized merchant name
     */

    /** Detects if a transaction is ACH (Automated Clearing House) */
    private boolean isACHTransaction(String description, final String paymentChannel) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);

        // Check payment channel first
        if (paymentChannel != null && "ach".equalsIgnoreCase(paymentChannel.trim())) {
            return true;
        }

        // Check description for ACH indicators
        final String[] achKeywords = {
            "ach", "automated clearing house", "direct deposit", "directdeposit",
            "dd deposit", "electronic deposit", "e deposit", "wire transfer",
            "bank transfer", "online transfer"
        };

        for (final String keyword : achKeywords) {
            if (descLower.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    /** Detects if a transaction is interest income */
    private boolean isInterestTransaction(
            final String categoryString,
            final String description,
            String merchantName,
            BigDecimal amount) {
        // Check category string first (most reliable)
        if (categoryString != null) {
            final String categoryLower = categoryString.toLowerCase(Locale.ROOT);
            if (categoryLower.contains(INTEREST) || INTEREST.equals(categoryLower)) {
                return true;
            }
        }

        // Check description for interest keywords
        if (description != null) {
            final String descLower = description.toLowerCase(Locale.ROOT);
            final String[] interestKeywords = {
                INTEREST,
                "intrst",
                "intr payment",
                "intrst payment",
                "intrst pymnt",
                "interest payment",
                "interest income",
                "interest earned",
                "interest credit"
            };
            for (final String keyword : interestKeywords) {
                if (descLower.contains(keyword)) {
                    // Exclude CD interest (that's investment)
                    if (!descLower.contains("cd interest") && !descLower.contains("certificate")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /** Detects if a transaction is dividend income */
    private boolean isDividendTransaction(
            final String categoryString,
            final String description,
            String merchantName,
            BigDecimal amount) {
        // Check category string first (most reliable)
        if (categoryString != null) {
            final String categoryLower = categoryString.toLowerCase(Locale.ROOT);
            if (categoryLower.contains(DIVIDEND) || DIVIDEND.equals(categoryLower)) {
                return true;
            }
        }

        // Check description for dividend keywords
        if (description != null) {
            final String descLower = description.toLowerCase(Locale.ROOT);
            final String[] dividendKeywords = {
                DIVIDEND,
                "dividends",
                "stock dividend",
                "dividend payment",
                "dividend income",
                "dividend distribution",
                "dividend credit"
            };
            for (final String keyword : dividendKeywords) {
                if (descLower.contains(keyword)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Detects if a transaction is salary/payroll */
    private boolean isSalaryTransaction(
            final String description, final BigDecimal amount, final String paymentChannel) {
        if (description == null) {
            return false;
        }

        // Salary should be positive income
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        final String descLower = description.toLowerCase(Locale.ROOT);

        // Check for salary/payroll keywords
        final String[] salaryKeywords = {
            SALARY,
            "payroll",
            "pay check",
            "paycheck",
            "wage",
            "wages",
            "pay stub",
            "paystub",
            "pay day",
            "payday",
            "direct deposit payroll",
            "payroll deposit",
            "employee payroll",
            "payroll payment",
            "salary payment",
            "pay from",
            "payment from employer",
            "employer payment",
            "pay adv",
            "pay advance",
            // Common payroll providers
            "adp",
            "paychex",
            "gusto",
            "justworks",
            "bamboo hr",
            "zenefits",
            "workday",
            "ceridian",
            "tri-net"
        };

        for (final String keyword : salaryKeywords) {
            if (descLower.contains(keyword)) {
                return true;
            }
        }

        // Check for ACH + salary-like description
        if (isACHTransaction(description, paymentChannel)) {
            // If it's ACH and has salary keywords, likely salary
            for (final String keyword : salaryKeywords) {
                if (descLower.contains(keyword)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Detects if a transaction is a cash withdrawal Cash withdrawals should be categorized as
     * "cash", type EXPENSE Examples: "ATM WITHDRAWAL", "CASH WITHDRAWAL", "ATM DEBIT", etc.
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @param transactionTypeIndicator Transaction type indicator
     * @param paymentChannel Payment channel (e.g., "atm")
     * @return true if this is a cash withdrawal
     */
    private boolean isCashWithdrawal(
            String description,
            final String merchantName,
            final String categoryString,
            final String transactionTypeIndicator,
            final String paymentChannel) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String categoryLower =
                categoryString != null ? categoryString.toLowerCase(Locale.ROOT) : "";
        final String typeIndicatorLower =
                transactionTypeIndicator != null
                        ? transactionTypeIndicator.toLowerCase(Locale.ROOT)
                        : "";
        final String paymentChannelLower =
                paymentChannel != null ? paymentChannel.toLowerCase(Locale.ROOT) : "";

        LOGGER.debug(
                "isCashWithdrawal: Checking description='{}', merchant='{}', category='{}', type='{}', channel='{}'",
                description,
                merchantName,
                categoryString,
                transactionTypeIndicator,
                paymentChannel);

        // Check category string first (most reliable)
        if (categoryLower.contains("cash")
                || categoryLower.contains("atm")
                || categoryLower.contains("withdrawal")
                || categoryLower.contains("cash withdrawal")) {
            LOGGER.info(
                    "isCashWithdrawal: ✅ Detected cash withdrawal from categoryString '{}'",
                    categoryString);
            return true;
        }

        // Check payment channel (ATM is a strong indicator)
        if (paymentChannelLower.contains("atm") || paymentChannelLower.contains("cash")) {
            LOGGER.info(
                    "isCashWithdrawal: ✅ Detected cash withdrawal from paymentChannel '{}'",
                    paymentChannel);
            return true;
        }

        // Check transaction type indicator
        if (typeIndicatorLower.contains("atm")
                || typeIndicatorLower.contains("cash")
                || typeIndicatorLower.contains("withdrawal")) {
            LOGGER.info(
                    "isCashWithdrawal: ✅ Detected cash withdrawal from transactionTypeIndicator '{}'",
                    transactionTypeIndicator);
            return true;
        }

        // Check for cash withdrawal patterns in description/merchant name
        // CRITICAL FIX: Include standalone "withdrawal" keyword
        final String[] cashKeywords = {
            "atm withdrawal",
            "atm withdraw",
            "cash withdrawal",
            "cash withdraw",
            "atm debit",
            "atm transaction",
            "atm cash",
            "cash advance",
            "withdrawal",
            "withdraw cash",
            "cash out",
            "cashout",
            "withdraw"
        };

        for (final String keyword : cashKeywords) {
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                // Exclude credit card cash advances (those are different)
                if (!descLower.contains("credit card")
                        && !descLower.contains(CREDITCARD)
                        && !merchantLower.contains("credit card")
                        && !merchantLower.contains(CREDITCARD)) {
                    LOGGER.info(
                            "isCashWithdrawal: ✅ Detected cash withdrawal keyword '{}' in description='{}' or merchant='{}'",
                            keyword,
                            description,
                            merchantName);
                    return true;
                }
            }
        }

        LOGGER.debug(
                "isCashWithdrawal: No cash withdrawal detected for description='{}', merchant='{}'",
                description,
                merchantName);
        return false;
    }

    /**
     * CRITICAL FIX: Detects if a transaction is a check payment Check payments should be
     * categorized as "payment", not transportation (e.g., "CHECK 176" was matching gas station
     * "76")
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @param transactionTypeIndicator Transaction type indicator (e.g., "CHECK")
     * @return true if this is a check payment
     */
    private boolean isCheckPayment(
            String description,
            final String merchantName,
            final String categoryString,
            final String transactionTypeIndicator) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String categoryLower =
                categoryString != null ? categoryString.toLowerCase(Locale.ROOT) : "";
        final String typeIndicatorLower =
                transactionTypeIndicator != null
                        ? transactionTypeIndicator.toLowerCase(Locale.ROOT)
                        : "";

        // Check transaction type indicator first (most reliable)
        if (typeIndicatorLower.contains("check")
                || typeIndicatorLower.contains("chk")
                || typeIndicatorLower.contains("cheque")) {
            LOGGER.debug(
                    "isCheckPayment: Detected check payment from transactionTypeIndicator '{}'",
                    transactionTypeIndicator);
            return true;
        }

        // Check category string
        if (categoryLower.contains("check")
                || categoryLower.contains("chk")
                || categoryLower.contains("cheque")
                || categoryLower.contains("check_paid")
                || categoryLower.contains("check_payment")) {
            LOGGER.debug(
                    "isCheckPayment: Detected check payment from categoryString '{}'",
                    categoryString);
            return true;
        }

        // Check description/merchant name for check payment patterns
        // CRITICAL: Must match "CHECK" as a word, not just "check" as substring (to avoid false
        // positives)
        // Patterns: "CHECK #123", "CHECK 123", "CHECK NUMBER", "CHECK PAYMENT", etc.
        final String[] checkPatterns = {
            "check #",
            "check number",
            "check no",
            "check payment",
            "check paid",
            "check #",
            "chk #",
            "chk number",
            "chk no",
            "cheque #",
            "cheque number"
        };

        for (final String pattern : checkPatterns) {
            if (descLower.contains(pattern) || merchantLower.contains(pattern)) {
                LOGGER.debug(
                        "isCheckPayment: Detected check payment pattern '{}' in description/merchant",
                        pattern);
                return true;
            }
        }

        // Check for "CHECK" followed by a number (e.g., "CHECK 176", "CHECK #176")
        // Use word boundary to ensure "CHECK" is a standalone word
        if ((descLower.matches(".*\\bcheck\\s+#?\\d+.*")
                        || merchantLower.matches(".*\\bcheck\\s+#?\\d+.*"))
                && !descLower.contains(CHECKING)
                && !merchantLower.contains(CHECKING)) {
            LOGGER.debug(
                    "isCheckPayment: Detected check payment with number pattern in description/merchant");
            return true;
        }

        return false;
    }

    /**
     * Detects if a transaction is a utility bill payment (direct payment to utility company)
     * Utility bill payments should be categorized as UTILITIES, not "payment" Examples: "PUGET
     * SOUND ENER BILLPAY", "CITY OF BELLEVUE UTILITY", etc.
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is a utility bill payment
     */
    private boolean isUtilityBillPayment(
            String description, final String merchantName, final String categoryString) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";

        LOGGER.debug(
                "isUtilityBillPayment: Checking description='{}', merchant='{}', category='{}'",
                description,
                merchantName,
                categoryString);

        // CRITICAL: Reject airport expenses (carts, chairs, parking, etc.) - they are
        // transportation, not utilities
        // "SEATTLEAP CART/CHAIR" (Seattle Airport cart) should not match "Seattle Public Utilities"
        final String combined = (merchantLower + " " + descLower).trim();
        if (combined.contains("airport")
                && (combined.contains("cart") || combined.contains("chair"))) {
            LOGGER.debug("isUtilityBillPayment: Rejecting airport cart/chair → not a utility");
            return false;
        }
        if ((combined.contains("seattleap")
                        || combined.contains("seattle ap")
                        || combined.contains("seattle airport"))
                && (combined.contains("cart") || combined.contains("chair"))) {
            LOGGER.debug("isUtilityBillPayment: Rejecting SEATTLEAP cart/chair → not a utility");
            return false;
        }
        // Also reject if it's just "SEATTLEAP" without clear utility indicators
        if ((combined.contains("seattleap") || combined.contains("seattle ap"))
                && !combined.contains("utility")
                && !combined.contains(UTILITIES)
                && !combined.contains("public utilities")) {
            LOGGER.debug(
                    "isUtilityBillPayment: Rejecting SEATTLEAP (airport, not utility) → not a utility");
            return false;
        }

        // Check for utility company names combined with payment indicators
        final String[] utilityCompanies = {
            "puget sound energy",
            "pse",
            "pacific gas",
            "pg&e",
            "pge",
            "southern california edison",
            "sce",
            "san diego gas",
            "sdge",
            "edison",
            "con edison",
            "coned",
            "duke energy",
            "dukeenergy",
            "dominion energy",
            "dominionenergy",
            "exelon",
            "first energy",
            "firstenergy",
            "american electric",
            "aep",
            "southern company",
            "southerncompany",
            "next era",
            "nextera",
            "xcel energy",
            "xcelenergy",
            "centerpoint",
            "center point",
            "entergy",
            "entergy",
            "evergy",
            "evergy",
            "pacificorp",
            "pacific corp",
            "portland general",
            "portlandgeneral",
            "city of",
            "municipal utility",
            "municipalutility"
        };

        // CRITICAL: Reject if it contains "city of" but also contains airport terms
        // "SEATTLEAP" should not match "city of seattle" if it's an airport expense
        if ((descLower.contains("city of") || merchantLower.contains("city of"))
                && ((descLower.contains("seattleap")
                                || merchantLower.contains("seattleap")
                                || descLower.contains("seattle ap")
                                || merchantLower.contains("seattle ap")
                                || descLower.contains("airport")
                                || merchantLower.contains("airport"))
                        && (descLower.contains("cart")
                                || merchantLower.contains("cart")
                                || descLower.contains("chair")
                                || merchantLower.contains("chair")))) {
            LOGGER.debug(
                    "isUtilityBillPayment: Rejecting 'city of seattle' match for airport cart/chair");
            return false;
        }

        // Check if description/merchant contains utility company name
        for (final String company : utilityCompanies) {
            if (descLower.contains(company) || merchantLower.contains(company)) {
                // Additional check: must be a payment (billpay, payment, autopay, etc.)
                // But NOT a credit card payment (exclude credit card company names)
                if ((descLower.contains("billpay")
                                || descLower.contains("bill pay")
                                || descLower.contains("payment")
                                || descLower.contains("autopay")
                                || descLower.contains("auto pay")
                                || descLower.contains("ppd id")
                                || merchantLower.contains("billpay")
                                || merchantLower.contains("bill pay")
                                || merchantLower.contains("payment")
                                || merchantLower.contains("autopay")
                                || merchantLower.contains("auto pay")
                                || merchantLower.contains("ppd id"))
                        && !descLower.contains("credit card")
                        && !descLower.contains(CREDITCARD)
                        && !merchantLower.contains("credit card")
                        && !merchantLower.contains(CREDITCARD)
                        && !descLower.contains("chase")
                        && !descLower.contains("citi")
                        && !descLower.contains(AMEX)
                        && !descLower.contains("discover")
                        && !merchantLower.contains("chase")
                        && !merchantLower.contains("citi")
                        && !merchantLower.contains(AMEX)
                        && !merchantLower.contains("discover")) {
                    LOGGER.info(
                            "isUtilityBillPayment: ✅ Detected utility bill payment for company '{}' in description='{}' or merchant='{}'",
                            company,
                            description,
                            merchantName);
                    return true;
                }
            }
        }

        // Check for utility patterns combined with payment indicators
        final String[] utilityKeywords = {
            "utility",
            UTILITIES,
            "energy",
            "ener ",
            "electric",
            "electricity",
            "gas company",
            "water company",
            "power company",
            "water utility"
        };

        final String[] paymentKeywords = {
            "billpay", "bill pay", "payment", "autopay", "auto pay", "ppd id"
        };

        for (final String utilityKeyword : utilityKeywords) {
            if (descLower.contains(utilityKeyword) || merchantLower.contains(utilityKeyword)) {
                for (final String paymentKeyword : paymentKeywords) {
                    if (descLower.contains(paymentKeyword)
                            || merchantLower.contains(paymentKeyword)) {
                        // Exclude credit card payments
                        if (!descLower.contains("credit card")
                                && !descLower.contains(CREDITCARD)
                                && !merchantLower.contains("credit card")
                                && !merchantLower.contains(CREDITCARD)
                                && !descLower.contains("chase")
                                && !descLower.contains("citi")
                                && !descLower.contains(AMEX)
                                && !descLower.contains("discover")
                                && !merchantLower.contains("chase")
                                && !merchantLower.contains("citi")
                                && !merchantLower.contains(AMEX)
                                && !merchantLower.contains("discover")) {
                            LOGGER.info(
                                    "isUtilityBillPayment: ✅ Detected utility bill payment with keywords '{}' + '{}' in description='{}' or merchant='{}'",
                                    utilityKeyword,
                                    paymentKeyword,
                                    description,
                                    merchantName);
                            return true;
                        }
                    }
                }
            }
        }

        LOGGER.debug(
                "isUtilityBillPayment: No utility bill payment detected for description='{}', merchant='{}'",
                description,
                merchantName);
        return false;
    }

    /**
     * CRITICAL FIX: Detects if an ACH transaction is a credit card payment ACH credit card payments
     * should be categorized as "payment" (expense), not DEPOSIT (income)
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is a credit card payment
     */
    private boolean isCreditCardPayment(
            String description, final String merchantName, final String categoryString) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String categoryLower =
                categoryString != null ? categoryString.toLowerCase(Locale.ROOT) : "";

        LOGGER.debug(
                "isCreditCardPayment: Checking description='{}', merchant='{}', category='{}'",
                description,
                merchantName,
                categoryString);

        // CRITICAL FIX: Check for "E-PAYMENT" with credit card company names FIRST (before other
        // patterns)
        // This catches "DISCOVER E-PAYMENT", "DISCOVER         E-PAYMENT", etc. even with extra
        // spaces
        if ((descLower.contains(E_PAYMENT)
                        || merchantLower.contains(E_PAYMENT)
                        || descLower.contains(EPAYMENT)
                        || merchantLower.contains(EPAYMENT))
                && (descLower.contains("discover")
                        || merchantLower.contains("discover")
                        || descLower.contains("chase")
                        || merchantLower.contains("chase")
                        || descLower.contains("citi")
                        || merchantLower.contains("citi")
                        || descLower.contains(AMEX)
                        || merchantLower.contains(AMEX))) {
            LOGGER.info(
                    "isCreditCardPayment: ✅ Detected e-payment with credit card company name (description='{}', merchant='{}')",
                    description,
                    merchantName);
            return true;
        }

        // CRITICAL FIX: Check for "AUTOPAY" with credit card company names (before other patterns)
        // This catches "CHASE CREDIT CRD AUTOPAY", "CITI AUTOPAY", "WF Credit Card AUTO PAY", etc.
        // and prevents false matches
        // Must check this FIRST to catch autopay patterns before they get misclassified
        // Note: "AUTO PAY" (with space) should match "autopay" or "auto pay" since we're using
        // contains()
        final boolean hasAutopay =
                descLower.contains("autopay")
                        || merchantLower.contains("autopay")
                        || descLower.contains("auto pay")
                        || merchantLower.contains("auto pay");
        final boolean hasCreditCardCompany =
                descLower.contains("chase")
                        || descLower.contains("citi")
                        || descLower.contains(AMEX)
                        || descLower.contains("discover")
                        || descLower.contains("capital one")
                        || descLower.contains("wells fargo")
                        || descLower.contains("wf")
                        || // CRITICAL: "WF" is Wells Fargo abbreviation (must be standalone word to
                        // avoid false positives)
                        descLower.contains("bofa")
                        || descLower.contains("bank of america")
                        || descLower.contains("synchrony")
                        || descLower.contains("us bank")
                        || descLower.contains("barclays")
                        || descLower.contains("amazon")
                        || descLower.contains("amz")
                        || // CRITICAL: Amazon Store Card
                        descLower.contains("store card")
                        || descLower.contains("storecrd")
                        || // Store card payments
                        merchantLower.contains("chase")
                        || merchantLower.contains("citi")
                        || merchantLower.contains(AMEX)
                        || merchantLower.contains("discover")
                        || merchantLower.contains("capital one")
                        || merchantLower.contains("wells fargo")
                        || merchantLower.contains("wf")
                        || // CRITICAL: "WF" is Wells Fargo abbreviation
                        merchantLower.contains("bofa")
                        || merchantLower.contains("bank of america")
                        || merchantLower.contains("synchrony")
                        || merchantLower.contains("us bank")
                        || merchantLower.contains("barclays")
                        || merchantLower.contains("amazon")
                        || merchantLower.contains("amz")
                        || // CRITICAL: Amazon Store Card
                        merchantLower.contains("store card")
                        || merchantLower.contains("storecrd"); // Store card payments

        if (hasAutopay && hasCreditCardCompany) {
            LOGGER.info(
                    "isCreditCardPayment: ✅ Detected credit card autopay with company name (description='{}', merchant='{}')",
                    description,
                    merchantName);
            return true;
        }

        // Check category string (most reliable)
        if (categoryLower.contains("credit card")
                || categoryLower.contains(CREDITCARD)
                || categoryLower.contains("card payment")
                || categoryLower.contains("card autopay")
                || categoryLower.contains("autopay")
                || categoryLower.contains("auto pay")) {
            LOGGER.info(
                    "isCreditCardPayment: ✅ Detected credit card payment from categoryString '{}'",
                    categoryString);
            return true;
        }

        // Check for credit card payment indicators in description/merchant name
        // CRITICAL: Expanded list to catch more patterns including "CITI AUTOPAY", "CHASE AUTOPAY",
        // "AMZ_STORECRD_PMT", "DISCOVER E-PAYMENT", etc.
        final String[] creditCardKeywords = {
            "credit card",
            CREDITCARD,
            "credit crd",
            "card autopay",
            "card payment",
            "autopay",
            "auto pay",
            "automatic payment",
            "card autopay",
            E_PAYMENT,
            EPAYMENT,
            "e payment", // CRITICAL: Discover and other cards use "E-PAYMENT"
            "chase credit crd",
            "chase credit card",
            "chase autopay",
            "chase card",
            "citi autopay",
            "citi card",
            "citicard",
            "citi credit",
            "citicardap",
            "amex autopay",
            "amex card",
            "american express",
            "amex payment",
            "discover autopay",
            "discover card",
            "discover payment",
            "discover e-payment", // CRITICAL: Discover E-PAYMENT pattern
            "wells fargo credit",
            "wf credit card",
            "wf credit",
            "wells fargo autopay", // CRITICAL: Added "wf credit" for "WF Credit Card"
            "bofa credit card",
            "bank of america credit",
            "bofa autopay",
            "capital one credit",
            "capitalone",
            "capital one autopay",
            "synchrony",
            "synchrony bank",
            "synchrony autopay",
            "us bank credit",
            "usbank credit",
            "us bank autopay",
            "barclays credit",
            "barclays autopay",
            "barclays card",
            "amazon store card",
            "amazon storecard",
            "amz store card",
            "amz storecrd", // CRITICAL: Amazon Store Card
            "amz_storecrd_pmt",
            "amz storecrd pmt",
            "store card payment",
            "storecard payment", // Amazon Store Card payment patterns
            "web id:",
            "web id",
            "citicardap", // Citi-specific patterns like "WEB ID: CITICARDAP"
            "ppd id:" // PPD (Prearranged Payment and Deposit) ID pattern for autopay
        };

        for (final String keyword : creditCardKeywords) {
            // CRITICAL: Use contains() which handles extra spaces (e.g., "DISCOVER
            // E-PAYMENT" contains "discover e-payment")
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                LOGGER.info(
                        "isCreditCardPayment: ✅ Detected credit card payment keyword '{}' in description='{}' or merchant='{}'",
                        keyword,
                        description,
                        merchantName);
                return true;
            }
        }

        // CRITICAL FIX: Also check for "discover" + E_PAYMENT separately (handles extra spaces
        // like "DISCOVER         E-PAYMENT")
        // This is a fallback in case the combined keyword doesn't match due to spacing
        if ((descLower.contains("discover") || merchantLower.contains("discover"))
                && (descLower.contains(E_PAYMENT)
                        || descLower.contains(EPAYMENT)
                        || merchantLower.contains(E_PAYMENT)
                        || merchantLower.contains(EPAYMENT))) {
            LOGGER.info(
                    "isCreditCardPayment: ✅ Detected Discover e-payment (separate keywords) in description='{}' or merchant='{}'",
                    description,
                    merchantName);
            return true;
        }

        // Check for payment patterns with card numbers or account identifiers
        // Pattern: "PAYMENT" or "E-PAYMENT" followed by digits (card number) or "WEB ID:" / "PPD
        // ID:" (online payment)
        // Also check for "AUTO PAY" (with space) or "AUTOPAY" patterns
        // CRITICAL: Added E_PAYMENT and EPAYMENT to catch Discover E-PAYMENT patterns
        final boolean hasPaymentKeyword =
                descLower.contains("payment")
                        || descLower.contains(E_PAYMENT)
                        || descLower.contains(EPAYMENT)
                        || merchantLower.contains("payment")
                        || merchantLower.contains(E_PAYMENT)
                        || merchantLower.contains(EPAYMENT);
        final boolean hasPaymentIndicator =
                (descLower.matches(".*\\d{10,}.*")
                                || descLower.contains("web id")
                                || descLower.contains("ppd id")
                                || descLower.contains("auto pay")
                                || descLower.contains("autopay")
                                || descLower.contains(E_PAYMENT)
                                || descLower.contains(EPAYMENT))
                        || (merchantLower.matches(".*\\d{10,}.*")
                                || merchantLower.contains("web id")
                                || merchantLower.contains("ppd id")
                                || merchantLower.contains("auto pay")
                                || merchantLower.contains("autopay")
                                || merchantLower.contains(E_PAYMENT)
                                || merchantLower.contains(EPAYMENT));

        if (hasPaymentKeyword && hasPaymentIndicator) {
            // Additional check: must have credit card company name, "autopay", "credit", CARD,
            // "store card", or "amazon"
            // CRITICAL: Added "wf" for Wells Fargo abbreviation, "auto pay" for "AUTO PAY" pattern,
            // and Amazon Store Card patterns
            if (descLower.contains("citi")
                    || descLower.contains("chase")
                    || descLower.contains(AMEX)
                    || descLower.contains("discover")
                    || descLower.contains("capital one")
                    || descLower.contains("autopay")
                    || descLower.contains("auto pay")
                    || descLower.contains("wells fargo")
                    || descLower.contains("wf")
                    || descLower.contains("amazon")
                    || descLower.contains("amz")
                    || descLower.contains("store card")
                    || descLower.contains("storecrd")
                    || descLower.contains("credit")
                    || descLower.contains(CARD)
                    || merchantLower.contains("citi")
                    || merchantLower.contains("chase")
                    || merchantLower.contains(AMEX)
                    || merchantLower.contains("discover")
                    || merchantLower.contains("capital one")
                    || merchantLower.contains("autopay")
                    || merchantLower.contains("auto pay")
                    || merchantLower.contains("wells fargo")
                    || merchantLower.contains("wf")
                    || merchantLower.contains("amazon")
                    || merchantLower.contains("amz")
                    || merchantLower.contains("store card")
                    || merchantLower.contains("storecrd")
                    || merchantLower.contains("credit")
                    || merchantLower.contains(CARD)) {
                LOGGER.info(
                        "isCreditCardPayment: ✅ Detected credit card/store card payment pattern with card number/web id/ppd id/auto pay");
                return true;
            }
        }

        LOGGER.debug(
                "isCreditCardPayment: No credit card payment detected for description='{}', merchant='{}'",
                description,
                merchantName);
        return false;
    }

    /**
     * Detects if a transaction is a loan payment (mortgage, auto loan, student loan, etc.) Loan
     * payments should be categorized as "payment", not OTHER
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is a loan payment
     */
    private boolean isLoanPayment(
            String description, final String merchantName, final String categoryString) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String categoryLower =
                categoryString != null ? categoryString.toLowerCase(Locale.ROOT) : "";

        // Check category string first (most reliable)
        if (categoryLower.contains("loan payment")
                || categoryLower.contains("mortgage payment")
                || categoryLower.contains("auto loan")
                || categoryLower.contains("student loan")
                || categoryLower.contains("personal loan")
                || categoryLower.contains("home loan")) {
            LOGGER.debug(
                    "isLoanPayment: Detected loan payment from categoryString '{}'",
                    categoryString);
            return true;
        }

        // Check for loan payment patterns
        final String[] loanKeywords = {
            "mortgage payment",
            "mortgage pay",
            "mortgage autopay",
            "auto loan",
            "car loan",
            "vehicle loan",
            "student loan",
            "education loan",
            "personal loan",
            "home loan",
            "home equity",
            "loan payment",
            "loan pay",
            "loan autopay",
            "principal payment",
            "interest payment"
        };

        for (final String keyword : loanKeywords) {
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                LOGGER.debug("isLoanPayment: Detected loan payment keyword '{}'", keyword);
                return true;
            }
        }

        return false;
    }

    /**
     * Detects if a transaction is an investment transfer (from/to investment firms) Investment
     * transfers should be categorized as "investment", not TRANSFER or DEPOSIT Examples: "Online
     * Transfer from Morgan Stanley", "Transfer from Fidelity", etc.
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is an investment transfer
     */
    private boolean isInvestmentTransfer(
            String description, final String merchantName, final String categoryString) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String categoryLower =
                categoryString != null ? categoryString.toLowerCase(Locale.ROOT) : "";

        LOGGER.debug(
                "isInvestmentTransfer: Checking description='{}', merchant='{}', category='{}'",
                description,
                merchantName,
                categoryString);

        // Check for investment firm names in description/merchant
        // Major investment firms and brokerages
        final String[] investmentFirms = {
            "morgan stanley",
            "morganstanley",
            "morgan stanley smith barney",
            "fidelity",
            "fidelity investments",
            "fidelity.com",
            "vanguard",
            "vanguard group",
            "vanguard.com",
            "charles schwab",
            "schwab",
            "schwab.com",
            "td ameritrade",
            "ameritrade",
            "tdameritrade",
            "etrade",
            "e-trade",
            "etrade.com",
            "robinhood",
            "robin hood",
            "robinhood.com",
            "merrill lynch",
            "merrill",
            "merrilllynch",
            "goldman sachs",
            "goldman",
            "goldmansachs",
            "jpmorgan",
            "jp morgan",
            "jpmorgan chase",
            "wells fargo advisors",
            "wells fargo investment",
            "edward jones",
            "edwardjones",
            "raymond james",
            "raymondjames",
            "lpl financial",
            "lpl",
            "ameriprise",
            "ameriprise financial",
            "prudential",
            "prudential financial",
            "northwestern mutual",
            "northwesternmutual",
            "massmutual",
            "mass mutual",
            "new york life",
            "newyorklife",
            "t rowe price",
            "troweprice",
            "franklin templeton",
            "franklintempleton",
            "blackrock",
            "ishares",
            "state street",
            "statestreet"
        };

        // Check if description/merchant contains investment firm name
        for (final String firm : investmentFirms) {
            if (descLower.contains(firm) || merchantLower.contains(firm)) {
                // CRITICAL FIX: For investment firms, check amount sign
                // - Negative amount (debit) = investment transfer (money going out)
                // - Positive amount (credit) = deposit (money coming in, not investment expense)
                // This fixes: "Online transfer from Morgan Stanley" (credit) should be DEPOSIT,
                // not "investment"
                boolean isCredit = false;
                // Note: amount is not available in this method, so we check description for credit
                // indicators
                if (descLower.contains("credit")
                        || descLower.contains(DEPOSIT)
                        || descLower.contains(FROM)
                        || merchantLower.contains(FROM)) {
                    // If it says FROM or "credit" or DEPOSIT, it's likely a credit (deposit)
                    // Investment transfers are typically debits (money going out)
                    isCredit = true;
                }

                // Additional check: must be a transfer (not a purchase/sale)
                if (descLower.contains(TRANSFER)
                        || merchantLower.contains(TRANSFER)
                        || descLower.contains(FROM)
                        || descLower.contains("to")
                        || categoryLower.contains("acct_xfer")
                        || categoryLower.contains(TRANSFER)) {
                    // CRITICAL: If it's a credit (deposit), it's NOT an investment transfer
                    // Investment transfers are debits (money going out to investment account)
                    if (!isCredit) {
                        LOGGER.info(
                                "isInvestmentTransfer: ✅ Detected investment transfer (debit) from firm '{}' in description='{}' or merchant='{}'",
                                firm,
                                description,
                                merchantName);
                        return true;
                    } else {
                        LOGGER.debug(
                                "isInvestmentTransfer: Skipping credit/deposit from investment firm '{}' - this should be 'deposit', not 'investment'",
                                firm);
                    }
                }
            }
        }

        // Check for investment-related keywords combined with transfer
        final String[] investmentKeywords = {
            "brokerage",
            "broker",
            "investment account",
            "investmentaccount",
            IRA,
            "401k",
            "401(k)",
            "403b",
            "403(b)",
            "529",
            "hsa",
            "retirement account",
            "retirementaccount",
            "pension",
            "mutual fund",
            "mutualfund",
            "etf",
            "stock",
            "stocks",
            "portfolio",
            "trading account",
            "tradingaccount"
        };

        for (final String keyword : investmentKeywords) {
            if ((descLower.contains(keyword) || merchantLower.contains(keyword))
                    && (descLower.contains(TRANSFER)
                            || merchantLower.contains(TRANSFER)
                            || descLower.contains(FROM)
                            || descLower.contains("to")
                            || categoryLower.contains("acct_xfer")
                            || categoryLower.contains(TRANSFER))) {
                LOGGER.info(
                        "isInvestmentTransfer: ✅ Detected investment transfer with keyword '{}' in description='{}' or merchant='{}'",
                        keyword,
                        description,
                        merchantName);
                return true;
            }
        }

        LOGGER.debug(
                "isInvestmentTransfer: No investment transfer detected for description='{}', merchant='{}'",
                description,
                merchantName);
        return false;
    }

    /**
     * Detects if a transaction is an account transfer (between accounts, not an expense) Account
     * transfers should be categorized as TRANSFER, not OTHER or "payment" Examples: "Online
     * Transfer to CHK", "ACCT_XFER", "Transfer to Savings", etc.
     *
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is an account transfer
     */
    private boolean isAccountTransfer(
            String description, final String merchantName, final String categoryString) {
        if (description == null) {
            description = "";
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String merchantLower =
                merchantName != null ? merchantName.toLowerCase(Locale.ROOT) : "";
        final String categoryLower =
                categoryString != null ? categoryString.toLowerCase(Locale.ROOT) : "";

        LOGGER.debug(
                "isAccountTransfer: Checking description='{}', merchant='{}', category='{}'",
                description,
                merchantName,
                categoryString);

        // Check category string first (most reliable)
        // ACCT_XFER = Account Transfer
        if (categoryLower.contains("acct_xfer")
                || categoryLower.contains("account transfer")
                || categoryLower.contains(TRANSFER)
                || categoryLower.contains("xfer")) {
            // But exclude credit card balance transfers and loan payments
            if (!categoryLower.contains("balance transfer") && !categoryLower.contains(LOAN)) {
                LOGGER.info(
                        "isAccountTransfer: ✅ Detected account transfer from categoryString '{}'",
                        categoryString);
                return true;
            }
        }

        // Check for transfer patterns in description/merchant name
        // Patterns: "Online Transfer to CHK", "Transfer to Savings", "Transfer from Checking", etc.
        // CRITICAL: Include money transfer services like Remitly
        final String[] transferKeywords = {
            "online transfer",
            "transfer to",
            "transfer from",
            "account transfer",
            "transfer to chk",
            "transfer to checking",
            "transfer to savings",
            "transfer from chk",
            "transfer from checking",
            "transfer from savings",
            "xfer to",
            "xfer from",
            "wire transfer",
            "wire debit",
            "wire credit",
            "international wire",
            "remitly",
            "rmtly",
            "money transfer",
            "currency transfer"
        };

        // Check for money transfer companies
        // CRITICAL FIX: Use word boundaries to prevent false positives (e.g., "FACTORIA" matching
        // "ria")
        final String[] transferCompanies = {
            "remitly",
            "rmtly",
            "western union",
            "moneygram",
            "wise",
            "transferwise",
            "xoom",
            "worldremit",
            "ria money transfer" // Removed standalone "ria" - too many false positives
        };

        for (final String company : transferCompanies) {
            // CRITICAL FIX: Use word boundaries for short company names to prevent substring
            // matches
            // For "ria", require it to be a whole word or part of "ria money transfer"
            if (company.length() <= 3) {
                // Short names: require word boundary (start/end of string or non-word character)
                final String pattern = "\\b" + company + "\\b";
                if (descLower.matches(".*" + pattern + ".*")
                        || (merchantName != null
                                && !merchantName.isBlank()
                                && merchantLower.matches(".*" + pattern + ".*"))) {
                    LOGGER.info(
                            "isAccountTransfer: ✅ Detected money transfer company '{}' in description='{}' or merchant='{}'",
                            company,
                            description,
                            merchantName);
                    return true;
                }
            } else {
                // Longer names: can use contains (less likely to be substring)
                if (descLower.contains(company)
                        || (merchantName != null
                                && !merchantName.isBlank()
                                && merchantLower.contains(company))) {
                    LOGGER.info(
                            "isAccountTransfer: ✅ Detected money transfer company '{}' in description='{}' or merchant='{}'",
                            company,
                            description,
                            merchantName);
                    return true;
                }
            }
        }

        // CRITICAL FIX: Check for standalone "ria" only if merchantName is not null (to avoid false
        // positives in descriptions)
        // "ria" alone is too ambiguous - only match if it's in merchant name (more reliable)
        if (merchantName != null && !merchantName.isBlank()) {
            if (merchantLower.matches(".*\\bria\\b.*") && merchantLower.length() < 20) {
                // Only match "ria" in short merchant names (likely to be actual company name)
                LOGGER.info(
                        "isAccountTransfer: ✅ Detected money transfer company 'ria' in merchant='{}'",
                        merchantName);
                return true;
            }
        }

        for (final String keyword : transferKeywords) {
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                // Additional check: must NOT be a credit card balance transfer or loan payment
                if (!descLower.contains("balance transfer")
                        && !descLower.contains("loan payment")
                        && !merchantLower.contains("balance transfer")
                        && !merchantLower.contains("loan payment")) {
                    LOGGER.info(
                            "isAccountTransfer: ✅ Detected account transfer keyword '{}' in description='{}' or merchant='{}'",
                            keyword,
                            description,
                            merchantName);
                    return true;
                }
            }
        }

        // Check for "CHK" (checking account) in transfer context
        // Pattern: "Transfer to CHK" or "Transfer from CHK"
        if ((descLower.contains(TRANSFER) || merchantLower.contains(TRANSFER))
                && (descLower.contains("chk")
                        || descLower.contains(CHECKING)
                        || descLower.contains(SAVINGS)
                        || descLower.contains("account")
                        || merchantLower.contains("chk")
                        || merchantLower.contains(CHECKING)
                        || merchantLower.contains(SAVINGS)
                        || merchantLower.contains("account"))) {
            // Additional check: must NOT be a credit card or loan payment
            if (!descLower.contains("credit")
                    && !descLower.contains(CARD)
                    && !descLower.contains(LOAN)
                    && !merchantLower.contains("credit")
                    && !merchantLower.contains(CARD)
                    && !merchantLower.contains(LOAN)) {
                LOGGER.info(
                        "isAccountTransfer: ✅ Detected account transfer with CHK/checking/savings pattern");
                return true;
            }
        }

        LOGGER.debug(
                "isAccountTransfer: No account transfer detected for description='{}', merchant='{}'",
                description,
                merchantName);
        return false;
    }

    /**
     * Analyze transaction details/type for account type inference Looks for keywords like "debit",
     * "credit", "check", "ACH", "ATM", TRANSFER Uses arrays to allow modification of counts
     */
    private void analyzeTransactionForAccountType(
            final String transactionText,
            final int[] debitCount,
            final int[] creditCount,
            final int[] checkCount,
            final int[] achCount,
            final int[] atmCount,
            final int[] transferCount) {
        if (transactionText == null || transactionText.isBlank()) {
            return;
        }

        final String textLower = transactionText.toLowerCase(Locale.ROOT);

        // Count transaction type indicators
        if (textLower.contains("debit")
                || textLower.contains(" db ")
                || textLower.contains(" dr ")
                || textLower.startsWith("db ")
                || textLower.startsWith("dr ")
                || textLower.contains("debit card")
                || textLower.contains("debit purchase")) {
            debitCount[0]++;
            LOGGER.debug("Found debit indicator in transaction: {}", transactionText);
        }
        if (textLower.contains("credit")
                || textLower.contains(" cr ")
                || textLower.startsWith("cr ")
                || textLower.contains("credit memo")
                || textLower.contains("credit adjustment")) {
            // Exclude "credit card" from credit count (that's a different thing)
            if (!textLower.contains("credit card") && !textLower.contains(CREDITCARD)) {
                creditCount[0]++;
                LOGGER.debug("Found credit indicator in transaction: {}", transactionText);
            }
        }
        if (textLower.contains("check")
                || textLower.contains("chk")
                || textLower.contains("cheque")
                || textLower.contains("check #")
                || textLower.contains("check number")
                || textLower.contains("check payment")
                || textLower.contains("check deposit")) {
            checkCount[0]++;
            LOGGER.debug("Found check indicator in transaction: {}", transactionText);
        }
        if (textLower.contains("ach")
                || textLower.contains("automated clearing")
                || textLower.contains("direct deposit")
                || textLower.contains("directdeposit")
                || textLower.contains("ach credit")
                || textLower.contains("ach debit")
                || textLower.contains("ach transfer")) {
            achCount[0]++;
            LOGGER.debug("Found ACH indicator in transaction: {}", transactionText);
        }
        if (textLower.contains("atm")
                || textLower.contains("at m")
                || textLower.contains("cash withdrawal")
                || textLower.contains("cash withdrawal")
                || textLower.contains("atm withdrawal")
                || textLower.contains("atm deposit")
                || textLower.contains("atm fee")) {
            atmCount[0]++;
            LOGGER.debug("Found ATM indicator in transaction: {}", transactionText);
        }
        if (textLower.contains(TRANSFER)
                || textLower.contains("xfer")
                || textLower.contains("wire transfer")
                || textLower.contains("online transfer")
                || textLower.contains("bank transfer")
                || textLower.contains("account transfer")
                || textLower.contains("internal transfer")) {
            transferCount[0]++;
            LOGGER.debug("Found transfer indicator in transaction: {}", transactionText);
        }
    }

    /**
     * Infer account type from transaction patterns Uses counts of different transaction types to
     * determine account type
     */
    private String inferAccountTypeFromTransactionPatterns(
            final int debitCount,
            final int creditCount,
            final int checkCount,
            final int achCount,
            final int atmCount,
            final int transferCount) {
        // If we see checks, it's definitely a depository account (checking)
        if (checkCount > 0) {
            return DEPOSITORY;
        }

        // If we see many debits and credits with ACH/transfers, likely depository
        if ((debitCount > 0 || creditCount > 0) && (achCount > 0 || transferCount > 0)) {
            return DEPOSITORY;
        }

        // If we see ATM transactions, likely depository (checking or savings)
        if (atmCount > 0) {
            return DEPOSITORY;
        }

        // If we see ACH transactions, likely depository
        if (achCount > 0) {
            return DEPOSITORY;
        }

        // If we see transfers, likely depository
        if (transferCount > 0) {
            return DEPOSITORY;
        }

        return null;
    }

    /** Infer account subtype (checking vs savings) from transaction patterns */
    private String inferAccountSubtypeFromTransactionPatterns(
            final int debitCount,
            final int creditCount,
            final int checkCount,
            final int achCount,
            final int atmCount,
            final int transferCount) {
        // If we see checks, it's definitely checking
        if (checkCount > 0) {
            LOGGER.debug("Inferred checking account from check transactions");
            return CHECKING;
        }

        // If we see many debits (purchases, payments), likely checking
        // Savings accounts typically have fewer transactions and more deposits/withdrawals
        if (debitCount > creditCount && debitCount > 2) {
            LOGGER.debug("Inferred checking account from high debit count: {}", debitCount);
            return CHECKING;
        }

        // If we see ATM transactions, more likely checking (savings may have ATM but less common)
        if (atmCount > 0) {
            LOGGER.debug("Inferred checking account from ATM transactions");
            return CHECKING;
        }

        // If we see many ACH transactions (direct deposits, bill payments), likely checking
        if (achCount > 2) {
            LOGGER.debug("Inferred checking account from ACH transactions: {}", achCount);
            return CHECKING;
        }

        // If we see transfers, could be either, but more common in checking
        if (transferCount > 0 && debitCount > 0) {
            LOGGER.debug("Inferred checking account from transfer and debit patterns");
            return CHECKING;
        }

        // Default to savings if we can't determine (fewer transactions, more deposits)
        if (creditCount > debitCount && creditCount > 2) {
            LOGGER.debug(
                    "Inferred savings account from high credit/deposit count: {}", creditCount);
            return SAVINGS;
        }

        return null;
    }

    /**
     * Detects RSU (Restricted Stock Unit) vesting transactions Enhanced version with more patterns
     */
    private boolean isRSUTransaction(
            final String categoryString,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        String combinedText = "";
        if (description != null) {
            combinedText += description.toLowerCase(Locale.ROOT) + " ";
        }
        if (merchantName != null) {
            combinedText += merchantName.toLowerCase(Locale.ROOT) + " ";
        }
        if (categoryString != null) {
            combinedText += categoryString.toLowerCase(Locale.ROOT);
        }
        combinedText = combinedText.trim();

        // Enhanced RSU detection patterns
        final String[] rsuPatterns = {
            "rsu", "restricted stock unit", "restricted stock", "stock unit vest",
            "stock vest", "rsu vest", "rsu vesting", "restricted stock vest",
            "equity vest", "equity vesting", "stock award", "stock award vest",
            "employee stock vest", "espp", "stock compensation", "equity compensation"
        };

        // Check if description/category contains RSU patterns
        for (final String pattern : rsuPatterns) {
            if (combinedText.contains(pattern)) {
                // Additional validation: RSU vests are typically positive income
                if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Sophisticated Merchant Name-Based Category Detection Uses comprehensive global merchant
     * patterns with fuzzy matching This is the key enhancement - merchant names are more reliable
     * than category strings
     *
     * @param merchantName Normalized merchant name
     * @param description Transaction description (for additional context)
     * @return Detected category or null if no match
     */
    private String detectCategoryFromMerchantName(
            final String merchantName, final String description) {
        if (merchantName == null || merchantName.isBlank()) {
            return null;
        }

        // CRITICAL: Check for credit card payments FIRST
        if (isCreditCardPayment(description, merchantName, null)) {
            LOGGER.info(
                    "detectCategoryFromMerchantName: Detected credit card payment (safety check) → 'payment'");
            return "payment";
        }

        // Normalize merchant name for matching
        final String normalized =
                StringUtils.normalizeMerchantName(merchantName).toLowerCase(Locale.ROOT);
        final String descLower = description != null ? description.toLowerCase(Locale.ROOT) : "";
        final String merchantLower = merchantName.toLowerCase(Locale.ROOT);

        LOGGER.debug(
                "detectCategoryFromMerchantName: Analyzing merchant='{}', normalized='{}'",
                merchantName,
                normalized);

        // CRITICAL: Subscription merchants (WSJ, NYTimes, etc.) - subscriptions, NOT education
        // Must come BEFORE education checks to avoid false positives
        final String[] subscriptionMerchants = {
            "wsj",
            "wall street journal",
            "the wall street journal",
            "nytimes",
            "new york times",
            "financial times",
            "ft.com",
            "the financial times",
            "economist",
            "the economist",
            "bloomberg",
            "bloomberg news"
        };
        for (final String merchant : subscriptionMerchants) {
            if (merchantLower.contains(merchant)
                    || normalized.contains(merchant)
                    || descLower.contains(merchant)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromMerchantName: Detected subscription merchant '{}' → 'subscriptions'",
                        merchant);
                return SUBSCRIPTIONS;
            }
        }

        // CRITICAL: Toll patterns (Eractoll, etc.) - transportation
        // Must come BEFORE education checks to prevent ERACTOLL from being caught by education
        if (merchantLower.contains(ERACTOLL)
                || merchantLower.contains("era toll")
                || normalized.contains(ERACTOLL)
                || normalized.contains("eratoll")
                || descLower.contains(ERACTOLL)
                || descLower.contains("era toll")
                || descLower.contains("toll payment")
                || descLower.contains("toll charge")
                || descLower.contains("toll fee")
                || descLower.contains("road toll")
                || descLower.contains("bridge toll")
                || descLower.contains("tunnel toll")
                || descLower.contains("highway toll")
                || descLower.contains("expressway toll")) {
            LOGGER.debug("🏷️ detectCategoryFromMerchantName: Detected toll → 'transportation'");
            return TRANSPORTATION;
        }

        // Use strategy manager to detect category
        final String category =
                categoryDetectionManager.detectCategory(normalized, descLower, merchantName);
        if (category != null) {
            return category;
        }

        // Fallback to OTHER if no category detected
        return OTHER;
    }

    /**
     * Description-Based Category Detection Uses description keywords when merchant name detection
     * fails
     *
     * @param description Transaction description
     * @param merchantName Merchant name (for context)
     * @param amount Transaction amount (for context)
     * @return Detected category or null if no match
     */
    private String detectCategoryFromDescription(
            final String description, final String merchantName, BigDecimal amount) {
        if (description == null || description.isBlank()) {
            return null;
        }

        final String descLower = description.toLowerCase(Locale.ROOT);
        LOGGER.debug("detectCategoryFromDescription: Analyzing description='{}'", description);

        // Normalize description for better matching (used for merchant name detection)
        final String normalizedDesc =
                StringUtils.normalizeMerchantName(description).toLowerCase(Locale.ROOT);

        // CRITICAL FIX: Check for travel-related services FIRST (before utilities) to ensure proper
        // categorization
        // CRITICAL FIX: Airport lounges (Centurion Lounge, Priority Pass, Admirals Club, etc.) -
        // travel, NOT utilities
        // Check for Centurion Lounge first (most specific pattern)
        String result;
        result =
                detectCategoryStep01Travel(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep02Rideshare(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep03GasStation(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep04PosDining(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep05Parking(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep06SportsFitnessClubs(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep07BookStores(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep08Utilities(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep09SpecificGroceries(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep10SpecificRestaurants(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep11PetTech(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep12GymBeautySports(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep13MoreTransportAndQfc(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep14SchoolPaymentsAndTypes(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep15SubscriptionsAndCharity(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep16PetClinicHealth(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep17TransportTicketsTolls(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep18MoreRestaurants(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep19ExamsAndRegional(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }
        result =
                detectCategoryStep20Entertainment(
                        descLower, normalizedDesc, description, merchantName, amount);
        if (result != null) {
            return result;
        }

        LOGGER.debug(
                "detectCategoryFromDescription: No match found for description '{}'", description);
        return null;
    }

    private String detectCategoryStep01Travel(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        if (descLower.contains("centurion lounge")
                || descLower.contains("centurionlounge")
                || descLower.contains("axp centurion")
                || descLower.contains("axpcenturion")
                || (descLower.contains("axp") && descLower.contains("centurion"))
                || normalizedDesc.contains("centurion lounge")
                || normalizedDesc.contains("centurionlounge")
                || normalizedDesc.contains("axp centurion")
                || normalizedDesc.contains("axpcenturion")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Centurion Lounge (AXP) → 'travel'");
            return TRAVEL;
        }

        // Other airport lounges
        final String[] travelLounges = {
            "priority pass", "prioritypass",
            "admirals club", "admiralsclub",
            "delta sky club", "deltaskyclub",
            "united club", "unitedclub",
            "american express lounge", "amex lounge",
            "plaza premium lounge", "plazapremiumlounge",
            "airport lounge", "airportlounge",
            "airport loung", "lounge"
        };
        for (final String lounge : travelLounges) {
            if (descLower.contains(lounge) || normalizedDesc.contains(lounge)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected airport lounge '{}' → 'travel'",
                        lounge);
                return TRAVEL;
            }
        }

        // Airlines (Delta, United, American Airlines, Southwest, JetBlue, Alaska, etc.)
        final String[] airlines = {
            "delta",
            "united",
            "american airlines",
            "americanairlines",
            "southwest",
            "jetblue",
            "alaska",
            "airline",
            "airlines",
            "spirit",
            "frontier",
            "allegiant",
            "hawaiian"
        };
        for (final String airline : airlines) {
            if (descLower.contains(airline)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected airline '{}' → 'travel'",
                        airline);
                return TRAVEL;
            }
        }

        // Hotels (Marriott, Hilton, Hyatt, Holiday Inn, Airbnb, etc.)
        final String[] hotels = {
            "hotel",
            "marriott",
            "hilton",
            "hyatt",
            "holiday inn",
            "holidayinn",
            "airbnb",
            "booking.com",
            "expedia",
            "travelocity",
            "priceline",
            "motel",
            "resort",
            "inn"
        };
        for (final String hotel : hotels) {
            if (descLower.contains(hotel)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected hotel/accommodation '{}' → 'travel'",
                        hotel);
                return TRAVEL;
            }
        }
        return null;
    }

    private String detectCategoryStep02Rideshare(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Ride-sharing services (Lyft, Uber) - transportation, NOT subscriptions
        // Exception: Lyft Pink subscription, Uber One subscription are subscriptions
        if (descLower.contains("lyft")) {
            // Check if it's Lyft Pink subscription
            if (descLower.contains("lyft pink")
                    || descLower.contains("lyftpink")
                    || descLower.contains("pink subscription")
                    || descLower.contains("pink membership")) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected Lyft Pink subscription → 'subscriptions'");
                return SUBSCRIPTIONS;
            }
            // Otherwise, it's a ride - transportation
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Lyft ride → 'transportation'");
            return TRANSPORTATION;
        }

        if (descLower.contains("uber")) {
            // Check if it's Uber One subscription
            if (descLower.contains("uber one")
                    || descLower.contains("uberone")
                    || descLower.contains("uber one subscription")
                    || descLower.contains("uberone subscription")
                    || descLower.contains("uber one membership")
                    || descLower.contains("uberone membership")) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected Uber One subscription → 'subscriptions'");
                return SUBSCRIPTIONS;
            }
            // Check if it's Uber Eats (dining, not transportation)
            if (descLower.contains("uber eats")
                    || descLower.contains("ubereats")
                    || descLower.contains("uber eat")) {
                LOGGER.debug("🏷️ detectCategoryFromDescription: Detected Uber Eats → 'dining'");
                return DINING;
            }
            // Otherwise, it's a ride - transportation
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Uber ride → 'transportation'");
            return TRANSPORTATION;
        }

        // Other ride-sharing services (taxi, cab, rideshare, etc.)
        final String[] rideshareServices = {
            "taxi",
            "cab",
            "rideshare",
            "ride share",
            "car service",
            "didi",
            "grab",
            "ola",
            "careem",
            "gett",
            "bolt"
        };
        for (final String service : rideshareServices) {
            if (descLower.contains(service)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected ride-sharing service '{}' → 'transportation'",
                        service);
                return TRANSPORTATION;
            }
        }
        return null;
    }

    private String detectCategoryStep03GasStation(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Gas stations (Exxon, Shell, Chevron, BP, Mobil, Buc-ee's, etc.) -
        // transportation, NOT subscriptions
        // Well-known gas station brands - always transportation
        // CRITICAL: Buc-ee's is a gas station/convenience store chain, not shopping
        final String[] knownGasStations = {
            "exxon",
            "shell",
            "chevron",
            "mobil",
            "esso",
            "speedway",
            "valero",
            "citgo",
            "phillips 66",
            "phillips66",
            "arco",
            "marathon",
            "sunoco",
            "conoco",
            "murphy usa",
            "murphyusa",
            "love's",
            "loves",
            "pilot",
            "flying j",
            "flyingj",
            "ta",
            "travel centers",
            "truck stop",
            "buc-ee",
            "bucee",
            "buc-ees",
            "bucees",
            "buc-ee's"
        };
        for (final String station : knownGasStations) {
            if (descLower.contains(station)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected gas station '{}' → 'transportation'",
                        station);
                return TRANSPORTATION;
            }
        }

        // BP and "76" need special handling to avoid false matches
        // BP - only if it's clearly a gas station (not British Petroleum payroll, etc.)
        if (descLower.contains("bp")
                && (descLower.contains("gas")
                        || descLower.contains(FUEL)
                        || descLower.contains("station")
                        || descLower.contains("bp ")
                        || descLower.contains(" bp ")
                        || descLower.contains(".bp"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected BP gas station → 'transportation'");
            return TRANSPORTATION;
        }

        // 76 gas station - must be specific to avoid matching "CHECK 176", etc.
        if ((descLower.contains("76 station")
                        || descLower.contains("76 gas")
                        || descLower.contains("union 76")
                        || descLower.contains("union76"))
                && (descLower.contains("gas")
                        || descLower.contains(FUEL)
                        || descLower.contains("station"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected 76 gas station → 'transportation'");
            return TRANSPORTATION;
        }

        // Gas station patterns (gas station, gas station, fuel, etc.)
        if (descLower.contains("gas station")
                || descLower.contains("gasstation")
                || descLower.contains("gas ")
                || descLower.contains(FUEL)
                || descLower.contains("petrol")
                || descLower.contains("diesel")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected gas station pattern → 'transportation'");
            return TRANSPORTATION;
        }

        // CRITICAL FIX: Check description for utility companies and patterns (merchant name might
        // be in description)
        // NOTE: normalizedDesc is already defined at the top of the function
        return null;
    }

    private String detectCategoryStep04PosDining(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: TST* pattern (Toast POS system) - dining, NOT utilities
        // TST* is a restaurant POS terminal code and should be detected BEFORE utilities
        if (descLower.contains("tst*")
                || normalizedDesc.contains("tst*")
                || (description != null
                        && (description.toUpperCase(Locale.ROOT).startsWith("TST*")
                                || description.toUpperCase(Locale.ROOT).contains("TST*")))) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected TST* pattern → 'dining'");
            return DINING;
        }

        // CRITICAL FIX: SQ* (Square POS system) - dining, NOT utilities
        // SQ* can appear with or without space: "SQ*" or "SQ *"
        // Square is a point-of-sale system used by restaurants and retail stores
        if (descLower.contains("sq*")
                || descLower.contains("sq *")
                || descLower.contains("sq  *")
                || normalizedDesc.contains("sq*")
                || normalizedDesc.contains("sq *")
                || normalizedDesc.contains("sq  *")
                || (description != null
                        && (description.toUpperCase(Locale.ROOT).contains("SQ*")
                                || description.toUpperCase(Locale.ROOT).contains("SQ *")
                                || description.toUpperCase(Locale.ROOT).startsWith("SQ")))) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected SQ* (Square POS) → 'dining'");
            return DINING;
        }

        // Other POS system patterns (RBL* = restaurant POS, etc.) - dining
        if (descLower.contains("rbl*")
                || descLower.contains("rbl *")
                || normalizedDesc.contains("rbl*")
                || normalizedDesc.contains("rbl *")
                || (description != null
                        && (description.toUpperCase(Locale.ROOT).contains("RBL*")
                                || description.toUpperCase(Locale.ROOT).contains("RBL *")))) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected RBL* POS pattern → 'dining'");
            return DINING;
        }

        // CRITICAL FIX: TPD (Top Pot Donuts) - dining, NOT utilities
        // TPD must be detected BEFORE utilities because it might be misclassified
        if (descLower.contains("tpd")
                || normalizedDesc.contains("tpd")
                || (description != null
                        && (description.toUpperCase(Locale.ROOT).startsWith("TPD")
                                || description.toUpperCase(Locale.ROOT).contains("TPD")))
                || descLower.contains("top pot donuts")
                || descLower.contains("toppotdonuts")
                || descLower.contains("top pot")
                || descLower.contains("toppot")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected TPD (Top Pot Donuts) → 'dining'");
            return DINING;
        }

        // CRITICAL FIX: Burger and Kabob Hut - dining, NOT utilities
        // This restaurant must be detected BEFORE utilities because it might be misclassified
        if (descLower.contains("burger and kabob hut")
                || descLower.contains("burgerandkabobhut")
                || descLower.contains("kabob hut")
                || descLower.contains("kabobhut")
                || normalizedDesc.contains("burger and kabob hut")
                || normalizedDesc.contains("burgerandkabobhut")
                || normalizedDesc.contains("kabob hut")
                || normalizedDesc.contains("kabobhut")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Burger and Kabob Hut → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectCategoryStep05Parking(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Parking payment services (PAY BY PHONE, ParkMobile, etc.) - transportation,
        // NOT utilities
        // These must be detected BEFORE utilities because "phone" keyword might match utilities
        if (descLower.contains("pay by phone")
                || descLower.contains("paybyphone")
                || descLower.contains("uw pay by phone")
                || descLower.contains("uwpay by phone")
                || descLower.contains("uw paybyphone")
                || descLower.contains("uwpaybyphone")
                || (descLower.contains("uw") && descLower.contains("pay by phone"))
                || normalizedDesc.contains("pay by phone")
                || normalizedDesc.contains("paybyphone")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected parking payment service → 'transportation'");
            return TRANSPORTATION;
        }

        // Other parking payment services (ParkMobile, Impark, Metropolis parking, etc.)
        final String[] parkingServices = {
            "parkmobile",
            "park mobile",
            "impark",
            "parking",
            "parking meter",
            "parkingmeter",
            "garage",
            "metropolis parking",
            "metropolisparking"
        };
        for (final String service : parkingServices) {
            if (descLower.contains(service) || normalizedDesc.contains(service)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected parking service '{}' → 'transportation'",
                        service);
                return TRANSPORTATION;
            }
        }
        return null;
    }

    private String detectCategoryStep06SportsFitnessClubs(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Sports clubs and fitness centers (badminton club, gym, fitness center,
        // etc.) - health, NOT utilities
        // These must be detected BEFORE utilities because "club" might be misclassified
        final String[] sportsAndFitness = {
            "badminton club",
            "badmintonclub",
            "badminton",
            "seattle badminton club",
            "seattlebadmintonclub",
            "fitness club",
            "fitnessclub",
            "health club",
            "healthclub",
            "athletic club",
            "athleticclub",
            "sports club",
            "sportsclub",
            "gym",
            "fitness center",
            "fitnesscenter",
            "workout",
            "personal trainer",
            "orangetheory",
            "orange theory",
            "crossfit"
        };
        for (final String fitness : sportsAndFitness) {
            if (descLower.contains(fitness) || normalizedDesc.contains(fitness)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected sports/fitness '{}' → 'health'",
                        fitness);
                return HEALTH;
            }
        }
        return null;
    }

    private String detectCategoryStep07BookStores(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: University book stores and book stores - education, NOT utilities
        // These must be detected BEFORE utilities because STORE might be misclassified
        if (descLower.contains("university book store")
                || descLower.contains("universitybookstore")
                || descLower.contains("university book")
                || descLower.contains("universitybook")
                || normalizedDesc.contains("university book store")
                || normalizedDesc.contains("universitybookstore")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected University Book Store → 'education'");
            return EDUCATION;
        }

        // Other book stores (bookstore, book store) - education
        if ((descLower.contains("bookstore")
                        || descLower.contains("book store")
                        || normalizedDesc.contains("bookstore")
                        || normalizedDesc.contains("book store"))
                && !descLower.contains("costco")
                && !descLower.contains("walmart")
                && !descLower.contains("target")
                && !descLower.contains("grocery")) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected book store → 'education'");
            return EDUCATION;
        }
        return null;
    }

    private String detectCategoryStep08Utilities(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        final String[] utilityCompanies = {
            "puget sound energy",
            "pse",
            "pacific gas",
            "pg&e",
            "pge",
            "southern california edison",
            "sce",
            "san diego gas",
            "sdge",
            "edison",
            "con edison",
            "coned",
            "duke energy",
            "dukeenergy",
            "dominion energy",
            "dominionenergy",
            "exelon",
            "first energy",
            "firstenergy",
            "american electric",
            "aep",
            "southern company",
            "southerncompany"
        };
        for (final String company : utilityCompanies) {
            if (normalizedDesc.contains(company) || descLower.contains(company)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected utility company '{}' → 'utilities'",
                        company);
                return UTILITIES;
            }
        }

        // Utility patterns in description (ENER, ENERGY, BILLPAY, etc.)
        if (descLower.contains("ener ")
                || descLower.contains("ener billpay")
                || descLower.contains("energy")
                || descLower.contains("electric")
                || descLower.contains("electricity")
                || descLower.contains("utility")
                || descLower.contains(UTILITIES)
                || descLower.contains("gas company")
                || descLower.contains("water company")
                || descLower.contains("power company")) {
            // If it's a bill payment with energy/utility keywords, it's utilities
            if (descLower.contains("billpay") || descLower.contains("bill pay")) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected utility bill payment → 'utilities'");
                return UTILITIES;
            }
        }
        return null;
    }

    private String detectCategoryStep09SpecificGroceries(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Check description for specific grocery store names (merchant name might be
        // in description)
        // This handles cases where CSV doesn't have a separate merchant name column
        if (normalizedDesc.contains("safeway")
                || normalizedDesc.contains("safeway.com")
                || normalizedDesc.startsWith("safeway")
                || normalizedDesc.endsWith("safeway")) {
            LOGGER.debug(
                    "detectCategoryFromDescription: Detected Safeway in description → 'groceries'");
            return GROCERIES;
        }

        // CRITICAL FIX: Check for Fred Meyer (Kroger-owned grocery store)
        if (descLower.contains("fred meyer")
                || descLower.contains("fredmeyer")
                || descLower.contains("fred-meyer")) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected Fred Meyer → 'groceries'");
            return GROCERIES;
        }

        // CRITICAL FIX: Check for PCC (PCC Natural Markets / PCC Community Markets) in description
        // PCC often appears in description field when merchant name is null
        if (normalizedDesc.contains("pcc")
                || descLower.contains("pcc")
                || normalizedDesc.contains("pcc natural markets")
                || normalizedDesc.contains("pcc community markets")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected PCC in description → 'groceries'");
            return GROCERIES;
        }

        // CRITICAL FIX: Check for Subway in description (common when merchantName is null)
        if (normalizedDesc.contains("subway") || descLower.contains("subway")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Subway in description → 'dining'");
            return DINING;
        }

        // CRITICAL FIX: Check for AMC and other movie theaters in description
        // AMC often appears in description field when merchant name is null
        if (normalizedDesc.contains("amc") || descLower.contains("amc")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected AMC in description → 'entertainment'");
            return "entertainment";
        }

        // Grocery keywords in description
        if (descLower.contains("grocery")
                || descLower.contains("supermarket")
                || descLower.contains("food market")
                || descLower.contains("produce")) {
            return GROCERIES;
        }

        // Sunny Honey Company in description
        if (descLower.contains("sunny honey") || descLower.contains("sunnyhoney")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Sunny Honey Company → 'groceries'");
            return GROCERIES;
        }
        return null;
    }

    private String detectCategoryStep10SpecificRestaurants(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // Specific Restaurants in Description - Must come before general restaurant patterns
        final String[] specificRestaurantsInDesc = {
            "daeho",
            "tutta bella",
            "tuttabella",
            "simply indian restaur",
            "simply indian restaurant",
            "simplyindian restaur",
            "simplyindian restaurant",
            "skills rainbow room",
            "skillsrainbow room",
            "kyurmaen",
            "kyurmaen ramen",
            "deep dive",
            "deepdive",
            "messina",
            "supreme dumplings",
            "supremedumplings",
            "cucina venti",
            "cucinaventi",
            "desi dhaba",
            "desidhaba",
            "medocinofarms",
            "medocino farms",
            "laughing monk brewing",
            "laughingmonk brewing",
            "laughing monk",
            "laughingmonk"
        };
        for (final String restaurant : specificRestaurantsInDesc) {
            if (descLower.contains(restaurant) || normalizedDesc.contains(restaurant)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected restaurant '{}' → 'dining'",
                        restaurant);
                return DINING;
            }
        }

        // NOTE: TST* pattern detection has been moved earlier (before utilities) to ensure proper
        // categorization

        // Food-related keywords that indicate restaurants (dumplings, burger, fast food, grill,
        // thai, dhaba, brewing)
        if (descLower.contains("dumplings")
                || descLower.contains("dumpling")
                || descLower.contains("burger")
                || descLower.contains("burgers")
                || descLower.contains("fast food")
                || descLower.contains("fastfood")
                || descLower.contains("grill")
                || descLower.contains("grilled")
                || descLower.contains("thai")
                || descLower.contains("dhaba")
                || descLower.contains("brewing")
                || descLower.contains("brewery")
                || descLower.contains("brew pub")
                || descLower.contains("brewpub")
                || normalizedDesc.contains("dumplings")
                || normalizedDesc.contains("dumpling")
                || normalizedDesc.contains("burger")
                || normalizedDesc.contains("burgers")
                || normalizedDesc.contains("fast food")
                || normalizedDesc.contains("fastfood")
                || normalizedDesc.contains("grill")
                || normalizedDesc.contains("grilled")
                || normalizedDesc.contains("thai")
                || normalizedDesc.contains("dhaba")
                || normalizedDesc.contains("brewing")
                || normalizedDesc.contains("brewery")
                || normalizedDesc.contains("brew pub")
                || normalizedDesc.contains("brewpub")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected food/restaurant keyword → 'dining'");
            return DINING;
        }

        // Dining keywords (including bakeries, Tea Lab, Chai, Boba, Ezell's, Le Panier, Mochinut,
        // honet.bellevue.com)
        // Includes "restaur" as keyword for restaurant
        if (descLower.contains("restaurant")
                || descLower.contains("rest ")
                || descLower.contains("restaur")
                || descLower.contains("restaur ")
                || descLower.contains(DINING)
                || descLower.contains("fast food")
                || descLower.contains("fastfood")
                || descLower.contains("cafe")
                || descLower.contains("café")
                || descLower.contains("caffe")
                || descLower.contains("cafeteria")
                || descLower.contains("tiffin")
                || descLower.contains("bakery")
                || descLower.contains("baker")
                || descLower.contains("hoffman")
                || descLower.contains("hoffman's")
                || descLower.contains("tea lab")
                || descLower.contains("tealab")
                || descLower.contains("chai")
                || descLower.contains("boba")
                || descLower.contains("ezell")
                || descLower.contains("ezells")
                || descLower.contains("le panier")
                || descLower.contains("lepanier")
                || descLower.contains("mochinut")
                || descLower.contains("honet.bellevue")
                || descLower.contains("honet")
                || normalizedDesc.contains("restaurant")
                || normalizedDesc.contains("restaur")) {
            if (descLower.contains("hoffman") || descLower.contains("hoffman's")) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected Hoffmans bakery → 'dining'");
            }
            return DINING;
        }
        return null;
    }

    private String detectCategoryStep11PetTech(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // Pet keywords (including SP Farmers Fetch Bones)
        if (descLower.contains("pet")
                || descLower.contains("veterinary")
                || descLower.contains("vet ")
                || descLower.contains("animal")
                || descLower.contains("sp farmers")
                || descLower.contains("fetch bones")
                || descLower.contains("fetchbones")) {
            if (descLower.contains("sp farmers") || descLower.contains("fetch bones")) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected SP Farmers Fetch Bones → 'pet'");
            }
            return "pet";
        }

        // CRITICAL FIX: Check for Cursor AI and other tech companies in description
        if (normalizedDesc.contains("cursor")
                || descLower.contains("cursor")
                || normalizedDesc.contains("ai powered")
                || descLower.contains("ai powered")
                || normalizedDesc.contains("ide")
                || descLower.contains("ide")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Cursor AI/tech in description → 'tech'");
            return "tech";
        }

        // Tech keywords
        if (descLower.contains("software")
                || descLower.contains("saas")
                || descLower.contains("subscription")
                || descLower.contains("api")
                || descLower.contains("developer")
                || descLower.contains("tech")
                || descLower.contains("integrated development")) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected tech keywords → 'tech'");
            return "tech";
        }
        return null;
    }

    private String detectCategoryStep12GymBeautySports(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Check for gyms/fitness in description (common when merchantName is null)
        // Moved to health category
        final String[] gymKeywords = {
            "proclub",
            "pro club",
            "24 hour fitness",
            "24hour fitness",
            "24hr fitness",
            "24-hour fitness",
            "gold's gym",
            "golds gym",
            "planet fitness",
            "equinox",
            "lifetime fitness",
            "ymca",
            "la fitness",
            "crunch fitness",
            "anytime fitness",
            "orange theory",
            "crossfit",
            "fitness",
            "gym",
            "health club",
            "athletic club",
            "fitness center",
            "workout",
            "personal trainer",
            "seattle badminton club",
            "badminton"
        };
        for (final String gym : gymKeywords) {
            if (descLower.contains(gym) || normalizedDesc.contains(gym)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected gym/fitness '{}' → 'health'",
                        gym);
                return HEALTH;
            }
        }

        // Beauty salons, hair cuts in description
        final String[] beautyKeywords = {
            "beauty salon",
            "beautysalon",
            "beauty parlor",
            "beautyparlor",
            "hair salon",
            "hairsalon",
            "hair cut",
            "haircut",
            "hair cuts",
            "haircuts",
            "hair color",
            "haircolor",
            "body waxing",
            "bodywaxing",
            "waxing",
            "makeup",
            "beauty studio",
            "beautystudio",
            "salon",
            "supercuts",
            "super cuts",
            "great clips",
            "greatclips",
            "lucky hair salon",
            "lucky hair salin",
            "luckyhair",
            "luckyhairsalin"
        };
        for (final String beauty : beautyKeywords) {
            if (descLower.contains(beauty) || normalizedDesc.contains(beauty)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected beauty service '{}' → 'health'",
                        beauty);
                return HEALTH;
            }
        }

        // Sports activities in description (golf, tennis, soccer, ski resorts, etc.)
        // Note: Mini Mountain is ski-gear/equipment, not a sports activity - handled separately
        if (descLower.contains("golf")
                || descLower.contains("tennis")
                || descLower.contains("soccer")
                || descLower.contains("football")
                || descLower.contains("basketball")
                || descLower.contains("baseball")
                || descLower.contains("swimming")
                || descLower.contains("yoga")
                || descLower.contains("pilates")
                || descLower.contains("martial arts")
                || descLower.contains("ski resort")
                || descLower.contains("summit at snoqualmie")) {
            // Check for "ski" but exclude "mini mountain" and equipment patterns
            if (descLower.contains("ski")
                    && !descLower.contains("mini mountain")
                    && !descLower.contains("ski gear")
                    && !descLower.contains("ski equipment")) {
                LOGGER.debug("🏷️ detectCategoryFromDescription: Detected ski activity → 'health'");
                return HEALTH;
            }
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected sports activity → 'health'");
            return HEALTH;
        }
        // Mini Mountain is ski-gear/equipment, not a sports activity
        if (descLower.contains("mini mountain")
                || normalizedDesc.contains("mini mountain")
                || descLower.contains("minimountain")
                || normalizedDesc.contains("minimountain")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Mini Mountain (ski-gear) → 'shopping'");
            return SHOPPING;
        }
        // Sports equipment patterns in description
        if (descLower.contains("ski gear")
                || descLower.contains("sports equipment")
                || descLower.contains("outdoor gear")
                || normalizedDesc.contains("ski gear")
                || normalizedDesc.contains("sports equipment")
                || normalizedDesc.contains("outdoor gear")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected sports equipment/gear → 'shopping'");
            return SHOPPING;
        }

        // Home improvement keywords (including Home Depot)
        if (descLower.contains("hardware")
                || descLower.contains("home improvement")
                || descLower.contains("homeimprovement")
                || descLower.contains("lumber")
                || descLower.contains("building supply")
                || descLower.contains("home depot")
                || descLower.contains("homedepot")
                || descLower.contains("lowes")
                || descLower.contains("menards")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected home improvement → 'home improvement'");
            return "home improvement";
        }
        return null;
    }

    private String detectCategoryStep13MoreTransportAndQfc(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // COSTCO GAS in description
        if (descLower.contains("costco gas")
                || descLower.contains("costcogas")
                || normalizedDesc.contains("costco gas")
                || normalizedDesc.contains("costcogas")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Costco Gas → 'transportation'");
            return TRANSPORTATION;
        }

        // Travel centers (gas station + grocery + food) - BUC-EE's
        if (descLower.contains("buc-ee")
                || descLower.contains("buc-ee's")
                || descLower.contains("bucee")
                || descLower.contains("bucees")
                || normalizedDesc.contains("buc-ee")
                || normalizedDesc.contains("bucee")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected BUC-EE's (travel center) → 'transportation'");
            return TRANSPORTATION;
        }

        // Gas keywords
        if (descLower.contains("gas station")
                || descLower.contains("gasstation")
                || descLower.contains(FUEL)
                || descLower.contains("petrol")
                || descLower.contains("kwik sak")
                || descLower.contains("kwiksak")
                || descLower.contains("kwik-sak")
                || normalizedDesc.contains("kwik sak")) {
            return TRANSPORTATION;
        }

        // NOTE: Parking payment services (PAY BY PHONE, ParkMobile, etc.) are handled earlier
        // (before utilities)
        // This section is kept for any additional parking patterns not covered earlier
        if (descLower.contains("parking")
                || descLower.contains("parking meter")
                || descLower.contains("parkingmeter")
                || descLower.contains("garage")) {
            // Skip if already handled above (pay by phone, parkmobile, etc.)
            if (!descLower.contains("pay by phone")
                    && !descLower.contains("paybyphone")
                    && !descLower.contains("parkmobile")
                    && !descLower.contains("park mobile")) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected parking → 'transportation'");
                return TRANSPORTATION;
            }
        }

        // QFC (grocery store) in description
        if (descLower.contains("qfc") || descLower.contains("quality food centers")) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected QFC → 'groceries'");
            return GROCERIES;
        }
        return null;
    }

    private String detectCategoryStep14SchoolPaymentsAndTypes(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // Education/School Payments
        // PayPAMS - online school payments for food (dining)
        if (descLower.contains("paypams") || descLower.contains("pay pams")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected PayPAMS (school food payment) → 'dining'");
            return DINING;
        }

        // School District payments - should be categorized as EDUCATION
        // Check for any school district (not just Bellevue)
        if (descLower.contains("school district")
                || descLower.contains("schooldistrict")
                || descLower.contains("school distri")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected School District → 'education'");
            return EDUCATION;
        }

        // CRITICAL FIX: Check for all school/education types FIRST (before charity)
        // Middle school, high school, elementary school, college, university, PhD, etc. → education
        for (final String school : SCHOOL_TYPES) {
            if (descLower.contains(school)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected school type '{}' → 'education'",
                        school);
                return EDUCATION;
            }
        }
        return null;
    }

    private String detectCategoryStep15SubscriptionsAndCharity(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL: Subscription merchants (WSJ, NYTimes, etc.) - subscriptions, NOT education
        // Must come BEFORE all education checks to avoid false positives
        final String[] subscriptionMerchants = {
            "wsj",
            "wall street journal",
            "the wall street journal",
            "nytimes",
            "new york times",
            "financial times",
            "ft.com",
            "the financial times",
            "economist",
            "the economist",
            "bloomberg",
            "bloomberg news"
        };
        for (final String merchant : subscriptionMerchants) {
            if (descLower.contains(merchant)
                    || normalizedDesc.contains(merchant)
                    || (merchantName != null
                            && merchantName.toLowerCase(Locale.ROOT).contains(merchant))) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected subscription merchant '{}' → 'subscriptions'",
                        merchant);
                return SUBSCRIPTIONS;
            }
        }

        // Educational media (books, newspapers, magazines, journals) → education
        // CRITICAL: Don't match "journal" alone - it matches subscription journals like "Wall
        // Street Journal"
        // Only match specific academic journal types or "journal" in educational context
        final String[] educationalMedia = {
            "newspaper",
            "magazine",
            "books",
            "bookstore",
            "book store",
            "textbook",
            "text book",
            "library",
            "academic journal",
            "research journal",
            "scientific journal",
            "scholarly journal",
            "peer-reviewed journal"
        };
        for (final String media : educationalMedia) {
            if (descLower.contains(media)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected educational media '{}' → 'education'",
                        media);
                return EDUCATION;
            }
        }

        // Check for "journal" only in educational context (not subscription journals like WSJ)
        // Must check AFTER subscription merchant check to avoid false positives
        if (descLower.contains("journal")
                && (descLower.contains("academic")
                        || descLower.contains("research")
                        || descLower.contains("scientific")
                        || descLower.contains("scholarly")
                        || descLower.contains("peer-reviewed")
                        || descLower.contains("education journal"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected educational journal → 'education'");
            return EDUCATION;
        }

        // Charity keywords (Go Fund Me, donations) - ONLY actual charity, NOT schools
        if (descLower.contains("go fund me")
                || descLower.contains("gofundme")
                || descLower.contains(CHARITY)
                || descLower.contains("donation")) {
            // Skip if it's actually a school (already handled above)
            boolean isSchool = false;
            for (final String school : SCHOOL_TYPES) {
                if (descLower.contains(school)) {
                    isSchool = true;
                    break;
                }
            }
            if (!isSchool) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected charity/donation → 'charity'");
                return CHARITY;
            }
        }
        return null;
    }

    private String detectCategoryStep16PetClinicHealth(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Pet care clinics (specialized) - pet, NOT healthcare
        // Pet care clinics should be detected BEFORE general healthcare/clinic detection
        if (descLower.contains("petcare clinic")
                || descLower.contains("petcareclinic")
                || descLower.contains("pet care clinic")
                || descLower.contains("petcare")
                || descLower.contains("pet care")
                || descLower.contains("pet clinic")
                || descLower.contains("petclinic")
                || descLower.contains("veterinary")
                || descLower.contains("vet ")
                || descLower.contains("vet.")
                || descLower.contains("animal hospital")
                || descLower.contains("animalhospital")
                || descLower.contains("animal clinic")
                || descLower.contains("animalclinic")
                || descLower.contains("pet hospital")
                || descLower.contains("pethospital")
                || descLower.contains("veterinarian")
                || descLower.contains("veterinary clinic")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected pet care clinic/service → 'pet'");
            return "pet";
        }

        // Pet-related services (petsmart, petco, chewy, etc.)
        final String[] petServices = {
            "petsmart",
            "petco",
            "pet supplies plus",
            "pet supplies",
            "petland",
            "chewy",
            "petmeds",
            "1800petmeds",
            "pet supermarket",
            "pet pharmacy",
            "petpharmacy"
        };
        for (final String service : petServices) {
            if (descLower.contains(service)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected pet service '{}' → 'pet'",
                        service);
                return "pet";
            }
        }

        // Health/Beauty keywords (nails, hair, spa, toes, skin, massages, cosmetic stores)
        // NOTE: Pet care clinics are handled above, so "clinic" here refers to general healthcare
        // clinics
        if (descLower.contains("nails")
                || descLower.contains("nail salon")
                || descLower.contains("nailsalon")
                || descLower.contains("manicure")
                || descLower.contains("pedicure")
                || descLower.contains("spa")
                || descLower.contains("massage")
                || descLower.contains("massages")
                || descLower.contains("toes")
                || descLower.contains("skin")
                || descLower.contains("skin care")
                || descLower.contains("skincare")
                || descLower.contains("stop 4 nails")
                || descLower.contains("stop4nails")
                || descLower.contains("stop four nails")
                || descLower.contains("stopfournails")
                || descLower.contains("hair salon")
                || descLower.contains("hairsalon")
                || descLower.contains("haircut")
                || descLower.contains("beauty salon")
                || descLower.contains("beautysalon")
                || descLower.contains("cosmetic store")
                || descLower.contains("cosmeticstore")
                || descLower.contains("cosmetics")
                || descLower.contains("makeup store")
                || descLower.contains("makeupstore")
                || descLower.contains("new york cosmetic")
                || descLower.contains("ny cosmetic")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected health/beauty service → 'health'");
            return HEALTH;
        }
        return null;
    }

    private String detectCategoryStep17TransportTicketsTolls(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // LUL Ticket Machine (London Underground) - transportation
        if (descLower.contains("lul ticket machine")
                || descLower.contains("lulticketmachine")
                || descLower.contains("lul ticket mach")
                || descLower.contains("london underground")
                || (descLower.contains("lul")
                        && (descLower.contains("ticket") || descLower.contains("machine")))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected LUL Ticket Machine → 'transportation'");
            return TRANSPORTATION;
        }

        // Ticket machine patterns (general)
        if (descLower.contains("ticket machine") || descLower.contains("ticketmachine")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected ticket machine → 'transportation'");
            return TRANSPORTATION;
        }

        // Amex Airlines Fee Reimbursement - transportation (even though it's a credit)
        if (descLower.contains("amex airlines fee reimbursement")
                || descLower.contains("amexairlinesfeereimbursement")
                || (descLower.contains(AMEX)
                        && descLower.contains("airlines")
                        && (descLower.contains(FEE) || descLower.contains("reimbursement")))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Amex Airlines Fee Reimbursement → 'transportation'");
            return TRANSPORTATION;
        }

        // CRITICAL: Financial education publications (Barrons, etc.) - education, NOT subscriptions
        // Must come AFTER subscription merchant check to avoid false positives
        if (descLower.contains("barrons")
                || descLower.contains("barron")
                || descLower.contains("barron's")
                || descLower.contains("dj*barrons")
                || descLower.contains("d j*barrons")
                || normalizedDesc.contains("barrons")
                || normalizedDesc.contains("barron")
                || (merchantName != null
                        && (merchantName.toLowerCase(Locale.ROOT).contains("barrons")
                                || merchantName.toLowerCase(Locale.ROOT).contains("barron")))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected Barrons financial education publication → 'education'");
            return EDUCATION;
        }

        // Toll patterns (Eractoll, etc.) - transportation
        // Must come BEFORE education check to prevent ERACTOLL from being caught by education
        if (descLower.contains(ERACTOLL)
                || descLower.contains("era toll")
                || normalizedDesc.contains(ERACTOLL)
                || normalizedDesc.contains("eratoll")
                || (merchantName != null
                        && (merchantName.toLowerCase(Locale.ROOT).contains(ERACTOLL)
                                || merchantName.toLowerCase(Locale.ROOT).contains("era toll")))
                || descLower.contains("toll payment")
                || descLower.contains("toll charge")
                || descLower.contains("toll fee")
                || descLower.contains("road toll")
                || descLower.contains("bridge toll")
                || descLower.contains("tunnel toll")
                || descLower.contains("highway toll")
                || descLower.contains("expressway toll")) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected toll → 'transportation'");
            return TRANSPORTATION;
        }

        // Car Service (Hona CTR, etc.)
        if (descLower.contains("hona ctr")
                || descLower.contains("honactr")
                || descLower.contains("hona car service")
                || descLower.contains("honacarservice")
                || descLower.contains("car service")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected car service → 'transportation'");
            return TRANSPORTATION;
        }
        return null;
    }

    private String detectCategoryStep18MoreRestaurants(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // Restaurant keywords (Burger and Kabob Hut, Insomnia Cookies, Banaras, Resy, Maxmillen)
        if (descLower.contains("burger and kabob hut")
                || descLower.contains("burgerandkabobhut")
                || descLower.contains("kabob hut")
                || descLower.contains("kabobhut")
                || descLower.contains("insomnia cookies")
                || descLower.contains("insomniacookies")
                || descLower.contains("insomnia cookie")
                || descLower.contains("banaras")
                || descLower.contains("banaras restaurant")
                || descLower.contains("banarasrestaurant")
                || descLower.contains("resy")
                || descLower.contains("maxmillen")
                || descLower.contains("maxmillian")
                || descLower.contains("maximilian")) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected restaurant → 'dining'");
            return DINING;
        }
        return null;
    }

    private String detectCategoryStep19ExamsAndRegional(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // CRITICAL FIX: Check for exam/testing keywords FIRST (AAMC, SAT, TOEFL, GRE, GMAT, LSAT,
        // MCAT, etc.)
        // These should be categorized as EDUCATION even if they're sometimes miscategorized as
        // "entertainment"
        // VUE (Pearson VUE) - testing center for professional exams
        // CRITICAL: Don't check for "act" here - it matches ERACTOLL. ACT is handled separately
        // below.
        if (descLower.contains("vue")
                && (descLower.contains("exam")
                        || descLower.contains("test")
                        || descLower.contains("aamc")
                        || descLower.contains("sat")
                        || descLower.contains("toefl")
                        || descLower.contains("gre")
                        || descLower.contains("gmat")
                        || descLower.contains("lsat")
                        || descLower.contains("mcat"))) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected VUE exam/testing → 'education'");
            return EDUCATION;
        }

        // CRITICAL: Toll patterns (Eractoll, etc.) - transportation
        // Must come BEFORE education/ACT check to prevent ERACTOLL from being caught by education
        if (descLower.contains(ERACTOLL)
                || descLower.contains("era toll")
                || normalizedDesc.contains(ERACTOLL)
                || normalizedDesc.contains("eratoll")
                || (merchantName != null
                        && (merchantName.toLowerCase(Locale.ROOT).contains(ERACTOLL)
                                || merchantName.toLowerCase(Locale.ROOT).contains("era toll")))
                || descLower.contains("toll payment")
                || descLower.contains("toll charge")
                || descLower.contains("toll fee")
                || descLower.contains("road toll")
                || descLower.contains("bridge toll")
                || descLower.contains("tunnel toll")
                || descLower.contains("highway toll")
                || descLower.contains("expressway toll")) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected toll → 'transportation'");
            return TRANSPORTATION;
        }

        // Exam/testing keywords (AAMC, SAT, TOEFL, GRE, GMAT, LSAT, MCAT, ACT, AP, IB, etc.)
        // CRITICAL: "act" must be checked as whole word to avoid matching ERACTOLL
        final String[] examKeywords = {
            "aamc",
            "sat",
            "toefl",
            "gre",
            "gmat",
            "lsat",
            "mcat",
            "ap exam",
            "ib exam",
            "clep",
            "praxis",
            "bar exam",
            "nclex",
            "usmle",
            "comlex",
            "test registration",
            "test fee",
            "test center",
            "pearson vue",
            "ets",
            "prometric"
        };
        for (final String exam : examKeywords) {
            if (descLower.contains(exam)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected exam/testing keyword '{}' → 'education'",
                        exam);
                return EDUCATION;
            }
        }
        // Check for "act" as whole word (ACT exam) - must come AFTER toll check to avoid matching
        // ERACTOLL
        // Check for "act exam", "act test", or "act" at word boundaries, but exclude ERACTOLL
        if (!descLower.contains(ERACTOLL)
                && !descLower.contains("era toll")
                && (descLower.contains("act exam")
                        || descLower.contains("act test")
                        || descLower.matches(".*\\bact\\b.*"))) {
            LOGGER.debug("🏷️ detectCategoryFromDescription: Detected ACT exam → 'education'");
            return EDUCATION;
        }

        // Regional school/college names - Education
        // CRITICAL: Check for regional terms BEFORE generic education keywords to ensure they're
        // always detected
        // Indian: Gurukul, Vidyalaya, Shiksha, Pathshala
        // Spanish: Escuela, Colegio, Universidad
        // French: École, Collège, Université
        // German: Schule, Universität
        // Arabic: Madrasa, Kuttab
        final String[] regionalSchoolTerms = {
            "gurukul",
            "vidyalaya",
            "shiksha",
            "pathshala",
            "escuela",
            "colegio",
            "universidad",
            "école",
            "collège",
            "université",
            "schule",
            "universität",
            "madrasa",
            "kuttab",
            "madrassa"
        };
        for (final String term : regionalSchoolTerms) {
            if (descLower.contains(term)) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected regional school term '{}' → 'education'",
                        term);
                return EDUCATION;
            }
        }

        // SP ANKI REMOTE - Education (Anki remote learning/spaced repetition)
        // CRITICAL: Check for "anki" before generic education keywords
        if (descLower.contains("sp anki remote")
                || descLower.contains("spankiremote")
                || descLower.contains("anki remote")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected SP ANKI REMOTE → 'education'");
            return EDUCATION;
        }

        // Education keywords (school, books, reading, newspapers, magazines, journals, etc.) -
        // categorized as EDUCATION
        // NOTE: School types (middle school, high school, etc.) and educational media are handled
        // separately above
        // NOTE: "gurukul" is handled separately above to ensure it's always detected
        // NOTE: Subscription merchants (WSJ, Barrons, etc.) are checked BEFORE this to prevent
        // false positives
        if (descLower.contains("school")
                || descLower.contains("university")
                || descLower.contains("college")
                || descLower.contains("tuition")
                || descLower.contains("books")
                || descLower.contains("bookstore")
                || descLower.contains("book store")
                || descLower.contains("reading")
                || descLower.contains("textbook")
                || descLower.contains("text book")
                || descLower.contains(EDUCATION)
                || descLower.contains("educational")
                || descLower.contains("course")
                || descLower.contains("class")
                || descLower.contains("lesson")
                || descLower.contains("training")
                || descLower.contains("anki")
                || descLower.contains("newspaper")
                || descLower.contains("magazine")
                ||
                // Note: "journal" is handled separately above to avoid matching subscription
                // journals
                descLower.contains("phd")
                || descLower.contains("ph.d")
                || descLower.contains("ph.d.")
                || descLower.contains("doctorate")
                || descLower.contains("library")) {
            // Skip if it's a school payment (PayPAMS) - those are handled separately
            if (!descLower.contains("paypams")) {
                LOGGER.debug(
                        "🏷️ detectCategoryFromDescription: Detected education item → 'education'");
                return EDUCATION;
            }
        }
        return null;
    }

    private String detectCategoryStep20Entertainment(
            final String descLower,
            final String normalizedDesc,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        // Entertainment keywords (State Fair, Disney, Universal Studio, Sea World, camping)
        // CRITICAL FIX: Check for movie theaters and entertainment venues in description
        // Use word boundaries for short names like "amc" to prevent false matches
        if (descLower.matches(".*\\bamc\\b.*")
                || descLower.contains("cinemark")
                || descLower.contains("regal")
                || descLower.contains("carmike")
                || descLower.contains("marcus")
                || descLower.contains("harkins")
                || descLower.contains("alamo drafthouse")
                || descLower.contains("movie theater")
                || descLower.contains("movietheater")
                || descLower.contains("cinema")
                || descLower.contains("theater")
                || descLower.contains("theatre")
                || descLower.contains("imax")
                || descLower.contains("escape room")
                || descLower.contains("escaperoom")
                || descLower.contains("conundroom")
                || descLower.contains("conundroom.us")
                || descLower.contains("top golf")
                || descLower.contains("topgolf")
                || descLower.contains("state fair")
                || descLower.contains("statefair")
                || descLower.contains("disney")
                || descLower.contains("universal studio")
                || descLower.contains("universalstudio")
                || descLower.contains("sea world")
                || descLower.contains("seaworld")
                || descLower.contains("camping")
                || descLower.contains("cape disappointment")
                || descLower.contains("recreation.gov")) {
            LOGGER.debug(
                    "🏷️ detectCategoryFromDescription: Detected entertainment venue → 'entertainment'");
            return "entertainment";
        }

        return null;
    }
}
