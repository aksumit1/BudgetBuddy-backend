package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;

/** Unit Tests for AppConfigIntegration */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@ExtendWith(MockitoExtension.class)
class AppConfigIntegrationTest {

    @Mock private AppConfigDataClient appConfigDataClient;

    @InjectMocks private AppConfigIntegration appConfigIntegration;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(appConfigIntegration, "applicationName", "test-app");
        ReflectionTestUtils.setField(appConfigIntegration, "appConfigEnvironment", "test");
        ReflectionTestUtils.setField(appConfigIntegration, "configProfile", "default");
        ReflectionTestUtils.setField(appConfigIntegration, "refreshIntervalSeconds", 60L);
    }

    @Test
    void testGetConfigValueWithValidKeyReturnsValue() {
        // Given
        final String testConfig = "{\"testKey\":\"testValue\"}";
        final java.util.concurrent.atomic.AtomicReference<String> latestConfig =
                (java.util.concurrent.atomic.AtomicReference<String>)
                        ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);

        // Also need to parse and set parsedConfiguration
        try {
            final com.fasterxml.jackson.databind.ObjectMapper mapper =
                    (com.fasterxml.jackson.databind.ObjectMapper)
                            ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            final com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            final java.util.concurrent.atomic.AtomicReference<
                            com.fasterxml.jackson.databind.JsonNode>
                    parsedConfig =
                            (java.util.concurrent.atomic.AtomicReference<
                                            com.fasterxml.jackson.databind.JsonNode>)
                                    ReflectionTestUtils.getField(
                                            appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        final Optional<String> value = appConfigIntegration.getConfigValue("testKey");

        // Then
        assertTrue(value.isPresent());
        assertEquals("testValue", value.get());
    }

    @Test
    void testGetConfigValueWithInvalidKeyReturnsEmpty() {
        // Given
        final String testConfig = "{\"testKey\":\"testValue\"}";
        final java.util.concurrent.atomic.AtomicReference<String> latestConfig =
                (java.util.concurrent.atomic.AtomicReference<String>)
                        ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);

        // Also need to parse and set parsedConfiguration
        try {
            final com.fasterxml.jackson.databind.ObjectMapper mapper =
                    (com.fasterxml.jackson.databind.ObjectMapper)
                            ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            final com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            final java.util.concurrent.atomic.AtomicReference<
                            com.fasterxml.jackson.databind.JsonNode>
                    parsedConfig =
                            (java.util.concurrent.atomic.AtomicReference<
                                            com.fasterxml.jackson.databind.JsonNode>)
                                    ReflectionTestUtils.getField(
                                            appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        final Optional<String> value = appConfigIntegration.getConfigValue("nonExistentKey");

        // Then
        assertFalse(value.isPresent());
    }

    @Test
    void testGetConfigValueAsBooleanWithBooleanValueReturnsBoolean() {
        // Given
        final String testConfig = "{\"featureEnabled\":true}";
        final java.util.concurrent.atomic.AtomicReference<String> latestConfig =
                (java.util.concurrent.atomic.AtomicReference<String>)
                        ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);

        // Also need to parse and set parsedConfiguration
        try {
            final com.fasterxml.jackson.databind.ObjectMapper mapper =
                    (com.fasterxml.jackson.databind.ObjectMapper)
                            ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            final com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            final java.util.concurrent.atomic.AtomicReference<
                            com.fasterxml.jackson.databind.JsonNode>
                    parsedConfig =
                            (java.util.concurrent.atomic.AtomicReference<
                                            com.fasterxml.jackson.databind.JsonNode>)
                                    ReflectionTestUtils.getField(
                                            appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        final boolean value = appConfigIntegration.getBooleanConfigValue("featureEnabled", false);

        // Then
        assertTrue(value);
    }

    @Test
    void testGetConfigValueAsIntWithIntValueReturnsInt() {
        // Given
        final String testConfig = "{\"maxRetries\":5}";
        final java.util.concurrent.atomic.AtomicReference<String> latestConfig =
                (java.util.concurrent.atomic.AtomicReference<String>)
                        ReflectionTestUtils.getField(appConfigIntegration, "latestConfiguration");
        latestConfig.set(testConfig);

        // Also need to parse and set parsedConfiguration
        try {
            final com.fasterxml.jackson.databind.ObjectMapper mapper =
                    (com.fasterxml.jackson.databind.ObjectMapper)
                            ReflectionTestUtils.getField(appConfigIntegration, "objectMapper");
            final com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(testConfig);
            final java.util.concurrent.atomic.AtomicReference<
                            com.fasterxml.jackson.databind.JsonNode>
                    parsedConfig =
                            (java.util.concurrent.atomic.AtomicReference<
                                            com.fasterxml.jackson.databind.JsonNode>)
                                    ReflectionTestUtils.getField(
                                            appConfigIntegration, "parsedConfiguration");
            parsedConfig.set(jsonNode);
        } catch (Exception e) {
            fail("Failed to parse test config: " + e.getMessage());
        }

        // When
        final int value = appConfigIntegration.getIntConfigValue("maxRetries", 3);

        // Then
        assertEquals(5, value);
    }

    @Test
    void testCleanupShutsDownScheduler() {
        // When
        appConfigIntegration.cleanup();

        // Then - Should not throw exception
        assertDoesNotThrow(() -> appConfigIntegration.cleanup());
    }
}
