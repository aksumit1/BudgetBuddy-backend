package com.budgetbuddy.filter;

import com.budgetbuddy.security.rate.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for RateLimitHeaderFilter
 */
@ExtendWith(MockitoExtension.class)
class RateLimitHeaderFilterTest {

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private RateLimitHeaderFilter filter;

    @BeforeEach
    void setUp() {
        when(request.getRequestURI()).thenReturn("/api/transactions");
    }

    @Test
    void testDoFilterInternal_WithValidRequest_AddsHeaders() throws Exception {
        // Given
        when(request.getAttribute("userId")).thenReturn("user-123");
        when(rateLimitService.getRemainingRequests(anyString(), anyString(), anyInt())).thenReturn(50);
        when(rateLimitService.getResetTime(anyString(), anyString())).thenReturn(System.currentTimeMillis() / 1000 + 60);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(response, atLeastOnce()).setHeader(eq("X-RateLimit-Limit"), anyString());
        verify(response, atLeastOnce()).setHeader(eq("X-RateLimit-Remaining"), anyString());
        verify(response, atLeastOnce()).setHeader(eq("X-RateLimit-Reset"), anyString());
        verify(filterChain, times(1)).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_WithNullUserId_SetsDefaultHeaders() throws Exception {
        // Given
        when(request.getAttribute("userId")).thenReturn(null);

        // When
        filter.doFilterInternal(request, response, filterChain);

        // Then
        verify(response, atLeastOnce()).setHeader(eq("X-RateLimit-Limit"), anyString());
        verify(filterChain, times(1)).doFilter(any(), any());
    }
}

