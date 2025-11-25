package com.budgetbuddy.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for MessageUtil
 */
@ExtendWith(MockitoExtension.class)
class MessageUtilTest {

    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private MessageUtil messageUtil;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.US);
    }

    @Test
    void testGetMessage_WithValidKey_ReturnsMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        when(messageSource.getMessage(eq("test.key"), isNull(), eq("test.key"), any(Locale.class)))
                .thenReturn("Test message");

        // When
        String message = messageUtil.getMessage("test.key");

        // Then
        assertNotNull(message);
        assertEquals("Test message", message);
    }

    @Test
    void testGetMessage_WithArguments_ReturnsFormattedMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        Object[] args = new Object[]{"John"};
        when(messageSource.getMessage(eq("test.key"), eq(args), eq("test.key"), any(Locale.class)))
                .thenReturn("Hello John");

        // When
        String message = messageUtil.getMessage("test.key", args);

        // Then
        assertNotNull(message);
        assertEquals("Hello John", message);
    }

    @Test
    void testGetErrorMessage_WithErrorCode_ReturnsMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        when(messageSource.getMessage(eq("error.user.not.found"), isNull(), eq("error.user.not.found"), any(Locale.class)))
                .thenReturn("User not found");

        // When
        String message = messageUtil.getErrorMessage("USER_NOT_FOUND");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetValidationMessage_WithFieldName_ReturnsMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        when(messageSource.getMessage(eq("validation.email"), isNull(), eq("validation.email"), any(Locale.class)))
                .thenReturn("Invalid email");

        // When
        String message = messageUtil.getValidationMessage("email");

        // Then
        assertNotNull(message);
    }
}

