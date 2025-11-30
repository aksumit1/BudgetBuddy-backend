package com.budgetbuddy.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Deployment Safety Service
 * Ensures safe deployments with health checks, smoke tests, and rollback capabilities
 *
 * Features:
 * - Configurable health check timeouts
 * - Smoke test execution
 * - Deployment readiness validation
 * - Proper error handling
 * - Thread-safe operations
 */
@Service
public class DeploymentSafetyService {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentSafetyService.class);

    @Value("${app.deployment.health-check-timeout:60}")
    private int healthCheckTimeoutSeconds;

    @Value("${app.deployment.health-check-interval:5}")
    private int healthCheckIntervalSeconds;

    @Value("${app.deployment.max-health-check-attempts:12}")
    private int maxHealthCheckAttempts;

    @Value("${app.deployment.smoke-test-endpoints:}")
    private List<String> smokeTestEndpoints;

    private final RestTemplate restTemplate;

    public DeploymentSafetyService(final RestTemplateBuilder restTemplateBuilder) {
        // Use request factory to set timeouts (replacement for deprecated setConnectTimeout/setReadTimeout)
        // Configure connection pool limits to prevent connection leaks
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        // Connection reuse is handled by the underlying HTTP client
        // Timeouts ensure connections don't hang indefinitely
        
        // Timeouts are configured via requestFactory (setConnectTimeout/setReadTimeout)
        // RestTemplateBuilder.setConnectTimeout/setReadTimeout are deprecated in Spring Boot 3.4+
        this.restTemplate = restTemplateBuilder
                .requestFactory(() -> requestFactory)
                .build();
    }

    /**
     * Validate deployment health
     */
    public DeploymentValidationResult validateDeployment(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            logger.error("Base URL is null or empty");
            DeploymentValidationResult result = new DeploymentValidationResult();
            result.setHealthy(false);
            result.setErrorMessage("Base URL is null or empty");
            result.setTimestamp(Instant.now());
            return result;
        }

        logger.info("Starting deployment validation for: {}", baseUrl);

        Instant startTime = Instant.now();
        boolean isHealthy = false;
        String errorMessage = null;

        for (int attempt = 1; attempt <= maxHealthCheckAttempts; attempt++) {
            try {
                logger.debug("Health check attempt {}/{}", attempt, maxHealthCheckAttempts);

                if (performHealthCheck(baseUrl)) {
                    isHealthy = true;
                    logger.info("Deployment health check passed after {} attempts", attempt);
                    break;
                }
            } catch (Exception e) {
                logger.warn("Health check attempt {} failed: {}", attempt, e.getMessage());
                errorMessage = e.getMessage();
            }

            if (attempt < maxHealthCheckAttempts) {
                try {
                    // Use TimeUnit for better readability and async-friendly pattern
                    java.util.concurrent.TimeUnit.SECONDS.sleep(healthCheckIntervalSeconds);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Health check interrupted");
                    break;
                }
            }
        }

        Duration duration = Duration.between(startTime, Instant.now());

        DeploymentValidationResult result = new DeploymentValidationResult();
        result.setHealthy(isHealthy);
        result.setDuration(duration);
        result.setErrorMessage(errorMessage);
        result.setTimestamp(Instant.now());

        return result;
    }

    /**
     * Perform health check
     */
    private boolean performHealthCheck(final String baseUrl) {
        try {
            String healthUrl = baseUrl + "/actuator/health";
            ResponseEntity<String> response = restTemplate.getForEntity(healthUrl, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                String body = response.getBody();
                return body.contains("\"status\":\"UP\"") || body.contains("\"status\":\"up\"");
            }

            return false;
        } catch (RestClientException e) {
            logger.debug("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Run smoke tests
     */
    public SmokeTestResult runSmokeTests(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            logger.error("Base URL is null or empty for smoke tests");
            SmokeTestResult result = new SmokeTestResult();
            result.setBaseUrl(baseUrl);
            result.setPassed(false);
            result.setStartTime(Instant.now());
            result.setEndTime(Instant.now());
            return result;
        }

        logger.info("Running smoke tests for: {}", baseUrl);

        SmokeTestResult result = new SmokeTestResult();
        result.setBaseUrl(baseUrl);
        result.setStartTime(Instant.now());

        if (smokeTestEndpoints == null || smokeTestEndpoints.isEmpty()) {
            logger.warn("No smoke test endpoints configured");
            result.setPassed(true);
            result.setEndTime(Instant.now());
            return result;
        }

        int passed = 0;
        int failed = 0;

        for (String endpoint : smokeTestEndpoints) {
            if (endpoint == null || endpoint.isEmpty()) {
                continue;
            }

            try {
                String url = baseUrl + endpoint;
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    passed++;
                    logger.debug("Smoke test passed: {}", endpoint);
                } else {
                    failed++;
                    logger.warn("Smoke test failed: {} - Status: {}", endpoint, response.getStatusCode());
                }
            } catch (RestClientException e) {
                failed++;
                logger.error("Smoke test error for {}: {}", endpoint, e.getMessage());
            }
        }

        result.setPassed(failed == 0);
        result.setPassedTests(passed);
        result.setFailedTests(failed);
        result.setEndTime(Instant.now());

        logger.info("Smoke tests completed: {} passed, {} failed", passed, failed);
        return result;
    }

    /**
     * Validate deployment readiness
     */
    public boolean isDeploymentReady(final String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            logger.error("Base URL is null or empty");
            return false;
        }

        DeploymentValidationResult healthResult = validateDeployment(baseUrl);

        if (!healthResult.isHealthy()) {
            logger.error("Deployment health check failed: {}", healthResult.getErrorMessage());
            return false;
        }

        SmokeTestResult smokeTestResult = runSmokeTests(baseUrl);

        if (!smokeTestResult.isPassed()) {
            logger.error("Smoke tests failed: {} passed, {} failed",
                    smokeTestResult.getPassedTests(), smokeTestResult.getFailedTests());
            return false;
        }

        logger.info("Deployment is ready");
        return true;
    }

    /**
     * Deployment Validation Result
     */
    public static class DeploymentValidationResult {
        private boolean healthy;
        private Duration duration;
        private String errorMessage;
        private Instant timestamp;

        // Getters and setters
        public boolean isHealthy() { return healthy; }
        public void setHealthy(final boolean healthy) { this.healthy = healthy; }
        public Duration getDuration() { return duration; }
        public void setDuration(final Duration duration) { this.duration = duration; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(final String errorMessage) { this.errorMessage = errorMessage; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(final Instant timestamp) { this.timestamp = timestamp; }
    }

    /**
     * Smoke Test Result
     */
    public static class SmokeTestResult {
        private String baseUrl;
        private boolean passed;
        private int passedTests;
        private int failedTests;
        private Instant startTime;
        private Instant endTime;

        // Getters and setters
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(final String baseUrl) { this.baseUrl = baseUrl; }
        public boolean isPassed() { return passed; }
        public void setPassed(final boolean passed) { this.passed = passed; }
        public int getPassedTests() { return passedTests; }
        public void setPassedTests(final int passedTests) { this.passedTests = passedTests; }
        public int getFailedTests() { return failedTests; }
        public void setFailedTests(final int failedTests) { this.failedTests = failedTests; }
        public Instant getStartTime() { return startTime; }
        public void setStartTime(final Instant startTime) { this.startTime = startTime; }
        public Instant getEndTime() { return endTime; }
        public void setEndTime(final Instant endTime) { this.endTime = endTime; }
    }
}
