package com.budgetbuddy.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT Authentication Filter for processing JWT tokens in requests
 * Handles JWT token validation and sets authentication in security context
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(final JwtTokenProvider tokenProvider, final UserDetailsService userDetailsService) {
        if (tokenProvider == null) {
            throw new IllegalArgumentException("JwtTokenProvider cannot be null");
        }
        if (userDetailsService == null) {
            throw new IllegalArgumentException("UserDetailsService cannot be null");
        }
        this.tokenProvider = tokenProvider;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);
            String requestURI = request != null ? request.getRequestURI() : null;

            if (StringUtils.hasText(jwt)) {
                logger.debug("JWT token extracted from request | CorrelationId: {} | Token length: {} | Endpoint: {}", 
                        MDC.get("correlationId"), jwt.length(), requestURI);
                try {
                    // Validate token first
                    if (tokenProvider.validateToken(jwt)) {
                        String username = tokenProvider.getUsernameFromToken(jwt);
                        
                        if (username == null || username.isEmpty()) {
                            logger.warn("JWT token contains empty username. Token will be ignored. | CorrelationId: {} | Endpoint: {}", 
                                    MDC.get("correlationId"), requestURI);
                            filterChain.doFilter(request, response);
                            return;
                        }

                        logger.debug("JWT token validated successfully for user: {} | CorrelationId: {} | Endpoint: {}", 
                                username, MDC.get("correlationId"), requestURI);

                        // Load user details
                        UserDetails userDetails;
                        try {
                            userDetails = userDetailsService.loadUserByUsername(username);
                        } catch (UsernameNotFoundException ex) {
                            // User not found - token is valid but user was deleted or doesn't exist
                            logger.warn("JWT token valid but user not found: {} | CorrelationId: {} | Endpoint: {}", 
                                    username, MDC.get("correlationId"), requestURI);
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // Validate userDetails is not null
                        if (userDetails == null) {
                            logger.warn("UserDetailsService returned null for username: {} | CorrelationId: {} | Endpoint: {}", 
                                    username, MDC.get("correlationId"), requestURI);
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // Create authentication token
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        // Set authentication in security context
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        logger.debug("Successfully authenticated user: {} | CorrelationId: {} | Endpoint: {}", 
                                username, MDC.get("correlationId"), requestURI);
                    } else {
                        // Token validation failed - log at WARN level with endpoint for debugging
                        logger.warn("JWT token validation failed | CorrelationId: {} | Token length: {} | Endpoint: {} | " +
                                "Check JwtTokenProvider logs for specific validation error (expired, malformed, signature mismatch, etc.)", 
                                MDC.get("correlationId"), jwt != null ? jwt.length() : 0, requestURI);
                    }
                } catch (io.jsonwebtoken.JwtException ex) {
                    // Invalid or malformed token - log at WARN level to diagnose authentication issues
                    logger.warn("JWT token parsing/validation error: {} | CorrelationId: {} | Token preview: {}", 
                            ex.getMessage(), MDC.get("correlationId"), 
                            jwt != null && jwt.length() > 20 ? jwt.substring(0, 20) + "..." : "null");
                } catch (IllegalArgumentException ex) {
                    // Invalid token format
                    logger.warn("Invalid JWT token format: {} | CorrelationId: {} | Token preview: {}", 
                            ex.getMessage(), MDC.get("correlationId"),
                            jwt != null && jwt.length() > 20 ? jwt.substring(0, 20) + "..." : "null");
                } catch (RuntimeException ex) {
                    // Unexpected runtime exception during token validation - log at ERROR level
                    // This could indicate a bug or configuration issue
                    logger.error("Unexpected runtime error during JWT token validation: {} | CorrelationId: {} | Token preview: {}", 
                            ex.getMessage(), MDC.get("correlationId"),
                            jwt != null && jwt.length() > 20 ? jwt.substring(0, 20) + "..." : "null", ex);
                }
            }
        } catch (UsernameNotFoundException ex) {
            // User not found - this is expected for deleted users with valid tokens
            logger.warn("User not found during JWT authentication: {} | CorrelationId: {}", 
                    ex.getMessage(), MDC.get("correlationId"));
        } catch (Exception ex) {
            // Unexpected error - log with full context
            String correlationId = MDC.get("correlationId");
            logger.error("Unexpected error setting user authentication in security context | CorrelationId: {} | Error: {}", 
                    correlationId, ex.getMessage(), ex);
        }

        // Always continue filter chain - don't block requests on authentication errors
        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(final HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        
        // Get request URI once and handle null case
        String requestURI = request.getRequestURI();
        String requestURIDisplay = requestURI != null ? requestURI : "null";
        
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken)) {
            if (bearerToken.startsWith(BEARER_PREFIX)) {
                String token = bearerToken.substring(BEARER_PREFIX.length()).trim();
                // Remove control characters (CTRL-CHAR, code 0-31 except whitespace)
                // This fixes issues with malformed tokens containing control characters
                token = cleanControlCharacters(token);
                logger.debug("Extracted JWT token from Authorization header | Token length: {} | Endpoint: {}", 
                        token.length(), requestURIDisplay);
                return token;
            } else {
                logger.warn("Authorization header does not start with 'Bearer ' prefix | Header value preview: {} | Endpoint: {}", 
                        bearerToken.length() > 50 ? bearerToken.substring(0, 50) + "..." : bearerToken, 
                        requestURIDisplay);
            }
        } else {
            logger.debug("No Authorization header found in request | Endpoint: {}", requestURIDisplay);
        }
        
        // Log at WARN level if this is a protected endpoint to help diagnose authentication issues
        if (requestURI != null && 
            requestURI.startsWith("/api/") && 
            !requestURI.startsWith("/api/auth/") &&
            !requestURI.startsWith("/api/public/")) {
            logger.debug("Protected endpoint accessed without Authorization header | Endpoint: {}", requestURI);
        }
        return null;
    }
    
    /**
     * Remove control characters from token string
     * Control characters (0-31) except whitespace (\r, \n, \t) are not allowed in JWT tokens
     */
    private String cleanControlCharacters(final String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        
        StringBuilder cleaned = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            // Keep printable characters and whitespace (\r, \n, \t)
            // Remove control characters (0-31) except whitespace
            if (c >= 32 || c == '\r' || c == '\n' || c == '\t') {
                cleaned.append(c);
            } else {
                logger.warn("Removed control character (code {}) from JWT token | CorrelationId: {}", 
                        (int) c, MDC.get("correlationId"));
            }
        }
        return cleaned.toString();
    }
}

