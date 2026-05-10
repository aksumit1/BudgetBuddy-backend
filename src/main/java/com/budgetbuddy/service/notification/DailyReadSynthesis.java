package com.budgetbuddy.service.notification;


import java.util.Locale;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Server-side port of iOS {@code DailyReadService}.
 *
 * <p>Produces a one-line narrative synthesis per user for the daily email. Uses the same priority
 * order (risk → caution → steady) and the same warm- toned copy so the email matches what the user
 * sees in-app.
 *
 * <p>Intentionally a subset of the iOS logic — no subscription tier, no promo APR (those require
 * extra iOS-side state not persisted). Covers the most-used signals: credit utilisation, cash
 * cushion, steady fallback.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class DailyReadSynthesis {

    /** A synthesised headline + mood band. Wire-compatible with iOS copy. */
    public static class Read {
        public enum Mood {
            ALERT,
            CAUTION,
            STEADY,
            CELEBRATION
        }

        public final Mood mood;
        public final String headline;

        public Read(final Mood mood, final String headline) {
            this.mood = mood;
            this.headline = headline;
        }
    }

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public DailyReadSynthesis(
            final AccountRepository accountRepository,
            final TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public Read synthesise(final String userId) {
        final List<AccountTable> accounts = safeAccounts(userId);

        // Credit utilisation — aggregate across credit / charge cards with limits.
        BigDecimal totalBalance = BigDecimal.ZERO;
        BigDecimal totalLimit = BigDecimal.ZERO;
        for (final AccountTable a : accounts) {
            if (a == null || a.getAccountType() == null) {
                continue;
            }
            final String type = a.getAccountType().toLowerCase(Locale.ROOT);
            if (!"creditcard".equals(type) && !"chargecard".equals(type)) {
                continue;
            }
            if (a.getCreditLimit() == null || a.getCreditLimit().signum() <= 0) {
                continue;
            }
            final BigDecimal balance = a.getBalance() == null ? BigDecimal.ZERO : a.getBalance().abs();
            totalBalance = totalBalance.add(balance);
            totalLimit = totalLimit.add(a.getCreditLimit());
        }
        final double util =
                totalLimit.signum() > 0
                        ? totalBalance.divide(totalLimit, 4, RoundingMode.HALF_UP).doubleValue()
                        : 0.0;
        if (util >= 0.50) {
            return new Read(
                    Read.Mood.ALERT,
                    "Your credit is "
                            + (int) (util * 100)
                            + "% used — bringing this down helps your score.");
        }
        if (util >= 0.30) {
            return new Read(
                    Read.Mood.CAUTION,
                    "Credit " + (int) (util * 100) + "% in use — worth bringing under 30%.");
        }

        // Cash cushion — liquid vs outflow last 30 days.
        double liquid = 0;
        for (final AccountTable a : accounts) {
            if (a == null || a.getAccountType() == null) {
                continue;
            }
            final String type = a.getAccountType().toLowerCase(Locale.ROOT);
            if ("checking".equals(type) || "savings".equals(type) || "moneymarket".equals(type)) {
                liquid += a.getBalance() == null ? 0 : Math.max(0, a.getBalance().doubleValue());
            }
        }
        final double outflow30 = sumRecentOutflow(userId, 30);
        if (outflow30 > 0 && liquid < outflow30 * 0.33) {
            return new Read(
                    Read.Mood.CAUTION,
                    "Tight month ahead — your cushion is thin relative to typical outflow.");
        }

        return new Read(Read.Mood.STEADY, "Nothing urgent today. Take the win.");
    }

    // MARK - helpers

    private List<AccountTable> safeAccounts(final String userId) {
        try {
            return accountRepository.findByUserId(userId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private double sumRecentOutflow(final String userId, final int days) {
        try {
            final LocalDate end = LocalDate.now();
            final LocalDate start = end.minusDays(days);
            final List<TransactionTable> rows =
                    transactionRepository.findByUserIdAndDateRange(
                            userId, start.toString(), end.toString());
            double total = 0;
            final Set<String> transferCats = Set.of("transfer", "payment");
            for (final TransactionTable t : rows) {
                if (t == null || t.getAmount() == null) {
                    continue;
                }
                if (t.getDeletedAt() != null) {
                    continue;
                }
                if (t.getCategoryPrimary() != null
                        && transferCats.contains(t.getCategoryPrimary().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (t.getAmount().signum() < 0) {
                    total += t.getAmount().abs().doubleValue();
                }
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }
}
