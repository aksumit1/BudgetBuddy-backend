package com.budgetbuddy.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.budgetbuddy.util.MessageUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.util.List;
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
    
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        when(webRequest.getLocale()).thenReturn(Locale.US);
        when(webRequest.getDescription(anyBoolean())).thenReturn("uri=/api/test");
        when(messageUtil.getErrorMessage(anyString())).thenReturn("Test error message");
        when(messageUtil.getValidationMessage(anyString())).thenReturn("Validation error");
        
        // Set up log appender to capture log events for verification
        logger = (Logger) LoggerFactory.getLogger(EnhancedGlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
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
    void testHandleValidationException_WithNullErrorMessage_HandlesGracefully() {
        // Given - Test the fix for NullPointerException when getValidationMessage returns null
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        org.springframework.validation.BindingResult bindingResult = mock(org.springframework.validation.BindingResult.class);
        FieldError fieldError = new FieldError("passwordResetRequest", "code", null, false, 
                new String[]{"NotBlank.passwordResetRequest.code", "NotBlank.code"}, null, "Verification code is required");

        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getAllErrors()).thenReturn(java.util.Collections.singletonList(fieldError));
        // Mock getValidationMessage to return null (simulating the bug scenario)
        when(messageUtil.getValidationMessage("code")).thenReturn(null);

        // When - Should not throw NullPointerException
        ResponseEntity<EnhancedGlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleValidationException(ex, webRequest);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getValidationErrors());
        // Should use fallback "Invalid value" when errorMessage is null
        assertEquals("Invalid value", response.getBody().getValidationErrors().get("code"));
    }

    @Test
    void testHandleGenericException_WithRuntimeException_ReturnsErrorResponse() {
        // Given
        // Note: This test intentionally throws a RuntimeException to verify that unexpected exceptions
        // are logged at ERROR level. The ERROR log is EXPECTED and CORRECT.
        RuntimeException ex = new RuntimeException("Unexpected error");
        org.slf4j.MDC.put("correlationId", "test-correlation-id");

        // When
        ResponseEntity<EnhancedGlobalExceptionHandler.ErrorResponse> response = exceptionHandler.handleGenericException(ex, webRequest);

        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Verify logging behavior - should log ERROR for unexpected exceptions
        List<ILoggingEvent> logEvents = logAppender.list;
        long errorLogs = logEvents.stream()
                .filter(event -> event.getLevel() == Level.ERROR 
                        && event.getMessage().contains("Unexpected error"))
                .count();
        
        assertEquals(1, errorLogs, "Should log ERROR when unexpected exception occurs");
        
        // Verify ERROR log contains expected message and correlation ID
        // Use getFormattedMessage() to get the actual formatted message, not the template
        boolean foundErrorLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR 
                        && event.getFormattedMessage().contains("Unexpected error")
                        && event.getFormattedMessage().contains("test-correlation-id"));
        assertTrue(foundErrorLog, "Should log ERROR with exception message and correlation ID");
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

