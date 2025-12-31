package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Tax Export Service
 * Exports transaction data in formats suitable for tax filing (1099, W-2, Schedule A, etc.)
 * Supports CSV, JSON, and PDF export formats
 */
@Service
public class TaxExportService {

    private static final Logger logger = LoggerFactory.getLogger(TaxExportService.class);
    
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    
    public TaxExportService(TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }
    
    /**
     * Tax export result containing categorized transactions
     */
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
    
    /**
     * Tax transaction with all relevant fields for tax filing
     */
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
        private BigDecimal originalAmount; // Original amount in original currency (for multi-currency)
        
        // Getters and setters
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        
        public String getUserName() { return userName; }
        public void setUserName(String userName) { this.userName = userName; }
        
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        
        public String getTaxTag() { return taxTag; }
        public void setTaxTag(String taxTag) { this.taxTag = taxTag; }
        
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
        
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        
        public BigDecimal getOriginalAmount() { return originalAmount; }
        public void setOriginalAmount(BigDecimal originalAmount) { this.originalAmount = originalAmount; }
    }
    
    /**
     * Tax summary with totals by category
     */
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
        public BigDecimal getTotalSalary() { return totalSalary; }
        public void setTotalSalary(BigDecimal totalSalary) { this.totalSalary = totalSalary; }
        
        public BigDecimal getTotalInterest() { return totalInterest; }
        public void setTotalInterest(BigDecimal totalInterest) { this.totalInterest = totalInterest; }
        
        public BigDecimal getTotalDividends() { return totalDividends; }
        public void setTotalDividends(BigDecimal totalDividends) { this.totalDividends = totalDividends; }
        
        public BigDecimal getTotalRSU() { return totalRSU; }
        public void setTotalRSU(BigDecimal totalRSU) { this.totalRSU = totalRSU; }
        
        public BigDecimal getTotalCharity() { return totalCharity; }
        public void setTotalCharity(BigDecimal totalCharity) { this.totalCharity = totalCharity; }
        
        public BigDecimal getTotalDMV() { return totalDMV; }
        public void setTotalDMV(BigDecimal totalDMV) { this.totalDMV = totalDMV; }
        
        public BigDecimal getTotalCPA() { return totalCPA; }
        public void setTotalCPA(BigDecimal totalCPA) { this.totalCPA = totalCPA; }
        
        public BigDecimal getTotalTuition() { return totalTuition; }
        public void setTotalTuition(BigDecimal totalTuition) { this.totalTuition = totalTuition; }
        
        public BigDecimal getTotalPropertyTax() { return totalPropertyTax; }
        public void setTotalPropertyTax(BigDecimal totalPropertyTax) { this.totalPropertyTax = totalPropertyTax; }
        
        public BigDecimal getTotalStateTax() { return totalStateTax; }
        public void setTotalStateTax(BigDecimal totalStateTax) { this.totalStateTax = totalStateTax; }
        
        public BigDecimal getTotalLocalTax() { return totalLocalTax; }
        public void setTotalLocalTax(BigDecimal totalLocalTax) { this.totalLocalTax = totalLocalTax; }
        
        public BigDecimal getTotalMortgageInterest() { return totalMortgageInterest; }
        public void setTotalMortgageInterest(BigDecimal totalMortgageInterest) { this.totalMortgageInterest = totalMortgageInterest; }
        
        public BigDecimal getTotalMedical() { return totalMedical; }
        public void setTotalMedical(BigDecimal totalMedical) { this.totalMedical = totalMedical; }
        
        public BigDecimal getTotalStockSales() { return totalStockSales; }
        public void setTotalStockSales(BigDecimal totalStockSales) { this.totalStockSales = totalStockSales; }
        
        public BigDecimal getTotalCapitalGains() { return totalCapitalGains; }
        public void setTotalCapitalGains(BigDecimal totalCapitalGains) { this.totalCapitalGains = totalCapitalGains; }
        
        public BigDecimal getTotalCapitalLosses() { return totalCapitalLosses; }
        public void setTotalCapitalLosses(BigDecimal totalCapitalLosses) { this.totalCapitalLosses = totalCapitalLosses; }
        
        public BigDecimal getYearEndBalance() { return yearEndBalance; }
        public void setYearEndBalance(BigDecimal yearEndBalance) { this.yearEndBalance = yearEndBalance; }
    }
    
    /**
     * Generate tax export for a specific year
     * @param userId User ID
     * @param year Tax year (e.g., 2024)
     * @param categories Optional list of categories to filter (null = all categories)
     * @param accountIds Optional list of account IDs to filter (null = all accounts)
     * @param startDate Optional start date within year (YYYY-MM-DD, null = year start)
     * @param endDate Optional end date within year (YYYY-MM-DD, null = year end)
     * @return Tax export result with categorized transactions
     */
    public TaxExportResult generateTaxExport(String userId, int year, 
                                              List<String> categories, List<String> accountIds,
                                              String startDate, String endDate) {
        // Validate inputs
        if (userId == null || userId.isEmpty()) {
            logger.error("generateTaxExport: userId is null or empty");
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        
        // Validate year (reasonable range: 1900-2100)
        if (year < 1900 || year > 2100) {
            logger.error("generateTaxExport: Invalid year {} for user {}", year, userId);
            throw new IllegalArgumentException("Year must be between 1900 and 2100");
        }
        
        logger.info("Generating tax export for user {} for year {} (categories: {}, accountIds: {}, dateRange: {} to {})", 
            userId, year, categories, accountIds, startDate, endDate);
        
        // Parse date range (use year boundaries if not specified)
        LocalDate startDateObj;
        LocalDate endDateObj;
        
        if (startDate != null && !startDate.isEmpty()) {
            try {
                startDateObj = LocalDate.parse(startDate);
                // Validate start date is within year
                if (startDateObj.getYear() != year) {
                    throw new IllegalArgumentException("Start date must be within the specified year");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid start date format. Expected YYYY-MM-DD: " + startDate, e);
            }
        } else {
            startDateObj = LocalDate.of(year, 1, 1);
        }
        
        if (endDate != null && !endDate.isEmpty()) {
            try {
                endDateObj = LocalDate.parse(endDate);
                // Validate end date is within year
                if (endDateObj.getYear() != year) {
                    throw new IllegalArgumentException("End date must be within the specified year");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid end date format. Expected YYYY-MM-DD: " + endDate, e);
            }
        } else {
            endDateObj = LocalDate.of(year, 12, 31);
        }
        
        if (startDateObj.isAfter(endDateObj)) {
            throw new IllegalArgumentException("Start date must be before or equal to end date");
        }
        
        // Get all transactions for the year
        // Format dates as ISO strings (YYYY-MM-DD)
        String startDateStr = startDateObj.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endDateStr = endDateObj.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        List<TransactionTable> transactions = transactionRepository.findByUserIdAndDateRange(
            userId, startDateStr, endDateStr
        );
        
        TaxExportResult result = new TaxExportResult();
        
        // Categorize transactions by tax type
        int skippedCount = 0;
        for (TransactionTable transaction : transactions) {
            if (transaction == null) {
                skippedCount++;
                continue;
            }
            
            // Validate transaction date is within year range (defense in depth)
            try {
                if (transaction.getTransactionDate() != null && !transaction.getTransactionDate().isEmpty()) {
                    LocalDate txDate = LocalDate.parse(transaction.getTransactionDate());
                    if (txDate.isBefore(startDateObj) || txDate.isAfter(endDateObj)) {
                        logger.debug("Skipping transaction {}: date {} is outside year range {} to {}", 
                            transaction.getTransactionId(), txDate, startDateObj, endDateObj);
                        skippedCount++;
                        continue;
                    }
                }
            } catch (Exception e) {
                logger.warn("Skipping transaction {}: invalid date format '{}': {}", 
                    transaction.getTransactionId(), transaction.getTransactionDate(), e.getMessage());
                skippedCount++;
                continue;
            }
            
            // Filter by account if specified
            if (accountIds != null && !accountIds.isEmpty()) {
                if (transaction.getAccountId() == null || !accountIds.contains(transaction.getAccountId())) {
                    skippedCount++;
                    continue;
                }
            }
            
            TaxTransaction taxTx = convertToTaxTransaction(transaction);
            if (taxTx == null) {
                skippedCount++;
                continue;
            }
            
            String taxCategory = determineTaxCategory(transaction);
            
            // Filter by category if specified
            if (categories != null && !categories.isEmpty()) {
                if (!categories.contains(taxCategory)) {
                    skippedCount++;
                    continue;
                }
            }
            
            result.getTransactionsByCategory().computeIfAbsent(taxCategory, k -> new ArrayList<>()).add(taxTx);
            
            // Update summary totals (with null-safe amount)
            BigDecimal amount = transaction.getAmount();
            if (amount == null) {
                logger.warn("Transaction {} has null amount, skipping summary update", transaction.getTransactionId());
                continue;
            }
            updateSummary(result.getSummary(), taxCategory, amount);
        }
        
        if (skippedCount > 0) {
            logger.warn("Skipped {} transactions during tax export (invalid dates, null data, etc.)", skippedCount);
        }
        
        // Calculate year-end balance
        try {
            BigDecimal yearEndBalance = calculateYearEndBalance(userId, year);
            result.getSummary().setYearEndBalance(yearEndBalance);
        } catch (Exception e) {
            logger.warn("Failed to calculate year-end balance for user {} year {}: {}", 
                userId, year, e.getMessage());
            // Continue without year-end balance rather than failing entire export
        }
        
        logger.info("Tax export generated: {} transactions across {} categories", 
            transactions.size(), result.getTransactionsByCategory().size());
        
        return result;
    }
    
    /**
     * Calculate year-end balance across all accounts
     * Uses account balances as of the end of the tax year
     */
    private BigDecimal calculateYearEndBalance(String userId, int year) {
        try {
            List<AccountTable> accounts = accountRepository.findByUserId(userId);
            
            if (accounts == null || accounts.isEmpty()) {
                logger.debug("No accounts found for user {} for year-end balance calculation", userId);
                return BigDecimal.ZERO;
            }
            
            BigDecimal totalBalance = BigDecimal.ZERO;
            for (AccountTable account : accounts) {
                if (account == null || account.getBalance() == null) {
                    continue;
                }
                
                // Only include active accounts
                Boolean active = account.getActive();
                if (active == null || active) {
                    totalBalance = totalBalance.add(account.getBalance());
                }
            }
            
            logger.debug("Calculated year-end balance for user {} year {}: {}", userId, year, totalBalance);
            return totalBalance;
        } catch (Exception e) {
            logger.error("Error calculating year-end balance for user {} year {}: {}", 
                userId, year, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Convert TransactionTable to TaxTransaction
     */
    private TaxTransaction convertToTaxTransaction(TransactionTable transaction) {
        TaxTransaction taxTx = new TaxTransaction();
        taxTx.setTransactionId(transaction.getTransactionId());
        if (transaction.getTransactionDate() != null && !transaction.getTransactionDate().isEmpty()) {
            taxTx.setDate(LocalDate.parse(transaction.getTransactionDate()));
        }
        taxTx.setDescription(transaction.getDescription());
        taxTx.setMerchantName(transaction.getMerchantName());
        taxTx.setUserName(transaction.getUserName()); // Card/account user (family member)
        taxTx.setAmount(transaction.getAmount());
        taxTx.setCategory(transaction.getCategoryPrimary());
        taxTx.setAccountId(transaction.getAccountId());
        taxTx.setCurrencyCode(transaction.getCurrencyCode() != null ? transaction.getCurrencyCode() : "USD");
        
        // Determine tax tag
        taxTx.setTaxTag(determineTaxTag(transaction));
        
        return taxTx;
    }
    
    /**
     * Determine tax category for a transaction
     */
    private String determineTaxCategory(TransactionTable transaction) {
        String description = transaction.getDescription() != null ? transaction.getDescription().toLowerCase() : "";
        String merchantName = transaction.getMerchantName() != null ? transaction.getMerchantName().toLowerCase() : "";
        String category = transaction.getCategoryPrimary() != null ? transaction.getCategoryPrimary().toLowerCase() : "";
        BigDecimal amount = transaction.getAmount();
        String paymentChannel = transaction.getPaymentChannel() != null ? transaction.getPaymentChannel().toLowerCase() : "";
        
        // Use local helper methods for detection
        if (isSalaryTransaction(description, amount, paymentChannel)) {
            return "SALARY";
        }
        if (isRSUTransaction(category, description, merchantName, amount)) {
            return "RSU";
        }
        if (isACHTransaction(description, paymentChannel)) {
            return amount.compareTo(BigDecimal.ZERO) > 0 ? "ACH_INCOME" : "ACH_EXPENSE";
        }
        // Check for mortgage interest FIRST (before general interest)
        String descLower = description.toLowerCase();
        if (descLower.contains("mortgage") && descLower.contains("interest")) {
            return "MORTGAGE_INTEREST";
        }
        if (category.equals("interest") || descLower.contains("interest")) {
            return "INTEREST";
        }
        if (category.equals("dividend") || description.contains("dividend")) {
            return "DIVIDEND";
        }
        if (isCharityTransaction(description, category)) {
            return "CHARITY";
        }
        if (isDMVFeeTransaction(description, merchantName)) {
            return "DMV";
        }
        if (isCPAFeeTransaction(description, merchantName)) {
            return "CPA";
        }
        if (isTuitionTransaction(description, merchantName, category)) {
            return "TUITION";
        }
        if (isPropertyTaxTransaction(description, merchantName)) {
            return "PROPERTY_TAX";
        }
        if (isStateTaxTransaction(description, merchantName)) {
            return "STATE_TAX";
        }
        if (isLocalTaxTransaction(description, merchantName)) {
            return "LOCAL_TAX";
        }
        if (category.equals("healthcare") || description.contains("medical")) {
            return "MEDICAL";
        }
        if (category.equals("investment") || description.contains("stock sale") || description.contains("capital gain")) {
            return amount.compareTo(BigDecimal.ZERO) > 0 ? "CAPITAL_GAIN" : "CAPITAL_LOSS";
        }
        
        return "OTHER";
    }
    
    /**
     * Determine tax tag for a transaction
     */
    private String determineTaxTag(TransactionTable transaction) {
        String category = determineTaxCategory(transaction);
        return category; // Tax tag is same as category for now
    }
    
    /**
     * Update summary totals
     * Validates amount is not null before updating
     */
    private void updateSummary(TaxSummary summary, String taxCategory, BigDecimal amount) {
        if (summary == null) {
            logger.warn("updateSummary: summary is null");
            return;
        }
        
        if (amount == null) {
            logger.warn("updateSummary: amount is null for category {}", taxCategory);
            return;
        }
        
        if (taxCategory == null || taxCategory.isEmpty()) {
            logger.warn("updateSummary: taxCategory is null or empty");
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
                summary.setTotalCharity(summary.getTotalCharity().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "DMV":
                summary.setTotalDMV(summary.getTotalDMV().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "CPA":
                summary.setTotalCPA(summary.getTotalCPA().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "TUITION":
                summary.setTotalTuition(summary.getTotalTuition().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "PROPERTY_TAX":
                summary.setTotalPropertyTax(summary.getTotalPropertyTax().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "STATE_TAX":
                summary.setTotalStateTax(summary.getTotalStateTax().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "LOCAL_TAX":
                summary.setTotalLocalTax(summary.getTotalLocalTax().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "MORTGAGE_INTEREST":
                summary.setTotalMortgageInterest(summary.getTotalMortgageInterest().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "MEDICAL":
                summary.setTotalMedical(summary.getTotalMedical().add(amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount));
                break;
            case "CAPITAL_GAIN":
                summary.setTotalCapitalGains(summary.getTotalCapitalGains().add(amount));
                break;
            case "CAPITAL_LOSS":
                summary.setTotalCapitalLosses(summary.getTotalCapitalLosses().add(amount));
                break;
        }
    }
    
    // Tax detection helper methods (reuse from CSVImportService logic)
    private boolean isSalaryTransaction(String description, BigDecimal amount, String paymentChannel) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        String descLower = description.toLowerCase();
        String channelLower = paymentChannel != null ? paymentChannel.toLowerCase() : "";
        return descLower.contains("payroll") || descLower.contains("salary") || 
               descLower.contains("paycheck") || descLower.contains("wages") ||
               (channelLower.contains("ach") && (descLower.contains("payroll") || descLower.contains("salary")));
    }
    
    private boolean isRSUTransaction(String category, String description, String merchantName, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        String combined = (category + " " + description + " " + (merchantName != null ? merchantName : "")).toLowerCase();
        return combined.contains("rsu") || combined.contains("restricted stock") || 
               combined.contains("stock vest") || combined.contains("equity vest");
    }
    
    private boolean isACHTransaction(String description, String paymentChannel) {
        String channelLower = paymentChannel != null ? paymentChannel.toLowerCase() : "";
        String descLower = description.toLowerCase();
        return channelLower.contains("ach") || descLower.contains("ach") || 
               descLower.contains("direct deposit") || descLower.contains("electronic transfer");
    }
    
    private boolean isCharityTransaction(String description, String category) {
        String combined = (description + " " + category).toLowerCase();
        return combined.contains("donation") || combined.contains("charity") || combined.contains("charitable") ||
               combined.contains("gofundme") || combined.contains("red cross") || combined.contains("tithe");
    }
    
    private boolean isDMVFeeTransaction(String description, String merchantName) {
        String combined = (description + " " + merchantName).toLowerCase();
        return combined.contains("dmv") || combined.contains("vehicle registration") || 
               combined.contains("license renewal") || combined.contains("driver license");
    }
    
    private boolean isCPAFeeTransaction(String description, String merchantName) {
        String combined = (description + " " + merchantName).toLowerCase();
        return combined.contains("cpa") || combined.contains("tax preparer") || 
               combined.contains("tax preparation") || combined.contains("h&r block") || combined.contains("turbotax");
    }
    
    private boolean isTuitionTransaction(String description, String merchantName, String category) {
        String combined = (description + " " + merchantName + " " + category).toLowerCase();
        return combined.contains("tuition") || combined.contains("university") || 
               combined.contains("school fee") || combined.contains("education");
    }
    
    private boolean isPropertyTaxTransaction(String description, String merchantName) {
        String combined = (description + " " + merchantName).toLowerCase();
        return combined.contains("property tax") || combined.contains("real estate tax") || 
               combined.contains("tax assessor");
    }
    
    private boolean isStateTaxTransaction(String description, String merchantName) {
        String combined = (description + " " + merchantName).toLowerCase();
        return combined.contains("state tax") || combined.contains("state income tax") || 
               combined.contains("franchise tax board") || combined.contains("ftb");
    }
    
    private boolean isLocalTaxTransaction(String description, String merchantName) {
        String combined = (description + " " + merchantName).toLowerCase();
        return combined.contains("city tax") || combined.contains("county tax") || 
               combined.contains("local tax") || combined.contains("municipal tax");
    }
    
    /**
     * Export tax data to CSV format
     * Suitable for importing into tax software (TurboTax, H&R Block, etc.)
     */
    public String exportToCSV(TaxExportResult result, int year) {
        StringBuilder csv = new StringBuilder();
        
        // Header
        csv.append("Tax Year: ").append(year).append("\n");
        csv.append("Generated: ").append(LocalDate.now()).append("\n\n");
        
        // Summary section
        csv.append("SUMMARY\n");
        csv.append("Category,Total Amount\n");
        TaxSummary summary = result.getSummary();
        csv.append("Salary,").append(summary.getTotalSalary()).append("\n");
        csv.append("Interest,").append(summary.getTotalInterest()).append("\n");
        csv.append("Dividends,").append(summary.getTotalDividends()).append("\n");
        csv.append("RSU,").append(summary.getTotalRSU()).append("\n");
        csv.append("Charity,").append(summary.getTotalCharity()).append("\n");
        csv.append("DMV Fees,").append(summary.getTotalDMV()).append("\n");
        csv.append("CPA Fees,").append(summary.getTotalCPA()).append("\n");
        csv.append("Tuition,").append(summary.getTotalTuition()).append("\n");
        csv.append("Property Tax,").append(summary.getTotalPropertyTax()).append("\n");
        csv.append("State Tax,").append(summary.getTotalStateTax()).append("\n");
        csv.append("Local Tax,").append(summary.getTotalLocalTax()).append("\n");
        csv.append("Mortgage Interest,").append(summary.getTotalMortgageInterest()).append("\n");
        csv.append("Medical,").append(summary.getTotalMedical()).append("\n");
        csv.append("Capital Gains,").append(summary.getTotalCapitalGains()).append("\n");
        csv.append("Capital Losses,").append(summary.getTotalCapitalLosses()).append("\n");
        csv.append("\n");
        
        // Check if there are any transactions
        boolean hasTransactions = result.getTransactionsByCategory() != null && 
                                  !result.getTransactionsByCategory().isEmpty() &&
                                  result.getTransactionsByCategory().values().stream()
                                      .anyMatch(list -> list != null && !list.isEmpty());
        
        // Detailed transactions by category
        csv.append("DETAILED TRANSACTIONS\n");
        csv.append("Category,Date,Description,Merchant,User,Amount,Tax Tag\n");
        
        if (!hasTransactions) {
            // Add helpful message when no transactions
            csv.append("No transactions found for tax year ").append(year)
               .append(".\nPlease ensure you have imported transactions and categorized them appropriately.\n");
        } else {
            for (Map.Entry<String, List<TaxTransaction>> entry : result.getTransactionsByCategory().entrySet()) {
                for (TaxTransaction tx : entry.getValue()) {
                    if (tx == null) {
                        continue; // Skip null transactions
                    }
                    
                    // Null-safe field extraction
                    String category = entry.getKey() != null ? entry.getKey() : "OTHER";
                    String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
                    String description = tx.getDescription() != null ? tx.getDescription() : "";
                    String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
                    String userName = tx.getUserName() != null ? tx.getUserName() : "";
                    String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
                    String taxTag = tx.getTaxTag() != null ? tx.getTaxTag() : "OTHER";
                    
                    // Proper CSV escaping: replace quotes with double quotes, escape newlines and commas
                    String escapedDescription = description
                        .replace("\"", "\"\"")  // Escape quotes
                        .replace("\n", " ")     // Replace newlines with space
                        .replace("\r", " ")     // Replace carriage returns
                        .replace(",", ";");     // Replace commas with semicolons (or quote entire field)
                    
                    String escapedMerchant = merchantName
                        .replace("\"", "\"\"")
                        .replace("\n", " ")
                        .replace("\r", " ")
                        .replace(",", ";");
                    
                    String escapedUser = userName
                        .replace("\"", "\"\"")
                        .replace("\n", " ")
                        .replace("\r", " ")
                        .replace(",", ";");
                    
                    csv.append(category).append(",")
                       .append(dateStr).append(",")
                       .append("\"").append(escapedDescription).append("\",")
                       .append("\"").append(escapedMerchant).append("\",")
                       .append("\"").append(escapedUser).append("\",")
                       .append(amountStr).append(",")
                       .append(taxTag).append("\n");
                }
            }
        }
        
        return csv.toString();
    }
    
    /**
     * Export tax data to JSON format
     * Suitable for programmatic consumption or API integration
     */
    public String exportToJSON(TaxExportResult result, int year) {
        // Simple JSON export - can be enhanced with proper JSON library
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"taxYear\": ").append(year).append(",\n");
        json.append("  \"generated\": \"").append(LocalDate.now()).append("\",\n");
        json.append("  \"summary\": {\n");
        TaxSummary summary = result.getSummary();
        json.append("    \"totalSalary\": ").append(summary.getTotalSalary()).append(",\n");
        json.append("    \"totalInterest\": ").append(summary.getTotalInterest()).append(",\n");
        json.append("    \"totalDividends\": ").append(summary.getTotalDividends()).append(",\n");
        json.append("    \"totalRSU\": ").append(summary.getTotalRSU()).append(",\n");
        json.append("    \"totalCharity\": ").append(summary.getTotalCharity()).append(",\n");
        json.append("    \"totalDMV\": ").append(summary.getTotalDMV()).append(",\n");
        json.append("    \"totalCPA\": ").append(summary.getTotalCPA()).append(",\n");
        json.append("    \"totalTuition\": ").append(summary.getTotalTuition()).append(",\n");
        json.append("    \"totalPropertyTax\": ").append(summary.getTotalPropertyTax()).append(",\n");
        json.append("    \"totalStateTax\": ").append(summary.getTotalStateTax()).append(",\n");
        json.append("    \"totalLocalTax\": ").append(summary.getTotalLocalTax()).append(",\n");
        json.append("    \"totalMortgageInterest\": ").append(summary.getTotalMortgageInterest()).append(",\n");
        json.append("    \"totalMedical\": ").append(summary.getTotalMedical()).append(",\n");
        json.append("    \"totalCapitalGains\": ").append(summary.getTotalCapitalGains()).append(",\n");
        json.append("    \"totalCapitalLosses\": ").append(summary.getTotalCapitalLosses()).append(",\n");
        json.append("    \"yearEndBalance\": ").append(summary.getYearEndBalance()).append("\n");
        json.append("  },\n");
        json.append("  \"transactions\": [\n");
        
        boolean first = true;
        for (Map.Entry<String, List<TaxTransaction>> entry : result.getTransactionsByCategory().entrySet()) {
            for (TaxTransaction tx : entry.getValue()) {
                if (!first) json.append(",\n");
                json.append("    {\n");
                json.append("      \"category\": \"").append(entry.getKey()).append("\",\n");
                json.append("      \"date\": \"").append(tx.getDate()).append("\",\n");
                json.append("      \"description\": \"").append(tx.getDescription().replace("\"", "\\\"")).append("\",\n");
                json.append("      \"merchantName\": \"").append(tx.getMerchantName() != null ? tx.getMerchantName().replace("\"", "\\\"") : "").append("\",\n");
                json.append("      \"userName\": \"").append(tx.getUserName() != null ? tx.getUserName().replace("\"", "\\\"") : "").append("\",\n");
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
     * Generate tax export for multiple years
     * Aggregates transactions across multiple years for comprehensive tax filing
     * 
     * @param userId User ID
     * @param years Array of tax years (e.g., [2022, 2023, 2024])
     * @param categories Optional list of categories to filter
     * @param accountIds Optional list of account IDs to filter
     * @return Combined tax export result across all years
     */
    public TaxExportResult generateMultiYearTaxExport(String userId, int[] years, 
                                                      List<String> categories, List<String> accountIds) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        
        if (years == null || years.length == 0) {
            throw new IllegalArgumentException("Years array cannot be null or empty");
        }
        
        // Validate all years
        for (int year : years) {
            if (year < 1900 || year > 2100) {
                throw new IllegalArgumentException("Invalid year: " + year + ". Must be between 1900 and 2100");
            }
        }
        
        logger.info("Generating multi-year tax export for user {} for years {}", userId, Arrays.toString(years));
        
        TaxExportResult combinedResult = new TaxExportResult();
        
        // Process each year and combine results
        for (int year : years) {
            TaxExportResult yearResult = generateTaxExport(userId, year, categories, accountIds, null, null);
            
            // Combine transactions by category
            for (Map.Entry<String, List<TaxTransaction>> entry : yearResult.getTransactionsByCategory().entrySet()) {
                String category = entry.getKey();
                List<TaxTransaction> yearTransactions = entry.getValue();
                
                combinedResult.getTransactionsByCategory()
                    .computeIfAbsent(category, k -> new ArrayList<>())
                    .addAll(yearTransactions);
            }
            
            // Combine summary totals
            TaxSummary combinedSummary = combinedResult.getSummary();
            TaxSummary yearSummary = yearResult.getSummary();
            
            combinedSummary.setTotalSalary(combinedSummary.getTotalSalary().add(yearSummary.getTotalSalary()));
            combinedSummary.setTotalInterest(combinedSummary.getTotalInterest().add(yearSummary.getTotalInterest()));
            combinedSummary.setTotalDividends(combinedSummary.getTotalDividends().add(yearSummary.getTotalDividends()));
            combinedSummary.setTotalRSU(combinedSummary.getTotalRSU().add(yearSummary.getTotalRSU()));
            combinedSummary.setTotalCharity(combinedSummary.getTotalCharity().add(yearSummary.getTotalCharity()));
            combinedSummary.setTotalDMV(combinedSummary.getTotalDMV().add(yearSummary.getTotalDMV()));
            combinedSummary.setTotalCPA(combinedSummary.getTotalCPA().add(yearSummary.getTotalCPA()));
            combinedSummary.setTotalTuition(combinedSummary.getTotalTuition().add(yearSummary.getTotalTuition()));
            combinedSummary.setTotalPropertyTax(combinedSummary.getTotalPropertyTax().add(yearSummary.getTotalPropertyTax()));
            combinedSummary.setTotalStateTax(combinedSummary.getTotalStateTax().add(yearSummary.getTotalStateTax()));
            combinedSummary.setTotalLocalTax(combinedSummary.getTotalLocalTax().add(yearSummary.getTotalLocalTax()));
            combinedSummary.setTotalMortgageInterest(combinedSummary.getTotalMortgageInterest().add(yearSummary.getTotalMortgageInterest()));
            combinedSummary.setTotalMedical(combinedSummary.getTotalMedical().add(yearSummary.getTotalMedical()));
            combinedSummary.setTotalCapitalGains(combinedSummary.getTotalCapitalGains().add(yearSummary.getTotalCapitalGains()));
            combinedSummary.setTotalCapitalLosses(combinedSummary.getTotalCapitalLosses().add(yearSummary.getTotalCapitalLosses()));
            
            // Use the latest year's year-end balance
            if (year == years[years.length - 1]) {
                combinedSummary.setYearEndBalance(yearSummary.getYearEndBalance());
            }
        }
        
        logger.info("Multi-year tax export generated: {} transactions across {} categories for {} years", 
            combinedResult.getTransactionsByCategory().values().stream().mapToInt(List::size).sum(),
            combinedResult.getTransactionsByCategory().size(),
            years.length);
        
        return combinedResult;
    }
    
    /**
     * Export multi-year tax data to CSV format
     * Includes year breakdown in the export
     */
    public String exportToCSVMultiYear(TaxExportResult result, int[] years) {
        StringBuilder csv = new StringBuilder();
        
        // Header
        csv.append("Multi-Year Tax Export\n");
        csv.append("Years: ").append(Arrays.toString(years).replaceAll("[\\[\\] ]", "")).append("\n");
        csv.append("Generated: ").append(LocalDate.now()).append("\n\n");
        
        // Summary section
        csv.append("SUMMARY (Combined Across All Years)\n");
        csv.append("Category,Total Amount\n");
        TaxSummary summary = result.getSummary();
        csv.append("Salary,").append(summary.getTotalSalary()).append("\n");
        csv.append("Interest,").append(summary.getTotalInterest()).append("\n");
        csv.append("Dividends,").append(summary.getTotalDividends()).append("\n");
        csv.append("RSU,").append(summary.getTotalRSU()).append("\n");
        csv.append("Charity,").append(summary.getTotalCharity()).append("\n");
        csv.append("DMV Fees,").append(summary.getTotalDMV()).append("\n");
        csv.append("CPA Fees,").append(summary.getTotalCPA()).append("\n");
        csv.append("Tuition,").append(summary.getTotalTuition()).append("\n");
        csv.append("Property Tax,").append(summary.getTotalPropertyTax()).append("\n");
        csv.append("State Tax,").append(summary.getTotalStateTax()).append("\n");
        csv.append("Local Tax,").append(summary.getTotalLocalTax()).append("\n");
        csv.append("Mortgage Interest,").append(summary.getTotalMortgageInterest()).append("\n");
        csv.append("Medical,").append(summary.getTotalMedical()).append("\n");
        csv.append("Capital Gains,").append(summary.getTotalCapitalGains()).append("\n");
        csv.append("Capital Losses,").append(summary.getTotalCapitalLosses()).append("\n");
        csv.append("Year-End Balance (Latest Year),").append(summary.getYearEndBalance()).append("\n");
        csv.append("\n");
        
        // Detailed transactions by category
        csv.append("DETAILED TRANSACTIONS (All Years Combined)\n");
        csv.append("Category,Date,Description,Merchant,User,Amount,Tax Tag,Year\n");
        
        for (Map.Entry<String, List<TaxTransaction>> entry : result.getTransactionsByCategory().entrySet()) {
            for (TaxTransaction tx : entry.getValue()) {
                if (tx == null) {
                    continue;
                }
                
                String category = entry.getKey() != null ? entry.getKey() : "OTHER";
                String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
                String description = tx.getDescription() != null ? tx.getDescription() : "";
                String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
                String userName = tx.getUserName() != null ? tx.getUserName() : "";
                String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
                String taxTag = tx.getTaxTag() != null ? tx.getTaxTag() : "OTHER";
                int year = tx.getDate() != null ? tx.getDate().getYear() : 0;
                
                // Proper CSV escaping
                String escapedDescription = description
                    .replace("\"", "\"\"")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace(",", ";");
                
                String escapedMerchant = merchantName
                    .replace("\"", "\"\"")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace(",", ";");
                
                String escapedUser = userName
                    .replace("\"", "\"\"")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace(",", ";");
                
                csv.append(category).append(",")
                    .append(dateStr).append(",")
                    .append("\"").append(escapedDescription).append("\",")
                    .append("\"").append(escapedMerchant).append("\",")
                    .append("\"").append(escapedUser).append("\",")
                    .append(amountStr).append(",")
                    .append(taxTag).append(",")
                    .append(year).append("\n");
            }
        }
        
        return csv.toString();
    }
    
    /**
     * Export Schedule A (Itemized Deductions) format
     * Includes: Charity, Medical, Property Tax, State Tax, Local Tax, Mortgage Interest, DMV, CPA
     */
    public String exportToScheduleA(TaxExportResult result, int year) {
        StringBuilder csv = new StringBuilder();
        
        csv.append("Schedule A - Itemized Deductions\n");
        csv.append("Tax Year: ").append(year).append("\n");
        csv.append("Generated: ").append(LocalDate.now()).append("\n\n");
        
        TaxSummary summary = result.getSummary();
        
        // Schedule A line items
        csv.append("Line,Description,Amount\n");
        csv.append("5a,State and local income taxes,").append(summary.getTotalStateTax()).append("\n");
        csv.append("5b,State and local real estate taxes,").append(summary.getTotalPropertyTax()).append("\n");
        csv.append("5c,State and local personal property taxes,").append(summary.getTotalLocalTax()).append("\n");
        csv.append("8a,Home mortgage interest,").append(summary.getTotalMortgageInterest()).append("\n");
        csv.append("11,Gifts to charity,").append(summary.getTotalCharity()).append("\n");
        csv.append("1,Medical and dental expenses,").append(summary.getTotalMedical()).append("\n");
        csv.append("Other,DMV fees,").append(summary.getTotalDMV()).append("\n");
        csv.append("Other,CPA fees,").append(summary.getTotalCPA()).append("\n");
        csv.append("\n");
        
        // Detailed transactions
        csv.append("DETAILED TRANSACTIONS\n");
        csv.append("Line,Date,Description,Merchant,User,Amount\n");
        
        List<String> scheduleACategories = Arrays.asList("CHARITY", "MEDICAL", "PROPERTY_TAX", 
            "STATE_TAX", "LOCAL_TAX", "MORTGAGE_INTEREST", "DMV", "CPA");
        
        for (String category : scheduleACategories) {
            List<TaxTransaction> transactions = result.getTransactionsByCategory().getOrDefault(category, Collections.emptyList());
            String lineNumber = getScheduleALineNumber(category);
            
            for (TaxTransaction tx : transactions) {
                if (tx == null) continue;
                
                String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
                String description = tx.getDescription() != null ? tx.getDescription() : "";
                String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
                String userName = tx.getUserName() != null ? tx.getUserName() : "";
                String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
                
                String escapedDescription = description
                    .replace("\"", "\"\"")
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .replace(",", ";");
                
                String escapedMerchant = merchantName != null ? merchantName.replace("\"", "\"\"").replace(",", ";") : "";
                String escapedUser = userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";
                
                csv.append(lineNumber).append(",")
                    .append(dateStr).append(",")
                    .append("\"").append(escapedDescription).append("\",")
                    .append("\"").append(escapedMerchant).append("\",")
                    .append("\"").append(escapedUser).append("\",")
                    .append(amountStr).append("\n");
            }
        }
        
        return csv.toString();
    }
    
    /**
     * Export Schedule B (Interest and Dividends) format
     */
    public String exportToScheduleB(TaxExportResult result, int year) {
        StringBuilder csv = new StringBuilder();
        
        csv.append("Schedule B - Interest and Dividends\n");
        csv.append("Tax Year: ").append(year).append("\n");
        csv.append("Generated: ").append(LocalDate.now()).append("\n\n");
        
        TaxSummary summary = result.getSummary();
        
        csv.append("SUMMARY\n");
        csv.append("Total Interest Income,").append(summary.getTotalInterest()).append("\n");
        csv.append("Total Dividend Income,").append(summary.getTotalDividends()).append("\n");
        csv.append("\n");
        
        // Part I: Interest Income
        csv.append("PART I - INTEREST INCOME\n");
        csv.append("Date,Description,Merchant,User,Amount\n");
        
        List<TaxTransaction> interestTransactions = result.getTransactionsByCategory()
            .getOrDefault("INTEREST", Collections.emptyList());
        
        for (TaxTransaction tx : interestTransactions) {
            if (tx == null) continue;
            
            String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
            String description = tx.getDescription() != null ? tx.getDescription() : "";
            String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
            String userName = tx.getUserName() != null ? tx.getUserName() : "";
            String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
            
            String escapedDescription = description
                .replace("\"", "\"\"")
                .replace("\n", " ")
                .replace(",", ";");
            
            String escapedMerchant = merchantName != null ? merchantName.replace("\"", "\"\"").replace(",", ";") : "";
            String escapedUser = userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";
            
            csv.append(dateStr).append(",")
                .append("\"").append(escapedDescription).append("\",")
                .append("\"").append(escapedMerchant).append("\",")
                .append("\"").append(escapedUser).append("\",")
                .append(amountStr).append("\n");
        }
        
        csv.append("\n");
        
        // Part II: Dividend Income
        csv.append("PART II - DIVIDEND INCOME\n");
        csv.append("Date,Description,Merchant,User,Amount\n");
        
        List<TaxTransaction> dividendTransactions = result.getTransactionsByCategory()
            .getOrDefault("DIVIDEND", Collections.emptyList());
        
        for (TaxTransaction tx : dividendTransactions) {
            if (tx == null) continue;
            
            String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
            String description = tx.getDescription() != null ? tx.getDescription() : "";
            String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
            String userName = tx.getUserName() != null ? tx.getUserName() : "";
            String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
            
            String escapedDescription = description
                .replace("\"", "\"\"")
                .replace("\n", " ")
                .replace(",", ";");
            
            String escapedMerchant = merchantName != null ? merchantName.replace("\"", "\"\"").replace(",", ";") : "";
            String escapedUser = userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";
            
            csv.append(dateStr).append(",")
                .append("\"").append(escapedDescription).append("\",")
                .append("\"").append(escapedMerchant).append("\",")
                .append("\"").append(escapedUser).append("\",")
                .append(amountStr).append("\n");
        }
        
        return csv.toString();
    }
    
    /**
     * Export Schedule D (Capital Gains and Losses) format
     */
    public String exportToScheduleD(TaxExportResult result, int year) {
        StringBuilder csv = new StringBuilder();
        
        csv.append("Schedule D - Capital Gains and Losses\n");
        csv.append("Tax Year: ").append(year).append("\n");
        csv.append("Generated: ").append(LocalDate.now()).append("\n\n");
        
        TaxSummary summary = result.getSummary();
        
        csv.append("SUMMARY\n");
        csv.append("Total Capital Gains,").append(summary.getTotalCapitalGains()).append("\n");
        csv.append("Total Capital Losses,").append(summary.getTotalCapitalLosses()).append("\n");
        csv.append("Net Capital Gain/Loss,").append(summary.getTotalCapitalGains().subtract(summary.getTotalCapitalLosses())).append("\n");
        csv.append("\n");
        
        // Part I: Short-Term Capital Gains and Losses
        csv.append("PART I - SHORT-TERM CAPITAL GAINS AND LOSSES\n");
        csv.append("Date,Description,Merchant,User,Amount,Type\n");
        
        List<TaxTransaction> capitalGainTransactions = result.getTransactionsByCategory()
            .getOrDefault("CAPITAL_GAIN", Collections.emptyList());
        List<TaxTransaction> capitalLossTransactions = result.getTransactionsByCategory()
            .getOrDefault("CAPITAL_LOSS", Collections.emptyList());
        
        // Combine and sort by date
        List<TaxTransaction> allCapitalTransactions = new ArrayList<>();
        allCapitalTransactions.addAll(capitalGainTransactions);
        allCapitalTransactions.addAll(capitalLossTransactions);
        allCapitalTransactions.sort((a, b) -> {
            if (a.getDate() == null || b.getDate() == null) return 0;
            return a.getDate().compareTo(b.getDate());
        });
        
        for (TaxTransaction tx : allCapitalTransactions) {
            if (tx == null) continue;
            
            String dateStr = tx.getDate() != null ? tx.getDate().toString() : "";
            String description = tx.getDescription() != null ? tx.getDescription() : "";
            String merchantName = tx.getMerchantName() != null ? tx.getMerchantName() : "";
            String userName = tx.getUserName() != null ? tx.getUserName() : "";
            String amountStr = tx.getAmount() != null ? tx.getAmount().toString() : "0.00";
            String type = tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) > 0 ? "GAIN" : "LOSS";
            
            String escapedDescription = description
                .replace("\"", "\"\"")
                .replace("\n", " ")
                .replace(",", ";");
            
            String escapedMerchant = merchantName != null ? merchantName.replace("\"", "\"\"").replace(",", ";") : "";
            String escapedUser = userName != null ? userName.replace("\"", "\"\"").replace(",", ";") : "";
            
            csv.append(dateStr).append(",")
                .append("\"").append(escapedDescription).append("\",")
                .append("\"").append(escapedMerchant).append("\",")
                .append("\"").append(escapedUser).append("\",")
                .append(amountStr).append(",")
                .append(type).append("\n");
        }
        
        return csv.toString();
    }
    
    /**
     * Get Schedule A line number for a tax category
     */
    private String getScheduleALineNumber(String category) {
        switch (category) {
            case "STATE_TAX": return "5a";
            case "PROPERTY_TAX": return "5b";
            case "LOCAL_TAX": return "5c";
            case "MORTGAGE_INTEREST": return "8a";
            case "CHARITY": return "11";
            case "MEDICAL": return "1";
            default: return "Other";
        }
    }
    
}

