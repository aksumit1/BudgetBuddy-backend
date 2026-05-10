package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for UserController Tests the /api/users/me endpoint */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private UserController userController;

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
    void testGetCurrentUserWithValidUserReturnsUserInfo() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<Map<String, Object>> response = userController.getCurrentUser(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        final Map<String, Object> userInfo = response.getBody();
        assertEquals(testUser.getUserId(), userInfo.get("userId"));
        assertEquals(testUser.getEmail(), userInfo.get("email"));
        assertEquals(testUser.getFirstName(), userInfo.get("firstName"));
        assertEquals(testUser.getLastName(), userInfo.get("lastName"));
        assertEquals(testUser.getEmailVerified(), userInfo.get("emailVerified"));
        assertEquals(testUser.getEnabled(), userInfo.get("enabled"));
        verify(userService, times(1)).findByEmail("test@example.com");
    }

    @Test
    void testGetCurrentUserWithNullUserDetailsThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userController.getCurrentUser(null);
                        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testGetCurrentUserWithNullUsernameThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(null);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userController.getCurrentUser(userDetails);
                        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testGetCurrentUserWithEmptyUsernameThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("");

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userController.getCurrentUser(userDetails);
                        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testGetCurrentUserWithUserNotFoundThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("nonexistent@example.com");
        when(userService.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userController.getCurrentUser(userDetails);
                        });
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userService, times(1)).findByEmail("nonexistent@example.com");
    }

    @Test
    void testGetCurrentUserWithNullFieldsReturnsDefaults() {
        // Given
        testUser.setFirstName(null);
        testUser.setLastName(null);
        testUser.setEmailVerified(null);
        testUser.setEnabled(null);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // When
        final ResponseEntity<Map<String, Object>> response = userController.getCurrentUser(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        final Map<String, Object> userInfo = response.getBody();
        assertEquals("", userInfo.get("firstName"));
        assertEquals("", userInfo.get("lastName"));
        assertEquals(false, userInfo.get("emailVerified"));
        assertEquals(true, userInfo.get("enabled"));
    }
}
