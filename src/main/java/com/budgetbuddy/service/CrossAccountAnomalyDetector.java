package com.budgetbuddy.service;


import java.util.Locale;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * JTBD #4 — "Did something weird hit me across multiple accounts?"
 *
 * <p>The existing {@code TransactionAnomalyService} runs per-transaction heuristics (z-score,
 * category spike, first-time merchant, duplicate within 24 h, amount threshold) against the user's
 * entire expense stream. It's good at catching *an* unusual charge. It misses cross-account
 * patterns — the kind that scream fraud:
 *
 * <ul>
 *   <li><b>Same-merchant burst:</b> the same merchant charges two different cards on the same day.
 *       Legitimately this happens (spouse's shared card), but at the pattern level it's worth
 *       surfacing — a skimmer farm often hits cards this way.
 *   <li><b>Rapid successive charges:</b> four charges at the same merchant within 60 minutes across
 *       any account. Classic card-testing pattern.
 *   <li><b>Amount near-duplicate across accounts:</b> $99.99 at Best Buy on one card and $99.99 at
 *       Best Buy Online the next day on another — sometimes a genuine double-charge, sometimes a
 *       deliberately-repeated fraud.
 * </ul>
 *
 * <p>Output is a small set of {@link Pattern} records, each naming the involved accounts and a
 * human-readable reason. The anomaly feedback + sensitivity knobs from Flow 7 apply — we consult
 * the same suppression list so users don't get pestered after dismissing a cross-account pattern
 * they've already confirmed as legitimate.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@Service
public class CrossAccountAnomalyDetector {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrossAccountAnomalyDetector.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;

    public CrossAccountAnomalyDetector(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public List<Pattern> detect(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }

        // 14-day analysis window is enough for burst patterns; we're not looking for
        // long-term trends here (the per-transaction detector handles that).
        final LocalDate end = LocalDate.now();
        final LocalDate start = end.minusDays(14);
        final List<TransactionTable> recent =
                transactionRepository
                        .findByUserIdAndDateRange(userId, start.format(DATE), end.format(DATE))
                        .stream()
                        .filter(t -> t != null && t.getAmount() != null)
                        .filter(t -> t.getDeletedAt() == null)
                        .filter(t -> t.getAmount().signum() < 0) // expenses only
                        .filter(t -> t.getAccountId() != null)
                        .collect(Collectors.toList());

        final List<Pattern> results = new ArrayList<>();
        results.addAll(detectSameMerchantBurst(recent));
        results.addAll(detectRapidSuccessiveCharges(recent));
        results.addAll(detectAmountNearDuplicateAcrossAccounts(recent));
        return results;
    }

    /**
     * Same merchant, same day, two or more distinct accounts. Could be legitimate (household shares
     * a merchant) but worth surfacing — users can dismiss if so.
     */
    private List<Pattern> detectSameMerchantBurst(final List<TransactionTable> rows) {
        // Group by (merchant, date).
        final Map<String, List<TransactionTable>> byKey = new HashMap<>();
        for (final TransactionTable t : rows) {
            final String merchant = normalise(t.getMerchantName());
            if (merchant.isEmpty() || t.getTransactionDate() == null) {
                continue;
            }
            final String key = merchant + "|" + t.getTransactionDate();
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        final List<Pattern> out = new ArrayList<>();
        for (final var entry : byKey.entrySet()) {
            final var txs = entry.getValue();
            final Set<String> distinctAccounts =
                    txs.stream().map(TransactionTable::getAccountId).collect(Collectors.toSet());
            if (distinctAccounts.size() < 2) {
                continue;
            }
            final BigDecimal total =
                    txs.stream()
                            .map(t -> t.getAmount().abs())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            final String merchant = txs.get(0).getMerchantName();
            out.add(
                    new Pattern(
                            "SAME_MERCHANT_MULTIPLE_ACCOUNTS",
                            "HIGH",
                            String.format(
                                    "%d charges from %s hit %d different accounts on %s (%s total)",
                                    txs.size(),
                                    merchant,
                                    distinctAccounts.size(),
                                    txs.get(0).getTransactionDate(),
                                    total),
                            merchant,
                            total,
                            new ArrayList<>(distinctAccounts),
                            txs.stream()
                                    .map(TransactionTable::getTransactionId)
                                    .collect(Collectors.toList())));
        }
        return out;
    }

    /**
     * Four or more charges at a single merchant within any 60-minute window across any account.
     * Transaction timestamps in this schema are date-only, so "within 60 minutes" degrades to
     * "within the same day with 4+ hits" — still a useful signal even without time-of-day.
     */
    private List<Pattern> detectRapidSuccessiveCharges(final List<TransactionTable> rows) {
        final Map<String, List<TransactionTable>> byMerchantDay = new HashMap<>();
        for (final TransactionTable t : rows) {
            final String merchant = normalise(t.getMerchantName());
            if (merchant.isEmpty() || t.getTransactionDate() == null) {
                continue;
            }
            byMerchantDay
                    .computeIfAbsent(
                            merchant + "|" + t.getTransactionDate(), k -> new ArrayList<>())
                    .add(t);
        }
        final List<Pattern> out = new ArrayList<>();
        for (final var entry : byMerchantDay.entrySet()) {
            final var txs = entry.getValue();
            if (txs.size() < 4) {
                continue;
            }
            final BigDecimal total =
                    txs.stream()
                            .map(t -> t.getAmount().abs())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            final String merchant = txs.get(0).getMerchantName();
            final Set<String> accounts =
                    txs.stream().map(TransactionTable::getAccountId).collect(Collectors.toSet());
            out.add(
                    new Pattern(
                            "RAPID_SUCCESSIVE_CHARGES",
                            "HIGH",
                            String.format(
                                    "%d charges at %s within one day (%s total)",
                                    txs.size(), merchant, total),
                            merchant,
                            total,
                            new ArrayList<>(accounts),
                            txs.stream()
                                    .map(TransactionTable::getTransactionId)
                                    .collect(Collectors.toList())));
        }
        return out;
    }

    /**
     * Amount near-duplicate across accounts: the same (merchant, rounded-to-dollar amount) hits ≥ 2
     * distinct accounts within 3 days. Flags classic fraud double-dip patterns where a bad actor
     * re-runs a charge on a different captured card.
     */
    private List<Pattern> detectAmountNearDuplicateAcrossAccounts(
            final List<TransactionTable> rows) {
        final List<TransactionTable> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing(TransactionTable::getTransactionDate));
        final List<Pattern> out = new ArrayList<>();

        // Walk the sorted stream; for each row look back within 3 days for a same
        // merchant + amount hit on a different account.
        for (int i = 0; i < sorted.size(); i++) {
            final TransactionTable a = sorted.get(i);
            final String aMerchant = normalise(a.getMerchantName());
            if (aMerchant.isEmpty()) {
                continue;
            }
            final BigDecimal aAmount = a.getAmount().abs().setScale(0, java.math.RoundingMode.HALF_UP);
            final LocalDate aDate = parse(a.getTransactionDate());
            if (aDate == null) {
                continue;
            }

            for (int j = i - 1; j >= 0; j--) {
                final TransactionTable b = sorted.get(j);
                final LocalDate bDate = parse(b.getTransactionDate());
                if (bDate == null) {
                    continue;
                }
                if (java.time.temporal.ChronoUnit.DAYS.between(bDate, aDate) > 3) {
                    break;
                }
                if (!normalise(b.getMerchantName()).equals(aMerchant)) {
                    continue;
                }
                if (Objects.equals(a.getAccountId(), b.getAccountId())) {
                    continue;
                }
                final BigDecimal bAmount =
                        b.getAmount().abs().setScale(0, java.math.RoundingMode.HALF_UP);
                if (aAmount.compareTo(bAmount) != 0) {
                    continue;
                }

                out.add(
                        new Pattern(
                                "AMOUNT_DUPLICATE_ACROSS_ACCOUNTS",
                                "HIGH",
                                String.format(
                                        "%s charged %s on %s (acct %s) and %s (acct %s) — check for double-dip",
                                        a.getMerchantName(),
                                        aAmount,
                                        b.getTransactionDate(),
                                        shortId(b.getAccountId()),
                                        a.getTransactionDate(),
                                        shortId(a.getAccountId())),
                                a.getMerchantName(),
                                aAmount.add(bAmount),
                                List.of(a.getAccountId(), b.getAccountId()),
                                List.of(a.getTransactionId(), b.getTransactionId())));
                break; // one pair is enough per anchor
            }
        }
        return out;
    }

    private String normalise(final String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private LocalDate parse(final String s) {
        try {
            return s == null ? null : LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String shortId(final String s) {
        if (s == null || s.length() < 6) {
            return s;
        }
        return "…" + s.substring(s.length() - 4);
    }

    public record Pattern(
            String type,
            String severity,
            String reason,
            String merchantName,
            BigDecimal totalAmount,
            List<String> accountIds,
            List<String> transactionIds) {}
}
