package com.budgetbuddy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Service for parsing categories from import files (CSV, Excel, PDF)
 * 
 * This service extracts category parsing logic from CSVImportService
 * to enable unified category determination across all import sources.
 * 
 * Currently delegates to CSVImportService.parseCategory() for implementation.
 * Future: Extract all parseCategory logic and helper methods into this service.
 */
@Service
public class ImportCategoryParser {

    private static final Logger logger = LoggerFactory.getLogger(ImportCategoryParser.class);

    private final CSVImportService csvImportService;

    public ImportCategoryParser(@Lazy CSVImportService csvImportService) {
        this.csvImportService = csvImportService;
    }

    /**
     * Parse category from import file data
     * 
     * @param categoryString Category string from import file
     * @param description Transaction description
     * @param merchantName Merchant name
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @param transactionTypeIndicator Transaction type indicator (DEBIT/CREDIT)
     * @return Detected category name
     */
    public String parseCategory(String categoryString, String description, String merchantName,
                               BigDecimal amount, String paymentChannel, String transactionTypeIndicator) {
        // Legacy method - delegate to new signature with null context
        return parseCategory(categoryString, description, merchantName, amount, paymentChannel, 
                            transactionTypeIndicator, null, null, null);
    }
    
    /**
     * Parse category from import file data with transaction type and account type context
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
        if (csvImportService == null) {
            logger.warn("CSVImportService not available, returning null category");
            return null;
        }
        
        try {
            return csvImportService.parseCategory(
                categoryString, description, merchantName, amount, paymentChannel, 
                transactionTypeIndicator, transactionType, accountType, accountSubtype);
        } catch (Exception e) {
            logger.error("Error parsing category: {}", e.getMessage(), e);
            return "other"; // Fallback to "other" on error
        }
    }
}

