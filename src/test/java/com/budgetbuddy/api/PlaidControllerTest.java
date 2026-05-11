package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidSyncService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateResponse;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for PlaidController */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidControllerTest {

    @Mock private PlaidService plaidService;

    @Mock private PlaidSyncService plaidSyncService;

    @Mock private UserService userService;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionService transactionService;

    @Mock
    @org.springframework.beans.factory.annotation.Qualifier("taskExecutor")
    private Executor taskExecutor;

    @Mock private UserDetails userDetails;

    // CRITICAL: Manually create PlaidController to avoid Mockito mocking issues
    // This ensures all dependencies are properly mocked
    private PlaidController plaidController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        // CRITICAL: Manually create PlaidController to avoid Mockito mocking issues
        // This ensures all dependencies are properly mocked
        plaidController =
                new PlaidController(
                        plaidService,
                        plaidSyncService,
                        userService,
                        accountRepository,
                        transactionService,
                        taskExecutor,
                        // PlaidAccessTokenRepository is exercised only in the new exchange path
                        // we added in this commit; the existing tests don't cover it, so we
                        // pass a Mockito mock to satisfy the constructor without behaviour.
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.repository.dynamodb.PlaidAccessTokenRepository
                                        .class));

        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // CRITICAL FIX: Mock taskExecutor to execute tasks synchronously in tests
        // This ensures async operations complete before assertions
        // Use doAnswer for void methods
        doAnswer(
                        invocation -> {
                            final Runnable runnable = invocation.getArgument(0);
                            runnable.run(); // Execute synchronously in tests
                            return null;
                        })
                .when(taskExecutor)
                .execute(any(Runnable.class));
    }

    @Test
    void testCreateLinkTokenWithValidUserReturnsToken() {
        // Given
        final LinkTokenCreateResponse mockResponse = new LinkTokenCreateResponse();
        mockResponse.setLinkToken("link-token-123");
        when(plaidService.createLinkToken(anyString(), anyString())).thenReturn(mockResponse);

        // When
        final ResponseEntity<PlaidController.LinkTokenResponse> response =
                plaidController.createLinkToken(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(plaidService, times(1)).createLinkToken(anyString(), anyString());
    }

    @Test
    void testCreateLinkTokenWithNullUserDetailsThrowsException() {
        // When/Then
        assertThrows(
                Exception.class,
                () -> {
                    plaidController.createLinkToken(null);
                });
    }

    @Test
    void testExchangePublicTokenWithValidTokenReturnsSuccess() {
        // Given
        final String publicToken = "public-token-123";
        final PlaidController.ExchangeTokenRequest request =
                new PlaidController.ExchangeTokenRequest();
        request.setPublicToken(publicToken);
        final ItemPublicTokenExchangeResponse mockResponse = new ItemPublicTokenExchangeResponse();
        mockResponse.setAccessToken("access-token-123");
        mockResponse.setItemId("item-id-123");
        when(plaidService.exchangePublicToken(anyString())).thenReturn(mockResponse);

        // Mock PlaidSyncService so the async runner doesn't blow up.
        doNothing().when(plaidSyncService).syncAccounts(any(), anyString(), anyString());
        doNothing().when(plaidSyncService).syncTransactions(any(), anyString());

        // When
        final ResponseEntity<?> response =
                plaidController.exchangePublicToken(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(plaidService, times(1)).exchangePublicToken(anyString());
    }

    /**
     * Regression guard for commit 834e3bc: when exchange-token went from synchronous to
     * fire-and-forget async, the existing test only asserted the 200 response and explicitly
     * skipped verifying that the side-effect sync actually ran. As a result, ska@yahoo.com saw
     * accounts but no transactions after linking. This test asserts that BOTH syncAccounts AND
     * syncTransactions are invoked, so a future change that drops one (or accidentally swaps the
     * contract) fails immediately.
     *
     * <p>The controller's taskExecutor is mocked to run synchronously in setUp, so by the time
     * exchangePublicToken returns, the async work has run.
     */
    @Test
    void testExchangePublicTokenTriggersBothAccountAndTransactionSync() {
        // Given
        final PlaidController.ExchangeTokenRequest request =
                new PlaidController.ExchangeTokenRequest();
        request.setPublicToken("public-token-xyz");
        final ItemPublicTokenExchangeResponse mockResponse = new ItemPublicTokenExchangeResponse();
        mockResponse.setAccessToken("access-token-xyz");
        mockResponse.setItemId("item-id-xyz");
        when(plaidService.exchangePublicToken(anyString())).thenReturn(mockResponse);
        doNothing().when(plaidSyncService).syncAccounts(any(), anyString(), anyString());
        doNothing().when(plaidSyncService).syncTransactions(any(), anyString());

        // When
        final ResponseEntity<?> response =
                plaidController.exchangePublicToken(userDetails, request);

        // Then - 200 returned AND both sync paths fired
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(plaidSyncService, times(1))
                .syncAccounts(eq(testUser), eq("access-token-xyz"), eq("item-id-xyz"));
        verify(plaidSyncService, times(1)).syncTransactions(eq(testUser), eq("access-token-xyz"));
    }

    /**
     * Accounts must be synced before transactions — transaction sync reads the accounts table to
     * know which Plaid accountIds to fetch transactions for. Reversing the order silently produces
     * the "accounts populate but transactions stay empty" symptom even though the response is 200.
     */
    @Test
    void testExchangePublicTokenSyncsAccountsBeforeTransactions() {
        // Given
        final PlaidController.ExchangeTokenRequest request =
                new PlaidController.ExchangeTokenRequest();
        request.setPublicToken("public-token-order");
        final ItemPublicTokenExchangeResponse mockResponse = new ItemPublicTokenExchangeResponse();
        mockResponse.setAccessToken("access-token-order");
        mockResponse.setItemId("item-id-order");
        when(plaidService.exchangePublicToken(anyString())).thenReturn(mockResponse);
        doNothing().when(plaidSyncService).syncAccounts(any(), anyString(), anyString());
        doNothing().when(plaidSyncService).syncTransactions(any(), anyString());

        // When
        plaidController.exchangePublicToken(userDetails, request);

        // Then - assert ordering via InOrder
        final org.mockito.InOrder inOrder = inOrder(plaidSyncService);
        inOrder.verify(plaidSyncService)
                .syncAccounts(eq(testUser), eq("access-token-order"), eq("item-id-order"));
        inOrder.verify(plaidSyncService).syncTransactions(eq(testUser), eq("access-token-order"));
    }

    /**
     * If the account-sync step throws, the controller must still attempt transaction sync
     * (PlaidController catches each independently) — and the user-facing response stays 200, since
     * the user's iOS app will retry on next launch. This guards the catch-and-continue contract in
     * syncAccountsAndTransactionsAsync.
     */
    @Test
    void testExchangePublicTokenAccountSyncFailureStillAttemptsTransactionSync() {
        // Given
        final PlaidController.ExchangeTokenRequest request =
                new PlaidController.ExchangeTokenRequest();
        request.setPublicToken("public-token-fail");
        final ItemPublicTokenExchangeResponse mockResponse = new ItemPublicTokenExchangeResponse();
        mockResponse.setAccessToken("access-token-fail");
        mockResponse.setItemId("item-id-fail");
        when(plaidService.exchangePublicToken(anyString())).thenReturn(mockResponse);
        doThrow(new RuntimeException("Plaid /accounts/get returned 503"))
                .when(plaidSyncService)
                .syncAccounts(any(), anyString(), anyString());
        doNothing().when(plaidSyncService).syncTransactions(any(), anyString());

        // When
        final ResponseEntity<?> response =
                plaidController.exchangePublicToken(userDetails, request);

        // Then - response still 200, transaction sync still attempted
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(plaidSyncService, times(1)).syncTransactions(eq(testUser), eq("access-token-fail"));
    }
}
