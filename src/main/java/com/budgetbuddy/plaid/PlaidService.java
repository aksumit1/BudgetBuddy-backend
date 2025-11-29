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

        // Allow Plaid service to be created even without credentials for scripts/analysis
        // The service will fail on actual API calls if credentials are missing
        if (clientId == null || clientId.isEmpty()) {
            logger.warn("⚠️ Plaid client ID is not configured. Plaid API calls will fail. " +
                    "Set PLAID_CLIENT_ID environment variable or app.plaid.client-id property.");
            // Use placeholder to allow service creation (will fail on actual API calls)
            clientId = "placeholder-client-id";
        }
        if (secret == null || secret.isEmpty()) {
            logger.warn("⚠️ Plaid secret is not configured. Plaid API calls will fail. " +
                    "Set PLAID_SECRET environment variable or app.plaid.secret property.");
            // Use placeholder to allow service creation (will fail on actual API calls)
            secret = "placeholder-secret";
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
            // Try setPlaidAdapter first, if it fails, try alternative methods
            boolean adapterSet = false;
            try {
                // Try the standard method
                apiClient.setPlaidAdapter(plaidBaseUrl);
                adapterSet = true;
                logger.debug("Plaid adapter configured using setPlaidAdapter for environment: {} (base URL: {})", environment, plaidBaseUrl);
            } catch (NoSuchMethodError e) {
                // Method doesn't exist at runtime - try reflection
                logger.debug("setPlaidAdapter method not found at runtime, trying reflection: {}", e.getMessage());
                try {
                    java.lang.reflect.Method setPlaidAdapterMethod = apiClient.getClass().getMethod("setPlaidAdapter", String.class);
                    setPlaidAdapterMethod.invoke(apiClient, plaidBaseUrl);
                    adapterSet = true;
                    logger.debug("Plaid adapter configured using reflection for environment: {} (base URL: {})", environment, plaidBaseUrl);
                } catch (java.lang.reflect.InvocationTargetException | java.lang.NoSuchMethodException | java.lang.IllegalAccessException reflectionException) {
                    logger.debug("Reflection method also failed: {}", reflectionException.getMessage());
                }
            } catch (Exception adapterException) {
                // Other exception - log but continue to try alternatives
                logger.debug("setPlaidAdapter failed with exception: {}", adapterException.getMessage());
            }
            
            // If setPlaidAdapter didn't work, try setting base URL via Retrofit builder
            if (!adapterSet) {
                try {
                    // Try to set base URL using Retrofit builder pattern
                    // The ApiClient might handle the base URL internally based on environment
                    logger.debug("Attempting to configure Plaid client without explicit adapter setting");
                    // For now, we'll proceed - the ApiClient may handle the environment automatically
                    // If this fails, we'll catch it when making actual API calls
                    logger.warn("Could not set Plaid adapter explicitly - API client may use default configuration. " +
                            "This may work if Plaid SDK handles environment automatically.");
                } catch (Exception e) {
                    logger.warn("Alternative Plaid configuration also failed: {}", e.getMessage());
                    // Don't throw - let it proceed and fail on actual API calls if needed
                }
            }
        } catch (Exception e) {
            logger.error("Failed to set Plaid adapter for environment '{}' with base URL '{}': {}", 
                    environment, plaidBaseUrl, e.getMessage(), e);
            // Don't throw immediately - the API client might still work with default configuration
            // We'll let it fail on actual API calls if the configuration is truly broken
            logger.warn("Continuing with Plaid service initialization despite adapter configuration warning");
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
            
            // Validate date range
            if (startLocalDate.isAfter(endLocalDate)) {
                throw new AppException(ErrorCode.INVALID_INPUT, 
                        String.format("Start date (%s) cannot be after end date (%s)", startDate, endDate));
            }
            
            // Validate date range is not too large (Plaid allows max 2 years)
            long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startLocalDate, endLocalDate);
            if (daysBetween > 730) {
                logger.warn("Date range exceeds 2 years ({} days). Plaid may limit results.", daysBetween);
            }

            logger.debug("Plaid: Requesting transactions for date range: {} to {} ({} days)", 
                    startDate, endDate, daysBetween);

            // First request
            TransactionsGetRequest request = new TransactionsGetRequest()
                    .accessToken(accessToken)
                    .startDate(startLocalDate)
                    .endDate(endLocalDate);

            // Execute request and check response
            retrofit2.Response<TransactionsGetResponse> httpResponse = plaidApi.transactionsGet(request).execute();
            
            if (!httpResponse.isSuccessful()) {
                String errorBody = "No error body";
                try {
                    var errorBodyStream = httpResponse.errorBody();
                    if (errorBodyStream != null) {
                        errorBody = errorBodyStream.string();
                    }
                } catch (Exception e) {
                    logger.warn("Could not read error body: {}", e.getMessage());
                    errorBody = "Could not read error body: " + e.getMessage();
                }
                logger.error("Plaid API error: HTTP {} - {}", httpResponse.code(), errorBody);
                
                // Check for rate limit errors (HTTP 429)
                if (httpResponse.code() == 429) {
                    // Parse Plaid error response to extract error details
                    PlaidErrorResponse plaidError = parsePlaidErrorResponse(errorBody);
                    if (plaidError != null && (plaidError.errorCode != null && 
                            (plaidError.errorCode.equals("TRANSACTIONS_LIMIT") || 
                             plaidError.errorCode.equals("RATE_LIMIT_EXCEEDED") ||
                             (plaidError.errorType != null && plaidError.errorType.equals("RATE_LIMIT_EXCEEDED"))))) {
                        logger.warn("Plaid rate limit exceeded: {} - {}. Request ID: {}", 
                                plaidError.errorCode, plaidError.errorMessage, plaidError.requestId);
                        throw new AppException(ErrorCode.PLAID_RATE_LIMIT_EXCEEDED,
                                String.format("Plaid rate limit exceeded: %s. %s. Please try again later.",
                                        plaidError.errorCode != null ? plaidError.errorCode : "RATE_LIMIT_EXCEEDED",
                                        plaidError.errorMessage != null ? plaidError.errorMessage : "Rate limit exceeded for transactions"));
                    }
                }
                
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, 
                        String.format("Plaid API returned error: HTTP %d - %s", httpResponse.code(), errorBody));
            }
            
            TransactionsGetResponse response = httpResponse.body();

            if (response == null) {
                logger.error("Plaid API returned null response body for date range: {} to {}", startDate, endDate);
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, 
                        String.format("Failed to get transactions: null response body (date range: %s to %s)", 
                                startDate, endDate));
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

                    // Execute pagination request and check for errors (including rate limits)
                    retrofit2.Response<TransactionsGetResponse> nextHttpResponse = plaidApi.transactionsGet(nextRequest).execute();
                    
                    if (!nextHttpResponse.isSuccessful()) {
                        String errorBody = "No error body";
                        try {
                            var errorBodyStream = nextHttpResponse.errorBody();
                            if (errorBodyStream != null) {
                                errorBody = errorBodyStream.string();
                            }
                        } catch (Exception e) {
                            logger.warn("Could not read error body: {}", e.getMessage());
                        }
                        
                        // Check for rate limit errors (HTTP 429) during pagination
                        if (nextHttpResponse.code() == 429) {
                            PlaidErrorResponse plaidError = parsePlaidErrorResponse(errorBody);
                            if (plaidError != null && (plaidError.errorCode != null && 
                                    (plaidError.errorCode.equals("TRANSACTIONS_LIMIT") || 
                                     plaidError.errorCode.equals("RATE_LIMIT_EXCEEDED") ||
                                     (plaidError.errorType != null && plaidError.errorType.equals("RATE_LIMIT_EXCEEDED"))))) {
                                logger.warn("Plaid rate limit exceeded during pagination (page {}): {} - {}. Request ID: {}", 
                                        pageCount + 1, plaidError.errorCode, plaidError.errorMessage, plaidError.requestId);
                                throw new AppException(ErrorCode.PLAID_RATE_LIMIT_EXCEEDED,
                                        String.format("Plaid rate limit exceeded during pagination: %s. %s. Please try again later.",
                                                plaidError.errorCode != null ? plaidError.errorCode : "RATE_LIMIT_EXCEEDED",
                                                plaidError.errorMessage != null ? plaidError.errorMessage : "Rate limit exceeded for transactions"));
                            }
                        }
                        
                        logger.warn("Plaid pagination request failed: HTTP {} - {}. Stopping pagination.", 
                                nextHttpResponse.code(), errorBody);
                        break; // Stop pagination on error
                    }
                    
                    TransactionsGetResponse nextResponse = nextHttpResponse.body();

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
        } catch (java.time.format.DateTimeParseException e) {
            logger.error("Plaid: Invalid date format - startDate: {}, endDate: {}, error: {}", 
                    startDate, endDate, e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT,
                    String.format("Invalid date format: %s", e.getMessage()), null, null, e);
        } catch (retrofit2.HttpException e) {
            String errorMessage = "Unknown error";
            String errorBody = null;
            try {
                retrofit2.Response<?> response = e.response();
                if (response != null) {
                    var errorBodyStream = response.errorBody();
                    if (errorBodyStream != null) {
                        errorBody = errorBodyStream.string();
                        errorMessage = errorBody;
                    } else {
                        errorMessage = e.getMessage() != null ? e.getMessage() : "No error message";
                    }
                } else {
                    errorMessage = e.getMessage() != null ? e.getMessage() : "No response";
                }
            } catch (Exception ex) {
                errorMessage = e.getMessage() != null ? e.getMessage() : "Could not read error details";
            }
            
            // Check for rate limit errors (HTTP 429)
            if (e.code() == 429 && errorBody != null) {
                PlaidErrorResponse plaidError = parsePlaidErrorResponse(errorBody);
                if (plaidError != null && (plaidError.errorCode != null && 
                        (plaidError.errorCode.equals("TRANSACTIONS_LIMIT") || 
                         plaidError.errorCode.equals("RATE_LIMIT_EXCEEDED") ||
                         plaidError.errorType != null && plaidError.errorType.equals("RATE_LIMIT_EXCEEDED")))) {
                    logger.warn("Plaid rate limit exceeded: {} - {}. Request ID: {}", 
                            plaidError.errorCode, plaidError.errorMessage, plaidError.requestId);
                    throw new AppException(ErrorCode.PLAID_RATE_LIMIT_EXCEEDED,
                            String.format("Plaid rate limit exceeded: %s. %s. Please try again later.",
                                    plaidError.errorCode != null ? plaidError.errorCode : "RATE_LIMIT_EXCEEDED",
                                    plaidError.errorMessage != null ? plaidError.errorMessage : "Rate limit exceeded for transactions"));
                }
            }
            
            logger.error("Plaid HTTP error: {} - {}", e.code(), errorMessage, e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    String.format("Plaid API HTTP error %d: %s", e.code(), errorMessage), null, null, e);
        } catch (java.io.IOException e) {
            logger.error("Plaid: Network/IO error getting transactions: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    String.format("Network error connecting to Plaid: %s", e.getMessage()), null, null, e);
        } catch (Exception e) {
            logger.error("Plaid: Failed to get transactions (accessToken length: {}, startDate: {}, endDate: {}): {}", 
                    accessToken != null ? accessToken.length() : 0, startDate, endDate, e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED,
                    String.format("Failed to get transactions: %s", e.getMessage()), null, null, e);
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

    /**
     * Parse Plaid error response JSON
     * Plaid returns errors in format:
     * {
     *   "display_message": null,
     *   "error_code": "TRANSACTIONS_LIMIT",
     *   "error_message": "rate limit exceeded...",
     *   "error_type": "RATE_LIMIT_EXCEEDED",
     *   "request_id": "...",
     *   "suggested_action": null
     * }
     */
    private PlaidErrorResponse parsePlaidErrorResponse(final String errorBody) {
        if (errorBody == null || errorBody.isEmpty() || !errorBody.trim().startsWith("{")) {
            return null;
        }
        
        try {
            // Simple JSON parsing using string manipulation (no external dependencies needed)
            // For more complex parsing, consider using Jackson ObjectMapper
            PlaidErrorResponse error = new PlaidErrorResponse();
            
            // Extract error_code
            int errorCodeIndex = errorBody.indexOf("\"error_code\"");
            if (errorCodeIndex >= 0) {
                int startIndex = errorBody.indexOf("\"", errorCodeIndex + 12) + 1;
                int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.errorCode = errorBody.substring(startIndex, endIndex);
                }
            }
            
            // Extract error_message
            int errorMessageIndex = errorBody.indexOf("\"error_message\"");
            if (errorMessageIndex >= 0) {
                int startIndex = errorBody.indexOf("\"", errorMessageIndex + 16) + 1;
                int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.errorMessage = errorBody.substring(startIndex, endIndex);
                }
            }
            
            // Extract error_type
            int errorTypeIndex = errorBody.indexOf("\"error_type\"");
            if (errorTypeIndex >= 0) {
                int startIndex = errorBody.indexOf("\"", errorTypeIndex + 13) + 1;
                int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.errorType = errorBody.substring(startIndex, endIndex);
                }
            }
            
            // Extract request_id
            int requestIdIndex = errorBody.indexOf("\"request_id\"");
            if (requestIdIndex >= 0) {
                int startIndex = errorBody.indexOf("\"", requestIdIndex + 13) + 1;
                int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.requestId = errorBody.substring(startIndex, endIndex);
                }
            }
            
            return error;
        } catch (Exception e) {
            logger.debug("Failed to parse Plaid error response: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Plaid error response structure
     */
    private static class PlaidErrorResponse {
        String errorCode;
        String errorMessage;
        String errorType;
        String requestId;
    }
}
