package com.budgetbuddy.exception;

import com.budgetbuddy.util.MessageUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for EnhancedGlobalExceptionHandler
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class EnhancedGlobalExceptionHandlerTest {

    @Mock
    private MessageUtil messageUtil;

    @Mock
    private WebRequest webRequest;

    @InjectMocks
    private EnhancedGlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        when(webRequest.getLocale()).thenReturn(Locale.US);
        when(webRequest.getDescription(anyBoolean())).thenReturn("uri=/api/test");
        when(messageUtil.getErrorMessage(anyString())).thenReturn("Test error message");
        when(messageUtil.getValidationMessage(anyString())).thenReturn("Validation error");
    }

    @Test
    void testHandleAppException_WithValidException_ReturnsErrorResponse() {
        // Given
        AppException ex = new AppException(ErrorCode.USER_NOT_FOUND, "User not found");
        org.slf4j.MDC.put("correlationId", "test-correlation-id");

        // When
        ResponseEntity<EnhancedGlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleAppException(ex, webRequest);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("USER_NOT_FOUND", response.getBody().getErrorCode());
    }

    @Test
    void testHandleValidationException_WithFieldErrors_ReturnsValidationErrors() {
        // Given
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        org.springframework.validation.BindingResult bindingResult = mock(org.springframework.validation.BindingResult.class);
        FieldError fieldError = new FieldError("user", "email", "Invalid email");

        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getAllErrors()).thenReturn(java.util.Collections.singletonList(fieldError));

        // When
        ResponseEntity<EnhancedGlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleValidationException(ex, webRequest);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testHandleGenericException_WithRuntimeException_ReturnsErrorResponse() {
        // Given
        RuntimeException ex = new RuntimeException("Unexpected error");

        // When
        ResponseEntity<EnhancedGlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleGenericException(ex, webRequest);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testSanitizeErrorMessage_RemovesSensitiveInfo() {
        // Given
        String errorMessage = "Error connecting to database: jdbc:postgresql://localhost:5432/budgetbuddy?user=admin&password=secret123";

        // When
        String sanitized = (String) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(exceptionHandler, "sanitizeErrorMessage", errorMessage);

        // Then
        assertNotNull(sanitized);
        assertFalse(sanitized.contains("password=secret123"));
        assertFalse(sanitized.contains("jdbc:postgresql"));
    }

    @Test
    void testMapErrorCodeToHttpStatus_WithVariousCodes_ReturnsCorrectStatus() {
        // When/Then
        HttpStatus status1 = (HttpStatus) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(exceptionHandler, "mapErrorCodeToHttpStatus", ErrorCode.USER_NOT_FOUND);
        assertEquals(HttpStatus.NOT_FOUND, status1);

        HttpStatus status2 = (HttpStatus) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(exceptionHandler, "mapErrorCodeToHttpStatus", ErrorCode.INVALID_CREDENTIALS);
        assertEquals(HttpStatus.UNAUTHORIZED, status2);

        HttpStatus status3 = (HttpStatus) org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(exceptionHandler, "mapErrorCodeToHttpStatus", ErrorCode.RATE_LIMIT_EXCEEDED);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, status3);
    }
}

