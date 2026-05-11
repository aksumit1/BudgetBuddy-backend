package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Tax Export Service Exports transaction data in formats suitable for tax filing (1099, W-2,
 * Schedule A, etc.) Supports CSV, JSON, and PDF export formats
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
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.DataClass",
    "PMD.OnlyOneReturn"
})
@Service
public class TaxExportService {

    private static final String GENERATED = "Generated: ";

    private static final String OTHER = "OTHER";

    private static final String SUMMARY = "SUMMARY\n";

    private static final String TAX_YEAR = "Tax Year: ";

    private static final String INTEREST = "interest";

    private static final Logger LOGGER = LoggerFactory.getLogger(TaxExportService.class);

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TaxExportService(
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    /** Tax export result containing categorized transactions */
    public static class TaxExportResult {
        private final Map<String, List<TaxTransaction>> transactionsByCategory = new HashMap<>();
        private final TaxSummary summary = new TaxSummary();

        public Map<String, List<TaxTransaction>> getTransactionsByCategory() {
            return transactionsByCategory;
        }

        public TaxSummary getSummary() {
            return summary;
        }
    }

    /** Tax transaction with all relevant fields for tax filing */
    public static class TaxTransaction {
        private String transactionId;
        private LocalDate date;
        private String description;
        private String merchantName;
        private String userName; // Card/account user name (family member who made the transaction)
        private BigDecimal amount;
        private String category;
        private String taxTag; // RSU, Salary, Interest, Dividend, Charity, etc.
        private String accountId;
        private String currencyCode; // Original currency code (for multi-currency transactions)
        private BigDecimal
                originalAmount; // Original amount in original currency (for multi-currency)

        // Getters and setters
        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(final String transactionId) {
            this.transactionId = transactionId;
        }

        public LocalDate getDate() {
            return date;
        }

        public void setDate(final LocalDate date) {
            this.date = date;
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

        public String getUserName() {
            return userName;
        }

        public void setUserName(final String userName) {
            this.userName = userName;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(final BigDecimal amount) {
            this.amount = amount;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(final String category) {
            this.category = category;
        }

        public String getTaxTag() {
            return taxTag;
        }

        public void setTaxTag(final String taxTag) {
            this.taxTag = taxTag;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(final String accountId) {
            this.accountId = accountId;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(final String currencyCode) {
            this.currencyCode = currencyCode;
        }

        public BigDecimal getOriginalAmount() {
            return originalAmount;
        }

        public void setOriginalAmount(final BigDecimal originalAmount) {
            this.originalAmount = originalAmount;
        }
    }

    /** Tax summary with totals by category */
    public static class TaxSummary {
        private BigDecimal totalSalary = BigDecimal.ZERO;
        private BigDecimal totalInterest = BigDecimal.ZERO;
        private BigDecimal totalDividends = BigDecimal.ZERO;
        private BigDecimal totalRSU = BigDecimal.ZERO;
        private BigDecimal totalCharity = BigDecimal.ZERO;
        private BigDecimal totalDMV = BigDecimal.ZERO;
        private BigDecimal totalCPA = BigDecimal.ZERO;
        private BigDecimal totalTuition = BigDecimal.ZERO;
        private BigDecimal totalPropertyTax = BigDecimal.ZERO;
        private BigDecimal totalStateTax = BigDecimal.ZERO;
        private BigDecimal totalLocalTax = BigDecimal.ZERO;
        private BigDecimal totalMortgageInterest = BigDecimal.ZERO;
        private BigDecimal totalMedical = BigDecimal.ZERO;
        private BigDecimal totalStockSales = BigDecimal.ZERO;
        private BigDecimal totalCapitalGains = BigDecimal.ZERO;
        private BigDecimal totalCapitalLosses = BigDecimal.ZERO;
        private BigDecimal yearEndBalance = BigDecimal.ZERO;

        // Getters and setters
        public BigDecimal getTotalSalary() {
            return totalSalary;
        }

        public void setTotalSalary(final BigDecimal totalSalary) {
            this.totalSalary = totalSalary;
        }

        public BigDecimal getTotalInterest() {
            return totalInterest;
        }

        public void setTotalInterest(final BigDecimal totalInterest) {
            this.totalInterest = totalInterest;
        }

        public BigDecimal getTotalDividends() {
            return totalDividends;
        }

        public void setTotalDividends(final BigDecimal totalDividends) {
            this.totalDividends = totalDividends;
        }

        public BigDecimal getTotalRSU() {
            return totalRSU;
        }

        public void setTotalRSU(final BigDecimal totalRSU) {
            this.totalRSU = totalRSU;
        }

        public BigDecimal getTotalCharity() {
            return totalCharity;
        }

        public void setTotalCharity(final BigDecimal totalCharity) {
            this.totalCharity = totalCharity;
        }

        public BigDecimal getTotalDMV() {
            return totalDMV;
        }

        public void setTotalDMV(final BigDecimal totalDMV) {
            this.totalDMV = totalDMV;
        }

        public BigDecimal getTotalCPA() {
            return totalCPA;
        }

        public void setTotalCPA(final BigDecimal totalCPA) {
            this.totalCPA = totalCPA;
        }

        public BigDecimal getTotalTuition() {
            return totalTuition;
        }

        public void setTotalTuition(final BigDecimal totalTuition) {
            this.totalTuition = totalTuition;
        }

        public BigDecimal getTotalPropertyTax() {
            return totalPropertyTax;
        }

        public void setTotalPropertyTax(final BigDecimal totalPropertyTax) {
            this.totalPropertyTax = totalPropertyTax;
        }

        public BigDecimal getTotalStateTax() {
            return totalStateTax;
        }

        public void setTotalStateTax(final BigDecimal totalStateTax) {
            this.totalStateTax = totalStateTax;
        }

        public BigDecimal getTotalLocalTax() {
            return totalLocalTax;
        }

        public void setTotalLocalTax(final BigDecimal totalLocalTax) {
            this.totalLocalTax = totalLocalTax;
        }

        public BigDecimal getTotalMortgageInterest() {
            return totalMortgageInterest;
        }

        public void setTotalMortgageInterest(final BigDecimal totalMortgageInterest) {
            this.totalMortgageInterest = totalMortgageInterest;
        }

        public BigDecimal getTotalMedical() {
            return totalMedical;
        }

        public void setTotalMedical(final BigDecimal totalMedical) {
            this.totalMedical = totalMedical;
        }

        public BigDecimal getTotalStockSales() {
            return totalStockSales;
        }

        public void setTotalStockSales(final BigDecimal totalStockSales) {
            this.totalStockSales = totalStockSales;
        }

        public BigDecimal getTotalCapitalGains() {
            return totalCapitalGains;
        }

        public void setTotalCapitalGains(final BigDecimal totalCapitalGains) {
            this.totalCapitalGains = totalCapitalGains;
        }

        public BigDecimal getTotalCapitalLosses() {
            return totalCapitalLosses;
        }

        public void setTotalCapitalLosses(final BigDecimal totalCapitalLosses) {
            this.totalCapitalLosses = totalCapitalLosses;
        }

        public BigDecimal getYearEndBalance() {
            return yearEndBalance;
        }

        public void setYearEndBalance(final BigDecimal yearEndBalance) {
            this.yearEndBalance = yearEndBalance;
        }
    }

    /**
     * Generate tax export for a specific year
     *
     * @param userId User ID
     * @param year Tax year (e.g., 2024)
     * @param categories Optional list of categories to filter (null = all categories)
     * @param accountIds Optional list of account IDs to filter (null = all accounts)
     * @param startDate Optional start date within year (YYYY-MM-DD, null = year start)
     * @param endDate Optional end date within year (YYYY-MM-DD, null = year end)
     * @return Tax export result with categorized transactions
     */
    public TaxExportResult generateTaxExport(
            final String userId,
            final int year,
            final List<String> categories,
            final List<String> accountIds,
            final String startDate,
            final String endDate) {
        // Validate inputs
        if (userId == null || userId.isEmpty()) {
            LOGGER.error("generateTaxExport: userId is null or empty");
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }

        // Validate year (reasonable range: 1900-2100)
        if (year < 1900 || year > 2100) {
            LOGGER.error("generateTaxExport: Invalid year {} for user {}", year, userId);
            throw new IllegalArgumentException("Year must be between 1900 and 2100");
        }

        LOGGER.info(
                "Generating tax export for user {} for year {} (categories: {}, accountIds: {}, dateRange: {} to {})",
                userId,
                year,
                categories,
                accountIds,
                startDate,
                endDate);

        // Parse date range (use year boundaries if not specified)
        final LocalDate startDateObj;
        final LocalDate endDateObj;

        if (startDate != null && !startDate.isEmpty()) {
            try {
                startDateObj = LocalDate.parse(startDate);
                // Validate start date is within year
                if (startDateObj.getYear() != year) {
                    throw new IllegalArgumentException(
                            "Start date must be within the specified year");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid start date format. Expected YYYY-MM-DD: " + startDate, e);
            }
        } else {
            startDateObj = LocalDate.of(year, 1, 1);
        }

        if (endDate != null && !endDate.isEmpty()) {
            try {
                endDateObj = LocalDate.parse(endDate);
                // Validate end date is within year
                if (endDateObj.getYear() != year) {
                    throw new IllegalArgumentException(
                            "End date must be within the specified year");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid end date format. Expected YYYY-MM-DD: " + endDate, e);
            }
        } else {
            endDateObj = LocalDate.of(year, 12, 31);
        }

        if (startDateObj.isAfter(endDateObj)) {
            throw new IllegalArgumentException("Start date must be before or equal to end date");
        }

        // Get all transactions for the year
        // Format dates as ISO strings (YYYY-MM-DD)
        final String startDateStr =
                startDateObj.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endDateStr =
                endDateObj.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        final TaxExportResult result = new TaxExportResult();

        // Categorize transactions by tax type
        int skippedCount = 0;
        for (final TransactionTable transaction : transactions) {
            if (transaction == null) {
                skippedCount++;
                continue;
            }

            // Validate transaction date is within year range (defense in depth)
            try {
                if (transaction.getTransactionDate() != null
                        && !transaction.getTransactionDate().isEmpty()) {
                    final LocalDate txDate = LocalDate.parse(transaction.getTransactionDate());
                    if (txDate.isBefore(startDateObj) || txDate.isAfter(endDateObj)) {
                        LOGGER.debug(
                                "Skipping transaction {}: date {} is outside year range {} to {}",
                                transaction.getTransactionId(),
                                txDate,
                                startDateObj,
                                endDateObj);
                        skippedCount++;
                        continue;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn(
                        "Skipping transaction {}: invalid date format '{}': {}",
                        transaction.getTransactionId(),
                        transaction.getTransactionDate(),
                        e.getMessage());
                skippedCount++;
                continue;
            }

            // Filter by account if specified
            if (accountIds != null && !accountIds.isEmpty()) {
                if (transaction.getAccountId() == null
                        || !accountIds.contains(transaction.getAccountId())) {
                    skippedCount++;
                    continue;
                }
            }

            final TaxTransaction taxTx = convertToTaxTransaction(transaction);
            if (taxTx == null) {
                skippedCount++;
                continue;
            }

            final String taxCategory = determineTaxCategory(transaction);

            // Filter by category if specified
            if (categories != null && !categories.isEmpty()) {
                if (!categories.contains(taxCategory)) {
                    skippedCount++;
                    continue;
                }
            }

            result.getTransactionsByCategory()
                    .computeIfAbsent(taxCategory, k -> new ArrayList<>())
                    .add(taxTx);

            // Update summary totals (with null-safe amount)
            final BigDecimal amount = transaction.getAmount();
            if (amount == null) {
                LOGGER.warn(
                        "Transaction {} has null amount, skipping summary update",
                        transaction.getTransactionId());
                continue;
            }
            updateSummary(result.getSummary(), taxCategory, amount);
        }

        if (skippedCount > 0) {
            LOGGER.warn(
                    "Skipped {} transactions during tax export (invalid dates, null data, etc.)",
                    skippedCount);
        }

        // Calculate year-end balance
        try {
            final BigDecimal yearEndBalance = calculateYearEndBalance(userId, year);
            result.getSummary().setYearEndBalance(yearEndBalance);
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to calculate year-end balance for user {} year {}: {}",
                    userId,
                    year,
                    e.getMessage());
            // Continue without year-end balance rather than failing entire export
        }

        LOGGER.info(
                "Tax export generated: {} transactions across {} categories",
                transactions.size(),
                result.getTransactionsByCategory().size());

        return result;
    }

    /**
     * Calculate year-end balance across all accounts Uses account balances as of the end of the tax
     * year
     */
    private BigDecimal calculateYearEndBalance(final String userId, final int year) {
        try {
            final List<AccountTable> accounts = accountRepository.findByUserId(userId);

            if (accounts == null || accounts.isEmpty()) {
                LOGGER.debug(
                        "No accounts found for user {} for year-end balance calculation", userId);
                return BigDecimal.ZERO;
            }

            BigDecimal totalBalance = BigDecimal.ZERO;
            for (final AccountTable account : accounts) {
                if (account == null || account.getBalance() == null) {
                    continue;
                }

                // Only include active accounts
                final Boolean active = account.getActive();
                if (active == null || active) {
                    totalBalance = totalBalance.add(account.getBalance());
                }
            }

            LOGGER.debug(
                    "Calculated year-end balance for user {} year {}: {}",
                    userId,
                    year,
                    totalBalance);
            return totalBalance;
        } catch (Exception e) {
            LOGGER.error(
                    "Error calculating year-end balance for user {} year {}: {}",
                    userId,
                    year,
                    e.getMessage(),
                    e);
            throw e;
        }
    }

    /** Convert TransactionTable to TaxTransaction */
    private TaxTransaction convertToTaxTransaction(final TransactionTable transaction) {
        final TaxTransaction taxTx = new TaxTransaction();
        taxTx.setTransactionId(transaction.getTransactionId());
        if (transaction.getTransactionDate() != null
                && !transaction.getTransactionDate().isEmpty()) {
            taxTx.setDate(LocalDate.parse(transaction.getTransactionDate()));
        }
        taxTx.setDescription(transaction.getDescription());
        taxTx.setMerchantName(transaction.getMerchantName());
        taxTx.setUserName(transaction.getUserName()); // Card/account user (family member)
        taxTx.setAmount(transaction.getAmount());
        taxTx.setCategory(transaction.getCategoryPrimary());
        taxTx.setAccountId(transaction.getAccountId());
        taxTx.setCurrencyCode(
                transaction.getCurrencyCode() != null ? transaction.getCurrencyCode() : "USD");

        // Determine tax tag
        taxTx.setTaxTag(determineTaxTag(transaction));

        return taxTx;
    }

    /** Lower-cased fields a transaction is classified against. {@link #amount} stays in original form. */
    private record TaxContext(
            String description,
            String merchantName,
            String category,
            BigDecimal amount,
            String paymentChannel) {}

    private static String lowerOrEmpty(final String value) {
        return value != null ? value.toLowerCase(Locale.ROOT) : "";
    }

    /** Determine tax category for a transaction */
    private String determineTaxCategory(final TransactionTable transaction) {
        final TaxContext ctx =
                new TaxContext(
                        lowerOrEmpty(transaction.getDescription()),
                        lowerOrEmpty(transaction.getMerchantName()),
                        lowerOrEmpty(transaction.getCategoryPrimary()),
                        transaction.getAmount(),
                        lowerOrEmpty(transaction.getPaymentChannel()));

        String result = classifyAsIncomeOrACH(ctx);
        if (result != null) {
            return result;
        }
        result = classifyAsInterestOrDividend(ctx);
        if (result != null) {
            return result;
        }
        result = classifyAsItemizedDeduction(ctx);
        if (result != null) {
            return result;
        }
        result = classifyAsTaxPayment(ctx);
        if (result != null) {
            return result;
        }
        result = classifyAsMedicalOrInvestment(ctx);
        if (result != null) {
            return result;
        }
        return "OTHER";
    }

    private String classifyAsIncomeOrACH(final TaxContext ctx) {
        if (isSalaryTransaction(ctx.description(), ctx.amount(), ctx.paymentChannel())) {
            return "SALARY";
        }
        if (isRSUTransaction(ctx.category(), ctx.description(), ctx.merchantName(), ctx.amount())) {
            return "RSU";
        }
        if (isACHTransaction(ctx.description(), ctx.paymentChannel())) {
            return ctx.amount().compareTo(BigDecimal.ZERO) > 0 ? "ACH_INCOME" : "ACH_EXPENSE";
        }
        return null;
    }

    private static String classifyAsInterestOrDividend(final TaxContext ctx) {
        // Mortgage interest is checked first so it isn't shadowed by the general INTEREST rule.
        if (ctx.description().contains("mortgage") && ctx.description().contains(INTEREST)) {
            return "MORTGAGE_INTEREST";
        }
        if (INTEREST.equals(ctx.category()) || ctx.description().contains(INTEREST)) {
            return "INTEREST";
        }
        if ("dividend".equals(ctx.category()) || ctx.description().contains("dividend")) {
            return "DIVIDEND";
        }
        return null;
    }

    private String classifyAsItemizedDeduction(final TaxContext ctx) {
        if (isCharityTransaction(ctx.description(), ctx.category())) {
            return "CHARITY";
        }
        if (isDMVFeeTransaction(ctx.description(), ctx.merchantName())) {
            return "DMV";
        }
        if (isCPAFeeTransaction(ctx.description(), ctx.merchantName())) {
            return "CPA";
        }
        if (isTuitionTransaction(ctx.description(), ctx.merchantName(), ctx.category())) {
            return "TUITION";
        }
        return null;
    }

    private String classifyAsTaxPayment(final TaxContext ctx) {
        if (isPropertyTaxTransaction(ctx.description(), ctx.merchantName())) {
            return "PROPERTY_TAX";
        }
        if (isStateTaxTransaction(ctx.description(), ctx.merchantName())) {
            return "STATE_TAX";
        }
        if (isLocalTaxTransaction(ctx.description(), ctx.merchantName())) {
            return "LOCAL_TAX";
        }
        return null;
    }

    private static String classifyAsMedicalOrInvestment(final TaxContext ctx) {
        if ("healthcare".equals(ctx.category()) || ctx.description().contains("medical")) {
            return "MEDICAL";
        }
        if ("investment".equals(ctx.category())
                || ctx.description().contains("stock sale")
                || ctx.description().contains("capital gain")) {
            return ctx.amount().compareTo(BigDecimal.ZERO) > 0 ? "CAPITAL_GAIN" : "CAPITAL_LOSS";
        }
        return null;
    }

    /** Determine tax tag for a transaction */
    private String determineTaxTag(final TransactionTable transaction) {
        final String category = determineTaxCategory(transaction);
        return category; // Tax tag is same as category for now
    }

    /** Update summary totals Validates amount is not null before updating */
    private void updateSummary(
            final TaxSummary summary, final String taxCategory, final BigDecimal amount) {
        if (summary == null) {
            LOGGER.warn("updateSummary: summary is null");
            return;
        }

        if (amount == null) {
            LOGGER.warn("updateSummary: amount is null for category {}", taxCategory);
            return;
        }

        if (taxCategory == null || taxCategory.isEmpty()) {
            LOGGER.warn("updateSummary: taxCategory is null or empty");
            return;
        }

        switch (taxCategory) {
            case "SALARY":
                summary.setTotalSalary(summary.getTotalSalary().add(amount));
                break;
            case "INTEREST":
                summary.setTotalInterest(summary.getTotalInterest().add(amount));
                break;
            case "DIVIDEND":
                summary.setTotalDividends(summary.getTotalDividends().add(amount));
                break;
            case "RSU":
                summary.setTotalRSU(summary.getTotalRSU().add(amount));
                break;
            case "CHARITY":
                // For tax reporting, expenses should be reported as positive dollar amounts
                summary.setTotalCharity(
                        summary.getTotalCharity()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "DMV":
                summary.setTotalDMV(
                        summary.getTotalDMV()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "CPA":
                summary.setTotalCPA(
                        summary.getTotalCPA()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "TUITION":
                summary.setTotalTuition(
                        summary.getTotalTuition()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "PROPERTY_TAX":
                summary.setTotalPropertyTax(
                        summary.getTotalPropertyTax()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "STATE_TAX":
                summary.setTotalStateTax(
                        summary.getTotalStateTax()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "LOCAL_TAX":
                summary.setTotalLocalTax(
                        summary.getTotalLocalTax()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "MORTGAGE_INTEREST":
                summary.setTotalMortgageInterest(
                        summary.getTotalMortgageInterest()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "MEDICAL":
                summary.setTotalMedical(
                        summary.getTotalMedical()
                                .add(
                                        amount.compareTo(BigDecimal.ZERO) < 0
                                                ? amount.negate()
                                                : amount));
                break;
            case "CAPITAL_GAIN":
                summary.setTotalCapitalGains(summary.getTotalCapitalGains().add(amount));
                break;
            case "CAPITAL_LOSS":
                summary.setTotalCapitalLosses(summary.getTotalCapitalLosses().add(amount));
                break;
            default:
                LOGGER.debug("updateSummary: unrecognised taxCategory '{}' — ignored", taxCategory);
                break;
        }
    }

    // Tax detection helper methods (reuse from CSVImportService logic)
    private boolean isSalaryTransaction(
            final String description, final BigDecimal amount, final String paymentChannel) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        final String descLower = description.toLowerCase(Locale.ROOT);
        final String channelLower =
                paymentChannel != null ? paymentChannel.toLowerCase(Locale.ROOT) : "";
        return descLower.contains("payroll")
                || descLower.contains("salary")
                || descLower.contains("paycheck")
                || descLower.contains("wages")
                || (channelLower.contains("ach")
                        && (descLower.contains("payroll") || descLower.contains("salary")));
    }

    private boolean isRSUTransaction(
            final String category,
            final String description,
            final String merchantName,
            final BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        final String combined =
                (category + " " + description + " " + (merchantName != null ? merchantName : ""))
                        .toLowerCase(Locale.ROOT);
        return combined.contains("rsu")
                || combined.contains("restricted stock")
                || combined.contains("stock vest")
                || combined.contains("equity vest");
    }

    private boolean isACHTransaction(final String description, final String paymentChannel) {
        final String channelLower =
                paymentChannel != null ? paymentChannel.toLowerCase(Locale.ROOT) : "";
        final String descLower = description.toLowerCase(Locale.ROOT);
        return channelLower.contains("ach")
                || descLower.contains("ach")
                || descLower.contains("direct deposit")
                || descLower.contains("electronic transfer");
    }

    private boolean isCharityTransaction(final String description, final String category) {
        final String combined = (description + " " + category).toLowerCase(Locale.ROOT);
        return combined.contains("donation")
                || combined.contains("charity")
                || combined.contains("charitable")
                || combined.contains("gofundme")
                || combined.contains("red cross")
                || combined.contains("tithe");
    }

    private boolean isDMVFeeTransaction(final String description, final String merchantName) {
        final String combined = (description + " " + merchantName).toLowerCase(Locale.ROOT);
        return combined.contains("dmv")
                || combined.contains("vehicle registration")
                || combined.contains("license renewal")
                || combined.contains("driver license");
    }

    private boolean isCPAFeeTransaction(final String description, final String merchantName) {
        final String combined = (description + " " + merchantName).toLowerCase(Locale.ROOT);
        return combined.contains("cpa")
                || combined.contains("tax preparer")
                || combined.contains("tax preparation")
                || combined.contains("h&r block")
                || combined.contains("turbotax");
    }

    private boolean isTuitionTransaction(
            final String description, final String merchantName, final String category) {
        final String combined =
                (description + " " + merchantName + " " + category).toLowerCase(Locale.ROOT);
        return combined.contains("tuition")
                || combined.contains("university")
                || combined.contains("school fee")
                || combined.contains("education");
    }

    private boolean isPropertyTaxTransaction(final String description, final String merchantName) {
        final String combined = (description + " " + merchantName).toLowerCase(Locale.ROOT);
        return combined.contains("property tax")
                || combined.contains("real estate tax")
                || combined.contains("tax assessor");
    }

    private boolean isStateTaxTransaction(final String description, final String merchantName) {
        final String combined = (description + " " + merchantName).toLowerCase(Locale.ROOT);
        return combined.contains("state tax")
                || combined.contains("state income tax")
                || combined.contains("franchise tax board")
                || combined.contains("ftb");
    }

    private boolean isLocalTaxTransaction(final String description, final String merchantName) {
        final String combined = (description + " " + merchantName).toLowerCase(Locale.ROOT);
        return combined.contains("city tax")
                || combined.contains("county tax")
                || combined.contains("local tax")
                || combined.contains("municipal tax");
    }

    /**
     * Export tax data to CSV format Suitable for importing into tax software (TurboTax, H&R Block,
     * etc.)
     */
    public String exportToCSV(final TaxExportResult result, final int year) {
        final StringBuilder csv = new StringBuilder();

        // Header
        csv.append(TAX_YEAR).append(year).append('\n');
        csv.append(GENERATED).append(LocalDate.now()).append("\n\n");

        // Summary section
        csv.append(SUMMARY);
        csv.append("Category,Total Amount\n");
        final TaxSummary summary = result.getSummary();
        csv.append("Salary,").append(summary.getTotalSalary()).append('\n');
        csv.append("Interest,").append(summary.getTotalInterest()).append('\n');
        csv.append("Dividends,").append(summary.getTotalDividends()).append('\n');
        csv.append("RSU,").append(summary.getTotalRSU()).append('\n');
        csv.append("Charity,").append(summary.getTotalCharity()).append('\n');
        csv.append("DMV Fees,").append(summary.getTotalDMV()).append('\n');
        csv.append("CPA Fees,").append(summary.getTotalCPA()).append('\n');
        csv.append("Tuition,").append(summary.getTotalTuition()).append('\n');
        csv.append("Property Tax,").append(summary.getTotalPropertyTax()).append('\n');
        csv.append("State Tax,").append(summary.getTotalStateTax()).append('\n');
        csv.append("Local Tax,").append(summary.getTotalLocalTax()).append('\n');
        csv.append("Mortgage Interest,").append(summary.getTotalMortgageInterest()).append('\n');
        csv.append("Medical,").append(summary.getTotalMedical()).append('\n');
        csv.append("Capital Gains,").append(summary.getTotalCapitalGains()).append('\n');
        csv.append("Capital Losses,").append(summary.getTotalCapitalLosses()).append('\n');
        csv.append('\n');

        // Check if there are any transactions
        final boolean hasTransactions =
                result.getTransactionsByCategory() != null
                        && !result.getTransactionsByCategory().isEmpty()
                        && result.getTransactionsByCategory().values().stream()
                                .anyMatch(list -> list != null && !list.isEmpty());

        // Detailed transactions by category
        csv.append("DETAILED TRANSACTIONS\n");
        csv.append("Category,Date,Description,Merchant,User,Amount,Tax Tag\n");

        if (!hasTransactions) {
            // Add helpful message when no transactions
            csv.append("No transactions found for tax year ")
                    .append(year)
                    .append(
                            ".\nPlease ensure you have imported transactions and categorized them appropriately.\n");
        } else {
            for (final Map.Entry<String, List<TaxTransaction>> entry :
                    result.getTransactionsByCategory().entrySet()) {
                for (final TaxTransaction tx : entry.getValue()) {
                    if (tx == null) {
                        continue; // Skip null transactions
                    }

                    // Null-safe field extraction
                    final String category = entry.getKey() != null ? entry.getKey() : OTHER;
                    final String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
                    final String description =
                            tx.getDescription() != null ? tx.getDescription() : "";
                    final String merchantName =
                            tx.getMerchantName() != null ? tx.getMerchantName() : "";
                    final String userName = tx.getUserName() != null ? tx.getUserName() : "";
                    final String amountStr =
                            tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
                    final String taxTag = tx.getTaxTag() != null ? tx.getTaxTag() : OTHER;

                    // Proper CSV escaping: replace quotes with double quotes, escape newlines and
                    // commas
                    final String escapedDescription =
                            description
                                    .replace("\"", "\"\"") // Escape quotes
                                    .replace("\n", " ") // Replace newlines with space
                                    .replace("\r", " ") // Replace carriage returns
                                    .replace(
                                            ",",
                                            ";"); // Replace commas with semicolons (or quote entire
                    // field)

                    final String escapedMerchant =
                            merchantName
                                    .replace("\"", "\"\"")
                                    .replace("\n", " ")
                                    .replace("\r", " ")
                                    .replace(",", ";");

                    final String escapedUser =
                            userName.replace("\"", "\"\"")
                                    .replace("\n", " ")
                                    .replace("\r", " ")
                                    .replace(",", ";");

                    csv.append(category)
                            .append(',')
                            .append(dateStr)
                            .append(',')
                            .append('"')
                            .append(escapedDescription)
                            .append("\",")
                            .append('"')
                            .append(escapedMerchant)
                            .append("\",")
                            .append('"')
                            .append(escapedUser)
                            .append("\",")
                            .append(amountStr)
                            .append(',')
                            .append(taxTag)
                            .append('\n');
                }
            }
        }

        return csv.toString();
    }

    /** Export tax data to JSON format Suitable for programmatic consumption or API integration */
    public String exportToJSON(final TaxExportResult result, final int year) {
        // Simple JSON export - can be enhanced with proper JSON library
        final StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"taxYear\": ").append(year).append(",\n");
        json.append("  \"generated\": \"").append(LocalDate.now()).append("\",\n");
        json.append("  \"summary\": {\n");
        final TaxSummary summary = result.getSummary();
        json.append("    \"totalSalary\": ").append(summary.getTotalSalary()).append(",\n");
        json.append("    \"totalInterest\": ").append(summary.getTotalInterest()).append(",\n");
        json.append("    \"totalDividends\": ").append(summary.getTotalDividends()).append(",\n");
        json.append("    \"totalRSU\": ").append(summary.getTotalRSU()).append(",\n");
        json.append("    \"totalCharity\": ").append(summary.getTotalCharity()).append(",\n");
        json.append("    \"totalDMV\": ").append(summary.getTotalDMV()).append(",\n");
        json.append("    \"totalCPA\": ").append(summary.getTotalCPA()).append(",\n");
        json.append("    \"totalTuition\": ").append(summary.getTotalTuition()).append(",\n");
        json.append("    \"totalPropertyTax\": ")
                .append(summary.getTotalPropertyTax())
                .append(",\n");
        json.append("    \"totalStateTax\": ").append(summary.getTotalStateTax()).append(",\n");
        json.append("    \"totalLocalTax\": ").append(summary.getTotalLocalTax()).append(",\n");
        json.append("    \"totalMortgageInterest\": ")
                .append(summary.getTotalMortgageInterest())
                .append(",\n");
        json.append("    \"totalMedical\": ").append(summary.getTotalMedical()).append(",\n");
        json.append("    \"totalCapitalGains\": ")
                .append(summary.getTotalCapitalGains())
                .append(",\n");
        json.append("    \"totalCapitalLosses\": ")
                .append(summary.getTotalCapitalLosses())
                .append(",\n");
        json.append("    \"yearEndBalance\": ").append(summary.getYearEndBalance()).append('\n');
        json.append("  },\n");
        json.append("  \"transactions\": [\n");

        boolean first = true;
        for (final Map.Entry<String, List<TaxTransaction>> entry :
                result.getTransactionsByCategory().entrySet()) {
            for (final TaxTransaction tx : entry.getValue()) {
                if (!first) {
                    json.append(",\n");
                }
                json.append("    {\n");
                json.append("      \"category\": \"").append(entry.getKey()).append("\",\n");
                json.append("      \"date\": \"").append(tx.getDate()).append("\",\n");
                json.append("      \"description\": \"")
                        .append(tx.getDescription().replace("\"", "\\\""))
                        .append("\",\n");
                json.append("      \"merchantName\": \"")
                        .append(
                                tx.getMerchantName() != null
                                        ? tx.getMerchantName().replace("\"", "\\\"")
                                        : "")
                        .append("\",\n");
                json.append("      \"userName\": \"")
                        .append(
                                tx.getUserName() != null
                                        ? tx.getUserName().replace("\"", "\\\"")
                                        : "")
                        .append("\",\n");
                json.append("      \"amount\": ").append(tx.getAmount()).append(",\n");
                json.append("      \"taxTag\": \"").append(tx.getTaxTag()).append("\"\n");
                json.append("    }");
                first = false;
            }
        }

        json.append("\n  ]\n");
        json.append("}\n");

        return json.toString();
    }

    /**
     * Generate tax export for multiple years Aggregates transactions across multiple years for
     * comprehensive tax filing
     *
     * @param userId User ID
     * @param years Array of tax years (e.g., [2022, 2023, 2024])
     * @param categories Optional list of categories to filter
     * @param accountIds Optional list of account IDs to filter
     * @return Combined tax export result across all years
     */
    public TaxExportResult generateMultiYearTaxExport(
            final String userId,
            final int[] years,
            final List<String> categories,
            final List<String> accountIds) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }

        if (years == null || years.length == 0) {
            throw new IllegalArgumentException("Years array cannot be null or empty");
        }

        // Validate all years
        for (final int year : years) {
            if (year < 1900 || year > 2100) {
                throw new IllegalArgumentException(
                        "Invalid year: " + year + ". Must be between 1900 and 2100");
            }
        }

        LOGGER.info(
                "Generating multi-year tax export for user {} for years {}",
                userId,
                Arrays.toString(years));

        final TaxExportResult combinedResult = new TaxExportResult();

        // Process each year and combine results
        for (final int year : years) {
            final TaxExportResult yearResult =
                    generateTaxExport(userId, year, categories, accountIds, null, null);

            // Combine transactions by category
            for (final Map.Entry<String, List<TaxTransaction>> entry :
                    yearResult.getTransactionsByCategory().entrySet()) {
                final String category = entry.getKey();
                final List<TaxTransaction> yearTransactions = entry.getValue();

                combinedResult
                        .getTransactionsByCategory()
                        .computeIfAbsent(category, k -> new ArrayList<>())
                        .addAll(yearTransactions);
            }

            // Combine summary totals
            final TaxSummary combinedSummary = combinedResult.getSummary();
            final TaxSummary yearSummary = yearResult.getSummary();

            combinedSummary.setTotalSalary(
                    combinedSummary.getTotalSalary().add(yearSummary.getTotalSalary()));
            combinedSummary.setTotalInterest(
                    combinedSummary.getTotalInterest().add(yearSummary.getTotalInterest()));
            combinedSummary.setTotalDividends(
                    combinedSummary.getTotalDividends().add(yearSummary.getTotalDividends()));
            combinedSummary.setTotalRSU(
                    combinedSummary.getTotalRSU().add(yearSummary.getTotalRSU()));
            combinedSummary.setTotalCharity(
                    combinedSummary.getTotalCharity().add(yearSummary.getTotalCharity()));
            combinedSummary.setTotalDMV(
                    combinedSummary.getTotalDMV().add(yearSummary.getTotalDMV()));
            combinedSummary.setTotalCPA(
                    combinedSummary.getTotalCPA().add(yearSummary.getTotalCPA()));
            combinedSummary.setTotalTuition(
                    combinedSummary.getTotalTuition().add(yearSummary.getTotalTuition()));
            combinedSummary.setTotalPropertyTax(
                    combinedSummary.getTotalPropertyTax().add(yearSummary.getTotalPropertyTax()));
            combinedSummary.setTotalStateTax(
                    combinedSummary.getTotalStateTax().add(yearSummary.getTotalStateTax()));
            combinedSummary.setTotalLocalTax(
                    combinedSummary.getTotalLocalTax().add(yearSummary.getTotalLocalTax()));
            combinedSummary.setTotalMortgageInterest(
                    combinedSummary
                            .getTotalMortgageInterest()
                            .add(yearSummary.getTotalMortgageInterest()));
            combinedSummary.setTotalMedical(
                    combinedSummary.getTotalMedical().add(yearSummary.getTotalMedical()));
            combinedSummary.setTotalCapitalGains(
                    combinedSummary.getTotalCapitalGains().add(yearSummary.getTotalCapitalGains()));
            combinedSummary.setTotalCapitalLosses(
                    combinedSummary
                            .getTotalCapitalLosses()
                            .add(yearSummary.getTotalCapitalLosses()));

            // Use the latest year's year-end balance
            if (year == years[years.length - 1]) {
                combinedSummary.setYearEndBalance(yearSummary.getYearEndBalance());
            }
        }

        LOGGER.info(
                "Multi-year tax export generated: {} transactions across {} categories for {} years",
                combinedResult.getTransactionsByCategory().values().stream()
                        .mapToInt(List::size)
                        .sum(),
                combinedResult.getTransactionsByCategory().size(),
                years.length);

        return combinedResult;
    }

    /** Export multi-year tax data to CSV format Includes year breakdown in the export */
    public String exportToCSVMultiYear(final TaxExportResult result, final int[] years) {
        final StringBuilder csv = new StringBuilder();

        // Header
        csv.append("Multi-Year Tax Export\n");
        csv.append("Years: ")
                .append(Arrays.toString(years).replaceAll("[\\[\\] ]", ""))
                .append('\n');
        csv.append(GENERATED).append(LocalDate.now()).append("\n\n");

        // Summary section
        csv.append("SUMMARY (Combined Across All Years)\n");
        csv.append("Category,Total Amount\n");
        final TaxSummary summary = result.getSummary();
        csv.append("Salary,").append(summary.getTotalSalary()).append('\n');
        csv.append("Interest,").append(summary.getTotalInterest()).append('\n');
        csv.append("Dividends,").append(summary.getTotalDividends()).append('\n');
        csv.append("RSU,").append(summary.getTotalRSU()).append('\n');
        csv.append("Charity,").append(summary.getTotalCharity()).append('\n');
        csv.append("DMV Fees,").append(summary.getTotalDMV()).append('\n');
        csv.append("CPA Fees,").append(summary.getTotalCPA()).append('\n');
        csv.append("Tuition,").append(summary.getTotalTuition()).append('\n');
        csv.append("Property Tax,").append(summary.getTotalPropertyTax()).append('\n');
        csv.append("State Tax,").append(summary.getTotalStateTax()).append('\n');
        csv.append("Local Tax,").append(summary.getTotalLocalTax()).append('\n');
        csv.append("Mortgage Interest,").append(summary.getTotalMortgageInterest()).append('\n');
        csv.append("Medical,").append(summary.getTotalMedical()).append('\n');
        csv.append("Capital Gains,").append(summary.getTotalCapitalGains()).append('\n');
        csv.append("Capital Losses,").append(summary.getTotalCapitalLosses()).append('\n');
        csv.append("Year-End Balance (Latest Year),")
                .append(summary.getYearEndBalance())
                .append('\n');
        csv.append('\n');

        // Detailed transactions by category
        csv.append("DETAILED TRANSACTIONS (All Years Combined)\n");
        csv.append("Category,Date,Description,Merchant,User,Amount,Tax Tag,Year\n");

        for (final Map.Entry<String, List<TaxTransaction>> entry :
                result.getTransactionsByCategory().entrySet()) {
            for (final TaxTransaction tx : entry.getValue()) {
                if (tx == null) {
                    continue;
                }

                final String category = entry.getKey() != null ? entry.getKey() : OTHER;
                final String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
                final String description = tx.getDescription() != null ? tx.getDescription() : "";
                final String merchantName =
                        tx.getMerchantName() != null ? tx.getMerchantName() : "";
                final String userName = tx.getUserName() != null ? tx.getUserName() : "";
                final String amountStr =
                        tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
                final String taxTag = tx.getTaxTag() != null ? tx.getTaxTag() : OTHER;
                final int year = tx.getDate() != null ? tx.getDate().getYear() : 0;

                // Proper CSV escaping
                final String escapedDescription =
                        description
                                .replace("\"", "\"\"")
                                .replace("\n", " ")
                                .replace("\r", " ")
                                .replace(",", ";");

                final String escapedMerchant =
                        merchantName
                                .replace("\"", "\"\"")
                                .replace("\n", " ")
                                .replace("\r", " ")
                                .replace(",", ";");

                final String escapedUser =
                        userName.replace("\"", "\"\"")
                                .replace("\n", " ")
                                .replace("\r", " ")
                                .replace(",", ";");

                csv.append(category)
                        .append(',')
                        .append(dateStr)
                        .append(',')
                        .append('"')
                        .append(escapedDescription)
                        .append("\",")
                        .append('"')
                        .append(escapedMerchant)
                        .append("\",")
                        .append('"')
                        .append(escapedUser)
                        .append("\",")
                        .append(amountStr)
                        .append(',')
                        .append(taxTag)
                        .append(',')
                        .append(year)
                        .append('\n');
            }
        }

        return csv.toString();
    }

    /**
     * Export Schedule A (Itemized Deductions) format Includes: Charity, Medical, Property Tax,
     * State Tax, Local Tax, Mortgage Interest, DMV, CPA
     */
    public String exportToScheduleA(final TaxExportResult result, final int year) {
        final StringBuilder csv = new StringBuilder();

        csv.append("Schedule A - Itemized Deductions\n");
        csv.append(TAX_YEAR).append(year).append('\n');
        csv.append(GENERATED).append(LocalDate.now()).append("\n\n");

        final TaxSummary summary = result.getSummary();

        // Schedule A line items
        csv.append("Line,Description,Amount\n");
        csv.append("5a,State and local income taxes,")
                .append(summary.getTotalStateTax())
                .append('\n');
        csv.append("5b,State and local real estate taxes,")
                .append(summary.getTotalPropertyTax())
                .append('\n');
        csv.append("5c,State and local personal property taxes,")
                .append(summary.getTotalLocalTax())
                .append('\n');
        csv.append("8a,Home mortgage interest,")
                .append(summary.getTotalMortgageInterest())
                .append('\n');
        csv.append("11,Gifts to charity,").append(summary.getTotalCharity()).append('\n');
        csv.append("1,Medical and dental expenses,").append(summary.getTotalMedical()).append('\n');
        csv.append("Other,DMV fees,").append(summary.getTotalDMV()).append('\n');
        csv.append("Other,CPA fees,").append(summary.getTotalCPA()).append('\n');
        csv.append('\n');

        // Detailed transactions
        csv.append("DETAILED TRANSACTIONS\n");
        csv.append("Line,Date,Description,Merchant,User,Amount\n");

        final List<String> scheduleACategories =
                Arrays.asList(
                        "CHARITY",
                        "MEDICAL",
                        "PROPERTY_TAX",
                        "STATE_TAX",
                        "LOCAL_TAX",
                        "MORTGAGE_INTEREST",
                        "DMV",
                        "CPA");

        for (final String category : scheduleACategories) {
            final List<TaxTransaction> transactions =
                    result.getTransactionsByCategory()
                            .getOrDefault(category, Collections.emptyList());
            final String lineNumber = getScheduleALineNumber(category);

            for (final TaxTransaction tx : transactions) {
                if (tx == null) {
                    continue;
                }

                final String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
                final String description = tx.getDescription() != null ? tx.getDescription() : "";
                final String merchantName =
                        tx.getMerchantName() != null ? tx.getMerchantName() : "";
                final String userName = tx.getUserName() != null ? tx.getUserName() : "";
                final String amountStr =
                        tx.getAmount() != null ? tx.getAmount().toString() : "0.00";

                final String escapedDescription =
                        description
                                .replace("\"", "\"\"")
                                .replace("\n", " ")
                                .replace("\r", " ")
                                .replace(",", ";");

                final String escapedMerchant =
                        merchantName != null
                                ? merchantName.replace("\"", "\"\"").replace(",", ";")
                                : "";
                final String escapedUser =
                        userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";

                csv.append(lineNumber)
                        .append(',')
                        .append(dateStr)
                        .append(',')
                        .append('"')
                        .append(escapedDescription)
                        .append("\",")
                        .append('"')
                        .append(escapedMerchant)
                        .append("\",")
                        .append('"')
                        .append(escapedUser)
                        .append("\",")
                        .append(amountStr)
                        .append('\n');
            }
        }

        return csv.toString();
    }

    /** Export Schedule B (Interest and Dividends) format */
    public String exportToScheduleB(final TaxExportResult result, final int year) {
        final StringBuilder csv = new StringBuilder();

        csv.append("Schedule B - Interest and Dividends\n");
        csv.append(TAX_YEAR).append(year).append('\n');
        csv.append(GENERATED).append(LocalDate.now()).append("\n\n");

        final TaxSummary summary = result.getSummary();

        csv.append(SUMMARY);
        csv.append("Total Interest Income,").append(summary.getTotalInterest()).append('\n');
        csv.append("Total Dividend Income,").append(summary.getTotalDividends()).append('\n');
        csv.append('\n');

        // Part I: Interest Income
        csv.append("PART I - INTEREST INCOME\n");
        csv.append("Date,Description,Merchant,User,Amount\n");

        final List<TaxTransaction> interestTransactions =
                result.getTransactionsByCategory()
                        .getOrDefault("INTEREST", Collections.emptyList());

        for (final TaxTransaction tx : interestTransactions) {
            if (tx == null) {
                continue;
            }

            final String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
            final String description = tx.getDescription() != null ? tx.getDescription() : "";
            final String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
            final String userName = tx.getUserName() != null ? tx.getUserName() : "";
            final String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";

            final String escapedDescription =
                    description.replace("\"", "\"\"").replace("\n", " ").replace(",", ";");

            final String escapedMerchant =
                    merchantName != null
                            ? merchantName.replace("\"", "\"\"").replace(",", ";")
                            : "";
            final String escapedUser =
                    userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";

            csv.append(dateStr)
                    .append(',')
                    .append('"')
                    .append(escapedDescription)
                    .append("\",")
                    .append('"')
                    .append(escapedMerchant)
                    .append("\",")
                    .append('"')
                    .append(escapedUser)
                    .append("\",")
                    .append(amountStr)
                    .append('\n');
        }

        csv.append('\n');

        // Part II: Dividend Income
        csv.append("PART II - DIVIDEND INCOME\n");
        csv.append("Date,Description,Merchant,User,Amount\n");

        final List<TaxTransaction> dividendTransactions =
                result.getTransactionsByCategory()
                        .getOrDefault("DIVIDEND", Collections.emptyList());

        for (final TaxTransaction tx : dividendTransactions) {
            if (tx == null) {
                continue;
            }

            final String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
            final String description = tx.getDescription() != null ? tx.getDescription() : "";
            final String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
            final String userName = tx.getUserName() != null ? tx.getUserName() : "";
            final String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";

            final String escapedDescription =
                    description.replace("\"", "\"\"").replace("\n", " ").replace(",", ";");

            final String escapedMerchant =
                    merchantName != null
                            ? merchantName.replace("\"", "\"\"").replace(",", ";")
                            : "";
            final String escapedUser =
                    userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";

            csv.append(dateStr)
                    .append(',')
                    .append('"')
                    .append(escapedDescription)
                    .append("\",")
                    .append('"')
                    .append(escapedMerchant)
                    .append("\",")
                    .append('"')
                    .append(escapedUser)
                    .append("\",")
                    .append(amountStr)
                    .append('\n');
        }

        return csv.toString();
    }

    /** Export Schedule D (Capital Gains and Losses) format */
    public String exportToScheduleD(final TaxExportResult result, final int year) {
        final StringBuilder csv = new StringBuilder();

        csv.append("Schedule D - Capital Gains and Losses\n");
        csv.append(TAX_YEAR).append(year).append('\n');
        csv.append(GENERATED).append(LocalDate.now()).append("\n\n");

        final TaxSummary summary = result.getSummary();

        csv.append(SUMMARY);
        csv.append("Total Capital Gains,").append(summary.getTotalCapitalGains()).append('\n');
        csv.append("Total Capital Losses,").append(summary.getTotalCapitalLosses()).append('\n');
        csv.append("Net Capital Gain/Loss,")
                .append(summary.getTotalCapitalGains().subtract(summary.getTotalCapitalLosses()))
                .append('\n');
        csv.append('\n');

        // Part I: Short-Term Capital Gains and Losses
        csv.append("PART I - SHORT-TERM CAPITAL GAINS AND LOSSES\n");
        csv.append("Date,Description,Merchant,User,Amount,Type\n");

        final List<TaxTransaction> capitalGainTransactions =
                result.getTransactionsByCategory()
                        .getOrDefault("CAPITAL_GAIN", Collections.emptyList());
        final List<TaxTransaction> capitalLossTransactions =
                result.getTransactionsByCategory()
                        .getOrDefault("CAPITAL_LOSS", Collections.emptyList());

        // Combine and sort by date
        final List<TaxTransaction> allCapitalTransactions = new ArrayList<>();
        allCapitalTransactions.addAll(capitalGainTransactions);
        allCapitalTransactions.addAll(capitalLossTransactions);
        allCapitalTransactions.sort(
                (a, b) -> {
                    if (a.getDate() == null || b.getDate() == null) {
                        return 0;
                    }
                    return a.getDate().compareTo(b.getDate());
                });

        for (final TaxTransaction tx : allCapitalTransactions) {
            if (tx == null) {
                continue;
            }

            final String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
            final String description = tx.getDescription() != null ? tx.getDescription() : "";
            final String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
            final String userName = tx.getUserName() != null ? tx.getUserName() : "";
            final String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
            final String type =
                    tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) > 0
                            ? "GAIN"
                            : "LOSS";

            final String escapedDescription =
                    description.replace("\"", "\"\"").replace("\n", " ").replace(",", ";");

            final String escapedMerchant =
                    merchantName != null
                            ? merchantName.replace("\"", "\"\"").replace(",", ";")
                            : "";
            final String escapedUser =
                    userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";

            csv.append(dateStr)
                    .append(',')
                    .append('"')
                    .append(escapedDescription)
                    .append("\",")
                    .append('"')
                    .append(escapedMerchant)
                    .append("\",")
                    .append('"')
                    .append(escapedUser)
                    .append("\",")
                    .append(amountStr)
                    .append(',')
                    .append(type)
                    .append('\n');
        }

        return csv.toString();
    }

    /** Get Schedule A line number for a tax category */
    private String getScheduleALineNumber(final String category) {
        switch (category) {
            case "STATE_TAX":
                return "5a";
            case "PROPERTY_TAX":
                return "5b";
            case "LOCAL_TAX":
                return "5c";
            case "MORTGAGE_INTEREST":
                return "8a";
            case "CHARITY":
                return "11";
            case "MEDICAL":
                return "1";
            default:
                return "Other";
        }
    }
}
