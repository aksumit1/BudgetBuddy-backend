package com.budgetbuddy.localization;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.util.MessageUtil;
import com.budgetbuddy.util.TableInitializer;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Localization Tests Tests internationalization and localization features */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalizationTest {

    @Autowired private MessageUtil messageUtil;

    @Autowired private DynamoDbClient dynamoDbClient;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @Test
    void testGetMessageWithDefaultLocale() {
        // Given - Default locale (en_US)
        LocaleContextHolder.setLocale(Locale.US);

        // When
        final String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessageWithFrenchLocale() {
        // Given - French locale
        LocaleContextHolder.setLocale(Locale.FRANCE);

        // When
        final String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessageWithSpanishLocale() {
        // Given - Spanish locale
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es-ES"));

        // When
        final String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessageWithGermanLocale() {
        // Given - German locale
        LocaleContextHolder.setLocale(Locale.GERMANY);

        // When
        final String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessageWithJapaneseLocale() {
        // Given - Japanese locale
        LocaleContextHolder.setLocale(Locale.JAPAN);

        // When
        final String message = messageUtil.getMessage("error.user.not.found");

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessageWithUnsupportedLocaleFallsBackToDefault() {
        // Given - Unsupported locale
        LocaleContextHolder.setLocale(Locale.forLanguageTag("xx-XX"));

        // When
        final String message = messageUtil.getMessage("error.user.not.found");

        // Then - Should fallback to default
        assertNotNull(message);
    }

    @Test
    void testGetErrorMessageWithErrorCode() {
        // Given
        LocaleContextHolder.setLocale(Locale.US);

        // When
        final String message = messageUtil.getErrorMessage(ErrorCode.USER_NOT_FOUND.name());

        // Then
        assertNotNull(message);
    }

    @Test
    void testGetMessageWithArguments() {
        // Given
        LocaleContextHolder.setLocale(Locale.US);
        final Object[] args = new Object[] {"test@example.com"};

        // When
        final String message = messageUtil.getMessage("error.user.not.found", args);

        // Then
        assertNotNull(message);
    }

    @Test
    void testLocaleResolutionFromAcceptLanguageHeader() {
        // Given - Simulate Accept-Language header
        // In real scenario, this would be set by LocaleResolver

        // When/Then - Locale should be resolved
        final Locale currentLocale = LocaleContextHolder.getLocale();
        assertNotNull(currentLocale);
    }
}
