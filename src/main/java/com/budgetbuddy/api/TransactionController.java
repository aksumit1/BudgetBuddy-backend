package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.security.*;
import com.budgetbuddy.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Transaction REST Controller
 * Optimized with pagination to minimize data transfer
 *
 * Thread-safe with proper error handling
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionService transactionService;
    private final UserService userService;
    private final AccountRepository accountRepository;
    
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
    private final ChunkedUploadService chunkedUploadService; // Chunked upload support
    private final ObjectMapper objectMapper; // For parsing JSON request bodies
    private final AccountDetectionService accountDetectionService; // For account balance date comparison
    
    // Allowed file extensions
    private static final Set<String> CSV_EXTENSIONS = Set.of("csv");
    private static final Set<String> EXCEL_EXTENSIONS = Set.of("xlsx", "xls");
    private static final Set<String> PDF_EXTENSIONS = Set.of("pdf");

    /**
     * Constructor with grouped dependencies via TransactionControllerConfig
     * Reduces constructor parameter count from 17 to 1, improving maintainability
     */
    public TransactionController(final com.budgetbuddy.api.config.TransactionControllerConfig config) {
        this.transactionService = config.getTransactionService();
        this.userService = config.getUserService();
        this.accountRepository = config.getAccountRepository();
        this.fileUploadRateLimiter = config.getFileUploadRateLimiter();
        this.fileSecurityValidator = config.getFileSecurityValidator();
        this.fileContentScanner = config.getFileContentScanner();
        this.fileQuarantineService = config.getFileQuarantineService();
        this.fileIntegrityService = config.getFileIntegrityService();
        this.csvImportService = config.getCsvImportService();
        this.excelImportService = config.getExcelImportService();
        this.pdfImportService = config.getPdfImportService();
        this.duplicateDetectionService = config.getDuplicateDetectionService();
        this.transactionTypeCategoryService = config.getTransactionTypeCategoryService();
        this.chunkedUploadService = config.getChunkedUploadService();
        this.accountDetectionService = config.getAccountDetectionService();
        this.objectMapper = config.getObjectMapper();
    }

    @GetMapping
    public ResponseEntity<List<TransactionTable>> getTransactions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Validate pagination parameters
        if (page < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be non-negative");
        }
        if (size < 1 || size > 100) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 100");
        }

        int skip = page * size;
        List<TransactionTable> transactions = transactionService.getTransactions(user, skip, size);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/range")
    public ResponseEntity<List<TransactionTable>> getTransactionsInRange(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(ErrorCode.INVALID_DATE_RANGE, "Start date must be before end date");
        }

        List<TransactionTable> transactions = transactionService.getTransactionsInRange(user, startDate, endDate);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/total")
    public ResponseEntity<TotalSpendingResponse> getTotalSpending(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(ErrorCode.INVALID_DATE_RANGE, "Start date must be before end date");
        }

        BigDecimal total = transactionService.getTotalSpending(user, startDate, endDate);
        return ResponseEntity.ok(new TotalSpendingResponse(total));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionTable> getTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id,
            @RequestParam(required = false) String plaidTransactionId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Pass plaidTransactionId for fallback lookup if UUID doesn't match
        TransactionTable transaction = transactionService.getTransaction(user, id, plaidTransactionId);
        return ResponseEntity.ok(transaction);
    }

    @PostMapping
    public ResponseEntity<TransactionTable> createTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Note: Bean Validation handles validation in integration tests/real requests
        // For unit tests, add defensive null check (Bean Validation doesn't execute in unit tests)
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction request is required");
        }
        
        // Manual validation for unit tests (Bean Validation doesn't execute in unit tests)
        if (request.getAmount() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Amount is required");
        }
        if (request.getTransactionDate() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction date is required");
        }
        if (request.getCategoryPrimary() == null || request.getCategoryPrimary().trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category primary is required");
        }
        
        // CRITICAL FIX: Account ID is now optional - if not provided, backend will use pseudo account

        TransactionTable transaction = transactionService.createTransaction(
                user,
                request.getAccountId(),
                request.getAmount(),
                request.getTransactionDate(),
                request.getDescription(),
                request.getCategoryPrimary(),
                request.getCategoryDetailed(),
                null, // importerCategoryPrimary
                null, // importerCategoryDetailed
                request.getTransactionId(), // Pass optional transactionId from app
                request.getNotes(), // Pass optional notes
                request.getPlaidAccountId(), // Pass optional Plaid account ID for fallback lookup
                request.getPlaidTransactionId(), // Pass optional Plaid transaction ID for fallback lookup and ID consistency
                request.getTransactionType(), // Pass optional user-selected transaction type
                request.getCurrencyCode(), // Pass optional currency code
                request.getImportSource(), // Pass optional import source
                request.getImportBatchId(), // Pass optional import batch ID
                request.getImportFileName(), // Pass optional import file name
                request.getReviewStatus(), // Pass optional review status
                request.getMerchantName(), // Pass optional merchant name (where purchase was made)
                request.getPaymentChannel(), // Pass optional payment channel
                request.getUserName(), // Pass optional user name (card/account user - family member)
                request.getGoalId() // Pass optional goal ID this transaction contributes to
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionTable> updateTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id,
            @Valid @RequestBody UpdateTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        // Validation is handled by Bean Validation annotations on UpdateTransactionRequest

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Use reviewStatus directly from request (no conversion needed)
        TransactionTable transaction = transactionService.updateTransaction(
                user,
                id,
                request.getPlaidTransactionId(), // Pass Plaid ID for fallback lookup
                request.getAmount(), // Pass amount (for type changes)
                request.getNotes(),
                request.getCategoryPrimary(),
                request.getCategoryDetailed(),
                request.getReviewStatus(), // Pass review status directly from request
                request.getIsHidden(), // Pass hidden state
                request.getTransactionType(), // Pass optional user-selected transaction type
                false, // Don't clear notes if null - preserve existing when doing partial updates
                request.getGoalId() // Pass optional goal ID this transaction contributes to
        );

        return ResponseEntity.ok(transaction);
    }

    /**
     * Batch import transactions from JSON request
     * Used for programmatic imports and testing
     */
    @PostMapping("/batch-import")
    public ResponseEntity<BatchImportResponse> batchImport(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BatchImportRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        // Validation is handled by Bean Validation annotations on BatchImportRequest
        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // CRITICAL: If createDetectedAccount is true and detectedAccount is provided, create the account first
        String accountIdToUse = null;
        if (Boolean.TRUE.equals(request.getCreateDetectedAccount()) && request.getDetectedAccount() != null) {
            // Convert DetectedAccountInfo to AccountDetectionService.DetectedAccount
            AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
            detectedAccount.setAccountName(request.getDetectedAccount().getAccountName());
            detectedAccount.setInstitutionName(request.getDetectedAccount().getInstitutionName());
            detectedAccount.setAccountType(request.getDetectedAccount().getAccountType());
            detectedAccount.setAccountSubtype(request.getDetectedAccount().getAccountSubtype());
            detectedAccount.setAccountNumber(request.getDetectedAccount().getAccountNumber());
            detectedAccount.setBalance(request.getDetectedAccount().getBalance()); // CRITICAL: Include balance from detected account
            // Note: balanceDate is not available in DetectedAccountInfo from iOS, will be set from import result if available
            
            // Create account if it doesn't exist
            accountIdToUse = autoCreateAccountIfDetected(user, detectedAccount);
            logger.info("📝 Auto-created account for batch import: {}", accountIdToUse);
        }
        
        // CRITICAL FIX: If detectedAccount has matchedAccountId, use it to override transaction accountIds
        // This handles the case where account already exists and was matched during preview
        if (accountIdToUse == null && request.getDetectedAccount() != null) {
            String matchedAccountId = request.getDetectedAccount().getMatchedAccountId();
            if (matchedAccountId != null && !matchedAccountId.trim().isEmpty()) {
                try {
                    // Verify the matched account exists and belongs to the user
                    Optional<AccountTable> matchedAccount = accountRepository.findById(matchedAccountId);
                    if (matchedAccount.isPresent()) {
                        AccountTable account = matchedAccount.get();
                        // CRITICAL: Verify account belongs to user and is not null
                        if (account.getUserId() != null && account.getUserId().equals(user.getUserId())) {
                            accountIdToUse = matchedAccountId;
                            logger.info("🔗 Using matched account ID from detectedAccount: {}", accountIdToUse);
                        } else {
                            logger.warn("⚠️ Matched account ID '{}' belongs to different user (account userId: '{}', request userId: '{}') - ignoring", 
                                    matchedAccountId, account.getUserId(), user.getUserId());
                        }
                    } else {
                        logger.warn("⚠️ Matched account ID '{}' not found in repository - ignoring", matchedAccountId);
                    }
                } catch (Exception e) {
                    logger.error("❌ Error verifying matched account ID '{}': {}", matchedAccountId, e.getMessage(), e);
                    // Don't fail the entire import - continue without matched account
                }
            }
        }

        // CRITICAL FIX: If account was auto-created or matched, update all transactions to use it
        // This ensures transactions are tagged to the correct account instead of wrong/pseudo account
        if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
            logger.info("🔗 Updating {} transactions to use account: {}", 
                    request.getTransactions().size(), accountIdToUse);
            int updatedCount = 0;
            for (CreateTransactionRequest txRequest : request.getTransactions()) {
                if (txRequest == null) {
                    logger.warn("⚠️ Null transaction request in batch - skipping");
                    continue;
                }
                // CRITICAL FIX: Always override accountId when accountIdToUse is set (from created or matched account)
                // This ensures transactions are always assigned to the correct account, even if they came with wrong accountId
                String oldAccountId = txRequest.getAccountId();
                txRequest.setAccountId(accountIdToUse);
                updatedCount++;
                if (oldAccountId != null && !oldAccountId.equals(accountIdToUse)) {
                    logger.debug("Updated transaction '{}' accountId from '{}' to '{}'", 
                            txRequest.getDescription() != null ? txRequest.getDescription() : "unknown",
                            oldAccountId, accountIdToUse);
                }
            }
            logger.info("✅ Updated {} transaction(s) to use account: {}", updatedCount, accountIdToUse);
        }

        // Use TransactionService's batch import method
        BatchImportResponse response = transactionService.createTransactionsBatch(
                user,
                request.getTransactions()
        );
        
        // CRITICAL FIX: Include createdAccountId in response if account was created or matched
        // Only include if accountIdToUse is valid and was successfully used
        if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
            try {
                // Validate UUID format to prevent invalid IDs from being sent to iOS
                java.util.UUID.fromString(accountIdToUse);
                response.setCreatedAccountId(accountIdToUse);
                logger.info("📤 Including createdAccountId '{}' in batch import response", accountIdToUse);
            } catch (IllegalArgumentException e) {
                logger.error("❌ Invalid UUID format for createdAccountId '{}': {}", accountIdToUse, e.getMessage());
                // Don't include invalid UUID in response - iOS will handle gracefully
                // Account was still created/used, but we won't return the ID if it's invalid
            }
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<TransactionTable> verifyTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        // Validation is handled by Bean Validation annotations on VerifyTransactionRequest

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Use plaidTransactionId from request body for fallback lookup if UUID doesn't match
        TransactionTable transaction = transactionService.getTransaction(
                user,
                request.getTransactionId(),
                request.getPlaidTransactionId()
        );
        return ResponseEntity.ok(transaction);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        transactionService.deleteTransaction(user, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * SECURITY: Apply comprehensive security processing to uploaded file
     * 
     * @param file Uploaded file
     * @param userId User ID for rate limiting
     * @param allowedExtensions Allowed file extensions
     * @return File content as byte array (for multiple reads)
     * @throws AppException if security check fails
     */
    private byte[] applySecurityProcessing(MultipartFile file, String userId, Set<String> allowedExtensions) {
        try {
            // 1. Rate limiting
            fileUploadRateLimiter.checkRateLimit(userId, file.getSize());

            // 2. File validation
            fileSecurityValidator.validateFileUpload(file, allowedExtensions);

            // 3. Read file content into byte array (for multiple reads)
            byte[] fileContent = file.getBytes();
            String fileName = file.getOriginalFilename();

            // 4. Content scanning
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                FileContentScanner.ScanResult scanResult = fileContentScanner.scanFile(inputStream, fileName);
                
                if (!scanResult.isSafe()) {
                    // Quarantine suspicious file
                    String reason = String.join("; ", scanResult.getFindings());
                    try (InputStream quarantineStream = new ByteArrayInputStream(fileContent)) {
                        String quarantineId = fileQuarantineService.quarantineFile(
                                quarantineStream, fileName, reason, userId);
                        logger.warn("File quarantined: {} (ID: {}) - Reason: {}", fileName, quarantineId, reason);
                    }
                    throw new AppException(ErrorCode.INVALID_INPUT, 
                            "File contains suspicious content and has been quarantined: " + reason);
                }
            }

            // 5. Calculate and store checksum
            try (InputStream checksumStream = new ByteArrayInputStream(fileContent)) {
                String checksum = fileIntegrityService.calculateChecksum(checksumStream);
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("fileName", fileName);
                metadata.put("fileSize", file.getSize());
                metadata.put("uploadTime", System.currentTimeMillis());
                metadata.put("userId", userId);
                fileIntegrityService.storeChecksum(fileName, checksum, metadata);
                logger.debug("File checksum calculated and stored: {} ({})", fileName, checksum);
            }

            // 6. Record upload (after all checks pass)
            fileUploadRateLimiter.recordUpload(userId, file.getSize());

            return fileContent;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Security processing failed for file: {}", file.getOriginalFilename(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "File security processing failed: " + e.getMessage());
        }
    }
    
    /**
     * CRITICAL: Helper method to safely get original filename from MultipartFile
     * Some Spring Boot configurations or file upload libraries may sanitize filenames,
     * but we need the original for account detection (e.g., "Chase3100_Activity_29251221.csv")
     * 
     * @param file MultipartFile from request
     * @return Original filename, sanitized and validated, or null if truly unavailable
     */
    private String getOriginalFilenameSafely(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.trim().isEmpty()) {
            logger.warn("⚠️ MultipartFile.getOriginalFilename() returned null or empty");
            return null; // Return null instead of "unknown" - let caller decide fallback
        }
        
        // Sanitize filename: remove path separators and dangerous characters
        filename = sanitizeFilename(filename.trim());
        
        // Validate filename length (RFC 2183 recommends max 255 bytes, we use 200 for safety)
        if (filename.length() > 200) {
            logger.warn("⚠️ Filename too long ({} chars), truncating: '{}'", filename.length(), filename);
            // Preserve extension while truncating
            int lastDot = filename.lastIndexOf('.');
            if (lastDot > 0 && lastDot < filename.length() - 1) {
                String ext = filename.substring(lastDot);
                String nameWithoutExt = filename.substring(0, lastDot);
                filename = nameWithoutExt.substring(0, Math.min(200 - ext.length(), nameWithoutExt.length())) + ext;
            } else {
                filename = filename.substring(0, Math.min(200, filename.length()));
            }
            logger.debug("📁 Truncated filename: '{}'", filename);
        }
        
        // Log if filename looks like a UUID (might indicate sanitization)
        if (filename.matches("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$")) {
            logger.warn("⚠️ Filename appears to be a UUID (possibly sanitized): '{}' - Original filename may have been lost", filename);
        }
        
        return filename;
    }
    
    /**
     * Sanitize filename by removing path separators and dangerous characters
     * 
     * @param filename Original filename
     * @return Sanitized filename safe for account detection and logging
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        
        // Remove path separators and dangerous patterns
        String sanitized = filename
            .replaceAll("[/\\\\]", "_")  // Replace path separators
            .replaceAll("\\.\\.", "_")   // Replace .. patterns
            .replaceAll("[\\x00-\\x1F\\x7F]", "_") // Replace control characters
            .trim();
        
        // Ensure non-empty after sanitization
        if (sanitized.isEmpty()) {
            sanitized = "import_" + System.currentTimeMillis() + ".csv";
            logger.warn("⚠️ Filename became empty after sanitization, using default: '{}'", sanitized);
        }
        
        return sanitized;
    }

    // MARK: - CSV Import Endpoints

    @PostMapping("/import-csv/preview")
    public ResponseEntity<CSVImportPreviewResponse> previewCSV(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String filename,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter,
            // fallback to MultipartFile.getOriginalFilename()
            // iOS app may send original filename as separate parameter if Spring sanitizes it
            String originalFilename;
            boolean fromParameter = false;
            
            if (filename != null && !filename.trim().isEmpty()) {
                // URL-decode the filename parameter (URLComponents automatically encodes it, Spring decodes it)
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            // If still null/empty, use a default (but log as error condition)
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".csv";
                logger.error("❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'", originalFilename);
            }
            
            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            boolean isUUIDFilename = originalFilename.matches("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                logger.warn("⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. " +
                           "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.", 
                           originalFilename);
            }
            
            logger.info("📁 CSV Preview - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})", 
                originalFilename, fromParameter, file.getOriginalFilename(), isUUIDFilename);
            
            // Log multipart request details for debugging
            logger.info("📤 CSV Preview Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', Has password: {}", 
                filename, file.getOriginalFilename(), file.getSize(), file.getContentType(), password != null && !password.isEmpty());
            
            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), CSV_EXTENSIONS);

            // Parse CSV - use original filename (not sanitized version) for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                CSVImportService.ImportResult importResult = csvImportService.parseCSV(
                        inputStream, originalFilename, user.getUserId(), password);

                // Build preview response with duplicate detection
                List<DuplicateDetectionService.ParsedTransaction> parsedForDuplicateCheck = new ArrayList<>();
                
                for (CSVImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                    // Convert to DuplicateDetectionService.ParsedTransaction
                    DuplicateDetectionService.ParsedTransaction dupTx = new DuplicateDetectionService.ParsedTransaction(
                            parsed.getDate(),
                            parsed.getAmount(),
                            parsed.getDescription(),
                            parsed.getMerchantName()
                    );
                    dupTx.setTransactionId(parsed.getTransactionId());
                    parsedForDuplicateCheck.add(dupTx);
                }

                // Detect duplicates
                Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates = 
                        duplicateDetectionService.detectDuplicates(user.getUserId(), parsedForDuplicateCheck);

                // P1: Pagination - Build transaction maps with duplicate info (with pagination)
                int totalTransactions = importResult.getTransactions().size();
                int startIndex = page * size;
                int endIndex = Math.min(startIndex + size, totalTransactions);
                int totalPages = (int) Math.ceil((double) totalTransactions / size);
                
                // Validate pagination parameters
                if (page < 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
                }
                if (size < 1 || size > 1000) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 1000");
                }
                
                List<Map<String, Object>> paginatedTransactions = new ArrayList<>();
                for (int i = startIndex; i < endIndex; i++) {
                    CSVImportService.ParsedTransaction parsed = importResult.getTransactions().get(i);
                    // Log category assignment for preview
                    logger.info("📋 CSV Preview Transaction[{}]: merchant='{}', description='{}', amount={}, category='{}'", 
                            i, parsed.getMerchantName(), parsed.getDescription(), parsed.getAmount(), parsed.getCategoryPrimary());
                    Map<String, Object> txMap = buildTransactionMap(parsed, duplicates.get(i));
                    paginatedTransactions.add(txMap);
                }

                CSVImportPreviewResponse response = new CSVImportPreviewResponse();
                response.setTotalParsed(importResult.getSuccessCount());
                response.setTransactions(paginatedTransactions);
                response.setPage(page);
                response.setSize(size);
                response.setTotalPages(totalPages);
                response.setTotalElements(totalTransactions);
                DetectedAccountInfo accountInfo = null;
                if (importResult.getDetectedAccount() != null) {
                    accountInfo = new DetectedAccountInfo();
                    
                    // CRITICAL: If account was matched, use the matched account's details instead of detected account
                    // This ensures iOS shows the existing account information, not the detected account
                    String matchedAccountId = importResult.getMatchedAccountId();
                    if (matchedAccountId != null && !matchedAccountId.trim().isEmpty()) {
                        // Fetch the matched account from database
                        Optional<AccountTable> matchedAccount = accountRepository.findById(matchedAccountId);
                        if (matchedAccount.isPresent() && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            AccountTable account = matchedAccount.get();
                            // Use matched account's details
                            accountInfo.setAccountName(account.getAccountName());
                            accountInfo.setInstitutionName(account.getInstitutionName());
                            accountInfo.setAccountType(account.getAccountType());
                            accountInfo.setAccountSubtype(account.getAccountSubtype());
                            accountInfo.setAccountNumber(account.getAccountNumber());
                            accountInfo.setCardNumber(null); // Card number not stored in AccountTable
                            accountInfo.setBalance(account.getBalance()); // Include balance from existing account
                            accountInfo.setMatchedAccountId(matchedAccountId);
                            
                            // CRITICAL: Include credit card metadata from existing account (if available)
                            // CSV/Excel imports don't extract this metadata, so use existing account's metadata
                            accountInfo.setPaymentDueDate(account.getPaymentDueDate());
                            accountInfo.setMinimumPaymentDue(account.getMinimumPaymentDue());
                            accountInfo.setRewardPoints(account.getRewardPoints());
                            
                            logger.info("✅ Matched detected account to existing account: {} (accountId: {})", 
                                    account.getAccountName(), matchedAccountId);
                        } else {
                            // Matched account not found or doesn't belong to user - use detected account
                            logger.warn("⚠️ Matched account ID '{}' not found or doesn't belong to user - using detected account info", matchedAccountId);
                            accountInfo.setAccountName(importResult.getDetectedAccount().getAccountName());
                            accountInfo.setInstitutionName(importResult.getDetectedAccount().getInstitutionName());
                            accountInfo.setAccountType(importResult.getDetectedAccount().getAccountType());
                            accountInfo.setAccountSubtype(importResult.getDetectedAccount().getAccountSubtype());
                            accountInfo.setAccountNumber(importResult.getDetectedAccount().getAccountNumber());
                            accountInfo.setCardNumber(importResult.getDetectedAccount().getCardNumber());
                            accountInfo.setBalance(importResult.getDetectedAccount().getBalance()); // Include detected balance
                            accountInfo.setMatchedAccountId(null); // Clear invalid match
                            
                            // CSV/Excel imports don't extract credit card metadata - set to null
                            accountInfo.setPaymentDueDate(null);
                            accountInfo.setMinimumPaymentDue(null);
                            accountInfo.setRewardPoints(null);
                        }
                    } else {
                        // No match found - use detected account info
                        accountInfo.setAccountName(importResult.getDetectedAccount().getAccountName());
                        accountInfo.setInstitutionName(importResult.getDetectedAccount().getInstitutionName());
                        accountInfo.setAccountType(importResult.getDetectedAccount().getAccountType());
                        accountInfo.setAccountSubtype(importResult.getDetectedAccount().getAccountSubtype());
                        accountInfo.setAccountNumber(importResult.getDetectedAccount().getAccountNumber());
                        accountInfo.setCardNumber(importResult.getDetectedAccount().getCardNumber());
                        accountInfo.setBalance(importResult.getDetectedAccount().getBalance()); // Include detected balance
                        accountInfo.setMatchedAccountId(null);
                        
                        // CSV/Excel imports don't extract credit card metadata - set to null
                        accountInfo.setPaymentDueDate(null);
                        accountInfo.setMinimumPaymentDue(null);
                        accountInfo.setRewardPoints(null);
                    }
                    
                    response.setDetectedAccount(accountInfo);
                }

                // Log response details
                logger.info("📥 CSV Preview Response - Total parsed: {}, Transactions: {}, Errors: {}, Detected account: {} (institution: {}, type: {}, number: {})", 
                    response.getTotalParsed(), 
                    response.getTransactions() != null ? response.getTransactions().size() : 0,
                    importResult.getErrors() != null ? importResult.getErrors().size() : 0,
                    accountInfo != null ? accountInfo.getAccountName() : "none",
                    accountInfo != null ? accountInfo.getInstitutionName() : "none",
                    accountInfo != null ? accountInfo.getAccountType() : "none",
                    accountInfo != null ? accountInfo.getAccountNumber() : "none");

                return ResponseEntity.ok(response);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("CSV preview failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to preview CSV: " + e.getMessage());
        }
    }

    @PostMapping("/import-csv")
    public ResponseEntity<BatchImportResponse> importCSV(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;
            
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".csv";
                logger.error("❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'", originalFilename);
            }
            
            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            boolean isUUIDFilename = originalFilename.matches("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                logger.warn("⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. " +
                           "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.", 
                           originalFilename);
            }
            
            logger.info("📁 CSV Import - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})", 
                originalFilename, fromParameter, file.getOriginalFilename(), isUUIDFilename);
            
            // Log multipart request details for debugging
            logger.info("📤 CSV Import Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', AccountId: '{}', Has password: {}", 
                filename, file.getOriginalFilename(), file.getSize(), file.getContentType(), accountId, password != null && !password.isEmpty());
            
            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), CSV_EXTENSIONS);

            // Parse and import CSV - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                CSVImportService.ImportResult importResult = csvImportService.parseCSV(
                        inputStream, originalFilename, user.getUserId(), password);

                // CRITICAL: Auto-create account if detected but not matched
                String accountIdToUse = accountId; // Use provided accountId if available
                logger.info("🔍 [Non-paginated Import] Account creation check - provided accountId: '{}', detectedAccount: {}, matchedAccountId: '{}'", 
                        accountId, 
                        importResult.getDetectedAccount() != null ? "present" : "null",
                        importResult.getMatchedAccountId());
                
                if (accountIdToUse == null || accountIdToUse.trim().isEmpty()) {
                    if (importResult.getMatchedAccountId() != null && !importResult.getMatchedAccountId().trim().isEmpty()) {
                        // Account was matched during preview - verify it exists and use it
                        Optional<AccountTable> matchedAccount = accountRepository.findById(importResult.getMatchedAccountId());
                        if (matchedAccount.isPresent() && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            accountIdToUse = importResult.getMatchedAccountId();
                            logger.info("✅ [Non-paginated Import] Using matched account ID from preview: '{}'", accountIdToUse);
                        } else {
                            logger.warn("⚠️ [Non-paginated Import] Matched account ID '{}' from preview not found or doesn't belong to user - will auto-create instead", 
                                    importResult.getMatchedAccountId());
                            // Fall through to auto-create logic
                        }
                    }
                    
                    // Auto-create if no account ID has been set and account is detected
                    // CRITICAL FIX: Only auto-create if detected account has meaningful information
                    // Don't create accounts when all fields are null/empty - use pseudo account instead
                    if ((accountIdToUse == null || accountIdToUse.trim().isEmpty()) && 
                        importResult.getDetectedAccount() != null) {
                        // Check if detected account has any meaningful information before attempting creation
                        AccountDetectionService.DetectedAccount detected = importResult.getDetectedAccount();
                        boolean hasAccountInfo = (detected.getInstitutionName() != null && !detected.getInstitutionName().trim().isEmpty()) ||
                                               (detected.getAccountName() != null && !detected.getAccountName().trim().isEmpty()) ||
                                               (detected.getAccountNumber() != null && !detected.getAccountNumber().trim().isEmpty()) ||
                                               (detected.getAccountType() != null && !detected.getAccountType().trim().isEmpty()) ||
                                               (detected.getMatchedAccountId() != null && !detected.getMatchedAccountId().trim().isEmpty());
                        
                        if (hasAccountInfo) {
                            logger.info("📝 [Non-paginated Import] Attempting to auto-create account for detected account: name='{}', institution='{}', type='{}'", 
                                    detected.getAccountName(),
                                    detected.getInstitutionName(),
                                    detected.getAccountType());
                            accountIdToUse = autoCreateAccountIfDetected(user, detected);
                            if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                                logger.info("✅ [Non-paginated Import] Auto-created account '{}' for detected account: {} (institution: {}, type: {})", 
                                        accountIdToUse, 
                                        detected.getAccountName(),
                                        detected.getInstitutionName(),
                                        detected.getAccountType());
                            } else {
                                logger.info("ℹ️ [Non-paginated Import] Auto-creation skipped - detected account has no meaningful information. Transactions will use pseudo account.");
                            }
                        } else {
                            logger.info("ℹ️ [Non-paginated Import] Detected account has no meaningful information (all fields null/empty). Skipping account creation. Transactions will use pseudo account.");
                        }
                    }
                }
                
                // Update all transactions with the account ID
                if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                    for (CSVImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }

                // Import transactions - use original filename
                return processBatchImport(user, importResult.getTransactions(), "CSV", originalFilename);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("CSV import failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to import CSV: " + e.getMessage());
        }
    }

    /**
     * Paginated CSV Import - Import transactions in chunks to avoid timeouts
     * This endpoint allows importing large files by processing transactions in pages
     * 
     * @param file CSV file to import
     * @param page Page number (0-indexed)
     * @param size Number of transactions per page (default: 100, max: 500)
     * @param accountId Optional account ID
     * @param password Optional password for encrypted files
     * @param filename Original filename for account detection
     * @return ChunkImportResponse with import results and pagination info
     */
    @PostMapping("/import-csv/chunk")
    public ResponseEntity<ChunkImportResponse> importCSVChunk(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String filename,
            @RequestParam(required = false) String previewCategoriesJson) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Validate pagination parameters
        if (page < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page must be >= 0");
        }
        if (size < 1 || size > 500) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Size must be between 1 and 500");
        }

        try {
            // Capture original filename
            String originalFilename;
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".csv";
            }

            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), CSV_EXTENSIONS);

            // CRITICAL: Parse preview categories from JSON if provided
            // Use preview categories if account matches preview account
            List<ImportCategoryPreservationRequest.PreviewCategory> previewCategories = null;
            String previewAccountId = null;
            if (previewCategoriesJson != null && !previewCategoriesJson.trim().isEmpty()) {
                try {
                    ImportCategoryPreservationRequest categoryPreservation = 
                        objectMapper.readValue(previewCategoriesJson, ImportCategoryPreservationRequest.class);
                    
                    if (categoryPreservation != null && categoryPreservation.getPreviewCategories() != null) {
                        // Check if account matches preview account
                        String previewAcctId = categoryPreservation.getPreviewAccountId();
                        String importAcctId = accountId;
                        
                        // If both are null/empty, they match (no account specified)
                        boolean accountsMatch = (previewAcctId == null || previewAcctId.trim().isEmpty()) &&
                                               (importAcctId == null || importAcctId.trim().isEmpty());
                        
                        // If both are provided, check if they match
                        if (!accountsMatch && previewAcctId != null && importAcctId != null) {
                            accountsMatch = previewAcctId.equals(importAcctId);
                        }
                        
                        if (accountsMatch) {
                            previewCategories = categoryPreservation.getPreviewCategories();
                            previewAccountId = previewAcctId;
                            logger.info("📋 Using preview categories for import (account matches: '{}', {} categories provided)", 
                                    previewAccountId, previewCategories.size());
                        } else {
                            logger.info("📋 Preview categories provided but account changed (preview: '{}', import: '{}') - will re-categorize", 
                                    previewAcctId, importAcctId);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse preview categories JSON: {}. Will re-categorize transactions.", e.getMessage());
                }
            }
            
            // Parse CSV file
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                CSVImportService.ImportResult importResult = csvImportService.parseCSV(
                        inputStream, originalFilename, user.getUserId(), password, previewCategories, previewAccountId);

                List<CSVImportService.ParsedTransaction> allTransactions = importResult.getTransactions();
                int totalTransactions = allTransactions.size();
                int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate page number
                if (page >= totalPages && totalPages > 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, 
                        String.format("Page %d is out of range. Total pages: %d", page, totalPages));
                }

                // Get transactions for this page
                int startIndex = page * size;
                int endIndex = Math.min(startIndex + size, totalTransactions);
                List<CSVImportService.ParsedTransaction> chunk = allTransactions.subList(startIndex, endIndex);

                logger.info("📦 Importing CSV chunk: page {} (transactions {} to {} of {})", 
                    page, startIndex + 1, endIndex, totalTransactions);

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // Only create on first page (page 0) to avoid creating multiple accounts
                // Reuse the same account across all pages for paginated imports
                String accountIdToUse = accountId;
                logger.info("🔍 [Paginated Import Page {}] Account creation check - provided accountId: '{}', detectedAccount: {}, matchedAccountId: '{}'", 
                        page,
                        accountId, 
                        importResult.getDetectedAccount() != null ? "present (name: '" + importResult.getDetectedAccount().getAccountName() + "', institution: '" + importResult.getDetectedAccount().getInstitutionName() + "')" : "null",
                        importResult.getMatchedAccountId());
                
                if (accountIdToUse == null || accountIdToUse.trim().isEmpty()) {
                    List<AccountTable> existingAccounts = accountRepository.findByUserId(user.getUserId());
                    logger.info("🔍 [Paginated Import Page {}] STEP 1: Checking existing accounts - Found {} accounts for user {}", 
                            page, existingAccounts != null ? existingAccounts.size() : 0, user.getUserId());
                    if (existingAccounts != null && !existingAccounts.isEmpty()) {
                        for (AccountTable acc : existingAccounts) {
                            logger.info("   📋 Existing account: ID='{}', name='{}', institution='{}', type='{}', createdAt='{}'", 
                                    acc.getAccountId(), acc.getAccountName(), acc.getInstitutionName(), 
                                    acc.getAccountType(), acc.getCreatedAt());
                        }
                    }
                    
                    // Step 1: Check if user has manually created an account matching the detected account
                    // (This includes both manually created accounts and previously auto-created accounts)
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null && existingAccounts != null && !existingAccounts.isEmpty()) {
                        AccountDetectionService.DetectedAccount detected = importResult.getDetectedAccount();
                        
                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null && !detected.getAccountNumber().trim().isEmpty()) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountNumber().equals(acc.getAccountNumber()))
                                .findFirst()
                                .orElse(null);
                        }
                        
                        // If no match by account number, try to match by account name and institution
                        if (matchingAccount == null && detected.getAccountName() != null && detected.getInstitutionName() != null) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountName().equals(acc.getAccountName()) &&
                                             detected.getInstitutionName().equals(acc.getInstitutionName()))
                                .findFirst()
                                .orElse(null);
                        }
                    }
                    
                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        logger.info("📝 Using existing account '{}' (name: '{}', institution: '{}') for import (page {})", 
                                accountIdToUse, matchingAccount.getAccountName(), matchingAccount.getInstitutionName(), page);
                    } else if (importResult.getMatchedAccountId() != null && !importResult.getMatchedAccountId().trim().isEmpty()) {
                        // Account was matched during preview - verify it exists and use it
                        Optional<AccountTable> matchedAccount = accountRepository.findById(importResult.getMatchedAccountId());
                        if (matchedAccount.isPresent() && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            accountIdToUse = importResult.getMatchedAccountId();
                            logger.info("📝 Using matched account ID from preview: '{}' (page {})", accountIdToUse, page);
                        } else {
                            logger.warn("⚠️ Matched account ID '{}' from preview not found or doesn't belong to user - will auto-create instead (page {})", 
                                    importResult.getMatchedAccountId(), page);
                            // Fall through to auto-create logic
                        }
                    }
                    
                    // CRITICAL: Auto-create account if:
                    // 1. No account ID has been set yet (accountIdToUse is still null/empty)
                    // 2. Account was detected AND has meaningful information
                    // 3. We're on the first page (page 0) OR we're on a subsequent page but no account was found
                    if (accountIdToUse == null || accountIdToUse.trim().isEmpty()) {
                        if (importResult.getDetectedAccount() != null) {
                            // CRITICAL FIX: Check if detected account has meaningful information before attempting creation
                            AccountDetectionService.DetectedAccount detectedAccount = importResult.getDetectedAccount();
                            boolean hasAccountInfo = (detectedAccount.getInstitutionName() != null && !detectedAccount.getInstitutionName().trim().isEmpty()) ||
                                                   (detectedAccount.getAccountName() != null && !detectedAccount.getAccountName().trim().isEmpty()) ||
                                                   (detectedAccount.getAccountNumber() != null && !detectedAccount.getAccountNumber().trim().isEmpty()) ||
                                                   (detectedAccount.getAccountType() != null && !detectedAccount.getAccountType().trim().isEmpty()) ||
                                                   (detectedAccount.getMatchedAccountId() != null && !detectedAccount.getMatchedAccountId().trim().isEmpty());
                            
                            if (hasAccountInfo) {
                                // Account was detected with meaningful information - try to create or reuse
                                if (page == 0) {
                                // First page: Always try to auto-create if account is detected with meaningful info
                                logger.info("📝 [Page 0] Attempting to auto-create account for detected account: name='{}', institution='{}', type='{}'", 
                                        detectedAccount.getAccountName(),
                                        detectedAccount.getInstitutionName(),
                                        detectedAccount.getAccountType());
                                logger.info("🔨 [Page 0] STEP 2: Calling autoCreateAccountIfDetected...");
                                accountIdToUse = autoCreateAccountIfDetected(user, detectedAccount);
                                if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                                    logger.info("✅ [Page 0] STEP 3: Account created successfully - ID='{}'", accountIdToUse);
                                    // Verify the account is retrievable
                                    Optional<AccountTable> createdAccount = accountRepository.findById(accountIdToUse);
                                    if (createdAccount.isPresent()) {
                                        AccountTable acc = createdAccount.get();
                                        logger.info("✅ [Page 0] STEP 4: Account verification - ID='{}', name='{}', institution='{}', type='{}', createdAt='{}'", 
                                                acc.getAccountId(), acc.getAccountName(), acc.getInstitutionName(), 
                                                acc.getAccountType(), acc.getCreatedAt());
                                    } else {
                                        logger.error("❌ [Page 0] STEP 4: Account verification FAILED - Account '{}' not found in repository!", accountIdToUse);
                                    }
                                } else {
                                    logger.info("ℹ️ [Page 0] STEP 3: Auto-creation skipped - detected account has no meaningful information. Transactions will use pseudo account.");
                                }
                            } else {
                                logger.info("ℹ️ [Page 0] Detected account has no meaningful information (all fields null/empty). Skipping account creation. Transactions will use pseudo account.");
                            }
                        } else if (page > 0) {
                            logger.info("🔍 [Page {}] STEP 2: Processing subsequent page - checking for account reuse", page);
                            // Subsequent pages: Try to reuse the matched account from preview first
                            // This ensures we use the same account across all pages
                            if (importResult.getMatchedAccountId() != null && !importResult.getMatchedAccountId().trim().isEmpty()) {
                                // CRITICAL: First try to use the matched account from preview
                                Optional<AccountTable> matchedAccount = accountRepository.findById(importResult.getMatchedAccountId());
                                if (matchedAccount.isPresent() && matchedAccount.get().getUserId().equals(user.getUserId())) {
                                    accountIdToUse = importResult.getMatchedAccountId();
                                    logger.info("✅ [Page {}] STEP 2a: Using matched account ID from preview: '{}'", page, accountIdToUse);
                                } else {
                                    logger.warn("⚠️ [Page {}] STEP 2a: Matched account ID '{}' from preview not found or doesn't belong to user", 
                                            page, importResult.getMatchedAccountId());
                                }
                            }
                            
                            // If no matched account, try to match by detected account attributes
                            if ((accountIdToUse == null || accountIdToUse.trim().isEmpty()) && existingAccounts != null && !existingAccounts.isEmpty()) {
                                logger.info("🔍 [Page {}] STEP 2b: Found {} existing accounts, checking for match by detected account", page, existingAccounts.size());
                                AccountDetectionService.DetectedAccount detectedAccountForPage = importResult.getDetectedAccount();
                                logger.info("🔍 [Page {}] STEP 2c: Detected account info - name='{}', institution='{}', type='{}', number='{}'", 
                                        page,
                                        detectedAccountForPage != null ? detectedAccountForPage.getAccountName() : "null",
                                        detectedAccountForPage != null ? detectedAccountForPage.getInstitutionName() : "null",
                                        detectedAccountForPage != null ? detectedAccountForPage.getAccountType() : "null",
                                        detectedAccountForPage != null && detectedAccountForPage.getAccountNumber() != null ? "***" + detectedAccountForPage.getAccountNumber().substring(Math.max(0, detectedAccountForPage.getAccountNumber().length() - 4)) : "null");
                                
                                if (detectedAccountForPage != null) {
                                    logger.info("🔍 [Page {}] STEP 2d: Most recent account not found, trying to match by detected account attributes", page);
                                    // CRITICAL: Try multiple matching strategies for better account reuse (deterministic, no time restrictions)
                                    // 1. First try by account number (most reliable)
                                    if (detectedAccountForPage.getAccountNumber() != null && !detectedAccountForPage.getAccountNumber().trim().isEmpty()) {
                                        String normalizedDetectedNumber = normalizeAccountNumber(detectedAccountForPage.getAccountNumber());
                                        logger.info("🔍 [Page {}] STEP 2d-1: Trying to match by account number '{}'", page, normalizedDetectedNumber);
                                        matchingAccount = existingAccounts.stream()
                                            .filter(acc -> {
                                                if (acc.getAccountNumber() == null) return false;
                                                String normalizedAccNumber = normalizeAccountNumber(acc.getAccountNumber());
                                                return normalizedDetectedNumber.equals(normalizedAccNumber);
                                            })
                                            .max(Comparator.comparing((AccountTable acc) -> 
                                                acc.getCreatedAt() != null ? acc.getCreatedAt() : Instant.ofEpochMilli(0)))
                                            .orElse(null);
                                        if (matchingAccount != null) {
                                            logger.info("✅ [Page {}] STEP 2d-1: Found match by account number - ID='{}'", page, matchingAccount.getAccountId());
                                        }
                                    }
                                    
                                    // 2. If no match by number, try by institution and account type (account name might differ due to generation)
                                    if (matchingAccount == null && detectedAccountForPage.getInstitutionName() != null && detectedAccountForPage.getAccountType() != null) {
                                        logger.info("🔍 [Page {}] STEP 2d-2: Trying to match by institution '{}' and type '{}'", 
                                                page, detectedAccountForPage.getInstitutionName(), detectedAccountForPage.getAccountType());
                                        matchingAccount = existingAccounts.stream()
                                            .filter(acc -> {
                                                boolean institutionMatch = detectedAccountForPage.getInstitutionName() != null && 
                                                                         detectedAccountForPage.getInstitutionName().equals(acc.getInstitutionName());
                                                boolean typeMatch = detectedAccountForPage.getAccountType() != null && 
                                                                  detectedAccountForPage.getAccountType().equals(acc.getAccountType());
                                                return institutionMatch && typeMatch;
                                            })
                                            .max(Comparator.comparing((AccountTable acc) -> 
                                                acc.getCreatedAt() != null ? acc.getCreatedAt() : Instant.ofEpochMilli(0)))
                                            .orElse(null);
                                        if (matchingAccount != null) {
                                            logger.info("✅ [Page {}] STEP 2d-2: Found match by institution/type - ID='{}'", page, matchingAccount.getAccountId());
                                        }
                                    }
                                    
                                    // 3. Fallback: Try by account name and institution (original logic)
                                    if (matchingAccount == null) {
                                        logger.info("🔍 [Page {}] STEP 2d-3: Trying to match by account name '{}' and institution '{}'", 
                                                page, detectedAccountForPage.getAccountName(), detectedAccountForPage.getInstitutionName());
                                        matchingAccount = existingAccounts.stream()
                                            .filter(acc -> {
                                                // Match by account name and institution
                                                boolean nameMatch = detectedAccountForPage.getAccountName() != null && 
                                                                  detectedAccountForPage.getAccountName().equals(acc.getAccountName());
                                                boolean institutionMatch = detectedAccountForPage.getInstitutionName() != null && 
                                                                         detectedAccountForPage.getInstitutionName().equals(acc.getInstitutionName());
                                                return nameMatch && institutionMatch;
                                            })
                                            .max(Comparator.comparing((AccountTable acc) -> 
                                                acc.getCreatedAt() != null ? acc.getCreatedAt() : Instant.ofEpochMilli(0)))
                                            .orElse(null);
                                        if (matchingAccount != null) {
                                            logger.info("✅ [Page {}] STEP 2d-3: Found match by name/institution - ID='{}'", page, matchingAccount.getAccountId());
                                        }
                                    }
                                    
                                    if (matchingAccount != null) {
                                        accountIdToUse = matchingAccount.getAccountId();
                                        logger.info("✅ [Page {}] STEP 2e: Using matched account - ID='{}', name='{}', institution='{}'", 
                                                page, accountIdToUse, matchingAccount.getAccountName(), matchingAccount.getInstitutionName());
                                    } else {
                                        logger.warn("⚠️ [Page {}] STEP 2e: No account matched by detected attributes", page);
                                    }
                                }
                            } else {
                                logger.warn("⚠️ [Page {}] STEP 2: existingAccounts is null or empty", page);
                            }
                        }
                            
                            // If still no account found, fall back to reusing most recent account or creating (only on page 0)
                            if (accountIdToUse == null || accountIdToUse.trim().isEmpty()) {
                                logger.info("🔍 [Page {}] STEP 3: accountIdToUse is still null, checking fallback options", page);
                                if (page > 0 && existingAccounts != null && !existingAccounts.isEmpty()) {
                                    logger.info("🔍 [Page {}] STEP 3a: Final fallback - trying to reuse most recent account from {} existing accounts", 
                                            page, existingAccounts.size());
                                    // CRITICAL: On page > 0, ALWAYS reuse the most recently created account
                                    // Never create a new account on subsequent pages (deterministic, no time restrictions)
                                    AccountTable mostRecentAccount = existingAccounts.stream()
                                        .filter(acc -> acc.getCreatedAt() != null)
                                        .max(Comparator.comparing((AccountTable acc) -> 
                                            acc.getCreatedAt() != null ? acc.getCreatedAt() : Instant.ofEpochMilli(0)))
                                        .orElse(null);
                                    
                                    if (mostRecentAccount != null) {
                                        accountIdToUse = mostRecentAccount.getAccountId();
                                        logger.info("✅ [Page {}] STEP 3b: Final fallback SUCCESS - Reusing most recent account - ID='{}', name='{}', createdAt='{}'", 
                                                page, accountIdToUse, mostRecentAccount.getAccountName(), mostRecentAccount.getCreatedAt());
                                    } else {
                                        logger.error("❌ [Page {}] STEP 3b: Final fallback FAILED - No account with createdAt found in {} accounts", 
                                                page, existingAccounts.size());
                                        for (AccountTable acc : existingAccounts) {
                                            logger.error("   Account: ID='{}', name='{}', createdAt='{}'", 
                                                    acc.getAccountId(), acc.getAccountName(), acc.getCreatedAt());
                                        }
                                    }
                                } else if (page == 0) {
                                    // CRITICAL FIX: Don't create generic accounts - use pseudo account instead
                                    // This prevents creating accounts with "Unknown" or "Imported Account" names
                                    logger.info("🔍 [Page 0] STEP 3: No account detected and no account ID provided - transactions will use pseudo account");
                                    // Leave accountIdToUse as null - TransactionService will use pseudo account
                                    accountIdToUse = null;
                                    logger.info("ℹ️ [Page 0] STEP 3: accountIdToUse set to null - transactions will use pseudo account (Manual Transactions)");
                                }
                            }
                        }
                    }
                }

                // Update chunk transactions with account ID
                if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                    for (CSVImportService.ParsedTransaction parsed : chunk) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }

                // Import this chunk
                ResponseEntity<BatchImportResponse> importResponseEntity = processBatchImport(user, chunk, "CSV", originalFilename);
                BatchImportResponse importResponse = importResponseEntity.getBody();

                // Build chunk response
                ChunkImportResponse response = new ChunkImportResponse();
                response.setImportResponse(importResponse);
                response.setPage(page);
                response.setSize(size);
                response.setTotal(totalTransactions);
                response.setTotalPages(totalPages);
                response.setHasNext(page < totalPages - 1);

                return ResponseEntity.ok(response);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("CSV chunk import failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to import CSV chunk: " + e.getMessage());
        }
    }

    // MARK: - Excel Import Endpoints

    @PostMapping("/import-excel/preview")
    public ResponseEntity<ExcelImportPreviewResponse> previewExcel(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String filename,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;
            
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".xlsx";
                logger.error("❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'", originalFilename);
            }
            
            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            boolean isUUIDFilename = originalFilename.matches("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                logger.warn("⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. " +
                           "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.", 
                           originalFilename);
            }
            
            logger.info("📁 Excel Preview - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})", 
                originalFilename, fromParameter, file.getOriginalFilename(), isUUIDFilename);
            
            // Log multipart request details for debugging
            logger.info("📤 Excel Preview Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}'", 
                filename, file.getOriginalFilename(), file.getSize(), file.getContentType());
            
            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), EXCEL_EXTENSIONS);

            // Parse Excel - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                ExcelImportService.ImportResult importResult = excelImportService.parseExcel(
                        inputStream, originalFilename, user.getUserId(), null);

                // Build preview response (similar to CSV)
                List<DuplicateDetectionService.ParsedTransaction> parsedForDuplicateCheck = new ArrayList<>();
                
                for (CSVImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                    DuplicateDetectionService.ParsedTransaction dupTx = new DuplicateDetectionService.ParsedTransaction(
                            parsed.getDate(),
                            parsed.getAmount(),
                            parsed.getDescription(),
                            parsed.getMerchantName()
                    );
                    dupTx.setTransactionId(parsed.getTransactionId());
                    parsedForDuplicateCheck.add(dupTx);
                }

                Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates = 
                        duplicateDetectionService.detectDuplicates(user.getUserId(), parsedForDuplicateCheck);

                // P1: Pagination - Build transaction maps with duplicate info (with pagination)
                int totalTransactions = importResult.getTransactions().size();
                int startIndex = page * size;
                int endIndex = Math.min(startIndex + size, totalTransactions);
                int totalPages = (int) Math.ceil((double) totalTransactions / size);
                
                // Validate pagination parameters
                if (page < 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
                }
                if (size < 1 || size > 1000) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 1000");
                }
                
                List<Map<String, Object>> paginatedTransactions = new ArrayList<>();
                for (int i = startIndex; i < endIndex; i++) {
                    CSVImportService.ParsedTransaction parsed = importResult.getTransactions().get(i);
                    Map<String, Object> txMap = buildTransactionMap(parsed, duplicates.get(i));
                    paginatedTransactions.add(txMap);
                }

                ExcelImportPreviewResponse response = new ExcelImportPreviewResponse();
                response.setTotalParsed(importResult.getSuccessCount());
                response.setTransactions(paginatedTransactions);
                response.setPage(page);
                response.setSize(size);
                response.setTotalPages(totalPages);
                response.setTotalElements(totalTransactions);
                DetectedAccountInfo accountInfo = null;
                if (importResult.getDetectedAccount() != null) {
                    accountInfo = new DetectedAccountInfo();
                    
                    // CRITICAL: If account was matched, use the matched account's details instead of detected account
                    // This ensures iOS shows the existing account information, not the detected account
                    String matchedAccountId = importResult.getMatchedAccountId();
                    if (matchedAccountId != null && !matchedAccountId.trim().isEmpty()) {
                        // Fetch the matched account from database
                        Optional<AccountTable> matchedAccount = accountRepository.findById(matchedAccountId);
                        if (matchedAccount.isPresent() && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            AccountTable account = matchedAccount.get();
                            // Use matched account's details
                            accountInfo.setAccountName(account.getAccountName());
                            accountInfo.setInstitutionName(account.getInstitutionName());
                            accountInfo.setAccountType(account.getAccountType());
                            accountInfo.setAccountSubtype(account.getAccountSubtype());
                            accountInfo.setAccountNumber(account.getAccountNumber());
                            accountInfo.setCardNumber(null); // Card number not stored in AccountTable
                            accountInfo.setBalance(account.getBalance()); // Include balance from existing account
                            accountInfo.setMatchedAccountId(matchedAccountId);
                            
                            // CRITICAL: Include credit card metadata from existing account (if available)
                            // Excel imports don't extract this metadata, so use existing account's metadata
                            accountInfo.setPaymentDueDate(account.getPaymentDueDate());
                            accountInfo.setMinimumPaymentDue(account.getMinimumPaymentDue());
                            accountInfo.setRewardPoints(account.getRewardPoints());
                            
                            logger.info("✅ [Excel] Matched detected account to existing account: {} (accountId: {})", 
                                    account.getAccountName(), matchedAccountId);
                        } else {
                            // Matched account not found or doesn't belong to user - use detected account
                            logger.warn("⚠️ [Excel] Matched account ID '{}' not found or doesn't belong to user - using detected account info", matchedAccountId);
                            accountInfo.setAccountName(importResult.getDetectedAccount().getAccountName());
                            accountInfo.setInstitutionName(importResult.getDetectedAccount().getInstitutionName());
                            accountInfo.setAccountType(importResult.getDetectedAccount().getAccountType());
                            accountInfo.setAccountSubtype(importResult.getDetectedAccount().getAccountSubtype());
                            accountInfo.setAccountNumber(importResult.getDetectedAccount().getAccountNumber());
                            accountInfo.setCardNumber(importResult.getDetectedAccount().getCardNumber());
                            accountInfo.setBalance(importResult.getDetectedAccount().getBalance()); // Include detected balance
                            accountInfo.setMatchedAccountId(null); // Clear invalid match
                            
                            // Excel imports don't extract credit card metadata - set to null
                            accountInfo.setPaymentDueDate(null);
                            accountInfo.setMinimumPaymentDue(null);
                            accountInfo.setRewardPoints(null);
                        }
                    } else {
                        // No match found - use detected account info
                        accountInfo.setAccountName(importResult.getDetectedAccount().getAccountName());
                        accountInfo.setInstitutionName(importResult.getDetectedAccount().getInstitutionName());
                        accountInfo.setAccountType(importResult.getDetectedAccount().getAccountType());
                        accountInfo.setAccountSubtype(importResult.getDetectedAccount().getAccountSubtype());
                        accountInfo.setAccountNumber(importResult.getDetectedAccount().getAccountNumber());
                        accountInfo.setCardNumber(importResult.getDetectedAccount().getCardNumber());
                        accountInfo.setBalance(importResult.getDetectedAccount().getBalance()); // Include detected balance
                        accountInfo.setMatchedAccountId(null);
                        
                        // Excel imports don't extract credit card metadata - set to null
                        accountInfo.setPaymentDueDate(null);
                        accountInfo.setMinimumPaymentDue(null);
                        accountInfo.setRewardPoints(null);
                    }
                    
                    response.setDetectedAccount(accountInfo);
                }

                // Log response details
                logger.info("📥 Excel Preview Response - Total parsed: {}, Transactions: {}, Detected account: {} (institution: {}, type: {}, number: {})", 
                    response.getTotalParsed(), 
                    response.getTransactions() != null ? response.getTransactions().size() : 0,
                    accountInfo != null ? accountInfo.getAccountName() : "none",
                    accountInfo != null ? accountInfo.getInstitutionName() : "none",
                    accountInfo != null ? accountInfo.getAccountType() : "none",
                    accountInfo != null ? accountInfo.getAccountNumber() : "none");

                return ResponseEntity.ok(response);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Excel preview failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to preview Excel: " + e.getMessage());
        }
    }

    @PostMapping("/import-excel")
    public ResponseEntity<BatchImportResponse> importExcel(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;
            
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".xlsx";
                logger.error("❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'", originalFilename);
            }
            
            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            boolean isUUIDFilename = originalFilename.matches("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                logger.warn("⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. " +
                           "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.", 
                           originalFilename);
            }
            
            logger.info("📁 Excel Import - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})", 
                originalFilename, fromParameter, file.getOriginalFilename(), isUUIDFilename);
            
            // Log multipart request details for debugging
            logger.info("📤 Excel Import Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', AccountId: '{}'", 
                filename, file.getOriginalFilename(), file.getSize(), file.getContentType(), accountId);
            
            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), EXCEL_EXTENSIONS);

            // Parse and import Excel - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                ExcelImportService.ImportResult importResult = excelImportService.parseExcel(
                        inputStream, originalFilename, user.getUserId(), null);

                return processBatchImport(user, importResult.getTransactions(), "EXCEL", originalFilename);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Excel import failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to import Excel: " + e.getMessage());
        }
    }

    @PostMapping("/import-excel/chunk")
    public ResponseEntity<ChunkImportResponse> importExcelChunk(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Validate pagination parameters
        if (page < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
        }
        if (size < 1 || size > 500) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 500");
        }

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".xlsx";
            }

            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), EXCEL_EXTENSIONS);

            // Parse Excel file
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                ExcelImportService.ImportResult importResult = excelImportService.parseExcel(
                        inputStream, originalFilename, user.getUserId(), null);

                List<CSVImportService.ParsedTransaction> allTransactions = importResult.getTransactions();
                int totalTransactions = allTransactions.size();
                int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate page number
                if (page >= totalPages && totalPages > 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, 
                        String.format("Page %d is out of range. Total pages: %d", page, totalPages));
                }

                // Get transactions for this page
                int startIndex = page * size;
                int endIndex = Math.min(startIndex + size, totalTransactions);
                List<CSVImportService.ParsedTransaction> chunk = allTransactions.subList(startIndex, endIndex);

                logger.info("📦 Importing Excel chunk: page {} (transactions {} to {} of {})", 
                    page, startIndex + 1, endIndex, totalTransactions);

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // Only create on first page (page 0) to avoid creating multiple accounts
                // Reuse the same account across all pages for paginated imports
                String accountIdToUse = accountId;
                if (accountIdToUse == null || accountIdToUse.trim().isEmpty()) {
                    List<AccountTable> existingAccounts = accountRepository.findByUserId(user.getUserId());
                    
                    // Step 1: Check if user has manually created an account matching the detected account
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null && existingAccounts != null && !existingAccounts.isEmpty()) {
                        AccountDetectionService.DetectedAccount detected = importResult.getDetectedAccount();
                        
                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null && !detected.getAccountNumber().trim().isEmpty()) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountNumber().equals(acc.getAccountNumber()))
                                .findFirst()
                                .orElse(null);
                        }
                        
                        // If no match by account number, try to match by account name and institution
                        if (matchingAccount == null && detected.getAccountName() != null && detected.getInstitutionName() != null) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountName().equals(acc.getAccountName()) &&
                                             detected.getInstitutionName().equals(acc.getInstitutionName()))
                                .findFirst()
                                .orElse(null);
                        }
                    }
                    
                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        logger.info("📝 [Excel] Using existing account '{}' (name: '{}', institution: '{}') for import (page {})", 
                                accountIdToUse, matchingAccount.getAccountName(), matchingAccount.getInstitutionName(), page);
                    } else if (importResult.getMatchedAccountId() != null && !importResult.getMatchedAccountId().trim().isEmpty()) {
                        // Account was matched during preview - use it
                        accountIdToUse = importResult.getMatchedAccountId();
                        logger.info("📝 [Excel] Using matched account ID from preview: '{}' (page {})", accountIdToUse, page);
                    } else if (importResult.getDetectedAccount() != null && page == 0) {
                        // CRITICAL: Only auto-create on first page (page 0) to avoid creating multiple accounts
                        // User hasn't manually created a matching account - auto-create it
                        accountIdToUse = autoCreateAccountIfDetected(user, importResult.getDetectedAccount());
                        logger.info("📝 [Excel] Auto-created account '{}' for detected account '{}' from '{}' (first page only)", 
                                accountIdToUse, 
                                importResult.getDetectedAccount().getAccountName(),
                                importResult.getDetectedAccount().getInstitutionName());
                    } else if (importResult.getDetectedAccount() != null && page > 0) {
                        // Subsequent pages: Try to find the account that was auto-created on first page
                        if (existingAccounts != null && !existingAccounts.isEmpty()) {
                            AccountDetectionService.DetectedAccount detected = importResult.getDetectedAccount();
                            Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> {
                                    boolean nameMatch = detected.getAccountName() != null && 
                                                      detected.getAccountName().equals(acc.getAccountName());
                                    boolean institutionMatch = detected.getInstitutionName() != null && 
                                                             detected.getInstitutionName().equals(acc.getInstitutionName());
                                    boolean recentlyCreated = acc.getCreatedAt() != null && 
                                                            acc.getCreatedAt().isAfter(fiveMinutesAgo);
                                    return nameMatch && institutionMatch && recentlyCreated;
                                })
                                .max(Comparator.comparing((AccountTable acc) -> 
                                    acc.getCreatedAt() != null ? acc.getCreatedAt() : Instant.ofEpochMilli(0)))
                                .orElse(null);
                            
                            if (matchingAccount != null) {
                                accountIdToUse = matchingAccount.getAccountId();
                                logger.info("📝 [Excel] Reusing auto-created account '{}' from first page (page {})", accountIdToUse, page);
                            }
                        }
                    }
                }

                // Update chunk transactions with account ID
                if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                    for (CSVImportService.ParsedTransaction parsed : chunk) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }

                // Import this chunk
                ResponseEntity<BatchImportResponse> importResponseEntity = processBatchImport(user, chunk, "EXCEL", originalFilename);
                BatchImportResponse importResponse = importResponseEntity.getBody();

                // Build chunk response
                ChunkImportResponse response = new ChunkImportResponse();
                response.setImportResponse(importResponse);
                response.setPage(page);
                response.setSize(size);
                response.setTotal(totalTransactions);
                response.setTotalPages(totalPages);
                response.setHasNext(page < totalPages - 1);

                return ResponseEntity.ok(response);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Excel chunk import failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to import Excel chunk: " + e.getMessage());
        }
    }

    // MARK: - PDF Import Endpoints

    @PostMapping("/import-pdf/preview")
    public ResponseEntity<PDFImportPreviewResponse> previewPDF(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String filename,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;
            
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".pdf";
                logger.error("❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'", originalFilename);
            }
            
            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            boolean isUUIDFilename = originalFilename.matches("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                logger.warn("⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. " +
                           "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.", 
                           originalFilename);
            }
            
            logger.info("📁 PDF Preview - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})", 
                originalFilename, fromParameter, file.getOriginalFilename(), isUUIDFilename);
            
            // Log multipart request details for debugging
            logger.info("📤 PDF Preview Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}'", 
                filename, file.getOriginalFilename(), file.getSize(), file.getContentType());
            
            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), PDF_EXTENSIONS);

            // Parse PDF - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                PDFImportService.ImportResult importResult = pdfImportService.parsePDF(
                        inputStream, originalFilename, user.getUserId(), null);

                // Build preview response (similar to CSV/Excel)
                List<DuplicateDetectionService.ParsedTransaction> parsedForDuplicateCheck = new ArrayList<>();
                
                for (PDFImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                    DuplicateDetectionService.ParsedTransaction dupTx = new DuplicateDetectionService.ParsedTransaction(
                            parsed.getDate(),
                            parsed.getAmount(),
                            parsed.getDescription(),
                            parsed.getMerchantName()
                    );
                    dupTx.setTransactionId(parsed.getTransactionId());
                    parsedForDuplicateCheck.add(dupTx);
                }

                Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates = 
                        duplicateDetectionService.detectDuplicates(user.getUserId(), parsedForDuplicateCheck);

                // Log duplicate detection results
                int duplicateCount = duplicates != null ? duplicates.size() : 0;
                logger.info("🔍 PDF Preview - Duplicate detection: {} transactions with duplicates out of {} total", 
                        duplicateCount, parsedForDuplicateCheck.size());
                if (duplicateCount > 0 && duplicates != null) {
                    for (Map.Entry<Integer, List<DuplicateDetectionService.DuplicateMatch>> entry : duplicates.entrySet()) {
                        logger.info("🔍 PDF Preview - Transaction index {} has {} duplicate(s): {}", 
                                entry.getKey(), entry.getValue().size(), 
                                entry.getValue().stream()
                                    .map(m -> String.format("similarity=%.2f, reason=%s", 
                                            m.getSimilarity(), // getSimilarity() returns primitive double, not Double
                                            m.getMatchReason() != null ? m.getMatchReason() : "unknown"))
                                    .collect(java.util.stream.Collectors.joining(", ")));
                    }
                }

                // P1: Pagination - Build transaction maps with duplicate info (with pagination)
                int totalTransactions = importResult.getTransactions().size();
                int startIndex = page * size;
                int endIndex = Math.min(startIndex + size, totalTransactions);
                int totalPages = (int) Math.ceil((double) totalTransactions / size);
                
                // Validate pagination parameters
                if (page < 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
                }
                if (size < 1 || size > 1000) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 1000");
                }
                
                List<Map<String, Object>> paginatedTransactions = new ArrayList<>();
                for (int i = startIndex; i < endIndex; i++) {
                    PDFImportService.ParsedTransaction parsed = importResult.getTransactions().get(i);
                    // CRITICAL FIX: Get duplicates for this transaction index (may be null if no duplicates)
                    // Note: duplicates map may contain empty lists for exact matches (shouldSkip = true)
                    List<DuplicateDetectionService.DuplicateMatch> txDuplicates = duplicates != null ? duplicates.get(i) : null;
                    
                    // Log duplicate status for debugging
                    if (txDuplicates != null) {
                        if (txDuplicates.isEmpty()) {
                            logger.debug("PDF Preview - Transaction index {} has exact match (empty list in duplicates map)", i);
                        } else {
                            logger.info("✅ PDF Preview - Transaction index {} has {} fuzzy duplicate(s) in response", i, txDuplicates.size());
                        }
                    } else {
                        logger.debug("PDF Preview - Transaction index {} has no duplicates (null in duplicates map)", i);
                    }
                    
                    Map<String, Object> txMap = buildPDFTransactionMap(parsed, txDuplicates);
                    paginatedTransactions.add(txMap);
                    
                    // Verify duplicate info was set correctly in the response
                    Boolean hasDuplicatesInResponse = (Boolean) txMap.get("hasDuplicates");
                    if (txDuplicates != null && !txDuplicates.isEmpty() && !Boolean.TRUE.equals(hasDuplicatesInResponse)) {
                        logger.warn("⚠️ PDF Preview - Transaction index {} has duplicates but hasDuplicates is false in response!", i);
                    }
                }

                PDFImportPreviewResponse response = new PDFImportPreviewResponse();
                response.setTotalParsed(importResult.getSuccessCount());
                response.setTransactions(paginatedTransactions);
                response.setPage(page);
                response.setSize(size);
                response.setTotalPages(totalPages);
                response.setTotalElements(totalTransactions);
                DetectedAccountInfo accountInfo = null;
                if (importResult.getDetectedAccount() != null) {
                    accountInfo = new DetectedAccountInfo();
                    
                    // CRITICAL: If account was matched, use the matched account's details instead of detected account
                    // This ensures iOS shows the existing account information, not the detected account
                    String matchedAccountId = importResult.getMatchedAccountId();
                    if (matchedAccountId != null && !matchedAccountId.trim().isEmpty()) {
                        // Fetch the matched account from database
                        Optional<AccountTable> matchedAccount = accountRepository.findById(matchedAccountId);
                        if (matchedAccount.isPresent() && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            AccountTable account = matchedAccount.get();
                            // Use matched account's details
                            accountInfo.setAccountName(account.getAccountName());
                            accountInfo.setInstitutionName(account.getInstitutionName());
                            accountInfo.setAccountType(account.getAccountType());
                            accountInfo.setAccountSubtype(account.getAccountSubtype());
                            accountInfo.setAccountNumber(account.getAccountNumber());
                            accountInfo.setCardNumber(null); // Card number not stored in AccountTable
                            accountInfo.setBalance(account.getBalance()); // Include balance from existing account
                            accountInfo.setMatchedAccountId(matchedAccountId);
                            
                            // CRITICAL: Include metadata from existing account (if available)
                            // But also include metadata from import result if it's newer (for preview display)
                            // The actual update will happen during import via updateAccountMetadataFromPDFImport
                            if (importResult != null && importResult.getPaymentDueDate() != null) {
                                // Use import result metadata if available (newer statement)
                                accountInfo.setPaymentDueDate(importResult.getPaymentDueDate());
                                accountInfo.setMinimumPaymentDue(importResult.getMinimumPaymentDue());
                                accountInfo.setRewardPoints(importResult.getRewardPoints());
                            } else {
                                // Fall back to existing account metadata
                                accountInfo.setPaymentDueDate(account.getPaymentDueDate());
                                accountInfo.setMinimumPaymentDue(account.getMinimumPaymentDue());
                                accountInfo.setRewardPoints(account.getRewardPoints());
                            }
                            
                            logger.info("✅ [PDF] Matched detected account to existing account: {} (accountId: {})", 
                                    account.getAccountName(), matchedAccountId);
                        } else {
                            // Matched account not found or doesn't belong to user - use detected account
                            logger.warn("⚠️ [PDF] Matched account ID '{}' not found or doesn't belong to user - using detected account info", matchedAccountId);
                            accountInfo.setAccountName(importResult.getDetectedAccount().getAccountName());
                            accountInfo.setInstitutionName(importResult.getDetectedAccount().getInstitutionName());
                            accountInfo.setAccountType(importResult.getDetectedAccount().getAccountType());
                            accountInfo.setAccountSubtype(importResult.getDetectedAccount().getAccountSubtype());
                            accountInfo.setAccountNumber(importResult.getDetectedAccount().getAccountNumber());
                            accountInfo.setCardNumber(importResult.getDetectedAccount().getCardNumber());
                            accountInfo.setBalance(importResult.getDetectedAccount().getBalance()); // Include detected balance
                            accountInfo.setMatchedAccountId(null); // Clear invalid match
                            
                            // CRITICAL: Include metadata from import result (extracted from PDF)
                            accountInfo.setPaymentDueDate(importResult.getPaymentDueDate());
                            accountInfo.setMinimumPaymentDue(importResult.getMinimumPaymentDue());
                            accountInfo.setRewardPoints(importResult.getRewardPoints());
                        }
                    } else {
                        // No match found - use detected account info
                        accountInfo.setAccountName(importResult.getDetectedAccount().getAccountName());
                        accountInfo.setInstitutionName(importResult.getDetectedAccount().getInstitutionName());
                        accountInfo.setAccountType(importResult.getDetectedAccount().getAccountType());
                        accountInfo.setAccountSubtype(importResult.getDetectedAccount().getAccountSubtype());
                        accountInfo.setAccountNumber(importResult.getDetectedAccount().getAccountNumber());
                        accountInfo.setCardNumber(importResult.getDetectedAccount().getCardNumber());
                        accountInfo.setBalance(importResult.getDetectedAccount().getBalance()); // Include detected balance
                        accountInfo.setMatchedAccountId(null);
                        
                        // CRITICAL: Include metadata from import result (extracted from PDF)
                        accountInfo.setPaymentDueDate(importResult.getPaymentDueDate());
                        accountInfo.setMinimumPaymentDue(importResult.getMinimumPaymentDue());
                        accountInfo.setRewardPoints(importResult.getRewardPoints());
                    }
                    
                    response.setDetectedAccount(accountInfo);
                    
                    // Log balance in detected account for debugging
                    if (accountInfo != null && accountInfo.getBalance() != null) {
                        logger.info("✅ [PDF Preview] Detected account balance included in response: {}", accountInfo.getBalance());
                    } else if (accountInfo != null) {
                        logger.debug("⚠️ [PDF Preview] Detected account balance is null in response");
                    }
                }

                // Log response details
                logger.info("📥 PDF Preview Response - Total parsed: {}, Transactions: {}, Detected account: {} (institution: {}, type: {}, number: {}, balance: {})", 
                    response.getTotalParsed(), 
                    response.getTransactions() != null ? response.getTransactions().size() : 0,
                    accountInfo != null ? accountInfo.getAccountName() : "none",
                    accountInfo != null ? accountInfo.getInstitutionName() : "none",
                    accountInfo != null ? accountInfo.getAccountType() : "none",
                    accountInfo != null ? accountInfo.getAccountNumber() : "none",
                    accountInfo != null && accountInfo.getBalance() != null ? accountInfo.getBalance() : "none");

                return ResponseEntity.ok(response);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("PDF preview failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to preview PDF: " + e.getMessage());
        }
    }

    @PostMapping("/import-pdf")
    public ResponseEntity<BatchImportResponse> importPDF(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;
            
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".pdf";
                logger.error("❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'", originalFilename);
            }
            
            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            boolean isUUIDFilename = originalFilename.matches("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                logger.warn("⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. " +
                           "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.", 
                           originalFilename);
            }
            
            logger.info("📁 PDF Import - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})", 
                originalFilename, fromParameter, file.getOriginalFilename(), isUUIDFilename);
            
            // Log multipart request details for debugging
            logger.info("📤 PDF Import Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', AccountId: '{}'", 
                filename, file.getOriginalFilename(), file.getSize(), file.getContentType(), accountId);
            
            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), PDF_EXTENSIONS);

            // Parse and import PDF - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                PDFImportService.ImportResult importResult = pdfImportService.parsePDF(
                        inputStream, originalFilename, user.getUserId(), null);

                // CRITICAL: Log metadata extraction immediately after parsing
                logger.info("📋 [PDF Import] After parsing PDF - importResult metadata: paymentDueDate={}, minimumPaymentDue={}, rewardPoints={}", 
                        importResult.getPaymentDueDate(), 
                        importResult.getMinimumPaymentDue(), 
                        importResult.getRewardPoints());

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // This ensures transactions are associated with the correct account
                String accountIdToUse = accountId;
                if (accountIdToUse == null || accountIdToUse.trim().isEmpty()) {
                    List<AccountTable> existingAccounts = accountRepository.findByUserId(user.getUserId());
                    
                    // Step 1: Check if user has manually created an account matching the detected account
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null && existingAccounts != null && !existingAccounts.isEmpty()) {
                        AccountDetectionService.DetectedAccount detected = importResult.getDetectedAccount();
                        
                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null && !detected.getAccountNumber().trim().isEmpty()) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountNumber().equals(acc.getAccountNumber()))
                                .findFirst()
                                .orElse(null);
                        }
                        
                        // If no match by account number, try to match by account name and institution
                        if (matchingAccount == null && detected.getAccountName() != null && detected.getInstitutionName() != null) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountName().equals(acc.getAccountName()) &&
                                             detected.getInstitutionName().equals(acc.getInstitutionName()))
                                .findFirst()
                                .orElse(null);
                        }
                    }
                    
                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        logger.info("📝 [PDF] Using existing account '{}' (name: '{}', institution: '{}') for import", 
                                accountIdToUse, matchingAccount.getAccountName(), matchingAccount.getInstitutionName());
                    } else if (importResult.getMatchedAccountId() != null && !importResult.getMatchedAccountId().trim().isEmpty()) {
                        // Account was matched during preview - use it
                        accountIdToUse = importResult.getMatchedAccountId();
                        logger.info("📝 [PDF] Using matched account ID from preview: '{}'", accountIdToUse);
                    } else if (importResult.getDetectedAccount() != null) {
                        // User hasn't manually created a matching account - auto-create it
                        // CRITICAL: Pass importResult metadata to set metadata during account creation
                        accountIdToUse = autoCreateAccountIfDetected(user, importResult.getDetectedAccount(), importResult);
                        logger.info("📝 [PDF] Auto-created account '{}' for detected account '{}' from '{}'", 
                                accountIdToUse, 
                                importResult.getDetectedAccount().getAccountName(),
                                importResult.getDetectedAccount().getInstitutionName());
                    }
                }

                // Update all transactions with the account ID
                if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                    for (PDFImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }
                
                // CRITICAL: Update account metadata with latest values from PDF import
                // This updates payment due date, minimum payment due, reward points, and balance
                // based on the latest payment due date
                // NOTE: This MUST be called for ALL imports, even if accountId was provided as parameter
                // The accountIdToUse might be null if no account was found/created, but if it's not null, we should update
                if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                    logger.info("📋 [PDF Import] About to update account metadata for accountId: '{}' - importResult metadata: paymentDueDate={}, minimumPaymentDue={}, rewardPoints={}", 
                            accountIdToUse, 
                            importResult.getPaymentDueDate(), 
                            importResult.getMinimumPaymentDue(), 
                            importResult.getRewardPoints());
                    updateAccountMetadataFromPDFImport(accountIdToUse, importResult);
                } else {
                    logger.warn("⚠️ [PDF Import] Cannot update account metadata: accountIdToUse is null or empty. Metadata extracted: paymentDueDate={}, minimumPaymentDue={}, rewardPoints={}", 
                            importResult.getPaymentDueDate(), 
                            importResult.getMinimumPaymentDue(), 
                            importResult.getRewardPoints());
                }

                return processPDFBatchImport(user, importResult.getTransactions(), "PDF", originalFilename);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("PDF import failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to import PDF: " + e.getMessage());
        }
    }

    @PostMapping("/import-pdf/chunk")
    public ResponseEntity<ChunkImportResponse> importPDFChunk(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Validate pagination parameters
        if (page < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
        }
        if (size < 1 || size > 500) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 500");
        }

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            if (filename != null && !filename.trim().isEmpty()) {
                originalFilename = java.net.URLDecoder.decode(filename.trim(), java.nio.charset.StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }
            
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".pdf";
            }

            // Apply security processing
            byte[] fileContent = applySecurityProcessing(file, user.getUserId(), PDF_EXTENSIONS);

            // Parse PDF file
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                PDFImportService.ImportResult importResult = pdfImportService.parsePDF(
                        inputStream, originalFilename, user.getUserId(), null);

                List<PDFImportService.ParsedTransaction> allTransactions = importResult.getTransactions();
                int totalTransactions = allTransactions.size();
                int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate page number
                if (page >= totalPages && totalPages > 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, 
                        String.format("Page %d is out of range. Total pages: %d", page, totalPages));
                }

                // Get transactions for this page
                int startIndex = page * size;
                int endIndex = Math.min(startIndex + size, totalTransactions);
                List<PDFImportService.ParsedTransaction> chunk = allTransactions.subList(startIndex, endIndex);

                logger.info("📦 Importing PDF chunk: page {} (transactions {} to {} of {})", 
                    page, startIndex + 1, endIndex, totalTransactions);

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // Only create on first page (page 0) to avoid creating multiple accounts
                // Reuse the same account across all pages for paginated imports
                String accountIdToUse = accountId;
                if (accountIdToUse == null || accountIdToUse.trim().isEmpty()) {
                    List<AccountTable> existingAccounts = accountRepository.findByUserId(user.getUserId());
                    
                    // Step 1: Check if user has manually created an account matching the detected account
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null && existingAccounts != null && !existingAccounts.isEmpty()) {
                        AccountDetectionService.DetectedAccount detected = importResult.getDetectedAccount();
                        
                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null && !detected.getAccountNumber().trim().isEmpty()) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountNumber().equals(acc.getAccountNumber()))
                                .findFirst()
                                .orElse(null);
                        }
                        
                        // If no match by account number, try to match by account name and institution
                        if (matchingAccount == null && detected.getAccountName() != null && detected.getInstitutionName() != null) {
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> detected.getAccountName().equals(acc.getAccountName()) &&
                                             detected.getInstitutionName().equals(acc.getInstitutionName()))
                                .findFirst()
                                .orElse(null);
                        }
                    }
                    
                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        logger.info("📝 [PDF] Using existing account '{}' (name: '{}', institution: '{}') for import (page {})", 
                                accountIdToUse, matchingAccount.getAccountName(), matchingAccount.getInstitutionName(), page);
                    } else if (importResult.getMatchedAccountId() != null && !importResult.getMatchedAccountId().trim().isEmpty()) {
                        // Account was matched during preview - use it
                        accountIdToUse = importResult.getMatchedAccountId();
                        logger.info("📝 [PDF] Using matched account ID from preview: '{}' (page {})", accountIdToUse, page);
                    } else if (importResult.getDetectedAccount() != null && page == 0) {
                        // CRITICAL: Only auto-create on first page (page 0) to avoid creating multiple accounts
                        // User hasn't manually created a matching account - auto-create it
                        accountIdToUse = autoCreateAccountIfDetected(user, importResult.getDetectedAccount(), importResult);
                        logger.info("📝 [PDF] Auto-created account '{}' for detected account '{}' from '{}' (first page only)", 
                                accountIdToUse, 
                                importResult.getDetectedAccount().getAccountName(),
                                importResult.getDetectedAccount().getInstitutionName());
                    } else if (importResult.getDetectedAccount() != null && page > 0) {
                        // Subsequent pages: Try to find the account that was auto-created on first page
                        if (existingAccounts != null && !existingAccounts.isEmpty()) {
                            AccountDetectionService.DetectedAccount detected = importResult.getDetectedAccount();
                            Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
                            matchingAccount = existingAccounts.stream()
                                .filter(acc -> {
                                    boolean nameMatch = detected.getAccountName() != null && 
                                                      detected.getAccountName().equals(acc.getAccountName());
                                    boolean institutionMatch = detected.getInstitutionName() != null && 
                                                             detected.getInstitutionName().equals(acc.getInstitutionName());
                                    boolean recentlyCreated = acc.getCreatedAt() != null && 
                                                            acc.getCreatedAt().isAfter(fiveMinutesAgo);
                                    return nameMatch && institutionMatch && recentlyCreated;
                                })
                                .max(Comparator.comparing((AccountTable acc) -> 
                                    acc.getCreatedAt() != null ? acc.getCreatedAt() : Instant.ofEpochMilli(0)))
                                .orElse(null);
                            
                            if (matchingAccount != null) {
                                accountIdToUse = matchingAccount.getAccountId();
                                logger.info("📝 [PDF] Reusing auto-created account '{}' from first page (page {})", accountIdToUse, page);
                            }
                        }
                    }
                }

                // Update chunk transactions with account ID
                if (accountIdToUse != null && !accountIdToUse.trim().isEmpty()) {
                    for (PDFImportService.ParsedTransaction parsed : chunk) {
                        parsed.setAccountId(accountIdToUse);
                    }
                    
                    // CRITICAL: Update account metadata with latest values from PDF import
                    // Only update on page 0 to avoid redundant updates (all pages have same metadata from same PDF)
                    if (page == 0) {
                        updateAccountMetadataFromPDFImport(accountIdToUse, importResult);
                    }
                }

                // Import this chunk
                ResponseEntity<BatchImportResponse> importResponseEntity = processPDFBatchImport(user, chunk, "PDF", originalFilename);
                BatchImportResponse importResponse = importResponseEntity.getBody();

                // Build chunk response
                ChunkImportResponse response = new ChunkImportResponse();
                response.setImportResponse(importResponse);
                response.setPage(page);
                response.setSize(size);
                response.setTotal(totalTransactions);
                response.setTotalPages(totalPages);
                response.setHasNext(page < totalPages - 1);

                return ResponseEntity.ok(response);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("PDF chunk import failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INVALID_INPUT, "Failed to import PDF chunk: " + e.getMessage());
        }
    }

    // MARK: - Preview Recalculation API

    /**
     * Recalculate preview transactions with new account type
     * Called when user changes account type during import preview
     * 
     * @param transactions List of transactions from preview
     * @param accountType New account type to use for recalculation
     * @param importSource Import source (CSV, EXCEL, PDF)
     * @return Updated transactions with recalculated categories and types
     */
    // Rate limiting for preview recalculation (10 requests per minute per user)
    private static final int MAX_RECALCULATE_REQUESTS_PER_MINUTE = 10;
    private final Map<String, List<Long>> recalculateRateLimitMap = new java.util.concurrent.ConcurrentHashMap<>();
    
    @PostMapping("/import/recalculate-preview")
    public ResponseEntity<Map<String, Object>> recalculatePreview(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody RecalculatePreviewRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        // Verify user exists (authorization check)
        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        logger.debug("Recalculating preview for user: {}", user.getUserId());

        // RATE LIMITING: Check if user has exceeded rate limit
        String userId = user.getUserId();
        long currentTime = System.currentTimeMillis();
        List<Long> requestTimes = recalculateRateLimitMap.computeIfAbsent(userId, k -> new java.util.ArrayList<>());
        
        // Remove requests older than 1 minute
        requestTimes.removeIf(time -> currentTime - time > 60000);
        
        if (requestTimes.size() >= MAX_RECALCULATE_REQUESTS_PER_MINUTE) {
            logger.warn("Rate limit exceeded for user {}: {} requests in last minute", userId, requestTimes.size());
            throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED, 
                "Too many recalculation requests. Please wait before trying again.");
        }
        
        // Record this request
        requestTimes.add(currentTime);

        try {
            // INPUT VALIDATION: Check required fields
            if (request.getTransactions() == null || request.getTransactions().isEmpty()) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Transactions list is required");
            }
            if (request.getAccountType() == null || request.getAccountType().trim().isEmpty()) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Account type is required");
            }
            
            // BOUNDARY CHECK: Limit transaction count to prevent memory exhaustion
            final int MAX_TRANSACTIONS_PER_REQUEST = 10000;
            if (request.getTransactions().size() > MAX_TRANSACTIONS_PER_REQUEST) {
                throw new AppException(ErrorCode.INVALID_INPUT, 
                    String.format("Too many transactions. Maximum %d transactions per request.", MAX_TRANSACTIONS_PER_REQUEST));
            }
            
            // VALIDATION: Validate account type
            String accountType = request.getAccountType().trim();
            Set<String> validAccountTypes = Set.of("depository", "credit", "loan", "investment", "other", "brokerage", "401k", "ira", "hsa", "529");
            if (!validAccountTypes.contains(accountType.toLowerCase())) {
                logger.warn("Invalid account type provided: {}", accountType);
                // Don't fail, but log warning
            }

            // Create temporary account for recalculation
            AccountTable tempAccount = new AccountTable();
            tempAccount.setAccountType(request.getAccountType().trim());
            tempAccount.setAccountSubtype(request.getAccountSubtype() != null ? request.getAccountSubtype().trim() : null);
            tempAccount.setInstitutionName(request.getInstitutionName() != null ? request.getInstitutionName().trim() : null);

            // Recalculate each transaction
            List<Map<String, Object>> recalculatedTransactions = new ArrayList<>();
            for (Map<String, Object> txMap : request.getTransactions()) {
                try {
                    // Extract and validate transaction data
                    String description = txMap.get("description") != null ? txMap.get("description").toString() : null;
                    String merchantName = txMap.get("merchantName") != null ? txMap.get("merchantName").toString() : null;
                    
                    // BOUNDARY CHECK: Validate string lengths
                    final int MAX_DESCRIPTION_LENGTH = 500;
                    final int MAX_MERCHANT_NAME_LENGTH = 200;
                    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
                        logger.warn("Description too long ({} chars), truncating to {}", description.length(), MAX_DESCRIPTION_LENGTH);
                        description = description.substring(0, MAX_DESCRIPTION_LENGTH);
                    }
                    if (merchantName != null && merchantName.length() > MAX_MERCHANT_NAME_LENGTH) {
                        logger.warn("Merchant name too long ({} chars), truncating to {}", merchantName.length(), MAX_MERCHANT_NAME_LENGTH);
                        merchantName = merchantName.substring(0, MAX_MERCHANT_NAME_LENGTH);
                    }
                    
                    BigDecimal amount = null;
                    if (txMap.get("amount") != null) {
                        if (txMap.get("amount") instanceof Number) {
                            amount = BigDecimal.valueOf(((Number) txMap.get("amount")).doubleValue());
                        } else if (txMap.get("amount") instanceof String) {
                            try {
                                amount = new BigDecimal(txMap.get("amount").toString());
                            } catch (NumberFormatException e) {
                                logger.warn("Invalid amount format: {}", txMap.get("amount"));
                            }
                        }
                    }
                    
                    // BOUNDARY CHECK: Validate amount range (prevent extreme values)
                    final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99"); // ~1 billion
                    final BigDecimal MIN_AMOUNT = new BigDecimal("-999999999.99");
                    if (amount != null) {
                        if (amount.compareTo(MAX_AMOUNT) > 0 || amount.compareTo(MIN_AMOUNT) < 0) {
                            logger.warn("Amount out of valid range: {}, skipping transaction", amount);
                            recalculatedTransactions.add(txMap);
                            continue;
                        }
                    }
                    
                    String paymentChannel = txMap.get("paymentChannel") != null ? txMap.get("paymentChannel").toString() : null;
                    String importerCategoryPrimary = txMap.get("importerCategoryPrimary") != null ? 
                        txMap.get("importerCategoryPrimary").toString() : null;
                    String importerCategoryDetailed = txMap.get("importerCategoryDetailed") != null ? 
                        txMap.get("importerCategoryDetailed").toString() : null;
                    String transactionTypeIndicator = txMap.get("transactionTypeIndicator") != null ? 
                        txMap.get("transactionTypeIndicator").toString() : null;

                    if (amount == null) {
                        // Skip transactions without valid amount
                        logger.debug("Skipping transaction without valid amount");
                        recalculatedTransactions.add(txMap);
                        continue;
                    }

                    // Recalculate category using unified service
                    TransactionTypeCategoryService.CategoryResult categoryResult = 
                        transactionTypeCategoryService.determineCategory(
                            importerCategoryPrimary,
                            importerCategoryDetailed,
                            tempAccount,
                            merchantName,
                            description,
                            amount,
                            paymentChannel,
                            transactionTypeIndicator,
                            request.getImportSource() != null ? request.getImportSource() : "CSV"
                        );

                    // Recalculate type using unified service
                    TransactionTypeCategoryService.TypeResult typeResult = 
                        transactionTypeCategoryService.determineTransactionType(
                            tempAccount,
                            categoryResult != null ? categoryResult.getCategoryPrimary() : null,
                            categoryResult != null ? categoryResult.getCategoryDetailed() : null,
                            amount,
                            transactionTypeIndicator,
                            description,
                            paymentChannel
                        );

                    // Create updated transaction map
                    Map<String, Object> updatedTx = new HashMap<>(txMap);
                    if (categoryResult != null) {
                        updatedTx.put("categoryPrimary", categoryResult.getCategoryPrimary());
                        updatedTx.put("categoryDetailed", categoryResult.getCategoryDetailed());
                    }
                    if (typeResult != null) {
                        updatedTx.put("transactionType", typeResult.getTransactionType().name());
                    }

                    recalculatedTransactions.add(updatedTx);
                } catch (Exception e) {
                    logger.warn("Failed to recalculate transaction, keeping original: {}", e.getMessage());
                    recalculatedTransactions.add(txMap); // Keep original on error
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("transactions", recalculatedTransactions);
            response.put("accountType", request.getAccountType());
            response.put("accountSubtype", request.getAccountSubtype());

            logger.info("Recalculated {} transactions with account type: {}", 
                recalculatedTransactions.size(), request.getAccountType());

            return ResponseEntity.ok(response);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Preview recalculation failed: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to recalculate preview: " + e.getMessage());
        }
    }

    // MARK: - Helper Methods

    private Map<String, Object> buildTransactionMap(
            CSVImportService.ParsedTransaction parsed,
            List<DuplicateDetectionService.DuplicateMatch> duplicateMatches) {
        Map<String, Object> txMap = buildTransactionMapInternal(
                parsed.getDate(),
                parsed.getAmount(),
                parsed.getDescription(),
                parsed.getMerchantName(),
                parsed.getCategoryPrimary(),
                parsed.getCategoryDetailed(),
                parsed.getCurrencyCode(),
                parsed.getPaymentChannel(),
                parsed.getTransactionType(),
                duplicateMatches,
                null // userName not available in CSV imports
        );
        // Add importer category fields for preview
        if (parsed.getImporterCategoryPrimary() != null) {
            txMap.put("importerCategoryPrimary", parsed.getImporterCategoryPrimary());
        }
        if (parsed.getImporterCategoryDetailed() != null) {
            txMap.put("importerCategoryDetailed", parsed.getImporterCategoryDetailed());
        }
        // Add transactionTypeIndicator for recalculation
        if (parsed.getTransactionTypeIndicator() != null) {
            txMap.put("transactionTypeIndicator", parsed.getTransactionTypeIndicator());
        }
        return txMap;
    }

    private Map<String, Object> buildPDFTransactionMap(
            PDFImportService.ParsedTransaction parsed,
            List<DuplicateDetectionService.DuplicateMatch> duplicateMatches) {
        Map<String, Object> txMap = buildTransactionMapInternal(
                parsed.getDate(),
                parsed.getAmount(),
                parsed.getDescription(),
                parsed.getMerchantName(),
                parsed.getCategoryPrimary(),
                parsed.getCategoryDetailed(),
                parsed.getCurrencyCode(),
                parsed.getPaymentChannel(),
                parsed.getTransactionType(),
                duplicateMatches,
                parsed.getUserName() // userName (card/account user from PDF User field)
        );
        // Add importer category fields for preview
        if (parsed.getImporterCategoryPrimary() != null) {
            txMap.put("importerCategoryPrimary", parsed.getImporterCategoryPrimary());
        }
        if (parsed.getImporterCategoryDetailed() != null) {
            txMap.put("importerCategoryDetailed", parsed.getImporterCategoryDetailed());
        }
        return txMap;
    }

    private Map<String, Object> buildTransactionMapInternal(
            LocalDate date,
            BigDecimal amount,
            String description,
            String merchantName,
            String categoryPrimary,
            String categoryDetailed,
            String currencyCode,
            String paymentChannel,
            String transactionType,
            List<DuplicateDetectionService.DuplicateMatch> duplicateMatches,
            String userName) {
        Map<String, Object> txMap = new HashMap<>();
        
        // CRITICAL: Null safety and validation for all fields
        // Date: Convert to string, null if invalid
        txMap.put("date", date != null ? date.toString() : null);
        
        // Amount: Validate and handle null/zero
        if (amount != null) {
            // CRITICAL: Validate amount is reasonable (prevent overflow issues)
            BigDecimal maxAmount = BigDecimal.valueOf(1_000_000_000);
            BigDecimal minAmount = BigDecimal.valueOf(-1_000_000_000);
            if (amount.compareTo(maxAmount) > 0 || amount.compareTo(minAmount) < 0) {
                logger.warn("buildTransactionMapInternal: Amount out of reasonable range: {}, using null", amount);
                txMap.put("amount", null);
            } else {
                txMap.put("amount", amount);
            }
        } else {
            txMap.put("amount", null);
        }
        
        // Description: Normalize empty strings to null for consistency
        txMap.put("description", description != null && !description.trim().isEmpty() ? description.trim() : null);
        
        // Merchant name: Normalize empty strings to null
        txMap.put("merchantName", merchantName != null && !merchantName.trim().isEmpty() ? merchantName.trim() : null);
        
        // User name: Card/account user (family member who made the transaction)
        txMap.put("userName", userName != null && !userName.trim().isEmpty() ? userName.trim() : null);
        
        // Category: Ensure we always have a valid category (never null)
        // CRITICAL: Default to "other" if category is null/empty to prevent downstream errors
        String safeCategoryPrimary = categoryPrimary != null && !categoryPrimary.trim().isEmpty() 
            ? categoryPrimary.trim() : "other";
        String safeCategoryDetailed = categoryDetailed != null && !categoryDetailed.trim().isEmpty() 
            ? categoryDetailed.trim() : safeCategoryPrimary; // Default to primary if detailed is null
        txMap.put("categoryPrimary", safeCategoryPrimary);
        txMap.put("categoryDetailed", safeCategoryDetailed);
        
        // Currency code: Normalize empty strings to null
        txMap.put("currencyCode", currencyCode != null && !currencyCode.trim().isEmpty() ? currencyCode.trim() : null);
        
        // Payment channel: Normalize empty strings to null
        txMap.put("paymentChannel", paymentChannel != null && !paymentChannel.trim().isEmpty() ? paymentChannel.trim() : null);
        
        // Transaction type: Ensure we always have a valid type (never null)
        // CRITICAL: Default to "EXPENSE" if type is null/empty (most common type)
        String safeTransactionType = transactionType != null && !transactionType.trim().isEmpty() 
            ? transactionType.trim().toUpperCase() : "EXPENSE";
        // Validate transaction type is one of the expected values
        if (!safeTransactionType.equals("INCOME") && !safeTransactionType.equals("EXPENSE") &&
            !safeTransactionType.equals("INVESTMENT") && !safeTransactionType.equals("LOAN")) {
            logger.warn("buildTransactionMapInternal: Invalid transaction type '{}', defaulting to 'EXPENSE'", transactionType);
            safeTransactionType = "EXPENSE";
        }
        txMap.put("transactionType", safeTransactionType);
        
        // Selected: Default to true, but unselect if duplicates found
        // CRITICAL FIX: An empty list in duplicateMatches means exact match (shouldSkip = true)
        // A non-empty list means fuzzy matches (similarity >= threshold)
        // Both should be marked as duplicates
        boolean hasDuplicates = duplicateMatches != null;
        // CRITICAL: Duplicates should be unselected by default (user must manually select them)
        txMap.put("selected", !hasDuplicates);
        
        // Duplicate information
        txMap.put("hasDuplicates", hasDuplicates);
        if (hasDuplicates && duplicateMatches != null) {
            if (!duplicateMatches.isEmpty()) {
                // Fuzzy matches - use the best match
                DuplicateDetectionService.DuplicateMatch bestMatch = duplicateMatches.get(0);
                // CRITICAL: Validate similarity and reason are not null
                Double similarity = bestMatch.getSimilarity();
                String reason = bestMatch.getMatchReason();
                txMap.put("duplicateSimilarity", similarity != null ? similarity : 1.0);
                txMap.put("duplicateReason", reason != null && !reason.trim().isEmpty() ? reason.trim() : "Exact match");
            } else {
                // Empty list = exact match (shouldSkip = true) - mark as duplicate with high similarity
                txMap.put("duplicateSimilarity", 1.0);
                txMap.put("duplicateReason", "Exact match");
            }
        } else {
            txMap.put("duplicateSimilarity", null);
            txMap.put("duplicateReason", null);
        }
        
        return txMap;
    }

    private ResponseEntity<BatchImportResponse> processBatchImport(
            UserTable user,
            List<CSVImportService.ParsedTransaction> transactions,
            String importSource,
            String fileName) {
        String batchId = UUID.randomUUID().toString();
        int created = 0;
        int failed = 0;
        int duplicates = 0;
        
        // CRITICAL: Detect duplicates before processing
        List<DuplicateDetectionService.ParsedTransaction> parsedForDuplicateCheck = new ArrayList<>();
        for (CSVImportService.ParsedTransaction parsed : transactions) {
            DuplicateDetectionService.ParsedTransaction dupTx = new DuplicateDetectionService.ParsedTransaction(
                    parsed.getDate(),
                    parsed.getAmount(),
                    parsed.getDescription(),
                    parsed.getMerchantName()
            );
            dupTx.setTransactionId(parsed.getTransactionId());
            parsedForDuplicateCheck.add(dupTx);
        }
        
        Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicateMap = 
                duplicateDetectionService.detectDuplicates(user.getUserId(), parsedForDuplicateCheck);
        
        // CRITICAL: Process all transactions in batches to handle large imports (up to 10,000)
        // Batch size of 500 to balance performance and memory usage (reduced from 1000 for better progress feedback)
        final int BATCH_SIZE = 500;
        final int totalTransactions = transactions.size();
        
        logger.info("📦 Processing {} transactions in batches of {} for {} import", 
                totalTransactions, BATCH_SIZE, importSource);
        
        for (int i = 0; i < totalTransactions; i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, totalTransactions);
            List<CSVImportService.ParsedTransaction> batch = transactions.subList(i, endIndex);
            int batchNumber = (i / BATCH_SIZE) + 1;
            int totalBatches = (int) Math.ceil((double) totalTransactions / BATCH_SIZE);
            
            logger.info("📦 Processing batch {}/{}: transactions {} to {} ({} transactions)", 
                    batchNumber, totalBatches, i + 1, endIndex, batch.size());
            
            int batchCreated = 0;
            int batchFailed = 0;
            int batchDuplicates = 0;
            
            for (int j = 0; j < batch.size(); j++) {
                CSVImportService.ParsedTransaction parsed = batch.get(j);
                int transactionIndex = i + j;
                
                // Check if this transaction is a duplicate
                // CRITICAL: A transaction is a duplicate if:
                // 1. It's in the duplicateMap (exact match, same ID, or fuzzy match), OR
                // 2. The duplicateMap contains an empty list for this index (exact match marker)
                List<DuplicateDetectionService.DuplicateMatch> duplicateMatches = duplicateMap.get(transactionIndex);
                boolean isDuplicate = duplicateMap.containsKey(transactionIndex); // Check if transaction is in map (exact match or fuzzy match)
                
                if (isDuplicate) {
                    if (duplicateMatches == null || duplicateMatches.isEmpty()) {
                        logger.info("⏭️ Skipping exact duplicate transaction (index {}): merchant='{}', description='{}', amount={}, date={}", 
                                transactionIndex, parsed.getMerchantName(), parsed.getDescription(), parsed.getAmount(), parsed.getDate());
                    } else {
                        logger.info("⏭️ Skipping fuzzy duplicate transaction (index {}): merchant='{}', description='{}', amount={}, date={} (similarity: {})", 
                                transactionIndex, parsed.getMerchantName(), parsed.getDescription(), parsed.getAmount(), parsed.getDate(),
                                duplicateMatches.get(0).getSimilarity());
                    }
                    batchDuplicates++;
                    duplicates++;
                    continue; // Skip duplicate transactions
                }
                
                try {
                    // Log category assignment for import (only for first few and last few in each batch to avoid log spam)
                    if (j < 3 || j >= batch.size() - 3) {
                        logger.debug("💾 {} Import: merchant='{}', description='{}', amount={}, category='{}'", 
                                importSource, parsed.getMerchantName(), parsed.getDescription(), parsed.getAmount(), parsed.getCategoryPrimary());
                    }
                    transactionService.createTransaction(
                            user,
                            parsed.getAccountId(), // May be null - TransactionService will use pseudo account
                            parsed.getAmount(),
                            parsed.getDate(),
                            parsed.getDescription(),
                            parsed.getCategoryPrimary(),
                            parsed.getCategoryDetailed(),
                            parsed.getImporterCategoryPrimary(), // Importer category (from parser)
                            parsed.getImporterCategoryDetailed(), // Importer category (from parser)
                            parsed.getTransactionId(),
                            null, // notes
                            null, // plaidAccountId
                            null, // plaidTransactionId
                            parsed.getTransactionType(),
                            parsed.getCurrencyCode(),
                            importSource,
                            batchId,
                            fileName,
                            null, // reviewStatus
                            parsed.getMerchantName(), // merchantName (where purchase was made)
                            parsed.getPaymentChannel(), // paymentChannel
                            null, // userName (not available in CSV/Excel imports)
                            null // goalId
                    );
                    batchCreated++;
                    created++;
                } catch (Exception e) {
                    logger.error("Failed to create transaction from import: {}", e.getMessage(), e);
                    batchFailed++;
                    failed++;
                }
            }
            
            logger.info("✅ Batch {}/{} completed: {} created, {} failed, {} duplicates (total so far: {} created, {} failed, {} duplicates)", 
                    batchNumber, totalBatches, batchCreated, batchFailed, batchDuplicates, created, failed, duplicates);
        }

        BatchImportResponse response = new BatchImportResponse();
        response.setTotal(totalTransactions);
        response.setCreated(created);
        response.setFailed(failed);
        response.setDuplicates(duplicates);
        
        logger.info("🎉 Import completed: {} total transactions, {} created, {} failed, {} duplicates", 
                totalTransactions, created, failed, duplicates);
        
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<BatchImportResponse> processPDFBatchImport(
            UserTable user,
            List<PDFImportService.ParsedTransaction> transactions,
            String importSource,
            String fileName) {
        String batchId = UUID.randomUUID().toString();
        int created = 0;
        int failed = 0;
        
        // CRITICAL: Process all transactions in batches to handle large imports (up to 10,000)
        // Batch size of 500 to balance performance and memory usage (reduced from 1000 for better progress feedback)
        final int BATCH_SIZE = 500;
        final int totalTransactions = transactions.size();
        
        logger.info("📦 Processing {} transactions in batches of {} for {} import", 
                totalTransactions, BATCH_SIZE, importSource);
        
        for (int i = 0; i < totalTransactions; i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, totalTransactions);
            List<PDFImportService.ParsedTransaction> batch = transactions.subList(i, endIndex);
            int batchNumber = (i / BATCH_SIZE) + 1;
            int totalBatches = (int) Math.ceil((double) totalTransactions / BATCH_SIZE);
            
            logger.info("📦 Processing batch {}/{}: transactions {} to {} ({} transactions)", 
                    batchNumber, totalBatches, i + 1, endIndex, batch.size());
            
            int batchCreated = 0;
            int batchFailed = 0;
            
            for (PDFImportService.ParsedTransaction parsed : batch) {
                try {
                    // CRITICAL: Log amount before creating transaction to track sign preservation
                    logger.info("📥 [PDF Import] Creating transaction: description='{}', parsedAmount={}, transactionType='{}', category='{}'", 
                            parsed.getDescription(), parsed.getAmount(), parsed.getTransactionType(), parsed.getCategoryPrimary());
                    
                    TransactionTable createdTx = transactionService.createTransaction(
                            user,
                            parsed.getAccountId(), // May be null - TransactionService will use pseudo account
                            parsed.getAmount(),
                            parsed.getDate(),
                            parsed.getDescription(),
                            parsed.getCategoryPrimary(),
                            parsed.getCategoryDetailed(),
                            parsed.getImporterCategoryPrimary(), // Importer category (from parser)
                            parsed.getImporterCategoryDetailed(), // Importer category (from parser)
                            parsed.getTransactionId(),
                            null, // notes
                            null, // plaidAccountId
                            null, // plaidTransactionId
                            parsed.getTransactionType(),
                            parsed.getCurrencyCode(),
                            importSource,
                            batchId,
                            fileName,
                            null, // reviewStatus
                            parsed.getMerchantName(), // merchantName (where purchase was made)
                            parsed.getPaymentChannel(), // paymentChannel
                            parsed.getUserName(), // userName (card/account user - family member)
                            null // goalId
                    );
                    
                    // CRITICAL: Log amount after creation to verify sign preservation
                    logger.info("✅ [PDF Import] Transaction created: transactionId='{}', storedAmount={}, parsedAmount={}, match={}", 
                            createdTx.getTransactionId(), createdTx.getAmount(), parsed.getAmount(), 
                            createdTx.getAmount().compareTo(parsed.getAmount()) == 0 ? "✅" : "❌ MISMATCH");
                    
                    batchCreated++;
                    created++;
                } catch (Exception e) {
                    logger.error("Failed to create transaction from import: {}", e.getMessage(), e);
                    batchFailed++;
                    failed++;
                }
            }
            
            logger.info("✅ Batch {}/{} completed: {} created, {} failed (total so far: {} created, {} failed)", 
                    batchNumber, totalBatches, batchCreated, batchFailed, created, failed);
        }

        BatchImportResponse response = new BatchImportResponse();
        response.setTotal(totalTransactions);
        response.setCreated(created);
        response.setFailed(failed);
        
        logger.info("🎉 Import completed: {} total transactions, {} created, {} failed", 
                totalTransactions, created, failed);
        
        return ResponseEntity.ok(response);
    }

    // DTOs
    public static class CreateTransactionRequest {
        private String transactionId; // Optional: If provided, use this ID (for app-backend ID consistency)
        private String accountId;
        
        @jakarta.validation.constraints.NotNull(message = "Amount is required")
        @jakarta.validation.constraints.DecimalMin(value = "-999999999.99", message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        @jakarta.validation.constraints.DecimalMax(value = "999999999.99", message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        private BigDecimal amount;
        
        @jakarta.validation.constraints.NotNull(message = "Transaction date is required")
        @jakarta.validation.constraints.PastOrPresent(message = "Transaction date cannot be in the future")
        private LocalDate transactionDate;
        
        @jakarta.validation.constraints.Size(max = 500, message = "Description cannot exceed 500 characters")
        private String description;
        
        @jakarta.validation.constraints.NotBlank(message = "Category primary is required")
        @jakarta.validation.constraints.Size(max = 100, message = "Category primary cannot exceed 100 characters")
        private String categoryPrimary; // Primary category (required)
        
        @jakarta.validation.constraints.Size(max = 100, message = "Category detailed cannot exceed 100 characters")
        private String categoryDetailed; // Detailed category (optional, defaults to primary if not provided)
        
        @jakarta.validation.constraints.Size(max = 1000, message = "Notes cannot exceed 1000 characters")
        private String notes; // Optional: User notes for the transaction
        
        @jakarta.validation.constraints.Size(max = 100, message = "Plaid account ID cannot exceed 100 characters")
        private String plaidAccountId; // Optional: Plaid account ID for fallback lookup if accountId not found
        
        @jakarta.validation.constraints.Size(max = 100, message = "Plaid transaction ID cannot exceed 100 characters")
        private String plaidTransactionId; // Optional: Plaid transaction ID for fallback lookup and ID consistency
        
        @jakarta.validation.constraints.Pattern(regexp = "^(INCOME|INVESTMENT|LOAN|EXPENSE)?$", message = "Transaction type must be INCOME, INVESTMENT, LOAN, or EXPENSE")
        private String transactionType; // Optional: User-selected transaction type (INCOME, INVESTMENT, LOAN, EXPENSE). If not provided, backend will calculate it.
        
        @jakarta.validation.constraints.Pattern(regexp = "^[A-Z]{3}$", message = "Currency code must be a 3-letter ISO code (e.g., USD, INR)")
        private String currencyCode; // Optional: Currency code (e.g., "USD", "INR")
        
        @jakarta.validation.constraints.Pattern(regexp = "^(CSV|Excel|PDF)?$", message = "Import source must be CSV, Excel, or PDF")
        private String importSource; // Optional: Import source (e.g., "CSV", "Excel", "PDF")
        
        @jakarta.validation.constraints.Size(max = 100, message = "Import batch ID cannot exceed 100 characters")
        private String importBatchId; // Optional: Batch ID for grouped imports
        
        @jakarta.validation.constraints.Size(max = 255, message = "Import file name cannot exceed 255 characters")
        private String importFileName; // Optional: Original file name for imports
        
        @jakarta.validation.constraints.Pattern(regexp = "^(none|flagged|reviewed|error)?$", message = "Review status must be none, flagged, reviewed, or error")
        private String reviewStatus; // Optional: review status ("none", "flagged", "reviewed", "error")
        
        @jakarta.validation.constraints.Size(max = 200, message = "Merchant name cannot exceed 200 characters")
        private String merchantName; // Optional: Merchant name (where purchase was made, e.g., "Amazon", "Starbucks")
        
        @jakarta.validation.constraints.Size(max = 50, message = "Payment channel cannot exceed 50 characters")
        private String paymentChannel; // Optional: Payment channel (online, in_store, ach, etc.)
        
        @jakarta.validation.constraints.Size(max = 100, message = "User name cannot exceed 100 characters")                                                    
        private String userName; // Optional: Card/account user name (family member who made the transaction)
        
        @jakarta.validation.constraints.Size(max = 100, message = "Goal ID cannot exceed 100 characters")
        private String goalId; // Optional: Goal this transaction contributes to

        // Getters and setters
        public String getTransactionId() { return transactionId; }
        public void setTransactionId(final String transactionId) { this.transactionId = transactionId; }
        public String getAccountId() { return accountId; }
        public void setAccountId(final String accountId) { this.accountId = accountId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(final BigDecimal amount) { this.amount = amount; }
        public LocalDate getTransactionDate() { return transactionDate; }
        public void setTransactionDate(final LocalDate transactionDate) { this.transactionDate = transactionDate; }
        public String getDescription() { return description; }
        public void setDescription(final String description) { this.description = description; }
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(final String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(final String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        public String getNotes() { return notes; }
        public void setNotes(final String notes) { this.notes = notes; }
        
        public String getPlaidAccountId() { return plaidAccountId; }
        public void setPlaidAccountId(final String plaidAccountId) { this.plaidAccountId = plaidAccountId; }
        
        public String getPlaidTransactionId() { return plaidTransactionId; }
        public void setPlaidTransactionId(final String plaidTransactionId) { this.plaidTransactionId = plaidTransactionId; }
        
        public String getTransactionType() { return transactionType; }
        public void setTransactionType(final String transactionType) { this.transactionType = transactionType; }
        
        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(final String currencyCode) { this.currencyCode = currencyCode; }
        
        public String getImportSource() { return importSource; }
        public void setImportSource(final String importSource) { this.importSource = importSource; }
        
        public String getImportBatchId() { return importBatchId; }
        public void setImportBatchId(final String importBatchId) { this.importBatchId = importBatchId; }
        
        public String getImportFileName() { return importFileName; }
        public void setImportFileName(final String importFileName) { this.importFileName = importFileName; }
        
        public String getReviewStatus() { return reviewStatus; }
        public void setReviewStatus(final String reviewStatus) { this.reviewStatus = reviewStatus; }
        
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(final String merchantName) { this.merchantName = merchantName; }
        
        public String getPaymentChannel() { return paymentChannel; }
        public void setPaymentChannel(final String paymentChannel) { this.paymentChannel = paymentChannel; }
        
        public String getUserName() { return userName; }                                                  
        public void setUserName(final String userName) { this.userName = userName; }
        
        public String getGoalId() { return goalId; }
        public void setGoalId(final String goalId) { this.goalId = goalId; }
    }

    public static class TotalSpendingResponse {
        private BigDecimal total;

        public TotalSpendingResponse(final BigDecimal total) {
            this.total = total;
        }

        public BigDecimal getTotal() { return total; }
        public void setTotal(final BigDecimal total) { this.total = total; }
    }

    public static class UpdateTransactionRequest {
        @jakarta.validation.constraints.DecimalMin(value = "-999999999.99", message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        @jakarta.validation.constraints.DecimalMax(value = "999999999.99", message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        private BigDecimal amount; // Optional: transaction amount (for type changes)
        
        @jakarta.validation.constraints.Size(max = 1000, message = "Notes cannot exceed 1000 characters")
        private String notes;
        
        @jakarta.validation.constraints.Size(max = 100, message = "Category primary cannot exceed 100 characters")
        private String categoryPrimary; // Optional: override primary category
        
        @jakarta.validation.constraints.Size(max = 100, message = "Category detailed cannot exceed 100 characters")
        private String categoryDetailed; // Optional: override detailed category
        
        @jakarta.validation.constraints.Size(max = 100, message = "Plaid transaction ID cannot exceed 100 characters")
        private String plaidTransactionId; // Optional: for fallback lookup if transactionId not found
        
        @jakarta.validation.constraints.Pattern(regexp = "^(none|flagged|reviewed|error)?$", message = "Review status must be none, flagged, reviewed, or error")
        private String reviewStatus; // Optional: review status ("none", "flagged", "reviewed", "error")
        
        private Boolean isHidden; // Optional: whether transaction is hidden from view
        
        @jakarta.validation.constraints.Pattern(regexp = "^(INCOME|INVESTMENT|LOAN|EXPENSE)?$", message = "Transaction type must be INCOME, INVESTMENT, LOAN, or EXPENSE")                                          
        private String transactionType; // Optional: User-selected transaction type (INCOME, INVESTMENT, LOAN, EXPENSE). If not provided, backend will calculate it.
        
        @jakarta.validation.constraints.Size(max = 100, message = "Goal ID cannot exceed 100 characters")
        private String goalId; // Optional: Goal this transaction contributes to

        public BigDecimal getAmount() { return amount; }
        public void setAmount(final BigDecimal amount) { this.amount = amount; }
        
        public String getNotes() { return notes; }
        public void setNotes(final String notes) { this.notes = notes; }
        
        public String getCategoryPrimary() { return categoryPrimary; }
        public void setCategoryPrimary(final String categoryPrimary) { this.categoryPrimary = categoryPrimary; }
        
        public String getCategoryDetailed() { return categoryDetailed; }
        public void setCategoryDetailed(final String categoryDetailed) { this.categoryDetailed = categoryDetailed; }
        
        public String getPlaidTransactionId() { return plaidTransactionId; }
        public void setPlaidTransactionId(final String plaidTransactionId) { this.plaidTransactionId = plaidTransactionId; }
        
        public String getReviewStatus() { return reviewStatus; }
        public void setReviewStatus(final String reviewStatus) { this.reviewStatus = reviewStatus; }
        
        public Boolean getIsHidden() { return isHidden; }
        public void setIsHidden(final Boolean isHidden) { this.isHidden = isHidden; }
        
        public String getTransactionType() { return transactionType; }                                    
        public void setTransactionType(final String transactionType) { this.transactionType = transactionType; }
        
        public String getGoalId() { return goalId; }
        public void setGoalId(final String goalId) { this.goalId = goalId; }
    }

    public static class VerifyTransactionRequest {
        @jakarta.validation.constraints.NotBlank(message = "Transaction ID is required")
        @jakarta.validation.constraints.Size(max = 100, message = "Transaction ID cannot exceed 100 characters")
        private String transactionId;
        
        @jakarta.validation.constraints.Size(max = 100, message = "Plaid transaction ID cannot exceed 100 characters")
        private String plaidTransactionId; // Optional: Plaid transaction ID in body for fallback lookup

        public String getTransactionId() { return transactionId; }
        public void setTransactionId(final String transactionId) { this.transactionId = transactionId; }
        
        public String getPlaidTransactionId() { return plaidTransactionId; }
        public void setPlaidTransactionId(final String plaidTransactionId) { this.plaidTransactionId = plaidTransactionId; }
    }

    public static class RecalculatePreviewRequest {
        private List<Map<String, Object>> transactions;
        private String accountType;
        private String accountSubtype;
        private String institutionName;
        private String importSource; // CSV, EXCEL, PDF

        public List<Map<String, Object>> getTransactions() { return transactions; }
        public void setTransactions(List<Map<String, Object>> transactions) { this.transactions = transactions; }
        public String getAccountType() { return accountType; }
        public void setAccountType(String accountType) { this.accountType = accountType; }
        public String getAccountSubtype() { return accountSubtype; }
        public void setAccountSubtype(String accountSubtype) { this.accountSubtype = accountSubtype; }
        public String getInstitutionName() { return institutionName; }
        public void setInstitutionName(String institutionName) { this.institutionName = institutionName; }
        public String getImportSource() { return importSource; }
        public void setImportSource(String importSource) { this.importSource = importSource; }
    }

    // MARK: - Import Response DTOs

    // Shared DetectedAccountInfo class for use across import responses and tests
    public static class DetectedAccountInfo {
        private String accountName;
        private String institutionName;
        private String accountType;
        private String accountSubtype;
        private String accountNumber;
        private String cardNumber; // Card number for credit cards (detected but separate from account number)
        private String matchedAccountId;
        private java.math.BigDecimal balance; // Detected balance from statement/import
        
        // Credit card statement metadata (from PDF imports)
        private java.time.LocalDate paymentDueDate; // Payment due date extracted from statement
        private java.math.BigDecimal minimumPaymentDue; // Minimum payment due amount
        private Long rewardPoints; // Reward points (0 to 10 million)

        public String getAccountName() { return accountName; }
        public void setAccountName(String accountName) { this.accountName = accountName; }
        public String getInstitutionName() { return institutionName; }
        public void setInstitutionName(String institutionName) { this.institutionName = institutionName; }
        public String getAccountType() { return accountType; }
        public void setAccountType(String accountType) { this.accountType = accountType; }
        public String getAccountSubtype() { return accountSubtype; }
        public void setAccountSubtype(String accountSubtype) { this.accountSubtype = accountSubtype; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        public String getCardNumber() { return cardNumber; }
        public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }
        public String getMatchedAccountId() { return matchedAccountId; }
        public void setMatchedAccountId(String matchedAccountId) { this.matchedAccountId = matchedAccountId; }
        
        public java.math.BigDecimal getBalance() { return balance; }
        public void setBalance(java.math.BigDecimal balance) { this.balance = balance; }
        
        public java.time.LocalDate getPaymentDueDate() { return paymentDueDate; }
        public void setPaymentDueDate(java.time.LocalDate paymentDueDate) { this.paymentDueDate = paymentDueDate; }
        
        public java.math.BigDecimal getMinimumPaymentDue() { return minimumPaymentDue; }
        public void setMinimumPaymentDue(java.math.BigDecimal minimumPaymentDue) { this.minimumPaymentDue = minimumPaymentDue; }
        
        public Long getRewardPoints() { return rewardPoints; }
        public void setRewardPoints(Long rewardPoints) { this.rewardPoints = rewardPoints; }
    }

    public static class CSVImportPreviewResponse {
        private int totalParsed;
        private List<Map<String, Object>> transactions;
        private DetectedAccountInfo detectedAccount;
        // P1: Pagination fields
        private int page;
        private int size;
        private int totalPages;
        private int totalElements;

        public int getTotalParsed() { return totalParsed; }
        public void setTotalParsed(int totalParsed) { this.totalParsed = totalParsed; }
        public List<Map<String, Object>> getTransactions() { return transactions; }
        public void setTransactions(List<Map<String, Object>> transactions) { this.transactions = transactions; }
        public DetectedAccountInfo getDetectedAccount() { return detectedAccount; }
        public void setDetectedAccount(DetectedAccountInfo detectedAccount) { this.detectedAccount = detectedAccount; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
        public int getTotalElements() { return totalElements; }
        public void setTotalElements(int totalElements) { this.totalElements = totalElements; }
    }

    public static class ExcelImportPreviewResponse {
        private int totalParsed;
        private List<Map<String, Object>> transactions;
        private DetectedAccountInfo detectedAccount;
        // P1: Pagination fields (for consistency with CSV preview)
        private int page;
        private int size;
        private int totalPages;
        private int totalElements;

        public int getTotalParsed() { return totalParsed; }
        public void setTotalParsed(int totalParsed) { this.totalParsed = totalParsed; }
        public List<Map<String, Object>> getTransactions() { return transactions; }
        public void setTransactions(List<Map<String, Object>> transactions) { this.transactions = transactions; }
        public DetectedAccountInfo getDetectedAccount() { return detectedAccount; }
        public void setDetectedAccount(DetectedAccountInfo detectedAccount) { this.detectedAccount = detectedAccount; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
        public int getTotalElements() { return totalElements; }
        public void setTotalElements(int totalElements) { this.totalElements = totalElements; }
    }

    public static class PDFImportPreviewResponse {
        private int totalParsed;
        private List<Map<String, Object>> transactions;
        private DetectedAccountInfo detectedAccount;
        // P1: Pagination fields (for consistency with CSV preview)
        private int page;
        private int size;
        private int totalPages;
        private int totalElements;

        public int getTotalParsed() { return totalParsed; }
        public void setTotalParsed(int totalParsed) { this.totalParsed = totalParsed; }
        public List<Map<String, Object>> getTransactions() { return transactions; }
        public void setTransactions(List<Map<String, Object>> transactions) { this.transactions = transactions; }
        public DetectedAccountInfo getDetectedAccount() { return detectedAccount; }
        public void setDetectedAccount(DetectedAccountInfo detectedAccount) { this.detectedAccount = detectedAccount; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
        public int getTotalElements() { return totalElements; }
        public void setTotalElements(int totalElements) { this.totalElements = totalElements; }
    }
    
    /**
     * Auto-create account if detected during import but not matched to existing account
     * 
     * @param user The user
     * @param detectedAccount The detected account information
     * @return The account ID (newly created or existing)
     */
    /**
     * Auto-creates an account if detected during import.
     * Uses optimistic locking to prevent race conditions in concurrent imports.
     * 
     * Thread-safety: Uses account number + user ID as unique constraint to prevent duplicates.
     * If account already exists (by account number or name+institution), returns existing account ID.
     * 
     * @param user The user for whom to create the account
     * @param detectedAccount The detected account information
     * @return Account ID (existing or newly created), or null if creation fails
     */
    private String autoCreateAccountIfDetected(UserTable user, AccountDetectionService.DetectedAccount detectedAccount) {
        return autoCreateAccountIfDetected(user, detectedAccount, null);
    }
    
    /**
     * Auto-create account if detected, with optional metadata from PDF import
     * @param user User creating the account
     * @param detectedAccount Detected account information
     * @param importResult Optional PDF import result containing metadata (paymentDueDate, minimumPaymentDue, rewardPoints)
     * @return Account ID of created or existing account
     */
    private String autoCreateAccountIfDetected(UserTable user, AccountDetectionService.DetectedAccount detectedAccount, PDFImportService.ImportResult importResult) {
        if (detectedAccount == null) {
            logger.warn("⚠️ autoCreateAccountIfDetected: detectedAccount is null - cannot create account");
            return null;
        }
        
        // CRITICAL FIX: Check if all fields are null/empty BEFORE attempting to create account
        // If all fields are empty, create account with defaults instead of returning null
        boolean allFieldsNullOrEmpty = (detectedAccount.getInstitutionName() == null || detectedAccount.getInstitutionName().trim().isEmpty()) &&
                                      (detectedAccount.getAccountName() == null || detectedAccount.getAccountName().trim().isEmpty()) &&
                                      (detectedAccount.getAccountType() == null || detectedAccount.getAccountType().trim().isEmpty()) &&
                                      (detectedAccount.getAccountSubtype() == null || detectedAccount.getAccountSubtype().trim().isEmpty()) &&
                                      (detectedAccount.getAccountNumber() == null || detectedAccount.getAccountNumber().trim().isEmpty()) &&
                                      (detectedAccount.getMatchedAccountId() == null || detectedAccount.getMatchedAccountId().trim().isEmpty());
        
        if (allFieldsNullOrEmpty) {
            logger.info("⚠️ autoCreateAccountIfDetected: All detected account fields are null/empty - creating account with defaults.");
            // Continue to create account with defaults instead of returning null
        }
        
        logger.info("🔍 autoCreateAccountIfDetected: Starting account creation check - name='{}', institution='{}', type='{}', accountNumber='{}', matchedAccountId='{}'", 
                detectedAccount.getAccountName(), 
                detectedAccount.getInstitutionName(),
                detectedAccount.getAccountType(),
                detectedAccount.getAccountNumber() != null ? "***" + detectedAccount.getAccountNumber().substring(Math.max(0, detectedAccount.getAccountNumber().length() - 4)) : "null",
                detectedAccount.getMatchedAccountId());
        
        // CRITICAL: If matchedAccountId is provided, verify it exists and return it
        // This handles the case where account was already matched during import preview
        if (detectedAccount.getMatchedAccountId() != null && !detectedAccount.getMatchedAccountId().trim().isEmpty()) {
            // Verify the account exists and belongs to the user
            Optional<AccountTable> matchedAccount = accountRepository.findById(detectedAccount.getMatchedAccountId());
            if (matchedAccount.isPresent() && matchedAccount.get().getUserId().equals(user.getUserId())) {
                logger.info("✅ autoCreateAccountIfDetected: Using matched account ID: {}", detectedAccount.getMatchedAccountId());
                return detectedAccount.getMatchedAccountId();
            } else {
                logger.warn("⚠️ autoCreateAccountIfDetected: Matched account ID '{}' not found or doesn't belong to user - will create new account", 
                        detectedAccount.getMatchedAccountId());
                // Continue to create new account
            }
        }
        
        try {
            // CRITICAL: Check if account already exists (by account number or name)
            // This check + save operation should be atomic, but DynamoDB doesn't support transactions across tables
            // We use account number as a natural unique key to prevent duplicates
            List<AccountTable> existingAccounts = accountRepository.findByUserId(user.getUserId());
            logger.info("🔍 autoCreateAccountIfDetected: Checking {} existing accounts for user", 
                    existingAccounts != null ? existingAccounts.size() : 0);
            
            if (existingAccounts != null && !existingAccounts.isEmpty()) {
                for (AccountTable existing : existingAccounts) {
                    // Match by account number if available (most reliable)
                    // CRITICAL FIX: Normalize account numbers before comparison (handles hyphens, spaces, etc.)
                    if (detectedAccount.getAccountNumber() != null && 
                        !detectedAccount.getAccountNumber().trim().isEmpty() &&
                        existing.getAccountNumber() != null &&
                        normalizeAccountNumber(detectedAccount.getAccountNumber()).equals(normalizeAccountNumber(existing.getAccountNumber()))) {
                        logger.info("✅ autoCreateAccountIfDetected: Found existing account by account number: {} (name: '{}')", 
                                existing.getAccountId(), existing.getAccountName());
                        // Update balance with date comparison if balance and date are available
                        if (detectedAccount.getBalance() != null && detectedAccount.getBalanceDate() != null) {
                            boolean balanceUpdated = accountDetectionService.updateAccountBalanceWithDateComparison(
                                existing, detectedAccount.getBalance(), detectedAccount.getBalanceDate());
                            if (balanceUpdated) {
                                existing.setUpdatedAt(java.time.Instant.now());
                                accountRepository.save(existing);
                                logger.info("✅ Updated existing account balance with date comparison: {} (date: {})", 
                                    detectedAccount.getBalance(), detectedAccount.getBalanceDate());
                            }
                        }
                        return existing.getAccountId();
                    }
                    // Match by account name and institution (fallback)
                    if (detectedAccount.getAccountName() != null && 
                        detectedAccount.getInstitutionName() != null &&
                        existing.getAccountName() != null &&
                        existing.getInstitutionName() != null &&
                        existing.getAccountName().equals(detectedAccount.getAccountName()) &&
                        existing.getInstitutionName().equals(detectedAccount.getInstitutionName())) {
                        logger.info("✅ autoCreateAccountIfDetected: Found existing account by name and institution: {} (name: '{}', institution: '{}')", 
                                existing.getAccountId(), existing.getAccountName(), existing.getInstitutionName());
                        // Update balance with date comparison if balance and date are available
                        if (detectedAccount.getBalance() != null && detectedAccount.getBalanceDate() != null) {
                            boolean balanceUpdated = accountDetectionService.updateAccountBalanceWithDateComparison(
                                existing, detectedAccount.getBalance(), detectedAccount.getBalanceDate());
                            if (balanceUpdated) {
                                existing.setUpdatedAt(java.time.Instant.now());
                                accountRepository.save(existing);
                                logger.info("✅ Updated existing account balance with date comparison: {} (date: {})", 
                                    detectedAccount.getBalance(), detectedAccount.getBalanceDate());
                            }
                        }
                        return existing.getAccountId();
                    }
                }
                logger.info("🔍 autoCreateAccountIfDetected: No matching account found in {} existing accounts - will create new account", existingAccounts.size());
            } else {
                logger.info("🔍 autoCreateAccountIfDetected: No existing accounts found for user - will create new account");
            }
            
            // RACE CONDITION FIX: Use account number as unique identifier
            // If account number exists, try to find it again (another thread may have created it)
            if (detectedAccount.getAccountNumber() != null && !detectedAccount.getAccountNumber().trim().isEmpty()) {
                // Re-check after initial check (double-check locking pattern)
                // Note: This is not perfect for DynamoDB, but reduces race condition window
                List<AccountTable> recheckAccounts = accountRepository.findByUserId(user.getUserId());
                if (recheckAccounts != null) {
                    for (AccountTable existing : recheckAccounts) {
                        // CRITICAL FIX: Normalize account numbers before comparison
                        if (existing.getAccountNumber() != null &&
                            normalizeAccountNumber(existing.getAccountNumber()).equals(normalizeAccountNumber(detectedAccount.getAccountNumber()))) {
                            logger.info("📝 Account created by another thread, using existing: {}", existing.getAccountId());
                            return existing.getAccountId();
                        }
                    }
                }
            }
            
            // Create new account
            logger.info("🔨 autoCreateAccountIfDetected: Creating new account - name='{}', institution='{}', type='{}'", 
                    detectedAccount.getAccountName(), 
                    detectedAccount.getInstitutionName(),
                    detectedAccount.getAccountType());
            
            AccountTable newAccount = new AccountTable();
            newAccount.setAccountId(UUID.randomUUID().toString().toLowerCase());
            newAccount.setUserId(user.getUserId());
            
            // Set balance from detected account if available, with date comparison
            if (detectedAccount.getBalance() != null) {
                newAccount.setBalance(detectedAccount.getBalance());
                // Set balance date if available
                if (detectedAccount.getBalanceDate() != null) {
                    newAccount.setBalanceDate(detectedAccount.getBalanceDate());
                    logger.info("✅ Set account balance from detected account: {} (date: {})", 
                        detectedAccount.getBalance(), detectedAccount.getBalanceDate());
                } else {
                    logger.info("✅ Set account balance from detected account: {} (no balance date)", 
                        detectedAccount.getBalance());
                }
            } else {
                logger.debug("⚠️ No balance in detected account - account will be created with null balance");
            }
            
            // NOTE: allFieldsNullOrEmpty check was moved to the beginning of the method
            // This prevents creating accounts when no account information is detected
            
            // CRITICAL: Sanitize institution name - remove control characters and truncate if too long
            String institutionName = detectedAccount.getInstitutionName();
            if (institutionName != null && !institutionName.trim().isEmpty()) {
                institutionName = sanitizeAccountName(institutionName);
            } else {
                institutionName = "Unknown";
                logger.warn("⚠️ autoCreateAccountIfDetected: Institution name is null/empty, using default 'Unknown'");
            }
            newAccount.setInstitutionName(institutionName);
            
            // CRITICAL: Normalize account type - convert to lowercase and validate
            String accountType = detectedAccount.getAccountType();
            if (accountType != null && !accountType.trim().isEmpty()) {
                accountType = accountType.trim().toLowerCase();
                // Validate account type - if invalid, default to "other"
                if (!isValidAccountType(accountType)) {
                    logger.warn("Invalid account type '{}', defaulting to 'other'", accountType);
                    accountType = "other";
                }
            } else {
                accountType = "other";
            }
            newAccount.setAccountType(accountType);
            newAccount.setAccountSubtype(detectedAccount.getAccountSubtype());
            
            // CRITICAL: Store only last 4 digits of account number for security
            String accountNumber = detectedAccount.getAccountNumber();
            
            // Use provided account name if available, otherwise generate it
            String accountName = detectedAccount.getAccountName();
            if (accountName != null && !accountName.trim().isEmpty()) {
                // Use the provided account name (sanitize it)
                accountName = sanitizeAccountName(accountName);
            } else {
                // Generate account name in format: <institutionName><accountType><last4digits>
                // CRITICAL: If all original fields were null/empty, pass null to generateAccountName
                // so it returns "Imported Account" (for test compatibility)
                if (allFieldsNullOrEmpty) {
                    accountName = generateAccountName(null, null, null, null);
                } else {
                    // Use the sanitized/normalized values we just set
                    // Prefer subtype over type (e.g., "checking" is better than "depository")
                    String accountSubtype = detectedAccount.getAccountSubtype();
                    accountName = generateAccountName(institutionName, accountType, accountSubtype, accountNumber);
                }
                accountName = sanitizeAccountName(accountName);
            }
            newAccount.setAccountName(accountName);
            if (accountNumber != null && !accountNumber.trim().isEmpty()) {
                accountNumber = accountNumber.trim();
                // Extract only digits
                String digitsOnly = accountNumber.replaceAll("[^0-9]", "");
                if (digitsOnly.length() > 4) {
                    // Store only last 4 digits
                    accountNumber = digitsOnly.substring(digitsOnly.length() - 4);
                } else if (digitsOnly.length() > 0) {
                    accountNumber = digitsOnly;
                }
            }
            newAccount.setAccountNumber(accountNumber);
            // Only set balance to ZERO if it wasn't already set from detected account
            if (newAccount.getBalance() == null) {
                newAccount.setBalance(BigDecimal.ZERO);
            }
            newAccount.setCurrencyCode("USD"); // Default to USD
            newAccount.setActive(true);
            
            // CRITICAL: Set metadata from import result if available (paymentDueDate, minimumPaymentDue, rewardPoints)
            // This ensures metadata is set during account creation, not just during update
            if (importResult != null) {
                java.time.LocalDate paymentDueDate = importResult.getPaymentDueDate();
                java.math.BigDecimal minimumPaymentDue = importResult.getMinimumPaymentDue();
                Long rewardPoints = importResult.getRewardPoints();
                
                if (paymentDueDate != null) {
                    newAccount.setPaymentDueDate(paymentDueDate);
                    if (minimumPaymentDue != null) {
                        newAccount.setMinimumPaymentDue(minimumPaymentDue);
                    }
                    if (rewardPoints != null) {
                        newAccount.setRewardPoints(rewardPoints);
                    }
                    logger.info("✅ Set account metadata during creation - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}", 
                            paymentDueDate, minimumPaymentDue, rewardPoints);
                }
            }
            
            Instant now = Instant.now();
            newAccount.setCreatedAt(now);
            newAccount.setUpdatedAt(now);
            newAccount.setLastSyncedAt(now);
            newAccount.setUpdatedAtTimestamp(now.getEpochSecond());
            
            try {
                accountRepository.save(newAccount);
                logger.info("✅✅✅ autoCreateAccountIfDetected: Successfully created account - ID: '{}', name: '{}', institution: '{}', type: '{}', accountNumber: '{}', paymentDueDate: '{}', minimumPaymentDue: '{}', rewardPoints: '{}'", 
                        newAccount.getAccountId(), 
                        newAccount.getAccountName(), 
                        newAccount.getInstitutionName(),
                        newAccount.getAccountType(),
                        newAccount.getAccountNumber() != null ? "***" + newAccount.getAccountNumber() : "null",
                        newAccount.getPaymentDueDate(),
                        newAccount.getMinimumPaymentDue(),
                        newAccount.getRewardPoints());
                return newAccount.getAccountId();
            } catch (Exception saveException) {
                // If save fails due to duplicate (race condition), try to find existing account
                logger.warn("Account save failed (possibly duplicate), attempting to find existing: {}", saveException.getMessage());
                List<AccountTable> finalCheck = accountRepository.findByUserId(user.getUserId());
                if (finalCheck != null) {
                    for (AccountTable existing : finalCheck) {
                        // CRITICAL FIX: Normalize account numbers before comparison
                        if (detectedAccount.getAccountNumber() != null && 
                            existing.getAccountNumber() != null &&
                            normalizeAccountNumber(existing.getAccountNumber()).equals(normalizeAccountNumber(detectedAccount.getAccountNumber()))) {
                            logger.info("📝 Found account after save failure: {}", existing.getAccountId());
                            return existing.getAccountId();
                        }
                    }
                }
                throw saveException; // Re-throw if we can't find existing account
            }
        } catch (Exception e) {
            logger.error("Failed to auto-create account: {}", e.getMessage(), e);
            // Don't fail the import if account creation fails - transactions will use pseudo account
            return null;
        }
    }
    
    /**
     * Validate account type - returns true if valid, false otherwise
     */
    private boolean isValidAccountType(String accountType) {
        if (accountType == null || accountType.trim().isEmpty()) {
            return false;
        }
        String normalized = accountType.toLowerCase().trim();
        // Valid account types
        return normalized.equals("depository") || normalized.equals("credit") || 
               normalized.equals("loan") || normalized.equals("investment") || 
               normalized.equals("other") || normalized.equals("brokerage") ||
               normalized.equals("checking") || normalized.equals("savings") ||
               normalized.equals("creditcard") || normalized.equals("mortgage");
    }
    
    /**
     * Normalize account number - remove hyphens, spaces, and other separators, extract last 4 digits
     * CRITICAL: This ensures consistent comparison regardless of format (e.g., "8-41007" vs "841007" vs "8 41007")
     * 
     * @param accountNumber Account number in any format (may contain hyphens, spaces, etc.)
     * @return Normalized account number (last 4 digits only, digits only)
     */
    private String normalizeAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return "";
        }
        
        // Remove all non-digit characters (hyphens, spaces, masks, etc.)
        String digitsOnly = accountNumber.replaceAll("[^0-9]", "");
        
        if (digitsOnly.length() == 0) {
            return "";
        }
        
        // Extract last 4 digits (for security and consistency)
        if (digitsOnly.length() > 4) {
            return digitsOnly.substring(digitsOnly.length() - 4);
        }
        
        return digitsOnly;
    }
    
    /**
     * Sanitize account name/institution name - remove control characters and truncate if too long
     */
    /**
     * Generate account name in format: <institutionName><accountType><last4digits>
     * Example: "ChaseChecking1234" (uses subtype "checking" if available, otherwise type "depository")
     * 
     * @param institutionName Institution name (e.g., "Chase Bank")
     * @param accountType Account type (e.g., "depository")
     * @param accountSubtype Account subtype (e.g., "checking") - preferred over accountType
     * @param accountNumber Account number (last 4 digits will be extracted)
     * @return Generated account name, or "Imported Account" if all inputs are null/empty
     */
    private String generateAccountName(String institutionName, String accountType, String accountSubtype, String accountNumber) {
        // If all fields are null/empty, return default "Imported Account" (for backward compatibility with tests)
        if ((institutionName == null || institutionName.trim().isEmpty()) &&
            (accountType == null || accountType.trim().isEmpty()) &&
            (accountSubtype == null || accountSubtype.trim().isEmpty()) &&
            (accountNumber == null || accountNumber.trim().isEmpty())) {
            return "Imported Account";
        }
        
        StringBuilder name = new StringBuilder();
        
        // Add institution name (default to "Unknown" if not provided)
        if (institutionName != null && !institutionName.trim().isEmpty()) {
            name.append(institutionName.trim());
        } else {
            name.append("Unknown");
        }
        
        // Prefer subtype over type (e.g., "checking" is better than "depository")
        String typeToUse = null;
        if (accountSubtype != null && !accountSubtype.trim().isEmpty()) {
            typeToUse = accountSubtype.trim();
        } else if (accountType != null && !accountType.trim().isEmpty()) {
            typeToUse = accountType.trim();
        }
        
        if (typeToUse != null) {
            name.append(typeToUse);
        } else {
            name.append("other");
        }
        
        // Extract and add last 4 digits from account number
        if (accountNumber != null && !accountNumber.trim().isEmpty()) {
            String accountNum = accountNumber.trim();
            // Extract last 4 digits (handle cases where account number might have non-digits)
            String digitsOnly = accountNum.replaceAll("[^0-9]", "");
            if (digitsOnly.length() >= 4) {
                name.append(digitsOnly.substring(digitsOnly.length() - 4));
            } else if (!digitsOnly.isEmpty()) {
                // If less than 4 digits, use what we have
                name.append(digitsOnly);
            }
        }
        
        String result = name.toString().trim();
        // CRITICAL FIX: Ensure we always return a non-empty name
        return result.isEmpty() ? "Imported Account" : result;
    }
    
    /**
     * Generate account name in format: <institutionName><accountType><last4digits>
     * Overloaded method for backward compatibility (without subtype)
     */
    private String generateAccountName(String institutionName, String accountType, String accountNumber) {
        return generateAccountName(institutionName, accountType, null, accountNumber);
    }
    
    private String sanitizeAccountName(String name) {
        if (name == null) {
            return null;
        }
        
        // Remove control characters (0x00-0x1F, 0x7F) except newline, tab, carriage return
        // Replace with space
        String sanitized = name.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        
        // Remove newline, tab, carriage return (control chars that might cause issues)
        sanitized = sanitized.replaceAll("[\\n\\r\\t]", " ");
        
        // Remove multiple spaces
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        
        // Truncate to 255 characters (DynamoDB limit) and add "..." if truncated
        final int MAX_LENGTH = 255;
        if (sanitized.length() > MAX_LENGTH) {
            sanitized = sanitized.substring(0, MAX_LENGTH - 3) + "...";
        }
        
        return sanitized;
    }

    public static class BatchImportRequest {
        @jakarta.validation.constraints.NotNull(message = "Transactions list is required")
        @jakarta.validation.constraints.NotEmpty(message = "Transactions list cannot be empty")
        @jakarta.validation.constraints.Size(max = 10000, message = "Batch size cannot exceed 10000 transactions")
        private List<CreateTransactionRequest> transactions;
        
        private DetectedAccountInfo detectedAccount;
        
        private Boolean createDetectedAccount;

        public List<CreateTransactionRequest> getTransactions() { return transactions; }
        public void setTransactions(List<CreateTransactionRequest> transactions) { this.transactions = transactions; }
        public DetectedAccountInfo getDetectedAccount() { return detectedAccount; }
        public void setDetectedAccount(DetectedAccountInfo detectedAccount) { this.detectedAccount = detectedAccount; }
        public Boolean getCreateDetectedAccount() { return createDetectedAccount; }
        public void setCreateDetectedAccount(Boolean createDetectedAccount) { this.createDetectedAccount = createDetectedAccount; }
    }

    public static class BatchImportResponse {
        private int total;
        private int created;
        private int failed;
        private Integer duplicates; // Nullable for consistency with iOS
        private List<String> errors;
        private List<String> createdTransactionIds;
        private String createdAccountId; // Account ID if a new account was created during import

        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getCreated() { return created; }
        public void setCreated(int created) { this.created = created; }
        public int getFailed() { return failed; }
        public void setFailed(int failed) { this.failed = failed; }
        public Integer getDuplicates() { return duplicates; }
        public void setDuplicates(Integer duplicates) { this.duplicates = duplicates; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> errors) { this.errors = errors; }
        public List<String> getCreatedTransactionIds() { return createdTransactionIds; }
        public void setCreatedTransactionIds(List<String> createdTransactionIds) { this.createdTransactionIds = createdTransactionIds; }
        public String getCreatedAccountId() { return createdAccountId; }
        public void setCreatedAccountId(String createdAccountId) { this.createdAccountId = createdAccountId; }
        
        // Computed field for backward compatibility
        public boolean getSuccessful() {
            return created > 0 && failed == 0;
        }
    }

    /**
     * Response for paginated chunk import
     * Contains import results and pagination information
     */
    public static class ChunkImportResponse {
        private BatchImportResponse importResponse;
        private int page;
        private int size;
        private int total;
        private int totalPages;
        private boolean hasNext;

        public BatchImportResponse getImportResponse() { return importResponse; }
        public void setImportResponse(BatchImportResponse importResponse) { this.importResponse = importResponse; }
        public int getPage() { return page; }
        public void setPage(int page) { this.page = page; }
        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }
        public int getTotal() { return total; }
        public void setTotal(int total) { this.total = total; }
        public int getTotalPages() { return totalPages; }
        public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
        public boolean isHasNext() { return hasNext; }
        public void setHasNext(boolean hasNext) { this.hasNext = hasNext; }
    }
    
    // MARK: - Chunked Upload Endpoints
    
    /**
     * Uploads a chunk of a file
     * Headers:
     * - X-Chunk-Index: Chunk index (0-based)
     * - X-Total-Chunks: Total number of chunks
     * - X-Upload-Id: Upload session ID (optional for first chunk, required for subsequent chunks)
     * - X-Filename: Original filename (required for first chunk)
     * - X-Content-Type: Content type (required for first chunk)
     */
    @PostMapping("/import-csv/upload-chunk")
    public ResponseEntity<ChunkUploadResponse> uploadChunk(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody byte[] chunkData,
            @RequestHeader(value = "X-Chunk-Index", required = false) Integer chunkIndex,
            @RequestHeader(value = "X-Total-Chunks", required = false) Integer totalChunks,
            @RequestHeader(value = "X-Upload-Id", required = false) String uploadId,
            @RequestHeader(value = "X-Filename", required = false) String filename,
            @RequestHeader(value = "X-Content-Type", required = false) String contentType) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        
        // Validate user authentication
        userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        
        // Validate required headers
        if (chunkIndex == null || totalChunks == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "X-Chunk-Index and X-Total-Chunks headers are required");
        }
        
        // Generate upload ID if not provided (first chunk)
        if (uploadId == null || uploadId.isEmpty()) {
            uploadId = java.util.UUID.randomUUID().toString();
        }
        
        // Filename and content type required for first chunk
        if (chunkIndex == 0) {
            if (filename == null || filename.isEmpty()) {
                throw new AppException(ErrorCode.INVALID_INPUT, "X-Filename header is required for first chunk");
            }
            if (contentType == null || contentType.isEmpty()) {
                contentType = "text/csv"; // Default for CSV
            }
        }
        
        // Upload chunk
        boolean isComplete = chunkedUploadService.uploadChunk(
            uploadId, chunkIndex, totalChunks, chunkData, filename, contentType);
        
        logger.info("Chunk {}/{} uploaded for uploadId: {} (complete: {})", 
            chunkIndex + 1, totalChunks, uploadId, isComplete);
        
        return ResponseEntity.ok(new ChunkUploadResponse(uploadId, chunkIndex, isComplete));
    }
    
    /**
     * Finalizes a chunked upload and processes the file
     */
    @PostMapping("/import-csv/finalize")
    public ResponseEntity<BatchImportResponse> finalizeChunkedUpload(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody Map<String, String> request,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String password) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        
        // Validate user authentication
        userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        
        String uploadId = request.get("uploadId");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "uploadId is required");
        }
        
        try {
            // Finalize upload and get assembled file
            ChunkedUploadService.AssembledFile assembledFile = chunkedUploadService.finalizeUpload(uploadId);
            
            // Create a MultipartFile-like wrapper for the assembled file
            org.springframework.web.multipart.MultipartFile file = new org.springframework.web.multipart.MultipartFile() {
                @Override
                public String getName() { return "file"; }
                @Override
                public String getOriginalFilename() { return assembledFile.getFilename(); }
                @Override
                public String getContentType() { return assembledFile.getContentType(); }
                @Override
                public boolean isEmpty() { return assembledFile.getData().length == 0; }
                @Override
                public long getSize() { return assembledFile.getData().length; }
                @Override
                public byte[] getBytes() throws IOException { return assembledFile.getData(); }
                @Override
                public java.io.InputStream getInputStream() throws IOException {
                    return new java.io.ByteArrayInputStream(assembledFile.getData());
                }
                @Override
                public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
                    java.nio.file.Files.write(dest.toPath(), assembledFile.getData());
                }
            };
            
            // Process the file using existing import logic
            return importCSV(userDetails, file, accountId, password, assembledFile.getFilename());
            
        } catch (IOException e) {
            logger.error("Error finalizing chunked upload: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to finalize upload: " + e.getMessage());
        }
    }
    
    /**
     * Response for chunk upload
     */
    public static class ChunkUploadResponse {
        private String uploadId;
        private int chunkIndex;
        private boolean success;
        
        public ChunkUploadResponse(String uploadId, int chunkIndex, boolean success) {
            this.uploadId = uploadId;
            this.chunkIndex = chunkIndex;
            this.success = success;
        }
        
        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
        public int getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
    }

    /**
     * Update account metadata from PDF import with "latest" logic
     * Updates payment due date, minimum payment due, reward points, and balance
     * based on the statement with the latest (most recent) payment due date
     * 
     * Logic:
     * - If new payment due date is later than existing, update all metadata
     * - If new payment due date is earlier or equal, keep existing metadata
     * - If account doesn't have payment due date, use new values
     * - Balance is updated from the statement with latest payment due date
     * 
     * @param accountId Account ID to update
     * @param importResult PDF import result containing metadata
     */
    private void updateAccountMetadataFromPDFImport(String accountId, PDFImportService.ImportResult importResult) {
        logger.info("🔄 [Account Metadata Update] Starting update for accountId: '{}'", accountId);
        
        if (accountId == null || accountId.trim().isEmpty()) {
            logger.warn("⚠️ [Account Metadata Update] Cannot update: accountId is null or empty");
            return;
        }
        
        if (importResult == null) {
            logger.warn("⚠️ [Account Metadata Update] Cannot update: importResult is null");
            return;
        }
        
        logger.info("🔄 [Account Metadata Update] Import result metadata - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}", 
                importResult.getPaymentDueDate(), importResult.getMinimumPaymentDue(), importResult.getRewardPoints());
        
        try {
            Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
            if (!accountOpt.isPresent()) {
                logger.warn("⚠️ Cannot update account metadata: account '{}' not found", accountId);
                return;
            }
            
            AccountTable account = accountOpt.get();
            boolean needsUpdate = false;
            
            // Get metadata from import result
            java.time.LocalDate newPaymentDueDate = importResult.getPaymentDueDate();
            java.math.BigDecimal newMinimumPaymentDue = importResult.getMinimumPaymentDue();
            Long newRewardPoints = importResult.getRewardPoints();
            java.math.BigDecimal newBalance = null;
            
            // Get balance from detected account if available
            if (importResult.getDetectedAccount() != null && importResult.getDetectedAccount().getBalance() != null) {
                newBalance = importResult.getDetectedAccount().getBalance();
            }
            
            // CRITICAL: Apply "latest" logic - only update if new payment due date is later
            java.time.LocalDate existingPaymentDueDate = account.getPaymentDueDate();
            
            if (newPaymentDueDate != null) {
                if (existingPaymentDueDate == null) {
                    // No existing payment due date - use new values
                    account.setPaymentDueDate(newPaymentDueDate);
                    if (newMinimumPaymentDue != null) {
                        account.setMinimumPaymentDue(newMinimumPaymentDue);
                    }
                    if (newRewardPoints != null) {
                        account.setRewardPoints(newRewardPoints);
                    }
                    if (newBalance != null) {
                        account.setBalance(newBalance);
                    }
                    needsUpdate = true;
                    logger.info("📅 [Account Metadata] Setting initial payment due date: {} (no existing date)", newPaymentDueDate);
                } else if (newPaymentDueDate.isAfter(existingPaymentDueDate)) {
                    // New payment due date is later - update all metadata
                    account.setPaymentDueDate(newPaymentDueDate);
                    if (newMinimumPaymentDue != null) {
                        account.setMinimumPaymentDue(newMinimumPaymentDue);
                    }
                    if (newRewardPoints != null) {
                        account.setRewardPoints(newRewardPoints);
                    }
                    // Update balance with date comparison (for checking accounts)
                    java.time.LocalDate newBalanceDate = null;
                    if (importResult.getDetectedAccount() != null) {
                        newBalanceDate = importResult.getDetectedAccount().getBalanceDate();
                    }
                    if (newBalance != null && newBalanceDate != null) {
                        // Use date comparison logic for balance updates
                        boolean balanceUpdated = accountDetectionService.updateAccountBalanceWithDateComparison(
                            account, newBalance, newBalanceDate);
                        if (balanceUpdated) {
                            needsUpdate = true;
                            logger.info("💰 [Account Metadata] Updated balance with date comparison: {} (date: {})", 
                                newBalance, newBalanceDate);
                        }
                    } else if (newBalance != null) {
                        // No balance date - update balance directly (backward compatibility)
                        account.setBalance(newBalance);
                        needsUpdate = true;
                        logger.info("💰 [Account Metadata] Updated balance (no date): {}", newBalance);
                    }
                    needsUpdate = true;
                    logger.info("📅 [Account Metadata] Updated to later payment due date: {} (was: {})", 
                            newPaymentDueDate, existingPaymentDueDate);
                } else {
                    // New payment due date is earlier or equal - keep existing metadata
                    // But still check balance update with date comparison
                    java.time.LocalDate newBalanceDate = null;
                    if (importResult.getDetectedAccount() != null) {
                        newBalanceDate = importResult.getDetectedAccount().getBalanceDate();
                    }
                    if (newBalance != null && newBalanceDate != null) {
                        boolean balanceUpdated = accountDetectionService.updateAccountBalanceWithDateComparison(
                            account, newBalance, newBalanceDate);
                        if (balanceUpdated) {
                            needsUpdate = true;
                            logger.info("💰 [Account Metadata] Updated balance with date comparison (payment due date not updated): {} (date: {})", 
                                newBalance, newBalanceDate);
                        }
                    }
                    logger.debug("📅 [Account Metadata] Keeping existing payment due date: {} (new: {} is not later)", 
                            existingPaymentDueDate, newPaymentDueDate);
                }
            } else {
                // No payment due date in import - use date comparison for balance update
                java.time.LocalDate newBalanceDate = null;
                if (importResult.getDetectedAccount() != null) {
                    newBalanceDate = importResult.getDetectedAccount().getBalanceDate();
                }
                if (newBalance != null && newBalanceDate != null) {
                    boolean balanceUpdated = accountDetectionService.updateAccountBalanceWithDateComparison(
                        account, newBalance, newBalanceDate);
                    if (balanceUpdated) {
                        needsUpdate = true;
                        logger.info("💰 [Account Metadata] Updated balance with date comparison: {} (date: {})", 
                            newBalance, newBalanceDate);
                    }
                } else if (newBalance != null && account.getBalance() == null) {
                    // No balance date - set initial balance if account has no balance
                    account.setBalance(newBalance);
                    needsUpdate = true;
                    logger.info("💰 [Account Metadata] Setting initial balance: {} (no existing balance, no balance date)", newBalance);
                }
            }
            
            // Update account if changes were made
            if (needsUpdate) {
                account.setUpdatedAt(java.time.Instant.now());
                logger.info("💾 [Account Metadata Update] Saving account '{}' to DynamoDB with metadata - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}, balance: {}", 
                        accountId, 
                        account.getPaymentDueDate(),
                        account.getMinimumPaymentDue(),
                        account.getRewardPoints(),
                        account.getBalance());
                accountRepository.save(account);
                logger.info("✅ [Account Metadata Update] Successfully saved account '{}' metadata to DynamoDB - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}, balance: {}", 
                        accountId, 
                        account.getPaymentDueDate(),
                        account.getMinimumPaymentDue(),
                        account.getRewardPoints(),
                        account.getBalance());
            } else {
                logger.info("ℹ️ [Account Metadata Update] No update needed for account '{}' - existing metadata is already up-to-date", accountId);
            }
        } catch (Exception e) {
            logger.error("❌ Error updating account metadata for account '{}': {}", accountId, e.getMessage(), e);
            // Don't fail the import if metadata update fails
        }
    }
}
