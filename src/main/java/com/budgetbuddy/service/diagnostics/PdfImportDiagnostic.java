package com.budgetbuddy.service.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Redacted snapshot of a failing PDF import. Built when reconciliation fails
 * (parsed sums don't match the issuer's printed section totals), or when the
 * user explicitly flags an import as incomplete. The blob is intentionally
 * structured for LLM consumption: balanced between context (raw text excerpt,
 * parsed rows, declared totals) and signal (specific deltas, hashes for
 * dedup).
 *
 * <p>All PII is removed by the time a blob lands here — see
 * {@link PdfImportDiagnosticRedactor}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "JSON DTO; getters expose lists by reference for Jackson")
public class PdfImportDiagnostic {

    @JsonProperty("schema_version")
    private String schemaVersion = "1";

    @JsonProperty("captured_at")
    private Instant capturedAt;

    @JsonProperty("parser_version")
    private String parserVersion;

    @JsonProperty("pdf_hash")
    private String pdfHash;

    @JsonProperty("page_count")
    private Integer pageCount;

    @JsonProperty("detected_account")
    private DetectedAccount detectedAccount;

    @JsonProperty("statement_period")
    private StatementPeriod statementPeriod;

    @JsonProperty("declared_totals")
    private DeclaredTotals declaredTotals;

    @JsonProperty("parsed_totals")
    private ParsedTotals parsedTotals;

    @JsonProperty("reconciliation")
    private List<ReconciliationDelta> reconciliation = new ArrayList<>();

    @JsonProperty("pdf_text_excerpt")
    private String pdfTextExcerpt;

    @JsonProperty("pdf_text_excerpt_length")
    private Integer pdfTextExcerptLength;

    @JsonProperty("parsed_rows")
    private List<ParsedRowSummary> parsedRows = new ArrayList<>();

    @JsonProperty("parse_errors")
    private List<String> parseErrors = new ArrayList<>();

    @JsonProperty("info_messages")
    private List<String> infoMessages = new ArrayList<>();

    @JsonProperty("redaction_applied")
    private List<String> redactionApplied = new ArrayList<>();

    public static class DetectedAccount {
        @JsonProperty("institution") public String institution;
        @JsonProperty("account_type") public String accountType;
        @JsonProperty("last_four_masked") public String lastFourMasked;
        @JsonProperty("brand") public String brand;
    }

    public static class StatementPeriod {
        @JsonProperty("start") public LocalDate start;
        @JsonProperty("end") public LocalDate end;
        @JsonProperty("statement_date") public LocalDate statementDate;
    }

    public static class DeclaredTotals {
        @JsonProperty("previous_balance") public BigDecimal previousBalance;
        @JsonProperty("new_balance") public BigDecimal newBalance;
        @JsonProperty("purchases") public BigDecimal purchases;
        @JsonProperty("payments_and_credits") public BigDecimal paymentsAndCredits;
        @JsonProperty("fees") public BigDecimal fees;
        @JsonProperty("interest") public BigDecimal interest;
        @JsonProperty("cash_advances") public BigDecimal cashAdvances;
        @JsonProperty("balance_transfers") public BigDecimal balanceTransfers;
    }

    public static class ParsedTotals {
        @JsonProperty("transaction_count") public int transactionCount;
        @JsonProperty("debit_sum") public BigDecimal debitSum;
        @JsonProperty("credit_sum") public BigDecimal creditSum;
    }

    public static class ReconciliationDelta {
        @JsonProperty("bucket") public String bucket;      // "debit" | "credit"
        @JsonProperty("expected") public BigDecimal expected;
        @JsonProperty("actual") public BigDecimal actual;
        @JsonProperty("delta") public BigDecimal delta;
        @JsonProperty("severity") public String severity;  // "warn" | "fail"
    }

    public static class ParsedRowSummary {
        @JsonProperty("date") public LocalDate date;
        @JsonProperty("amount") public BigDecimal amount;
        @JsonProperty("direction") public String direction;   // "debit" | "credit"
        @JsonProperty("description_redacted") public String descriptionRedacted;
        @JsonProperty("merchant_redacted") public String merchantRedacted;
        @JsonProperty("card_last_four_masked") public String cardLastFourMasked;
    }

    // -- getters/setters --

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(final String v) { this.schemaVersion = v; }

    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(final Instant v) { this.capturedAt = v; }

    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(final String v) { this.parserVersion = v; }

    public String getPdfHash() { return pdfHash; }
    public void setPdfHash(final String v) { this.pdfHash = v; }

    public Integer getPageCount() { return pageCount; }
    public void setPageCount(final Integer v) { this.pageCount = v; }

    public DetectedAccount getDetectedAccount() { return detectedAccount; }
    public void setDetectedAccount(final DetectedAccount v) { this.detectedAccount = v; }

    public StatementPeriod getStatementPeriod() { return statementPeriod; }
    public void setStatementPeriod(final StatementPeriod v) { this.statementPeriod = v; }

    public DeclaredTotals getDeclaredTotals() { return declaredTotals; }
    public void setDeclaredTotals(final DeclaredTotals v) { this.declaredTotals = v; }

    public ParsedTotals getParsedTotals() { return parsedTotals; }
    public void setParsedTotals(final ParsedTotals v) { this.parsedTotals = v; }

    public List<ReconciliationDelta> getReconciliation() { return reconciliation; }
    public void setReconciliation(final List<ReconciliationDelta> v) {
        this.reconciliation = v == null ? new ArrayList<>() : v;
    }

    public String getPdfTextExcerpt() { return pdfTextExcerpt; }
    public void setPdfTextExcerpt(final String v) { this.pdfTextExcerpt = v; }

    public Integer getPdfTextExcerptLength() { return pdfTextExcerptLength; }
    public void setPdfTextExcerptLength(final Integer v) { this.pdfTextExcerptLength = v; }

    public List<ParsedRowSummary> getParsedRows() { return parsedRows; }
    public void setParsedRows(final List<ParsedRowSummary> v) {
        this.parsedRows = v == null ? new ArrayList<>() : v;
    }

    public List<String> getParseErrors() { return parseErrors; }
    public void setParseErrors(final List<String> v) {
        this.parseErrors = v == null ? new ArrayList<>() : v;
    }

    public List<String> getInfoMessages() { return infoMessages; }
    public void setInfoMessages(final List<String> v) {
        this.infoMessages = v == null ? new ArrayList<>() : v;
    }

    public List<String> getRedactionApplied() { return redactionApplied; }
    public void setRedactionApplied(final List<String> v) {
        this.redactionApplied = v == null ? new ArrayList<>() : v;
    }
}
