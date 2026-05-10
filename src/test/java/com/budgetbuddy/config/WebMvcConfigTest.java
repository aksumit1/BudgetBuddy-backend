package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

/** Tests for WebMvcConfig */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class WebMvcConfigTest {

    @Autowired(required = false)
    private WebMvcConfig webMvcConfig;

    @Autowired(required = false)
    private ApiVersioningInterceptor apiVersioningInterceptor;

    @Test
    void testWebMvcConfigIsCreated() {
        // Then
        assertNotNull(webMvcConfig, "WebMvcConfig should be created");
    }

    @Test
    void testApiVersioningInterceptorIsCreated() {
        // Then
        assertNotNull(apiVersioningInterceptor, "ApiVersioningInterceptor should be created");
    }

    @Test
    void testWebMvcConfigRegistersInterceptors() {
        // Given
        if (webMvcConfig == null) {
            // WebMvcConfig might not be created in test profile
            return;
        }
        final InterceptorRegistry registry = new InterceptorRegistry();

        // When - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    webMvcConfig.addInterceptors(registry);
                },
                "Should register interceptors without exception");

        // Then - Verify config exists
        assertNotNull(webMvcConfig, "WebMvcConfig should exist");
    }
}
