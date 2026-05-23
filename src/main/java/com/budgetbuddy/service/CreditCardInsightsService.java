package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates credit-card insights from the new statement-summary fields the PDF
 * parser persists on {@link AccountTable} (task #44). Distinct from
 * {@link HighInterestDetectionService} which targets long-running interest cost —
 * this one surfaces operational and behavioural nudges:
 *
 * <ul>
 *   <li><b>Past-due alert</b> — pastDueAmount &gt; 0 means the user is delinquent
 *       and needs immediate action. Highest severity.
 *   <li><b>High-utilization warning</b> — credit utilization &gt; 30% (canonical
 *       FICO threshold). Severity scales with ratio.
 *   <li><b>AutoPay-off nudge</b> — autoPayEnabled is explicitly false on a card
 *       that does carry a balance. Quick win to enable.
 *   <li><b>Annual fee approaching</b> — annualMembershipFeeDueDate within the
 *       next 30 days, paired with the fee amount. Lets the user decide whether
 *       to downgrade / cancel before the fee hits.
 * </ul>
 *
 * <p>Each insight is computed off the persisted AccountTable row — no
 * recomputation, no extra DB read per metric. The service is stateless and safe
 * to call repeatedly.
 */
// Spring constructor injection — AccountRepository is shared by design.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class CreditCardInsightsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreditCardInsightsService.class);

    private final AccountRepository accountRepository;
    private final com.budgetbuddy.config.InsightsThresholds thresholds;

    @org.springframework.beans.factory.annotation.Autowired
    public CreditCardInsightsService(
            final AccountRepository accountRepository,
            final com.budgetbuddy.config.InsightsThresholds thresholds) {
        this.accountRepository = accountRepository;
        // Defensive default for Mockito @InjectMocks paths.
        this.thresholds = thresholds != null
                ? thresholds
                : new com.budgetbuddy.config.InsightsThresholds();
    }

    /** Backwards-compat constructor for tests; uses default thresholds. */
    public CreditCardInsightsService(final AccountRepository accountRepository) {
        this(accountRepository, new com.budgetbuddy.config.InsightsThresholds());
    }

    private BigDecimal utilizationWarningThreshold() {
        return BigDecimal.valueOf(thresholds.getCreditCard().getUtilizationWarningThreshold());
    }

    private BigDecimal utilizationHighThreshold() {
        return BigDecimal.valueOf(thresholds.getCreditCard().getUtilizationHighThreshold());
    }

    private int annualFeeWarningWindowDays() {
        return thresholds.getCreditCard().getAnnualFeeWarningWindowDays();
    }

    /** Severity ladder used across credit-card insights. Mirrors other services. */
    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }

    /** A single credit-card insight emitted by this service. */
    public record CreditCardInsight(
            String accountId,
            String accountName,
            String institutionName,
            String type, // PAST_DUE / HIGH_UTILIZATION / AUTOPAY_OFF / ANNUAL_FEE_APPROACHING
            Severity severity,
            String message,
            String recommendation) {}

    /**
     * Produce every credit-card insight for the user. Iterates accounts ONCE per
     * call; each insight type is a constant-cost predicate on the account row
     * (no extra DB reads). Returns an empty list when the user has no credit
     * cards or none of the conditions fire.
     */
    public List<CreditCardInsight> detect(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        final List<AccountTable> accounts = accountRepository.findByUserId(userId);
        final List<CreditCardInsight> out = new ArrayList<>();
        for (final AccountTable account : accounts) {
            if (account == null
                    || account.getAccountType() == null
                    || !"creditCard".equals(account.getAccountType())) {
                continue;
            }
            collectPastDueAlert(account, out);
            collectHighUtilizationWarning(account, out);
            collectAutoPayOffNudge(account, out);
            collectAnnualFeeApproaching(account, out);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Credit-card insights for user {}: {} alerts", userId, out.size());
        }
        return out;
    }

    // ---------- past due ----------

    private static void collectPastDueAlert(
            final AccountTable account, final List<CreditCardInsight> out) {
        final BigDecimal past = account.getPastDueAmount();
        if (past == null || past.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        out.add(
                new CreditCardInsight(
                        account.getAccountId(),
                        account.getAccountName(),
                        account.getInstitutionName(),
                        "PAST_DUE",
                        Severity.HIGH,
                        String.format(
                                Locale.US,
                                "Past due on %s: $%s",
                                account.getAccountName(),
                                past.setScale(2, RoundingMode.HALF_UP).toPlainString()),
                        "Pay the past-due balance immediately to avoid penalty APR and"
                                + " credit-score impact."));
    }

    // ---------- utilization ----------

    private void collectHighUtilizationWarning(
            final AccountTable account, final List<CreditCardInsight> out) {
        final BigDecimal limit = account.getCreditLimit();
        final BigDecimal balance = account.getBalance();
        if (limit == null
                || limit.compareTo(BigDecimal.ZERO) <= 0
                || balance == null
                || balance.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        // Use absolute balance — credit cards carry negative sign in our convention.
        final BigDecimal absBalance = balance.abs();
        final BigDecimal utilization =
                absBalance.divide(limit, 4, RoundingMode.HALF_UP);
        if (utilization.compareTo(utilizationWarningThreshold()) <= 0) {
            return;
        }
        final Severity severity =
                utilization.compareTo(utilizationHighThreshold()) > 0
                        ? Severity.HIGH
                        : Severity.MEDIUM;
        out.add(
                new CreditCardInsight(
                        account.getAccountId(),
                        account.getAccountName(),
                        account.getInstitutionName(),
                        "HIGH_UTILIZATION",
                        severity,
                        String.format(
                                Locale.US,
                                "%s utilization is %.0f%% ($%s of $%s)",
                                account.getAccountName(),
                                utilization.doubleValue() * 100,
                                absBalance.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                limit.setScale(2, RoundingMode.HALF_UP).toPlainString()),
                        severity == Severity.HIGH
                                ? "Pay down to below 30% utilization — keeping it above 70% can"
                                        + " materially hurt your credit score."
                                : "Aim to keep utilization below 30% to optimize your credit score."));
    }

    // ---------- AutoPay off ----------

    private static void collectAutoPayOffNudge(
            final AccountTable account, final List<CreditCardInsight> out) {
        // Only fire when AutoPay is EXPLICITLY off (Boolean.FALSE), AND the card
        // carries a balance worth automating. autoPayEnabled == null means we
        // don't know — don't pester the user with a guess.
        if (!Boolean.FALSE.equals(account.getAutoPayEnabled())) {
            return;
        }
        final BigDecimal balance = account.getBalance();
        if (balance == null || balance.abs().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        out.add(
                new CreditCardInsight(
                        account.getAccountId(),
                        account.getAccountName(),
                        account.getInstitutionName(),
                        "AUTOPAY_OFF",
                        Severity.LOW,
                        String.format(
                                Locale.US,
                                "AutoPay is off on %s — risk of missed payment.",
                                account.getAccountName()),
                        "Enable AutoPay for at least the minimum to protect your credit score from"
                                + " accidental missed payments."));
    }

    // ---------- annual fee approaching ----------

    private void collectAnnualFeeApproaching(
            final AccountTable account, final List<CreditCardInsight> out) {
        final BigDecimal fee = account.getAnnualMembershipFee();
        final LocalDate dueDate = account.getAnnualMembershipFeeDueDate();
        if (fee == null
                || fee.compareTo(BigDecimal.ZERO) <= 0
                || dueDate == null) {
            return;
        }
        final long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
        if (daysUntil < 0 || daysUntil > annualFeeWarningWindowDays()) {
            return; // outside the warning window — either already passed or too far
        }
        out.add(
                new CreditCardInsight(
                        account.getAccountId(),
                        account.getAccountName(),
                        account.getInstitutionName(),
                        "ANNUAL_FEE_APPROACHING",
                        Severity.MEDIUM,
                        String.format(
                                Locale.US,
                                "Annual fee of $%s due in %d day%s on %s",
                                fee.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                daysUntil,
                                daysUntil == 1 ? "" : "s",
                                account.getAccountName()),
                        "Review whether the card's benefits still justify the fee. You can usually"
                                + " downgrade or cancel within 30 days of the fee posting for a"
                                + " refund."));
    }
}
