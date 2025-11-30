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
        when(userRepository.findAllByEmail(testEmail)).thenReturn(java.util.Collections.emptyList());

        // When
        UserTable result = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                testClientSalt,
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
        // Given - Simulate duplicate email detected after save
        PasswordHashingService.PasswordHashResult serverHash =
                new PasswordHashingService.PasswordHashResult("server-hash", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), isNull()))
                .thenReturn(serverHash);
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        // Simulate finding 2 users with same email (race condition detected)
        UserTable existingUser = new UserTable();
        existingUser.setUserId(UUID.randomUUID().toString());
        existingUser.setEmail(testEmail);
        UserTable newUser = new UserTable();
        newUser.setUserId(UUID.randomUUID().toString());
        newUser.setEmail(testEmail);
        when(userRepository.findAllByEmail(testEmail)).thenReturn(java.util.Arrays.asList(existingUser, newUser));
        doNothing().when(userRepository).delete(anyString());

        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> userService.createUserSecure(
                        testEmail,
                        testPasswordHash,
                        testClientSalt,
                        "Test",
                        "User"
                ));
        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getErrorCode());
    }

    @Test
    void testCreateUserSecure_WithNullEmail_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> userService.createUserSecure(
                        null,
                        testPasswordHash,
                        testClientSalt,
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

        // When
        userService.changePasswordSecure("user-123", "new-password-hash", "new-client-salt");

        // Then
        verify(userRepository).save(any(UserTable.class));
    }

    @Test
    void testChangePasswordSecure_WithInvalidUserId_ThrowsException() {
        // Given
        when(userRepository.findById("invalid-user")).thenReturn(Optional.empty());

        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> userService.changePasswordSecure("invalid-user", "hash", "salt"));
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

