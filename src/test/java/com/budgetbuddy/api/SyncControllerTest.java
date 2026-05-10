package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.dto.IncrementalSyncResponse;
import com.budgetbuddy.dto.SyncAllResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.SyncService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for SyncController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class SyncControllerTest {

    @Mock private SyncService syncService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private SyncController syncController;

    private UserTable testUser;
    private SyncAllResponse testSyncAllResponse;
    private IncrementalSyncResponse testIncrementalResponse;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("test-user-123");
        testUser.setEmail("test@example.com");

        // Create test account
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId("account-1");
        testAccount.setAccountName("Test Account");
        testAccount.setBalance(new BigDecimal("1000.00"));

        // Create test transaction
        final TransactionTable testTransaction = new TransactionTable();
        testTransaction.setTransactionId("transaction-1");
        testTransaction.setAmount(new BigDecimal("50.00"));

        testSyncAllResponse =
                new SyncAllResponse(
                        Arrays.asList(testAccount),
                        Arrays.asList(testTransaction),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Instant.now().getEpochSecond());

        testIncrementalResponse =
                new IncrementalSyncResponse(
                        Arrays.asList(testAccount),
                        Arrays.asList(testTransaction),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Instant.now().getEpochSecond(),
                        false);
    }

    @Test
    void getAllDataSuccess() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(syncService.getAllData(testUser.getUserId())).thenReturn(testSyncAllResponse);

        // When
        final ResponseEntity<SyncAllResponse> response = syncController.getAllData(userDetails);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getAccounts().size());
        assertEquals(1, response.getBody().getTransactions().size());

        verify(userService).findByEmail("test@example.com");
        verify(syncService).getAllData(testUser.getUserId());
    }

    @Test
    void getAllDataUnauthenticated() {
        // Given
        final UserDetails nullUserDetails = null;

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            syncController.getAllData(nullUserDetails);
                        });

        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(syncService, never()).getAllData(anyString());
    }

    @Test
    void getAllDataUserNotFound() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.empty());

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            syncController.getAllData(userDetails);
                        });

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(syncService, never()).getAllData(anyString());
    }

    @Test
    void getIncrementalChangesSuccess() {
        // Given
        final Long sinceTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(syncService.getIncrementalChanges(testUser.getUserId(), sinceTimestamp))
                .thenReturn(testIncrementalResponse);

        // When
        final ResponseEntity<IncrementalSyncResponse> response =
                syncController.getIncrementalChanges(userDetails, sinceTimestamp);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getAccounts().size());
        assertEquals(1, response.getBody().getTransactions().size());

        verify(userService).findByEmail("test@example.com");
        verify(syncService).getIncrementalChanges(testUser.getUserId(), sinceTimestamp);
    }

    @Test
    void getIncrementalChangesNoSinceParameter() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(syncService.getIncrementalChanges(testUser.getUserId(), null))
                .thenReturn(testIncrementalResponse);

        // When
        final ResponseEntity<IncrementalSyncResponse> response =
                syncController.getIncrementalChanges(userDetails, null);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(syncService).getIncrementalChanges(testUser.getUserId(), null);
    }

    @Test
    void getIncrementalChangesUnauthenticated() {
        // Given
        final UserDetails nullUserDetails = null;

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            syncController.getIncrementalChanges(
                                    nullUserDetails, Instant.now().getEpochSecond());
                        });

        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(syncService, never()).getIncrementalChanges(anyString(), anyLong());
    }
}
