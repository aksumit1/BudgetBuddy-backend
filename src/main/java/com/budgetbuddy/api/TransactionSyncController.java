package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionSyncService;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Transaction Sync REST Controller
 * Handles real-time and scheduled transaction synchronization
 *
 * Features:
 * - Full transaction sync
 * - Incremental transaction sync
 * - Sync status tracking
 * - Async processing
 */
@RestController
@RequestMapping("/api/transactions/sync")
@Tag(name = "Transactions", description = "Transaction management and synchronization")
public class TransactionSyncController {

    private static final Logger logger = LoggerFactory.getLogger(TransactionSyncController.class);

    private final TransactionSyncService transactionSyncService;
    private final UserService userService;

    public TransactionSyncController(final TransactionSyncService transactionSyncService, final UserService userService) {
        this.transactionSyncService = transactionSyncService;
        this.userService = userService;
    }

    /**
     * Sync Transactions
     * Triggers full transaction synchronization
     */
    @PostMapping
    @Operation(
        summary = "Sync Transactions",
        description = "Triggers full synchronization of transactions from Plaid. Returns immediately with sync status."
    )
    public ResponseEntity<Map<String, Object>> syncTransactions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam @NotBlank @Parameter(description = "Plaid access token") String accessToken) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            CompletableFuture<TransactionSyncService.SyncResult> future =
                    transactionSyncService.syncTransactions(user.getUserId(), accessToken);

            logger.info("Transaction sync started for user: {}", user.getUserId());

            return ResponseEntity.accepted().body(Map.of(
                    "status", "accepted",
                    "message", "Transaction sync started",
                    "userId", user.getUserId()
            ));
        } catch (Exception e) {
            logger.error("Failed to start transaction sync for user {}: {}",
                    user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to start transaction sync", null, null, e);
        }
    }

    /**
     * Sync Transactions Incremental
     * Syncs only transactions since a specific date
     */
    @PostMapping("/incremental")
    @Operation(
        summary = "Sync Transactions Incremental",
        description = "Syncs only new or updated transactions since a specific date for efficient updates"
    )
    public ResponseEntity<TransactionSyncService.SyncResult> syncIncremental(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam @NotBlank @Parameter(description = "Plaid access token") String accessToken,
            @RequestParam @Parameter(description = "Sync transactions since this date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sinceDate) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token is required");
        }

        if (sinceDate == null) {
            sinceDate = LocalDate.now().minusDays(30);
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            CompletableFuture<TransactionSyncService.SyncResult> future =
                    transactionSyncService.syncIncremental(user.getUserId(), accessToken, sinceDate);

            TransactionSyncService.SyncResult result = future.get();
            logger.info("Incremental sync completed for user: {} - New: {}, Updated: {}",
                    user.getUserId(), result.getNewCount(), result.getUpdatedCount());

            return ResponseEntity.ok(result);
        } catch (java.util.concurrent.ExecutionException e) {
            logger.error("Incremental sync execution failed for user {}: {}",
                    user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to sync transactions", null, null, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Incremental sync interrupted for user {}: {}",
                    user.getUserId(), e.getMessage());
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Sync operation interrupted", null, null, e);
        } catch (Exception e) {
            logger.error("Failed to sync transactions for user {}: {}",
                    user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to sync transactions", null, null, e);
        }
    }

    /**
     * Get Sync Status
     * Returns the status of the last sync operation
     */
    @GetMapping("/status")
    @Operation(
        summary = "Get Sync Status",
        description = "Returns the status and results of the last transaction synchronization"
    )
    public ResponseEntity<Map<String, Object>> getSyncStatus(
            @AuthenticationPrincipal UserDetails userDetails) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // In production, store sync status in database
        // For now, return a placeholder response
        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "lastSync", java.time.Instant.now().toString(),
                "userId", user.getUserId()
        ));
    }
}
