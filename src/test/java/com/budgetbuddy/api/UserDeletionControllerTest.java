package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserDeletionService;
import com.budgetbuddy.service.UserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for UserDeletionController Tests user data and account deletion endpoints */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class UserDeletionControllerTest {

    @Mock private UserDeletionService userDeletionService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private UserDeletionController userDeletionController;

    private UserTable testUser;
    private String testUserId;
    private String testEmail;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testEmail = "test@example.com";

        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail(testEmail);

        // Set up log appender to capture log events for verification
        logger = (Logger) LoggerFactory.getLogger(UserDeletionController.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @Test
    void testDeleteAllDataWithConfirmationDeletesData() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(userDeletionService).deleteAllUserData(testUserId);

        // When
        final ResponseEntity<Map<String, String>> response =
                userDeletionController.deleteAllData(userDetails, true);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        verify(userDeletionService, times(1)).deleteAllUserData(testUserId);
    }

    @Test
    void testDeleteAllDataWithoutConfirmationReturnsBadRequest() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);

        // When
        final ResponseEntity<Map<String, String>> response =
                userDeletionController.deleteAllData(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        verify(userDeletionService, never()).deleteAllUserData(anyString());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testDeleteAllDataWithNullUserDetailsThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userDeletionController.deleteAllData(null, true);
                        });
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
        verify(userDeletionService, never()).deleteAllUserData(anyString());
    }

    @Test
    void testDeleteAllDataWithUserNotFoundThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.empty());

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userDeletionController.deleteAllData(userDetails, true);
                        });
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userDeletionService, never()).deleteAllUserData(anyString());
    }

    @Test
    void testDeleteAllDataWithServiceExceptionThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doThrow(new RuntimeException("Service error"))
                .when(userDeletionService)
                .deleteAllUserData(testUserId);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userDeletionController.deleteAllData(userDetails, true);
                        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }

    @Test
    void testDeletePlaidIntegrationWithConfirmationDeletesIntegration() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(userDeletionService).deletePlaidIntegration(testUserId);

        // When
        final ResponseEntity<Map<String, String>> response =
                userDeletionController.deletePlaidIntegration(userDetails, true);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        verify(userDeletionService, times(1)).deletePlaidIntegration(testUserId);
    }

    @Test
    void testDeletePlaidIntegrationWithoutConfirmationReturnsBadRequest() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);

        // When
        final ResponseEntity<Map<String, String>> response =
                userDeletionController.deletePlaidIntegration(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        verify(userDeletionService, never()).deletePlaidIntegration(anyString());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testDeletePlaidIntegrationWithServiceExceptionThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doThrow(new RuntimeException("Service error"))
                .when(userDeletionService)
                .deletePlaidIntegration(testUserId);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userDeletionController.deletePlaidIntegration(userDetails, true);
                        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }

    @Test
    void testDeleteAccountWithConfirmationDeletesAccount() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doNothing().when(userDeletionService).deleteAccountCompletely(testUserId);

        // When
        final ResponseEntity<Map<String, String>> response =
                userDeletionController.deleteAccount(userDetails, true);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("success", response.getBody().get("status"));
        verify(userDeletionService, times(1)).deleteAccountCompletely(testUserId);
    }

    @Test
    void testDeleteAccountWithoutConfirmationReturnsBadRequest() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);

        // When
        final ResponseEntity<Map<String, String>> response =
                userDeletionController.deleteAccount(userDetails, false);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
        verify(userDeletionService, never()).deleteAccountCompletely(anyString());
        verify(userService, never()).findByEmail(anyString());
    }

    @Test
    void testDeleteAccountWithServiceExceptionThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(testEmail);
        when(userService.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        doThrow(new RuntimeException("Service error"))
                .when(userDeletionService)
                .deleteAccountCompletely(testUserId);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            userDeletionController.deleteAccount(userDetails, true);
                        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());

        // Verify logging behavior - should log ERROR when account deletion fails
        final List<ILoggingEvent> logEvents = logAppender.list;
        final long errorLogs =
                logEvents.stream()
                        .filter(
                                event ->
                                        event.getLevel() == Level.ERROR
                                                && event.getMessage()
                                                        .contains("Failed to delete account"))
                        .count();

        assertEquals(1, errorLogs, "Should log ERROR when account deletion fails");

        // Verify ERROR log contains expected message
        // Use getFormattedMessage() to get the actual formatted message, not the template
        final boolean foundErrorLog =
                logEvents.stream()
                        .anyMatch(
                                event ->
                                        event.getLevel() == Level.ERROR
                                                && event.getFormattedMessage()
                                                        .contains("Failed to delete account")
                                                && event.getFormattedMessage()
                                                        .contains("Service error"));
        assertTrue(foundErrorLog, "Should log ERROR with service error message");
    }
}
