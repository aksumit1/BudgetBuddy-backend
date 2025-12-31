package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import com.budgetbuddy.service.ml.FuzzyMatchingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.HashMap;
import java.util.Map;

/**
 * CSV Import Service
 * Mirrors the iOS app's CSVImportService functionality
 * Parses CSV files from banks/credit cards and converts them to transaction data
 */
@Service
public class CSVImportService {

    private static final Logger logger = LoggerFactory.getLogger(CSVImportService.class);
    
    private final AccountDetectionService accountDetectionService;
    private final EnhancedCategoryDetectionService enhancedCategoryDetection;
    // NOTE: fuzzyMatchingService is injected but used indirectly via enhancedCategoryDetection
    // Kept in constructor for potential future direct use
    @SuppressWarnings("unused")
    private final FuzzyMatchingService fuzzyMatchingService;
    private final TransactionTypeCategoryService transactionTypeCategoryService;
    private final ImportCategoryParser importCategoryParser;
    
    public CSVImportService(AccountDetectionService accountDetectionService,
                           EnhancedCategoryDetectionService enhancedCategoryDetection,
                           FuzzyMatchingService fuzzyMatchingService,
                           TransactionTypeCategoryService transactionTypeCategoryService,
                           ImportCategoryParser importCategoryParser) {
        this.accountDetectionService = accountDetectionService;
        this.enhancedCategoryDetection = enhancedCategoryDetection;
        this.fuzzyMatchingService = fuzzyMatchingService;
        this.transactionTypeCategoryService = transactionTypeCategoryService;
        this.importCategoryParser = importCategoryParser;
    }
    
