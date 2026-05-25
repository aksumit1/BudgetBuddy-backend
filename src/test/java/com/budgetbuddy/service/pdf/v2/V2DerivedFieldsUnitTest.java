package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the descriptor-derived helpers added during the
 * per-product audit fix:
 *
 * <ul>
 *   <li>{@code derivePaymentChannel} — descriptor-pattern → channel mapping.
 *       Per-product integration test asserts coverage; this test pins the
 *       specific input → output mapping so a future edit can't silently
 *       reclassify ACH rows as wallet, etc.</li>
 *   <li>{@code statementMentionsNoAnnualFee} — detection of "No Annual Fee"
 *       disclosure so the BigDecimal.ZERO default kicks in. Without this
 *       the field is null and analytics can't distinguish "card has no
 *       fee" from "we failed to extract".</li>
 *   <li>{@code extractPhone} ACH-identifier exclusion — PPD/Web/Tel/CCD ID
 *       rows have 10-digit IDs that look like phones but aren't. The
 *       integration test asserts the contract on real corpus rows; this
 *       test pins the exact regex behavior so a future loosening would
 *       fire here first.</li>
 * </ul>
 */
class V2DerivedFieldsUnitTest {

    private static final PDFImportService SVC = TestPdfImportFactory.newSvc();

    private static String derivePaymentChannel(final ParsedTransaction tx) throws Exception {
        final Method m = PDFImportService.class.getDeclaredMethod(
                "derivePaymentChannel", ParsedTransaction.class);
        m.setAccessible(true);
        return (String) m.invoke(SVC, tx);
    }

    private static boolean noAnnualFee(final String[] lines) throws Exception {
        final Method m = PDFImportService.class.getDeclaredMethod(
                "statementMentionsNoAnnualFee", String[].class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, (Object) lines);
    }

    private static String extractPhone(final String text) throws Exception {
        final Method m = PDFImportService.class.getDeclaredMethod(
                "extractPhone", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, text);
    }

    private static ParsedTransaction debit(final String desc) {
        final ParsedTransaction t = new ParsedTransaction();
        t.setDate(LocalDate.of(2026, 5, 1));
        t.setDescription(desc);
        t.setAmount(new BigDecimal("10.00").negate());
        t.setFlowDirection(FlowDirection.DEBIT);
        return t;
    }

    private static ParsedTransaction credit(final String desc) {
        final ParsedTransaction t = debit(desc);
        t.setAmount(new BigDecimal("10.00"));
        t.setFlowDirection(FlowDirection.CREDIT);
        return t;
    }

    // ====================================================================
    //  derivePaymentChannel — descriptor pattern mapping
    // ====================================================================

    @Test void ach_ppdId() throws Exception {
        assertEquals("ach", derivePaymentChannel(debit(
                "Morgan Stanley   ACH Credit                 PPD ID: 6427014001")));
    }

    @Test void ach_webId() throws Exception {
        assertEquals("ach", derivePaymentChannel(debit(
                "Amazon.Com Svcs  Payroll                    Web ID: 9111111103")));
    }

    @Test void ach_telId() throws Exception {
        assertEquals("ach", derivePaymentChannel(debit(
                "City of Bellevue Utility    6523060         Tel ID: 0000063576")));
    }

    @Test void ach_autopay() throws Exception {
        assertEquals("ach", derivePaymentChannel(debit("AUTOPAY THANK YOU")));
        assertEquals("ach", derivePaymentChannel(debit("AUTOMATIC PAYMENT")));
    }

    @Test void ach_billpay() throws Exception {
        assertEquals("ach", derivePaymentChannel(debit(
                "Puget Sound Ener Billpay                    PPD ID: 0000000160")));
    }

    @Test void ach_directpay() throws Exception {
        assertEquals("ach", derivePaymentChannel(debit("DIRECTPAY FULL BALANCE")));
    }

    @Test void wire_transfer() throws Exception {
        assertEquals("wire", derivePaymentChannel(debit("WIRE TRANSFER TO RECIPIENT")));
    }

    @Test void p2p_zelle() throws Exception {
        assertEquals("p2p_transfer", derivePaymentChannel(debit("ZELLE PAYMENT TO JOHN")));
    }

    @Test void p2p_venmo() throws Exception {
        assertEquals("p2p_transfer", derivePaymentChannel(debit("VENMO*JANE DOE")));
    }

    @Test void wallet_apple_pay_prefix() throws Exception {
        assertEquals("online_wallet", derivePaymentChannel(debit(
                "AplPay STARBUCKS BELLEVUE WA")));
    }

    @Test void wallet_square_prefix() throws Exception {
        assertEquals("online_wallet", derivePaymentChannel(debit(
                "SQ *BLUE BOTTLE SAN FRANCISCO CA")));
    }

    @Test void wallet_toast_prefix() throws Exception {
        assertEquals("online_wallet", derivePaymentChannel(debit(
                "TST* SALT & STRAW KIRKLAND WA")));
    }

    @Test void wallet_explicit_walletProvider() throws Exception {
        final ParsedTransaction t = debit("STARBUCKS BELLEVUE WA");
        t.setWalletProvider("apple-pay");
        assertEquals("online_wallet", derivePaymentChannel(t));
    }

