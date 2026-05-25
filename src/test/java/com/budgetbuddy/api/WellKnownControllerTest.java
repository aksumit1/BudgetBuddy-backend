package com.budgetbuddy.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Tests for WellKnownController */
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(
        properties = {"app.apple.team-id=TEST_TEAM_ID", "app.apple.bundle-id=com.test.budgetbuddy"})
class WellKnownControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void testGetAppleAppSiteAssociationReturnsJson() throws Exception {
        // When/Then
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.applinks").exists())
                .andExpect(jsonPath("$.applinks.details").isArray())
                .andExpect(jsonPath("$.applinks.details[0].appIDs").isArray())
                .andExpect(
                        jsonPath("$.applinks.details[0].appIDs[0]")
                                .value("TEST_TEAM_ID.com.test.budgetbuddy"));
    }

    @Test
    void testGetAppleAppSiteAssociationHasCorrectStructure() throws Exception {
        // When/Then
        mockMvc.perform(get("/.well-known/apple-app-site-association"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applinks.details[0].components").isArray())
                .andExpect(jsonPath("$.applinks.details[0].components[0]./").value("/plaid/*"));
    }
}
