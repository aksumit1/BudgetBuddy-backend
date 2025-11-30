package com.budgetbuddy.security.ddos;

import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.exception.EnhancedGlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks 404 (Not Found) errors and throttles sources that generate excessive 404s
 * This is a DDoS protection mechanism - repeated 404s can indicate scanning/probing attacks
 * 
 * Order: Runs after DDoSProtectionFilter but before request processing
 * to check if source is already blocked, and after response to track 404s
 */
@Component
@Order(2) // After DDoSProtectionFilter (Order 1)
public class NotFoundErrorTrackingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(NotFoundErrorTrackingFilter.class);
    private static final ObjectMapper objectMapper;
    
    static {
        // Initialize ObjectMapper with JavaTimeModule for Instant serialization
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    private final NotFoundErrorTrackingService notFoundTrackingService;

    public NotFoundErrorTrackingFilter(final NotFoundErrorTrackingService notFoundTrackingService) {
        if (notFoundTrackingService == null) {
            throw new IllegalArgumentException("NotFoundErrorTrackingService cannot be null");
        }
        this.notFoundTrackingService = notFoundTrackingService;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain) 
            throws ServletException, IOException {

        String clientIp = getClientIpAddress(request);
        String userId = getUserIdFromRequest(request);
        
        // Use IP as primary identifier, fallback to userId if IP is not available
        String sourceId = clientIp != null ? clientIp : (userId != null ? userId : "unknown");

        // Check if source is already blocked due to excessive 404s
        if (notFoundTrackingService.isBlocked(sourceId)) {
            logger.warn("404 tracking: Blocked request from source {} due to excessive 404 errors", sourceId);
            sendBlockedError(response, "Too many 404 errors. Please check your requests and try again later.", 3600);
            return;
        }

        // Wrap response to capture status code
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(request, wrappedResponse);
        } finally {
            // Track 404 errors after response is sent
            int status = wrappedResponse.getStatus();
            if (status == HttpServletResponse.SC_NOT_FOUND) {
                boolean shouldBlock = notFoundTrackingService.recordNotFoundError(sourceId);
                if (shouldBlock) {
                    logger.warn("404 tracking: Source {} exceeded 404 threshold and will be blocked", sourceId);
                } else {
                    logger.debug("404 tracking: Recorded 404 error for source {} (status: {})", sourceId, status);
                }
            }

            // Copy response body back to original response
            wrappedResponse.copyBodyToResponse();
        }
    }

    /**
     * Extract client IP address from request
     * Priority: X-Forwarded-For > X-Real-IP > RemoteAddr
     * 
     * Note: In MockMvc tests, headers must be explicitly set and may return empty strings
     * instead of null, so we check for both null and empty strings.
     */
    private String getClientIpAddress(final HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        // Check X-Forwarded-For first (standard proxy header)
        // MockMvc may return empty string instead of null, so check both
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null) {
            ip = ip.trim();
            // If empty after trim, treat as not set
            if (ip.isEmpty()) {
                ip = null;
            }
        }
        
        // If X-Forwarded-For is not available or invalid, check X-Real-IP
        if (ip == null || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
            if (ip != null) {
                ip = ip.trim();
                // If empty after trim, treat as not set
                if (ip.isEmpty()) {
                    ip = null;
                }
            }
        }
        
        // Fallback to RemoteAddr if both headers are unavailable
        if (ip == null || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // Handle multiple IPs (X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2")
        if (ip != null && ip.contains(",")) {
            String[] ips = ip.split(",");
            if (ips.length > 0) {
                // Take the first IP (original client IP)
                ip = ips[0].trim();
                // Validate the extracted IP is not empty
                if (ip.isEmpty()) {
                    ip = request.getRemoteAddr();
                }
            } else {
                ip = request.getRemoteAddr();
            }
        }

        // Final validation and fallback
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        return ip;
    }

    private String getUserIdFromRequest(final HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        // Extract user ID from JWT token if available
        // This would typically be done by Spring Security after authentication
        // For now, return null and use IP-based tracking
        return null;
    }

    /**
     * Send blocked error response in standard format
     */
    private void sendBlockedError(final HttpServletResponse response, final String message, final int retryAfter) throws IOException {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
            MDC.put("correlationId", correlationId);
        }

        EnhancedGlobalExceptionHandler.ErrorResponse errorResponse = EnhancedGlobalExceptionHandler.ErrorResponse.builder()
                .errorCode(ErrorCode.RATE_LIMIT_EXCEEDED.name())
                .message(message)
                .correlationId(correlationId)
                .timestamp(Instant.now())
                .path("")
                .build();

        response.setStatus(429); // SC_TOO_MANY_REQUESTS
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        objectMapper.writeValue(response.getWriter(), errorResponse);
    }
}

