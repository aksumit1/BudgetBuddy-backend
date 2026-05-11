package com.budgetbuddy.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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

/**
 * JWT Authentication Filter for processing JWT tokens in requests Handles JWT token validation and
 * sets authentication in security context
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "CT_CONSTRUCTOR_THROW",
        justification = "Java 25: Object.finalize() is deprecated-for-removal, so the finalizer-attack vector this rule guards against is not exploitable. Constructors throw to signal a startup misconfiguration (missing credentials, AWS client init failure, etc.).")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID = "correlationId";

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(
            final JwtTokenProvider tokenProvider, final UserDetailsService userDetailsService) {
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
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain)
            throws ServletException, IOException {
        try {
            final String jwt = getJwtFromRequest(request);
            final String requestURI = request != null ? request.getRequestURI() : null;

            if (StringUtils.hasText(jwt)) {
                LOGGER.debug(
                        "JWT token extracted from request | CorrelationId: {} | Token length: {} | Endpoint: {}",
                        MDC.get(CORRELATION_ID),
                        jwt.length(),
                        requestURI);
                try {
                    // Validate token first
                    if (tokenProvider.validateToken(jwt)) {
                        final String username = tokenProvider.getUsernameFromToken(jwt);

                        if (username == null || username.isEmpty()) {
                            LOGGER.warn(
                                    "JWT token contains empty username. Token will be ignored. | CorrelationId: {} | Endpoint: {}",
                                    MDC.get(CORRELATION_ID),
                                    requestURI);
                            filterChain.doFilter(request, response);
                            return;
                        }

                        LOGGER.debug(
                                "JWT token validated successfully for user: {} | CorrelationId: {} | Endpoint: {}",
                                username,
                                MDC.get(CORRELATION_ID),
                                requestURI);

                        // Load user details
                        final UserDetails userDetails;
                        try {
                            userDetails = userDetailsService.loadUserByUsername(username);
                        } catch (UsernameNotFoundException ex) {
                            // User not found - token is valid but user was deleted or doesn't exist
                            LOGGER.warn(
                                    "JWT token valid but user not found: {} | CorrelationId: {} | Endpoint: {}",
                                    username,
                                    MDC.get(CORRELATION_ID),
                                    requestURI);
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // Validate userDetails is not null
                        if (userDetails == null) {
                            LOGGER.warn(
                                    "UserDetailsService returned null for username: {} | CorrelationId: {} | Endpoint: {}",
                                    username,
                                    MDC.get(CORRELATION_ID),
                                    requestURI);
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // Create authentication token
                        final UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(
                                new WebAuthenticationDetailsSource().buildDetails(request));

                        // Set authentication in security context
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        LOGGER.debug(
                                "Successfully authenticated user: {} | CorrelationId: {} | Endpoint: {}",
                                username,
                                MDC.get(CORRELATION_ID),
                                requestURI);
                    } else {
                        // Token validation failed - log at WARN level with endpoint for debugging.
                        // jwt is non-null inside this branch (StringUtils.hasText check above).
                        LOGGER.warn(
                                "JWT token validation failed | CorrelationId: {} | Token length: {} | Endpoint: {} | "
                                        + "Check JwtTokenProvider logs for specific validation error (expired, malformed, signature mismatch, etc.)",
                                MDC.get(CORRELATION_ID),
                                jwt.length(),
                                requestURI);
                    }
                } catch (io.jsonwebtoken.JwtException ex) {
                    LOGGER.warn(
                            "JWT token parsing/validation error: {} | CorrelationId: {} | Token preview: {}",
                            ex.getMessage(),
                            MDC.get(CORRELATION_ID),
                            jwt.length() > 20 ? jwt.substring(0, 20) + "..." : jwt);
                } catch (IllegalArgumentException ex) {
                    LOGGER.warn(
                            "Invalid JWT token format: {} | CorrelationId: {} | Token preview: {}",
                            ex.getMessage(),
                            MDC.get(CORRELATION_ID),
                            jwt.length() > 20 ? jwt.substring(0, 20) + "..." : jwt);
                } catch (RuntimeException ex) {
                    LOGGER.error(
                            "Unexpected runtime error during JWT token validation: {} | CorrelationId: {} | Token preview: {}",
                            ex.getMessage(),
                            MDC.get(CORRELATION_ID),
                            jwt.length() > 20 ? jwt.substring(0, 20) + "..." : jwt,
                            ex);
                }
            }
        } catch (UsernameNotFoundException ex) {
            // User not found - this is expected for deleted users with valid tokens
            LOGGER.warn(
                    "User not found during JWT authentication: {} | CorrelationId: {}",
                    ex.getMessage(),
                    MDC.get(CORRELATION_ID));
        } catch (Exception ex) {
            // Unexpected error - log with full context
            final String correlationId = MDC.get(CORRELATION_ID);
            LOGGER.error(
                    "Unexpected error setting user authentication in security context | CorrelationId: {} | Error: {}",
                    correlationId,
                    ex.getMessage(),
                    ex);
        }

        // Always continue filter chain - don't block requests on authentication errors
        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(final HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        // Get request URI once and handle null case
        final String requestURI = request.getRequestURI();
        final String requestURIDisplay = requestURI != null ? requestURI : "null";

        final String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken)) {
            if (bearerToken.startsWith(BEARER_PREFIX)) {
                String token = bearerToken.substring(BEARER_PREFIX.length()).trim();
                // Remove control characters (CTRL-CHAR, code 0-31 except whitespace)
                // This fixes issues with malformed tokens containing control characters
                token = cleanControlCharacters(token);
                LOGGER.debug(
                        "Extracted JWT token from Authorization header | Token length: {} | Endpoint: {}",
                        token.length(),
                        requestURIDisplay);
                return token;
            } else {
                LOGGER.warn(
                        "Authorization header does not start with 'Bearer ' prefix | Header value preview: {} | Endpoint: {}",
                        bearerToken.length() > 50
                                ? bearerToken.substring(0, 50) + "..."
                                : bearerToken,
                        requestURIDisplay);
            }
        } else {
            LOGGER.debug(
                    "No Authorization header found in request | Endpoint: {}", requestURIDisplay);
        }

        // Log at WARN level if this is a protected endpoint to help diagnose authentication issues
        if (requestURI != null
                && requestURI.startsWith("/api/")
                && !requestURI.startsWith("/api/auth/")
                && !requestURI.startsWith("/api/public/")) {
            LOGGER.debug(
                    "Protected endpoint accessed without Authorization header | Endpoint: {}",
                    requestURI);
        }
        return null;
    }

    /**
     * Remove control characters from token string Control characters (0-31) except whitespace (\r,
     * \n, \t) are not allowed in JWT tokens
     */
    private String cleanControlCharacters(final String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }

        final StringBuilder cleaned = new StringBuilder(token.length());
        for (final char c : token.toCharArray()) {
            // Keep printable characters and whitespace (\r, \n, \t)
            // Remove control characters (0-31) except whitespace
            if (c >= 32 || c == '\r' || c == '\n' || c == '\t') {
                cleaned.append(c);
            } else {
                LOGGER.warn(
                        "Removed control character (code {}) from JWT token | CorrelationId: {}",
                        (int) c,
                        MDC.get(CORRELATION_ID));
            }
        }
        return cleaned.toString();
    }
}
