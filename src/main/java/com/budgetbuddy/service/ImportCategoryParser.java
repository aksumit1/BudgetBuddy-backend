package com.budgetbuddy.service;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Service for parsing categories from import files (CSV, Excel, PDF)
 *
 * <p>This service extracts category parsing logic from CSVImportService to enable unified category
 * determination across all import sources.
 *
 * <p>Currently delegates to CSVImportService.parseCategory() for implementation. Future: Extract
 * all parseCategory logic and helper methods into this service.
 */
@Service
public class ImportCategoryParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportCategoryParser.class);

    private final CSVImportService csvImportService;

    public ImportCategoryParser(@Lazy final CSVImportService csvImportService) {
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
    public String parseCategory(
            final String categoryString,
            final String description,
            final String merchantName,
            final BigDecimal amount,
            final String paymentChannel,
            final String transactionTypeIndicator) {
        // Legacy method - delegate to new signature with null context
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
        if (csvImportService == null) {
            LOGGER.warn("CSVImportService not available, returning null category");
            return null;
        }

        try {
            return csvImportService.parseCategory(
                    categoryString,
                    description,
                    merchantName,
                    amount,
                    paymentChannel,
                    transactionTypeIndicator,
                    transactionType,
                    accountType,
                    accountSubtype);
        } catch (Exception e) {
            LOGGER.error("Error parsing category: {}", e.getMessage(), e);
            return "other"; // Fallback to "other" on error
        }
    }
}
