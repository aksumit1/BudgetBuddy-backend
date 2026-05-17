package com.budgetbuddy.service.pdf.v2;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Maps v2 metadata fields to {@link ImportResult} setters in one place.
 *
 * <p>Before this class existed, {@code PDFImportService.applyV2FillMissing}
 * had 25+ field-by-field if-blocks: one to clear the legacy-extracted value
 * when v2 declares the field, and a parallel one to write the v2 result.
 * Adding a new metadata field meant touching that method twice — easy to
 * forget half. Now each field is one row in the {@link #BINDINGS} list:
 * which rule list it reads from, which evaluator field carries its value,
 * which {@code ImportResult} setter receives the value. Adding a new field
 * = one row.
 *
 * <p>Why not pure reflection? Because the v2 names and {@code ImportResult}
 * setter names diverge in several cases (e.g. {@code feesTotal} →
 * {@code setFeesChargedTotal}, {@code pointsEarned} →
 * {@code setPointsEarnedThisPeriod}). A typed binding table is just as
 * compact and far easier to read than reflection-with-annotations.
 */
public final class V2FieldBinder {

    /**
     * Single field binding: how to read the rule list (to know whether v2
     * "owns" this field), how to read the extracted value, and how to write
     * the value into the legacy {@code ImportResult}. Generic parameter
     * {@code V} is the field's value type.
     */
    public static final class Binding<V> {
        public final String name;
        public final Function<PdfTemplateV2.MetadataRules, List<?>> ruleList;
        public final Function<PdfTemplateV2Evaluator.MetadataResult, V> getValue;
        public final BiConsumer<ImportResult, V> setter;

        public Binding(final String name,
                final Function<PdfTemplateV2.MetadataRules, List<?>> ruleList,
                final Function<PdfTemplateV2Evaluator.MetadataResult, V> getValue,
                final BiConsumer<ImportResult, V> setter) {
            this.name = name;
            this.ruleList = ruleList;
            this.getValue = getValue;
            this.setter = setter;
        }
    }

    public static final List<Binding<?>> BINDINGS = List.of(
            new Binding<>("new_balance",
                    PdfTemplateV2.MetadataRules::getNewBalance,
                    m -> m.newBalance, ImportResult::setNewBalance),
            new Binding<>("previous_balance",
                    PdfTemplateV2.MetadataRules::getPreviousBalance,
                    m -> m.previousBalance, ImportResult::setPreviousBalance),
            new Binding<>("credit_limit",
                    PdfTemplateV2.MetadataRules::getCreditLimit,
                    m -> m.creditLimit, ImportResult::setCreditLimit),
            new Binding<>("available_credit",
                    PdfTemplateV2.MetadataRules::getAvailableCredit,
                    m -> m.availableCredit, ImportResult::setAvailableCredit),
            new Binding<>("minimum_payment_due",
                    PdfTemplateV2.MetadataRules::getMinimumPaymentDue,
                    m -> m.minimumPaymentDue, ImportResult::setMinimumPaymentDue),
            new Binding<>("payment_due_date",
                    PdfTemplateV2.MetadataRules::getPaymentDueDate,
                    m -> m.paymentDueDate, ImportResult::setPaymentDueDate),
            new Binding<>("purchases_total",
                    PdfTemplateV2.MetadataRules::getPurchasesTotal,
                    m -> m.purchasesTotal, ImportResult::setPurchasesTotal),
            new Binding<>("payments_total",
                    PdfTemplateV2.MetadataRules::getPaymentsTotal,
                    m -> m.paymentsAndCreditsTotal, ImportResult::setPaymentsAndCreditsTotal),
            new Binding<>("fees_total",
                    PdfTemplateV2.MetadataRules::getFeesTotal,
                    m -> m.feesTotal, ImportResult::setFeesChargedTotal),
            new Binding<>("interest_total",
                    PdfTemplateV2.MetadataRules::getInterestTotal,
                    m -> m.interestTotal, ImportResult::setInterestChargedTotal),
            new Binding<>("ytd_fees",
                    PdfTemplateV2.MetadataRules::getYtdFees,
                    m -> m.ytdFees, ImportResult::setYtdFeesCharged),
            new Binding<>("ytd_interest",
                    PdfTemplateV2.MetadataRules::getYtdInterest,
                    m -> m.ytdInterest, ImportResult::setYtdInterestCharged),
            new Binding<>("purchase_apr",
                    PdfTemplateV2.MetadataRules::getPurchaseApr,
                    m -> m.purchaseApr, ImportResult::setPurchaseApr),
            new Binding<>("cash_advance_apr",
                    PdfTemplateV2.MetadataRules::getCashAdvanceApr,
                    m -> m.cashAdvanceApr, ImportResult::setCashAdvanceApr),
            new Binding<>("balance_transfer_apr",
                    PdfTemplateV2.MetadataRules::getBalanceTransferApr,
                    m -> m.balanceTransferApr, ImportResult::setBalanceTransferApr),
            new Binding<>("penalty_apr",
                    PdfTemplateV2.MetadataRules::getPenaltyApr,
                    m -> m.penaltyApr, ImportResult::setPenaltyApr),
            new Binding<>("points_balance",
                    PdfTemplateV2.MetadataRules::getPointsBalance,
                    m -> m.pointsBalance, ImportResult::setPointsBalance),
            new Binding<>("points_earned",
                    PdfTemplateV2.MetadataRules::getPointsEarned,
                    m -> m.pointsEarned, ImportResult::setPointsEarnedThisPeriod),
            new Binding<>("previous_points_balance",
                    PdfTemplateV2.MetadataRules::getPreviousPointsBalance,
                    m -> m.previousPointsBalance, ImportResult::setPreviousPointsBalance),
            new Binding<>("cashback_balance",
                    PdfTemplateV2.MetadataRules::getCashbackBalance,
                    m -> m.cashbackBalance, ImportResult::setCashBackBalance),
            new Binding<>("autopay_enabled",
                    PdfTemplateV2.MetadataRules::getAutopayEnabled,
                    m -> m.autopayEnabled, ImportResult::setAutoPayEnabled),
            new Binding<>("next_autopay_amount",
                    PdfTemplateV2.MetadataRules::getNextAutopayAmount,
                    m -> m.nextAutopayAmount, ImportResult::setNextAutoPayAmount),
            new Binding<>("annual_fee",
                    PdfTemplateV2.MetadataRules::getAnnualFee,
                    m -> m.annualFee, ImportResult::setAnnualMembershipFee),
            new Binding<>("annual_fee_due_date",
                    PdfTemplateV2.MetadataRules::getAnnualFeeDueDate,
                    m -> m.annualFeeDueDate, ImportResult::setAnnualMembershipFeeDueDate),
            new Binding<>("foreign_tx_fee_percent",
                    PdfTemplateV2.MetadataRules::getForeignTxFeePercent,
                    m -> m.foreignTxFeePercent, ImportResult::setForeignTransactionFeePercent),
            new Binding<>("billing_days",
                    PdfTemplateV2.MetadataRules::getBillingDays,
                    m -> m.billingDays, ImportResult::setBillingDays));

    /**
     * Statement-date bindings are special: they're driven by statementPeriod
     * (a List<PeriodRule>, not List<LabelRule>) and don't need the
     * clear-then-fill semantics — there's no parallel legacy extractor at
     * the {@code ImportResult} layer. Handled separately by
     * {@link #applyStatementDates}.
     */
    private V2FieldBinder() { }

    /**
     * Clear any legacy-extracted value for every field the template's
     * metadata rules cover, then overwrite with the v2-extracted value when
     * present. This is the "v2 owns the field if v2 declares it" contract
     * that makes the audit corpus reconcile cleanly.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void applyFillMissing(
            final PdfTemplateV2.MetadataRules rules,
            final PdfTemplateV2Evaluator.MetadataResult extracted,
            final ImportResult target) {
        if (rules == null || target == null) return;
        for (final Binding<?> b : BINDINGS) {
            final List<?> ruleList = b.ruleList.apply(rules);
            if (ruleList == null || ruleList.isEmpty()) continue;
            // v2 declares this field — clear the legacy value first so the
            // final result reflects v2's view (including a deliberate null).
            ((BiConsumer) b.setter).accept(target, null);
            if (extracted == null) continue;
            final Object value = b.getValue.apply(extracted);
            if (value != null) {
                ((BiConsumer) b.setter).accept(target, value);
            }
        }
        applyStatementDates(rules, extracted, target);
    }

    /**
     * Statement dates are derived from two distinct YAML rule lists —
     * {@code statement_date} (List&lt;LabelRule&gt;) and {@code statement_period}
     * (List&lt;PeriodRule&gt; producing both start and end). The clear-then-fill
     * contract for these is: if v2 declares the corresponding rule list,
     * clear any legacy value first, then write the v2 value when present.
     */
    private static void applyStatementDates(
            final PdfTemplateV2.MetadataRules rules,
            final PdfTemplateV2Evaluator.MetadataResult m,
            final ImportResult target) {
        if (rules == null || target == null) return;
        if (rules.getStatementDate() != null && !rules.getStatementDate().isEmpty()) {
            target.setStatementDate(null);
        }
        if (rules.getStatementPeriod() != null && !rules.getStatementPeriod().isEmpty()) {
            target.setStatementStartDate(null);
            target.setStatementEndDate(null);
        }
        if (m == null) return;
        if (m.statementDate != null) target.setStatementDate(m.statementDate);
        if (m.statementStart != null) target.setStatementStartDate(m.statementStart);
        if (m.statementEnd != null) target.setStatementEndDate(m.statementEnd);
    }

    // Compile-time hint that BigDecimal / Long / Integer / Boolean / LocalDate
    // are the value types covered by the bindings above. If a new field uses
    // a type outside this set, add a Binding entry and ensure the setter
    // accepts that type.
    @SuppressWarnings("unused")
    private static final Class<?>[] SUPPORTED_VALUE_TYPES = {
            BigDecimal.class, Long.class, Integer.class, Boolean.class, LocalDate.class
    };
}
