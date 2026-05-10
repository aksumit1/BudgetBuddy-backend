package com.budgetbuddy.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.ProviderService;
import com.budgetbuddy.service.UserService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Tests for ProviderController Tests multi-provider management, health tracking, and stale
 * connection handling
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ProviderControllerTest {

    @Mock private ProviderService providerService;

    @Mock private UserService userService;

    @InjectMocks private ProviderController controller;

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
    void testGetAllProvidersSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final ProviderController.ProviderHealthResponse plaidResponse =
                new ProviderController.ProviderHealthResponse();
        plaidResponse.setProviderId("plaid");
        plaidResponse.setIsHealthy(true);
        plaidResponse.setIsStale(false);

        final ProviderController.ProviderHealthResponse stripeResponse =
                new ProviderController.ProviderHealthResponse();
        stripeResponse.setProviderId("stripe");
        stripeResponse.setIsHealthy(true);
        stripeResponse.setIsStale(false);

        when(providerService.getAllProviders("user-123"))
                .thenReturn(List.of(plaidResponse, stripeResponse));

        // When
        final ResponseEntity<List<ProviderController.ProviderHealthResponse>> result =
                controller.getAllProviders(userDetails);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(2, result.getBody().size());
        assertTrue(result.getBody().stream().anyMatch(p -> "plaid".equals(p.getProviderId())));
        assertTrue(result.getBody().stream().anyMatch(p -> "stripe".equals(p.getProviderId())));
    }

    @Test
    void testGetProviderHealthSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final ProviderController.ProviderHealthResponse response =
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);
        response.setIsStale(false);
        response.setLastSuccess(Instant.now());
        response.setFailureCount(0);

        when(providerService.getProviderHealth("user-123", "plaid")).thenReturn(response);

        // When
        final ResponseEntity<ProviderController.ProviderHealthResponse> result =
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
    void testGetProviderHealthProviderNotFound() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        when(providerService.getProviderHealth("user-123", "unknown")).thenReturn(null);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.getProviderHealth(userDetails, "unknown");
                        });

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testUpdateProviderHealthSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final ProviderController.ProviderHealthUpdateRequest request =
                new ProviderController.ProviderHealthUpdateRequest();
        request.setIsHealthy(true);
        request.setIsStale(false);

        final ProviderController.ProviderHealthResponse response =
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);
        response.setIsStale(false);

        when(providerService.updateProviderHealth(
                        eq("user-123"), eq("plaid"), anyBoolean(), anyBoolean(), isNull()))
                .thenReturn(response);

        // When
        final ResponseEntity<ProviderController.ProviderHealthResponse> result =
                controller.updateProviderHealth(userDetails, "plaid", request);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(providerService, times(1))
                .updateProviderHealth(eq("user-123"), eq("plaid"), eq(true), eq(false), isNull());
    }

    @Test
    void testMarkProviderAsStaleSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final ProviderController.ProviderHealthResponse response =
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(false);
        response.setIsStale(true);

        when(providerService.markProviderAsStale("user-123", "plaid")).thenReturn(response);

        // When
        final ResponseEntity<ProviderController.ProviderHealthResponse> result =
                controller.markProviderAsStale(userDetails, "plaid");

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertTrue(result.getBody().getIsStale());
        verify(providerService, times(1)).markProviderAsStale("user-123", "plaid");
    }

    @Test
    void testClearStaleStatusSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final ProviderController.ProviderHealthResponse response =
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);
        response.setIsStale(false);

        when(providerService.clearStaleStatus("user-123", "plaid")).thenReturn(response);

        // When
        final ResponseEntity<ProviderController.ProviderHealthResponse> result =
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
    void testUpdateProviderHealthUnauthorized() {
        // Given
        final UserDetails nullUserDetails = null;
        final ProviderController.ProviderHealthUpdateRequest request =
                new ProviderController.ProviderHealthUpdateRequest();

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.updateProviderHealth(nullUserDetails, "plaid", request);
                        });

        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testUpdateProviderHealthInvalidProviderId() {
        // Given
        final ProviderController.ProviderHealthUpdateRequest request =
                new ProviderController.ProviderHealthUpdateRequest();

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.updateProviderHealth(userDetails, "", request);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testUpdateProviderHealthNullRequest() {
        // Given
        final ProviderController.ProviderHealthUpdateRequest nullRequest = null;

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.updateProviderHealth(userDetails, "plaid", nullRequest);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testConcurrentProviderUpdatesRaceConditionProtection() throws InterruptedException {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final ProviderController.ProviderHealthUpdateRequest request =
                new ProviderController.ProviderHealthUpdateRequest();
        request.setIsHealthy(true);

        final ProviderController.ProviderHealthResponse response =
                new ProviderController.ProviderHealthResponse();
        response.setProviderId("plaid");
        response.setIsHealthy(true);

        when(providerService.updateProviderHealth(
                        eq("user-123"), eq("plaid"), anyBoolean(), anyBoolean(), isNull()))
                .thenReturn(response);

        // When - Simulate concurrent updates
        final int threadCount = 10;
        final Thread[] threads = new Thread[threadCount];
        final boolean[] success = new boolean[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    ResponseEntity<ProviderController.ProviderHealthResponse>
                                            result =
                                                    controller.updateProviderHealth(
                                                            userDetails, "plaid", request);
                                    success[index] = result.getStatusCode() == HttpStatus.OK;
                                } catch (Exception e) {
                                    success[index] = false;
                                }
                            });
        }

        // Start all threads
        for (final Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads
        for (final Thread thread : threads) {
            thread.join();
        }

        // Then - All should succeed (race condition protection)
        for (final boolean s : success) {
            assertTrue(s, "All concurrent updates should succeed");
        }
    }
}
