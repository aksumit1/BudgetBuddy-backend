package com.budgetbuddy.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;

import static org.mockito.Mockito.*;

class JwtAuthenticationEntryPointTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private AuthenticationException authException;

    private JwtAuthenticationEntryPoint entryPoint;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        entryPoint = new JwtAuthenticationEntryPoint();
    }

    @Test
    void testCommence_ShouldSendUnauthorizedError() throws IOException, ServletException {
        // Given
        when(request.getRequestURI()).thenReturn("/api/transactions");
        when(authException.getMessage()).thenReturn("JWT token is invalid");
        // Note: getClass() cannot be mocked as Class is final
        
        // When
        entryPoint.commence(request, response, authException);
        
        // Then
        verify(response, times(1)).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
    }

    @Test
    void testCommence_WithNullMessage_ShouldStillSendError() throws IOException, ServletException {
        // Given
        when(request.getRequestURI()).thenReturn("/api/test");
        when(authException.getMessage()).thenReturn(null);
        // Note: getClass() cannot be mocked as Class is final
        
        // When
        entryPoint.commence(request, response, authException);
        
        // Then
        verify(response, times(1)).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
    }
}

