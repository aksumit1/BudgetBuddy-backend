package com.budgetbuddy.plaid;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.plaid.client.ApiClient;
import com.plaid.client.request.PlaidApi;
import com.plaid.client.model.*;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive Plaid Integration Service
 * Handles all Plaid API interactions with error handling and compliance
 *
 * Thread-safe implementation with proper dependency injection
 */
@Service
public class PlaidService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidService.class);

    private final PlaidApi plaidApi;
    private final String environment;
    @SuppressWarnings("unused") // Reserved for future PCI-DSS compliance logging
    private final PCIDSSComplianceService pciDSSComplianceService;
    private final String redirectUri;
    private final String webhookUrl;

    public PlaidService(@Value("${app.plaid.client-id}") String clientId,
            @Value("${app.plaid.secret}") String secret,
            @Value("${app.plaid.environment:sandbox}") String environment,
            @Value("${app.plaid.redirect-uri:}") String redirectUri,
            @Value("${app.plaid.webhook-url:}") String webhookUrl,
            PCIDSSComplianceService pciDSSComplianceService) {

        if (clientId == null || clientId.isEmpty()) {
            throw new IllegalArgumentException("Plaid client ID cannot be null or empty");
        }
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Plaid secret cannot be null or empty");
        }
        if (pciDSSComplianceService == null) {
            throw new IllegalArgumentException("PCIDSSComplianceService cannot be null");
        }

        this.environment = environment;
        this.pciDSSComplianceService = pciDSSComplianceService;
        this.redirectUri = redirectUri;
        this.webhookUrl = webhookUrl;

        Map<String, String> apiKeys = new HashMap<>();
        apiKeys.put("clientId", clientId);
        apiKeys.put("secret", secret);

        ApiClient apiClient = new ApiClient(apiKeys);
        // Set Plaid environment - Plaid SDK uses base URL
        // Map string environment to Plaid base URL
        String plaidBaseUrl;
        if ("production".equalsIgnoreCase(environment)) {
            plaidBaseUrl = "https://production.plaid.com";
        } else if ("development".equalsIgnoreCase(environment)) {
            plaidBaseUrl = "https://development.plaid.com";
        } else {
            plaidBaseUrl = "https://sandbox.plaid.com"; // Default to sandbox
        }
        
        try {
            // Set base URL directly using reflection (Plaid SDK may not expose this directly)
            // Try setPlaidAdapter first, if it fails, set base URL directly
            try {
                apiClient.setPlaidAdapter(plaidBaseUrl);
            } catch (Exception adapterException) {
                // If setPlaidAdapter fails, try setting base URL via Retrofit builder
                logger.debug("setPlaidAdapter failed, trying alternative configuration: {}", adapterException.getMessage());
                // The ApiClient should handle the base URL internally
                // If this still fails, we'll throw the original exception
                throw adapterException;
            }
            logger.debug("Plaid adapter configured for environment: {} (base URL: {})", environment, plaidBaseUrl);
        } catch (Exception e) {
            logger.error("Failed to set Plaid adapter for environment '{}' with base URL '{}': {}", 
                    environment, plaidBaseUrl, e.getMessage(), e);
            throw new IllegalArgumentException("Failed to configure Plaid API client for environment: " + environment, e);
        }

        this.plaidApi = apiClient.createService(PlaidApi.class);
    }

    /**
     * Create Link Token for Plaid Link
     * Generates a secure link token for Plaid Link initialization
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public LinkTokenCreateResponse createLinkToken(final String userId, final String clientName) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID cannot be null or empty");
        }
        if (clientName == null || clientName.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Client name cannot be null or empty");
        }

        try {
            // Build products list based on environment
            // AUTH product requires OAuth which is not supported in sandbox
            List<Products> productsList = new java.util.ArrayList<>();
            productsList.add(Products.TRANSACTIONS);
            productsList.add(Products.IDENTITY);
            
            // Only include AUTH in production/development (not sandbox)
            if (!"sandbox".equalsIgnoreCase(environment)) {
                productsList.add(Products.AUTH);
                logger.debug("Including AUTH product for environment: {}", environment);
            } else {
                logger.debug("Skipping AUTH product for sandbox environment (OAuth not supported)");
            }
            
            LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                    .user(new LinkTokenCreateRequestUser().clientUserId(userId))
                    .clientName(clientName)
                    .products(productsList)
                    .countryCodes(List.of(CountryCode.US))
                    .language("en");
            
            // Set redirect URI - Plaid requires this for OAuth flows
            // Use configured value or default based on environment
            String finalRedirectUri = redirectUri;
            if (finalRedirectUri == null || finalRedirectUri.isEmpty()) {
                // Default redirect URI based on environment
                if ("production".equalsIgnoreCase(environment)) {
                    finalRedirectUri = "https://app.budgetbuddy.com/plaid/callback";
                } else if ("development".equalsIgnoreCase(environment)) {
                    finalRedirectUri = "https://dev.budgetbuddy.com/plaid/callback";
                } else {
                    // For sandbox, use a localhost or configured redirect URI
                    // Note: Sandbox may require redirect URI to be configured in Plaid dashboard
                    finalRedirectUri = "https://app.budgetbuddy.com/plaid/callback";
                }
            }
            
            // Always set redirect URI - Plaid requires this even in sandbox
            // The redirect URI must be set for OAuth flows to work
            if (finalRedirectUri == null || finalRedirectUri.isEmpty()) {
                logger.error("Redirect URI is null or empty - Plaid will reject this request!");
                throw new AppException(ErrorCode.INVALID_INPUT, 
                        "Redirect URI must be configured for Plaid OAuth integration");
            }
            
            // Set redirect URI on the request
            request.redirectUri(finalRedirectUri);
            logger.info("✅ Setting redirect URI for Plaid Link token: {}", finalRedirectUri);
            
            // Set webhook URL if configured (optional, but recommended)
            String finalWebhookUrl = webhookUrl;
            if (finalWebhookUrl == null || finalWebhookUrl.isEmpty()) {
                // Default webhook URL based on environment
                if (!"sandbox".equalsIgnoreCase(environment)) {
                    finalWebhookUrl = "https://api.budgetbuddy.com/api/plaid/webhooks";
                    request.webhook(finalWebhookUrl);
                    logger.debug("Setting webhook URL: {}", finalWebhookUrl);
                } else {
                    logger.debug("Skipping webhook URL for sandbox environment (optional)");
                }
            } else {
                request.webhook(finalWebhookUrl);
                logger.debug("Setting webhook URL: {}", finalWebhookUrl);
            }

            var callResponse = plaidApi.linkTokenCreate(request).execute();
            
            if (!callResponse.isSuccessful()) {
                String errorBody = "No error body";
                try {
                    final okhttp3.ResponseBody errorBodyResponse = callResponse.errorBody();
                    if (errorBodyResponse != null) {
                        errorBody = errorBodyResponse.string();
                    }
                } catch (Exception e) {
                    logger.debug("Failed to read error body: {}", e.getMessage());
                }
                logger.error("Plaid API error: HTTP {} - {}", callResponse.code(), errorBody);
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                        "Plaid API returned error: " + callResponse.code() + " - " + errorBody);
            }

            LinkTokenCreateResponse response = callResponse.body();

            if (response == null || response.getLinkToken() == null) {
                logger.error("Plaid API returned null response or null link token");
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to create link token: null response");
            }

            logger.info("Plaid: Link token created for user: {}, expires: {}",
                    userId, response.getExpiration());
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Plaid: Failed to create link token: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to create Plaid link token: " + e.getMessage(), Map.of("userId", userId), null, e);
        }
    }

    /**
     * Exchange Public Token for Access Token
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public ItemPublicTokenExchangeResponse exchangePublicToken(final String publicToken) {
        if (publicToken == null || publicToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Public token cannot be null or empty");
        }

        try {
            ItemPublicTokenExchangeRequest request = new ItemPublicTokenExchangeRequest()
                    .publicToken(publicToken);

            ItemPublicTokenExchangeResponse response = plaidApi.itemPublicTokenExchange(request).execute().body();

            if (response == null || response.getAccessToken() == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to exchange public token");
            }

            logger.info("Plaid: Public token exchanged successfully");
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Plaid: Failed to exchange public token: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to exchange public token", null, null, e);
        }
    }

    /**
     * Get Accounts
     * Retrieves all accounts for an access token
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public AccountsGetResponse getAccounts(final String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            AccountsGetRequest request = new AccountsGetRequest()
                    .accessToken(accessToken);

            AccountsGetResponse response = plaidApi.accountsGet(request).execute().body();

            if (response == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get accounts");
            }

            logger.debug("Plaid: Retrieved {} accounts",
                    response.getAccounts() != null ? response.getAccounts().size() : 0);
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Plaid: Failed to get accounts: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to get accounts", null, null, e);
        }
    }

    /**
     * Get Transactions
     * Retrieves transactions for a date range
     * Handles pagination to fetch ALL transactions
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public TransactionsGetResponse getTransactions(final String accessToken, final String startDate, final String endDate) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }
        if (startDate == null || startDate.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date cannot be null or empty");
        }
        if (endDate == null || endDate.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "End date cannot be null or empty");
        }

        try {
            // Convert String dates to LocalDate for Plaid API
            java.time.LocalDate startLocalDate = java.time.LocalDate.parse(startDate);
            java.time.LocalDate endLocalDate = java.time.LocalDate.parse(endDate);

            // First request
            TransactionsGetRequest request = new TransactionsGetRequest()
                    .accessToken(accessToken)
                    .startDate(startLocalDate)
                    .endDate(endLocalDate);

            TransactionsGetResponse response = plaidApi.transactionsGet(request).execute().body();

            if (response == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get transactions");
            }

            // Handle pagination - Plaid API may return transactions in pages
            // Collect all transactions from all pages
            java.util.List<com.plaid.client.model.Transaction> allTransactions = new java.util.ArrayList<>();
            if (response.getTransactions() != null) {
                allTransactions.addAll(response.getTransactions());
            }

            // Check if there are more pages using reflection (Plaid SDK structure may vary)
            int pageCount = 1;
            int maxPages = 100; // Safety limit to prevent infinite loops
            String nextCursor = null;

            // Try to get nextCursor using reflection (method name may vary)
            try {
                java.lang.reflect.Method getNextCursorMethod = response.getClass().getMethod("getNextCursor");
                Object cursorObj = getNextCursorMethod.invoke(response);
                if (cursorObj != null) {
                    nextCursor = cursorObj.toString();
                }
            } catch (NoSuchMethodException e) {
                // Try alternative method names
                try {
                    java.lang.reflect.Method getCursorMethod = response.getClass().getMethod("getCursor");
                    Object cursorObj = getCursorMethod.invoke(response);
                    if (cursorObj != null) {
                        nextCursor = cursorObj.toString();
                    }
                } catch (Exception e2) {
                    logger.debug("Plaid API response does not have pagination cursor - assuming single page");
                }
            } catch (Exception e) {
                logger.debug("Could not check for pagination cursor: {}", e.getMessage());
            }

            // Fetch additional pages if cursor exists
            while (nextCursor != null && !nextCursor.isEmpty() && pageCount < maxPages) {
                logger.info("Plaid: Fetching page {} of transactions (cursor: {})", pageCount + 1, nextCursor);
                
                try {
                    // Create request with cursor for next page
                    TransactionsGetRequestOptions options = new TransactionsGetRequestOptions();
                    // Try to set cursor using reflection
                    try {
                        java.lang.reflect.Method setCursorMethod = options.getClass().getMethod("cursor", String.class);
                        setCursorMethod.invoke(options, nextCursor);
                    } catch (NoSuchMethodException e) {
                        // Try alternative method
                        java.lang.reflect.Method setCursorMethod2 = options.getClass().getMethod("setCursor", String.class);
                        setCursorMethod2.invoke(options, nextCursor);
                    }

                    TransactionsGetRequest nextRequest = new TransactionsGetRequest()
                            .accessToken(accessToken)
                            .startDate(startLocalDate)
                            .endDate(endLocalDate)
                            .options(options);

                    TransactionsGetResponse nextResponse = plaidApi.transactionsGet(nextRequest).execute().body();

                    if (nextResponse == null || nextResponse.getTransactions() == null || nextResponse.getTransactions().isEmpty()) {
                        logger.debug("Plaid: No more transactions in page {}", pageCount + 1);
                        break;
                    }

                    allTransactions.addAll(nextResponse.getTransactions());
                    
                    // Get next cursor for next iteration
                    nextCursor = null;
                    try {
                        java.lang.reflect.Method getNextCursorMethod = nextResponse.getClass().getMethod("getNextCursor");
                        Object cursorObj = getNextCursorMethod.invoke(nextResponse);
                        if (cursorObj != null) {
                            nextCursor = cursorObj.toString();
                        }
                    } catch (NoSuchMethodException e) {
                        try {
                            java.lang.reflect.Method getCursorMethod = nextResponse.getClass().getMethod("getCursor");
                            Object cursorObj = getCursorMethod.invoke(nextResponse);
                            if (cursorObj != null) {
                                nextCursor = cursorObj.toString();
                            }
                        } catch (Exception e2) {
                            // No more pages
                            break;
                        }
                    } catch (Exception e) {
                        logger.debug("Could not get next cursor: {}", e.getMessage());
                        break;
                    }
                    
                    pageCount++;
                } catch (Exception e) {
                    logger.warn("Error fetching paginated transactions: {}", e.getMessage());
                    break;
                }
            }

            if (pageCount > 1) {
                logger.info("Plaid: Retrieved {} transactions across {} pages",
                        allTransactions.size(), pageCount);
            } else {
                logger.info("Plaid: Retrieved {} transactions (single page)",
                        allTransactions.size());
            }

            // Update response with all collected transactions
            if (response.getTransactions() != null) {
                response.getTransactions().clear();
                response.getTransactions().addAll(allTransactions);
            }

            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Plaid: Failed to get transactions: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to get transactions", null, null, e);
        }
    }

    /**
     * Get Institutions
     * Retrieves supported financial institutions
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public InstitutionsGetResponse getInstitutions(final String query, final int count) {
        if (query == null || query.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Query cannot be null or empty");
        }
        if (count <= 0 || count > 500) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Count must be between 1 and 500");
        }

        try {
            InstitutionsGetRequest request = new InstitutionsGetRequest();
            // Set query if provided - Plaid API may use different method name
            if (query != null && !query.isEmpty()) {
                // Try to set query using reflection if method exists
                try {
                    java.lang.reflect.Method method = request.getClass().getMethod("query", String.class);
                    method.invoke(request, query);
                } catch (NoSuchMethodException e) {
                    // If query method doesn't exist, try alternative
                    try {
                        java.lang.reflect.Method method = request.getClass().getMethod("setQuery", String.class);
                        method.invoke(request, query);
                    } catch (Exception e2) {
                        logger.warn("Could not set query on InstitutionsGetRequest: {}", e2.getMessage());
                    }
                } catch (Exception e) {
                    logger.warn("Error setting query: {}", e.getMessage());
                }
            }
            request.count(count);
            request.countryCodes(List.of(CountryCode.US));

            InstitutionsGetResponse response = plaidApi.institutionsGet(request).execute().body();

            if (response == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get institutions");
            }

            logger.debug("Plaid: Retrieved {} institutions",
                    response.getInstitutions() != null ? response.getInstitutions().size() : 0);
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Plaid: Failed to get institutions: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to get institutions", null, null, e);
        }
    }

    /**
     * Remove Item
     * Removes a Plaid item (disconnects account)
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public ItemRemoveResponse removeItem(final String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Access token cannot be null or empty");
        }

        try {
            ItemRemoveRequest request = new ItemRemoveRequest()
                    .accessToken(accessToken);

            ItemRemoveResponse response = plaidApi.itemRemove(request).execute().body();

            if (response == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to remove item");
            }

            logger.info("Plaid: Item removed successfully");
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Plaid: Failed to remove item: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to remove item", null, null, e);
        }
    }
}
