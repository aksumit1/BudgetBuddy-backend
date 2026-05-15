package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Wiring coverage for the static helpers that copy {@link
 * PDFImportService.ImportResult} into the API DTOs. The two helpers are the seam
 * between the parser and every PDF-import response (preview, chunk, full import) —
 * a regression here means a UI field that the parser correctly extracted silently
 * drops out of the wire response.
 *
 * <p>Specifically validates:
 *
 * <ol>
 *   <li>Null inputs are handled gracefully (no NPE, no partial state).
 *   <li>Every one of the 24 summary fields round-trips from {@link
 *       PDFImportService.ImportResult} to {@link
 *       TransactionController.DetectedAccountInfo} without truncation, format change,
 *       or omission.
 *   <li>Negative amounts (e.g. paymentsAndCreditsTotal) preserve their sign.
 *   <li>The chunk endpoint's "page 0 only" intent is honoured by the helper —
 *       calling the build path twice produces independent DTOs (no shared mutable
 *       state).
 *   <li>A partially-populated ImportResult (some fields null) produces a DTO with
 *       the same null pattern — null in => null out, no silent zero substitution.
 * </ol>
 */
class TransactionControllerWiringTest {

    // ---- null-safety ----

    @Test
    void buildDetectedAccountInfo_returnsNull_forNullImportResult() {
        // Defensive: caller passes null when no PDF was parsed. Helper must NOT throw —
        // it should hand back null so the caller can simply omit the detectedAccount
        // field from the response body.
        assertNull(
                TransactionController.buildDetectedAccountInfoFromImportResult(null),
                "null importResult must yield null DTO, not an empty DTO");
    }

    @Test
    void copyStatementSummary_isNoOp_whenEitherArgumentIsNull() {
        // Both null-arg paths must NOT throw — the helper is the kind of thing that
        // gets called from a finally block or an error path.
        TransactionController.copyStatementSummaryToAccountInfo(null, null);
        TransactionController.copyStatementSummaryToAccountInfo(
                new PDFImportService.ImportResult(), null);
        TransactionController.copyStatementSummaryToAccountInfo(
                null, new TransactionController.DetectedAccountInfo());
        // If we reach here, no NPE. Assert it explicitly for the test reporter.
        assertTrue(true, "All three null-arg permutations completed without throwing");
    }

    // ---- full round-trip ----

