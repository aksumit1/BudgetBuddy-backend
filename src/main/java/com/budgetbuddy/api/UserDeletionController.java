package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserDeletionService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * User Deletion REST Controller Provides endpoints for secure data and account deletion
 *
 * <p>Features: - Delete all user data (keep account) - Delete Plaid integration only - Delete
 * account completely - GDPR compliant - Secure and encrypted
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@RestController
@RequestMapping("/api/user")
@Tag(name = "User Deletion", description = "User data and account deletion endpoints")
public class UserDeletionController {

    private static final String CONFIRMATION_REQUIRED_SET_CONFIRM_TRUE =
            "Confirmation required. Set confirm=true to proceed.";

    private static final String USER_NOT_AUTHENTICATED = "User not authenticated";

    private static final String USER_NOT_FOUND_1 = "User not found";

    private static final String ERROR = "error";

    private static final String MESSAGE = "message";

    private static final String STATUS = "status";

    private static final String SUCCESS = "success";

    private static final Logger LOGGER = LoggerFactory.getLogger(UserDeletionController.class);

    private final UserDeletionService userDeletionService;
    private final UserService userService;

    public UserDeletionController(
            final UserDeletionService userDeletionService, final UserService userService) {
        this.userDeletionService = userDeletionService;
        this.userService = userService;
    }

    /**
     * Delete All User Data Removes all financial data but keeps the account
     *
     * <p>Deletes: - All accounts and transactions - All budgets and goals - Plaid integration
     *
     * <p>Keeps: - User account (can still log in) - Audit logs (anonymized)
     */
    @DeleteMapping("/data")
    @Operation(
            summary = "Delete All User Data",
            description =
                    "Deletes all financial data (accounts, transactions, budgets, goals) but keeps the user account. Requires confirmation.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "All data deleted successfully"),
                @ApiResponse(responseCode = "400", description = "Confirmation required"),
                @ApiResponse(responseCode = "401", description = "Unauthorized"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    public ResponseEntity<Map<String, String>> deleteAllData(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(required = false, defaultValue = "false") final boolean confirm) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (!confirm) {
            return ResponseEntity.badRequest()
                    .body(Map.of(ERROR, CONFIRMATION_REQUIRED_SET_CONFIRM_TRUE));
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            userDeletionService.deleteAllUserData(user.getUserId());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("User {} deleted all their data", user.getUserId());
            }
            return ResponseEntity.ok(
                    Map.of(STATUS, SUCCESS, MESSAGE, "All user data deleted successfully"));
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Failed to delete user data for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to delete user data", null, null, e);
        }
    }

    /**
     * Delete Plaid Integration Removes Plaid connection and associated data
     *
     * <p>Deletes: - All accounts and transactions from Plaid - Plaid item connections
     *
     * <p>Keeps: - User account - Budgets and goals
     */
    @DeleteMapping("/plaid")
    @Operation(
            summary = "Delete Plaid Integration",
            description =
                    "Removes Plaid integration and all associated accounts/transactions. Requires confirmation.")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Plaid integration deleted successfully"),
                @ApiResponse(responseCode = "400", description = "Confirmation required"),
                @ApiResponse(responseCode = "401", description = "Unauthorized"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    public ResponseEntity<Map<String, String>> deletePlaidIntegration(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(required = false, defaultValue = "false") final boolean confirm) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (!confirm) {
            return ResponseEntity.badRequest()
                    .body(Map.of(ERROR, CONFIRMATION_REQUIRED_SET_CONFIRM_TRUE));
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            userDeletionService.deletePlaidIntegration(user.getUserId());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("User {} deleted Plaid integration", user.getUserId());
            }
            return ResponseEntity.ok(
                    Map.of(STATUS, SUCCESS, MESSAGE, "Plaid integration deleted successfully"));
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Failed to delete Plaid integration for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to delete Plaid integration",
                    null,
                    null,
                    e);
        }
    }

    /**
     * Delete Account Completely Permanently deletes the user account and all associated data
     *
     * <p>WARNING: This is irreversible!
     *
     * <p>Deletes: - All user data (accounts, transactions, budgets, goals) - Plaid integration -
     * User account
     *
     * <p>Keeps: - Audit logs (anonymized, for compliance)
     */
    @DeleteMapping("/account")
    @Operation(
            summary = "Delete Account Completely",
            description =
                    "Permanently deletes the user account and all associated data. This is irreversible. Requires confirmation.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Account deleted successfully"),
                @ApiResponse(responseCode = "400", description = "Confirmation required"),
                @ApiResponse(responseCode = "401", description = "Unauthorized"),
                @ApiResponse(responseCode = "500", description = "Internal server error")
            })
    public ResponseEntity<Map<String, String>> deleteAccount(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(required = false, defaultValue = "false") final boolean confirm) {

        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTHENTICATED);
        }

        if (!confirm) {
            return ResponseEntity.badRequest()
                    .body(Map.of(ERROR, CONFIRMATION_REQUIRED_SET_CONFIRM_TRUE));
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND_1));

        try {
            userDeletionService.deleteAccountCompletely(user.getUserId());
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("User {} deleted their account completely", user.getUserId());
            }
            return ResponseEntity.ok(
                    Map.of(STATUS, SUCCESS, MESSAGE, "Account deleted successfully"));
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Failed to delete account for user {}: {}",
                        user.getUserId(),
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to delete account", null, null, e);
        }
    }
}
