package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;

/** Tests for CorrelationIdFilter */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class CorrelationIdFilterTest {

    @Autowired private CorrelationIdFilter correlationIdFilter;

    @Test
    void testFilterIsCreated() {
        // Then
        assertNotNull(correlationIdFilter, "CorrelationIdFilter should be created");
    }

    @Test
    void testDoFilterInternalWithExistingCorrelationIdUsesExisting()
            throws ServletException, IOException {
        // Given
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final String existingCorrelationId = "existing-correlation-id";
        request.addHeader("X-Correlation-ID", existingCorrelationId);
        final FilterChain filterChain =
                (req, res) -> {
                    // Verify correlation ID is set
                    if (res
                            instanceof
                            org.springframework.mock.web.MockHttpServletResponse mockResponse) {
                        assertEquals(
                                existingCorrelationId, mockResponse.getHeader("X-Correlation-ID"));
                    }
                };

        // When
        correlationIdFilter.doFilterInternal(request, response, filterChain);

        // Then
        assertEquals(existingCorrelationId, response.getHeader("X-Correlation-ID"));
    }

    @Test
    void testDoFilterInternalWithoutCorrelationIdGeneratesNew()
            throws ServletException, IOException {
        // Given
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain filterChain =
                (req, res) -> {
                    // Filter chain should execute
                };

        // When
        correlationIdFilter.doFilterInternal(request, response, filterChain);

        // Then
        final String correlationId = response.getHeader("X-Correlation-ID");
        assertNotNull(correlationId, "Should generate correlation ID");
        assertFalse(correlationId.isEmpty(), "Correlation ID should not be empty");
    }

    @Test
    void testDoFilterInternalWithEmptyCorrelationIdGeneratesNew()
            throws ServletException, IOException {
        // Given
        final MockHttpServletRequest request = new MockHttpServletRequest();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Correlation-ID", "");
        final FilterChain filterChain =
                (req, res) -> {
                    // Filter chain should execute
                };

        // When
        correlationIdFilter.doFilterInternal(request, response, filterChain);

        // Then
        final String correlationId = response.getHeader("X-Correlation-ID");
        assertNotNull(correlationId, "Should generate correlation ID");
        assertFalse(correlationId.isEmpty(), "Correlation ID should not be empty");
    }
}
