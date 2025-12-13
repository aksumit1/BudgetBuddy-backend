package com.budgetbuddy.exception;

import java.time.Instant;
import java.util.Map;

/**
 * Application Exception with comprehensive error context
 */
public class AppException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    private final ErrorCode errorCode;
    private final Instant timestamp;
    @SuppressWarnings("serial") // Map is serializable, but Object values might not be - acceptable for error context
    private final Map<String, Object> context;
    private final String userMessage;
    private final String technicalMessage;
    private final Throwable rootCause;

    public AppException(final ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null, null, null);
    }

    public AppException(final ErrorCode errorCode, final String message) {
        this(errorCode, message, null, null, null);
    }

    public AppException(final ErrorCode errorCode, final String message, final Throwable cause) {
        this(errorCode, message, null, null, cause);
    }

    public AppException(final ErrorCode errorCode, final String message, final Map<String, Object> context, final String userMessage, final Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.timestamp = Instant.now();
        this.context = context;
        this.userMessage = userMessage != null ? userMessage : errorCode.getMessage();
        this.technicalMessage = message;
        this.rootCause = cause;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getTechnicalMessage() {
        return technicalMessage;
    }

    public Throwable getRootCause() {
        return rootCause;
    }

    public int getHttpStatus() {
        // Map error codes to HTTP status codes
        int code = errorCode.getCode();
        if (code >= 1001 && code < 2000) {
            return 401; // Unauthorized
        } else if (code >= 2001 && code < 3000) {
            return 400; // Bad Request
        } else if (code >= 3001 && code < 5000) {
            return 502; // Bad Gateway (external service)
        } else if (code >= 5001 && code < 6000) {
            return 400; // Bad Request
        } else if (code >= 6001 && code < 7000) {
            return 503; // Service Unavailable
        } else if (code >= 7001 && code < 8000) {
            return 503; // Service Unavailable
        } else if (code >= 8001 && code < 9000) {
            return 403; // Forbidden
        } else if (code >= 9001 && code < 10000) {
            return 400; // Bad Request
        } else {
            return 500; // Internal Server Error
        }
    }
}

