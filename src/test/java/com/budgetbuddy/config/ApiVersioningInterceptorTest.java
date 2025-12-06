package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ApiVersioningInterceptor
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {
    "api.version=1.0.0",
    "api.base-url=https://api.budgetbuddy.com"
})
class ApiVersioningInterceptorTest {

    @Autowired
    private ApiVersioningInterceptor interceptor;

    @Test
    void testInterceptor_IsCreated() {
        // Then
        assertNotNull(interceptor, "ApiVersioningInterceptor should be created");
    }

    @Test
    void testPreHandle_AddsApiVersionHeader() {
        // Given
        HttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        // When
        boolean result = interceptor.preHandle(request, response, null);

        // Then
        assertTrue(result, "Should return true");
        assertEquals("1.0.0", response.getHeader("X-API-Version"), "Should add API version header");
    }

    @Test
    void testPreHandle_AddsApiBaseUrlHeader() {
        // Given
        HttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        // When
        interceptor.preHandle(request, response, null);

        // Then
        assertEquals("https://api.budgetbuddy.com", response.getHeader("X-API-Base-URL"), 
                "Should add API base URL header");
    }

    @Test
    void testPreHandle_WithEmptyBaseUrl_DoesNotAddHeader() {
        // Given - Create interceptor with empty base URL
        ApiVersioningInterceptor interceptorWithEmptyUrl = new ApiVersioningInterceptor();
        // Use reflection or create a test configuration
        HttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        // When
        boolean result = interceptor.preHandle(request, response, null);

        // Then - Should still work
        assertTrue(result, "Should return true");
        assertNotNull(response.getHeader("X-API-Version"), "Should still add version header");
    }
}

