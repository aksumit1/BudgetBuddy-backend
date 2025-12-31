package com.budgetbuddy.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request/Response Logging Filter
 * Logs request and response details with sanitization
 *
 * Features:
 * - Correlation ID generation
 * - Request/response body logging (sanitized)
 * - Headers logging (sanitized)
 * - Performance metrics
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);
    private static final Logger requestLogger = LoggerFactory.getLogger("REQUEST_LOGGER");
    
    // Constructor to log filter initialization
    public RequestResponseLoggingFilter() {
        logger.info("✅ RequestResponseLoggingFilter initialized and registered");
        requestLogger.info("✅ REQUEST_LOGGER initialized");
    }

    // Patterns for sensitive data sanitization
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("(?i)(\"password\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("(?i)(\"(?:token|access_token|refresh_token|authorization)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(\"(?:secret|api_key|apikey|private_key)\"\\s*:\\s*\")([^\"]+)(\")");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b");

    // Headers to exclude from logging
    private static final List<String> SENSITIVE_HEADERS = Arrays.asList(
            "authorization", "x-api-key", "x-auth-token", "cookie", "set-cookie"
    );

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {

        String uri = request.getRequestURI();
        String method = request.getMethod();
        
        // Generate correlation ID if not present
        String correlationId = request.getHeader("X-Request-ID");
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Request-ID", correlationId);

        // Wrap request and response for body reading
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } catch (Exception e) {
            // Log exception but don't fail the request
            logger.error("Exception in filter chain for request [{}] {} {}: {}", 
                correlationId, method, uri, e.getMessage(), e);
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;

            try {
                // Log request - ALWAYS log, even if body is empty
                // This is critical - we must log every request
                logRequest(wrappedRequest, correlationId);
            } catch (Exception e) {
                // Log error but don't fail the request
                logger.error("❌ Error logging request [{}] {} {}: {}", 
                    correlationId, method, uri, e.getMessage(), e);
                e.printStackTrace(); // Print stack trace to help debug
                // Still try to log basic info - this ensures we ALWAYS log something
                try {
                    String queryString = request.getQueryString();
                    String fullUri = queryString != null ? uri + "?" + queryString : uri;
                    String reqContentType = request.getContentType();
                    requestLogger.info("REQUEST [{}] {} {} | ContentType: {} | [ERROR IN DETAILED LOGGING: {}]", 
                        correlationId, method, fullUri, reqContentType, e.getMessage());
                    logger.info("REQUEST [{}] {} {} | ContentType: {} | [ERROR IN DETAILED LOGGING: {}]", 
                        correlationId, method, fullUri, reqContentType, e.getMessage());
                } catch (Exception e2) {
                    logger.error("❌ Failed to log even basic request info [{}]: {}", correlationId, e2.getMessage(), e2);
                    e2.printStackTrace();
                }
            }

            try {
                // Log response
                logResponse(wrappedResponse, correlationId, duration);
            } catch (Exception e) {
                // Log error but don't fail the request
                logger.error("Error logging response [{}]: {}", correlationId, e.getMessage(), e);
            }

            try {
                // Copy response body back to original response
                wrappedResponse.copyBodyToResponse();
            } catch (Exception e) {
                logger.error("Error copying response body [{}]: {}", correlationId, e.getMessage(), e);
            }
        }
    }

    private void logRequest(final ContentCachingRequestWrapper request, final String correlationId) {
        try {
            String method = request.getMethod();
            String uri = request.getRequestURI();
            String queryString = request.getQueryString();
            String fullUri = queryString != null ? uri + "?" + queryString : uri;

            // Log headers (sanitized)
            StringBuilder headersLog = new StringBuilder();
            Collections.list(request.getHeaderNames()).forEach((headerName) -> {
                if (!SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
                    headersLog.append(headerName).append(": ").append(request.getHeader(headerName)).append("; ");
                } else {
                    headersLog.append(headerName).append(": [REDACTED]; ");
                }
            });

            // Log query parameters (especially important for file uploads with filename parameter)
            StringBuilder queryParamsLog = new StringBuilder();
            if (queryString != null && !queryString.isEmpty()) {
                queryParamsLog.append("QueryParams: ");
                String[] params = queryString.split("&");
                for (String param : params) {
                    String[] keyValue = param.split("=", 2);
                    if (keyValue.length == 2) {
                        String key = keyValue[0];
                        String value = java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                        // Sanitize password in query params
                        if (key.toLowerCase().contains("password")) {
                            value = "[REDACTED]";
                        }
                        queryParamsLog.append(key).append("=").append(value).append("; ");
                    }
                }
            }

            // Log request parameters (form data, including multipart)
            StringBuilder requestParamsLog = new StringBuilder();
            try {
                // Get all parameter names
                java.util.Enumeration<String> paramNames = request.getParameterNames();
                if (paramNames.hasMoreElements()) {
                    requestParamsLog.append("RequestParams: ");
                    while (paramNames.hasMoreElements()) {
                        String paramName = paramNames.nextElement();
                        String[] paramValues = request.getParameterValues(paramName);
                        if (paramValues != null && paramValues.length > 0) {
                            // Sanitize password parameters
                            if (paramName.toLowerCase().contains("password")) {
                                requestParamsLog.append(paramName).append("=[REDACTED]; ");
                            } else {
                                // For other params, log first value (or all if multiple)
                                String value = paramValues[0];
                                if (value.length() > 100) {
                                    value = value.substring(0, 100) + "... [truncated]";
                                }
                                requestParamsLog.append(paramName).append("=").append(value).append("; ");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore - parameters might not be available yet
            }

            // Handle multipart requests specially
            String contentType = request.getContentType();
            String body = "[empty]";
            
            // Try to get multipart information if available
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                body = getMultipartRequestInfo(request);
                // If multipart info extraction failed, at least log that it's multipart
                if (body == null || body.isEmpty() || body.equals("[empty]")) {
                    body = "[multipart/form-data - body not readable in filter (consumed by Spring multipart resolver)]";
                }
            } else {
                // For non-multipart, try to get body
                byte[] bodyBytes = request.getContentAsByteArray();
                if (bodyBytes != null && bodyBytes.length > 0) {
                    body = getSanitizedBody(bodyBytes, contentType);
                }
            }

            // Build complete log message - ALWAYS log, even if body is empty
            StringBuilder logMessage = new StringBuilder("REQUEST [").append(correlationId).append("] ")
                    .append(method).append(" ").append(fullUri);
            
            if (queryParamsLog.length() > 0) {
                logMessage.append(" | ").append(queryParamsLog.toString());
            }
            
            if (requestParamsLog.length() > 0) {
                logMessage.append(" | ").append(requestParamsLog.toString());
            }
            
            logMessage.append(" | Headers: ").append(headersLog.toString())
                      .append(" | Body: ").append(body);
            
            // For multipart requests, add a note if body is empty (expected behavior)
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                if (body == null || body.isEmpty() || body.equals("[empty]")) {
                    logMessage.append(" [NOTE: Multipart body consumed by Spring multipart resolver - see controller logs for file details]");
                }
                // Always log multipart requests at INFO level for visibility
                logger.info("📤 Multipart request detected: {} {} | ContentType: {}", method, fullUri, contentType);
            }

            // CRITICAL: Always log - use info level to ensure it's visible
            // Use both requestLogger and main logger to ensure visibility
            String logMsg = logMessage.toString();
            
            // Always log to REQUEST_LOGGER
            try {
                requestLogger.info(logMsg);
            } catch (Exception e) {
                logger.error("❌ Failed to log to REQUEST_LOGGER: {}", e.getMessage(), e);
            }
            
            // Also log to main logger for multipart requests and import endpoints to ensure visibility
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                try {
                    logger.info("📤 MULTIPART REQUEST: {}", logMsg);
                } catch (Exception e) {
                    logger.error("❌ Failed to log multipart request to main logger: {}", e.getMessage(), e);
                }
            }
            
            // Also log import endpoints to main logger
            if (uri != null && (uri.contains("/import") || uri.contains("/batch-import"))) {
                try {
                    logger.info("📤 IMPORT REQUEST: {}", logMsg);
                } catch (Exception e) {
                    logger.error("❌ Failed to log import request to main logger: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.warn("Error logging request: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Extract multipart request information
     * Tries multiple methods to get multipart data
     */
    private String getMultipartRequestInfo(final HttpServletRequest request) {
        StringBuilder info = new StringBuilder("[multipart/form-data] ");
        
        try {
            // Method 1: Try to access as MultipartHttpServletRequest (if already parsed by Spring)
            if (request instanceof MultipartHttpServletRequest) {
                MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
                
                // Get all file parts
                java.util.Map<String, MultipartFile> fileMap = multipartRequest.getFileMap();
                for (java.util.Map.Entry<String, MultipartFile> entry : fileMap.entrySet()) {
                    MultipartFile file = entry.getValue();
                    if (file != null && !file.isEmpty()) {
                        String originalFilename = file.getOriginalFilename();
                        if (originalFilename != null) {
                            info.append("file: ").append(entry.getKey())
                                .append(" (filename: ").append(originalFilename)
                                .append(", size: ").append(file.getSize()).append(" bytes); ");
                        } else {
                            info.append("file: ").append(entry.getKey())
                                .append(" (size: ").append(file.getSize()).append(" bytes); ");
                        }
                    }
                }
                
                // Get all parameter names (non-file form fields)
                java.util.Map<String, String[]> parameterMap = multipartRequest.getParameterMap();
                java.util.Set<String> paramNames = new java.util.HashSet<>();
                for (String paramName : parameterMap.keySet()) {
                    if (!fileMap.containsKey(paramName)) {
                        paramNames.add(paramName);
                    }
                }
                if (!paramNames.isEmpty()) {
                    info.append("fields: ").append(String.join(", ", paramNames)).append("; ");
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
                String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
                
                // Extract filename from Content-Disposition headers
                java.util.regex.Pattern filenamePattern = java.util.regex.Pattern.compile(
                    "Content-Disposition:.*filename[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher matcher = filenamePattern.matcher(bodyStr);
                if (matcher.find()) {
                    info.append("filename: ").append(matcher.group(1)).append("; ");
                }
                
                // Extract form field names
                java.util.regex.Pattern fieldPattern = java.util.regex.Pattern.compile(
                    "Content-Disposition:.*name[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher fieldMatcher = fieldPattern.matcher(bodyStr);
                java.util.Set<String> fields = new java.util.HashSet<>();
                while (fieldMatcher.find()) {
                    String fieldName = fieldMatcher.group(1);
                    if (!fieldName.equals("file")) {
                        fields.add(fieldName);
                    }
                }
                if (!fields.isEmpty()) {
                    info.append("fields: ").append(String.join(", ", fields)).append("; ");
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

    private void logResponse(final ContentCachingResponseWrapper response, final String correlationId, final long duration) {
        try {
            int status = response.getStatus();

            // Log headers (sanitized)
            StringBuilder headersLog = new StringBuilder();
            response.getHeaderNames().forEach((headerName) -> {
                if (!SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
                    headersLog.append(headerName).append(": ").append(response.getHeader(headerName)).append("; ");
                } else {
                    headersLog.append(headerName).append(": [REDACTED]; ");
                }
            });

            // Log body (sanitized)
            String body = getSanitizedBody(response.getContentAsByteArray());

            requestLogger.info("RESPONSE [{}] Status: {} | Duration: {}ms | Headers: {} | Body: {}",
                    correlationId, status, duration, headersLog.toString(), body);
        } catch (Exception e) {
            logger.warn("Error logging response: {}", e.getMessage());
        }
    }

    private String getSanitizedBody(final byte[] bodyBytes, final String contentType) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "[empty]";
        }

        try {
            // Handle multipart/form-data specially (file uploads)
            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                // Extract metadata from multipart body without logging binary data
                String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
                
                // Extract filename from Content-Disposition headers
                StringBuilder metadata = new StringBuilder("[multipart/form-data] ");
                java.util.regex.Pattern filenamePattern = java.util.regex.Pattern.compile(
                    "Content-Disposition:.*filename[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher matcher = filenamePattern.matcher(bodyStr);
                if (matcher.find()) {
                    metadata.append("filename: ").append(matcher.group(1)).append("; ");
                }
                
                // Extract form field names
                java.util.regex.Pattern fieldPattern = java.util.regex.Pattern.compile(
                    "Content-Disposition:.*name[=:]\\s*[\"']?([^\"'\\r\\n]+)[\"']?", 
                    java.util.regex.Pattern.CASE_INSENSITIVE
                );
                java.util.regex.Matcher fieldMatcher = fieldPattern.matcher(bodyStr);
                java.util.Set<String> fields = new java.util.HashSet<>();
                while (fieldMatcher.find()) {
                    String fieldName = fieldMatcher.group(1);
                    if (!fieldName.equals("file")) { // Skip file field, already logged filename
                        fields.add(fieldName);
                    }
                }
                if (!fields.isEmpty()) {
                    metadata.append("fields: ").append(String.join(", ", fields)).append("; ");
                }
                
                // Add file size info
                metadata.append("size: ").append(bodyBytes.length).append(" bytes");
                
                return metadata.toString();
            }
            
            // For non-multipart requests, parse as text
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            // Sanitize sensitive data
            body = PASSWORD_PATTERN.matcher(body).replaceAll("$1[REDACTED]$3");
            body = TOKEN_PATTERN.matcher(body).replaceAll("$1[REDACTED]$3");
            body = SECRET_PATTERN.matcher(body).replaceAll("$1[REDACTED]$3");
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

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        // Skip logging for actuator endpoints and static resources
        // IMPORTANT: Do NOT skip import endpoints - they need to be logged
        String path = request.getRequestURI();
        boolean shouldSkip = path.startsWith("/actuator") ||
               path.startsWith("/swagger-ui") ||
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/favicon.ico");
        
        // Debug log to verify filter is being called
        if (!shouldSkip && (path.contains("/import") || path.contains("/transactions"))) {
            logger.debug("RequestResponseLoggingFilter processing: {} {}", request.getMethod(), path);
        }
        
        return shouldSkip;
    }
}

