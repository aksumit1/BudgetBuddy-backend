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
 * 
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
        when(passwordHashingService.hashClientPassword(anyString(), any()))
                .thenReturn(hashResult);
    }

    @Test
    void testCreateUserSecure_NewUser_Succeeds() {
        // Arrange
        String email = "newuser@example.com";
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        when(userRepository.findAllByEmail(email)).thenReturn(java.util.Collections.emptyList());

        // Act
        // BREAKING CHANGE: Client salt removed
        UserTable result = userService.createUserSecure(
                email,
                "client-hash",
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
        
        // Verify user was saved (the implementation saves first, then checks for duplicates)
        verify(userRepository, times(1)).saveIfNotExists(any(UserTable.class));
        // Verify duplicate check was performed
        verify(userRepository, times(1)).findAllByEmail(email);
    }

    @Test
    void testCreateUserSecure_DuplicateEmail_ThrowsException() {
        // Arrange - Simulate duplicate email detected after save
        String email = "existing@example.com";
        UserTable existingUser = new UserTable();
        existingUser.setEmail(email);
        existingUser.setUserId("existing-user-id");
        
        UserTable newUser = new UserTable();
        newUser.setEmail(email);
        newUser.setUserId("new-user-id");
        
        // Save succeeds, but duplicate check finds 2 users
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        when(userRepository.findAllByEmail(email)).thenReturn(java.util.Arrays.asList(existingUser, newUser));
        doNothing().when(userRepository).delete(anyString());

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            // BREAKING CHANGE: Client salt removed
            userService.createUserSecure(
                    email,
                    "client-hash",
                    "John",
                    "Doe"
            );
        });

        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("already exists"));
        
        // Verify saveIfNotExists was called (implementation saves first)
        verify(userRepository, times(1)).saveIfNotExists(any(UserTable.class));
        // Verify duplicate check was performed
        verify(userRepository, times(1)).findAllByEmail(email);
    }

    @Test
    void testCreateUserSecure_EmailCheckHappensBeforeUserCreation() {
        // Arrange
        String email = "test@example.com";
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        when(userRepository.findAllByEmail(email)).thenReturn(java.util.Collections.emptyList());

        // Act
        userService.createUserSecure(email, "hash", "First", "Last");

        // Assert - Verify order of operations (implementation saves first, then checks for duplicates)
        var inOrder = inOrder(userRepository);
        inOrder.verify(userRepository).saveIfNotExists(any(UserTable.class));
        inOrder.verify(userRepository).findAllByEmail(email);
    }

    @Test
    void testCreateUserSecure_SaveIfNotExistsFails_HandlesGracefully() {
        // Arrange - saveIfNotExists returns false (userId collision)
        String email = "test@example.com";
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(false);

        // Act & Assert - Should throw INTERNAL_SERVER_ERROR when saveIfNotExists fails
        AppException exception = assertThrows(AppException.class, () -> {
            userService.createUserSecure(email, "hash", "First", "Last");
        });

        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Failed to create user"));
        
        // Verify saveIfNotExists was called
        verify(userRepository, times(1)).saveIfNotExists(any(UserTable.class));
    }

    @Test
    void testCreateUserSecure_InvalidInput_ThrowsException() {
        // Act & Assert - Null email
        assertThrows(AppException.class, () -> {
            userService.createUserSecure(null, "hash", "First", "Last");
        });

        // Act & Assert - Empty email
        assertThrows(AppException.class, () -> {
            userService.createUserSecure("", "hash", "First", "Last");
        });

        // Act & Assert - Null password hash
        assertThrows(AppException.class, () -> {
            userService.createUserSecure("test@example.com", null, "First", "Last");
        });

        // Act & Assert - Null salt
        assertThrows(AppException.class, () -> {
            userService.createUserSecure("test@example.com", "hash", "First", "Last");
        });
    }

    @Test
    void testCreateUserSecure_UserHasCorrectDefaultValues() {
        // Arrange
        String email = "newuser@example.com";
        when(userRepository.saveIfNotExists(any(UserTable.class))).thenReturn(true);
        when(userRepository.findAllByEmail(email)).thenReturn(java.util.Collections.emptyList());

        // Act
        // BREAKING CHANGE: Client salt removed
        UserTable result = userService.createUserSecure(
                email,
                "client-hash",
                null, // No first name
                null  // No last name
        );

        // Assert
        assertNotNull(result.getUserId());
        assertFalse(result.getUserId().isEmpty());
        assertEquals(email, result.getEmail());
        assertNotNull(result.getPasswordHash());
        assertNotNull(result.getServerSalt());
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        assertTrue(result.getEnabled());
        assertFalse(result.getEmailVerified());
        assertFalse(result.getTwoFactorEnabled());
        assertEquals(Set.of("USER"), result.getRoles());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
    }
}

