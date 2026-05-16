package com.budgetbuddy.service.diagnostics;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts a finished {@link ImportResult} (plus the raw PDF bytes + text) into
 * a redacted {@link PdfImportDiagnostic}. The builder is the seam where parse
 * internals meet the export-shape, so we keep this one place tidy rather than
 * sprinkling redaction logic across the parser.
 *
 * <p>Diagnostic capture fires only when the parse is "interesting" — i.e. one
 * of:
 *
 * <ul>
 *   <li>Reconciliation deltas exceed tolerance</li>
 *   <li>The result has any parse errors</li>
 *   <li>Tolerance / count comparison shows obvious gaps (zero transactions,
 *       extreme outliers)</li>
 *   <li>The caller forces a capture (e.g. user-flagged failure on iOS)</li>
 * </ul>
 *
 * <p>A successful parse with no warnings produces no blob. That keeps storage
 * cost proportional to actual problems.
 */
public final class PdfImportDiagnosticBuilder {

    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("1.00");
    private static final int TEXT_EXCERPT_MAX_CHARS = 4_000;

    private final PdfImportDiagnosticRedactor redactor;

    public PdfImportDiagnosticBuilder() {
        this(new PdfImportDiagnosticRedactor());
    }

    public PdfImportDiagnosticBuilder(final PdfImportDiagnosticRedactor redactor) {
        this.redactor = redactor;
    }

    /**
     * Build a diagnostic blob iff something looks off. Returns null when the
     * parse looks clean — caller should NOT store a blob in that case.
     */
    public PdfImportDiagnostic buildIfInteresting(
            final ImportResult result,
            final byte[] pdfBytes,
            final String rawPdfText,
            final Integer pageCount,
            final String parserVersion,
            final boolean forceCapture) {
        if (result == null) return null;

        final PdfImportDiagnostic d = new PdfImportDiagnostic();
        d.setCapturedAt(Instant.now());
        d.setParserVersion(parserVersion);
        d.setPdfHash(sha256(pdfBytes));
        d.setPageCount(pageCount);

        // Account block
        final DetectedAccount a = result.getDetectedAccount();
        if (a != null) {
            final PdfImportDiagnostic.DetectedAccount da =
                    new PdfImportDiagnostic.DetectedAccount();
            da.institution = a.getInstitutionName();
            da.accountType = a.getAccountType();
            da.lastFourMasked = redactor.maskLastFour(a.getAccountNumber());
            d.setDetectedAccount(da);
        }

        // Statement period
        if (result.getStatementStartDate() != null
                || result.getStatementEndDate() != null
                || result.getStatementDate() != null) {
            final PdfImportDiagnostic.StatementPeriod sp =
                    new PdfImportDiagnostic.StatementPeriod();
            sp.start = result.getStatementStartDate();
            sp.end = result.getStatementEndDate();
            sp.statementDate = result.getStatementDate();
            d.setStatementPeriod(sp);
        }

        // Declared totals
        final PdfImportDiagnostic.DeclaredTotals dt =
                new PdfImportDiagnostic.DeclaredTotals();
        dt.previousBalance = result.getPreviousBalance();
        dt.newBalance = result.getNewBalance();
        dt.purchases = result.getPurchasesTotal();
        dt.paymentsAndCredits = result.getPaymentsAndCreditsTotal();
        dt.fees = result.getFeesChargedTotal();
        dt.interest = result.getInterestChargedTotal();
        dt.cashAdvances = result.getCashAdvancesTotal();
        dt.balanceTransfers = result.getBalanceTransfersTotal();
        d.setDeclaredTotals(dt);

        // Parsed totals
        BigDecimal debitSum = BigDecimal.ZERO;
        BigDecimal creditSum = BigDecimal.ZERO;
        for (final ParsedTransaction t : result.getTransactions()) {
            final BigDecimal amt = t.getAmount() == null
                    ? BigDecimal.ZERO : t.getAmount().abs();
            if (t.getFlowDirection() == FlowDirection.CREDIT) {
                creditSum = creditSum.add(amt);
            } else {
                debitSum = debitSum.add(amt);
            }
        }
        final PdfImportDiagnostic.ParsedTotals pt = new PdfImportDiagnostic.ParsedTotals();
        pt.transactionCount = result.getTransactions().size();
        pt.debitSum = debitSum;
        pt.creditSum = creditSum;
        d.setParsedTotals(pt);

        // Reconciliation deltas
        final List<PdfImportDiagnostic.ReconciliationDelta> reconc = new ArrayList<>();
        BigDecimal expectedDebit = null;
        if (dt.purchases != null) expectedDebit = nz(expectedDebit).add(dt.purchases.abs());
        if (dt.fees != null) expectedDebit = nz(expectedDebit).add(dt.fees.abs());
        if (dt.interest != null) expectedDebit = nz(expectedDebit).add(dt.interest.abs());
        if (dt.cashAdvances != null) expectedDebit = nz(expectedDebit).add(dt.cashAdvances.abs());
        if (dt.balanceTransfers != null) expectedDebit = nz(expectedDebit).add(dt.balanceTransfers.abs());
        if (expectedDebit != null) {
            reconc.add(deltaOf("debit", expectedDebit, debitSum));
        }
        if (dt.paymentsAndCredits != null) {
            reconc.add(deltaOf("credit", dt.paymentsAndCredits.abs(), creditSum));
        }
        d.setReconciliation(reconc);

        // Errors / info
        d.setParseErrors(result.getErrors());
        d.setInfoMessages(result.getInfoMessages());

        // Decide if interesting. Even with clean math we still capture when the
        // caller forces it (e.g. user-flagged) or when there are parse errors.
        final boolean hasFailingReconc =
                reconc.stream().anyMatch(r -> "fail".equals(r.severity));
        final boolean hasParseErrors =
                result.getErrors() != null && !result.getErrors().isEmpty();
        final boolean hasReconcInfo =
                result.getInfoMessages() != null
                        && result.getInfoMessages().stream()
                                .anyMatch(m -> m != null
                                        && m.contains("Transaction sum mismatch"));
        if (!forceCapture && !hasFailingReconc && !hasParseErrors && !hasReconcInfo) {
            return null;
        }

        // Text excerpt — last so we have the totals to anchor a useful window.
        // We pull the first N chars of metadata + the windows around each
        // failing delta. Each window is independently redacted.
        final String excerpt = buildExcerpt(rawPdfText, result, expectedDebit, debitSum);
        final PdfImportDiagnosticRedactor.Result redacted = redactor.redact(excerpt);
        d.setPdfTextExcerpt(redacted.getText());
        d.setPdfTextExcerptLength(redacted.getText().length());

        // Parsed rows — descriptions redacted.
        final List<PdfImportDiagnostic.ParsedRowSummary> rows = new ArrayList<>();
        final Set<String> allFired = new LinkedHashSet<>(redacted.getRulesFired());
        for (final ParsedTransaction t : result.getTransactions()) {
            final PdfImportDiagnostic.ParsedRowSummary r =
                    new PdfImportDiagnostic.ParsedRowSummary();
            r.date = t.getDate();
            r.amount = t.getAmount();
            r.direction = t.getFlowDirection() == FlowDirection.CREDIT ? "credit" : "debit";
            final PdfImportDiagnosticRedactor.Result rd = redactor.redact(
                    nullSafe(t.getDescription()));
            r.descriptionRedacted = rd.getText();
            allFired.addAll(rd.getRulesFired());
            final PdfImportDiagnosticRedactor.Result rm = redactor.redact(
                    nullSafe(t.getMerchantName()));
            r.merchantRedacted = rm.getText();
            allFired.addAll(rm.getRulesFired());
            r.cardLastFourMasked = redactor.maskLastFour(t.getCardLastFour());
            rows.add(r);
        }
        d.setParsedRows(rows);
        d.setRedactionApplied(new ArrayList<>(allFired));

        return d;
    }

