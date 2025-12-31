package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionSyncService;
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

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for TransactionSyncController
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionSyncControllerTest {

    @Mock
    private TransactionSyncService transactionSyncService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    // CRITICAL: Manually create TransactionSyncController to avoid Mockito mocking issues
    // Mockito sometimes has issues with classes that have complex dependencies
    private TransactionSyncController controller;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        // CRITICAL: Manually create TransactionSyncController to avoid Mockito mocking issues
        // This ensures all dependencies are properly mocked
        controller = new TransactionSyncController(transactionSyncService, userService);
        
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testSyncTransactions_WithValidRequest_ReturnsAccepted() {
        // Given
        String accessToken = "access-token-123";
        TransactionSyncService.SyncResult syncResult = new TransactionSyncService.SyncResult();
        syncResult.setNewCount(0);
        syncResult.setUpdatedCount(0);
        
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncTransactions("user-123", accessToken))
                .thenReturn(CompletableFuture.completedFuture(syncResult));

        // When
        ResponseEntity<Map<String, Object>> response = controller.syncTransactions(userDetails, accessToken);

        // Then
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("accepted", response.getBody().get("status"));
        verify(transactionSyncService).syncTransactions("user-123", accessToken);
    }

    @Test
    void testSyncTransactions_WithNullUserDetails_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.syncTransactions(null, "token"));
    }

    @Test
    void testSyncTransactions_WithEmptyAccessToken_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.syncTransactions(userDetails, ""));
    }

    @Test
    void testSyncIncremental_WithValidRequest_ReturnsResult() throws Exception {
        // Given
        String accessToken = "access-token-123";
        LocalDate sinceDate = LocalDate.now().minusDays(30);
        
        TransactionSyncService.SyncResult syncResult = new TransactionSyncService.SyncResult();
        syncResult.setNewCount(10);
        syncResult.setUpdatedCount(5);
        
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncIncremental("user-123", accessToken, sinceDate))
                .thenReturn(CompletableFuture.completedFuture(syncResult));

        // When
        ResponseEntity<TransactionSyncService.SyncResult> response = 
                controller.syncIncremental(userDetails, accessToken, sinceDate);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getBody().getNewCount());
        assertEquals(5, response.getBody().getUpdatedCount());
    }

    @Test
    void testSyncIncremental_WithNullSinceDate_UsesDefault() throws Exception {
        // Given
        String accessToken = "access-token-123";
        TransactionSyncService.SyncResult syncResult = new TransactionSyncService.SyncResult();
        syncResult.setNewCount(0);
        syncResult.setUpdatedCount(0);
        
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncIncremental(eq("user-123"), eq(accessToken), any(LocalDate.class)))
                .thenReturn(CompletableFuture.completedFuture(syncResult));

        // When
        ResponseEntity<TransactionSyncService.SyncResult> response = 
                controller.syncIncremental(userDetails, accessToken, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testSyncIncremental_WithExecutionException_ThrowsException() throws Exception {
        // Given
        String accessToken = "access-token-123";
        LocalDate sinceDate = LocalDate.now().minusDays(30);
        
        CompletableFuture<TransactionSyncService.SyncResult> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Sync failed"));
        
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncIncremental("user-123", accessToken, sinceDate))
                .thenReturn(future);

        // When/Then
        assertThrows(AppException.class, () -> 
                controller.syncIncremental(userDetails, accessToken, sinceDate));
    }

    @Test
    void testGetSyncStatus_WithValidUser_ReturnsStatus() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<Map<String, Object>> response = controller.getSyncStatus(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("completed", response.getBody().get("status"));
        assertEquals("user-123", response.getBody().get("userId"));
    }

    @Test
    void testGetSyncStatus_WithNullUserDetails_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.getSyncStatus(null));
    }
}

