package com.budgetbuddy.api;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.service.UserService;
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
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private PlaidController plaidController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
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

        // When
        ResponseEntity<?> response = plaidController.exchangePublicToken(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(plaidService, times(1)).exchangePublicToken(anyString());
    }
}

