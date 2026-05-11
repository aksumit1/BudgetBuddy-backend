package com.budgetbuddy.plaid;

import com.budgetbuddy.compliance.pcidss.PCIDSSComplianceService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.plaid.client.ApiClient;
import com.plaid.client.model.AccountsGetRequest;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.CountryCode;
import com.plaid.client.model.InstitutionsGetRequest;
import com.plaid.client.model.InstitutionsGetResponse;
import com.plaid.client.model.ItemPublicTokenExchangeRequest;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.ItemRemoveRequest;
import com.plaid.client.model.ItemRemoveResponse;
import com.plaid.client.model.LinkTokenCreateRequest;
import com.plaid.client.model.LinkTokenCreateRequestUser;
import com.plaid.client.model.LinkTokenCreateResponse;
import com.plaid.client.model.Products;
import com.plaid.client.model.TransactionsGetRequest;
import com.plaid.client.model.TransactionsGetRequestOptions;
import com.plaid.client.model.TransactionsGetResponse;
import com.plaid.client.model.WebhookVerificationKeyGetRequest;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import com.plaid.client.request.PlaidApi;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Comprehensive Plaid Integration Service Handles all Plaid API interactions with error handling
 * and compliance
 *
 * <p>Thread-safe implementation with proper dependency injection
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
        justification =
                "Spring constructor injection — beans are shared by design; CT_CONSTRUCTOR_THROW: Java 25 deprecates Object.finalize() for removal, so the finalizer-attack vector this rule guards against is not exploitable")
