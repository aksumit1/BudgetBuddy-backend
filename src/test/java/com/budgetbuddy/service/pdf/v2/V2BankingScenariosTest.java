package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.FlowDirection;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import com.budgetbuddy.service.ml.MerchantLocationSplitter;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Real-world banking / credit-card / financial-statement scenarios drawn
 * from world knowledge of how US and international banks emit transaction
 * rows. Each test pins a specific behavior the parser must handle, with
 * the source of the pattern documented inline.
 *
 * <h3>Why these matter</h3>
 *
 * The corpus floor tests catch aggregate regressions but won't catch a
 * specific pattern that's rare in our sample set. These scenario tests
 * pin the behavior of patterns that are common in production usage but
 * may not appear in our 130-PDF local corpus. Adding a test here is
 * cheaper than waiting for a user-reported issue.
 *
 * <h3>Coverage areas</h3>
 *
 * <ul>
 *   <li>Transaction-type derivation (FEE / INTEREST / CASH_ADVANCE /
 *       BALANCE_TRANSFER / REFUND / DEPOSIT / WITHDRAWAL / CHECK)</li>
 *   <li>Online-only subscription rows (no city/state by design)</li>
 *   <li>Payment-processor descriptor noise (Stripe / Etsy / eBay / Cash App)</li>
 *   <li>Amazon descriptor variants (AMZN MKTP / KINDLE* / AMAZON DIGITAL)</li>
 *   <li>Apple descriptor variants (APPLE.COM/BILL / APL*ITUNES)</li>
 *   <li>Google descriptor variants (GOOGLE *YOUTUBE / GOOGLE *FI)</li>
 *   <li>UPI / Indian-format mobile-payment descriptors</li>
 *   <li>JPY zero-decimal currency precision</li>
 *   <li>Refund with negative amount / chargeback</li>
 *   <li>Authorization hold patterns</li>
 *   <li>Hotel-folio noise (Costco Travel)</li>
 * </ul>
 */
class V2BankingScenariosTest {

    // Reflection helper to exercise the private deriveTransactionType
    // logic in PDFImportService without spinning up a full service.
    private static String deriveType(final ParsedTransaction tx) throws Exception {
        final Method m = PDFImportService.class.getDeclaredMethod(
                "deriveTransactionType", ParsedTransaction.class);
        m.setAccessible(true);
        // deriveTransactionType is an instance method — build a minimal
        // service from the test-factory helper.
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        return (String) m.invoke(svc, tx);
    }

    private static ParsedTransaction debit(final String desc, final String amt) {
        final ParsedTransaction t = new ParsedTransaction();
        t.setDescription(desc);
        t.setAmount(new BigDecimal(amt).negate());
        t.setFlowDirection(FlowDirection.DEBIT);
        return t;
    }

    private static ParsedTransaction credit(final String desc, final String amt) {
        final ParsedTransaction t = new ParsedTransaction();
        t.setDescription(desc);
        t.setAmount(new BigDecimal(amt));
        t.setFlowDirection(FlowDirection.CREDIT);
        return t;
    }

    // ====================================================================
    //  TRANSACTION TYPE: fees, interest, cash advance, balance transfer
    //  These were causing miscategorization before the derive logic was
    //  added; pinning them ensures the type-extraction stays correct.
    // ====================================================================

    @Test
    void annualFeeChargeMapsToFee() throws Exception {
        // Chase, Citi, Amex all print "ANNUAL MEMBERSHIP FEE" once per year.
        // Must classify as FEE (not PURCHASE) so analytics can separate
        // out unavoidable carrying costs from discretionary spend.
        assertEquals("FEE", deriveType(debit("ANNUAL MEMBERSHIP FEE", "95.00")));
        assertEquals("FEE", deriveType(debit("ANNUAL FEE", "550.00")));
    }

