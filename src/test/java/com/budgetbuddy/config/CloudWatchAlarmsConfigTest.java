package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit Tests for CloudWatchAlarmsConfig */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
class CloudWatchAlarmsConfigTest {

    private static final String GREATERTHANTHRESHOLD = "GreaterThanThreshold";

    private CloudWatchAlarmsConfig config;

    @BeforeEach
    void setUp() {
        config = new CloudWatchAlarmsConfig();
    }

    @Test
    void testGetErrorRateAlarmConfig() {
        // Given
        ReflectionTestUtils.setField(config, "errorRateThreshold", 10);
        ReflectionTestUtils.setField(config, "errorRateEvaluationPeriods", 2);

        // When
        final CloudWatchAlarmsConfig.AlarmConfig alarmConfig = config.getErrorRateAlarmConfig();

        // Then
        assertNotNull(alarmConfig);
        assertEquals("ErrorRate", alarmConfig.getName());
        assertEquals(10, alarmConfig.getThreshold());
        assertEquals(2, alarmConfig.getEvaluationPeriods());
        assertEquals(GREATERTHANTHRESHOLD, alarmConfig.getComparisonOperator());
    }

    @Test
    void testGetLatencyP95AlarmConfig() {
        // Given
        ReflectionTestUtils.setField(config, "latencyP95ThresholdMs", 1000);
        ReflectionTestUtils.setField(config, "latencyEvaluationPeriods", 3);

        // When
        final CloudWatchAlarmsConfig.AlarmConfig alarmConfig = config.getLatencyP95AlarmConfig();

        // Then
        assertNotNull(alarmConfig);
        assertEquals("LatencyP95", alarmConfig.getName());
        assertEquals(1000, alarmConfig.getThreshold());
        assertEquals(3, alarmConfig.getEvaluationPeriods());
        assertEquals(GREATERTHANTHRESHOLD, alarmConfig.getComparisonOperator());
    }

    @Test
    void testGetLatencyP99AlarmConfig() {
        // Given
        ReflectionTestUtils.setField(config, "latencyP99ThresholdMs", 2000);
        ReflectionTestUtils.setField(config, "latencyEvaluationPeriods", 3);

        // When
        final CloudWatchAlarmsConfig.AlarmConfig alarmConfig = config.getLatencyP99AlarmConfig();

        // Then
        assertNotNull(alarmConfig);
        assertEquals("LatencyP99", alarmConfig.getName());
        assertEquals(2000, alarmConfig.getThreshold());
        assertEquals(3, alarmConfig.getEvaluationPeriods());
        assertEquals(GREATERTHANTHRESHOLD, alarmConfig.getComparisonOperator());
    }

    @Test
    void testGetDatabaseConnectionPoolAlarmConfig() {
        // Given
        ReflectionTestUtils.setField(config, "databaseConnectionPoolThreshold", 80);
        ReflectionTestUtils.setField(config, "resourceEvaluationPeriods", 2);

        // When
        final CloudWatchAlarmsConfig.AlarmConfig alarmConfig =
                config.getDatabaseConnectionPoolAlarmConfig();

        // Then
        assertNotNull(alarmConfig);
        assertEquals("DatabaseConnectionPool", alarmConfig.getName());
        assertEquals(80, alarmConfig.getThreshold());
        assertEquals(2, alarmConfig.getEvaluationPeriods());
        assertEquals(GREATERTHANTHRESHOLD, alarmConfig.getComparisonOperator());
    }

    @Test
    void testGetMemoryAlarmConfig() {
        // Given
        ReflectionTestUtils.setField(config, "memoryThreshold", 85);
        ReflectionTestUtils.setField(config, "resourceEvaluationPeriods", 2);

        // When
        final CloudWatchAlarmsConfig.AlarmConfig alarmConfig = config.getMemoryAlarmConfig();

        // Then
        assertNotNull(alarmConfig);
        assertEquals("MemoryUsage", alarmConfig.getName());
        assertEquals(85, alarmConfig.getThreshold());
        assertEquals(2, alarmConfig.getEvaluationPeriods());
        assertEquals(GREATERTHANTHRESHOLD, alarmConfig.getComparisonOperator());
    }

    @Test
    void testGetCpuAlarmConfig() {
        // Given
        ReflectionTestUtils.setField(config, "cpuThreshold", 80);
        ReflectionTestUtils.setField(config, "resourceEvaluationPeriods", 2);

        // When
        final CloudWatchAlarmsConfig.AlarmConfig alarmConfig = config.getCpuAlarmConfig();

        // Then
        assertNotNull(alarmConfig);
        assertEquals("CpuUsage", alarmConfig.getName());
        assertEquals(80, alarmConfig.getThreshold());
        assertEquals(2, alarmConfig.getEvaluationPeriods());
        assertEquals(GREATERTHANTHRESHOLD, alarmConfig.getComparisonOperator());
    }

    @Test
    void testAlarmConfigConstructor() {
        // When
        final CloudWatchAlarmsConfig.AlarmConfig alarmConfig =
                new CloudWatchAlarmsConfig.AlarmConfig("TestAlarm", 50, 3, GREATERTHANTHRESHOLD);

        // Then
        assertEquals("TestAlarm", alarmConfig.getName());
        assertEquals(50, alarmConfig.getThreshold());
        assertEquals(3, alarmConfig.getEvaluationPeriods());
        assertEquals(GREATERTHANTHRESHOLD, alarmConfig.getComparisonOperator());
    }
}
