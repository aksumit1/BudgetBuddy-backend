package com.budgetbuddy.exception;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.AWSTestConfiguration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.WebRequest;

/**
 * Integration Tests for MissingServletRequestParameterException handling Verifies that missing
 * required parameters return proper 400 Bad Request instead of 500 Internal Server Error
 */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class MissingServletRequestParameterExceptionTest {

    @Autowired private EnhancedGlobalExceptionHandler exceptionHandler;

    @Test
    void testMissingServletRequestParameterExceptionReturns400BadRequest() throws Exception {
        // Given - An endpoint that requires a parameter
        // When - Request is made without the required parameter
        // Then - Should return 400 Bad Request with proper error message

        // Note: /api/plaid/accounts requires authentication, so it returns 401
        // Instead, test the exception handler directly
        final MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("testParam", "String");

        final WebRequest webRequest = mock(WebRequest.class);
        when(webRequest.getLocale()).thenReturn(java.util.Locale.ENGLISH);
        when(webRequest.getDescription(anyBoolean())).thenReturn("uri=/api/test");

        // The handler should return 400 Bad Request
        final org.springframework.http.ResponseEntity<?> response =
                exceptionHandler.handleMissingServletRequestParameterException(ex, webRequest);

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testExceptionHandlerHandlesMissingParameter() {
        // Given
        final MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("accessToken", "String");

        // When/Then - Handler should not throw exception
        assertDoesNotThrow(
                () -> {
                    // Create a mock WebRequest
                    final WebRequest webRequest = mock(WebRequest.class);
                    when(webRequest.getLocale()).thenReturn(java.util.Locale.ENGLISH);
                    when(webRequest.getDescription(anyBoolean())).thenReturn("uri=/api/test");

                    // The handler should process the exception and return proper response
                    exceptionHandler.handleMissingServletRequestParameterException(ex, webRequest);
                });
    }
}
