package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionSyncService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for TransactionSyncController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionSyncControllerTest {

    private static final String USER_123 = "user-123";

    @Mock private TransactionSyncService transactionSyncService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

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
        testUser.setUserId(USER_123);
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testSyncTransactionsWithValidRequestReturnsAccepted() {
        // Given
        final String accessToken = "access-token-123";
        final TransactionSyncService.SyncResult syncResult =
                new TransactionSyncService.SyncResult();
        syncResult.setNewCount(0);
        syncResult.setUpdatedCount(0);

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncTransactions(USER_123, accessToken))
                .thenReturn(CompletableFuture.completedFuture(syncResult));

        // When
        final ResponseEntity<Map<String, Object>> response =
                controller.syncTransactions(userDetails, accessToken);

        // Then
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("accepted", response.getBody().get("status"));
        verify(transactionSyncService).syncTransactions(USER_123, accessToken);
    }

    @Test
    void testSyncTransactionsWithNullUserDetailsThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.syncTransactions(null, "token"));
    }

    @Test
    void testSyncTransactionsWithEmptyAccessTokenThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.syncTransactions(userDetails, ""));
    }

    @Test
    void testSyncIncrementalWithValidRequestReturnsResult() throws Exception {
        // Given
        final String accessToken = "access-token-123";
        final LocalDate sinceDate = LocalDate.now().minusDays(30);

        final TransactionSyncService.SyncResult syncResult =
                new TransactionSyncService.SyncResult();
        syncResult.setNewCount(10);
        syncResult.setUpdatedCount(5);

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncIncremental(USER_123, accessToken, sinceDate))
                .thenReturn(CompletableFuture.completedFuture(syncResult));

        // When
        final ResponseEntity<TransactionSyncService.SyncResult> response =
                controller.syncIncremental(userDetails, accessToken, sinceDate);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(10, response.getBody().getNewCount());
        assertEquals(5, response.getBody().getUpdatedCount());
    }

    @Test
    void testSyncIncrementalWithNullSinceDateUsesDefault() throws Exception {
        // Given
        final String accessToken = "access-token-123";
        final TransactionSyncService.SyncResult syncResult =
                new TransactionSyncService.SyncResult();
        syncResult.setNewCount(0);
        syncResult.setUpdatedCount(0);

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncIncremental(
                        eq(USER_123), eq(accessToken), any(LocalDate.class)))
                .thenReturn(CompletableFuture.completedFuture(syncResult));

        // When
        final ResponseEntity<TransactionSyncService.SyncResult> response =
                controller.syncIncremental(userDetails, accessToken, null);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testSyncIncrementalWithExecutionExceptionThrowsException() throws Exception {
        // Given
        final String accessToken = "access-token-123";
        final LocalDate sinceDate = LocalDate.now().minusDays(30);

        final CompletableFuture<TransactionSyncService.SyncResult> future =
                new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("Sync failed"));

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(transactionSyncService.syncIncremental(USER_123, accessToken, sinceDate))
                .thenReturn(future);

        // When/Then
        assertThrows(
                AppException.class,
                () -> controller.syncIncremental(userDetails, accessToken, sinceDate));
    }

    @Test
    void testGetSyncStatusWithValidUserReturnsStatus() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<Map<String, Object>> response = controller.getSyncStatus(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("completed", response.getBody().get("status"));
        assertEquals(USER_123, response.getBody().get("userId"));
    }

    @Test
    void testGetSyncStatusWithNullUserDetailsThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.getSyncStatus(null));
    }
}
