package com.budgetbuddy.service.pdf.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Authoring-time linter for v2 YAML templates. Catches the most common kinds
 * of authoring mistake that would otherwise silently produce null fields at
 * extraction time:
 *
 * <ul>
 *   <li>Required top-level fields missing ({@code id}, {@code institution}).</li>
 *   <li>Label-rule shape conflicts (no usable {@code label} / {@code pattern} /
 *       {@code stacked_header}, or {@code stacked_index} without
 *       {@code stacked_labels}, or {@code stacked_index} out of bounds).</li>
 *   <li>Uncompileable regex patterns (the offending pattern would be a no-op
 *       at runtime; better to fail fast at startup).</li>
 *   <li>{@code adjacent} values outside the supported set
 *       ({@code dollar} / {@code amount} / {@code date}).</li>
 * </ul>
 *
 * <p>Returns issues rather than throwing so the registry can decide whether to
 * skip the template or load it with a WARN. The default registry policy is to
 * load with WARN — the YAML is still usable, the linter just flags the rough
 * edges so they get fixed.
 */
public final class PdfTemplateV2Validator {

    public enum Severity { WARN, ERROR }

    public static final class Issue {
        public final Severity severity;
        public final String path;     // dotted path within the template
        public final String message;

        public Issue(final Severity severity, final String path, final String message) {
            this.severity = severity;
            this.path = path;
            this.message = message;
        }

        @Override
        public String toString() {
            return severity + " " + path + ": " + message;
        }
    }

    private PdfTemplateV2Validator() { }

    public static List<Issue> validate(final PdfTemplateV2 t) {
        final List<Issue> out = new ArrayList<>();
        if (t == null) {
            out.add(new Issue(Severity.ERROR, "<template>", "null template"));
            return out;
        }
        if (isBlank(t.getId())) {
            out.add(new Issue(Severity.ERROR, "id", "missing required field"));
        }
        if (isBlank(t.getInstitution())) {
            out.add(new Issue(Severity.ERROR, "institution", "missing required field"));
        }
        validateCardDetection(t, out);
        validateMetadata(t, out);
        validateSamples(t, out);
        return out;
    }

    private static void validateCardDetection(final PdfTemplateV2 t, final List<Issue> out) {
        if (t.getCardDetection() == null) return;
        int i = 0;
        for (final PdfTemplateV2.RegexRule r : t.getCardDetection().getInstitutionMatch()) {
            checkRegex(r.getPattern(), "card_detection.institution_match[" + i + "].pattern", out);
            for (int j = 0; j < r.getAnyOf().size(); j++) {
                checkRegex(r.getAnyOf().get(j),
                        "card_detection.institution_match[" + i + "].any_of[" + j + "]", out);
            }
            i++;
        }
        i = 0;
        for (final PdfTemplateV2.RegexRule r : t.getCardDetection().getLastFour()) {
            if (isBlank(r.getPattern())) {
                out.add(new Issue(Severity.WARN,
                        "card_detection.last_four[" + i + "]",
                        "rule has no pattern — will never match"));
            } else {
                checkRegex(r.getPattern(), "card_detection.last_four[" + i + "].pattern", out);
            }
            i++;
        }
    }

    private static void validateMetadata(final PdfTemplateV2 t, final List<Issue> out) {
        final PdfTemplateV2.MetadataRules m = t.getMetadata();
        if (m == null) return;
        validateLabelRules("metadata.new_balance", m.getNewBalance(), out);
        validateLabelRules("metadata.previous_balance", m.getPreviousBalance(), out);
        validateLabelRules("metadata.credit_limit", m.getCreditLimit(), out);
        validateLabelRules("metadata.available_credit", m.getAvailableCredit(), out);
        validateLabelRules("metadata.minimum_payment_due", m.getMinimumPaymentDue(), out);
        validateLabelRules("metadata.payment_due_date", m.getPaymentDueDate(), out);
        validateLabelRules("metadata.purchases_total", m.getPurchasesTotal(), out);
        validateLabelRules("metadata.payments_total", m.getPaymentsTotal(), out);
        validateLabelRules("metadata.fees_total", m.getFeesTotal(), out);
        validateLabelRules("metadata.interest_total", m.getInterestTotal(), out);
        validateLabelRules("metadata.ytd_fees", m.getYtdFees(), out);
        validateLabelRules("metadata.ytd_interest", m.getYtdInterest(), out);
        validateLabelRules("metadata.purchase_apr", m.getPurchaseApr(), out);
        validateLabelRules("metadata.cash_advance_apr", m.getCashAdvanceApr(), out);
        validateLabelRules("metadata.balance_transfer_apr", m.getBalanceTransferApr(), out);
        validateLabelRules("metadata.penalty_apr", m.getPenaltyApr(), out);
        validateLabelRules("metadata.points_balance", m.getPointsBalance(), out);
        validateLabelRules("metadata.points_earned", m.getPointsEarned(), out);
        validateLabelRules("metadata.previous_points_balance", m.getPreviousPointsBalance(), out);
        validateLabelRules("metadata.cashback_balance", m.getCashbackBalance(), out);
        validateLabelRules("metadata.autopay_enabled", m.getAutopayEnabled(), out);
        validateLabelRules("metadata.next_autopay_amount", m.getNextAutopayAmount(), out);
        validateLabelRules("metadata.annual_fee", m.getAnnualFee(), out);
        validateLabelRules("metadata.annual_fee_due_date", m.getAnnualFeeDueDate(), out);
        validateLabelRules("metadata.foreign_tx_fee_percent", m.getForeignTxFeePercent(), out);
        validateLabelRules("metadata.billing_days", m.getBillingDays(), out);
        validateLabelRules("metadata.statement_date", m.getStatementDate(), out);
        for (int i = 0; i < m.getStatementPeriod().size(); i++) {
            checkRegex(m.getStatementPeriod().get(i).getPattern(),
                    "metadata.statement_period[" + i + "].pattern", out);
        }
    }

