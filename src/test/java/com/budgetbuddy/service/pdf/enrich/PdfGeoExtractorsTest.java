package com.budgetbuddy.service.pdf.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pins the extracted-out geo helpers. Mirrors the in-place tests on
 * PDFImportService so a future refactor that drops the originals
 * doesn't lose the contract.
 */
class PdfGeoExtractorsTest {

    @Test void phone_realFormat() {
        assertEquals("8005551212",
                PdfGeoExtractors.extractPhone("STARBUCKS 800-555-1212 SEATTLE WA"));
    }

    @Test void phone_bareTen() {
        assertEquals("8005551212",
                PdfGeoExtractors.extractPhone("MERCHANT 8005551212 SEATTLE WA"));
    }

    @Test void phone_ppdIdRejected() {
        assertNull(PdfGeoExtractors.extractPhone(
                "Morgan Stanley   ACH Credit  PPD ID: 6427014001"));
    }

    @Test void phone_webIdRejected() {
        assertNull(PdfGeoExtractors.extractPhone(
                "Amazon.Com Svcs  Payroll   Web ID: 9111111103"));
    }

    @Test void phone_telIdRejected() {
        assertNull(PdfGeoExtractors.extractPhone(
                "City of Bellevue Utility   Tel ID: 0000063576"));
    }

    @Test void phone_blankInputs() {
        assertNull(PdfGeoExtractors.extractPhone(null));
        assertNull(PdfGeoExtractors.extractPhone(""));
        assertNull(PdfGeoExtractors.extractPhone("    "));
    }

    @Test void zipFromDescription_requiresStateTrailer() {
        // Standard "<city> <state> <zip>" pattern.
        assertEquals("20001", PdfGeoExtractors.extractZipFromDescription(
                "AplPay LUCKY BUNS WASHINGTON DC 20001"));
    }

    @Test void zipFromDescription_rejectsStoreNumber() {
        // "16245" is a SUBWAY store number, not a ZIP. No state code earlier.
        assertNull(PdfGeoExtractors.extractZipFromDescription("AplPay SUBWAY 16245"));
    }

    @Test void zipFromDescription_nullSafe() {
        assertNull(PdfGeoExtractors.extractZipFromDescription(null));
    }

    @Test void streetAddress_basic() {
        // Note: the regex matches a digit-prefixed span, so leading
        // "#5421" gets absorbed as part of the match. Caller does NOT
        // rely on this being a pure address — it's a "looks-like
        // street" heuristic. The substantive part ("BELLEVUE WAY NE")
        // is what matters for the downstream geo enrichment.
        final String addr = PdfGeoExtractors.extractStreetAddress(
                "MERCHANT 1500 BELLEVUE WAY NE BELLEVUE WA");
        assertNotNull(addr);
        assertEquals("1500 BELLEVUE WAY NE", addr);
    }

    @Test void streetAddress_withApt() {
        final String addr = PdfGeoExtractors.extractStreetAddress(
                "WALGREENS 100 MAIN ST APT 5 BOSTON MA");
        assertNotNull(addr);
        assertEquals("100 MAIN ST APT 5", addr);
    }

    @Test void streetAddress_returnsNullForNoAddress() {
        assertNull(PdfGeoExtractors.extractStreetAddress("NETFLIX.COM LOS GATOS CA"));
        assertNull(PdfGeoExtractors.extractStreetAddress("AMZN MKTP US*123ABC"));
        assertNull(PdfGeoExtractors.extractStreetAddress(""));
        assertNull(PdfGeoExtractors.extractStreetAddress(null));
    }

    @Test void streetAddress_rejectsBogusZeroPrefix() {
        // 0000-prefixed digits are clearly transaction-id leakage.
        assertNull(PdfGeoExtractors.extractStreetAddress("0000123 ST"));
    }
}
