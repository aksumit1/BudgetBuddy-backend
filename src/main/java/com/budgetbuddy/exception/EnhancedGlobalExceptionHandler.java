package com.budgetbuddy.exception;

import com.budgetbuddy.api.response.ApiResponse;
import com.budgetbuddy.util.MessageUtil;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
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

/**
 * Enhanced Global Exception Handler Provides localized error messages and comprehensive error
 * handling
 *
 * <p>Features: - Localized error messages - Correlation ID tracking - Detailed error responses -
 * Validation error mapping - Proper HTTP status code mapping
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings({"PMD.DataClass", "PMD.OnlyOneReturn"})
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@RestControllerAdvice
public class EnhancedGlobalExceptionHandler {

    private static final String CORRELATION_ID = "correlationId";

    private static final String UNKNOWN = "unknown";
    private static final String URI = "uri=";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EnhancedGlobalExceptionHandler.class);

    private final MessageUtil messageUtil;

    public EnhancedGlobalExceptionHandler(final MessageUtil messageUtil) {
        this.messageUtil = messageUtil;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(
            final AppException ex, final WebRequest request) {
        final String correlationId = MDC.get(CORRELATION_ID);

        String localizedMessage = messageUtil.getErrorMessage(ex.getErrorCode().name());
        if (localizedMessage.equals(
                "error." + ex.getErrorCode().name().toLowerCase(Locale.ROOT).replace("_", "."))) {
            // Fallback to original message (sanitized).
            localizedMessage = sanitizeErrorMessage(ex.getMessage());
        } else {
            localizedMessage = sanitizeErrorMessage(localizedMessage);
        }

        // Cause info is logged server-side; not surfaced in the wire envelope.
        if (ex.getCause() != null && LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "AppException cause | CorrelationId: {} | cause={} message={}",
                    correlationId,
                    ex.getCause().getClass().getSimpleName(),
                    ex.getCause().getMessage());
        }

        final HttpStatus status = mapErrorCodeToHttpStatus(ex.getErrorCode());

        // Business logic errors (USER_ALREADY_EXISTS etc.) log at WARN; system errors at ERROR.
        if (isBusinessLogicError(ex.getErrorCode())) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Business logic error: {} - {} | CorrelationId: {}",
                        ex.getErrorCode(),
                        ex.getMessage(),
                        correlationId);
            }
        } else if (LOGGER.isErrorEnabled()) {
            LOGGER.error(
                    "Application error: {} - {} | CorrelationId: {}",
                    ex.getErrorCode(),
                    ex.getMessage(),
                    correlationId,
                    ex);
        }

        return respond(status, ex.getErrorCode().name(), localizedMessage, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(
            final HttpRequestMethodNotSupportedException ex, final WebRequest request) {
        // getSupportedHttpMethods() is @Nullable per Spring's API contract.
        final var supported = ex.getSupportedHttpMethods();
        final String supportedList =
                supported == null
                        ? "(unknown)"
                        : String.join(
                                ", ", supported.stream().map(method -> method.name()).toList());

        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "Method not supported: {} for path {} | CorrelationId: {}",
                    ex.getMethod(),
                    request.getDescription(false),
                    MDC.get(CORRELATION_ID));
        }

        return respond(
                HttpStatus.METHOD_NOT_ALLOWED,
                "METHOD_NOT_ALLOWED",
                "Request method '"
                        + ex.getMethod()
                        + "' is not supported for this endpoint. Supported methods: "
                        + supportedList,
                null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(
            final MethodArgumentNotValidException ex, final WebRequest request) {
        final Map<String, String> validationErrors = new HashMap<>();
        ex.getBindingResult()
                .getAllErrors()
                .forEach(
                        error -> {
                            // JDK 25: Enhanced pattern matching for instanceof
                            if (error instanceof FieldError fieldError) {
                                final String fieldName = fieldError.getField();
                                String errorMessage = messageUtil.getValidationMessage(fieldName);
                                final String validationKey =
                                        "validation."
                                                + fieldName
                                                        .toLowerCase(Locale.ROOT)
                                                        .replace("_", ".");
                                if (errorMessage != null && errorMessage.equals(validationKey)) {
                                    errorMessage = error.getDefaultMessage();
                                }
                                validationErrors.put(
                                        fieldName,
                                        errorMessage != null ? errorMessage : "Invalid value");
                            } else {
                                final String objectName = error.getObjectName();
                                final String errorMessage = error.getDefaultMessage();
                                validationErrors.put(
                                        objectName,
                                        errorMessage != null ? errorMessage : "Validation failed");
                            }
                        });

        LOGGER.warn("Validation error: {}", validationErrors);

        return respond(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                messageUtil.getValidationMessage("validation.failed"),
                validationErrors);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingServletRequestParameterException(
            final org.springframework.web.bind.MissingServletRequestParameterException ex,
            final WebRequest request) {
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "Missing request parameter: {} | CorrelationId: {}",
                    ex.getParameterName(),
                    MDC.get(CORRELATION_ID));
        }
        return respond(
                HttpStatus.BAD_REQUEST,
                "MISSING_REQUIRED_FIELD",
                "Required request parameter '" + ex.getParameterName() + "' is missing",
                Map.of(
                        ex.getParameterName(),
                        "Required parameter '" + ex.getParameterName() + "' is missing"));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            final org.springframework.http.converter.HttpMessageNotReadableException ex,
            final WebRequest request) {
        LOGGER.warn(
                "Invalid request body format | CorrelationId: {}", MDC.get(CORRELATION_ID));
        return respond(
                HttpStatus.BAD_REQUEST,
                "INVALID_INPUT",
                "Invalid request body format. Please check your JSON syntax.",
                null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupportedException(
            final HttpMediaTypeNotSupportedException ex, final WebRequest request) {
        // Store getContentType() once — double-call races a non-null
        // check vs a null deref (SpotBugs flagged this).
        final org.springframework.http.MediaType ct = ex.getContentType();
        final String contentType = ct != null ? ct.toString() : UNKNOWN;
        final String supportedTypes =
                ex.getSupportedMediaTypes() != null && !ex.getSupportedMediaTypes().isEmpty()
                        ? String.join(
                                ", ",
                                ex.getSupportedMediaTypes().stream()
                                        .map(mediaType -> mediaType.toString())
                                        .toList())
                        : "application/json";
        LOGGER.warn(
                "Unsupported media type: {} | Supported types: {} | CorrelationId: {}",
                contentType,
                supportedTypes,
                MDC.get(CORRELATION_ID));
        return respond(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type '"
                        + contentType
                        + "' is not supported. Supported types: "
                        + supportedTypes,
                null);
    }

    @ExceptionHandler(com.fasterxml.jackson.core.JsonParseException.class)
    public ResponseEntity<ApiResponse<Void>> handleJsonParseException(
            final com.fasterxml.jackson.core.JsonParseException ex, final WebRequest request) {
        LOGGER.warn("JSON parse error | CorrelationId: {}", MDC.get(CORRELATION_ID));
        return respond(
                HttpStatus.BAD_REQUEST,
                "INVALID_INPUT",
                "Invalid JSON format. Please check your request body.",
                null);
    }

    /**
     * Handles MultipartException - typically occurs when client aborts file upload This is a
     * client-side issue (network timeout, user cancellation, etc.), not a server error
     */
    @ExceptionHandler(org.springframework.web.multipart.MultipartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMultipartException(
            final org.springframework.web.multipart.MultipartException ex,
            final WebRequest request) {
        final String correlationId = MDC.get(CORRELATION_ID);

        final Throwable rootCause = ex.getRootCause();
        final boolean isClientAbort =
                rootCause instanceof org.apache.catalina.connector.ClientAbortException
                        || (rootCause != null
                                && rootCause.getCause()
                                        instanceof
                                        org.apache.catalina.connector.ClientAbortException)
                        || (rootCause != null && rootCause instanceof java.io.EOFException);

        final String errorCode = isClientAbort ? "CLIENT_ABORTED_REQUEST" : "FILE_UPLOAD_FAILED";
        final String message =
                isClientAbort
                        ? "File upload was interrupted. This may be due to network issues or the upload being cancelled."
                        : "File upload failed. Please check the file size and format, then try again.";

        // Client-side issue regardless of root cause — log WARN, not ERROR.
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "{} | CorrelationId: {} | Root cause: {}",
                    isClientAbort
                            ? "Client aborted file upload request"
                            : "Multipart file upload failed",
                    correlationId,
                    rootCause != null ? rootCause.getClass().getSimpleName() : UNKNOWN);
        }

        return respond(HttpStatus.BAD_REQUEST, errorCode, message, null);
    }

    /**
     * Handles ClientAbortException - client closed connection before request completed This is a
     * client-side issue (network timeout, user cancellation, etc.), not a server error
     */
    @ExceptionHandler(org.apache.catalina.connector.ClientAbortException.class)
    public ResponseEntity<ApiResponse<Void>> handleClientAbortException(
            final org.apache.catalina.connector.ClientAbortException ex, final WebRequest request) {
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "Client aborted request | CorrelationId: {} | Message: {}",
                    MDC.get(CORRELATION_ID),
                    ex.getMessage());
        }
        return respond(
                HttpStatus.BAD_REQUEST,
                "CLIENT_ABORTED_REQUEST",
                "Request was cancelled or connection was closed before completion. "
                        + "This may be due to network issues.",
                null);
    }

    /**
     * AWS SDK conditional-check failures (optimistic concurrency loss
     * on conditional writes) should surface as 409 CONFLICT, not 500.
     * The client likely needs to refresh and retry, not file a
     * server-error ticket.
     */
    @ExceptionHandler(
            software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException.class)
    public ResponseEntity<ApiResponse<Void>> handleConditionalCheckFailed(
            final software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException ex,
            final WebRequest request) {
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "DynamoDB conditional-check failed (likely concurrent write) | "
                            + "CorrelationId: {} | Message: {}",
                    MDC.get(CORRELATION_ID), ex.getMessage());
        }
        return respond(
                HttpStatus.CONFLICT,
                "CONFLICT",
                "The resource was modified by another request. Please retry.",
                null);
    }

    /**
     * AWS SDK ResourceNotFoundException — usually means a GSI is
     * missing or an entity was deleted between fetch and follow-up.
     * Map to 404 instead of 500.
     */
    @ExceptionHandler(
            software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleAwsResourceNotFound(
            final software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException ex,
            final WebRequest request) {
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "DynamoDB resource not found | CorrelationId: {} | Message: {}",
                    MDC.get(CORRELATION_ID), ex.getMessage());
        }
        return respond(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource was not found.", null);
    }

    /**
     * AWS SDK ProvisionedThroughputExceededException — DynamoDB is
     * throttling. Surface as 503 with a Retry-After header so clients
     * back off instead of retrying immediately.
     */
    @ExceptionHandler(
            software.amazon.awssdk.services.dynamodb.model
                    .ProvisionedThroughputExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleThroughputExceeded(
            final software.amazon.awssdk.services.dynamodb.model
                    .ProvisionedThroughputExceededException ex,
            final WebRequest request) {
        final String correlationId = MDC.get(CORRELATION_ID);
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "DynamoDB throttling | CorrelationId: {} | Message: {}",
                    correlationId, ex.getMessage());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(ApiResponse.error(
                        "SERVICE_UNAVAILABLE",
                        "The service is temporarily overloaded. Please retry shortly.",
                        correlationId));
    }

    /**
     * AWS SDK ValidationException — bad input to DynamoDB (illegal
     * key, attribute size, etc.). Almost always a backend bug, but
     * map to 400 so the iOS client doesn't display a generic 500.
     */
    @ExceptionHandler(
            software.amazon.awssdk.services.dynamodb.model.DynamoDbException.class)
    public ResponseEntity<ApiResponse<Void>> handleDynamoDbException(
            final software.amazon.awssdk.services.dynamodb.model.DynamoDbException ex,
            final WebRequest request) {
        final int sdkStatus = ex.statusCode();
        final HttpStatus status;
        if (sdkStatus == 400) {
            status = HttpStatus.BAD_REQUEST;
        } else if (sdkStatus >= 500 || sdkStatus == 0) {
            status = HttpStatus.BAD_GATEWAY;
        } else {
            status = HttpStatus.valueOf(sdkStatus);
        }
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "DynamoDB exception (sdkStatus={}) | CorrelationId: {} | Message: {}",
                    sdkStatus, MDC.get(CORRELATION_ID), ex.getMessage());
        }
        return respond(
                status,
                "DYNAMODB_ERROR",
                "Storage error: " + ex.awsErrorDetails().errorCode(),
                null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
            final Exception ex, final WebRequest request) {
        final String correlationId = MDC.get(CORRELATION_ID);

        // Delegate to specific handlers when applicable.
        if (ex instanceof HttpRequestMethodNotSupportedException methodEx) {
            return handleMethodNotSupportedException(methodEx, request);
        }
        if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            return handleHttpMessageNotReadableException(
                    (org.springframework.http.converter.HttpMessageNotReadableException) ex,
                    request);
        }
        if (ex instanceof com.fasterxml.jackson.core.JsonParseException) {
            return handleJsonParseException(
                    (com.fasterxml.jackson.core.JsonParseException) ex, request);
        }
        if (ex instanceof org.springframework.web.multipart.MultipartException) {
            return handleMultipartException(
                    (org.springframework.web.multipart.MultipartException) ex, request);
        }
        if (ex instanceof org.apache.catalina.connector.ClientAbortException) {
            return handleClientAbortException(
                    (org.apache.catalina.connector.ClientAbortException) ex, request);
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return handleHttpMediaTypeNotSupportedException(
                    (HttpMediaTypeNotSupportedException) ex, request);
        }

        String sanitizedMessage = messageUtil.getErrorMessage("internal.server.error");
        if (sanitizedMessage == null || sanitizedMessage.isEmpty()) {
            sanitizedMessage =
                    "An internal error occurred. Please contact support with correlation ID: "
                            + correlationId;
        }

        // Log full details internally; the wire body never exposes them.
        LOGGER.error("Unexpected error | CorrelationId: {}", correlationId, ex);

        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_SERVER_ERROR",
                sanitizedMessage,
                null);
    }

    /**
     * Single funnel for every error response — every handler ends here.
     * Reads {@code correlationId} from MDC and stamps it into the
     * envelope so iOS can quote it back in a support ticket.
     */
    private ResponseEntity<ApiResponse<Void>> respond(
            final HttpStatus status,
            final String code,
            final String message,
            @Nullable final Map<String, String> validationErrors) {
        final String correlationId = MDC.get(CORRELATION_ID);
        return ResponseEntity.status(status).body(
                ApiResponse.error(code, message, correlationId, validationErrors, null));
    }

    /** Sanitize error messages to prevent information leakage */
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

    private HttpStatus mapErrorCodeToHttpStatus(final ErrorCode errorCode) {
        return switch (errorCode) {
            case USER_NOT_FOUND,
                    TRANSACTION_NOT_FOUND,
                    ACCOUNT_NOT_FOUND,
                    BUDGET_NOT_FOUND,
                    GOAL_NOT_FOUND,
                    RECORD_NOT_FOUND ->
                    HttpStatus.NOT_FOUND;
            case INVALID_CREDENTIALS, UNAUTHORIZED_ACCESS -> HttpStatus.UNAUTHORIZED;
            case INSUFFICIENT_PERMISSIONS -> HttpStatus.FORBIDDEN;
            case USER_ALREADY_EXISTS,
                    EMAIL_ALREADY_REGISTERED,
                    INVALID_INPUT,
                    MISSING_REQUIRED_FIELD,
                    INVALID_FORMAT ->
                    HttpStatus.BAD_REQUEST;
            case RATE_LIMIT_EXCEEDED, PLAID_RATE_LIMIT_EXCEEDED, STRIPE_RATE_LIMIT_EXCEEDED ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            // Flow 4: O2 optimistic-lock collision → 409; O11 goal already closed → 422.
            case CONFLICT -> HttpStatus.CONFLICT;
            // Spring 7 renamed UNPROCESSABLE_ENTITY → UNPROCESSABLE_CONTENT
            // (RFC 9110 reason phrase). Same 422 status code, new symbol.
            case GOAL_ALREADY_COMPLETED -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    /**
     * Determines if an error code represents a business logic error (expected) vs system error
     * (unexpected) Business logic errors should be logged at WARN level, system errors at ERROR
     * level
     */
    private boolean isBusinessLogicError(final ErrorCode errorCode) {
        return switch (errorCode) {
            // Business logic errors - expected in normal operation
            case USER_ALREADY_EXISTS,
                    EMAIL_ALREADY_REGISTERED,
                    INVALID_CREDENTIALS,
                    INVALID_INPUT,
                    MISSING_REQUIRED_FIELD,
                    INVALID_FORMAT,
                    RECORD_ALREADY_EXISTS,
                    USER_NOT_FOUND,
                    RECORD_NOT_FOUND,
                    ACCOUNT_NOT_FOUND,
                    TRANSACTION_NOT_FOUND,
                    BUDGET_NOT_FOUND,
                    GOAL_NOT_FOUND,
                    INSUFFICIENT_BALANCE,
                    BUDGET_EXCEEDED,
                    TRANSACTION_LIMIT_EXCEEDED,
                    PASSWORD_TOO_WEAK,
                    INVALID_EMAIL_FORMAT,
                    RATE_LIMIT_EXCEEDED,
                    PLAID_RATE_LIMIT_EXCEEDED,
                    STRIPE_RATE_LIMIT_EXCEEDED ->
                    true;
            // System errors - unexpected, should be logged as ERROR
            default -> false;
        };
    }

}
