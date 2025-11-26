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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for UserService - Registration Bug Fix
 * 
 * Tests the fix where new user registration was always failing due to:
 * 1. Cache returning stale data in existsByEmail check
 * 2. Email check happening before user creation
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class UserServiceRegistrationTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHashingService passwordHashingService;

    @InjectMocks
    private UserService userService;

    private PasswordHashingService.PasswordHashResult hashResult;

    @BeforeEach
    void setUp() {
        hashResult = new PasswordHashingService.PasswordHashResult("hashed-password", "server-salt");
        when(passwordHashingService.hashClientPassword(anyString(), anyString(), any()))
                .thenReturn(hashResult);
    }

    @Test
    void testCreateUserSecure_NewUser_Succeeds() {
        // Arrange
        String email = "newuser@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);

        // Act
        UserTable result = userService.createUserSecure(
                email,
                "client-hash",
                "client-salt",
                "John",
                "Doe"
        );

        // Assert
        assertNotNull(result);
        assertEquals(email, result.getEmail());
        assertEquals("John", result.getFirstName());
        assertEquals("Doe", result.getLastName());
        assertTrue(result.getEnabled());
        assertFalse(result.getEmailVerified());
        assertTrue(result.getRoles().contains("USER"));
        
        // Verify email was checked first
        verify(userRepository, times(1)).findByEmail(email);
        // Verify user was saved
        verify(userRepository, times(1)).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecure_DuplicateEmail_ThrowsException() {
        // Arrange
        String email = "existing@example.com";
        UserTable existingUser = new UserTable();
        existingUser.setEmail(email);
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            userService.createUserSecure(
                    email,
                    "client-hash",
                    "client-salt",
                    "John",
                    "Doe"
            );
        });

        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("already exists"));
        
        // Verify saveIfNotExists was NOT called (early return)
        verify(userRepository, never()).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecure_EmailCheckHappensBeforeUserCreation() {
        // Arrange
        String email = "test@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);

        // Act
        userService.createUserSecure(email, "hash", "salt", "First", "Last");

        // Assert - Verify order of operations
        var inOrder = inOrder(userRepository);
        inOrder.verify(userRepository).findByEmail(email);
        inOrder.verify(userRepository).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecure_SaveIfNotExistsFails_HandlesGracefully() {
        // Arrange
        String email = "test@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(false);
        // Simulate race condition - user was created between check and save
        UserTable existingUser = new UserTable();
        existingUser.setEmail(email);
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(existingUser));

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            userService.createUserSecure(email, "hash", "salt", "First", "Last");
        });

        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getErrorCode());
        
        // Verify email was re-checked after saveIfNotExists failed
        verify(userRepository, atLeast(2)).findByEmail(email);
    }

    @Test
    void testCreateUserSecure_InvalidInput_ThrowsException() {
        // Act & Assert - Null email
        assertThrows(AppException.class, () -> {
            userService.createUserSecure(null, "hash", "salt", "First", "Last");
        });

        // Act & Assert - Empty email
        assertThrows(AppException.class, () -> {
            userService.createUserSecure("", "hash", "salt", "First", "Last");
        });

        // Act & Assert - Null password hash
        assertThrows(AppException.class, () -> {
            userService.createUserSecure("test@example.com", null, "salt", "First", "Last");
        });

        // Act & Assert - Null salt
        assertThrows(AppException.class, () -> {
            userService.createUserSecure("test@example.com", "hash", null, "First", "Last");
        });
    }

    @Test
    void testCreateUserSecure_UserHasCorrectDefaultValues() {
        // Arrange
        String email = "newuser@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);

        // Act
        UserTable result = userService.createUserSecure(
                email,
                "client-hash",
                "client-salt",
                null, // No first name
                null  // No last name
        );

        // Assert
        assertNotNull(result.getUserId());
        assertFalse(result.getUserId().isEmpty());
        assertEquals(email, result.getEmail());
        assertNotNull(result.getPasswordHash());
        assertNotNull(result.getServerSalt());
        assertEquals("client-salt", result.getClientSalt());
        assertTrue(result.getEnabled());
        assertFalse(result.getEmailVerified());
        assertFalse(result.getTwoFactorEnabled());
        assertEquals(Set.of("USER"), result.getRoles());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
    }
}