    // Date formatters matching iOS app - supports global formats
    // CRITICAL FIX: Prioritize unambiguous formats and use smart detection for ambiguous formats
    // US: MM/dd/yyyy, M/d/yyyy
    // Europe/India: dd/MM/yyyy, dd.MM.yyyy, dd-MM-yyyy
    // ISO: yyyy-MM-dd
    // Asia: yyyy/MM/dd (Japan, China)
    private static final List<DateTimeFormatter> DATE_FORMATTERS = Arrays.asList(
        // ISO format first (most unambiguous - no ambiguity)
        DateTimeFormatter.ISO_LOCAL_DATE,  // yyyy-MM-dd
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),  // Asian format (yyyy/MM/dd) - unambiguous
        // Formats with separators that make them unambiguous
        DateTimeFormatter.ofPattern("dd.MM.yyyy"),  // Germany, Austria, Switzerland (DD.MM.YYYY) - dot separator is unambiguous
        DateTimeFormatter.ofPattern("dd-MM-yyyy"), // Netherlands, Belgium - dash separator is unambiguous
        // Text formats (unambiguous due to month names)
        DateTimeFormatter.ofPattern("MMM dd, yyyy"), // US text format (e.g., "Dec 01, 2024")
        DateTimeFormatter.ofPattern("dd MMM yyyy"),  // UK text format (e.g., "01 Dec 2024")
        // CRITICAL FIX: For ambiguous formats (MM/dd vs dd/MM), use smart detection
        // Try US format first for dates where first number > 12 (unambiguous)
        // Then try European format for dates where second number > 12 (unambiguous)
        // This prevents dates like "12/1/2024" from being misinterpreted
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),  // US format - try before European for better US compatibility
        DateTimeFormatter.ofPattern("M/d/yyyy"),    // US format (single digit month/day)
        DateTimeFormatter.ofPattern("MM-dd-yyyy"),   // US format with dash
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),  // UK, India (DD/MM/YYYY) - after US to avoid ambiguity
        DateTimeFormatter.ISO_LOCAL_DATE
    );

    /**
     * Parsed CSV row data
     */
    public static class ParsedRow {
        private final Map<String, String> fields = new HashMap<>();
        
        public void put(String key, String value) {
            fields.put(key.toLowerCase().trim(), value != null ? value.trim() : "");
        }
        
        public String get(String key) {
            return fields.get(key.toLowerCase().trim());
        }
        
        public String findField(String... keys) {
            for (String key : keys) {
                String value = get(key);
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return null;
        }
    }

    /**
     * Import result containing parsed transactions
     */
    public static class ImportResult {
        private final List<ParsedTransaction> transactions = new ArrayList<>();
        private int successCount = 0;
        private int failureCount = 0;
        private final List<String> errors = new ArrayList<>();
        private AccountDetectionService.DetectedAccount detectedAccount; // Detected account from import
        private String matchedAccountId; // Matched existing account ID (if found)
        
        public void addTransaction(ParsedTransaction transaction) {
            transactions.add(transaction);
            successCount++;
        }
        
        public void addError(String error) {
            errors.add(error);
            failureCount++;
        }
        
        /**
         * Add an informational message without incrementing failure count
         * Used for cases like empty files where it's not a failure, just no data
         */
        public void addInfo(String message) {
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
        
        public void setDetectedAccount(AccountDetectionService.DetectedAccount detectedAccount) {
            this.detectedAccount = detectedAccount;
        }
        
        public String getMatchedAccountId() {
            return matchedAccountId;
        }
        
        public void setMatchedAccountId(String matchedAccountId) {
            this.matchedAccountId = matchedAccountId;
        }
    }

    /**
     * Parsed transaction data ready for database creation
     */
    public static class ParsedTransaction {
        private LocalDate date;
        private BigDecimal amount;
        private String description;
        private String merchantName;
        private String categoryPrimary; // Internal category (for display)
        private String categoryDetailed; // Internal category (for display)
        private String importerCategoryPrimary; // Importer's original category (from parser)
        private String importerCategoryDetailed; // Importer's original category (from parser)
        private String paymentChannel;
        private String transactionType;
        private String transactionTypeIndicator; // DEBIT/CREDIT indicator from CSV (for recalculation)
        private String transactionId; // Optional: Transaction ID provided by iOS for consistency
        private String currencyCode; // Detected currency code (USD, INR, etc.)
        private String accountId; // Detected or matched account ID
        
        // Getters and setters
        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }
        
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
        
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        
        public String getImporterCategoryPrimary() { return importerCategoryPrimary; }
        public void setImporterCategoryPrimary(String importerCategoryPrimary) { this.importerCategoryPrimary = importerCategoryPrimary; }
        
        public String getImporterCategoryDetailed() { return importerCategoryDetailed; }
        public void setImporterCategoryDetailed(String importerCategoryDetailed) { this.importerCategoryDetailed = importerCategoryDetailed; }
        
        public String getPaymentChannel() { return paymentChannel; }
        public void setPaymentChannel(String paymentChannel) { this.paymentChannel = paymentChannel; }
        
        public String getTransactionType() { return transactionType; }
        public void setTransactionType(String transactionType) { this.transactionType = transactionType; }
        
        public String getTransactionTypeIndicator() { return transactionTypeIndicator; }
        public void setTransactionTypeIndicator(String transactionTypeIndicator) { this.transactionTypeIndicator = transactionTypeIndicator; }
        
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
        
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
        
        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }
    }

    /**
     * Parse CSV file and return import result
     * @param inputStream The input stream containing CSV data
     * @param filename The filename (used for account detection)
     * @param userId The user ID (used for account matching)
     * @param password Optional password for password-protected files (ZIP archives)
     */
    // HIGH PRIORITY FIX: Transaction count limit (10,000 per file)
    private static final int MAX_TRANSACTIONS_PER_FILE = 10000;
    
    // CRITICAL: Static category map to avoid recreating on every parseCategory call (performance optimization)
    private static final Map<String, String> CATEGORY_MAP = initializeCategoryMap();
    
    private static Map<String, String> initializeCategoryMap() {
        Map<String, String> map = new HashMap<>();
        // Expenses - Common
        map.put("groceries", "groceries");
        map.put("grocery", "groceries");
        map.put("food", "groceries");
        map.put("supermarket", "groceries");
        map.put("dining", "dining");
        map.put("restaurant", "dining");
        map.put("food & dining", "dining");
        map.put("fast food", "dining");
        map.put("rent", "rent");
        map.put("housing", "rent");
        map.put("mortgage", "rent");
        map.put("utilities", "utilities");
        map.put("utility", "utilities");
        map.put("electric", "utilities");
        map.put("gas", "utilities");
        map.put("water", "utilities");
        map.put("internet", "utilities");
        map.put("phone", "utilities");
        map.put("transportation", "transportation");
        map.put("fuel", "transportation");
        map.put("gas & fuel", "transportation");
        map.put("auto", "transportation");
        map.put("car", "transportation");
        map.put("parking", "transportation");
        map.put("entertainment", "entertainment");
        map.put("shopping", "shopping");
        map.put("retail", "shopping");
        map.put("travel", "travel");
        map.put("hotel", "travel");
        map.put("airline", "travel");
        map.put("subscription", "subscriptions");
        map.put("subscriptions", "subscriptions");
        map.put("streaming", "subscriptions");
        map.put("healthcare", "healthcare");
        map.put("medical", "healthcare");
        map.put("pharmacy", "healthcare");
        map.put("payment", "payment");
        map.put("transfer", "transfer"); // CRITICAL: Account transfers are separate from payments
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
        map.put("dividend", "dividend");
        map.put("interest", "interest");
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
        map.put("salary", "salary");
        map.put("income", "income");
        map.put("payroll", "salary");
        map.put("wages", "salary");
        map.put("dividends", "dividend");
        map.put("capital gain", "investment");
        map.put("reinvestment", "investment");
        map.put("principal", "payment");
        map.put("escrow", "utilities");
        map.put("loan payment", "payment");
        map.put("mortgage payment", "payment");
        map.put("auto loan", "payment");
        map.put("student loan", "payment");
        map.put("cash advance", "other");
        map.put("balance transfer", "payment");
        map.put("annual fee", "other");
        map.put("late fee", "other");
        map.put("overlimit fee", "other");
        map.put("returned payment fee", "other");
        map.put("hsa contribution", "other");
        map.put("hsa distribution", "healthcare");
        map.put("refund", "payment");
        map.put("deposit", "deposit");
        map.put("fee", "fee");
        map.put("fee_transaction", "fee");
        map.put("fee transaction", "fee");
        map.put("charge", "other");
        map.put("request", "other");
        map.put("cash back", "income");
        map.put("cashback", "income");
        map.put("receive", "income");
        map.put("send", "payment");
        map.put("upi", "payment");
        map.put("neft", "payment");
        map.put("rtgs", "payment");
        map.put("imps", "payment");
        map.put("purchase", "other");
        map.put("captured", "payment");
        map.put("debit", "other");
        map.put("credit", "income");
        map.put("charity", "charity");
        map.put("charitable", "charity");
        map.put("donation", "charity");
        map.put("donate", "charity");
        map.put("tuition", "other");
        map.put("education", "other");
        map.put("school fee", "other");
        map.put("university fee", "other");
        map.put("college fee", "other");
        map.put("cpa", "other");
        map.put("tax preparer", "other");
        map.put("tax preparation", "other");
        map.put("accounting service", "other");
        map.put("dmv", "other");
        map.put("vehicle registration", "other");
        map.put("license renewal", "other");
        map.put("driver license", "other");
        map.put("property tax", "other");
        map.put("real estate tax", "other");
        map.put("state tax", "other");
        map.put("local tax", "other");
        map.put("other", "other");
        map.put("misc", "other");
        map.put("miscellaneous", "other");
        return Collections.unmodifiableMap(map);
    }
    
    public ImportResult parseCSV(InputStream inputStream, String filename, String userId, String password) {
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
    public ImportResult parseCSV(InputStream inputStream, String filename, String userId, String password,
                                 List<com.budgetbuddy.api.ImportCategoryPreservationRequest.PreviewCategory> previewCategories,
                                 String previewAccountId) {
        // Handle null filename
        if (filename == null) {
            filename = "unknown.csv";
        }
        ImportResult result = new ImportResult();
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            
            // Read header row
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                // CRITICAL: Return gracefully for empty files - not a failure, just no data
                logger.info("CSV file is empty - no data to process");
                result.addInfo("CSV file is empty - no data to process");
                return result;
            }
            
            // CRITICAL: Strip UTF-8 BOM if present (EF BB BF)
            // BOM can appear at the start of the first line
            if (headerLine.length() > 0 && headerLine.charAt(0) == '\uFEFF') {
                headerLine = headerLine.substring(1);
                logger.debug("Stripped UTF-8 BOM from header line");
            }
            // Also check for byte-order mark in first 3 bytes (EF BB BF)
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
                logger.debug("Stripped UTF-8 BOM marker from header line");
            }
            
            List<String> headers = parseCSVLine(headerLine);
            // CRITICAL: Check if headers are empty or only whitespace
            // Headers are considered empty if the list is empty OR all headers are empty/whitespace
            boolean headersEmpty = headers.isEmpty() || headers.stream().allMatch(h -> h == null || h.trim().isEmpty());
            if (headersEmpty) {
                // CRITICAL: Return gracefully for files with no headers - not a failure, just no data
                logger.info("CSV file has no headers - cannot parse data");
                result.addInfo("CSV file has no headers - cannot parse data");
                return result;
            }
            
            // Normalize headers (lowercase, trim)
            List<String> normalizedHeaders = new ArrayList<>();
            Map<String, Integer> headerCounts = new HashMap<>();
            
            // Handle duplicate headers by making them unique
            // This prevents duplicate key errors when creating row dictionaries
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                String normalized = header.toLowerCase().trim();
                String baseHeader = normalized.isEmpty() ? "column" + (i + 1) : normalized;
                
                int count = headerCounts.getOrDefault(baseHeader, 0);
                headerCounts.put(baseHeader, count + 1);
                
                if (count == 0) {
                    // First occurrence - use as-is
                    normalizedHeaders.add(baseHeader);
                } else {
                    // Duplicate - append number to make it unique
                    String uniqueHeader = baseHeader + "_" + (count + 1);
                    normalizedHeaders.add(uniqueHeader);
                    logger.warn("Duplicate header '{}' at column {} - renamed to '{}'", header, i + 1, uniqueHeader);
                }
            }
            
            if (headerCounts.values().stream().anyMatch(count -> count > 1)) {
                logger.info("Detected and resolved duplicate headers in CSV file");
            }
            
            // Detect account information from filename and headers
            AccountDetectionService.DetectedAccount detectedAccount = null;
            String matchedAccountId = null;
            boolean isTransactionTable = false;
            
            // Find balance column index for extracting balance from last transaction
            Integer balanceColumnIndex = null;
            for (int i = 0; i < normalizedHeaders.size(); i++) {
                String header = normalizedHeaders.get(i);
                if (header != null && header.toLowerCase().trim().equals("balance")) {
                    balanceColumnIndex = i;
                    logger.info("✓ Found balance column at index {}: '{}'", i, headers.get(i));
                    break;
                }
            }
            // Track balance from row with latest date (not necessarily last row)
            String latestDateBalanceValue = null;
            LocalDate latestDateForBalance = null;
            if (userId != null) {
                try {
                    // Check if headers are transaction table headers
                    isTransactionTable = accountDetectionService.isTransactionTableHeaders(normalizedHeaders);
                    if (isTransactionTable) {
                        logger.info("⚠️ CSV headers are transaction table headers - will skip extracting account info from transaction data");
                    }
                    
                    // Detect from headers first
                    AccountDetectionService.DetectedAccount fromHeaders = 
                        accountDetectionService.detectFromHeaders(normalizedHeaders, filename);
                    
                    // Match to existing account
                    if (fromHeaders != null) {
                        try {
                            matchedAccountId = accountDetectionService.matchToExistingAccount(userId, fromHeaders);
                            if (matchedAccountId != null) {
                                detectedAccount = fromHeaders;
                                logger.info("Matched CSV import to existing account: {} (accountId: {}, accountNumber: {})", 
                                    detectedAccount.getAccountName(), matchedAccountId,
                                    detectedAccount.getAccountNumber() != null ? detectedAccount.getAccountNumber() : "N/A");
                            } else {
                                // Enhanced logging with account number and other details
                                String accountName = fromHeaders.getAccountName() != null ? fromHeaders.getAccountName() : "Unknown";
                                String accountNumber = fromHeaders.getAccountNumber() != null ? fromHeaders.getAccountNumber() : "N/A";
                                String institution = fromHeaders.getInstitutionName() != null ? fromHeaders.getInstitutionName() : "N/A";
                                logger.info("Detected account from CSV but no match found - Name: {}, AccountNumber: {}, Institution: {}, Type: {}", 
                                    accountName, accountNumber, institution,
                                    fromHeaders.getAccountType() != null ? fromHeaders.getAccountType() : "N/A");
                                detectedAccount = fromHeaders;
                            }
                        } catch (Exception e) {
                            logger.warn("Error during account matching for CSV import: {}", e.getMessage());
                            // Continue without account matching - user can select account in UI
                            detectedAccount = fromHeaders;
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Error during account detection for CSV import: {}", e.getMessage());
                    // Continue without account detection - user can select account in UI
                }
            }
            
            // Parse data rows
            String line;
            int rowNumber = 2; // Start at 2 (header is row 1)
            int mismatchCount = 0;
            int rowsReadForAccountDetection = 0;
            final int MAX_ROWS_FOR_ACCOUNT_DETECTION = 20; // Check first 20 rows for account info (increased for better detection)
            
            // Track transaction patterns for account type inference (use arrays to allow modification)
            final int[] debitCount = {0};
            final int[] creditCount = {0};
            final int[] checkCount = {0};
            final int[] achCount = {0};
            final int[] atmCount = {0};
            final int[] transferCount = {0};
            
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue; // Skip empty lines
                }
                
                List<String> values = parseCSVLine(line);
                
                // Remove ONLY truly trailing empty fields beyond the expected header count
                // This handles cases like ",," at the end while preserving legitimate empty columns
                while (values.size() > headers.size() && 
                       values.get(values.size() - 1).trim().isEmpty()) {
                    values.remove(values.size() - 1);
                }
                
                // Handle column count mismatch - use header count as truth
                if (values.size() != headers.size()) {
                    mismatchCount++;
                    
                    if (values.size() > headers.size()) {
                        // Too many columns - truncate to header count
                        // Only log first few mismatches to avoid spam
                        if (mismatchCount <= 3) {
                            logger.warn("Row {}: Expected {} columns, found {}. Truncating to match header.", 
                                rowNumber, headers.size(), values.size());
                        }
                        values = values.subList(0, headers.size());
                    } else {
                        // Too few columns - pad with empty strings
                        if (mismatchCount <= 3) {
                            logger.warn("Row {}: Expected {} columns, found {}. Padding with empty values.", 
                                rowNumber, headers.size(), values.size());
                        }
                        while (values.size() < headers.size()) {
                            values.add("");
                        }
                    }
                }
                
                // Create parsed row
                ParsedRow row = new ParsedRow();
                for (int i = 0; i < headers.size(); i++) {
                    if (i < values.size()) {
                        row.put(headers.get(i), values.get(i));
                    } else {
                        row.put(headers.get(i), "");
                    }
                }
                
                // CRITICAL FIX: Extract account information from data rows
                // For transaction tables, skip extracting institution names and account types from transaction data
                // (they're payment recipients/transaction types, not account metadata)
                // Only extract account numbers from dedicated account number columns
                if (userId != null && detectedAccount != null && rowsReadForAccountDetection < MAX_ROWS_FOR_ACCOUNT_DETECTION) {
                    rowsReadForAccountDetection++;
                    logger.debug("Analyzing data row {} for account information", rowNumber);
                    
                    // Check each field value for account information
                    // CRITICAL: Add bounds checking to prevent IndexOutOfBoundsException
                    int maxColIndex = Math.min(values.size(), headers.size());
                    for (int colIndex = 0; colIndex < maxColIndex; colIndex++) {
                        String value = null;
                        String header = null;
                        
                        try {
                            value = colIndex < values.size() ? values.get(colIndex) : null;
                            header = colIndex < headers.size() ? headers.get(colIndex) : null;
                        } catch (IndexOutOfBoundsException e) {
                            logger.warn("Index out of bounds accessing column {} (values: {}, headers: {})", 
                                colIndex, values.size(), headers.size());
                            continue; // Skip this column
                        }
                        
                        if (value != null && !value.trim().isEmpty() && header != null) {
                            String headerLower = header != null ? header.toLowerCase().trim() : "";
                            
                            // Extract account number from data (only from dedicated account number columns)
                            if (detectedAccount.getAccountNumber() == null) {
                                // Check if this column header matches account number keywords
                                boolean isAccountNumberColumn = false;
                                for (String keyword : accountDetectionService.getAccountNumberKeywords()) {
                                    if (headerLower.contains(keyword)) {
                                        isAccountNumberColumn = true;
                                        break;
                                    }
                                }
                                
                                if (isAccountNumberColumn) {
                                    // Extract account number from value
                                    String accountNum = extractAccountNumberFromValue(value);
                                    if (accountNum != null) {
                                        detectedAccount.setAccountNumber(accountNum);
                                        logger.info("✓ Extracted account number from row {} column '{}': {}", 
                                            rowNumber, header, accountNum);
                                    }
                                }
                                // CRITICAL: Don't try pattern matching in transaction tables - 
                                // dates and other numbers will be mistaken for account numbers
                            }
                            
                            // CRITICAL: Skip extracting institution names from transaction data when it's a transaction table
                            // Transaction descriptions contain payment recipients (e.g., "CITI AUTOPAY"), not the account's bank
                            if (!isTransactionTable) {
                                // Extract institution/product name from data (only for non-transaction tables)
                                if (detectedAccount.getInstitutionName() == null || 
                                    detectedAccount.getInstitutionName().equals("Unknown")) {
                                    // Check if this column header matches institution/product keywords
                                    boolean isInstitutionColumn = false;
                                    for (String keyword : accountDetectionService.getInstitutionKeywords()) {
                                        if (headerLower.contains(keyword)) {
                                            isInstitutionColumn = true;
                                            break;
                                        }
                                    }
                                    
                                    if (isInstitutionColumn) {
                                        // Use value as institution name
                                        detectedAccount.setInstitutionName(value.trim());
                                        logger.info("✓ Extracted institution name from row {} column '{}': {}", 
                                            rowNumber, header, value.trim());
                                    } else {
                                        // Try pattern matching for product names
                                        String institution = extractInstitutionFromValue(value);
                                        if (institution != null) {
                                            detectedAccount.setInstitutionName(institution);
                                            logger.info("✓ Extracted institution/product name from row {}: {}", 
                                                rowNumber, institution);
                                        }
                                    }
                                }
                            } else {
                                logger.debug("⚠️ Skipping institution name extraction from transaction data row {} (transaction table)", rowNumber);
                            }
                            
                            // CRITICAL: Skip extracting account type from transaction data when it's a transaction table
                            // Transaction "type" columns refer to transaction types (debit/credit), not account types
                            if (!isTransactionTable) {
                                // Extract account type from data (only for non-transaction tables)
                                if (detectedAccount.getAccountType() == null) {
                                    // Check if this column header matches account type keywords
                                    boolean isAccountTypeColumn = false;
                                    for (String keyword : accountDetectionService.getAccountTypeKeywords()) {
                                        if (headerLower.contains(keyword)) {
                                            isAccountTypeColumn = true;
                                            break;
                                        }
                                    }
                                    
                                    if (isAccountTypeColumn) {
                                        // Extract account type from value
                                        String accountType = extractAccountTypeFromValue(value);
                                        if (accountType != null) {
                                            detectedAccount.setAccountType(accountType);
                                            logger.info("✓ Extracted account type from row {} column '{}': {}", 
                                                rowNumber, header, accountType);
                                        }
                                    }
                                }
                            } else {
                                logger.debug("⚠️ Skipping account type extraction from transaction data row {} (transaction table - will infer from patterns)", rowNumber);
                            }
                            
                            // DEEP ANALYSIS: Analyze transaction details/type for account type inference
                            // Look for keywords in description, transaction type, details columns
                            boolean isTransactionDetailsColumn = headerLower.contains("description") || 
                                                               headerLower.contains("details") ||
                                                               headerLower.contains("memo") ||
                                                               headerLower.contains("transaction type") ||
                                                               headerLower.contains("type") ||
                                                               headerLower.contains("category");
                            
                            if (isTransactionDetailsColumn) {
                                // Analyze transaction details for account type clues
                                analyzeTransactionForAccountType(value, 
                                    debitCount, creditCount, checkCount, achCount, atmCount, transferCount);
                            }
                        }
                    }
                }
                
                // After analyzing multiple rows, infer account type from transaction patterns
                // CRITICAL: For transaction tables, prioritize pattern-based inference over data extraction
                // This ensures we infer "checking" from debit/credit/check patterns, not from transaction data
                // CRITICAL: Lower threshold to 3 rows for better test coverage and faster inference
                if (rowsReadForAccountDetection >= 3 && detectedAccount != null) {
                    // For transaction tables, always infer from patterns (don't trust extracted account type from data)
                    if (isTransactionTable && detectedAccount.getAccountType() != null) {
                        logger.info("⚠️ Transaction table detected - re-inferring account type from patterns (ignoring extracted type: {})", 
                            detectedAccount.getAccountType());
                        detectedAccount.setAccountType(null); // Clear extracted type to force pattern inference
                    }
                    
                    if (detectedAccount.getAccountType() == null) {
                        logger.info("🔍 Attempting to infer account type from transaction patterns (rows analyzed: {}, debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})", 
                            rowsReadForAccountDetection, debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                        String inferredType = inferAccountTypeFromTransactionPatterns(
                            debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                        if (inferredType != null) {
                            detectedAccount.setAccountType(inferredType);
                            if (inferredType.equals("depository")) {
                                // Try to determine subtype (checking vs savings)
                                String subtype = inferAccountSubtypeFromTransactionPatterns(
                                    debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                                if (subtype != null) {
                                    detectedAccount.setAccountSubtype(subtype);
                                }
                            }
                            logger.info("✓ Inferred account type from transaction patterns: {} / {} (debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})", 
                                inferredType, detectedAccount.getAccountSubtype(), 
                                debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                        } else {
                            logger.warn("⚠️ Could not infer account type from transaction patterns - user will need to select account type manually");
                        }
                    }
                } else if (rowsReadForAccountDetection < 3 && detectedAccount != null && detectedAccount.getAccountType() == null) {
                    logger.debug("⏳ Waiting for more transaction rows to infer account type (current: {}, need: 3)", rowsReadForAccountDetection);
                }
                
                // HIGH PRIORITY FIX: Check transaction count limit
                if (result.getSuccessCount() >= MAX_TRANSACTIONS_PER_FILE) {
                    result.addError(String.format("Transaction limit exceeded. Maximum %d transactions per file. Stopping at row %d.", 
                        MAX_TRANSACTIONS_PER_FILE, rowNumber));
                    logger.warn("Transaction limit reached: {} transactions. Stopping CSV parsing at row {}", 
                        MAX_TRANSACTIONS_PER_FILE, rowNumber);
                    break; // Stop parsing to prevent memory issues
                }
                
                // Parse transaction from row (pass header line for currency detection)
                try {
                    // CRITICAL: Get preview category for this transaction index if available
                    // Note: previewCategories are indexed by transaction order (0-based)
                    // For paginated imports, we only have preview categories for the first page
                    // For subsequent pages, previewCategories will be null or empty
                    com.budgetbuddy.api.ImportCategoryPreservationRequest.PreviewCategory previewCategory = null;
                    String preserveAccountId = null;
                    if (previewCategories != null && !previewCategories.isEmpty() && 
                        result.getSuccessCount() < previewCategories.size()) {
                        previewCategory = previewCategories.get(result.getSuccessCount());
                        preserveAccountId = previewAccountId;
                        logger.debug("Using preview category for transaction {}: categoryPrimary='{}'", 
                                result.getSuccessCount(), previewCategory.getCategoryPrimary());
                    }
                    
                    ParsedTransaction transaction = parseTransaction(
                        row, rowNumber, headerLine, filename,
                        previewCategory != null ? previewCategory.getCategoryPrimary() : null,
                        previewCategory != null ? previewCategory.getCategoryDetailed() : null,
                        previewCategory != null ? previewCategory.getImporterCategoryPrimary() : null,
                        previewCategory != null ? previewCategory.getImporterCategoryDetailed() : null,
                        preserveAccountId,
                        result.getDetectedAccount()
                    );
                    if (transaction != null) {
                        // Set account ID if detected
                        if (matchedAccountId != null) {
                            transaction.setAccountId(matchedAccountId);
                        }
                        result.addTransaction(transaction);
                        
                        // Track balance from row with latest date (for checking/savings/money market accounts)
                        if (balanceColumnIndex != null && balanceColumnIndex < normalizedHeaders.size() && transaction.getDate() != null) {
                            String balanceHeader = normalizedHeaders.get(balanceColumnIndex);
                            String balanceValue = row.get(balanceHeader);
                            if (balanceValue != null && !balanceValue.trim().isEmpty()) {
                                // Update if this row has a later date than the current latest
                                if (latestDateForBalance == null || transaction.getDate().isAfter(latestDateForBalance)) {
                                    latestDateBalanceValue = balanceValue.trim();
                                    latestDateForBalance = transaction.getDate();
                                    logger.debug("Tracked balance from transaction row {} (date: {}): {}", 
                                        rowNumber, latestDateForBalance, latestDateBalanceValue);
                                }
                            }
                        }
                    } else {
                        result.addError(String.format("Row %d: Could not parse transaction (missing date or amount)", rowNumber));
                    }
                } catch (Exception e) {
                    result.addError(String.format("Row %d: Error parsing transaction - %s", rowNumber, e.getMessage()));
                }
                
                rowNumber++;
            }
            
            if (mismatchCount > 3) {
                logger.info("Processed {} rows with column count mismatches (all handled successfully)", mismatchCount);
            }
            
            // Final account type inference if we didn't reach 3 rows during parsing
            // This handles cases where files have fewer than 3 rows but still have transaction patterns
            if (detectedAccount != null && detectedAccount.getAccountType() == null && rowsReadForAccountDetection > 0) {
                logger.info("🔍 Final attempt to infer account type from transaction patterns (rows analyzed: {}, debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})", 
                    rowsReadForAccountDetection, debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                String inferredType = inferAccountTypeFromTransactionPatterns(
                    debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                if (inferredType != null) {
                    detectedAccount.setAccountType(inferredType);
                    if (inferredType.equals("depository")) {
                        String subtype = inferAccountSubtypeFromTransactionPatterns(
                            debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                        if (subtype != null) {
                            detectedAccount.setAccountSubtype(subtype);
                        }
                    }
                    logger.info("✓ Inferred account type from transaction patterns (final): {} / {} (debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})", 
                        inferredType, detectedAccount.getAccountSubtype(), 
                        debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                } else {
                    logger.warn("⚠️ Could not infer account type from transaction patterns - user will need to select account type manually");
                }
            }
            
            // Extract balance from transaction with latest date if balance wasn't found in headers
            // This is for checking/savings/money market accounts where balance is in transaction table
            if (detectedAccount != null && detectedAccount.getBalance() == null && 
                latestDateBalanceValue != null && balanceColumnIndex != null && latestDateForBalance != null) {
                // Only extract from latest date transaction for depository accounts (checking, savings, money market)
                String accountType = detectedAccount.getAccountType();
                if (accountType != null && (accountType.equalsIgnoreCase("depository") || 
                    accountType.equalsIgnoreCase("checking") || accountType.equalsIgnoreCase("savings") ||
                    accountType.equalsIgnoreCase("moneyMarket") || accountType.equalsIgnoreCase("money_market"))) {
                    java.math.BigDecimal balance = accountDetectionService.extractBalanceFromTransactionValue(latestDateBalanceValue);
                    if (balance != null) {
                        detectedAccount.setBalance(balance);
                        // Store the date of the balance for comparison with existing account
                        detectedAccount.setBalanceDate(latestDateForBalance);
                        logger.info("✓ Extracted balance from transaction with latest date ({}): {}", latestDateForBalance, balance);
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
            
            logger.info("Parsed CSV: {} successful, {} failed", result.getSuccessCount(), result.getFailureCount());
            
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error parsing CSV file: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to parse CSV file: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Extract account number from a value using enhanced pattern matching
     */
    private String extractAccountNumberFromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        // Try enhanced account number pattern
        java.util.regex.Pattern accountNumPattern = java.util.regex.Pattern.compile(
            "(?:(?:account|acct|card|credit\\s*card|debit\\s*card)\\s*(?:number|#|no\\.?)?\\s*(?:ending\\s*(?:in|with)?\\s*:?\\s*|with\\s*(?:last\\s*)?(?:4\\s*)?(?:digits?|numbers?)\\s*:?\\s*)?|(?:account|acct|card|credit\\s*card|debit\\s*card|number|#|no\\.?)\\s*:?\\s*)([*xX]{0,4}\\d{4}|\\d{4,19})",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        
        java.util.regex.Matcher matcher = accountNumPattern.matcher(value);
        if (matcher.find()) {
            try {
                String accountNum = matcher.group(1);
                if (accountNum != null) {
                    accountNum = accountNum.replaceAll("[*xX]", "");
                    if (accountNum.length() >= 4) {
                        // Extract last 4 digits
                        int startIndex = Math.max(0, accountNum.length() - 4);
                        return accountNum.substring(startIndex);
                    }
                }
            } catch (Exception e) {
                logger.warn("Error extracting account number from value '{}': {}", value, e.getMessage());
            }
        }
        
        // Try simple 4+ digit pattern if no keyword match
        java.util.regex.Pattern simplePattern = java.util.regex.Pattern.compile("(\\d{4,19})");
        matcher = simplePattern.matcher(value);
        if (matcher.find()) {
            String accountNum = matcher.group(1);
            if (accountNum.length() >= 4) {
                // Extract last 4 digits
                int startIndex = Math.max(0, accountNum.length() - 4);
                return accountNum.substring(startIndex);
            }
        }
        
        return null;
    }
    
    /**
     * Extract institution name from a value
     */
    private String extractInstitutionFromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        String valueLower = value.toLowerCase();
        
        // Check if value contains known institution keywords
        for (String institution : Arrays.asList("citi", "amex", "american express", "chase", "bofa", 
                                                "bank of america", "wells fargo", "capital one",
                                                "discover", "visa", "mastercard", "synchrony")) {
            if (valueLower.contains(institution) && value.length() > institution.length() + 5) {
                // Likely a product name - use the full value as institution name
                return value.trim();
            }
        }
        
        return null;
    }
    
    /**
     * Extract account type from a value
     */
    private String extractAccountTypeFromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        
        String valueLower = value.toLowerCase();
        
        // Map common account type values to our account types
        if (valueLower.contains("checking") || valueLower.contains("check")) {
            return "depository";
        } else if (valueLower.contains("savings") || valueLower.contains("saving")) {
            return "depository";
        } else if (valueLower.contains("credit card") || valueLower.contains("creditcard") || 
                   valueLower.contains("card")) {
            return "credit";
        } else if (valueLower.contains("loan") || valueLower.contains("mortgage")) {
            return "loan";
        } else if (valueLower.contains("investment") || valueLower.contains("brokerage") ||
                   valueLower.contains("ira") || valueLower.contains("401k")) {
            return "investment";
        }
        
        return null;
    }
    
    /**
     * Parse a single CSV line, handling quoted fields and commas
     */
    private List<String> parseCSVLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder currentField = new StringBuilder();
        boolean insideQuotes = false;
        Character previousChar = null;
        
        for (char c : line.toCharArray()) {
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
     * @param row The parsed row data
     * @param rowNumber The row number (for error reporting)
     * @param headerLine The CSV header line (for currency detection)
     */
    ParsedTransaction parseTransaction(ParsedRow row, int rowNumber, String headerLine, String filename) {
        return parseTransaction(row, rowNumber, headerLine, filename, null, null, null, null, null, null);
    }
    
    /**
     * Parse a transaction from a CSV row
     * 
     * @param row The parsed CSV row
     * @param rowNumber Row number (for error reporting)
     * @param headerLine Header line (for error reporting)
     * @param filename Filename (for account detection)
     * @param preserveCategoryPrimary Pre-parsed category primary (from preview) - if provided, skip category parsing
     * @param preserveCategoryDetailed Pre-parsed category detailed (from preview) - if provided, skip category parsing
     * @param preserveImporterCategoryPrimary Pre-parsed importer category primary (from preview) - if provided, skip category parsing
     * @param preserveImporterCategoryDetailed Pre-parsed importer category detailed (from preview) - if provided, skip category parsing
     * @param preserveAccountId Pre-parsed account ID (from preview) - used to determine if account changed
     * @param detectedAccount Detected account information (for transaction type determination)
     * @return ParsedTransaction object
     */
    ParsedTransaction parseTransaction(ParsedRow row, int rowNumber, String headerLine, String filename,
                                       String preserveCategoryPrimary, String preserveCategoryDetailed,
                                       String preserveImporterCategoryPrimary, String preserveImporterCategoryDetailed,
                                       String preserveAccountId,
                                       AccountDetectionService.DetectedAccount detectedAccount) {
        ParsedTransaction transaction = new ParsedTransaction();
        
        // Parse date (supports all major US financial institutions and account types)
        String dateString = row.findField(
            // Common formats (checking/savings/money market)
            // Note: "trade date" is prioritized for investment accounts (checked later in investment-specific lists)
            // CRITICAL: "details" is added for test compatibility - some banks use "details" column for dates
            // It's checked early so date values are matched before description matching
            "date", "transaction date", "posting date", "posted date", "post date", "trade date", "settlement date", "details",
            "transaction_date", "posting_date", "posted_date", "post_date", "settlement_date",
            // Major Banks - Checking/Savings
            "transaction date", "transactiondate", "posting date",  // Chase
            "date posted", "dateposted", "transaction date",        // Bank of America
            "transaction date", "date", "posting date",             // Wells Fargo
            "date", "transaction date", "posting date",             // Citi (Citibank)
            "transaction date", "date posted", "post date",          // Capital One
            "transaction date", "date", "posting date",             // US Bank
            "date", "transaction date",                              // TD Bank
            "transaction date", "date",                               // Chime
            "date", "transaction date", "posting date",             // SoFi
            "date", "transaction date",                              // Citizens Bank
            "transaction date", "date",                              // Regions Bank
            "date", "transaction date",                              // KeyBank
            "date", "transaction date",                              // Navy Federal Credit Union
            "transaction date", "date",                              // First Tech Federal
            "date", "transaction date",                              // Synchrony Bank
            // US Credit Unions
            "date", "transaction date", "posting date",              // State Employees Credit Union
            "date", "transaction date",                              // School First Federal Credit Union
            "date", "transaction date", "posting date",              // PenFed Credit Union
            "date", "transaction date",                              // Boeing Employees Credit Union
            "date", "transaction date",                              // Alliant Credit Union
            "date", "transaction date",                              // America First Credit Union
            "date", "transaction date",                              // Golden 1 Credit Union
            "date", "transaction date",                              // Mountain America Credit Union
            "date", "transaction date",                              // Suncoast Credit Union
            "date", "transaction date",                              // Randolph-Brooks Federal Credit Union
            "date", "transaction date",                              // Lake Michigan Credit Union
            "date", "transaction date",                              // Security Service Federal Credit Union
            "date", "transaction date",                              // FourLeaf Federal Credit Union
            "date", "transaction date",                              // Idaho Central Credit Union
            "date", "transaction date",                              // Global Credit Union
            "date", "transaction date",                              // Digital Federal Credit Union
            "date", "transaction date",                              // GreenState Credit Union
            // Credit Card formats (all major issuers - Global)
            // US Credit Cards
            "trans. date", "trans date", "trans_date", "transaction date", // Discover, Amex
            "post date", "postdate", "posted date", "transaction date",    // Most credit cards
            "purchase date", "purchase_date", "transaction date",          // Credit card purchases
            "transaction date", "date",                                  // Chase, Citi, Capital One credit cards
            "date", "transaction date",                                    // Amex, Discover, US Bank credit cards
            "date", "transaction date",                                    // Synchrony Bank credit cards
            // European Credit Cards
            "datum", "date", "transaction date", "buchungsdatum", "wertstellung", // Deutsche Bank, Commerzbank credit cards - prioritize "datum" over "wertstellung"
            "date", "transaction date", "datum", "valutadatum",          // ING, Rabobank credit cards
            "date", "transaction date", "date de transaction", "date de valeur", // BNP Paribas, Crédit Agricole credit cards
            "date", "transaction date", "fecha", "fecha de valor",       // BBVA, Santander credit cards
            "date", "transaction date", "data", "data di valuta",        // UniCredit, Intesa Sanpaolo credit cards
            // Asian Credit Cards
            "date", "transaction date", "取引日", "決済日",                // Japanese credit cards (JCB, MUFG Card)
            "date", "transaction date", "交易日期", "结算日期",            // Chinese credit cards (UnionPay)
            "date", "transaction date", "거래일자", "결제일자",            // Korean credit cards (KB Card, Shinhan Card)
            "date", "transaction date", "วันที่ทำรายการ", "วันที่ชำระ",    // Thai credit cards
            // Indian Credit Cards
            "date", "transaction date", "value date", "posting date",    // HDFC, ICICI, Axis credit cards
            // Global Payment Networks
            "transaction date", "date", "posting date",                  // Mastercard, Visa (global)
            "transaction date", "date",                                  // American Express (global)
            "transaction date", "date",                                  // Discover (global)
            "transaction date", "date",                                  // JCB (Japan/Asia)
            "transaction date", "date",                                  // UnionPay (China/Asia)
            // PayPal formats
            "date", "time", "datetime", "transaction date",              // PayPal
            // Venmo formats
            "date", "transaction date", "datetime", "time",              // Venmo
            // Zelle formats
            "date", "transaction date", "posted date", "posting date",    // Zelle (via banks)
            // Apple Pay / Apple Card formats
            "date", "transaction date", "posted date", "purchase date",   // Apple Pay, Apple Card
            // Google Pay formats
            "date", "transaction date", "datetime", "time",              // Google Pay
            // PhonePe / UPI formats (India)
            "date", "transaction date", "datetime", "time",              // PhonePe, UPI transactions
            // Paytm formats (India - wallet/payment service)
            "date", "transaction date", "transaction date & time", "datetime", "time", // Paytm
            // Amazon Pay formats
            "date", "transaction date", "order date",                  // Amazon Pay
            // Razor Pay formats (India)
            "date", "transaction date", "created at", "created_at",      // Razor Pay
            // Indian Banks formats (DD/MM/YYYY date format)
            "date", "transaction date", "value date", "posting date",    // State Bank of India, ICICI, HDFC, etc.
            "trade date", "date", "transaction date", "value date", "settlement date", // ICICI Direct (brokerage) - prioritize "trade date" over "settlement date"
            // European Banks formats (DD/MM/YYYY or DD.MM.YYYY date format)
            "date", "transaction date", "value date", "posting date",    // HSBC, Barclays, Lloyds (UK)
            "datum", "date", "transaction date", "buchungsdatum", "wertstellung", // Deutsche Bank, Commerzbank (Germany) - prioritize "datum" over "wertstellung"
            "date", "transaction date", "datum", "valutadatum",          // ING, Rabobank (Netherlands)
            "date", "transaction date", "date de transaction", "date de valeur", // BNP Paribas, Crédit Agricole (France)
            "date", "transaction date", "fecha", "fecha de valor",       // BBVA, Santander (Spain)
            "date", "transaction date", "data", "data di valuta",        // UniCredit, Intesa Sanpaolo (Italy)
            // Asian Banks formats
            "date", "transaction date", "取引日", "決済日",                // Japanese banks (MUFG, SMBC, Mizuho)
            "date", "transaction date", "交易日期", "结算日期",            // Chinese banks (ICBC, CCB, BOC)
            "date", "transaction date", "거래일자", "결제일자",            // Korean banks (KB, Shinhan, Hana)
            "date", "transaction date", "วันที่ทำรายการ", "วันที่ชำระ",    // Thai banks (Bangkok Bank, Kasikorn)
            "date", "transaction date", "tanggal transaksi", "tanggal penyelesaian", // Indonesian banks (BCA, Mandiri)
            "date", "transaction date", "ngày giao dịch", "ngày thanh toán", // Vietnamese banks (Vietcombank, BIDV)
            // Payment network formats (Mastercard, Visa)
            "transaction date", "date", "posting date",                  // Mastercard, Visa (via issuing banks)
            // Plaid (financial data aggregation - uses bank formats)
            "date", "transaction date", "authorized date", "posted date", // Plaid
            // Investment account formats (all major brokerages - Global)
            // US Investment Platforms
            "run date", "rundate", "run_date", "transaction date",        // Fidelity
            "transaction date", "trade date", "settlement date", "settlement_date", // Fidelity NetBenefits - prioritize transaction date over settlement
            "transaction date", "trade date", "settlement date", "settlement_date",     // Vanguard, Schwab - prioritize transaction/trade date over settlement
            "trade date", "tradedate", "trade_date", "transaction date", "settlement date",  // TD Ameritrade, E*TRADE - prioritize trade date
            "date", "transaction date", "trade date", "settlement date",                     // Robinhood - prioritize transaction/trade date
            "transaction date", "trade date", "settlement date",                        // Morgan Stanley - prioritize transaction/trade date
            "date", "transaction date", "trade date", "settlement date",                     // Goldman Sachs - prioritize transaction/trade date
            "transaction date", "trade date", "date", "settlement date",                                    // Generic US investment accounts - prioritize transaction/trade date
            // European Investment Platforms
            "date", "transaction date", "trade date", "settlement date", // Interactive Brokers (Europe)
            "date", "transaction date", "trade date", "value date",       // Degiro, eToro (Europe)
            "datum", "date", "transaction date", "buchungsdatum", "wertstellung", // German brokerages (Comdirect, Consorsbank) - prioritize "datum" over "wertstellung"
            "date", "transaction date", "datum", "valutadatum",          // Dutch brokerages (BinckBank, Lynx)
            "date", "transaction date", "date de transaction", "date de valeur", // French brokerages (Boursorama, Binck)
            "date", "transaction date", "fecha", "fecha de valor",       // Spanish brokerages (SelfBank, Renta 4)
            "date", "transaction date", "data", "data di valuta",        // Italian brokerages (FinecoBank, Directa)
            // Asian Investment Platforms
            "trade date", "date", "transaction date", "settlement date", // ICICI Direct, HDFC Securities (India) - prioritize "trade date" over "settlement date"
            "date", "transaction date", "取引日", "決済日",                // Japanese brokerages (MUFG Securities, Nomura)
            "date", "transaction date", "交易日期", "结算日期",            // Chinese brokerages (CITIC Securities, Huatai Securities)
            "date", "transaction date", "거래일자", "결제일자",            // Korean brokerages (Samsung Securities, Mirae Asset)
            "date", "transaction date", "วันที่ทำรายการ", "วันที่ชำระ",    // Thai brokerages
            // Global Investment Platforms
            "trade date", "transaction date", "value date", "settlement date", // ICICI Direct (brokerage - India)
            "transaction date", "trade date", "settlement date",         // Interactive Brokers (global)
            "date", "transaction date", "trade date",                    // eToro (global)
            "transaction date", "date",                                    // Generic investment accounts (global)
            // Loan account formats (all loan types)
            "payment date", "payment_date", "paymentdate",                // Mortgages, auto loans, student loans
            "due date", "duedate", "due_date", "payment date",            // Loan payments
            "transaction date", "date",                                    // Loan transactions
            // HSA (Health Savings Account) formats
            "transaction date", "date", "posting date",                    // HSA accounts
            "transaction date"                                             // Generic fallback
        );
        if (dateString == null || dateString.isEmpty()) {
            logger.debug("parseTransaction Row {}: Date string is null or empty", rowNumber);
            return null; // Date is required
        }
        
        LocalDate date = parseDate(dateString);
        if (date == null) {
            logger.warn("Row {}: Could not parse date: {}", rowNumber, dateString);
            return null; // Date is required
        }
        transaction.setDate(date);
        
        // Parse amount (supports all major US financial institutions and account types)
        String amountString = row.findField(
            // Common formats (checking/savings/money market)
            "amount", "transaction amount", "debit", "credit", "transaction_amount",
            "amount (usd)", "amount(usd)", "amount usd", "amount_usd",
            // Major Banks - Checking/Savings/Money Market
            "amount (usd)", "amount(usd)", "amount",                    // Chase
            "amount", "debit/credit", "debit", "credit",              // Bank of America
            "amount", "debit", "credit", "transaction amount",        // Wells Fargo
            "amount", "debit amount", "credit amount", "transaction amount", // Citi
            "amount", "transaction amount", "debit", "credit",        // Capital One
            "amount", "debit", "credit",                               // US Bank
            "amount", "debit", "credit",                               // TD Bank
            "amount", "transaction amount",                            // Chime
            "amount", "debit", "credit",                               // SoFi
            "amount", "debit", "credit",                               // Citizens Bank
            "amount", "transaction amount",                            // Regions Bank
            "amount", "debit", "credit",                               // KeyBank
            "amount", "transaction amount",                            // Navy Federal Credit Union
            "amount", "debit", "credit",                               // First Tech Federal
            "amount", "debit", "credit",                               // Synchrony Bank
            // Credit Card formats (all major issuers - Global)
            // US Credit Cards
            "amount", "charge amount", "charge_amount",                // Credit cards
            "purchase amount", "purchase_amount", "amount",            // Credit card purchases
            "payment amount", "payment_amount", "amount",              // Credit card payments
            "credit", "debit", "amount",                               // Credit card transactions
            "amount", "transaction amount",                            // Generic credit cards
            // European Credit Cards
            "amount", "transaction amount", "betrag", "soll", "haben", // German credit cards
            "amount", "transaction amount", "bedrag", "debet", "credit", // Dutch credit cards
            "amount", "transaction amount", "montant", "débit", "crédit", // French credit cards
            "amount", "transaction amount", "importe", "débito", "crédito", // Spanish credit cards
            "amount", "transaction amount", "importo", "addebito", "accredito", // Italian credit cards
            // Asian Credit Cards
            "amount", "transaction amount", "金額", "取引金額",          // Japanese credit cards
            "amount", "transaction amount", "金额", "交易金额",            // Chinese credit cards
            "amount", "transaction amount", "금액", "거래금액",            // Korean credit cards
            "amount", "transaction amount", "จำนวนเงิน", "ยอดเงิน",        // Thai credit cards
            // Indian Credit Cards
            "amount", "transaction amount", "debit", "credit",         // HDFC, ICICI, Axis credit cards
            "amount (inr)", "amount(inr)", "amount inr",              // Indian credit cards with INR
            // PayPal formats (prefer Net over Gross, as Net is the actual amount after fees)
            "net", "gross", "fee", "amount", "transaction amount",    // PayPal (Net = actual amount, Gross = before fees)
            // Venmo formats
            "amount", "transaction amount", "net amount", "total",     // Venmo
            // Zelle formats
            "amount", "transaction amount", "debit", "credit",        // Zelle (via banks)
            // Apple Pay / Apple Card formats
            "amount", "transaction amount", "purchase amount",         // Apple Pay, Apple Card
            // Google Pay formats
            "amount", "transaction amount", "net amount",              // Google Pay
            // PhonePe / UPI formats (India)
            "amount", "transaction amount", "debit amount", "credit amount", // PhonePe, UPI (may use INR)
            // Paytm formats (India - wallet/payment service)
            "amount", "transaction amount", "transaction value", "credit", "debit", // Paytm (may use INR)
            // Amazon Pay formats
            "amount", "transaction amount", "order amount",            // Amazon Pay
            // Razor Pay formats (India)
            "amount", "transaction amount", "amount paid", "amount_paid", // Razor Pay
            // Indian Banks formats (may use INR currency)
            "amount", "transaction amount", "debit", "credit",         // State Bank of India, ICICI, HDFC, etc.
            "amount (inr)", "amount(inr)", "amount inr",              // Indian banks with INR
            "net amount", "gross amount", "amount", "transaction amount", "credit", "debit", // ICICI Direct (brokerage)
            // European Banks formats (may use EUR, GBP, CHF, etc.)
            "amount", "transaction amount", "betrag", "soll", "haben", // German banks (Deutsche Bank, Commerzbank)
            "amount", "transaction amount", "bedrag", "debet", "credit", // Dutch banks (ING, Rabobank)
            "amount", "transaction amount", "montant", "débit", "crédit", // French banks (BNP Paribas, Crédit Agricole)
            "amount", "transaction amount", "importe", "débito", "crédito", // Spanish banks (BBVA, Santander)
            "amount", "transaction amount", "importo", "addebito", "accredito", // Italian banks (UniCredit, Intesa)
            "amount (eur)", "amount(eur)", "amount eur", "amount (gbp)", "amount(gbp)", "amount gbp", // European banks
            // Asian Banks formats
            "amount", "transaction amount", "金額", "取引金額",          // Japanese banks
            "amount", "transaction amount", "金额", "交易金额",            // Chinese banks
            "amount", "transaction amount", "금액", "거래금액",            // Korean banks
            "amount", "transaction amount", "จำนวนเงิน", "ยอดเงิน",        // Thai banks
            "amount", "transaction amount", "jumlah", "nominal",         // Indonesian banks
            "amount", "transaction amount", "số tiền", "giá trị",       // Vietnamese banks
            // Payment network formats (Mastercard, Visa)
            "amount", "transaction amount", "debit", "credit",         // Mastercard, Visa (via issuing banks)
            // Plaid formats
            "amount", "transaction amount", "authorized amount",        // Plaid
            // Investment account formats (all major brokerages - Global)
            // US Investment Platforms
            "amount ($)", "amount($)", "amount", "net amount",         // Fidelity, Vanguard
            "amount", "net amount", "net_amount", "total",            // Schwab, TD Ameritrade
            "total", "total amount", "total_amount", "amount",        // Investment transactions
            "proceeds", "cost basis", "cost_basis", "amount",         // Investment sales
            "amount", "net amount",                                    // Robinhood
            "amount", "net amount", "total",                           // Morgan Stanley, Goldman Sachs
            "amount", "net amount", "transaction amount", "contribution", "withdrawal", // Fidelity NetBenefits
            "amount", "transaction amount",                            // E*TRADE, generic US investments
            // European Investment Platforms
            "amount", "net amount", "betrag", "nettobetrag",          // German brokerages
            "amount", "net amount", "bedrag", "netto bedrag",          // Dutch brokerages
            "amount", "net amount", "montant", "montant net",          // French brokerages
            "amount", "net amount", "importe", "importe neto",        // Spanish brokerages
            "amount", "net amount", "importo", "importo netto",        // Italian brokerages
            "amount (eur)", "amount(eur)", "amount eur",              // European brokerages with EUR
            "amount (gbp)", "amount(gbp)", "amount gbp",              // UK brokerages with GBP
            "amount (chf)", "amount(chf)", "amount chf",              // Swiss brokerages with CHF
            // Asian Investment Platforms
            "amount", "net amount", "transaction amount", "credit", "debit", // ICICI Direct, HDFC Securities (India)
            "amount (inr)", "amount(inr)", "amount inr",              // Indian brokerages with INR
            "amount", "net amount", "金額", "取引金額",                  // Japanese brokerages
            "amount", "net amount", "金额", "交易金额",                  // Chinese brokerages
            "amount", "net amount", "금액", "거래금액",                  // Korean brokerages
            "amount", "net amount", "จำนวนเงิน", "ยอดเงิน",              // Thai brokerages
            // Global Investment Platforms
            "net amount", "gross amount", "amount", "transaction amount", // ICICI Direct (brokerage - India)
            "amount", "net amount", "total",                           // Interactive Brokers (global)
            "amount", "transaction amount",                            // eToro (global)
            "amount", "transaction amount",                            // Generic investment accounts (global)
            // Loan account formats (all loan types)
            "payment amount", "payment_amount", "payment",            // Loan payments
            "principal", "interest", "amount",                        // Loan components
            "total payment", "total_payment", "payment",               // Total loan payment
            "escrow", "escrow payment", "escrow_payment",              // Mortgage escrow
            "payment", "amount",                                      // Generic loan payments
            // HSA (Health Savings Account) formats
            "amount", "transaction amount", "debit", "credit",         // HSA accounts
            "amount"                                                   // Generic fallback
        );
        if (amountString == null || amountString.isEmpty()) {
            return null; // Amount is required
        }
        
        // Parse amount with currency detection (use header line and filename for better detection)
        AmountParseResult amountResult = parseAmountWithCurrency(amountString, headerLine, filename);
        if (amountResult.amount == null) {
            logger.warn("Row {}: Could not parse amount: {}", rowNumber, amountString);
            return null; // Amount is required
        }
        BigDecimal amount = amountResult.amount;
        
        // CRITICAL: Reverse sign for credit card accounts
        // Credit card imports typically have expenses as positive, but backend stores them as negative
        // For credit card accounts: reverse the sign to match backend convention
        if (detectedAccount != null && detectedAccount.getAccountType() != null) {
            String accountType = detectedAccount.getAccountType().toLowerCase();
            if (accountType.contains("credit") || accountType.equals("creditcard") || accountType.equals("credit_card")) {
                amount = amount.negate();
                logger.debug("Reversed sign for credit card account: {} → {}", amountResult.amount, amount);
            }
        }
        
        logger.debug("Setting amount on parsed transaction: {}", amount);
        transaction.setAmount(amount);
        transaction.setCurrencyCode(amountResult.currencyCode);
        
        // Parse description (supports all major US financial institutions and account types)
        String description = row.findField(
            // Common formats (checking/savings/money market)
            "description", "memo", "details", "merchant", "payee", "name", "payee name",
            "transaction description", "transaction_description", "transaction details",
            // Major Banks - Checking/Savings/Money Market
            "description", "details", "memo",                        // Chase
            "description", "payee", "memo",                          // Bank of America
            "description", "memo", "details",                       // Wells Fargo
            "description", "transaction description", "memo",        // Citi
            "description", "merchant", "memo",                      // Capital One
            "description", "memo", "transaction description",        // US Bank
            "description", "memo",                                   // TD Bank
            "description", "merchant", "memo",                      // Chime
            "description", "memo", "transaction description",        // SoFi
            "description", "memo",                                   // Citizens Bank
            "description", "memo",                                   // Regions Bank
            "description", "memo",                                   // KeyBank
            "description", "memo",                                   // Navy Federal Credit Union
            "description", "memo",                                   // First Tech Federal
            "description", "memo",                                   // Synchrony Bank
            // US Credit Unions
            "description", "memo",                                   // State Employees Credit Union
            "description", "memo",                                   // School First Federal Credit Union
            "description", "memo",                                   // PenFed Credit Union
            "description", "memo",                                   // Boeing Employees Credit Union
            "description", "memo",                                   // Alliant Credit Union
            "description", "memo",                                   // America First Credit Union
            "description", "memo",                                   // Golden 1 Credit Union
            "description", "memo",                                   // Mountain America Credit Union
            "description", "memo",                                   // Suncoast Credit Union
            "description", "memo",                                   // Randolph-Brooks Federal Credit Union
            "description", "memo",                                   // Lake Michigan Credit Union
            "description", "memo",                                   // Security Service Federal Credit Union
            "description", "memo",                                   // FourLeaf Federal Credit Union
            "description", "memo",                                   // Idaho Central Credit Union
            "description", "memo",                                   // Global Credit Union
            "description", "memo",                                   // Digital Federal Credit Union
            "description", "memo",                                   // GreenState Credit Union
            // Credit Card formats (all major issuers)
            "description", "merchant name", "merchant_name",        // Credit cards
            "merchant", "vendor", "store", "description",            // Credit card merchants
            "transaction description", "purchase description",       // Credit card purchases
            "category", "transaction category", "description",      // Some credit cards put description in category
            "description", "merchant",                               // Generic credit cards
            // PayPal formats
            "name", "description", "type", "from email address", "to email address", "item title", // PayPal
            "note", "memo", "description",                            // PayPal notes
            // Venmo formats
            "description", "note", "memo", "what for", "note to self", // Venmo
            "from", "to", "name",                                     // Venmo sender/recipient
            // Zelle formats
            "description", "memo", "note", "transaction description", // Zelle (via banks)
            "recipient", "sender", "name",                           // Zelle recipient/sender
            // Apple Pay / Apple Card formats
            "description", "merchant", "merchant name", "transaction description", // Apple Pay, Apple Card
            "location", "store",                                      // Apple Pay location/store
            // Google Pay formats
            "description", "merchant", "merchant name", "note",      // Google Pay
            "recipient", "sender", "name",                           // Google Pay P2P
            // PhonePe / UPI formats (India)
            "description", "remarks", "note", "transaction description", // PhonePe, UPI
            "beneficiary name", "payer name", "upi id",             // UPI transaction details
            // Paytm formats (India - wallet/payment service)
            "description", "transaction description", "merchant name", "merchant", // Paytm
            "note", "remarks", "transaction type", "payment method", // Paytm transaction details
            // Amazon Pay formats
            "description", "merchant", "order description",          // Amazon Pay
            // Razor Pay formats (India)
            "description", "notes", "description",                   // Razor Pay
            // Indian Banks formats
            "description", "narration", "particulars", "transaction description", // State Bank of India, ICICI, HDFC, etc.
            "beneficiary", "payer", "remarks",                      // Indian bank transaction details
            // European Banks formats
            "description", "memo", "details", "verwendungszweck", "zweck", // German banks
            "description", "memo", "details", "omschrijving", "beschrijving", // Dutch banks
            "description", "memo", "details", "libellé", "détails", // French banks
            "description", "memo", "details", "concepto", "detalles", // Spanish banks
            "description", "memo", "details", "causale", "dettagli", // Italian banks
            // Asian Banks formats
            "description", "memo", "details", "摘要", "备注",            // Chinese banks
            "description", "memo", "details", "摘要", "備考",            // Japanese banks
            "description", "memo", "details", "내역", "비고",            // Korean banks
            "description", "memo", "details", "รายละเอียด", "หมายเหตุ",  // Thai banks
            "description", "memo", "details", "keterangan", "catatan", // Indonesian banks
            "description", "memo", "details", "mô tả", "ghi chú",      // Vietnamese banks
            // Payment network formats (Mastercard, Visa)
            "description", "merchant name", "merchant",              // Mastercard, Visa (via issuing banks)
            // Plaid formats
            "description", "merchant name", "name", "original description", // Plaid
            // Investment account formats (all major brokerages)
            "transaction description", "transaction_description",    // Fidelity, Vanguard
            "action", "transaction type", "transaction_type",       // Investment actions
            "security description", "security_description", "symbol", // Investment securities
            "description", "memo", "transaction description",       // Generic investment description
            "description", "memo",                                   // Robinhood, Morgan Stanley, Goldman Sachs
            "description", "transaction description",                // E*TRADE, Schwab, TD Ameritrade
            "transaction description", "description", "action description", // Fidelity NetBenefits
            "security name", "security", "symbol", "company name", "transaction description", // Fidelity NetBenefits - prioritize "security name"
            "particulars", "transaction description", "description", "narration", // ICICI Direct (brokerage) - prioritize "particulars"
            "particulars", "security name", "symbol", "scrip name", "company name", // ICICI Direct (brokerage) - prioritize "particulars"
            // Loan account formats (all loan types)
            "description", "transaction description", "memo",        // Loan transactions
            "payment type", "payment_type", "description",           // Loan payment types
            "memo", "notes", "description",                          // Loan notes
            // HSA (Health Savings Account) formats
            "description", "memo", "transaction description",        // HSA accounts
            "description"                                            // Generic fallback
        );
        transaction.setDescription(description != null && !description.isEmpty() 
            ? description : "Imported Transaction");
        
        // Parse merchant name (supports all account types from all institutions)
        String merchantName = row.findField(
            // Common formats (checking/savings/money market)
            "merchant", "merchant name", "merchant_name", "payee", "name", "payee_name",
            // Credit card specific (all issuers)
            "merchant", "vendor", "store", "merchant name", "merchant_name",
            // Synchrony Bank
            "merchant", "merchant name", "description",
            // PayPal formats
            "name", "from email address", "to email address", "item title", // PayPal
            // Venmo formats
            "from", "to", "name", "recipient", "sender",              // Venmo
            // Zelle formats
            "recipient", "sender", "name", "merchant",                // Zelle
            // Apple Pay / Apple Card formats
            "merchant", "merchant name", "store", "location",        // Apple Pay, Apple Card
            // Google Pay formats
            "merchant", "merchant name", "recipient", "sender",      // Google Pay
            // PhonePe / UPI formats (India)
            "beneficiary name", "payer name", "merchant",            // PhonePe, UPI
            // Paytm formats (India - wallet/payment service)
            "merchant name", "merchant", "shop name", "store name", // Paytm
            "business name", "recipient", "sender", "name",         // Paytm P2P transactions
            // Amazon Pay formats
            "merchant", "seller", "store",                           // Amazon Pay
            // Razor Pay formats (India)
            "merchant", "customer",                                  // Razor Pay
            // Indian Banks formats
            "beneficiary", "payer", "merchant",                      // State Bank of India, ICICI, HDFC, etc.
            "beneficiary name", "payer name", "counter party",      // ICICI Direct (brokerage)
            // Payment network formats (Mastercard, Visa)
            "merchant name", "merchant", "vendor",                    // Mastercard, Visa (via issuing banks)
            // Plaid formats
            "merchant name", "merchant", "name",                      // Plaid
            // Investment specific (all brokerages)
            "security description", "security_description", "symbol", "company name",
            "security", "symbol",                                    // Fidelity, Vanguard, Schwab, etc.
            "security name", "security_name",                       // Fidelity NetBenefits - prioritize security name for merchant
            // Generic
            "payee", "name", "merchant"
        );
        // Normalize merchant name (lenient normalization for better matching)
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            transaction.setMerchantName(normalizeMerchantName(merchantName));
        } else {
            transaction.setMerchantName(merchantName);
        }
        
        // Parse category (supports all account types from all institutions)
        String categoryString = row.findField(
            // Common formats (checking/savings/money market)
            "category", "transaction category", "type", "transaction_type",
            // Credit card specific (all issuers)
            "category", "merchant category", "merchant_category", "mcc", "mcc code",
            "transaction category",                                   // Chase, Amex, Discover, etc.
            // PayPal specific
            "type", "category", "transaction type",                  // PayPal transaction types
            // Synchrony Bank specific
            "category", "type", "transaction category",              // Synchrony Bank
            // Venmo specific
            "type", "category", "transaction type",                  // Venmo transaction types
            // Zelle specific
            "category", "type", "transaction type",                  // Zelle (via banks)
            // Apple Pay / Apple Card specific
            "category", "merchant category", "transaction category", // Apple Pay, Apple Card
            // Investment specific (all brokerages)
            "transaction type", "transaction_type", "action", "security type", "security_type",
            "type", "action",                                        // Fidelity, Vanguard, Schwab, Robinhood, etc.
            "transaction type", "type", "action", "contribution type", "withdrawal type", // Fidelity NetBenefits
            "transaction type", "type", "action", "buy/sell", "transaction code", // ICICI Direct (brokerage)
            // Loan specific (all loan types)
            "payment type", "payment_type", "transaction type",
            // HSA specific
            "category", "type", "transaction type",
            // Generic
            "type", "category", "transaction category"
        );
        // Parse payment channel first (needed for enhanced category detection)
        String paymentChannel = row.findField("payment channel", "payment type", "payment_channel", "payment_type");
        transaction.setPaymentChannel(paymentChannel);
        
        // CRITICAL: Parse debit/credit indicator from Details or Type column
        // This helps determine transaction type more accurately than just amount sign
        String transactionTypeIndicator = row.findField(
            "details", "type", "transaction type", "transaction_type",
            "debit/credit", "debit credit", "dr/cr", "dr cr",
            "debit", "credit", "dr", "cr",
            // Account-specific indicators
            "transaction code", "transaction_code", "txn code", "txn_code",
            // Investment account indicators
            "action", "transaction action", "buy/sell",
            // Loan account indicators
            "payment type", "payment_type"
        );
        
        // CRITICAL: Determine transaction type FIRST (before categories) using account type
        // This is crucial for proper category determination which depends on transaction type
        String accountTypeString = null;
        String accountSubtypeString = null;
        if (detectedAccount != null) {
            accountTypeString = detectedAccount.getAccountType();
            accountSubtypeString = detectedAccount.getAccountSubtype();
        }
        
        TransactionTypeCategoryService.TypeResult typeResult = null;
        if (accountTypeString != null) {
            // Use account type-based determination (same as PDF import)
            typeResult = transactionTypeCategoryService.determineTransactionTypeFromAccountType(
                accountTypeString,
                accountSubtypeString,
                amount,
                description,
                paymentChannel
            );
        }
        
        String transactionType = null;
        if (typeResult != null) {
            transactionType = typeResult.getTransactionType().name();
            logger.debug("Transaction type determined: {} (source: {}, confidence: {})",
                typeResult.getTransactionType(), typeResult.getSource(), 
                String.format("%.2f", typeResult.getConfidence()));
        }
        
        // CRITICAL: Preserve categories from preview if account hasn't changed
        // If preserveCategoryPrimary is provided, it means we're importing and account matches preview
        // In this case, use the categories from preview instead of re-parsing
        if (preserveCategoryPrimary != null && preserveImporterCategoryPrimary != null) {
            // Account hasn't changed - preserve categories from preview
            transaction.setCategoryPrimary(preserveCategoryPrimary);
            transaction.setCategoryDetailed(preserveCategoryDetailed != null ? preserveCategoryDetailed : preserveCategoryPrimary);
            transaction.setImporterCategoryPrimary(preserveImporterCategoryPrimary);
            transaction.setImporterCategoryDetailed(preserveImporterCategoryDetailed != null ? preserveImporterCategoryDetailed : preserveImporterCategoryPrimary);
            logger.debug("✅ Category preserved from preview: merchant='{}', description='{}', amount={}, category='{}' (account unchanged: '{}')",
                    merchantName, description, amount, preserveCategoryPrimary, preserveAccountId);
        } else {
            // Account changed or first time parsing - parse categories with context
            // CRITICAL: Parse category using import parser with transaction type and account type context
            String parsedCategory = importCategoryParser.parseCategory(
                categoryString, description, merchantName, amount, paymentChannel, transactionTypeIndicator,
                transactionType, accountTypeString, accountSubtypeString);
            
            // Set importer category fields (context-aware parsed category)
            transaction.setImporterCategoryPrimary(parsedCategory);
            transaction.setImporterCategoryDetailed(parsedCategory);
            
            // CRITICAL: Use unified service to determine internal categories (hybrid logic)
            // Account will be null during parsing, but unified service can still work
            // Account will be available when transaction is created in TransactionService
            TransactionTypeCategoryService.CategoryResult categoryResult = 
                transactionTypeCategoryService.determineCategory(
                    parsedCategory,  // Importer category (from parser)
                    parsedCategory,
                    null,  // Account not available during parsing
                    merchantName,
                    description,
                    amount,
                    paymentChannel,
                    transactionTypeIndicator,
                    "CSV"  // Import source
                );
            
            if (categoryResult != null) {
                transaction.setCategoryPrimary(categoryResult.getCategoryPrimary());
                transaction.setCategoryDetailed(categoryResult.getCategoryDetailed());
                logger.debug("✅ Category assigned: merchant='{}', description='{}', amount={}, category='{}' (source: {}, confidence: {}, from categoryString='{}')",
                        merchantName, description, amount, categoryResult.getCategoryPrimary(),
                        categoryResult.getSource(), String.format("%.2f", categoryResult.getConfidence()), categoryString);
            } else {
                // Fallback
                transaction.setCategoryPrimary(parsedCategory);
                transaction.setCategoryDetailed(parsedCategory);
                logger.debug("✅ Category assigned: merchant='{}', description='{}', amount={}, category='{}' (from categoryString='{}')",
                        merchantName, description, amount, parsedCategory, categoryString);
            }
        }
        
        // Fallback to category-based determination if transaction type not yet determined
        if (transactionType == null) {
            typeResult = transactionTypeCategoryService.determineTransactionType(
                null,  // Account not available during parsing
                transaction.getCategoryPrimary(),
                transaction.getCategoryDetailed(),
                amount,
                transactionTypeIndicator,
                description,
                paymentChannel
            );
        
        if (typeResult != null) {
                transactionType = typeResult.getTransactionType().name();
            logger.debug("Transaction type determined: {} (source: {}, confidence: {})",
                typeResult.getTransactionType(), typeResult.getSource(), 
                String.format("%.2f", typeResult.getConfidence()));
        } else {
            // Fallback to old logic
                transactionType = determineTransactionType(
                transaction.getCategoryPrimary(), amount, transactionTypeIndicator, description, paymentChannel);
            }
        }
        
        // CRITICAL: For checking accounts, change Income/Transfer to Income/Deposit for positive amounts
        if (accountTypeString != null && 
            (accountTypeString.equalsIgnoreCase("checking") || 
             accountTypeString.equalsIgnoreCase("depository")) &&
            amount != null && amount.compareTo(BigDecimal.ZERO) > 0 &&
            transactionType != null && transactionType.equalsIgnoreCase("INCOME") &&
            (transaction.getCategoryPrimary() != null && transaction.getCategoryPrimary().equalsIgnoreCase("transfer"))) {
            // Change category from transfer to deposit for positive income transactions in checking accounts
            transaction.setCategoryPrimary("deposit");
            transaction.setCategoryDetailed("deposit");
            logger.debug("Changed category from transfer to deposit for checking account positive income transaction: amount={}, description='{}'", 
                amount, description);
        }
        
        transaction.setTransactionType(transactionType);
        
        // CRITICAL: Store transactionTypeIndicator for preview response and recalculation
        transaction.setTransactionTypeIndicator(transactionTypeIndicator);
        
        return transaction;
    }

    /**
     * Parse date string in various formats (mirrors iOS app)
     */
    private LocalDate parseDate(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            logger.debug("parseDate: Input dateString is null or empty");
            return null;
        }
        
        String trimmed = dateString.trim();
        logger.debug("parseDate: Parsing date string '{}'", trimmed);
        
        // CRITICAL FIX: Smart detection for ambiguous MM/dd vs dd/MM formats
        // If date contains "/" and matches pattern like "MM/dd" or "dd/MM", use smart detection
        if (trimmed.contains("/") && trimmed.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
            String[] parts = trimmed.split("/");
            if (parts.length == 3) {
                try {
                    int first = Integer.parseInt(parts[0]);
                    int second = Integer.parseInt(parts[1]);
                    int year = Integer.parseInt(parts[2]);
                    
                    // If first number > 12, it must be MM/dd/yyyy (US format)
                    if (first > 12 && second <= 12) {
                        logger.debug("parseDate: Smart detection - first number {} > 12, using MM/dd/yyyy format", first);
                        try {
                            LocalDate parsed = LocalDate.of(year, first, second);
                            logger.debug("parseDate: Successfully parsed '{}' as MM/dd/yyyy -> {}", trimmed, parsed);
                            return parsed;
                        } catch (Exception e) {
                            logger.debug("parseDate: Failed to parse '{}' as MM/dd/yyyy: {}", trimmed, e.getMessage());
                        }
                    }
                    // If second number > 12, it must be dd/MM/yyyy (European format)
                    else if (second > 12 && first <= 12) {
                        logger.debug("parseDate: Smart detection - second number {} > 12, using dd/MM/yyyy format", second);
                        try {
                            LocalDate parsed = LocalDate.of(year, second, first);
                            logger.debug("parseDate: Successfully parsed '{}' as dd/MM/yyyy -> {}", trimmed, parsed);
                            return parsed;
                        } catch (Exception e) {
                            logger.debug("parseDate: Failed to parse '{}' as dd/MM/yyyy: {}", trimmed, e.getMessage());
                        }
                    }
                    // If both numbers <= 12, it's ambiguous - prefer US format (MM/dd/yyyy) for US-based users
                    // This is a reasonable default for most users
                    else if (first <= 12 && second <= 12) {
                        logger.debug("parseDate: Ambiguous date '{}' (both numbers <= 12), preferring MM/dd/yyyy (US format)", trimmed);
                        // Try US format first
                        try {
                            LocalDate parsed = LocalDate.of(year, first, second);
                            logger.debug("parseDate: Successfully parsed '{}' as MM/dd/yyyy (preferred) -> {}", trimmed, parsed);
                            return parsed;
                        } catch (Exception e) {
                            logger.debug("parseDate: Failed to parse '{}' as MM/dd/yyyy: {}", trimmed, e.getMessage());
                        }
                    }
                } catch (NumberFormatException e) {
                    logger.debug("parseDate: Could not parse numbers from '{}', falling back to standard parsing", trimmed);
                }
            }
        }
        
        // Try each formatter (fallback to standard parsing)
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                LocalDate parsed = LocalDate.parse(trimmed, formatter);
                logger.debug("parseDate: Successfully parsed '{}' with formatter '{}' -> {}", trimmed, formatter, parsed);
                return parsed;
            } catch (DateTimeParseException e) {
                logger.debug("parseDate: Failed to parse '{}' with formatter '{}': {}", trimmed, formatter, e.getMessage());
                // Continue to next formatter
            }
        }
        
        // Try ISO 8601 with time component (extract date part only)
        // Handle edge case: string might be shorter than 10 characters
        if (trimmed.length() >= 10) {
            try {
                String datePart = trimmed.substring(0, 10);
                LocalDate parsed = LocalDate.parse(datePart, DateTimeFormatter.ISO_LOCAL_DATE);
                logger.debug("parseDate: Successfully parsed '{}' (extracted date part '{}') as ISO_LOCAL_DATE -> {}", trimmed, datePart, parsed);
                return parsed;
            } catch (Exception e) {
                logger.debug("parseDate: Failed to parse '{}' as ISO_LOCAL_DATE: {}", trimmed, e.getMessage());
                // Ignore and continue
            }
        }
        
        // Try to extract date from datetime strings (e.g., "19/12/2025 14:30" -> "19/12/2025")
        // Look for space or 'T' separator and try parsing the date part before it
        int spaceIndex = trimmed.indexOf(' ');
        int tIndex = trimmed.indexOf('T');
        int separatorIndex = (spaceIndex >= 0 && tIndex >= 0) ? Math.min(spaceIndex, tIndex) : 
                            (spaceIndex >= 0 ? spaceIndex : tIndex);
        if (separatorIndex > 0) {
            String datePart = trimmed.substring(0, separatorIndex);
            for (DateTimeFormatter formatter : DATE_FORMATTERS) {
                try {
                    LocalDate parsed = LocalDate.parse(datePart, formatter);
                    logger.debug("parseDate: Successfully parsed '{}' (extracted date part '{}') with formatter '{}' -> {}", trimmed, datePart, formatter, parsed);
                    return parsed;
                } catch (DateTimeParseException e) {
                    // Continue to next formatter
                }
            }
        }
        
        logger.warn("parseDate: Could not parse date string '{}' with any formatter", trimmed);
        return null;
    }

    /**
     * Parse amount string, handling various formats (mirrors iOS app)
     * Returns a pair: [amount, currencyCode]
     */
    private static class AmountParseResult {
        BigDecimal amount;
        String currencyCode;
        
        AmountParseResult(BigDecimal amount, String currencyCode) {
            this.amount = amount;
            this.currencyCode = currencyCode;
        }
    }
    
    /**
     * Detect currency from amount string or header
     * Supports: USD ($), INR (₹, Rs, Rs.), EUR (€), GBP (£), CAD (C$), AUD (A$), etc.
     */
    private String detectCurrency(String amountString, String headerLine, String filename) {
        if (amountString == null) {
            amountString = "";
        }
        String upper = amountString.toUpperCase();
        String headerUpper = headerLine != null ? headerLine.toUpperCase() : "";
        String filenameUpper = filename != null ? filename.toUpperCase() : "";
        
        // Check for currency symbols in amount string
        if (amountString.contains("₹") || upper.contains("RS") || upper.contains("INR")) {
            return "INR";
        }
        if (amountString.contains("$") || upper.contains("USD")) {
            return "USD";
        }
        if (amountString.contains("€") || upper.contains("EUR")) {
            return "EUR";
        }
        if (amountString.contains("£") || upper.contains("GBP")) {
            return "GBP";
        }
        if (upper.contains("CAD") || amountString.contains("C$")) {
            return "CAD";
        }
        if (upper.contains("AUD") || amountString.contains("A$")) {
            return "AUD";
        }
        // For ¥ symbol, use context-based detection (filename, headers, bank names)
        // Check for Chinese context first (CNY), then Japanese (JPY)
        if (amountString.contains("¥") || upper.contains("CNY") || upper.contains("JPY") || 
            upper.contains("YUAN") || upper.contains("YEN")) {
            // Check for Chinese context (CNY) - check filename first, then headers, then amount
            if (upper.contains("CNY") || upper.contains("YUAN") || headerUpper.contains("CNY") || 
                headerUpper.contains("YUAN") || headerUpper.contains("CHINA") || 
                headerUpper.contains("CHINESE") || headerUpper.contains("CITIC") || 
                headerUpper.contains("ICBC") || headerUpper.contains("CCB") || 
                headerUpper.contains("BOC") || headerUpper.contains("ABC") ||
                headerUpper.contains("UNIONPAY") || headerUpper.contains("交易") ||
                headerUpper.contains("金额") || filenameUpper.contains("CITIC") ||
                filenameUpper.contains("CHINA") || filenameUpper.contains("CHINESE") ||
                filenameUpper.contains("UNIONPAY") || filenameUpper.contains("CNY") ||
                filenameUpper.contains("YUAN")) {
                return "CNY";
            }
            // Check for Japanese context (JPY) - check filename first, then headers, then amount
            if (upper.contains("JPY") || upper.contains("YEN") || headerUpper.contains("JPY") || 
                headerUpper.contains("YEN") || headerUpper.contains("JAPAN") || 
                headerUpper.contains("JAPANESE") || headerUpper.contains("MUFG") || 
                headerUpper.contains("MIZUHO") || headerUpper.contains("SMBC") ||
                headerUpper.contains("JCB") || headerUpper.contains("取引") ||
                headerUpper.contains("金額") || filenameUpper.contains("JAPAN") ||
                filenameUpper.contains("JAPANESE") || filenameUpper.contains("MUFG") ||
                filenameUpper.contains("JCB") || filenameUpper.contains("JPY") ||
                filenameUpper.contains("YEN")) {
                return "JPY";
            }
            // Default to JPY if no context (¥ is more commonly used for JPY)
            return "JPY";
        }
        if (upper.contains("CHF") || amountString.contains("Fr")) {
            return "CHF"; // Swiss Franc
        }
        if (upper.contains("SEK") || amountString.contains("kr")) {
            return "SEK"; // Swedish Krona
        }
        if (upper.contains("NOK") || amountString.contains("kr")) {
            return "NOK"; // Norwegian Krone
        }
        if (upper.contains("DKK") || amountString.contains("kr")) {
            return "DKK"; // Danish Krone
        }
        if (upper.contains("PLN") || amountString.contains("zł")) {
            return "PLN"; // Polish Zloty
        }
        if (upper.contains("SGD") || amountString.contains("S$")) {
            return "SGD"; // Singapore Dollar
        }
        if (upper.contains("HKD") || amountString.contains("HK$")) {
            return "HKD"; // Hong Kong Dollar
        }
        if (upper.contains("KRW") || amountString.contains("₩")) {
            return "KRW"; // South Korean Won
        }
        if (upper.contains("THB") || amountString.contains("฿")) {
            return "THB"; // Thai Baht
        }
        if (upper.contains("IDR") || amountString.contains("Rp")) {
            return "IDR"; // Indonesian Rupiah
        }
        if (upper.contains("VND") || amountString.contains("₫")) {
            return "VND"; // Vietnamese Dong
        }
        if (upper.contains("MYR") || amountString.contains("RM")) {
            return "MYR"; // Malaysian Ringgit
        }
        if (upper.contains("PHP") || amountString.contains("₱")) {
            return "PHP"; // Philippine Peso
        }
        if (upper.contains("BRL") || amountString.contains("R$")) {
            return "BRL"; // Brazilian Real
        }
        if (upper.contains("MXN") || amountString.contains("Mex$")) {
            return "MXN"; // Mexican Peso
        }
        if (upper.contains("ZAR") || amountString.contains("R")) {
            return "ZAR"; // South African Rand
        }
        if (upper.contains("AUD") || amountString.contains("A$")) {
            return "AUD"; // Australian Dollar
        }
        if (upper.contains("NZD") || amountString.contains("NZ$")) {
            return "NZD"; // New Zealand Dollar
        }
        
        // Check header for currency indicators
        if (headerUpper.contains("INR") || headerUpper.contains("RUPEES") || headerUpper.contains("RS")) {
            return "INR";
        }
        if (headerUpper.contains("USD") || headerUpper.contains("DOLLARS")) {
            return "USD";
        }
        if (headerUpper.contains("EUR") || headerUpper.contains("EUROS")) {
            return "EUR";
        }
        if (headerUpper.contains("GBP") || headerUpper.contains("POUNDS")) {
            return "GBP";
        }
        if (headerUpper.contains("CHF") || headerUpper.contains("FRANCS")) {
            return "CHF";
        }
        if (headerUpper.contains("JPY") || headerUpper.contains("YEN")) {
            return "JPY";
        }
        if (headerUpper.contains("CNY") || headerUpper.contains("YUAN")) {
            return "CNY";
        }
        
        // Default to USD if no currency detected
        return "USD";
    }
    
    /**
     * Parse amount string, handling various formats (mirrors iOS app)
     * Returns AmountParseResult with amount and detected currency
     */
    private AmountParseResult parseAmountWithCurrency(String amountString, String headerLine, String filename) {
        if (amountString == null || amountString.trim().isEmpty()) {
            return new AmountParseResult(null, "USD");
        }
        
        // Detect currency before cleaning (with filename context)
        String currencyCode = detectCurrency(amountString, headerLine, filename);
        
        // Remove currency symbols and whitespace (supports USD, INR, and other currencies)
        String cleaned = amountString
            .replace("$", "")
            .replace("₹", "")  // Indian Rupee symbol
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
            .replace("Fr", "")  // Swiss Franc
            .replace("kr", "")  // Nordic currencies (SEK, NOK, DKK)
            .replace("zł", "")  // Polish Zloty
            .replace("S$", "")  // Singapore Dollar
            .replace("HK$", "") // Hong Kong Dollar
            .replace("₩", "")   // Korean Won
            .replace("฿", "")   // Thai Baht
            .replace("Rp", "")  // Indonesian Rupiah
            .replace("₫", "")   // Vietnamese Dong
            .replace("RM", "")  // Malaysian Ringgit
            .replace("₱", "")   // Philippine Peso
            .replace("R$", "") // Brazilian Real
            .replace("Mex$", "") // Mexican Peso
            .replace(" ", "")
            .trim();
        
        // Handle European number format (comma as decimal separator: 1.234,56)
        // Check if we have a comma followed by 2 digits at the end (likely decimal separator)
        boolean isEuropeanFormat = cleaned.matches(".*\\d{1,3}(?:\\.\\d{3})*,\\d{1,2}$");
        if (isEuropeanFormat) {
            // European format: 1.234,56 -> 1234.56
            cleaned = cleaned.replace(".", ""); // Remove thousand separators (periods)
            cleaned = cleaned.replace(",", "."); // Convert comma to decimal point
        } else {
            // US/Asian format: 1,234.56 -> 1234.56
            cleaned = cleaned.replace(",", ""); // Remove thousand separators (commas)
        }
        
        // Handle negative amounts (parentheses or minus sign)
        boolean isNegative = (cleaned.startsWith("(") && cleaned.endsWith(")")) || cleaned.startsWith("-");
        String numericString = cleaned
            .replace("(", "")
            .replace(")", "")
            .replace("-", "");
        
        // Validate numeric string is not empty after cleaning
        if (numericString == null || numericString.trim().isEmpty()) {
            logger.warn("Amount string is empty after cleaning: {}", amountString);
            return new AmountParseResult(null, currencyCode);
        }
        
        try {
            BigDecimal amount = new BigDecimal(numericString);
            
            // Validate amount is within reasonable bounds (prevent overflow/underflow)
            // Max: 999,999,999.99 (matches AmountValidator.MAX_AMOUNT)
            BigDecimal maxAmount = new BigDecimal("999999999.99");
            BigDecimal minAmount = maxAmount.negate();
            
            if (amount.compareTo(maxAmount) > 0) {
                logger.warn("Amount exceeds maximum: {} (capped at {})", amount, maxAmount);
                amount = maxAmount;
            } else if (amount.compareTo(minAmount) < 0) {
                logger.warn("Amount below minimum: {} (capped at {})", amount, minAmount);
                amount = minAmount;
            }
            
            // Round to 2 decimal places (standard for currency)
            amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
            
            // CRITICAL: Preserve original sign - don't convert to absolute for positive amounts
            // This ensures debit/credit information is preserved
            BigDecimal finalAmount = isNegative ? amount.negate() : amount;
            logger.debug("Parsed amount: original='{}', isNegative={}, numeric={}, final={}", 
                    amountString, isNegative, amount, finalAmount);
            
            return new AmountParseResult(finalAmount, currencyCode);
        } catch (NumberFormatException e) {
            logger.warn("Could not parse amount: {} (cleaned: {})", amountString, numericString);
            return new AmountParseResult(null, currencyCode);
        } catch (ArithmeticException e) {
            logger.warn("Arithmetic error parsing amount: {} - {}", amountString, e.getMessage());
            return new AmountParseResult(null, currencyCode);
        }
    }
    

    /**
     * Parse category string to category name with enhanced RSU, ACH, Salary detection
     * Supports categories from checking accounts, credit cards, investment accounts, and loans
     * 
     * @param categoryString Category string from CSV
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param amount Transaction amount
     * @param paymentChannel Payment channel (e.g., "ach")
     * @return Category name
     */
    /**
     * Parse category string to category name with sophisticated merchant name and description detection
     * Uses world's best practices: merchant name patterns, description analysis, fuzzy matching
     * Supports all account types: checking, savings, credit card, debit card, money market, investment, loan
     * Works globally: US, India, Europe, Asia, etc.
     * 
     * Public method for use by PDFImportService and ExcelImportService
     * 
     * @param categoryString Category string from CSV (may be null/empty)
     * @param description Transaction description
     * @param merchantName Merchant name (normalized)
     * @param amount Transaction amount
     * @param paymentChannel Payment channel (e.g., "ach", "pos", "online")
     * @param transactionTypeIndicator Debit/credit indicator from CSV (e.g., "DEBIT", "CREDIT", "DR", "CR")
     * @return Detected category name
     */
    public String parseCategory(String categoryString, String description, String merchantName, 
                                 BigDecimal amount, String paymentChannel, String transactionTypeIndicator) {
        // Legacy method signature - delegate to new signature with null context
        return parseCategory(categoryString, description, merchantName, amount, paymentChannel, 
                            transactionTypeIndicator, null, null, null);
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
     * @param transactionType Transaction type (INCOME, EXPENSE, INVESTMENT, LOAN) - null if not yet determined
     * @param accountType Account type (depository, credit, loan, investment, etc.) - null if not available
     * @param accountSubtype Account subtype (checking, savings, credit card, etc.) - null if not available
     * @return Detected category name
     */
    public String parseCategory(String categoryString, String description, String merchantName, 
                                 BigDecimal amount, String paymentChannel, String transactionTypeIndicator,
                                 String transactionType, String accountType, String accountSubtype) {
        // Use simplified version - delegate to parseCategorySimplified
        return parseCategorySimplified(categoryString, description, merchantName, amount, paymentChannel,
                                      transactionTypeIndicator, transactionType, accountType, accountSubtype);
    }
    
    /**
     * SIMPLIFIED category parser with context-aware rules grouped by transaction type
     * Structure:
     * 1. Early unambiguous checks (transaction-type independent)
     * 2. Context-aware rules by transaction type (INCOME, EXPENSE, INVESTMENT, LOAN)
     * 3. ML/Fuzzy matching (earlier in flow)
     * 4. Category string mapping fallback
     */
    private String parseCategorySimplified(String categoryString, String description, String merchantName,
                                          BigDecimal amount, String paymentChannel, String transactionTypeIndicator,
                                          String transactionType, String accountType, String accountSubtype) {
        // Input validation and normalization
        String safeCategoryString = categoryString != null ? categoryString.trim() : null;
        String safeDescription = description != null ? description.trim() : null;
        String safeMerchantName = merchantName != null ? merchantName.trim() : null;
        String safePaymentChannel = paymentChannel != null ? paymentChannel.trim() : null;
        String safeTransactionTypeIndicator = transactionTypeIndicator != null ? transactionTypeIndicator.trim() : null;
        String safeTransactionType = transactionType != null ? transactionType.trim().toUpperCase() : null;
        String safeAccountType = accountType != null ? accountType.trim().toLowerCase() : null;
        String safeAccountSubtype = accountSubtype != null ? accountSubtype.trim().toLowerCase() : null;
        
        // Validate amount
        BigDecimal safeAmount = amount;
        if (amount != null) {
            BigDecimal maxAmount = BigDecimal.valueOf(1_000_000_000);
            BigDecimal minAmount = BigDecimal.valueOf(-1_000_000_000);
            if (amount.compareTo(maxAmount) > 0 || amount.compareTo(minAmount) < 0) {
                logger.warn("parseCategorySimplified: Amount out of reasonable range: {}, using null", amount);
                safeAmount = null;
            }
        }
        
        // Check if investment account (used in multiple places)
        boolean isInvestmentAccount = safeAccountType != null && 
            (safeAccountType.contains("investment") || safeAccountType.contains("ira") || 
             safeAccountType.contains("401k") || safeAccountType.contains("hsa") || safeAccountType.contains("529"));
        
        // ========== STEP 1: Early Unambiguous Checks (Transaction-Type Independent) ==========
        
        // STEP 1a: Check for zero amount transactions FIRST (before any other checks)
        // CRITICAL: Zero amount transactions with fee descriptions should be 'other', not 'fee'
        // Even if category string is "fee", zero amounts should be 'other' if description contains fee
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) == 0) {
            // For zero amounts, check description first (description takes precedence over category string)
            if (safeDescription != null) {
                String descLower = safeDescription.toLowerCase();
                if (descLower.contains("fee") || descLower.contains("adjustment") || descLower.contains("correction")) {
                    logger.debug("parseCategorySimplified: Zero amount with fee/adjustment description → 'other'");
                    return "other";
                }
                if (descLower.contains("transfer")) {
                    logger.debug("parseCategorySimplified: Zero amount with transfer description → 'payment'");
                    return "payment";
                }
            }
            // If no description match, check category string mapping (but skip "fee" category for zero amounts)
            if (safeCategoryString != null && !safeCategoryString.trim().isEmpty()) {
                String categoryLower = safeCategoryString.toLowerCase();
                // Skip "fee" category for zero amounts - they should be "other"
                if (!categoryLower.equals("fee") && !categoryLower.equals("fee_transaction") && !categoryLower.equals("fee transaction")) {
                    String mapped = CATEGORY_MAP.get(categoryLower);
                    if (mapped != null && !mapped.equals("fee")) {
                        logger.debug("parseCategorySimplified: Zero amount with category → '{}'", mapped);
                        return mapped;
                    }
                }
            }
            logger.debug("parseCategorySimplified: Zero amount without clear category → 'other'");
            return "other";
        }
        
        // STEP 1b: Check for ACH_CREDIT deposits (before payment detection)
        // CRITICAL: Must come before payment checks to prevent ACH_CREDIT from being categorized as payment
        if (safeCategoryString != null && safeAmount != null) {
            String categoryLower = safeCategoryString.toLowerCase();
            if ((categoryLower.contains("ach_credit") || categoryLower.contains("ach credit")) &&
                safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                if (isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
                    logger.debug("🏷️ parseCategorySimplified: ACH_CREDIT salary → 'salary'");
                    return "salary";
                }
                logger.debug("🏷️ parseCategorySimplified: ACH_CREDIT → 'deposit'");
                return "deposit";
            }
        }
        
        // STEP 1c: Check for fees (FEE_TRANSACTION, safe deposit box, etc.)
        if (safeCategoryString != null) {
            String categoryLower = safeCategoryString.toLowerCase();
            if (categoryLower.contains("fee_transaction") || categoryLower.contains("fee transaction")) {
                logger.debug("🏷️ parseCategorySimplified: FEE_TRANSACTION → 'fee'");
                return "fee";
            }
        }
        // Check for safe deposit box in description
        if (safeDescription != null) {
            String descLower = safeDescription.toLowerCase();
            if (descLower.contains("safe deposit box") || descLower.contains("safedepositbox") ||
                descLower.contains("safe deposit") || descLower.contains("safety deposit box")) {
                logger.debug("🏷️ parseCategorySimplified: Safe deposit box → 'fee'");
                return "fee";
            }
        }
        
        // Cash withdrawal
        if (isCashWithdrawal(safeDescription, safeMerchantName, safeCategoryString, safeTransactionTypeIndicator, safePaymentChannel)) {
            logger.debug("🏷️ parseCategorySimplified: Cash withdrawal → 'cash'");
            return "cash";
        }
        if (safeDescription != null && safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) < 0) {
            String descLower = safeDescription.toLowerCase();
            if (descLower.contains("withdrawal") && !descLower.contains("investment") && 
                !descLower.contains("transfer") && !descLower.contains("payment")) {
                logger.debug("🏷️ parseCategorySimplified: Withdrawal → 'cash'");
                return "cash";
            }
        }
        
        // Check payment
        if (isCheckPayment(safeDescription, safeMerchantName, safeCategoryString, safeTransactionTypeIndicator)) {
            logger.debug("🏷️ parseCategorySimplified: Check payment → 'payment'");
            return "payment";
        }
        
        // STEP 0: Check for Airport Expenses (carts, chairs, parking, etc.) - CRITICAL: Must come BEFORE utilities
        // "SEATTLEAP CART/CHAIR" (Seattle Airport cart) should be "transportation", not "utilities"
        if (safeDescription != null || safeMerchantName != null) {
            String combined = ((safeMerchantName != null ? safeMerchantName : "") + " " +
                              (safeDescription != null ? safeDescription : "")).toLowerCase().trim();
            // Airport cart/chair rentals
            if ((combined.contains("seattleap") || combined.contains("seattle ap") || combined.contains("seattle airport")) &&
                (combined.contains("cart") || combined.contains("chair"))) {
                logger.debug("🏷️ parseCategorySimplified: Detected airport cart/chair → 'transportation'");
                return "transportation";
            }
            // General airport cart/chair patterns
            if (combined.contains("airport") && (combined.contains("cart") || combined.contains("chair"))) {
                logger.debug("🏷️ parseCategorySimplified: Detected airport cart/chair → 'transportation'");
                return "transportation";
            }
        }
        
        // STEP 1a: Check for Cable/Internet/Phone Providers - CRITICAL: Must come BEFORE credit card payment
        // Cable/internet/phone bills (e.g., "Comcast Payment", "Xfinity Mobile Payment") should be "utilities", not "payment"
        if (safeDescription != null || safeMerchantName != null) {
            String combined = ((safeMerchantName != null ? safeMerchantName : "") + " " + 
                              (safeDescription != null ? safeDescription : "")).toLowerCase().trim();
            // Cable/Internet Providers
            String[] cableInternetProviders = {
                "comcast", "xfinity", "xfinity mobile", "xfinitymobile",
                "spectrum", "charter", "charter spectrum",
                "cox", "cox communications",
                "optimum", "altice", "frontier", "frontier communications",
                "centurylink", "century link", "windstream", "suddenlink", "mediacom",
                "dish", "dish network", "directv", "direct tv",
                "att u-verse", "att uverse", "fios", "verizon fios"
            };
            for (String provider : cableInternetProviders) {
                if (combined.contains(provider)) {
                    logger.debug("🏷️ parseCategorySimplified: Detected cable/internet provider '{}' → 'utilities'", provider);
                    return "utilities";
                }
            }
            // Phone/Mobile Providers (excluding Xfinity Mobile which is already covered above)
            String[] phoneProviders = {
                "verizon wireless", "verizonwireless", "verizon",
                "at&t", "att", "at and t", "t-mobile", "tmobile", "t mobile",
                "sprint", "us cellular", "uscellular", "cricket", "cricket wireless",
                "boost mobile", "boostmobile", "metropcs", "metro pcs", "metropcs",
                "mint mobile", "mintmobile", "google fi", "googlefi", "visible",
                "straight talk", "straighttalk", "us mobile", "usmobile"
            };
            for (String provider : phoneProviders) {
                if (combined.contains(provider)) {
                    logger.debug("🏷️ parseCategorySimplified: Detected phone/mobile provider '{}' → 'utilities'", provider);
                    return "utilities";
                }
            }
        }
        
        // Utility bill payment
        if (isUtilityBillPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            logger.debug("🏷️ parseCategorySimplified: Utility bill payment → 'utilities'");
            return "utilities";
        }
        
        // CRITICAL: Interest/Dividend checks BEFORE credit card payment to avoid false matches
        // Interest (context-aware: investment vs income)
        if (isInterestTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            if ("INVESTMENT".equals(safeTransactionType) || isInvestmentAccount) {
                logger.debug("parseCategorySimplified: Interest (investment) → 'investmentInterest'");
                return "investmentInterest";
            }
            logger.debug("parseCategorySimplified: Interest → 'interest'");
            return "interest";
        }
        
        // Dividend (context-aware: investment vs income)
        if (isDividendTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            if ("INVESTMENT".equals(safeTransactionType) || isInvestmentAccount) {
                logger.debug("parseCategorySimplified: Dividend (investment) → 'investmentDividend'");
                return "investmentDividend";
            }
            logger.debug("parseCategorySimplified: Dividend → 'dividend'");
            return "dividend";
        }
        
        // RSU transactions
        if (isRSUTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            logger.debug("parseCategorySimplified: RSU transaction → 'rsu'");
            return "rsu";
        }
        
        // Credit card payment (AFTER interest/dividend to avoid false matches)
        if (isCreditCardPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            logger.debug("🏷️ parseCategorySimplified: Credit card payment → 'payment'");
            return "payment";
        }
        
        // Loan payment
        if (isLoanPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            String descLower = safeDescription != null ? safeDescription.toLowerCase() : "";
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            String combinedText = (merchantLower + " " + descLower).trim();
            
            if (combinedText.contains("escrow") || combinedText.contains("tax escrow") || 
                combinedText.contains("insurance escrow") || combinedText.contains("property tax") ||
                combinedText.contains("homeowners insurance") || combinedText.contains("hazard insurance")) {
                logger.debug("🏷️ parseCategorySimplified: Loan escrow → 'loanEscrow'");
                return "loanEscrow";
            }
            if (combinedText.contains("bill") || combinedText.contains("utility bill") ||
                combinedText.contains("electric bill") || combinedText.contains("water bill") ||
                combinedText.contains("cable bill") || combinedText.contains("internet bill")) {
                logger.debug("🏷️ parseCategorySimplified: Loan bills → 'loanBills'");
                return "loanBills";
            }
            logger.debug("🏷️ parseCategorySimplified: Loan payment → 'payment'");
            return "payment";
        }
        
        // Investment transfer (BEFORE account transfer to avoid false matches)
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) < 0 && 
            isInvestmentTransfer(safeDescription, safeMerchantName, safeCategoryString)) {
            logger.debug("🏷️ parseCategorySimplified: Investment transfer → 'investmentTransfer'");
            return "investmentTransfer";
        }
        
        // Account transfer (AFTER investment transfer check)
        if (isAccountTransfer(safeDescription, safeMerchantName, safeCategoryString)) {
            logger.debug("🏷️ parseCategorySimplified: Account transfer → 'transfer'");
            return "transfer";
        }
        if (safeDescription != null) {
            String descLower = safeDescription.toLowerCase();
            if ((descLower.contains("wire") || descLower.contains("international wire")) &&
                (descLower.contains("debit") || descLower.contains("transfer"))) {
                logger.debug("🏷️ parseCategorySimplified: Wire transfer → 'transfer'");
                return "transfer";
            }
        }
        
        // ========== STEP 2: Context-Aware Rules by Transaction Type ==========
        
        // Investment rules (check before type-specific rules since they can override)
        if ("INVESTMENT".equals(safeTransactionType) || isInvestmentAccount) {
            String investmentCategory = applyInvestmentRules(safeDescription, safeMerchantName, safeCategoryString, 
                                                           safeAmount, safeTransactionType, safeAccountType);
            if (investmentCategory != null) {
                return investmentCategory;
            }
        }
        
        // Type-specific rules
        if ("INCOME".equals(safeTransactionType) && safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
            String incomeCategory = applyIncomeRules(safeDescription, safeMerchantName, safeAmount, 
                                                     safePaymentChannel, safeAccountType, safeAccountSubtype);
            if (incomeCategory != null) {
                logger.debug("🏷️ parseCategorySimplified: Income category → '{}'", incomeCategory);
                return incomeCategory;
            }
        }
        
        if ("LOAN".equals(safeTransactionType)) {
            String loanCategory = applyLoanRules(safeDescription, safeMerchantName);
            if (loanCategory != null) {
                logger.debug("🏷️ parseCategorySimplified: Loan category → '{}'", loanCategory);
                return loanCategory;
            }
        }
        
        // ========== STEP 3: ML/Fuzzy Matching (Earlier in Flow) ==========
        
        if ((safeMerchantName != null && !safeMerchantName.isEmpty()) || 
            (safeDescription != null && !safeDescription.isEmpty())) {
            try {
                // Build combined string for airport expense checks
                String combined = ((safeMerchantName != null ? safeMerchantName : "") + " " +
                                  (safeDescription != null ? safeDescription : "")).toLowerCase().trim();
                
                EnhancedCategoryDetectionService.DetectionResult enhancedResult = 
                    enhancedCategoryDetection.detectCategory(safeMerchantName, safeDescription, safeAmount, 
                                                           safePaymentChannel, safeCategoryString);
                
                if (enhancedResult != null && enhancedResult.category != null) {
                    // CRITICAL: Reject utilities category if it's an airport expense
                    if ("utilities".equals(enhancedResult.category) &&
                        combined.contains("airport") && (combined.contains("cart") || combined.contains("chair"))) {
                        logger.debug("🏷️ parseCategorySimplified: Rejecting utilities match for airport cart/chair");
                    } else if ("utilities".equals(enhancedResult.category) &&
                               (combined.contains("seattleap") || combined.contains("seattle ap")) &&
                               (combined.contains("cart") || combined.contains("chair"))) {
                        logger.debug("🏷️ parseCategorySimplified: Rejecting utilities match for SEATTLEAP cart/chair");
                    } else {
                        double confidenceThreshold = "FUZZY_MATCH".equals(enhancedResult.method) ? 0.50 : 0.55;
                        if (enhancedResult.confidence >= confidenceThreshold) {
                            logger.debug("🏷️ parseCategorySimplified: ML/Fuzzy match → '{}' (confidence: {}, method: {})", 
                                    enhancedResult.category, String.format("%.2f", enhancedResult.confidence), enhancedResult.method);
                            return enhancedResult.category;
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("parseCategorySimplified: ML detection failed (non-fatal): {}", e.getMessage());
            }
        }
        
        // ========== STEP 4: Merchant/Description Matching (Simplified) ==========
        
        if (safeMerchantName != null && !safeMerchantName.trim().isEmpty()) {
            String merchantCategory = detectCategoryFromMerchantName(safeMerchantName, safeDescription);
            if (merchantCategory != null) {
                logger.debug("🏷️ parseCategorySimplified: Merchant match → '{}'", merchantCategory);
                return merchantCategory;
            }
        }
        
        String descriptionCategory = detectCategoryFromDescription(safeDescription, safeMerchantName, safeAmount);
        if (descriptionCategory != null) {
            logger.debug("🏷️ parseCategorySimplified: Description match → '{}'", descriptionCategory);
            return descriptionCategory;
        }
        
        // ========== STEP 5: ACH/Deposit Rules ==========
        
        if (isACHTransaction(safeDescription, safePaymentChannel)) {
            if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                if (isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
                    logger.debug("parseCategorySimplified: ACH salary → 'salary'");
                    return "salary";
                }
                logger.debug("parseCategorySimplified: ACH deposit → 'deposit'");
                return "deposit";
            }
        }
        
        if (safeCategoryString != null && safeAmount != null) {
            String categoryLower = safeCategoryString.toLowerCase();
            if ((categoryLower.contains("ach_credit") || categoryLower.contains("ach credit")) &&
                safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("🏷️ parseCategorySimplified: ACH_CREDIT → 'deposit'");
                return "deposit";
            }
        }
        
        // ========== STEP 6: Category String Mapping Fallback ==========
        
        if (safeCategoryString != null && !safeCategoryString.isEmpty()) {
            String lower = safeCategoryString.toLowerCase();
            String exactMatch = CATEGORY_MAP.get(lower);
            if (exactMatch != null) {
                logger.debug("parseCategorySimplified: Category map exact match → '{}'", exactMatch);
                return exactMatch;
            }
            
            // Substring match (longest first)
            List<Map.Entry<String, String>> sortedEntries = new ArrayList<>(CATEGORY_MAP.entrySet());
            sortedEntries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
            
            for (Map.Entry<String, String> entry : sortedEntries) {
                String key = entry.getKey();
                if (lower.contains("ach_credit") || lower.contains("ach credit")) {
                    if (key.equals("credit")) continue; // Skip "credit" → "income" for ACH_CREDIT
                }
                if (lower.contains(key)) {
                    logger.debug("parseCategorySimplified: Category map substring match → '{}'", entry.getValue());
                    return entry.getValue();
                }
            }
        }
        
        // ========== STEP 7: Zero Amount Handling ==========
        
        // ========== STEP 8: Final Fallback ==========
        
        logger.debug("🏷️ parseCategorySimplified: No match found → 'other'");
        return "other";
    }
    
    /**
     * Apply investment-specific category rules
     */
    private String applyInvestmentRules(String description, String merchantName, String categoryString,
                                       BigDecimal amount, String transactionType, String accountType) {
        if (amount == null) return null;
        
        String descLower = description != null ? description.toLowerCase() : "";
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String combinedText = (merchantLower + " " + descLower).trim();
        
        // Investment fees (negative amounts)
        if (amount.compareTo(BigDecimal.ZERO) < 0 && 
            (combinedText.contains("fee") || combinedText.contains("management fee") || 
             combinedText.contains("advisory fee") || combinedText.contains("custodian fee") ||
             combinedText.contains("account fee") || combinedText.contains("administrative fee") ||
             combinedText.contains("expense ratio") || combinedText.contains("expense fee"))) {
            return "investmentFees";
        }
        
        // Investment purchase (negative amounts)
        if (amount.compareTo(BigDecimal.ZERO) < 0 && 
            (combinedText.contains("purchase") || combinedText.contains("buy") || 
             combinedText.contains("contribution") || combinedText.contains("deposit")) &&
            !combinedText.contains("fee") && !combinedText.contains("transfer")) {
            return "investmentPurchase";
        }
        
        // Investment sale (positive amounts)
        if (amount.compareTo(BigDecimal.ZERO) > 0 && 
            (combinedText.contains("sale") || combinedText.contains("sell") || 
             combinedText.contains("redemption") || combinedText.contains("withdrawal") ||
             combinedText.contains("distribution") || combinedText.contains("proceeds"))) {
            return "investmentSold";
        }
        
        // Deposit from investment firm (positive amounts)
        if (amount.compareTo(BigDecimal.ZERO) > 0) {
            String[] investmentFirms = {
                "morgan stanley", "morganstanley", "fidelity", "vanguard", "schwab", "charles schwab",
                "td ameritrade", "etrade", "robinhood", "merrill lynch", "goldman sachs"
            };
            for (String firm : investmentFirms) {
                if ((descLower.contains(firm) || merchantLower.contains(firm)) &&
                    (descLower.contains("transfer") || descLower.contains("from") || descLower.contains("deposit"))) {
                    return "deposit";
                }
            }
        }
        
        return null;
    }
    
    /**
     * Apply income-specific category rules
     */
    private String applyIncomeRules(String description, String merchantName, BigDecimal amount,
                                   String paymentChannel, String accountType, String accountSubtype) {
        // Use existing helper method
        return determineIncomeCategoryFromContext(description, merchantName, amount, paymentChannel, accountType, accountSubtype);
    }
    
    /**
     * Apply loan-specific category rules
     */
    private String applyLoanRules(String description, String merchantName) {
        // Loan-specific rules are already handled in early checks (loanEscrow, loanBills, payment)
        // This is mainly for any additional loan-specific logic
        return null;
    }
    
    /**
     * LEGACY parseCategory implementation - kept for reference/testing
     * This is the original complex implementation with 480+ lines
     */
    @SuppressWarnings("unused")
    private String parseCategoryLegacy(String categoryString, String description, String merchantName,
                                       BigDecimal amount, String paymentChannel, String transactionTypeIndicator,
                                       String transactionType, String accountType, String accountSubtype) {
        // CRITICAL: Input validation and null safety
        // Normalize inputs to prevent null pointer exceptions
        String safeCategoryString = categoryString != null ? categoryString.trim() : null;
        String safeDescription = description != null ? description.trim() : null;
        String safeMerchantName = merchantName != null ? merchantName.trim() : null;
        String safePaymentChannel = paymentChannel != null ? paymentChannel.trim() : null;
        // NOTE: safeTransactionTypeIndicator is normalized but passed to determineTransactionType which does its own normalization
        // However, it IS used in isCashWithdrawal and isCheckPayment checks below
        String safeTransactionTypeIndicator = transactionTypeIndicator != null ? transactionTypeIndicator.trim() : null;
        String safeTransactionType = transactionType != null ? transactionType.trim().toUpperCase() : null;
        String safeAccountType = accountType != null ? accountType.trim().toLowerCase() : null;
        String safeAccountSubtype = accountSubtype != null ? accountSubtype.trim().toLowerCase() : null;
        
        // CRITICAL: Validate amount is reasonable (prevent overflow/underflow issues)
        BigDecimal safeAmount = amount;
        if (amount != null) {
            // Check for extreme values that might cause issues
            BigDecimal maxAmount = BigDecimal.valueOf(1_000_000_000); // 1 billion
            BigDecimal minAmount = BigDecimal.valueOf(-1_000_000_000);
            if (amount.compareTo(maxAmount) > 0 || amount.compareTo(minAmount) < 0) {
                logger.warn("parseCategory: Amount out of reasonable range: {}, using null for safety", amount);
                safeAmount = null;
            }
        }
        
        // STEP 1a: Check for ACH_CREDIT deposits FIRST (before payment detection)
        // CRITICAL: Must come before payment checks to prevent ACH_CREDIT from being categorized as payment
        if (safeCategoryString != null && safeAmount != null) {
            String categoryLower = safeCategoryString.toLowerCase();
            if ((categoryLower.contains("ach_credit") || categoryLower.contains("ach credit")) &&
                safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                if (isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
                    logger.debug("🏷️ parseCategory: ACH_CREDIT salary → 'salary'");
                    return "salary";
                }
                logger.debug("🏷️ parseCategory: ACH_CREDIT → 'deposit'");
                return "deposit";
            }
        }
        
        // STEP 1b: Check for fees (FEE_TRANSACTION, safe deposit box, etc.)
        if (safeCategoryString != null) {
            String categoryLower = safeCategoryString.toLowerCase();
            if (categoryLower.contains("fee_transaction") || categoryLower.contains("fee transaction")) {
                logger.debug("🏷️ parseCategory: FEE_TRANSACTION → 'fee'");
                return "fee";
            }
        }
        // Check for safe deposit box in description
        if (safeDescription != null) {
            String descLower = safeDescription.toLowerCase();
            if (descLower.contains("safe deposit box") || descLower.contains("safedepositbox") ||
                descLower.contains("safe deposit") || descLower.contains("safety deposit box")) {
                logger.debug("🏷️ parseCategory: Safe deposit box → 'fee'");
                return "fee";
            }
        }
        
        // STEP 1c: Check for Cash Withdrawal - CRITICAL FIX: Must come before payment checks
        // STEP 0: Check for Airport Expenses (carts, chairs, parking, etc.) - CRITICAL: Must come BEFORE utilities
        // "SEATTLEAP CART/CHAIR" (Seattle Airport cart) should be "transportation", not "utilities"
        if (safeDescription != null || safeMerchantName != null) {
            String combined = ((safeMerchantName != null ? safeMerchantName : "") + " " +
                              (safeDescription != null ? safeDescription : "")).toLowerCase().trim();
            // Airport cart/chair rentals
            if ((combined.contains("seattleap") || combined.contains("seattle ap") || combined.contains("seattle airport")) &&
                (combined.contains("cart") || combined.contains("chair"))) {
                logger.debug("🏷️ parseCategory: Detected airport cart/chair → 'transportation'");
                return "transportation";
            }
            // General airport cart/chair patterns
            if (combined.contains("airport") && (combined.contains("cart") || combined.contains("chair"))) {
                logger.debug("🏷️ parseCategory: Detected airport cart/chair → 'transportation'");
                return "transportation";
            }
        }
        
        // Cash withdrawals should be type EXPENSE, category "cash"
        // CRITICAL: Also check for "withdrawal" keyword (standalone)
        if (isCashWithdrawal(safeDescription, safeMerchantName, safeCategoryString, safeTransactionTypeIndicator, safePaymentChannel)) {
            logger.debug("🏷️ parseCategory: Detected cash withdrawal → 'cash'");
            return "cash";
        }
        
        // CRITICAL FIX: Check for standalone "withdrawal" keyword (not just "cash withdrawal")
        if (safeDescription != null && safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) < 0) {
            String descLower = safeDescription.toLowerCase();
            if (descLower.contains("withdrawal") && !descLower.contains("investment") && 
                !descLower.contains("transfer") && !descLower.contains("payment")) {
                logger.debug("🏷️ parseCategory: Detected withdrawal (negative amount) → 'cash'");
                return "cash";
            }
        }
        
        // STEP 1b: Check for Check Payment - CRITICAL FIX: Must come before merchant name detection
        // Check payments should be "payment", not transportation (e.g., "CHECK 176" was matching gas station "76")
        if (isCheckPayment(safeDescription, safeMerchantName, safeCategoryString, safeTransactionTypeIndicator)) {
            logger.debug("🏷️ parseCategory: Detected check payment → 'payment'");
            return "payment";
        }
        
        // STEP 1a: Check for Cable/Internet/Phone Providers - CRITICAL: Must come BEFORE credit card payment
        // Cable/internet/phone bills (e.g., "Comcast Payment", "Xfinity Mobile Payment") should be "utilities", not "payment"
        // This must come before credit card payment to prevent "e payment" in "Mobile Payment" from matching
        if (safeDescription != null || safeMerchantName != null) {
            String combined = ((safeMerchantName != null ? safeMerchantName : "") + " " + 
                              (safeDescription != null ? safeDescription : "")).toLowerCase().trim();
            // Cable/Internet Providers
            String[] cableInternetProviders = {
                "comcast", "xfinity", "xfinity mobile", "xfinitymobile",
                "spectrum", "charter", "charter spectrum",
                "cox", "cox communications",
                "optimum", "altice", "frontier", "frontier communications",
                "centurylink", "century link", "windstream", "suddenlink", "mediacom",
                "dish", "dish network", "directv", "direct tv",
                "att u-verse", "att uverse", "fios", "verizon fios"
            };
            for (String provider : cableInternetProviders) {
                if (combined.contains(provider)) {
                    logger.debug("🏷️ parseCategory: Detected cable/internet provider '{}' → 'utilities'", provider);
                    return "utilities";
                }
            }
            // Phone/Mobile Providers (excluding Xfinity Mobile which is already covered above)
            String[] phoneProviders = {
                "verizon wireless", "verizonwireless", "verizon",
                "at&t", "att", "at and t", "t-mobile", "tmobile", "t mobile",
                "sprint", "us cellular", "uscellular", "cricket", "cricket wireless",
                "boost mobile", "boostmobile", "metropcs", "metro pcs", "metropcs",
                "mint mobile", "mintmobile", "google fi", "googlefi", "visible",
                "straight talk", "straighttalk", "us mobile", "usmobile"
            };
            for (String provider : phoneProviders) {
                if (combined.contains(provider)) {
                    logger.debug("🏷️ parseCategory: Detected phone/mobile provider '{}' → 'utilities'", provider);
                    return "utilities";
                }
            }
        }
        
        // STEP 1b: Check for Utility Bill Payment - CRITICAL FIX: Must come BEFORE credit card payment
        // Direct payments to utility companies (e.g., "PUGET SOUND ENER BILLPAY") should be "utilities", not "payment"
        // This is different from credit card payments - utility bills are actual expenses, not payments for expenses already counted
        if (isUtilityBillPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            logger.debug("🏷️ parseCategory: Detected utility bill payment → 'utilities'");
            return "utilities";
        }
        
        // STEP 1c: Check for Interest Income - CRITICAL: Must come BEFORE credit card payment to avoid false matches
        // Interest payments should be "interest" (income) or "investmentInterest" (investment), not "payment" (expense)
        if (isInterestTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            // Context-aware: If transaction type is INVESTMENT or account is investment, use investmentInterest
            if ("INVESTMENT".equals(safeTransactionType) || 
                (safeAccountType != null && (safeAccountType.contains("investment") || safeAccountType.contains("ira") || 
                 safeAccountType.contains("401k") || safeAccountType.contains("hsa") || safeAccountType.contains("529")))) {
                logger.debug("parseCategory: Detected interest transaction (investment account) → 'investmentInterest'");
                return "investmentInterest";
            }
            logger.debug("parseCategory: Detected interest transaction → 'interest'");
            return "interest";
        }
        
        // STEP 1c: Check for Dividend Income - CRITICAL: Must come BEFORE credit card payment to avoid false matches
        // Dividend payments should be "dividend" (income) or "investmentDividend" (investment), not "payment" (expense) or generic "investment"
        if (isDividendTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            // Context-aware: If transaction type is INVESTMENT or account is investment, use investmentDividend
            if ("INVESTMENT".equals(safeTransactionType) || 
                (safeAccountType != null && (safeAccountType.contains("investment") || safeAccountType.contains("ira") || 
                 safeAccountType.contains("401k") || safeAccountType.contains("hsa") || safeAccountType.contains("529")))) {
                logger.debug("parseCategory: Detected dividend transaction (investment account) → 'investmentDividend'");
                return "investmentDividend";
            }
            logger.debug("parseCategory: Detected dividend transaction → 'dividend'");
            return "dividend";
        }
        
        // STEP 1d: Check for Credit Card Payment - CRITICAL FIX: Must come early, not just for ACH
        // Credit card payments should be "payment" (expense), not "other" or "deposit" (income)
        // This includes ACH autopay, online payments, etc.
        // CRITICAL: Check this BEFORE merchant name detection to avoid false matches (e.g., "CHASE CREDIT CRD" matching transportation)
        // CRITICAL: But AFTER interest/dividend check to avoid false positives (e.g., "Interest payment" matching credit card)
        if (isCreditCardPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            logger.debug("🏷️ parseCategory: Detected credit card payment → 'payment'");
            return "payment";
        } else {
            // Log why it wasn't detected for debugging
            logger.debug("🏷️ parseCategory: Credit card payment check returned false for description='{}', merchant='{}', category='{}'",
                    safeDescription, safeMerchantName, safeCategoryString);
        }
        
        // STEP 1e: Check for Loan Payment/Escrow/Bills - CRITICAL FIX: Must come early
        // Loan payments (mortgage, auto loan, student loan) should be "payment", "loanEscrow", or "loanBills"
        if (isLoanPayment(safeDescription, safeMerchantName, safeCategoryString)) {
            // Context-aware: Check if it's escrow or bills
            String descLower = safeDescription != null ? safeDescription.toLowerCase() : "";
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            String combinedText = (merchantLower + " " + descLower).trim();
            
            // Check for escrow keywords
            if (combinedText.contains("escrow") || combinedText.contains("tax escrow") || 
                combinedText.contains("insurance escrow") || combinedText.contains("property tax") ||
                combinedText.contains("homeowners insurance") || combinedText.contains("hazard insurance")) {
                logger.debug("🏷️ parseCategory: Detected loan escrow → 'loanEscrow'");
                return "loanEscrow";
            }
            
            // Check for bill payment keywords (utilities, etc. paid through loan)
            if (combinedText.contains("bill") || combinedText.contains("utility bill") ||
                combinedText.contains("electric bill") || combinedText.contains("water bill") ||
                combinedText.contains("cable bill") || combinedText.contains("internet bill")) {
                logger.debug("🏷️ parseCategory: Detected loan bills → 'loanBills'");
                return "loanBills";
            }
            
            logger.debug("🏷️ parseCategory: Detected loan payment → 'payment'");
            return "payment";
        }
        
        // STEP 1f: Check for Investment Transfer - CRITICAL FIX: Must come before regular account transfer
        // Investment transfers (from/to investment firms like Morgan Stanley, Fidelity, etc.) should be "investmentTransfer"
        // CRITICAL: Only for DEBITS (money going out). Credits should be "deposit"
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) < 0) {
            // Only check for investment transfer if amount is negative (debit)
            if (isInvestmentTransfer(safeDescription, safeMerchantName, safeCategoryString)) {
                logger.debug("🏷️ parseCategory: Detected investment transfer (debit) → 'investmentTransfer'");
                return "investmentTransfer";
            }
        }
        
        // STEP 1f0: Check for Investment Fees - CRITICAL: Must come before other investment checks
        // Investment fees (negative amounts on investment accounts) should be "investmentFees"
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) < 0 && 
            ("INVESTMENT".equals(safeTransactionType) || 
             (safeAccountType != null && (safeAccountType.contains("investment") || safeAccountType.contains("ira") || 
              safeAccountType.contains("401k") || safeAccountType.contains("hsa") || safeAccountType.contains("529"))))) {
            String descLower = safeDescription != null ? safeDescription.toLowerCase() : "";
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            String combinedText = (merchantLower + " " + descLower).trim();
            
            // Check for fee keywords
            if (combinedText.contains("fee") || combinedText.contains("management fee") || 
                combinedText.contains("advisory fee") || combinedText.contains("custodian fee") ||
                combinedText.contains("account fee") || combinedText.contains("administrative fee") ||
                combinedText.contains("expense ratio") || combinedText.contains("expense fee") ||
                (combinedText.contains("annual") && combinedText.contains("fee"))) {
                logger.debug("🏷️ parseCategory: Detected investment fee → 'investmentFees'");
                return "investmentFees";
            }
        }
        
        // STEP 1f1: Check for Investment Purchase - CRITICAL: Negative amounts on investment accounts
        // Investment purchases (negative amounts on investment accounts) should be "investmentPurchase"
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) < 0 && 
            ("INVESTMENT".equals(safeTransactionType) || 
             (safeAccountType != null && (safeAccountType.contains("investment") || safeAccountType.contains("ira") || 
              safeAccountType.contains("401k") || safeAccountType.contains("hsa") || safeAccountType.contains("529"))))) {
            String descLower = safeDescription != null ? safeDescription.toLowerCase() : "";
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            String combinedText = (merchantLower + " " + descLower).trim();
            
            // Check for purchase keywords (but not fees or transfers)
            if ((combinedText.contains("purchase") || combinedText.contains("buy") || 
                 combinedText.contains("contribution") || combinedText.contains("deposit")) &&
                !combinedText.contains("fee") && !combinedText.contains("transfer")) {
                logger.debug("🏷️ parseCategory: Detected investment purchase → 'investmentPurchase'");
                return "investmentPurchase";
            }
        }
        
        // STEP 1f2: Check for Investment Sold - CRITICAL: Positive amounts on investment accounts
        // Investment sales (positive amounts on investment accounts) should be "investmentSold"
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0 && 
            ("INVESTMENT".equals(safeTransactionType) || 
             (safeAccountType != null && (safeAccountType.contains("investment") || safeAccountType.contains("ira") || 
              safeAccountType.contains("401k") || safeAccountType.contains("hsa") || safeAccountType.contains("529"))))) {
            String descLower = safeDescription != null ? safeDescription.toLowerCase() : "";
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            String combinedText = (merchantLower + " " + descLower).trim();
            
            // Check for sale keywords
            if (combinedText.contains("sale") || combinedText.contains("sell") || 
                combinedText.contains("redemption") || combinedText.contains("withdrawal") ||
                combinedText.contains("distribution") || combinedText.contains("proceeds")) {
                logger.debug("🏷️ parseCategory: Detected investment sale → 'investmentSold'");
                return "investmentSold";
            }
        }
        
        // STEP 1f1: Check for Deposit from Investment Firm - CRITICAL FIX
        // Credits from investment firms (e.g., "Online transfer from Morgan Stanley" with positive amount) should be "deposit"
        if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
            String descLower = safeDescription != null ? safeDescription.toLowerCase() : "";
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            String[] investmentFirms = {
                "morgan stanley", "morganstanley", "fidelity", "vanguard", "schwab", "charles schwab",
                "td ameritrade", "etrade", "robinhood", "merrill lynch", "goldman sachs"
            };
            for (String firm : investmentFirms) {
                if ((descLower.contains(firm) || merchantLower.contains(firm)) &&
                    (descLower.contains("transfer") || descLower.contains("from") || descLower.contains("deposit"))) {
                    logger.debug("🏷️ parseCategory: Detected deposit from investment firm '{}' (credit) → 'deposit'", firm);
                    return "deposit";
                }
            }
        }
        
        // STEP 1g: Check for Account Transfer - CRITICAL FIX: Must come early
        // Account transfers (ACCT_XFER, Online Transfer to CHK, etc.) should be "transfer", not "other"
        // CRITICAL: Also check for wire transfers, international wires, money transfer services
        if (isAccountTransfer(safeDescription, safeMerchantName, safeCategoryString)) {
            logger.debug("🏷️ parseCategory: Detected account transfer → 'transfer'");
            return "transfer";
        }
        
        // CRITICAL FIX: Check for wire transfers and international wires
        if (safeDescription != null) {
            String descLower = safeDescription.toLowerCase();
            if ((descLower.contains("wire") || descLower.contains("international wire")) &&
                (descLower.contains("debit") || descLower.contains("transfer"))) {
                logger.debug("🏷️ parseCategory: Detected wire transfer → 'transfer'");
                return "transfer";
            }
        }
        
        // STEP 2: Check for Salary/Payroll (highest priority for income)
        if (isInterestTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            logger.debug("parseCategory: Detected interest transaction → 'interest'");
            return "interest";
        }
        
        // STEP 3: Check for Dividend Income - CRITICAL: Must come before salary to catch dividend payments
        if (isDividendTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            logger.debug("parseCategory: Detected dividend transaction → 'dividend'");
            return "dividend";
        }
        
        // STEP 3: Context-aware Income Category Detection
        // For INCOME type transactions, determine specific income category (salary, deposit, dividend, interest)
        if ("INCOME".equals(safeTransactionType) && safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
            String incomeCategory = determineIncomeCategoryFromContext(
                safeDescription, safeMerchantName, safeAmount, safePaymentChannel, 
                safeAccountType, safeAccountSubtype);
            if (incomeCategory != null) {
                logger.debug("🏷️ parseCategory: Context-aware income category detected → '{}'", incomeCategory);
                return incomeCategory;
            }
        }
        
        // STEP 3a: Check for Salary/Payroll (highest priority for income)
        // CRITICAL FIX: Include Amazon.com SVCS Payroll and other payroll patterns
        if (isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
            logger.debug("parseCategory: Detected salary transaction → 'salary'");
            return "salary";
        }
        
        // CRITICAL FIX: Check for specific payroll patterns
        if (safeDescription != null && safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
            String descLower = safeDescription.toLowerCase();
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            if ((descLower.contains("amazon.com svcs") || descLower.contains("amazon.com services") ||
                 merchantLower.contains("amazon.com svcs") || merchantLower.contains("amazon.com services")) &&
                (descLower.contains("payroll") || descLower.contains("salary") || descLower.contains("pay"))) {
                logger.debug("🏷️ parseCategory: Detected Amazon.com SVCS Payroll → 'salary'");
                return "salary";
            }
        }
        
        // STEP 3.5: Check for Property Tax - CRITICAL FIX
        // Property tax (e.g., Santa Clara DTAC) should be "other" (no specific property tax category)
        if (safeDescription != null || safeMerchantName != null) {
            String descLower = safeDescription != null ? safeDescription.toLowerCase() : "";
            String merchantLower = safeMerchantName != null ? safeMerchantName.toLowerCase() : "";
            if (descLower.contains("property tax") || descLower.contains("santa clara dtac") ||
                merchantLower.contains("property tax") || merchantLower.contains("santa clara dtac") ||
                descLower.contains("dtac") || merchantLower.contains("dtac")) {
                logger.debug("🏷️ parseCategory: Detected property tax → 'other'");
                return "other"; // Property tax category - user can override if needed
            }
        }
        
        // STEP 4: Check for RSU transactions
        if (isRSUTransaction(safeCategoryString, safeDescription, safeMerchantName, safeAmount)) {
            logger.debug("parseCategory: Detected RSU transaction → 'rsu'");
            return "rsu";
        }
        
        // STEP 4: Sophisticated Merchant Name-Based Detection (NEW - Global Scale)
        // This is the key enhancement - merchant names are more reliable than category strings
        // CRITICAL FIX: Skip merchant name detection when merchantName is null/empty to save resources
        String merchantCategory = null;
        if (safeMerchantName != null && !safeMerchantName.trim().isEmpty()) {
            merchantCategory = detectCategoryFromMerchantName(safeMerchantName, safeDescription);
            if (merchantCategory != null) {
                logger.debug("🏷️ parseCategory: Detected category from merchant name '{}' → '{}'", safeMerchantName, merchantCategory);
                return merchantCategory;
            }
        } else {
            logger.debug("🏷️ parseCategory: Skipping merchant name detection - merchantName is null/empty");
        }
        
        // STEP 5: Description-Based Category Detection (NEW - Fallback when merchant name fails)
        String descriptionCategory = detectCategoryFromDescription(safeDescription, safeMerchantName, safeAmount);
        if (descriptionCategory != null) {
            logger.debug("🏷️ parseCategory: Detected category from description '{}' → '{}'", safeDescription, descriptionCategory);
            return descriptionCategory;
        }
        
        // STEP 6: Enhanced Category Detection (ML + Fuzzy Matching) - CRITICAL FIX: This was missing!
        // Only use if we have merchant name or description (ML needs some input)
        // NOTE: safeAmount is already validated at the beginning of the method
        if ((safeMerchantName != null && !safeMerchantName.isEmpty()) || 
            (safeDescription != null && !safeDescription.isEmpty())) {
            try {
                logger.debug("🏷️ parseCategory: Attempting enhanced detection - merchant='{}', description='{}', amount={}, channel='{}', category='{}'",
                        safeMerchantName, safeDescription, safeAmount, safePaymentChannel, safeCategoryString);
                
                EnhancedCategoryDetectionService.DetectionResult enhancedResult = 
                    enhancedCategoryDetection.detectCategory(safeMerchantName, safeDescription, safeAmount, safePaymentChannel, safeCategoryString);
                
                // CRITICAL: Log the result for debugging
                if (enhancedResult == null) {
                    logger.debug("🏷️ parseCategory: Enhanced detection returned null result");
                } else if (enhancedResult.category == null) {
                    logger.debug("🏷️ parseCategory: Enhanced detection returned null category (method: {}, confidence: {})",
                            enhancedResult.method, String.format("%.2f", enhancedResult.confidence));
                } else {
                    logger.debug("🏷️ parseCategory: Enhanced detection result - category='{}', confidence={}, method='{}', reason='{}'",
                            enhancedResult.category, String.format("%.2f", enhancedResult.confidence), enhancedResult.method, 
                            enhancedResult.reason != null ? enhancedResult.reason : "N/A");
                }
                
                // CRITICAL: Use enhanced detection if confidence is reasonable
                // For FUZZY_MATCH on known merchants, use lower threshold (0.50) since these are deterministic matches
                // For other methods (SEMANTIC_MATCH, ML_PREDICTION), use higher threshold (0.55)
                if (enhancedResult != null && enhancedResult.category != null) {
                    double confidenceThreshold = "FUZZY_MATCH".equals(enhancedResult.method) ? 0.50 : 0.55;
                    
                    if (enhancedResult.confidence >= confidenceThreshold) {
                        logger.debug("🏷️ parseCategory: ✅ Enhanced detection (ML/Fuzzy) found: '{}' (confidence: {}, method: {}, threshold: {}) - RETURNING THIS CATEGORY", 
                                enhancedResult.category, String.format("%.2f", enhancedResult.confidence), enhancedResult.method, String.format("%.2f", confidenceThreshold));
                        // NOTE: Do NOT train model during preview - only during actual import
                        // Training happens in TransactionService when transaction is successfully created
                        return enhancedResult.category;
                    } else {
                        logger.debug("🏷️ parseCategory: Enhanced detection found '{}' but confidence too low ({} < {}), continuing to fallback methods", 
                                enhancedResult.category, String.format("%.2f", enhancedResult.confidence), String.format("%.2f", confidenceThreshold));
                    }
                } else if (enhancedResult == null) {
                    logger.debug("🏷️ parseCategory: Enhanced detection returned null - no match found");
                }
            } catch (Exception e) {
                // CRITICAL: Don't fail category detection if ML/fuzzy matching fails
                // Log error but continue to fallback methods
                logger.warn("🏷️ parseCategory: Enhanced category detection failed (non-fatal): {} - stack trace: {}", 
                        e.getMessage(), e.getClass().getSimpleName());
                logger.debug("🏷️ parseCategory: Enhanced detection exception details", e);
            }
        } else {
            logger.debug("🏷️ parseCategory: Skipping enhanced detection - both merchantName and description are null/empty");
        }
        
        // STEP 7: Check for zero amount transactions (boundary condition)
        // Zero amounts are ambiguous - use category/description to determine type
        if (amount != null && amount.compareTo(BigDecimal.ZERO) == 0) {
            logger.debug("parseCategory: Zero amount transaction detected, using category/description inference");
            // Zero amounts are typically fees, adjustments, or transfers
            // If we have a category hint, use it; otherwise default to "other"
            if (categoryString != null && !categoryString.trim().isEmpty()) {
                String lower = categoryString.toLowerCase();
                String mapped = CATEGORY_MAP.get(lower);
                if (mapped != null) {
                    logger.debug("parseCategory: Zero amount with category hint '{}' → '{}'", categoryString, mapped);
                    return mapped;
                }
            }
            // For zero amounts without clear category, check description for hints
            if (description != null && !description.trim().isEmpty()) {
                String descLower = description.toLowerCase();
                if (descLower.contains("fee") || descLower.contains("adjustment") || descLower.contains("correction")) {
                    logger.debug("parseCategory: Zero amount with fee/adjustment description → 'other'");
                    return "other";
                }
                if (descLower.contains("transfer")) {
                    logger.debug("parseCategory: Zero amount with transfer description → 'payment'");
                    return "payment";
                }
            }
            // Default for zero amounts
            logger.debug("parseCategory: Zero amount without clear category → 'other'");
            return "other";
        }
        
        // STEP 8: Check for ACH transactions (after merchant/description checks)
        if (isACHTransaction(safeDescription, safePaymentChannel)) {
            // If ACH is positive and looks like salary, categorize as salary
            if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0 && 
                isSalaryTransaction(safeDescription, safeAmount, safePaymentChannel)) {
                logger.debug("parseCategory: Detected ACH salary transaction → 'salary'");
                return "salary";
            }
            // Otherwise, ACH positive transactions are typically deposits (but not credit card payments - already handled)
            if (safeAmount != null && safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                logger.debug("parseCategory: Detected ACH deposit transaction → 'deposit'");
                return "deposit";
            }
        }
        
        // STEP 9: Check for ACH_CREDIT specifically (before category map lookup)
        // CRITICAL: ACH_CREDIT with positive amount should be "deposit", not "income" (unless specifically identified as salary, interest, etc.)
        // This prevents "ACH_CREDIT" from matching "credit" → "income" in the category map
        if (safeCategoryString != null && safeAmount != null) {
            String categoryLower = safeCategoryString.toLowerCase();
            if ((categoryLower.contains("ach_credit") || categoryLower.contains("ach credit")) &&
                safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                // Already checked for salary in STEP 8, so this is a generic deposit
                logger.debug("🏷️ parseCategory: Detected ACH_CREDIT with positive amount → 'deposit'");
                return "deposit";
            }
        }
        
        // STEP 10: Fall back to standard category string parsing (using static map for performance)
        if (safeCategoryString != null && !safeCategoryString.isEmpty()) {
            String lower = safeCategoryString.toLowerCase();
            
            // CRITICAL: First try exact match (most reliable)
            String exactMatch = CATEGORY_MAP.get(lower);
            if (exactMatch != null) {
                logger.debug("parseCategory: Exact category string match '{}' → '{}'", categoryString, exactMatch);
                return exactMatch;
            }
            
            // Then try substring match (less reliable but handles variations)
            // CRITICAL: Use longest match first to avoid partial matches (e.g., "gas" matching "gas station" before "gas station")
            // CRITICAL: Exclude "credit" from matching "ACH_CREDIT" to prevent false "income" categorization
            List<Map.Entry<String, String>> sortedEntries = new ArrayList<>(CATEGORY_MAP.entrySet());
            sortedEntries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length())); // Sort by length descending
            
            for (Map.Entry<String, String> entry : sortedEntries) {
                String key = entry.getKey();
                // CRITICAL: Skip "credit" keyword if categoryString contains "ACH_CREDIT" to prevent false "income" match
                if (lower.contains("ach_credit") || lower.contains("ach credit")) {
                    if (key.equals("credit")) {
                        continue; // Skip "credit" → "income" mapping for ACH_CREDIT
                    }
                }
                if (lower.contains(key)) {
                    logger.debug("parseCategory: Matched category string '{}' → '{}' (substring match)", safeCategoryString, entry.getValue());
                    return entry.getValue();
                }
            }
        }
        
        // STEP 10: Final fallback - no category detected
        // CRITICAL: Always return a valid category (never null)
        logger.debug("🏷️ parseCategory: No category detected for merchant='{}', description='{}', falling back to 'other'", 
                safeMerchantName, safeDescription);
        return "other";
    }
    
    /**
     * Overloaded method for backward compatibility (without transactionTypeIndicator)
     */
    public String parseCategory(String categoryString, String description, String merchantName, 
                                 BigDecimal amount, String paymentChannel) {
        return parseCategory(categoryString, description, merchantName, amount, paymentChannel, null, null, null, null);
    }
    
    /**
     * Determines income category from context (transaction type, account type, description)
     * Returns specific income category: salary, deposit, dividend, interest, stipend, rentIncome, tips, otherIncome
     */
    private String determineIncomeCategoryFromContext(String description, String merchantName, 
                                                      BigDecimal amount, String paymentChannel,
                                                      String accountType, String accountSubtype) {
        if (description == null && merchantName == null) {
            return null;
        }
        
        String descLower = description != null ? description.toLowerCase() : "";
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String combinedText = (merchantLower + " " + descLower).trim();
        
        // Salary/Payroll - highest priority
        if (isSalaryTransaction(description, amount, paymentChannel)) {
            return "salary";
        }
        
        // Interest (but not from investment accounts - those are handled separately)
        boolean isInvestmentAccount = accountType != null && 
            (accountType.contains("investment") || accountType.contains("ira") || 
             accountType.contains("401k") || accountType.contains("hsa") || accountType.contains("529"));
        if (!isInvestmentAccount && isInterestTransaction(null, description, merchantName, amount)) {
            return "interest";
        }
        
        // Dividend (but not from investment accounts - those are investmentDividend)
        if (!isInvestmentAccount && isDividendTransaction(null, description, merchantName, amount)) {
            return "dividend";
        }
        
        // Stipend
        if (combinedText.contains("stipend") || combinedText.contains("scholarship") ||
            combinedText.contains("grant") || combinedText.contains("fellowship") ||
            combinedText.contains("bursary")) {
            return "stipend";
        }
        
        // Rental Income
        if (combinedText.contains("rent received") || combinedText.contains("rental income") ||
            combinedText.contains("property income") || combinedText.contains("rent payment received") ||
            (combinedText.contains("rent") && (combinedText.contains("received") || combinedText.contains("income")))) {
            return "rentIncome";
        }
        
        // Tips
        if (combinedText.contains("tip") || combinedText.contains("gratuity")) {
            return "tips";
        }
        
        // Default: deposit for ACH credits or generic deposits
        if ("ach".equalsIgnoreCase(paymentChannel) || combinedText.contains("deposit") ||
            combinedText.contains("transfer from") || combinedText.contains("online transfer")) {
            return "deposit";
        }
        
        return null; // Could not determine specific income category
    }
    
    /**
     * Normalize merchant name for better matching and consistency
     * Lenient normalization - removes common prefixes/suffixes but preserves merchant identity
     * 
     * @param merchantName Raw merchant name from CSV
     * @return Normalized merchant name
     */
    private String normalizeMerchantName(String merchantName) {
        if (merchantName == null || merchantName.trim().isEmpty()) {
            return merchantName;
        }
        
        String normalized = merchantName.trim().toUpperCase();
        
        // Remove payment processor prefixes (lenient - allow variations)
        normalized = normalized.replaceAll("^ACH\\s+", "");
        normalized = normalized.replaceAll("^PPD\\s+", "");
        normalized = normalized.replaceAll("^WEB\\s+ID:\\s*", "");
        normalized = normalized.replaceAll("^ID:\\s*", "");
        normalized = normalized.replaceAll("^POS\\s+", "");
        normalized = normalized.replaceAll("^DEBIT\\s+CARD\\s+", "");
        normalized = normalized.replaceAll("^CREDIT\\s+CARD\\s+", "");
        
        // Remove common suffixes (lenient - allow with/without periods)
        normalized = normalized.replaceAll("\\.COM$", "");
        normalized = normalized.replaceAll("\\.NET$", "");
        normalized = normalized.replaceAll("\\.ORG$", "");
        normalized = normalized.replaceAll("\\.CO\\.UK$", "");
        normalized = normalized.replaceAll("\\.CO$", "");
        normalized = normalized.replaceAll("\\s+INC\\.?$", "");
        normalized = normalized.replaceAll("\\s+LLC\\.?$", "");
        normalized = normalized.replaceAll("\\s+CORP\\.?$", "");
        normalized = normalized.replaceAll("\\s+CORPORATION$", "");
        normalized = normalized.replaceAll("\\s+COMPANY$", "");
        normalized = normalized.replaceAll("\\s+LTD\\.?$", "");
        normalized = normalized.replaceAll("\\s+LIMITED$", "");
        
        // Remove store numbers and IDs (lenient - allow various formats)
        // CRITICAL FIX: Handle # with or without space before it (e.g., "SAFEWAY#1444" or "SAFEWAY #1444")
        normalized = normalized.replaceAll("#\\s*\\d+", ""); // Remove #123, # 123, etc. (with or without space)
        normalized = normalized.replaceAll("\\s+STORE\\s+#\\s*\\d+", "");
        normalized = normalized.replaceAll("\\s+STORE\\s*\\d+", "");
        normalized = normalized.replaceAll("\\s+ST\\s+#\\s*\\d+", "");
        normalized = normalized.replaceAll("\\s+LOC\\s+#\\s*\\d+", "");
        // CRITICAL: Only remove standalone 4+ digit numbers that are NOT part of a word
        // This prevents removing years or legitimate numbers, but removes store IDs
        normalized = normalized.replaceAll("\\s+\\d{4,}(?=\\s|$)", ""); // Remove long numeric IDs (4+ digits) at end or followed by space
        
        // Normalize whitespace (multiple spaces to single space)
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replaceAll("\\s+-\\s+", " ");
        normalized = normalized.replaceAll("\\s+\\|\\s+", " ");
        normalized = normalized.replaceAll("\\s+/\\s+", " ");
        normalized = normalized.replaceAll("\\s+_\\s+", " ");
        
        // Trim and return
        normalized = normalized.trim();
        
        // If normalization resulted in empty string, return original
        if (normalized.isEmpty()) {
            return merchantName.trim();
        }
        
        return normalized;
    }
    
    /**
     * Detects if a transaction is ACH (Automated Clearing House)
     */
    private boolean isACHTransaction(String description, String paymentChannel) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        
        // Check payment channel first
        if (paymentChannel != null && "ach".equalsIgnoreCase(paymentChannel.trim())) {
            return true;
        }
        
        // Check description for ACH indicators
        String[] achKeywords = {
            "ach", "automated clearing house", "direct deposit", "directdeposit",
            "dd deposit", "electronic deposit", "e deposit", "wire transfer",
            "bank transfer", "online transfer"
        };
        
        for (String keyword : achKeywords) {
            if (descLower.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Detects if a transaction is interest income
     */
    private boolean isInterestTransaction(String categoryString, String description, String merchantName, BigDecimal amount) {
        // Check category string first (most reliable)
        if (categoryString != null) {
            String categoryLower = categoryString.toLowerCase();
            if (categoryLower.contains("interest") || categoryLower.equals("interest")) {
                return true;
            }
        }
        
        // Check description for interest keywords
        if (description != null) {
            String descLower = description.toLowerCase();
            String[] interestKeywords = {
                "interest", "intrst", "intr payment", "intrst payment", "intrst pymnt",
                "interest payment", "interest income", "interest earned", "interest credit"
            };
            for (String keyword : interestKeywords) {
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
    
    /**
     * Detects if a transaction is dividend income
     */
    private boolean isDividendTransaction(String categoryString, String description, String merchantName, BigDecimal amount) {
        // Check category string first (most reliable)
        if (categoryString != null) {
            String categoryLower = categoryString.toLowerCase();
            if (categoryLower.contains("dividend") || categoryLower.equals("dividend")) {
                return true;
            }
        }
        
        // Check description for dividend keywords
        if (description != null) {
            String descLower = description.toLowerCase();
            String[] dividendKeywords = {
                "dividend", "dividends", "stock dividend", "dividend payment",
                "dividend income", "dividend distribution", "dividend credit"
            };
            for (String keyword : dividendKeywords) {
                if (descLower.contains(keyword)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Detects if a transaction is salary/payroll
     */
    private boolean isSalaryTransaction(String description, BigDecimal amount, String paymentChannel) {
        if (description == null) {
            return false;
        }
        
        // Salary should be positive income
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        
        String descLower = description.toLowerCase();
        
        // Check for salary/payroll keywords
        String[] salaryKeywords = {
            "salary", "payroll", "pay check", "paycheck", "wage", "wages",
            "pay stub", "paystub", "pay day", "payday", "direct deposit payroll",
            "payroll deposit", "employee payroll", "payroll payment", "salary payment",
            "pay from", "payment from employer", "employer payment", "pay adv", "pay advance",
            // Common payroll providers
            "adp", "paychex", "gusto", "justworks", "bamboo hr", "zenefits",
            "workday", "ceridian", "tri-net"
        };
        
        for (String keyword : salaryKeywords) {
            if (descLower.contains(keyword)) {
                return true;
            }
        }
        
        // Check for ACH + salary-like description
        if (isACHTransaction(description, paymentChannel)) {
            // If it's ACH and has salary keywords, likely salary
            for (String keyword : salaryKeywords) {
                if (descLower.contains(keyword)) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Detects if a transaction is a cash withdrawal
     * Cash withdrawals should be categorized as "cash", type EXPENSE
     * Examples: "ATM WITHDRAWAL", "CASH WITHDRAWAL", "ATM DEBIT", etc.
     * 
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @param transactionTypeIndicator Transaction type indicator
     * @param paymentChannel Payment channel (e.g., "atm")
     * @return true if this is a cash withdrawal
     */
    private boolean isCashWithdrawal(String description, String merchantName, String categoryString, 
                                     String transactionTypeIndicator, String paymentChannel) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String categoryLower = categoryString != null ? categoryString.toLowerCase() : "";
        String typeIndicatorLower = transactionTypeIndicator != null ? transactionTypeIndicator.toLowerCase() : "";
        String paymentChannelLower = paymentChannel != null ? paymentChannel.toLowerCase() : "";
        
        logger.debug("isCashWithdrawal: Checking description='{}', merchant='{}', category='{}', type='{}', channel='{}'",
                description, merchantName, categoryString, transactionTypeIndicator, paymentChannel);
        
        // Check category string first (most reliable)
        if (categoryLower.contains("cash") || categoryLower.contains("atm") || 
            categoryLower.contains("withdrawal") || categoryLower.contains("cash withdrawal")) {
            logger.info("isCashWithdrawal: ✅ Detected cash withdrawal from categoryString '{}'", categoryString);
            return true;
        }
        
        // Check payment channel (ATM is a strong indicator)
        if (paymentChannelLower.contains("atm") || paymentChannelLower.contains("cash")) {
            logger.info("isCashWithdrawal: ✅ Detected cash withdrawal from paymentChannel '{}'", paymentChannel);
            return true;
        }
        
        // Check transaction type indicator
        if (typeIndicatorLower.contains("atm") || typeIndicatorLower.contains("cash") ||
            typeIndicatorLower.contains("withdrawal")) {
            logger.info("isCashWithdrawal: ✅ Detected cash withdrawal from transactionTypeIndicator '{}'", transactionTypeIndicator);
            return true;
        }
        
        // Check for cash withdrawal patterns in description/merchant name
        // CRITICAL FIX: Include standalone "withdrawal" keyword
        String[] cashKeywords = {
            "atm withdrawal", "atm withdraw", "cash withdrawal", "cash withdraw",
            "atm debit", "atm transaction", "atm cash", "cash advance",
            "withdrawal", "withdraw cash", "cash out", "cashout", "withdraw"
        };
        
        for (String keyword : cashKeywords) {
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                // Exclude credit card cash advances (those are different)
                if (!descLower.contains("credit card") && !descLower.contains("creditcard") &&
                    !merchantLower.contains("credit card") && !merchantLower.contains("creditcard")) {
                    logger.info("isCashWithdrawal: ✅ Detected cash withdrawal keyword '{}' in description='{}' or merchant='{}'", 
                            keyword, description, merchantName);
                    return true;
                }
            }
        }
        
        logger.debug("isCashWithdrawal: No cash withdrawal detected for description='{}', merchant='{}'", description, merchantName);
        return false;
    }
    
    /**
     * CRITICAL FIX: Detects if a transaction is a check payment
     * Check payments should be categorized as "payment", not transportation (e.g., "CHECK 176" was matching gas station "76")
     * 
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @param transactionTypeIndicator Transaction type indicator (e.g., "CHECK")
     * @return true if this is a check payment
     */
    private boolean isCheckPayment(String description, String merchantName, String categoryString, String transactionTypeIndicator) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String categoryLower = categoryString != null ? categoryString.toLowerCase() : "";
        String typeIndicatorLower = transactionTypeIndicator != null ? transactionTypeIndicator.toLowerCase() : "";
        
        // Check transaction type indicator first (most reliable)
        if (typeIndicatorLower.contains("check") || typeIndicatorLower.contains("chk") || typeIndicatorLower.contains("cheque")) {
            logger.debug("isCheckPayment: Detected check payment from transactionTypeIndicator '{}'", transactionTypeIndicator);
            return true;
        }
        
        // Check category string
        if (categoryLower.contains("check") || categoryLower.contains("chk") || categoryLower.contains("cheque") ||
            categoryLower.contains("check_paid") || categoryLower.contains("check_payment")) {
            logger.debug("isCheckPayment: Detected check payment from categoryString '{}'", categoryString);
            return true;
        }
        
        // Check description/merchant name for check payment patterns
        // CRITICAL: Must match "CHECK" as a word, not just "check" as substring (to avoid false positives)
        // Patterns: "CHECK #123", "CHECK 123", "CHECK NUMBER", "CHECK PAYMENT", etc.
        String[] checkPatterns = {
            "check #", "check number", "check no", "check payment", "check paid",
            "check #", "chk #", "chk number", "chk no", "cheque #", "cheque number"
        };
        
        for (String pattern : checkPatterns) {
            if (descLower.contains(pattern) || merchantLower.contains(pattern)) {
                logger.debug("isCheckPayment: Detected check payment pattern '{}' in description/merchant", pattern);
                return true;
            }
        }
        
        // Check for "CHECK" followed by a number (e.g., "CHECK 176", "CHECK #176")
        // Use word boundary to ensure "CHECK" is a standalone word
        if ((descLower.matches(".*\\bcheck\\s+#?\\d+.*") || merchantLower.matches(".*\\bcheck\\s+#?\\d+.*")) &&
            !descLower.contains("checking") && !merchantLower.contains("checking")) {
            logger.debug("isCheckPayment: Detected check payment with number pattern in description/merchant");
            return true;
        }
        
        return false;
    }
    
    /**
     * Detects if a transaction is a utility bill payment (direct payment to utility company)
     * Utility bill payments should be categorized as "utilities", not "payment"
     * Examples: "PUGET SOUND ENER BILLPAY", "CITY OF BELLEVUE UTILITY", etc.
     * 
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is a utility bill payment
     */
    private boolean isUtilityBillPayment(String description, String merchantName, String categoryString) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        
        logger.debug("isUtilityBillPayment: Checking description='{}', merchant='{}', category='{}'",
                description, merchantName, categoryString);
        
        // CRITICAL: Reject airport expenses (carts, chairs, parking, etc.) - they are transportation, not utilities
        // "SEATTLEAP CART/CHAIR" (Seattle Airport cart) should not match "Seattle Public Utilities"
        String combined = (merchantLower + " " + descLower).trim();
        if (combined.contains("airport") && (combined.contains("cart") || combined.contains("chair"))) {
            logger.debug("isUtilityBillPayment: Rejecting airport cart/chair → not a utility");
            return false;
        }
        if ((combined.contains("seattleap") || combined.contains("seattle ap") || combined.contains("seattle airport")) &&
            (combined.contains("cart") || combined.contains("chair"))) {
            logger.debug("isUtilityBillPayment: Rejecting SEATTLEAP cart/chair → not a utility");
            return false;
        }
        // Also reject if it's just "SEATTLEAP" without clear utility indicators
        if ((combined.contains("seattleap") || combined.contains("seattle ap")) &&
            !combined.contains("utility") && !combined.contains("utilities") && !combined.contains("public utilities")) {
            logger.debug("isUtilityBillPayment: Rejecting SEATTLEAP (airport, not utility) → not a utility");
            return false;
        }
        
        // Check for utility company names combined with payment indicators
        String[] utilityCompanies = {
            "puget sound energy", "pse", "pacific gas", "pg&e", "pge",
            "southern california edison", "sce", "san diego gas", "sdge",
            "edison", "con edison", "coned", "duke energy", "dukeenergy",
            "dominion energy", "dominionenergy", "exelon", "first energy", "firstenergy",
            "american electric", "aep", "southern company", "southerncompany",
            "next era", "nextera", "xcel energy", "xcelenergy", "centerpoint",
            "center point", "entergy", "entergy", "evergy", "evergy",
            "pacificorp", "pacific corp", "portland general", "portlandgeneral",
            "city of", "municipal utility", "municipalutility"
        };
        
        // CRITICAL: Reject if it contains "city of" but also contains airport terms
        // "SEATTLEAP" should not match "city of seattle" if it's an airport expense
        if ((descLower.contains("city of") || merchantLower.contains("city of")) &&
            ((descLower.contains("seattleap") || merchantLower.contains("seattleap") ||
              descLower.contains("seattle ap") || merchantLower.contains("seattle ap") ||
              descLower.contains("airport") || merchantLower.contains("airport")) &&
             (descLower.contains("cart") || merchantLower.contains("cart") ||
              descLower.contains("chair") || merchantLower.contains("chair")))) {
            logger.debug("isUtilityBillPayment: Rejecting 'city of seattle' match for airport cart/chair");
            return false;
        }
        
        // Check if description/merchant contains utility company name
        for (String company : utilityCompanies) {
            if (descLower.contains(company) || merchantLower.contains(company)) {
                // Additional check: must be a payment (billpay, payment, autopay, etc.)
                // But NOT a credit card payment (exclude credit card company names)
                if ((descLower.contains("billpay") || descLower.contains("bill pay") ||
                     descLower.contains("payment") || descLower.contains("autopay") ||
                     descLower.contains("auto pay") || descLower.contains("ppd id") ||
                     merchantLower.contains("billpay") || merchantLower.contains("bill pay") ||
                     merchantLower.contains("payment") || merchantLower.contains("autopay") ||
                     merchantLower.contains("auto pay") || merchantLower.contains("ppd id")) &&
                    !descLower.contains("credit card") && !descLower.contains("creditcard") &&
                    !merchantLower.contains("credit card") && !merchantLower.contains("creditcard") &&
                    !descLower.contains("chase") && !descLower.contains("citi") &&
                    !descLower.contains("amex") && !descLower.contains("discover") &&
                    !merchantLower.contains("chase") && !merchantLower.contains("citi") &&
                    !merchantLower.contains("amex") && !merchantLower.contains("discover")) {
                    logger.info("isUtilityBillPayment: ✅ Detected utility bill payment for company '{}' in description='{}' or merchant='{}'", 
                            company, description, merchantName);
                    return true;
                }
            }
        }
        
        // Check for utility patterns combined with payment indicators
        String[] utilityKeywords = {
            "utility", "utilities", "energy", "ener ", "electric", "electricity",
            "gas company", "water company", "power company", "water utility"
        };
        
        String[] paymentKeywords = {
            "billpay", "bill pay", "payment", "autopay", "auto pay", "ppd id"
        };
        
        for (String utilityKeyword : utilityKeywords) {
            if (descLower.contains(utilityKeyword) || merchantLower.contains(utilityKeyword)) {
                for (String paymentKeyword : paymentKeywords) {
                    if (descLower.contains(paymentKeyword) || merchantLower.contains(paymentKeyword)) {
                        // Exclude credit card payments
                        if (!descLower.contains("credit card") && !descLower.contains("creditcard") &&
                            !merchantLower.contains("credit card") && !merchantLower.contains("creditcard") &&
                            !descLower.contains("chase") && !descLower.contains("citi") &&
                            !descLower.contains("amex") && !descLower.contains("discover") &&
                            !merchantLower.contains("chase") && !merchantLower.contains("citi") &&
                            !merchantLower.contains("amex") && !merchantLower.contains("discover")) {
                            logger.info("isUtilityBillPayment: ✅ Detected utility bill payment with keywords '{}' + '{}' in description='{}' or merchant='{}'", 
                                    utilityKeyword, paymentKeyword, description, merchantName);
                            return true;
                        }
                    }
                }
            }
        }
        
        logger.debug("isUtilityBillPayment: No utility bill payment detected for description='{}', merchant='{}'", description, merchantName);
        return false;
    }
    
    /**
     * CRITICAL FIX: Detects if an ACH transaction is a credit card payment
     * ACH credit card payments should be categorized as "payment" (expense), not "deposit" (income)
     * 
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is a credit card payment
     */
    private boolean isCreditCardPayment(String description, String merchantName, String categoryString) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String categoryLower = categoryString != null ? categoryString.toLowerCase() : "";
        
        logger.debug("isCreditCardPayment: Checking description='{}', merchant='{}', category='{}'",
                description, merchantName, categoryString);
        
        // CRITICAL FIX: Check for "E-PAYMENT" with credit card company names FIRST (before other patterns)
        // This catches "DISCOVER E-PAYMENT", "DISCOVER         E-PAYMENT", etc. even with extra spaces
        if ((descLower.contains("e-payment") || merchantLower.contains("e-payment") || 
             descLower.contains("epayment") || merchantLower.contains("epayment")) &&
            (descLower.contains("discover") || merchantLower.contains("discover") ||
             descLower.contains("chase") || merchantLower.contains("chase") ||
             descLower.contains("citi") || merchantLower.contains("citi") ||
             descLower.contains("amex") || merchantLower.contains("amex"))) {
            logger.info("isCreditCardPayment: ✅ Detected e-payment with credit card company name (description='{}', merchant='{}')", 
                    description, merchantName);
            return true;
        }
        
        // CRITICAL FIX: Check for "AUTOPAY" with credit card company names (before other patterns)
        // This catches "CHASE CREDIT CRD AUTOPAY", "CITI AUTOPAY", "WF Credit Card AUTO PAY", etc. and prevents false matches
        // Must check this FIRST to catch autopay patterns before they get misclassified
        // Note: "AUTO PAY" (with space) should match "autopay" or "auto pay" since we're using contains()
        boolean hasAutopay = descLower.contains("autopay") || merchantLower.contains("autopay") ||
                            descLower.contains("auto pay") || merchantLower.contains("auto pay");
        boolean hasCreditCardCompany = descLower.contains("chase") || descLower.contains("citi") || descLower.contains("amex") ||
             descLower.contains("discover") || descLower.contains("capital one") || descLower.contains("wells fargo") ||
             descLower.contains("wf") || // CRITICAL: "WF" is Wells Fargo abbreviation (must be standalone word to avoid false positives)
             descLower.contains("bofa") || descLower.contains("bank of america") || descLower.contains("synchrony") ||
             descLower.contains("us bank") || descLower.contains("barclays") ||
             descLower.contains("amazon") || descLower.contains("amz") || // CRITICAL: Amazon Store Card
             descLower.contains("store card") || descLower.contains("storecrd") || // Store card payments
             merchantLower.contains("chase") || merchantLower.contains("citi") || merchantLower.contains("amex") ||
             merchantLower.contains("discover") || merchantLower.contains("capital one") || merchantLower.contains("wells fargo") ||
             merchantLower.contains("wf") || // CRITICAL: "WF" is Wells Fargo abbreviation
             merchantLower.contains("bofa") || merchantLower.contains("bank of america") || merchantLower.contains("synchrony") ||
             merchantLower.contains("us bank") || merchantLower.contains("barclays") ||
             merchantLower.contains("amazon") || merchantLower.contains("amz") || // CRITICAL: Amazon Store Card
             merchantLower.contains("store card") || merchantLower.contains("storecrd"); // Store card payments
        
        if (hasAutopay && hasCreditCardCompany) {
            logger.info("isCreditCardPayment: ✅ Detected credit card autopay with company name (description='{}', merchant='{}')", 
                    description, merchantName);
            return true;
        }
        
        // Check category string (most reliable)
        if (categoryLower.contains("credit card") || categoryLower.contains("creditcard") ||
            categoryLower.contains("card payment") || categoryLower.contains("card autopay") ||
            categoryLower.contains("autopay") || categoryLower.contains("auto pay")) {
            logger.info("isCreditCardPayment: ✅ Detected credit card payment from categoryString '{}'", categoryString);
            return true;
        }
        
        // Check for credit card payment indicators in description/merchant name
        // CRITICAL: Expanded list to catch more patterns including "CITI AUTOPAY", "CHASE AUTOPAY", "AMZ_STORECRD_PMT", "DISCOVER E-PAYMENT", etc.
        String[] creditCardKeywords = {
            "credit card", "creditcard", "credit crd", "card autopay", "card payment",
            "autopay", "auto pay", "automatic payment", "card autopay",
            "e-payment", "epayment", "e payment", // CRITICAL: Discover and other cards use "E-PAYMENT"
            "chase credit crd", "chase credit card", "chase autopay", "chase card",
            "citi autopay", "citi card", "citicard", "citi credit", "citicardap",
            "amex autopay", "amex card", "american express", "amex payment",
            "discover autopay", "discover card", "discover payment", "discover e-payment", // CRITICAL: Discover E-PAYMENT pattern
            "wells fargo credit", "wf credit card", "wf credit", "wells fargo autopay", // CRITICAL: Added "wf credit" for "WF Credit Card"
            "bofa credit card", "bank of america credit", "bofa autopay",
            "capital one credit", "capitalone", "capital one autopay",
            "synchrony", "synchrony bank", "synchrony autopay",
            "us bank credit", "usbank credit", "us bank autopay",
            "barclays credit", "barclays autopay", "barclays card",
            "amazon store card", "amazon storecard", "amz store card", "amz storecrd", // CRITICAL: Amazon Store Card
            "amz_storecrd_pmt", "amz storecrd pmt", "store card payment", "storecard payment", // Amazon Store Card payment patterns
            "web id:", "web id", "citicardap", // Citi-specific patterns like "WEB ID: CITICARDAP"
            "ppd id:" // PPD (Prearranged Payment and Deposit) ID pattern for autopay
        };
        
        for (String keyword : creditCardKeywords) {
            // CRITICAL: Use contains() which handles extra spaces (e.g., "DISCOVER         E-PAYMENT" contains "discover e-payment")
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                logger.info("isCreditCardPayment: ✅ Detected credit card payment keyword '{}' in description='{}' or merchant='{}'", 
                        keyword, description, merchantName);
                return true;
            }
        }
        
        // CRITICAL FIX: Also check for "discover" + "e-payment" separately (handles extra spaces like "DISCOVER         E-PAYMENT")
        // This is a fallback in case the combined keyword doesn't match due to spacing
        if ((descLower.contains("discover") || merchantLower.contains("discover")) &&
            (descLower.contains("e-payment") || descLower.contains("epayment") || 
             merchantLower.contains("e-payment") || merchantLower.contains("epayment"))) {
            logger.info("isCreditCardPayment: ✅ Detected Discover e-payment (separate keywords) in description='{}' or merchant='{}'", 
                    description, merchantName);
            return true;
        }
        
        // Check for payment patterns with card numbers or account identifiers
        // Pattern: "PAYMENT" or "E-PAYMENT" followed by digits (card number) or "WEB ID:" / "PPD ID:" (online payment)
        // Also check for "AUTO PAY" (with space) or "AUTOPAY" patterns
        // CRITICAL: Added "e-payment" and "epayment" to catch Discover E-PAYMENT patterns
        boolean hasPaymentKeyword = descLower.contains("payment") || descLower.contains("e-payment") || descLower.contains("epayment") ||
                                   merchantLower.contains("payment") || merchantLower.contains("e-payment") || merchantLower.contains("epayment");
        boolean hasPaymentIndicator = (descLower.matches(".*\\d{10,}.*") || descLower.contains("web id") || descLower.contains("ppd id") || 
             descLower.contains("auto pay") || descLower.contains("autopay") || descLower.contains("e-payment") || descLower.contains("epayment")) ||
            (merchantLower.matches(".*\\d{10,}.*") || merchantLower.contains("web id") || merchantLower.contains("ppd id") ||
             merchantLower.contains("auto pay") || merchantLower.contains("autopay") || merchantLower.contains("e-payment") || merchantLower.contains("epayment"));
        
        if (hasPaymentKeyword && hasPaymentIndicator) {
            // Additional check: must have credit card company name, "autopay", "credit", "card", "store card", or "amazon"
            // CRITICAL: Added "wf" for Wells Fargo abbreviation, "auto pay" for "AUTO PAY" pattern, and Amazon Store Card patterns
            if (descLower.contains("citi") || descLower.contains("chase") || descLower.contains("amex") ||
                descLower.contains("discover") || descLower.contains("capital one") || descLower.contains("autopay") ||
                descLower.contains("auto pay") || descLower.contains("wells fargo") || descLower.contains("wf") ||
                descLower.contains("amazon") || descLower.contains("amz") || descLower.contains("store card") || descLower.contains("storecrd") ||
                descLower.contains("credit") || descLower.contains("card") ||
                merchantLower.contains("citi") || merchantLower.contains("chase") || merchantLower.contains("amex") ||
                merchantLower.contains("discover") || merchantLower.contains("capital one") || merchantLower.contains("autopay") ||
                merchantLower.contains("auto pay") || merchantLower.contains("wells fargo") || merchantLower.contains("wf") ||
                merchantLower.contains("amazon") || merchantLower.contains("amz") || merchantLower.contains("store card") || merchantLower.contains("storecrd") ||
                merchantLower.contains("credit") || merchantLower.contains("card")) {
                logger.info("isCreditCardPayment: ✅ Detected credit card/store card payment pattern with card number/web id/ppd id/auto pay");
                return true;
            }
        }
        
        logger.debug("isCreditCardPayment: No credit card payment detected for description='{}', merchant='{}'", description, merchantName);
        return false;
    }
    
    /**
     * Detects if a transaction is a loan payment (mortgage, auto loan, student loan, etc.)
     * Loan payments should be categorized as "payment", not "other"
     * 
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is a loan payment
     */
    private boolean isLoanPayment(String description, String merchantName, String categoryString) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String categoryLower = categoryString != null ? categoryString.toLowerCase() : "";
        
        // Check category string first (most reliable)
        if (categoryLower.contains("loan payment") || categoryLower.contains("mortgage payment") ||
            categoryLower.contains("auto loan") || categoryLower.contains("student loan") ||
            categoryLower.contains("personal loan") || categoryLower.contains("home loan")) {
            logger.debug("isLoanPayment: Detected loan payment from categoryString '{}'", categoryString);
            return true;
        }
        
        // Check for loan payment patterns
        String[] loanKeywords = {
            "mortgage payment", "mortgage pay", "mortgage autopay",
            "auto loan", "car loan", "vehicle loan",
            "student loan", "education loan",
            "personal loan", "home loan", "home equity",
            "loan payment", "loan pay", "loan autopay",
            "principal payment", "interest payment"
        };
        
        for (String keyword : loanKeywords) {
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                logger.debug("isLoanPayment: Detected loan payment keyword '{}'", keyword);
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Detects if a transaction is an investment transfer (from/to investment firms)
     * Investment transfers should be categorized as "investment", not "transfer" or "deposit"
     * Examples: "Online Transfer from Morgan Stanley", "Transfer from Fidelity", etc.
     * 
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is an investment transfer
     */
    private boolean isInvestmentTransfer(String description, String merchantName, String categoryString) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String categoryLower = categoryString != null ? categoryString.toLowerCase() : "";
        
        logger.debug("isInvestmentTransfer: Checking description='{}', merchant='{}', category='{}'",
                description, merchantName, categoryString);
        
        // Check for investment firm names in description/merchant
        // Major investment firms and brokerages
        String[] investmentFirms = {
            "morgan stanley", "morganstanley", "morgan stanley smith barney",
            "fidelity", "fidelity investments", "fidelity.com",
            "vanguard", "vanguard group", "vanguard.com",
            "charles schwab", "schwab", "schwab.com",
            "td ameritrade", "ameritrade", "tdameritrade",
            "etrade", "e-trade", "etrade.com",
            "robinhood", "robin hood", "robinhood.com",
            "merrill lynch", "merrill", "merrilllynch",
            "goldman sachs", "goldman", "goldmansachs",
            "jpmorgan", "jp morgan", "jpmorgan chase",
            "wells fargo advisors", "wells fargo investment",
            "edward jones", "edwardjones",
            "raymond james", "raymondjames",
            "lpl financial", "lpl",
            "ameriprise", "ameriprise financial",
            "prudential", "prudential financial",
            "northwestern mutual", "northwesternmutual",
            "massmutual", "mass mutual",
            "new york life", "newyorklife",
            "t rowe price", "troweprice",
            "franklin templeton", "franklintempleton",
            "blackrock", "ishares",
            "state street", "statestreet"
        };
        
        // Check if description/merchant contains investment firm name
        for (String firm : investmentFirms) {
            if (descLower.contains(firm) || merchantLower.contains(firm)) {
                // CRITICAL FIX: For investment firms, check amount sign
                // - Negative amount (debit) = investment transfer (money going out)
                // - Positive amount (credit) = deposit (money coming in, not investment expense)
                // This fixes: "Online transfer from Morgan Stanley" (credit) should be "deposit", not "investment"
                boolean isCredit = false;
                // Note: amount is not available in this method, so we check description for credit indicators
                if (descLower.contains("credit") || descLower.contains("deposit") || 
                    descLower.contains("from") || merchantLower.contains("from")) {
                    // If it says "from" or "credit" or "deposit", it's likely a credit (deposit)
                    // Investment transfers are typically debits (money going out)
                    isCredit = true;
                }
                
                // Additional check: must be a transfer (not a purchase/sale)
                if (descLower.contains("transfer") || merchantLower.contains("transfer") ||
                    descLower.contains("from") || descLower.contains("to") ||
                    categoryLower.contains("acct_xfer") || categoryLower.contains("transfer")) {
                    // CRITICAL: If it's a credit (deposit), it's NOT an investment transfer
                    // Investment transfers are debits (money going out to investment account)
                    if (!isCredit) {
                        logger.info("isInvestmentTransfer: ✅ Detected investment transfer (debit) from firm '{}' in description='{}' or merchant='{}'", 
                                firm, description, merchantName);
                        return true;
                    } else {
                        logger.debug("isInvestmentTransfer: Skipping credit/deposit from investment firm '{}' - this should be 'deposit', not 'investment'", firm);
                    }
                }
            }
        }
        
        // Check for investment-related keywords combined with transfer
        String[] investmentKeywords = {
            "brokerage", "broker", "investment account", "investmentaccount",
            "ira", "401k", "401(k)", "403b", "403(b)", "529", "hsa",
            "retirement account", "retirementaccount", "pension",
            "mutual fund", "mutualfund", "etf", "stock", "stocks",
            "portfolio", "trading account", "tradingaccount"
        };
        
        for (String keyword : investmentKeywords) {
            if ((descLower.contains(keyword) || merchantLower.contains(keyword)) &&
                (descLower.contains("transfer") || merchantLower.contains("transfer") ||
                 descLower.contains("from") || descLower.contains("to") ||
                 categoryLower.contains("acct_xfer") || categoryLower.contains("transfer"))) {
                logger.info("isInvestmentTransfer: ✅ Detected investment transfer with keyword '{}' in description='{}' or merchant='{}'", 
                        keyword, description, merchantName);
                return true;
            }
        }
        
        logger.debug("isInvestmentTransfer: No investment transfer detected for description='{}', merchant='{}'", description, merchantName);
        return false;
    }
    
    /**
     * Detects if a transaction is an account transfer (between accounts, not an expense)
     * Account transfers should be categorized as "transfer", not "other" or "payment"
     * Examples: "Online Transfer to CHK", "ACCT_XFER", "Transfer to Savings", etc.
     * 
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param categoryString Category string from CSV
     * @return true if this is an account transfer
     */
    private boolean isAccountTransfer(String description, String merchantName, String categoryString) {
        if (description == null) {
            description = "";
        }
        String descLower = description.toLowerCase();
        String merchantLower = merchantName != null ? merchantName.toLowerCase() : "";
        String categoryLower = categoryString != null ? categoryString.toLowerCase() : "";
        
        logger.debug("isAccountTransfer: Checking description='{}', merchant='{}', category='{}'",
                description, merchantName, categoryString);
        
        // Check category string first (most reliable)
        // ACCT_XFER = Account Transfer
        if (categoryLower.contains("acct_xfer") || categoryLower.contains("account transfer") ||
            categoryLower.contains("transfer") || categoryLower.contains("xfer")) {
            // But exclude credit card balance transfers and loan payments
            if (!categoryLower.contains("balance transfer") && !categoryLower.contains("loan")) {
                logger.info("isAccountTransfer: ✅ Detected account transfer from categoryString '{}'", categoryString);
                return true;
            }
        }
        
        // Check for transfer patterns in description/merchant name
        // Patterns: "Online Transfer to CHK", "Transfer to Savings", "Transfer from Checking", etc.
        // CRITICAL: Include money transfer services like Remitly
        String[] transferKeywords = {
            "online transfer", "transfer to", "transfer from", "account transfer",
            "transfer to chk", "transfer to checking", "transfer to savings",
            "transfer from chk", "transfer from checking", "transfer from savings",
            "xfer to", "xfer from", "wire transfer", "wire debit", "wire credit",
            "international wire", "remitly", "rmtly", "money transfer", "currency transfer"
        };
        
        // Check for money transfer companies
        // CRITICAL FIX: Use word boundaries to prevent false positives (e.g., "FACTORIA" matching "ria")
        String[] transferCompanies = {
            "remitly", "rmtly", "western union", "moneygram", "wise", "transferwise",
            "xoom", "worldremit", "ria money transfer"  // Removed standalone "ria" - too many false positives
        };
        
        for (String company : transferCompanies) {
            // CRITICAL FIX: Use word boundaries for short company names to prevent substring matches
            // For "ria", require it to be a whole word or part of "ria money transfer"
            if (company.length() <= 3) {
                // Short names: require word boundary (start/end of string or non-word character)
                String pattern = "\\b" + company + "\\b";
                if (descLower.matches(".*" + pattern + ".*") || 
                    (merchantName != null && !merchantName.trim().isEmpty() && merchantLower.matches(".*" + pattern + ".*"))) {
                    logger.info("isAccountTransfer: ✅ Detected money transfer company '{}' in description='{}' or merchant='{}'", 
                            company, description, merchantName);
                    return true;
                }
            } else {
                // Longer names: can use contains (less likely to be substring)
                if (descLower.contains(company) || 
                    (merchantName != null && !merchantName.trim().isEmpty() && merchantLower.contains(company))) {
                    logger.info("isAccountTransfer: ✅ Detected money transfer company '{}' in description='{}' or merchant='{}'", 
                            company, description, merchantName);
                    return true;
                }
            }
        }
        
        // CRITICAL FIX: Check for standalone "ria" only if merchantName is not null (to avoid false positives in descriptions)
        // "ria" alone is too ambiguous - only match if it's in merchant name (more reliable)
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            if (merchantLower.matches(".*\\bria\\b.*") && merchantLower.length() < 20) {
                // Only match "ria" in short merchant names (likely to be actual company name)
                logger.info("isAccountTransfer: ✅ Detected money transfer company 'ria' in merchant='{}'", merchantName);
                return true;
            }
        }
        
        for (String keyword : transferKeywords) {
            if (descLower.contains(keyword) || merchantLower.contains(keyword)) {
                // Additional check: must NOT be a credit card balance transfer or loan payment
                if (!descLower.contains("balance transfer") && !descLower.contains("loan payment") &&
                    !merchantLower.contains("balance transfer") && !merchantLower.contains("loan payment")) {
                    logger.info("isAccountTransfer: ✅ Detected account transfer keyword '{}' in description='{}' or merchant='{}'", 
                            keyword, description, merchantName);
                    return true;
                }
            }
        }
        
        // Check for "CHK" (checking account) in transfer context
        // Pattern: "Transfer to CHK" or "Transfer from CHK"
        if ((descLower.contains("transfer") || merchantLower.contains("transfer")) &&
            (descLower.contains("chk") || descLower.contains("checking") || 
             descLower.contains("savings") || descLower.contains("account") ||
             merchantLower.contains("chk") || merchantLower.contains("checking") ||
             merchantLower.contains("savings") || merchantLower.contains("account"))) {
            // Additional check: must NOT be a credit card or loan payment
            if (!descLower.contains("credit") && !descLower.contains("card") && !descLower.contains("loan") &&
                !merchantLower.contains("credit") && !merchantLower.contains("card") && !merchantLower.contains("loan")) {
                logger.info("isAccountTransfer: ✅ Detected account transfer with CHK/checking/savings pattern");
                return true;
            }
        }
        
        logger.debug("isAccountTransfer: No account transfer detected for description='{}', merchant='{}'", description, merchantName);
        return false;
    }
    
    /**
     * Analyze transaction details/type for account type inference
     * Looks for keywords like "debit", "credit", "check", "ACH", "ATM", "transfer"
     * Uses arrays to allow modification of counts
     */
    private void analyzeTransactionForAccountType(String transactionText, 
                                                   int[] debitCount, int[] creditCount, int[] checkCount,
                                                   int[] achCount, int[] atmCount, int[] transferCount) {
        if (transactionText == null || transactionText.trim().isEmpty()) {
            return;
        }
        
        String textLower = transactionText.toLowerCase();
        
        // Count transaction type indicators
        if (textLower.contains("debit") || textLower.contains(" db ") || textLower.contains(" dr ") ||
            textLower.startsWith("db ") || textLower.startsWith("dr ") ||
            textLower.contains("debit card") || textLower.contains("debit purchase")) {
            debitCount[0]++;
            logger.debug("Found debit indicator in transaction: {}", transactionText);
        }
        if (textLower.contains("credit") || textLower.contains(" cr ") || 
            textLower.startsWith("cr ") || textLower.contains("credit memo") ||
            textLower.contains("credit adjustment")) {
            // Exclude "credit card" from credit count (that's a different thing)
            if (!textLower.contains("credit card") && !textLower.contains("creditcard")) {
                creditCount[0]++;
                logger.debug("Found credit indicator in transaction: {}", transactionText);
            }
        }
        if (textLower.contains("check") || textLower.contains("chk") || textLower.contains("cheque") ||
            textLower.contains("check #") || textLower.contains("check number") ||
            textLower.contains("check payment") || textLower.contains("check deposit")) {
            checkCount[0]++;
            logger.debug("Found check indicator in transaction: {}", transactionText);
        }
        if (textLower.contains("ach") || textLower.contains("automated clearing") || 
            textLower.contains("direct deposit") || textLower.contains("directdeposit") ||
            textLower.contains("ach credit") || textLower.contains("ach debit") ||
            textLower.contains("ach transfer")) {
            achCount[0]++;
            logger.debug("Found ACH indicator in transaction: {}", transactionText);
        }
        if (textLower.contains("atm") || textLower.contains("at m") || 
            textLower.contains("cash withdrawal") || textLower.contains("cash withdrawal") ||
            textLower.contains("atm withdrawal") || textLower.contains("atm deposit") ||
            textLower.contains("atm fee")) {
            atmCount[0]++;
            logger.debug("Found ATM indicator in transaction: {}", transactionText);
        }
        if (textLower.contains("transfer") || textLower.contains("xfer") || 
            textLower.contains("wire transfer") || textLower.contains("online transfer") ||
            textLower.contains("bank transfer") || textLower.contains("account transfer") ||
            textLower.contains("internal transfer")) {
            transferCount[0]++;
            logger.debug("Found transfer indicator in transaction: {}", transactionText);
        }
    }
    
    /**
     * Infer account type from transaction patterns
     * Uses counts of different transaction types to determine account type
     */
    private String inferAccountTypeFromTransactionPatterns(int debitCount, int creditCount, int checkCount,
                                                           int achCount, int atmCount, int transferCount) {
        // If we see checks, it's definitely a depository account (checking)
        if (checkCount > 0) {
            return "depository";
        }
        
        // If we see many debits and credits with ACH/transfers, likely depository
        if ((debitCount > 0 || creditCount > 0) && (achCount > 0 || transferCount > 0)) {
            return "depository";
        }
        
        // If we see ATM transactions, likely depository (checking or savings)
        if (atmCount > 0) {
            return "depository";
        }
        
        // If we see ACH transactions, likely depository
        if (achCount > 0) {
            return "depository";
        }
        
        // If we see transfers, likely depository
        if (transferCount > 0) {
            return "depository";
        }
        
        return null;
    }
    
    /**
     * Infer account subtype (checking vs savings) from transaction patterns
     */
    private String inferAccountSubtypeFromTransactionPatterns(int debitCount, int creditCount, int checkCount,
                                                              int achCount, int atmCount, int transferCount) {
        // If we see checks, it's definitely checking
        if (checkCount > 0) {
            logger.debug("Inferred checking account from check transactions");
            return "checking";
        }
        
        // If we see many debits (purchases, payments), likely checking
        // Savings accounts typically have fewer transactions and more deposits/withdrawals
        if (debitCount > creditCount && debitCount > 2) {
            logger.debug("Inferred checking account from high debit count: {}", debitCount);
            return "checking";
        }
        
        // If we see ATM transactions, more likely checking (savings may have ATM but less common)
        if (atmCount > 0) {
            logger.debug("Inferred checking account from ATM transactions");
            return "checking";
        }
        
        // If we see many ACH transactions (direct deposits, bill payments), likely checking
        if (achCount > 2) {
            logger.debug("Inferred checking account from ACH transactions: {}", achCount);
            return "checking";
        }
        
        // If we see transfers, could be either, but more common in checking
        if (transferCount > 0 && debitCount > 0) {
            logger.debug("Inferred checking account from transfer and debit patterns");
            return "checking";
        }
        
        // Default to savings if we can't determine (fewer transactions, more deposits)
        if (creditCount > debitCount && creditCount > 2) {
            logger.debug("Inferred savings account from high credit/deposit count: {}", creditCount);
            return "savings";
        }
        
        return null;
    }
    
    /**
     * Detects RSU (Restricted Stock Unit) vesting transactions
     * Enhanced version with more patterns
     */
    private boolean isRSUTransaction(String categoryString, String description, String merchantName, BigDecimal amount) {
        String combinedText = "";
        if (description != null) {
            combinedText += description.toLowerCase() + " ";
        }
        if (merchantName != null) {
            combinedText += merchantName.toLowerCase() + " ";
        }
        if (categoryString != null) {
            combinedText += categoryString.toLowerCase();
        }
        combinedText = combinedText.trim();
        
        // Enhanced RSU detection patterns
        String[] rsuPatterns = {
            "rsu", "restricted stock unit", "restricted stock", "stock unit vest",
            "stock vest", "rsu vest", "rsu vesting", "restricted stock vest",
            "equity vest", "equity vesting", "stock award", "stock award vest",
            "employee stock vest", "espp", "stock compensation", "equity compensation"
        };
        
        // Check if description/category contains RSU patterns
        for (String pattern : rsuPatterns) {
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
     * Sophisticated Merchant Name-Based Category Detection
     * Uses comprehensive global merchant patterns with fuzzy matching
     * This is the key enhancement - merchant names are more reliable than category strings
     * 
     * @param merchantName Normalized merchant name
     * @param description Transaction description (for additional context)
     * @return Detected category or null if no match
     */
    private String detectCategoryFromMerchantName(String merchantName, String description) {
        if (merchantName == null || merchantName.trim().isEmpty()) {
            return null;
        }
        
        // CRITICAL: Check for credit card payments FIRST to prevent false matches
        // This is a safety check in case credit card payment detection didn't catch it earlier
        if (isCreditCardPayment(description, merchantName, null)) {
            logger.info("detectCategoryFromMerchantName: Detected credit card payment (safety check) → 'payment'");
            return "payment";
        }
        
        // Normalize merchant name for matching (case-insensitive, remove special chars)
        String normalized = normalizeMerchantName(merchantName).toLowerCase();
        String descLower = description != null ? description.toLowerCase() : "";
        
        logger.debug("detectCategoryFromMerchantName: Analyzing merchant='{}', normalized='{}'", merchantName, normalized);
        
        // ========== GROCERIES - Global Grocery Stores ==========
        // US Grocery Chains
        // CRITICAL FIX: Check for safeway with more lenient matching (handles variations like "SAFEWAY #1444")
        if (normalized.contains("safeway") || normalized.contains("safeway.com") || 
            normalized.startsWith("safeway") || normalized.endsWith("safeway")) {
            logger.debug("detectCategoryFromMerchantName: Detected Safeway in normalized='{}' → 'groceries'", normalized);
            return "groceries";
        }
        if (normalized.contains("pcc") || normalized.contains("pcc natural markets") || normalized.contains("pcc community markets")) {
            return "groceries";
        }
        if (normalized.contains("amazon fresh") || normalized.contains("amazonfresh") || 
            (normalized.contains("amazon") && (descLower.contains("fresh") || descLower.contains("grocery")))) {
            return "groceries";
        }
        if (normalized.contains("qfc") || normalized.contains("quality food centers") ||
            descLower.contains("qfc") || descLower.contains("quality food centers")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected QFC → 'groceries'");
            return "groceries";
        }
        // Chefstore (grocery store)
        if (normalized.contains("chefstore") || normalized.contains("chef store") ||
            descLower.contains("chefstore") || descLower.contains("chef store")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Chefstore → 'groceries'");
            return "groceries";
        }
        // Town & Country market (pantry/market patterns are typically grocery stores)
        if (normalized.contains("town & country") || normalized.contains("town and country") ||
            normalized.contains("town&country") || descLower.contains("town & country") ||
            descLower.contains("town and country") || descLower.contains("town&country")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Town & Country market → 'groceries'");
            return "groceries";
        }
        // Mayuri Foods (grocery store)
        if (normalized.contains("mayuri") || descLower.contains("mayuri")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Mayuri Foods → 'groceries'");
            return "groceries";
        }
        // Meet Fresh (grocery store)
        if (normalized.contains("meet fresh") || normalized.contains("meetfresh") ||
            descLower.contains("meet fresh") || descLower.contains("meetfresh")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Meet Fresh → 'groceries'");
            return "groceries";
        }
        // Pantry/market patterns (typically grocery stores)
        if ((normalized.contains("pantry") || normalized.contains("market")) &&
            !normalized.contains("parking") && !descLower.contains("parking")) {
            logger.debug("detectCategoryFromMerchantName: Detected pantry/market pattern → 'groceries'");
            return "groceries";
        }
        if (normalized.contains("sunny honey") || normalized.contains("sunnyhoney") ||
            descLower.contains("sunny honey") || descLower.contains("sunnyhoney")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Sunny Honey Company → 'groceries'");
            return "groceries";
        }
        if (normalized.contains("dk market") || normalized.contains("dkmarket")) {
            return "groceries";
        }
        // CRITICAL: COSTCO GAS must be checked BEFORE general COSTCO (groceries)
        if (normalized.contains("costco gas") || normalized.contains("costcogas") ||
            descLower.contains("costco gas") || descLower.contains("costcogas")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Costco Gas → 'transportation'");
            return "transportation";
        }
        
        // CRITICAL: COSTCO WHSE (Costco Warehouse) must be checked before general "costco"
        // "COSTCO WHSE" is a store location, not "costco wholesale corporation" (payroll)
        if (normalized.contains("costco whse") || normalized.contains("costcowhse") ||
            normalized.contains("costco warehouse") || normalized.contains("costcowarehouse") ||
            descLower.contains("costco whse") || descLower.contains("costcowhse") ||
            descLower.contains("costco warehouse") || descLower.contains("costcowarehouse")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Costco Warehouse (COSTCO WHSE) → 'groceries'");
            return "groceries";
        }
        
        // Additional US grocery chains
        String[] usGroceryStores = {
            "whole foods", "wholefoods", "wf ", "trader joe", "traderjoe", "kroger",
            "albertsons", "publix", "wegmans", "h-e-b", "heb", "stop & shop", "stopandshop",
            "giant eagle", "gianteagle", "meijer", "food lion", "foodlion",
            "ralphs", "vons", "fred meyer", "fredmeyer", "smiths", "king soopers",
            "harris teeter", "harristeeter", "sprouts", "sprouts farmers market",
            "aldi", "lidl", "costco", "sam's club", "samsclub", "bj's", "bjs",
            "target", "walmart", "walmart supercenter", "supercenter"
        };
        for (String store : usGroceryStores) {
            if (normalized.contains(store)) {
                return "groceries";
            }
        }
        
        // Indian Grocery Stores
        String[] indianGroceryStores = {
            "indian supermarket", "indian store", "indian market", "indian grocery",
            "patel brothers", "patelbrothers", "apna bazaar", "apnabazaar",
            "namaste plaza", "namasteplaza", "bombay bazaar", "bombaybazaar",
            "india gate", "indiagate", "spice bazaar", "spicebazaar"
        };
        for (String store : indianGroceryStores) {
            if (normalized.contains(store) || descLower.contains(store)) {
                return "groceries";
            }
        }
        
        // Global Grocery Patterns
        if (normalized.contains("supermarket") || normalized.contains("super market") ||
            normalized.contains("grocery") || normalized.contains("grocery store") ||
            normalized.contains("food market") || normalized.contains("foodmart") ||
            normalized.contains("hypermarket") || normalized.contains("hyper market")) {
            return "groceries";
        }
        
        // ========== DINING - Fast Food & Restaurants ==========
        // Fast Food Chains
        if (normalized.contains("subway") || descLower.contains("subway")) {
            return "dining";
        }
        if (normalized.contains("panda express") || normalized.contains("pandaexpress") || descLower.contains("panda express")) {
            return "dining";
        }
        if (normalized.contains("starbucks") || descLower.contains("starbucks")) {
            return "dining";
        }
        if (normalized.contains("chipotle") || descLower.contains("chipotle")) {
            return "dining";
        }
        
        // Additional Fast Food Chains
        String[] fastFoodChains = {
            "mcdonald", "mcdonalds", "burger king", "burgerking", "wendy's", "wendys",
            "taco bell", "tacobell", "kfc", "pizza hut", "pizzahut", "domino's", "dominos",
            "papa john's", "papajohns", "little caesar", "littlecaesar", "papa murphy", "papamurphy",
            "dunkin", "dunkin donuts", "dunkindonuts", "tim hortons", "timhortons",
            "panera", "panera bread", "panerabread", "jamba juice", "jambajuice",
            "smoothie king", "smoothieking", "qdoba", "moe's", "moes", "baja fresh", "bajafresh"
        };
        for (String chain : fastFoodChains) {
            if (normalized.contains(chain) || descLower.contains(chain)) {
                return "dining";
            }
        }
        
        // Cafe & Cafeteria Patterns
        if (normalized.contains("cafe") || normalized.contains("café") || 
            normalized.contains("cafeteria") || normalized.contains("coffee") ||
            normalized.contains("espresso") || normalized.contains("latte") ||
            descLower.contains("cafe") || descLower.contains("café") ||
            descLower.contains("cafeteria")) {
            return "dining";
        }
        
        // Tiffins (Indian meal delivery)
        if (normalized.contains("tiffin") || descLower.contains("tiffin") ||
            normalized.contains("tiffins") || descLower.contains("tiffins")) {
            return "dining";
        }
        
        // Specific Restaurants - Must come before general restaurant patterns
        String[] specificRestaurants = {
            "daeho", "tutta bella", "tuttabella", "simply indian restaur", "simply indian restaurant",
            "simplyindian restaur", "simplyindian restaurant", "skills rainbow room", "skillsrainbow room",
            "kyurmaen", "kyurmaen ramen", "deep dive", "deepdive", "messina", "supreme dumplings",
            "supremedumplings", "cucina venti", "cucinaventi", "desi dhaba", "desidhaba", "medocinofarms",
            "medocino farms", "laughing monk brewing", "laughingmonk brewing", "laughing monk", "laughingmonk",
            "indian sizzler", "indiansizzler", "shana thai", "shanathai", "tpd",
            "burger and kabob hut", "burgerandkabobhut", "kabob hut", "kabobhut",
            "insomnia cookies", "insomniacookies", "insomnia cookie",
            "banaras", "banaras restaurant", "banarasrestaurant",
            "resy", "maxmillen", "maxmillian", "maximilian"
        };
        for (String restaurant : specificRestaurants) {
            if (normalized.contains(restaurant) || descLower.contains(restaurant)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected restaurant '{}' → 'dining'", restaurant);
                return "dining";
            }
        }
        
        // TST* pattern - Transaction Service Terminal (restaurant pattern)
        if (normalized.contains("tst*") || descLower.contains("tst*") ||
            (merchantName != null && merchantName.toUpperCase().startsWith("TST*")) ||
            (description != null && description.toUpperCase().startsWith("TST*"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected TST* pattern → 'dining'");
            return "dining";
        }
        
        // Food-related keywords that indicate restaurants (dumplings, burger, fast food, grill, thai, dhaba, brewing)
        if (normalized.contains("dumplings") || normalized.contains("dumpling") ||
            normalized.contains("burger") || normalized.contains("burgers") ||
            normalized.contains("fast food") || normalized.contains("fastfood") ||
            normalized.contains("grill") || normalized.contains("grilled") ||
            normalized.contains("thai") || normalized.contains("dhaba") ||
            normalized.contains("brewing") || normalized.contains("brewery") ||
            normalized.contains("brew pub") || normalized.contains("brewpub") ||
            descLower.contains("dumplings") || descLower.contains("dumpling") ||
            descLower.contains("burger") || descLower.contains("burgers") ||
            descLower.contains("fast food") || descLower.contains("fastfood") ||
            descLower.contains("grill") || descLower.contains("grilled") ||
            descLower.contains("thai") || descLower.contains("dhaba") ||
            descLower.contains("brewing") || descLower.contains("brewery") ||
            descLower.contains("brew pub") || descLower.contains("brewpub")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected food/restaurant keyword → 'dining'");
            return "dining";
        }
        
        // Restaurant Patterns (Global) - Includes "restaur" as keyword for restaurant
        if (normalized.contains("restaurant") || normalized.contains("rest ") ||
            normalized.contains("restaur") || normalized.contains("restaur ") ||
            normalized.contains("diner") || normalized.contains("bistro") ||
            normalized.contains("steakhouse") ||
            normalized.contains("pizzeria") || normalized.contains("trattoria") ||
            normalized.contains("tavern") || normalized.contains("pub") ||
            descLower.contains("restaurant") || descLower.contains("restaur") ||
            descLower.contains("dining")) {
            return "dining";
        }
        
        // ========== PET ==========
        if (normalized.contains("petsmart") || descLower.contains("petsmart")) {
            return "pet";
        }
        if (normalized.contains("petco") || descLower.contains("petco")) {
            return "pet";
        }
        // SP Farmers Fetch Bones
        if (normalized.contains("sp farmers") || normalized.contains("fetch bones") ||
            normalized.contains("fetchbones") || descLower.contains("sp farmers") ||
            descLower.contains("fetch bones") || descLower.contains("fetchbones")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected SP Farmers Fetch Bones → 'pet'");
            return "pet";
        }
        if (normalized.contains("pet supplies") || normalized.contains("petsupplies") ||
            normalized.contains("pet supply") || descLower.contains("pet supplies")) {
            return "pet";
        }
        if (normalized.contains("petcare") || normalized.contains("pet care") ||
            descLower.contains("petcare") || descLower.contains("pet care")) {
            return "pet";
        }
        if (normalized.contains("pet clinic") || normalized.contains("petclinic") ||
            normalized.contains("veterinary") || normalized.contains("vet ") ||
            normalized.contains("animal hospital") || descLower.contains("pet clinic") ||
            descLower.contains("veterinary")) {
            return "pet";
        }
        
        // ========== SUBSCRIPTIONS (News/Investment Journals) ==========
        // CRITICAL: Must come BEFORE tech to prevent "J*Barrons" from matching tech patterns
        // Check both normalized and original strings for asterisks and special characters
        String merchantLower = (merchantName != null ? merchantName.toLowerCase() : "");
        String descLowerForJournals = (description != null ? description.toLowerCase() : "");
        
        String[] newsJournals = {
            "barrons", "barron's", "barron",
            "new york times", "nytimes", "ny times", "the new york times",
            "wall street journal", "wsj", "the wall street journal",
            "financial times", "ft.com", "the financial times",
            "economist", "the economist", "bloomberg", "bloomberg news",
            "reuters", "reuters news", "cnn", "cnn news", "bbc", "bbc news",
            "washington post", "wapo", "the washington post",
            "usa today", "usatoday", "los angeles times", "latimes",
            "chicago tribune", "boston globe", "the boston globe"
        };
        for (String journal : newsJournals) {
            if (normalized.contains(journal) || descLower.contains(journal) || 
                merchantLower.contains(journal) || descLowerForJournals.contains(journal)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected news/investment journal '{}' → 'subscriptions'", journal);
                return "subscriptions";
            }
        }
        // Special handling for patterns with asterisks (e.g., "J*Barrons")
        if ((merchantLower.contains("barrons") || merchantLower.contains("barron")) ||
            (descLowerForJournals.contains("barrons") || descLowerForJournals.contains("barron"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Barrons (with special chars) → 'subscriptions'");
            return "subscriptions";
        }
        
        // ========== ENTERTAINMENT (Streaming Services) ==========
        // CRITICAL: Must come BEFORE tech to prevent streaming services from matching tech patterns
        // Streaming services are entertainment, not subscriptions category
        // Subscriptions are detected separately via SubscriptionService
        String[] streamingServices = {
            "netflix", "hulu", "huluplus", "hulu plus", "disney", "disney+", "disneyplus",
            "hbo", "hbo max", "hbomax", "paramount", "paramount+", "paramount plus",
            "peacock", "nbc peacock", "spotify", "apple music", "applemusic",
            "youtube premium", "youtubepremium", "youtube tv", "youtubetv",
            "amazon prime", "amazonprime", "prime video", "primevideo",
            "showtime", "starz", "crunchyroll", "funimation"
        };
        for (String service : streamingServices) {
            if (normalized.contains(service) || descLower.contains(service)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected streaming service '{}' → 'entertainment'", service);
                return "entertainment";
            }
        }
        
        // ========== SUBSCRIPTIONS (Software/Non-Entertainment) ==========
        // CRITICAL: Must come BEFORE tech to prevent software subscriptions (Adobe, etc.) from matching tech patterns
        // Software subscriptions remain as subscriptions category
        String[] softwareSubscriptions = {
            "adobe", "adobe creative cloud", "microsoft 365", "office 365",
            "dropbox", "icloud", "google drive", "google one",
            "github", "github pro", "canva", "canva pro", "grammarly",
            "nordvpn", "expressvpn", "surfshark", "zoom", "slack"
        };
        for (String service : softwareSubscriptions) {
            if (normalized.contains(service) || descLower.contains(service)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected software subscription '{}' → 'subscriptions'", service);
                return "subscriptions";
            }
        }
        
        // ========== TECH ==========
        // AI/Tech Services - Must come after streaming services to prevent false matches
        String[] aiTechServices = {
            "chatgpt", "chat gpt", "openai", "open ai",
            "anthropic", "anthropic ai", "claude", "claude ai",
            "cohere", "hugging face", "huggingface",
            "cursor", "cursor ai", "github copilot", "copilot",
            "replicate", "together ai", "togetherai"
        };
        for (String service : aiTechServices) {
            if (normalized.contains(service) || descLower.contains(service)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected AI/tech service '{}' → 'tech'", service);
                return "tech";
            }
        }
        if (normalized.contains("cursor") || descLower.contains("cursor") ||
            normalized.contains("cursor ai") || descLower.contains("cursor ai")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Cursor → 'tech'");
            return "tech";
        }
        if (normalized.contains("ai powered") || descLower.contains("ai powered") ||
            normalized.contains("artificial intelligence") || descLower.contains("artificial intelligence")) {
            return "tech";
        }
        // Tech Companies
        String[] techCompanies = {
            "microsoft", "apple", "google", "amazon web services", "aws",
            "adobe", "oracle", "salesforce", "servicenow", "atlassian",
            "github", "gitlab", "slack", "zoom", "dropbox", "box",
            "notion", "figma", "sketch", "linear", "vercel", "netlify"
        };
        for (String company : techCompanies) {
            if (normalized.contains(company) || descLower.contains(company)) {
                return "tech";
            }
        }
        
        // Software/Technology Patterns
        if (normalized.contains("software") || normalized.contains("saas") ||
            normalized.contains("cloud") || normalized.contains("api") ||
            normalized.contains("developer") || normalized.contains("dev tools") ||
            descLower.contains("software") || descLower.contains("subscription")) {
            return "tech";
        }
        
        // ========== HOME IMPROVEMENT ==========
        if (normalized.contains("home depot") || normalized.contains("homedepot") ||
            descLower.contains("home depot")) {
            return "home improvement";
        }
        String[] homeImprovementStores = {
            "lowes", "lowe's", "menards", "ace hardware", "acehardware",
            "true value", "truevalue", "harbor freight", "harborfreight",
            "northern tool", "northerntool", "harbor freight tools"
        };
        for (String store : homeImprovementStores) {
            if (normalized.contains(store) || descLower.contains(store)) {
                return "home improvement";
            }
        }
        
        // Hardware/Home Improvement Patterns
        if (normalized.contains("hardware") || normalized.contains("home improvement") ||
            normalized.contains("homeimprovement") || normalized.contains("lumber") ||
            normalized.contains("building supply") || descLower.contains("hardware")) {
            return "home improvement";
        }
        
        // ========== DINING ==========
        // Bakeries (Hoffmans, Le Panier)
        if (normalized.contains("hoffman") || normalized.contains("hoffman's") ||
            descLower.contains("hoffman") || descLower.contains("hoffman's")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Hoffmans bakery → 'dining'");
            return "dining"; // Bakeries are dining
        }
        if (normalized.contains("le panier") || normalized.contains("lepanier") ||
            descLower.contains("le panier") || descLower.contains("lepanier")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Le Panier bakery → 'dining'");
            return "dining";
        }
        if (normalized.contains("bakery") || normalized.contains("baker") ||
            descLower.contains("bakery") || descLower.contains("baker")) {
            return "dining";
        }
        // Tea Lab, Chai, Boba, Mochinut
        if (normalized.contains("tea lab") || normalized.contains("tealab") ||
            normalized.contains("chai") || descLower.contains("chai") ||
            normalized.contains("boba") || descLower.contains("boba") ||
            normalized.contains("mochinut") || descLower.contains("mochinut")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected tea/chai/boba → 'dining'");
            return "dining";
        }
        // Ezell's Famous Chicken
        if (normalized.contains("ezell") || normalized.contains("ezells") ||
            descLower.contains("ezell") || descLower.contains("ezells")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Ezell's Famous Chicken → 'dining'");
            return "dining";
        }
        // honest.bellevue.com
        if (normalized.contains("honest.bellevue") || normalized.contains("honest") ||
            descLower.contains("honest.bellevue") || descLower.contains("honest")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected honest.bellevue.com → 'dining'");
            return "dining";
        }
        
        // ========== TRANSPORTATION ==========
        // CRITICAL: Airport expenses (carts, chairs, parking, etc.) must come BEFORE utilities check
        // "SEATTLEAP" (Seattle Airport) should not match "Seattle Public Utilities"
        if (normalized.contains("seattleap") || normalized.contains("seattle ap") ||
            normalized.contains("seattle airport") || descLower.contains("seattleap") ||
            descLower.contains("seattle ap") || descLower.contains("seattle airport")) {
            // Airport cart/chair rentals
            if (normalized.contains("cart") || normalized.contains("chair") ||
                descLower.contains("cart") || descLower.contains("chair")) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected airport cart/chair → 'transportation'");
                return "transportation";
            }
        }
        // General airport cart/chair patterns
        if ((normalized.contains("airport") || descLower.contains("airport")) &&
            (normalized.contains("cart") || normalized.contains("chair") ||
             descLower.contains("cart") || descLower.contains("chair"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected airport cart/chair → 'transportation'");
            return "transportation";
        }
        
        // ========== GAS STATIONS ==========
        if (normalized.contains("chevron") || descLower.contains("chevron")) {
            return "transportation";
        }
        String[] gasStations = {
            "shell", "bp ", "bp.", "exxon", "mobil", "esso",
            "arco", "valero", "citgo", "speedway", "7-eleven", "7eleven",
            "circle k", "circlek", "chevron", "texaco", "phillips 66", "phillips66",
            "conoco", "marathon", "sunoco", "sinclair", "kwik trip", "kwiktrip"
        };
        for (String station : gasStations) {
            if (normalized.contains(station) || descLower.contains(station)) {
                return "transportation";
            }
        }
        
        // CRITICAL FIX: "76" gas station must be more specific to avoid matching "CHECK 176"
        // Only match "76" if it's clearly a gas station (e.g., "76 station", "76 gas", "union 76")
        if ((normalized.contains("76 station") || normalized.contains("76 gas") || 
             normalized.contains("union 76") || normalized.contains("76 fuel") ||
             descLower.contains("76 station") || descLower.contains("76 gas") ||
             descLower.contains("union 76") || descLower.contains("76 fuel")) &&
            !normalized.contains("check") && !descLower.contains("check")) {
            return "transportation";
        }
        
        // Gas Station Patterns
        if (normalized.contains("gas station") || normalized.contains("gasstation") ||
            normalized.contains("fuel") || normalized.contains("petrol") ||
            normalized.contains("gas ") || descLower.contains("gas station")) {
            return "transportation";
        }
        
        // ========== ENTERTAINMENT ==========
        if (normalized.contains("amc") || descLower.contains("amc")) {
            return "entertainment";
        }
        // State Fair, Disney, Universal Studio, Sea World
        if (normalized.contains("state fair") || normalized.contains("statefair") ||
            normalized.contains("disney") || descLower.contains("disney") ||
            normalized.contains("universal studio") || normalized.contains("universalstudio") ||
            normalized.contains("universal studios") || descLower.contains("universal studio") ||
            normalized.contains("sea world") || normalized.contains("seaworld") ||
            descLower.contains("sea world") || descLower.contains("seaworld")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected theme park/fair → 'entertainment'");
            return "entertainment";
        }
        // Camping (Cape Disappointment, recreation.gov)
        if (normalized.contains("camping") || descLower.contains("camping") ||
            normalized.contains("cape disappointment") || descLower.contains("cape disappointment") ||
            normalized.contains("recreation.gov") || descLower.contains("recreation.gov")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected camping → 'entertainment'");
            return "entertainment";
        }
        String[] entertainmentVenues = {
            "cinemark", "regal", "carmike", "marcus", "harkins",
            "alamo drafthouse", "alamodrafthouse", "movie theater", "movietheater",
            "cinema", "theater", "theatre", "imax", "escape room", "escaperoom",
            "escape rooms", "escaperooms", "countdown rooms", "countdownrooms"
        };
        for (String venue : entertainmentVenues) {
            if (normalized.contains(venue) || descLower.contains(venue)) {
                return "entertainment";
            }
        }
        
        // Streaming Services - Must come before general entertainment patterns
        String[] streamingServicesInEntertainment = {
            "netflix", "hulu", "huluplus", "hulu plus", "disney", "disney+", "disneyplus",
            "hbo", "hbo max", "hbomax", "paramount", "paramount+", "paramount plus",
            "peacock", "nbc peacock", "spotify", "apple music", "applemusic",
            "youtube premium", "youtubepremium", "youtube tv", "youtubetv",
            "amazon prime", "amazonprime", "prime video", "primevideo",
            "showtime", "starz", "crunchyroll", "funimation"
        };
        for (String service : streamingServicesInEntertainment) {
            if (normalized.contains(service) || descLower.contains(service)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected streaming service '{}' → 'entertainment'", service);
                return "entertainment";
            }
        }
        
        // Entertainment Patterns
        if (normalized.contains("entertainment") || normalized.contains("arcade") ||
            normalized.contains("bowling") || normalized.contains("mini golf") ||
            normalized.contains("laser tag") || descLower.contains("entertainment")) {
            return "entertainment";
        }
        
        // Top Golf and similar entertainment venues
        if (normalized.contains("top golf") || normalized.contains("topgolf") ||
            descLower.contains("top golf") || descLower.contains("topgolf")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Top Golf → 'entertainment'");
            return "entertainment";
        }
        
        // Escape rooms (entertainment)
        if (normalized.contains("escape room") || normalized.contains("escaperoom") ||
            normalized.contains("conundroom") || descLower.contains("escape room") ||
            descLower.contains("escaperoom") || descLower.contains("conundroom")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected escape room → 'entertainment'");
            return "entertainment";
        }
        
        // ========== EDUCATION/SCHOOL PAYMENTS ==========
        // PayPAMS - online school payments for food (dining)
        if (normalized.contains("paypams") || normalized.contains("pay pams") ||
            descLower.contains("paypams") || descLower.contains("pay pams")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected PayPAMS (school food payment) → 'dining'");
            return "dining";
        }
        
        // Bellevue School District - school district payment (education/other, not charity)
        if (normalized.contains("bellevue school district") || normalized.contains("bellevueschooldistrict") ||
            descLower.contains("bellevue school district") || descLower.contains("bellevueschooldistrict")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Bellevue School District → 'other'");
            return "other";
        }
        
        // Education-related items (school, books, reading, tuition, etc.) - categorized as "other"
        // Note: Education maps to "other" in category mapping
        String[] educationKeywords = {
            "gurukul", "school", "university", "college", "tuition", "tuition fee",
            "books", "bookstore", "book store", "reading", "textbook", "text book",
            "education", "educational", "course", "class", "lesson", "training"
        };
        for (String edu : educationKeywords) {
            if (normalized.contains(edu) || descLower.contains(edu)) {
                // Skip if it's a school payment (PayPAMS, school district) - those are handled separately
                if (!normalized.contains("paypams") && !normalized.contains("school district") &&
                    !descLower.contains("paypams") && !descLower.contains("school district")) {
                    logger.debug("🏷️ detectCategoryFromMerchantName: Detected education item '{}' → 'other'", edu);
                    return "other";
                }
            }
        }
        
        // ========== CHARITY ==========
        // Go Fund Me, schools (middle school, school district, elementary, secondary, high school, senior secondary school)
        if (normalized.contains("go fund me") || normalized.contains("gofundme") ||
            descLower.contains("go fund me") || descLower.contains("gofundme")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Go Fund Me → 'charity'");
            return "charity";
        }
        String[] schoolKeywords = {
            "middle school", "middleschool", "school district", "schooldistrict",
            "elementary", "secondary school", "secondaryschool", "high school", "highschool",
            "senior secondary school", "seniorsecondaryschool"
        };
        for (String school : schoolKeywords) {
            if (normalized.contains(school) || descLower.contains(school)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected school '{}' → 'charity'", school);
                return "charity";
            }
        }
        // Charity/donation patterns
        if (normalized.contains("charity") || normalized.contains("donation") ||
            normalized.contains("non-profit") || normalized.contains("nonprofit") ||
            descLower.contains("charity") || descLower.contains("donation")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected charity/donation → 'charity'");
            return "charity";
        }
        
        // ========== HEALTH ==========
        // Health, fitness, gyms, beauty salons, hair cuts, golf, tennis, soccer, ski resorts, etc.
        String[] gymsAndFitness = {
            "proclub", "pro club", "24 hour fitness", "24hour fitness", "24hr fitness",
            "24-hour fitness", "24-hourfitness", "gold's gym", "golds gym", "goldsgym",
            "planet fitness", "planetfitness", "equinox", "lifetime fitness", "lifetimefitness",
            "ymca", "ymca fitness", "la fitness", "lafitness", "crunch fitness", "crunchfitness",
            "anytime fitness", "anytimefitness", "orange theory", "orangetheory", "crossfit",
            "fitness", "gym", "health club", "healthclub", "athletic club", "athleticclub",
            "fitness center", "fitnesscenter", "workout", "personal trainer", "personaltrainer",
            "seattle badminton club", "badminton"
        };
        for (String gym : gymsAndFitness) {
            if (normalized.contains(gym) || descLower.contains(gym)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected gym/fitness '{}' → 'health'", gym);
                return "health";
            }
        }
        
        // Beauty salons, hair cuts, makeup, body waxing, nails, spa, massages, toes, skin
        String[] beautyServices = {
            "beauty salon", "beautysalon", "beauty parlor", "beautyparlor",
            "hair salon", "hairsalon", "hair cut", "haircut", "hair cuts", "haircuts",
            "hair color", "haircolor", "hair coloring", "haircoloring",
            "body waxing", "bodywaxing", "waxing", "makeup", "make up",
            "beauty studio", "beautystudio", "salon", "saloon",
            "supercuts", "super cuts", "great clips", "greatclips",
            "lucky hair salon", "lucky hair salin", "luckyhair", "luckyhairsalin",
            "nails", "nail salon", "nailsalon", "nail", "manicure", "pedicure",
            "spa", "massage", "massages", "toes", "skin", "skin care", "skincare",
            "stop 4 nails", "stop4nails", "stop four nails", "stopfournails",
            "cosmetic store", "cosmeticstore", "cosmetics", "makeup store", "makeupstore"
        };
        for (String beauty : beautyServices) {
            if (normalized.contains(beauty) || descLower.contains(beauty)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected beauty service '{}' → 'health'", beauty);
                return "health";
            }
        }
        
        // Golf (indoor and outdoor)
        if (normalized.contains("golf") || descLower.contains("golf") ||
            normalized.contains("golf course") || descLower.contains("golf course") ||
            normalized.contains("driving range") || descLower.contains("driving range") ||
            normalized.contains("golf club") || descLower.contains("golf club")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected golf → 'health'");
            return "health";
        }
        
        // Tennis
        if (normalized.contains("tennis") || descLower.contains("tennis") ||
            normalized.contains("tennis court") || descLower.contains("tennis court") ||
            normalized.contains("tennis club") || descLower.contains("tennis club")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected tennis → 'health'");
            return "health";
        }
        
        // Soccer and other sports activities
        if (normalized.contains("soccer") || descLower.contains("soccer") ||
            normalized.contains("football") || descLower.contains("football") ||
            normalized.contains("basketball") || descLower.contains("basketball") ||
            normalized.contains("baseball") || descLower.contains("baseball") ||
            normalized.contains("swimming") || descLower.contains("swimming") ||
            normalized.contains("yoga") || descLower.contains("yoga") ||
            normalized.contains("pilates") || descLower.contains("pilates") ||
            normalized.contains("martial arts") || descLower.contains("martial arts")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected sports activity → 'health'");
            return "health";
        }
        
        // Ski resorts (Summit at Snoqualmie, etc.)
        // Note: Mini Mountain is ski-gear/equipment, not a resort - handled in shopping section
        if (normalized.contains("ski resort") || descLower.contains("ski resort") ||
            normalized.contains("summit at snoqualmie") || descLower.contains("summit at snoqualmie")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected ski resort → 'health'");
            return "health";
        }
        // Ski activities (skiing, ski lessons, etc.) - but not ski gear/equipment
        if ((normalized.contains("ski") || descLower.contains("ski")) &&
            !normalized.contains("mini mountain") && !descLower.contains("mini mountain") &&
            !normalized.contains("ski gear") && !descLower.contains("ski gear") &&
            !normalized.contains("ski equipment") && !descLower.contains("ski equipment")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected ski activity → 'health'");
            return "health";
        }
        
        // ========== HEALTHCARE ==========
        
        String[] healthcareProviders = {
            "cvs", "walgreens", "rite aid", "riteaid", "pharmacy",
            "urgent care", "urgentcare", "hospital", "clinic", "medical center",
            "medicalcenter", "doctor", "physician", "dentist", "dental"
        };
        for (String provider : healthcareProviders) {
            if (normalized.contains(provider) || descLower.contains(provider)) {
                return "healthcare";
            }
        }
        
        // ========== UTILITIES ==========
        // CRITICAL: Check for utility companies and energy providers BEFORE transportation
        // CRITICAL: Check for municipal utilities FIRST (e.g., "CITY OF BELLEVUE UTILITY")
        // This prevents "CITY" from matching transportation patterns
        if ((normalized.contains("city of") || descLower.contains("city of")) &&
            (normalized.contains("utility") || normalized.contains("utilities") ||
             descLower.contains("utility") || descLower.contains("utilities"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected municipal utility (city of ... utility) → 'utilities'");
            return "utilities";
        }
        
        // Common utility company patterns
        String[] utilityCompanies = {
            "puget sound energy", "pse", "pacific gas", "pg&e", "pge",
            "southern california edison", "sce", "san diego gas", "sdge",
            "edison", "con edison", "coned", "duke energy", "dukeenergy",
            "dominion energy", "dominionenergy", "exelon", "first energy", "firstenergy",
            "american electric", "aep", "southern company", "southerncompany",
            "next era", "nextera", "xcel energy", "xcelenergy", "centerpoint",
            "center point", "entergy", "entergy", "evergy", "evergy",
            "pacificorp", "pacific corp", "pge", "portland general", "portlandgeneral"
        };
        for (String company : utilityCompanies) {
            if (normalized.contains(company) || descLower.contains(company)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected utility company '{}' → 'utilities'", company);
                return "utilities";
            }
        }
        
        // Utility patterns (energy, electric, gas, water, etc.)
        // CRITICAL: Check for "utility" keyword BEFORE transportation to prevent false matches
        if (normalized.contains("energy") || normalized.contains("ener ") || normalized.contains("ener billpay") ||
            normalized.contains("electric") || normalized.contains("electricity") ||
            normalized.contains("utility") || normalized.contains("utilities") ||
            normalized.contains("gas company") || normalized.contains("gascompany") ||
            normalized.contains("water company") || normalized.contains("watercompany") ||
            normalized.contains("power company") || normalized.contains("powercompany") ||
            descLower.contains("energy") || descLower.contains("ener ") || descLower.contains("ener billpay") ||
            descLower.contains("electric") || descLower.contains("electricity") ||
            descLower.contains("utility") || descLower.contains("utilities") ||
            descLower.contains("gas company") || descLower.contains("water company") ||
            descLower.contains("power company")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected utility pattern → 'utilities'");
            return "utilities";
        }
        
        // Bill payment patterns (often utilities)
        if ((normalized.contains("billpay") || normalized.contains("bill pay") || 
             descLower.contains("billpay") || descLower.contains("bill pay")) &&
            (normalized.contains("ener") || normalized.contains("energy") || 
             normalized.contains("electric") || normalized.contains("gas") ||
             normalized.contains("water") || normalized.contains("utility") ||
             descLower.contains("ener") || descLower.contains("energy") ||
             descLower.contains("electric") || descLower.contains("gas") ||
             descLower.contains("water") || descLower.contains("utility"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected utility bill payment → 'utilities'");
            return "utilities";
        }
        
        // CRITICAL: Cable/Internet Providers - Must come BEFORE transportation
        // Comcast, Xfinity, Spectrum, Charter, Cox, etc.
        String[] cableInternetProviders = {
            "comcast", "xfinity", "xfinity mobile", "xfinitymobile",
            "spectrum", "charter", "charter spectrum",
            "cox", "cox communications",
            "optimum", "altice", "frontier", "frontier communications",
            "centurylink", "century link", "windstream", "suddenlink", "mediacom",
            "dish", "dish network", "directv", "direct tv",
            "att u-verse", "att uverse", "fios", "verizon fios"
        };
        for (String provider : cableInternetProviders) {
            if (normalized.contains(provider) || descLower.contains(provider)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected cable/internet provider '{}' → 'utilities'", provider);
                return "utilities";
            }
        }
        
        // CRITICAL: Phone/Mobile providers - Must come BEFORE transportation to prevent "mobile" matching transportation
        // Verizon Wireless, AT&T, T-Mobile, etc. (excluding Xfinity Mobile which is already covered above)
        String[] phoneProviders = {
            "verizon wireless", "verizonwireless", "verizon",
            "at&t", "att", "at and t", "t-mobile", "tmobile", "t mobile",
            "sprint", "us cellular", "uscellular", "cricket", "cricket wireless",
            "boost mobile", "boostmobile", "metropcs", "metro pcs", "metropcs",
            "mint mobile", "mintmobile", "google fi", "googlefi", "visible",
            "straight talk", "straighttalk", "us mobile", "usmobile"
        };
        for (String provider : phoneProviders) {
            if (normalized.contains(provider) || descLower.contains(provider)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected phone/mobile provider '{}' → 'utilities'", provider);
                return "utilities";
            }
        }
        
        // ========== TRANSPORTATION ==========
        String[] transportationServices = {
            "uber", "lyft", "taxi", "rapido", "pay by phone","cab", "rideshare",
            "parkmobile", "didi", "grab", "ola", "careem", "gett", "bolt", "amtrak", "greyhound", "bus ", "transit", "metro",
            "parking", "parking meter", "parkingmeter", "garage",
            "metropolis parking", "metropolisparking", "impark",
            "uw pay by phone", "uwpay by phone", "gojek", "cabify","blablacar",
            "Indrive", "Waymo", "Chauffeur", "zoox", "yellow cab", "checkers cab",
            "black cab"
        };
        for (String service : transportationServices) {
            if (normalized.contains(service) || descLower.contains(service)) {
                logger.debug("detectCategoryFromMerchantName: Detected transportation service '{}' → 'transportation'", service);
                return "transportation";
            }
        }
        
        // Parking-specific detection (Metropolis, Impark, UW pay by phone, etc.)
        if (normalized.contains("metropolis") && (normalized.contains("parking") || descLower.contains("parking"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Metropolis parking → 'transportation'");
            return "transportation";
        }
        if (normalized.contains("impark") || descLower.contains("impark")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Impark parking → 'transportation'");
            return "transportation";
        }
        // UW pay by phone is parking, not utilities
        if ((normalized.contains("uw") || descLower.contains("uw")) && 
            (normalized.contains("pay by phone") || descLower.contains("pay by phone"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected UW pay by phone (parking) → 'transportation'");
            return "transportation";
        }
        
        // State Department of Transportation (DOT) patterns - Toll roads, highway authorities
        // Pattern: [State] DOT, [State] Department of Transportation, State Toll Authority
        String[] stateDOTPatterns = {
            // Washington State
            "wsdot", "washington state dot", "washington state department of transportation",
            "goodtogo", "good to go", "good-to-go",
            // California
            "caltrans", "cal trans", "california dot", "california department of transportation",
            "fastrak", "fastrak", "fast trak", "ez pass california",
            // New York
            "nysdot", "ny dot", "new york state dot", "new york state department of transportation",
            "ez pass", "ezpass", "e-zpass", "new york thruway",
            // Texas
            "txdot", "texas dot", "texas department of transportation",
            "ez tag", "eztag", "txtag", "tx tag", "dallas north tollway",
            // Florida
            "fdot", "florida dot", "florida department of transportation",
            "sunpass", "sun pass", "epass", "e pass", "leeway", "lee way",
            // Illinois
            "idot", "illinois dot", "illinois department of transportation",
            "ipass", "i-pass", "illinois tollway",
            // Massachusetts
            "massdot", "mass dot", "massachusetts dot", "massachusetts department of transportation",
            "e-zpass ma", "ezpass ma", "mass pike",
            // Pennsylvania
            "penn dot", "penndot", "pennsylvania dot", "pennsylvania department of transportation",
            "e-zpass pa", "ezpass pa", "pa turnpike", "pennsylvania turnpike",
            // New Jersey
            "njdot", "nj dot", "new jersey dot", "new jersey department of transportation",
            "e-zpass nj", "ezpass nj", "garden state parkway", "new jersey turnpike",
            // Maryland
            "mdot", "md dot", "maryland dot", "maryland department of transportation",
            "e-zpass md", "ezpass md", "maryland transportation authority",
            // Virginia
            "vdot", "va dot", "virginia dot", "virginia department of transportation",
            "ez-pass va", "ezpass va", "virginia transportation authority",
            // General patterns
            "state dot", "state department of transportation", "department of transportation",
            "dot toll", "toll road", "tollway", "toll authority", "toll plaza",
            "highway authority", "transportation authority", "turnpike authority",
            // Additional toll patterns
            "eractoll", "era toll", "toll payment", "toll charge", "toll fee",
            "road toll", "bridge toll", "tunnel toll", "highway toll", "expressway toll"
        };
        for (String dot : stateDOTPatterns) {
            if (normalized.contains(dot) || descLower.contains(dot)) {
                logger.debug("🏷️ detectCategoryFromMerchantName: Detected state DOT/toll '{}' → 'transportation'", dot);
                return "transportation";
            }
        }
        
        // Amex Airlines Fee Reimbursement - transportation (even though it's a credit)
        if (normalized.contains("amex airlines fee reimbursement") || 
            normalized.contains("amexairlinesfeereimbursement") ||
            descLower.contains("amex airlines fee reimbursement") ||
            descLower.contains("amexairlinesfeereimbursement") ||
            (normalized.contains("amex") && normalized.contains("airlines") && 
             (normalized.contains("fee") || normalized.contains("reimbursement")))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Amex Airlines Fee Reimbursement → 'transportation'");
            return "transportation";
        }
        
        // Car Service (Hona CTR, etc.)
        if (normalized.contains("hona ctr") || normalized.contains("honactr") ||
            normalized.contains("hona car service") || normalized.contains("honacarservice") ||
            descLower.contains("hona ctr") || descLower.contains("honactr") ||
            descLower.contains("hona car service") || descLower.contains("honacarservice") ||
            (normalized.contains("car service") || descLower.contains("car service"))) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected car service → 'transportation'");
            return "transportation";
        }
        
        // ========== SHOPPING ==========
        // Sports equipment/gear (ski gear, outdoor equipment, etc.) - Must come BEFORE clothing/apparel
        if (normalized.contains("mini mountain") || descLower.contains("mini mountain") ||
            normalized.contains("minimountain") || descLower.contains("minimountain")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Mini Mountain (ski-gear) → 'shopping'");
            return "shopping";
        }
        // Sports equipment patterns
        if (normalized.contains("ski gear") || normalized.contains("skigear") ||
            normalized.contains("ski equipment") || normalized.contains("skiequipment") ||
            normalized.contains("sports equipment") || normalized.contains("sportsequipment") ||
            normalized.contains("outdoor gear") || normalized.contains("outdoorgear") ||
            descLower.contains("ski gear") || descLower.contains("sports equipment") ||
            descLower.contains("outdoor gear")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected sports equipment/gear → 'shopping'");
            return "shopping";
        }
        
        // Clothing/Apparel patterns - Must come BEFORE general shopping stores
        String merchantLowerForShopping = (merchantName != null ? merchantName.toLowerCase() : "");
        String descLowerForShopping = (description != null ? description.toLowerCase() : "");
        
        if (normalized.contains("clothing") || normalized.contains("apparel") ||
            normalized.contains("men's clothing") || normalized.contains("mens clothing") ||
            normalized.contains("women's clothing") || normalized.contains("womens clothing") ||
            normalized.contains("men's apparel") || normalized.contains("mens apparel") ||
            normalized.contains("women's apparel") || normalized.contains("womens apparel") ||
            descLower.contains("clothing") || descLower.contains("apparel") ||
            descLower.contains("men's clothing") || descLower.contains("mens clothing") ||
            descLower.contains("women's clothing") || descLower.contains("womens clothing") ||
            merchantLowerForShopping.contains("clothing") || merchantLowerForShopping.contains("apparel") ||
            merchantLowerForShopping.contains("men's") || merchantLowerForShopping.contains("mens") ||
            merchantLowerForShopping.contains("women's") || merchantLowerForShopping.contains("womens") ||
            descLowerForShopping.contains("clothing") || descLowerForShopping.contains("apparel") ||
            descLowerForShopping.contains("men's") || descLowerForShopping.contains("mens") ||
            descLowerForShopping.contains("women's") || descLowerForShopping.contains("womens")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected clothing/apparel → 'shopping'");
            return "shopping";
        }
        
        String[] shoppingStores = {
            "target", "walmart", "costco", "best buy", "bestbuy",
            "macy's", "macys", "nordstrom", "tj maxx", "tjmaxx",
            "marshalls", "ross", "burlington", "kohl's", "kohls",
            "daiso"
        };
        for (String store : shoppingStores) {
            if (normalized.contains(store) || descLower.contains(store)) {
                return "shopping";
            }
        }
        // Daiso (retail chain)
        if (normalized.contains("daiso") || descLower.contains("daiso")) {
            logger.debug("🏷️ detectCategoryFromMerchantName: Detected Daiso → 'shopping'");
            return "shopping";
        }
        
        
        // ========== TRAVEL ==========
        String[] travelServices = {
            "airline", "airlines", "delta", "united", "american airlines",
            "southwest", "jetblue", "alaska", "hotel", "marriott",
            "hilton", "hyatt", "holiday inn", "holidayinn", "airbnb"
        };
        for (String service : travelServices) {
            if (normalized.contains(service) || descLower.contains(service)) {
                return "travel";
            }
        }
        
        logger.debug("detectCategoryFromMerchantName: No match found for merchant '{}'", merchantName);
        return null;
    }
    
    /**
     * Description-Based Category Detection
     * Uses description keywords when merchant name detection fails
     * 
     * @param description Transaction description
     * @param merchantName Merchant name (for context)
     * @param amount Transaction amount (for context)
     * @return Detected category or null if no match
     */
    private String detectCategoryFromDescription(String description, String merchantName, BigDecimal amount) {
        if (description == null || description.trim().isEmpty()) {
            return null;
        }
        
        String descLower = description.toLowerCase();
        logger.debug("detectCategoryFromDescription: Analyzing description='{}'", description);
        
        // CRITICAL FIX: Check description for utility companies and patterns (merchant name might be in description)
        String normalizedDesc = normalizeMerchantName(description).toLowerCase();
        String[] utilityCompanies = {
            "puget sound energy", "pse", "pacific gas", "pg&e", "pge",
            "southern california edison", "sce", "san diego gas", "sdge",
            "edison", "con edison", "coned", "duke energy", "dukeenergy",
            "dominion energy", "dominionenergy", "exelon", "first energy", "firstenergy",
            "american electric", "aep", "southern company", "southerncompany"
        };
        for (String company : utilityCompanies) {
            if (normalizedDesc.contains(company) || descLower.contains(company)) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected utility company '{}' → 'utilities'", company);
                return "utilities";
            }
        }
        
        // Utility patterns in description (ENER, ENERGY, BILLPAY, etc.)
        if (descLower.contains("ener ") || descLower.contains("ener billpay") || 
            descLower.contains("energy") || descLower.contains("electric") || 
            descLower.contains("electricity") || descLower.contains("utility") ||
            descLower.contains("utilities") || descLower.contains("gas company") ||
            descLower.contains("water company") || descLower.contains("power company")) {
            // If it's a bill payment with energy/utility keywords, it's utilities
            if (descLower.contains("billpay") || descLower.contains("bill pay")) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected utility bill payment → 'utilities'");
                return "utilities";
            }
        }
        
        // CRITICAL FIX: Check description for specific grocery store names (merchant name might be in description)
        // This handles cases where CSV doesn't have a separate merchant name column
        if (normalizedDesc.contains("safeway") || normalizedDesc.contains("safeway.com") || 
            normalizedDesc.startsWith("safeway") || normalizedDesc.endsWith("safeway")) {
            logger.debug("detectCategoryFromDescription: Detected Safeway in description → 'groceries'");
            return "groceries";
        }
        
        // CRITICAL FIX: Check for PCC (PCC Natural Markets / PCC Community Markets) in description
        // PCC often appears in description field when merchant name is null
        if (normalizedDesc.contains("pcc") || descLower.contains("pcc") ||
            normalizedDesc.contains("pcc natural markets") || normalizedDesc.contains("pcc community markets")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected PCC in description → 'groceries'");
            return "groceries";
        }
        
        // CRITICAL FIX: Check for Subway in description (common when merchantName is null)
        if (normalizedDesc.contains("subway") || descLower.contains("subway")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected Subway in description → 'dining'");
            return "dining";
        }
        
        // CRITICAL FIX: Check for AMC and other movie theaters in description
        // AMC often appears in description field when merchant name is null
        if (normalizedDesc.contains("amc") || descLower.contains("amc")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected AMC in description → 'entertainment'");
            return "entertainment";
        }
        
        // Grocery keywords in description
        if (descLower.contains("grocery") || descLower.contains("supermarket") ||
            descLower.contains("food market") || descLower.contains("produce")) {
            return "groceries";
        }
        
        // Sunny Honey Company in description
        if (descLower.contains("sunny honey") || descLower.contains("sunnyhoney")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected Sunny Honey Company → 'groceries'");
            return "groceries";
        }
        
        // Specific Restaurants in Description - Must come before general restaurant patterns
        String[] specificRestaurantsInDesc = {
            "daeho", "tutta bella", "tuttabella", "simply indian restaur", "simply indian restaurant",
            "simplyindian restaur", "simplyindian restaurant", "skills rainbow room", "skillsrainbow room",
            "kyurmaen", "kyurmaen ramen", "deep dive", "deepdive", "messina", "supreme dumplings",
            "supremedumplings", "cucina venti", "cucinaventi", "desi dhaba", "desidhaba", "medocinofarms",
            "medocino farms", "laughing monk brewing", "laughingmonk brewing", "laughing monk", "laughingmonk"
        };
        for (String restaurant : specificRestaurantsInDesc) {
            if (descLower.contains(restaurant) || normalizedDesc.contains(restaurant)) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected restaurant '{}' → 'dining'", restaurant);
                return "dining";
            }
        }
        
        // TST* pattern - Transaction Service Terminal (restaurant pattern)
        if (descLower.contains("tst*") || normalizedDesc.contains("tst*") ||
            (description != null && description.toUpperCase().startsWith("TST*"))) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected TST* pattern → 'dining'");
            return "dining";
        }
        
        // Food-related keywords that indicate restaurants (dumplings, burger, fast food, grill, thai, dhaba, brewing)
        if (descLower.contains("dumplings") || descLower.contains("dumpling") ||
            descLower.contains("burger") || descLower.contains("burgers") ||
            descLower.contains("fast food") || descLower.contains("fastfood") ||
            descLower.contains("grill") || descLower.contains("grilled") ||
            descLower.contains("thai") || descLower.contains("dhaba") ||
            descLower.contains("brewing") || descLower.contains("brewery") ||
            descLower.contains("brew pub") || descLower.contains("brewpub") ||
            normalizedDesc.contains("dumplings") || normalizedDesc.contains("dumpling") ||
            normalizedDesc.contains("burger") || normalizedDesc.contains("burgers") ||
            normalizedDesc.contains("fast food") || normalizedDesc.contains("fastfood") ||
            normalizedDesc.contains("grill") || normalizedDesc.contains("grilled") ||
            normalizedDesc.contains("thai") || normalizedDesc.contains("dhaba") ||
            normalizedDesc.contains("brewing") || normalizedDesc.contains("brewery") ||
            normalizedDesc.contains("brew pub") || normalizedDesc.contains("brewpub")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected food/restaurant keyword → 'dining'");
            return "dining";
        }
        
        // Dining keywords (including bakeries, Tea Lab, Chai, Boba, Ezell's, Le Panier, Mochinut, honet.bellevue.com)
        // Includes "restaur" as keyword for restaurant
        if (descLower.contains("restaurant") || descLower.contains("rest ") ||
            descLower.contains("restaur") || descLower.contains("restaur ") ||
            descLower.contains("dining") ||
            descLower.contains("fast food") || descLower.contains("fastfood") ||
            descLower.contains("cafe") || descLower.contains("café") ||
            descLower.contains("cafeteria") || descLower.contains("tiffin") ||
            descLower.contains("bakery") || descLower.contains("baker") ||
            descLower.contains("hoffman") || descLower.contains("hoffman's") ||
            descLower.contains("tea lab") || descLower.contains("tealab") ||
            descLower.contains("chai") || descLower.contains("boba") ||
            descLower.contains("ezell") || descLower.contains("ezells") ||
            descLower.contains("le panier") || descLower.contains("lepanier") ||
            descLower.contains("mochinut") || descLower.contains("honet.bellevue") ||
            descLower.contains("honet") ||
            normalizedDesc.contains("restaurant") || normalizedDesc.contains("restaur")) {
            if (descLower.contains("hoffman") || descLower.contains("hoffman's")) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected Hoffmans bakery → 'dining'");
            }
            return "dining";
        }
        
        // Pet keywords (including SP Farmers Fetch Bones)
        if (descLower.contains("pet") || descLower.contains("veterinary") ||
            descLower.contains("vet ") || descLower.contains("animal") ||
            descLower.contains("sp farmers") || descLower.contains("fetch bones") ||
            descLower.contains("fetchbones")) {
            if (descLower.contains("sp farmers") || descLower.contains("fetch bones")) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected SP Farmers Fetch Bones → 'pet'");
            }
            return "pet";
        }
        
        // CRITICAL FIX: Check for Cursor AI and other tech companies in description
        if (normalizedDesc.contains("cursor") || descLower.contains("cursor") ||
            normalizedDesc.contains("ai powered") || descLower.contains("ai powered") ||
            normalizedDesc.contains("ide") || descLower.contains("ide")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected Cursor AI/tech in description → 'tech'");
            return "tech";
        }
        
        // Tech keywords
        if (descLower.contains("software") || descLower.contains("saas") ||
            descLower.contains("subscription") || descLower.contains("api") ||
            descLower.contains("developer") || descLower.contains("tech") ||
            descLower.contains("integrated development")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected tech keywords → 'tech'");
            return "tech";
        }
        
        // CRITICAL FIX: Check for gyms/fitness in description (common when merchantName is null)
        // Moved to health category
        String[] gymKeywords = {
            "proclub", "pro club", "24 hour fitness", "24hour fitness", "24hr fitness",
            "24-hour fitness", "gold's gym", "golds gym", "planet fitness", "equinox",
            "lifetime fitness", "ymca", "la fitness", "crunch fitness", "anytime fitness",
            "orange theory", "crossfit", "fitness", "gym", "health club", "athletic club",
            "fitness center", "workout", "personal trainer", "seattle badminton club", "badminton"
        };
        for (String gym : gymKeywords) {
            if (descLower.contains(gym) || normalizedDesc.contains(gym)) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected gym/fitness '{}' → 'health'", gym);
                return "health";
            }
        }
        
        // Beauty salons, hair cuts in description
        String[] beautyKeywords = {
            "beauty salon", "beautysalon", "beauty parlor", "beautyparlor",
            "hair salon", "hairsalon", "hair cut", "haircut", "hair cuts", "haircuts",
            "hair color", "haircolor", "body waxing", "bodywaxing", "waxing", "makeup",
            "beauty studio", "beautystudio", "salon", "supercuts", "super cuts",
            "great clips", "greatclips", "lucky hair salon", "lucky hair salin", "luckyhair", "luckyhairsalin"
        };
        for (String beauty : beautyKeywords) {
            if (descLower.contains(beauty) || normalizedDesc.contains(beauty)) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected beauty service '{}' → 'health'", beauty);
                return "health";
            }
        }
        
        // Sports activities in description (golf, tennis, soccer, ski resorts, etc.)
        // Note: Mini Mountain is ski-gear/equipment, not a sports activity - handled separately
        if (descLower.contains("golf") || descLower.contains("tennis") ||
            descLower.contains("soccer") || descLower.contains("football") ||
            descLower.contains("basketball") || descLower.contains("baseball") ||
            descLower.contains("swimming") || descLower.contains("yoga") ||
            descLower.contains("pilates") || descLower.contains("martial arts") ||
            descLower.contains("ski resort") || descLower.contains("summit at snoqualmie")) {
            // Check for "ski" but exclude "mini mountain" and equipment patterns
            if (descLower.contains("ski") && 
                !descLower.contains("mini mountain") && !descLower.contains("ski gear") &&
                !descLower.contains("ski equipment")) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected ski activity → 'health'");
                return "health";
            }
            logger.debug("🏷️ detectCategoryFromDescription: Detected sports activity → 'health'");
            return "health";
        }
        // Mini Mountain is ski-gear/equipment, not a sports activity
        if (descLower.contains("mini mountain") || normalizedDesc.contains("mini mountain") ||
            descLower.contains("minimountain") || normalizedDesc.contains("minimountain")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected Mini Mountain (ski-gear) → 'shopping'");
            return "shopping";
        }
        // Sports equipment patterns in description
        if (descLower.contains("ski gear") || descLower.contains("sports equipment") ||
            descLower.contains("outdoor gear") || normalizedDesc.contains("ski gear") ||
            normalizedDesc.contains("sports equipment") || normalizedDesc.contains("outdoor gear")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected sports equipment/gear → 'shopping'");
            return "shopping";
        }
        
        // Home improvement keywords (including Home Depot)
        if (descLower.contains("hardware") || descLower.contains("home improvement") ||
            descLower.contains("homeimprovement") || descLower.contains("lumber") ||
            descLower.contains("building supply") || descLower.contains("home depot") ||
            descLower.contains("homedepot") || descLower.contains("lowes") ||
            descLower.contains("menards")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected home improvement → 'home improvement'");
            return "home improvement";
        }
        
        // COSTCO GAS in description
        if (descLower.contains("costco gas") || descLower.contains("costcogas") ||
            normalizedDesc.contains("costco gas") || normalizedDesc.contains("costcogas")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected Costco Gas → 'transportation'");
            return "transportation";
        }
        
        // Travel centers (gas station + grocery + food) - BUC-EE's
        if (descLower.contains("buc-ee") || descLower.contains("buc-ee's") ||
            descLower.contains("bucee") || descLower.contains("bucees") ||
            normalizedDesc.contains("buc-ee") || normalizedDesc.contains("bucee")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected BUC-EE's (travel center) → 'transportation'");
            return "transportation";
        }
        
        // Gas keywords
        if (descLower.contains("gas station") || descLower.contains("gasstation") ||
            descLower.contains("fuel") || descLower.contains("petrol") ||
            descLower.contains("kwik sak") || descLower.contains("kwiksak") ||
            descLower.contains("kwik-sak") || normalizedDesc.contains("kwik sak")) {
            return "transportation";
        }
        
        // Parking keywords (including Metropolis parking, Impark, UW pay by phone)
        if (descLower.contains("parking") || descLower.contains("parking meter") ||
            descLower.contains("parkingmeter") || descLower.contains("garage") ||
            descLower.contains("metropolis parking") || descLower.contains("metropolisparking") ||
            descLower.contains("impark") ||
            descLower.contains("uw pay by phone") || descLower.contains("uwpay by phone") ||
            (descLower.contains("uw") && descLower.contains("pay by phone"))) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected parking → 'transportation'");
            return "transportation";
        }
        
        // QFC (grocery store) in description
        if (descLower.contains("qfc") || descLower.contains("quality food centers")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected QFC → 'groceries'");
            return "groceries";
        }
        
        // Education/School Payments
        // PayPAMS - online school payments for food (dining)
        if (descLower.contains("paypams") || descLower.contains("pay pams")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected PayPAMS (school food payment) → 'dining'");
            return "dining";
        }
        
        // Bellevue School District - school district payment (education/other, not charity)
        if (descLower.contains("bellevue school district") || descLower.contains("bellevueschooldistrict")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected Bellevue School District → 'other'");
            return "other";
        }
        
        // Charity keywords (Go Fund Me, schools, donations)
        if (descLower.contains("go fund me") || descLower.contains("gofundme") ||
            descLower.contains("middle school") || descLower.contains("middleschool") ||
            descLower.contains("school district") || descLower.contains("schooldistrict") ||
            descLower.contains("elementary") || descLower.contains("secondary school") ||
            descLower.contains("secondaryschool") || descLower.contains("high school") ||
            descLower.contains("highschool") || descLower.contains("senior secondary school") ||
            descLower.contains("charity") || descLower.contains("donation")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected charity/school → 'charity'");
            return "charity";
        }
        
        // Health/Beauty keywords (nails, hair, spa, toes, skin, massages, cosmetic stores)
        if (descLower.contains("nails") || descLower.contains("nail salon") || descLower.contains("nailsalon") ||
            descLower.contains("manicure") || descLower.contains("pedicure") ||
            descLower.contains("spa") || descLower.contains("massage") || descLower.contains("massages") ||
            descLower.contains("toes") || descLower.contains("skin") || descLower.contains("skin care") ||
            descLower.contains("skincare") || descLower.contains("stop 4 nails") || descLower.contains("stop4nails") ||
            descLower.contains("stop four nails") || descLower.contains("stopfournails") ||
            descLower.contains("hair salon") || descLower.contains("hairsalon") || descLower.contains("haircut") ||
            descLower.contains("beauty salon") || descLower.contains("beautysalon") ||
            descLower.contains("cosmetic store") || descLower.contains("cosmeticstore") ||
            descLower.contains("cosmetics") || descLower.contains("makeup store") || descLower.contains("makeupstore") ||
            descLower.contains("new york cosmetic") || descLower.contains("ny cosmetic")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected health/beauty service → 'health'");
            return "health";
        }
        
        // Amex Airlines Fee Reimbursement - transportation (even though it's a credit)
        if (descLower.contains("amex airlines fee reimbursement") || 
            descLower.contains("amexairlinesfeereimbursement") ||
            (descLower.contains("amex") && descLower.contains("airlines") && 
             (descLower.contains("fee") || descLower.contains("reimbursement")))) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected Amex Airlines Fee Reimbursement → 'transportation'");
            return "transportation";
        }
        
        // Toll patterns (Eractoll, etc.)
        if (descLower.contains("eractoll") || descLower.contains("era toll") ||
            descLower.contains("toll payment") || descLower.contains("toll charge") ||
            descLower.contains("toll fee") || descLower.contains("road toll") ||
            descLower.contains("bridge toll") || descLower.contains("tunnel toll") ||
            descLower.contains("highway toll") || descLower.contains("expressway toll")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected toll → 'transportation'");
            return "transportation";
        }
        
        // Car Service (Hona CTR, etc.)
        if (descLower.contains("hona ctr") || descLower.contains("honactr") ||
            descLower.contains("hona car service") || descLower.contains("honacarservice") ||
            descLower.contains("car service")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected car service → 'transportation'");
            return "transportation";
        }
        
        // Restaurant keywords (Burger and Kabob Hut, Insomnia Cookies, Banaras, Resy, Maxmillen)
        if (descLower.contains("burger and kabob hut") || descLower.contains("burgerandkabobhut") ||
            descLower.contains("kabob hut") || descLower.contains("kabobhut") ||
            descLower.contains("insomnia cookies") || descLower.contains("insomniacookies") ||
            descLower.contains("insomnia cookie") || descLower.contains("banaras") ||
            descLower.contains("banaras restaurant") || descLower.contains("banarasrestaurant") ||
            descLower.contains("resy") || descLower.contains("maxmillen") ||
            descLower.contains("maxmillian") || descLower.contains("maximilian")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected restaurant → 'dining'");
            return "dining";
        }
        
        // Education keywords (Gurukul, school, books, reading, etc.) - categorized as "other"
        if (descLower.contains("gurukul") || descLower.contains("school") ||
            descLower.contains("university") || descLower.contains("college") ||
            descLower.contains("tuition") || descLower.contains("books") ||
            descLower.contains("bookstore") || descLower.contains("book store") ||
            descLower.contains("reading") || descLower.contains("textbook") ||
            descLower.contains("text book") || descLower.contains("education") ||
            descLower.contains("educational") || descLower.contains("course") ||
            descLower.contains("class") || descLower.contains("lesson") ||
            descLower.contains("training")) {
            // Skip if it's a school payment (PayPAMS, school district) - those are handled separately
            if (!descLower.contains("paypams") && !descLower.contains("school district")) {
                logger.debug("🏷️ detectCategoryFromDescription: Detected education item → 'other'");
                return "other";
            }
        }
        
        // Entertainment keywords (State Fair, Disney, Universal Studio, Sea World, camping)
        // CRITICAL FIX: Check for movie theaters and entertainment venues in description
        // Use word boundaries for short names like "amc" to prevent false matches
        if (descLower.matches(".*\\bamc\\b.*") || 
            descLower.contains("cinemark") || descLower.contains("regal") ||
            descLower.contains("carmike") || descLower.contains("marcus") ||
            descLower.contains("harkins") || descLower.contains("alamo drafthouse") ||
            descLower.contains("movie theater") || descLower.contains("movietheater") ||
            descLower.contains("cinema") || descLower.contains("theater") || 
            descLower.contains("theatre") || descLower.contains("imax") ||
            descLower.contains("escape room") || descLower.contains("escaperoom") ||
            descLower.contains("conundroom") || descLower.contains("conundroom.us") ||
            descLower.contains("top golf") || descLower.contains("topgolf") ||
            descLower.contains("state fair") || descLower.contains("statefair") ||
            descLower.contains("disney") || descLower.contains("universal studio") ||
            descLower.contains("universalstudio") || descLower.contains("sea world") ||
            descLower.contains("seaworld") || descLower.contains("camping") ||
            descLower.contains("cape disappointment") || descLower.contains("recreation.gov")) {
            logger.debug("🏷️ detectCategoryFromDescription: Detected entertainment venue → 'entertainment'");
            return "entertainment";
        }
        
        logger.debug("detectCategoryFromDescription: No match found for description '{}'", description);
        return null;
    }
    
    /**
     * Sophisticated Transaction Type Determination
     * Uses category, amount, debit/credit indicator, and description to determine INCOME vs EXPENSE
     * 
     * @param category Detected category
     * @param amount Transaction amount
     * @param transactionTypeIndicator Debit/credit indicator from CSV
     * @param description Transaction description
     * @param paymentChannel Payment channel
     * @return "INCOME" or "EXPENSE"
     */
    private String determineTransactionType(String category, BigDecimal amount, 
                                           String transactionTypeIndicator, 
                                           String description, String paymentChannel) {
        // CRITICAL: Input validation and null safety
        String safeCategory = category != null ? category.trim().toLowerCase() : null;
        String safeTransactionTypeIndicator = transactionTypeIndicator != null ? transactionTypeIndicator.trim() : null;
        String safeDescription = description != null ? description.trim() : null;
        // NOTE: paymentChannel parameter is not used in this method - removed unused variable
        BigDecimal safeAmount = amount;
        
        // CRITICAL: Validate amount is reasonable
        if (amount != null) {
            BigDecimal maxAmount = BigDecimal.valueOf(1_000_000_000);
            BigDecimal minAmount = BigDecimal.valueOf(-1_000_000_000);
            if (amount.compareTo(maxAmount) > 0 || amount.compareTo(minAmount) < 0) {
                logger.warn("determineTransactionType: Amount out of reasonable range: {}, using null", amount);
                safeAmount = null;
            }
        }
        
        // STEP 1: Use explicit debit/credit indicator if available (most reliable)
        if (safeTransactionTypeIndicator != null && !safeTransactionTypeIndicator.isEmpty()) {
            String indicator = safeTransactionTypeIndicator.toLowerCase();
            if (indicator.contains("debit") || indicator.contains("dr") || 
                indicator.startsWith("db ") || indicator.startsWith("dr ") ||
                indicator.equals("db") || indicator.equals("dr")) {
                logger.debug("determineTransactionType: Using debit indicator → 'EXPENSE'");
                return "EXPENSE";
            }
            if (indicator.contains("credit") || indicator.contains("cr") ||
                indicator.startsWith("cr ") || indicator.equals("cr")) {
                // CRITICAL: Exclude credit card payments (they're expenses even if marked as "credit")
                if (!isCreditCardPayment(safeDescription, null, null)) {
                    logger.debug("determineTransactionType: Using credit indicator → 'INCOME'");
                    return "INCOME";
                } else {
                    logger.debug("determineTransactionType: Credit indicator but credit card payment → 'EXPENSE'");
                    return "EXPENSE";
                }
            }
        }
        
        // STEP 2: Use category to infer type (income categories → INCOME, expense categories → EXPENSE, payment → LOAN)
        if (safeCategory != null && !safeCategory.isEmpty()) {
            // CRITICAL FIX: Income categories MUST result in INCOME type, regardless of amount sign
            // Income categories (case-insensitive check)
            if (safeCategory.equals("salary") || safeCategory.equals("rsu") || 
                safeCategory.equals("interest") || safeCategory.equals("dividend") ||
                safeCategory.equals("income") || safeCategory.equals("deposit") ||
                safeCategory.equals("stipend") || safeCategory.equals("rentincome") ||
                safeCategory.equals("tips") || safeCategory.equals("otherincome")) {
                logger.info("determineTransactionType: ✅ Income category '{}' → 'INCOME' (amount: {})", category, safeAmount);
                return "INCOME";
            }
            
            // Investment categories - deposits are INVESTMENT type, withdrawals are INVESTMENT type (negative)
            if (safeCategory.equals("investment") || safeCategory.equals("cd") || 
                safeCategory.equals("bonds") || safeCategory.equals("stocks") ||
                safeCategory.equals("mutualfunds") || safeCategory.equals("etf") ||
                safeCategory.equals("401k") || safeCategory.equals("ira") ||
                safeCategory.equals("529") || safeCategory.equals("hsa")) {
                // Investment transactions are INVESTMENT type regardless of amount sign
                logger.info("determineTransactionType: ✅ Investment category '{}' → 'INVESTMENT' (amount: {})", category, safeAmount);
                return "INVESTMENT";
            }
            
            // CRITICAL FIX: Payment category should be LOAN type (not EXPENSE)
            // Payments to credit cards, loans, investments are LOAN type
            // This distinguishes payments (money going to debts/investments) from expenses (money spent on goods/services)
            if (safeCategory.equals("payment")) {
                // Check if it's a payment to credit card, loan, or investment
                if (isCreditCardPayment(safeDescription, null, null) ||
                    isLoanPayment(safeDescription, null, null) ||
                    (safeDescription != null && (safeDescription.toLowerCase().contains("credit card") || 
                     safeDescription.toLowerCase().contains("loan") ||
                     safeDescription.toLowerCase().contains("mortgage") || 
                     safeDescription.toLowerCase().contains("investment")))) {
                    logger.info("determineTransactionType: ✅ Payment category → 'LOAN' (credit card/loan/investment payment)");
                    return "LOAN";
                }
                // Generic payment (check, cash withdrawal, etc.) - still LOAN type
                logger.info("determineTransactionType: ✅ Payment category → 'LOAN' (generic payment)");
                return "LOAN";
            }
            
            // Cash withdrawal category - EXPENSE type
            if (safeCategory.equals("cash")) {
                logger.info("determineTransactionType: ✅ Cash category → 'EXPENSE'");
                return "EXPENSE";
            }
            
            // Transfer category - depends on context (could be transfer, investment transfer, etc.)
            if (safeCategory.equals("transfer")) {
                // Check if it's an investment transfer
                if (isInvestmentTransfer(safeDescription, null, null)) {
                    logger.info("determineTransactionType: ✅ Transfer category (investment) → 'INVESTMENT'");
                    return "INVESTMENT";
                }
                // Regular account transfer - use amount sign
                if (safeAmount != null) {
                    if (safeAmount.compareTo(BigDecimal.ZERO) > 0) {
                        logger.info("determineTransactionType: Transfer category with positive amount → 'INCOME'");
                        return "INCOME";
                    } else {
                        logger.info("determineTransactionType: Transfer category with negative amount → 'EXPENSE'");
                        return "EXPENSE";
                    }
                }
                // Default transfer to EXPENSE if amount is null
                logger.info("determineTransactionType: Transfer category with null amount → 'EXPENSE' (default)");
                return "EXPENSE";
            }
            
            // Expense categories (all others are expenses)
            // Common expense categories: groceries, dining, rent, utilities, transportation, etc.
            if (!safeCategory.equals("other")) {
                logger.debug("determineTransactionType: Expense category '{}' → 'EXPENSE'", category);
                return "EXPENSE";
            }
        }
        
        // STEP 3: For ambiguous categories (other, payment, transfer) or null category, use amount sign
        // CRITICAL: Handle zero amounts specially
        if (safeAmount != null) {
            int comparison = safeAmount.compareTo(BigDecimal.ZERO);
            if (comparison > 0) {
                // Positive amount = INCOME (money coming in)
                logger.debug("determineTransactionType: Positive amount {} → 'INCOME'", safeAmount);
                return "INCOME";
            } else if (comparison < 0) {
                // Negative amount = EXPENSE (money going out)
                logger.debug("determineTransactionType: Negative amount {} → 'EXPENSE'", safeAmount);
                return "EXPENSE";
            } else {
                // CRITICAL: Zero amount is ambiguous - use description/context
                logger.debug("determineTransactionType: Zero amount detected, using context");
                if (safeDescription != null && !safeDescription.isEmpty()) {
                    String descLower = safeDescription.toLowerCase();
                    // Zero amounts are often fees, adjustments, or transfers
                    if (descLower.contains("fee") || descLower.contains("adjustment") || 
                        descLower.contains("correction") || descLower.contains("charge")) {
                        logger.debug("determineTransactionType: Zero amount with fee/adjustment context → 'EXPENSE'");
                        return "EXPENSE";
                    }
                    if (descLower.contains("refund") || descLower.contains("credit")) {
                        logger.debug("determineTransactionType: Zero amount with refund/credit context → 'INCOME'");
                        return "INCOME";
                    }
                }
                // Default zero amounts to EXPENSE (more common for fees/adjustments)
                logger.debug("determineTransactionType: Zero amount without clear context → 'EXPENSE' (default)");
                return "EXPENSE";
            }
        }
        
        // STEP 4: Final fallback - no amount, no category, no indicator
        // CRITICAL: Default to EXPENSE (most common transaction type)
        logger.debug("determineTransactionType: No indicators available, defaulting to 'EXPENSE'");
        return "EXPENSE";
    }
}


