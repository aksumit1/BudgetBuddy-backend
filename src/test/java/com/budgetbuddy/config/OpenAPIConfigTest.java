package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OpenAPIConfig
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {
    "app.api.version=1.0.0",
    "app.api.base-url=https://api.budgetbuddy.com"
})
class OpenAPIConfigTest {

    @Autowired
    private OpenAPI openAPI;

    @Test
    void testOpenAPI_IsCreated() {
        // Then
        assertNotNull(openAPI, "OpenAPI should be created");
    }

    @Test
    void testOpenAPI_HasInfo() {
        // Then
        assertNotNull(openAPI.getInfo(), "OpenAPI should have info");
        assertEquals("BudgetBuddy Backend API", openAPI.getInfo().getTitle());
        assertEquals("1.0.0", openAPI.getInfo().getVersion());
    }

    @Test
    void testOpenAPI_HasServers() {
        // Then
        assertNotNull(openAPI.getServers(), "OpenAPI should have servers");
        assertFalse(openAPI.getServers().isEmpty(), "Should have at least one server");
    }

    @Test
    void testOpenAPI_HasTags() {
        // Then
        assertNotNull(openAPI.getTags(), "OpenAPI should have tags");
        assertFalse(openAPI.getTags().isEmpty(), "Should have at least one tag");
    }
}

