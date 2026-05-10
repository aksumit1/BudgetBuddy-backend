package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Flow 7 / O7 — monthly / yearly report exports. Lean, self-contained: we don't pull in a PDF
 * library yet; CSV covers the 95 % case for tax time, and the JSON payload is a drop-in for anyone
 * who wants to render a PDF on the client. If we later add an iText dependency, we can keep the URL
 * stable and add {@code format=pdf}.
 *
 * <p>Endpoints: - {@code GET /api/reports/monthly?year=2026&month=03&format=csv|json} - {@code GET
 * /api/reports/yearly?year=2026&format=csv|json}
 *
 * <p>The CSV returns three sections (spend-by-category, budgets, goals) stacked in one file — easy
 * to import into spreadsheets, simpler than three separate endpoints.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final UserService userService;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;

    public ReportController(
            final UserService userService,
            final TransactionRepository transactionRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository) {
        this.userService = userService;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
    }

    @GetMapping("/monthly")
    public ResponseEntity<?> monthly(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam final int year,
            @RequestParam final int month,
            @RequestParam(defaultValue = "json") final String format) {
        final UserTable user = authenticate(userDetails);
        final LocalDate start = LocalDate.of(year, month, 1);
        final LocalDate end = start.plusMonths(1).minusDays(1);
        return buildReport(user, start, end, format, String.format("%04d-%02d", year, month));
    }

    @GetMapping("/yearly")
    public ResponseEntity<?> yearly(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam final int year,
            @RequestParam(defaultValue = "json") final String format) {
        final UserTable user = authenticate(userDetails);
        final LocalDate start = LocalDate.of(year, 1, 1);
        final LocalDate end = LocalDate.of(year, 12, 31);
        return buildReport(user, start, end, format, String.valueOf(year));
    }

    private UserTable authenticate(final UserDetails userDetails) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        return userService
                .findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
    }

    private ResponseEntity<?> buildReport(
            final UserTable user,
            final LocalDate start,
            final LocalDate end,
            final String format,
            final String label) {
        final List<TransactionTable> txs =
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), start.format(DATE), end.format(DATE));
        final List<BudgetTable> budgets = budgetRepository.findByUserId(user.getUserId());
        final List<GoalTable> goals = goalRepository.findByUserId(user.getUserId());

        // Per-category spend
        final Map<String, BigDecimal> spendByCat = new TreeMap<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        for (final TransactionTable t : txs) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            if (t.getDeletedAt() != null) {
                continue;
            }
            if (t.getAmount().signum() > 0) {
                income = income.add(t.getAmount());
            } else {
                final BigDecimal abs = t.getAmount().abs();
                expenses = expenses.add(abs);
                final String cat =
                        t.getCategoryPrimary() == null ? "uncategorized" : t.getCategoryPrimary();
                spendByCat.merge(cat, abs, BigDecimal::add);
            }
        }

        if ("csv".equalsIgnoreCase(format)) {
            return csvResponse(label, spendByCat, budgets, goals, income, expenses);
        }
        return jsonResponse(label, spendByCat, budgets, goals, income, expenses);
    }

    private ResponseEntity<Map<String, Object>> jsonResponse(
            final String label,
            final Map<String, BigDecimal> spendByCat,
            final List<BudgetTable> budgets,
            final List<GoalTable> goals,
            final BigDecimal income,
            final BigDecimal expenses) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", label);
        out.put("income", income.setScale(2, RoundingMode.HALF_UP));
        out.put("expenses", expenses.setScale(2, RoundingMode.HALF_UP));
        out.put("net", income.subtract(expenses).setScale(2, RoundingMode.HALF_UP));
        out.put("spendByCategory", spendByCat);
        out.put("budgets", budgets);
        out.put("goals", goals);
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<byte[]> csvResponse(
            final String label,
            final Map<String, BigDecimal> spendByCat,
            final List<BudgetTable> budgets,
            final List<GoalTable> goals,
            final BigDecimal income,
            final BigDecimal expenses) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Period,").append(label).append("\n");
        sb.append("Income,").append(income.setScale(2, RoundingMode.HALF_UP)).append("\n");
        sb.append("Expenses,").append(expenses.setScale(2, RoundingMode.HALF_UP)).append("\n");
        sb.append("Net,")
                .append(income.subtract(expenses).setScale(2, RoundingMode.HALF_UP))
                .append("\n\n");

        sb.append("Category,Spent\n");
        for (final var e : spendByCat.entrySet()) {
            sb.append(csvField(e.getKey()))
                    .append(",")
                    .append(e.getValue().setScale(2, RoundingMode.HALF_UP))
                    .append("\n");
        }
        sb.append("\n");

        sb.append("Budget Category,Limit,Period,Currency\n");
        for (final BudgetTable b : budgets) {
            sb.append(csvField(b.getCategory()))
                    .append(",")
                    .append(b.getMonthlyLimit() == null ? "0" : b.getMonthlyLimit())
                    .append(",")
                    .append(b.getPeriod() == null ? "monthly" : b.getPeriod())
                    .append(",")
                    .append(b.getCurrencyCode() == null ? "USD" : b.getCurrencyCode())
                    .append("\n");
        }
        sb.append("\n");

        sb.append("Goal,Current,Target,TargetDate,Horizon,Completed\n");
        for (final GoalTable g : goals) {
            if (g.getDeletedAt() != null) {
                continue;
            }
            sb.append(csvField(g.getName()))
                    .append(",")
                    .append(g.getCurrentAmount() == null ? "0" : g.getCurrentAmount())
                    .append(",")
                    .append(g.getTargetAmount() == null ? "0" : g.getTargetAmount())
                    .append(",")
                    .append(g.getTargetDate() == null ? "" : g.getTargetDate())
                    .append(",")
                    .append(g.getHorizon() == null ? "" : g.getHorizon())
                    .append(",")
                    .append(Boolean.TRUE.equals(g.getCompleted()) ? "yes" : "no")
                    .append("\n");
        }

        final byte[] body = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "budgetbuddy-" + label + ".csv");
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** Escape commas / quotes / newlines per RFC 4180. */
    private String csvField(final String s) {
        if (s == null) {
            return "";
        }
        final boolean needsQuote = s.contains(",") || s.contains("\"") || s.contains("\n");
        final String escaped = s.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }
}
