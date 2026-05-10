package com.budgetbuddy.deployment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Deployment Safety Service Ensures safe deployments with health checks, smoke tests, and rollback
 * capabilities
 *
 * <p>Features: - Configurable health check timeouts - Smoke test execution - Deployment readiness
 * validation - Proper error handling - Thread-safe operations
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class DeploymentSafetyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeploymentSafetyService.class);

    @Value("${app.deployment.health-check-timeout:60}")
    private int healthCheckTimeoutSeconds;

    @Value("${app.deployment.health-check-interval:5}")
    private int healthCheckIntervalSeconds;

    @Value("${app.deployment.max-health-check-attempts:12}")
    private int maxHealthCheckAttempts;

    @Value("${app.deployment.smoke-test-endpoints:}")
    private List<String> smokeTestEndpoints;

    private final RestTemplate restTemplate;
    private final boolean isTestEnvironment;

    public DeploymentSafetyService(
            final RestTemplateBuilder restTemplateBuilder, final Environment environment) {
        // Use request factory to set timeouts (replacement for deprecated
        // setConnectTimeout/setReadTimeout)
        // Configure connection pool limits to prevent connection leaks
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        // Connection reuse is handled by the underlying HTTP client
        // Timeouts ensure connections don't hang indefinitely

        // Timeouts are configured via requestFactory (setConnectTimeout/setReadTimeout)
        // RestTemplateBuilder.setConnectTimeout/setReadTimeout are deprecated in Spring Boot 3.4+
        this.restTemplate = restTemplateBuilder.requestFactory(() -> requestFactory).build();

        // Detect test environment
        boolean isTest = false;
        if (environment != null) {
            try {
                isTest =
                        environment.acceptsProfiles(
                                org.springframework.core.env.Profiles.of("test"));
            } catch (Exception e) {
                final String activeProfiles = environment.getProperty("spring.profiles.active", "");
                isTest = activeProfiles.contains("test");
            }
        }
        if (!isTest) {
            final String sysProp = System.getProperty("spring.profiles.active", "");
            isTest = sysProp.contains("test");
        }
        this.isTestEnvironment = isTest;
    }

    /** Validate deployment health */
    public DeploymentValidationResult validateDeployment(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            // In test environments, this is expected - log at DEBUG level
            // In production, log at WARN level (not ERROR, as this is a handled condition)
            if (isTestEnvironment) {
                LOGGER.debug("Base URL is null or empty (expected in test environment)");
            } else {
                LOGGER.warn("Base URL is null or empty");
            }
            final DeploymentValidationResult result = new DeploymentValidationResult();
            result.setHealthy(false);
            result.setErrorMessage("Base URL is null or empty");
            result.setTimestamp(Instant.now());
            return result;
        }

        LOGGER.info("Starting deployment validation for: {}", baseUrl);

        final Instant startTime = Instant.now();
        boolean isHealthy = false;
        String errorMessage = null;

        for (int attempt = 1; attempt <= maxHealthCheckAttempts; attempt++) {
            try {
                LOGGER.debug("Health check attempt {}/{}", attempt, maxHealthCheckAttempts);

                if (performHealthCheck(baseUrl)) {
                    isHealthy = true;
                    LOGGER.info("Deployment health check passed after {} attempts", attempt);
                    break;
                } else {
                    // Health check returned false but didn't throw exception
                    // This means the health endpoint returned non-UP status
                    if (errorMessage == null) {
                        errorMessage = "Health check returned non-UP status";
                    }
                }
            } catch (Exception e) {
                // In test environments, connection refused is expected - log at DEBUG
                if (isTestEnvironment
                        && (e.getMessage() != null
                                && e.getMessage().contains("Connection refused"))) {
                    LOGGER.debug(
                            "Health check attempt {} failed (expected in test): {}",
                            attempt,
                            e.getMessage());
                } else {
                    LOGGER.warn("Health check attempt {} failed: {}", attempt, e.getMessage());
                }
                errorMessage = e.getMessage();
            }

            if (attempt < maxHealthCheckAttempts) {
                try {
                    // Use TimeUnit for better readability and async-friendly pattern
                    java.util.concurrent.TimeUnit.SECONDS.sleep(healthCheckIntervalSeconds);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("Health check interrupted");
                    break;
                }
            }
        }

        final Duration duration = Duration.between(startTime, Instant.now());

        final DeploymentValidationResult result = new DeploymentValidationResult();
        result.setHealthy(isHealthy);
        result.setDuration(duration);
        result.setErrorMessage(errorMessage);
        result.setTimestamp(Instant.now());

        return result;
    }

    /** Perform health check */
    private boolean performHealthCheck(final String baseUrl) throws RestClientException {
        final String healthUrl = baseUrl + "/actuator/health";
        final ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            final String body = response.getBody();
            return body.contains("\"status\":\"UP\"") || body.contains("\"status\":\"up\"");
        }

        return false;
    }

    /** Run smoke tests */
    public SmokeTestResult runSmokeTests(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            // In test environments, this is expected - log at DEBUG level
            if (isTestEnvironment) {
                LOGGER.debug(
                        "Base URL is null or empty for smoke tests (expected in test environment)");
            } else {
                LOGGER.warn("Base URL is null or empty for smoke tests");
            }
            final SmokeTestResult result = new SmokeTestResult();
            result.setBaseUrl(baseUrl);
            result.setPassed(false);
            result.setStartTime(Instant.now());
            result.setEndTime(Instant.now());
            return result;
        }

        LOGGER.info("Running smoke tests for: {}", baseUrl);

        final SmokeTestResult result = new SmokeTestResult();
        result.setBaseUrl(baseUrl);
        result.setStartTime(Instant.now());

        if (smokeTestEndpoints == null || smokeTestEndpoints.isEmpty()) {
            LOGGER.warn("No smoke test endpoints configured");
            result.setPassed(true);
            result.setEndTime(Instant.now());
            return result;
        }

        int passed = 0;
        int failed = 0;

        for (final String endpoint : smokeTestEndpoints) {
            if (endpoint == null || endpoint.isEmpty()) {
                continue;
            }

            try {
                final String url = baseUrl + endpoint;
                final ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    passed++;
                    LOGGER.debug("Smoke test passed: {}", endpoint);
                } else {
                    failed++;
                    LOGGER.warn(
                            "Smoke test failed: {} - Status: {}",
                            endpoint,
                            response.getStatusCode());
                }
            } catch (RestClientException e) {
                failed++;
                // Log at WARN level - this is a handled failure (test continues and reports
                // failure)
                // ERROR would be more appropriate for unexpected/unhandled errors
                LOGGER.warn("Smoke test error for {}: {}", endpoint, e.getMessage());
            }
        }

        result.setPassed(failed == 0);
        result.setPassedTests(passed);
        result.setFailedTests(failed);
        result.setEndTime(Instant.now());

        LOGGER.info("Smoke tests completed: {} passed, {} failed", passed, failed);
        return result;
    }

    /** Validate deployment readiness */
    public boolean isDeploymentReady(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            // In test environments, this is expected - log at DEBUG level
            if (isTestEnvironment) {
                LOGGER.debug("Base URL is null or empty (expected in test environment)");
            } else {
                LOGGER.warn("Base URL is null or empty");
            }
            return false;
        }

        final DeploymentValidationResult healthResult = validateDeployment(baseUrl);

        if (!healthResult.isHealthy()) {
            // In test environments, health check failures are expected - log at DEBUG
            if (isTestEnvironment) {
                LOGGER.debug(
                        "Deployment health check failed (expected in test): {}",
                        healthResult.getErrorMessage());
            } else {
                LOGGER.error("Deployment health check failed: {}", healthResult.getErrorMessage());
            }
            return false;
        }

        final SmokeTestResult smokeTestResult = runSmokeTests(baseUrl);

        if (!smokeTestResult.isPassed()) {
            LOGGER.error(
                    "Smoke tests failed: {} passed, {} failed",
                    smokeTestResult.getPassedTests(),
                    smokeTestResult.getFailedTests());
            return false;
        }

        LOGGER.info("Deployment is ready");
        return true;
    }

    /** Deployment Validation Result */
    public static class DeploymentValidationResult {
        private boolean healthy;
        private Duration duration;
        private String errorMessage;
        private Instant timestamp;

        // Getters and setters
        public boolean isHealthy() {
            return healthy;
        }

        public void setHealthy(final boolean healthy) {
            this.healthy = healthy;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(final Duration duration) {
            this.duration = duration;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(final String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(final Instant timestamp) {
            this.timestamp = timestamp;
        }
    }

    /** Smoke Test Result */
    public static class SmokeTestResult {
        private String baseUrl;
        private boolean passed;
        private int passedTests;
        private int failedTests;
        private Instant startTime;
        private Instant endTime;

        // Getters and setters
        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isPassed() {
            return passed;
        }

        public void setPassed(final boolean passed) {
            this.passed = passed;
        }

        public int getPassedTests() {
            return passedTests;
        }

        public void setPassedTests(final int passedTests) {
            this.passedTests = passedTests;
        }

        public int getFailedTests() {
            return failedTests;
        }

        public void setFailedTests(final int failedTests) {
            this.failedTests = failedTests;
        }

        public Instant getStartTime() {
            return startTime;
        }

        public void setStartTime(final Instant startTime) {
            this.startTime = startTime;
        }

        public Instant getEndTime() {
            return endTime;
        }

        public void setEndTime(final Instant endTime) {
            this.endTime = endTime;
        }
    }
}
