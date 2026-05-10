package com.budgetbuddy.service.correctness;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.aws.CloudWatchService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Detects drift between Plaid's reported account balance delta and the transaction-derived delta
 * across a sync cycle.
 *
 * <p><strong>What drift means here.</strong> Between two consecutive Plaid syncs, Plaid's balance
 * should move by exactly the sum of transactions ingested in that window. If Plaid's balance jumped
 * $500 but only $200 of transactions arrived to explain it, either (a) we failed to ingest
 * transactions, (b) Plaid corrected an earlier balance, (c) a pending posted with an amount we
 * missed. All three are user-visible drift.
 *
 * <p><strong>Why not absolute balance vs transaction sum.</strong> That comparison only works if
 * transactions span from account-opening to now. We keep a 90-day window; comparing plaidBalance to
 * a 90-day sum would always report massive "drift" that's really just the pre-window balance.
 * Delta-vs-delta is the right shape.
 *
 * <p><strong>Signs.</strong> Our TransactionTable convention: negative amounts are outflows,
 * positive are inflows. For a depository account the balance change equals {@code sum(amount)}. For
 * a liability account the balance is "amount owed"; an outflow (purchase, negative amount)
 * <em>increases</em> what's owed, so sign flips.
 *
 * <p><strong>Failure mode is best-effort.</strong> Never throws into the sync path. On any
 * exception, logs and returns null so the sync itself still completes.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class BalanceReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceReconciliationService.class);

    /** Below this delta we treat drift as noise (rounding, currency micro-FX). */
    private static final BigDecimal NOISE_THRESHOLD = new BigDecimal("1.00");

    /** Above this delta we log at WARN (vs INFO) and raise a metric alarm. */
    private static final BigDecimal WARN_THRESHOLD = new BigDecimal("10.00");

    /**
     * Safety floor — if the previous lastSyncedAt isn't available, fall back to a 30-day window so
     * we don't scan the full history of every account on first sync.
     */
    private static final int FALLBACK_WINDOW_DAYS = 30;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CloudWatchService cloudWatchService;

    public BalanceReconciliationService(
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository,
            final CloudWatchService cloudWatchService) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.cloudWatchService = cloudWatchService;
    }

    /**
     * Compare Plaid's balance delta to the transaction-derived delta across this sync cycle. Called
     * with the in-memory, about-to-be-saved account (has the NEW Plaid balance and the OLD
     * lastSyncedAt — we re-read the persisted row to recover the previous balance).
     *
     * @return signed drift ({@code plaidDelta − derivedDelta}) for structured logging; {@code null}
     *     if reconciliation couldn't run (missing data, first sync with no prior state).
     */
    public BigDecimal reconcile(final AccountTable account) {
        if (account == null || account.getUserId() == null || account.getAccountId() == null) {
            return null;
        }
        final BigDecimal newPlaidBalance = account.getBalance();
        if (newPlaidBalance == null) {
            return null;
        }

        try {
            // Recover the previous persisted state. On first sync there's
            // no prior state, so we skip — there's no delta to check yet.
            final Optional<AccountTable> prior = accountRepository.findById(account.getAccountId());
            if (prior.isEmpty() || prior.get().getBalance() == null) {
                return null;
            }
            final BigDecimal oldPlaidBalance = prior.get().getBalance();
            final Instant lastSyncedAt = prior.get().getLastSyncedAt();

            // Determine the window of transactions that SHOULD explain the
            // balance movement. Prefer "since last sync"; fall back to 30d
            // if lastSyncedAt isn't recorded.
            final LocalDate end = LocalDate.now();
            final LocalDate start;
            if (lastSyncedAt != null) {
                start = LocalDate.ofInstant(lastSyncedAt, java.time.ZoneOffset.UTC);
            } else {
                start = end.minusDays(FALLBACK_WINDOW_DAYS);
            }
            final List<TransactionTable> all =
                    transactionRepository.findByUserIdAndDateRange(
                            account.getUserId(), start.format(DATE), end.format(DATE));

            BigDecimal derivedSum = BigDecimal.ZERO;
            int counted = 0;
            for (final TransactionTable t : all) {
                if (t == null || t.getAmount() == null) {
                    continue;
                }
                if (!account.getAccountId().equals(t.getAccountId())) {
                    continue;
                }
                if (t.getDeletedAt() != null) {
                    continue;
                }
                derivedSum = derivedSum.add(t.getAmount());
                counted++;
            }

            final boolean isLiability = isLiabilityAccount(account);
            final BigDecimal derivedDelta = isLiability ? derivedSum.negate() : derivedSum;
            final BigDecimal plaidDelta = newPlaidBalance.subtract(oldPlaidBalance);

            // Drift = how much of the balance change isn't explained by the
            // transactions we ingested. Zero = perfect agreement.
            final BigDecimal drift = plaidDelta.subtract(derivedDelta).setScale(2, RoundingMode.HALF_UP);
            final BigDecimal absDrift = drift.abs();

            recordMetric(account, absDrift);

            if (absDrift.compareTo(WARN_THRESHOLD) >= 0) {
                LOG.warn(
                        "Balance drift WARN: userId={} accountId={} type={} plaidDelta={} derivedDelta={} drift={} txCount={}",
                        account.getUserId(),
                        account.getAccountId(),
                        account.getAccountType(),
                        plaidDelta,
                        derivedDelta,
                        drift,
                        counted);
            } else if (absDrift.compareTo(NOISE_THRESHOLD) >= 0) {
                LOG.info(
                        "Balance drift INFO: userId={} accountId={} type={} plaidDelta={} derivedDelta={} drift={} txCount={}",
                        account.getUserId(),
                        account.getAccountId(),
                        account.getAccountType(),
                        plaidDelta,
                        derivedDelta,
                        drift,
                        counted);
            }
            return drift;
        } catch (Exception e) {
            // Never let reconciliation throw into the sync path.
            LOG.warn(
                    "Balance reconciliation failed for user={} account={}: {}",
                    account.getUserId(),
                    account.getAccountId(),
                    e.getMessage());
            return null;
        }
    }

    private static boolean isLiabilityAccount(final AccountTable account) {
        final String type = account.getAccountType();
        if (type == null) {
            return false;
        }
        final String lower = type.toLowerCase(Locale.ROOT);
        return lower.contains("credit") || lower.contains("loan") || lower.contains("mortgage");
    }

    private void recordMetric(final AccountTable account, final BigDecimal absDrift) {
        try {
            final Map<String, String> dims = new HashMap<>();
            dims.put(
                    "AccountType",
                    account.getAccountType() == null ? "unknown" : account.getAccountType());
            cloudWatchService.putMetric("balance.drift.abs", absDrift.doubleValue(), dims);
        } catch (Exception e) {
            LOG.debug("Could not publish balance drift metric: {}", e.getMessage());
        }
    }
}
