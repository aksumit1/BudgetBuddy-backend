package com.budgetbuddy.api;



import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
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
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.ImportHistoryService;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Transaction REST Controller Optimized with pagination to minimize data transfer
 *
 * <p>Thread-safe with proper error handling
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference (design is "
                        + "value-semantic, Jackson creates fresh instances); Spring constructor "
                        + "injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionController.class);

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
    private final ChunkedUploadService chunkedUploadService; // Chunked upload support
    private final ObjectMapper objectMapper; // For parsing JSON request bodies
    private final AccountDetectionService
            accountDetectionService; // For account balance date comparison
    private final com.budgetbuddy.notification.DataChangeNotificationService
            dataChangeNotificationService; // For push notifications
    private final SubscriptionService subscriptionService; // For automatic subscription detection
    private final ImportHistoryService importHistoryService; // For import history and statistics
    // Flow 4 / O11: validate that a transaction isn't being tagged to an already-completed goal.
    private final GoalService goalService;
    // Flow 5 / O8: server-side threshold alerts.
    private final com.budgetbuddy.service.BudgetThresholdEvaluator budgetThresholdEvaluator;
    // Flow 6 / O3: post-ingest goal recalc + milestone push.
    private final com.budgetbuddy.service.GoalIngestEvaluator goalIngestEvaluator;
    // Flow 6 / O5: credit goalAllocation → goal.currentAmount after each ingest.
    private final com.budgetbuddy.service.BudgetToGoalFlowService budgetToGoalFlowService;
    // Correctness: retry-safe POST — client sends Idempotency-Key header.
    private final com.budgetbuddy.service.correctness.IdempotencyService idempotencyService;
    // Maintainability: processBatchImport delegates here so CSV/Excel/PDF/
    // chunked imports share one tested orchestrator instead of 4 forks.
    private final com.budgetbuddy.service.importer.TransactionImportOrchestrator importOrchestrator;

    // Flow 7 / O4: audit every transaction mutation end-to-end.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.budgetbuddy.compliance.MutationAuditInterceptor auditInterceptor;

    // Flow 7 / O9: push HIGH-severity anomalies proactively.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.budgetbuddy.service.AnomalyAlertPusher anomalyAlertPusher;

    // Allowed file extensions
    private static final Set<String> CSV_EXTENSIONS = Set.of("csv");
    private static final Set<String> EXCEL_EXTENSIONS = Set.of("xlsx", "xls");
    private static final Set<String> PDF_EXTENSIONS = Set.of("pdf");

    /**
     * Constructor with grouped dependencies via TransactionControllerConfig Reduces constructor
     * parameter count from 17 to 1, improving maintainability
     */
    public TransactionController(
            final com.budgetbuddy.api.config.TransactionControllerConfig config,
            final GoalService goalService,
            final com.budgetbuddy.service.BudgetThresholdEvaluator budgetThresholdEvaluator,
            final com.budgetbuddy.service.GoalIngestEvaluator goalIngestEvaluator,
            final com.budgetbuddy.service.BudgetToGoalFlowService budgetToGoalFlowService,
            final com.budgetbuddy.service.correctness.IdempotencyService idempotencyService,
            final com.budgetbuddy.service.importer.TransactionImportOrchestrator
                    importOrchestrator) {
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
        this.chunkedUploadService = config.getChunkedUploadService();
        this.accountDetectionService = config.getAccountDetectionService();
        this.objectMapper = config.getObjectMapper();
        this.dataChangeNotificationService = config.getDataChangeNotificationService();
        this.subscriptionService = config.getSubscriptionService();
        this.importHistoryService = config.getImportHistoryService();
        // GoalService is used by POST /link-goal — injected directly rather
        // than through the config grouping since the config DTO doesn't
        // expose it (pre-existing gap). Keeps the rest of the grouped-
        // config pattern intact.
        this.goalService = goalService;
        this.budgetThresholdEvaluator = budgetThresholdEvaluator;
        this.goalIngestEvaluator = goalIngestEvaluator;
        this.budgetToGoalFlowService = budgetToGoalFlowService;
        this.idempotencyService = idempotencyService;
        this.importOrchestrator = importOrchestrator;
    }

    @GetMapping
    public ResponseEntity<List<TransactionTable>> getTransactions(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "20") final int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Validate pagination parameters
        if (page < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be non-negative");
        }
        if (size < 1 || size > 100) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Page size must be between 1 and 100");
        }

        final int skip = page * size;
        final List<TransactionTable> transactions = transactionService.getTransactions(user, skip, size);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/range")
    public ResponseEntity<List<TransactionTable>> getTransactionsInRange(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(
                    ErrorCode.INVALID_DATE_RANGE, "Start date must be before end date");
        }

        final List<TransactionTable> transactions =
                transactionService.getTransactionsInRange(user, startDate, endDate);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/total")
    public ResponseEntity<TotalSpendingResponse> getTotalSpending(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate endDate) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (startDate == null || endDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new AppException(
                    ErrorCode.INVALID_DATE_RANGE, "Start date must be before end date");
        }

        final BigDecimal total = transactionService.getTotalSpending(user, startDate, endDate);
        return ResponseEntity.ok(new TotalSpendingResponse(total));
    }

    /**
     * Get import history for the authenticated user Returns a list of all import operations (CSV,
     * Excel, PDF, etc.)
     */
    @GetMapping("/import-history")
    public ResponseEntity<List<ImportHistoryResponse>> getImportHistory(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final List<com.budgetbuddy.model.ImportHistory> historyList =
                importHistoryService.getUserImportHistory(user.getUserId());

        final List<ImportHistoryResponse> responseList =
                historyList.stream()
                        .map(ImportHistoryResponse::from)
                        .collect(java.util.stream.Collectors.toList());

        return ResponseEntity.ok(responseList);
    }

    /**
     * Get import statistics for the authenticated user Returns aggregated statistics about all
     * import operations
     */
    @GetMapping("/import-statistics")
    public ResponseEntity<ImportStatisticsResponse> getImportStatistics(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final Map<String, Object> stats =
                importHistoryService.getImportStatistics(user.getUserId());

        final ImportStatisticsResponse response = ImportStatisticsResponse.from(stats);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionTable> getTransaction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String id,
            @RequestParam(required = false) final String plaidTransactionId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Pass plaidTransactionId for fallback lookup if UUID doesn't match
        final TransactionTable transaction =
                transactionService.getTransaction(user, id, plaidTransactionId);
        return ResponseEntity.ok(transaction);
    }

    @PostMapping
    public ResponseEntity<TransactionTable> createTransaction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestHeader(
                            value = "Idempotency-Key",
                            required = false) final String idempotencyKey,
            @Valid @RequestBody final CreateTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

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
        if (request.getCategoryPrimary() == null || request.getCategoryPrimary().isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category primary is required");
        }

        // CRITICAL FIX: Account ID is now optional - if not provided, backend will use pseudo
        // account

        // Retry-safe create. If the client's URLSession retried a dropped
        // POST, the second call will return the transaction created by the
        // first call instead of inserting a duplicate. The client must send
        // a unique Idempotency-Key header per intended create action.
        final String resolvedTransactionId =
                idempotencyService.runOnce(
                        user.getUserId(),
                        idempotencyKey,
                        () ->
                                transactionService
                                        .createTransaction(
                                                user,
                                                request.getAccountId(),
                                                request.getAmount(),
                                                request.getTransactionDate(),
                                                request.getDescription(),
                                                request.getCategoryPrimary(),
                                                request.getCategoryDetailed(),
                                                null, // importerCategoryPrimary
                                                null, // importerCategoryDetailed
                                                request.getTransactionId(),
                                                request.getNotes(),
                                                request.getPlaidAccountId(),
                                                request.getPlaidTransactionId(),
                                                request.getTransactionType(),
                                                request.getCurrencyCode(),
                                                request.getImportSource(),
                                                request.getImportBatchId(),
                                                request.getImportFileName(),
                                                request.getReviewStatus(),
                                                request.getMerchantName(),
                                                request.getLocation(),
                                                request.getPaymentChannel(),
                                                request.getUserName(),
                                                request.getGoalId(),
                                                request.getLinkedTransactionId())
                                        .getTransactionId());

        // Re-fetch to return the authoritative current state of the row
        // (matters for the idempotency-hit path where another request may
        // have created it seconds ago; reading ensures we never return stale).
        final TransactionTable transaction =
                transactionService.getTransaction(user, resolvedTransactionId, null);

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyTransactionCreated(
                    user.getUserId(), transaction.getTransactionId());
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to send data change notification for transaction creation: {}",
                    e.getMessage());
            // Don't fail the request if notification fails
        }

        // CRITICAL FIX: Automatically detect subscriptions after transaction creation
        // Run asynchronously to avoid blocking the response
        try {
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> {
                        try {
                            final List<com.budgetbuddy.model.Subscription> detected =
                                    subscriptionService.detectSubscriptions(user.getUserId());
                            if (!detected.isEmpty()) {
                                subscriptionService.saveSubscriptions(user.getUserId(), detected);
                                LOGGER.info(
                                        "Detected {} subscriptions after transaction creation",
                                        detected.size());
                            }
                        } catch (Exception e) {
                            LOGGER.warn(
                                    "Failed to detect subscriptions after transaction creation: {}",
                                    e.getMessage());
                            // Don't fail the request if subscription detection fails
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to trigger subscription detection after transaction creation: {}",
                    e.getMessage());
            // Don't fail the request if subscription detection fails
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(transaction);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionTable> updateTransaction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String id,
            @Valid @RequestBody final UpdateTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        // Validation is handled by Bean Validation annotations on UpdateTransactionRequest

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Flow 4 / O2 optimistic lock. If the client passed `ifUnmodifiedSince`, compare it
        // against the stored row's `updatedAt`. A mismatch means another device (or the
        // Plaid refresh job) already moved this row — bail with 409 so the client can
        // surface the change instead of silently clobbering it.
        // O11: server-side guard so a stale draft can't tag a transaction to a goal that
        // completed while the user was editing.
        if (request.getGoalId() != null && !request.getGoalId().isBlank()) {
            try {
                final com.budgetbuddy.model.dynamodb.GoalTable goal =
                        goalService.getGoal(user, request.getGoalId());
                if (Boolean.TRUE.equals(goal.getCompleted())) {
                    throw new AppException(
                            ErrorCode.GOAL_ALREADY_COMPLETED,
                            "Goal \""
                                    + (goal.getName() == null
                                            ? request.getGoalId()
                                            : goal.getName())
                                    + "\" is already completed.");
                }
            } catch (AppException e) {
                // Rethrow our own conditions; swallow lookup misses (handled downstream as needed).
                if (e.getErrorCode() == ErrorCode.GOAL_ALREADY_COMPLETED) {
                    throw e;
                }
            }
        }

        if (request.getIfUnmodifiedSince() != null && !request.getIfUnmodifiedSince().isBlank()) {
            try {
                final Instant clientKnownUpdatedAt =
                        Instant.parse(request.getIfUnmodifiedSince());
                final Optional<TransactionTable> existing =
                        transactionService.findByTransactionIdAndUserId(id, user.getUserId());
                if (existing.isPresent() && existing.get().getUpdatedAt() != null) {
                    final Instant serverUpdatedAt = existing.get().getUpdatedAt();
                    // Allow up to 1 second of skew to tolerate round-trip timestamp truncation
                    // (backend stores seconds; client sends seconds; no actual conflict there).
                    if (serverUpdatedAt.isAfter(clientKnownUpdatedAt.plusSeconds(1))) {
                        LOGGER.info(
                                "409 Conflict on tx {} — server updatedAt {} is newer than client ifUnmodifiedSince {}",
                                id,
                                serverUpdatedAt,
                                clientKnownUpdatedAt);
                        throw new AppException(
                                ErrorCode.CONFLICT,
                                "Transaction changed on another device. Reload before retrying.");
                    }
                }
            } catch (java.time.format.DateTimeParseException ignored) {
                // Malformed header → proceed without the check rather than failing the edit.
            }
        }

        // Use reviewStatus directly from request (no conversion needed)
        final TransactionTable transaction =
                transactionService.updateTransaction(
                        user,
                        id,
                        request.getPlaidTransactionId(), // Pass Plaid ID for fallback lookup
                        request.getAmount(), // Pass amount (for type changes)
                        request.getNotes(),
                        request.getCategoryPrimary(),
                        request.getCategoryDetailed(),
                        request.getReviewStatus(), // Pass review status directly from request
                        request.getIsHidden(), // Pass hidden state
                        request.getTransactionType(), // Pass optional user-selected transaction
                        // type
                        false, // Don't clear notes if null - preserve existing when doing partial
                        // updates
                        request.getGoalId(), // Pass optional goal ID this transaction contributes
                        // to
                        request.getLinkedTransactionId() // Pass optional linked transaction ID
                // (for cross-account duplicate detection)
                );

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyTransactionUpdated(
                    user.getUserId(), transaction.getTransactionId());
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to send data change notification for transaction update: {}",
                    e.getMessage());
            // Don't fail the request if notification fails
        }

        // Flow 5 / O8: after the edit lands, check whether the touched categories
        // just crossed a budget threshold. Non-blocking — failures here never fail
        // the user-visible update.
        try {
            final Set<String> touched = new java.util.HashSet<>();
            if (transaction.getCategoryPrimary() != null) {
                touched.add(transaction.getCategoryPrimary());
            }
            if (transaction.getCategoryDetailed() != null) {
                touched.add(transaction.getCategoryDetailed());
            }
            budgetThresholdEvaluator.evaluate(user.getUserId(), touched);
        } catch (Exception e) {
            LOGGER.warn("Budget threshold evaluation failed: {}", e.getMessage());
        }

        // Flow 6 / O3: after the edit lands, recompute goal progress and emit any
        // newly-crossed milestone pushes. Also non-blocking.
        try {
            // Flow 6 / O5 runs FIRST so that any under-spend this cycle gets flowed
            // into goal.currentAmount before the ingest evaluator looks at progress.
            // Otherwise a milestone push could fire one ingest late.
            budgetToGoalFlowService.flowForUser(user);
            goalIngestEvaluator.evaluate(user);
        } catch (Exception e) {
            LOGGER.warn("Goal ingest evaluation failed: {}", e.getMessage());
        }

        if (auditInterceptor != null) {
            auditInterceptor.transactionChanged(
                    user.getUserId(),
                    transaction.getTransactionId(),
                    "UPDATE",
                    String.format(
                            "category=%s amount=%s",
                            transaction.getCategoryPrimary(), transaction.getAmount()));
        }
        // Flow 7 / O9 — after everything else, check whether this edit unmasked a
        // HIGH-severity anomaly worth pushing.
        if (anomalyAlertPusher != null) {
            try {
                anomalyAlertPusher.pushHighSeverityIfAny(user.getUserId());
            } catch (Exception e) {
                LOGGER.warn("Anomaly push failed: {}", e.getMessage());
            }
        }

        // CRITICAL FIX: Automatically detect subscriptions after transaction update
        // Especially important when category changes to subscription-related
        // Run asynchronously to avoid blocking the response
        try {
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> {
                        try {
                            // Check if category changed to subscription-related
                            final boolean isSubscriptionCategory =
                                    ("subscriptions"
                                            .equalsIgnoreCase(
                                                    request.getCategoryPrimary()))
                                            || ("subscriptions"
                                            .equalsIgnoreCase(
                                                    request.getCategoryDetailed()));

                            // Always run detection after update (category might have changed, or
                            // new transaction pattern might emerge)
                            final List<com.budgetbuddy.model.Subscription> detected =
                                    subscriptionService.detectSubscriptions(user.getUserId());
                            if (!detected.isEmpty()) {
                                subscriptionService.saveSubscriptions(user.getUserId(), detected);
                                LOGGER.info(
                                        "Detected {} subscriptions after transaction update (category changed to subscription: {})",
                                        detected.size(),
                                        isSubscriptionCategory);
                            }
                        } catch (Exception e) {
                            LOGGER.warn(
                                    "Failed to detect subscriptions after transaction update: {}",
                                    e.getMessage());
                            // Don't fail the request if subscription detection fails
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to trigger subscription detection after transaction update: {}",
                    e.getMessage());
            // Don't fail the request if subscription detection fails
        }

        return ResponseEntity.ok(transaction);
    }

    /** Batch import transactions from JSON request Used for programmatic imports and testing */
    @PostMapping("/batch-import")
    public ResponseEntity<BatchImportResponse> batchImport(
            @AuthenticationPrincipal final UserDetails userDetails,
            @Valid @RequestBody final BatchImportRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        // Validation is handled by Bean Validation annotations on BatchImportRequest
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // CRITICAL: If createDetectedAccount is true and detectedAccount is provided, create the
        // account first
        String accountIdToUse = null;
        if (Boolean.TRUE.equals(request.getCreateDetectedAccount())
                && request.getDetectedAccount() != null) {
            // Convert DetectedAccountInfo to AccountDetectionService.DetectedAccount
            final AccountDetectionService.DetectedAccount detectedAccount =
                    new AccountDetectionService.DetectedAccount();
            detectedAccount.setAccountName(request.getDetectedAccount().getAccountName());
            detectedAccount.setInstitutionName(request.getDetectedAccount().getInstitutionName());
            detectedAccount.setAccountType(request.getDetectedAccount().getAccountType());
            detectedAccount.setAccountSubtype(request.getDetectedAccount().getAccountSubtype());
            detectedAccount.setAccountNumber(request.getDetectedAccount().getAccountNumber());
            detectedAccount.setBalance(
                    request.getDetectedAccount()
                            .getBalance()); // CRITICAL: Include balance from detected account
            // Note: balanceDate is not available in DetectedAccountInfo from iOS, will be set from
            // import result if available

            // Create account if it doesn't exist
            accountIdToUse = autoCreateAccountIfDetected(user, detectedAccount);
            LOGGER.info("📝 Auto-created account for batch import: {}", accountIdToUse);
        }

        // CRITICAL FIX: If detectedAccount has matchedAccountId, use it to override transaction
        // accountIds
        // This handles the case where account already exists and was matched during preview
        if (accountIdToUse == null && request.getDetectedAccount() != null) {
            final String matchedAccountId = request.getDetectedAccount().getMatchedAccountId();
            if (matchedAccountId != null && !matchedAccountId.isBlank()) {
                try {
                    // Verify the matched account exists and belongs to the user
                    final Optional<AccountTable> matchedAccount =
                            accountRepository.findById(matchedAccountId);
                    if (matchedAccount.isPresent()) {
                        final AccountTable account = matchedAccount.get();
                        // CRITICAL: Verify account belongs to user and is not null
                        if (account.getUserId() != null
                                && account.getUserId().equals(user.getUserId())) {
                            accountIdToUse = matchedAccountId;
                            LOGGER.info(
                                    "🔗 Using matched account ID from detectedAccount: {}",
                                    accountIdToUse);
                        } else {
                            LOGGER.warn(
                                    "⚠️ Matched account ID '{}' belongs to different user (account userId: '{}', request userId: '{}') - ignoring",
                                    matchedAccountId,
                                    account.getUserId(),
                                    user.getUserId());
                        }
                    } else {
                        LOGGER.warn(
                                "⚠️ Matched account ID '{}' not found in repository - ignoring",
                                matchedAccountId);
                    }
                } catch (Exception e) {
                    LOGGER.error(
                            "❌ Error verifying matched account ID '{}': {}",
                            matchedAccountId,
                            e.getMessage(),
                            e);
                    // Don't fail the entire import - continue without matched account
                }
            }
        }

        // CRITICAL FIX: If account was auto-created or matched, update all transactions to use it
        // This ensures transactions are tagged to the correct account instead of wrong/pseudo
        // account
        if (accountIdToUse != null && !accountIdToUse.isBlank()) {
            LOGGER.info(
                    "🔗 Updating {} transactions to use account: {}",
                    request.getTransactions().size(),
                    accountIdToUse);
            int updatedCount = 0;
            for (final CreateTransactionRequest txRequest : request.getTransactions()) {
                if (txRequest == null) {
                    LOGGER.warn("⚠️ Null transaction request in batch - skipping");
                    continue;
                }
                // CRITICAL FIX: Always override accountId when accountIdToUse is set (from created
                // or matched account)
                // This ensures transactions are always assigned to the correct account, even if
                // they came with wrong accountId
                final String oldAccountId = txRequest.getAccountId();
                txRequest.setAccountId(accountIdToUse);
                updatedCount++;
                if (oldAccountId != null && !oldAccountId.equals(accountIdToUse)) {
                    LOGGER.debug(
                            "Updated transaction '{}' accountId from '{}' to '{}'",
                            txRequest.getDescription() != null
                                    ? txRequest.getDescription()
                                    : "unknown",
                            oldAccountId,
                            accountIdToUse);
                }
            }
            LOGGER.info(
                    "✅ Updated {} transaction(s) to use account: {}", updatedCount, accountIdToUse);
        }

        // Use TransactionService's batch import method
        final BatchImportResponse response =
                transactionService.createTransactionsBatch(user, request.getTransactions());

        // Send push notification for real-time sync on other devices (batch import)
        try {
            final int totalCount = response.getCreated(); // Number of successfully created transactions
            if (totalCount > 0) {
                dataChangeNotificationService.notifyBatchTransactionsImported(
                        user.getUserId(), totalCount);
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to send data change notification for batch import: {}", e.getMessage());
            // Don't fail the request if notification fails
        }

        // CRITICAL FIX: Automatically detect subscriptions after batch import
        // Run asynchronously to avoid blocking the response
        final int finalCreated = response.getCreated(); // Capture for lambda
        if (finalCreated > 0) {
            try {
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            try {
                                final List<com.budgetbuddy.model.Subscription> detected =
                                        subscriptionService.detectSubscriptions(user.getUserId());
                                if (!detected.isEmpty()) {
                                    subscriptionService.saveSubscriptions(
                                            user.getUserId(), detected);
                                    LOGGER.info(
                                            "Detected {} subscriptions after batch import ({} transactions created)",
                                            detected.size(),
                                            finalCreated);
                                }
                            } catch (Exception e) {
                                LOGGER.warn(
                                        "Failed to detect subscriptions after batch import: {}",
                                        e.getMessage());
                                // Don't fail the request if subscription detection fails
                            }
                        });
            } catch (Exception e) {
                LOGGER.warn(
                        "Failed to trigger subscription detection after batch import: {}",
                        e.getMessage());
                // Don't fail the request if subscription detection fails
            }
        }

        // CRITICAL FIX: Include createdAccountId in response if account was created or matched
        // Only include if accountIdToUse is valid and was successfully used
        if (accountIdToUse != null && !accountIdToUse.isBlank()) {
            try {
                // Validate UUID format to prevent invalid IDs from being sent to iOS
                UUID.fromString(accountIdToUse);
                response.setCreatedAccountId(accountIdToUse);
                LOGGER.info(
                        "📤 Including createdAccountId '{}' in batch import response",
                        accountIdToUse);
            } catch (IllegalArgumentException e) {
                LOGGER.error(
                        "❌ Invalid UUID format for createdAccountId '{}': {}",
                        accountIdToUse,
                        e.getMessage());
                // Don't include invalid UUID in response - iOS will handle gracefully
                // Account was still created/used, but we won't return the ID if it's invalid
            }
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify")
    public ResponseEntity<TransactionTable> verifyTransaction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @Valid @RequestBody final VerifyTransactionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        // Validation is handled by Bean Validation annotations on VerifyTransactionRequest

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Use plaidTransactionId from request body for fallback lookup if UUID doesn't match
        final TransactionTable transaction =
                transactionService.getTransaction(
                        user, request.getTransactionId(), request.getPlaidTransactionId());
        return ResponseEntity.ok(transaction);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        transactionService.deleteTransaction(user, id);

        // CRITICAL FIX: Automatically detect subscriptions after transaction deletion
        // Deletion might affect existing subscription patterns, so re-run detection
        // Run asynchronously to avoid blocking the response
        try {
            java.util.concurrent.CompletableFuture.runAsync(
                    () -> {
                        try {
                            final List<com.budgetbuddy.model.Subscription> detected =
                                    subscriptionService.detectSubscriptions(user.getUserId());
                            if (!detected.isEmpty()) {
                                subscriptionService.saveSubscriptions(user.getUserId(), detected);
                                LOGGER.info(
                                        "Detected {} subscriptions after transaction deletion",
                                        detected.size());
                            }
                        } catch (Exception e) {
                            LOGGER.warn(
                                    "Failed to detect subscriptions after transaction deletion: {}",
                                    e.getMessage());
                            // Don't fail the request if subscription detection fails
                        }
                    });
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to trigger subscription detection after transaction deletion: {}",
                    e.getMessage());
            // Don't fail the request if subscription detection fails
        }

        if (auditInterceptor != null) {
            auditInterceptor.transactionChanged(
                    user.getUserId(), id, "DELETE", "Transaction soft-deleted");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Flow 4 / O9: restore a soft-deleted transaction. Used by the 10-second undo toast on the
     * client. Returns the live row so the client can merge it back into memory.
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<TransactionTable> restoreTransaction(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        final TransactionTable restored = transactionService.restoreTransaction(user, id);
        if (auditInterceptor != null) {
            auditInterceptor.transactionChanged(
                    user.getUserId(), id, "RESTORE", "Transaction restored from soft-delete");
        }
        return ResponseEntity.ok(restored);
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
    private byte[] applySecurityProcessing(
            final MultipartFile file, final String userId, final Set<String> allowedExtensions) {
        try {
            // 1. Rate limiting
            fileUploadRateLimiter.checkRateLimit(userId, file.getSize());

            // 2. File validation
            fileSecurityValidator.validateFileUpload(file, allowedExtensions);

            // 3. Read file content into byte array (for multiple reads)
            final byte[] fileContent = file.getBytes();
            // Spring's MultipartFile.getOriginalFilename() can return null
            // (browser oddity, multipart parser quirk); fall back so downstream
            // checksum / quarantine paths always have a non-null label.
            final String originalName = file.getOriginalFilename();
            final String fileName = originalName != null ? originalName : "unnamed-upload";

            // 4. Content scanning
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final FileContentScanner.ScanResult scanResult =
                        fileContentScanner.scanFile(inputStream, fileName);

                if (!scanResult.isSafe()) {
                    // Quarantine suspicious file
                    final String reason = String.join("; ", scanResult.getFindings());
                    try (InputStream quarantineStream = new ByteArrayInputStream(fileContent)) {
                        final String quarantineId =
                                fileQuarantineService.quarantineFile(
                                        quarantineStream, fileName, reason, userId);
                        LOGGER.warn(
                                "File quarantined: {} (ID: {}) - Reason: {}",
                                fileName,
                                quarantineId,
                                reason);
                    }
                    throw new AppException(
                            ErrorCode.INVALID_INPUT,
                            "File contains suspicious content and has been quarantined: " + reason);
                }
            }

            // 5. Calculate and store checksum
            try (InputStream checksumStream = new ByteArrayInputStream(fileContent)) {
                final String checksum = fileIntegrityService.calculateChecksum(checksumStream);
                final Map<String, Object> metadata = new HashMap<>();
                metadata.put("fileName", fileName);
                metadata.put("fileSize", file.getSize());
                metadata.put("uploadTime", System.currentTimeMillis());
                metadata.put("userId", userId);
                fileIntegrityService.storeChecksum(fileName, checksum, metadata);
                LOGGER.debug("File checksum calculated and stored: {} ({})", fileName, checksum);
            }

            // 6. Record upload (after all checks pass)
            fileUploadRateLimiter.recordUpload(userId, file.getSize());

            return fileContent;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Security processing failed for file: {}", file.getOriginalFilename(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "File security processing failed: " + e.getMessage());
        }
    }

    /**
     * CRITICAL: Helper method to safely get original filename from MultipartFile Some Spring Boot
     * configurations or file upload libraries may sanitize filenames, but we need the original for
     * account detection (e.g., "Chase3100_Activity_29251221.csv")
     *
     * @param file MultipartFile from request
     * @return Original filename, sanitized and validated, or null if truly unavailable
     */
    private String getOriginalFilenameSafely(final MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            LOGGER.warn("⚠️ MultipartFile.getOriginalFilename() returned null or empty");
            return null; // Return null instead of "unknown" - let caller decide fallback
        }

        // Sanitize filename: remove path separators and dangerous characters
        filename = sanitizeFilename(filename.trim());

        // Validate filename length (RFC 2183 recommends max 255 bytes, we use 200 for safety)
        if (filename.length() > 200) {
            LOGGER.warn(
                    "⚠️ Filename too long ({} chars), truncating: '{}'",
                    filename.length(),
                    filename);
            // Preserve extension while truncating
            final int lastDot = filename.lastIndexOf('.');
            if (lastDot > 0 && lastDot < filename.length() - 1) {
                final String ext = filename.substring(lastDot);
                final String nameWithoutExt = filename.substring(0, lastDot);
                filename =
                        nameWithoutExt.substring(
                                        0, Math.min(200 - ext.length(), nameWithoutExt.length()))
                                + ext;
            } else {
                filename = filename.substring(0, Math.min(200, filename.length()));
            }
            LOGGER.debug("📁 Truncated filename: '{}'", filename);
        }

        // Log if filename looks like a UUID (might indicate sanitization)
        if (filename.matches(
                "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$")) {
            LOGGER.warn(
                    "⚠️ Filename appears to be a UUID (possibly sanitized): '{}' - Original filename may have been lost",
                    filename);
        }

        return filename;
    }

    /**
     * Sanitize filename by removing path separators and dangerous characters
     *
     * @param filename Original filename
     * @return Sanitized filename safe for account detection and logging
     */
    private String sanitizeFilename(final String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }

        // Remove path separators and dangerous patterns
        String sanitized =
                filename.replaceAll("[/\\\\]", "_") // Replace path separators
                        .replaceAll("\\.\\.", "_") // Replace .. patterns
                        .replaceAll("[\\x00-\\x1F\\x7F]", "_") // Replace control characters
                        .trim();

        // Ensure non-empty after sanitization
        if (sanitized.isEmpty()) {
            sanitized = "import_" + System.currentTimeMillis() + ".csv";
            LOGGER.warn(
                    "⚠️ Filename became empty after sanitization, using default: '{}'", sanitized);
        }

        return sanitized;
    }

    // MARK: - CSV Import Endpoints

    @PostMapping("/import-csv/preview")
    public ResponseEntity<CSVImportPreviewResponse> previewCSV(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) final String password,
            @RequestParam(required = false) final String filename,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "100") final int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter,
            // fallback to MultipartFile.getOriginalFilename()
            // iOS app may send original filename as separate parameter if Spring sanitizes it
            String originalFilename;
            boolean fromParameter = false;

            if (filename != null && !filename.isBlank()) {
                // URL-decode the filename parameter (URLComponents automatically encodes it, Spring
                // decodes it)
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            // If still null/empty, use a default (but log as error condition)
            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".csv";
                LOGGER.error(
                        "❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'",
                        originalFilename);
            }

            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            final boolean isUUIDFilename =
                    originalFilename.matches(
                            "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                LOGGER.warn(
                        "⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. "
                                + "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.",
                        originalFilename);
            }

            LOGGER.info(
                    "📁 CSV Preview - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})",
                    originalFilename,
                    fromParameter,
                    file.getOriginalFilename(),
                    isUUIDFilename);

            // Log multipart request details for debugging
            LOGGER.info(
                    "📤 CSV Preview Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', Has password: {}",
                    filename,
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getContentType(),
                    password != null && !password.isEmpty());

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), CSV_EXTENSIONS);

            // Parse CSV - use original filename (not sanitized version) for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final CSVImportService.ImportResult importResult =
                        csvImportService.parseCSV(
                                inputStream, originalFilename, user.getUserId(), password);

                // Build preview response with duplicate detection
                final List<DuplicateDetectionService.ParsedTransaction> parsedForDuplicateCheck =
                        new ArrayList<>();

                for (final CSVImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                    // Convert to DuplicateDetectionService.ParsedTransaction
                    final DuplicateDetectionService.ParsedTransaction dupTx =
                            new DuplicateDetectionService.ParsedTransaction(
                                    parsed.getDate(),
                                    parsed.getAmount(),
                                    parsed.getDescription(),
                                    parsed.getMerchantName());
                    dupTx.setTransactionId(parsed.getTransactionId());
                    parsedForDuplicateCheck.add(dupTx);
                }

                // Detect duplicates
                final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                        duplicateDetectionService.detectDuplicates(
                                user.getUserId(), parsedForDuplicateCheck);

                // P1: Pagination - Build transaction maps with duplicate info (with pagination)
                final int totalTransactions = importResult.getTransactions().size();
                final int startIndex = page * size;
                final int endIndex = Math.min(startIndex + size, totalTransactions);
                final int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate pagination parameters
                if (page < 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
                }
                if (size < 1 || size > 1000) {
                    throw new AppException(
                            ErrorCode.INVALID_INPUT, "Page size must be between 1 and 1000");
                }

                final List<Map<String, Object>> paginatedTransactions = new ArrayList<>();
                for (int i = startIndex; i < endIndex; i++) {
                    final CSVImportService.ParsedTransaction parsed =
                            importResult.getTransactions().get(i);
                    // Log category assignment for preview
                    LOGGER.info(
                            "📋 CSV Preview Transaction[{}]: merchant='{}', description='{}', amount={}, category='{}'",
                            i,
                            parsed.getMerchantName(),
                            parsed.getDescription(),
                            parsed.getAmount(),
                            parsed.getCategoryPrimary());
                    final Map<String, Object> txMap = buildTransactionMap(parsed, duplicates.get(i));
                    paginatedTransactions.add(txMap);
                }

                final CSVImportPreviewResponse response = new CSVImportPreviewResponse();
                response.setTotalParsed(importResult.getSuccessCount());
                response.setTransactions(paginatedTransactions);
                response.setPage(page);
                response.setSize(size);
                response.setTotalPages(totalPages);
                response.setTotalElements(totalTransactions);
                DetectedAccountInfo accountInfo = null;
                if (importResult.getDetectedAccount() != null) {
                    accountInfo = new DetectedAccountInfo();

                    // CRITICAL: If account was matched, use the matched account's details instead
                    // of detected account
                    // This ensures iOS shows the existing account information, not the detected
                    // account
                    final String matchedAccountId = importResult.getMatchedAccountId();
                    if (matchedAccountId != null && !matchedAccountId.isBlank()) {
                        // Fetch the matched account from database
                        final Optional<AccountTable> matchedAccount =
                                accountRepository.findById(matchedAccountId);
                        if (matchedAccount.isPresent()
                                && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            final AccountTable account = matchedAccount.get();
                            // Use matched account's details
                            accountInfo.setAccountName(account.getAccountName());
                            accountInfo.setInstitutionName(account.getInstitutionName());
                            accountInfo.setAccountType(account.getAccountType());
                            accountInfo.setAccountSubtype(account.getAccountSubtype());
                            accountInfo.setAccountNumber(account.getAccountNumber());
                            accountInfo.setCardNumber(
                                    null); // Card number not stored in AccountTable
                            accountInfo.setBalance(
                                    account.getBalance()); // Include balance from existing account
                            accountInfo.setMatchedAccountId(matchedAccountId);

                            // CRITICAL: Include credit card metadata from existing account (if
                            // available)
                            // CSV/Excel imports don't extract this metadata, so use existing
                            // account's metadata
                            accountInfo.setPaymentDueDate(account.getPaymentDueDate());
                            accountInfo.setMinimumPaymentDue(account.getMinimumPaymentDue());
                            accountInfo.setRewardPoints(account.getRewardPoints());

                            LOGGER.info(
                                    "✅ Matched detected account to existing account: {} (accountId: {})",
                                    account.getAccountName(),
                                    matchedAccountId);
                        } else {
                            // Matched account not found or doesn't belong to user - use detected
                            // account
                            LOGGER.warn(
                                    "⚠️ Matched account ID '{}' not found or doesn't belong to user - using detected account info",
                                    matchedAccountId);
                            accountInfo.setAccountName(
                                    importResult.getDetectedAccount().getAccountName());
                            accountInfo.setInstitutionName(
                                    importResult.getDetectedAccount().getInstitutionName());
                            accountInfo.setAccountType(
                                    importResult.getDetectedAccount().getAccountType());
                            accountInfo.setAccountSubtype(
                                    importResult.getDetectedAccount().getAccountSubtype());
                            accountInfo.setAccountNumber(
                                    importResult.getDetectedAccount().getAccountNumber());
                            accountInfo.setCardNumber(
                                    importResult.getDetectedAccount().getCardNumber());
                            accountInfo.setBalance(
                                    importResult
                                            .getDetectedAccount()
                                            .getBalance()); // Include detected balance
                            accountInfo.setMatchedAccountId(null); // Clear invalid match

                            // CSV/Excel imports don't extract credit card metadata - set to null
                            accountInfo.setPaymentDueDate(null);
                            accountInfo.setMinimumPaymentDue(null);
                            accountInfo.setRewardPoints(null);
                        }
                    } else {
                        // No match found - use detected account info
                        accountInfo.setAccountName(
                                importResult.getDetectedAccount().getAccountName());
                        accountInfo.setInstitutionName(
                                importResult.getDetectedAccount().getInstitutionName());
                        accountInfo.setAccountType(
                                importResult.getDetectedAccount().getAccountType());
                        accountInfo.setAccountSubtype(
                                importResult.getDetectedAccount().getAccountSubtype());
                        accountInfo.setAccountNumber(
                                importResult.getDetectedAccount().getAccountNumber());
                        accountInfo.setCardNumber(
                                importResult.getDetectedAccount().getCardNumber());
                        accountInfo.setBalance(
                                importResult
                                        .getDetectedAccount()
                                        .getBalance()); // Include detected balance
                        accountInfo.setMatchedAccountId(null);

                        // CSV/Excel imports don't extract credit card metadata - set to null
                        accountInfo.setPaymentDueDate(null);
                        accountInfo.setMinimumPaymentDue(null);
                        accountInfo.setRewardPoints(null);
                    }

                    response.setDetectedAccount(accountInfo);
                }

                // Log response details
                LOGGER.info(
                        "📥 CSV Preview Response - Total parsed: {}, Transactions: {}, Errors: {}, Detected account: {} (institution: {}, type: {}, number: {})",
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
            LOGGER.error("CSV preview failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to preview CSV: " + e.getMessage());
        }
    }

    @PostMapping("/import-csv")
    public ResponseEntity<BatchImportResponse> importCSV(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(required = false) final String accountId,
            @RequestParam(required = false) final String password,
            @RequestParam(required = false) final String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;

            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".csv";
                LOGGER.error(
                        "❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'",
                        originalFilename);
            }

            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            final boolean isUUIDFilename =
                    originalFilename.matches(
                            "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                LOGGER.warn(
                        "⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. "
                                + "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.",
                        originalFilename);
            }

            LOGGER.info(
                    "📁 CSV Import - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})",
                    originalFilename,
                    fromParameter,
                    file.getOriginalFilename(),
                    isUUIDFilename);

            // Log multipart request details for debugging
            LOGGER.info(
                    "📤 CSV Import Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', AccountId: '{}', Has password: {}",
                    filename,
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getContentType(),
                    accountId,
                    password != null && !password.isEmpty());

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), CSV_EXTENSIONS);

            // Parse and import CSV - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final CSVImportService.ImportResult importResult =
                        csvImportService.parseCSV(
                                inputStream, originalFilename, user.getUserId(), password);

                // CRITICAL: Auto-create account if detected but not matched
                String accountIdToUse = accountId; // Use provided accountId if available
                LOGGER.info(
                        "🔍 [Non-paginated Import] Account creation check - provided accountId: '{}', detectedAccount: {}, matchedAccountId: '{}'",
                        accountId,
                        importResult.getDetectedAccount() != null ? "present" : "null",
                        importResult.getMatchedAccountId());

                if (accountIdToUse == null || accountIdToUse.isBlank()) {
                    if (importResult.getMatchedAccountId() != null
                            && !importResult.getMatchedAccountId().isBlank()) {
                        // Account was matched during preview - verify it exists and use it
                        final Optional<AccountTable> matchedAccount =
                                accountRepository.findById(importResult.getMatchedAccountId());
                        if (matchedAccount.isPresent()
                                && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            accountIdToUse = importResult.getMatchedAccountId();
                            LOGGER.info(
                                    "✅ [Non-paginated Import] Using matched account ID from preview: '{}'",
                                    accountIdToUse);
                        } else {
                            LOGGER.warn(
                                    "⚠️ [Non-paginated Import] Matched account ID '{}' from preview not found or doesn't belong to user - will auto-create instead",
                                    importResult.getMatchedAccountId());
                            // Fall through to auto-create logic
                        }
                    }

                    // Auto-create if no account ID has been set and account is detected
                    // CRITICAL FIX: Only auto-create if detected account has meaningful information
                    // Don't create accounts when all fields are null/empty - use pseudo account
                    // instead
                    if ((accountIdToUse == null || accountIdToUse.isBlank())
                            && importResult.getDetectedAccount() != null) {
                        // Check if detected account has any meaningful information before
                        // attempting creation
                        final AccountDetectionService.DetectedAccount detected =
                                importResult.getDetectedAccount();
                        final boolean hasAccountInfo =
                                (detected.getInstitutionName() != null
                                        && !detected.getInstitutionName().isBlank())
                                        || (detected.getAccountName() != null
                                        && !detected.getAccountName().isBlank())
                                        || (detected.getAccountNumber() != null
                                        && !detected.getAccountNumber().isBlank())
                                        || (detected.getAccountType() != null
                                        && !detected.getAccountType().isBlank())
                                        || (detected.getMatchedAccountId() != null
                                        && !detected.getMatchedAccountId()
                                        .trim()
                                        .isEmpty());

                        if (hasAccountInfo) {
                            LOGGER.info(
                                    "📝 [Non-paginated Import] Attempting to auto-create account for detected account: name='{}', institution='{}', type='{}'",
                                    detected.getAccountName(),
                                    detected.getInstitutionName(),
                                    detected.getAccountType());
                            accountIdToUse = autoCreateAccountIfDetected(user, detected);
                            if (accountIdToUse != null && !accountIdToUse.isBlank()) {
                                LOGGER.info(
                                        "✅ [Non-paginated Import] Auto-created account '{}' for detected account: {} (institution: {}, type: {})",
                                        accountIdToUse,
                                        detected.getAccountName(),
                                        detected.getInstitutionName(),
                                        detected.getAccountType());
                            } else {
                                LOGGER.info(
                                        "ℹ️ [Non-paginated Import] Auto-creation skipped - detected account has no meaningful information. Transactions will use pseudo account.");
                            }
                        } else {
                            LOGGER.info(
                                    "ℹ️ [Non-paginated Import] Detected account has no meaningful information (all fields null/empty). Skipping account creation. Transactions will use pseudo account.");
                        }
                    }
                }

                // Update all transactions with the account ID
                if (accountIdToUse != null && !accountIdToUse.isBlank()) {
                    for (final CSVImportService.ParsedTransaction parsed :
                            importResult.getTransactions()) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }

                // Import transactions - use original filename
                return processBatchImport(
                        user, importResult.getTransactions(), "CSV", originalFilename);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("CSV import failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to import CSV: " + e.getMessage());
        }
    }

    /**
     * Paginated CSV Import - Import transactions in chunks to avoid timeouts This endpoint allows
     * importing large files by processing transactions in pages
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
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "100") final int size,
            @RequestParam(required = false) final String accountId,
            @RequestParam(required = false) final String password,
            @RequestParam(required = false) final String filename,
            @RequestParam(required = false) final String previewCategoriesJson) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

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
            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".csv";
            }

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), CSV_EXTENSIONS);

            // CRITICAL: Parse preview categories from JSON if provided
            // Use preview categories if account matches preview account
            List<ImportCategoryPreservationRequest.PreviewCategory> previewCategories = null;
            String previewAccountId = null;
            if (previewCategoriesJson != null && !previewCategoriesJson.isBlank()) {
                try {
                    final ImportCategoryPreservationRequest categoryPreservation =
                            objectMapper.readValue(
                                    previewCategoriesJson, ImportCategoryPreservationRequest.class);

                    if (categoryPreservation != null
                            && categoryPreservation.getPreviewCategories() != null) {
                        // Check if account matches preview account
                        final String previewAcctId = categoryPreservation.getPreviewAccountId();
                        final String importAcctId = accountId;

                        // If both are null/empty, they match (no account specified)
                        boolean accountsMatch =
                                (previewAcctId == null || previewAcctId.isBlank())
                                        && (importAcctId == null || importAcctId.isBlank());

                        // If both are provided, check if they match
                        if (!accountsMatch && previewAcctId != null && importAcctId != null) {
                            accountsMatch = previewAcctId.equals(importAcctId);
                        }

                        if (accountsMatch) {
                            previewCategories = categoryPreservation.getPreviewCategories();
                            previewAccountId = previewAcctId;
                            LOGGER.info(
                                    "📋 Using preview categories for import (account matches: '{}', {} categories provided)",
                                    previewAccountId,
                                    previewCategories.size());
                        } else {
                            LOGGER.info(
                                    "📋 Preview categories provided but account changed (preview: '{}', import: '{}') - will re-categorize",
                                    previewAcctId,
                                    importAcctId);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn(
                            "Failed to parse preview categories JSON: {}. Will re-categorize transactions.",
                            e.getMessage());
                }
            }

            // Parse CSV file
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final CSVImportService.ImportResult importResult =
                        csvImportService.parseCSV(
                                inputStream,
                                originalFilename,
                                user.getUserId(),
                                password,
                                previewCategories,
                                previewAccountId);

                final List<CSVImportService.ParsedTransaction> allTransactions =
                        importResult.getTransactions();
                final int totalTransactions = allTransactions.size();
                final int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate page number
                if (page >= totalPages && totalPages > 0) {
                    throw new AppException(
                            ErrorCode.INVALID_INPUT,
                            String.format(
                                    "Page %d is out of range. Total pages: %d", page, totalPages));
                }

                // Get transactions for this page
                final int startIndex = page * size;
                final int endIndex = Math.min(startIndex + size, totalTransactions);
                final List<CSVImportService.ParsedTransaction> chunk =
                        allTransactions.subList(startIndex, endIndex);

                LOGGER.info(
                        "📦 Importing CSV chunk: page {} (transactions {} to {} of {})",
                        page,
                        startIndex + 1,
                        endIndex,
                        totalTransactions);

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // Only create on first page (page 0) to avoid creating multiple accounts
                // Reuse the same account across all pages for paginated imports
                String accountIdToUse = accountId;
                LOGGER.info(
                        "🔍 [Paginated Import Page {}] Account creation check - provided accountId: '{}', detectedAccount: {}, matchedAccountId: '{}'",
                        page,
                        accountId,
                        importResult.getDetectedAccount() != null
                                ? "present (name: '"
                                        + importResult.getDetectedAccount().getAccountName()
                                        + "', institution: '"
                                        + importResult.getDetectedAccount().getInstitutionName()
                                        + "')"
                                : "null",
                        importResult.getMatchedAccountId());

                if (accountIdToUse == null || accountIdToUse.isBlank()) {
                    final List<AccountTable> existingAccounts =
                            accountRepository.findByUserId(user.getUserId());
                    LOGGER.info(
                            "🔍 [Paginated Import Page {}] STEP 1: Checking existing accounts - Found {} accounts for user {}",
                            page,
                            existingAccounts != null ? existingAccounts.size() : 0,
                            user.getUserId());
                    if (existingAccounts != null && !existingAccounts.isEmpty()) {
                        for (final AccountTable acc : existingAccounts) {
                            LOGGER.info(
                                    "   📋 Existing account: ID='{}', name='{}', institution='{}', type='{}', createdAt='{}'",
                                    acc.getAccountId(),
                                    acc.getAccountName(),
                                    acc.getInstitutionName(),
                                    acc.getAccountType(),
                                    acc.getCreatedAt());
                        }
                    }

                    // Step 1: Check if user has manually created an account matching the detected
                    // account
                    // (This includes both manually created accounts and previously auto-created
                    // accounts)
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null
                            && existingAccounts != null
                            && !existingAccounts.isEmpty()) {
                        final AccountDetectionService.DetectedAccount detected =
                                importResult.getDetectedAccount();

                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null
                                && !detected.getAccountNumber().isBlank()) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountNumber()
                                                                    .equals(acc.getAccountNumber()))
                                            .findFirst()
                                            .orElse(null);
                        }

                        // If no match by account number, try to match by account name and
                        // institution
                        if (matchingAccount == null
                                && detected.getAccountName() != null
                                && detected.getInstitutionName() != null) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getAccountName())
                                                                    && detected.getInstitutionName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getInstitutionName()))
                                            .findFirst()
                                            .orElse(null);
                        }
                    }

                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching
                        // account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        LOGGER.info(
                                "📝 Using existing account '{}' (name: '{}', institution: '{}') for import (page {})",
                                accountIdToUse,
                                matchingAccount.getAccountName(),
                                matchingAccount.getInstitutionName(),
                                page);
                    } else if (importResult.getMatchedAccountId() != null
                            && !importResult.getMatchedAccountId().isBlank()) {
                        // Account was matched during preview - verify it exists and use it
                        final Optional<AccountTable> matchedAccount =
                                accountRepository.findById(importResult.getMatchedAccountId());
                        if (matchedAccount.isPresent()
                                && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            accountIdToUse = importResult.getMatchedAccountId();
                            LOGGER.info(
                                    "📝 Using matched account ID from preview: '{}' (page {})",
                                    accountIdToUse,
                                    page);
                        } else {
                            LOGGER.warn(
                                    "⚠️ Matched account ID '{}' from preview not found or doesn't belong to user - will auto-create instead (page {})",
                                    importResult.getMatchedAccountId(),
                                    page);
                            // Fall through to auto-create logic
                        }
                    }

                    // CRITICAL: Auto-create account if:
                    // 1. No account ID has been set yet (accountIdToUse is still null/empty)
                    // 2. Account was detected AND has meaningful information
                    // 3. We're on the first page (page 0) OR we're on a subsequent page but no
                    // account was found
                    if (accountIdToUse == null || accountIdToUse.isBlank()) {
                        if (importResult.getDetectedAccount() != null) {
                            // CRITICAL FIX: Check if detected account has meaningful information
                            // before attempting creation
                            final AccountDetectionService.DetectedAccount detectedAccount =
                                    importResult.getDetectedAccount();
                            final boolean hasAccountInfo =
                                    (detectedAccount.getInstitutionName() != null
                                            && !detectedAccount
                                            .getInstitutionName()
                                            .trim()
                                            .isEmpty())
                                            || (detectedAccount.getAccountName() != null
                                            && !detectedAccount
                                            .getAccountName()
                                            .trim()
                                            .isEmpty())
                                            || (detectedAccount.getAccountNumber() != null
                                            && !detectedAccount
                                            .getAccountNumber()
                                            .trim()
                                            .isEmpty())
                                            || (detectedAccount.getAccountType() != null
                                            && !detectedAccount
                                            .getAccountType()
                                            .trim()
                                            .isEmpty())
                                            || (detectedAccount.getMatchedAccountId() != null
                                            && !detectedAccount
                                            .getMatchedAccountId()
                                            .trim()
                                            .isEmpty());

                            if (hasAccountInfo) {
                                // Account was detected with meaningful information - try to create
                                // or reuse
                                if (page == 0) {
                                    // First page: Always try to auto-create if account is detected
                                    // with meaningful info
                                    LOGGER.info(
                                            "📝 [Page 0] Attempting to auto-create account for detected account: name='{}', institution='{}', type='{}'",
                                            detectedAccount.getAccountName(),
                                            detectedAccount.getInstitutionName(),
                                            detectedAccount.getAccountType());
                                    LOGGER.info(
                                            "🔨 [Page 0] STEP 2: Calling autoCreateAccountIfDetected...");
                                    accountIdToUse =
                                            autoCreateAccountIfDetected(user, detectedAccount);
                                    if (accountIdToUse != null
                                            && !accountIdToUse.isBlank()) {
                                        LOGGER.info(
                                                "✅ [Page 0] STEP 3: Account created successfully - ID='{}'",
                                                accountIdToUse);
                                        // Verify the account is retrievable
                                        final Optional<AccountTable> createdAccount =
                                                accountRepository.findById(accountIdToUse);
                                        if (createdAccount.isPresent()) {
                                            final AccountTable acc = createdAccount.get();
                                            LOGGER.info(
                                                    "✅ [Page 0] STEP 4: Account verification - ID='{}', name='{}', institution='{}', type='{}', createdAt='{}'",
                                                    acc.getAccountId(),
                                                    acc.getAccountName(),
                                                    acc.getInstitutionName(),
                                                    acc.getAccountType(),
                                                    acc.getCreatedAt());
                                        } else {
                                            LOGGER.error(
                                                    "❌ [Page 0] STEP 4: Account verification FAILED - Account '{}' not found in repository!",
                                                    accountIdToUse);
                                        }
                                    } else {
                                        LOGGER.info(
                                                "ℹ️ [Page 0] STEP 3: Auto-creation skipped - detected account has no meaningful information. Transactions will use pseudo account.");
                                    }
                                } else {
                                    LOGGER.info(
                                            "ℹ️ [Page 0] Detected account has no meaningful information (all fields null/empty). Skipping account creation. Transactions will use pseudo account.");
                                }
                            } else if (page > 0) {
                                LOGGER.info(
                                        "🔍 [Page {}] STEP 2: Processing subsequent page - checking for account reuse",
                                        page);
                                // Subsequent pages: Try to reuse the matched account from preview
                                // first
                                // This ensures we use the same account across all pages
                                if (importResult.getMatchedAccountId() != null
                                        && !importResult.getMatchedAccountId().isBlank()) {
                                    // CRITICAL: First try to use the matched account from preview
                                    final Optional<AccountTable> matchedAccount =
                                            accountRepository.findById(
                                                    importResult.getMatchedAccountId());
                                    if (matchedAccount.isPresent()
                                            && matchedAccount
                                                    .get()
                                                    .getUserId()
                                                    .equals(user.getUserId())) {
                                        accountIdToUse = importResult.getMatchedAccountId();
                                        LOGGER.info(
                                                "✅ [Page {}] STEP 2a: Using matched account ID from preview: '{}'",
                                                page,
                                                accountIdToUse);
                                    } else {
                                        LOGGER.warn(
                                                "⚠️ [Page {}] STEP 2a: Matched account ID '{}' from preview not found or doesn't belong to user",
                                                page,
                                                importResult.getMatchedAccountId());
                                    }
                                }

                                // If no matched account, try to match by detected account
                                // attributes
                                if ((accountIdToUse == null || accountIdToUse.isBlank())
                                        && existingAccounts != null
                                        && !existingAccounts.isEmpty()) {
                                    LOGGER.info(
                                            "🔍 [Page {}] STEP 2b: Found {} existing accounts, checking for match by detected account",
                                            page,
                                            existingAccounts.size());
                                    final AccountDetectionService.DetectedAccount detectedAccountForPage =
                                            importResult.getDetectedAccount();
                                    LOGGER.info(
                                            "🔍 [Page {}] STEP 2c: Detected account info - name='{}', institution='{}', type='{}', number='{}'",
                                            page,
                                            detectedAccountForPage != null
                                                    ? detectedAccountForPage.getAccountName()
                                                    : "null",
                                            detectedAccountForPage != null
                                                    ? detectedAccountForPage.getInstitutionName()
                                                    : "null",
                                            detectedAccountForPage != null
                                                    ? detectedAccountForPage.getAccountType()
                                                    : "null",
                                            detectedAccountForPage != null
                                                            && detectedAccountForPage
                                                                            .getAccountNumber()
                                                                    != null
                                                    ? "***"
                                                            + detectedAccountForPage
                                                                    .getAccountNumber()
                                                                    .substring(
                                                                            Math.max(
                                                                                    0,
                                                                                    detectedAccountForPage
                                                                                                    .getAccountNumber()
                                                                                                    .length()
                                                                                            - 4))
                                                    : "null");

                                    if (detectedAccountForPage != null) {
                                        LOGGER.info(
                                                "🔍 [Page {}] STEP 2d: Most recent account not found, trying to match by detected account attributes",
                                                page);
                                        // CRITICAL: Try multiple matching strategies for better
                                        // account reuse (deterministic, no time restrictions)
                                        // 1. First try by account number (most reliable)
                                        if (detectedAccountForPage.getAccountNumber() != null
                                                && !detectedAccountForPage
                                                        .getAccountNumber()
                                                        .trim()
                                                        .isEmpty()) {
                                            final String normalizedDetectedNumber =
                                                    normalizeAccountNumber(
                                                            detectedAccountForPage
                                                                    .getAccountNumber());
                                            LOGGER.info(
                                                    "🔍 [Page {}] STEP 2d-1: Trying to match by account number '{}'",
                                                    page,
                                                    normalizedDetectedNumber);
                                            matchingAccount =
                                                    existingAccounts.stream()
                                                            .filter(
                                                                    acc -> {
                                                                        if (acc.getAccountNumber()
                                                                                == null) {
                                                                            return false;
                                                                        }
                                                                        final String normalizedAccNumber =
                                                                                normalizeAccountNumber(
                                                                                        acc
                                                                                                .getAccountNumber());
                                                                        return normalizedDetectedNumber
                                                                                .equals(
                                                                                        normalizedAccNumber);
                                                                    })
                                                            .max(
                                                                    Comparator.comparing(
                                                                            (AccountTable acc) ->
                                                                                    acc
                                                                                                            .getCreatedAt()
                                                                                                    != null
                                                                                            ? acc
                                                                                                    .getCreatedAt()
                                                                                            : Instant
                                                                                                    .ofEpochMilli(
                                                                                                            0)))
                                                            .orElse(null);
                                            if (matchingAccount != null) {
                                                LOGGER.info(
                                                        "✅ [Page {}] STEP 2d-1: Found match by account number - ID='{}'",
                                                        page,
                                                        matchingAccount.getAccountId());
                                            }
                                        }

                                        // 2. If no match by number, try by institution and account
                                        // type (account name might differ due to generation)
                                        if (matchingAccount == null
                                                && detectedAccountForPage.getInstitutionName()
                                                        != null
                                                && detectedAccountForPage.getAccountType()
                                                        != null) {
                                            LOGGER.info(
                                                    "🔍 [Page {}] STEP 2d-2: Trying to match by institution '{}' and type '{}'",
                                                    page,
                                                    detectedAccountForPage.getInstitutionName(),
                                                    detectedAccountForPage.getAccountType());
                                            matchingAccount =
                                                    existingAccounts.stream()
                                                            .filter(
                                                                    acc -> {
                                                                        final boolean institutionMatch =
                                                                                detectedAccountForPage
                                                                                        .getInstitutionName()
                                                                                        != null
                                                                                        && detectedAccountForPage
                                                                                        .getInstitutionName()
                                                                                        .equals(
                                                                                                acc
                                                                                                        .getInstitutionName());
                                                                        final boolean typeMatch =
                                                                                detectedAccountForPage
                                                                                        .getAccountType()
                                                                                        != null
                                                                                        && detectedAccountForPage
                                                                                        .getAccountType()
                                                                                        .equals(
                                                                                                acc
                                                                                                        .getAccountType());
                                                                        return institutionMatch
                                                                                && typeMatch;
                                                                    })
                                                            .max(
                                                                    Comparator.comparing(
                                                                            (AccountTable acc) ->
                                                                                    acc
                                                                                                            .getCreatedAt()
                                                                                                    != null
                                                                                            ? acc
                                                                                                    .getCreatedAt()
                                                                                            : Instant
                                                                                                    .ofEpochMilli(
                                                                                                            0)))
                                                            .orElse(null);
                                            if (matchingAccount != null) {
                                                LOGGER.info(
                                                        "✅ [Page {}] STEP 2d-2: Found match by institution/type - ID='{}'",
                                                        page,
                                                        matchingAccount.getAccountId());
                                            }
                                        }

                                        // 3. Fallback: Try by account name and institution
                                        // (original logic)
                                        if (matchingAccount == null) {
                                            LOGGER.info(
                                                    "🔍 [Page {}] STEP 2d-3: Trying to match by account name '{}' and institution '{}'",
                                                    page,
                                                    detectedAccountForPage.getAccountName(),
                                                    detectedAccountForPage.getInstitutionName());
                                            matchingAccount =
                                                    existingAccounts.stream()
                                                            .filter(
                                                                    acc -> {
                                                                        // Match by account name and
                                                                        // institution
                                                                        final boolean nameMatch =
                                                                                detectedAccountForPage
                                                                                        .getAccountName()
                                                                                        != null
                                                                                        && detectedAccountForPage
                                                                                        .getAccountName()
                                                                                        .equals(
                                                                                                acc
                                                                                                        .getAccountName());
                                                                        final boolean institutionMatch =
                                                                                detectedAccountForPage
                                                                                        .getInstitutionName()
                                                                                        != null
                                                                                        && detectedAccountForPage
                                                                                        .getInstitutionName()
                                                                                        .equals(
                                                                                                acc
                                                                                                        .getInstitutionName());
                                                                        return nameMatch
                                                                                && institutionMatch;
                                                                    })
                                                            .max(
                                                                    Comparator.comparing(
                                                                            (AccountTable acc) ->
                                                                                    acc
                                                                                                            .getCreatedAt()
                                                                                                    != null
                                                                                            ? acc
                                                                                                    .getCreatedAt()
                                                                                            : Instant
                                                                                                    .ofEpochMilli(
                                                                                                            0)))
                                                            .orElse(null);
                                            if (matchingAccount != null) {
                                                LOGGER.info(
                                                        "✅ [Page {}] STEP 2d-3: Found match by name/institution - ID='{}'",
                                                        page,
                                                        matchingAccount.getAccountId());
                                            }
                                        }

                                        if (matchingAccount != null) {
                                            accountIdToUse = matchingAccount.getAccountId();
                                            LOGGER.info(
                                                    "✅ [Page {}] STEP 2e: Using matched account - ID='{}', name='{}', institution='{}'",
                                                    page,
                                                    accountIdToUse,
                                                    matchingAccount.getAccountName(),
                                                    matchingAccount.getInstitutionName());
                                        } else {
                                            LOGGER.warn(
                                                    "⚠️ [Page {}] STEP 2e: No account matched by detected attributes",
                                                    page);
                                        }
                                    }
                                } else {
                                    LOGGER.warn(
                                            "⚠️ [Page {}] STEP 2: existingAccounts is null or empty",
                                            page);
                                }
                            }

                            // If still no account found, fall back to reusing most recent account
                            // or creating (only on page 0)
                            if (accountIdToUse == null || accountIdToUse.isBlank()) {
                                LOGGER.info(
                                        "🔍 [Page {}] STEP 3: accountIdToUse is still null, checking fallback options",
                                        page);
                                if (page > 0
                                        && existingAccounts != null
                                        && !existingAccounts.isEmpty()) {
                                    LOGGER.info(
                                            "🔍 [Page {}] STEP 3a: Final fallback - trying to reuse most recent account from {} existing accounts",
                                            page,
                                            existingAccounts.size());
                                    // CRITICAL: On page > 0, ALWAYS reuse the most recently created
                                    // account
                                    // Never create a new account on subsequent pages
                                    // (deterministic, no time restrictions)
                                    final AccountTable mostRecentAccount =
                                            existingAccounts.stream()
                                                    .filter(acc -> acc.getCreatedAt() != null)
                                                    .max(
                                                            Comparator.comparing(
                                                                    (AccountTable acc) ->
                                                                            acc.getCreatedAt()
                                                                                    != null
                                                                                    ? acc
                                                                                    .getCreatedAt()
                                                                                    : Instant
                                                                                    .ofEpochMilli(
                                                                                            0)))
                                                    .orElse(null);

                                    if (mostRecentAccount != null) {
                                        accountIdToUse = mostRecentAccount.getAccountId();
                                        LOGGER.info(
                                                "✅ [Page {}] STEP 3b: Final fallback SUCCESS - Reusing most recent account - ID='{}', name='{}', createdAt='{}'",
                                                page,
                                                accountIdToUse,
                                                mostRecentAccount.getAccountName(),
                                                mostRecentAccount.getCreatedAt());
                                    } else {
                                        LOGGER.error(
                                                "❌ [Page {}] STEP 3b: Final fallback FAILED - No account with createdAt found in {} accounts",
                                                page,
                                                existingAccounts.size());
                                        for (final AccountTable acc : existingAccounts) {
                                            LOGGER.error(
                                                    "   Account: ID='{}', name='{}', createdAt='{}'",
                                                    acc.getAccountId(),
                                                    acc.getAccountName(),
                                                    acc.getCreatedAt());
                                        }
                                    }
                                } else if (page == 0) {
                                    // CRITICAL FIX: Don't create generic accounts - use pseudo
                                    // account instead
                                    // This prevents creating accounts with "Unknown" or "Imported
                                    // Account" names
                                    LOGGER.info(
                                            "🔍 [Page 0] STEP 3: No account detected and no account ID provided - transactions will use pseudo account");
                                    // Leave accountIdToUse as null - TransactionService will use
                                    // pseudo account
                                    accountIdToUse = null;
                                    LOGGER.info(
                                            "ℹ️ [Page 0] STEP 3: accountIdToUse set to null - transactions will use pseudo account (Manual Transactions)");
                                }
                            }
                        }
                    }
                }

                // Update chunk transactions with account ID
                if (accountIdToUse != null && !accountIdToUse.isBlank()) {
                    for (final CSVImportService.ParsedTransaction parsed : chunk) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }

                // Import this chunk
                final ResponseEntity<BatchImportResponse> importResponseEntity =
                        processBatchImport(user, chunk, "CSV", originalFilename);
                final BatchImportResponse importResponse = importResponseEntity.getBody();

                // Build chunk response
                final ChunkImportResponse response = new ChunkImportResponse();
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
            LOGGER.error("CSV chunk import failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to import CSV chunk: " + e.getMessage());
        }
    }

    // MARK: - Excel Import Endpoints

    @PostMapping("/import-excel/preview")
    public ResponseEntity<ExcelImportPreviewResponse> previewExcel(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) final String filename,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "100") final int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;

            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".xlsx";
                LOGGER.error(
                        "❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'",
                        originalFilename);
            }

            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            final boolean isUUIDFilename =
                    originalFilename.matches(
                            "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                LOGGER.warn(
                        "⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. "
                                + "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.",
                        originalFilename);
            }

            LOGGER.info(
                    "📁 Excel Preview - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})",
                    originalFilename,
                    fromParameter,
                    file.getOriginalFilename(),
                    isUUIDFilename);

            // Log multipart request details for debugging
            LOGGER.info(
                    "📤 Excel Preview Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}'",
                    filename,
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getContentType());

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), EXCEL_EXTENSIONS);

            // Parse Excel - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final ExcelImportService.ImportResult importResult =
                        excelImportService.parseExcel(
                                inputStream, originalFilename, user.getUserId(), null);

                // Build preview response (similar to CSV)
                final List<DuplicateDetectionService.ParsedTransaction> parsedForDuplicateCheck =
                        new ArrayList<>();

                for (final CSVImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                    final DuplicateDetectionService.ParsedTransaction dupTx =
                            new DuplicateDetectionService.ParsedTransaction(
                                    parsed.getDate(),
                                    parsed.getAmount(),
                                    parsed.getDescription(),
                                    parsed.getMerchantName());
                    dupTx.setTransactionId(parsed.getTransactionId());
                    parsedForDuplicateCheck.add(dupTx);
                }

                final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                        duplicateDetectionService.detectDuplicates(
                                user.getUserId(), parsedForDuplicateCheck);

                // P1: Pagination - Build transaction maps with duplicate info (with pagination)
                final int totalTransactions = importResult.getTransactions().size();
                final int startIndex = page * size;
                final int endIndex = Math.min(startIndex + size, totalTransactions);
                final int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate pagination parameters
                if (page < 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
                }
                if (size < 1 || size > 1000) {
                    throw new AppException(
                            ErrorCode.INVALID_INPUT, "Page size must be between 1 and 1000");
                }

                final List<Map<String, Object>> paginatedTransactions = new ArrayList<>();
                for (int i = startIndex; i < endIndex; i++) {
                    final CSVImportService.ParsedTransaction parsed =
                            importResult.getTransactions().get(i);
                    final Map<String, Object> txMap = buildTransactionMap(parsed, duplicates.get(i));
                    paginatedTransactions.add(txMap);
                }

                final ExcelImportPreviewResponse response = new ExcelImportPreviewResponse();
                response.setTotalParsed(importResult.getSuccessCount());
                response.setTransactions(paginatedTransactions);
                response.setPage(page);
                response.setSize(size);
                response.setTotalPages(totalPages);
                response.setTotalElements(totalTransactions);
                DetectedAccountInfo accountInfo = null;
                if (importResult.getDetectedAccount() != null) {
                    accountInfo = new DetectedAccountInfo();

                    // CRITICAL: If account was matched, use the matched account's details instead
                    // of detected account
                    // This ensures iOS shows the existing account information, not the detected
                    // account
                    final String matchedAccountId = importResult.getMatchedAccountId();
                    if (matchedAccountId != null && !matchedAccountId.isBlank()) {
                        // Fetch the matched account from database
                        final Optional<AccountTable> matchedAccount =
                                accountRepository.findById(matchedAccountId);
                        if (matchedAccount.isPresent()
                                && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            final AccountTable account = matchedAccount.get();
                            // Use matched account's details
                            accountInfo.setAccountName(account.getAccountName());
                            accountInfo.setInstitutionName(account.getInstitutionName());
                            accountInfo.setAccountType(account.getAccountType());
                            accountInfo.setAccountSubtype(account.getAccountSubtype());
                            accountInfo.setAccountNumber(account.getAccountNumber());
                            accountInfo.setCardNumber(
                                    null); // Card number not stored in AccountTable
                            accountInfo.setBalance(
                                    account.getBalance()); // Include balance from existing account
                            accountInfo.setMatchedAccountId(matchedAccountId);

                            // CRITICAL: Include credit card metadata from existing account (if
                            // available)
                            // Excel imports don't extract this metadata, so use existing account's
                            // metadata
                            accountInfo.setPaymentDueDate(account.getPaymentDueDate());
                            accountInfo.setMinimumPaymentDue(account.getMinimumPaymentDue());
                            accountInfo.setRewardPoints(account.getRewardPoints());

                            LOGGER.info(
                                    "✅ [Excel] Matched detected account to existing account: {} (accountId: {})",
                                    account.getAccountName(),
                                    matchedAccountId);
                        } else {
                            // Matched account not found or doesn't belong to user - use detected
                            // account
                            LOGGER.warn(
                                    "⚠️ [Excel] Matched account ID '{}' not found or doesn't belong to user - using detected account info",
                                    matchedAccountId);
                            accountInfo.setAccountName(
                                    importResult.getDetectedAccount().getAccountName());
                            accountInfo.setInstitutionName(
                                    importResult.getDetectedAccount().getInstitutionName());
                            accountInfo.setAccountType(
                                    importResult.getDetectedAccount().getAccountType());
                            accountInfo.setAccountSubtype(
                                    importResult.getDetectedAccount().getAccountSubtype());
                            accountInfo.setAccountNumber(
                                    importResult.getDetectedAccount().getAccountNumber());
                            accountInfo.setCardNumber(
                                    importResult.getDetectedAccount().getCardNumber());
                            accountInfo.setBalance(
                                    importResult
                                            .getDetectedAccount()
                                            .getBalance()); // Include detected balance
                            accountInfo.setMatchedAccountId(null); // Clear invalid match

                            // Excel imports don't extract credit card metadata - set to null
                            accountInfo.setPaymentDueDate(null);
                            accountInfo.setMinimumPaymentDue(null);
                            accountInfo.setRewardPoints(null);
                        }
                    } else {
                        // No match found - use detected account info
                        accountInfo.setAccountName(
                                importResult.getDetectedAccount().getAccountName());
                        accountInfo.setInstitutionName(
                                importResult.getDetectedAccount().getInstitutionName());
                        accountInfo.setAccountType(
                                importResult.getDetectedAccount().getAccountType());
                        accountInfo.setAccountSubtype(
                                importResult.getDetectedAccount().getAccountSubtype());
                        accountInfo.setAccountNumber(
                                importResult.getDetectedAccount().getAccountNumber());
                        accountInfo.setCardNumber(
                                importResult.getDetectedAccount().getCardNumber());
                        accountInfo.setBalance(
                                importResult
                                        .getDetectedAccount()
                                        .getBalance()); // Include detected balance
                        accountInfo.setMatchedAccountId(null);

                        // Excel imports don't extract credit card metadata - set to null
                        accountInfo.setPaymentDueDate(null);
                        accountInfo.setMinimumPaymentDue(null);
                        accountInfo.setRewardPoints(null);
                    }

                    response.setDetectedAccount(accountInfo);
                }

                // Log response details
                LOGGER.info(
                        "📥 Excel Preview Response - Total parsed: {}, Transactions: {}, Detected account: {} (institution: {}, type: {}, number: {})",
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
            LOGGER.error("Excel preview failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to preview Excel: " + e.getMessage());
        }
    }

    @PostMapping("/import-excel")
    public ResponseEntity<BatchImportResponse> importExcel(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(required = false) final String accountId,
            @RequestParam(required = false) final String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;

            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".xlsx";
                LOGGER.error(
                        "❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'",
                        originalFilename);
            }

            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            final boolean isUUIDFilename =
                    originalFilename.matches(
                            "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                LOGGER.warn(
                        "⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. "
                                + "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.",
                        originalFilename);
            }

            LOGGER.info(
                    "📁 Excel Import - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})",
                    originalFilename,
                    fromParameter,
                    file.getOriginalFilename(),
                    isUUIDFilename);

            // Log multipart request details for debugging
            LOGGER.info(
                    "📤 Excel Import Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', AccountId: '{}'",
                    filename,
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getContentType(),
                    accountId);

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), EXCEL_EXTENSIONS);

            // Parse and import Excel - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final ExcelImportService.ImportResult importResult =
                        excelImportService.parseExcel(
                                inputStream, originalFilename, user.getUserId(), null);

                return processBatchImport(
                        user, importResult.getTransactions(), "EXCEL", originalFilename);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Excel import failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to import Excel: " + e.getMessage());
        }
    }

    @PostMapping("/import-excel/chunk")
    public ResponseEntity<ChunkImportResponse> importExcelChunk(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "100") final int size,
            @RequestParam(required = false) final String accountId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) final String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

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
            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".xlsx";
            }

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), EXCEL_EXTENSIONS);

            // Parse Excel file
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final ExcelImportService.ImportResult importResult =
                        excelImportService.parseExcel(
                                inputStream, originalFilename, user.getUserId(), null);

                final List<CSVImportService.ParsedTransaction> allTransactions =
                        importResult.getTransactions();
                final int totalTransactions = allTransactions.size();
                final int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate page number
                if (page >= totalPages && totalPages > 0) {
                    throw new AppException(
                            ErrorCode.INVALID_INPUT,
                            String.format(
                                    "Page %d is out of range. Total pages: %d", page, totalPages));
                }

                // Get transactions for this page
                final int startIndex = page * size;
                final int endIndex = Math.min(startIndex + size, totalTransactions);
                final List<CSVImportService.ParsedTransaction> chunk =
                        allTransactions.subList(startIndex, endIndex);

                LOGGER.info(
                        "📦 Importing Excel chunk: page {} (transactions {} to {} of {})",
                        page,
                        startIndex + 1,
                        endIndex,
                        totalTransactions);

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // Only create on first page (page 0) to avoid creating multiple accounts
                // Reuse the same account across all pages for paginated imports
                String accountIdToUse = accountId;
                if (accountIdToUse == null || accountIdToUse.isBlank()) {
                    final List<AccountTable> existingAccounts =
                            accountRepository.findByUserId(user.getUserId());

                    // Step 1: Check if user has manually created an account matching the detected
                    // account
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null
                            && existingAccounts != null
                            && !existingAccounts.isEmpty()) {
                        final AccountDetectionService.DetectedAccount detected =
                                importResult.getDetectedAccount();

                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null
                                && !detected.getAccountNumber().isBlank()) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountNumber()
                                                                    .equals(acc.getAccountNumber()))
                                            .findFirst()
                                            .orElse(null);
                        }

                        // If no match by account number, try to match by account name and
                        // institution
                        if (matchingAccount == null
                                && detected.getAccountName() != null
                                && detected.getInstitutionName() != null) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getAccountName())
                                                                    && detected.getInstitutionName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getInstitutionName()))
                                            .findFirst()
                                            .orElse(null);
                        }
                    }

                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching
                        // account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        LOGGER.info(
                                "📝 [Excel] Using existing account '{}' (name: '{}', institution: '{}') for import (page {})",
                                accountIdToUse,
                                matchingAccount.getAccountName(),
                                matchingAccount.getInstitutionName(),
                                page);
                    } else if (importResult.getMatchedAccountId() != null
                            && !importResult.getMatchedAccountId().isBlank()) {
                        // Account was matched during preview - use it
                        accountIdToUse = importResult.getMatchedAccountId();
                        LOGGER.info(
                                "📝 [Excel] Using matched account ID from preview: '{}' (page {})",
                                accountIdToUse,
                                page);
                    } else if (importResult.getDetectedAccount() != null && page == 0) {
                        // CRITICAL: Only auto-create on first page (page 0) to avoid creating
                        // multiple accounts
                        // User hasn't manually created a matching account - auto-create it
                        accountIdToUse =
                                autoCreateAccountIfDetected(
                                        user, importResult.getDetectedAccount());
                        LOGGER.info(
                                "📝 [Excel] Auto-created account '{}' for detected account '{}' from '{}' (first page only)",
                                accountIdToUse,
                                importResult.getDetectedAccount().getAccountName(),
                                importResult.getDetectedAccount().getInstitutionName());
                    } else if (importResult.getDetectedAccount() != null && page > 0) {
                        // Subsequent pages: Try to find the account that was auto-created on first
                        // page
                        if (existingAccounts != null && !existingAccounts.isEmpty()) {
                            final AccountDetectionService.DetectedAccount detected =
                                    importResult.getDetectedAccount();
                            final Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc -> {
                                                        final boolean nameMatch =
                                                                detected.getAccountName() != null
                                                                        && detected.getAccountName()
                                                                        .equals(
                                                                                acc
                                                                                        .getAccountName());
                                                        final boolean institutionMatch =
                                                                detected.getInstitutionName()
                                                                        != null
                                                                        && detected.getInstitutionName()
                                                                        .equals(
                                                                                acc
                                                                                        .getInstitutionName());
                                                        final boolean recentlyCreated =
                                                                acc.getCreatedAt() != null
                                                                        && acc.getCreatedAt()
                                                                        .isAfter(
                                                                                fiveMinutesAgo);
                                                        return nameMatch
                                                                && institutionMatch
                                                                && recentlyCreated;
                                                    })
                                            .max(
                                                    Comparator.comparing(
                                                            (AccountTable acc) ->
                                                                    acc.getCreatedAt() != null
                                                                            ? acc.getCreatedAt()
                                                                            : Instant.ofEpochMilli(
                                                                                    0)))
                                            .orElse(null);

                            if (matchingAccount != null) {
                                accountIdToUse = matchingAccount.getAccountId();
                                LOGGER.info(
                                        "📝 [Excel] Reusing auto-created account '{}' from first page (page {})",
                                        accountIdToUse,
                                        page);
                            }
                        }
                    }
                }

                // Update chunk transactions with account ID
                if (accountIdToUse != null && !accountIdToUse.isBlank()) {
                    for (final CSVImportService.ParsedTransaction parsed : chunk) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }

                // Import this chunk
                final ResponseEntity<BatchImportResponse> importResponseEntity =
                        processBatchImport(user, chunk, "EXCEL", originalFilename);
                final BatchImportResponse importResponse = importResponseEntity.getBody();

                // Build chunk response
                final ChunkImportResponse response = new ChunkImportResponse();
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
            LOGGER.error("Excel chunk import failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to import Excel chunk: " + e.getMessage());
        }
    }

    // MARK: - PDF Import Endpoints

    @PostMapping("/import-pdf/preview")
    public ResponseEntity<PDFImportPreviewResponse> previewPDF(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) final String filename,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "100") final int size) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;

            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".pdf";
                LOGGER.error(
                        "❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'",
                        originalFilename);
            }

            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            final boolean isUUIDFilename =
                    originalFilename.matches(
                            "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                LOGGER.warn(
                        "⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. "
                                + "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.",
                        originalFilename);
            }

            LOGGER.info(
                    "📁 PDF Preview - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})",
                    originalFilename,
                    fromParameter,
                    file.getOriginalFilename(),
                    isUUIDFilename);

            // Log multipart request details for debugging
            LOGGER.info(
                    "📤 PDF Preview Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}'",
                    filename,
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getContentType());

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), PDF_EXTENSIONS);

            // Parse PDF - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final PDFImportService.ImportResult importResult =
                        pdfImportService.parsePDF(
                                inputStream, originalFilename, user.getUserId(), null);

                // Build preview response (similar to CSV/Excel)
                final List<DuplicateDetectionService.ParsedTransaction> parsedForDuplicateCheck =
                        new ArrayList<>();

                for (final PDFImportService.ParsedTransaction parsed : importResult.getTransactions()) {
                    final DuplicateDetectionService.ParsedTransaction dupTx =
                            new DuplicateDetectionService.ParsedTransaction(
                                    parsed.getDate(),
                                    parsed.getAmount(),
                                    parsed.getDescription(),
                                    parsed.getMerchantName());
                    dupTx.setTransactionId(parsed.getTransactionId());
                    parsedForDuplicateCheck.add(dupTx);
                }

                final Map<Integer, List<DuplicateDetectionService.DuplicateMatch>> duplicates =
                        duplicateDetectionService.detectDuplicates(
                                user.getUserId(), parsedForDuplicateCheck);

                // Log duplicate detection results
                final int duplicateCount = duplicates.size();
                LOGGER.info(
                        "🔍 PDF Preview - Duplicate detection: {} transactions with duplicates out of {} total",
                        duplicateCount,
                        parsedForDuplicateCheck.size());
                if (duplicateCount > 0) {
                    for (final Map.Entry<Integer, List<DuplicateDetectionService.DuplicateMatch>> entry :
                            duplicates.entrySet()) {
                        LOGGER.info(
                                "🔍 PDF Preview - Transaction index {} has {} duplicate(s): {}",
                                entry.getKey(),
                                entry.getValue().size(),
                                entry.getValue().stream()
                                        .map(
                                                m ->
                                                        String.format(
                                                                "similarity=%.2f, reason=%s",
                                                                m
                                                                        .getSimilarity(), // getSimilarity() returns primitive double, not Double
                                                                m.getMatchReason() != null
                                                                        ? m.getMatchReason()
                                                                        : "unknown"))
                                        .collect(java.util.stream.Collectors.joining(", ")));
                    }
                }

                // P1: Pagination - Build transaction maps with duplicate info (with pagination)
                final int totalTransactions = importResult.getTransactions().size();
                final int startIndex = page * size;
                final int endIndex = Math.min(startIndex + size, totalTransactions);
                final int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate pagination parameters
                if (page < 0) {
                    throw new AppException(ErrorCode.INVALID_INPUT, "Page number must be >= 0");
                }
                if (size < 1 || size > 1000) {
                    throw new AppException(
                            ErrorCode.INVALID_INPUT, "Page size must be between 1 and 1000");
                }

                final List<Map<String, Object>> paginatedTransactions = new ArrayList<>();
                for (int i = startIndex; i < endIndex; i++) {
                    final PDFImportService.ParsedTransaction parsed =
                            importResult.getTransactions().get(i);
                    // CRITICAL FIX: Get duplicates for this transaction index (may be null if no
                    // duplicates)
                    // Note: duplicates map may contain empty lists for exact matches (shouldSkip =
                    // true)
                    final List<DuplicateDetectionService.DuplicateMatch> txDuplicates =
                            duplicates != null ? duplicates.get(i) : null;

                    // Log duplicate status for debugging
                    if (txDuplicates != null) {
                        if (txDuplicates.isEmpty()) {
                            LOGGER.debug(
                                    "PDF Preview - Transaction index {} has exact match (empty list in duplicates map)",
                                    i);
                        } else {
                            LOGGER.info(
                                    "✅ PDF Preview - Transaction index {} has {} fuzzy duplicate(s) in response",
                                    i,
                                    txDuplicates.size());
                        }
                    } else {
                        LOGGER.debug(
                                "PDF Preview - Transaction index {} has no duplicates (null in duplicates map)",
                                i);
                    }

                    final Map<String, Object> txMap = buildPDFTransactionMap(parsed, txDuplicates);
                    paginatedTransactions.add(txMap);

                    // Verify duplicate info was set correctly in the response
                    final Boolean hasDuplicatesInResponse = (Boolean) txMap.get("hasDuplicates");
                    if (txDuplicates != null
                            && !txDuplicates.isEmpty()
                            && !Boolean.TRUE.equals(hasDuplicatesInResponse)) {
                        LOGGER.warn(
                                "⚠️ PDF Preview - Transaction index {} has duplicates but hasDuplicates is false in response!",
                                i);
                    }
                }

                final PDFImportPreviewResponse response = new PDFImportPreviewResponse();
                response.setTotalParsed(importResult.getSuccessCount());
                response.setTransactions(paginatedTransactions);
                response.setPage(page);
                response.setSize(size);
                response.setTotalPages(totalPages);
                response.setTotalElements(totalTransactions);
                // Forward any non-fatal parser notes (OCR used, best-effort
                // fallback used, etc.) so iOS can tell the user their import
                // wasn't a perfect structured parse.
                if (importResult.getInfoMessages() != null
                        && !importResult.getInfoMessages().isEmpty()) {
                    response.setInfoMessages(
                            new ArrayList<>(importResult.getInfoMessages()));
                }
                DetectedAccountInfo accountInfo = null;
                if (importResult.getDetectedAccount() != null) {
                    accountInfo = new DetectedAccountInfo();

                    // CRITICAL: If account was matched, use the matched account's details instead
                    // of detected account
                    // This ensures iOS shows the existing account information, not the detected
                    // account
                    final String matchedAccountId = importResult.getMatchedAccountId();
                    if (matchedAccountId != null && !matchedAccountId.isBlank()) {
                        // Fetch the matched account from database
                        final Optional<AccountTable> matchedAccount =
                                accountRepository.findById(matchedAccountId);
                        if (matchedAccount.isPresent()
                                && matchedAccount.get().getUserId().equals(user.getUserId())) {
                            final AccountTable account = matchedAccount.get();
                            // Use matched account's details
                            accountInfo.setAccountName(account.getAccountName());
                            accountInfo.setInstitutionName(account.getInstitutionName());
                            accountInfo.setAccountType(account.getAccountType());
                            accountInfo.setAccountSubtype(account.getAccountSubtype());
                            accountInfo.setAccountNumber(account.getAccountNumber());
                            accountInfo.setCardNumber(
                                    null); // Card number not stored in AccountTable
                            accountInfo.setBalance(
                                    account.getBalance()); // Include balance from existing account
                            accountInfo.setMatchedAccountId(matchedAccountId);

                            // CRITICAL: Include metadata from existing account (if available)
                            // But also include metadata from import result if it's newer (for
                            // preview display)
                            // The actual update will happen during import via
                            // updateAccountMetadataFromPDFImport
                            if (importResult.getPaymentDueDate() != null) {
                                // Use import result metadata if available (newer statement)
                                accountInfo.setPaymentDueDate(importResult.getPaymentDueDate());
                                accountInfo.setMinimumPaymentDue(
                                        importResult.getMinimumPaymentDue());
                                accountInfo.setRewardPoints(importResult.getRewardPoints());
                            } else {
                                // Fall back to existing account metadata
                                accountInfo.setPaymentDueDate(account.getPaymentDueDate());
                                accountInfo.setMinimumPaymentDue(account.getMinimumPaymentDue());
                                accountInfo.setRewardPoints(account.getRewardPoints());
                            }

                            LOGGER.info(
                                    "✅ [PDF] Matched detected account to existing account: {} (accountId: {})",
                                    account.getAccountName(),
                                    matchedAccountId);
                        } else {
                            // Matched account not found or doesn't belong to user - use detected
                            // account
                            LOGGER.warn(
                                    "⚠️ [PDF] Matched account ID '{}' not found or doesn't belong to user - using detected account info",
                                    matchedAccountId);
                            accountInfo.setAccountName(
                                    importResult.getDetectedAccount().getAccountName());
                            accountInfo.setInstitutionName(
                                    importResult.getDetectedAccount().getInstitutionName());
                            accountInfo.setAccountType(
                                    importResult.getDetectedAccount().getAccountType());
                            accountInfo.setAccountSubtype(
                                    importResult.getDetectedAccount().getAccountSubtype());
                            accountInfo.setAccountNumber(
                                    importResult.getDetectedAccount().getAccountNumber());
                            accountInfo.setCardNumber(
                                    importResult.getDetectedAccount().getCardNumber());
                            accountInfo.setBalance(
                                    importResult
                                            .getDetectedAccount()
                                            .getBalance()); // Include detected balance
                            accountInfo.setMatchedAccountId(null); // Clear invalid match

                            // CRITICAL: Include metadata from import result (extracted from PDF)
                            accountInfo.setPaymentDueDate(importResult.getPaymentDueDate());
                            accountInfo.setMinimumPaymentDue(importResult.getMinimumPaymentDue());
                            accountInfo.setRewardPoints(importResult.getRewardPoints());
                        }
                    } else {
                        // No match found - use detected account info
                        accountInfo.setAccountName(
                                importResult.getDetectedAccount().getAccountName());
                        accountInfo.setInstitutionName(
                                importResult.getDetectedAccount().getInstitutionName());
                        accountInfo.setAccountType(
                                importResult.getDetectedAccount().getAccountType());
                        accountInfo.setAccountSubtype(
                                importResult.getDetectedAccount().getAccountSubtype());
                        accountInfo.setAccountNumber(
                                importResult.getDetectedAccount().getAccountNumber());
                        accountInfo.setCardNumber(
                                importResult.getDetectedAccount().getCardNumber());
                        accountInfo.setBalance(
                                importResult
                                        .getDetectedAccount()
                                        .getBalance()); // Include detected balance
                        accountInfo.setMatchedAccountId(null);

                        // CRITICAL: Include metadata from import result (extracted from PDF)
                        accountInfo.setPaymentDueDate(importResult.getPaymentDueDate());
                        accountInfo.setMinimumPaymentDue(importResult.getMinimumPaymentDue());
                        accountInfo.setRewardPoints(importResult.getRewardPoints());
                    }

                    response.setDetectedAccount(accountInfo);

                    // Log balance in detected account for debugging
                    if (accountInfo.getBalance() != null) {
                        LOGGER.info(
                                "✅ [PDF Preview] Detected account balance included in response: {}",
                                accountInfo.getBalance());
                    } else if (accountInfo != null) {
                        LOGGER.debug(
                                "⚠️ [PDF Preview] Detected account balance is null in response");
                    }
                }

                // Log response details
                LOGGER.info(
                        "📥 PDF Preview Response - Total parsed: {}, Transactions: {}, Detected account: {} (institution: {}, type: {}, number: {}, balance: {})",
                        response.getTotalParsed(),
                        response.getTransactions() != null ? response.getTransactions().size() : 0,
                        accountInfo != null ? accountInfo.getAccountName() : "none",
                        accountInfo != null ? accountInfo.getInstitutionName() : "none",
                        accountInfo != null ? accountInfo.getAccountType() : "none",
                        accountInfo != null ? accountInfo.getAccountNumber() : "none",
                        accountInfo != null && accountInfo.getBalance() != null
                                ? accountInfo.getBalance()
                                : "none");

                return ResponseEntity.ok(response);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("PDF preview failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to preview PDF: " + e.getMessage());
        }
    }

    @PostMapping("/import-pdf")
    public ResponseEntity<BatchImportResponse> importPDF(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(required = false) final String accountId,
            @RequestParam(required = false) final String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // CRITICAL: Capture original filename - prefer explicit filename parameter
            String originalFilename;
            boolean fromParameter = false;

            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
                fromParameter = true;
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".pdf";
                LOGGER.error(
                        "❌ Both filename parameter and MultipartFile.getOriginalFilename() returned null/empty, using default: '{}'",
                        originalFilename);
            }

            // Check if filename is a UUID (indicates frontend is not preserving original filename)
            final boolean isUUIDFilename =
                    originalFilename.matches(
                            "^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}\\.(csv|xlsx|xls|pdf)$");
            if (isUUIDFilename) {
                LOGGER.warn(
                        "⚠️ WARNING: Filename is a UUID '{}' - Original filename was not preserved by frontend. "
                                + "Account detection from filename will be limited. Frontend should preserve original filename for better account detection.",
                        originalFilename);
            }

            LOGGER.info(
                    "📁 PDF Import - Using filename for account detection: '{}' (from parameter: {}, from MultipartFile: '{}', isUUID: {})",
                    originalFilename,
                    fromParameter,
                    file.getOriginalFilename(),
                    isUUIDFilename);

            // Log multipart request details for debugging
            LOGGER.info(
                    "📤 PDF Import Request Details - Filename param: '{}', MultipartFile name: '{}', Size: {} bytes, ContentType: '{}', AccountId: '{}'",
                    filename,
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getContentType(),
                    accountId);

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), PDF_EXTENSIONS);

            // Parse and import PDF - use original filename for account detection
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final PDFImportService.ImportResult importResult =
                        pdfImportService.parsePDF(
                                inputStream, originalFilename, user.getUserId(), null);

                // CRITICAL: Log metadata extraction immediately after parsing
                LOGGER.info(
                        "📋 [PDF Import] After parsing PDF - importResult metadata: paymentDueDate={}, minimumPaymentDue={}, rewardPoints={}",
                        importResult.getPaymentDueDate(),
                        importResult.getMinimumPaymentDue(),
                        importResult.getRewardPoints());

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // This ensures transactions are associated with the correct account
                String accountIdToUse = accountId;
                if (accountIdToUse == null || accountIdToUse.isBlank()) {
                    final List<AccountTable> existingAccounts =
                            accountRepository.findByUserId(user.getUserId());

                    // Step 1: Check if user has manually created an account matching the detected
                    // account
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null
                            && existingAccounts != null
                            && !existingAccounts.isEmpty()) {
                        final AccountDetectionService.DetectedAccount detected =
                                importResult.getDetectedAccount();

                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null
                                && !detected.getAccountNumber().isBlank()) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountNumber()
                                                                    .equals(acc.getAccountNumber()))
                                            .findFirst()
                                            .orElse(null);
                        }

                        // If no match by account number, try to match by account name and
                        // institution
                        if (matchingAccount == null
                                && detected.getAccountName() != null
                                && detected.getInstitutionName() != null) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getAccountName())
                                                                    && detected.getInstitutionName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getInstitutionName()))
                                            .findFirst()
                                            .orElse(null);
                        }
                    }

                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching
                        // account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        LOGGER.info(
                                "📝 [PDF] Using existing account '{}' (name: '{}', institution: '{}') for import",
                                accountIdToUse,
                                matchingAccount.getAccountName(),
                                matchingAccount.getInstitutionName());
                    } else if (importResult.getMatchedAccountId() != null
                            && !importResult.getMatchedAccountId().isBlank()) {
                        // Account was matched during preview - use it
                        accountIdToUse = importResult.getMatchedAccountId();
                        LOGGER.info(
                                "📝 [PDF] Using matched account ID from preview: '{}'",
                                accountIdToUse);
                    } else if (importResult.getDetectedAccount() != null) {
                        // User hasn't manually created a matching account - auto-create it
                        // CRITICAL: Pass importResult metadata to set metadata during account
                        // creation
                        accountIdToUse =
                                autoCreateAccountIfDetected(
                                        user, importResult.getDetectedAccount(), importResult);
                        LOGGER.info(
                                "📝 [PDF] Auto-created account '{}' for detected account '{}' from '{}'",
                                accountIdToUse,
                                importResult.getDetectedAccount().getAccountName(),
                                importResult.getDetectedAccount().getInstitutionName());
                    }
                }

                // Update all transactions with the account ID
                if (accountIdToUse != null && !accountIdToUse.isBlank()) {
                    for (final PDFImportService.ParsedTransaction parsed :
                            importResult.getTransactions()) {
                        parsed.setAccountId(accountIdToUse);
                    }
                }

                // CRITICAL: Update account metadata with latest values from PDF import
                // This updates payment due date, minimum payment due, reward points, and balance
                // based on the latest payment due date
                // NOTE: This MUST be called for ALL imports, even if accountId was provided as
                // parameter
                // The accountIdToUse might be null if no account was found/created, but if it's not
                // null, we should update
                if (accountIdToUse != null && !accountIdToUse.isBlank()) {
                    LOGGER.info(
                            "📋 [PDF Import] About to update account metadata for accountId: '{}' - importResult metadata: paymentDueDate={}, minimumPaymentDue={}, rewardPoints={}",
                            accountIdToUse,
                            importResult.getPaymentDueDate(),
                            importResult.getMinimumPaymentDue(),
                            importResult.getRewardPoints());
                    updateAccountMetadataFromPDFImport(accountIdToUse, importResult);
                } else {
                    LOGGER.warn(
                            "⚠️ [PDF Import] Cannot update account metadata: accountIdToUse is null or empty. Metadata extracted: paymentDueDate={}, minimumPaymentDue={}, rewardPoints={}",
                            importResult.getPaymentDueDate(),
                            importResult.getMinimumPaymentDue(),
                            importResult.getRewardPoints());
                }

                return processPDFBatchImport(
                        user, importResult.getTransactions(), "PDF", originalFilename);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("PDF import failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to import PDF: " + e.getMessage());
        }
    }

    @PostMapping("/import-pdf/chunk")
    public ResponseEntity<ChunkImportResponse> importPDFChunk(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam("file") final MultipartFile file,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "100") final int size,
            @RequestParam(required = false) final String accountId,
            @RequestParam(required = false) String password,
            @RequestParam(required = false) final String filename) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

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
            if (filename != null && !filename.isBlank()) {
                originalFilename =
                        java.net.URLDecoder.decode(
                                filename.trim(), StandardCharsets.UTF_8);
                originalFilename = sanitizeFilename(originalFilename);
            } else {
                originalFilename = getOriginalFilenameSafely(file);
            }

            if (originalFilename == null || originalFilename.isBlank()) {
                originalFilename = "import_" + System.currentTimeMillis() + ".pdf";
            }

            // Apply security processing
            final byte[] fileContent = applySecurityProcessing(file, user.getUserId(), PDF_EXTENSIONS);

            // Parse PDF file
            try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
                final PDFImportService.ImportResult importResult =
                        pdfImportService.parsePDF(
                                inputStream, originalFilename, user.getUserId(), null);

                final List<PDFImportService.ParsedTransaction> allTransactions =
                        importResult.getTransactions();
                final int totalTransactions = allTransactions.size();
                final int totalPages = (int) Math.ceil((double) totalTransactions / size);

                // Validate page number
                if (page >= totalPages && totalPages > 0) {
                    throw new AppException(
                            ErrorCode.INVALID_INPUT,
                            String.format(
                                    "Page %d is out of range. Total pages: %d", page, totalPages));
                }

                // Get transactions for this page
                final int startIndex = page * size;
                final int endIndex = Math.min(startIndex + size, totalTransactions);
                final List<PDFImportService.ParsedTransaction> chunk =
                        allTransactions.subList(startIndex, endIndex);

                LOGGER.info(
                        "📦 Importing PDF chunk: page {} (transactions {} to {} of {})",
                        page,
                        startIndex + 1,
                        endIndex,
                        totalTransactions);

                // CRITICAL: Auto-create detected account if user hasn't manually created it
                // Only create on first page (page 0) to avoid creating multiple accounts
                // Reuse the same account across all pages for paginated imports
                String accountIdToUse = accountId;
                if (accountIdToUse == null || accountIdToUse.isBlank()) {
                    final List<AccountTable> existingAccounts =
                            accountRepository.findByUserId(user.getUserId());

                    // Step 1: Check if user has manually created an account matching the detected
                    // account
                    AccountTable matchingAccount = null;
                    if (importResult.getDetectedAccount() != null
                            && existingAccounts != null
                            && !existingAccounts.isEmpty()) {
                        final AccountDetectionService.DetectedAccount detected =
                                importResult.getDetectedAccount();

                        // First, try to match by account number (most reliable)
                        if (detected.getAccountNumber() != null
                                && !detected.getAccountNumber().isBlank()) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountNumber()
                                                                    .equals(acc.getAccountNumber()))
                                            .findFirst()
                                            .orElse(null);
                        }

                        // If no match by account number, try to match by account name and
                        // institution
                        if (matchingAccount == null
                                && detected.getAccountName() != null
                                && detected.getInstitutionName() != null) {
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc ->
                                                            detected.getAccountName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getAccountName())
                                                                    && detected.getInstitutionName()
                                                                            .equals(
                                                                                    acc
                                                                                            .getInstitutionName()))
                                            .findFirst()
                                            .orElse(null);
                        }
                    }

                    if (matchingAccount != null) {
                        // User has already created (or we previously auto-created) a matching
                        // account - use it
                        accountIdToUse = matchingAccount.getAccountId();
                        LOGGER.info(
                                "📝 [PDF] Using existing account '{}' (name: '{}', institution: '{}') for import (page {})",
                                accountIdToUse,
                                matchingAccount.getAccountName(),
                                matchingAccount.getInstitutionName(),
                                page);
                    } else if (importResult.getMatchedAccountId() != null
                            && !importResult.getMatchedAccountId().isBlank()) {
                        // Account was matched during preview - use it
                        accountIdToUse = importResult.getMatchedAccountId();
                        LOGGER.info(
                                "📝 [PDF] Using matched account ID from preview: '{}' (page {})",
                                accountIdToUse,
                                page);
                    } else if (importResult.getDetectedAccount() != null && page == 0) {
                        // CRITICAL: Only auto-create on first page (page 0) to avoid creating
                        // multiple accounts
                        // User hasn't manually created a matching account - auto-create it
                        accountIdToUse =
                                autoCreateAccountIfDetected(
                                        user, importResult.getDetectedAccount(), importResult);
                        LOGGER.info(
                                "📝 [PDF] Auto-created account '{}' for detected account '{}' from '{}' (first page only)",
                                accountIdToUse,
                                importResult.getDetectedAccount().getAccountName(),
                                importResult.getDetectedAccount().getInstitutionName());
                    } else if (importResult.getDetectedAccount() != null && page > 0) {
                        // Subsequent pages: Try to find the account that was auto-created on first
                        // page
                        if (existingAccounts != null && !existingAccounts.isEmpty()) {
                            final AccountDetectionService.DetectedAccount detected =
                                    importResult.getDetectedAccount();
                            final Instant fiveMinutesAgo = Instant.now().minusSeconds(300);
                            matchingAccount =
                                    existingAccounts.stream()
                                            .filter(
                                                    acc -> {
                                                        final boolean nameMatch =
                                                                detected.getAccountName() != null
                                                                        && detected.getAccountName()
                                                                        .equals(
                                                                                acc
                                                                                        .getAccountName());
                                                        final boolean institutionMatch =
                                                                detected.getInstitutionName()
                                                                        != null
                                                                        && detected.getInstitutionName()
                                                                        .equals(
                                                                                acc
                                                                                        .getInstitutionName());
                                                        final boolean recentlyCreated =
                                                                acc.getCreatedAt() != null
                                                                        && acc.getCreatedAt()
                                                                        .isAfter(
                                                                                fiveMinutesAgo);
                                                        return nameMatch
                                                                && institutionMatch
                                                                && recentlyCreated;
                                                    })
                                            .max(
                                                    Comparator.comparing(
                                                            (AccountTable acc) ->
                                                                    acc.getCreatedAt() != null
                                                                            ? acc.getCreatedAt()
                                                                            : Instant.ofEpochMilli(
                                                                                    0)))
                                            .orElse(null);

                            if (matchingAccount != null) {
                                accountIdToUse = matchingAccount.getAccountId();
                                LOGGER.info(
                                        "📝 [PDF] Reusing auto-created account '{}' from first page (page {})",
                                        accountIdToUse,
                                        page);
                            }
                        }
                    }
                }

                // Update chunk transactions with account ID
                if (accountIdToUse != null && !accountIdToUse.isBlank()) {
                    for (final PDFImportService.ParsedTransaction parsed : chunk) {
                        parsed.setAccountId(accountIdToUse);
                    }

                    // CRITICAL: Update account metadata with latest values from PDF import
                    // Only update on page 0 to avoid redundant updates (all pages have same
                    // metadata from same PDF)
                    if (page == 0) {
                        updateAccountMetadataFromPDFImport(accountIdToUse, importResult);
                    }
                }

                // Import this chunk
                final ResponseEntity<BatchImportResponse> importResponseEntity =
                        processPDFBatchImport(user, chunk, "PDF", originalFilename);
                final BatchImportResponse importResponse = importResponseEntity.getBody();

                // Build chunk response
                final ChunkImportResponse response = new ChunkImportResponse();
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
            LOGGER.error("PDF chunk import failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Failed to import PDF chunk: " + e.getMessage());
        }
    }

    // MARK: - Preview Recalculation API

    /**
     * Recalculate preview transactions with new account type Called when user changes account type
     * during import preview
     *
     * @param transactions List of transactions from preview
     * @param accountType New account type to use for recalculation
     * @param importSource Import source (CSV, EXCEL, PDF)
     * @return Updated transactions with recalculated categories and types
     */
    // Rate limiting for preview recalculation (10 requests per minute per user)
    private static final int MAX_RECALCULATE_REQUESTS_PER_MINUTE = 10;

    private final Map<String, List<Long>> recalculateRateLimitMap =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/import/recalculate-preview")
    public ResponseEntity<Map<String, Object>> recalculatePreview(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final RecalculatePreviewRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        // Verify user exists (authorization check)
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        LOGGER.debug("Recalculating preview for user: {}", user.getUserId());

        // RATE LIMITING: Check if user has exceeded rate limit
        final String userId = user.getUserId();
        final long currentTime = System.currentTimeMillis();
        final List<Long> requestTimes =
                recalculateRateLimitMap.computeIfAbsent(userId, k -> new ArrayList<>());

        // Remove requests older than 1 minute
        requestTimes.removeIf(time -> currentTime - time > 60_000);

        if (requestTimes.size() >= MAX_RECALCULATE_REQUESTS_PER_MINUTE) {
            LOGGER.warn(
                    "Rate limit exceeded for user {}: {} requests in last minute",
                    userId,
                    requestTimes.size());
            throw new AppException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many recalculation requests. Please wait before trying again.");
        }

        // Record this request
        requestTimes.add(currentTime);

        try {
            // INPUT VALIDATION: Check required fields
            if (request.getTransactions() == null || request.getTransactions().isEmpty()) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Transactions list is required");
            }
            if (request.getAccountType() == null || request.getAccountType().isBlank()) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Account type is required");
            }

            // BOUNDARY CHECK: Limit transaction count to prevent memory exhaustion
            final int MAX_TRANSACTIONS_PER_REQUEST = 10_000;
            if (request.getTransactions().size() > MAX_TRANSACTIONS_PER_REQUEST) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT,
                        String.format(
                                "Too many transactions. Maximum %d transactions per request.",
                                MAX_TRANSACTIONS_PER_REQUEST));
            }

            // VALIDATION: Validate account type
            final String accountType = request.getAccountType().trim();
            final Set<String> validAccountTypes =
                    Set.of(
                            "depository",
                            "credit",
                            "loan",
                            "investment",
                            "other",
                            "brokerage",
                            "401k",
                            "ira",
                            "hsa",
                            "529");
            if (!validAccountTypes.contains(accountType.toLowerCase(Locale.ROOT))) {
                LOGGER.warn("Invalid account type provided: {}", accountType);
                // Don't fail, but log warning
            }

            // Recalculate each transaction
            final List<Map<String, Object>> recalculatedTransactions = new ArrayList<>();
            for (final Map<String, Object> txMap : request.getTransactions()) {
                try {
                    // Extract and validate transaction data
                    String description =
                            txMap.get("description") != null
                                    ? txMap.get("description").toString()
                                    : null;
                    String merchantName =
                            txMap.get("merchantName") != null
                                    ? txMap.get("merchantName").toString()
                                    : null;

                    // BOUNDARY CHECK: Validate string lengths
                    final int MAX_DESCRIPTION_LENGTH = 500;
                    final int MAX_MERCHANT_NAME_LENGTH = 200;
                    if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
                        LOGGER.warn(
                                "Description too long ({} chars), truncating to {}",
                                description.length(),
                                MAX_DESCRIPTION_LENGTH);
                        description = description.substring(0, MAX_DESCRIPTION_LENGTH);
                    }
                    if (merchantName != null && merchantName.length() > MAX_MERCHANT_NAME_LENGTH) {
                        LOGGER.warn(
                                "Merchant name too long ({} chars), truncating to {}",
                                merchantName.length(),
                                MAX_MERCHANT_NAME_LENGTH);
                        merchantName = merchantName.substring(0, MAX_MERCHANT_NAME_LENGTH);
                    }

                    BigDecimal amount = null;
                    if (txMap.get("amount") != null) {
                        if (txMap.get("amount") instanceof Number) {
                            amount =
                                    BigDecimal.valueOf(
                                            ((Number) txMap.get("amount")).doubleValue());
                        } else if (txMap.get("amount") instanceof String) {
                            try {
                                amount = new BigDecimal(txMap.get("amount").toString());
                            } catch (NumberFormatException e) {
                                LOGGER.warn("Invalid amount format: {}", txMap.get("amount"));
                            }
                        }
                    }

                    // BOUNDARY CHECK: Validate amount range (prevent extreme values)
                    final BigDecimal MAX_AMOUNT = new BigDecimal("999999999.99"); // ~1 billion
                    final BigDecimal MIN_AMOUNT = new BigDecimal("-999999999.99");
                    if (amount != null) {
                        if (amount.compareTo(MAX_AMOUNT) > 0 || amount.compareTo(MIN_AMOUNT) < 0) {
                            LOGGER.warn(
                                    "Amount out of valid range: {}, skipping transaction", amount);
                            recalculatedTransactions.add(txMap);
                            continue;
                        }
                    }

                    final String paymentChannel =
                            txMap.get("paymentChannel") != null
                                    ? txMap.get("paymentChannel").toString()
                                    : null;

                    if (amount == null) {
                        // Skip transactions without valid amount
                        LOGGER.debug("Skipping transaction without valid amount");
                        recalculatedTransactions.add(txMap);
                        continue;
                    }

                    // Determination now happens during transaction creation only; keep preview data
                    final Map<String, Object> updatedTx = new HashMap<>(txMap);
                    if (description != null) {
                        updatedTx.put("description", description);
                    }
                    if (merchantName != null) {
                        updatedTx.put("merchantName", merchantName);
                    }
                    if (paymentChannel != null) {
                        updatedTx.put("paymentChannel", paymentChannel);
                    }
                    recalculatedTransactions.add(updatedTx);
                } catch (Exception e) {
                    LOGGER.warn(
                            "Failed to recalculate transaction, keeping original: {}",
                            e.getMessage());
                    recalculatedTransactions.add(txMap); // Keep original on error
                }
            }

            final Map<String, Object> response = new HashMap<>();
            response.put("transactions", recalculatedTransactions);
            response.put("accountType", request.getAccountType());
            response.put("accountSubtype", request.getAccountSubtype());

            LOGGER.info(
                    "Recalculated {} transactions with account type: {}",
                    recalculatedTransactions.size(),
                    request.getAccountType());

            return ResponseEntity.ok(response);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Preview recalculation failed: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to recalculate preview: " + e.getMessage());
        }
    }

    // MARK: - Helper Methods

    private Map<String, Object> buildTransactionMap(
            final CSVImportService.ParsedTransaction parsed,
            final List<DuplicateDetectionService.DuplicateMatch> duplicateMatches) {
        final Map<String, Object> txMap =
                buildTransactionMapInternal(
                        parsed.getDate(),
                        parsed.getAmount(),
                        parsed.getDescription(),
                        parsed.getMerchantName(),
                        parsed.getLocation(),
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
            final PDFImportService.ParsedTransaction parsed,
            final List<DuplicateDetectionService.DuplicateMatch> duplicateMatches) {
        final Map<String, Object> txMap =
                buildTransactionMapInternal(
                        parsed.getDate(),
                        parsed.getAmount(),
                        parsed.getDescription(),
                        parsed.getMerchantName(),
                        parsed.getLocation(),
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
            final LocalDate date,
            final BigDecimal amount,
            final String description,
            final String merchantName,
            final String location,
            final String categoryPrimary,
            final String categoryDetailed,
            final String currencyCode,
            final String paymentChannel,
            final String transactionType,
            final List<DuplicateDetectionService.DuplicateMatch> duplicateMatches,
            final String userName) {
        final Map<String, Object> txMap = new HashMap<>();

        // CRITICAL: Null safety and validation for all fields
        // Date: Convert to string, null if invalid
        txMap.put("date", date != null ? date.toString() : null);

        // Amount: Validate and handle null/zero
        if (amount != null) {
            // CRITICAL: Validate amount is reasonable (prevent overflow issues)
            final BigDecimal maxAmount = BigDecimal.valueOf(1_000_000_000);
            final BigDecimal minAmount = BigDecimal.valueOf(-1_000_000_000);
            if (amount.compareTo(maxAmount) > 0 || amount.compareTo(minAmount) < 0) {
                LOGGER.warn(
                        "buildTransactionMapInternal: Amount out of reasonable range: {}, using null",
                        amount);
                txMap.put("amount", null);
            } else {
                txMap.put("amount", amount);
            }
        } else {
            txMap.put("amount", null);
        }

        // Description: Normalize empty strings to null for consistency
        txMap.put(
                "description",
                description != null && !description.isBlank() ? description.trim() : null);

        // Merchant name: Normalize empty strings to null
        txMap.put(
                "merchantName",
                merchantName != null && !merchantName.isBlank()
                        ? merchantName.trim()
                        : null);

        // Location: Normalize empty strings to null
        txMap.put(
                "location",
                location != null && !location.isBlank() ? location.trim() : null);

        // User name: Card/account user (family member who made the transaction)
        txMap.put(
                "userName",
                userName != null && !userName.isBlank() ? userName.trim() : null);

        // Category: Ensure we always have a valid category (never null)
        // CRITICAL: Default to "other" if category is null/empty to prevent downstream errors
        final String safeCategoryPrimary =
                categoryPrimary != null && !categoryPrimary.isBlank()
                        ? categoryPrimary.trim()
                        : "other";
        final String safeCategoryDetailed =
                categoryDetailed != null && !categoryDetailed.isBlank()
                        ? categoryDetailed.trim()
                        : safeCategoryPrimary; // Default to primary if detailed is null
        txMap.put("categoryPrimary", safeCategoryPrimary);
        txMap.put("categoryDetailed", safeCategoryDetailed);

        // Currency code: Normalize empty strings to null
        txMap.put(
                "currencyCode",
                currencyCode != null && !currencyCode.isBlank()
                        ? currencyCode.trim()
                        : null);

        // Payment channel: Normalize empty strings to null
        txMap.put(
                "paymentChannel",
                paymentChannel != null && !paymentChannel.isBlank()
                        ? paymentChannel.trim()
                        : null);

        // Transaction type: Ensure we always have a valid type (never null)
        // CRITICAL: Default to "EXPENSE" if type is null/empty (most common type)
        String safeTransactionType =
                transactionType != null && !transactionType.isBlank()
                        ? transactionType.trim().toUpperCase(Locale.ROOT)
                        : "EXPENSE";
        // Validate transaction type is one of the expected values
        if (!"INCOME".equals(safeTransactionType)
                && !"EXPENSE".equals(safeTransactionType)
                && !"INVESTMENT".equals(safeTransactionType)
                && !"PAYMENT".equals(safeTransactionType)) {
            LOGGER.warn(
                    "buildTransactionMapInternal: Invalid transaction type '{}', defaulting to 'EXPENSE'",
                    transactionType);
            safeTransactionType = "EXPENSE";
        }
        txMap.put("transactionType", safeTransactionType);

        // Selected: Default to true, but unselect if duplicates found
        // CRITICAL FIX: An empty list in duplicateMatches means exact match (shouldSkip = true)
        // A non-empty list means fuzzy matches (similarity >= threshold)
        // Both should be marked as duplicates
        final boolean hasDuplicates = duplicateMatches != null;
        // CRITICAL: Duplicates should be unselected by default (user must manually select them)
        txMap.put("selected", !hasDuplicates);

        // Duplicate information
        txMap.put("hasDuplicates", hasDuplicates);
        if (hasDuplicates && duplicateMatches != null) {
            if (!duplicateMatches.isEmpty()) {
                // Fuzzy matches - use the best match
                final DuplicateDetectionService.DuplicateMatch bestMatch = duplicateMatches.get(0);
                // CRITICAL: Validate similarity and reason are not null
                final Double similarity = bestMatch.getSimilarity();
                final String reason = bestMatch.getMatchReason();
                txMap.put("duplicateSimilarity", similarity);
                txMap.put(
                        "duplicateReason",
                        reason != null && !reason.isBlank() ? reason.trim() : "Exact match");
            } else {
                // Empty list = exact match (shouldSkip = true) - mark as duplicate with high
                // similarity
                txMap.put("duplicateSimilarity", 1.0);
                txMap.put("duplicateReason", "Exact match");
            }
        } else {
            txMap.put("duplicateSimilarity", null);
            txMap.put("duplicateReason", null);
        }

        return txMap;
    }

    /**
     * Thin delegator — duplicate detection + batched create + async subscription detection now live
     * in {@link com.budgetbuddy.service.importer.TransactionImportOrchestrator}. Keeping the
     * controller-level method signature unchanged means every CSV/Excel/PDF/chunked entry-point
     * keeps calling this without edits.
     */
    private ResponseEntity<BatchImportResponse> processBatchImport(
            final UserTable user,
            final List<CSVImportService.ParsedTransaction> transactions,
            final String importSource,
            final String fileName) {
        final com.budgetbuddy.service.importer.TransactionImportOrchestrator.BatchImportResult result =
                importOrchestrator.processBatch(user, transactions, importSource, fileName);
        final BatchImportResponse response = new BatchImportResponse();
        response.setTotal(result.getTotal());
        response.setCreated(result.getCreated());
        response.setFailed(result.getFailed());
        response.setDuplicates(result.getDuplicates());
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<BatchImportResponse> processPDFBatchImport(
            final UserTable user,
            final List<PDFImportService.ParsedTransaction> transactions,
            final String importSource,
            final String fileName) {
        final String batchId = UUID.randomUUID().toString();
        int created = 0;
        int failed = 0;

        // CRITICAL: Process all transactions in batches to handle large imports (up to 10,000)
        // Batch size of 500 to balance performance and memory usage (reduced from 1000 for better
        // progress feedback)
        final int BATCH_SIZE = 500;
        final int totalTransactions = transactions.size();

        LOGGER.info(
                "📦 Processing {} transactions in batches of {} for {} import",
                totalTransactions,
                BATCH_SIZE,
                importSource);

        for (int i = 0; i < totalTransactions; i += BATCH_SIZE) {
            final int endIndex = Math.min(i + BATCH_SIZE, totalTransactions);
            final List<PDFImportService.ParsedTransaction> batch = transactions.subList(i, endIndex);
            final int batchNumber = (i / BATCH_SIZE) + 1;
            final int totalBatches = (int) Math.ceil((double) totalTransactions / BATCH_SIZE);

            LOGGER.info(
                    "📦 Processing batch {}/{}: transactions {} to {} ({} transactions)",
                    batchNumber,
                    totalBatches,
                    i + 1,
                    endIndex,
                    batch.size());

            int batchCreated = 0;
            int batchFailed = 0;

            for (final PDFImportService.ParsedTransaction parsed : batch) {
                try {

                    // CRITICAL: Log amount before creating transaction to track sign preservation
                    LOGGER.info(
                            "📥 [PDF Import] Creating transaction: description='{}', parsedAmount={}, transactionType='{}', category='{}'",
                            parsed.getDescription(),
                            parsed.getAmount(),
                            parsed.getTransactionType(),
                            parsed.getCategoryPrimary());

                    final TransactionTable createdTx =
                            transactionService.createTransaction(
                                    user,
                                    parsed.getAccountId(), // May be null - TransactionService
                                    // will use pseudo account
                                    parsed.getAmount(),
                                    parsed.getDate(),
                                    parsed.getDescription(),
                                    parsed.getCategoryPrimary(),
                                    parsed.getCategoryDetailed(),
                                    parsed.getImporterCategoryPrimary(), // Importer category
                                    // (from parser)
                                    parsed.getImporterCategoryDetailed(), // Importer category
                                    // (from parser)
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
                                    parsed.getMerchantName(), // merchantName (where purchase was
                                    // made)
                                    parsed.getLocation(), // location (store/city/state)
                                    parsed.getPaymentChannel(), // paymentChannel
                                    parsed.getUserName(), // userName (card/account user - family
                                    // member)
                                    null, // goalId
                                    null // linkedTransactionId
                            );

                    // CRITICAL: Log amount after creation to verify sign preservation
                    LOGGER.info(
                            "✅ [PDF Import] Transaction created: transactionId='{}', storedAmount={}, parsedAmount={}, match={}",
                            createdTx.getTransactionId(),
                            createdTx.getAmount(),
                            parsed.getAmount(),
                            createdTx.getAmount().compareTo(parsed.getAmount()) == 0
                                    ? "✅"
                                    : "❌ MISMATCH");

                    batchCreated++;
                    created++;
                } catch (Exception e) {
                    LOGGER.error("Failed to create transaction from import: {}", e.getMessage(), e);
                    batchFailed++;
                    failed++;
                }
            }

            LOGGER.info(
                    "✅ Batch {}/{} completed: {} created, {} failed (total so far: {} created, {} failed)",
                    batchNumber,
                    totalBatches,
                    batchCreated,
                    batchFailed,
                    created,
                    failed);
        }

        final BatchImportResponse response = new BatchImportResponse();
        response.setTotal(totalTransactions);
        response.setCreated(created);
        response.setFailed(failed);

        LOGGER.info(
                "🎉 Import completed: {} total transactions, {} created, {} failed",
                totalTransactions,
                created,
                failed);

        // CRITICAL FIX: Automatically detect subscriptions after PDF import
        // Run asynchronously to avoid blocking the response
        final int finalCreated = created; // Capture for lambda
        if (finalCreated > 0) {
            try {
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            try {
                                final List<com.budgetbuddy.model.Subscription> detected =
                                        subscriptionService.detectSubscriptions(user.getUserId());
                                if (!detected.isEmpty()) {
                                    subscriptionService.saveSubscriptions(
                                            user.getUserId(), detected);
                                    LOGGER.info(
                                            "Detected {} subscriptions after PDF import ({} transactions created)",
                                            detected.size(),
                                            finalCreated);
                                }
                            } catch (Exception e) {
                                LOGGER.warn(
                                        "Failed to detect subscriptions after PDF import: {}",
                                        e.getMessage());
                                // Don't fail the request if subscription detection fails
                            }
                        });
            } catch (Exception e) {
                LOGGER.warn(
                        "Failed to trigger subscription detection after PDF import: {}",
                        e.getMessage());
                // Don't fail the request if subscription detection fails
            }
        }

        return ResponseEntity.ok(response);
    }

    // DTOs
    public static class CreateTransactionRequest {
        private String transactionId; // Optional: If provided, use this ID (for app-backend ID
        // consistency)
        private String accountId;

        @jakarta.validation.constraints.NotNull(message = "Amount is required")
        @jakarta.validation.constraints.DecimalMin(
                value = "-999999999.99",
                message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        @jakarta.validation.constraints.DecimalMax(
                value = "999999999.99",
                message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        private BigDecimal amount;

        @jakarta.validation.constraints.NotNull(message = "Transaction date is required")
        @jakarta.validation.constraints.PastOrPresent(
                message = "Transaction date cannot be in the future")
        private LocalDate transactionDate;

        @jakarta.validation.constraints.Size(
                max = 500,
                message = "Description cannot exceed 500 characters")
        private String description;

        @jakarta.validation.constraints.NotBlank(message = "Category primary is required")
        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Category primary cannot exceed 100 characters")
        private String categoryPrimary; // Primary category (required)

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Category detailed cannot exceed 100 characters")
        private String categoryDetailed; // Detailed category (optional, defaults to primary if not

        // provided)

        @jakarta.validation.constraints.Size(
                max = 1000,
                message = "Notes cannot exceed 1000 characters")
        private String notes; // Optional: User notes for the transaction

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Plaid account ID cannot exceed 100 characters")
        private String
                plaidAccountId; // Optional: Plaid account ID for fallback lookup if accountId not

        // found

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Plaid transaction ID cannot exceed 100 characters")
        private String
                plaidTransactionId; // Optional: Plaid transaction ID for fallback lookup and ID

        // consistency

        @jakarta.validation.constraints.Pattern(
                regexp = "^(INCOME|INVESTMENT|PAYMENT|EXPENSE)?$",
                message = "Transaction type must be INCOME, INVESTMENT, PAYMENT, or EXPENSE")
        private String
                transactionType; // Optional: User-selected transaction type (INCOME, INVESTMENT,

        // PAYMENT, EXPENSE). If not provided, backend will calculate it.

        @jakarta.validation.constraints.Pattern(
                regexp = "^[A-Z]{3}$",
                message = "Currency code must be a 3-letter ISO code (e.g., USD, INR)")
        private String currencyCode; // Optional: Currency code (e.g., "USD", "INR")

        @jakarta.validation.constraints.Pattern(
                regexp = "^(CSV|Excel|PDF)?$",
                message = "Import source must be CSV, Excel, or PDF")
        private String importSource; // Optional: Import source (e.g., "CSV", "Excel", "PDF")

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Import batch ID cannot exceed 100 characters")
        private String importBatchId; // Optional: Batch ID for grouped imports

        @jakarta.validation.constraints.Size(
                max = 255,
                message = "Import file name cannot exceed 255 characters")
        private String importFileName; // Optional: Original file name for imports

        @jakarta.validation.constraints.Pattern(
                regexp = "^(none|flagged|reviewed|error)?$",
                message = "Review status must be none, flagged, reviewed, or error")
        private String
                reviewStatus; // Optional: review status ("none", "flagged", "reviewed", "error")

        @jakarta.validation.constraints.Size(
                max = 200,
                message = "Merchant name cannot exceed 200 characters")
        private String
                merchantName; // Optional: Merchant name (where purchase was made, e.g., "Amazon",

        // "Starbucks")

        @jakarta.validation.constraints.Size(
                max = 200,
                message = "Location cannot exceed 200 characters")
        private String location; // Optional: Location (store/city/state)

        @jakarta.validation.constraints.Size(
                max = 50,
                message = "Payment channel cannot exceed 50 characters")
        private String paymentChannel; // Optional: Payment channel (online, in_store, ach, etc.)

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "User name cannot exceed 100 characters")
        private String userName; // Optional: Card/account user name (family member who made the

        // transaction)

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Goal ID cannot exceed 100 characters")
        private String goalId; // Optional: Goal this transaction contributes to

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Linked transaction ID cannot exceed 100 characters")
        private String linkedTransactionId; // Optional: ID of linked transaction (e.g., credit card

        // payment linked to checking payment)

        // Getters and setters
        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(final String transactionId) {
            this.transactionId = transactionId;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(final String accountId) {
            this.accountId = accountId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(final BigDecimal amount) {
            this.amount = amount;
        }

        public LocalDate getTransactionDate() {
            return transactionDate;
        }

        public void setTransactionDate(final LocalDate transactionDate) {
            this.transactionDate = transactionDate;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public String getCategoryPrimary() {
            return categoryPrimary;
        }

        public void setCategoryPrimary(final String categoryPrimary) {
            this.categoryPrimary = categoryPrimary;
        }

        public String getCategoryDetailed() {
            return categoryDetailed;
        }

        public void setCategoryDetailed(final String categoryDetailed) {
            this.categoryDetailed = categoryDetailed;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(final String notes) {
            this.notes = notes;
        }

        public String getPlaidAccountId() {
            return plaidAccountId;
        }

        public void setPlaidAccountId(final String plaidAccountId) {
            this.plaidAccountId = plaidAccountId;
        }

        public String getPlaidTransactionId() {
            return plaidTransactionId;
        }

        public void setPlaidTransactionId(final String plaidTransactionId) {
            this.plaidTransactionId = plaidTransactionId;
        }

        public String getTransactionType() {
            return transactionType;
        }

        public void setTransactionType(final String transactionType) {
            this.transactionType = transactionType;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(final String currencyCode) {
            this.currencyCode = currencyCode;
        }

        public String getImportSource() {
            return importSource;
        }

        public void setImportSource(final String importSource) {
            this.importSource = importSource;
        }

        public String getImportBatchId() {
            return importBatchId;
        }

        public void setImportBatchId(final String importBatchId) {
            this.importBatchId = importBatchId;
        }

        public String getImportFileName() {
            return importFileName;
        }

        public void setImportFileName(final String importFileName) {
            this.importFileName = importFileName;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(final String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public void setMerchantName(final String merchantName) {
            this.merchantName = merchantName;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(final String location) {
            this.location = location;
        }

        public String getPaymentChannel() {
            return paymentChannel;
        }

        public void setPaymentChannel(final String paymentChannel) {
            this.paymentChannel = paymentChannel;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(final String userName) {
            this.userName = userName;
        }

        public String getGoalId() {
            return goalId;
        }

        public void setGoalId(final String goalId) {
            this.goalId = goalId;
        }

        public String getLinkedTransactionId() {
            return linkedTransactionId;
        }

        public void setLinkedTransactionId(final String linkedTransactionId) {
            this.linkedTransactionId = linkedTransactionId;
        }
    }

    public static class TotalSpendingResponse {
        private BigDecimal total;

        public TotalSpendingResponse(final BigDecimal total) {
            this.total = total;
        }

        public BigDecimal getTotal() {
            return total;
        }

        public void setTotal(final BigDecimal total) {
            this.total = total;
        }
    }

    public static class UpdateTransactionRequest {
        @jakarta.validation.constraints.DecimalMin(
                value = "-999999999.99",
                message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        @jakarta.validation.constraints.DecimalMax(
                value = "999999999.99",
                message = "Amount must be between -999,999,999.99 and 999,999,999.99")
        private BigDecimal amount; // Optional: transaction amount (for type changes)

        @jakarta.validation.constraints.Size(
                max = 1000,
                message = "Notes cannot exceed 1000 characters")
        private String notes;

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Category primary cannot exceed 100 characters")
        private String categoryPrimary; // Optional: override primary category

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Category detailed cannot exceed 100 characters")
        private String categoryDetailed; // Optional: override detailed category

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Plaid transaction ID cannot exceed 100 characters")
        private String
                plaidTransactionId; // Optional: for fallback lookup if transactionId not found

        @jakarta.validation.constraints.Pattern(
                regexp = "^(none|flagged|reviewed|error)?$",
                message = "Review status must be none, flagged, reviewed, or error")
        private String
                reviewStatus; // Optional: review status ("none", "flagged", "reviewed", "error")

        private Boolean isHidden; // Optional: whether transaction is hidden from view

        @jakarta.validation.constraints.Pattern(
                regexp = "^(INCOME|INVESTMENT|PAYMENT|EXPENSE)?$",
                message = "Transaction type must be INCOME, INVESTMENT, PAYMENT, or EXPENSE")
        private String
                transactionType; // Optional: User-selected transaction type (INCOME, INVESTMENT,

        // PAYMENT, EXPENSE). If not provided, backend will calculate it.

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Goal ID cannot exceed 100 characters")
        private String goalId; // Optional: Goal this transaction contributes to

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Linked transaction ID cannot exceed 100 characters")
        private String linkedTransactionId; // Optional: ID of linked transaction (e.g., credit card

        // payment linked to checking payment)

        /**
         * Flow 4 / O2 optimistic-lock token. The client echoes the server's last known {@code
         * updatedAt} (ISO 8601). If the row has since been modified server-side, the controller
         * returns 409 Conflict so the client can surface the change and let the user decide. Null
         * disables the check (legacy callers, bulk imports).
         */
        private String ifUnmodifiedSince;

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(final BigDecimal amount) {
            this.amount = amount;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(final String notes) {
            this.notes = notes;
        }

        public String getCategoryPrimary() {
            return categoryPrimary;
        }

        public void setCategoryPrimary(final String categoryPrimary) {
            this.categoryPrimary = categoryPrimary;
        }

        public String getCategoryDetailed() {
            return categoryDetailed;
        }

        public void setCategoryDetailed(final String categoryDetailed) {
            this.categoryDetailed = categoryDetailed;
        }

        public String getPlaidTransactionId() {
            return plaidTransactionId;
        }

        public void setPlaidTransactionId(final String plaidTransactionId) {
            this.plaidTransactionId = plaidTransactionId;
        }

        public String getReviewStatus() {
            return reviewStatus;
        }

        public void setReviewStatus(final String reviewStatus) {
            this.reviewStatus = reviewStatus;
        }

        public Boolean getIsHidden() {
            return isHidden;
        }

        public void setIsHidden(final Boolean isHidden) {
            this.isHidden = isHidden;
        }

        public String getTransactionType() {
            return transactionType;
        }

        public void setTransactionType(final String transactionType) {
            this.transactionType = transactionType;
        }

        public String getGoalId() {
            return goalId;
        }

        public void setGoalId(final String goalId) {
            this.goalId = goalId;
        }

        public String getLinkedTransactionId() {
            return linkedTransactionId;
        }

        public void setLinkedTransactionId(final String linkedTransactionId) {
            this.linkedTransactionId = linkedTransactionId;
        }

        public String getIfUnmodifiedSince() {
            return ifUnmodifiedSince;
        }

        public void setIfUnmodifiedSince(final String ifUnmodifiedSince) {
            this.ifUnmodifiedSince = ifUnmodifiedSince;
        }
    }

    public static class VerifyTransactionRequest {
        @jakarta.validation.constraints.NotBlank(message = "Transaction ID is required")
        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Transaction ID cannot exceed 100 characters")
        private String transactionId;

        @jakarta.validation.constraints.Size(
                max = 100,
                message = "Plaid transaction ID cannot exceed 100 characters")
        private String
                plaidTransactionId; // Optional: Plaid transaction ID in body for fallback lookup

        public String getTransactionId() {
            return transactionId;
        }

        public void setTransactionId(final String transactionId) {
            this.transactionId = transactionId;
        }

        public String getPlaidTransactionId() {
            return plaidTransactionId;
        }

        public void setPlaidTransactionId(final String plaidTransactionId) {
            this.plaidTransactionId = plaidTransactionId;
        }
    }

    public static class RecalculatePreviewRequest {
        private List<Map<String, Object>> transactions;
        private String accountType;
        private String accountSubtype;
        private String institutionName;
        private String importSource; // CSV, EXCEL, PDF

        public List<Map<String, Object>> getTransactions() {
            return transactions;
        }

        public void setTransactions(final List<Map<String, Object>> transactions) {
            this.transactions = transactions;
        }

        public String getAccountType() {
            return accountType;
        }

        public void setAccountType(final String accountType) {
            this.accountType = accountType;
        }

        public String getAccountSubtype() {
            return accountSubtype;
        }

        public void setAccountSubtype(final String accountSubtype) {
            this.accountSubtype = accountSubtype;
        }

        public String getInstitutionName() {
            return institutionName;
        }

        public void setInstitutionName(final String institutionName) {
            this.institutionName = institutionName;
        }

        public String getImportSource() {
            return importSource;
        }

        public void setImportSource(final String importSource) {
            this.importSource = importSource;
        }
    }

    // MARK: - Import Response DTOs

    // Shared DetectedAccountInfo class for use across import responses and tests
    public static class DetectedAccountInfo {
        private String accountName;
        private String institutionName;
        private String accountType;
        private String accountSubtype;
        private String accountNumber;
        private String
                cardNumber; // Card number for credit cards (detected but separate from account
        // number)
        private String matchedAccountId;
        private java.math.BigDecimal balance; // Detected balance from statement/import

        // Credit card statement metadata (from PDF imports)
        private java.time.LocalDate paymentDueDate; // Payment due date extracted from statement
        private java.math.BigDecimal minimumPaymentDue; // Minimum payment due amount
        private Long rewardPoints; // Reward points (0 to 10 million)

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(final String accountName) {
            this.accountName = accountName;
        }

        public String getInstitutionName() {
            return institutionName;
        }

        public void setInstitutionName(final String institutionName) {
            this.institutionName = institutionName;
        }

        public String getAccountType() {
            return accountType;
        }

        public void setAccountType(final String accountType) {
            this.accountType = accountType;
        }

        public String getAccountSubtype() {
            return accountSubtype;
        }

        public void setAccountSubtype(final String accountSubtype) {
            this.accountSubtype = accountSubtype;
        }

        public String getAccountNumber() {
            return accountNumber;
        }

        public void setAccountNumber(final String accountNumber) {
            this.accountNumber = accountNumber;
        }

        public String getCardNumber() {
            return cardNumber;
        }

        public void setCardNumber(final String cardNumber) {
            this.cardNumber = cardNumber;
        }

        public String getMatchedAccountId() {
            return matchedAccountId;
        }

        public void setMatchedAccountId(final String matchedAccountId) {
            this.matchedAccountId = matchedAccountId;
        }

        public java.math.BigDecimal getBalance() {
            return balance;
        }

        public void setBalance(final java.math.BigDecimal balance) {
            this.balance = balance;
        }

        public java.time.LocalDate getPaymentDueDate() {
            return paymentDueDate;
        }

        public void setPaymentDueDate(final java.time.LocalDate paymentDueDate) {
            this.paymentDueDate = paymentDueDate;
        }

        public java.math.BigDecimal getMinimumPaymentDue() {
            return minimumPaymentDue;
        }

        public void setMinimumPaymentDue(final java.math.BigDecimal minimumPaymentDue) {
            this.minimumPaymentDue = minimumPaymentDue;
        }

        public Long getRewardPoints() {
            return rewardPoints;
        }

        public void setRewardPoints(final Long rewardPoints) {
            this.rewardPoints = rewardPoints;
        }
    }

    public static class CSVImportPreviewResponse {
        private int totalParsed;
        private List<Map<String, Object>> transactions;
        private DetectedAccountInfo detectedAccount;
        private List<String>
                infoMessages; // non-fatal notes from the parser (OCR used, fallback used, etc.)
        // P1: Pagination fields
        private int page;
        private int size;
        private int totalPages;
        private int totalElements;

        public int getTotalParsed() {
            return totalParsed;
        }

        public void setTotalParsed(final int totalParsed) {
            this.totalParsed = totalParsed;
        }

        public List<Map<String, Object>> getTransactions() {
            return transactions;
        }

        public void setTransactions(final List<Map<String, Object>> transactions) {
            this.transactions = transactions;
        }

        public DetectedAccountInfo getDetectedAccount() {
            return detectedAccount;
        }

        public void setDetectedAccount(final DetectedAccountInfo detectedAccount) {
            this.detectedAccount = detectedAccount;
        }

        public List<String> getInfoMessages() {
            return infoMessages;
        }

        public void setInfoMessages(final List<String> infoMessages) {
            this.infoMessages = infoMessages;
        }

        public int getPage() {
            return page;
        }

        public void setPage(final int page) {
            this.page = page;
        }

        public int getSize() {
            return size;
        }

        public void setSize(final int size) {
            this.size = size;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public void setTotalPages(final int totalPages) {
            this.totalPages = totalPages;
        }

        public int getTotalElements() {
            return totalElements;
        }

        public void setTotalElements(final int totalElements) {
            this.totalElements = totalElements;
        }
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

        public int getTotalParsed() {
            return totalParsed;
        }

        public void setTotalParsed(final int totalParsed) {
            this.totalParsed = totalParsed;
        }

        public List<Map<String, Object>> getTransactions() {
            return transactions;
        }

        public void setTransactions(final List<Map<String, Object>> transactions) {
            this.transactions = transactions;
        }

        public DetectedAccountInfo getDetectedAccount() {
            return detectedAccount;
        }

        public void setDetectedAccount(final DetectedAccountInfo detectedAccount) {
            this.detectedAccount = detectedAccount;
        }

        public int getPage() {
            return page;
        }

        public void setPage(final int page) {
            this.page = page;
        }

        public int getSize() {
            return size;
        }

        public void setSize(final int size) {
            this.size = size;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public void setTotalPages(final int totalPages) {
            this.totalPages = totalPages;
        }

        public int getTotalElements() {
            return totalElements;
        }

        public void setTotalElements(final int totalElements) {
            this.totalElements = totalElements;
        }
    }

    public static class PDFImportPreviewResponse {
        private int totalParsed;
        private List<Map<String, Object>> transactions;
        private DetectedAccountInfo detectedAccount;
        private List<String>
                infoMessages; // non-fatal notes (OCR fallback used, loose regex fallback, etc.)
        // P1: Pagination fields (for consistency with CSV preview)
        private int page;
        private int size;
        private int totalPages;
        private int totalElements;

        public int getTotalParsed() {
            return totalParsed;
        }

        public void setTotalParsed(final int totalParsed) {
            this.totalParsed = totalParsed;
        }

        public List<Map<String, Object>> getTransactions() {
            return transactions;
        }

        public void setTransactions(final List<Map<String, Object>> transactions) {
            this.transactions = transactions;
        }

        public DetectedAccountInfo getDetectedAccount() {
            return detectedAccount;
        }

        public void setDetectedAccount(final DetectedAccountInfo detectedAccount) {
            this.detectedAccount = detectedAccount;
        }

        public List<String> getInfoMessages() {
            return infoMessages;
        }

        public void setInfoMessages(final List<String> infoMessages) {
            this.infoMessages = infoMessages;
        }

        public int getPage() {
            return page;
        }

        public void setPage(final int page) {
            this.page = page;
        }

        public int getSize() {
            return size;
        }

        public void setSize(final int size) {
            this.size = size;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public void setTotalPages(final int totalPages) {
            this.totalPages = totalPages;
        }

        public int getTotalElements() {
            return totalElements;
        }

        public void setTotalElements(final int totalElements) {
            this.totalElements = totalElements;
        }
    }

    /**
     * Auto-create account if detected during import but not matched to existing account
     *
     * @param user The user
     * @param detectedAccount The detected account information
     * @return The account ID (newly created or existing)
     */
    /**
     * Auto-creates an account if detected during import. Uses optimistic locking to prevent race
     * conditions in concurrent imports.
     *
     * <p>Thread-safety: Uses account number + user ID as unique constraint to prevent duplicates.
     * If account already exists (by account number or name+institution), returns existing account
     * ID.
     *
     * @param user The user for whom to create the account
     * @param detectedAccount The detected account information
     * @return Account ID (existing or newly created), or null if creation fails
     */
    private String autoCreateAccountIfDetected(
            final UserTable user, final AccountDetectionService.DetectedAccount detectedAccount) {
        return autoCreateAccountIfDetected(user, detectedAccount, null);
    }

    /**
     * Auto-create account if detected, with optional metadata from PDF import
     *
     * @param user User creating the account
     * @param detectedAccount Detected account information
     * @param importResult Optional PDF import result containing metadata (paymentDueDate,
     *     minimumPaymentDue, rewardPoints)
     * @return Account ID of created or existing account
     */
    private String autoCreateAccountIfDetected(
            final UserTable user,
            final AccountDetectionService.DetectedAccount detectedAccount,
            final PDFImportService.ImportResult importResult) {
        if (detectedAccount == null) {
            LOGGER.warn(
                    "⚠️ autoCreateAccountIfDetected: detectedAccount is null - cannot create account");
            return null;
        }

        // CRITICAL FIX: Check if all fields are null/empty BEFORE attempting to create account
        // If all fields are empty, create account with defaults instead of returning null
        final boolean allFieldsNullOrEmpty =
                (detectedAccount.getInstitutionName() == null
                        || detectedAccount.getInstitutionName().isBlank())
                        && (detectedAccount.getAccountName() == null
                        || detectedAccount.getAccountName().isBlank())
                        && (detectedAccount.getAccountType() == null
                        || detectedAccount.getAccountType().isBlank())
                        && (detectedAccount.getAccountSubtype() == null
                        || detectedAccount.getAccountSubtype().isBlank())
                        && (detectedAccount.getAccountNumber() == null
                        || detectedAccount.getAccountNumber().isBlank())
                        && (detectedAccount.getMatchedAccountId() == null
                        || detectedAccount.getMatchedAccountId().isBlank());

        if (allFieldsNullOrEmpty) {
            LOGGER.info(
                    "⚠️ autoCreateAccountIfDetected: All detected account fields are null/empty - creating account with defaults.");
            // Continue to create account with defaults instead of returning null
        }

        LOGGER.info(
                "🔍 autoCreateAccountIfDetected: Starting account creation check - name='{}', institution='{}', type='{}', accountNumber='{}', matchedAccountId='{}'",
                detectedAccount.getAccountName(),
                detectedAccount.getInstitutionName(),
                detectedAccount.getAccountType(),
                detectedAccount.getAccountNumber() != null
                        ? "***"
                                + detectedAccount
                                        .getAccountNumber()
                                        .substring(
                                                Math.max(
                                                        0,
                                                        detectedAccount.getAccountNumber().length()
                                                                - 4))
                        : "null",
                detectedAccount.getMatchedAccountId());

        // CRITICAL: If matchedAccountId is provided, verify it exists and return it
        // This handles the case where account was already matched during import preview
        if (detectedAccount.getMatchedAccountId() != null
                && !detectedAccount.getMatchedAccountId().isBlank()) {
            // Verify the account exists and belongs to the user
            final Optional<AccountTable> matchedAccount =
                    accountRepository.findById(detectedAccount.getMatchedAccountId());
            if (matchedAccount.isPresent()
                    && matchedAccount.get().getUserId().equals(user.getUserId())) {
                LOGGER.info(
                        "✅ autoCreateAccountIfDetected: Using matched account ID: {}",
                        detectedAccount.getMatchedAccountId());
                return detectedAccount.getMatchedAccountId();
            } else {
                LOGGER.warn(
                        "⚠️ autoCreateAccountIfDetected: Matched account ID '{}' not found or doesn't belong to user - will create new account",
                        detectedAccount.getMatchedAccountId());
                // Continue to create new account
            }
        }

        try {
            // CRITICAL: Check if account already exists (by account number or name)
            // This check + save operation should be atomic, but DynamoDB doesn't support
            // transactions across tables
            // We use account number as a natural unique key to prevent duplicates
            final List<AccountTable> existingAccounts = accountRepository.findByUserId(user.getUserId());
            LOGGER.info(
                    "🔍 autoCreateAccountIfDetected: Checking {} existing accounts for user",
                    existingAccounts != null ? existingAccounts.size() : 0);

            if (existingAccounts != null && !existingAccounts.isEmpty()) {
                for (final AccountTable existing : existingAccounts) {
                    // Match by account number if available (most reliable)
                    // CRITICAL FIX: Normalize account numbers before comparison (handles hyphens,
                    // spaces, etc.)
                    if (detectedAccount.getAccountNumber() != null
                            && !detectedAccount.getAccountNumber().isBlank()
                            && existing.getAccountNumber() != null
                            && normalizeAccountNumber(detectedAccount.getAccountNumber())
                                    .equals(normalizeAccountNumber(existing.getAccountNumber()))) {
                        LOGGER.info(
                                "✅ autoCreateAccountIfDetected: Found existing account by account number: {} (name: '{}')",
                                existing.getAccountId(),
                                existing.getAccountName());
                        // Update balance with date comparison if balance and date are available
                        if (detectedAccount.getBalance() != null
                                && detectedAccount.getBalanceDate() != null) {
                            final boolean balanceUpdated =
                                    accountDetectionService.updateAccountBalanceWithDateComparison(
                                            existing,
                                            detectedAccount.getBalance(),
                                            detectedAccount.getBalanceDate());
                            if (balanceUpdated) {
                                existing.setUpdatedAt(Instant.now());
                                accountRepository.save(existing);
                                LOGGER.info(
                                        "✅ Updated existing account balance with date comparison: {} (date: {})",
                                        detectedAccount.getBalance(),
                                        detectedAccount.getBalanceDate());
                            }
                        }
                        return existing.getAccountId();
                    }
                    // Match by account name and institution (fallback)
                    if (detectedAccount.getAccountName() != null
                            && detectedAccount.getInstitutionName() != null
                            && existing.getAccountName() != null
                            && existing.getInstitutionName() != null
                            && existing.getAccountName().equals(detectedAccount.getAccountName())
                            && existing.getInstitutionName()
                                    .equals(detectedAccount.getInstitutionName())) {
                        LOGGER.info(
                                "✅ autoCreateAccountIfDetected: Found existing account by name and institution: {} (name: '{}', institution: '{}')",
                                existing.getAccountId(),
                                existing.getAccountName(),
                                existing.getInstitutionName());
                        // Update balance with date comparison if balance and date are available
                        if (detectedAccount.getBalance() != null
                                && detectedAccount.getBalanceDate() != null) {
                            final boolean balanceUpdated =
                                    accountDetectionService.updateAccountBalanceWithDateComparison(
                                            existing,
                                            detectedAccount.getBalance(),
                                            detectedAccount.getBalanceDate());
                            if (balanceUpdated) {
                                existing.setUpdatedAt(Instant.now());
                                accountRepository.save(existing);
                                LOGGER.info(
                                        "✅ Updated existing account balance with date comparison: {} (date: {})",
                                        detectedAccount.getBalance(),
                                        detectedAccount.getBalanceDate());
                            }
                        }
                        return existing.getAccountId();
                    }
                }
                LOGGER.info(
                        "🔍 autoCreateAccountIfDetected: No matching account found in {} existing accounts - will create new account",
                        existingAccounts.size());
            } else {
                LOGGER.info(
                        "🔍 autoCreateAccountIfDetected: No existing accounts found for user - will create new account");
            }

            // RACE CONDITION FIX: Use account number as unique identifier
            // If account number exists, try to find it again (another thread may have created it)
            if (detectedAccount.getAccountNumber() != null
                    && !detectedAccount.getAccountNumber().isBlank()) {
                // Re-check after initial check (double-check locking pattern)
                // Note: This is not perfect for DynamoDB, but reduces race condition window
                final List<AccountTable> recheckAccounts =
                        accountRepository.findByUserId(user.getUserId());
                if (recheckAccounts != null) {
                    for (final AccountTable existing : recheckAccounts) {
                        // CRITICAL FIX: Normalize account numbers before comparison
                        if (existing.getAccountNumber() != null
                                && normalizeAccountNumber(existing.getAccountNumber())
                                        .equals(
                                                normalizeAccountNumber(
                                                        detectedAccount.getAccountNumber()))) {
                            LOGGER.info(
                                    "📝 Account created by another thread, using existing: {}",
                                    existing.getAccountId());
                            return existing.getAccountId();
                        }
                    }
                }
            }

            // Create new account
            LOGGER.info(
                    "🔨 autoCreateAccountIfDetected: Creating new account - name='{}', institution='{}', type='{}'",
                    detectedAccount.getAccountName(),
                    detectedAccount.getInstitutionName(),
                    detectedAccount.getAccountType());

            final AccountTable newAccount = new AccountTable();
            newAccount.setAccountId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
            newAccount.setUserId(user.getUserId());

            // Set balance from detected account if available, with date comparison
            if (detectedAccount.getBalance() != null) {
                newAccount.setBalance(detectedAccount.getBalance());
                // Set balance date if available
                if (detectedAccount.getBalanceDate() != null) {
                    newAccount.setBalanceDate(detectedAccount.getBalanceDate());
                    LOGGER.info(
                            "✅ Set account balance from detected account: {} (date: {})",
                            detectedAccount.getBalance(),
                            detectedAccount.getBalanceDate());
                } else {
                    LOGGER.info(
                            "✅ Set account balance from detected account: {} (no balance date)",
                            detectedAccount.getBalance());
                }
            } else {
                LOGGER.debug(
                        "⚠️ No balance in detected account - account will be created with null balance");
            }

            // NOTE: allFieldsNullOrEmpty check was moved to the beginning of the method
            // This prevents creating accounts when no account information is detected

            // CRITICAL: Sanitize institution name - remove control characters and truncate if too
            // long
            String institutionName = detectedAccount.getInstitutionName();
            if (institutionName != null && !institutionName.isBlank()) {
                institutionName = sanitizeAccountName(institutionName);
            } else {
                institutionName = "Unknown";
                LOGGER.warn(
                        "⚠️ autoCreateAccountIfDetected: Institution name is null/empty, using default 'Unknown'");
            }
            newAccount.setInstitutionName(institutionName);

            // CRITICAL: Normalize account type - convert to lowercase and validate
            String accountType = detectedAccount.getAccountType();
            if (accountType != null && !accountType.isBlank()) {
                accountType = accountType.trim().toLowerCase(Locale.ROOT);
                // Validate account type - if invalid, default to "other"
                if (!isValidAccountType(accountType)) {
                    LOGGER.warn("Invalid account type '{}', defaulting to 'other'", accountType);
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
            if (accountName != null && !accountName.isBlank()) {
                // Use the provided account name (sanitize it)
                accountName = sanitizeAccountName(accountName);
            } else {
                // Generate account name in format: <institutionName> <accountType> <last4digits>
                // CRITICAL: If all original fields were null/empty, pass null to
                // generateAccountName
                // so it returns "Imported Account" (for test compatibility)
                if (allFieldsNullOrEmpty) {
                    accountName = generateAccountName(null, null, null, null);
                } else {
                    // Use the sanitized/normalized values we just set
                    // Prefer subtype over type (e.g., "checking" is better than "depository")
                    final String accountSubtype = detectedAccount.getAccountSubtype();
                    accountName =
                            generateAccountName(
                                    institutionName, accountType, accountSubtype, accountNumber);
                }
                accountName = sanitizeAccountName(accountName);
            }
            newAccount.setAccountName(accountName);
            if (accountNumber != null && !accountNumber.isBlank()) {
                accountNumber = accountNumber.trim();
                // Extract only digits
                final String digitsOnly = accountNumber.replaceAll("[^0-9]", "");
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

            // CRITICAL: Set metadata from import result if available (paymentDueDate,
            // minimumPaymentDue, rewardPoints)
            // This ensures metadata is set during account creation, not just during update
            if (importResult != null) {
                final LocalDate paymentDueDate = importResult.getPaymentDueDate();
                final BigDecimal minimumPaymentDue = importResult.getMinimumPaymentDue();
                final Long rewardPoints = importResult.getRewardPoints();

                if (paymentDueDate != null) {
                    newAccount.setPaymentDueDate(paymentDueDate);
                    if (minimumPaymentDue != null) {
                        newAccount.setMinimumPaymentDue(minimumPaymentDue);
                    }
                    if (rewardPoints != null) {
                        newAccount.setRewardPoints(rewardPoints);
                    }
                    LOGGER.info(
                            "✅ Set account metadata during creation - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}",
                            paymentDueDate,
                            minimumPaymentDue,
                            rewardPoints);
                }
            }

            final Instant now = Instant.now();
            newAccount.setCreatedAt(now);
            newAccount.setUpdatedAt(now);
            newAccount.setLastSyncedAt(now);
            newAccount.setUpdatedAtTimestamp(now.getEpochSecond());

            try {
                accountRepository.save(newAccount);
                LOGGER.info(
                        "✅✅✅ autoCreateAccountIfDetected: Successfully created account - ID: '{}', name: '{}', institution: '{}', type: '{}', accountNumber: '{}', paymentDueDate: '{}', minimumPaymentDue: '{}', rewardPoints: '{}'",
                        newAccount.getAccountId(),
                        newAccount.getAccountName(),
                        newAccount.getInstitutionName(),
                        newAccount.getAccountType(),
                        newAccount.getAccountNumber() != null
                                ? "***" + newAccount.getAccountNumber()
                                : "null",
                        newAccount.getPaymentDueDate(),
                        newAccount.getMinimumPaymentDue(),
                        newAccount.getRewardPoints());
                return newAccount.getAccountId();
            } catch (Exception saveException) {
                // If save fails due to duplicate (race condition), try to find existing account
                LOGGER.warn(
                        "Account save failed (possibly duplicate), attempting to find existing: {}",
                        saveException.getMessage());
                final List<AccountTable> finalCheck = accountRepository.findByUserId(user.getUserId());
                if (finalCheck != null) {
                    for (final AccountTable existing : finalCheck) {
                        // CRITICAL FIX: Normalize account numbers before comparison
                        if (detectedAccount.getAccountNumber() != null
                                && existing.getAccountNumber() != null
                                && normalizeAccountNumber(existing.getAccountNumber())
                                        .equals(
                                                normalizeAccountNumber(
                                                        detectedAccount.getAccountNumber()))) {
                            LOGGER.info(
                                    "📝 Found account after save failure: {}",
                                    existing.getAccountId());
                            return existing.getAccountId();
                        }
                    }
                }
                throw saveException; // Re-throw if we can't find existing account
            }
        } catch (Exception e) {
            LOGGER.error("Failed to auto-create account: {}", e.getMessage(), e);
            // Don't fail the import if account creation fails - transactions will use pseudo
            // account
            return null;
        }
    }

    /** Validate account type - returns true if valid, false otherwise */
    private boolean isValidAccountType(final String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return false;
        }
        final String normalized = accountType.toLowerCase(Locale.ROOT).trim();
        // Valid account types
        return "depository".equals(normalized)
                || "credit".equals(normalized)
                || "loan".equals(normalized)
                || "investment".equals(normalized)
                || "other".equals(normalized)
                || "brokerage".equals(normalized)
                || "checking".equals(normalized)
                || "savings".equals(normalized)
                || "creditcard".equals(normalized)
                || "mortgage".equals(normalized);
    }

    /**
     * Normalize account number - remove hyphens, spaces, and other separators, extract last 4
     * digits CRITICAL: This ensures consistent comparison regardless of format (e.g., "8-41007" vs
     * "841007" vs "8 41007")
     *
     * @param accountNumber Account number in any format (may contain hyphens, spaces, etc.)
     * @return Normalized account number (last 4 digits only, digits only)
     */
    private String normalizeAccountNumber(final String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "";
        }

        // Remove all non-digit characters (hyphens, spaces, masks, etc.)
        final String digitsOnly = accountNumber.replaceAll("[^0-9]", "");

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
     * Generate account name in format: <institutionName> <accountType> <last4digits> Example:
     * "Chase checking 1234" (uses subtype "checking" if available, otherwise type "depository")
     *
     * @param institutionName Institution name (e.g., "Chase Bank")
     * @param accountType Account type (e.g., "depository")
     * @param accountSubtype Account subtype (e.g., "checking") - preferred over accountType
     * @param accountNumber Account number (last 4 digits will be extracted)
     * @return Generated account name, or "Imported Account" if all inputs are null/empty
     */
    private String generateAccountName(
            final String institutionName,
            final String accountType,
            final String accountSubtype,
            final String accountNumber) {
        // If all fields are null/empty, return default "Imported Account" (for backward
        // compatibility with tests)
        if ((institutionName == null || institutionName.isBlank())
                && (accountType == null || accountType.isBlank())
                && (accountSubtype == null || accountSubtype.isBlank())
                && (accountNumber == null || accountNumber.isBlank())) {
            return "Imported Account";
        }

        final StringBuilder name = new StringBuilder();

        // Add institution name (default to "Unknown" if not provided)
        if (institutionName != null && !institutionName.isBlank()) {
            name.append(institutionName.trim());
        } else {
            name.append("Unknown");
        }

        // Prefer subtype over type (e.g., "checking" is better than "depository")
        String typeToUse = null;
        if (accountSubtype != null && !accountSubtype.isBlank()) {
            typeToUse = accountSubtype.trim();
        } else if (accountType != null && !accountType.isBlank()) {
            typeToUse = accountType.trim();
        }

        if (typeToUse != null) {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(typeToUse);
        } else {
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append("other");
        }

        // Extract and add last 4 digits from account number
        if (accountNumber != null && !accountNumber.isBlank()) {
            final String accountNum = accountNumber.trim();
            // Extract last 4 digits (handle cases where account number might have non-digits)
            final String digitsOnly = accountNum.replaceAll("[^0-9]", "");
            if (digitsOnly.length() >= 4) {
                if (name.length() > 0) {
                    name.append(' ');
                }
                name.append(digitsOnly.substring(digitsOnly.length() - 4));
            } else if (!digitsOnly.isEmpty()) {
                // If less than 4 digits, use what we have
                if (name.length() > 0) {
                    name.append(' ');
                }
                name.append(digitsOnly);
            }
        }

        final String result = name.toString().trim();
        // CRITICAL FIX: Ensure we always return a non-empty name
        return result.isEmpty() ? "Imported Account" : result;
    }

    /**
     * Generate account name in format: <institutionName><accountType><last4digits> Overloaded
     * method for backward compatibility (without subtype)
     */
    private String generateAccountName(
            final String institutionName, final String accountType, final String accountNumber) {
        return generateAccountName(institutionName, accountType, null, accountNumber);
    }

    private String sanitizeAccountName(final String name) {
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
        @jakarta.validation.constraints.Size(
                max = 10_000,
                message = "Batch size cannot exceed 10000 transactions")
        private List<CreateTransactionRequest> transactions;

        private DetectedAccountInfo detectedAccount;

        private Boolean createDetectedAccount;

        public List<CreateTransactionRequest> getTransactions() {
            return transactions;
        }

        public void setTransactions(final List<CreateTransactionRequest> transactions) {
            this.transactions = transactions;
        }

        public DetectedAccountInfo getDetectedAccount() {
            return detectedAccount;
        }

        public void setDetectedAccount(final DetectedAccountInfo detectedAccount) {
            this.detectedAccount = detectedAccount;
        }

        public Boolean getCreateDetectedAccount() {
            return createDetectedAccount;
        }

        public void setCreateDetectedAccount(final Boolean createDetectedAccount) {
            this.createDetectedAccount = createDetectedAccount;
        }
    }

    public static class BatchImportResponse {
        private int total;
        private int created;
        private int failed;
        private Integer duplicates; // Nullable for consistency with iOS
        private List<String> errors;
        private List<String> createdTransactionIds;
        private String createdAccountId; // Account ID if a new account was created during import

        public int getTotal() {
            return total;
        }

        public void setTotal(final int total) {
            this.total = total;
        }

        public int getCreated() {
            return created;
        }

        public void setCreated(final int created) {
            this.created = created;
        }

        public int getFailed() {
            return failed;
        }

        public void setFailed(final int failed) {
            this.failed = failed;
        }

        public Integer getDuplicates() {
            return duplicates;
        }

        public void setDuplicates(final Integer duplicates) {
            this.duplicates = duplicates;
        }

        public List<String> getErrors() {
            return errors;
        }

        public void setErrors(final List<String> errors) {
            this.errors = errors;
        }

        public List<String> getCreatedTransactionIds() {
            return createdTransactionIds;
        }

        public void setCreatedTransactionIds(final List<String> createdTransactionIds) {
            this.createdTransactionIds = createdTransactionIds;
        }

        public String getCreatedAccountId() {
            return createdAccountId;
        }

        public void setCreatedAccountId(final String createdAccountId) {
            this.createdAccountId = createdAccountId;
        }

        // Computed field for backward compatibility
        public boolean getSuccessful() {
            return created > 0 && failed == 0;
        }
    }

    /** Response for paginated chunk import Contains import results and pagination information */
    public static class ChunkImportResponse {
        private BatchImportResponse importResponse;
        private int page;
        private int size;
        private int total;
        private int totalPages;
        private boolean hasNext;

        public BatchImportResponse getImportResponse() {
            return importResponse;
        }

        public void setImportResponse(final BatchImportResponse importResponse) {
            this.importResponse = importResponse;
        }

        public int getPage() {
            return page;
        }

        public void setPage(final int page) {
            this.page = page;
        }

        public int getSize() {
            return size;
        }

        public void setSize(final int size) {
            this.size = size;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(final int total) {
            this.total = total;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public void setTotalPages(final int totalPages) {
            this.totalPages = totalPages;
        }

        public boolean isHasNext() {
            return hasNext;
        }

        public void setHasNext(final boolean hasNext) {
            this.hasNext = hasNext;
        }
    }

    // MARK: - Chunked Upload Endpoints

    /**
     * Uploads a chunk of a file Headers: - X-Chunk-Index: Chunk index (0-based) - X-Total-Chunks:
     * Total number of chunks - X-Upload-Id: Upload session ID (optional for first chunk, required
     * for subsequent chunks) - X-Filename: Original filename (required for first chunk) -
     * X-Content-Type: Content type (required for first chunk)
     */
    @PostMapping("/import-csv/upload-chunk")
    public ResponseEntity<ChunkUploadResponse> uploadChunk(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final byte[] chunkData,
            @RequestHeader(value = "X-Chunk-Index", required = false) final Integer chunkIndex,
            @RequestHeader(value = "X-Total-Chunks", required = false) final Integer totalChunks,
            @RequestHeader(value = "X-Upload-Id", required = false) String uploadId,
            @RequestHeader(value = "X-Filename", required = false) final String filename,
            @RequestHeader(value = "X-Content-Type", required = false) String contentType) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        // Validate user authentication
        userService
                .findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Validate required headers
        if (chunkIndex == null || totalChunks == null) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    "X-Chunk-Index and X-Total-Chunks headers are required");
        }

        // Generate upload ID if not provided (first chunk)
        if (uploadId == null || uploadId.isEmpty()) {
            uploadId = UUID.randomUUID().toString();
        }

        // Filename and content type required for first chunk
        if (chunkIndex == 0) {
            if (filename == null || filename.isEmpty()) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT, "X-Filename header is required for first chunk");
            }
            if (contentType == null || contentType.isEmpty()) {
                contentType = "text/csv"; // Default for CSV
            }
        }

        // Upload chunk
        final boolean isComplete =
                chunkedUploadService.uploadChunk(
                        uploadId, chunkIndex, totalChunks, chunkData, filename, contentType);

        LOGGER.info(
                "Chunk {}/{} uploaded for uploadId: {} (complete: {})",
                chunkIndex + 1,
                totalChunks,
                uploadId,
                isComplete);

        return ResponseEntity.ok(new ChunkUploadResponse(uploadId, chunkIndex, isComplete));
    }

    /** Finalizes a chunked upload and processes the file */
    @PostMapping("/import-csv/finalize")
    public ResponseEntity<BatchImportResponse> finalizeChunkedUpload(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final Map<String, String> request,
            @RequestParam(required = false) final String accountId,
            @RequestParam(required = false) final String password) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        // Validate user authentication
        userService
                .findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final String uploadId = request.get("uploadId");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "uploadId is required");
        }

        try {
            // Finalize upload and get assembled file
            final ChunkedUploadService.AssembledFile assembledFile =
                    chunkedUploadService.finalizeUpload(uploadId);

            // Create a MultipartFile-like wrapper for the assembled file
            final MultipartFile file =
                    new MultipartFile() {
                        @Override
                        public String getName() {
                            return "file";
                        }

                        @Override
                        public String getOriginalFilename() {
                            return assembledFile.getFilename();
                        }

                        @Override
                        public String getContentType() {
                            return assembledFile.getContentType();
                        }

                        @Override
                        public boolean isEmpty() {
                            return assembledFile.getData().length == 0;
                        }

                        @Override
                        public long getSize() {
                            return assembledFile.getData().length;
                        }

                        @Override
                        public byte[] getBytes() throws IOException {
                            return assembledFile.getData();
                        }

                        @Override
                        public InputStream getInputStream() throws IOException {
                            return new ByteArrayInputStream(assembledFile.getData());
                        }

                        @Override
                        public void transferTo(final java.io.File dest)
                                throws IOException, IllegalStateException {
                            java.nio.file.Files.write(dest.toPath(), assembledFile.getData());
                        }
                    };

            // Process the file using existing import logic
            return importCSV(userDetails, file, accountId, password, assembledFile.getFilename());

        } catch (IOException e) {
            LOGGER.error("Error finalizing chunked upload: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to finalize upload: " + e.getMessage());
        }
    }

    /** Response for chunk upload */
    public static class ChunkUploadResponse {
        private String uploadId;
        private int chunkIndex;
        private boolean success;

        public ChunkUploadResponse(final String uploadId, final int chunkIndex, final boolean success) {
            this.uploadId = uploadId;
            this.chunkIndex = chunkIndex;
            this.success = success;
        }

        public String getUploadId() {
            return uploadId;
        }

        public void setUploadId(final String uploadId) {
            this.uploadId = uploadId;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public void setChunkIndex(final int chunkIndex) {
            this.chunkIndex = chunkIndex;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(final boolean success) {
            this.success = success;
        }
    }

    /**
     * Update account metadata from PDF import with "latest" logic Updates payment due date, minimum
     * payment due, reward points, and balance based on the statement with the latest (most recent)
     * payment due date
     *
     * <p>Logic: - If new payment due date is later than existing, update all metadata - If new
     * payment due date is earlier or equal, keep existing metadata - If account doesn't have
     * payment due date, use new values - Balance is updated from the statement with latest payment
     * due date
     *
     * @param accountId Account ID to update
     * @param importResult PDF import result containing metadata
     */
    private void updateAccountMetadataFromPDFImport(
            final String accountId, final PDFImportService.ImportResult importResult) {
        LOGGER.info("🔄 [Account Metadata Update] Starting update for accountId: '{}'", accountId);

        if (accountId == null || accountId.isBlank()) {
            LOGGER.warn("⚠️ [Account Metadata Update] Cannot update: accountId is null or empty");
            return;
        }

        if (importResult == null) {
            LOGGER.warn("⚠️ [Account Metadata Update] Cannot update: importResult is null");
            return;
        }

        LOGGER.info(
                "🔄 [Account Metadata Update] Import result metadata - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}",
                importResult.getPaymentDueDate(),
                importResult.getMinimumPaymentDue(),
                importResult.getRewardPoints());

        try {
            final Optional<AccountTable> accountOpt = accountRepository.findById(accountId);
            if (!accountOpt.isPresent()) {
                LOGGER.warn("⚠️ Cannot update account metadata: account '{}' not found", accountId);
                return;
            }

            final AccountTable account = accountOpt.get();
            boolean needsUpdate = false;

            // Get metadata from import result
            final LocalDate newPaymentDueDate = importResult.getPaymentDueDate();
            final BigDecimal newMinimumPaymentDue = importResult.getMinimumPaymentDue();
            final Long newRewardPoints = importResult.getRewardPoints();
            BigDecimal newBalance = null;

            // Get balance from detected account if available
            if (importResult.getDetectedAccount() != null
                    && importResult.getDetectedAccount().getBalance() != null) {
                newBalance = importResult.getDetectedAccount().getBalance();
            }

            // CRITICAL: Apply "latest" logic - only update if new payment due date is later
            final LocalDate existingPaymentDueDate = account.getPaymentDueDate();

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
                    LOGGER.info(
                            "📅 [Account Metadata] Setting initial payment due date: {} (no existing date)",
                            newPaymentDueDate);
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
                    LocalDate newBalanceDate = null;
                    if (importResult.getDetectedAccount() != null) {
                        newBalanceDate = importResult.getDetectedAccount().getBalanceDate();
                    }
                    if (newBalance != null && newBalanceDate != null) {
                        // Use date comparison logic for balance updates
                        final boolean balanceUpdated =
                                accountDetectionService.updateAccountBalanceWithDateComparison(
                                        account, newBalance, newBalanceDate);
                        if (balanceUpdated) {
                            needsUpdate = true;
                            LOGGER.info(
                                    "💰 [Account Metadata] Updated balance with date comparison: {} (date: {})",
                                    newBalance,
                                    newBalanceDate);
                        }
                    } else if (newBalance != null) {
                        // No balance date - update balance directly (backward compatibility)
                        account.setBalance(newBalance);
                        needsUpdate = true;
                        LOGGER.info(
                                "💰 [Account Metadata] Updated balance (no date): {}", newBalance);
                    }
                    needsUpdate = true;
                    LOGGER.info(
                            "📅 [Account Metadata] Updated to later payment due date: {} (was: {})",
                            newPaymentDueDate,
                            existingPaymentDueDate);
                } else {
                    // New payment due date is earlier or equal - keep existing metadata
                    // But still check balance update with date comparison
                    LocalDate newBalanceDate = null;
                    if (importResult.getDetectedAccount() != null) {
                        newBalanceDate = importResult.getDetectedAccount().getBalanceDate();
                    }
                    if (newBalance != null && newBalanceDate != null) {
                        final boolean balanceUpdated =
                                accountDetectionService.updateAccountBalanceWithDateComparison(
                                        account, newBalance, newBalanceDate);
                        if (balanceUpdated) {
                            needsUpdate = true;
                            LOGGER.info(
                                    "💰 [Account Metadata] Updated balance with date comparison (payment due date not updated): {} (date: {})",
                                    newBalance,
                                    newBalanceDate);
                        }
                    }
                    LOGGER.debug(
                            "📅 [Account Metadata] Keeping existing payment due date: {} (new: {} is not later)",
                            existingPaymentDueDate,
                            newPaymentDueDate);
                }
            } else {
                // No payment due date in import - use date comparison for balance update
                LocalDate newBalanceDate = null;
                if (importResult.getDetectedAccount() != null) {
                    newBalanceDate = importResult.getDetectedAccount().getBalanceDate();
                }
                if (newBalance != null && newBalanceDate != null) {
                    final boolean balanceUpdated =
                            accountDetectionService.updateAccountBalanceWithDateComparison(
                                    account, newBalance, newBalanceDate);
                    if (balanceUpdated) {
                        needsUpdate = true;
                        LOGGER.info(
                                "💰 [Account Metadata] Updated balance with date comparison: {} (date: {})",
                                newBalance,
                                newBalanceDate);
                    }
                } else if (newBalance != null && account.getBalance() == null) {
                    // No balance date - set initial balance if account has no balance
                    account.setBalance(newBalance);
                    needsUpdate = true;
                    LOGGER.info(
                            "💰 [Account Metadata] Setting initial balance: {} (no existing balance, no balance date)",
                            newBalance);
                }
            }

            // Update account if changes were made
            if (needsUpdate) {
                account.setUpdatedAt(Instant.now());
                LOGGER.info(
                        "💾 [Account Metadata Update] Saving account '{}' to DynamoDB with metadata - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}, balance: {}",
                        accountId,
                        account.getPaymentDueDate(),
                        account.getMinimumPaymentDue(),
                        account.getRewardPoints(),
                        account.getBalance());
                accountRepository.save(account);
                LOGGER.info(
                        "✅ [Account Metadata Update] Successfully saved account '{}' metadata to DynamoDB - paymentDueDate: {}, minimumPaymentDue: {}, rewardPoints: {}, balance: {}",
                        accountId,
                        account.getPaymentDueDate(),
                        account.getMinimumPaymentDue(),
                        account.getRewardPoints(),
                        account.getBalance());
            } else {
                LOGGER.info(
                        "ℹ️ [Account Metadata Update] No update needed for account '{}' - existing metadata is already up-to-date",
                        accountId);
            }
        } catch (Exception e) {
            LOGGER.error(
                    "❌ Error updating account metadata for account '{}': {}",
                    accountId,
                    e.getMessage(),
                    e);
            // Don't fail the import if metadata update fails
        }
    }

    // MARK: - Import History Response DTOs

    /** Import History Response DTO Matches the BackendImportHistory format expected by iOS app */
    public static class ImportHistoryResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("importId")
        private String id;

        private String userId;
        private String fileName;
        private String fileType;
        private String importSource;
        private String status;
        private int totalTransactions;
        private int successfulTransactions;
        private int failedTransactions;
        private int skippedTransactions;
        private int duplicateTransactions;
        private String accountId;

        @com.fasterxml.jackson.annotation.JsonFormat(
                shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                timezone = "UTC")
        private Instant startedAt;

        @com.fasterxml.jackson.annotation.JsonFormat(
                shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING,
                pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                timezone = "UTC")
        private Instant completedAt;

        private String errorMessage;
        private boolean canResume;
        private String importBatchId;

        public ImportHistoryResponse() {}

        public static ImportHistoryResponse from(final com.budgetbuddy.model.ImportHistory history) {
            final ImportHistoryResponse response = new ImportHistoryResponse();
            response.id = history.getImportId();
            response.userId = history.getUserId();
            response.fileName = history.getFileName();
            response.fileType = history.getFileType();
            response.importSource = history.getImportSource();
            response.status = history.getStatus();
            response.totalTransactions = history.getTotalTransactions();
            response.successfulTransactions = history.getSuccessfulTransactions();
            response.failedTransactions = history.getFailedTransactions();
            response.skippedTransactions = history.getSkippedTransactions();
            response.duplicateTransactions = history.getDuplicateTransactions();
            response.accountId = history.getAccountId();
            response.startedAt = history.getStartedAt();
            response.completedAt = history.getCompletedAt();
            response.errorMessage = history.getErrorMessage();
            response.canResume = history.isCanResume();
            response.importBatchId = history.getImportBatchId();
            return response;
        }

        // Getters and setters
        @com.fasterxml.jackson.annotation.JsonProperty("importId")
        public String getId() {
            return id;
        }

        @com.fasterxml.jackson.annotation.JsonProperty("importId")
        public void setId(final String id) {
            this.id = id;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(final String fileName) {
            this.fileName = fileName;
        }

        public String getFileType() {
            return fileType;
        }

        public void setFileType(final String fileType) {
            this.fileType = fileType;
        }

        public String getImportSource() {
            return importSource;
        }

        public void setImportSource(final String importSource) {
            this.importSource = importSource;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(final String status) {
            this.status = status;
        }

        public int getTotalTransactions() {
            return totalTransactions;
        }

        public void setTotalTransactions(final int totalTransactions) {
            this.totalTransactions = totalTransactions;
        }

        public int getSuccessfulTransactions() {
            return successfulTransactions;
        }

        public void setSuccessfulTransactions(final int successfulTransactions) {
            this.successfulTransactions = successfulTransactions;
        }

        public int getFailedTransactions() {
            return failedTransactions;
        }

        public void setFailedTransactions(final int failedTransactions) {
            this.failedTransactions = failedTransactions;
        }

        public int getSkippedTransactions() {
            return skippedTransactions;
        }

        public void setSkippedTransactions(final int skippedTransactions) {
            this.skippedTransactions = skippedTransactions;
        }

        public int getDuplicateTransactions() {
            return duplicateTransactions;
        }

        public void setDuplicateTransactions(final int duplicateTransactions) {
            this.duplicateTransactions = duplicateTransactions;
        }

        public String getAccountId() {
            return accountId;
        }

        public void setAccountId(final String accountId) {
            this.accountId = accountId;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(final Instant startedAt) {
            this.startedAt = startedAt;
        }

        public Instant getCompletedAt() {
            return completedAt;
        }

        public void setCompletedAt(final Instant completedAt) {
            this.completedAt = completedAt;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(final String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public boolean isCanResume() {
            return canResume;
        }

        public void setCanResume(final boolean canResume) {
            this.canResume = canResume;
        }

        public String getImportBatchId() {
            return importBatchId;
        }

        public void setImportBatchId(final String importBatchId) {
            this.importBatchId = importBatchId;
        }
    }

    /** Import Statistics Response DTO Matches the ImportStatistics format expected by iOS app */
    public static class ImportStatisticsResponse {
        private int totalImports;
        private int completedImports;
        private int failedImports;
        private int partialImports;
        private int totalTransactionsImported;
        private int totalTransactionsFailed;
        private int totalDuplicates;

        public ImportStatisticsResponse() {}

        public static ImportStatisticsResponse from(final java.util.Map<String, Object> stats) {
            final ImportStatisticsResponse response = new ImportStatisticsResponse();
            response.totalImports = ((Number) stats.getOrDefault("totalImports", 0)).intValue();
            response.completedImports =
                    ((Number) stats.getOrDefault("completedImports", 0)).intValue();
            response.failedImports = ((Number) stats.getOrDefault("failedImports", 0)).intValue();
            response.partialImports = ((Number) stats.getOrDefault("partialImports", 0)).intValue();
            response.totalTransactionsImported =
                    ((Number) stats.getOrDefault("totalTransactionsImported", 0)).intValue();
            response.totalTransactionsFailed =
                    ((Number) stats.getOrDefault("totalTransactionsFailed", 0)).intValue();
            response.totalDuplicates =
                    ((Number) stats.getOrDefault("totalDuplicates", 0)).intValue();
            return response;
        }

        // Getters and setters
        public int getTotalImports() {
            return totalImports;
        }

        public void setTotalImports(final int totalImports) {
            this.totalImports = totalImports;
        }

        public int getCompletedImports() {
            return completedImports;
        }

        public void setCompletedImports(final int completedImports) {
            this.completedImports = completedImports;
        }

        public int getFailedImports() {
            return failedImports;
        }

        public void setFailedImports(final int failedImports) {
            this.failedImports = failedImports;
        }

        public int getPartialImports() {
            return partialImports;
        }

        public void setPartialImports(final int partialImports) {
            this.partialImports = partialImports;
        }

        public int getTotalTransactionsImported() {
            return totalTransactionsImported;
        }

        public void setTotalTransactionsImported(final int totalTransactionsImported) {
            this.totalTransactionsImported = totalTransactionsImported;
        }

        public int getTotalTransactionsFailed() {
            return totalTransactionsFailed;
        }

        public void setTotalTransactionsFailed(final int totalTransactionsFailed) {
            this.totalTransactionsFailed = totalTransactionsFailed;
        }

        public int getTotalDuplicates() {
            return totalDuplicates;
        }

        public void setTotalDuplicates(final int totalDuplicates) {
            this.totalDuplicates = totalDuplicates;
        }
    }
}
