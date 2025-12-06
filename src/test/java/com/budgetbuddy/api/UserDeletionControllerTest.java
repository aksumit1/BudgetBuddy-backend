package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserDeletionService;
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
 * Unit Tests for UserDeletionController
 * Tests user data and account deletion endpoints
 */
@ExtendWith(MockitoExtension.class)
class UserDeletionControllerTest {

    @Mock
    private UserDeletionService userDeletionService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private UserDeletionController userDeletionController;

    private UserTable testUser;
    private String testUserId;
    private String testEmail;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testEmail = "test@example.com";
        
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail(testEmail);
    }

    @Test
    void testDeleteAllData_WithConfirmation_DeletesData() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(userDeletionService).deleteAllUserData(testUserId);

        // When
        ResponseEntity<Map<String, String>> response = 
                userDeletionController.deleteAllData(userDetails, true);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        verify(userDeletionService, times(1)).deleteAllUserData(testUserId);
    }

    @Test
    void testDeleteAllData_WithoutConfirmation_ReturnsBadRequest() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        
        // When
        ResponseEntity<Map<String, String>> response = 
                userDeletionController.deleteAllData(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        verify(userDeletionService, never()).deleteAllUserData(anyString());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testDeleteAllData_WithNullUserDetails_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionController.deleteAllData(null, true);
        });
        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
        verify(userDeletionService, never()).deleteAllUserData(anyString());
    }

    @Test
    void testDeleteAllData_WithUserNotFound_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.empty());

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionController.deleteAllData(userDetails, true);
        });
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userDeletionService, never()).deleteAllUserData(anyString());
    }

    @Test
    void testDeleteAllData_WithServiceException_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doThrow(new RuntimeException("Service error")).when(userDeletionService).deleteAllUserData(testUserId);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionController.deleteAllData(userDetails, true);
        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }

    @Test
    void testDeletePlaidIntegration_WithConfirmation_DeletesIntegration() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(userDeletionService).deletePlaidIntegration(testUserId);

        // When
        ResponseEntity<Map<String, String>> response = 
                userDeletionController.deletePlaidIntegration(userDetails, true);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        verify(userDeletionService, times(1)).deletePlaidIntegration(testUserId);
    }

    @Test
    void testDeletePlaidIntegration_WithoutConfirmation_ReturnsBadRequest() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        
        // When
        ResponseEntity<Map<String, String>> response = 
                userDeletionController.deletePlaidIntegration(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        verify(userDeletionService, never()).deletePlaidIntegration(anyString());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testDeletePlaidIntegration_WithServiceException_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doThrow(new RuntimeException("Service error")).when(userDeletionService).deletePlaidIntegration(testUserId);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionController.deletePlaidIntegration(userDetails, true);
        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }

    @Test
    void testDeleteAccount_WithConfirmation_DeletesAccount() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(userDeletionService).deleteAccountCompletely(testUserId);

        // When
        ResponseEntity<Map<String, String>> response = 
                userDeletionController.deleteAccount(userDetails, true);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        verify(userDeletionService, times(1)).deleteAccountCompletely(testUserId);
    }

    @Test
    void testDeleteAccount_WithoutConfirmation_ReturnsBadRequest() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        
        // When
        ResponseEntity<Map<String, String>> response = 
                userDeletionController.deleteAccount(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        verify(userDeletionService, never()).deleteAccountCompletely(anyString());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testDeleteAccount_WithServiceException_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doThrow(new RuntimeException("Service error")).when(userDeletionService).deleteAccountCompletely(testUserId);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionController.deleteAccount(userDetails, true);
        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }
}

