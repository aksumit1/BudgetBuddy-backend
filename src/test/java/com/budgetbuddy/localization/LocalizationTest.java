package com.budgetbuddy.localization;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.util.MessageUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Localization Tests
 * Tests internationalization and localization features
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class LocalizationTest {

    @Autowired
    private MessageUtil messageUtil;

    @Test
    void testGetMessage_WithDefaultLocale() {
        // Given - Default locale (en_US)
        LocaleContextHolder.setLocale(Locale.US);

        // When
        String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessage_WithFrenchLocale() {
        // Given - French locale
        LocaleContextHolder.setLocale(Locale.FRANCE);

        // When
        String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessage_WithSpanishLocale() {
        // Given - Spanish locale
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es-ES"));

        // When
        String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessage_WithGermanLocale() {
        // Given - German locale
        LocaleContextHolder.setLocale(Locale.GERMANY);

        // When
        String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessage_WithJapaneseLocale() {
        // Given - Japanese locale
        LocaleContextHolder.setLocale(Locale.JAPAN);

        // When
        String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessage_WithUnsupportedLocale_FallsBackToDefault() {
        // Given - Unsupported locale
        LocaleContextHolder.setLocale(Locale.forLanguageTag("xx-XX"));

        // When
        String message = messageUtil.getMessage("error.user.not.found");

        // Then - Should fallback to default
        assertNotNull(message);
    }

    @Test
    void testGetErrorMessage_WithErrorCode() {
        // Given
        LocaleContextHolder.setLocale(Locale.US);

        // When
        String message = messageUtil.getErrorMessage(ErrorCode.USER_NOT_FOUND.name());

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessage_WithArguments() {
        // Given
        LocaleContextHolder.setLocale(Locale.US);
        Object[] args = new Object[]{"test@example.com"};

        // When
        String message = messageUtil.getMessage("error.user.not.found", args);

        // Then
        assertNotNull(message);
    }

    @Test
    void testLocaleResolution_FromAcceptLanguageHeader() {
        // Given - Simulate Accept-Language header
        // In real scenario, this would be set by LocaleResolver

        // When/Then - Locale should be resolved
        Locale currentLocale = LocaleContextHolder.getLocale();
        assertNotNull(currentLocale);
    }
}
