package com.budgetbuddy.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.contains;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/** Unit Tests for Deployment Safety Service */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class DeploymentSafetyServiceTest {

    @Mock private RestTemplate restTemplate;

    @Mock private RestTemplateBuilder restTemplateBuilder;

    @Mock private org.springframework.core.env.Environment environment;

    private DeploymentSafetyService service;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked") final
                java.util.function.Supplier<org.springframework.http.client.ClientHttpRequestFactory>
                supplier = any(java.util.function.Supplier.class);
        when(restTemplateBuilder.requestFactory(supplier)).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);

        // Mock environment to return false for test profile (unit tests run without test profile)
        // Use lenient stubbing since not all tests use the environment
        lenient()
                .when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class)))
                .thenReturn(false);
        lenient().when(environment.getProperty("spring.profiles.active", "")).thenReturn("");

        service = new DeploymentSafetyService(restTemplateBuilder, environment);

        // Set test values using reflection
        ReflectionTestUtils.setField(service, "healthCheckTimeoutSeconds", 60);
        ReflectionTestUtils.setField(service, "healthCheckIntervalSeconds", 5);
        ReflectionTestUtils.setField(service, "maxHealthCheckAttempts", 3);
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", Collections.emptyList());

        // Set up log appender to capture log events for verification
        logger = (Logger) LoggerFactory.getLogger(DeploymentSafetyService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @Test
    void testValidateDeploymentWithHealthyResponseReturnsHealthy() {
        // Given
        final ResponseEntity<String> response =
                new ResponseEntity<>("{\"status\":\"UP\"}", HttpStatus.OK);
        when(restTemplate.getForEntity(anyString(), eq(String.class))).thenReturn(response);

        // When
        final DeploymentSafetyService.DeploymentValidationResult result =
                service.validateDeployment("http://localhost:8080");

        // Then
        assertNotNull(result);
        assertTrue(result.isHealthy());
        assertNull(result.getErrorMessage());
        assertNotNull(result.getTimestamp());
    }

    @Test
    void testValidateDeploymentWithEmptyBaseUrlReturnsUnhealthy() {
        // When
        final DeploymentSafetyService.DeploymentValidationResult result = service.validateDeployment("");

        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
        assertEquals("Base URL is null or empty", result.getErrorMessage());

        // Verify logging behavior - should log WARN for null/empty base URL (configuration issue,
        // not critical error)
        final List<ILoggingEvent> logEvents = logAppender.list;
        final long warnLogs =
                logEvents.stream()
                        .filter(
                                event ->
                                        event.getLevel() == Level.WARN
                                                && event.getMessage()
                                                .contains("Base URL is null or empty"))
                        .count();

        assertEquals(1, warnLogs, "Should log WARN when base URL is empty");
    }

    @Test
    void testValidateDeploymentWithNullBaseUrlReturnsUnhealthy() {
        // When
        final DeploymentSafetyService.DeploymentValidationResult result =
                service.validateDeployment(null);

        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
        assertEquals("Base URL is null or empty", result.getErrorMessage());
    }

    @Test
    void testValidateDeploymentWithFailedHealthCheckReturnsUnhealthy() {
        // Given
        final RestClientException exception = new RestClientException("Connection refused");
        when(restTemplate.getForEntity(anyString(), eq(String.class))).thenThrow(exception);

        // When
        final DeploymentSafetyService.DeploymentValidationResult result =
                service.validateDeployment("http://localhost:8080");

        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
        // Error message should be set from the exception message
        assertNotNull(
                result.getErrorMessage(), "Error message should be set when health check fails");
        assertEquals("Connection refused", result.getErrorMessage());
    }

    @Test
    void testValidateDeploymentWithNonUpStatusReturnsUnhealthy() {
        // Given
        final ResponseEntity<String> response =
                new ResponseEntity<>("{\"status\":\"DOWN\"}", HttpStatus.OK);
        when(restTemplate.getForEntity(anyString(), eq(String.class))).thenReturn(response);

        // When
        final DeploymentSafetyService.DeploymentValidationResult result =
                service.validateDeployment("http://localhost:8080");

        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
    }

    @Test
    void testRunSmokeTestsWithEmptyEndpointsReturnsPassed() {
        // Given
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", Collections.emptyList());

        // When
        final DeploymentSafetyService.SmokeTestResult result =
                service.runSmokeTests("http://localhost:8080");

        // Then
        assertNotNull(result);
        assertTrue(result.isPassed());
        assertEquals(0, result.getPassedTests());
        assertEquals(0, result.getFailedTests());
    }

    @Test
    void testRunSmokeTestsWithNullEndpointsReturnsPassed() {
        // Given
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", null);

        // When
        final DeploymentSafetyService.SmokeTestResult result =
                service.runSmokeTests("http://localhost:8080");

        // Then
        assertNotNull(result);
        assertTrue(result.isPassed());
    }

    @Test
    void testRunSmokeTestsWithSuccessfulEndpointsReturnsPassed() {
        // Given
        final List<String> endpoints = List.of("/api/health", "/api/status");
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", endpoints);

        final ResponseEntity<String> response = new ResponseEntity<>("OK", HttpStatus.OK);
        when(restTemplate.getForEntity(anyString(), eq(String.class))).thenReturn(response);

        // When
        final DeploymentSafetyService.SmokeTestResult result =
                service.runSmokeTests("http://localhost:8080");

        // Then
        assertNotNull(result);
        assertTrue(result.isPassed());
        assertEquals(2, result.getPassedTests());
        assertEquals(0, result.getFailedTests());
    }

    @Test
    void testRunSmokeTestsWithFailedEndpointsReturnsFailed() {
        // Given
        final List<String> endpoints = List.of("/api/health");
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", endpoints);

        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        // When
        final DeploymentSafetyService.SmokeTestResult result =
                service.runSmokeTests("http://localhost:8080");

        // Then
        assertNotNull(result);
        assertFalse(result.isPassed());
        assertEquals(0, result.getPassedTests());
        assertEquals(1, result.getFailedTests());

        // Verify logging behavior - should log WARN for smoke test failures (handled gracefully)
        final List<ILoggingEvent> logEvents = logAppender.list;
        final long warnLogs =
                logEvents.stream()
                        .filter(
                                event ->
                                        event.getLevel() == Level.WARN
                                                && event.getMessage().contains("Smoke test error"))
                        .count();

        assertEquals(1, warnLogs, "Should log WARN for smoke test connection errors");

        // Verify INFO log for completion
        final boolean foundInfoLog =
                logEvents.stream()
                        .anyMatch(
                                event ->
                                        event.getLevel() == Level.INFO
                                                && event.getMessage()
                                                .contains("Smoke tests completed"));
        assertTrue(foundInfoLog, "Should log INFO when smoke tests complete");
    }

    @Test
    void testRunSmokeTestsWithEmptyBaseUrlReturnsFailed() {
        // When
        final DeploymentSafetyService.SmokeTestResult result = service.runSmokeTests("");

        // Then
        assertNotNull(result);
        assertFalse(result.isPassed());

        // Verify logging behavior - should log WARN for null/empty base URL (configuration issue,
        // not critical error)
        final List<ILoggingEvent> logEvents = logAppender.list;
        final long warnLogs =
                logEvents.stream()
                        .filter(
                                event ->
                                        event.getLevel() == Level.WARN
                                                && event.getMessage()
                                                .contains(
                                                        "Base URL is null or empty for smoke tests"))
                        .count();

        assertEquals(1, warnLogs, "Should log WARN when base URL is empty for smoke tests");
    }

    @Test
    void testIsDeploymentReadyWithHealthyAndPassedSmokeTestsReturnsTrue() {
        // Given
        final ResponseEntity<String> healthResponse =
                new ResponseEntity<>("{\"status\":\"UP\"}", HttpStatus.OK);
        when(restTemplate.getForEntity(contains("/actuator/health"), eq(String.class)))
                .thenReturn(healthResponse);
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", Collections.emptyList());

        // When
        final boolean isReady = service.isDeploymentReady("http://localhost:8080");

        // Then
        assertTrue(isReady);
    }

    @Test
    void testIsDeploymentReadyWithUnhealthyReturnsFalse() {
        // Given
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));

        // When
        final boolean isReady = service.isDeploymentReady("http://localhost:8080");

        // Then
        assertFalse(isReady);
    }

    @Test
    void testDeploymentValidationResultSettersAndGetters() {
        // Given
        final DeploymentSafetyService.DeploymentValidationResult result =
                new DeploymentSafetyService.DeploymentValidationResult();

        // When
        result.setHealthy(true);
        result.setDuration(Duration.ofSeconds(10));
        result.setErrorMessage("test error");
        result.setTimestamp(Instant.now());

        // Then
        assertTrue(result.isHealthy());
        assertEquals(10, result.getDuration().getSeconds());
        assertEquals("test error", result.getErrorMessage());
        assertNotNull(result.getTimestamp());
    }

    @Test
    void testSmokeTestResultSettersAndGetters() {
        // Given
        final DeploymentSafetyService.SmokeTestResult result =
                new DeploymentSafetyService.SmokeTestResult();

        // When
        result.setBaseUrl("http://localhost:8080");
        result.setPassed(true);
        result.setPassedTests(5);
        result.setFailedTests(0);
        result.setStartTime(Instant.now());
        result.setEndTime(Instant.now());

        // Then
        assertEquals("http://localhost:8080", result.getBaseUrl());
        assertTrue(result.isPassed());
        assertEquals(5, result.getPassedTests());
        assertEquals(0, result.getFailedTests());
        assertNotNull(result.getStartTime());
        assertNotNull(result.getEndTime());
    }
}
