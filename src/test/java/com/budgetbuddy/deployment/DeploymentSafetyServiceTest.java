package com.budgetbuddy.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for Deployment Safety Service
 */
@ExtendWith(MockitoExtension.class)
class DeploymentSafetyServiceTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    private DeploymentSafetyService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        java.util.function.Supplier<org.springframework.http.client.ClientHttpRequestFactory> supplier = any(java.util.function.Supplier.class);
        when(restTemplateBuilder.requestFactory(supplier)).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        
        service = new DeploymentSafetyService(restTemplateBuilder);
        
        // Set test values using reflection
        ReflectionTestUtils.setField(service, "healthCheckTimeoutSeconds", 60);
        ReflectionTestUtils.setField(service, "healthCheckIntervalSeconds", 5);
        ReflectionTestUtils.setField(service, "maxHealthCheckAttempts", 3);
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", Collections.emptyList());
    }

    @Test
    void testValidateDeployment_WithHealthyResponse_ReturnsHealthy() {
        // Given
        ResponseEntity<String> response = new ResponseEntity<>("{\"status\":\"UP\"}", HttpStatus.OK);
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(response);
        
        // When
        DeploymentSafetyService.DeploymentValidationResult result = 
                service.validateDeployment("http://localhost:8080");
        
        // Then
        assertNotNull(result);
        assertTrue(result.isHealthy());
        assertNull(result.getErrorMessage());
        assertNotNull(result.getTimestamp());
    }

    @Test
    void testValidateDeployment_WithEmptyBaseUrl_ReturnsUnhealthy() {
        // When
        DeploymentSafetyService.DeploymentValidationResult result = 
                service.validateDeployment("");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
        assertEquals("Base URL is null or empty", result.getErrorMessage());
    }

    @Test
    void testValidateDeployment_WithNullBaseUrl_ReturnsUnhealthy() {
        // When
        DeploymentSafetyService.DeploymentValidationResult result = 
                service.validateDeployment(null);
        
        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
        assertEquals("Base URL is null or empty", result.getErrorMessage());
    }

    @Test
    void testValidateDeployment_WithFailedHealthCheck_ReturnsUnhealthy() {
        // Given
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));
        
        // When
        DeploymentSafetyService.DeploymentValidationResult result = 
                service.validateDeployment("http://localhost:8080");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    void testValidateDeployment_WithNonUpStatus_ReturnsUnhealthy() {
        // Given
        ResponseEntity<String> response = new ResponseEntity<>("{\"status\":\"DOWN\"}", HttpStatus.OK);
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(response);
        
        // When
        DeploymentSafetyService.DeploymentValidationResult result = 
                service.validateDeployment("http://localhost:8080");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isHealthy());
    }

    @Test
    void testRunSmokeTests_WithEmptyEndpoints_ReturnsPassed() {
        // Given
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", Collections.emptyList());
        
        // When
        DeploymentSafetyService.SmokeTestResult result = 
                service.runSmokeTests("http://localhost:8080");
        
        // Then
        assertNotNull(result);
        assertTrue(result.isPassed());
        assertEquals(0, result.getPassedTests());
        assertEquals(0, result.getFailedTests());
    }

    @Test
    void testRunSmokeTests_WithNullEndpoints_ReturnsPassed() {
        // Given
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", null);
        
        // When
        DeploymentSafetyService.SmokeTestResult result = 
                service.runSmokeTests("http://localhost:8080");
        
        // Then
        assertNotNull(result);
        assertTrue(result.isPassed());
    }

    @Test
    void testRunSmokeTests_WithSuccessfulEndpoints_ReturnsPassed() {
        // Given
        List<String> endpoints = List.of("/api/health", "/api/status");
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", endpoints);
        
        ResponseEntity<String> response = new ResponseEntity<>("OK", HttpStatus.OK);
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(response);
        
        // When
        DeploymentSafetyService.SmokeTestResult result = 
                service.runSmokeTests("http://localhost:8080");
        
        // Then
        assertNotNull(result);
        assertTrue(result.isPassed());
        assertEquals(2, result.getPassedTests());
        assertEquals(0, result.getFailedTests());
    }

    @Test
    void testRunSmokeTests_WithFailedEndpoints_ReturnsFailed() {
        // Given
        List<String> endpoints = List.of("/api/health");
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", endpoints);
        
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));
        
        // When
        DeploymentSafetyService.SmokeTestResult result = 
                service.runSmokeTests("http://localhost:8080");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isPassed());
        assertEquals(0, result.getPassedTests());
        assertEquals(1, result.getFailedTests());
    }

    @Test
    void testRunSmokeTests_WithEmptyBaseUrl_ReturnsFailed() {
        // When
        DeploymentSafetyService.SmokeTestResult result = 
                service.runSmokeTests("");
        
        // Then
        assertNotNull(result);
        assertFalse(result.isPassed());
    }

    @Test
    void testIsDeploymentReady_WithHealthyAndPassedSmokeTests_ReturnsTrue() {
        // Given
        ResponseEntity<String> healthResponse = new ResponseEntity<>("{\"status\":\"UP\"}", HttpStatus.OK);
        when(restTemplate.getForEntity(contains("/actuator/health"), eq(String.class)))
                .thenReturn(healthResponse);
        ReflectionTestUtils.setField(service, "smokeTestEndpoints", Collections.emptyList());
        
        // When
        boolean isReady = service.isDeploymentReady("http://localhost:8080");
        
        // Then
        assertTrue(isReady);
    }

    @Test
    void testIsDeploymentReady_WithUnhealthy_ReturnsFalse() {
        // Given
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new RestClientException("Connection refused"));
        
        // When
        boolean isReady = service.isDeploymentReady("http://localhost:8080");
        
        // Then
        assertFalse(isReady);
    }

    @Test
    void testDeploymentValidationResult_SettersAndGetters() {
        // Given
        DeploymentSafetyService.DeploymentValidationResult result = 
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
    void testSmokeTestResult_SettersAndGetters() {
        // Given
        DeploymentSafetyService.SmokeTestResult result = 
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

