package com.budgetbuddy.service.imports;

import java.io.InputStream;
import java.util.List;

/**
 * P3: Plugin interface for import sources
 * Allows adding new import sources without modifying core code
 */
public interface ImportSourcePlugin {
    
    /**
     * Returns the name of this import source (e.g., "CSV", "EXCEL", "PDF", "OFX")
     */
    String getSourceName();
    
    /**
     * Returns the file extensions supported by this plugin (e.g., ["csv"], ["xlsx", "xls"])
     */
    List<String> getSupportedExtensions();
    
    /**
     * Parses the file and returns preview transactions
     * @param inputStream The file input stream
     * @param filename The original filename (for account detection)
     * @param userId The user ID
     * @param password Optional password for password-protected files
     * @return ImportResult with parsed transactions
     */
    ImportResult parseForPreview(InputStream inputStream, String filename, String userId, String password);
    
    /**
     * Result of import parsing
     */
    interface ImportResult {
        List<ParsedTransaction> getTransactions();
        DetectedAccountInfo getDetectedAccount();
        String getMatchedAccountId();
        List<String> getErrors();
        int getSuccessCount();
        
        interface ParsedTransaction {
            String getDate();
            java.math.BigDecimal getAmount();
            String getDescription();
            String getMerchantName();
            String getCategoryPrimary();
            String getCategoryDetailed();
            String getImporterCategoryPrimary();
            String getImporterCategoryDetailed();
            String getCurrencyCode();
            String getPaymentChannel();
            String getTransactionType();
            String getTransactionId();
            void setAccountId(String accountId);
        }
        
        interface DetectedAccountInfo {
            String getAccountNumber();
            String getInstitutionName();
            String getAccountName();
            String getAccountType();
            String getAccountSubtype();
        }
    }
}

