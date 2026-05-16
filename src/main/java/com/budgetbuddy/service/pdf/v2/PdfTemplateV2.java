package com.budgetbuddy.service.pdf.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;

/**
 * YAML schema v2 — full extraction rules in YAML, not code.
 *
 * <h3>Goal</h3>
 * A non-programmer should be able to add a new bank by writing ONE YAML file
 * that covers:
 *
 * <ul>
 *   <li>Card detection (institution, last-4, account holder)</li>
 *   <li>Statement metadata (period, totals, balances)</li>
 *   <li>Transaction line layouts (existing v1 capability)</li>
 *   <li>Preprocessing hints (FX strip strategies, section-header markers)</li>
 * </ul>
 *
 * <p>v1 templates remain supported via {@link com.budgetbuddy.service.pdf.PdfTemplate}.
 * v2 superset adds the card-detection and metadata-rule sections. The registry
 * loads both and the v2 evaluator runs first; v1 acts as a fallback for the
 * single-line transaction shape.
 *
 * <h3>Migration order</h3>
 * Each issuer migrates one at a time, gated by the audit harness. See
 * {@code docs/pdf-import-architecture.md} for the full rollout plan.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "JSON DTO; getters expose lists by reference for Jackson")
@SuppressWarnings({"PMD.DataClass", "PMD.OnlyOneReturn"})
public class PdfTemplateV2 {

    private String id;
    private String institution;
    private String description;
    private String status; // "unverified" | "validated" | "production"
    @JsonProperty("card_detection") private CardDetection cardDetection;
    private MetadataRules metadata;
    private List<com.budgetbuddy.service.pdf.PdfTemplate.Layout> layouts =
            Collections.emptyList();
    private Preprocessing preprocessing;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardDetection {
        @JsonProperty("institution_match")
        private List<RegexRule> institutionMatch = Collections.emptyList();
        @JsonProperty("last_four")
        private List<RegexRule> lastFour = Collections.emptyList();
        @JsonProperty("account_holder")
        private List<HolderRule> accountHolder = Collections.emptyList();

        public List<RegexRule> getInstitutionMatch() { return institutionMatch; }
        public void setInstitutionMatch(final List<RegexRule> v) {
            this.institutionMatch = v == null ? Collections.emptyList() : v;
        }

        public List<RegexRule> getLastFour() { return lastFour; }
        public void setLastFour(final List<RegexRule> v) {
            this.lastFour = v == null ? Collections.emptyList() : v;
        }

        public List<HolderRule> getAccountHolder() { return accountHolder; }
        public void setAccountHolder(final List<HolderRule> v) {
            this.accountHolder = v == null ? Collections.emptyList() : v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HolderRule {
        private String source;  // "header" | "above_account_ending" | "above_card_ending"
        private String pattern; // regex, optional
        @JsonProperty("lines_above_account") private String linesAboveAccount; // e.g. "1-3"

        public String getSource() { return source; }
        public void setSource(final String v) { this.source = v; }
        public String getPattern() { return pattern; }
        public void setPattern(final String v) { this.pattern = v; }
        public String getLinesAboveAccount() { return linesAboveAccount; }
        public void setLinesAboveAccount(final String v) { this.linesAboveAccount = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RegexRule {
        private String pattern;
        @JsonProperty("any_of") private List<String> anyOf = Collections.emptyList();
        @JsonProperty("filename_fallback") private Boolean filenameFallback;

        public String getPattern() { return pattern; }
        public void setPattern(final String v) { this.pattern = v; }
        public List<String> getAnyOf() { return anyOf; }
        public void setAnyOf(final List<String> v) {
            this.anyOf = v == null ? Collections.emptyList() : v;
        }
        public Boolean getFilenameFallback() { return filenameFallback; }
        public void setFilenameFallback(final Boolean v) { this.filenameFallback = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MetadataRules {
        @JsonProperty("statement_date") private List<LabelRule> statementDate = Collections.emptyList();
        @JsonProperty("statement_period") private List<PeriodRule> statementPeriod = Collections.emptyList();
        @JsonProperty("new_balance") private List<LabelRule> newBalance = Collections.emptyList();
        @JsonProperty("previous_balance") private List<LabelRule> previousBalance = Collections.emptyList();
        @JsonProperty("purchases_total") private List<LabelRule> purchasesTotal = Collections.emptyList();
        @JsonProperty("payments_total") private List<LabelRule> paymentsTotal = Collections.emptyList();
        @JsonProperty("fees_total") private List<LabelRule> feesTotal = Collections.emptyList();
        @JsonProperty("interest_total") private List<LabelRule> interestTotal = Collections.emptyList();

        public List<LabelRule> getStatementDate() { return statementDate; }
        public void setStatementDate(final List<LabelRule> v) {
            this.statementDate = v == null ? Collections.emptyList() : v;
        }
        public List<PeriodRule> getStatementPeriod() { return statementPeriod; }
        public void setStatementPeriod(final List<PeriodRule> v) {
            this.statementPeriod = v == null ? Collections.emptyList() : v;
        }
        public List<LabelRule> getNewBalance() { return newBalance; }
        public void setNewBalance(final List<LabelRule> v) {
            this.newBalance = v == null ? Collections.emptyList() : v;
        }
        public List<LabelRule> getPreviousBalance() { return previousBalance; }
        public void setPreviousBalance(final List<LabelRule> v) {
            this.previousBalance = v == null ? Collections.emptyList() : v;
        }
        public List<LabelRule> getPurchasesTotal() { return purchasesTotal; }
        public void setPurchasesTotal(final List<LabelRule> v) {
            this.purchasesTotal = v == null ? Collections.emptyList() : v;
        }
        public List<LabelRule> getPaymentsTotal() { return paymentsTotal; }
        public void setPaymentsTotal(final List<LabelRule> v) {
            this.paymentsTotal = v == null ? Collections.emptyList() : v;
        }
        public List<LabelRule> getFeesTotal() { return feesTotal; }
        public void setFeesTotal(final List<LabelRule> v) {
            this.feesTotal = v == null ? Collections.emptyList() : v;
        }
        public List<LabelRule> getInterestTotal() { return interestTotal; }
        public void setInterestTotal(final List<LabelRule> v) {
            this.interestTotal = v == null ? Collections.emptyList() : v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LabelRule {
        private String label;     // e.g. "New Balance"
        private String adjacent;  // "dollar" | "amount" | "date"
        private String pattern;   // alternative: explicit regex
        // Stacked label-then-value layout (Amex):
        //   Account Summary
        //   Previous Balance
        //   Payments/Credits
        //   New Charges
        //   Fees
        //   Interest Charged
        //   $26,145.56
        //   -$1,424.36
        //   +$1,461.05
        //   ...
        // When present, the evaluator scans for the header line, then expects
        // the labels in order, then reads the next N $-values, and returns the
        // value at index `stackedIndex`.
        @JsonProperty("stacked_header") private String stackedHeader;
        @JsonProperty("stacked_labels") private List<String> stackedLabels = Collections.emptyList();
        @JsonProperty("stacked_index") private Integer stackedIndex;

        public String getLabel() { return label; }
        public void setLabel(final String v) { this.label = v; }
        public String getAdjacent() { return adjacent; }
        public void setAdjacent(final String v) { this.adjacent = v; }
        public String getPattern() { return pattern; }
        public void setPattern(final String v) { this.pattern = v; }
        public String getStackedHeader() { return stackedHeader; }
        public void setStackedHeader(final String v) { this.stackedHeader = v; }
        public List<String> getStackedLabels() { return stackedLabels; }
        public void setStackedLabels(final List<String> v) {
            this.stackedLabels = v == null ? Collections.emptyList() : v;
        }
        public Integer getStackedIndex() { return stackedIndex; }
        public void setStackedIndex(final Integer v) { this.stackedIndex = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PeriodRule {
        private String pattern;
        public String getPattern() { return pattern; }
        public void setPattern(final String v) { this.pattern = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Preprocessing {
        @JsonProperty("fx_block_strip")
        private List<FxStripStrategy> fxBlockStrip = Collections.emptyList();
        private Stitch stitch;

        public List<FxStripStrategy> getFxBlockStrip() { return fxBlockStrip; }
        public void setFxBlockStrip(final List<FxStripStrategy> v) {
            this.fxBlockStrip = v == null ? Collections.emptyList() : v;
        }
        public Stitch getStitch() { return stitch; }
        public void setStitch(final Stitch v) { this.stitch = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FxStripStrategy {
        // Refers to a built-in strategy by name:
        // "chase-exchg-rate", "chase-fee-parentref", "amex-three-line"
        private String kind;
        public String getKind() { return kind; }
        public void setKind(final String v) { this.kind = v; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stitch {
        @JsonProperty("end_of_transaction_marker")
        private String endOfTransactionMarker;
        @JsonProperty("section_headers")
        private List<String> sectionHeaders = Collections.emptyList();

        public String getEndOfTransactionMarker() { return endOfTransactionMarker; }
        public void setEndOfTransactionMarker(final String v) {
            this.endOfTransactionMarker = v;
        }
        public List<String> getSectionHeaders() { return sectionHeaders; }
        public void setSectionHeaders(final List<String> v) {
            this.sectionHeaders = v == null ? Collections.emptyList() : v;
        }
    }

    // ---- top-level getters/setters ----

    public String getId() { return id; }
    public void setId(final String v) { this.id = v; }
    public String getInstitution() { return institution; }
    public void setInstitution(final String v) { this.institution = v; }
    public String getDescription() { return description; }
    public void setDescription(final String v) { this.description = v; }
    public String getStatus() { return status; }
    public void setStatus(final String v) { this.status = v; }
    public CardDetection getCardDetection() { return cardDetection; }
    public void setCardDetection(final CardDetection v) { this.cardDetection = v; }
    public MetadataRules getMetadata() { return metadata; }
    public void setMetadata(final MetadataRules v) { this.metadata = v; }
    public List<com.budgetbuddy.service.pdf.PdfTemplate.Layout> getLayouts() {
        return layouts;
    }
    public void setLayouts(
            final List<com.budgetbuddy.service.pdf.PdfTemplate.Layout> v) {
        this.layouts = v == null ? Collections.emptyList() : v;
    }
    public Preprocessing getPreprocessing() { return preprocessing; }
    public void setPreprocessing(final Preprocessing v) { this.preprocessing = v; }

    public boolean isV2() {
        return cardDetection != null || metadata != null || preprocessing != null;
    }
}
