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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

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
        } finally {
            long duration = System.currentTimeMillis() - startTime;

            // Log request
            logRequest(wrappedRequest, correlationId);

            // Log response
            logResponse(wrappedResponse, correlationId, duration);

            // Copy response body back to original response
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request, String correlationId) {
        try {
            String method = request.getMethod();
            String uri = request.getRequestURI();
            String queryString = request.getQueryString();
            String fullUri = queryString != null ? uri + "?" + queryString : uri;

            // Log headers (sanitized)
            StringBuilder headersLog = new StringBuilder();
            Collections.list(request.getHeaderNames()).forEach(headerName -> {
                if (!SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
                    headersLog.append(headerName).append(": ").append(request.getHeader(headerName)).append("; ");
                } else {
                    headersLog.append(headerName).append(": [REDACTED]; ");
                }
            });

            // Log body (sanitized)
            String body = getSanitizedBody(request.getContentAsByteArray());

            requestLogger.info("REQUEST [{}] {} {} | Headers: {} | Body: {}",
                    correlationId, method, fullUri, headersLog.toString(), body);
        } catch (Exception e) {
            logger.warn("Error logging request: {}", e.getMessage());
        }
    }

    private void logResponse(ContentCachingResponseWrapper response, String correlationId, long duration) {
        try {
            int status = response.getStatus();

            // Log headers (sanitized)
            StringBuilder headersLog = new StringBuilder();
            response.getHeaderNames().forEach(headerName -> {
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

    private String getSanitizedBody(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "[empty]";
        }

        try {
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
            return "[unable to parse body]";
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip logging for actuator endpoints and static resources
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || 
               path.startsWith("/swagger-ui") || 
               path.startsWith("/v3/api-docs") ||
               path.startsWith("/favicon.ico");
    }
}

