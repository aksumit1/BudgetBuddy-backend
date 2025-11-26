package com.budgetbuddy.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for RequestResponseLoggingFilter
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class RequestResponseLoggingFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private RequestResponseLoggingFilter filter;

    @BeforeEach
    void setUp() throws IOException {
        when(request.getRequestURI()).thenReturn("/api/test");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);
    }

    @Test
    void testDoFilterInternal_WithValidRequest_ProcessesRequest() throws ServletException, IOException {
        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_WithHealthEndpoint_SkipsFilter() throws ServletException, IOException {
        // Given
        when(request.getRequestURI()).thenReturn("/actuator/health");

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    void testShouldNotFilter_WithHealthEndpoint_ReturnsTrue() {
        // Given
        when(request.getRequestURI()).thenReturn("/actuator/health");

        // When
        boolean shouldNotFilter = filter.shouldNotFilter(request);

        // Then
        assertTrue(shouldNotFilter);
    }

    @Test
    void testShouldNotFilter_WithApiEndpoint_ReturnsFalse() {
        // Given
        when(request.getRequestURI()).thenReturn("/api/transactions");

        // When
        boolean shouldNotFilter = filter.shouldNotFilter(request);

        // Then
        assertFalse(shouldNotFilter);
    }
}

