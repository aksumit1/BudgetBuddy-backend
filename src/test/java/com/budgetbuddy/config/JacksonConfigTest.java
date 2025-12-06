package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JacksonConfig
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class JacksonConfigTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testObjectMapper_IsCreated() {
        // Then
        assertNotNull(objectMapper, "ObjectMapper should be created");
    }

    @Test
    void testObjectMapper_SerializesInstant() throws Exception {
        // Given
        Instant instant = Instant.now();
        TestObject obj = new TestObject();
        obj.instant = instant;

        // When
        String json = objectMapper.writeValueAsString(obj);
        TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertNotNull(deserialized.instant, "Instant should be deserialized");
    }

    @Test
    void testObjectMapper_SerializesBigDecimal() throws Exception {
        // Given
        BigDecimal amount = new BigDecimal("123.45");
        TestObject obj = new TestObject();
        obj.amount = amount;

        // When
        String json = objectMapper.writeValueAsString(obj);
        TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertEquals(amount, deserialized.amount, "BigDecimal should be serialized as number");
    }

    @Test
    void testObjectMapper_SerializesLocalDate() throws Exception {
        // Given
        LocalDate date = LocalDate.now();
        TestObject obj = new TestObject();
        obj.date = date;

        // When
        String json = objectMapper.writeValueAsString(obj);
        TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertEquals(date, deserialized.date, "LocalDate should be deserialized");
    }

    @Test
    void testObjectMapper_SerializesLocalDateTime() throws Exception {
        // Given
        LocalDateTime dateTime = LocalDateTime.now();
        TestObject obj = new TestObject();
        obj.dateTime = dateTime;

        // When
        String json = objectMapper.writeValueAsString(obj);
        TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertNotNull(deserialized.dateTime, "LocalDateTime should be deserialized");
    }

    @Test
    void testObjectMapper_DoesNotWriteDatesAsTimestamps() throws Exception {
        // Given
        Instant instant = Instant.parse("2023-01-01T00:00:00Z");
        TestObject obj = new TestObject();
        obj.instant = instant;

        // When
        String json = objectMapper.writeValueAsString(obj);

        // Then - Should not contain timestamp format
        assertFalse(json.contains("1672531200"), "Should not write dates as timestamps");
        assertTrue(json.contains("2023") || json.contains("instant"), "Should use ISO format");
    }

    static class TestObject {
        public Instant instant;
        public BigDecimal amount;
        public LocalDate date;
        public LocalDateTime dateTime;
    }
}

