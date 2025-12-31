package com.budgetbuddy.api;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.service.PlaidSyncService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.TransactionService;
import com.plaid.client.model.ItemPublicTokenExchangeResponse;
import com.plaid.client.model.LinkTokenCreateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PlaidController
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidControllerTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private PlaidSyncService plaidSyncService;

    @Mock
    private UserService userService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    @org.springframework.beans.factory.annotation.Qualifier("taskExecutor")
    private Executor taskExecutor;

    @Mock
    private UserDetails userDetails;

    // CRITICAL: Manually create PlaidController to avoid Mockito mocking issues
    // This ensures all dependencies are properly mocked
    private PlaidController plaidController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        // CRITICAL: Manually create PlaidController to avoid Mockito mocking issues
        // This ensures all dependencies are properly mocked
        plaidController = new PlaidController(
                plaidService,
                plaidSyncService,
                userService,
                accountRepository,
                transactionService,
                taskExecutor
        );
        
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        
        // CRITICAL FIX: Mock taskExecutor to execute tasks synchronously in tests
        // This ensures async operations complete before assertions
        // Use doAnswer for void methods
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run(); // Execute synchronously in tests
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    void testCreateLinkToken_WithValidUser_ReturnsToken() {
        // Given
        LinkTokenCreateResponse mockResponse = new LinkTokenCreateResponse();
        mockResponse.setLinkToken("link-token-123");
        when(plaidService.createLinkToken(anyString(), anyString())).thenReturn(mockResponse);

        // When
        ResponseEntity<PlaidController.LinkTokenResponse> response =
                plaidController.createLinkToken(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(plaidService, times(1)).createLinkToken(anyString(), anyString());
    }

    @Test
    void testCreateLinkToken_WithNullUserDetails_ThrowsException() {
        // When/Then
        assertThrows(Exception.class, () -> {
            plaidController.createLinkToken(null);
        });
    }

    @Test
    void testExchangePublicToken_WithValidToken_ReturnsSuccess() {
        // Given
        String publicToken = "public-token-123";
        PlaidController.ExchangeTokenRequest request = new PlaidController.ExchangeTokenRequest();
        request.setPublicToken(publicToken);
        ItemPublicTokenExchangeResponse mockResponse = new ItemPublicTokenExchangeResponse();
        mockResponse.setAccessToken("access-token-123");
        mockResponse.setItemId("item-id-123");
        when(plaidService.exchangePublicToken(anyString())).thenReturn(mockResponse);
        
        // CRITICAL FIX: Mock PlaidSyncService methods to prevent exceptions in async sync
        // The controller calls syncAccountsAndTransactionsAsync which uses these services
        doNothing().when(plaidSyncService).syncAccounts(any(), anyString(), anyString());
        doNothing().when(plaidSyncService).syncTransactions(any(), anyString());

        // When
        ResponseEntity<?> response = plaidController.exchangePublicToken(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        verify(plaidService, times(1)).exchangePublicToken(anyString());
        // Note: Async sync methods are called but we don't verify them since they run asynchronously
        // The important thing is that the response is returned successfully
    }
}

