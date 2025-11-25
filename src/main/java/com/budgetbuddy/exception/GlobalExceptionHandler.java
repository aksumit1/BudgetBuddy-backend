package com.budgetbuddy.exception;

import com.budgetbuddy.compliance.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Global Exception Handler
 * Provides comprehensive error handling and user-friendly error responses
 *
 * Features:
 * - Centralized exception handling
 * - Audit logging
 * - User-friendly error messages
 * - Proper HTTP status codes
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AuditLogService auditLogService;

    public GlobalExceptionHandler(final AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * Handle application-specific exceptions
     */
    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleAppException(AppException ex, WebRequest request) {
        logger.error("Application error: {} - {}", ex.getErrorCode(), ex.getTechnicalMessage(), ex);

        // Log to audit trail
        try {
            auditLogService.logAction(
                    getUserIdFromRequest(request),
                    "ERROR",
                    "EXCEPTION",
                    ex.getErrorCode().name(),
                    Map.of(
                            "errorCode", ex.getErrorCode().getCode(),
                            "message", ex.getTechnicalMessage(),
                            "context", ex.getContext() != null ? ex.getContext() : Map.of()
                    ),
                    getClientIp(request),
                    request.getHeader("User-Agent")
            );
        } catch (Exception e) {
            logger.warn("Failed to log to audit trail: {}", e.getMessage());
        }

        ErrorResponse errorResponse = new ErrorResponse(
                ex.getErrorCode(),
                ex.getUserMessage(),
                ex.getTechnicalMessage(),
                ex.getTimestamp(),
                ex.getContext(),
                request.getDescription(false),
                getCorrelationId(request)
        );

        return ResponseEntity.status(ex.getHttpStatus()).body(errorResponse);
    }

    /**
     * Handle validation errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            // JDK 25: Enhanced pattern matching for instanceof
            if (error instanceof FieldError fieldError) {
                String fieldName = fieldError.getField();
                String errorMessage = error.getDefaultMessage();
                errors.put(fieldName, errorMessage != null ? errorMessage : "Invalid value");
            } else {
                String objectName = error.getObjectName();
                String errorMessage = error.getDefaultMessage();
                errors.put(objectName, errorMessage != null ? errorMessage : "Validation failed");
            }
        });

        logger.warn("Validation error: {}", errors);

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCode.INVALID_INPUT,
                "Validation failed",
                "Invalid input data",
                Instant.now(),
                Map.of("validationErrors", errors),
                request.getDescription(false),
                getCorrelationId(request)
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handle authentication errors
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentialsException(
            BadCredentialsException ex, WebRequest request) {
        logger.warn("Authentication failed: {}", ex.getMessage());

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCode.AUTHENTICATION_FAILED,
                "Invalid credentials",
                "Authentication failed",
                Instant.now(),
                null,
                request.getDescription(false),
                getCorrelationId(request)
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    /**
     * Handle authorization errors
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {
        logger.warn("Access denied: {}", ex.getMessage());

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCode.UNAUTHORIZED_ACCESS,
                "Access denied",
                "Insufficient permissions",
                Instant.now(),
                null,
                request.getDescription(false),
                getCorrelationId(request)
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Handle all other exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        logger.error("Unexpected error: {}", ex.getMessage(), ex);

        // Log to audit trail
        try {
            auditLogService.logAction(
                    getUserIdFromRequest(request),
                    "ERROR",
                    "EXCEPTION",
                    "UNKNOWN",
                    Map.of(
                            "exceptionType", ex.getClass().getName(),
                            "message", ex.getMessage() != null ? ex.getMessage() : "Unknown error"
                    ),
                    getClientIp(request),
                    request.getHeader("User-Agent")
            );
        } catch (Exception e) {
            logger.warn("Failed to log to audit trail: {}", e.getMessage());
        }

        ErrorResponse errorResponse = new ErrorResponse(
                ErrorCode.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                "Internal server error",
                Instant.now(),
                null,
                request.getDescription(false),
                getCorrelationId(request)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }

    /**
     * Extract user ID from request
     */
    private String getUserIdFromRequest(final WebRequest request) {
        // Extract user ID from request (e.g., from JWT token in SecurityContext)
        // This is a simplified version - in production, extract from SecurityContext
        try {
            // TODO: Extract from SecurityContext.getContext().getAuthentication()
            return "UNKNOWN";
        } catch (Exception e) {
            logger.debug("Failed to extract user ID from request: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * Extract client IP from request
     */
    private String getClientIp(final WebRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Client-IP");
        }
        return ip != null && !ip.isEmpty() ? ip.split(",")[0].trim() : "unknown";
    }

    /**
     * Extract correlation ID from request
     */
    private String getCorrelationId(final WebRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");
        return correlationId != null && !correlationId.isEmpty() ? correlationId : null;
    }

    /**
     * Error Response DTO
     */
    public static class ErrorResponse {
        private final ErrorCode errorCode;
        private final String userMessage;
        private final String technicalMessage;
        private final Instant timestamp;
        private final Map<String, Object> context;
        private final String path;
        private final String requestId;
        private final String correlationId;

        public ErrorResponse(final ErrorCode errorCode, final String userMessage, final String technicalMessage, final Instant timestamp, final Map<String, Object> context, final String path, final String correlationId) {
            this.errorCode = errorCode;
            this.userMessage = userMessage;
            this.technicalMessage = technicalMessage;
            this.timestamp = timestamp;
            this.context = context;
            this.path = path != null ? path.replace("uri=", "") : null;
            this.requestId = java.util.UUID.randomUUID().toString();
            this.correlationId = correlationId;
        }

        // Getters
        public ErrorCode getErrorCode() { return errorCode; }
        public String getUserMessage() { return userMessage; }
        public String getTechnicalMessage() { return technicalMessage; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, Object> getContext() { return context; }
        public String getPath() { return path; }
        public String getRequestId() { return requestId; }
        public String getCorrelationId() { return correlationId; }
    }
}
