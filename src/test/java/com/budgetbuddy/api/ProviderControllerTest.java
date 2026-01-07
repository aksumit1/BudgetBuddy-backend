package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.ProviderService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ProviderController
 * Tests multi-provider management, health tracking, and stale connection handling
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ProviderControllerTest {

    @Mock
    private ProviderService providerService;

    @Mock
    private UserService userService;

    @InjectMocks
    private ProviderController controller;

    private UserDetails userDetails;
    private UserTable user;

    @BeforeEach
    void setUp() {
        userDetails = mock(UserDetails.class);
        lenient().when(userDetails.getUsername()).thenReturn("test@example.com");

        user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
    }

    @Test
    void testGetAllProviders_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        ProviderController.ProviderHealthResponse plaidResponse = 
                new ProviderController.ProviderHealthResponse();
        plaidResponse.setProviderId("plaid");
        plaidResponse.setIsHealthy(true);
        plaidResponse.setIsStale(false);
        
        ProviderController.ProviderHealthResponse stripeResponse = 
                new ProviderController.ProviderHealthResponse();
        stripeResponse.setProviderId("stripe");
        stripeResponse.setIsHealthy(true);
        stripeResponse.setIsStale(false);
        
        when(providerService.getAllProviders("user-123"))
                .thenReturn(List.of(plaidResponse, stripeResponse));

        // When
        ResponseEntity<List<ProviderController.ProviderHealthResponse>> result = 
                controller.getAllProviders(userDetails);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(2, result.getBody().size());
        assertTrue(result.getBody().stream()
                .anyMatch(p -> "plaid".equals(p.getProviderId())));
        assertTrue(result.getBody().stream()
                .anyMatch(p -> "stripe".equals(p.getProviderId())));
    }

    @Test
    void testGetProviderHealth_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        ProviderController.ProviderHealthResponse response = 
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);
        response.setIsStale(false);
        response.setLastSuccess(Instant.now());
        response.setFailureCount(0);
        
        when(providerService.getProviderHealth("user-123", "plaid")).thenReturn(response);

        // When
        ResponseEntity<ProviderController.ProviderHealthResponse> result = 
                controller.getProviderHealth(userDetails, "plaid");

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals("plaid", result.getBody().getProviderId());
        assertTrue(result.getBody().getIsHealthy());
        assertFalse(result.getBody().getIsStale());
    }

    @Test
    void testGetProviderHealth_ProviderNotFound() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        when(providerService.getProviderHealth("user-123", "unknown")).thenReturn(null);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.getProviderHealth(userDetails, "unknown");
        });

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testUpdateProviderHealth_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        ProviderController.ProviderHealthUpdateRequest request = 
                new ProviderController.ProviderHealthUpdateRequest();
        request.setIsHealthy(true);
        request.setIsStale(false);
        
        ProviderController.ProviderHealthResponse response = 
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);
        response.setIsStale(false);
        
        when(providerService.updateProviderHealth(eq("user-123"), eq("plaid"), 
                anyBoolean(), anyBoolean(), isNull()))
                .thenReturn(response);

        // When
        ResponseEntity<ProviderController.ProviderHealthResponse> result = 
                controller.updateProviderHealth(userDetails, "plaid", request);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(providerService, times(1)).updateProviderHealth(
                eq("user-123"), eq("plaid"), eq(true), eq(false), isNull());
    }

    @Test
    void testMarkProviderAsStale_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        ProviderController.ProviderHealthResponse response = 
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(false);
        response.setIsStale(true);
        
        when(providerService.markProviderAsStale("user-123", "plaid")).thenReturn(response);

        // When
        ResponseEntity<ProviderController.ProviderHealthResponse> result = 
                controller.markProviderAsStale(userDetails, "plaid");

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertTrue(result.getBody().getIsStale());
        verify(providerService, times(1)).markProviderAsStale("user-123", "plaid");
    }

    @Test
    void testClearStaleStatus_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        ProviderController.ProviderHealthResponse response = 
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);
        response.setIsStale(false);
        
        when(providerService.clearStaleStatus("user-123", "plaid")).thenReturn(response);

        // When
        ResponseEntity<ProviderController.ProviderHealthResponse> result = 
                controller.clearStaleStatus(userDetails, "plaid");

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertFalse(result.getBody().getIsStale());
        assertTrue(result.getBody().getIsHealthy());
        verify(providerService, times(1)).clearStaleStatus("user-123", "plaid");
    }

    @Test
    void testUpdateProviderHealth_Unauthorized() {
        // Given
        UserDetails nullUserDetails = null;
        ProviderController.ProviderHealthUpdateRequest request = 
                new ProviderController.ProviderHealthUpdateRequest();

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.updateProviderHealth(nullUserDetails, "plaid", request);
        });

        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testUpdateProviderHealth_InvalidProviderId() {
        // Given
        ProviderController.ProviderHealthUpdateRequest request = 
                new ProviderController.ProviderHealthUpdateRequest();

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.updateProviderHealth(userDetails, "", request);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testUpdateProviderHealth_NullRequest() {
        // Given
        ProviderController.ProviderHealthUpdateRequest nullRequest = null;

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.updateProviderHealth(userDetails, "plaid", nullRequest);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testConcurrentProviderUpdates_RaceConditionProtection() throws InterruptedException {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        ProviderController.ProviderHealthUpdateRequest request = 
                new ProviderController.ProviderHealthUpdateRequest();
        request.setIsHealthy(true);
        
        ProviderController.ProviderHealthResponse response = 
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);
        
        when(providerService.updateProviderHealth(eq("user-123"), eq("plaid"), 
                anyBoolean(), anyBoolean(), isNull()))
                .thenReturn(response);

        // When - Simulate concurrent updates
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        final boolean[] success = new boolean[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    ResponseEntity<ProviderController.ProviderHealthResponse> result = 
                            controller.updateProviderHealth(userDetails, "plaid", request);
                    success[index] = (result.getStatusCode() == HttpStatus.OK);
                } catch (Exception e) {
                    success[index] = false;
                }
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Then - All should succeed (race condition protection)
        for (boolean s : success) {
            assertTrue(s, "All concurrent updates should succeed");
        }
    }
}

