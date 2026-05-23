package com.budgetbuddy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Earliest hook to inject the LocalStack endpoint into Spring's property
 * environment — runs BEFORE any {@code @Configuration} class is processed,
 * so {@link com.budgetbuddy.config.DynamoDBConfig} (which binds
 * {@code app.aws.dynamodb.endpoint} via @Value) sees the bootstrap-resolved
 * URL instead of the {@code application-test.yml} default of
 * {@code localhost:4567}.
 *
 * <p>Registered in {@code META-INF/spring.factories}. Only active when
 * the {@code test} profile is selected (we still bootstrap unconditionally
 * — the property push is a no-op outside the test profile).
 */
public class LocalStackEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "localStackTestBootstrap";

    @Override
    public void postProcessEnvironment(
            final ConfigurableEnvironment environment, final SpringApplication application) {
        // Only intervene under the test profile. Production / dev environments
        // must never see this hijack the endpoint.
        boolean testProfile = false;
        for (final String p : environment.getActiveProfiles()) {
            if ("test".equalsIgnoreCase(p)) {
                testProfile = true;
                break;
            }
        }
        if (!testProfile) {
            // Also respect the default-profile case: SpringBootTest sets the
            // active profile *after* this runs in some boot versions, but the
            // ConfigurableEnvironment exposes a "spring.profiles.active" key
            // as soon as @ActiveProfiles is processed.
            final String activeProp = environment.getProperty("spring.profiles.active", "");
            if (!activeProp.toLowerCase(java.util.Locale.ROOT).contains("test")) {
                return;
            }
        }
        final String endpoint = LocalStackTestBootstrap.endpoint();
        final java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put("app.aws.dynamodb.endpoint", endpoint);
        props.put("app.aws.s3.endpoint", endpoint);
        props.put("app.aws.secretsmanager.endpoint", endpoint);
        props.put("app.aws.cloudwatch.endpoint", endpoint);
        props.put("aws.dynamodb.endpoint", endpoint);
        props.put("aws.s3.endpoint", endpoint);
        props.put("DYNAMODB_ENDPOINT", endpoint);
        props.put("AWS_S3_ENDPOINT", endpoint);
        // Insert with high precedence so application-test.yml's defaults lose.
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
    }
}
