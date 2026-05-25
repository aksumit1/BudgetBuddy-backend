package com.budgetbuddy.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.ml.TransactionAnomalyDetector;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;

/**
 * Controller tests for the on-demand anomaly endpoint. The controller is
 * {@code @ConditionalOnBean(TransactionAnomalyDetector.class)} — it loads
 * only when anomaly detection is enabled. The test enables the bean via
 * properties + a @MockitoBean of the detector.
 */
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = "app.anomaly-detection.enabled=true")
class TransactionAnomalyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TransactionAnomalyDetector detector;
    @MockitoBean private TransactionRepository transactionRepository;
    @MockitoBean private UserService userService;

    private static final String USER_ID = "user-1";
    private static final String EMAIL = "test@example.com";

    @Test
    @WithMockUser(username = EMAIL)
    void anomalousTransactionReturnsScoreAndFlag() throws Exception {
        final UserTable user = userWithId(USER_ID);
        when(userService.findByEmail(eq(EMAIL))).thenReturn(Optional.of(user));

        final TransactionTable tx = new TransactionTable();
        final String txId = UUID.randomUUID().toString();
        tx.setTransactionId(txId);
        tx.setUserId(USER_ID);
        tx.setMerchantName("Unfamiliar Vendor");
        when(transactionRepository.findById(eq(txId))).thenReturn(Optional.of(tx));
        when(transactionRepository.findByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of(tx));
        when(detector.scoreUnusualness(any(), any())).thenReturn(0.72);
        when(detector.isAnomalous(eq(0.72))).thenReturn(true);

        mockMvc.perform(get("/api/transactions/anomaly/" + txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(txId))
                .andExpect(jsonPath("$.data.score").value(0.72))
                .andExpect(jsonPath("$.data.anomalous").value(true));
    }

    @Test
    @WithMockUser(username = EMAIL)
    void scoreSentinelReturnsNegativeOneAndFalse() throws Exception {
        final UserTable user = userWithId(USER_ID);
        when(userService.findByEmail(eq(EMAIL))).thenReturn(Optional.of(user));
        final TransactionTable tx = new TransactionTable();
        final String txId = UUID.randomUUID().toString();
        tx.setTransactionId(txId);
        tx.setUserId(USER_ID);
        when(transactionRepository.findById(eq(txId))).thenReturn(Optional.of(tx));
        when(transactionRepository.findByUserId(eq(USER_ID), anyInt(), anyInt()))
                .thenReturn(List.of(tx));
        // Detector returns sentinel -1 (couldn't score).
        when(detector.scoreUnusualness(any(), any())).thenReturn(-1.0);

        mockMvc.perform(get("/api/transactions/anomaly/" + txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(-1.0))
                .andExpect(jsonPath("$.data.anomalous").value(false));
    }

    @Test
    @WithMockUser(username = EMAIL)
    void crossUserAccessIsRejected() throws Exception {
        final UserTable user = userWithId(USER_ID);
        when(userService.findByEmail(eq(EMAIL))).thenReturn(Optional.of(user));
        // The transaction belongs to a DIFFERENT user — the controller
        // throws UNAUTHORIZED_ACCESS before scoring.
        final TransactionTable tx = new TransactionTable();
        final String txId = UUID.randomUUID().toString();
        tx.setTransactionId(txId);
        tx.setUserId("other-user");
        when(transactionRepository.findById(eq(txId))).thenReturn(Optional.of(tx));

        mockMvc.perform(get("/api/transactions/anomaly/" + txId))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @WithMockUser(username = EMAIL)
    void missingTransactionReturns404Family() throws Exception {
        when(userService.findByEmail(eq(EMAIL))).thenReturn(Optional.of(userWithId(USER_ID)));
        when(transactionRepository.findById(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/transactions/anomaly/" + UUID.randomUUID()))
                .andExpect(status().is4xxClientError());
    }

    private static UserTable userWithId(final String id) {
        final UserTable u = new UserTable();
        u.setUserId(id);
        u.setEmail(EMAIL);
        return u;
    }
}
