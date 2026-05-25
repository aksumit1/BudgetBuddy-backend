package com.budgetbuddy.api.response;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or whole controller class) whose JSON
 * response must be served as-is, bypassing the universal
 * {@link ApiResponse} envelope normally applied by
 * {@link ApiResponseWrappingAdvice}.
 *
 * <p>Use this for endpoints with strict, externally-defined JSON
 * contracts that third parties parse by shape — wrapping them in
 * {@code {status, data, error, ...}} would break those consumers.
 * Concrete cases in this service:
 *
 * <ul>
 *   <li>{@code /.well-known/apple-app-site-association} — Apple
 *       Universal Links spec demands the file have {@code applinks}
 *       at the root, not nested under {@code data}.
 *   <li>Other well-known / OAuth / OIDC discovery documents whose
 *       shape is fixed by an external standard.
 * </ul>
 *
 * <p>The exception envelope still applies on errors — only successful
 * bodies are passed through unmodified.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RawResponse {}
