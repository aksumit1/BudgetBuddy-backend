package com.budgetbuddy.security;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.AuthenticationException;

class JwtAuthenticationEntryPointTest {

    @Mock private HttpServletRequest request;

    @Mock private HttpServletResponse response;

    @Mock private AuthenticationException authException;

    private JwtAuthenticationEntryPoint entryPoint;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        entryPoint = new JwtAuthenticationEntryPoint();
    }

    @Test
    void testCommenceShouldSendUnauthorizedError() throws IOException, ServletException {
        // Given
        when(request.getRequestURI()).thenReturn("/api/transactions");
        when(authException.getMessage()).thenReturn("JWT token is invalid");
        // Note: getClass() cannot be mocked as Class is final

        // When
        entryPoint.commence(request, response, authException);

        // Then
        verify(response, times(1))
                .sendError(HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
    }

    @Test
    void testCommenceWithNullMessageShouldStillSendError() throws IOException, ServletException {
        // Given
        when(request.getRequestURI()).thenReturn("/api/test");
        when(authException.getMessage()).thenReturn(null);
        // Note: getClass() cannot be mocked as Class is final

        // When
        entryPoint.commence(request, response, authException);

        // Then
        verify(response, times(1))
                .sendError(HttpServletResponse.SC_UNAUTHORIZED, "Error: Unauthorized");
    }
}
