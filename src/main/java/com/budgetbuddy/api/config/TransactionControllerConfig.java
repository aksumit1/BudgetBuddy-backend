package com.budgetbuddy.api.config;

import com.budgetbuddy.security.*;
import com.budgetbuddy.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Configuration object for TransactionController dependencies
 * Groups related services to reduce constructor parameter count
 */
@Component
public class TransactionControllerConfig {

    private final TransactionService transactionService;
    private final UserService userService;
    private final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;
    
    // Security services for file uploads
    private final FileUploadRateLimiter fileUploadRateLimiter;
    private final FileSecurityValidator fileSecurityValidator;
    private final FileContentScanner fileContentScanner;
    private final FileQuarantineService fileQuarantineService;
    private final FileIntegrityService fileIntegrityService;
    
    // Import services
    private final CSVImportService csvImportService;
    private final ExcelImportService excelImportService;
    private final PDFImportService pdfImportService;
    private final DuplicateDetectionService duplicateDetectionService;
    private final TransactionTypeCategoryService transactionTypeCategoryService;
    private final ChunkedUploadService chunkedUploadService;
    private final AccountDetectionService accountDetectionService;
    
    // Utilities
    private final ObjectMapper objectMapper;

    public TransactionControllerConfig(
            final TransactionService transactionService,
            final UserService userService,
            final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository,
            final FileUploadRateLimiter fileUploadRateLimiter,
            final FileSecurityValidator fileSecurityValidator,
            final FileContentScanner fileContentScanner,
            final FileQuarantineService fileQuarantineService,
            final FileIntegrityService fileIntegrityService,
            final CSVImportService csvImportService,
            final ExcelImportService excelImportService,
            final PDFImportService pdfImportService,
            final DuplicateDetectionService duplicateDetectionService,
            final TransactionTypeCategoryService transactionTypeCategoryService,
            final ChunkedUploadService chunkedUploadService,
            final ObjectMapper objectMapper,
            final AccountDetectionService accountDetectionService) {
        this.transactionService = transactionService;
        this.userService = userService;
        this.accountRepository = accountRepository;
        this.fileUploadRateLimiter = fileUploadRateLimiter;
        this.fileSecurityValidator = fileSecurityValidator;
        this.fileContentScanner = fileContentScanner;
        this.fileQuarantineService = fileQuarantineService;
        this.fileIntegrityService = fileIntegrityService;
        this.csvImportService = csvImportService;
        this.excelImportService = excelImportService;
        this.pdfImportService = pdfImportService;
        this.duplicateDetectionService = duplicateDetectionService;
        this.transactionTypeCategoryService = transactionTypeCategoryService;
        this.chunkedUploadService = chunkedUploadService;
        this.accountDetectionService = accountDetectionService;
        this.objectMapper = objectMapper;
    }

    // Getters
    public TransactionService getTransactionService() { return transactionService; }
    public UserService getUserService() { return userService; }
    public com.budgetbuddy.repository.dynamodb.AccountRepository getAccountRepository() { return accountRepository; }
    public FileUploadRateLimiter getFileUploadRateLimiter() { return fileUploadRateLimiter; }
    public FileSecurityValidator getFileSecurityValidator() { return fileSecurityValidator; }
    public FileContentScanner getFileContentScanner() { return fileContentScanner; }
    public FileQuarantineService getFileQuarantineService() { return fileQuarantineService; }
    public FileIntegrityService getFileIntegrityService() { return fileIntegrityService; }
    public CSVImportService getCsvImportService() { return csvImportService; }
    public ExcelImportService getExcelImportService() { return excelImportService; }
    public PDFImportService getPdfImportService() { return pdfImportService; }
    public DuplicateDetectionService getDuplicateDetectionService() { return duplicateDetectionService; }
    public TransactionTypeCategoryService getTransactionTypeCategoryService() { return transactionTypeCategoryService; }
    public ChunkedUploadService getChunkedUploadService() { return chunkedUploadService; }
    public AccountDetectionService getAccountDetectionService() { return accountDetectionService; }
    public ObjectMapper getObjectMapper() { return objectMapper; }
}

