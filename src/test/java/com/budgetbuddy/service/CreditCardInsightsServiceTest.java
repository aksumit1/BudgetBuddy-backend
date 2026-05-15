package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Deep validation of the credit-card insights powered by the new statement-summary
 * fields. Each insight has its own ladder of error / boundary / edge / null-safety
 * cases pinned individually so a regression on any one fires a specific failing
 * assertion.
 *
 * <p>What's exercised:
 *
 * <ul>
 *   <li><b>Past-due alert:</b> fires only when pastDueAmount > 0; null and zero are
 *       silent; severity is HIGH; recommendation actionable.
 *   <li><b>High-utilization:</b> 30% boundary respected, 70% escalates to HIGH,
 *       uses abs(balance) for credit cards, div-by-zero guard, nil-limit silent.
 *   <li><b>AutoPay-off:</b> only fires when EXPLICITLY false AND balance is non-zero;
 *       null autoPayEnabled never fires (we don't know); zero balance never fires
 *       (no point alerting on inactive cards).
 *   <li><b>Annual fee approaching:</b> fires within 30-day window, NOT outside it,
 *       not on past dates, not without a fee amount; severity MEDIUM.
 *   <li><b>Non-credit accounts:</b> never produce credit-card insights.
 *   <li><b>Empty user / null user:</b> graceful empty list.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CreditCardInsightsServiceTest {

    @Mock private AccountRepository accountRepository;

    private CreditCardInsightsService service;
    private static final String USER_ID = "user-uuid";

    @BeforeEach
    void setUp() {
        service = new CreditCardInsightsService(accountRepository);
    }

    // ---------- null / empty user ----------

    @Test
    void detect_returnsEmptyList_forNullUserId() {
        assertTrue(service.detect(null).isEmpty(),
                "Null userId must produce empty list, not NPE");
    }

    @Test
    void detect_returnsEmptyList_forBlankUserId() {
        assertTrue(service.detect("").isEmpty());
    }

    @Test
    void detect_returnsEmptyList_whenUserHasNoCreditCards() {
        final AccountTable checking = creditCard(0, null, null, null, null, null, null);
        checking.setAccountType("checking");
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(checking));
        assertTrue(service.detect(USER_ID).isEmpty(),
                "Non-credit accounts must produce no credit-card insights");
    }

    // ---------- PAST_DUE ----------

    @Test
    void pastDueAlert_firesOnlyWhenAmountIsPositive() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(100, 10_000, null, null, /* pastDue */ new BigDecimal("250.00"),
                        null, null)
        ));
        final List<CreditCardInsightsService.CreditCardInsight> insights =
                service.detect(USER_ID);
        // Find the PAST_DUE alert specifically (utilization may also fire).
        final boolean hasPastDue = insights.stream()
                .anyMatch(i -> "PAST_DUE".equals(i.type()));
        assertTrue(hasPastDue, "PAST_DUE alert must fire for non-zero past-due amount");
        final CreditCardInsightsService.CreditCardInsight pd = insights.stream()
                .filter(i -> "PAST_DUE".equals(i.type()))
                .findFirst().orElseThrow();
        assertEquals(CreditCardInsightsService.Severity.HIGH, pd.severity(),
                "Past due is always HIGH severity");
        assertTrue(pd.message().contains("250.00"),
                "Message must include the past-due dollar amount");
    }

    @Test
    void pastDueAlert_doesNotFire_whenAmountIsZero() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(100, null, null, null,
                        /* pastDue */ BigDecimal.ZERO, null, null)
        ));
        final long pastDueCount = service.detect(USER_ID).stream()
                .filter(i -> "PAST_DUE".equals(i.type())).count();
        assertEquals(0, pastDueCount, "Zero past-due must NOT trigger the alert");
    }

    @Test
    void pastDueAlert_doesNotFire_whenAmountIsNull() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(100, null, null, null, /* pastDue */ null, null, null)
        ));
        final long pastDueCount = service.detect(USER_ID).stream()
                .filter(i -> "PAST_DUE".equals(i.type())).count();
        assertEquals(0, pastDueCount);
    }

    // ---------- HIGH_UTILIZATION ----------

    @Test
    void highUtilization_doesNotFire_atExactly30Percent() {
        // Boundary: > 0.30 only. Exactly 30% is NOT a warning — matches the
        // canonical FICO narrative.
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(3_000, 10_000, null, null, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "HIGH_UTILIZATION".equals(i.type())).count();
        assertEquals(0, count, "30%% utilization is exactly at threshold — must NOT alert");
    }

    @Test
    void highUtilization_firesAt31Percent_asMedium() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(3_100, 10_000, null, null, null, null, null)
        ));
        final CreditCardInsightsService.CreditCardInsight ins = service.detect(USER_ID).stream()
                .filter(i -> "HIGH_UTILIZATION".equals(i.type())).findFirst().orElseThrow();
        assertEquals(CreditCardInsightsService.Severity.MEDIUM, ins.severity());
    }

    @Test
    void highUtilization_escalatesToHigh_above70Percent() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(8_000, 10_000, null, null, null, null, null)
        ));
        final CreditCardInsightsService.CreditCardInsight ins = service.detect(USER_ID).stream()
                .filter(i -> "HIGH_UTILIZATION".equals(i.type())).findFirst().orElseThrow();
        assertEquals(CreditCardInsightsService.Severity.HIGH, ins.severity());
        assertTrue(ins.message().contains("80%"),
                "Message must include the actual percentage; got: " + ins.message());
    }

    @Test
    void highUtilization_usesAbsBalance_forNegativeCreditCardSign() {
        // Credit cards may carry a negative balance per accounting convention.
        // Utilization must compute on abs().
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(-5_000, 10_000, null, null, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "HIGH_UTILIZATION".equals(i.type())).count();
        assertEquals(1, count, "Negative balance must still produce utilization alert");
    }

    @Test
    void highUtilization_doesNotFire_whenLimitIsNull() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(5_000, /* limit */ null, null, null, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "HIGH_UTILIZATION".equals(i.type())).count();
        assertEquals(0, count);
    }

    @Test
    void highUtilization_doesNotFire_whenLimitIsZero() {
        // Defensive: avoid div-by-zero on bogus data.
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(5_000, 0, null, null, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "HIGH_UTILIZATION".equals(i.type())).count();
        assertEquals(0, count, "Zero credit limit must NOT trigger div-by-zero NPE");
    }

    @Test
    void highUtilization_doesNotFire_whenBalanceIsZero() {
        // Zero utilization is the IDEAL state — never alert.
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(0, 10_000, null, null, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "HIGH_UTILIZATION".equals(i.type())).count();
        assertEquals(0, count);
    }

    // ---------- AUTOPAY_OFF ----------

    @Test
    void autoPayOff_firesOnlyWhenExplicitlyFalse() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, 10_000, null, /* autoPay */ Boolean.FALSE, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "AUTOPAY_OFF".equals(i.type())).count();
        assertEquals(1, count, "Explicit AutoPay=false must trigger nudge");
    }

    @Test
    void autoPayOff_doesNotFire_whenAutoPayIsNull() {
        // null means "we don't know". Don't pester the user with an alert based
        // on missing data — could be falsely accusing an active AutoPay user.
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, 10_000, null, /* autoPay */ null, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "AUTOPAY_OFF".equals(i.type())).count();
        assertEquals(0, count, "Unknown AutoPay status must NOT trigger alert");
    }

    @Test
    void autoPayOff_doesNotFire_whenAutoPayIsTrue() {
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, 10_000, null, /* autoPay */ Boolean.TRUE, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "AUTOPAY_OFF".equals(i.type())).count();
        assertEquals(0, count);
    }

    @Test
    void autoPayOff_doesNotFire_onZeroBalanceCards() {
        // No point alerting on an inactive paid-off card. AutoPay matters only
        // when there's a balance the missed payment would affect.
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(/* balance */ 0, 10_000, null,
                        /* autoPay */ Boolean.FALSE, null, null, null)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "AUTOPAY_OFF".equals(i.type())).count();
        assertEquals(0, count);
    }

    // ---------- ANNUAL_FEE_APPROACHING ----------

    @Test
    void annualFeeApproaching_firesWithinThirtyDayWindow() {
        final LocalDate inTwoWeeks = LocalDate.now().plusDays(14);
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, null, null, null, null,
                        new BigDecimal("95.00"), inTwoWeeks)
        ));
        final CreditCardInsightsService.CreditCardInsight ins = service.detect(USER_ID).stream()
                .filter(i -> "ANNUAL_FEE_APPROACHING".equals(i.type()))
                .findFirst().orElseThrow();
        assertEquals(CreditCardInsightsService.Severity.MEDIUM, ins.severity());
        assertTrue(ins.message().contains("95.00"));
        assertTrue(ins.message().contains("14"),
                "Message must include days-until count; got: " + ins.message());
    }

    @Test
    void annualFeeApproaching_doesNotFire_beyondThirtyDayWindow() {
        final LocalDate inSixtyDays = LocalDate.now().plusDays(60);
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, null, null, null, null,
                        new BigDecimal("95.00"), inSixtyDays)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "ANNUAL_FEE_APPROACHING".equals(i.type())).count();
        assertEquals(0, count, "Fee 60 days out is too far — would create alert fatigue");
    }

    @Test
    void annualFeeApproaching_doesNotFire_forPastDates() {
        // A date that already passed = the fee was already billed. Showing an
        // alert would be confusing.
        final LocalDate yesterday = LocalDate.now().minusDays(1);
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, null, null, null, null,
                        new BigDecimal("95.00"), yesterday)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "ANNUAL_FEE_APPROACHING".equals(i.type())).count();
        assertEquals(0, count, "Past fee date must NOT fire — already billed");
    }

    @Test
    void annualFeeApproaching_doesNotFire_withZeroOrNullFee() {
        final LocalDate inWeek = LocalDate.now().plusDays(7);
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, null, null, null, null,
                        /* fee */ BigDecimal.ZERO, inWeek),
                creditCard(500, null, null, null, null,
                        /* fee */ null, inWeek)
        ));
        final long count = service.detect(USER_ID).stream()
                .filter(i -> "ANNUAL_FEE_APPROACHING".equals(i.type())).count();
        assertEquals(0, count, "Zero/null fee must NOT trigger alert");
    }

    @Test
    void annualFeeApproaching_messageUsesCorrectPlural() {
        // "1 day" vs "2 days" — small UX detail.
        final LocalDate tomorrow = LocalDate.now().plusDays(1);
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(500, null, null, null, null,
                        new BigDecimal("95.00"), tomorrow)
        ));
        final String message = service.detect(USER_ID).stream()
                .filter(i -> "ANNUAL_FEE_APPROACHING".equals(i.type()))
                .map(CreditCardInsightsService.CreditCardInsight::message)
                .findFirst().orElseThrow();
        assertTrue(message.contains("1 day "),
                "Singular 'day' for 1-day case; got: " + message);
    }

    // ---------- multiple alerts on the same account ----------

    @Test
    void detect_emitsAllApplicableAlerts_forSingleAccountWithMultipleConditions() {
        // A card that's both past-due AND over-utilized AND AutoPay-off should
        // produce all three alerts — they're independent dimensions.
        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(
                creditCard(/* balance */ 8_500, /* limit */ 10_000,
                        null, /* autoPay */ false,
                        /* pastDue */ new BigDecimal("250"), null, null)
        ));
        final List<String> types = service.detect(USER_ID).stream()
                .map(CreditCardInsightsService.CreditCardInsight::type)
                .sorted().toList();
        assertTrue(types.contains("PAST_DUE"));
        assertTrue(types.contains("HIGH_UTILIZATION"));
        assertTrue(types.contains("AUTOPAY_OFF"));
    }

    // ---------- multiple accounts ----------

    @Test
    void detect_iteratesAllAccounts_independently() {
        final AccountTable cardA = creditCard(500, 10_000, null, null, null, null, null); // healthy
        cardA.setAccountName("Healthy Card");
        final AccountTable cardB = creditCard(8_000, 10_000, null, null, null, null, null); // 80%
        cardB.setAccountName("Stressed Card");

        when(accountRepository.findByUserId(eq(USER_ID))).thenReturn(List.of(cardA, cardB));

        final List<CreditCardInsightsService.CreditCardInsight> insights =
                service.detect(USER_ID);
        // Only cardB should generate an alert.
        final long stressedAlerts = insights.stream()
                .filter(i -> "Stressed Card".equals(i.accountName())).count();
        final long healthyAlerts = insights.stream()
                .filter(i -> "Healthy Card".equals(i.accountName())).count();
        assertTrue(stressedAlerts > 0, "Stressed card must produce alerts");
        assertEquals(0, healthyAlerts, "Healthy card must produce no alerts");
    }

    // ---------- helpers ----------

    private static AccountTable creditCard(
            final double balance,
            final Number limit,
            final BigDecimal apr,
            final Boolean autoPay,
            final BigDecimal pastDue,
            final BigDecimal annualFee,
            final LocalDate annualFeeDueDate) {
        final AccountTable a = new AccountTable();
        a.setAccountId(UUID.randomUUID().toString());
        a.setUserId(USER_ID);
        a.setAccountType("creditCard");
        a.setAccountName("Test Card");
        a.setInstitutionName("Test Bank");
        a.setBalance(BigDecimal.valueOf(balance));
        if (limit != null) {
            a.setCreditLimit(new BigDecimal(limit.toString()));
        }
        a.setAprPercent(apr);
        a.setAutoPayEnabled(autoPay);
        a.setPastDueAmount(pastDue);
        a.setAnnualMembershipFee(annualFee);
        a.setAnnualMembershipFeeDueDate(annualFeeDueDate);
        return a;
    }
}