    @Test
    void buildDetectedAccountInfo_roundTripsEveryStatementSummaryField() {
        // A fully-populated ImportResult — every field set to a distinct value so we
        // can detect any cross-wiring (e.g. setNewBalance accidentally returns the
        // previousBalance value).
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();

        // Identity / header fields.
        final AccountDetectionService.DetectedAccount det =
                new AccountDetectionService.DetectedAccount();
        det.setAccountName("Test Card");
        det.setInstitutionName("Test Bank");
        det.setAccountType("credit");
        det.setAccountSubtype("Test Premier");
        det.setAccountNumber("4242");
        det.setCardNumber("4242");
        det.setBalance(new BigDecimal("777.77"));
        ir.setDetectedAccount(det);
        ir.setMatchedAccountId("acct-uuid-here");
        ir.setPaymentDueDate(LocalDate.of(2026, 7, 14));
        ir.setMinimumPaymentDue(new BigDecimal("25.00"));
        ir.setRewardPoints(1234L);

        // Statement summary block. Use distinct values throughout.
        ir.setNewBalance(new BigDecimal("100.01"));
        ir.setPreviousBalance(new BigDecimal("200.02"));
        ir.setCreditLimit(new BigDecimal("300.03"));
        ir.setAvailableCredit(new BigDecimal("400.04"));
        ir.setPastDueAmount(new BigDecimal("500.05"));
        ir.setPurchasesTotal(new BigDecimal("600.06"));
        ir.setPaymentsAndCreditsTotal(new BigDecimal("-700.07"));
        ir.setCashAdvancesTotal(new BigDecimal("800.08"));
        ir.setBalanceTransfersTotal(new BigDecimal("900.09"));
        ir.setFeesChargedTotal(new BigDecimal("1000.10"));
        ir.setInterestChargedTotal(new BigDecimal("1100.11"));
        ir.setPurchaseApr(new BigDecimal("19.49"));
        ir.setCashAdvanceApr(new BigDecimal("28.49"));
        ir.setBalanceTransferApr(new BigDecimal("19.50"));
        ir.setPenaltyApr(new BigDecimal("29.99"));
        ir.setCashAccessLine(new BigDecimal("5000.00"));
        ir.setAvailableForCash(new BigDecimal("4500.00"));
        ir.setForeignTransactionFeePercent(new BigDecimal("3"));
        ir.setBillingDays(31);
        ir.setStatementDate(LocalDate.of(2026, 6, 17));
        ir.setAnnualMembershipFee(new BigDecimal("95.00"));
        ir.setAnnualMembershipFeeDueDate(LocalDate.of(2026, 9, 1));
        ir.setAutoPayEnabled(true);
        ir.setNextAutoPayAmount(new BigDecimal("100.01"));
        ir.setPointsEarnedThisPeriod(4500L);
        ir.setPointsBalance(50000L);

        final TransactionController.DetectedAccountInfo info =
                TransactionController.buildDetectedAccountInfoFromImportResult(ir);
        assertNotNull(info, "Full ImportResult must produce a non-null DTO");

        // Identity round-trip.
        assertEquals("Test Card", info.getAccountName());
        assertEquals("Test Bank", info.getInstitutionName());
        assertEquals("credit", info.getAccountType());
        assertEquals("Test Premier", info.getAccountSubtype());
        assertEquals("4242", info.getAccountNumber());
        assertEquals("4242", info.getCardNumber());
        assertEquals(0, new BigDecimal("777.77").compareTo(info.getBalance()));
        assertEquals("acct-uuid-here", info.getMatchedAccountId());
        assertEquals(LocalDate.of(2026, 7, 14), info.getPaymentDueDate());
        assertEquals(0, new BigDecimal("25.00").compareTo(info.getMinimumPaymentDue()));
        assertEquals(1234L, info.getRewardPoints().longValue());

        // Statement summary block round-trip. Distinct values mean any cross-wired
        // setter will fail with a specific "expected X but was Y" diagnostic.
        assertEquals(0, new BigDecimal("100.01").compareTo(info.getNewBalance()));
        assertEquals(0, new BigDecimal("200.02").compareTo(info.getPreviousBalance()));
        assertEquals(0, new BigDecimal("300.03").compareTo(info.getCreditLimit()));
        assertEquals(0, new BigDecimal("400.04").compareTo(info.getAvailableCredit()));
        assertEquals(0, new BigDecimal("500.05").compareTo(info.getPastDueAmount()));
        assertEquals(0, new BigDecimal("600.06").compareTo(info.getPurchasesTotal()));
        assertEquals(0,
                new BigDecimal("-700.07").compareTo(info.getPaymentsAndCreditsTotal()),
                "Negative payment totals must preserve their sign through the copy");
        assertEquals(0, new BigDecimal("800.08").compareTo(info.getCashAdvancesTotal()));
        assertEquals(0, new BigDecimal("900.09").compareTo(info.getBalanceTransfersTotal()));
        assertEquals(0, new BigDecimal("1000.10").compareTo(info.getFeesChargedTotal()));
        assertEquals(0, new BigDecimal("1100.11").compareTo(info.getInterestChargedTotal()));
        assertEquals(0, new BigDecimal("19.49").compareTo(info.getPurchaseApr()));
        assertEquals(0, new BigDecimal("28.49").compareTo(info.getCashAdvanceApr()));
        assertEquals(0,
                new BigDecimal("19.50").compareTo(info.getBalanceTransferApr()),
                "Balance-transfer APR must NOT be confused with purchase APR");
        assertEquals(0, new BigDecimal("29.99").compareTo(info.getPenaltyApr()));
        assertEquals(0, new BigDecimal("5000.00").compareTo(info.getCashAccessLine()));
        assertEquals(0, new BigDecimal("4500.00").compareTo(info.getAvailableForCash()));
        assertEquals(0,
                new BigDecimal("3").compareTo(info.getForeignTransactionFeePercent()));
        assertEquals(31, info.getBillingDays().intValue());
        assertEquals(LocalDate.of(2026, 6, 17), info.getStatementDate());
        assertEquals(0, new BigDecimal("95.00").compareTo(info.getAnnualMembershipFee()));
        assertEquals(LocalDate.of(2026, 9, 1), info.getAnnualMembershipFeeDueDate());
        assertTrue(info.getAutoPayEnabled());
        assertEquals(0, new BigDecimal("100.01").compareTo(info.getNextAutoPayAmount()));
        assertEquals(4500L, info.getPointsEarnedThisPeriod().longValue());
        assertEquals(50000L, info.getPointsBalance().longValue());
    }

