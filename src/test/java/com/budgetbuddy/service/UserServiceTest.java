package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.PasswordHashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for UserService
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHashingService passwordHashingService;

    @Mock
    private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @InjectMocks
    private UserService userService;

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
    void testCreateUserSecure_WithValidData_CreatesUser() {
        // Given
        PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        // CRITICAL FIX: findAllByEmail is now called asynchronously, so we don't need to stub it here
        // when(userRepository.findAllByEmail(testEmail)).thenReturn(java.util.Collections.emptyList());

        // When - BREAKING CHANGE: Client salt removed
        UserTable result = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                "Test",
                "User"
        );

        // Then
        assertNotNull(result);
        assertNotNull(result.getUserId());
        assertEquals(testEmail, result.getEmail());
        verify(userRepository).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecure_WithExistingEmail_ThrowsException() {
        // Given - Simulate userId collision (saveIfNotExists returns false)
        // CRITICAL FIX: The implementation changed - duplicate email detection is now async
        // When saveIfNotExists returns false, it means userId collision (INTERNAL_SERVER_ERROR)
        PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        // Simulate userId collision (extremely rare but possible)
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(false);

        // When/Then
        // BREAKING CHANGE: Client salt removed
        AppException exception = assertThrows(AppException.class,
                () -> userService.createUserSecure(
                        testEmail,
                        testPasswordHash,
                        "Test",
                        "User"
                ));
        // CRITICAL FIX: The implementation now throws INTERNAL_SERVER_ERROR for userId collision
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Failed to create user") || exception.getMessage().contains("try again"));
    }

    @Test
    void testCreateUserSecure_CreatesPseudoAccount() {
        // Given
        PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        when(userRepository.findAllByEmail(testEmail)).thenReturn(java.util.Collections.emptyList());
        
        // Mock pseudo account creation
        com.budgetbuddy.model.dynamodb.AccountTable pseudoAccount = new com.budgetbuddy.model.dynamodb.AccountTable();
        pseudoAccount.setAccountId("pseudo-account-id");
        pseudoAccount.setAccountName("Manual Transactions");
        when(accountRepository.getOrCreatePseudoAccount(anyString())).thenReturn(pseudoAccount);

        // When
        UserTable result = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                "Test",
                "User"
        );

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
    void testCreateUserSecure_WithPseudoAccountCreationFailure_StillCreatesUser() {
        // Given - Pseudo account creation fails, but user creation should still succeed
        PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        // CRITICAL FIX: findAllByEmail is called asynchronously in CompletableFuture.runAsync(), so it's not called synchronously
        // Remove this stubbing or make it lenient since it's not used in the synchronous path
        lenient().when(userRepository.findAllByEmail(testEmail)).thenReturn(java.util.Collections.emptyList());
        
        // CRITICAL FIX: Use lenient() since pseudo account creation is async and might not be called synchronously
        // Mock pseudo account creation failure
        lenient().when(accountRepository.getOrCreatePseudoAccount(anyString()))
                .thenThrow(new RuntimeException("Failed to create pseudo account"));

        // When - Should still create user (pseudo account creation is best-effort)
        UserTable result = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                "Test",
                "User"
        );

        // Then - User should still be created
        assertNotNull(result);
        assertEquals(testEmail, result.getEmail());
        verify(userRepository).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecure_WithNullEmail_ThrowsException() {
        // When/Then
        // BREAKING CHANGE: Client salt removed
        AppException exception = assertThrows(AppException.class,
                () -> userService.createUserSecure(
                        null,
                        testPasswordHash,
                        "Test",
                        "User"
                ));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testFindByEmail_WithValidEmail_ReturnsUser() {
        // Given
        UserTable user = createTestUser();
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(user));

        // When
        Optional<UserTable> result = userService.findByEmail(testEmail);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testEmail, result.get().getEmail());
    }

    @Test
    void testFindByEmail_WithNullEmail_ReturnsEmpty() {
        // When
        Optional<UserTable> result = userService.findByEmail(null);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testUpdateUser_WithValidUser_UpdatesUser() {
        // Given
        UserTable user = createTestUser();
        doNothing().when(userRepository).save(any(UserTable.class));

        // When
        UserTable result = userService.updateUser(user);

        // Then
        assertNotNull(result);
        verify(userRepository).save(user);
    }

    @Test
    void testUpdateUser_WithNullUser_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> userService.updateUser(null));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testChangePasswordSecure_WithValidData_UpdatesPassword() {
        // Given
        UserTable user = createTestUser();
        PasswordHashingService.PasswordHashResult newHash =
                new PasswordHashingService.PasswordHashResult("new-hash", "new-salt");
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(newHash);
        doNothing().when(userRepository).save(any(UserTable.class));

        // When - BREAKING CHANGE: Client salt removed
        userService.changePasswordSecure("user-123", "new-password-hash");

        // Then
        verify(userRepository).save(any(UserTable.class));
    }

    @Test
    void testChangePasswordSecure_WithInvalidUserId_ThrowsException() {
        // Given
        when(userRepository.findById("invalid-user")).thenReturn(Optional.empty());

        // When/Then
        // BREAKING CHANGE: Client salt removed
        AppException exception = assertThrows(AppException.class,
                () -> userService.changePasswordSecure("invalid-user", "hash"));
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    // Helper methods
    private UserTable createTestUser() {
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail(testEmail);
        user.setFirstName("Test");
        user.setLastName("User");
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        return user;
    }
}

