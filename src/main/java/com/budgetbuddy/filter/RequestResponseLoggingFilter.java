package com.budgetbuddy.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Request/Response Logging Filter Logs request and response details with sanitization
 *
 * <p>Features: - Correlation ID generation - Request/response body logging (sanitized) - Headers
 * logging (sanitized) - Performance metrics
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final String A_1_REDACTED_3 = "$1[REDACTED]$3";

    private static final String EMPTY = "[empty]";

    private static final String FIELDS = "fields: ";

    private static final String MULTIPART_FORM_DATA = "multipart/form-data";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final Logger REQUEST_LOGGER = LoggerFactory.getLogger("REQUEST_LOGGER");

    // Constructor to log filter initialization
    public RequestResponseLoggingFilter() {
        LOGGER.info("✅ RequestResponseLoggingFilter initialized and registered");
        REQUEST_LOGGER.info("✅ REQUEST_LOGGER initialized");
    }

    // Patterns for sensitive data sanitization
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("(?i)(\"password\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile(
                    "(?i)(\"(?:token|access_token|refresh_token|authorization)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern SECRET_PATTERN =
            Pattern.compile(
                    "(?i)(\"(?:secret|api_key|apikey|private_key)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final Pattern CARD_PATTERN =
            Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b");

    // Headers to exclude from logging
    private static final List<String> SENSITIVE_HEADERS =
            Arrays.asList("authorization", "x-api-key", "x-auth-token", "cookie", "set-cookie");

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain)
            throws ServletException, IOException {

        final String uri = request.getRequestURI();
        final String method = request.getMethod();

        // Generate correlation ID if not present
        String correlationId = request.getHeader("X-Request-ID");
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Request-ID", correlationId);

        // Wrap request and response for body reading. The 1-arg constructor
        // is deprecated for removal in Spring 7 — use the explicit cache-size
        // form. 256 KiB matches Spring's prior default; we don't ingest larger
        // request bodies on any logged endpoint.
        final ContentCachingRequestWrapper wrappedRequest =
                new ContentCachingRequestWrapper(request, 256 * 1024);
        final ContentCachingResponseWrapper wrappedResponse =
                new ContentCachingResponseWrapper(response);

        final long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } catch (Exception e) {
            // Log exception but don't fail the request
            LOGGER.error(
                    "Exception in filter chain for request [{}] {} {}: {}",
                    correlationId,
                    method,
                    uri,
                    e.getMessage(),
                    e);
            throw e;
        } finally {
            final long duration = System.currentTimeMillis() - startTime;

            try {
                // Log request - ALWAYS log, even if body is empty
                // This is critical - we must log every request
                logRequest(wrappedRequest, correlationId);
            } catch (Exception e) {
                // Log error but don't fail the request
                LOGGER.error(
                        "❌ Error logging request [{}] {} {}: {}",
                        correlationId,
                        method,
                        uri,
                        e.getMessage(),
                        e);
                e.printStackTrace(); // Print stack trace to help debug
                // Still try to log basic info - this ensures we ALWAYS log something
                try {
                    final String queryString = request.getQueryString();
                    final String fullUri = queryString != null ? uri + "?" + queryString : uri;
                    final String reqContentType = request.getContentType();
                    REQUEST_LOGGER.info(
                            "REQUEST [{}] {} {} | ContentType: {} | [ERROR IN DETAILED LOGGING: {}]",
                            correlationId,
                            method,
                            fullUri,
                            reqContentType,
                            e.getMessage());
                    LOGGER.info(
                            "REQUEST [{}] {} {} | ContentType: {} | [ERROR IN DETAILED LOGGING: {}]",
                            correlationId,
                            method,
                            fullUri,
                            reqContentType,
                            e.getMessage());
                } catch (Exception e2) {
                    LOGGER.error(
                            "❌ Failed to log even basic request info [{}]: {}",
                            correlationId,
                            e2.getMessage(),
                            e2);
                    e2.printStackTrace();
                }
            }

            try {
                // Log response
                logResponse(wrappedResponse, correlationId, duration);
            } catch (Exception e) {
                // Log error but don't fail the request
                LOGGER.error("Error logging response [{}]: {}", correlationId, e.getMessage(), e);
            }

            try {
                // Copy response body back to original response
                wrappedResponse.copyBodyToResponse();
            } catch (Exception e) {
                // Handle client disconnection gracefully - this is expected behavior
                // when clients navigate away, timeout, or cancel requests
                if (isClientAbortException(e)) {
                    // Log at debug level since this is expected behavior
                    LOGGER.debug(
                            "Client disconnected before response could be copied [{}]: {}",
                            correlationId,
                            e.getMessage());
                } else {
                    // Log other errors at error level
                    LOGGER.error(
                            "Error copying response body [{}]: {}",
                            correlationId,
                            e.getMessage(),
                            e);
                }
            }
        }
    }

    private void logRequest(
            final ContentCachingRequestWrapper request, final String correlationId) {
        try {
            final String method = request.getMethod();
            final String uri = request.getRequestURI();
            final String queryString = request.getQueryString();
            final String fullUri = queryString != null ? uri + "?" + queryString : uri;

            // Log headers (sanitized)
            final StringBuilder headersLog = new StringBuilder();
            Collections.list(request.getHeaderNames())
                    .forEach(
                            headerName -> {
                                if (!SENSITIVE_HEADERS.contains(
                                        headerName.toLowerCase(Locale.ROOT))) {
                                    headersLog
                                            .append(headerName)
                                            .append(": ")
                                            .append(request.getHeader(headerName))
                                            .append("; ");
                                } else {
                                    headersLog.append(headerName).append(": [REDACTED]; ");
                                }
                            });

            // Log query parameters (especially important for file uploads with filename parameter)
            final StringBuilder queryParamsLog = new StringBuilder();
            if (queryString != null && !queryString.isEmpty()) {
                queryParamsLog.append("QueryParams: ");
                final String[] params = queryString.split("&");
                for (final String param : params) {
                    final String[] keyValue = param.split("=", 2);
                    if (keyValue.length == 2) {
                        final String key = keyValue[0];
                        String value =
                                java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                        // Sanitize password in query params
                        if (key.toLowerCase(Locale.ROOT).contains("password")) {
                            value = "[REDACTED]";
                        }
                        queryParamsLog.append(key).append('=').append(value).append("; ");
                    }
                }
            }

            // Log request parameters (form data, including multipart)
            final StringBuilder requestParamsLog = new StringBuilder();
            try {
                // Get all parameter names
                final java.util.Enumeration<String> paramNames = request.getParameterNames();
                if (paramNames.hasMoreElements()) {
                    requestParamsLog.append("RequestParams: ");
                    while (paramNames.hasMoreElements()) {
                        final String paramName = paramNames.nextElement();
                        final String[] paramValues = request.getParameterValues(paramName);
                        if (paramValues != null && paramValues.length > 0) {
                            // Sanitize password parameters
                            if (paramName.toLowerCase(Locale.ROOT).contains("password")) {
                                requestParamsLog.append(paramName).append("=[REDACTED]; ");
                            } else {
                                // For other params, log first value (or all if multiple)
                                String value = paramValues[0];
                                if (value.length() > 100) {
                                    value = value.substring(0, 100) + "... [truncated]";
                                }
                                requestParamsLog
                                        .append(paramName)
                                        .append('=')
                                        .append(value)
                                        .append("; ");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore - parameters might not be available yet
            }

            // Handle multipart requests specially
            final String contentType = request.getContentType();
            String body = EMPTY;

            // Try to get multipart information if available
            if (contentType != null && contentType.startsWith(MULTIPART_FORM_DATA)) {
                body = getMultipartRequestInfo(request);
                // If multipart info extraction failed, at least log that it's multipart
                if (body == null || body.isEmpty() || EMPTY.equals(body)) {
                    body =
                            "[multipart/form-data - body not readable in filter (consumed by Spring multipart resolver)]";
                }
            } else {
                // For non-multipart, try to get body
                final byte[] bodyBytes = request.getContentAsByteArray();
                if (bodyBytes.length > 0) {
                    body = getSanitizedBody(bodyBytes, contentType);
                }
            }

            // Build complete log message - ALWAYS log, even if body is empty
            final StringBuilder logMessage =
                    new StringBuilder("REQUEST [")
                            .append(correlationId)
                            .append("] ")
                            .append(method)
                            .append(' ')
                            .append(fullUri);

            if (queryParamsLog.length() > 0) {
                logMessage.append(" | ").append(queryParamsLog.toString());
            }

            if (requestParamsLog.length() > 0) {
                logMessage.append(" | ").append(requestParamsLog.toString());
            }

            logMessage
                    .append(" | Headers: ")
                    .append(headersLog.toString())
                    .append(" | Body: ")
                    .append(body);

            // For multipart requests, add a note if body is empty (expected behavior)
            if (contentType != null && contentType.startsWith(MULTIPART_FORM_DATA)) {
                if (body == null || body.isEmpty() || EMPTY.equals(body)) {
                    logMessage.append(
                            " [NOTE: Multipart body consumed by Spring multipart resolver - see controller logs for file details]");
                }
                // Always log multipart requests at INFO level for visibility
                LOGGER.info(
                        "📤 Multipart request detected: {} {} | ContentType: {}",
                        method,
                        fullUri,
                        contentType);
            }

            // CRITICAL: Always log - use info level to ensure it's visible
            // Use both REQUEST_LOGGER and main LOGGER to ensure visibility
            final String logMsg = logMessage.toString();

            // Always log to REQUEST_LOGGER
            try {
                REQUEST_LOGGER.info(logMsg);
            } catch (Exception e) {
                LOGGER.error("❌ Failed to log to REQUEST_LOGGER: {}", e.getMessage(), e);
            }

            // Also log to main LOGGER for multipart requests and import endpoints to ensure
            // visibility
            if (contentType != null && contentType.startsWith(MULTIPART_FORM_DATA)) {
                try {
                    LOGGER.info("📤 MULTIPART REQUEST: {}", logMsg);
                } catch (Exception e) {
                    LOGGER.error(
                            "❌ Failed to log multipart request to main LOGGER: {}",
                            e.getMessage(),
                            e);
                }
            }

            // Also log import endpoints to main LOGGER
            if (uri != null && (uri.contains("/import") || uri.contains("/batch-import"))) {
                try {
                    LOGGER.info("📤 IMPORT REQUEST: {}", logMsg);
                } catch (Exception e) {
                    LOGGER.error(
                            "❌ Failed to log import request to main LOGGER: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error logging request: {}", e.getMessage(), e);
        }
    }

    /** Extract multipart request information Tries multiple methods to get multipart data */
    private String getMultipartRequestInfo(final HttpServletRequest request) {
        final StringBuilder info = new StringBuilder("[multipart/form-data] ");

        try {
            // Method 1: Try to access as MultipartHttpServletRequest (if already parsed by Spring)
            if (request instanceof MultipartHttpServletRequest) {
                final MultipartHttpServletRequest multipartRequest =
                        (MultipartHttpServletRequest) request;

                // Get all file parts
                final java.util.Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
                for (final java.util.Map.Entry<String, MultipartFile> entry : fileMap.entrySet()) {
                    final MultipartFile file = entry.getValue();
                    if (file != null && !file.isEmpty()) {
                        final String originalFilename = file.getOriginalFilename();
                        if (originalFilename != null) {
                            info.append("file: ")
                                    .append(entry.getKey())
                                    .append(" (filename: ")
                                    .append(originalFilename)
                                    .append(", size: ")
                                    .append(file.getSize())
                                    .append(" bytes); ");
                        } else {
                            info.append("file: ")
                                    .append(entry.getKey())
                                    .append(" (size: ")
                                    .append(file.getSize())
                                    .append(" bytes); ");
                        }
                    }
                }

                // Get all parameter names (non-file form fields)
                final java.util.Map<String, String[]> parameterMap =
                        multipartRequest.getParameterMap();
                final java.util.Set<String> paramNames = new java.util.HashSet<>();
                for (final String paramName : parameterMap.keySet()) {
                    if (!fileMap.containsKey(paramName)) {
                        paramNames.add(paramName);
                    }
                }
                if (!paramNames.isEmpty()) {
                    info.append(FIELDS).append(String.join(", ", paramNames)).append("; ");
                }

                return info.toString();
            }
        } catch (Exception e) {
            // Not a MultipartHttpServletRequest or not yet parsed
        }

        try {
            // Method 2: Try to read from cached body (might be empty for multipart)
            byte[] bodyBytes = null;
            if (request instanceof ContentCachingRequestWrapper) {
                bodyBytes = ((ContentCachingRequestWrapper) request).getContentAsByteArray();
            }

            if (bodyBytes != null && bodyBytes.length > 0) {
                // Try to extract metadata from raw multipart body
                final String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);

                // Extract filename from Content-Disposition headers
                final Pattern filenamePattern =
                        Pattern.compile(
                                "Content-Disposition:.*filename[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?",
                                Pattern.CASE_INSENSITIVE);
                final java.util.regex.Matcher matcher = filenamePattern.matcher(bodyStr);
                if (matcher.find()) {
                    info.append("filename: ").append(matcher.group(1)).append("; ");
                }

                // Extract form field names
                final Pattern fieldPattern =
                        Pattern.compile(
                                "Content-Disposition:.*name[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?",
                                Pattern.CASE_INSENSITIVE);
                final java.util.regex.Matcher fieldMatcher = fieldPattern.matcher(bodyStr);
                final java.util.Set<String> fields = new java.util.HashSet<>();
                while (fieldMatcher.find()) {
                    final String fieldName = fieldMatcher.group(1);
                    if (!"file".equals(fieldName)) {
                        fields.add(fieldName);
                    }
                }
                if (!fields.isEmpty()) {
                    info.append(FIELDS).append(String.join(", ", fields)).append("; ");
                }

                info.append("size: ").append(bodyBytes.length).append(" bytes");
                return info.toString();
            }
        } catch (Exception e) {
            // Can't read body
        }

        // Fallback: just indicate it's multipart
        info.append("(multipart body not readable in filter - check controller logs)");
        return info.toString();
    }

    private void logResponse(
            final ContentCachingResponseWrapper response,
            final String correlationId,
            final long duration) {
        try {
            final int status = response.getStatus();

            // Log headers (sanitized)
            final StringBuilder headersLog = new StringBuilder();
            response.getHeaderNames()
                    .forEach(
                            headerName -> {
                                if (!SENSITIVE_HEADERS.contains(
                                        headerName.toLowerCase(Locale.ROOT))) {
                                    headersLog
                                            .append(headerName)
                                            .append(": ")
                                            .append(response.getHeader(headerName))
                                            .append("; ");
                                } else {
                                    headersLog.append(headerName).append(": [REDACTED]; ");
                                }
                            });

            // Log body (sanitized)
            final String body = getSanitizedBody(response.getContentAsByteArray());

            REQUEST_LOGGER.info(
                    "RESPONSE [{}] Status: {} | Duration: {}ms | Headers: {} | Body: {}",
                    correlationId,
                    status,
                    duration,
                    headersLog.toString(),
                    body);
        } catch (Exception e) {
            LOGGER.warn("Error logging response: {}", e.getMessage());
        }
    }

    private String getSanitizedBody(final byte[] bodyBytes, final String contentType) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "[empty]";
        }

        try {
            // Handle multipart/form-data specially (file uploads)
            if (contentType != null && contentType.startsWith(MULTIPART_FORM_DATA)) {
                // Extract metadata from multipart body without logging binary data
                final String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);

                // Extract filename from Content-Disposition headers
                final StringBuilder metadata = new StringBuilder("[multipart/form-data] ");
                final Pattern filenamePattern =
                        Pattern.compile(
                                "Content-Disposition:.*filename[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?",
                                Pattern.CASE_INSENSITIVE);
                final java.util.regex.Matcher matcher = filenamePattern.matcher(bodyStr);
                if (matcher.find()) {
                    metadata.append("filename: ").append(matcher.group(1)).append("; ");
                }

                // Extract form field names
                final Pattern fieldPattern =
                        Pattern.compile(
                                "Content-Disposition:.*name[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?",
                                Pattern.CASE_INSENSITIVE);
                final java.util.regex.Matcher fieldMatcher = fieldPattern.matcher(bodyStr);
                final java.util.Set<String> fields = new java.util.HashSet<>();
                while (fieldMatcher.find()) {
                    final String fieldName = fieldMatcher.group(1);
                    if (!"file".equals(fieldName)) { // Skip file field, already logged filename
                        fields.add(fieldName);
                    }
                }
                if (!fields.isEmpty()) {
                    metadata.append(FIELDS).append(String.join(", ", fields)).append("; ");
                }

                // Add file size info
                metadata.append("size: ").append(bodyBytes.length).append(" bytes");

                return metadata.toString();
            }

            // For non-multipart requests, parse as text
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            // Sanitize sensitive data
            body = PASSWORD_PATTERN.matcher(body).replaceAll(A_1_REDACTED_3);
            body = TOKEN_PATTERN.matcher(body).replaceAll(A_1_REDACTED_3);
            body = SECRET_PATTERN.matcher(body).replaceAll(A_1_REDACTED_3);
            body = EMAIL_PATTERN.matcher(body).replaceAll("$1@[REDACTED]");
            body = CARD_PATTERN.matcher(body).replaceAll("[REDACTED]");

            // Limit body length for logging
            if (body.length() > 1000) {
                body = body.substring(0, 1000) + "... [truncated]";
            }

            return body;
        } catch (Exception e) {
            return "[unable to parse body: " + e.getMessage() + "]";
        }
    }

    private String getSanitizedBody(final byte[] bodyBytes) {
        return getSanitizedBody(bodyBytes, null);
    }

    /**
     * Check if exception is a client abort (broken pipe) exception These are expected when clients
     * disconnect before response is fully written
     */
    private boolean isClientAbortException(final Exception e) {
        // Check for ClientAbortException (Tomcat)
        if (e.getClass().getName().contains("ClientAbortException")) {
            return true;
        }

        // Check for IOException with "Broken pipe" message
        if (e instanceof IOException) {
            final String message = e.getMessage();
            if (message != null
                    && (message.contains("Broken pipe")
                            || message.contains("Connection reset by peer"))) {
                return true;
            }
        }

        // Check cause chain
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause.getClass().getName().contains("ClientAbortException")) {
                return true;
            }
            if (cause instanceof IOException) {
                final String message = cause.getMessage();
                if (message != null
                        && (message.contains("Broken pipe")
                                || message.contains("Connection reset by peer"))) {
                    return true;
                }
            }
            cause = cause.getCause();
        }

        return false;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        // Skip logging for actuator endpoints and static resources
        // IMPORTANT: Do NOT skip import endpoints - they need to be logged
        final String path = request.getRequestURI();
        final boolean shouldSkip =
                path.startsWith("/actuator")
                        || path.startsWith("/swagger-ui")
                        || path.startsWith("/v3/api-docs")
                        || path.startsWith("/favicon.ico");

        // Debug log to verify filter is being called
        if (!shouldSkip && (path.contains("/import") || path.contains("/transactions"))) {
            LOGGER.debug(
                    "RequestResponseLoggingFilter processing: {} {}", request.getMethod(), path);
        }

        return shouldSkip;
    }
}
