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
    private final PCIDSSComplianceService pciDSSComplianceService;

    public PlaidService(
            @Value("${app.plaid.client-id}") String clientId,
            @Value("${app.plaid.secret}") String secret,
            @Value("${app.plaid.environment:sandbox}") String environment,
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
        
        Map<String, String> apiKeys = new HashMap<>();
        apiKeys.put("clientId", clientId);
        apiKeys.put("secret", secret);
        
        ApiClient apiClient = new ApiClient(apiKeys);
        apiClient.setPlaidAdapter(
                environment.equals("production") 
                        ? com.plaid.client.PlaidEnvironment.production 
                        : com.plaid.client.PlaidEnvironment.sandbox
        );
        
        this.plaidApi = apiClient.createService(PlaidApi.class);
    }

    /**
     * Create Link Token for Plaid Link
     * Generates a secure link token for Plaid Link initialization
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public LinkTokenCreateResponse createLinkToken(String userId, String clientName) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID cannot be null or empty");
        }
        if (clientName == null || clientName.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Client name cannot be null or empty");
        }

        try {
            LinkTokenCreateRequest request = new LinkTokenCreateRequest()
                    .user(new LinkTokenCreateRequestUser().clientUserId(userId))
                    .clientName(clientName)
                    .products(List.of(Products.TRANSACTIONS, Products.AUTH, Products.IDENTITY))
                    .countryCodes(List.of(CountryCode.US))
                    .language("en")
                    .webhook("https://api.budgetbuddy.com/api/plaid/webhooks")  // Webhook URL
                    .redirectUri("https://app.budgetbuddy.com/plaid/callback");  // Redirect URI

            LinkTokenCreateResponse response = plaidApi.linkTokenCreate(request).execute().body();
            
            if (response == null || response.getLinkToken() == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to create link token");
            }

            logger.info("Plaid: Link token created for user: {}, expires: {}", 
                    userId, response.getExpiration());
            return response;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Plaid: Failed to create link token: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, 
                    "Failed to create Plaid link token", Map.of("userId", userId), null, e);
        }
    }

    /**
     * Exchange Public Token for Access Token
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public ItemPublicTokenExchangeResponse exchangePublicToken(String publicToken) {
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
    public AccountsGetResponse getAccounts(String accessToken) {
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
     */
    @CircuitBreaker(name = "plaid")
    @Retry(name = "plaid")
    public TransactionsGetResponse getTransactions(String accessToken, String startDate, String endDate) {
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
            TransactionsGetRequest request = new TransactionsGetRequest()
                    .accessToken(accessToken)
                    .startDate(startDate)
                    .endDate(endDate);

            TransactionsGetResponse response = plaidApi.transactionsGet(request).execute().body();
            
            if (response == null) {
                throw new AppException(ErrorCode.PLAID_CONNECTION_FAILED, "Failed to get transactions");
            }

            logger.debug("Plaid: Retrieved {} transactions", 
                    response.getTransactions() != null ? response.getTransactions().size() : 0);
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
    public InstitutionsGetResponse getInstitutions(String query, int count) {
        if (query == null || query.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Query cannot be null or empty");
        }
        if (count <= 0 || count > 500) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Count must be between 1 and 500");
        }

        try {
            InstitutionsGetRequest request = new InstitutionsGetRequest()
                    .query(query)
                    .count(count)
                    .countryCodes(List.of(CountryCode.US));

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
    public ItemRemoveResponse removeItem(String accessToken) {
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
