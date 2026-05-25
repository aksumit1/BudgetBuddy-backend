package com.budgetbuddy.service.diagnostics;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
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
 *   <li>{@code pdf_import_v2_cutover_total} (counter, path=v2|legacy|shadow):
 *       which extraction path actually fired for this parse.</li>
 *   <li>{@code pdf_import_year_rollover_corrections_total} (counter): how many
 *       transactions had their year shifted by the rollover-correction pass.</li>
 *   <li>{@code pdf_import_field_extraction_total} (counter, field=..., status=
 *       extracted|missing): per-field hit/miss so we can spot a single header
 *       field silently regressing across an issuer.</li>
 *   <li>{@code pdf_import_geo_enriched_total} (counter, component=
 *       city|state|country|postal|phone|address|none): % of v2 tx with each
 *       structured geo component populated.</li>
 *   <li>{@code pdf_import_fx_annotated_total} (counter): tx with FX context.</li>
 *   <li>{@code pdf_import_family_card_matched_total} (counter): tx where a
 *       family-card userName / cardLastFour was assigned by YAML rules.</li>
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
            recordFieldExtraction(result, institution, accountType);
            recordPerTxEnrichment(result, institution, accountType);
        }
    }

    /**
     * Increment the v2 cutover counter — tells us which extraction path
     * (v2 / legacy / shadow) actually drove the row builds for this parse.
     * The PDFImportService cutover logic decides per-file based on the
     * V2_TX_PRODUCTION_ISSUERS allow-list; this counter lets us watch the
     * rollout progress on a per-issuer dashboard.
     */
    public void recordV2CutoverPath(final String institution, final String path) {
        Counter.builder("pdf_import_v2_cutover_total")
                .tags("institution", slug(institution), "path", slug(path))
                .register(registry)
                .increment();
    }

    /**
     * Count of transactions whose date was shifted forward/backward a year by
     * the year-rollover corrector. A sudden spike here means the corrector
     * is over-firing (likely a statement-period extraction regression).
     */
    public void recordYearRolloverCorrections(final String institution, final int count) {
        if (count <= 0) return;
        Counter.builder("pdf_import_year_rollover_corrections_total")
                .tags("institution", slug(institution))
                .register(registry)
                .increment(count);
    }

    /** Per-parse field-extraction outcome for the header summary fields. */
    private void recordFieldExtraction(
            final ImportResult result, final String institution, final String accountType) {
        // Tracked fields, ordered by user-facing importance. Each row emits a
        // single {extracted, missing} datapoint per parse — never per tx, to
        // keep cardinality bounded.
        emitFieldStatus(institution, accountType, "statement_date", result.getStatementDate() != null);
        emitFieldStatus(institution, accountType, "new_balance", result.getNewBalance() != null);
        emitFieldStatus(institution, accountType, "previous_balance",
                result.getPreviousBalance() != null);
        emitFieldStatus(institution, accountType, "credit_limit",
                result.getCreditLimit() != null);
        emitFieldStatus(institution, accountType, "available_credit",
                result.getAvailableCredit() != null);
        emitFieldStatus(institution, accountType, "payment_due_date",
                result.getPaymentDueDate() != null);
        emitFieldStatus(institution, accountType, "min_payment",
                result.getMinimumPaymentDue() != null);
        emitFieldStatus(institution, accountType, "purchases_total",
                result.getPurchasesTotal() != null);
        emitFieldStatus(institution, accountType, "payments_credits_total",
                result.getPaymentsAndCreditsTotal() != null);
        emitFieldStatus(institution, accountType, "fees_total",
                result.getFeesChargedTotal() != null);
        emitFieldStatus(institution, accountType, "interest_total",
                result.getInterestChargedTotal() != null);
        emitFieldStatus(institution, accountType, "purchase_apr",
                result.getPurchaseApr() != null);
        emitFieldStatus(institution, accountType, "autopay_enabled",
                result.getAutoPayEnabled() != null);
    }

    private void emitFieldStatus(
            final String institution, final String accountType,
            final String field, final boolean extracted) {
        Counter.builder("pdf_import_field_extraction_total")
                .tags("institution", institution,
                        "account_type", accountType,
                        "field", field,
                        "status", extracted ? "extracted" : "missing")
                .register(registry)
                .increment();
    }

    /** Per-tx enrichment hit rates: geo, FX, family-card / wallet. */
    private void recordPerTxEnrichment(
            final ImportResult result, final String institution, final String accountType) {
        int geoCity = 0, geoState = 0, geoCountry = 0, geoPostal = 0, geoPhone = 0, geoAddr = 0;
        int geoNone = 0;
        int fxCount = 0;
        int walletCount = 0;
        int familyCardCount = 0;
        for (final ParsedTransaction t : result.getTransactions()) {
            boolean anyGeo = false;
            if (t.getCity() != null) { geoCity++; anyGeo = true; }
            if (t.getState() != null) { geoState++; anyGeo = true; }
            if (t.getCountry() != null) { geoCountry++; anyGeo = true; }
            if (t.getPostalCode() != null) { geoPostal++; anyGeo = true; }
            if (t.getPhoneNumber() != null) { geoPhone++; anyGeo = true; }
            if (t.getStreetAddress() != null) { geoAddr++; anyGeo = true; }
            if (!anyGeo) geoNone++;
            if (t.getOriginalCurrencyCode() != null) fxCount++;
            if (t.getWalletProvider() != null) walletCount++;
            if (t.getUserName() != null || t.getCardLastFour() != null) familyCardCount++;
        }
        emitGeoComponent(institution, accountType, "city", geoCity);
        emitGeoComponent(institution, accountType, "state", geoState);
        emitGeoComponent(institution, accountType, "country", geoCountry);
        emitGeoComponent(institution, accountType, "postal", geoPostal);
        emitGeoComponent(institution, accountType, "phone", geoPhone);
        emitGeoComponent(institution, accountType, "address", geoAddr);
        emitGeoComponent(institution, accountType, "none", geoNone);
        if (fxCount > 0) {
            Counter.builder("pdf_import_fx_annotated_total")
                    .tags("institution", institution, "account_type", accountType)
                    .register(registry)
                    .increment(fxCount);
        }
        if (walletCount > 0) {
            Counter.builder("pdf_import_wallet_detected_total")
                    .tags("institution", institution, "account_type", accountType)
                    .register(registry)
                    .increment(walletCount);
        }
        if (familyCardCount > 0) {
            Counter.builder("pdf_import_family_card_matched_total")
                    .tags("institution", institution, "account_type", accountType)
                    .register(registry)
                    .increment(familyCardCount);
        }
    }

    private void emitGeoComponent(
            final String institution, final String accountType,
            final String component, final int count) {
        if (count <= 0) return;
        Counter.builder("pdf_import_geo_enriched_total")
                .tags("institution", institution,
                        "account_type", accountType,
                        "component", component)
                .register(registry)
                .increment(count);
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
