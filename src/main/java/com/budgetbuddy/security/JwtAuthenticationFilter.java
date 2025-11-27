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

            if (StringUtils.hasText(jwt)) {
                try {
                    // Validate token first
                    if (tokenProvider.validateToken(jwt)) {
                        String username = tokenProvider.getUsernameFromToken(jwt);
                        
                        if (username == null || username.isEmpty()) {
                            logger.warn("JWT token contains empty username. Token will be ignored. | CorrelationId: {}", 
                                    MDC.get("correlationId"));
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // Load user details
                        UserDetails userDetails;
                        try {
                            userDetails = userDetailsService.loadUserByUsername(username);
                        } catch (UsernameNotFoundException ex) {
                            // User not found - token is valid but user was deleted or doesn't exist
                            logger.warn("JWT token valid but user not found: {} | CorrelationId: {}", 
                                    username, MDC.get("correlationId"));
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // Validate userDetails is not null
                        if (userDetails == null) {
                            logger.warn("UserDetailsService returned null for username: {} | CorrelationId: {}", 
                                    username, MDC.get("correlationId"));
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // Create authentication token
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        // Set authentication in security context
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        logger.debug("Successfully authenticated user: {} | CorrelationId: {}", 
                                username, MDC.get("correlationId"));
                    } else {
                        logger.debug("JWT token validation failed | CorrelationId: {}", MDC.get("correlationId"));
                    }
                } catch (io.jsonwebtoken.JwtException ex) {
                    // Invalid or malformed token - log at debug level as this is expected for unauthenticated requests
                    logger.debug("JWT token parsing/validation error: {} | CorrelationId: {}", 
                            ex.getMessage(), MDC.get("correlationId"));
                } catch (IllegalArgumentException ex) {
                    // Invalid token format
                    logger.debug("Invalid JWT token format: {} | CorrelationId: {}", 
                            ex.getMessage(), MDC.get("correlationId"));
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
        
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}

