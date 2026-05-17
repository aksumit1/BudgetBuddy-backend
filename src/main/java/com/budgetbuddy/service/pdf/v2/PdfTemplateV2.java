package com.budgetbuddy.service.pdf.v2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
    // Optional list of template IDs this template inherits from. The registry
    // resolves the chain at load time: parent rules are appended AFTER
    // child rules in each List<LabelRule>, so child rules win on first-match
    // but the parent acts as a fallback. Lets common rules (Payment Due
    // Date, Minimum Payment Due, FX fee, billing days) live in one shared
    // fragment instead of being duplicated across every issuer YAML.
    @JsonProperty("extends") private List<String> extendsList = Collections.emptyList();
    @JsonProperty("card_detection") private CardDetection cardDetection;
    private MetadataRules metadata;
    private List<com.budgetbuddy.service.pdf.PdfTemplate.Layout> layouts =
            Collections.emptyList();
    private Preprocessing preprocessing;
    // Transaction-shape rules. Each shape declares how to recognize one
    // transaction in the PDF text — either a single-line regex (the existing
    // `layouts:` case) or a multi-line pattern (Amex 3-line, FX-block-prefix,
    // etc.). The TransactionExtractor evaluates these in order; the first
    // shape that matches wins. Status: scaffolding — schema is wired and
    // tested standalone via TransactionExtractorTest; PDFImportService
    // integration is a follow-up because the legacy multi-line path is
    // currently load-bearing for corpus reconciliation.
    private List<TransactionShape> transactions = Collections.emptyList();
    // Inline self-test cases. Each sample carries a synthetic input + a map
    // of expected metadata fields. V2YamlSelfTest iterates every template and
    // asserts the evaluator returns those values — so adding a new issuer is a
    // single-file change that automatically gets regression coverage.
    private List<Sample> samples = Collections.emptyList();

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
        @JsonProperty("credit_limit") private List<LabelRule> creditLimit = Collections.emptyList();
        @JsonProperty("available_credit") private List<LabelRule> availableCredit = Collections.emptyList();
        @JsonProperty("minimum_payment_due") private List<LabelRule> minimumPaymentDue = Collections.emptyList();
        @JsonProperty("payment_due_date") private List<LabelRule> paymentDueDate = Collections.emptyList();
        @JsonProperty("purchases_total") private List<LabelRule> purchasesTotal = Collections.emptyList();
        @JsonProperty("payments_total") private List<LabelRule> paymentsTotal = Collections.emptyList();
        @JsonProperty("payments_total_sum") private boolean paymentsTotalSum;
        @JsonProperty("fees_total") private List<LabelRule> feesTotal = Collections.emptyList();
        @JsonProperty("interest_total") private List<LabelRule> interestTotal = Collections.emptyList();
        @JsonProperty("ytd_fees") private List<LabelRule> ytdFees = Collections.emptyList();
        @JsonProperty("ytd_interest") private List<LabelRule> ytdInterest = Collections.emptyList();
        @JsonProperty("purchase_apr") private List<LabelRule> purchaseApr = Collections.emptyList();
        @JsonProperty("cash_advance_apr") private List<LabelRule> cashAdvanceApr = Collections.emptyList();
        @JsonProperty("balance_transfer_apr") private List<LabelRule> balanceTransferApr = Collections.emptyList();
        @JsonProperty("penalty_apr") private List<LabelRule> penaltyApr = Collections.emptyList();
        @JsonProperty("points_balance") private List<LabelRule> pointsBalance = Collections.emptyList();
        @JsonProperty("points_earned") private List<LabelRule> pointsEarned = Collections.emptyList();
        @JsonProperty("previous_points_balance") private List<LabelRule> previousPointsBalance = Collections.emptyList();
        @JsonProperty("cashback_balance") private List<LabelRule> cashbackBalance = Collections.emptyList();
        @JsonProperty("autopay_enabled") private List<LabelRule> autopayEnabled = Collections.emptyList();
        @JsonProperty("next_autopay_amount") private List<LabelRule> nextAutopayAmount = Collections.emptyList();
        @JsonProperty("annual_fee") private List<LabelRule> annualFee = Collections.emptyList();
        @JsonProperty("annual_fee_due_date") private List<LabelRule> annualFeeDueDate = Collections.emptyList();
        @JsonProperty("foreign_tx_fee_percent") private List<LabelRule> foreignTxFeePercent = Collections.emptyList();
        @JsonProperty("billing_days") private List<LabelRule> billingDays = Collections.emptyList();

        public boolean isPaymentsTotalSum() { return paymentsTotalSum; }
        public void setPaymentsTotalSum(final boolean v) { this.paymentsTotalSum = v; }
        public List<LabelRule> getCreditLimit() { return creditLimit; }
        public void setCreditLimit(final List<LabelRule> v) { this.creditLimit = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getAvailableCredit() { return availableCredit; }
        public void setAvailableCredit(final List<LabelRule> v) { this.availableCredit = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getMinimumPaymentDue() { return minimumPaymentDue; }
        public void setMinimumPaymentDue(final List<LabelRule> v) { this.minimumPaymentDue = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getPaymentDueDate() { return paymentDueDate; }
        public void setPaymentDueDate(final List<LabelRule> v) { this.paymentDueDate = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getYtdFees() { return ytdFees; }
        public void setYtdFees(final List<LabelRule> v) { this.ytdFees = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getYtdInterest() { return ytdInterest; }
        public void setYtdInterest(final List<LabelRule> v) { this.ytdInterest = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getPurchaseApr() { return purchaseApr; }
        public void setPurchaseApr(final List<LabelRule> v) { this.purchaseApr = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getCashAdvanceApr() { return cashAdvanceApr; }
        public void setCashAdvanceApr(final List<LabelRule> v) { this.cashAdvanceApr = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getBalanceTransferApr() { return balanceTransferApr; }
        public void setBalanceTransferApr(final List<LabelRule> v) { this.balanceTransferApr = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getPenaltyApr() { return penaltyApr; }
        public void setPenaltyApr(final List<LabelRule> v) { this.penaltyApr = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getPointsBalance() { return pointsBalance; }
        public void setPointsBalance(final List<LabelRule> v) { this.pointsBalance = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getPointsEarned() { return pointsEarned; }
        public void setPointsEarned(final List<LabelRule> v) { this.pointsEarned = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getPreviousPointsBalance() { return previousPointsBalance; }
        public void setPreviousPointsBalance(final List<LabelRule> v) { this.previousPointsBalance = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getCashbackBalance() { return cashbackBalance; }
        public void setCashbackBalance(final List<LabelRule> v) { this.cashbackBalance = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getAutopayEnabled() { return autopayEnabled; }
        public void setAutopayEnabled(final List<LabelRule> v) { this.autopayEnabled = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getNextAutopayAmount() { return nextAutopayAmount; }
        public void setNextAutopayAmount(final List<LabelRule> v) { this.nextAutopayAmount = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getAnnualFee() { return annualFee; }
        public void setAnnualFee(final List<LabelRule> v) { this.annualFee = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getAnnualFeeDueDate() { return annualFeeDueDate; }
        public void setAnnualFeeDueDate(final List<LabelRule> v) { this.annualFeeDueDate = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getForeignTxFeePercent() { return foreignTxFeePercent; }
        public void setForeignTxFeePercent(final List<LabelRule> v) { this.foreignTxFeePercent = v == null ? Collections.emptyList() : v; }
        public List<LabelRule> getBillingDays() { return billingDays; }
        public void setBillingDays(final List<LabelRule> v) { this.billingDays = v == null ? Collections.emptyList() : v; }

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
        // Optional scope: when set, the rule only considers text appearing
        // AFTER the first occurrence of {@code afterSection} (matched as a
        // case-insensitive substring on a whole line). Lets you say
        // "only look for purchase_apr after the 'Interest Charge Calculation'
        // header" so APR rates buried in disclosure prose at the top of the
        // statement don't false-positive against the rule. Applies to both
        // label-adjacent and explicit-pattern strategies.
        @JsonProperty("after_section") private String afterSection;
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
        public String getAfterSection() { return afterSection; }
        public void setAfterSection(final String v) { this.afterSection = v; }
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
    public List<String> getExtendsList() { return extendsList; }
    public void setExtendsList(final List<String> v) {
        this.extendsList = v == null ? Collections.emptyList() : v;
    }
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
    public List<TransactionShape> getTransactions() { return transactions; }
    public void setTransactions(final List<TransactionShape> v) {
        this.transactions = v == null ? Collections.emptyList() : v;
    }
    public List<Sample> getSamples() { return samples; }
    public void setSamples(final List<Sample> v) {
        this.samples = v == null ? Collections.emptyList() : v;
    }

    public boolean isV2() {
        return cardDetection != null || metadata != null || preprocessing != null;
    }

    /**
     * Self-test sample for a template. The {@code expected} map is keyed by
     * snake_case metadata field name (e.g. {@code new_balance},
     * {@code purchase_apr}, {@code points_balance}) and carries the value the
     * evaluator must return. Values can be numbers, booleans, or
     * {@code MM/dd/yy} / {@code yyyy-MM-dd} date strings — the test runner
     * coerces to the right Java type per field.
     *
     * <p>Use samples to lock in known statement layouts: each new fixture in
     * the corpus should be a one-line addition here, not a new Java test
     * class.
     */
    /**
     * One transaction-shape rule. Two modes:
     *
     * <ol>
     *   <li><b>Single-line</b> ({@code line_regex} set): the existing
     *       single-line transaction case. Named groups {@code date},
     *       {@code description}, {@code amount} are required.</li>
     *   <li><b>Multi-line</b> ({@code start_regex} + {@code end_regex} set):
     *       a transaction spans 2-N consecutive non-blank lines.
     *       {@code start_regex} captures date+desc on the first line;
     *       {@code end_regex} captures the amount on the last line; lines
     *       in between are description continuation. {@code max_lines}
     *       bounds the scan window (default 5).</li>
     * </ol>
     *
     * <p>{@code section_anchor} is optional: when set, the shape only fires
     * for text in sections whose header matches. Avoids non-transaction
     * blocks (rewards summary, fees disclosure) being misread as
     * transactions.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionShape {
        private String name;
        // single-line:
        @JsonProperty("line_regex") private String lineRegex;
        // multi-line:
        @JsonProperty("start_regex") private String startRegex;
        @JsonProperty("end_regex") private String endRegex;
        @JsonProperty("max_lines") private Integer maxLines;
        // shared:
        @JsonProperty("date_format") private String dateFormat;
        @JsonProperty("account_type") private String accountType;
        @JsonProperty("sign_convention") private String signConvention;
        @JsonProperty("min_amount") private java.math.BigDecimal minAmount;
        @JsonProperty("section_anchor") private String sectionAnchor;
        // FX-block stripping: when set, lines matching this regex are
        // discarded BEFORE shape matching. Lets Amex's foreign-tx info
        // block be removed without each shape having to anticipate it.
        @JsonProperty("strip_lines_matching") private List<String> stripLinesMatching = Collections.emptyList();

        public String getName() { return name; }
        public void setName(final String v) { this.name = v; }
        public String getLineRegex() { return lineRegex; }
        public void setLineRegex(final String v) { this.lineRegex = v; }
        public String getStartRegex() { return startRegex; }
        public void setStartRegex(final String v) { this.startRegex = v; }
        public String getEndRegex() { return endRegex; }
        public void setEndRegex(final String v) { this.endRegex = v; }
        public Integer getMaxLines() { return maxLines; }
        public void setMaxLines(final Integer v) { this.maxLines = v; }
        public String getDateFormat() { return dateFormat; }
        public void setDateFormat(final String v) { this.dateFormat = v; }
        public String getAccountType() { return accountType; }
        public void setAccountType(final String v) { this.accountType = v; }
        public String getSignConvention() { return signConvention; }
        public void setSignConvention(final String v) { this.signConvention = v; }
        public java.math.BigDecimal getMinAmount() { return minAmount; }
        public void setMinAmount(final java.math.BigDecimal v) { this.minAmount = v; }
        public String getSectionAnchor() { return sectionAnchor; }
        public void setSectionAnchor(final String v) { this.sectionAnchor = v; }
        public List<String> getStripLinesMatching() { return stripLinesMatching; }
        public void setStripLinesMatching(final List<String> v) {
            this.stripLinesMatching = v == null ? Collections.emptyList() : v;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sample {
        private String name;
        private String input;
        private Map<String, Object> expected = Collections.emptyMap();

        public String getName() { return name; }
        public void setName(final String v) { this.name = v; }
        public String getInput() { return input; }
        public void setInput(final String v) { this.input = v; }
        public Map<String, Object> getExpected() { return expected; }
        public void setExpected(final Map<String, Object> v) {
            this.expected = v == null ? Collections.emptyMap() : v;
        }
    }
}
