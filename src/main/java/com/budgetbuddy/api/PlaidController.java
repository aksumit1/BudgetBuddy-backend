package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.service.PlaidSyncService;
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
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * Plaid Integration REST Controller
 * Provides endpoints for Plaid Link integration
 *
 * Features:
 * - Link token generation
 * - Public token exchange
 * - Account retrieval
 * - Data synchronization
 */
@RestController
@RequestMapping("/api/plaid")
@Tag(name = "Plaid", description = "Plaid financial data integration")
public class PlaidController {

    private static final Logger logger = LoggerFactory.getLogger(PlaidController.class);

    private final PlaidService plaidService;
    private final PlaidSyncService plaidSyncService;
    private final UserService userService;
    private final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    public PlaidController(final PlaidService plaidService, final PlaidSyncService plaidSyncService, 
            final UserService userService, final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository) {
        this.plaidService = plaidService;
        this.plaidSyncService = plaidSyncService;
        this.userService = userService;
        this.accountRepository = accountRepository;
    }

    /**
     * Create Link Token
     * Generates a link token for Plaid Link initialization
     */
    @PostMapping(value = "/link-token", produces = "application/json")
    @Operation(
        summary = "Create Plaid Link Token",
        description = "Generates a link token required to initialize Plaid Link. The token is used to securely connect a user's bank account."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Link token created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<LinkTokenResponse> createLinkToken(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            var response = plaidService.createLinkToken(user.getUserId(), "BudgetBuddy");

            LinkTokenResponse linkTokenResponse = new LinkTokenResponse();
            linkTokenResponse.setLinkToken(response.getLinkToken());
            if (response.getExpiration() != null) {
                linkTokenResponse.setExpiration(response.getExpiration().toString());
            }

            logger.info("Link token created for user: {}", user.getUserId());
            return ResponseEntity.ok(linkTokenResponse);
        } catch (Exception e) {
            logger.error("Failed to create link token for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to create link token", null, null, e);
        }
    }

    /**
     * Exchange Public Token
     * Exchanges a public token for an access token and syncs data
     */
    @PostMapping("/exchange-token")
    @Operation(
        summary = "Exchange Public Token",
        description = "Exchanges a Plaid public token for an access token and initiates data synchronization"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Token exchanged and data synced successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid public token"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<ExchangeTokenResponse> exchangePublicToken(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody ExchangeTokenRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        if (request == null || request.getPublicToken() == null || request.getPublicToken().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Public token is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            var response = plaidService.exchangePublicToken(request.getPublicToken());
            String accessToken = response.getAccessToken();

            // Sync accounts and transactions
            plaidSyncService.syncAccounts(user, accessToken);
            plaidSyncService.syncTransactions(user, accessToken);

            ExchangeTokenResponse tokenResponse = new ExchangeTokenResponse();
            tokenResponse.setAccessToken(accessToken);
            tokenResponse.setItemId(response.getItemId());

            logger.info("Token exchanged and data synced for user: {}", user.getUserId());
            return ResponseEntity.ok(tokenResponse);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to exchange token for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to exchange token", null, null, e);
        }
    }

    /**
     * Get Accounts
     * Retrieves accounts for the authenticated user
     * If accessToken is provided, fetches from Plaid API
     * If accessToken is not provided, returns accounts from database
     */
    @GetMapping("/accounts")
    @Operation(
        summary = "Get Accounts",
        description = "Retrieves all linked financial accounts for the authenticated user. If accessToken is provided, fetches from Plaid API. Otherwise, returns accounts from database."
    )
    public ResponseEntity<AccountsResponse> getAccounts(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) @Parameter(description = "Plaid access token (optional)") String accessToken) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            AccountsResponse accountsResponse = new AccountsResponse();

            // If accessToken is provided, fetch from Plaid API
            if (accessToken != null && !accessToken.isEmpty()) {
                var response = plaidService.getAccounts(accessToken);
                // Convert List<AccountBase> to List<Object> for AccountsResponse
                if (response.getAccounts() != null) {
                    accountsResponse.setAccounts(new java.util.ArrayList<>(response.getAccounts()));
                }
                accountsResponse.setItem(response.getItem());
                logger.debug("Retrieved accounts from Plaid API for user: {}", user.getUserId());
            } else {
                // If no accessToken, return accounts from database
                var accounts = accountRepository.findByUserId(user.getUserId());
                
                // Convert AccountTable to response format
                if (accounts != null && !accounts.isEmpty()) {
                    accountsResponse.setAccounts(new java.util.ArrayList<>(accounts));
                } else {
                    accountsResponse.setAccounts(new java.util.ArrayList<>());
                }
                accountsResponse.setItem(null); // No item info when fetching from DB
                logger.debug("Retrieved {} accounts from database for user: {}", 
                        accounts != null ? accounts.size() : 0, user.getUserId());
            }

            return ResponseEntity.ok(accountsResponse);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to get accounts for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to retrieve accounts", null, null, e);
        }
    }

    /**
     * Sync Data
     * Manually triggers data synchronization
     */
    @PostMapping("/sync")
    @Operation(
        summary = "Sync Financial Data",
        description = "Manually triggers synchronization of accounts and transactions from Plaid"
    )
    public ResponseEntity<Map<String, String>> syncData(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody SyncRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        if (request == null || request.getAccessToken() == null || request.getAccessToken().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            plaidSyncService.syncAccounts(user, request.getAccessToken());
            plaidSyncService.syncTransactions(user, request.getAccessToken());

            logger.info("Data synchronized for user: {}", user.getUserId());
            return ResponseEntity.ok(Map.of("status", "success", "message", "Data synchronized successfully"));
        } catch (Exception e) {
            logger.error("Failed to sync data for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to sync data", null, null, e);
        }
    }

    // DTOs
    public static class LinkTokenResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("link_token")
        private String linkToken;
        private String expiration;

        public String getLinkToken() { return linkToken; }
        public void setLinkToken(final String linkToken) { this.linkToken = linkToken; }
        public String getExpiration() { return expiration; }
        public void setExpiration(final String expiration) { this.expiration = expiration; }
    }

    public static class ExchangeTokenRequest {
        @NotBlank(message = "Public token is required")
        @com.fasterxml.jackson.annotation.JsonProperty("public_token")
        private String publicToken;

        public String getPublicToken() { return publicToken; }
        public void setPublicToken(final String publicToken) { this.publicToken = publicToken; }
    }

    public static class ExchangeTokenResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("access_token")
        private String accessToken;
        @com.fasterxml.jackson.annotation.JsonProperty("plaid_item_id")
        private String itemId;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(final String accessToken) { this.accessToken = accessToken; }
        public String getItemId() { return itemId; }
        public void setItemId(final String itemId) { this.itemId = itemId; }
    }

    public static class AccountsResponse {
        private java.util.List<Object> accounts;
        private Object item;

        public java.util.List<Object> getAccounts() { return accounts; }
        public void setAccounts(final java.util.List<Object> accounts) { this.accounts = accounts; }
        public Object getItem() { return item; }
        public void setItem(final Object item) { this.item = item; }
    }

    public static class SyncRequest {
        @NotBlank(message = "Access token is required")
        @com.fasterxml.jackson.annotation.JsonProperty("access_token")
        private String accessToken;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(final String accessToken) { this.accessToken = accessToken; }
    }
}
