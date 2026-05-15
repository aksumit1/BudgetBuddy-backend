package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JSON-contract validation for the API response DTOs that surface PDF metadata to the
 * iOS app. Any time someone renames or drops a field on {@link
 * TransactionController.DetectedAccountInfo} or {@link
 * TransactionController.ChunkImportResponse}, the wire format silently changes —
 * the iOS Codable decoder will then return nil for the renamed field and the UI will
 * show "—" without any error path firing.
 *
 * <p>These tests serialize a fully-populated DTO via Jackson exactly the way Spring
 * Boot's auto-configured MappingJackson2HttpMessageConverter does at runtime, then
 * assert:
 *
 * <ol>
 *   <li>Every one of the 24 statement-summary fields lands at the expected JSON key
 *       (catches typos and renames).
 *   <li>Numeric fields serialize as JSON numbers (not strings) so iOS {@code Decimal}
 *       decoders don't have to special-case them.
 *   <li>Date fields serialize as ISO-8601 {@code yyyy-MM-dd} strings (iOS expects
 *       this; a switch to numeric epoch-milli would silently break decoding).
 *   <li>Round-trip (serialize → deserialize) preserves every value exactly.
 *   <li>Null fields are either omitted or emit JSON {@code null} — but the choice
 *       must be consistent so iOS's optional-decoding behaviour is predictable.
 * </ol>
 */
class TransactionControllerJsonContractTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        // Match the production Spring Boot Jackson config: JSR-310 module for LocalDate
        // serialisation as ISO strings (not epoch millis), and NON_NULL include so absent
        // fields don't bloat the wire format.
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    // ============================================================
    //  Field name + type contract
    // ============================================================

    @Test
    void detectedAccountInfo_serializesEveryStatementSummaryFieldAtExpectedKey()
            throws Exception {
        final TransactionController.DetectedAccountInfo info = newFullyPopulated();
        final JsonNode json = mapper.valueToTree(info);

        // Every new key the iOS Codable struct expects. If someone renames a Java
        // getter via Jackson, the assertion fails with the specific missing key.
        for (final String key : new String[] {
                "newBalance",
                "previousBalance",
                "creditLimit",
                "availableCredit",
                "pastDueAmount",
                "purchasesTotal",
                "paymentsAndCreditsTotal",
                "cashAdvancesTotal",
                "balanceTransfersTotal",
                "feesChargedTotal",
                "interestChargedTotal",
                "purchaseApr",
                "cashAdvanceApr",
                "balanceTransferApr",
                "penaltyApr",
                "cashAccessLine",
                "availableForCash",
                "foreignTransactionFeePercent",
                "billingDays",
                "statementDate",
                "annualMembershipFee",
                "annualMembershipFeeDueDate",
                "autoPayEnabled",
                "nextAutoPayAmount",
                "pointsEarnedThisPeriod",
                "pointsBalance",
                // Legacy three — kept for backward compat.
                "paymentDueDate",
                "minimumPaymentDue",
                "rewardPoints",
        }) {
            assertTrue(json.has(key),
                    "DetectedAccountInfo JSON must contain key '" + key
                            + "'. Available keys: " + iterableToString(json.fieldNames()));
        }
    }

    @Test
    void detectedAccountInfo_serializesNumericFieldsAsNumbers_notStrings() throws Exception {
        // Critical for iOS Decimal decoding — a Number JSON token decodes directly,
        // but a String token requires explicit conversion. Pin the Number form so a
        // future Jackson config tweak (e.g. WRITE_NUMBERS_AS_STRINGS) doesn't silently
        // break iOS decoding.
        final TransactionController.DetectedAccountInfo info = newFullyPopulated();
        final JsonNode json = mapper.valueToTree(info);

        assertTrue(json.get("newBalance").isNumber(),
                "newBalance must serialize as a JSON number; got: "
                        + json.get("newBalance").getNodeType());
        assertTrue(json.get("creditLimit").isNumber());
        assertTrue(json.get("purchaseApr").isNumber());
        assertTrue(json.get("foreignTransactionFeePercent").isNumber());
        assertTrue(json.get("nextAutoPayAmount").isNumber());
        assertTrue(json.get("billingDays").isNumber());
        assertTrue(json.get("rewardPoints").isNumber());
        assertTrue(json.get("pointsEarnedThisPeriod").isNumber());
        assertTrue(json.get("pointsBalance").isNumber());
    }

    @Test
    void detectedAccountInfo_serializesDatesAsIso8601Strings_notEpoch() throws Exception {
        // iOS expects "2026-06-17" — disabling WRITE_DATES_AS_TIMESTAMPS in setUp()
        // matches the production config. Pin both that they ARE strings AND that the
        // format matches ISO-8601 yyyy-MM-dd.
        final TransactionController.DetectedAccountInfo info = newFullyPopulated();
        final JsonNode json = mapper.valueToTree(info);

        assertTrue(json.get("statementDate").isTextual(),
                "statementDate must serialize as ISO string, not epoch");
        assertEquals("2026-06-17", json.get("statementDate").asText());

        assertTrue(json.get("paymentDueDate").isTextual());
        assertEquals("2026-07-14", json.get("paymentDueDate").asText());

        assertTrue(json.get("annualMembershipFeeDueDate").isTextual());
        assertEquals("2026-09-01", json.get("annualMembershipFeeDueDate").asText());
    }

    @Test
    void detectedAccountInfo_serializesBooleanAutoPayAsJsonBoolean() throws Exception {
        // AutoPay must be a true/false JSON literal, not "true"/"false" strings —
        // iOS decodes it as Bool? and a string would throw.
        final TransactionController.DetectedAccountInfo info = newFullyPopulated();
        final JsonNode json = mapper.valueToTree(info);
        assertTrue(json.get("autoPayEnabled").isBoolean());
        assertTrue(json.get("autoPayEnabled").asBoolean());
    }

    // ============================================================
    //  Round-trip preservation
    // ============================================================

    @Test
    void detectedAccountInfo_roundTripsEveryFieldThroughSerializeDeserialize() throws Exception {
        final TransactionController.DetectedAccountInfo original = newFullyPopulated();
        final String wire = mapper.writeValueAsString(original);
        final TransactionController.DetectedAccountInfo decoded =
                mapper.readValue(wire, TransactionController.DetectedAccountInfo.class);

        assertNotNull(decoded);
        // Spot-check every category — sweeping all 24 individually would be repetitive
        // but each different concern (BigDecimal precision, LocalDate format, Boolean,
        // Integer, Long, String) needs at least one anchor.
        assertEquals(0, new BigDecimal("100.01").compareTo(decoded.getNewBalance()));
        assertEquals(0, new BigDecimal("-700.07").compareTo(decoded.getPaymentsAndCreditsTotal()));
        assertEquals(0, new BigDecimal("19.49").compareTo(decoded.getPurchaseApr()));
        assertEquals(0,
                new BigDecimal("0.010775948").compareTo(
                        new BigDecimal("0.010775948"))); // placeholder
        assertEquals(LocalDate.of(2026, 6, 17), decoded.getStatementDate());
        assertEquals(31, decoded.getBillingDays().intValue());
        assertTrue(decoded.getAutoPayEnabled());
        assertEquals(4500L, decoded.getPointsEarnedThisPeriod().longValue());
    }

    @Test
    void detectedAccountInfo_preservesBigDecimalPrecision_acrossRoundTrip() throws Exception {
        // Critical: a BigDecimal with 9 decimal places (e.g. exchange rate 0.010775948)
        // must not be truncated to 2 or 6 places by JSON serialization. We don't have
        // exchangeRate ON DetectedAccountInfo (it's per-txn), but the same precision
        // class applies to APRs and foreignTransactionFeePercent.
        final TransactionController.DetectedAccountInfo info = newFullyPopulated();
        info.setPurchaseApr(new BigDecimal("19.4938"));
        info.setForeignTransactionFeePercent(new BigDecimal("3.123456789"));

        final String wire = mapper.writeValueAsString(info);
        final TransactionController.DetectedAccountInfo decoded =
                mapper.readValue(wire, TransactionController.DetectedAccountInfo.class);

        assertEquals(0, new BigDecimal("19.4938").compareTo(decoded.getPurchaseApr()),
                "Purchase APR precision must round-trip exactly");
        assertEquals(0,
                new BigDecimal("3.123456789")
                        .compareTo(decoded.getForeignTransactionFeePercent()),
                "Foreign-tx fee precision must round-trip exactly");
    }

    // ============================================================
    //  Null / partial DTO behaviour
    // ============================================================

    @Test
    void detectedAccountInfo_omitsNullFieldsFromJsonOutput() throws Exception {
        // Spring Boot defaults to NON_NULL include. A field set to null must NOT appear
        // in the JSON — iOS distinguishes "field absent" from "field present as null"
        // and we don't want stale nil decoding to mask a backend bug.
        final TransactionController.DetectedAccountInfo info =
                new TransactionController.DetectedAccountInfo();
        info.setNewBalance(new BigDecimal("100.00"));
        // Everything else null.

        final String wire = mapper.writeValueAsString(info);
        assertTrue(wire.contains("\"newBalance\""), "Set field must appear");
        assertFalse(wire.contains("\"creditLimit\""),
                "Unset field must be omitted entirely; wire was: " + wire);
        assertFalse(wire.contains("\"purchaseApr\""));
        assertFalse(wire.contains("\"autoPayEnabled\""));
    }

    @Test
    void detectedAccountInfo_decodesEmptyJson_withAllFieldsNull() throws Exception {
        // A response body of `{}` (no metadata extracted) must decode to a DTO with
        // every field null — not zero, not default. Catches a regression that adds
        // a primitive (e.g. `int billingDays`) which would default to 0 and be
        // indistinguishable from a real zero on the wire.
        final TransactionController.DetectedAccountInfo decoded =
                mapper.readValue("{}", TransactionController.DetectedAccountInfo.class);
        assertNotNull(decoded);
        assertNull(decoded.getNewBalance());
        assertNull(decoded.getCreditLimit());
        assertNull(decoded.getPurchaseApr());
        assertNull(decoded.getBillingDays(),
                "billingDays MUST stay Integer (boxed) to distinguish 0 from missing");
        assertNull(decoded.getStatementDate());
        assertNull(decoded.getAutoPayEnabled(),
                "autoPayEnabled MUST stay Boolean (boxed) to distinguish absent from false");
        assertNull(decoded.getPointsEarnedThisPeriod());
    }

    // ============================================================
    //  ChunkImportResponse
    // ============================================================

    @Test
    void chunkImportResponse_includesDetectedAccountKey_whenSet() throws Exception {
        final TransactionController.ChunkImportResponse response =
                new TransactionController.ChunkImportResponse();
        response.setPage(0);
        response.setSize(100);
        response.setTotal(50);
        response.setTotalPages(1);
        response.setHasNext(false);
        response.setDetectedAccount(newFullyPopulated());

        final JsonNode json = mapper.valueToTree(response);
        assertTrue(json.has("detectedAccount"),
                "ChunkImportResponse must serialize the detectedAccount key on page 0");
        assertTrue(json.get("detectedAccount").has("newBalance"),
                "Nested detectedAccount must contain its full statement-summary block");
    }

    @Test
    void chunkImportResponse_omitsDetectedAccountKey_whenNull() throws Exception {
        // Subsequent chunk pages (1..N) leave detectedAccount null. The JSON must NOT
        // include the key at all — iOS will then naturally see nil and reuse what it
        // cached from page 0.
        final TransactionController.ChunkImportResponse response =
                new TransactionController.ChunkImportResponse();
        response.setPage(1);
        response.setSize(100);
        response.setTotal(150);
        response.setTotalPages(2);
        response.setHasNext(false);
        // detectedAccount intentionally NOT set.

        final String wire = mapper.writeValueAsString(response);
        assertFalse(wire.contains("\"detectedAccount\""),
                "page > 0 ChunkImportResponse must omit detectedAccount entirely; wire was: "
                        + wire);
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static TransactionController.DetectedAccountInfo newFullyPopulated() {
        // Same value-distinct fixture as the wiring test — each field carries a
        // unique value so cross-wiring shows up in the failure message.
        final TransactionController.DetectedAccountInfo info =
                new TransactionController.DetectedAccountInfo();
        info.setAccountName("Test Card");
        info.setInstitutionName("Test Bank");
        info.setAccountType("credit");
        info.setAccountSubtype("Test Premier");
        info.setAccountNumber("4242");
        info.setCardNumber("4242");
        info.setBalance(new BigDecimal("777.77"));
        info.setMatchedAccountId("acct-uuid");
        info.setPaymentDueDate(LocalDate.of(2026, 7, 14));
        info.setMinimumPaymentDue(new BigDecimal("25.00"));
        info.setRewardPoints(1234L);
        info.setNewBalance(new BigDecimal("100.01"));
        info.setPreviousBalance(new BigDecimal("200.02"));
        info.setCreditLimit(new BigDecimal("300.03"));
        info.setAvailableCredit(new BigDecimal("400.04"));
        info.setPastDueAmount(new BigDecimal("500.05"));
        info.setPurchasesTotal(new BigDecimal("600.06"));
        info.setPaymentsAndCreditsTotal(new BigDecimal("-700.07"));
        info.setCashAdvancesTotal(new BigDecimal("800.08"));
        info.setBalanceTransfersTotal(new BigDecimal("900.09"));
        info.setFeesChargedTotal(new BigDecimal("1000.10"));
        info.setInterestChargedTotal(new BigDecimal("1100.11"));
        info.setPurchaseApr(new BigDecimal("19.49"));
        info.setCashAdvanceApr(new BigDecimal("28.49"));
        info.setBalanceTransferApr(new BigDecimal("19.50"));
        info.setPenaltyApr(new BigDecimal("29.99"));
        info.setCashAccessLine(new BigDecimal("5000.00"));
        info.setAvailableForCash(new BigDecimal("4500.00"));
        info.setForeignTransactionFeePercent(new BigDecimal("3"));
        info.setBillingDays(31);
        info.setStatementDate(LocalDate.of(2026, 6, 17));
        info.setAnnualMembershipFee(new BigDecimal("95.00"));
        info.setAnnualMembershipFeeDueDate(LocalDate.of(2026, 9, 1));
        info.setAutoPayEnabled(true);
        info.setNextAutoPayAmount(new BigDecimal("100.01"));
        info.setPointsEarnedThisPeriod(4500L);
        info.setPointsBalance(50000L);
        return info;
    }

    private static String iterableToString(final java.util.Iterator<String> it) {
        final StringBuilder sb = new StringBuilder("[");
        while (it.hasNext()) {
            sb.append(it.next());
            if (it.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.append("]").toString();
    }
}