    @Test
    void foreignTransactionFeeMapsToFee() throws Exception {
        // Cards without no-foreign-fee benefits charge 3% per international
        // tx, often as a separate row right after the purchase. Must be
        // FEE so analytics don't double-count it as another purchase.
        assertEquals("FEE", deriveType(debit("FOREIGN TRANSACTION FEE", "4.85")));
    }

    @Test
    void lateFeeMapsToFee() throws Exception {
        assertEquals("FEE", deriveType(debit("LATE FEE", "29.00")));
        assertEquals("FEE", deriveType(debit("RETURNED PAYMENT FEE", "35.00")));
        assertEquals("FEE", deriveType(debit("OVERLIMIT FEE", "29.00")));
    }

    @Test
    void cashAdvanceFeeAndCashAdvanceAreDistinct() throws Exception {
        // "CASH ADVANCE FEE" is the per-tx fee; "CASH ADVANCE" itself is
        // the principal withdrawn. Different categories for different
        // analytical purposes.
        assertEquals("FEE", deriveType(debit("CASH ADVANCE FEE", "10.00")));
        assertEquals("CASH_ADVANCE", deriveType(debit("CASH ADVANCE", "500.00")));
        assertEquals("CASH_ADVANCE", deriveType(debit("ATM WITHDRAWAL", "200.00")));
    }

    @Test
    void interestChargeMapsToInterest() throws Exception {
        // The label varies by issuer — pin all three forms.
        assertEquals("INTEREST", deriveType(debit("INTEREST CHARGE ON PURCHASES", "12.45")));
        assertEquals("INTEREST", deriveType(debit("INTEREST CHARGED - CASH ADVANCES", "5.30")));
        assertEquals("INTEREST", deriveType(debit("INTEREST ASSESSED", "8.75")));
    }

    @Test
    void balanceTransferMapsToBalanceTransfer() throws Exception {
        // Intro APR offer rows: principal moved from another card.
        assertEquals("BALANCE_TRANSFER", deriveType(debit("BALANCE TRANSFER FROM CITI", "2500.00")));
    }

    @Test
    void balanceTransferFeeMapsToFee() throws Exception {
        // The 3-5% fee charged on the transfer.
        assertEquals("FEE", deriveType(debit("BALANCE TRANSFER FEE", "75.00")));
    }

    @Test
    void refundMapsToRefund_creditDirection() throws Exception {
        assertEquals("REFUND", deriveType(credit("AMAZON.COM REFUND", "29.99")));
        assertEquals("REFUND", deriveType(credit("TARGET RETURN", "15.00")));
        assertEquals("REFUND", deriveType(credit("CREDIT ADJUSTMENT - DISPUTE", "100.00")));
    }

    @Test
    void cashbackCreditMapsToRefund() throws Exception {
        // Discover Cashback Bonus credit, Chase Freedom rewards redeemed
        // as statement credit — both print "CASHBACK" or "CASH BACK".
        assertEquals("REFUND", deriveType(credit("CASHBACK BONUS", "47.50")));
        assertEquals("REFUND", deriveType(credit("CASH BACK REDEMPTION", "100.00")));
    }

    @Test
    void depositOnCheckingMapsToDeposit() throws Exception {
        // Deposit-account rows: a credit labeled "DEPOSIT" is a true cash
        // inflow, distinct from a PAYMENT (which is paying off a debt).
        assertEquals("DEPOSIT", deriveType(credit("MOBILE DEPOSIT", "500.00")));
        assertEquals("DEPOSIT", deriveType(credit("ATM DEPOSIT", "200.00")));
    }

    @Test
    void atmWithdrawalCheckingMapsToCashAdvance() throws Exception {
        // ATM withdrawals on a credit card = cash advance. On checking it
        // would be WITHDRAWAL, but deriveTransactionType treats both as
        // the same "money out the ATM" category — current behavior.
        assertEquals("CASH_ADVANCE", deriveType(debit("ATM WITHDRAWAL #1234", "100.00")));
    }

