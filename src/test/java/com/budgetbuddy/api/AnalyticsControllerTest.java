package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.analytics.AnalyticsService;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for AnalyticsController
 */
@SpringBootTest(
    classes = com.budgetbuddy.BudgetBuddyApplication.class,
    webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyticsService analyticsService;

    @MockBean
    private UserService userService;

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetSpendingSummary_ReturnsSummary() throws Exception {
        // Given
        UserTable user = new UserTable();
        user.setUserId(UUID.randomUUID().toString());
        user.setEmail("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        AnalyticsService.SpendingSummary summary = new AnalyticsService.SpendingSummary(
                new BigDecimal("1000.00"), 20L);
        when(analyticsService.getSpendingSummary(any(UserTable.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(summary);

        // When/Then
        mockMvc.perform(get("/api/analytics/spending-summary")
                        .param("startDate", "2024-01-01")
                        .param("endDate", "2024-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpending").exists());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetSpendingByCategory_ReturnsCategorySpending() throws Exception {
        // Given
        UserTable user = new UserTable();
        user.setUserId(UUID.randomUUID().toString());
        user.setEmail("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        Map<String, BigDecimal> categorySpending = Map.of(
                "FOOD_AND_DRINK", new BigDecimal("500.00"),
                "TRANSPORTATION", new BigDecimal("300.00")
        );
        when(analyticsService.getSpendingByCategory(any(UserTable.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(categorySpending);

        // When/Then
        mockMvc.perform(get("/api/analytics/spending-by-category")
                        .param("startDate", "2024-01-01")
                        .param("endDate", "2024-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.FOOD_AND_DRINK").exists());
    }
}