    @Test void atm_withdrawal() throws Exception {
        assertEquals("atm", derivePaymentChannel(debit("ATM WITHDRAWAL #1234")));
    }

    @Test void check_paid() throws Exception {
        assertEquals("check", derivePaymentChannel(debit("CHECK 185 PAID")));
    }

    @Test void issuer_payment_thankYou() throws Exception {
        // Multi-space variant ("MTC PAYMENT   THANK YOU") — must normalize.
        assertEquals("issuer_payment", derivePaymentChannel(credit("MTC PAYMENT   THANK YOU")));
        assertEquals("issuer_payment", derivePaymentChannel(credit("AUTOMATIC PAYMENT - THANK YOU")));
    }

    @Test void issuer_credit_amexPlatinumMR() throws Exception {
        // Amex Membership Rewards statement-credit row.
        assertEquals("issuer_credit", derivePaymentChannel(credit(
                "AGARWAL SUMIT KUMAR Platinum Digital Entertainment Credit GOOGLE *YOUTUBE MUSIC")));
    }

    @Test void online_url_in_descriptor() throws Exception {
        assertEquals("online", derivePaymentChannel(debit("NETFLIX.COM LOS GATOS CA")));
    }

    @Test void online_amazon_prefix() throws Exception {
        assertEquals("online", derivePaymentChannel(debit("AMZN MKTP US*A1B2C3D4")));
    }

    @Test void in_store_credit_card_purchase_with_location() throws Exception {
        final ParsedTransaction t = debit("STARBUCKS BELLEVUE WA");
        t.setLocation("Bellevue, WA");
        assertEquals("in_store", derivePaymentChannel(t));
    }

    @Test void issuer_internal_fee_row() throws Exception {
        final ParsedTransaction t = debit("ANNUAL MEMBERSHIP FEE");
        t.setTransactionType("FEE");
        assertEquals("issuer_internal", derivePaymentChannel(t));
    }

    @Test void issuer_internal_interest() throws Exception {
        final ParsedTransaction t = debit("INTEREST CHARGE ON PURCHASES");
        t.setTransactionType("INTEREST");
        assertEquals("issuer_internal", derivePaymentChannel(t));
    }

    @Test void unknown_lastResort() throws Exception {
        // No pattern matches, no location, no type — final fallback.
        final ParsedTransaction t = debit("UNKNOWN MERCHANT XYZ");
        assertEquals("unknown", derivePaymentChannel(t));
    }

    @Test void nullInput_returnsNull() throws Exception {
        assertNull(derivePaymentChannel(null));
        final ParsedTransaction blank = new ParsedTransaction();
        blank.setDescription("");
        assertNull(derivePaymentChannel(blank));
    }

    // ====================================================================
    //  statementMentionsNoAnnualFee
    // ====================================================================

    @Test void noAnnualFee_explicitPhrase() throws Exception {
        assertEquals(true, noAnnualFee(new String[]{
                "Annual Fee", "No Annual Fee", "Foreign Transaction Fee: 3%"}));
    }

    @Test void noAnnualFee_zeroVariant() throws Exception {
        assertEquals(true, noAnnualFee(new String[]{
                "ANNUAL FEE: $0", "Other disclosure"}));
        assertEquals(true, noAnnualFee(new String[]{
                "Annual Fee $0.00", "Other"}));
    }

    @Test void noAnnualFee_realCardWithFee_returnsFalse() throws Exception {
        // Amex Platinum charges $695/yr.
        assertEquals(false, noAnnualFee(new String[]{
                "ANNUAL MEMBERSHIP FEE: $695.00",
                "Foreign Transaction Fee: None"}));
    }

    @Test void noAnnualFee_nullSafe() throws Exception {
        assertEquals(false, noAnnualFee(null));
        assertEquals(false, noAnnualFee(new String[]{}));
        assertEquals(false, noAnnualFee(new String[]{null, "  ", ""}));
    }

    // ====================================================================
    //  extractPhone — ACH-identifier exclusion contract
    // ====================================================================

    @Test void extractPhone_realPhoneStillExtracts() throws Exception {
        assertEquals("8005551212", extractPhone("STARBUCKS 800-555-1212 SEATTLE WA"));
    }

    @Test void extractPhone_ppdIdRejected() throws Exception {
        assertNull(extractPhone("Morgan Stanley   ACH Credit  PPD ID: 6427014001"),
                "PPD ID row must NOT yield a phone");
    }

    @Test void extractPhone_webIdRejected() throws Exception {
        assertNull(extractPhone("Amazon.Com Svcs  Payroll  Web ID: 9111111103"));
    }

    @Test void extractPhone_telIdRejected() throws Exception {
        assertNull(extractPhone("City of Bellevue Utility  Tel ID: 0000063576"));
    }

    @Test void extractPhone_achPmtRejected() throws Exception {
        assertNull(extractPhone("American Express ACH Pmt    A4822  Web ID: 2005032111"));
    }

    @Test void extractPhone_achCreditRejected() throws Exception {
        assertNull(extractPhone("Morgan Stanley   ACH Credit  6427014001"));
    }

    @Test void extractPhone_blankInputs() throws Exception {
        assertNull(extractPhone(null));
        assertNull(extractPhone(""));
        assertNull(extractPhone("    "));
    }
}
