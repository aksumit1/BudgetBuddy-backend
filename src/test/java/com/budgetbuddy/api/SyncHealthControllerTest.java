package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SyncHealthService;
import com.budgetbuddy.service.SyncHealthService.SyncHealthResponse;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for SyncHealthController
 * Tests sync health status tracking, error handling, and race condition protection
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SyncHealthControllerTest {

    @Mock
    private SyncHealthService syncHealthService;

    @Mock
    private UserService userService;

    @InjectMocks
    private SyncHealthController controller;

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
    void testGetSyncHealth_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("success");
        response.setLastSyncDate(Instant.now());
        response.setConsecutiveFailures(0);
        response.setConnectionHealth("healthy");
        response.setIsStale(false);
        response.setTimeAgo("just now");
        response.setMessage("Up to date");
        
        when(syncHealthService.getSyncHealth(eq("user-123"), any())).thenReturn(response);

        // When
        ResponseEntity<SyncHealthResponse> result = controller.getSyncHealth(userDetails);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals("success", result.getBody().getStatus());
        assertEquals("healthy", result.getBody().getConnectionHealth());
        assertFalse(result.getBody().getIsStale());
    }

    @Test
    void testGetSyncHealth_Unauthorized() {
        // Given
        UserDetails nullUserDetails = null;

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.getSyncHealth(nullUserDetails);
        });

        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetSyncHealth_UserNotFound() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.empty());

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.getSyncHealth(userDetails);
        });

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testUpdateSyncStatus_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        SyncHealthController.SyncStatusUpdateRequest request = 
                new SyncHealthController.SyncStatusUpdateRequest();
        request.setStatus("syncing");
        request.setLastSyncDate(Instant.now());
        request.setConsecutiveFailures(0);
        request.setConnectionHealth("healthy");
        
        SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("syncing");
        response.setConnectionHealth("healthy");
        
        when(syncHealthService.getSyncHealth(eq("user-123"), any())).thenReturn(response);

        // When
        ResponseEntity<SyncHealthResponse> result = controller.updateSyncStatus(userDetails, request);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        verify(syncHealthService, times(1)).getSyncHealth(anyString(), any());
    }

    @Test
    void testUpdateSyncStatus_InvalidRequest() {
        // Given
        SyncHealthController.SyncStatusUpdateRequest nullRequest = null;

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.updateSyncStatus(userDetails, nullRequest);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testUpdateSyncStatus_EmptyStatus() {
        // Given
        SyncHealthController.SyncStatusUpdateRequest request = 
                new SyncHealthController.SyncStatusUpdateRequest();
        request.setStatus(""); // Empty status

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            controller.updateSyncStatus(userDetails, request);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testClearSyncErrors_Success() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("idle");
        response.setConsecutiveFailures(0);
        response.setConnectionHealth("healthy");
        
        when(syncHealthService.getSyncHealth(eq("user-123"), any())).thenReturn(response);

        // When
        ResponseEntity<SyncHealthResponse> result = controller.clearSyncErrors(userDetails);

        // Then
        assertNotNull(result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(0, result.getBody().getConsecutiveFailures());
    }

    @Test
    void testConcurrentUpdates_RaceConditionProtection() throws InterruptedException {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(user));
        
        SyncHealthController.SyncStatusUpdateRequest request = 
                new SyncHealthController.SyncStatusUpdateRequest();
        request.setStatus("syncing");
        
        SyncHealthResponse response = new SyncHealthResponse();
        response.setStatus("syncing");
        
        when(syncHealthService.getSyncHealth(eq("user-123"), any())).thenReturn(response);

        // When - Simulate concurrent updates
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        final boolean[] success = new boolean[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    ResponseEntity<SyncHealthResponse> result = 
                            controller.updateSyncStatus(userDetails, request);
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

