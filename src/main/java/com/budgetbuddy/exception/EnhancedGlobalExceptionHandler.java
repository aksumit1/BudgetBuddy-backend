package com.budgetbuddy.exception;

import com.budgetbuddy.util.MessageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
        // Locale retrieved but not currently used - kept for potential future localization
        @SuppressWarnings("unused")
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

        // Log based on error severity - business logic errors (like USER_ALREADY_EXISTS) should be WARN, not ERROR
        if (isBusinessLogicError(ex.getErrorCode())) {
            logger.warn("Business logic error: {} - {} | CorrelationId: {}",
                    ex.getErrorCode(), ex.getMessage(), correlationId);
        } else {
            // System errors, unexpected errors should be logged as ERROR
            logger.error("Application error: {} - {} | CorrelationId: {}",
                    ex.getErrorCode(), ex.getMessage(), correlationId, ex);
        }

        return ResponseEntity.status(status).body(errorResponse);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("METHOD_NOT_ALLOWED")
                .message("Request method '" + ex.getMethod() + "' is not supported for this endpoint. Supported methods: " + 
                        String.join(", ", ex.getSupportedHttpMethods().stream()
                                .map(method -> method.name())
                                .toList()))
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        logger.warn("Method not supported: {} for path {} | CorrelationId: {}", 
                ex.getMethod(), request.getDescription(false), correlationId);

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorResponse);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");
        // Locale retrieved but not currently used - kept for potential future localization
        @SuppressWarnings("unused")
        Locale locale = request.getLocale();

        Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            // JDK 25: Enhanced pattern matching for instanceof
            if (error instanceof FieldError fieldError) {
                String fieldName = fieldError.getField();
                String errorMessage = messageUtil.getValidationMessage(fieldName);
                String validationKey = "validation." 
                        + fieldName.toLowerCase().replace("_", ".");
                // Check if errorMessage is null before calling equals()
                if (errorMessage != null && errorMessage.equals(validationKey)) {
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

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
            org.springframework.web.bind.MissingServletRequestParameterException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("MISSING_REQUIRED_FIELD")
                .message("Required request parameter '" + ex.getParameterName() + "' is missing")
                .validationErrors(Map.of(ex.getParameterName(), "Required parameter '" + ex.getParameterName() + "' is missing"))
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        logger.warn("Missing request parameter: {} | CorrelationId: {}", ex.getParameterName(), correlationId);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(
            org.springframework.http.converter.HttpMessageNotReadableException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("INVALID_INPUT")
                .message("Invalid request body format. Please check your JSON syntax.")
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        logger.warn("Invalid request body format | CorrelationId: {}", correlationId);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpMediaTypeNotSupportedException(
            HttpMediaTypeNotSupportedException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        String contentType = ex.getContentType() != null ? ex.getContentType().toString() : "unknown";
        String supportedTypes = ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()
                ? String.join(", ", ex.getSupportedMediaTypes().stream()
                        .map(mediaType -> mediaType.toString())
                        .toList())
                : "application/json";

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("UNSUPPORTED_MEDIA_TYPE")
                .message("Content-Type '" + contentType + "' is not supported. Supported types: " + supportedTypes)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        logger.warn("Unsupported media type: {} | Supported types: {} | CorrelationId: {}", 
                contentType, supportedTypes, correlationId);

        // Return 415 Unsupported Media Type (4xx client error, not 500 server error)
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(errorResponse);
    }

    @ExceptionHandler(com.fasterxml.jackson.core.JsonParseException.class)
    public ResponseEntity<ErrorResponse> handleJsonParseException(
            com.fasterxml.jackson.core.JsonParseException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("INVALID_INPUT")
                .message("Invalid JSON format. Please check your request body.")
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        logger.warn("JSON parse error | CorrelationId: {}", correlationId);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles MultipartException - typically occurs when client aborts file upload
     * This is a client-side issue (network timeout, user cancellation, etc.), not a server error
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipartException(
            org.springframework.web.multipart.MultipartException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        // Check if this is caused by a client abort
        Throwable rootCause = ex.getRootCause();
        boolean isClientAbort = rootCause instanceof org.apache.catalina.connector.ClientAbortException ||
                               (rootCause != null && rootCause.getCause() instanceof org.apache.catalina.connector.ClientAbortException) ||
                               (rootCause != null && rootCause instanceof java.io.EOFException);

        String errorCode = isClientAbort ? "CLIENT_ABORTED_REQUEST" : "FILE_UPLOAD_FAILED";
        String message = isClientAbort 
            ? "File upload was interrupted. This may be due to network issues or the upload being cancelled."
            : "File upload failed. Please check the file size and format, then try again.";

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode(errorCode)
                .message(message)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        // Log at WARN level since this is typically a client-side issue, not a server error
        if (isClientAbort) {
            logger.warn("Client aborted file upload request | CorrelationId: {} | Root cause: {}", 
                correlationId, rootCause != null ? rootCause.getClass().getSimpleName() : "unknown");
        } else {
            logger.warn("Multipart file upload failed | CorrelationId: {} | Root cause: {}", 
                correlationId, rootCause != null ? rootCause.getClass().getSimpleName() : "unknown");
        }

        // Return 400 Bad Request for client-side issues (not 500 Internal Server Error)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles ClientAbortException - client closed connection before request completed
     * This is a client-side issue (network timeout, user cancellation, etc.), not a server error
     */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public ResponseEntity<ErrorResponse> handleClientAbortException(
            org.apache.catalina.connector.ClientAbortException ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        ErrorResponse errorResponse = ErrorResponse.builder()
                .errorCode("CLIENT_ABORTED_REQUEST")
                .message("Request was cancelled or connection was closed before completion. This may be due to network issues.")
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(request.getDescription(false).replace("uri=", ""))
                .build();

        // Log at WARN level since this is a client-side issue, not a server error
        logger.warn("Client aborted request | CorrelationId: {} | Message: {}", 
            correlationId, ex.getMessage());

        // Return 400 Bad Request for client-side issues (not 500 Internal Server Error)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        String correlationId = MDC.get("correlationId");

        // Check if this is a method not supported exception that wasn't caught by the specific handler
        if (ex instanceof HttpRequestMethodNotSupportedException methodEx) {
            return handleMethodNotSupportedException(methodEx, request);
        }

        // Check if this is an HTTP message not readable exception (malformed JSON)
        if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return handleHttpMessageNotReadableException(
                    (org.springframework.http.converter.HttpMessageNotReadableException) ex, request);
        }

        // Check if this is a JSON parse exception
        if (ex instanceof com.fasterxml.jackson.core.JsonParseException) {
            return handleJsonParseException((com.fasterxml.jackson.core.JsonParseException) ex, request);
        }

        // Check if this is a multipart exception (client abort, etc.)
        if (ex instanceof org.springframework.web.multipart.MultipartException) {
            return handleMultipartException((org.springframework.web.multipart.MultipartException) ex, request);
        }

        // Check if this is a client abort exception
        if (ex instanceof org.apache.catalina.connector.ClientAbortException) {
            return handleClientAbortException((org.apache.catalina.connector.ClientAbortException) ex, request);
        }

        // Check if this is an unsupported media type exception
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return handleHttpMediaTypeNotSupportedException((HttpMediaTypeNotSupportedException) ex, request);
        }

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
                 BUDGET_NOT_FOUND, GOAL_NOT_FOUND, RECORD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_CREDENTIALS, UNAUTHORIZED_ACCESS -> HttpStatus.UNAUTHORIZED;
            case INSUFFICIENT_PERMISSIONS -> HttpStatus.FORBIDDEN;
            case USER_ALREADY_EXISTS, EMAIL_ALREADY_REGISTERED, INVALID_INPUT, MISSING_REQUIRED_FIELD, INVALID_FORMAT -> HttpStatus.BAD_REQUEST;
            case RATE_LIMIT_EXCEEDED, PLAID_RATE_LIMIT_EXCEEDED, STRIPE_RATE_LIMIT_EXCEEDED -> HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Determines if an error code represents a business logic error (expected) vs system error (unexpected)
     * Business logic errors should be logged at WARN level, system errors at ERROR level
     */
    private boolean isBusinessLogicError(final ErrorCode errorCode) {
        return switch (errorCode) {
            // Business logic errors - expected in normal operation
            case USER_ALREADY_EXISTS, EMAIL_ALREADY_REGISTERED, INVALID_CREDENTIALS, INVALID_INPUT,
                 MISSING_REQUIRED_FIELD, INVALID_FORMAT, RECORD_ALREADY_EXISTS, USER_NOT_FOUND,
                 RECORD_NOT_FOUND, ACCOUNT_NOT_FOUND, TRANSACTION_NOT_FOUND, BUDGET_NOT_FOUND,
                 GOAL_NOT_FOUND, INSUFFICIENT_BALANCE, BUDGET_EXCEEDED, TRANSACTION_LIMIT_EXCEEDED,
                 PASSWORD_TOO_WEAK, INVALID_EMAIL_FORMAT, RATE_LIMIT_EXCEEDED, PLAID_RATE_LIMIT_EXCEEDED, STRIPE_RATE_LIMIT_EXCEEDED -> true;
            // System errors - unexpected, should be logged as ERROR
            default -> false;
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
