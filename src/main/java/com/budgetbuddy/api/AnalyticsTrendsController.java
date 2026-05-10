package com.budgetbuddy.api;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.NetWorthSnapshotTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.NetWorthSnapshotRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 7 / O14 — server-side month-over-month trends.
 *
 * <p>Until now the iOS client bucketed transactions by month in a local helper. That's three
 * implementations (iOS, future web, future watch) for the same maths. This controller owns it.
 *
 * <p>One endpoint: {@code GET /api/analytics/trends?metric={spend|saving|networth}&window=12}
 * Returns a list of {@code {period: "2026-03", value: 1234.56}} rows, oldest first.
 *
 * <p>Response is cached under the tight analytics manager (5-min TTL, Flow 7 / O13) so repeat hits
 * from the chart don't hammer DynamoDB.
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsTrendsController {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final UserService userService;
    private final TransactionRepository transactionRepository;
    private final NetWorthSnapshotRepository netWorthRepository;
    private final TrendsCache trendsCache;

    public AnalyticsTrendsController(
            final UserService userService,
            final TransactionRepository transactionRepository,
            final NetWorthSnapshotRepository netWorthRepository,
            final TrendsCache trendsCache) {
        this.userService = userService;
        this.transactionRepository = transactionRepository;
        this.netWorthRepository = netWorthRepository;
        this.trendsCache = trendsCache;
    }

    @GetMapping("/trends")
    public ResponseEntity<List<Map<String, Object>>> trends(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(defaultValue = "spend") final String metric,
            @RequestParam(defaultValue = "12") final int window) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        final int w = Math.max(1, Math.min(window, 60));
        final String metricKey = metric == null ? "spend" : metric.toLowerCase(Locale.ROOT);
        switch (metricKey) {
            case "spend":
                return ResponseEntity.ok(trendsCache.spendTrend(user.getUserId(), w));
            case "saving":
                return ResponseEntity.ok(trendsCache.savingTrend(user.getUserId(), w));
            case "networth":
                return ResponseEntity.ok(trendsCache.netWorthTrend(user.getUserId(), w));
            default:
                throw new AppException(
                        ErrorCode.INVALID_INPUT, "metric must be spend | saving | networth");
        }
    }

    /**
     * Cache shim — the @Cacheable annotation on this bean makes caching work. Keeping it separate
     * lets the controller stay small and the cache keys readable.
     */
    @Component
    @SuppressWarnings("PMD.LawOfDemeter") // DTO/BigDecimal getter chains, see outer class.
    public static class TrendsCache {

        private final TransactionRepository transactionRepository;
        private final NetWorthSnapshotRepository netWorthRepository;

        public TrendsCache(
                final TransactionRepository transactionRepository,
                final NetWorthSnapshotRepository netWorthRepository) {
            this.transactionRepository = transactionRepository;
            this.netWorthRepository = netWorthRepository;
        }

        @Cacheable(
                value = "analytics",
                cacheManager = "analyticsCacheManager",
                key = "#userId + '_trend_spend_' + #months")
        public List<Map<String, Object>> spendTrend(final String userId, final int months) {
            return monthlyAggregate(userId, months, true);
        }

        @Cacheable(
                value = "analytics",
                cacheManager = "analyticsCacheManager",
                key = "#userId + '_trend_saving_' + #months")
        public List<Map<String, Object>> savingTrend(final String userId, final int months) {
            // saving = income - expenses per month
            final LocalDate today = LocalDate.now();
            final LocalDate from = today.minusMonths(months - 1).withDayOfMonth(1);
            final List<TransactionTable> rows =
                    transactionRepository.findByUserIdAndDateRange(
                            userId, from.format(DATE), today.format(DATE));
            final Map<String, BigDecimal> savingByMonth = new TreeMap<>();
            for (int i = 0; i < months; i++) {
                savingByMonth.put(from.plusMonths(i).format(MONTH), BigDecimal.ZERO);
            }
            for (final TransactionTable t : rows) {
                if (t == null || t.getAmount() == null) {
                    continue;
                }
                if (t.getDeletedAt() != null) {
                    continue;
                }
                if (t.getTransactionDate() == null || t.getAmount() == null) {
                    continue;
                }
                try {
                    final YearMonth ym = YearMonth.from(LocalDate.parse(t.getTransactionDate()));
                    final String key = ym.format(MONTH);
                    if (!savingByMonth.containsKey(key)) {
                        continue;
                    }
                    savingByMonth.merge(key, t.getAmount(), BigDecimal::add);
                } catch (DateTimeException ignored) {
                    // skip rows whose transaction date can't be parsed
                }
            }
            return toTrend(savingByMonth);
        }

        @Cacheable(
                value = "analytics",
                cacheManager = "analyticsCacheManager",
                key = "#userId + '_trend_networth_' + #months")
        public List<Map<String, Object>> netWorthTrend(final String userId, final int months) {
            // Pull one snapshot per month (the last one in each) from the snapshot table.
            final LocalDate from = LocalDate.now().minusMonths(months - 1).withDayOfMonth(1);
            final List<NetWorthSnapshotTable> snapshots =
                    netWorthRepository.findByUserIdSince(userId, from.format(DATE));
            final Map<String, NetWorthSnapshotTable> byMonth = new TreeMap<>();
            for (final NetWorthSnapshotTable s : snapshots) {
                if (s == null || s.getSnapshotDate() == null) {
                    continue;
                }
                try {
                    final YearMonth ym = YearMonth.from(LocalDate.parse(s.getSnapshotDate()));
                    byMonth.merge(
                            ym.format(MONTH),
                            s,
                            (existing, candidate) ->
                                    existing.getSnapshotDate()
                                                            .compareTo(candidate.getSnapshotDate())
                                                    >= 0
                                            ? existing
                                            : candidate);
                } catch (DateTimeException ignored) {
                    // skip snapshots whose date string can't be parsed
                }
            }
            final List<Map<String, Object>> out = new ArrayList<>();
            for (int i = 0; i < months; i++) {
                final String key = from.plusMonths(i).format(MONTH);
                final NetWorthSnapshotTable s = byMonth.get(key);
                final BigDecimal value = s == null ? BigDecimal.ZERO : s.getNetWorth();
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", key);
                row.put("value", value == null ? BigDecimal.ZERO : value);
                out.add(row);
            }
            return out;
        }

        private List<Map<String, Object>> monthlyAggregate(
                final String userId, final int months, final boolean expensesOnly) {
            final LocalDate today = LocalDate.now();
            final LocalDate from = today.minusMonths(months - 1).withDayOfMonth(1);
            final List<TransactionTable> rows =
                    transactionRepository.findByUserIdAndDateRange(
                            userId, from.format(DATE), today.format(DATE));
            final Map<String, BigDecimal> byMonth = new TreeMap<>();
            for (int i = 0; i < months; i++) {
                byMonth.put(from.plusMonths(i).format(MONTH), BigDecimal.ZERO);
            }
            for (final TransactionTable t : rows) {
                if (t == null || t.getAmount() == null) {
                    continue;
                }
                if (t.getDeletedAt() != null) {
                    continue;
                }
                if (expensesOnly && t.getAmount().signum() >= 0) {
                    continue;
                }
                try {
                    final YearMonth ym = YearMonth.from(LocalDate.parse(t.getTransactionDate()));
                    final String key = ym.format(MONTH);
                    if (!byMonth.containsKey(key)) {
                        continue;
                    }
                    byMonth.merge(key, t.getAmount().abs(), BigDecimal::add);
                } catch (DateTimeException ignored) {
                    // skip rows whose transaction date can't be parsed
                }
            }
            return toTrend(byMonth);
        }

        private List<Map<String, Object>> toTrend(final Map<String, BigDecimal> byMonth) {
            final List<Map<String, Object>> out = new ArrayList<>();
            for (final var e : byMonth.entrySet()) {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("period", e.getKey());
                row.put("value", e.getValue().setScale(2, RoundingMode.HALF_UP));
                out.add(row);
            }
            return out;
        }
    }
}
