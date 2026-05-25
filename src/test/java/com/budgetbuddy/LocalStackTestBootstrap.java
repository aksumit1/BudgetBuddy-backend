package com.budgetbuddy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-singleton LocalStack container shared by every {@code @SpringBootTest}
 * that runs in this surefire/failsafe JVM. The previous shape forced
 * developers to manually start docker-compose before {@code mvn test} —
 * which is fragile and meant a forgotten {@code docker compose up}
 * silently produced 23 connection-refused errors.
 *
 * <p>Lazy: the container only starts on the first call to {@link
 * #endpoint()}. If Docker isn't available, {@code endpoint()} returns
 * the legacy {@code http://localhost:4566} default so any test that
 * actually has a manually-started LocalStack continues to work, and
 * any test that doesn't have one fails with the same connection-refused
 * error it produced before this class existed.
 *
 * <p>The container is registered with the Testcontainers Ryuk reaper so
 * it shuts down when the JVM exits.
 *
 * <h3>Override knobs</h3>
 *
 * <ul>
 *   <li>{@code -Daws.dynamodb.endpoint=URL} — explicit override, never
 *       starts a container.
 *   <li>{@code DYNAMODB_ENDPOINT=URL} — same, but as an env var (used
 *       by docker-compose-based runs).
 *   <li>{@code -Dbb.tests.no-localstack=true} — skip the Testcontainers
 *       attempt entirely (useful when running only unit tests).
 * </ul>
 */
public final class LocalStackTestBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalStackTestBootstrap.class);

    private static final String SKIP_FLAG = "bb.tests.no-localstack";
    private static final String FALLBACK = "http://localhost:4566";
    private static final DockerImageName IMAGE =
            DockerImageName.parse("localstack/localstack:3.8")
                    .asCompatibleSubstituteFor("localstack/localstack");

    private static volatile String resolvedEndpoint;
    private static volatile LocalStackContainer container;

    private LocalStackTestBootstrap() {}

    /** Resolve the endpoint to point tests at — explicit override → started container → fallback. */
    public static String endpoint() {
        if (resolvedEndpoint != null) return resolvedEndpoint;
        synchronized (LocalStackTestBootstrap.class) {
            if (resolvedEndpoint != null) return resolvedEndpoint;

            // 1. Explicit overrides win.
            final String prop = System.getProperty("aws.dynamodb.endpoint");
            if (prop != null && !prop.isEmpty()) {
                resolvedEndpoint = prop;
                return resolvedEndpoint;
            }
            final String env = System.getenv("DYNAMODB_ENDPOINT");
            if (env != null && !env.isEmpty()) {
                resolvedEndpoint = env;
                return resolvedEndpoint;
            }

            // 2. Caller opted out of the embedded container.
            if (Boolean.getBoolean(SKIP_FLAG)) {
                resolvedEndpoint = FALLBACK;
                return resolvedEndpoint;
            }

            // 3. Start a programmatic LocalStack — but only if Docker is
            // actually reachable. Probing here (instead of letting
            // .start() throw deep in the Docker client) gives a much
            // clearer log line and a faster failure path.
            if (!isDockerAvailable()) {
                LOGGER.warn(
                        "LocalStack auto-bootstrap: Docker not available; falling back to {} "
                                + "(set DYNAMODB_ENDPOINT or start LocalStack manually to override).",
                        FALLBACK);
                resolvedEndpoint = FALLBACK;
                return resolvedEndpoint;
            }

            try {
                container =
                        new LocalStackContainer(IMAGE)
                                .withServices(
                                        LocalStackContainer.Service.DYNAMODB,
                                        LocalStackContainer.Service.S3,
                                        LocalStackContainer.Service.SECRETSMANAGER,
                                        LocalStackContainer.Service.CLOUDWATCH,
                                        LocalStackContainer.Service.IAM,
                                        LocalStackContainer.Service.STS)
                                .withReuse(true);
                container.start();
                resolvedEndpoint = container.getEndpoint().toString();
                LOGGER.info(
                        "LocalStack auto-bootstrap: started container, endpoint={}",
                        resolvedEndpoint);
                propagateToSystemProperties(resolvedEndpoint);
                return resolvedEndpoint;
            } catch (Throwable t) {
                LOGGER.warn(
                        "LocalStack auto-bootstrap: container start failed ({}); falling back to {}.",
                        t.getMessage(),
                        FALLBACK);
                resolvedEndpoint = FALLBACK;
                return resolvedEndpoint;
            }
        }
    }

    /**
     * True when LocalStack is reachable: either {@link #endpoint()}
     * brought up an embedded Testcontainer, OR an external endpoint
     * (via {@code DYNAMODB_ENDPOINT} / {@code aws.dynamodb.endpoint})
     * was supplied. Returns false when {@code endpoint()} fell back to
     * the hardcoded localhost stub — that means tests requiring
     * LocalStack would fail with a connection error, so we'd rather
     * skip them via {@link RequiresLocalStack}.
     */
    public static boolean isAvailable() {
        if (container != null && container.isRunning()) {
            return true;
        }
        // Externally-managed endpoint (real LocalStack running on host).
        if (System.getProperty("aws.dynamodb.endpoint") != null
                || System.getenv("DYNAMODB_ENDPOINT") != null) {
            return true;
        }
        return false;
    }

    private static boolean isDockerAvailable() {
        try {
            return org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Push the resolved endpoint into every property name that the
     * production beans read. {@code DynamoDBConfig} binds
     * {@code app.aws.dynamodb.endpoint} via @Value at bean-construction
     * time — without these overrides it would still resolve the
     * application-test.yml default of {@code localhost:4567}, completely
     * bypassing the test client config.
     */
    private static void propagateToSystemProperties(final String endpoint) {
        System.setProperty("aws.dynamodb.endpoint", endpoint);
        System.setProperty("aws.s3.endpoint", endpoint);
        System.setProperty("DYNAMODB_ENDPOINT", endpoint);
        System.setProperty("AWS_S3_ENDPOINT", endpoint);
        // Spring property names that the main Configuration classes bind:
        System.setProperty("app.aws.dynamodb.endpoint", endpoint);
        System.setProperty("app.aws.s3.endpoint", endpoint);
        System.setProperty("app.aws.secretsmanager.endpoint", endpoint);
        System.setProperty("app.aws.cloudwatch.endpoint", endpoint);
    }
}
