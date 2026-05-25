package com.budgetbuddy.service.pdf.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Sister test of V2DerivedFieldsUnitTest, pinning the same input → output
 * mapping on the extracted PdfDerivedFields class. Catches the regression
 * where a future fix updates the original location and not the extracted
 * one (or vice versa).
 */
class PdfDerivedFieldsTest {

    private static ParsedTransaction debit(final String desc) {
        final ParsedTransaction t = new ParsedTransaction();
        t.setDate(LocalDate.of(2026, 5, 1));
        t.setDescription(desc);
        t.setAmount(new BigDecimal("10.00").negate());
        t.setFlowDirection(FlowDirection.DEBIT);
        return t;
    }

    @Test void ach() {
        assertEquals("ach", PdfDerivedFields.derivePaymentChannel(
                debit("Morgan Stanley ACH Credit PPD ID: 6427014001")));
    }

    @Test void issuerPayment() {
        assertEquals("issuer_payment",
                PdfDerivedFields.derivePaymentChannel(debit("MTC PAYMENT   THANK YOU")));
    }

    @Test void wallet() {
        assertEquals("online_wallet",
                PdfDerivedFields.derivePaymentChannel(debit("AplPay STARBUCKS BELLEVUE WA")));
    }

    @Test void atm() {
        assertEquals("atm",
                PdfDerivedFields.derivePaymentChannel(debit("ATM WITHDRAWAL #1234")));
    }

    @Test void check() {
        assertEquals("check",
                PdfDerivedFields.derivePaymentChannel(debit("CHECK 185 PAID")));
    }

    @Test void p2p() {
        assertEquals("p2p_transfer",
                PdfDerivedFields.derivePaymentChannel(debit("ZELLE PAYMENT TO JOHN")));
    }

    @Test void online() {
        assertEquals("online",
                PdfDerivedFields.derivePaymentChannel(debit("AMZN MKTP US*A1B2C3")));
    }

    @Test void inStoreDefault() {
        final ParsedTransaction t = debit("STARBUCKS BELLEVUE WA");
        t.setLocation("Bellevue, WA");
        assertEquals("in_store", PdfDerivedFields.derivePaymentChannel(t));
    }

    @Test void unknownLastResort() {
        assertEquals("unknown",
                PdfDerivedFields.derivePaymentChannel(debit("UNKNOWN MERCHANT XYZ")));
    }

    @Test void noAnnualFee_yes() {
        assertTrue(PdfDerivedFields.statementMentionsNoAnnualFee(
                new String[]{"Some text", "No Annual Fee", "etc"}));
    }

    @Test void noAnnualFee_no() {
        assertFalse(PdfDerivedFields.statementMentionsNoAnnualFee(
                new String[]{"ANNUAL MEMBERSHIP FEE: $695.00"}));
    }

    @Test void noAnnualFee_nullSafe() {
        assertFalse(PdfDerivedFields.statementMentionsNoAnnualFee(null));
        assertFalse(PdfDerivedFields.statementMentionsNoAnnualFee(new String[]{null}));
    }
}
