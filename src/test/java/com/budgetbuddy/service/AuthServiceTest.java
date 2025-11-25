package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setFirstName("Test");
        testUser.setLastName("User");
    }

    @Test
    void testRegisterUser_Success() {
        // Arrange
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        when(userRepository.save(any(UserTable.class))).thenReturn(testUser);

        // Act
        var result = authService.register("test@example.com", "password123", "Test", "User");

        // Assert
        assertNotNull(result);
        assertEquals("test@example.com", result.getEmail());
        verify(userRepository, times(1)).save(any(UserTable.class));
    }

    @Test
    void testRegisterUser_EmailAlreadyExists() {
        // Arrange
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            authService.register("test@example.com", "password123", "Test", "User");
        });

        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getErrorCode());
        verify(userRepository, never()).save(any(UserTable.class));
    }

    @Test
    void testLogin_Success() {
        // Arrange
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        // Act
        var result = authService.login("test@example.com", "password123");

        // Assert
        assertNotNull(result);
        assertNotNull(result.getToken());
        assertEquals("user-123", result.getUserId());
    }

    @Test
    void testLogin_InvalidCredentials() {
        // Arrange
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            authService.login("test@example.com", "wrong-password");
        });

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void testLogin_UserNotFound() {
        // Arrange
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            authService.login("nonexistent@example.com", "password123");
        });

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }
}