// Plaid SDK calls + reflection bootstrap — broad catches translate any
// runtime/SDK exception into AppException; narrowing is impractical here.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class PlaidService {

    private static final String PLAID = "plaid";

    private static final String ACCESS_TOKEN_CANNOT_BE_NULL_OR_EMPTY =
            "Access token cannot be null or empty";

    private static final String NO_ERROR_BODY = "No error body";

    private static final String RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

    private static final String RATE_LIMIT_EXCEEDED_FOR_TRANSACTIONS =
            "Rate limit exceeded for transactions";

    private static final String TRANSACTIONS_LIMIT = "TRANSACTIONS_LIMIT";

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidService.class);

    private final PlaidApi plaidApi;
    private final String environment;

    @SuppressWarnings({
        "unused",
        "PMD.AvoidCatchingGenericException"
    }) // Reserved for future PCI-DSS compliance logging
    private final PCIDSSComplianceService pciDSSComplianceService;

    private final String redirectUri;
    private final String webhookUrl;
    private final String clientId; // Store for validation
    private final String secret; // Store for validation

    public PlaidService(
            @Value("${app.plaid.client-id}") String clientId,
            @Value("${app.plaid.secret}") String secret,
            @Value("${app.plaid.environment:sandbox}") final String environment,
            @Value("${app.plaid.redirect-uri:}") final String redirectUri,
            @Value("${app.plaid.webhook-url:}") final String webhookUrl,
            @Value("${app.features.enable-plaid:true}") final boolean plaidEnabled,
            final PCIDSSComplianceService pciDSSComplianceService) {

        // Store original values for validation before modifying them
        final String originalClientId = clientId;
        final String originalSecret = secret;

        // Allow Plaid service to be created even without credentials for scripts/analysis
        // The service will fail on actual API calls if credentials are missing
        // Only warn if Plaid feature is enabled (to avoid noise in local development)
        if (clientId == null || clientId.isEmpty() || "placeholder-client-id".equals(clientId)) {
            if (plaidEnabled) {
                LOGGER.warn(
                        "⚠️ Plaid client ID is not configured. Plaid API calls will fail. "
                                + "Set PLAID_CLIENT_ID environment variable or app.plaid.client-id property.");
            } else {
                LOGGER.debug("Plaid client ID not configured (Plaid feature is disabled).");
            }
            // Use placeholder to allow service creation (will fail on actual API calls)
            clientId = "placeholder-client-id";
        }
        if (secret == null || secret.isEmpty() || "placeholder-secret".equals(secret)) {
            if (plaidEnabled) {
                LOGGER.warn(
                        "⚠️ Plaid secret is not configured. Plaid API calls will fail. "
                                + "Set PLAID_SECRET environment variable or app.plaid.secret property.");
            } else {
                LOGGER.debug("Plaid secret not configured (Plaid feature is disabled).");
            }
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
        this.clientId = originalClientId; // Store original for validation
        this.secret = originalSecret; // Store original for validation

        final Map<String, String> apiKeys = new HashMap<>();
        apiKeys.put("clientId", clientId);
        apiKeys.put("secret", secret);

        final ApiClient apiClient = new ApiClient(apiKeys);
        // Stamp every outbound Plaid request with the inbound correlation ID so a single
        // customer trace can be followed from the access log → Plaid request log →
        // back to a specific Plaid request_id. Without this, Plaid's request_ids are
        // floating identifiers that ops can't tie to a customer ticket.
        try {
            final okhttp3.OkHttpClient.Builder httpBuilder =
                    apiClient
                            .getOkBuilder()
                            .addInterceptor(
                                    chain -> {
                                        final String correlationId =
                                                org.slf4j.MDC.get("correlationId");
                                        if (correlationId == null || correlationId.isEmpty()) {
                                            return chain.proceed(chain.request());
                                        }
                                        return chain.proceed(
                                                chain.request()
                                                        .newBuilder()
                                                        .header("X-Correlation-Id", correlationId)
                                                        .build());
                                    });
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Plaid OkHttp interceptor for correlation-ID propagation attached: {}",
                        httpBuilder);
            }
        } catch (Exception e) {
            // Plaid SDK without getOkBuilder() — log and continue. Outbound Plaid calls
            // simply won't carry the header; everything else still works.
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Could not attach Plaid correlation-ID interceptor (SDK API mismatch): {}",
                        e.getMessage());
            }
        }
        // Set Plaid environment - Plaid SDK uses base URL
        // Map string environment to Plaid base URL
        final String plaidBaseUrl;
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
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Plaid adapter configured using setPlaidAdapter for environment: {} (base URL: {})",
                            environment,
                            plaidBaseUrl);
                }
            } catch (NoSuchMethodError e) {
                // Method doesn't exist at runtime - try reflection
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "setPlaidAdapter method not found at runtime, trying reflection: {}",
                            e.getMessage());
                }
                try {
                    final java.lang.reflect.Method setPlaidAdapterMethod =
                            apiClient.getClass().getMethod("setPlaidAdapter", String.class);
                    setPlaidAdapterMethod.invoke(apiClient, plaidBaseUrl);
                    adapterSet = true;
                    LOGGER.debug(
                            "Plaid adapter configured using reflection for environment: {} (base URL: {})",
                            environment,
                            plaidBaseUrl);
                } catch (java.lang.reflect.InvocationTargetException
                        | NoSuchMethodException
                        | IllegalAccessException reflectionException) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Reflection method also failed: {}",
                                reflectionException.getMessage());
                    }
                }
            } catch (Exception adapterException) {
                // Other exception - log but continue to try alternatives
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "setPlaidAdapter failed with exception: {}",
                            adapterException.getMessage());
                }
            }

            // If setPlaidAdapter didn't work, try setting base URL via Retrofit builder
            if (!adapterSet) {
                try {
                    // Try to set base URL using Retrofit builder pattern
                    // The ApiClient might handle the base URL internally based on environment
                    LOGGER.debug(
                            "Attempting to configure Plaid client without explicit adapter setting");
                    // For now, we'll proceed - the ApiClient may handle the environment
                    // automatically
                    // If this fails, we'll catch it when making actual API calls
                    LOGGER.warn(
                            "Could not set Plaid adapter explicitly - API client may use default configuration. "
                                    + "This may work if Plaid SDK handles environment automatically.");
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Alternative Plaid configuration also failed: {}", e.getMessage());
                    }
                    // Don't throw - let it proceed and fail on actual API calls if needed
                }
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Failed to set Plaid adapter for environment '{}' with base URL '{}': {}",
                        environment,
                        plaidBaseUrl,
                        e.getMessage(),
                        e);
            }
            // Don't throw immediately - the API client might still work with default configuration
            // We'll let it fail on actual API calls if the configuration is truly broken
            LOGGER.warn(
                    "Continuing with Plaid service initialization despite adapter configuration warning");
        }

        this.plaidApi = apiClient.createService(PlaidApi.class);
    }

    /**
     * Create Link Token for Plaid Link Generates a secure link token for Plaid Link initialization
     */
    @CircuitBreaker(name = PLAID)
    @Retry(name = PLAID)
    public LinkTokenCreateResponse createLinkToken(final String userId, final String clientName) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID cannot be null or empty");
        }
        if (clientName == null || clientName.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Client name cannot be null or empty");
        }

        // Validate Plaid credentials before making API call
        // Check if we're using placeholder credentials (which will fail)
        if (clientId == null || clientId.isEmpty() || "placeholder-client-id".equals(clientId)) {
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    "Plaid client ID is not configured. Please set app.plaid.client-id property or PLAID_CLIENT_ID environment variable. "
                            + "Get your Plaid credentials from https://dashboard.plaid.com/developers/keys");
        }
        if (secret == null || secret.isEmpty() || "placeholder-secret".equals(secret)) {
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    "Plaid secret is not configured. Please set app.plaid.secret property or PLAID_SECRET environment variable. "
                            + "Get your Plaid credentials from https://dashboard.plaid.com/developers/keys");
        }
        if (plaidApi == null) {
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    "Plaid API client is not initialized. Please check Plaid configuration.");
        }

        try {
            // Build products list based on environment
            // AUTH product requires OAuth which is not supported in sandbox
            final List<Products> productsList = new java.util.ArrayList<>();
            productsList.add(Products.TRANSACTIONS);
            productsList.add(Products.IDENTITY);

            // Only include AUTH in production/development (not sandbox)
            if (!"sandbox".equalsIgnoreCase(environment)) {
                productsList.add(Products.AUTH);
                LOGGER.debug("Including AUTH product for environment: {}", environment);
            } else {
                LOGGER.debug("Skipping AUTH product for sandbox environment (OAuth not supported)");
            }

            final LinkTokenCreateRequest request =
                    new LinkTokenCreateRequest()
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
                LOGGER.error("Redirect URI is null or empty - Plaid will reject this request!");
                throw new AppException(
                        ErrorCode.INVALID_INPUT,
                        "Redirect URI must be configured for Plaid OAuth integration");
            }

            // Set redirect URI on the request
            request.redirectUri(finalRedirectUri);
            LOGGER.info("✅ Setting redirect URI for Plaid Link token: {}", finalRedirectUri);

            // Set webhook URL if configured (optional, but recommended)
            String finalWebhookUrl = webhookUrl;
            if (finalWebhookUrl == null || finalWebhookUrl.isEmpty()) {
                // Default webhook URL based on environment
                if (!"sandbox".equalsIgnoreCase(environment)) {
                    finalWebhookUrl = "https://api.budgetbuddy.com/api/plaid/webhooks";
                    request.webhook(finalWebhookUrl);
                    LOGGER.debug("Setting webhook URL: {}", finalWebhookUrl);
                } else {
                    LOGGER.debug("Skipping webhook URL for sandbox environment (optional)");
                }
            } else {
                request.webhook(finalWebhookUrl);
                LOGGER.debug("Setting webhook URL: {}", finalWebhookUrl);
            }

            final var callResponse = plaidApi.linkTokenCreate(request).execute();

            if (!callResponse.isSuccessful()) {
                String errorBody = NO_ERROR_BODY;
                // Use try-with-resources to ensure ResponseBody is closed to prevent connection
                // leaks
                try (okhttp3.ResponseBody errorBodyResponse = callResponse.errorBody()) {
                    if (errorBodyResponse != null) {
                        errorBody = errorBodyResponse.string();
                    }
                } catch (Exception e) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Failed to read error body: {}", e.getMessage());
                    }
                }
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Plaid API error: HTTP {} - {}", callResponse.code(), errorBody);
                }
                throw new AppException(
                        ErrorCode.PLAID_CONNECTION_FAILED,
                        "Plaid API returned error: " + callResponse.code() + " - " + errorBody);
            }

            final LinkTokenCreateResponse response = callResponse.body();

            if (response == null || response.getLinkToken() == null) {
                LOGGER.error("Plaid API returned null response or null link token");
                throw new AppException(
                        ErrorCode.PLAID_CONNECTION_FAILED,
                        "Failed to create link token: null response");
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Plaid: Link token created for user: {}, expires: {}",
                        userId,
                        response.getExpiration());
            }
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid: Failed to create link token: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to create Plaid link token: " + e.getMessage(),
                    Map.of("userId", userId),
                    null,
                    e);
        }
    }

    /** Exchange Public Token for Access Token */
    @CircuitBreaker(name = PLAID)
    @Retry(name = PLAID)
    public ItemPublicTokenExchangeResponse exchangePublicToken(final String publicToken) {
        if (publicToken == null || publicToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Public token cannot be null or empty");
        }

        try {
            final ItemPublicTokenExchangeRequest request =
                    new ItemPublicTokenExchangeRequest().publicToken(publicToken);

            final ItemPublicTokenExchangeResponse response =
                    plaidApi.itemPublicTokenExchange(request).execute().body();

            if (response == null || response.getAccessToken() == null) {
                throw new AppException(
                        ErrorCode.PLAID_CONNECTION_FAILED, "Failed to exchange public token");
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Plaid: Public token exchanged successfully");
            }
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid: Failed to exchange public token: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to exchange public token",
                    null,
                    null,
                    e);
        }
    }

    /** Get Accounts Retrieves all accounts for an access token */
    @CircuitBreaker(name = PLAID)
    @Retry(name = PLAID)
    public AccountsGetResponse getAccounts(final String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, ACCESS_TOKEN_CANNOT_BE_NULL_OR_EMPTY);
        }

        try {
            final AccountsGetRequest request = new AccountsGetRequest().accessToken(accessToken);

            final AccountsGetResponse response = plaidApi.accountsGet(request).execute().body();

            if (response == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get accounts");
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Plaid: Retrieved {} accounts",
                        response.getAccounts() != null ? response.getAccounts().size() : 0);
            }
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid: Failed to get accounts: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get accounts", null, null, e);
        }
    }

    /**
     * Get Transactions Retrieves transactions for a date range Handles pagination to fetch ALL
     * transactions
     */
    @CircuitBreaker(name = PLAID)
    @Retry(name = PLAID)
    public TransactionsGetResponse getTransactions(
            final String accessToken, final String startDate, final String endDate) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, ACCESS_TOKEN_CANNOT_BE_NULL_OR_EMPTY);
        }
        if (startDate == null || startDate.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Start date cannot be null or empty");
        }
        if (endDate == null || endDate.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "End date cannot be null or empty");
        }

        try {
            // Convert String dates to LocalDate for Plaid API
            final java.time.LocalDate startLocalDate = java.time.LocalDate.parse(startDate);
            final java.time.LocalDate endLocalDate = java.time.LocalDate.parse(endDate);

            // Validate date range
            if (startLocalDate.isAfter(endLocalDate)) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT,
                        String.format(
                                "Start date (%s) cannot be after end date (%s)",
                                startDate, endDate));
            }

            // Validate date range is not too large (Plaid allows max 2 years)
            final long daysBetween =
                    java.time.temporal.ChronoUnit.DAYS.between(startLocalDate, endLocalDate);
            if (daysBetween > 730) {
                LOGGER.warn(
                        "Date range exceeds 2 years ({} days). Plaid may limit results.",
                        daysBetween);
            }

            LOGGER.debug(
                    "Plaid: Requesting transactions for date range: {} to {} ({} days)",
                    startDate,
                    endDate,
                    daysBetween);

            // First request
            final TransactionsGetRequest request =
                    new TransactionsGetRequest()
                            .accessToken(accessToken)
                            .startDate(startLocalDate)
                            .endDate(endLocalDate);

            // Execute request and check response
            final retrofit2.Response<TransactionsGetResponse> httpResponse =
                    plaidApi.transactionsGet(request).execute();

            if (!httpResponse.isSuccessful()) {
                String errorBody = NO_ERROR_BODY;
                // Use try-with-resources to ensure ResponseBody is closed to prevent connection
                // leaks
                try (var errorBodyStream = httpResponse.errorBody()) {
                    if (errorBodyStream != null) {
                        errorBody = errorBodyStream.string();
                    }
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Could not read error body: {}", e.getMessage());
                    }
                    errorBody = "Could not read error body: " + e.getMessage();
                }
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error("Plaid API error: HTTP {} - {}", httpResponse.code(), errorBody);
                }

                // Check for rate limit errors (HTTP 429)
                if (httpResponse.code() == 429) {
                    // Parse Plaid error response to extract error details
                    final PlaidErrorResponse plaidError = parsePlaidErrorResponse(errorBody);
                    if (plaidError != null
                            && (plaidError.errorCode != null
                                    && (TRANSACTIONS_LIMIT.equals(plaidError.errorCode)
                                            || RATE_LIMIT_EXCEEDED.equals(plaidError.errorCode)
                                            || (RATE_LIMIT_EXCEEDED.equals(
                                                    plaidError.errorType))))) {
                        LOGGER.warn(
                                "Plaid rate limit exceeded: {} - {}. Request ID: {}",
                                plaidError.errorCode,
                                plaidError.errorMessage,
                                plaidError.requestId);
                        throw new AppException(
                                ErrorCode.PLAID_RATE_LIMIT_EXCEEDED,
                                String.format(
                                        "Plaid rate limit exceeded: %s. %s. Please try again later.",
                                        plaidError.errorCode != null
                                                ? plaidError.errorCode
                                                : RATE_LIMIT_EXCEEDED,
                                        plaidError.errorMessage != null
                                                ? plaidError.errorMessage
                                                : RATE_LIMIT_EXCEEDED_FOR_TRANSACTIONS));
                    }
                }

                throw new AppException(
                        ErrorCode.PLAID_CONNECTION_FAILED,
                        String.format(
                                "Plaid API returned error: HTTP %d - %s",
                                httpResponse.code(), errorBody));
            }

            final TransactionsGetResponse response = httpResponse.body();

            if (response == null) {
                LOGGER.error(
                        "Plaid API returned null response body for date range: {} to {}",
                        startDate,
                        endDate);
                throw new AppException(
                        ErrorCode.PLAID_CONNECTION_FAILED,
                        String.format(
                                "Failed to get transactions: null response body (date range: %s to %s)",
                                startDate, endDate));
            }

            // Handle pagination - Plaid API may return transactions in pages
            // Collect all transactions from all pages
            final List<com.plaid.client.model.Transaction> allTransactions =
                    new java.util.ArrayList<>();
            if (response.getTransactions() != null) {
                allTransactions.addAll(response.getTransactions());
            }

            // Check if there are more pages using reflection (Plaid SDK structure may vary)
            int pageCount = 1;
            final int maxPages = 100; // Safety limit to prevent infinite loops
            String nextCursor = null;

            // Try to get nextCursor using reflection (method name may vary)
            try {
                final java.lang.reflect.Method getNextCursorMethod =
                        response.getClass().getMethod("getNextCursor");
                final Object cursorObj = getNextCursorMethod.invoke(response);
                if (cursorObj != null) {
                    nextCursor = cursorObj.toString();
                }
            } catch (NoSuchMethodException e) {
                // Try alternative method names
                try {
                    final java.lang.reflect.Method getCursorMethod =
                            response.getClass().getMethod("getCursor");
                    final Object cursorObj = getCursorMethod.invoke(response);
                    if (cursorObj != null) {
                        nextCursor = cursorObj.toString();
                    }
                } catch (Exception e2) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Plaid API response does not have pagination cursor - assuming single page");
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Could not check for pagination cursor: {}", e.getMessage());
                }
            }

            // Fetch additional pages if cursor exists
            while (nextCursor != null && !nextCursor.isEmpty() && pageCount < maxPages) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Plaid: Fetching page {} of transactions (cursor: {})",
                            pageCount + 1,
                            nextCursor);
                }

                try {
                    // Create request with cursor for next page
                    final TransactionsGetRequestOptions options =
                            new TransactionsGetRequestOptions();
                    // Try to set cursor using reflection
                    try {
                        final java.lang.reflect.Method setCursorMethod =
                                options.getClass().getMethod("cursor", String.class);
                        setCursorMethod.invoke(options, nextCursor);
                    } catch (NoSuchMethodException e) {
                        // Try alternative method
                        final java.lang.reflect.Method setCursorMethod2 =
                                options.getClass().getMethod("setCursor", String.class);
                        setCursorMethod2.invoke(options, nextCursor);
                    }

                    final TransactionsGetRequest nextRequest =
                            new TransactionsGetRequest()
                                    .accessToken(accessToken)
                                    .startDate(startLocalDate)
                                    .endDate(endLocalDate)
                                    .options(options);

                    // Execute pagination request and check for errors (including rate limits)
                    final retrofit2.Response<TransactionsGetResponse> nextHttpResponse =
                            plaidApi.transactionsGet(nextRequest).execute();

                    if (!nextHttpResponse.isSuccessful()) {
                        String errorBody = NO_ERROR_BODY;
                        // Use try-with-resources to ensure ResponseBody is closed to prevent
                        // connection leaks
                        try (var errorBodyStream = nextHttpResponse.errorBody()) {
                            if (errorBodyStream != null) {
                                errorBody = errorBodyStream.string();
                            }
                        } catch (Exception e) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn("Could not read error body: {}", e.getMessage());
                            }
                        }

                        // Check for rate limit errors (HTTP 429) during pagination
                        if (nextHttpResponse.code() == 429) {
                            final PlaidErrorResponse plaidError =
                                    parsePlaidErrorResponse(errorBody);
                            if (plaidError != null
                                    && (plaidError.errorCode != null
                                            && (TRANSACTIONS_LIMIT.equals(plaidError.errorCode)
                                                    || RATE_LIMIT_EXCEEDED.equals(
                                                            plaidError.errorCode)
                                                    || (RATE_LIMIT_EXCEEDED.equals(
                                                            plaidError.errorType))))) {
                                if (LOGGER.isWarnEnabled()) {
                                    LOGGER.warn(
                                            "Plaid rate limit exceeded during pagination (page {}): {} - {}. Request ID: {}",
                                            pageCount + 1,
                                            plaidError.errorCode,
                                            plaidError.errorMessage,
                                            plaidError.requestId);
                                }
                                throw new AppException(
                                        ErrorCode.PLAID_RATE_LIMIT_EXCEEDED,
                                        String.format(
                                                "Plaid rate limit exceeded during pagination: %s. %s. Please try again later.",
                                                plaidError.errorCode != null
                                                        ? plaidError.errorCode
                                                        : RATE_LIMIT_EXCEEDED,
                                                plaidError.errorMessage != null
                                                        ? plaidError.errorMessage
                                                        : RATE_LIMIT_EXCEEDED_FOR_TRANSACTIONS));
                            }
                        }

                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Plaid pagination request failed: HTTP {} - {}. Stopping pagination.",
                                    nextHttpResponse.code(),
                                    errorBody);
                        }
                        break; // Stop pagination on error
                    }

                    final TransactionsGetResponse nextResponse = nextHttpResponse.body();

                    if (nextResponse == null
                            || nextResponse.getTransactions() == null
                            || nextResponse.getTransactions().isEmpty()) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Plaid: No more transactions in page {}", pageCount + 1);
                        }
                        break;
                    }

                    allTransactions.addAll(nextResponse.getTransactions());

                    // Get next cursor for next iteration
                    nextCursor = null;
                    try {
                        final java.lang.reflect.Method getNextCursorMethod =
                                nextResponse.getClass().getMethod("getNextCursor");
                        final Object cursorObj = getNextCursorMethod.invoke(nextResponse);
                        if (cursorObj != null) {
                            nextCursor = cursorObj.toString();
                        }
                    } catch (NoSuchMethodException e) {
                        try {
                            final java.lang.reflect.Method getCursorMethod =
                                    nextResponse.getClass().getMethod("getCursor");
                            final Object cursorObj = getCursorMethod.invoke(nextResponse);
                            if (cursorObj != null) {
                                nextCursor = cursorObj.toString();
                            }
                        } catch (Exception e2) {
                            // No more pages
                            break;
                        }
                    } catch (Exception e) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Could not get next cursor: {}", e.getMessage());
                        }
                        break;
                    }

                    pageCount++;
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Error fetching paginated transactions: {}", e.getMessage());
                    }
                    break;
                }
            }

            if (pageCount > 1) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Plaid: Retrieved {} transactions across {} pages",
                            allTransactions.size(),
                            pageCount);
                }
            } else {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Plaid: Retrieved {} transactions (single page)",
                            allTransactions.size());
                }
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
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Plaid: Invalid date format - startDate: {}, endDate: {}, error: {}",
                        startDate,
                        endDate,
                        e.getMessage());
            }
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    String.format("Invalid date format: %s", e.getMessage()),
                    null,
                    null,
                    e);
        } catch (retrofit2.HttpException e) {
            String errorMessage = "Unknown error";
            String errorBody = null;
            try {
                final retrofit2.Response<?> response = e.response();
                if (response != null) {
                    final var errorBodyStream = response.errorBody();
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
                errorMessage =
                        e.getMessage() != null ? e.getMessage() : "Could not read error details";
            }

            // Check for rate limit errors (HTTP 429)
            if (e.code() == 429 && errorBody != null) {
                final PlaidErrorResponse plaidError = parsePlaidErrorResponse(errorBody);
                if (plaidError != null
                        && (plaidError.errorCode != null
                                && (TRANSACTIONS_LIMIT.equals(plaidError.errorCode)
                                        || RATE_LIMIT_EXCEEDED.equals(plaidError.errorCode)
                                        || RATE_LIMIT_EXCEEDED.equals(plaidError.errorType)))) {
                    LOGGER.warn(
                            "Plaid rate limit exceeded: {} - {}. Request ID: {}",
                            plaidError.errorCode,
                            plaidError.errorMessage,
                            plaidError.requestId);
                    throw new AppException(
                            ErrorCode.PLAID_RATE_LIMIT_EXCEEDED,
                            String.format(
                                    "Plaid rate limit exceeded: %s. %s. Please try again later.",
                                    plaidError.errorCode != null
                                            ? plaidError.errorCode
                                            : RATE_LIMIT_EXCEEDED,
                                    plaidError.errorMessage != null
                                            ? plaidError.errorMessage
                                            : RATE_LIMIT_EXCEEDED_FOR_TRANSACTIONS));
                }
            }

            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid HTTP error: {} - {}", e.code(), errorMessage, e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    String.format("Plaid API HTTP error %d: %s", e.code(), errorMessage),
                    null,
                    null,
                    e);
        } catch (java.io.IOException e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid: Network/IO error getting transactions: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    String.format("Network error connecting to Plaid: %s", e.getMessage()),
                    null,
                    null,
                    e);
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Plaid: Failed to get transactions (accessToken length: {}, startDate: {}, endDate: {}): {}",
                        accessToken.length(),
                        startDate,
                        endDate,
                        e.getMessage(),
                        e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    String.format("Failed to get transactions: %s", e.getMessage()),
                    null,
                    null,
                    e);
        }
    }

    /** Get Institutions Retrieves supported financial institutions */
    @CircuitBreaker(name = PLAID)
    @Retry(name = PLAID)
    public InstitutionsGetResponse getInstitutions(final String query, final int count) {
        if (query == null || query.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Query cannot be null or empty");
        }
        if (count <= 0 || count > 500) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Count must be between 1 and 500");
        }

        try {
            final InstitutionsGetRequest request = new InstitutionsGetRequest();
            // Set query if provided - Plaid API may use different method name
            if (!query.isEmpty()) {
                // Try to set query using reflection if method exists
                try {
                    final java.lang.reflect.Method method =
                            request.getClass().getMethod("query", String.class);
                    method.invoke(request, query);
                } catch (NoSuchMethodException e) {
                    // If query method doesn't exist, try alternative
                    try {
                        final java.lang.reflect.Method method =
                                request.getClass().getMethod("setQuery", String.class);
                        method.invoke(request, query);
                    } catch (Exception e2) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Could not set query on InstitutionsGetRequest: {}",
                                    e2.getMessage());
                        }
                    }
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn("Error setting query: {}", e.getMessage());
                    }
                }
            }
            request.count(count);
            request.countryCodes(List.of(CountryCode.US));

            final InstitutionsGetResponse response =
                    plaidApi.institutionsGet(request).execute().body();

            if (response == null) {
                throw new AppException(
                        ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get institutions");
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Plaid: Retrieved {} institutions",
                        response.getInstitutions() != null ? response.getInstitutions().size() : 0);
            }
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid: Failed to get institutions: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get institutions", null, null, e);
        }
    }

    /** Remove Item Removes a Plaid item (disconnects account) */
    @CircuitBreaker(name = PLAID)
    @Retry(name = PLAID)
    public ItemRemoveResponse removeItem(final String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, ACCESS_TOKEN_CANNOT_BE_NULL_OR_EMPTY);
        }

        try {
            final ItemRemoveRequest request = new ItemRemoveRequest().accessToken(accessToken);

            final ItemRemoveResponse response = plaidApi.itemRemove(request).execute().body();

            if (response == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to remove item");
            }

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Plaid: Item removed successfully");
            }
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Plaid: Failed to remove item: {}", e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED, "Failed to remove item", null, null, e);
        }
    }

    /**
     * Fetch the public JWK Plaid uses to sign a given webhook. The {@code keyId} is the {@code kid}
     * claim from the {@code Plaid-Verification} JWT header. Per Plaid's docs the key is rotated
     * occasionally; callers are responsible for caching with a sensible TTL.
     */
    @CircuitBreaker(name = PLAID)
    @Retry(name = PLAID)
    public WebhookVerificationKeyGetResponse webhookVerificationKeyGet(final String keyId) {
        if (keyId == null || keyId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "keyId cannot be null or empty");
        }
        try {
            final WebhookVerificationKeyGetRequest request =
                    new WebhookVerificationKeyGetRequest().keyId(keyId);
            final WebhookVerificationKeyGetResponse response =
                    plaidApi.webhookVerificationKeyGet(request).execute().body();
            if (response == null || response.getKey() == null) {
                throw new AppException(
                        ErrorCode.PLAID_CONNECTION_FAILED,
                        "Failed to fetch webhook verification key");
            }
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Plaid: webhook key fetch failed for kid={}: {}", keyId, e.getMessage(), e);
            }
            throw new AppException(
                    ErrorCode.PLAID_CONNECTION_FAILED,
                    "Failed to fetch webhook verification key",
                    null,
                    null,
                    e);
        }
    }

    /**
     * Parse Plaid error response JSON Plaid returns errors in format: { "display_message": null,
     * "error_code": "TRANSACTIONS_LIMIT", "error_message": "rate limit exceeded...", "error_type":
     * "RATE_LIMIT_EXCEEDED", "request_id": "...", "suggested_action": null }
     */
    private PlaidErrorResponse parsePlaidErrorResponse(final String errorBody) {
        if (errorBody == null || errorBody.isEmpty() || !errorBody.trim().startsWith("{")) {
            return null;
        }

        try {
            // Simple JSON parsing using string manipulation (no external dependencies needed)
            // For more complex parsing, consider using Jackson ObjectMapper
            final PlaidErrorResponse error = new PlaidErrorResponse();

            // Extract error_code
            final int errorCodeIndex = errorBody.indexOf("\"error_code\"");
            if (errorCodeIndex >= 0) {
                final int startIndex = errorBody.indexOf("\"", errorCodeIndex + 12) + 1;
                final int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.errorCode = errorBody.substring(startIndex, endIndex);
                }
            }

            // Extract error_message
            final int errorMessageIndex = errorBody.indexOf("\"error_message\"");
            if (errorMessageIndex >= 0) {
                final int startIndex = errorBody.indexOf("\"", errorMessageIndex + 16) + 1;
                final int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.errorMessage = errorBody.substring(startIndex, endIndex);
                }
            }

            // Extract error_type
            final int errorTypeIndex = errorBody.indexOf("\"error_type\"");
            if (errorTypeIndex >= 0) {
                final int startIndex = errorBody.indexOf("\"", errorTypeIndex + 13) + 1;
                final int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.errorType = errorBody.substring(startIndex, endIndex);
                }
            }

            // Extract request_id
            final int requestIdIndex = errorBody.indexOf("\"request_id\"");
            if (requestIdIndex >= 0) {
                final int startIndex = errorBody.indexOf("\"", requestIdIndex + 13) + 1;
                final int endIndex = errorBody.indexOf("\"", startIndex);
                if (startIndex > 0 && endIndex > startIndex) {
                    error.requestId = errorBody.substring(startIndex, endIndex);
                }
            }

            return error;
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Failed to parse Plaid error response: {}", e.getMessage());
            }
            return null;
        }
    }

    /** Plaid error response structure */
    private static final class PlaidErrorResponse {
        String errorCode;
        String errorMessage;
        String errorType;
        String requestId;
    }
}
