package com.budgetbuddy.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark controller methods that require an authenticated user
 * Eliminates the need for repeated authentication checks in every endpoint
 * 
 * Methods annotated with this will automatically:
 * 1. Verify userDetails is not null
 * 2. Load the UserTable entity
 * 3. Make it available via method parameter injection
 * 
 * Usage:
 * <pre>
 * {@code
 * @RequireAuthenticatedUser
 * @GetMapping
 * public ResponseEntity<List<TransactionTable>> getTransactions(
 *         @AuthenticatedUser UserTable user,
 *         @RequestParam int page) {
 *     // user is guaranteed to be non-null and loaded
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuthenticatedUser {
}

