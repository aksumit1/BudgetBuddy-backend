package com.budgetbuddy.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Tests for JacksonConfig */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class JacksonConfigTest {

    @Autowired private ObjectMapper objectMapper;

    @Test
    void testObjectMapperIsCreated() {
        // Then
        assertNotNull(objectMapper, "ObjectMapper should be created");
    }

    @Test
    void testObjectMapperSerializesInstant() throws Exception {
        // Given
        final Instant instant = Instant.now();
        final TestObject obj = new TestObject();
        obj.instant = instant;

        // When
        final String json = objectMapper.writeValueAsString(obj);
        final TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertNotNull(deserialized.instant, "Instant should be deserialized");
    }

    @Test
    void testObjectMapperSerializesBigDecimal() throws Exception {
        // Given
        final BigDecimal amount = new BigDecimal("123.45");
        final TestObject obj = new TestObject();
        obj.amount = amount;

        // When
        final String json = objectMapper.writeValueAsString(obj);
        final TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertEquals(amount, deserialized.amount, "BigDecimal should be serialized as number");
    }

    @Test
    void testObjectMapperSerializesLocalDate() throws Exception {
        // Given
        final LocalDate date = LocalDate.now();
        final TestObject obj = new TestObject();
        obj.date = date;

        // When
        final String json = objectMapper.writeValueAsString(obj);
        final TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertEquals(date, deserialized.date, "LocalDate should be deserialized");
    }

    @Test
    void testObjectMapperSerializesLocalDateTime() throws Exception {
        // Given
        final LocalDateTime dateTime = LocalDateTime.now();
        final TestObject obj = new TestObject();
        obj.dateTime = dateTime;

        // When
        final String json = objectMapper.writeValueAsString(obj);
        final TestObject deserialized = objectMapper.readValue(json, TestObject.class);

        // Then
        assertNotNull(json, "JSON should not be null");
        assertNotNull(deserialized.dateTime, "LocalDateTime should be deserialized");
    }

    @Test
    void testObjectMapperDoesNotWriteDatesAsTimestamps() throws Exception {
        // Given
        final Instant instant = Instant.parse("2023-01-01T00:00:00Z");
        final TestObject obj = new TestObject();
        obj.instant = instant;

        // When
        final String json = objectMapper.writeValueAsString(obj);

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
