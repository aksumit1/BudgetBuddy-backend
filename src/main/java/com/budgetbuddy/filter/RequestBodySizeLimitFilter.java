package com.budgetbuddy.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects requests whose declared {@code Content-Length} exceeds {@link #MAX_JSON_BODY_BYTES} on
 * non-multipart endpoints. Spring's {@code spring.servlet.multipart.*} properties only cap
 * multipart payloads; without this filter a remote caller could POST a multi-gigabyte JSON body to
 * (for example) the Plaid webhook endpoint and force the JVM to buffer it before
 * {@code @RequestBody} parsing ever begins.
 *
 * <p>The cap intentionally runs early in the filter chain so the body is rejected at servlet level
 * rather than consumed into memory first.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestBodySizeLimitFilter.class);

    /** Cap for JSON/text request bodies — matches the existing 10MB multipart cap. */
    static final long MAX_JSON_BODY_BYTES = 10L * 1024 * 1024;

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain)
            throws ServletException, IOException {

        final long declaredLength = request.getContentLengthLong();
        final String contentType = request.getContentType();

        // Multipart uploads have their own size enforcement (spring.servlet.multipart.*).
        // Only police plain non-multipart bodies here.
        final boolean isMultipart =
                contentType != null
                        && contentType.toLowerCase(java.util.Locale.ROOT).contains("multipart/");

        if (!isMultipart && declaredLength > MAX_JSON_BODY_BYTES) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Rejecting oversize request body: uri={} declaredLength={} cap={}",
                        request.getRequestURI(),
                        declaredLength,
                        MAX_JSON_BODY_BYTES);
            }
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Request body exceeds the allowed size\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
