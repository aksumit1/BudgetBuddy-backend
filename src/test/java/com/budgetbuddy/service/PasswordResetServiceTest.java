package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.EmailNotificationService;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;

    @Mock private EmailNotificationService emailService;

    @InjectMocks private PasswordResetService passwordResetService;

    private UserTable testUser;
    private String testEmail;

    @BeforeEach
    void setUp() {
        testEmail = "test@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(testEmail);
        testUser.setPasswordHash("existing_hash");
        testUser.setServerSalt("existing_salt");
        testUser.setCreatedAt(Instant.now());
    }

    @Test
    void testRequestPasswordResetSuccess() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(emailService.sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class)))
                .thenReturn(true);

        // When
        assertDoesNotThrow(() -> passwordResetService.requestPasswordReset(testEmail));

        // Then
        verify(userRepository).findByEmail(testEmail);
        verify(emailService)
                .sendEmail(
                        eq(testUser.getUserId()),
                        eq(testEmail),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class));
    }

    @Test
    void testRequestPasswordResetUserNotFound() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.empty());

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            passwordResetService.requestPasswordReset(testEmail);
                        });

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userRepository).findByEmail(testEmail);
        verify(emailService, never())
                .sendEmail(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void testRequestPasswordResetEmailSendFails() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(emailService.sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class)))
                .thenReturn(false);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            passwordResetService.requestPasswordReset(testEmail);
                        });

        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }

    @Test
    void testRequestPasswordResetInvalidEmail() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            passwordResetService.requestPasswordReset("");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testVerifyResetCodeSuccess() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(emailService.sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class)))
                .thenReturn(true);

        // Request reset to generate code
        passwordResetService.requestPasswordReset(testEmail);

        // Capture the code from email
        final ArgumentCaptor<Map<String, Object>> templateDataCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(emailService)
                .sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        templateDataCaptor.capture());
        final String code = (String) templateDataCaptor.getValue().get("code");

        // When
        assertDoesNotThrow(() -> passwordResetService.verifyResetCode(testEmail, code));

        // Then
        verify(userRepository, atLeastOnce()).findByEmail(testEmail);
    }

    @Test
    void testVerifyResetCodeInvalidCode() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(emailService.sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class)))
                .thenReturn(true);

        passwordResetService.requestPasswordReset(testEmail);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            passwordResetService.verifyResetCode(testEmail, "000000");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testVerifyResetCodeCodeNotFound() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            passwordResetService.verifyResetCode(testEmail, "123456");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("No reset code found"));
    }

    @Test
    void testVerifyResetCodeInvalidFormat() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            passwordResetService.verifyResetCode(testEmail, "12345"); // Too short
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testResetPasswordSuccess() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(emailService.sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class)))
                .thenReturn(true);

        // Request and verify code
        passwordResetService.requestPasswordReset(testEmail);
        final ArgumentCaptor<Map<String, Object>> templateDataCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(emailService)
                .sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        templateDataCaptor.capture());
        final String code = (String) templateDataCaptor.getValue().get("code");
        passwordResetService.verifyResetCode(testEmail, code);

        // When
        assertDoesNotThrow(
                () -> {
                    passwordResetService.resetPassword(testEmail, code, "new_hash");
                });

        // Then
        verify(userRepository, atLeast(2)).findByEmail(testEmail);
    }

    @Test
    void testResetPasswordUnverifiedCode() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(emailService.sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class)))
                .thenReturn(true);

        passwordResetService.requestPasswordReset(testEmail);
        final ArgumentCaptor<Map<String, Object>> templateDataCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(emailService)
                .sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        templateDataCaptor.capture());
        final String code = (String) templateDataCaptor.getValue().get("code");

        // When/Then - Try to reset without verifying
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            passwordResetService.resetPassword(testEmail, code, "new_hash");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Invalid or unverified code"));
    }

    @Test
    void testResetPasswordMissingParameters() {
        // When/Then
        assertThrows(
                AppException.class,
                () -> {
                    passwordResetService.resetPassword("", "123456", "hash");
                });

        assertThrows(
                AppException.class,
                () -> {
                    passwordResetService.resetPassword(testEmail, "", "hash");
                });

        assertThrows(
                AppException.class,
                () -> {
                    passwordResetService.resetPassword(testEmail, "123456", "");
                });

        assertThrows(
                AppException.class,
                () -> {
                    passwordResetService.resetPassword(testEmail, "123456", "hash");
                });
    }

    @Test
    void testCodeGenerationValidFormat() {
        // Given
        when(userRepository.findByEmail(testEmail)).thenReturn(Optional.of(testUser));
        when(emailService.sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        any(Map.class)))
                .thenReturn(true);

        // When
        passwordResetService.requestPasswordReset(testEmail);

        // Then - Verify code format
        final ArgumentCaptor<Map<String, Object>> templateDataCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(emailService)
                .sendEmail(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        isNull(),
                        templateDataCaptor.capture());
        final String code = (String) templateDataCaptor.getValue().get("code");
        assertNotNull(code);
        assertEquals(6, code.length());
        assertTrue(code.matches("\\d{6}")); // 6 digits
    }
}
