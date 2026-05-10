package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionSyncService;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transaction Sync REST Controller Handles real-time and scheduled transaction synchronization
 *
 * <p>Features: - Full transaction sync - Incremental transaction sync - Sync status tracking -
 * Async processing
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@RestController
@RequestMapping("/api/transactions/sync")
@Tag(name = "Transactions", description = "Transaction management and synchronization")
public class TransactionSyncController {

    private static final String USER_NOT_AUTHENTICATED = "User not authenticated";

    private static final String USER_NOT_FOUND_1 = "User not found";

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionSyncController.class);

    private final TransactionSyncService transactionSyncService;
    private final UserService userService;

    public TransactionSyncController(
            final TransactionSyncService transactionSyncService, final UserService userService) {
        this.transactionSyncService = transactionSyncService;
        this.userService = userService;
    }

    /** Sync Transactions Triggers full transaction synchronization */
    @PostMapping
    @Operation(
            summary = "Sync Transactions",
            description =
                    "Triggers full synchronization of transactions from Plaid. Returns immediately with sync status.")
    public ResponseEntity<Map<String, Object>> syncTransactions(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam @NotBlank @Parameter(description = "Plaid access token") final String accessToken) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            transactionSyncService.syncTransactions(user.getUserId(), accessToken);

            LOGGER.info("Transaction sync started for user: {}", user.getUserId());

            return ResponseEntity.accepted()
                    .body(
                            Map.of(
                                    "status", "accepted",
                                    "message", "Transaction sync started",
                                    "userId", user.getUserId()));
        } catch (Exception e) {
            LOGGER.error(
                    "Failed to start transaction sync for user {}: {}",
                    user.getUserId(),
                    e.getMessage(),
                    e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to start transaction sync",
                    null,
                    null,
                    e);
        }
    }

    /** Sync Transactions Incremental Syncs only transactions since a specific date */
    @PostMapping("/incremental")
    @Operation(
            summary = "Sync Transactions Incremental",
            description =
                    "Syncs only new or updated transactions since a specific date for efficient updates")
    public ResponseEntity<TransactionSyncService.SyncResult> syncIncremental(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam @NotBlank @Parameter(description = "Plaid access token")
                    final String accessToken,
            @RequestParam
                    @Parameter(description = "Sync transactions since this date")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate sinceDate) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token is required");
        }

        if (sinceDate == null) {
            sinceDate = LocalDate.now().minusDays(30);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            final CompletableFuture<TransactionSyncService.SyncResult> future =
                    transactionSyncService.syncIncremental(
                            user.getUserId(), accessToken, sinceDate);

            final TransactionSyncService.SyncResult result = future.get();
            LOGGER.info(
                    "Incremental sync completed for user: {} - New: {}, Updated: {}",
                    user.getUserId(),
                    result.getNewCount(),
                    result.getUpdatedCount());

            return ResponseEntity.ok(result);
        } catch (java.util.concurrent.ExecutionException e) {
            LOGGER.error(
                    "Incremental sync execution failed for user {}: {}",
                    user.getUserId(),
                    e.getMessage(),
                    e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to sync transactions",
                    null,
                    null,
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error(
                    "Incremental sync interrupted for user {}: {}",
                    user.getUserId(),
                    e.getMessage());
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Sync operation interrupted", null, null, e);
        } catch (Exception e) {
            LOGGER.error(
                    "Failed to sync transactions for user {}: {}",
                    user.getUserId(),
                    e.getMessage(),
                    e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to sync transactions", null, null, e);
        }
    }

    /** Get Sync Status Returns the status of the last sync operation */
    @GetMapping("/status")
    @Operation(
            summary = "Get Sync Status",
            description = "Returns the status and results of the last transaction synchronization")
    public ResponseEntity<Map<String, Object>> getSyncStatus(
            @AuthenticationPrincipal final UserDetails userDetails) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        // In production, store sync status in database
        // For now, return a placeholder response
        return ResponseEntity.ok(
                Map.of(
                        "status", "completed",
                        "lastSync", java.time.Instant.now().toString(),
                        "userId", user.getUserId()));
    }
}
