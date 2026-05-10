package com.budgetbuddy.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.when;

import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

/** Unit Tests for MessageUtil */
@ExtendWith(MockitoExtension.class)
class MessageUtilTest {

    @Mock private MessageSource messageSource;

    @InjectMocks private MessageUtil messageUtil;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.US);
    }

    @Test
    void testGetMessageWithValidKeyReturnsMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        when(messageSource.getMessage(eq("test.key"), isNull(), eq("test.key"), any(Locale.class)))
                .thenReturn("Test message");

        // When
        final String message = messageUtil.getMessage("test.key");

        // Then
        assertNotNull(message);
        assertEquals("Test message", message);
    }

    @Test
    void testGetMessageWithArgumentsReturnsFormattedMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        final Object[] args = new Object[] {"John"};
        when(messageSource.getMessage(eq("test.key"), eq(args), eq("test.key"), any(Locale.class)))
                .thenReturn("Hello John");

        // When
        final String message = messageUtil.getMessage("test.key", args);

        // Then
        assertNotNull(message);
        assertEquals("Hello John", message);
    }

    @Test
    void testGetErrorMessageWithErrorCodeReturnsMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        when(messageSource.getMessage(
                        eq("error.user.not.found"),
                        isNull(),
                        eq("error.user.not.found"),
                        any(Locale.class)))
                .thenReturn("User not found");

        // When
        final String message = messageUtil.getErrorMessage("USER_NOT_FOUND");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetValidationMessageWithFieldNameReturnsMessage() {
        // Given - MessageUtil.getMessage calls messageSource.getMessage(code, args, code, locale)
        when(messageSource.getMessage(
                        eq("validation.email"),
                        isNull(),
                        eq("validation.email"),
                        any(Locale.class)))
                .thenReturn("Invalid email");

        // When
        final String message = messageUtil.getValidationMessage("email");

        // Then
        assertNotNull(message);
    }
}
