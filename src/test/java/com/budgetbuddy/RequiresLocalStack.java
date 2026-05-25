package com.budgetbuddy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Tag tests that need a reachable LocalStack endpoint (Docker-backed
 * or manually started). Tests annotated with this either run normally,
 * or get skipped with a clear log line when LocalStack is unreachable
 * — instead of failing deep in the AWS SDK with an inscrutable
 * connection error.
 *
 * <p>Usage:
 * <pre>
 *   {@literal @}RequiresLocalStack
 *   class MyIntegrationTest { ... }
 * </pre>
 *
 * <p>CI without Docker: set {@code -Dskip.localstack=true} OR omit
 * {@code DYNAMODB_ENDPOINT} — annotated tests skip; non-annotated
 * tests run as before.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("requires-localstack")
@ExtendWith(RequiresLocalStack.LocalStackReachableCheck.class)
public @interface RequiresLocalStack {

    /** JUnit extension that aborts the test class when LocalStack is unreachable. */
    final class LocalStackReachableCheck implements BeforeAllCallback {
        @Override
        public void beforeAll(final ExtensionContext context) {
            Assumptions.assumeTrue(
                    LocalStackTestBootstrap.isAvailable(),
                    "Skipping " + context.getDisplayName()
                            + ": LocalStack endpoint not reachable. Start Docker or "
                            + "set DYNAMODB_ENDPOINT to a running LocalStack to enable this test.");
        }
    }
}
