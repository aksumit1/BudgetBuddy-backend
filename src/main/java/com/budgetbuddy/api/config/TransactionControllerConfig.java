package com.budgetbuddy.api.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.notification.DataChangeNotificationService;
import com.budgetbuddy.security.FileContentScanner;
import com.budgetbuddy.security.FileIntegrityService;
import com.budgetbuddy.security.FileQuarantineService;
import com.budgetbuddy.security.FileSecurityValidator;
import com.budgetbuddy.security.FileUploadRateLimiter;
import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.CSVImportService;
import com.budgetbuddy.service.ChunkedUploadService;
import com.budgetbuddy.service.DuplicateDetectionService;
import com.budgetbuddy.service.ExcelImportService;
import com.budgetbuddy.service.ImportHistoryService;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.TransactionTypeCategoryService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Configuration object for TransactionController dependencies Groups related services to reduce
 * constructor parameter count
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
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

    // Notification service
    private final DataChangeNotificationService dataChangeNotificationService;

    // Subscription service for automatic detection
    private final SubscriptionService subscriptionService;

    // Import history service
    private final ImportHistoryService importHistoryService;

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
            final AccountDetectionService accountDetectionService,
            final DataChangeNotificationService dataChangeNotificationService,
            final SubscriptionService subscriptionService,
            final ImportHistoryService importHistoryService) {
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
        this.dataChangeNotificationService = dataChangeNotificationService;
        this.subscriptionService = subscriptionService;
        this.importHistoryService = importHistoryService;
    }

    // Getters
    public TransactionService getTransactionService() {
        return transactionService;
    }

    public UserService getUserService() {
        return userService;
    }

    public com.budgetbuddy.repository.dynamodb.AccountRepository getAccountRepository() {
        return accountRepository;
    }

    public FileUploadRateLimiter getFileUploadRateLimiter() {
        return fileUploadRateLimiter;
    }

    public FileSecurityValidator getFileSecurityValidator() {
        return fileSecurityValidator;
    }

    public FileContentScanner getFileContentScanner() {
        return fileContentScanner;
    }

    public FileQuarantineService getFileQuarantineService() {
        return fileQuarantineService;
    }

    public FileIntegrityService getFileIntegrityService() {
        return fileIntegrityService;
    }

    public CSVImportService getCsvImportService() {
        return csvImportService;
    }

    public ExcelImportService getExcelImportService() {
        return excelImportService;
    }

    public PDFImportService getPdfImportService() {
        return pdfImportService;
    }

    public DuplicateDetectionService getDuplicateDetectionService() {
        return duplicateDetectionService;
    }

    public TransactionTypeCategoryService getTransactionTypeCategoryService() {
        return transactionTypeCategoryService;
    }

    public ChunkedUploadService getChunkedUploadService() {
        return chunkedUploadService;
    }

    public AccountDetectionService getAccountDetectionService() {
        return accountDetectionService;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public DataChangeNotificationService getDataChangeNotificationService() {
        return dataChangeNotificationService;
    }

    public SubscriptionService getSubscriptionService() {
        return subscriptionService;
    }

    public ImportHistoryService getImportHistoryService() {
        return importHistoryService;
    }
}
