package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for UserController
 * Tests the /api/users/me endpoint
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private UserController userController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
        testUser.setEmailVerified(true);
        testUser.setEnabled(true);
    }

    @Test
    void testGetCurrentUser_WithValidUser_ReturnsUserInfo() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<Map<String, Object>> response = userController.getCurrentUser(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<String, Object> userInfo = response.getBody();
        assertEquals(testUser.getUserId(), userInfo.get("userId"));
        assertEquals(testUser.getEmail(), userInfo.get("email"));
        assertEquals(testUser.getFirstName(), userInfo.get("firstName"));
        assertEquals(testUser.getLastName(), userInfo.get("lastName"));
        assertEquals(testUser.getEmailVerified(), userInfo.get("emailVerified"));
        assertEquals(testUser.getEnabled(), userInfo.get("enabled"));
        verify(userService, times(1)).findByEmail("test@example.com");
    }

    @Test
    void testGetCurrentUser_WithNullUserDetails_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userController.getCurrentUser(null);
        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testGetCurrentUser_WithNullUsername_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(null);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userController.getCurrentUser(userDetails);
        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testGetCurrentUser_WithEmptyUsername_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("");

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userController.getCurrentUser(userDetails);
        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testGetCurrentUser_WithUserNotFound_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("nonexistent@example.com");
        when(userService.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userController.getCurrentUser(userDetails);
        });
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userService, times(1)).findByEmail("nonexistent@example.com");
    }

    @Test
    void testGetCurrentUser_WithNullFields_ReturnsDefaults() {
        // Given
        testUser.setFirstName(null);
        testUser.setLastName(null);
        testUser.setEmailVerified(null);
        testUser.setEnabled(null);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        ResponseEntity<Map<String, Object>> response = userController.getCurrentUser(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> userInfo = response.getBody();
        assertEquals("", userInfo.get("firstName"));
        assertEquals("", userInfo.get("lastName"));
        assertEquals(false, userInfo.get("emailVerified"));
        assertEquals(true, userInfo.get("enabled"));
    }
}

