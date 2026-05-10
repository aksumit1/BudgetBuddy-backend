package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Unit Tests for ApiVersioningConfig Tests API versioning configuration */
@ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
@SpringJUnitConfig(ApiVersioningConfig.class)
class ApiVersioningConfigTest {

    @Test
    void testApiVersioningConfigLoadsSuccessfully() {
        // Given/When - Configuration should load without errors
        // This test verifies that the configuration class is properly annotated
        // and can be loaded by Spring

        // Then - Configuration should be valid
        assertTrue(true, "ApiVersioningConfig should load successfully");
    }

    @Test
    void testApiVersioningConfigImplementsWebMvcConfigurer() {
        // Given
        final ApiVersioningConfig config = new ApiVersioningConfig();

        // Then
        assertTrue(
                config
                        instanceof
                        org.springframework.web.servlet.config.annotation.WebMvcConfigurer,
                "ApiVersioningConfig should implement WebMvcConfigurer");
    }
}