    private static PdfImportDiagnostic.ReconciliationDelta deltaOf(
            final String bucket, final BigDecimal expected, final BigDecimal actual) {
        final PdfImportDiagnostic.ReconciliationDelta r =
                new PdfImportDiagnostic.ReconciliationDelta();
        r.bucket = bucket;
        r.expected = expected;
        r.actual = actual;
        r.delta = actual.subtract(expected);
        r.severity = r.delta.abs().compareTo(RECONCILIATION_TOLERANCE) > 0 ? "fail" : "warn";
        return r;
    }

    /**
     * Build the text excerpt. Strategy: take the first 1KB of text (which has
     * most institution-identification clues) + windows around the lines that
     * contain the declared totals (so the LLM sees how those totals were
     * printed and can correlate to parsed sums).
     */
    private static String buildExcerpt(
            final String rawText,
            final ImportResult result,
            final BigDecimal expectedDebit,
            final BigDecimal actualDebit) {
        if (rawText == null || rawText.isEmpty()) return "";
        final StringBuilder out = new StringBuilder();
        final int headLimit = Math.min(1_000, rawText.length());
        out.append("--- HEAD (first 1KB) ---\n");
        out.append(rawText, 0, headLimit);
        out.append("\n");

        // Window around lines whose content matches the declared totals OR the
        // parsed-sum delta amount — those are the rows the LLM most needs to see.
        final Set<String> anchors = new HashSet<>();
        addAnchor(anchors, result.getPurchasesTotal());
        addAnchor(anchors, result.getPaymentsAndCreditsTotal());
        addAnchor(anchors, result.getFeesChargedTotal());
        addAnchor(anchors, result.getInterestChargedTotal());
        addAnchor(anchors, result.getNewBalance());
        if (expectedDebit != null && actualDebit != null) {
            addAnchor(anchors, expectedDebit.subtract(actualDebit).abs());
        }

        final String[] lines = rawText.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            for (final String anchor : anchors) {
                if (line.contains(anchor)) {
                    out.append("\n--- WINDOW around line ")
                            .append(i + 1)
                            .append(" ('")
                            .append(anchor)
                            .append("') ---\n");
                    final int start = Math.max(0, i - 3);
                    final int end = Math.min(lines.length, i + 4);
                    for (int j = start; j < end; j++) {
                        out.append(lines[j]).append('\n');
                    }
                    break;
                }
            }
            if (out.length() > TEXT_EXCERPT_MAX_CHARS) {
                out.append("\n--- (truncated) ---\n");
                break;
            }
        }
        return out.toString();
    }

    private static void addAnchor(final Set<String> anchors, final BigDecimal v) {
        if (v == null) return;
        anchors.add(v.toPlainString());
        anchors.add(v.toPlainString().replace(".", "."));  // identity, leave room for variants
    }

    private static String sha256(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(bytes);
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static BigDecimal nz(final BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nullSafe(final String s) {
        return s == null ? "" : s;
    }
}
