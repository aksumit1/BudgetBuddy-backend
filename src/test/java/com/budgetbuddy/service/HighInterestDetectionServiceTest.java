package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.HighInterestDetectionService.HighInterestAlert;
import com.budgetbuddy.service.HighInterestDetectionService.Severity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins three bugs fixed in {@link HighInterestDetectionService}: the
 * hard-coded 3-month divisor (overstated/understated monthly interest
 * depending on actual data density), the fabricated 20% APR (presented
 * truth-y in a user-facing recommendation), and the substring keyword
 * match that fired on merchants containing "interest".
 */
@ExtendWith(MockitoExtension.class)
class HighInterestDetectionServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    private HighInterestDetectionService svc;

    private static final String USER = "u1";
    private static final String CC_ID = "cc-1";

    @BeforeEach
    void setUp() {
        svc = new HighInterestDetectionService(accountRepository, transactionRepository);
        // Default no accounts / no transactions — individual tests override.
        lenient().when(accountRepository.findByUserId(anyString()))
                .thenReturn(new ArrayList<>());
        lenient().when(transactionRepository.findByUserIdAndDateRange(
                        anyString(), anyString(), anyString()))
                .thenReturn(new ArrayList<>());
    }

    // ------------------------------------------------------------------
    // Bug 1: keyword false positives
    // ------------------------------------------------------------------

    @Test
    void interestKeyword_doesNotMatchMerchantsContainingTheSubstring() {
        // "INTERESTING TIMES CAFE" must not be flagged as an interest charge.
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        interestTx("INTERESTING TIMES CAFE", "-150.00", today()),
                        interestTx("FINANCE CHARGES PRO", "-200.00", today())));
        // Note: no accountRepository.findById stub needed — the keyword
        // filter rejects both rows so the per-account branch never runs.

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        assertTrue(alerts.isEmpty(),
                "Substring matches on merchant names must not trigger interest alerts");
    }

    @Test
    void interestKeyword_matchesWholeWordInChargeDescription() {
        // "INTEREST CHARGED" must match, "FINANCE CHARGE" must match.
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        interestTx("INTEREST CHARGED ON PURCHASES", "-60.00", "2025-03-15"),
                        interestTx("FINANCE CHARGE", "-65.00", "2025-04-15"),
                        interestTx("INTEREST CHARGE", "-70.00", "2025-05-15")));
        when(accountRepository.findById(CC_ID))
                .thenReturn(Optional.of(creditCardAccount(BigDecimal.valueOf(1000),
                        BigDecimal.valueOf(24))));

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        assertFalse(alerts.isEmpty(), "Real interest charges should fire alerts");
    }

    // ------------------------------------------------------------------
    // Bug 2: hard-coded 3-month divisor was wrong for sparse / dense data
    // ------------------------------------------------------------------

    @Test
    void monthlyInterest_dividesByDistinctMonths_notHardcoded3() {
        // Account has 4 interest charges spanning 2 distinct months —
        // monthly interest should be total/2, not total/3.
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        interestTx("INTEREST CHARGED", "-60.00", "2025-03-05"),
                        interestTx("INTEREST CHARGED", "-60.00", "2025-03-25"),
                        interestTx("INTEREST CHARGED", "-60.00", "2025-04-05"),
                        interestTx("INTEREST CHARGED", "-60.00", "2025-04-25")));
        // Need a balance high enough that the implied rate clears the 15% floor.
        when(accountRepository.findById(CC_ID))
                .thenReturn(Optional.of(creditCardAccount(BigDecimal.valueOf(1000), null)));

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        assertFalse(alerts.isEmpty(),
                "Expected an alert from real interest charges");
        // 240 total / 2 distinct months = 120/month, not 240/3 = 80/month.
        assertEquals(0, alerts.get(0).getMonthlyInterest()
                .compareTo(new BigDecimal("120.00")),
                "Monthly interest must reflect distinct billing months actually observed");
    }

    @Test
    void monthlyInterest_singleMonthOfData_doesNotUnderstateBy3x() {
        // One charge in one month — prior bug: $120 / 3 = $40/month, which
        // fell below the $50/month threshold and produced no alert at all.
        // Fix: 1 distinct month → $120 monthly, which triggers correctly.
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        interestTx("INTEREST CHARGED", "-120.00", "2025-05-15")));
        when(accountRepository.findById(CC_ID))
                .thenReturn(Optional.of(creditCardAccount(BigDecimal.valueOf(1000), null)));

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        assertFalse(alerts.isEmpty(),
                "Single-month interest charge must not be silently 1/3-divided below threshold");
    }

    // ------------------------------------------------------------------
    // Bug 3: fabricated 20% APR was presented as fact
    // ------------------------------------------------------------------

    @Test
    void creditCardAlert_suppressed_whenNoRealAprAvailable() {
        // Credit card with a balance but no aprPercent recorded must NOT
        // produce an alert claiming a 20% APR — that's a guess.
        final AccountTable cc = creditCardAccount(BigDecimal.valueOf(5000), null);
        when(accountRepository.findByUserId(USER)).thenReturn(List.of(cc));

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        // detectInterestCharges path also runs but returns nothing
        // (no transactions in this scenario), so the analyzeCreditCards
        // path is the only possible source of an alert.
        assertTrue(alerts.isEmpty(),
                "Credit-card alert must require real APR data, not a 20% fallback");
    }

    @Test
    void creditCardAlert_fires_whenRealAprIsHigh() {
        // Same setup but with a real 28.99% APR from the statement.
        final AccountTable cc = creditCardAccount(
                BigDecimal.valueOf(5000), new BigDecimal("28.99"));
        when(accountRepository.findByUserId(USER)).thenReturn(List.of(cc));

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        assertEquals(1, alerts.size());
        // 28.99% / 100 = 0.2899
        assertTrue(alerts.get(0).getInterestRate() > 0.28
                && alerts.get(0).getInterestRate() < 0.29);
        assertEquals(Severity.HIGH, alerts.get(0).getSeverity());
    }

    // ------------------------------------------------------------------
    // Loan path: estimate is allowed but severity capped + recommendation
    // discloses estimation
    // ------------------------------------------------------------------

    @Test
    void loanAlert_estimatedApr_severityCappedAtMedium() {
        // Personal loan with no real APR, balance large enough that the
        // estimated 15% × balance clears the $1000/year threshold.
        final AccountTable loan = loanAccount("personalLoan",
                BigDecimal.valueOf(20_000), /*aprPercent=*/ null);
        when(accountRepository.findByUserId(USER)).thenReturn(List.of(loan));

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        assertEquals(1, alerts.size());
        // Even though estimated rate would otherwise be HIGH (>=25%)
        // — here 15% is just MEDIUM — confirm we never escalate based
        // on a guess.
        assertEquals(Severity.MEDIUM, alerts.get(0).getSeverity());
        assertTrue(alerts.get(0).getRecommendation().contains("estimated"),
                "Loan recommendation must disclose APR is estimated");
    }

    @Test
    void loanAlert_realApr_canBeHighSeverity() {
        final AccountTable loan = loanAccount("personalLoan",
                BigDecimal.valueOf(20_000), new BigDecimal("29.99"));
        when(accountRepository.findByUserId(USER)).thenReturn(List.of(loan));

        final List<HighInterestAlert> alerts = svc.detectHighInterest(USER);
        assertEquals(1, alerts.size());
        assertEquals(Severity.HIGH, alerts.get(0).getSeverity());
        assertFalse(alerts.get(0).getRecommendation().contains("estimated"),
                "Real APR must not be tagged as estimated");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static TransactionTable interestTx(
            final String description, final String amount, final String date) {
        final TransactionTable tx = new TransactionTable();
        tx.setAccountId(CC_ID);
        tx.setDescription(description);
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionDate(date);
        return tx;
    }

    private static AccountTable creditCardAccount(
            final BigDecimal balance, final BigDecimal aprPercent) {
        final AccountTable a = new AccountTable();
        a.setAccountId(CC_ID);
        a.setUserId(USER);
        a.setAccountName("Test Card");
        a.setInstitutionName("Test Bank");
        a.setAccountType("creditCard");
        a.setBalance(balance.negate()); // CC balance is stored negative
        a.setAprPercent(aprPercent);
        return a;
    }

    private static AccountTable loanAccount(
            final String type, final BigDecimal balance, final BigDecimal aprPercent) {
        final AccountTable a = new AccountTable();
        a.setAccountId("loan-1");
        a.setUserId(USER);
        a.setAccountName("Test Loan");
        a.setInstitutionName("Test Bank");
        a.setAccountType(type);
        a.setBalance(balance.negate());
        a.setAprPercent(aprPercent);
        return a;
    }

    private static String today() {
        return LocalDate.now().toString();
    }
}
