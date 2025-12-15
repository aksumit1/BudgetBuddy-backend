package com.budgetbuddy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for XRayConfig
 */
class XRayConfigTest {

    private XRayConfig config;

    @BeforeEach
    void setUp() {
        config = new XRayConfig();
    }

    @Test
    void testXRayEnabled_True() {
        // Given
        ReflectionTestUtils.setField(config, "xrayEnabled", true);
        ReflectionTestUtils.setField(config, "samplingRate", 0.1);

        // When & Then - verify fields are set
        assertTrue((Boolean) ReflectionTestUtils.getField(config, "xrayEnabled"));
        assertEquals(0.1, (Double) ReflectionTestUtils.getField(config, "samplingRate"));
    }

    @Test
    void testXRayEnabled_False() {
        // Given
        ReflectionTestUtils.setField(config, "xrayEnabled", false);
        ReflectionTestUtils.setField(config, "samplingRate", 0.0);

        // When & Then - verify fields are set
        assertFalse((Boolean) ReflectionTestUtils.getField(config, "xrayEnabled"));
        assertEquals(0.0, (Double) ReflectionTestUtils.getField(config, "samplingRate"));
    }

    @Test
    void testSamplingRate_CanBeSet() {
        // Given
        ReflectionTestUtils.setField(config, "xrayEnabled", true);
        ReflectionTestUtils.setField(config, "samplingRate", 0.5);

        // When & Then
        assertEquals(0.5, (Double) ReflectionTestUtils.getField(config, "samplingRate"));
    }
}
