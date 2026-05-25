package com.budgetbuddy.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Correlation ID Filter — populates the SLF4J MDC with per-request
 * identifiers so existing {@code LOGGER.info("...")} calls
 * automatically gain structured context without any code change at the
 * call site. Compatible JSON log appenders include the MDC fields in
 * each event.
 *
 * <p>Fields populated:
 * <ul>
 *   <li>{@code correlationId} — propagated from {@code X-Correlation-ID}
 *       header if present, else freshly generated.</li>
 *   <li>{@code httpMethod} — request method (GET/POST/...).</li>
 *   <li>{@code path} — request URI (no query string).</li>
 * </ul>
 *
 * <p>User identity is populated by a separate authentication
 * interceptor that runs after Spring Security, so anonymous requests
 * still get correlation/path tracking.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String HTTP_METHOD_MDC_KEY = "httpMethod";
    public static final String PATH_MDC_KEY = "path";

    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);

        if (correlationId == null || correlationId.isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Per-request MDC fields. Cleared in `finally` so a re-used
        // request thread doesn't carry stale values to the next call.
        MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        MDC.put(HTTP_METHOD_MDC_KEY, request.getMethod());
        MDC.put(PATH_MDC_KEY, request.getRequestURI());

        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
