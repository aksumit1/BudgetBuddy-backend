package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final com.budgetbuddy.service.TransactionService transactionService;

    public PlaidController(final PlaidService plaidService, final PlaidSyncService plaidSyncService, 
            final UserService userService, final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository,
            final com.budgetbuddy.service.TransactionService transactionService) {
        this.plaidService = plaidService;
        this.plaidSyncService = plaidSyncService;
        this.userService = userService;
        this.accountRepository = accountRepository;
        this.transactionService = transactionService;
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
            String itemId = response.getItemId();

            if (accessToken == null || accessToken.isEmpty()) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Access token is null or empty");
            }
            if (itemId == null || itemId.isEmpty()) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Item ID is null or empty");
            }

            // Sync accounts and transactions (don't fail the request if sync fails)
            // CRITICAL: Pass itemId to syncAccounts to enable deduplication before API calls
            try {
                plaidSyncService.syncAccounts(user, accessToken, itemId);
            } catch (Exception syncError) {
                logger.warn("Account sync failed for user {} (non-fatal): {}", user.getUserId(), syncError.getMessage());
                // Continue - token exchange succeeded even if sync failed
            }

            try {
                plaidSyncService.syncTransactions(user, accessToken);
            } catch (Exception syncError) {
                logger.warn("Transaction sync failed for user {} (non-fatal): {}", user.getUserId(), syncError.getMessage());
                // Continue - token exchange succeeded even if sync failed
            }

            ExchangeTokenResponse tokenResponse = new ExchangeTokenResponse();
            tokenResponse.setAccessToken(accessToken);
            tokenResponse.setItemId(itemId);

            logger.info("Token exchanged successfully for user: {} (itemId: {})", user.getUserId(), itemId);
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

            // If accessToken is provided, try to fetch from Plaid API
            // If Plaid fails, fall back to database
            if (accessToken != null && !accessToken.isEmpty()) {
                try {
                    var response = plaidService.getAccounts(accessToken);
                    // Convert List<AccountBase> to List<Object> for AccountsResponse
                    if (response.getAccounts() != null) {
                        accountsResponse.setAccounts(new java.util.ArrayList<>(response.getAccounts()));
                    }
                    accountsResponse.setItem(response.getItem());
                    logger.debug("Retrieved accounts from Plaid API for user: {}", user.getUserId());
                } catch (Exception plaidError) {
                    // CRITICAL: If Plaid fails, fall back to database instead of throwing error
                    // This ensures users can still see their data even if Plaid has issues
                    logger.warn("Plaid API failed for user {}: {} - falling back to database", 
                            user.getUserId(), plaidError.getMessage());
                    
                    // Fall back to database
                    var accounts = accountRepository.findByUserId(user.getUserId());
                    if (accounts != null && !accounts.isEmpty()) {
                        accountsResponse.setAccounts(new java.util.ArrayList<>(accounts));
                        logger.info("Retrieved {} accounts from database (Plaid fallback) for user: {}", 
                                accounts.size(), user.getUserId());
                    } else {
                        accountsResponse.setAccounts(new java.util.ArrayList<>());
                        logger.warn("No accounts found in database for user: {} (Plaid also failed)", 
                                user.getUserId());
                    }
                    accountsResponse.setItem(null); // No item info when fetching from DB
                }
            } else {
                // If no accessToken, return accounts from database
                var accounts = accountRepository.findByUserId(user.getUserId());
                
                // Convert AccountTable to response format
                if (accounts != null && !accounts.isEmpty()) {
                    accountsResponse.setAccounts(new java.util.ArrayList<>(accounts));
                    logger.info("Retrieved {} accounts from database for user: {}", accounts.size(), user.getUserId());
                    // Log account details for debugging
                    for (var account : accounts) {
                        logger.debug("Account: {} (ID: {}, Active: {}, PlaidID: {})", 
                                account.getAccountName(), account.getAccountId(), 
                                account.getActive(), account.getPlaidAccountId());
                    }
                } else {
                    accountsResponse.setAccounts(new java.util.ArrayList<>());
                    logger.warn("No accounts found in database for user: {} (this may indicate accounts weren't saved or active flag is false)", 
                            user.getUserId());
                }
                accountsResponse.setItem(null); // No item info when fetching from DB
            }

            return ResponseEntity.ok(accountsResponse);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to get accounts for user {}: {}", user.getUserId(), e.getMessage(), e);
            // CRITICAL: Even if there's an unexpected error, try to return data from database
            // This ensures users can still see their data
            try {
                var accounts = accountRepository.findByUserId(user.getUserId());
                AccountsResponse accountsResponse = new AccountsResponse();
                if (accounts != null && !accounts.isEmpty()) {
                    accountsResponse.setAccounts(new java.util.ArrayList<>(accounts));
                    logger.info("Retrieved {} accounts from database (error fallback) for user: {}", 
                            accounts.size(), user.getUserId());
                    return ResponseEntity.ok(accountsResponse);
                }
            } catch (Exception dbError) {
                logger.error("Failed to fall back to database for user {}: {}", user.getUserId(), dbError.getMessage());
            }
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to retrieve accounts", null, null, e);
        }
    }

    /**
     * Get Transactions
     * Retrieves transactions for a date range from database (synced from Plaid)
     */
    @GetMapping("/transactions")
    @Operation(
        summary = "Get Transactions",
        description = "Retrieves transactions from database for the authenticated user within a date range. Transactions are synced from Plaid."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Transactions retrieved successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<java.util.List<com.budgetbuddy.model.dynamodb.TransactionTable>> getTransactions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) @Parameter(description = "Start date (YYYY-MM-DD)") String start,
            @RequestParam(required = false) @Parameter(description = "End date (YYYY-MM-DD)") String end) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            // Default to last 30 days if dates not provided
            java.time.LocalDate endDate = end != null ? java.time.LocalDate.parse(end) : java.time.LocalDate.now();
            java.time.LocalDate startDate = start != null ? java.time.LocalDate.parse(start) : endDate.minusDays(30);
            
            // Validate date range
            if (startDate.isAfter(endDate)) {
                logger.warn("Invalid date range for user {}: start date {} is after end date {}", 
                        user.getUserId(), startDate, endDate);
                throw new AppException(ErrorCode.INVALID_INPUT, 
                        "Start date must be before or equal to end date");
            }
            
            // Get transactions from database (synced transactions)
            var dbTransactions = transactionService.getTransactionsInRange(user, startDate, endDate);
            
            logger.info("Retrieved {} transactions from database for user: {} (date range: {} to {})", 
                    dbTransactions != null ? dbTransactions.size() : 0, user.getUserId(), startDate, endDate);
            if (dbTransactions == null || dbTransactions.isEmpty()) {
                logger.warn("No transactions found for user {} in date range {} to {}. Check if transactions were synced and have correct transactionDate.", 
                        user.getUserId(), startDate, endDate);
            }
            return ResponseEntity.ok(dbTransactions);
        } catch (AppException e) {
            throw e;
        } catch (java.time.format.DateTimeParseException e) {
            logger.error("Invalid date format for user {}: {}", user.getUserId(), e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid date format. Use YYYY-MM-DD");
        } catch (Exception e) {
            logger.error("Failed to get transactions for user {}: {}", user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to retrieve transactions", null, null, e);
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
            // Note: itemId is not available in sync request, so pass null
            // syncAccounts will extract itemId from Plaid response if needed
            plaidSyncService.syncAccounts(user, request.getAccessToken(), null);
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

    /**
     * Update Account Sync Settings
     * Updates lastSyncedAt for accounts after successful sync
     * This ensures sync settings are maintained in backend even if app is deleted
     * Accepts an array of account sync settings: [{"accountId": "...", "lastSyncedAt": epochSeconds}]
     * If empty array or null, updates all user accounts with current timestamp
     */
    @PutMapping("/accounts/sync-settings")
    @Operation(
        summary = "Update Account Sync Settings",
        description = "Updates lastSyncedAt for specified accounts after successful sync. Ensures sync settings persist in backend."
    )
    public ResponseEntity<Map<String, String>> updateAccountSyncSettings(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) List<AccountSyncSettingRequest> requests) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        try {
            int updatedCount = 0;
            
            if (requests != null && !requests.isEmpty()) {
                // Update specific accounts with provided lastSyncedAt values
                for (AccountSyncSettingRequest request : requests) {
                    if (request.getAccountId() == null || request.getAccountId().isEmpty()) {
                        logger.warn("Skipping sync setting update - accountId is null or empty");
                        continue;
                    }
                    
                    Optional<AccountTable> accountOpt = accountRepository.findById(request.getAccountId());
                    if (accountOpt.isEmpty()) {
                        logger.warn("Account not found for sync setting update: {}", request.getAccountId());
                        continue;
                    }
                    
                    AccountTable account = accountOpt.get();
                    // Verify the account belongs to the user
                    if (!account.getUserId().equals(user.getUserId())) {
                        logger.warn("Account {} does not belong to user {}, skipping sync setting update",
                                request.getAccountId(), user.getUserId());
                        continue;
                    }
                    
                    // Convert epoch seconds to Instant
                    if (request.getLastSyncedAt() != null && request.getLastSyncedAt() > 0) {
                        account.setLastSyncedAt(java.time.Instant.ofEpochSecond(request.getLastSyncedAt()));
                    } else {
                        // If lastSyncedAt is 0 or null, use current time
                        account.setLastSyncedAt(java.time.Instant.now());
                    }
                    
                    accountRepository.save(account);
                    updatedCount++;
                    logger.debug("Updated lastSyncedAt for account {}: {}", 
                            account.getAccountId(), account.getLastSyncedAt());
                }
            } else {
                // Fallback: If no specific requests, update all user accounts with current timestamp
                var userAccounts = accountRepository.findByUserId(user.getUserId());
                
                if (userAccounts.isEmpty()) {
                    logger.warn("No accounts found for user: {}", user.getUserId());
                    return ResponseEntity.ok(Map.of("status", "success", "message", "No accounts to update"));
                }
                
                java.time.Instant now = java.time.Instant.now();
                for (var account : userAccounts) {
                    account.setLastSyncedAt(now);
                    accountRepository.save(account);
                    updatedCount++;
                }
            }
            
            logger.info("Updated lastSyncedAt for {} accounts for user: {}", updatedCount, user.getUserId());
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Sync settings updated",
                    "accountsUpdated", String.valueOf(updatedCount)
            ));
        } catch (Exception e) {
            logger.error("Failed to update account sync settings for user {}: {}", 
                    user.getUserId(), e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to update sync settings", null, null, e);
        }
    }
    
    /**
     * Request DTO for account sync settings
     */
    public static class AccountSyncSettingRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("accountId")
        private String accountId;
        
        @com.fasterxml.jackson.annotation.JsonProperty("lastSyncedAt")
        private Long lastSyncedAt; // Epoch seconds
        
        public String getAccountId() {
            return accountId;
        }
        
        public void setAccountId(String accountId) {
            this.accountId = accountId;
        }
        
        public Long getLastSyncedAt() {
            return lastSyncedAt;
        }
        
        public void setLastSyncedAt(Long lastSyncedAt) {
            this.lastSyncedAt = lastSyncedAt;
        }
    }

    public static class SyncRequest {
        @NotBlank(message = "Access token is required")
        @com.fasterxml.jackson.annotation.JsonProperty("access_token")
        private String accessToken;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(final String accessToken) { this.accessToken = accessToken; }
    }
}
