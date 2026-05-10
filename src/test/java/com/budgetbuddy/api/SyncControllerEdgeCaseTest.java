package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.when;

import com.budgetbuddy.dto.IncrementalSyncResponse;
import com.budgetbuddy.dto.SyncStatusResponse;
import com.budgetbuddy.service.SyncService;
import com.budgetbuddy.service.UserService;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

/** SyncController Edge Case Tests Tests edge cases, error handling, and boundary conditions */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@DisplayName("SyncController Edge Case Tests")
class SyncControllerEdgeCaseTest {

    private static final String USER_123 = "user-123";

    @Mock private SyncService syncService;

    @Mock private UserService userService;

    private SyncController controller;

    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        controller = new SyncController(syncService, userService);
        userDetails =
                new User(
                        "test@example.com",
                        "password",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    @DisplayName("Should handle null user details")
    void testNullUserDetails() {
        assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> {
                    controller.getAllData(null);
                });
    }

    @Test
    @DisplayName("Should handle empty username")
    void testEmptyUsername() {
        // Spring Security User constructor doesn't allow empty username
        // So we test with a UserDetails that has empty username via custom implementation
        final UserDetails emptyUser =
                new org.springframework.security.core.userdetails.UserDetails() {
                    @Override
                    public java.util.Collection<
                                    ? extends org.springframework.security.core.GrantedAuthority>
                            getAuthorities() {
                        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
                    }

                    @Override
                    public String getPassword() {
                        return "password";
                    }

                    @Override
                    public String getUsername() {
                        return ""; // Empty username
                    }

                    @Override
                    public boolean isAccountNonExpired() {
                        return true;
                    }

                    @Override
                    public boolean isAccountNonLocked() {
                        return true;
                    }

                    @Override
                    public boolean isCredentialsNonExpired() {
                        return true;
                    }

                    @Override
                    public boolean isEnabled() {
                        return true;
                    }
                };

        assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> {
                    controller.getAllData(emptyUser);
                });
    }

    @Test
    @DisplayName("Should handle user not found")
    void testUserNotFound() {
        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.empty());

        assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> {
                    controller.getAllData(userDetails);
                });
    }

    @Test
    @DisplayName("Should handle sync service exception")
    void testSyncServiceException() {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                new com.budgetbuddy.model.dynamodb.UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.of(user));
        when(syncService.getAllData(anyString())).thenThrow(new RuntimeException("Database error"));

        assertThrows(
                RuntimeException.class,
                () -> {
                    controller.getAllData(userDetails);
                });
    }

    @Test
    @DisplayName("Should handle incremental sync with null timestamp")
    void testIncrementalSyncNullTimestamp() {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                new com.budgetbuddy.model.dynamodb.UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final IncrementalSyncResponse response =
                new IncrementalSyncResponse(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        System.currentTimeMillis(),
                        false);

        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.of(user));
        when(syncService.getIncrementalChanges(anyString(), isNull())).thenReturn(response);

        final ResponseEntity<IncrementalSyncResponse> result =
                controller.getIncrementalChanges(userDetails, null);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
    }

    @Test
    @DisplayName("Should handle sync status with empty data")
    void testSyncStatusEmptyData() {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                new com.budgetbuddy.model.dynamodb.UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final SyncStatusResponse response =
                new SyncStatusResponse(
                        true,
                        null,
                        0,
                        SyncStatusResponse.SyncStatus.IDLE,
                        new SyncStatusResponse.DataCounts(0, 0, 0, 0),
                        System.currentTimeMillis());

        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.of(user));
        when(syncService.getSyncStatus(anyString())).thenReturn(response);

        final ResponseEntity<SyncStatusResponse> result = controller.getSyncStatus(userDetails);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(0, result.getBody().getDataCounts().getTransactions());
    }

    @Test
    @DisplayName("Should handle sync status with large data counts")
    void testSyncStatusLargeDataCounts() {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                new com.budgetbuddy.model.dynamodb.UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final SyncStatusResponse response =
                new SyncStatusResponse(
                        true,
                        System.currentTimeMillis(),
                        10_000,
                        SyncStatusResponse.SyncStatus.SYNCING,
                        new SyncStatusResponse.DataCounts(100, 100_000, 50, 200),
                        System.currentTimeMillis());

        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.of(user));
        when(syncService.getSyncStatus(anyString())).thenReturn(response);

        final ResponseEntity<SyncStatusResponse> result = controller.getSyncStatus(userDetails);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(100_000, result.getBody().getDataCounts().getTransactions());
        assertEquals(SyncStatusResponse.SyncStatus.SYNCING, result.getBody().getSyncStatus());
    }

    @Test
    @DisplayName("Should handle negative timestamp in incremental sync")
    void testIncrementalSyncNegativeTimestamp() {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                new com.budgetbuddy.model.dynamodb.UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final IncrementalSyncResponse response =
                new IncrementalSyncResponse(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        System.currentTimeMillis(),
                        false);

        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.of(user));
        when(syncService.getIncrementalChanges(anyString(), eq(-1L))).thenReturn(response);

        final ResponseEntity<IncrementalSyncResponse> result =
                controller.getIncrementalChanges(userDetails, -1L);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
    }

    @Test
    @DisplayName("Should handle future timestamp in incremental sync")
    void testIncrementalSyncFutureTimestamp() {
        final com.budgetbuddy.model.dynamodb.UserTable user =
                new com.budgetbuddy.model.dynamodb.UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final long futureTimestamp = System.currentTimeMillis() / 1000 + 86_400; // 1 day in future
        final IncrementalSyncResponse response =
                new IncrementalSyncResponse(
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        System.currentTimeMillis(),
                        false);

        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.of(user));
        when(syncService.getIncrementalChanges(anyString(), eq(futureTimestamp)))
                .thenReturn(response);

        final ResponseEntity<IncrementalSyncResponse> result =
                controller.getIncrementalChanges(userDetails, futureTimestamp);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
    }
}
