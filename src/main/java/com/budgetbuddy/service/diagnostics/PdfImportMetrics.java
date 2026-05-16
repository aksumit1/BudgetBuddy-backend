package com.budgetbuddy.service.diagnostics;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Per-parse metrics emission. All metrics tag on {@code institution} and
 * {@code account_type} so dashboards can slice by issuer. The goal is to detect
 * regressions on a per-issuer basis before users notice — when Chase's
 * reconciliation pass rate suddenly drops from 99% to 80% next month, we know
 * their template changed.
 *
 * <h3>Metric inventory</h3>
 * <ul>
 *   <li>{@code pdf_import_total} (counter, status=success|error)</li>
 *   <li>{@code pdf_import_reconciliation_total} (counter, bucket=debit|credit,
 *       status=pass|fail|skipped)</li>
 *   <li>{@code pdf_import_transactions} (distribution summary): tx count per parse</li>
 *   <li>{@code pdf_import_duration_seconds} (timer): wall-clock per parse</li>
 *   <li>{@code pdf_import_failure_reasons_total} (counter, reason=...)</li>
 * </ul>
 *
 * <p>If no {@link MeterRegistry} is available (test contexts, no actuator),
 * the component is created with a no-op registry and emission is silent.
 */
@Component
public class PdfImportMetrics {

    private final MeterRegistry registry;

    @Autowired(required = false)
    public PdfImportMetrics(final MeterRegistry registry) {
        this.registry = registry == null
                ? new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                : registry;
    }

    /** Record a complete parse. Call this exactly once per parsePDF invocation. */
    public void recordParse(
            final ImportResult result,
            final Throwable failure,
            final long durationNanos) {
        final String institution = institutionTag(result);
        final String accountType = accountTypeTag(result);
        final String status = failure == null ? "success" : "error";

        Counter.builder("pdf_import_total")
                .tags("institution", institution,
                        "account_type", accountType,
                        "status", status)
                .register(registry)
                .increment();

        if (failure != null) {
            Counter.builder("pdf_import_failure_reasons_total")
                    .tags("institution", institution,
                            "reason", failure.getClass().getSimpleName())
                    .register(registry)
                    .increment();
            return;
        }

        Timer.builder("pdf_import_duration_seconds")
                .tags("institution", institution, "account_type", accountType)
                .publishPercentileHistogram()
                .register(registry)
                .record(java.time.Duration.ofNanos(durationNanos));

        if (result != null) {
            DistributionSummary.builder("pdf_import_transactions")
                    .tags("institution", institution, "account_type", accountType)
                    .register(registry)
                    .record(result.getTransactions().size());

            recordReconciliation(result, institution, accountType);
        }
    }

    private void recordReconciliation(
            final ImportResult result,
            final String institution,
            final String accountType) {
        // Debit reconciliation
        BigDecimal expectedDebit = null;
        if (result.getPurchasesTotal() != null) {
            expectedDebit = nzAbs(expectedDebit).add(result.getPurchasesTotal().abs());
        }
        if (result.getFeesChargedTotal() != null) {
            expectedDebit = nzAbs(expectedDebit).add(result.getFeesChargedTotal().abs());
        }
        if (result.getInterestChargedTotal() != null) {
            expectedDebit = nzAbs(expectedDebit).add(result.getInterestChargedTotal().abs());
        }
        if (result.getCashAdvancesTotal() != null) {
            expectedDebit = nzAbs(expectedDebit).add(result.getCashAdvancesTotal().abs());
        }
        final BigDecimal parsedDebit = sumByDirection(result, FlowDirection.DEBIT);
        emitReconciliation(institution, accountType, "debit", expectedDebit, parsedDebit);

        // Credit reconciliation
        final BigDecimal expectedCredit = result.getPaymentsAndCreditsTotal() == null
                ? null
                : result.getPaymentsAndCreditsTotal().abs();
        final BigDecimal parsedCredit = sumByDirection(result, FlowDirection.CREDIT);
        emitReconciliation(institution, accountType, "credit", expectedCredit, parsedCredit);
    }

    private void emitReconciliation(
            final String institution,
            final String accountType,
            final String bucket,
            final BigDecimal expected,
            final BigDecimal actual) {
        final String status;
        if (expected == null) {
            status = "skipped";
        } else {
            final BigDecimal delta = actual.subtract(expected).abs();
            status = delta.compareTo(new BigDecimal("1.00")) <= 0 ? "pass" : "fail";
        }
        Counter.builder("pdf_import_reconciliation_total")
                .tags("institution", institution,
                        "account_type", accountType,
                        "bucket", bucket,
                        "status", status)
                .register(registry)
                .increment();
    }

    private static BigDecimal sumByDirection(
            final ImportResult result, final FlowDirection direction) {
        BigDecimal sum = BigDecimal.ZERO;
        for (final ParsedTransaction t : result.getTransactions()) {
            if (t.getFlowDirection() == direction
                    || (t.getFlowDirection() == null && direction == FlowDirection.DEBIT)) {
                sum = sum.add(t.getAmount() == null ? BigDecimal.ZERO : t.getAmount().abs());
            }
        }
        return sum;
    }

    private static BigDecimal nzAbs(final BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String institutionTag(final ImportResult result) {
        if (result == null) return "unknown";
        final DetectedAccount a = result.getDetectedAccount();
        if (a == null || a.getInstitutionName() == null) return "unknown";
        return slug(a.getInstitutionName());
    }

    private static String accountTypeTag(final ImportResult result) {
        if (result == null) return "unknown";
        final DetectedAccount a = result.getDetectedAccount();
        if (a == null || a.getAccountType() == null) return "unknown";
        return slug(a.getAccountType());
    }

    /**
     * Tag-safe slug. Limits cardinality (high-cardinality tags blow up
     * Prometheus storage) and normalises casing across issuer detection
     * variants ("Chase" vs "chase" vs "Chase Bank").
     */
    private static String slug(final String raw) {
        if (raw == null) return "unknown";
        final String s = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "unknown" : (s.length() > 40 ? s.substring(0, 40) : s);
    }
}
