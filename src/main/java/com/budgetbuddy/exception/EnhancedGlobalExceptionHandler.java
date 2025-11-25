package com.budgetbuddy.exception;

import com.budgetbuddy.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Enhanced Global Exception Handler
 * Provides localized error messages and comprehensive error handling
 *
 * Features:
 * - Localized error messages
 * - Correlation ID tracking
 * - Detailed error responses
 * - Validation error mapping
 * - Proper HTTP status code mapping
 */
@RestControllerAdvice
public class EnhancedGlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(EnhancedGlobalExceptionHandler.class);

    private final MessageUtil messageUtil;

    public EnhancedGlobalExceptionHandler(final MessageUtil messageUtil) {
        this.messageUtil = messageUtil;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");
        Locale locale = request.getLocale();

        String localizedMessage = messageUtil.getErrorMessage(ex.getErrorCode().name());
        if (localizedMessage.equals("error." + ex.getErrorCode().name().toLowerCase().replace("_", "."))) {
            localizedMessage = sanitizeErrorMessage(ex.getMessage()); // Fallback to original message (sanitized)
        } else {
            localizedMessage = sanitizeErrorMessage(localizedMessage);
        }

        // Sanitize technical details to prevent information leakage
        Map<String, Object> technicalDetails = ex.getCause() != null
                ? Map.of("cause", ex.getCause().getClass().getSimpleName(), "message", ex.getCause().getMessage())
                : Map.of();
        Map<String, Object> sanitizedTechnicalDetails = sanitizeTechnicalDetails(technicalDetails);

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode(ex.getErrorCode().name())
                .message(localizedMessage)
                .technicalDetails(sanitizedTechnicalDetails)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        HttpStatus status = mapErrorCodeToHttpStatus(ex.getErrorCode());

        // Log full details internally, but return sanitized message
        logger.error("Application error: {} - {} | CorrelationId: {}",
                ex.getErrorCode(), ex.getMessage(), correlationId, ex);

        return ResponseEntity.status(status).body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");
        Locale locale = request.getLocale();

        Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            // JDK 25: Enhanced pattern matching for instanceof
            if (error instanceof FieldError fieldError) {
                String fieldName = fieldError.getField();
                String errorMessage = messageUtil.getValidationMessage(fieldName);
                String validationKey = "validation." 
                        + fieldName.toLowerCase().replace("_", ".");
                if (errorMessage.equals(validationKey)) {
                    errorMessage = error.getDefaultMessage();
                }
                validationErrors.put(fieldName, 
                        errorMessage != null ? errorMessage : "Invalid value");
            } else {
                String objectName = error.getObjectName();
                String errorMessage = error.getDefaultMessage();
                validationErrors.put(objectName, 
                        errorMessage != null ? errorMessage : "Validation failed");
            }
        });

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("VALIDATION_FAILED")
                .message(messageUtil.getValidationMessage("validation.failed"))
                .validationErrors(validationErrors)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        logger.warn("Validation error: {}", validationErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        // Sanitize error message - never expose internal details
        String sanitizedMessage = messageUtil.getErrorMessage("internal.server.error");
        if (sanitizedMessage == null || sanitizedMessage.isEmpty()) {
            sanitizedMessage = "An internal error occurred. Please contact support with correlation ID: " + correlationId;
        }

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("INTERNAL_SERVER_ERROR")
                .message(sanitizedMessage)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        // Log full details internally, but never expose to client
        logger.error("Unexpected error | CorrelationId: {}", correlationId, ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    /**
     * Sanitize error messages to prevent information leakage
     */
    private String sanitizeErrorMessage(final String message) {
        if (message == null) {
            return "An error occurred";
        }

        String sanitized = message;

        // Remove stack traces
        if (sanitized.contains("at ") && sanitized.contains("(")) {
            sanitized = sanitized.split("\n")[0]; // Keep only first line
        }

        // Remove file paths
        sanitized = sanitized.replaceAll("(/[^\\s]+/)+[^\\s]+\\.java:\\d+", "[file]");
        sanitized = sanitized.replaceAll("C:\\\\[^\\s]+", "[file]");

        // Remove internal package names (keep only public API)
        sanitized = sanitized.replaceAll("com\\.budgetbuddy\\.internal\\.[^\\s]+", "[internal]");

        // Remove SQL details
        sanitized = sanitized.replaceAll("SQL[^;]+;", "[SQL query]");

        // Remove connection strings
        sanitized = sanitized.replaceAll("jdbc:[^\\s]+", "[database connection]");

        return sanitized;
    }

    /**
     * Sanitize technical details map
     */
    private Map<String, Object> sanitizeTechnicalDetails(Map<String, Object> technicalDetails) {
        if (technicalDetails == null || technicalDetails.isEmpty()) {
            return null;
        }

        Map<String, Object> sanitized = new HashMap<>();
        technicalDetails.forEach((key, value) -> {
            // Only include safe technical details
            if (key != null && !key.toLowerCase().contains("password")
                    && !key.toLowerCase().contains("secret")
                    && !key.toLowerCase().contains("token")
                    && !key.toLowerCase().contains("key")) {
                sanitized.put(key, sanitizeErrorMessage(String.valueOf(value)));
            }
        });

        return sanitized.isEmpty() ? null : sanitized;
    }

    private HttpStatus mapErrorCodeToHttpStatus(final ErrorCode errorCode) {
        return switch (errorCode) {
            case USER_NOT_FOUND, TRANSACTION_NOT_FOUND, ACCOUNT_NOT_FOUND,
                 BUDGET_NOT_FOUND, GOAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_CREDENTIALS, UNAUTHORIZED, UNAUTHORIZED_ACCESS -> HttpStatus.UNAUTHORIZED;
            case INSUFFICIENT_PERMISSIONS -> HttpStatus.FORBIDDEN;
            case USER_ALREADY_EXISTS, INVALID_INPUT, MISSING_REQUIRED_FIELD, INVALID_FORMAT -> HttpStatus.BAD_REQUEST;
            case RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Enhanced Error Response DTO
     */
    public static class ErrorResponse {
        private String errorCode;
        private String message;
        private Map<String, Object> technicalDetails;
        private Map<String, String> validationErrors;
        private String correlationId;
        private Instant timestamp;
        private String path;

        // Builder pattern
        public static ErrorResponseBuilder builder() {
            return new ErrorResponseBuilder();
        }

        // Getters and setters
        public String getErrorCode() { return errorCode; }
        public void setErrorCode(final String errorCode) { this.errorCode = errorCode; }
        public String getMessage() { return message; }
        public void setMessage(final String message) { this.message = message; }
        public Map<String, Object> getTechnicalDetails() { return technicalDetails; }
        public void setTechnicalDetails(final Map<String, Object> technicalDetails) { this.technicalDetails = technicalDetails; }
        public Map<String, String> getValidationErrors() { return validationErrors; }
        public void setValidationErrors(final Map<String, String> validationErrors) { this.validationErrors = validationErrors; }
        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(final String correlationId) { this.correlationId = correlationId; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }
        public String getPath() { return path; }
        public void setPath(final String path) { this.path = path; }

        public static class ErrorResponseBuilder {
            private String errorCode;
            private String message;
            private Map<String, Object> technicalDetails;
            private Map<String, String> validationErrors;
            private String correlationId;
            private Instant timestamp;
            private String path;

            public ErrorResponseBuilder errorCode(final String errorCode) { this.errorCode = errorCode; return this; }
            public ErrorResponseBuilder message(final String message) { this.message = message; return this; }
            public ErrorResponseBuilder technicalDetails(final Map<String, Object> technicalDetails) { this.technicalDetails = technicalDetails; return this; }
            public ErrorResponseBuilder validationErrors(final Map<String, String> validationErrors) { this.validationErrors = validationErrors; return this; }
            public ErrorResponseBuilder correlationId(final String correlationId) { this.correlationId = correlationId; return this; }
            public ErrorResponseBuilder timestamp(final Instant timestamp) { this.timestamp = timestamp; return this; }
            public ErrorResponseBuilder path(final String path) { this.path = path; return this; }

            public ErrorResponse build() {
                ErrorResponse response = new ErrorResponse();
                response.setErrorCode(errorCode);
                response.setMessage(message);
                response.setTechnicalDetails(technicalDetails);
                response.setValidationErrors(validationErrors);
                response.setCorrelationId(correlationId);
                response.setTimestamp(timestamp);
                response.setPath(path);
                return response;
            }
        }
    }
}
