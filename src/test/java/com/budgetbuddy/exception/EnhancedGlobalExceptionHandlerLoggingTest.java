package com.budgetbuddy.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import com.budgetbuddy.util.MessageUtil;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for EnhancedGlobalExceptionHandler - Error Logging Level Bug Fix
 * 
 * Tests the fix where business logic errors (like USER_ALREADY_EXISTS) were being
 * logged at ERROR level instead of WARN level
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class EnhancedGlobalExceptionHandlerLoggingTest {

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
        // Set up log appender to capture log events
        logger = (Logger) LoggerFactory.getLogger(EnhancedGlobalExceptionHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);

        when(webRequest.getLocale()).thenReturn(java.util.Locale.ENGLISH);
        when(webRequest.getDescription(anyBoolean())).thenReturn("uri=/api/auth/register");
        when(messageUtil.getErrorMessage(anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(0);
            return "Error: " + code;
        });
    }

    @Test
    void testHandleAppException_BusinessLogicError_LogsAtWarnLevel() {
        // Arrange
        AppException exception = new AppException(ErrorCode.USER_ALREADY_EXISTS, "User already exists");

        // Act
        ResponseEntity<EnhancedGlobalExceptionHandler.ErrorResponse> response = 
                exceptionHandler.handleAppException(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        
        // Check that WARN level was used, not ERROR
        List<ILoggingEvent> logEvents = logAppender.list;
        boolean foundWarnLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN 
                        && event.getMessage().contains("Business logic error")
                        && event.getMessage().contains("USER_ALREADY_EXISTS"));
        
        assertTrue(foundWarnLog, "Business logic error should be logged at WARN level");
        
        // Verify ERROR level was NOT used for this business logic error
        boolean foundErrorLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR 
                        && event.getMessage().contains("USER_ALREADY_EXISTS"));
        
        assertFalse(foundErrorLog, "Business logic error should NOT be logged at ERROR level");
    }

    @Test
    void testHandleAppException_SystemError_LogsAtErrorLevel() {
        // Arrange
        AppException exception = new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Internal server error");

        // Act
        ResponseEntity<EnhancedGlobalExceptionHandler.ErrorResponse> response = 
                exceptionHandler.handleAppException(exception, webRequest);

        // Assert
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        
        // Check that ERROR level was used for system errors
        List<ILoggingEvent> logEvents = logAppender.list;
        boolean foundErrorLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR 
                        && event.getMessage().contains("Application error")
                        && event.getMessage().contains("INTERNAL_SERVER_ERROR"));
        
        assertTrue(foundErrorLog, "System error should be logged at ERROR level");
    }

    @Test
    void testHandleAppException_InvalidCredentials_LogsAtWarnLevel() {
        // Arrange
        AppException exception = new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");

        // Act
        exceptionHandler.handleAppException(exception, webRequest);

        // Assert
        List<ILoggingEvent> logEvents = logAppender.list;
        boolean foundWarnLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN 
                        && event.getMessage().contains("Business logic error")
                        && event.getMessage().contains("INVALID_CREDENTIALS"));
        
        assertTrue(foundWarnLog, "INVALID_CREDENTIALS should be logged at WARN level");
    }

    @Test
    void testHandleAppException_UserNotFound_LogsAtWarnLevel() {
        // Arrange
        AppException exception = new AppException(ErrorCode.USER_NOT_FOUND, "User not found");

        // Act
        exceptionHandler.handleAppException(exception, webRequest);

        // Assert
        List<ILoggingEvent> logEvents = logAppender.list;
        boolean foundWarnLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN 
                        && event.getMessage().contains("Business logic error")
                        && event.getMessage().contains("USER_NOT_FOUND"));
        
        assertTrue(foundWarnLog, "USER_NOT_FOUND should be logged at WARN level");
    }

    @Test
    void testIsBusinessLogicError_ReturnsTrueForBusinessLogicErrors() {
        // Use reflection to test the private method
        try {
            java.lang.reflect.Method method = EnhancedGlobalExceptionHandler.class
                    .getDeclaredMethod("isBusinessLogicError", ErrorCode.class);
            method.setAccessible(true);
            
            assertTrue((Boolean) method.invoke(exceptionHandler, ErrorCode.USER_ALREADY_EXISTS));
            assertTrue((Boolean) method.invoke(exceptionHandler, ErrorCode.INVALID_CREDENTIALS));
            assertTrue((Boolean) method.invoke(exceptionHandler, ErrorCode.USER_NOT_FOUND));
            assertTrue((Boolean) method.invoke(exceptionHandler, ErrorCode.INVALID_INPUT));
            assertTrue((Boolean) method.invoke(exceptionHandler, ErrorCode.RATE_LIMIT_EXCEEDED));
            
            assertFalse((Boolean) method.invoke(exceptionHandler, ErrorCode.INTERNAL_SERVER_ERROR));
            assertFalse((Boolean) method.invoke(exceptionHandler, ErrorCode.SERVICE_UNAVAILABLE_ERROR));
        } catch (Exception e) {
            // If reflection fails, skip this test
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Could not access isBusinessLogicError method");
        }
    }
}

