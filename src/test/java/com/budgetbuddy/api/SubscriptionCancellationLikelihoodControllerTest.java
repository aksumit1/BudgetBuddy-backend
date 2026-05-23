package com.budgetbuddy.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.SubscriptionUsagePredictor;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller tests for the on-demand cancellation-likelihood endpoint.
 * The controller is {@code @ConditionalOnBean(SubscriptionUsagePredictor.class)}
 * — the test enables the bean via property + @MockitoBean.
 */
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = "app.subscription.usage-prediction.enabled=true")
class SubscriptionCancellationLikelihoodControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private SubscriptionUsagePredictor predictor;
    @MockitoBean private SubscriptionService subscriptionService;
    @MockitoBean private TransactionRepository transactionRepository;
    @MockitoBean private UserService userService;

    private static final String USER_ID = "user-1";
    private static final String EMAIL = "test@example.com";

    @Test
    @WithMockUser(username = EMAIL)
    void returnsLikelihoodPerActiveSubscription() throws Exception {
        when(userService.findByEmail(eq(EMAIL))).thenReturn(Optional.of(userWithId(USER_ID)));
        final Subscription a = sub("sub-1", "Netflix");
        final Subscription b = sub("sub-2", "Spotify");
        when(subscriptionService.getActiveSubscriptions(eq(USER_ID))).thenReturn(List.of(a, b));
        when(transactionRepository.findByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(predictor.cancellationLikelihood(eq(a), any())).thenReturn(0.81);
        when(predictor.cancellationLikelihood(eq(b), any())).thenReturn(0.15);
        when(predictor.shouldFlagForCancellation(eq(0.81))).thenReturn(true);
        when(predictor.shouldFlagForCancellation(eq(0.15))).thenReturn(false);

        mockMvc.perform(get("/api/subscriptions/insights/cancellation-likelihood"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subscriptionId").value("sub-1"))
                .andExpect(jsonPath("$[0].likelihood").value(0.81))
                .andExpect(jsonPath("$[0].shouldFlag").value(true))
                .andExpect(jsonPath("$[1].subscriptionId").value("sub-2"))
                .andExpect(jsonPath("$[1].shouldFlag").value(false));
    }

    @Test
    @WithMockUser(username = EMAIL)
    void emptySubscriptionListReturnsEmptyArray() throws Exception {
        when(userService.findByEmail(eq(EMAIL))).thenReturn(Optional.of(userWithId(USER_ID)));
        when(subscriptionService.getActiveSubscriptions(eq(USER_ID))).thenReturn(List.of());
        when(transactionRepository.findByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/subscriptions/insights/cancellation-likelihood"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(username = EMAIL)
    void sentinelLikelihoodReportedAsNegativeOne() throws Exception {
        when(userService.findByEmail(eq(EMAIL))).thenReturn(Optional.of(userWithId(USER_ID)));
        final Subscription s = sub("sub-x", "Mystery");
        when(subscriptionService.getActiveSubscriptions(eq(USER_ID))).thenReturn(List.of(s));
        when(transactionRepository.findByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(predictor.cancellationLikelihood(eq(s), any())).thenReturn(-1.0);

        mockMvc.perform(get("/api/subscriptions/insights/cancellation-likelihood"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].likelihood").value(-1.0))
                .andExpect(jsonPath("$[0].shouldFlag").value(false));
    }

    private static UserTable userWithId(final String id) {
        final UserTable u = new UserTable();
        u.setUserId(id);
        u.setEmail(EMAIL);
        return u;
    }

    private static Subscription sub(final String id, final String merchant) {
        final Subscription s = new Subscription();
        s.setSubscriptionId(id);
        s.setUserId(USER_ID);
        s.setMerchantName(merchant);
        s.setAmount(new BigDecimal("-9.99"));
        s.setFrequency(Subscription.SubscriptionFrequency.MONTHLY);
        s.setActive(true);
        return s;
    }
}
