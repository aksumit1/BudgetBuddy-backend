package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SyncHealthService;
import com.budgetbuddy.service.SyncHealthService.SyncHealthResponse;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
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
 * Tests for SyncHealthController Tests sync health status tracking, error handling, and race
 * condition protection
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
class SyncHealthControllerTest {

    private static final String USER_123 = "user-123";
    private static final String HEALTHY = "healthy";

    @Mock private SyncHealthService syncHealthService;

    @Mock private UserService userService;

    @InjectMocks private SyncHealthController controller;

    private UserDetails userDetails;
    private UserTable user;

    @BeforeEach
    void setUp() {
        userDetails = mock(UserDetails.class);
        lenient().when(userDetails.getUsername()).thenReturn("test@example.com");

        user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");
    }

    @Test
    void testGetSyncHealthSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("success");
        response.setLastSyncDate(Instant.now());
        response.setConsecutiveFailures(0);
        response.setConnectionHealth(HEALTHY);
        response.setIsStale(false);
        response.setTimeAgo("just now");
        response.setMessage("Up to date");

        when(syncHealthService.getSyncHealth(eq(USER_123), any())).thenReturn(response);

        // When
        final ResponseEntity<SyncHealthResponse> result = controller.getSyncHealth(userDetails);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals("success", result.getBody().getStatus());
        assertEquals(HEALTHY, result.getBody().getConnectionHealth());
        assertFalse(result.getBody().getIsStale());
    }

    @Test
    void testGetSyncHealthUnauthorized() {
        // Given
        final UserDetails nullUserDetails = null;

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.getSyncHealth(nullUserDetails);
                        });

        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetSyncHealthUserNotFound() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.empty());

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.getSyncHealth(userDetails);
                        });

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testUpdateSyncStatusSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final SyncHealthController.SyncStatusUpdateRequest request =
                new SyncHealthController.SyncStatusUpdateRequest();
        request.setStatus("syncing");
        request.setLastSyncDate(Instant.now());
        request.setConsecutiveFailures(0);
        request.setConnectionHealth(HEALTHY);

        final SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("syncing");
        response.setConnectionHealth(HEALTHY);

        when(syncHealthService.getSyncHealth(eq(USER_123), any())).thenReturn(response);

        // When
        final ResponseEntity<SyncHealthResponse> result =
                controller.updateSyncStatus(userDetails, request);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(syncHealthService, times(1)).getSyncHealth(anyString(), any());
    }

    @Test
    void testUpdateSyncStatusInvalidRequest() {
        // Given
        final SyncHealthController.SyncStatusUpdateRequest nullRequest = null;

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.updateSyncStatus(userDetails, nullRequest);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testUpdateSyncStatusEmptyStatus() {
        // Given
        final SyncHealthController.SyncStatusUpdateRequest request =
                new SyncHealthController.SyncStatusUpdateRequest();
        request.setStatus(""); // Empty status

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            controller.updateSyncStatus(userDetails, request);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testClearSyncErrorsSuccess() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("idle");
        response.setConsecutiveFailures(0);
        response.setConnectionHealth(HEALTHY);

        when(syncHealthService.getSyncHealth(eq(USER_123), any())).thenReturn(response);

        // When
        final ResponseEntity<SyncHealthResponse> result = controller.clearSyncErrors(userDetails);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(0, result.getBody().getConsecutiveFailures());
    }

    @Test
    void testConcurrentUpdatesRaceConditionProtection() throws InterruptedException {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));

        final SyncHealthController.SyncStatusUpdateRequest request =
                new SyncHealthController.SyncStatusUpdateRequest();
        request.setStatus("syncing");

        final SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("syncing");

        when(syncHealthService.getSyncHealth(eq(USER_123), any())).thenReturn(response);

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
                                    final ResponseEntity<SyncHealthResponse> result =
                                            controller.updateSyncStatus(userDetails, request);
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
