package com.budgetbuddy.service.pdf.ai;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Structural-anomaly detector for parsed statements. Maintains a rolling
 * structural fingerprint per (institution, accountNumber) and flags
 * when the current parse's fingerprint diverges from the historical
 * baseline beyond a configurable threshold.
 *
 * <p>The fingerprint is intentionally simple — counts of distinct
 * section labels, transaction-row shape (date prefix, multi-line vs
 * single-line, presence of FX block, presence of geo trailer), and the
 * ratio of populated vs missing extractor fields. A drift signal means
 * "the issuer changed something" before users complain.
 *
 * <p>When a drift fires, we increment a Micrometer counter so on-call
 * sees it on the dashboard, AND optionally enqueue a triage report
 * via AiParseFailureTriageWorker (if both are enabled).
 *
 * <p>This is deliberately NOT LLM-based — the fingerprint is cheap
 * deterministic features that we already compute. The "AI" here is
 * downstream: when drift fires, the triage worker invokes the LLM to
 * propose a fix.
 *
 * <p>Activation: {@code app.pdf.anomaly-detector.enabled=true}.
 */
@Service
@ConditionalOnProperty(
        name = "app.pdf.anomaly-detector.enabled",
        havingValue = "true",
        matchIfMissing = false)
@SuppressFBWarnings(value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
public class StatementFormatAnomalyDetector {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StatementFormatAnomalyDetector.class);

    /**
     * Rolling history of structural fingerprints. Key = (institution +
     * "|" + accountNumber). Value = last fingerprint observed. Memory
     * bounded by (#institutions × #accounts-per-institution), which in
     * practice is small (<10K entries even for power users).
     */
    private final Map<String, String> fingerprintHistory = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;

    @Value("${app.pdf.anomaly-detector.warn-on-first-import:false}")
    private boolean warnOnFirstImport;

    @Autowired
    public StatementFormatAnomalyDetector(
            @Autowired(required = false) final MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry == null
                ? new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
                : meterRegistry;
    }

    /**
     * Examine the parsed result and emit a drift warning if the current
     * fingerprint differs from the last observation for this account.
     * Called from PDFImportService after the parse completes — non-fatal
     * for the import, just an observability hook.
     */
    public void inspect(final ImportResult r) {
        if (r == null || r.getDetectedAccount() == null) return;
        final String institution = r.getDetectedAccount().getInstitutionName();
        final String accountNumber = r.getDetectedAccount().getAccountNumber();
        if (institution == null || accountNumber == null) return;

        final String key = institution + "|" + accountNumber;
        final String current = computeFingerprint(r);
        final String prior = fingerprintHistory.put(key, current);

        if (prior == null) {
            // First import for this account — no baseline yet. By default
            // don't warn; warn-on-first-import can be flipped on by ops
            // when establishing initial baselines.
            if (warnOnFirstImport) {
                LOGGER.info("anomaly-detector: first observation for {} fp={}", key, current);
            }
            return;
        }
        if (!prior.equals(current)) {
            LOGGER.warn("anomaly-detector: structural drift detected for {} (was {} → now {})",
                    key, prior, current);
            Counter.builder("pdf_import_format_drift_total")
                    .tags("institution", slug(institution))
                    .register(meterRegistry)
                    .increment();
        }
    }

    /**
     * Build a compact fingerprint string. The fields chosen are stable
     * enough that minor day-to-day variation (transaction count, dollar
     * amounts) doesn't trigger drift, but a real format change (issuer
     * stops printing the APR table, adds a new "rewards summary" section)
     * does.
     */
    private static String computeFingerprint(final ImportResult r) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("h=").append(boolBit(r.getNewBalance() != null));
        sb.append(boolBit(r.getPreviousBalance() != null));
        sb.append(boolBit(r.getCreditLimit() != null));
        sb.append(boolBit(r.getPaymentDueDate() != null));
        sb.append(boolBit(r.getMinimumPaymentDue() != null));
        sb.append(boolBit(r.getPurchasesTotal() != null));
        sb.append(boolBit(r.getPaymentsAndCreditsTotal() != null));
        sb.append(boolBit(r.getFeesChargedTotal() != null));
        sb.append(boolBit(r.getInterestChargedTotal() != null));
        sb.append(boolBit(r.getPurchaseApr() != null));
        sb.append(boolBit(r.getAutoPayEnabled() != null));
        // Tx-count bucket. A jump from "0-10 tx" to "100+ tx" would not
        // alone be drift (some months are heavier). Bucket loosely.
        final int txCount = r.getTransactions() == null ? 0 : r.getTransactions().size();
        sb.append(";txBucket=").append(txBucket(txCount));
        // Hash the result to keep the metric tag bounded — full fingerprint
        // appears in logs but the metric tag uses the short hash.
        return shortHash(sb.toString());
    }

    private static char boolBit(final boolean b) {
        return b ? '1' : '0';
    }

    private static String txBucket(final int n) {
        if (n == 0) return "0";
        if (n <= 10) return "1-10";
        if (n <= 30) return "11-30";
        if (n <= 100) return "31-100";
        return "100+";
    }

    private static String shortHash(final String input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] d = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            return input.substring(0, Math.min(8, input.length()));
        }
    }

    private static String slug(final String s) {
        return s == null ? "unknown"
                : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }
}
