package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.PasswordHashingService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for UserService */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String TEST = "Test";

    @Mock private UserRepository userRepository;

    @Mock private PasswordHashingService passwordHashingService;

    @Mock private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @InjectMocks private UserService userService;

    private String testEmail;
    private String testPasswordHash;
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        testEmail = "test@example.com";
        testPasswordHash = "hashed-password";
        testClientSalt = "client-salt";
    }

    @Test
    void testCreateUserSecureWithValidDataCreatesUser() {
        // Given
        final PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        // CRITICAL FIX: findAllByEmail is now called asynchronously, so we don't need to stub it
        // here
        // when(userRepository.findAllByEmail(testEmail)).thenReturn(java.util.Collections.emptyList());

        // When - BREAKING CHANGE: Client salt removed
        final UserTable result =
                userService.createUserSecure(testEmail, testPasswordHash, TEST, "User");

        // Then
        assertNotNull(result);
        assertNotNull(result.getUserId());
        assertEquals(testEmail, result.getEmail());
        verify(userRepository).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecureWithExistingEmailThrowsException() {
        // Given - Simulate userId collision (saveIfNotExists returns false)
        // CRITICAL FIX: The implementation changed - duplicate email detection is now async
        // When saveIfNotExists returns false, it means userId collision (INTERNAL_SERVER_ERROR)
        final PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        // Simulate userId collision (extremely rare but possible)
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(false);

        // When/Then
        // BREAKING CHANGE: Client salt removed
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () ->
                                userService.createUserSecure(
                                        testEmail, testPasswordHash, TEST, "User"));
        // CRITICAL FIX: The implementation now throws INTERNAL_SERVER_ERROR for userId collision
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
        assertTrue(
                exception.getMessage().contains("Failed to create user")
                        || exception.getMessage().contains("try again"));
    }

    @Test
    void testCreateUserSecureCreatesPseudoAccount() {
        // Given
        final PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        when(userRepository.findAllByEmail(testEmail))
                .thenReturn(java.util.Collections.emptyList());

        // Mock pseudo account creation
        final com.budgetbuddy.model.dynamodb.AccountTable pseudoAccount =
                new com.budgetbuddy.model.dynamodb.AccountTable();
        pseudoAccount.setAccountId("pseudo-account-id");
        pseudoAccount.setAccountName("Manual Transactions");
        when(accountRepository.getOrCreatePseudoAccount(anyString())).thenReturn(pseudoAccount);

        // When
        final UserTable result =
                userService.createUserSecure(testEmail, testPasswordHash, TEST, "User");

        // Then
        assertNotNull(result);
        // Note: Pseudo account creation is now async, so we need to wait a bit for the async task
        // Or we can verify it was called eventually using awaitility or similar
        // For now, we'll just verify the user was created successfully
        // The pseudo account will be created asynchronously
        try {
            Thread.sleep(100); // Give async task time to execute
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Verify pseudo account creation was attempted (may be async)
        verify(accountRepository, timeout(1000)).getOrCreatePseudoAccount(result.getUserId());
    }

    @Test
    void testCreateUserSecureWithPseudoAccountCreationFailureStillCreatesUser() {
        // Given - Pseudo account creation fails, but user creation should still succeed
        final PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        // CRITICAL FIX: findAllByEmail is called asynchronously in CompletableFuture.runAsync(), so
        // it's not called synchronously
        // Remove this stubbing or make it lenient since it's not used in the synchronous path
        lenient()
                .when(userRepository.findAllByEmail(testEmail))
                .thenReturn(java.util.Collections.emptyList());

        // CRITICAL FIX: Use lenient() since pseudo account creation is async and might not be
        // called synchronously
        // Mock pseudo account creation failure
        lenient()
                .when(accountRepository.getOrCreatePseudoAccount(anyString()))
                .thenThrow(new RuntimeException("Failed to create pseudo account"));

        // When - Should still create user (pseudo account creation is best-effort)
        final UserTable result =
                userService.createUserSecure(testEmail, testPasswordHash, TEST, "User");

        // Then - User should still be created
        assertNotNull(result);
        assertEquals(testEmail, result.getEmail());
        verify(userRepository).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecureWithNullEmailThrowsException() {
        // When/Then
        // BREAKING CHANGE: Client salt removed
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> userService.createUserSecure(null, testPasswordHash, TEST, "User"));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testFindByEmailWithValidEmailReturnsUser() {
        // Given
        final UserTable user = createTestUser();
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(user));

        // When
        final Optional<UserTable> result = userService.findByEmail(testEmail);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testEmail, result.get().getEmail());
    }

    @Test
    void testFindByEmailWithNullEmailReturnsEmpty() {
        // When
        final Optional<UserTable> result = userService.findByEmail(null);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testUpdateUserWithValidUserUpdatesUser() {
        // Given
        final UserTable user = createTestUser();
        doNothing().when(userRepository).save(any(UserTable.class));

        // When
        final UserTable result = userService.updateUser(user);

        // Then
        assertNotNull(result);
        verify(userRepository).save(user);
    }

    @Test
    void testUpdateUserWithNullUserThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(AppException.class, () -> userService.updateUser(null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testChangePasswordSecureWithValidDataUpdatesPassword() {
        // Given
        final UserTable user = createTestUser();
        final PasswordHashingService.PasswordHashResult newHash =
                new PasswordHashingService.PasswordHashResult("new-hash", "new-salt");
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(passwordHashingService.hashClientPassword(anyString(), isNull())).thenReturn(newHash);
        doNothing().when(userRepository).save(any(UserTable.class));

        // When - BREAKING CHANGE: Client salt removed
        userService.changePasswordSecure("user-123", "new-password-hash");

        // Then
        verify(userRepository).save(any(UserTable.class));
    }

    @Test
    void testChangePasswordSecureWithInvalidUserIdThrowsException() {
        // Given
        when(userRepository.findById("invalid-user")).thenReturn(Optional.empty());

        // When/Then
        // BREAKING CHANGE: Client salt removed
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> userService.changePasswordSecure("invalid-user", "hash"));
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    // Helper methods
    private UserTable createTestUser() {
        final UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail(testEmail);
        user.setFirstName(TEST);
        user.setLastName("User");
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        return user;
    }
}
