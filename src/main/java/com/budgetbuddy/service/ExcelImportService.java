package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * Excel Import Service
 * Parses Excel files (.xlsx, .xls) and converts them to transaction data
 * Reuses CSVImportService logic for date/amount parsing and transaction creation
 */
@Service
public class ExcelImportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelImportService.class);
    
    // Reuse CSV import service for parsing logic
    private final CSVImportService csvImportService;
    private final AccountDetectionService accountDetectionService;
    
    public ExcelImportService(CSVImportService csvImportService, AccountDetectionService accountDetectionService) {
        this.csvImportService = csvImportService;
        this.accountDetectionService = accountDetectionService;
    }

    /**
     * Import result containing parsed transactions
     */
    public static class ImportResult {
        private final List<CSVImportService.ParsedTransaction> transactions = new ArrayList<>();
        private int successCount = 0;
        private int failureCount = 0;
        private final List<String> errors = new ArrayList<>();
        private AccountDetectionService.DetectedAccount detectedAccount; // Detected account from import
        private String matchedAccountId; // Matched existing account ID (if found)
        
        public void addTransaction(CSVImportService.ParsedTransaction transaction) {
            transactions.add(transaction);
            successCount++;
        }
        
        public void addError(String error) {
            errors.add(error);
            failureCount++;
        }
        
        public List<CSVImportService.ParsedTransaction> getTransactions() {
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

    // Maximum transactions per Excel file
    private static final int MAX_TRANSACTIONS_PER_FILE = 10000;

    /**
     * Parse Excel file and return import result
     * @param inputStream The input stream containing Excel data
     * @param filename The filename (used for account detection)
     * @param userId The user ID (used for account matching)
     * @param password Optional password for password-protected files
     */
    public ImportResult parseExcel(InputStream inputStream, String filename, String userId, String password) {
        ImportResult result = new ImportResult();
        
        try (Workbook workbook = createWorkbook(inputStream)) {
            // Check file metadata and sheet names for account information
            logger.info("=== EXCEL FILE METADATA ANALYSIS ===");
            
            // Check workbook properties
            if (workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook) {
                org.apache.poi.ooxml.POIXMLProperties props = ((org.apache.poi.xssf.usermodel.XSSFWorkbook) workbook).getProperties();
                if (props != null) {
                    org.apache.poi.ooxml.POIXMLProperties.CoreProperties coreProps = props.getCoreProperties();
                    if (coreProps != null) {
                        String title = coreProps.getTitle();
                        String subject = coreProps.getSubject();
                        String creator = coreProps.getCreator();
                        if (title != null && !title.trim().isEmpty()) {
                            logger.info("Excel file title: '{}'", title);
                        }
                        if (subject != null && !subject.trim().isEmpty()) {
                            logger.info("Excel file subject: '{}'", subject);
                        }
                        if (creator != null && !creator.trim().isEmpty()) {
                            logger.info("Excel file creator: '{}'", creator);
                        }
                    }
                }
            }
            
            // Check all sheet names
            int sheetCount = workbook.getNumberOfSheets();
            logger.info("Excel file has {} sheet(s)", sheetCount);
            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                logger.info("Sheet {}: '{}' ({} rows)", i + 1, sheetName, sheet.getPhysicalNumberOfRows());
            }
            
            // Get first sheet (most common case)
            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = sheet.getSheetName();
            
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Excel file is empty or has no data");
            }
            
            logger.info("Using sheet: '{}' with {} rows", sheetName, sheet.getPhysicalNumberOfRows());
            
            // Use sheet name as additional context for account detection
            String detectionContext = filename;
            if (sheetName != null && !sheetName.trim().isEmpty() && !sheetName.equalsIgnoreCase("Sheet1")) {
                detectionContext = sheetName + " (" + filename + ")";
                logger.info("Using sheet name '{}' as context for account detection", sheetName);
            }
            
            // Read header row (first row)
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Excel file has no header row");
            }
            
            List<String> headers = extractHeaders(headerRow);
            if (headers.isEmpty()) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Excel file has no valid headers");
            }
            
            logger.info("Found {} columns in Excel file: {}", headers.size(), String.join(", ", headers));
            
            // Detect account information from filename, sheet name, and headers
            AccountDetectionService.DetectedAccount detectedAccount = null;
            String matchedAccountId = null;
            
            // Find balance column index for extracting balance from last transaction
            Integer balanceColumnIndex = null;
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                if (header != null && header.toLowerCase().trim().equals("balance")) {
                    balanceColumnIndex = i;
                    logger.info("✓ Found balance column at index {}: '{}'", i, header);
                    break;
                }
            }
            // Track balance from row with latest date (not necessarily last row)
            String latestDateBalanceValue = null;
            java.time.LocalDate latestDateForBalance = null;
            if (userId != null) {
                // Handle null filename
                if (filename == null) {
                    filename = "unknown.xlsx";
                }
                
                // First, try detecting from sheet name
                AccountDetectionService.DetectedAccount fromSheetName = null;
                if (sheetName != null && !sheetName.trim().isEmpty() && !sheetName.equalsIgnoreCase("Sheet1")) {
                    fromSheetName = accountDetectionService.detectFromFilename(sheetName);
                    if (fromSheetName != null) {
                        logger.info("Detected account info from sheet name '{}': institution={}, type={}, number={}", 
                            sheetName,
                            fromSheetName.getInstitutionName() != null ? fromSheetName.getInstitutionName() : "N/A",
                            fromSheetName.getAccountType() != null ? fromSheetName.getAccountType() : "N/A",
                            fromSheetName.getAccountNumber() != null ? fromSheetName.getAccountNumber() : "N/A");
                    }
                }
                
                // Detect from headers
                AccountDetectionService.DetectedAccount fromHeaders = 
                    accountDetectionService.detectFromHeaders(headers, detectionContext);
                
                // Match to existing account
                if (fromHeaders != null) {
                    try {
                        matchedAccountId = accountDetectionService.matchToExistingAccount(userId, fromHeaders);
                            if (matchedAccountId != null) {
                                detectedAccount = fromHeaders;
                                logger.info("Matched Excel import to existing account: {} (accountId: {}, accountNumber: {})", 
                                    detectedAccount.getAccountName(), matchedAccountId,
                                    detectedAccount.getAccountNumber() != null ? detectedAccount.getAccountNumber() : "N/A");
                            } else {
                                // Enhanced logging with account number and other details
                                String accountName = fromHeaders.getAccountName() != null ? fromHeaders.getAccountName() : "Unknown";
                                String accountNumber = fromHeaders.getAccountNumber() != null ? fromHeaders.getAccountNumber() : "N/A";
                                String institution = fromHeaders.getInstitutionName() != null ? fromHeaders.getInstitutionName() : "N/A";
                                logger.info("Detected account from Excel but no match found - Name: {}, AccountNumber: {}, Institution: {}, Type: {}", 
                                    accountName, accountNumber, institution,
                                    fromHeaders.getAccountType() != null ? fromHeaders.getAccountType() : "N/A");
                                detectedAccount = fromHeaders;
                            }
                    } catch (Exception e) {
                        logger.warn("Error during account matching for Excel import: {}", e.getMessage());
                        // Continue without account matching - user can select account in UI
                        detectedAccount = fromHeaders;
                    }
                }
            }
            
            // Parse data rows
            int rowNumber = 1; // Start at 1 (header is row 0)
            int rowsReadForAccountDetection = 0;
            final int MAX_ROWS_FOR_ACCOUNT_DETECTION = 20; // Check first 20 rows for account info (increased for better detection)
            
            // Track transaction patterns for account type inference (use arrays to allow modification)
            final int[] debitCount = {0};
            final int[] creditCount = {0};
            final int[] checkCount = {0};
            final int[] achCount = {0};
            final int[] atmCount = {0};
            final int[] transferCount = {0};
            
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue; // Skip empty rows
                }
                
                // Extract values from row (needed for balance tracking and account detection)
                List<String> values = new ArrayList<>();
                for (int colIndex = 0; colIndex < headers.size(); colIndex++) {
                    Cell cell = row.getCell(colIndex);
                    String value = cell != null ? getCellValueAsString(cell) : "";
                    values.add(value);
                }
                
                // Convert Excel row to CSVImportService.ParsedRow format
                CSVImportService.ParsedRow parsedRow = convertRowToParsedRow(row, headers);
                
                // Skip empty rows
                if (isRowEmpty(parsedRow)) {
                    continue;
                }
                
                // CRITICAL FIX: Extract account information from data rows
                if (userId != null && detectedAccount != null && rowsReadForAccountDetection < MAX_ROWS_FOR_ACCOUNT_DETECTION) {
                    rowsReadForAccountDetection++;
                    logger.debug("Analyzing Excel data row {} for account information", rowNumber);
                    
                    // Check each field value for account information
                    for (int colIndex = 0; colIndex < values.size() && colIndex < headers.size(); colIndex++) {
                        String value = values.get(colIndex);
                        String header = headers.get(colIndex);
                        
                        if (value != null && !value.trim().isEmpty()) {
                            String headerLower = header != null ? header.toLowerCase().trim() : "";
                            
                            // Extract account number from data
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
                                        logger.info("✓ Extracted account number from Excel row {} column '{}': {}", 
                                            rowNumber, header, accountNum);
                                    }
                                } else {
                                    // Try pattern matching in value
                                    String accountNum = extractAccountNumberFromValue(value);
                                    if (accountNum != null) {
                                        detectedAccount.setAccountNumber(accountNum);
                                        logger.info("✓ Extracted account number from Excel row {} (pattern match): {}", 
                                            rowNumber, accountNum);
                                    }
                                }
                            }
                            
                            // Extract institution/product name from data
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
                                    logger.info("✓ Extracted institution name from Excel row {} column '{}': {}", 
                                        rowNumber, header, value.trim());
                                } else {
                                    // Try pattern matching for product names
                                    String institution = extractInstitutionFromValue(value);
                                    if (institution != null) {
                                        detectedAccount.setInstitutionName(institution);
                                        logger.info("✓ Extracted institution/product name from Excel row {}: {}", 
                                            rowNumber, institution);
                                    }
                                }
                            }
                            
                            // Extract account type from data
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
                                        logger.info("✓ Extracted account type from Excel row {} column '{}': {}", 
                                            rowNumber, header, accountType);
                                    }
                                }
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
                if (rowsReadForAccountDetection >= 5 && detectedAccount != null && detectedAccount.getAccountType() == null) {
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
                        logger.info("✓ Inferred account type from Excel transaction patterns: {} / {} (debit: {}, credit: {}, check: {}, ACH: {}, ATM: {}, transfer: {})", 
                            inferredType, detectedAccount.getAccountSubtype(), 
                            debitCount[0], creditCount[0], checkCount[0], achCount[0], atmCount[0], transferCount[0]);
                    }
                }
                
                // Check transaction limit
                if (result.getSuccessCount() >= MAX_TRANSACTIONS_PER_FILE) {
                    result.addError(String.format("Transaction limit exceeded. Maximum %d transactions per file. Stopping at row %d.", 
                        MAX_TRANSACTIONS_PER_FILE, rowNumber + 1));
                    logger.warn("Transaction limit reached: {} transactions. Stopping Excel parsing at row {}", 
                        MAX_TRANSACTIONS_PER_FILE, rowNumber + 1);
                    break;
                }
                
                // Parse transaction using CSVImportService logic
                try {
                    CSVImportService.ParsedTransaction transaction = csvImportService.parseTransaction(
                        parsedRow, 
                        rowNumber, 
                        String.join(",", headers), // Pass header line for currency detection
                        filename // Pass filename for currency detection (CNY vs JPY context)
                    );
                    if (transaction != null) {
                        // Set account ID if detected
                        if (matchedAccountId != null) {
                            transaction.setAccountId(matchedAccountId);
                        }
                        result.addTransaction(transaction);
                        
                        // Track balance from row with latest date (for checking/savings/money market accounts)
                        if (balanceColumnIndex != null && balanceColumnIndex < values.size() && transaction.getDate() != null) {
                            String balanceValue = values.get(balanceColumnIndex);
                            if (balanceValue != null && !balanceValue.trim().isEmpty()) {
                                // Update if this row has a later date than the current latest
                                if (latestDateForBalance == null || transaction.getDate().isAfter(latestDateForBalance)) {
                                    latestDateBalanceValue = balanceValue.trim();
                                    latestDateForBalance = transaction.getDate();
                                    logger.debug("Tracked balance from Excel transaction row {} (date: {}): {}", 
                                        rowNumber + 1, latestDateForBalance, latestDateBalanceValue);
                                }
                            }
                        }
                    } else {
                        result.addError(String.format("Row %d: Could not parse transaction (missing date or amount)", rowNumber + 1));
                    }
                } catch (Exception e) {
                    result.addError(String.format("Row %d: Error parsing transaction - %s", rowNumber + 1, e.getMessage()));
                }
                
                rowNumber++;
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
                        logger.info("✓ Extracted balance from Excel transaction with latest date ({}): {}", latestDateForBalance, balance);
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
            
            logger.info("Parsed Excel: {} successful, {} failed", result.getSuccessCount(), result.getFailureCount());
            
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error parsing Excel file: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to parse Excel file: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * Create workbook from input stream (supports both .xlsx and .xls)
     */
    private Workbook createWorkbook(InputStream inputStream) throws Exception {
        // Read all bytes first to support both formats
        byte[] bytes;
        try {
            bytes = inputStream.readAllBytes();
        } catch (java.io.IOException e) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to read Excel file: " + e.getMessage());
        }
        
        // Try XLSX first (most common)
        try {
            return new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
        } catch (Exception e) {
            // If XLSX fails, try HSSF (old .xls format)
            try {
                return new HSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
            } catch (Exception e2) {
                throw new AppException(ErrorCode.INVALID_INPUT, 
                    "Failed to parse Excel file. Supported formats: .xlsx, .xls. Error: " + e2.getMessage());
            }
        }
    }

    /**
     * Extract headers from first row
     */
    private List<String> extractHeaders(Row headerRow) {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            Cell cell = headerRow.getCell(i);
            String header = getCellValueAsString(cell);
            if (header != null && !header.trim().isEmpty()) {
                headers.add(header.trim());
            } else {
                headers.add("column" + (i + 1)); // Default header for empty cells
            }
        }
        return headers;
    }

    /**
     * Convert Excel row to ParsedRow format
     */
    private CSVImportService.ParsedRow convertRowToParsedRow(Row row, List<String> headers) {
        CSVImportService.ParsedRow parsedRow = new CSVImportService.ParsedRow();
        
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.getCell(i);
            String value = getCellValueAsString(cell);
            parsedRow.put(headers.get(i), value != null ? value : "");
        }
        
        return parsedRow;
    }

    /**
     * Get cell value as string, handling different cell types
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    // Handle date cells
                    Date date = cell.getDateCellValue();
                    if (date != null) {
                        // Convert to LocalDate and format as ISO string
                        return java.time.Instant.ofEpochMilli(date.getTime())
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                            .toString();
                    }
                } else {
                    // Handle numeric cells (avoid scientific notation)
                    double numericValue = cell.getNumericCellValue();
                    // Check if it's a whole number
                    if (numericValue == Math.floor(numericValue)) {
                        return String.valueOf((long) numericValue);
                    } else {
                        // Format with appropriate decimal places
                        return String.valueOf(numericValue);
                    }
                }
                return "";
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                // Evaluate formula and return result
                try {
                    FormulaEvaluator evaluator = cell.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator();
                    CellValue cellValue = evaluator.evaluate(cell);
                    if (cellValue != null) {
                        switch (cellValue.getCellType()) {
                            case STRING:
                                return cellValue.getStringValue();
                            case NUMERIC:
                                return String.valueOf(cellValue.getNumberValue());
                            case BOOLEAN:
                                return String.valueOf(cellValue.getBooleanValue());
                            default:
                                return "";
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to evaluate formula in cell: {}", e.getMessage());
                }
                return "";
            case BLANK:
                return "";
            default:
                return "";
        }
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
            logger.debug("Found debit indicator in Excel transaction: {}", transactionText);
        }
        if (textLower.contains("credit") || textLower.contains(" cr ") || 
            textLower.startsWith("cr ") || textLower.contains("credit memo") ||
            textLower.contains("credit adjustment")) {
            // Exclude "credit card" from credit count (that's a different thing)
            if (!textLower.contains("credit card") && !textLower.contains("creditcard")) {
                creditCount[0]++;
                logger.debug("Found credit indicator in Excel transaction: {}", transactionText);
            }
        }
        if (textLower.contains("check") || textLower.contains("chk") || textLower.contains("cheque") ||
            textLower.contains("check #") || textLower.contains("check number") ||
            textLower.contains("check payment") || textLower.contains("check deposit")) {
            checkCount[0]++;
            logger.debug("Found check indicator in Excel transaction: {}", transactionText);
        }
        if (textLower.contains("ach") || textLower.contains("automated clearing") || 
            textLower.contains("direct deposit") || textLower.contains("directdeposit") ||
            textLower.contains("ach credit") || textLower.contains("ach debit") ||
            textLower.contains("ach transfer")) {
            achCount[0]++;
            logger.debug("Found ACH indicator in Excel transaction: {}", transactionText);
        }
        if (textLower.contains("atm") || textLower.contains("at m") || 
            textLower.contains("cash withdrawal") || textLower.contains("cash withdrawal") ||
            textLower.contains("atm withdrawal") || textLower.contains("atm deposit") ||
            textLower.contains("atm fee")) {
            atmCount[0]++;
            logger.debug("Found ATM indicator in Excel transaction: {}", transactionText);
        }
        if (textLower.contains("transfer") || textLower.contains("xfer") || 
            textLower.contains("wire transfer") || textLower.contains("online transfer") ||
            textLower.contains("bank transfer") || textLower.contains("account transfer") ||
            textLower.contains("internal transfer")) {
            transferCount[0]++;
            logger.debug("Found transfer indicator in Excel transaction: {}", transactionText);
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
     * Check if row is empty (all cells are empty)
     */
    private boolean isRowEmpty(CSVImportService.ParsedRow row) {
        // Check common fields - if all are empty, row is empty
        String date = row.findField("date", "transaction date", "posting date");
        String amount = row.findField("amount", "transaction amount", "debit", "credit");
        String description = row.findField("description", "details", "memo");
        
        return (date == null || date.trim().isEmpty()) &&
               (amount == null || amount.trim().isEmpty()) &&
               (description == null || description.trim().isEmpty());
    }
}

