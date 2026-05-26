package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.MissedPaymentDetectionService.AlertType;
import com.budgetbuddy.service.MissedPaymentDetectionService.MissedPaymentAlert;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the fix in {@code checkPaymentMade}: the prior code computed
 * {@code actionDesc = ""} when the action had no description, then
 * matched it against transaction descriptions with {@code contains("")},
 * which is true for every string. That silently suppressed every
 * overdue alert whenever the action had no description.
 */
@ExtendWith(MockitoExtension.class)
class MissedPaymentDetectionServiceTest {

    private static final String USER = "u1";

    @Mock private TransactionActionRepository actionRepository;
    @Mock private TransactionRepository transactionRepository;
    private MissedPaymentDetectionService svc;

    @BeforeEach
    void setUp() {
        svc = new MissedPaymentDetectionService(actionRepository, transactionRepository);
        lenient().when(transactionRepository.findByUserIdAndDateRange(
                        anyString(), anyString(), anyString()))
                .thenReturn(new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // Bug: empty-string contains-match suppressed every overdue alert
    // ------------------------------------------------------------------

    @Test
    void overdueAction_withNullDescription_isStillFlagged_whenNoMatchingTxExists() {
        // Action has a title and a due date but no description. Prior
        // bug: the null description became "" and matched every tx in
        // the window, so this overdue alert was wrongly suppressed.
        final TransactionActionTable action = pastDueAction(
                "Electric Bill", /*description=*/ null, daysAgo(15));
        when(actionRepository.findByUserId(USER)).thenReturn(List.of(action));
        // Some unrelated transaction is in the window — it must not
        // satisfy the description match.
        when(transactionRepository.findByUserIdAndDateRange(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(otherTx("COFFEE SHOP", "-5.00", daysAgo(15))));

        final List<MissedPaymentAlert> alerts = svc.detectMissedPayments(USER);
        assertEquals(1, alerts.size(), "Overdue alert must fire when no matching payment exists");
        assertEquals(AlertType.OVERDUE, alerts.getFirst().getType());
    }

    @Test
    void overdueAction_withMatchingTxByTitle_isSuppressed() {
        // Legitimate suppression: a real payment matches by title.
        final TransactionActionTable action = pastDueAction(
                "Electric Bill", null, daysAgo(15));
        when(actionRepository.findByUserId(USER)).thenReturn(List.of(action));
        when(transactionRepository.findByUserIdAndDateRange(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(otherTx("ELECTRIC BILL PAYMENT", "-120.00", daysAgo(15))));

        final List<MissedPaymentAlert> alerts = svc.detectMissedPayments(USER);
        assertTrue(alerts.isEmpty(), "Matching payment should suppress the OVERDUE alert");
    }

    @Test
    void overdueAction_withBothTitleAndDescNullOrBlank_isFlagged() {
        // Defensive: action with no identifying information at all.
        // We choose to flag (false positive) rather than suppress (false
        // negative), per "extraction tradeoffs: false positives are
        // worse than missing data" — but here a missed bill alert is
        // strictly more useful than silently dropping it.
        final TransactionActionTable action = pastDueAction("", "  ", daysAgo(10));
        when(actionRepository.findByUserId(USER)).thenReturn(List.of(action));
        when(transactionRepository.findByUserIdAndDateRange(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(otherTx("COFFEE", "-5.00", daysAgo(10))));

        final List<MissedPaymentAlert> alerts = svc.detectMissedPayments(USER);
        // Either: no alert (because action has no usable identifier at
        // all) OR a flagged alert — but never silently suppressed by
        // matching against the empty string.
        assertFalse(
                alerts.stream().anyMatch(a -> a.getType() == AlertType.OVERDUE
                        && a.getDueDate().equals(daysAgoDate(10))
                        && a.getMessage().toLowerCase().contains("hasn't been paid")
                        && a.getDescription() != null
                        && a.getDescription().equals("COFFEE")),
                "Empty-string actionDesc must not match coffee transaction");
    }

    @Test
    void completedAction_isSkipped() {
        // Sanity: completed actions don't produce alerts.
        final TransactionActionTable action = pastDueAction(
                "Electric Bill", null, daysAgo(15));
        action.setIsCompleted(Boolean.TRUE);
        when(actionRepository.findByUserId(USER)).thenReturn(List.of(action));

        final List<MissedPaymentAlert> alerts = svc.detectMissedPayments(USER);
        assertTrue(alerts.isEmpty());
    }

    @Test
    void atRiskAction_dueWithin3Days_fires() {
        final TransactionActionTable action = pastDueAction(
                "Rent", null, /*due in 2 days*/ LocalDate.now().plusDays(2).toString());
        when(actionRepository.findByUserId(USER)).thenReturn(List.of(action));
        when(transactionRepository.findByUserIdAndDateRange(
                anyString(), anyString(), anyString()))
                .thenReturn(List.of(otherTx("COFFEE", "-3.00",
                        LocalDate.now().toString())));

        final List<MissedPaymentAlert> alerts = svc.detectMissedPayments(USER);
        assertEquals(1, alerts.size());
        assertEquals(AlertType.AT_RISK, alerts.getFirst().getType());
    }

    private static TransactionActionTable pastDueAction(
            final String title, final String description, final String dueDate) {
        final TransactionActionTable a = new TransactionActionTable();
        a.setActionId("act-" + (title == null ? "X" : title));
        a.setUserId(USER);
        a.setTitle(title);
        a.setDescription(description);
        a.setDueDate(dueDate);
        a.setIsCompleted(Boolean.FALSE);
        return a;
    }

    private static TransactionTable otherTx(
            final String description, final String amount, final String date) {
        final TransactionTable tx = new TransactionTable();
        tx.setDescription(description);
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionDate(date);
        return tx;
    }

    private static String daysAgo(final int n) {
        return daysAgoDate(n).toString();
    }

    private static LocalDate daysAgoDate(final int n) {
        return LocalDate.now().minusDays(n);
    }
}
