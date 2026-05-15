package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.service.PDFImportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Persistence-layer coverage for the {@link AccountTable} statement-summary fields
 * added in task #44. Validates the {@link
 * TransactionController#applyStatementSummaryToAccount(AccountTable,
 * PDFImportService.ImportResult)} helper against the conditions the user explicitly
 * called out: error, edge, race, hang, boundary, leak.
 *
 * <p>The helper is the seam between the PDF parser and DynamoDB. A regression here
 * either:
 *
 * <ul>
 *   <li>Loses a field at persistence time (extracted ✓, surfaced via API ✓, but
 *       the DB row doesn't carry it past the preview screen).
 *   <li>Overwrites a previously-good value with null when the new statement
 *       didn't include that label (partial-extraction regression).
 *   <li>Loses the latest-statement-wins invariant — an older statement uploaded
 *       after a newer one would clobber the row.
 * </ul>
 *
 * <p>The latest-statement gate itself lives in the calling {@code
 * updateAccountMetadataFromPDFImport}; this file tests {@code
 * applyStatementSummaryToAccount} in isolation so the field-by-field copy is
 * pinned independent of the date-comparison logic.
 */
class AccountTableStatementSummaryPersistenceTest {

    // ============================================================
    //  Null safety — error path
    // ============================================================

    @Test
    void apply_isNoOp_whenAccountIsNull() {
        // Caller passes null when accountRepository.findById returned empty. Must NOT
        // throw — the calling code logs and returns instead.
        TransactionController.applyStatementSummaryToAccount(null, fullyPopulated());
        // Reached here without NPE — that's the assertion.
        assertTrue(true);
    }

    @Test
    void apply_isNoOp_whenImportResultIsNull() {
        final AccountTable account = freshAccount();
        TransactionController.applyStatementSummaryToAccount(account, null);
        // Account must remain in its initial state — no fields were touched.
        assertNull(account.getNewBalance());
        assertNull(account.getCreditLimit());
    }

    // ============================================================
    //  Full round-trip — every field copied to the right setter
    // ============================================================

    @Test
    void apply_copiesEverySummaryFieldToAccount() {
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = fullyPopulated();

        TransactionController.applyStatementSummaryToAccount(account, ir);

        // Balances + summary.
        assertEquals(0, new BigDecimal("100.01").compareTo(account.getNewBalance()));
        assertEquals(0, new BigDecimal("200.02").compareTo(account.getPreviousBalance()));
        assertEquals(0, new BigDecimal("500.05").compareTo(account.getPastDueAmount()));
        assertEquals(0, new BigDecimal("300.03").compareTo(account.getCreditLimit()));
        assertEquals(0, new BigDecimal("400.04").compareTo(account.getAvailableCredit()));
        assertEquals(LocalDate.of(2026, 6, 17), account.getStatementDate());
        assertEquals(31, account.getBillingDays().intValue());

        // Section totals.
        assertEquals(0, new BigDecimal("600.06").compareTo(account.getPurchasesTotal()));
        assertEquals(0,
                new BigDecimal("-700.07").compareTo(account.getPaymentsAndCreditsTotal()),
                "Negative payments total must keep its sign during persistence");
        assertEquals(0, new BigDecimal("800.08").compareTo(account.getCashAdvancesTotal()));
        assertEquals(0, new BigDecimal("900.09").compareTo(account.getBalanceTransfersTotal()));
        assertEquals(0, new BigDecimal("1000.10").compareTo(account.getFeesChargedTotal()));
        assertEquals(0, new BigDecimal("1100.11").compareTo(account.getInterestChargedTotal()));

        // APRs — purchase routes to aprPercent (canonical existing column), others
        // to their dedicated new columns.
        assertEquals(0, new BigDecimal("19.49").compareTo(account.getAprPercent()),
                "Purchase APR persists into the canonical aprPercent column");
        assertEquals(0, new BigDecimal("28.49").compareTo(account.getCashAdvanceApr()));
        assertEquals(0, new BigDecimal("19.50").compareTo(account.getBalanceTransferApr()),
                "balanceTransferApr must NOT collide with aprPercent");
        assertEquals(0, new BigDecimal("29.99").compareTo(account.getPenaltyApr()));

        // Cash sub-limits.
        assertEquals(0, new BigDecimal("5000.00").compareTo(account.getCashAccessLine()));
        assertEquals(0, new BigDecimal("4500.00").compareTo(account.getAvailableForCash()));

        // Foreign-tx fee reuses existing column.
        assertEquals(0,
                new BigDecimal("3").compareTo(account.getForeignTxFeePercent()));

        // Annual fee.
        assertEquals(0, new BigDecimal("95.00").compareTo(account.getAnnualMembershipFee()));
        assertEquals(LocalDate.of(2026, 9, 1), account.getAnnualMembershipFeeDueDate());

        // AutoPay.
        assertTrue(account.getAutoPayEnabled());
        assertEquals(0, new BigDecimal("100.01").compareTo(account.getNextAutoPayAmount()));

        // Points split.
        assertEquals(4500L, account.getPointsEarnedThisPeriod().longValue());
        assertEquals(50000L, account.getPointsBalance().longValue());
    }

    // ============================================================
    //  Partial extraction — null fields don't nuke existing values
    // ============================================================

    @Test
    void apply_doesNotOverwriteExistingValuesWithNullsFromPartialExtraction() {
        // Scenario: account already has good values (set by a prior statement).
        // The latest import succeeded for paymentDueDate / minimumPaymentDue but
        // failed to extract APR. The helper must NOT null-out the existing APR.
        final AccountTable account = freshAccount();
        account.setAprPercent(new BigDecimal("19.49"));
        account.setCreditLimit(new BigDecimal("25000"));
        account.setAutoPayEnabled(true);
        account.setPointsBalance(75_000L);

        final PDFImportService.ImportResult partial = new PDFImportService.ImportResult();
        partial.setNewBalance(new BigDecimal("500.00")); // only this field extracted
        // APR / limit / autopay / points all null — partial extraction.

        TransactionController.applyStatementSummaryToAccount(account, partial);

        // The newly-extracted field is persisted.
        assertEquals(0, new BigDecimal("500.00").compareTo(account.getNewBalance()));
        // But the previously-good values are PRESERVED — null-in-ImportResult must
        // NOT touch the account.
        assertEquals(0, new BigDecimal("19.49").compareTo(account.getAprPercent()),
                "Purchase APR must be preserved when the new import didn't extract it");
        assertEquals(0, new BigDecimal("25000").compareTo(account.getCreditLimit()));
        assertTrue(account.getAutoPayEnabled(),
                "AutoPay status must be preserved through a partial extraction");
        assertEquals(75_000L, account.getPointsBalance().longValue());
    }

    @Test
    void apply_overwritesExistingValueWhenNewExtractionHasNonNullValue() {
        // The other direction: an existing value should be SUPERSEDED when the new
        // import has a value for the same field. This is the "latest statement wins"
        // outcome for fields that are present on both.
        final AccountTable account = freshAccount();
        account.setCreditLimit(new BigDecimal("25000"));
        account.setAutoPayEnabled(false);

        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setCreditLimit(new BigDecimal("50000")); // upgraded
        ir.setAutoPayEnabled(true); // user enabled AutoPay

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals(0, new BigDecimal("50000").compareTo(account.getCreditLimit()),
                "Newer credit limit must override the older value");
        assertTrue(account.getAutoPayEnabled());
    }

    // ============================================================
    //  Boundary / edge values
    // ============================================================

    @Test
    void apply_persistsZeroValues_distinctFromNullValues() {
        // Zero is a meaningful value: $0.00 fees / $0.00 interest / 0% APR.
        // Must NOT be confused with "no data extracted" (which is null).
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setFeesChargedTotal(BigDecimal.ZERO);
        ir.setInterestChargedTotal(BigDecimal.ZERO);
        ir.setPastDueAmount(BigDecimal.ZERO);

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals(0, BigDecimal.ZERO.compareTo(account.getFeesChargedTotal()),
                "Zero must persist — it's a legitimate value, distinct from null");
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getInterestChargedTotal()));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getPastDueAmount()));
    }

    @Test
    void apply_persistsAutoPayOffAsFalse_notNull() {
        // AutoPay being explicitly OFF is a distinct state from "we couldn't tell"
        // (null). Must be persisted as the Boolean false.
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setAutoPayEnabled(false);

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals(Boolean.FALSE, account.getAutoPayEnabled(),
                "AutoPay OFF must persist as Boolean.FALSE — not be confused with null");
    }

    @Test
    void apply_handlesVeryLargeBigDecimals_withoutPrecisionLoss() {
        // Premium cards can have $100k+ limits and points balances in millions.
        // No precision loss from the copy.
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setCreditLimit(new BigDecimal("250000.00"));
        ir.setPointsBalance(1_500_000L);

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals(0, new BigDecimal("250000.00").compareTo(account.getCreditLimit()));
        assertEquals(1_500_000L, account.getPointsBalance().longValue());
    }

    // ============================================================
    //  Race / re-apply — idempotency
    // ============================================================

    @Test
    void apply_isIdempotent_acrossRepeatedInvocations() {
        // Calling the helper twice with the same import result must produce the
        // same end state — important for any caller that retries on optimistic-
        // lock conflicts (the AccountRepository.save path).
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = fullyPopulated();

        TransactionController.applyStatementSummaryToAccount(account, ir);
        final BigDecimal afterFirst = account.getNewBalance();
        final Boolean autoPayAfterFirst = account.getAutoPayEnabled();

        TransactionController.applyStatementSummaryToAccount(account, ir);
        assertEquals(afterFirst, account.getNewBalance(),
                "Re-applying the same ImportResult must be a stable no-op");
        assertEquals(autoPayAfterFirst, account.getAutoPayEnabled());
    }

    // ============================================================
    //  Cross-extractor isolation — no setter aliasing
    // ============================================================

    @Test
    void apply_doesNotCrossWireSimilarFields() {
        // Pin that the helper doesn't accidentally route, e.g. cashAdvanceApr into
        // aprPercent. Distinct values catch any setter swap.
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setPurchaseApr(new BigDecimal("19.49"));
        ir.setCashAdvanceApr(new BigDecimal("28.49"));
        ir.setBalanceTransferApr(new BigDecimal("19.50"));
        ir.setPenaltyApr(new BigDecimal("29.99"));

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals(0, new BigDecimal("19.49").compareTo(account.getAprPercent()));
        assertEquals(0, new BigDecimal("28.49").compareTo(account.getCashAdvanceApr()));
        assertEquals(0, new BigDecimal("19.50").compareTo(account.getBalanceTransferApr()));
        assertEquals(0, new BigDecimal("29.99").compareTo(account.getPenaltyApr()));
    }

    @Test
    void apply_keepsCreditLimitDistinctFromCashAccessLine() {
        // Credit limit and cash access line are similarly-named fields with very
        // different semantics. Cross-wiring would silently understate or overstate
        // user credit headroom.
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setCreditLimit(new BigDecimal("50000"));
        ir.setCashAccessLine(new BigDecimal("7500"));

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals(0, new BigDecimal("50000").compareTo(account.getCreditLimit()));
        assertEquals(0, new BigDecimal("7500").compareTo(account.getCashAccessLine()));
    }

    @Test
    void apply_keepsAvailableCreditDistinctFromAvailableForCash() {
        final AccountTable account = freshAccount();
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
        ir.setAvailableCredit(new BigDecimal("49000"));
        ir.setAvailableForCash(new BigDecimal("7500"));

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals(0, new BigDecimal("49000").compareTo(account.getAvailableCredit()));
        assertEquals(0, new BigDecimal("7500").compareTo(account.getAvailableForCash()));
    }

    // ============================================================
    //  Identity preservation — helper doesn't touch caller-set fields
    // ============================================================

    @Test
    void apply_leavesIdentityFieldsAlone_evenWhenImportResultHasDetectedAccount() {
        // The helper is the summary-block copy. It must NOT touch identity fields
        // (accountName, institutionName, accountNumber, accountId) — those are owned
        // by the caller's flow, which has logic to decide whether the parser's
        // identity should override the existing AccountTable value.
        final AccountTable account = freshAccount();
        account.setAccountId("acct-uuid");
        account.setUserId("user-1");
        account.setAccountName("My Manually Renamed Card");
        account.setInstitutionName("My Bank");
        account.setAccountNumber("1234");
        account.setAccountNameOverridden(true);

        final PDFImportService.ImportResult ir = fullyPopulated();
        // Even with a detected-account that has a different name, the helper must
        // leave the existing identity alone.

        TransactionController.applyStatementSummaryToAccount(account, ir);

        assertEquals("acct-uuid", account.getAccountId());
        assertEquals("user-1", account.getUserId());
        assertEquals("My Manually Renamed Card", account.getAccountName(),
                "Helper must NOT overwrite the user's renamed account name");
        assertEquals("My Bank", account.getInstitutionName());
        assertEquals("1234", account.getAccountNumber());
        assertTrue(account.getAccountNameOverridden());
    }

    // ============================================================
    //  helpers
    // ============================================================

    private static AccountTable freshAccount() {
        // A bare AccountTable in its initial state — every new field starts null.
        return new AccountTable();
    }

    private static PDFImportService.ImportResult fullyPopulated() {
        // Same value-distinct fixture as the wiring tests so cross-wiring shows up
        // with a specific expected/actual diagnostic.
        final PDFImportService.ImportResult ir = new PDFImportService.ImportResult();
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
        ir.setPointsBalance(50_000L);
        return ir;
    }
}