    // ---- partial / null-pattern preservation ----

    @Test
    void buildDetectedAccountInfo_preservesNullPattern_whenSomeFieldsAreUnset() {
        // A typical "header-only" ImportResult: only paymentDueDate set. Every other
        // summary field must come back null — the helper must NOT substitute zeros or
        // other defaults (otherwise iOS shows "Credit limit: $0" when the statement
        // simply didn't print one).
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setPaymentDueDate(LocalDate.of(2026, 7, 14));

        final TransactionController.DetectedAccountInfo info =
                TransactionController.buildDetectedAccountInfoFromImportResult(ir);
        assertNotNull(info);
        assertEquals(LocalDate.of(2026, 7, 14), info.getPaymentDueDate());

        assertNull(info.getNewBalance());
        assertNull(info.getCreditLimit());
        assertNull(info.getPurchaseApr());
        assertNull(info.getAutoPayEnabled());
        assertNull(info.getPointsEarnedThisPeriod());
        assertNull(info.getForeignTransactionFeePercent());
        assertNull(info.getStatementDate());
    }

    @Test
    void buildDetectedAccountInfo_returnsDtoWithIdentityNull_whenNoDetectedAccount() {
        // ImportResult had only summary block, no detected account (e.g. a PDF whose
        // institution we couldn't recognise but whose balance/limit fields are
        // unambiguous). The DTO should carry the summary and leave identity null.
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setNewBalance(new BigDecimal("250.00"));
        ir.setCreditLimit(new BigDecimal("10000"));

        final TransactionController.DetectedAccountInfo info =
                TransactionController.buildDetectedAccountInfoFromImportResult(ir);
        assertNotNull(info);
        assertNull(info.getAccountName());
        assertNull(info.getInstitutionName());
        assertNull(info.getAccountNumber());
        assertEquals(0, new BigDecimal("250.00").compareTo(info.getNewBalance()));
        assertEquals(0, new BigDecimal("10000").compareTo(info.getCreditLimit()));
    }

    // ---- independent DTO instances (no shared mutable state) ----

    @Test
    void buildDetectedAccountInfo_returnsIndependentInstances_acrossCalls() {
        // Critical for the chunk endpoint: page 0 builds a DTO and may be retried by
        // the client (e.g. network blip). Each call must produce a fresh DTO — a
        // shared mutable singleton would have a race where two concurrent requests
        // see each other's data. Pin this explicitly.
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setNewBalance(new BigDecimal("123.45"));

        final TransactionController.DetectedAccountInfo a =
                TransactionController.buildDetectedAccountInfoFromImportResult(ir);
        final TransactionController.DetectedAccountInfo b =
                TransactionController.buildDetectedAccountInfoFromImportResult(ir);

        assertFalse(a == b, "Each call must return a new DetectedAccountInfo instance");

        // Mutating one must NOT affect the other.
        a.setNewBalance(new BigDecimal("999.99"));
        assertEquals(0,
                new BigDecimal("123.45").compareTo(b.getNewBalance()),
                "DTOs must be independent — mutating one must not leak into the other");
    }

    @Test
    void copyStatementSummary_onlyOverwritesFieldsItOwns_leavingIdentityAlone() {
        // The chunk endpoint may call copy on a DTO that already had identity fields
        // populated by some upstream step. The copy must overwrite the summary block
        // but NOT touch identity (accountName, institutionName, etc.) — otherwise the
        // matched-account flow would overwrite the right account with a parser guess.
        final TransactionController.DetectedAccountInfo info =
                new TransactionController.DetectedAccountInfo();
        info.setAccountName("Original Account");
        info.setInstitutionName("Original Bank");
        info.setAccountNumber("9999");

        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setNewBalance(new BigDecimal("50.00"));
        // Note: ir's DetectedAccount intentionally NOT set — the copy helper must
        // touch only the summary fields, not the identity ones.

        TransactionController.copyStatementSummaryToAccountInfo(ir, info);

        // Identity preserved.
        assertEquals("Original Account", info.getAccountName());
        assertEquals("Original Bank", info.getInstitutionName());
        assertEquals("9999", info.getAccountNumber());
        // Summary populated.
        assertEquals(0, new BigDecimal("50.00").compareTo(info.getNewBalance()));
    }
}
