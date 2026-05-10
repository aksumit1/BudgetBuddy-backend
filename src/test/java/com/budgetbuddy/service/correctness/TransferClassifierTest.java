package com.budgetbuddy.service.correctness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import org.junit.jupiter.api.Test;

/**
 * Tests pinning the transfer-vs-real-transaction distinction.
 *
 * <p>The cost of a false positive (paycheck misclassified as transfer) and a false negative
 * (savings sweep counted as income) is asymmetric: the false positive silently erases a paycheck
 * from cash-flow projections, which is catastrophic. These tests bias toward letting borderline
 * cases through rather than being over-eager.
 */
class TransferClassifierTest {

    @Test
    void plaidRawPrimaryTransferInIsAuthoritative() {
        // The classifier's most reliable path: raw Plaid taxonomy from the
        // personal_finance_category field, preserved on ingest into
        // importerCategoryPrimary. This is an *exact* match — no substring
        // false positives like "TRANSFER_IN" matching "TRANSFERable".
        final TransactionTable t = new TransactionTable();
        t.setImporterCategoryPrimary("TRANSFER_IN");
        assertTrue(TransferClassifier.isTransfer(t));
    }

    @Test
    void plaidDetailCreditCardPaymentIsTransfer() {
        // The classic double-count case. The user's CC payment of $500 from
        // checking reduces the CC liability *and* the checking balance; if
        // we count it as an expense the cash-flow view thinks $1000 left
        // the user's balance sheet.
        final TransactionTable t = new TransactionTable();
        t.setImporterCategoryDetailed("LOAN_PAYMENTS_CREDIT_CARD_PAYMENT");
        assertTrue(TransferClassifier.isTransfer(t));
    }

    @Test
    void paycheckFromRawPlaidIncomeIsNotTransfer() {
        // Plaid's INCOME_WAGES is the canonical paycheck category. Must NOT
        // be flagged as a transfer — that would erase user income from cash
        // flow.
        final TransactionTable t = new TransactionTable();
        t.setImporterCategoryPrimary("INCOME");
        t.setImporterCategoryDetailed("INCOME_WAGES");
        t.setMerchantName("ACME CORP PAYROLL");
        assertFalse(TransferClassifier.isTransfer(t));
    }

    @Test
    void heuristicFallbackCatchesZelleCashout() {
        // No raw Plaid category (CSV or manual import). Merchant name is
        // all we have to work with. "zelle" is a strong signal — typically
        // peer-to-peer transfers between the user's own accounts.
        final TransactionTable t = new TransactionTable();
        t.setMerchantName("ZELLE Payment");
        assertTrue(TransferClassifier.isTransfer(t));
    }

    @Test
    void heuristicFallbackCatchesInternalTransferInDescription() {
        final TransactionTable t = new TransactionTable();
        t.setDescription("Internal transfer to Savings");
        assertTrue(TransferClassifier.isTransfer(t));
    }

    @Test
    void unrelatedMerchantWithTransferWordInNameIsNotFlagged() {
        // "Transfer Station" is a garbage dump service in the US, not a
        // financial transfer. The classifier's merchant phrase list is
        // specific ("transfer from", "transfer to", "internal transfer") —
        // a lone "transfer" substring is NOT enough to flag. This guards
        // against the false-positive-paycheck drift we're most afraid of.
        final TransactionTable t = new TransactionTable();
        t.setMerchantName("Transfer Station Parking");
        assertFalse(TransferClassifier.isTransfer(t));
    }

    @Test
    void nullTransactionIsNotTransfer() {
        // Defensive: the detector is called in hot loops that might see a
        // null row (legacy data, race with a delete). Null must not throw.
        assertFalse(TransferClassifier.isTransfer(null));
    }

    @Test
    void emptyTransactionIsNotTransfer() {
        // All fields null — the detector must return false, not match on
        // "nothing" which would flag every ambiguous import as a transfer.
        final TransactionTable t = new TransactionTable();
        assertFalse(TransferClassifier.isTransfer(t));
    }
}
