package com.budgetbuddy.api.response;

import com.budgetbuddy.config.CorrelationIdFilter;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Globally wraps every JSON-bound controller response into the
 * {@link ApiResponse} envelope. iOS clients always receive the same
 * top-level shape: {@code status}, {@code data}, {@code error},
 * {@code correlationId}, {@code timestamp}.
 *
 * <p>The {@code supports()} filter targets Jackson converters only —
 * non-JSON responses (SSE, CSV file downloads, binary payloads,
 * images) flow through their dedicated converters untouched. This is
 * cleaner than listing every body type to skip.
 *
 * <p>Bodies that are already {@link ApiResponse} (e.g. produced by
 * {@code EnhancedGlobalExceptionHandler} for errors) pass through
 * unchanged — wrapping is idempotent so the handler and this advice
 * never collide.
 *
 * <p>The {@code X-Correlation-ID} response header is populated by
 * {@link CorrelationIdFilter} earlier in the chain, so non-JSON
 * responses still carry it for log correlation.
 */
@RestControllerAdvice
public class ApiResponseWrappingAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(
            final MethodParameter returnType,
            final Class<? extends HttpMessageConverter<?>> converterType) {
        // Only wrap when the body is destined for a Jackson JSON
        // converter — SSE, byte[], Resource, String, etc. use their
        // own converters and must pass through unmodified.
        //
        // Spring Boot 4 / Spring 7 ship both flavours of Jackson converter:
        //   - AbstractJackson2HttpMessageConverter (Jackson 2 — what we
        //     pin into the converter chain in WebMvcConfig)
        //   - AbstractJacksonHttpMessageConverter  (Jackson 3 — Spring's
        //     new default, no "2" in the name)
        // Either is a JSON destination; match on simple-name containment
        // so both keep the envelope wrapping behaviour.
        if (!converterType.getSimpleName().contains("Jackson")) {
            return false;
        }
        // Explicit opt-out for endpoints with strict externally-fixed
        // shapes (e.g. apple-app-site-association, OAuth discovery
        // docs). The handler method or its declaring class can carry
        // @RawResponse to skip envelope wrapping entirely.
        if (returnType.hasMethodAnnotation(RawResponse.class)) {
            return false;
        }
        final Class<?> declaringClass = returnType.getDeclaringClass();
        if (declaringClass.isAnnotationPresent(RawResponse.class)) {
            return false;
        }
        return true;
    }

    @Override
    @Nullable
    public Object beforeBodyWrite(
            @Nullable final Object body,
            final MethodParameter returnType,
            final MediaType selectedContentType,
            final Class<? extends HttpMessageConverter<?>> selectedConverterType,
            final ServerHttpRequest request,
            final ServerHttpResponse response) {
        if (body instanceof ApiResponse<?>) {
            // Already wrapped by the exception handler (errors) or by
            // a controller that built one manually. Don't double-wrap.
            return body;
        }
        final String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
        return ApiResponse.ok(body, correlationId);
    }
}
