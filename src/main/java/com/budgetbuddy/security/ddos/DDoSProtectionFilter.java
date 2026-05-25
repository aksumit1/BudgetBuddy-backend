package com.budgetbuddy.security.ddos;

import com.budgetbuddy.api.response.ApiResponse;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.security.rate.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * DDoS Protection Filter Implements multiple layers of protection: - IP-based rate limiting -
 * Per-customer throttling - Request size limits - Connection limits
 *
 * <p>Thread-safe implementation with proper dependency injection
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "CT_CONSTRUCTOR_THROW",
        justification =
                "Java 25: Object.finalize() is deprecated-for-removal, so the finalizer-attack vector this rule guards against is not exploitable. Constructors throw to signal a startup misconfiguration (missing credentials, AWS client init failure, etc.).")
@Component
@Order(1) // Execute before other filters
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
public class DDoSProtectionFilter extends OncePerRequestFilter {

    private static final String APPLICATION_JSON = "application/json";

    private static final String CORRELATION_ID = "correlationId";

    private static final String UNKNOWN = "unknown";

    private static final Logger LOGGER = LoggerFactory.getLogger(DDoSProtectionFilter.class);

    private static final int MAX_REQUEST_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_HEADER_SIZE = 8 * 1024; // 8KB

    private final RateLimitService rateLimitService;
    private final DDoSProtectionService ddosProtectionService;
    private final ObjectMapper objectMapper;
    private final com.budgetbuddy.security.JwtTokenProvider jwtTokenProvider;