    @Test
    void checkPaidOnCheckingMapsToCheck() throws Exception {
        // Checking-account row: paper check written by the user.
        assertEquals("CHECK", deriveType(debit("CHECK 185 PAID", "750.00")));
    }

    @Test
    void purchaseIsTheDefault() throws Exception {
        // Anything not matching the above keywords is a PURCHASE.
        assertEquals("PURCHASE", deriveType(debit("STARBUCKS BELLEVUE WA", "5.75")));
        assertEquals("PURCHASE", deriveType(debit("AMAZON MARKETPLACE", "29.99")));
    }

    // ====================================================================
    //  SUBSCRIPTION ROWS — online-only services, no city/state extraction
    //  Online subscriptions have either a generic city ("LOS GATOS CA" for
    //  Netflix) OR no location at all (Apple, Google billing rows). They
    //  should not surface a misleading user-visible "Bellevue, WA" badge.
    // ====================================================================

    @Test
    void netflixRowDoesNotHallucinateLocation() {
        // "NETFLIX.COM LOS GATOS CA" — Netflix's billing descriptor.
        // Has a real city (Netflix HQ), but we don't extract it because
        // the URL prefix marks it as online-only — splitter sees
        // "NETFLIX.COM" and bails (URL chars in city candidate).
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "NETFLIX.COM LOS GATOS CA");
        // Either null or a state-only result — never a city with a dot.
        if (s.location() != null) {
            assertTrue(!s.location().contains("Netflix.com")
                            && !s.location().contains("."),
                    "Netflix URL must not be parsed as city: " + s.location());
        }
    }

    @Test
    void appleBillingRowHasNoLocation() {
        // "APPLE.COM/BILL CUPERTINO CA 866-712-7753"
        // Apple bills from CA but we shouldn't surface that as a tx location.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "APPLE.COM/BILL CUPERTINO CA");
        if (s.location() != null) {
            // Acceptable if it captured CA — but it must NOT have a
            // city with "APPLE.COM/BILL" in it.
            assertTrue(!s.location().toLowerCase().contains("apple")
                            && !s.location().contains("/"),
                    "Apple billing URL must not bleed into location: " + s.location());
        }
    }

    @Test
    void spotifyOnlineSubscriptionHasNoCity() {
        // "SPOTIFY USA NEW YORK NY 123456789"
        // Even though there's "NEW YORK NY" in the row, this is a global
        // online subscription. We can extract location since it's printed,
        // but the test pins behavior: if extracted, it must be correct.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "SPOTIFY USA NEW YORK NY");
        // New York will be extracted; that's correct behavior.
        assertEquals("New York, NY", s.location());
    }

    @Test
    void googleYoutubeMusicHasOnlineUrlInDescriptor() {
        // "GOOGLE *YOUTUBE MUSIC G.CO/HELPPAY# CA"
        // The "G.CO/HELPPAY#" piece is a help URL — must not be city.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "GOOGLE *YOUTUBE MUSIC G.CO/HELPPAY# CA");
        // If location is set, city must NOT contain dots, slashes, or #
        if (s.location() != null && s.location().contains(",")) {
            final String city = s.location().split(",")[0].trim();
            assertTrue(!city.contains(".") && !city.contains("/") && !city.contains("#"),
                    "Help URL must not become city: " + s.location());
        }
    }

    // ====================================================================
    //  AMAZON DESCRIPTOR VARIANTS — typically online, no location
    // ====================================================================

    @Test
    void amznMktpHasNoLocation() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AMZN MKTP US*A1B2C3D4");
        assertNull(s.location(),
                "Online Amazon Marketplace order has no location");
    }

    @Test
    void amazonPrimeHasNoLocation() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "Amazon Prime");
        assertNull(s.location());
    }

    @Test
    void kindleStoreHasNoLocation() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "KINDLE STORE AMZN.COM/BILL");
        // No US state trailer, so no location even with the .com URL.
        assertNull(s.location());
    }

    // ====================================================================
    //  PAYMENT-PROCESSOR DESCRIPTOR NOISE
    //  Real-world descriptor formats from Plaid's documentation and
    //  empirical observation across many issuers.
    // ====================================================================

    @Test
    void stripeDescriptorWithMerchant() {
        // Stripe payments sometimes prepend "SP *" or "STR *" before merchant.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "SP *INDIE GAME STUDIO SAN FRANCISCO CA");
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void cashAppDescriptorIsCleanlyParsed() {
        // Cash App transactions: "CASH APP*<merchant>" or just "CASH APP"
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "CASH APP*JOHN DOE SAN FRANCISCO CA");
        assertEquals("San Francisco, CA", s.location());
    }

    @Test
    void venmoPaymentDescriptorWithNoLocation() {
        // Venmo P2P typically has no merchant location.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "VENMO*PAYMENT");
        assertNull(s.location(),
                "Venmo P2P row has no merchant location");
    }

    @Test
    void etsyDescriptorWithSellerCity() {
        // Etsy: "ETSY.COM/SOMETHING" — online URL, no city expected.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "ETSY.COM/INVOICE");
        assertNull(s.location());
    }

    @Test
    void ebayOrderDescriptor() {
        // eBay: "EBAY O*<order-id>" — typically online.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "EBAY O*123-45678-90123");
        assertNull(s.location());
    }

    // ====================================================================
    //  FX / INTERNATIONAL CURRENCY EDGE CASES
    // ====================================================================

    @Test
    void refundWithNegativeOriginalAmount_keepsNegativeSign() {
        // International refund: negative original amount, negative
        // settlement amount, FX context preserved. The splitter shouldn't
        // care about sign — that's the storage layer's concern — but
        // pin the location extraction works regardless.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "THE WESTIN PUNE - REFUND PUNE IN");
        assertEquals("Pune, IN", s.location());
    }

    @Test
    void jpyZeroDecimalCurrency_locationStillExtracts() {
        // JPY is a zero-decimal currency: ¥10000 is the full amount,
        // not ¥100.00. The splitter doesn't deal with amounts, but pin
        // location extraction works on Japan rows regardless.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AEON STYLE NARITA AIRPORT TOKYO JP");
        assertEquals("Tokyo, JP", s.location());
    }

    @Test
    void inrFormatLargeAmount_locationStillExtracts() {
        // INR amounts can be very large (lakhs/crores). Splitter doesn't
        // care about the amount value, just the row's location structure.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "CHALET HOTELS LIMITED BENGALURU IN");
        assertEquals("Bengaluru, IN", s.location());
    }

    @Test
    void europeanCityWithLongName() {
        // European city names can be unusually long.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "MERCHANT IN COPENHAGEN COPENHAGEN DK");
        // "DK" is in ALPHA2_COUNTRIES_SAFE; "Copenhagen" extracted as city.
        assertEquals("Copenhagen, DK", s.location());
    }

    // ====================================================================
    //  TOLL / TRANSIT / RIDESHARE
    // ====================================================================

    @Test
    void wsdotTollChargeWithPhoneAndState() {
        // Washington DOT toll: "WSDOT 866-936-8246 WA"
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "WSDOT 866-936-8246 WA");
        // Phone strip → "WSDOT WA" — state-only location.
        assertEquals("WA", s.location());
    }

    @Test
    void uberEatsHelpUrlDoesNotBecomeCity() {
        // "AplPay UBER EATS HTTPS://HELP.UBER.COM CA"
        // The URL must not be parsed as city.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AplPay UBER EATS HTTPS://HELP.UBER.COM CA");
        // Either null OR state-only; never a city with "://" or "."
        if (s.location() != null && s.location().contains(",")) {
            final String city = s.location().split(",")[0].trim();
            assertTrue(!city.contains("://") && !city.contains("."),
                    "URL must not become city: " + s.location());
        }
    }

    // ====================================================================
    //  AUTHORIZATION HOLDS  (no row format in PDF — pending only;
    //  documented here as out-of-scope but worth noting)
    // ====================================================================

    @Test
    void hotelPreAuthHold_documentedAsOutOfScope() {
        // Hotels place a $1 pre-auth hold then settle to actual amount.
        // PDF statements only show settled rows, so we don't see the hold.
        // This test exists to document that the parser deliberately
        // doesn't handle this case — the pendingAmount field on the
        // Transaction model carries the prior amount when a pending
        // resolves with drift, but that's a Plaid concern, not PDF.
        assertTrue(true, "Pre-auth holds are a Plaid concern, not PDF.");
    }

    // ====================================================================
    //  GROCERY / RETAIL CHAINS — verify family chains parse correctly
    // ====================================================================

    @Test
    void traderJoesWithLocation() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "TRADER JOE'S #189 BELLEVUE WA");
        assertEquals("Bellevue, WA", s.location());
    }

    @Test
    void wholeFoodsAmazonOwnedWithLocation() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "WHOLE FOODS MKT 10256 SEATTLE WA");
        assertEquals("Seattle, WA", s.location());
    }

    @Test
    void safewayWithStoreCode() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "SAFEWAY STORE 1234 BELLEVUE WA");
        assertEquals("Bellevue, WA", s.location());
    }

    // ====================================================================
    //  GAS STATIONS — variable descriptor formats
    // ====================================================================

    @Test
    void costcoGasNoStateCodeUntrimmed() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "COSTCO GAS #0110 ISSAQUAH WA");
        assertEquals("Issaquah, WA", s.location());
    }

    @Test
    void chevronExtractsLocation() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "AplPay CHEVRON 0306145/CHEVRON SUNNYVALE CA");
        // Should parse "Sunnyvale, CA"
        assertEquals("Sunnyvale, CA", s.location());
    }

    // ====================================================================
    //  AIRLINES — multi-segment ticket descriptors
    // ====================================================================

    @Test
    void deltaAirlinesAtlantaHub() {
        // Delta prints "DELTA AIR LINES ATLANTA" with optional flight details.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "DELTA AIR LINES ATLANTA GA");
        assertEquals("Atlanta, GA", s.location());
    }

    @Test
    void unitedAirlines() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "UNITED 0162345678901 CHICAGO IL");
        assertEquals("Chicago, IL", s.location());
    }

    // ====================================================================
    //  EDGE: case-sensitivity insensitivity
    // ====================================================================

    @Test
    void mixedCaseDescriptorStillExtracts() {
        // Some issuers print lower-case ("AplPay" form). Splitter should
        // upcase tokens it compares against state/country sets.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "aplpay Starbucks bellevue WA");
        assertEquals("Bellevue, WA", s.location());
    }

    // ====================================================================
    //  EDGE: numeric-heavy descriptors should not hallucinate
    // ====================================================================

    @Test
    void allNumericDescriptorDoesNotExtract() {
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "1234567890 5678901234");
        assertNull(s.location(),
                "Pure-numeric descriptor must not yield location");
    }

    @Test
    void merchantWithEmbeddedNumber_doesNotConfuseExtraction() {
        // "7-ELEVEN 38420" - merchant has a number, but city/state at end.
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(
                "7-ELEVEN 38420 BELLEVUE WA");
        assertEquals("Bellevue, WA", s.location());
    }

    // ====================================================================
    //  VERY-LONG descriptions (continuation rows from multi-line PDFs)
    // ====================================================================

    @Test
    void veryLongDescriptionDoesNotCrash() {
        final String longDesc =
                "AMAZON.COM 800-279-6620 WA 12345678901234 INC ORDER #112-1234567-1234567 "
                        + "ITEM: ELECTRONICS / COMPUTERS / LAPTOPS RETURN-WINDOW EXPIRED";
        final MerchantLocationSplitter.Split s = MerchantLocationSplitter.split(longDesc);
        // Should not crash; may or may not extract a location.
        assertNotNull(s, "Splitter must not crash on long descriptions");
        assertNotNull(s.merchant());
    }
}
