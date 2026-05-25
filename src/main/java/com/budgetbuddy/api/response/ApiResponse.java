package com.budgetbuddy.api.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Map;

/**
 * Universal wire envelope for every HTTP response from this service.
 *
 * <p>iOS knows exactly where to look: {@code data} on success;
 * {@code error.code} / {@code error.message} / {@code error.validationErrors}
 * on failure; {@code correlationId} for log correlation in support
 * tickets. The wrapping is applied globally by
 * {@link ApiResponseWrappingAdvice} (success) and by
 * {@code EnhancedGlobalExceptionHandler} (failure) — controllers
 * return their domain DTO and never touch this record directly.
 *
 * <p>{@link JsonInclude} omits null fields so the success path is
 * compact (no empty {@code error}) and validation errors only appear
 * when populated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        Status status,
        @Nullable T data,
        @Nullable ApiError error,
        String correlationId,
        Instant timestamp) {

    public enum Status { ok, error }

    public static <T> ApiResponse<T> ok(final T data, final String correlationId) {
        return new ApiResponse<>(Status.ok, data, null, correlationId, Instant.now());
    }

    public static <T> ApiResponse<T> error(
            final String code, final String message, final String correlationId) {
        return error(code, message, correlationId, null, null);
    }

    public static <T> ApiResponse<T> error(
            final String code,
            final String message,
            final String correlationId,
            @Nullable final Map<String, String> validationErrors,
            @Nullable final Map<String, Object> details) {
        return new ApiResponse<>(
                Status.error,
                null,
                new ApiError(code, message, validationErrors, details),
                correlationId,
                Instant.now());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(
            String code,
            String message,
            @Nullable Map<String, String> validationErrors,
            @Nullable Map<String, Object> details) {}
}
