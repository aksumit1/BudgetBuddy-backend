package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CorrelationIdFilter
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class CorrelationIdFilterTest {

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Test
    void testFilter_IsCreated() {
        // Then
        assertNotNull(correlationIdFilter, "CorrelationIdFilter should be created");
    }

    @Test
    void testDoFilterInternal_WithExistingCorrelationId_UsesExisting() throws ServletException, IOException {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String existingCorrelationId = "existing-correlation-id";
        request.addHeader("X-Correlation-ID", existingCorrelationId);
        FilterChain filterChain = (req, res) -> {
            // Verify correlation ID is set
            if (res instanceof org.springframework.mock.web.MockHttpServletResponse mockResponse) {
                assertEquals(existingCorrelationId, mockResponse.getHeader("X-Correlation-ID"));
            }
        };

        // When
        correlationIdFilter.doFilterInternal(request, response, filterChain);

        // Then
        assertEquals(existingCorrelationId, response.getHeader("X-Correlation-ID"));
    }

    @Test
    void testDoFilterInternal_WithoutCorrelationId_GeneratesNew() throws ServletException, IOException {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = (req, res) -> {
            // Filter chain should execute
        };

        // When
        correlationIdFilter.doFilterInternal(request, response, filterChain);

        // Then
        String correlationId = response.getHeader("X-Correlation-ID");
        assertNotNull(correlationId, "Should generate correlation ID");
        assertFalse(correlationId.isEmpty(), "Correlation ID should not be empty");
    }

    @Test
    void testDoFilterInternal_WithEmptyCorrelationId_GeneratesNew() throws ServletException, IOException {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Correlation-ID", "");
        FilterChain filterChain = (req, res) -> {
            // Filter chain should execute
        };

        // When
        correlationIdFilter.doFilterInternal(request, response, filterChain);

        // Then
        String correlationId = response.getHeader("X-Correlation-ID");
        assertNotNull(correlationId, "Should generate correlation ID");
        assertFalse(correlationId.isEmpty(), "Correlation ID should not be empty");
    }
}

