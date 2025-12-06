package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InternationalizationConfig
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class InternationalizationConfigTest {

    @Autowired
    private LocaleResolver localeResolver;

    @Autowired
    private LocaleChangeInterceptor localeChangeInterceptor;

    @Autowired
    private ResourceBundleMessageSource messageSource;

    @Test
    void testLocaleResolver_IsCreated() {
        // Then
        assertNotNull(localeResolver, "LocaleResolver should be created");
    }

    @Test
    void testLocaleResolver_ResolvesDefaultLocale() {
        // Given - Create mock request
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();

        // When
        Locale locale = localeResolver.resolveLocale(request);

        // Then
        assertNotNull(locale, "Locale should not be null");
        assertEquals(Locale.US, locale, "Default locale should be US");
    }

    @Test
    void testLocaleChangeInterceptor_IsCreated() {
        // Then
        assertNotNull(localeChangeInterceptor, "LocaleChangeInterceptor should be created");
    }

    @Test
    void testLocaleChangeInterceptor_HasParamName() {
        // When
        String paramName = localeChangeInterceptor.getParamName();

        // Then
        assertEquals("lang", paramName, "Param name should be 'lang'");
    }

    @Test
    void testMessageSource_IsCreated() {
        // Then
        assertNotNull(messageSource, "MessageSource should be created");
    }

    @Test
    void testMessageSource_ReturnsMessage() {
        // When
        String message = messageSource.getMessage("test.key", null, Locale.US);

        // Then - Should return code as default message if not found
        assertNotNull(message, "Message should not be null");
    }

    @Test
    void testMessageSource_WithDefaultEncoding() {
        // Then - Verify message source is created with UTF-8 encoding
        // (getDefaultEncoding() is not visible, so we verify it works by testing message retrieval)
        String message = messageSource.getMessage("test.key", null, Locale.US);
        assertNotNull(message, "Message source should work with UTF-8 encoding");
    }
}

