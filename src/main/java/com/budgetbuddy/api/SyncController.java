package com.budgetbuddy.api;

import com.budgetbuddy.dto.IncrementalSyncResponse;
import com.budgetbuddy.dto.SyncAllResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SyncService;
import com.budgetbuddy.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sync REST Controller Provides optimized sync endpoints for mobile apps
 *
 * <p>Endpoints: - GET /api/sync/all: Returns all user data (for first sync or force refresh) - GET
 * /api/sync/incremental: Returns only changed data since timestamp (for periodic sync)
 */
@RestController
@RequestMapping("/api/sync")
@Tag(name = "Sync", description = "Optimized sync endpoints for mobile apps")
public class SyncController {

    private static final String USER_ID_IS_INVALID = "User ID is invalid";

    private static final String USER_NOT_AUTHENTICATED = "User not authenticated";

    private static final String USER_NOT_FOUND_1 = "User not found";

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncController.class);

    private final SyncService syncService;
    private final UserService userService;

    public SyncController(final SyncService syncService, final UserService userService) {
        this.syncService = syncService;
        this.userService = userService;
    }

    /**
     * Get all user data Returns all accounts, transactions, budgets, goals, and actions Used for
     * first sync or force refresh
     *
     * @param userDetails Authenticated user details
     * @return All user data
     */
    @GetMapping("/all")
    @Operation(
            summary = "Get All User Data",
            description =
                    "Returns all user data (accounts, transactions, budgets, goals, actions) for first sync or force refresh")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Data retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<SyncAllResponse> getAllData(
            @AuthenticationPrincipal final UserDetails userDetails) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_INVALID);
        }

        LOGGER.info("Sync all request for user: {}", user.getUserId());

        final SyncAllResponse response = syncService.getAllData(user.getUserId());

        return ResponseEntity.ok(response);
    }

    /**
     * Get incremental changes since timestamp Returns only items that were created or updated after
     * the specified timestamp Used for periodic sync to minimize data transfer
     *
     * @param userDetails Authenticated user details
     * @param since Epoch timestamp (seconds) - only return items changed after this time
     * @return Incremental changes
     */
    @GetMapping("/incremental")
    @Operation(
            summary = "Get Incremental Changes",
            description =
                    "Returns only changed items since the specified timestamp for efficient periodic sync")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Changes retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<IncrementalSyncResponse> getIncrementalChanges(
            @AuthenticationPrincipal final UserDetails userDetails,
            @Parameter(
                            description =
                                    "Epoch timestamp (seconds) - only return items changed after this time")
                    @RequestParam(required = false) final Long since) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_INVALID);
        }

        LOGGER.info("Sync incremental request for user: {}, since: {}", user.getUserId(), since);

        final IncrementalSyncResponse response =
                syncService.getIncrementalChanges(user.getUserId(), since);

        return ResponseEntity.ok(response);
    }

    /**
     * Get sync status Returns current sync status, last sync time, and data counts Used by offline
     * mode to check sync state
     *
     * @param userDetails Authenticated user details
     * @return Sync status information
     */
    @GetMapping("/status")
    @Operation(
            summary = "Get Sync Status",
            description =
                    "Returns current sync status, last sync time, and data counts for offline mode support")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "User not authenticated"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<com.budgetbuddy.dto.SyncStatusResponse> getSyncStatus(
            @AuthenticationPrincipal final UserDetails userDetails) {

        if (userDetails == null
                || userDetails.getUsername() == null
                || userDetails.getUsername().isEmpty()) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_INVALID);
        }

        LOGGER.info("Sync status request for user: {}", user.getUserId());

        final com.budgetbuddy.dto.SyncStatusResponse response =
                syncService.getSyncStatus(user.getUserId());

        return ResponseEntity.ok(response);
    }
}
