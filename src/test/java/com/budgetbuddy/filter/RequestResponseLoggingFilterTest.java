package com.budgetbuddy.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for RequestResponseLoggingFilter */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RequestResponseLoggingFilterTest {

    @Mock private HttpServletRequest request;

    @Mock private HttpServletResponse response;

    @Mock private FilterChain filterChain;

    @InjectMocks private RequestResponseLoggingFilter filter;

    @BeforeEach
    void setUp() throws IOException {
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        final StringWriter stringWriter = new StringWriter();
        final PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);
    }

    @Test
    void testDoFilterInternalWithValidRequestProcessesRequest()
            throws ServletException, IOException {
        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternalWithHealthEndpointSkipsFilter()
            throws ServletException, IOException {
        // Given
        when(request.getRequestURI()).thenReturn("/actuator/health");

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    void testShouldNotFilterWithHealthEndpointReturnsTrue() {
        // Given
        when(request.getRequestURI()).thenReturn("/actuator/health");

        // When
        final boolean shouldNotFilter = filter.shouldNotFilter(request);

        // Then
        assertTrue(shouldNotFilter);
    }

    @Test
    void testShouldNotFilterWithApiEndpointReturnsFalse() {
        // Given
        when(request.getRequestURI()).thenReturn("/api/transactions");

        // When
        final boolean shouldNotFilter = filter.shouldNotFilter(request);

        // Then
        assertFalse(shouldNotFilter);
    }
}
