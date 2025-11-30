package com.budgetbuddy.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionResponse;

import java.nio.ByteBuffer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AppConfigIntegration
 * 
 */
@ExtendWith(MockitoExtension.class)
class AppConfigIntegrationTest {

    @Mock
    private AppConfigDataClient appConfigDataClient;

    @InjectMocks
    private AppConfigIntegration appConfigIntegration;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(appConfigIntegration, "applicationName", "test-app");
        ReflectionTestUtils.setField(appConfigIntegration, "appConfigEnvironment", "test");
        ReflectionTestUtils.setField(appConfigIntegration, "configProfile", "default");
        ReflectionTestUtils.setField(appConfigIntegration, "refreshIntervalSeconds", 60L);
    }

    @Test
    void testGetConfigValue_WithValidKey_ReturnsValue() {
        // Given
        String testConfig = "{\"testKey\":\"testValue\"}";
        java.util.concurrent.atomic.AtomicReference<String> latestConfig = 
            (java.util.concurrent.atomic.AtomicReference<String>) ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);
        
        // Also need to parse and set parsedConfiguration
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                (com.fasterxml.jackson.databind.ObjectMapper) ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> parsedConfig = 
                (java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>) 
                ReflectionTestUtils.getField(appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        Optional<String> value = appConfigIntegration.getConfigValue("testKey");

        // Then
        assertTrue(value.isPresent());
        assertEquals("testValue", value.get());
    }

    @Test
    void testGetConfigValue_WithInvalidKey_ReturnsEmpty() {
        // Given
        String testConfig = "{\"testKey\":\"testValue\"}";
        java.util.concurrent.atomic.AtomicReference<String> latestConfig = 
            (java.util.concurrent.atomic.AtomicReference<String>) ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);
        
        // Also need to parse and set parsedConfiguration
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                (com.fasterxml.jackson.databind.ObjectMapper) ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> parsedConfig = 
                (java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>) 
                ReflectionTestUtils.getField(appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        Optional<String> value = appConfigIntegration.getConfigValue("nonExistentKey");

        // Then
        assertFalse(value.isPresent());
    }

    @Test
    void testGetConfigValueAsBoolean_WithBooleanValue_ReturnsBoolean() {
        // Given
        String testConfig = "{\"featureEnabled\":true}";
        java.util.concurrent.atomic.AtomicReference<String> latestConfig = 
            (java.util.concurrent.atomic.AtomicReference<String>) ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);
        
        // Also need to parse and set parsedConfiguration
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                (com.fasterxml.jackson.databind.ObjectMapper) ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> parsedConfig = 
                (java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>) 
                ReflectionTestUtils.getField(appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        boolean value = appConfigIntegration.getBooleanConfigValue("featureEnabled", false);

        // Then
        assertTrue(value);
    }

    @Test
    void testGetConfigValueAsInt_WithIntValue_ReturnsInt() {
        // Given
        String testConfig = "{\"maxRetries\":5}";
        java.util.concurrent.atomic.AtomicReference<String> latestConfig = 
            (java.util.concurrent.atomic.AtomicReference<String>) ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);
        
        // Also need to parse and set parsedConfiguration
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = 
                (com.fasterxml.jackson.databind.ObjectMapper) ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> parsedConfig = 
                (java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>) 
                ReflectionTestUtils.getField(appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        int value = appConfigIntegration.getIntConfigValue("maxRetries", 3);

        // Then
        assertEquals(5, value);
    }

    @Test
    void testCleanup_ShutsDownScheduler() {
        // When
        appConfigIntegration.cleanup();

        // Then - Should not throw exception
        assertDoesNotThrow(() -> appConfigIntegration.cleanup());
    }
}

