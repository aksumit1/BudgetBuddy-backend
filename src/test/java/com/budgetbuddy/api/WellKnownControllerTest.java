package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for WellKnownController
 */
@SpringBootTest(
    classes = com.budgetbuddy.BudgetBuddyApplication.class,
    webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {
    "app.apple.team-id=TEST_TEAM_ID",
    "app.apple.bundle-id=com.test.budgetbuddy"
})
class WellKnownControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGetAppleAppSiteAssociation_ReturnsJson() throws Exception {
        // When/Then
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.applinks").exists())
                .andExpect(jsonPath("$.applinks.details").isArray())
                .andExpect(jsonPath("$.applinks.details[0].appIDs").isArray())
                .andExpect(jsonPath("$.applinks.details[0].appIDs[0]").value("TEST_TEAM_ID.com.test.budgetbuddy"));
    }

    @Test
    void testGetAppleAppSiteAssociation_HasCorrectStructure() throws Exception {
        // When/Then
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applinks.details[0].components").isArray())
                .andExpect(jsonPath("$.applinks.details[0].components[0]./").value("/plaid/*"));
    }
}