    public DDoSProtectionFilter(
            final RateLimitService rateLimitService,
            final DDoSProtectionService ddosProtectionService,
            final com.budgetbuddy.security.JwtTokenProvider jwtTokenProvider) {
        if (rateLimitService == null) {
            throw new IllegalArgumentException("RateLimitService cannot be null");
        }
        if (ddosProtectionService == null) {
            throw new IllegalArgumentException("DDoSProtectionService cannot be null");
        }
        this.rateLimitService = rateLimitService;
        this.ddosProtectionService = ddosProtectionService;
        this.jwtTokenProvider = jwtTokenProvider;
        // Initialize ObjectMapper with JavaTimeModule for Instant serialization
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain)
            throws ServletException, IOException {

        final String clientIp = getClientIpAddress(request);
        final String userId = getUserIdFromRequest(request);

        // Layer 1: IP-based rate limiting (DDoS protection)
        if (clientIp != null && !ddosProtectionService.isAllowed(clientIp)) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "DDoS protection: Blocked request from IP: {} | CorrelationId: {}",
                        clientIp,
                        MDC.get(CORRELATION_ID));
            }
            String correlationId = MDC.get(CORRELATION_ID);
            if (correlationId == null) {
                correlationId = java.util.UUID.randomUUID().toString();
                MDC.put(CORRELATION_ID, correlationId);
            }
            sendRateLimitError(
                    response,
                    correlationId,
                    "Rate limit exceeded. Please try again later.",
                    60,
                    request.getRequestURI());
            return;
        }

        // Layer 2: Per-customer throttling
        if (userId != null && !rateLimitService.isAllowed(userId, request.getRequestURI())) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Rate limit: Blocked request from user: {} for endpoint: {} | CorrelationId: {}",
                        userId,
                        request.getRequestURI(),
                        MDC.get(CORRELATION_ID));
            }
            final int retryAfter = rateLimitService.getRetryAfter(userId, request.getRequestURI());
            String correlationId = MDC.get(CORRELATION_ID);
            if (correlationId == null) {
                correlationId = java.util.UUID.randomUUID().toString();
                MDC.put(CORRELATION_ID, correlationId);
            }
            sendRateLimitError(
                    response,
                    correlationId,
                    "Rate limit exceeded for your account. Please try again in "
                            + retryAfter
                            + " seconds.",
                    retryAfter,
                    request.getRequestURI());
            return;
        }

        // Layer 3: Request size validation
        final long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_REQUEST_SIZE) {
            LOGGER.warn("Request too large: {} bytes from IP: {}", contentLength, clientIp);
            response.setStatus(413); // SC_REQUEST_ENTITY_TOO_LARGE
            response.setContentType(APPLICATION_JSON);
            response.getWriter().write("{\"error\":\"Request payload too large\"}");
            return;
        }

        // Layer 4: Header size validation
        if (getTotalHeaderSize(request) > MAX_HEADER_SIZE) {
            LOGGER.warn("Headers too large from IP: {}", clientIp);
            response.setStatus(431); // SC_REQUEST_HEADER_FIELDS_TOO_LARGE
            response.setContentType(APPLICATION_JSON);
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
     * Extract client IP address from request Priority: X-Forwarded-For > X-Real-IP > RemoteAddr
     * Handles X-Forwarded-For with multiple IPs safely
     */
    private String getClientIpAddress(final HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        // Check X-Forwarded-For first (standard proxy header)
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null) {
            ip = ip.trim();
        }

        // If X-Forwarded-For is not available or invalid, check X-Real-IP
        if (ip == null || ip.isEmpty() || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
            if (ip != null) {
                ip = ip.trim();
            }
        }

        // Fallback to RemoteAddr if both headers are unavailable
        if (ip == null || ip.isEmpty() || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        // Handle multiple IPs (X-Forwarded-For can contain multiple IPs: "client, proxy1, proxy2")
        if (ip != null && ip.contains(",")) {
            final String[] ips = ip.split(",");
            if (ips.length > 0) {
                // Take the first IP (original client IP)
                ip = ips[0].trim();
            } else {
                ip = request.getRemoteAddr();
            }
        }

        // Final validation and fallback
        if (ip == null || ip.isEmpty() || UNKNOWN.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        return ip;
    }

    private String getUserIdFromRequest(final HttpServletRequest request) {
        if (request == null || jwtTokenProvider == null) {
            return null;
        }

        // Extract user ID from JWT token if available
        // Note: This filter runs before JWT authentication filter, so we parse token directly
        final String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                final String token = authHeader.substring(7).trim(); // Remove "Bearer " prefix
                // Validate token before extracting username (to avoid parsing invalid tokens)
                if (jwtTokenProvider.validateToken(token)) {
                    final String username = jwtTokenProvider.getUsernameFromToken(token);
                    if (username != null && !username.isEmpty()) {
                        return username; // Use username as user ID for rate limiting
                    }
                }
            } catch (Exception e) {
                // Token parsing failed - this is expected for invalid tokens
                // Don't log at error level as this is normal for unauthenticated requests
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Could not extract user ID from JWT token: {}", e.getMessage());
                }
            }
        }

        // Fallback: Try to get from SecurityContext (in case filter order changed)
        try {
            final org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext()
                            .getAuthentication();
            if (auth != null
                    && auth.isAuthenticated()
                    && auth.getPrincipal()
                            instanceof org.springframework.security.core.userdetails.UserDetails) {
                final org.springframework.security.core.userdetails.UserDetails userDetails =
                        (org.springframework.security.core.userdetails.UserDetails)
                                auth.getPrincipal();
                return userDetails.getUsername();
            }
        } catch (Exception e) {
            // SecurityContext not available - this is normal when filter runs before authentication
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "SecurityContext not available for user ID extraction: {}", e.getMessage());
            }
        }

        return null; // No user ID available - rate limiting will be IP-based
    }

    /** Calculate total header size Thread-safe and handles null values */
    private int getTotalHeaderSize(final HttpServletRequest request) {
        if (request == null) {
            return 0;
        }

        try {
            int totalSize = 0;
            final java.util.Enumeration<String> headerNames = request.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                final String headerName = headerNames.nextElement();
                if (headerName != null) {
                    final String headerValue = request.getHeader(headerName);
                    totalSize +=
                            headerName.length() + (headerValue != null ? headerValue.length() : 0);
                }
            }
            return totalSize;
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Failed to calculate header size: {}", e.getMessage());
            }
            return 0;
        }
    }

    /**
     * Send rate-limit error response in the standard envelope. Bypasses
     * Spring's @ExceptionHandler chain because filters run earlier, so
     * we write the {@link ApiResponse} envelope directly to the
     * servlet response.
     */
    private void sendRateLimitError(
            final HttpServletResponse response,
            final String correlationId,
            final String message,
            final int retryAfter,
            final String path)
            throws IOException {
        final ApiResponse<Void> body = ApiResponse.error(
                ErrorCode.RATE_LIMIT_EXCEEDED.name(), message, correlationId);

        response.setStatus(429); // SC_TOO_MANY_REQUESTS
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(APPLICATION_JSON);
        response.setCharacterEncoding("UTF-8");

        objectMapper.writeValue(response.getWriter(), body);
    }
}
