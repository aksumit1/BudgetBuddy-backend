package com.budgetbuddy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Jackson Configuration for JSON serialization
 * 
 * CRITICAL: All dates are serialized in ISO 8601 format to ensure consistency with iOS app.
 * - Instant: ISO 8601 with timezone (e.g., "2025-12-29T17:20:44.123Z")
 * - LocalDate: ISO 8601 date only (e.g., "2025-12-29")
 * - LocalDateTime: ISO 8601 without timezone (e.g., "2025-12-29T17:20:44.123")
 * 
 * With WRITE_DATES_AS_TIMESTAMPS disabled, JavaTimeModule automatically uses ISO 8601 format.
 * Individual date fields also have @JsonFormat annotations for explicit ISO format specification.
 * 
 * iOS app should expect ISO 8601 format and should NOT perform any date conversion.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // BigDecimal serializes as number (not string) for JSON compatibility
                // iOS app supports both String and Double formats
                .build();
        return mapper;
    }
}

