package com.budgetbuddy.service;

import com.budgetbuddy.service.category.CategoryCascade;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class ImportCategoryParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportCategoryParser.class);

    private final CSVImportService csvImportService;
    private final CategoryCascade cascade;

    @Autowired
    public ImportCategoryParser(
            @Lazy final CSVImportService csvImportService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    final CategoryCascade cascade) {
        this.csvImportService = csvImportService;
        this.cascade = cascade;
    }

    /**
     * Legacy 1-arg constructor kept for tests that wired this service
     * before {@link CategoryCascade} existed. The cascade is the
     * preferred entry point in production; tests that don't exercise
     * the cascade can still construct an instance via this overload
     * without having to mock four extra beans.
     */
    public ImportCategoryParser(final CSVImportService csvImportService) {
        this(csvImportService, null);
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

        // ---- Layered cascade FIRST ----
        // Run the unified L0→L8 cascade (user override → MCC → merchant DB →
        // Plaid → curated YAML → location lookup → ML/fuzzy). If any layer
        // returns a category, we use it. The legacy CSVImportService keyword
        // chain becomes L9 — the genuine fallback it was always meant to be.
        //
        // null-guarded: in test setups that mock the cascade out (or in
        // environments where DynamoDB isn't reachable yet), we degrade
        // silently to the legacy chain. No behaviour regression.
        if (cascade != null) {
            try {
                final TransactionTypeCategoryService.CategoryResult cascadeResult =
                        cascade.classify(
                                CategoryCascade.Context.builder()
                                        .merchantName(merchantName)
                                        .description(description)
                                        // Thread the issuer's MCC trailer (e.g.,
                                        // Amex "RESTAURANT", Discover "Restaurants")
                                        // through so L2.5 can map it directly. This
                                        // is one of the highest-confidence signals
                                        // and was previously discarded.
                                        .importerCategoryPrimary(categoryString)
                                        .importerCategoryDetailed(categoryString)
                                        .build());
                if (cascadeResult != null
                        && cascadeResult.getCategoryPrimary() != null
                        && !cascadeResult.getCategoryPrimary().isBlank()) {
                    return cascadeResult.getCategoryPrimary();
                }
            } catch (Exception e) {
                LOGGER.debug(
                        "CategoryCascade failed (will fall back to CSVImportService): {}",
                        e.getMessage());
            }
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
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error parsing category: {}", e.getMessage(), e);
            }
            return "other"; // Fallback to "other" on error
        }
    }
}