    private static void validateLabelRules(
            final String path, final List<PdfTemplateV2.LabelRule> rules, final List<Issue> out) {
        if (rules == null) return;
        for (int i = 0; i < rules.size(); i++) {
            final PdfTemplateV2.LabelRule r = rules.get(i);
            final String rulePath = path + "[" + i + "]";
            final boolean hasLabel = !isBlank(r.getLabel());
            final boolean hasPattern = !isBlank(r.getPattern());
            final boolean hasStacked = !isBlank(r.getStackedHeader());

            if (!hasLabel && !hasPattern && !hasStacked) {
                out.add(new Issue(Severity.ERROR, rulePath,
                        "rule has no label, pattern, or stacked_header — it can never match"));
                continue;
            }
            if (hasPattern) {
                checkRegex(r.getPattern(), rulePath + ".pattern", out);
            }
            if (hasStacked) {
                if (r.getStackedLabels().isEmpty()) {
                    out.add(new Issue(Severity.ERROR, rulePath + ".stacked_labels",
                            "stacked_header set but stacked_labels is empty"));
                } else if (r.getStackedIndex() == null) {
                    out.add(new Issue(Severity.ERROR, rulePath + ".stacked_index",
                            "stacked_header set but stacked_index is null"));
                } else if (r.getStackedIndex() < 0
                        || r.getStackedIndex() >= r.getStackedLabels().size()) {
                    out.add(new Issue(Severity.ERROR, rulePath + ".stacked_index",
                            "stacked_index " + r.getStackedIndex() + " is out of range for "
                                    + r.getStackedLabels().size() + " labels"));
                }
            }
            if (r.getAdjacent() != null) {
                final String a = r.getAdjacent().toLowerCase();
                if (!a.equals("dollar") && !a.equals("amount") && !a.equals("date")) {
                    out.add(new Issue(Severity.WARN, rulePath + ".adjacent",
                            "unrecognized adjacent kind '" + r.getAdjacent()
                                    + "' (expected dollar|amount|date)"));
                }
            }
        }
    }

    private static void validateSamples(final PdfTemplateV2 t, final List<Issue> out) {
        for (int i = 0; i < t.getSamples().size(); i++) {
            final PdfTemplateV2.Sample s = t.getSamples().get(i);
            final String basePath = "samples[" + i + "]";
            if (isBlank(s.getInput())) {
                out.add(new Issue(Severity.ERROR, basePath + ".input",
                        "sample has no input — runner would fail with NPE"));
            }
            if (s.getExpected().isEmpty()) {
                out.add(new Issue(Severity.WARN, basePath + ".expected",
                        "sample has no expected fields — runner won't assert anything"));
            }
        }
    }

    private static void checkRegex(final String pattern, final String path, final List<Issue> out) {
        if (isBlank(pattern)) return;
        try {
            Pattern.compile(pattern);
        } catch (final PatternSyntaxException ex) {
            out.add(new Issue(Severity.ERROR, path,
                    "invalid regex: " + ex.getDescription()
                            + (ex.getIndex() >= 0 ? " (at index " + ex.getIndex() + ")" : "")));
        }
    }

    private static boolean isBlank(final String s) {
        return s == null || s.trim().isEmpty();
    }
}
