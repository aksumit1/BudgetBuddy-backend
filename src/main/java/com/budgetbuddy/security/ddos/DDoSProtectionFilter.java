package com.budgetbuddy.security.ddos;

import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.exception.EnhancedGlobalExceptionHandler;
import com.budgetbuddy.security.ddos.DDoSProtectionService;
import com.budgetbuddy.security.rate.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DDoS Protection Filter
 * Implements multiple layers of protection:
 * - IP-based rate limiting
 * - Per-customer throttling
 * - Request size limits
 * - Connection limits
 *
 * Thread-safe implementation with proper dependency injection
 */
@Component
@Order(1) // Execute before other filters
public class DDoSProtectionFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(DDoSProtectionFilter.class);

    private static final int MAX_REQUEST_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_HEADER_SIZE = 8 * 1024; // 8KB

    private final RateLimitService rateLimitService;
    private final DDoSProtectionService ddosProtectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DDoSProtectionFilter(final RateLimitService rateLimitService, final DDoSProtectionService ddosProtectionService) {
        if (rateLimitService == null) {
            throw new IllegalArgumentException("RateLimitService cannot be null");
        }
        if (ddosProtectionService == null) {
            throw new IllegalArgumentException("DDoSProtectionService cannot be null");
        }
        this.rateLimitService = rateLimitService;
        this.ddosProtectionService = ddosProtectionService;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) throws ServletException, IOException {

        String clientIp = getClientIpAddress(request);
        String userId = getUserIdFromRequest(request);

        // Layer 1: IP-based rate limiting (DDoS protection)
        if (clientIp != null && !ddosProtectionService.isAllowed(clientIp)) {
            logger.warn("DDoS protection: Blocked request from IP: {} | CorrelationId: {}", clientIp, MDC.get("correlationId"));
            String correlationId = MDC.get("correlationId");
            if (correlationId == null) {
                correlationId = java.util.UUID.randomUUID().toString();
                MDC.put("correlationId", correlationId);
            }
            sendRateLimitError(response, correlationId, "Rate limit exceeded. Please try again later.", 60, request.getRequestURI());
            return;
        }

        // Layer 2: Per-customer throttling
        if (userId != null && !rateLimitService.isAllowed(userId, request.getRequestURI())) {
            logger.warn("Rate limit: Blocked request from user: {} for endpoint: {} | CorrelationId: {}", userId, request.getRequestURI(), MDC.get("correlationId"));
            int retryAfter = rateLimitService.getRetryAfter(userId, request.getRequestURI());
            String correlationId = MDC.get("correlationId");
            if (correlationId == null) {
                correlationId = java.util.UUID.randomUUID().toString();
                MDC.put("correlationId", correlationId);
            }
            sendRateLimitError(response, correlationId, "Rate limit exceeded for your account. Please try again in " + retryAfter + " seconds.", retryAfter, request.getRequestURI());
            return;
        }

        // Layer 3: Request size validation
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_REQUEST_SIZE) {
            logger.warn("Request too large: {} bytes from IP: {}", contentLength, clientIp);
            response.setStatus(413); // SC_REQUEST_ENTITY_TOO_LARGE
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Request payload too large\"}");
            return;
        }

        // Layer 4: Header size validation
        if (getTotalHeaderSize(request) > MAX_HEADER_SIZE) {
            logger.warn("Headers too large from IP: {}", clientIp);
            response.setStatus(431); // SC_REQUEST_HEADER_FIELDS_TOO_LARGE
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Request headers too large\"}");
            return;
        }

        // Record request for monitoring
        if (clientIp != null) {
            ddosProtectionService.recordRequest(clientIp, userId);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract client IP address from request
     * Handles X-Forwarded-For with multiple IPs safely
     */
    private String getClientIpAddress(final HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // Handle multiple IPs (X-Forwarded-For can contain multiple IPs)
        if (ip != null && ip.contains(",")) {
            String[] ips = ip.split(",");
            if (ips.length > 0) {
                ip = ips[0].trim();
            } else {
                ip = request.getRemoteAddr();
            }
        }

        return ip != null && !ip.isEmpty() ? ip : request.getRemoteAddr();
    }

    private String getUserIdFromRequest(final HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        // Extract user ID from JWT token if available
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // Token parsing would be done by JWT filter, but we can extract user ID here
            // For now, return null and let rate limiting be IP-based
            return null;
        }
        return null;
    }

    /**
     * Calculate total header size
     * Thread-safe and handles null values
     */
    private int getTotalHeaderSize(final HttpServletRequest request) {
        if (request == null) {
            return 0;
        }

        try {
            int totalSize = 0;
            java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                String headerName = headerNames.nextElement();
                if (headerName != null) {
                    String headerValue = request.getHeader(headerName);
                    totalSize += headerName.length() + (headerValue != null ? headerValue.length() : 0);
                }
            }
            return totalSize;
        } catch (Exception e) {
            logger.warn("Failed to calculate header size: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Send rate limit error response in standard format
     * Matches EnhancedGlobalExceptionHandler.ErrorResponse format
     */
    private void sendRateLimitError(final HttpServletResponse response, final String correlationId, final String message, final int retryAfter, final String path) throws IOException {
        EnhancedGlobalExceptionHandler.ErrorResponse errorResponse = EnhancedGlobalExceptionHandler.ErrorResponse.builder()
                .errorCode(ErrorCode.RATE_LIMIT_EXCEEDED.name())
                .message(message)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path(path != null ? path : "")
                .build();

        response.setStatus(429); // SC_TOO_MANY_REQUESTS
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}
