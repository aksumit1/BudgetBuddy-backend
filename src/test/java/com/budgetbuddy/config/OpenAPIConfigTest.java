package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Tests for OpenAPIConfig */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(
        properties = {"app.api.version=1.0.0", "app.api.base-url=https://api.budgetbuddy.com"})
class OpenAPIConfigTest {

    @Autowired private OpenAPI openAPI;

    @Test
    void testOpenAPIIsCreated() {
        // Then
        assertNotNull(openAPI, "OpenAPI should be created");
    }

    @Test
    void testOpenAPIHasInfo() {
        // Then
        assertNotNull(openAPI.getInfo(), "OpenAPI should have info");
        assertEquals("BudgetBuddy Backend API", openAPI.getInfo().getTitle());
        assertEquals("1.0.0", openAPI.getInfo().getVersion());
    }

    @Test
    void testOpenAPIHasServers() {
        // Then
        assertNotNull(openAPI.getServers(), "OpenAPI should have servers");
        assertFalse(openAPI.getServers().isEmpty(), "Should have at least one server");
    }

    @Test
    void testOpenAPIHasTags() {
        // Then
        assertNotNull(openAPI.getTags(), "OpenAPI should have tags");
        assertFalse(openAPI.getTags().isEmpty(), "Should have at least one tag");
    }
}
