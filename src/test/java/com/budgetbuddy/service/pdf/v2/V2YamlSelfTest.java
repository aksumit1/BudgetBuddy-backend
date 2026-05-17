package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Self-test runner for every v2 YAML template's inline {@code samples:} block.
 *
 * <p>Each YAML template can carry one or more {@code Sample} blocks with a
 * synthetic input snippet and an {@code expected} map of metadata field
 * values. This test discovers every template on the classpath and emits one
 * dynamic test per sample so adding a new issuer or a new corpus statement is
 * a YAML-only change — no Java required.
 *
 * <p>This replaces the per-issuer {@code *StatementFixtureTest} explosion in
 * the v1 era: the same coverage now lives next to the rules it exercises.
 */
class V2YamlSelfTest {

    private static final PdfTemplateV2Evaluator EVAL = new PdfTemplateV2Evaluator();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("M/d/yy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yy"),
    };

    @TestFactory
    Iterable<DynamicTest> samplesFromEveryYamlTemplate() throws IOException {
        final List<DynamicTest> tests = new ArrayList<>();
        final Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:pdf-templates-v2/*.yaml");
        // First pass: load every template. Second pass: resolve extends: so
        // a template that inherits from common.yaml carries the merged rule
        // list when its samples run.
        final List<PdfTemplateV2> raw = new ArrayList<>();
        final List<String> filenames = new ArrayList<>();
        for (final Resource r : resources) {
            try {
                raw.add(YAML_MAPPER.readValue(r.getInputStream(), PdfTemplateV2.class));
                filenames.add(r.getFilename());
            } catch (final IOException ex) {
                tests.add(DynamicTest.dynamicTest(
                        "YAML load: " + r.getFilename(),
                        () -> fail("YAML parse failed: " + ex.getMessage())));
            }
        }
        final List<PdfTemplateV2> resolved = TemplateMerger.resolve(raw);
        for (int i = 0; i < resolved.size(); i++) {
            final PdfTemplateV2 t = resolved.get(i);
            final String filename = filenames.get(i);
            if (t.getSamples().isEmpty()) continue;
            for (final PdfTemplateV2.Sample s : t.getSamples()) {
                final String label = filename + " :: "
                        + (s.getName() == null ? "<unnamed>" : s.getName());
                tests.add(DynamicTest.dynamicTest(label, () -> runSample(t, s)));
            }
        }
        return tests;
    }

    private static void runSample(final PdfTemplateV2 t, final PdfTemplateV2.Sample s) {
        assertNotNull(s.getInput(), "sample 'input' must be set");
        final PdfTemplateV2Evaluator.MetadataResult m =
                EVAL.evaluateMetadata(t, s.getInput());
        assertNotNull(m, "evaluator returned null");
        for (final Map.Entry<String, Object> e : s.getExpected().entrySet()) {
            final String field = e.getKey();
            final Object expected = e.getValue();
            final Object actual = readField(m, field);
            assertFieldEquals(field, expected, actual);
        }
    }

    /**
     * Look up the named metadata field on the evaluator result. Returns
     * {@code null} when the field is unknown; the assertion below then yields
     * a descriptive failure rather than a NullPointerException deep in
     * reflection.
     */
    private static Object readField(
            final PdfTemplateV2Evaluator.MetadataResult m, final String field) {
        switch (field) {
            case "new_balance": return m.newBalance;
            case "previous_balance": return m.previousBalance;
            case "credit_limit": return m.creditLimit;
            case "available_credit": return m.availableCredit;
            case "minimum_payment_due": return m.minimumPaymentDue;
            case "payment_due_date": return m.paymentDueDate;
            case "purchases_total": return m.purchasesTotal;
            case "payments_and_credits_total":
            case "payments_total": return m.paymentsAndCreditsTotal;
            case "fees_total": return m.feesTotal;
            case "interest_total": return m.interestTotal;
            case "ytd_fees": return m.ytdFees;
            case "ytd_interest": return m.ytdInterest;
            case "purchase_apr": return m.purchaseApr;
            case "cash_advance_apr": return m.cashAdvanceApr;
            case "balance_transfer_apr": return m.balanceTransferApr;
            case "penalty_apr": return m.penaltyApr;
            case "points_balance": return m.pointsBalance;
            case "points_earned": return m.pointsEarned;
            case "previous_points_balance": return m.previousPointsBalance;
            case "cashback_balance": return m.cashbackBalance;
            case "autopay_enabled": return m.autopayEnabled;
            case "next_autopay_amount": return m.nextAutopayAmount;
            case "annual_fee": return m.annualFee;
            case "annual_fee_due_date": return m.annualFeeDueDate;
            case "foreign_tx_fee_percent": return m.foreignTxFeePercent;
            case "billing_days": return m.billingDays;
            case "statement_date": return m.statementDate;
            case "statement_start": return m.statementStart;
            case "statement_end": return m.statementEnd;
            default:
                fail("unknown expected field: " + field
                        + " (add it to V2YamlSelfTest.readField or rename the YAML key)");
                return null;
        }
    }

    /**
     * Compare expected vs actual with type coercion: YAML numbers come in as
     * Integer/Long/Double; we coerce them to BigDecimal (for amount/percent
     * fields) or Long (for points) based on the actual's runtime type. Date
     * fields accept ISO-8601 or US-slash forms. {@code null} expected means
     * the field must NOT be set (intentional negative).
     */
    private static void assertFieldEquals(
            final String field, final Object expected, final Object actual) {
        if (expected == null) {
            assertEquals(null, actual,
                    "field '" + field + "' expected null but got " + actual);
            return;
        }
        if (actual == null) {
            fail("field '" + field + "' expected " + expected + " but evaluator returned null");
        }
        if (actual instanceof BigDecimal bd) {
            final BigDecimal exp = toBigDecimal(field, expected);
            assertEquals(0, exp.compareTo(bd),
                    "field '" + field + "' expected " + exp + " got " + bd);
            return;
        }
        if (actual instanceof Long || actual instanceof Integer) {
            final long exp = ((Number) expected).longValue();
            final long act = ((Number) actual).longValue();
            assertEquals(exp, act, "field '" + field + "' expected " + exp + " got " + act);
            return;
        }
        if (actual instanceof Boolean) {
            assertEquals(expected, actual, "field '" + field + "'");
            return;
        }
        if (actual instanceof LocalDate ld) {
            final LocalDate exp = toLocalDate(field, expected);
            assertEquals(exp, ld, "field '" + field + "' expected " + exp + " got " + ld);
            return;
        }
        // Fallback: string-equality.
        assertEquals(String.valueOf(expected), String.valueOf(actual),
                "field '" + field + "'");
    }

    private static BigDecimal toBigDecimal(final String field, final Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s) {
            try { return new BigDecimal(s.trim()); }
            catch (final NumberFormatException ex) {
                fail("field '" + field + "' expected numeric, got " + s);
            }
        }
        fail("field '" + field + "' cannot coerce " + v.getClass().getSimpleName() + " to BigDecimal");
        return null;
    }

    private static LocalDate toLocalDate(final String field, final Object v) {
        if (v instanceof LocalDate d) return d;
        final String s = String.valueOf(v).trim();
        for (final DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(s, fmt); }
            catch (final DateTimeParseException ignored) { /* try next */ }
        }
        fail("field '" + field + "' could not parse date: " + s);
        return null;
    }

}
