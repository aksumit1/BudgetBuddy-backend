package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit Tests for XRayConfig */
class XRayConfigTest {

    private static final String SAMPLINGRATE = "samplingRate";
    private static final String XRAYENABLED = "xrayEnabled";

    private XRayConfig config;

    @BeforeEach
    void setUp() {
        config = new XRayConfig();
    }

    @Test
    void testXRayEnabledTrue() {
        // Given
        ReflectionTestUtils.setField(config, XRAYENABLED, true);
        ReflectionTestUtils.setField(config, SAMPLINGRATE, 0.1);

        // When & Then - verify fields are set
        assertTrue((Boolean) ReflectionTestUtils.getField(config, XRAYENABLED));
        assertEquals(0.1, (Double) ReflectionTestUtils.getField(config, SAMPLINGRATE));
    }

    @Test
    void testXRayEnabledFalse() {
        // Given
        ReflectionTestUtils.setField(config, XRAYENABLED, false);
        ReflectionTestUtils.setField(config, SAMPLINGRATE, 0.0);

        // When & Then - verify fields are set
        assertFalse((Boolean) ReflectionTestUtils.getField(config, XRAYENABLED));
        assertEquals(0.0, (Double) ReflectionTestUtils.getField(config, SAMPLINGRATE));
    }

    @Test
    void testSamplingRateCanBeSet() {
        // Given
        ReflectionTestUtils.setField(config, XRAYENABLED, true);
        ReflectionTestUtils.setField(config, SAMPLINGRATE, 0.5);

        // When & Then
        assertEquals(0.5, (Double) ReflectionTestUtils.getField(config, SAMPLINGRATE));
    }
}
