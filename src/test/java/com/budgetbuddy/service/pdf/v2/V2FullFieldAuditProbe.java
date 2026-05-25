package com.budgetbuddy.service.pdf.v2;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Comprehensive per-product field-by-field audit. For every detected
 * institution + product in the corpus, reports:
 *
 *   1. Account-level field population (statement date, balances, APRs,
 *      credit limit, rewards, fees, etc.) — single value per parse
 *   2. Transaction-level field coverage (date, amount, merchant, location,
 *      geo, FX, wallet, cardLastFour, userName, type, category) —
 *      population rate across all rows
 *
 * Output is human-readable. Use this when investigating "are we getting
 * every field on every product?" — the alternative is the corpus
 * coverage floor tests which give aggregate % but hide per-product gaps.
 */
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class V2FullFieldAuditProbe {

    private static final String CORPUS_DIR = System.getProperty(
            "pdf.lbl.dir", "/Users/garimaagarwal/Downloads/statements");

    @Test
    void reportFieldCoveragePerProduct() throws Exception {
        final File dir = new File(CORPUS_DIR);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null) return;
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        final Map<String, ProductStats> byProduct = new LinkedHashMap<>();
        int parseErrors = 0;
        for (final File pdf : pdfs) {
            try (InputStream in = new FileInputStream(pdf)) {
                final ImportResult r = svc.parsePDF(in, pdf.getName(), "probe", null);
                final DetectedAccount a = r.getDetectedAccount();
                final String institution = a == null ? "(no-account)" : a.getInstitutionName();
                final String cardName = a == null || a.getAccountName() == null
                        ? "(unknown)" : a.getAccountName();
                final String key = institution + " | " + cardName;
                final ProductStats ps = byProduct.computeIfAbsent(key,
                        k -> new ProductStats(institution, cardName));
                ps.recordParse(r);
            } catch (final Exception e) {
                parseErrors++;
            }
        }
        printReport(byProduct, parseErrors);
    }

    private static void printReport(
            final Map<String, ProductStats> byProduct, final int parseErrors) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println(" PER-PRODUCT FIELD COVERAGE AUDIT");
        System.out.println("============================================================");
        System.out.printf(" %d distinct products, %d parse errors%n", byProduct.size(), parseErrors);
        System.out.println();
        for (final ProductStats ps : byProduct.values()) {
            ps.print();
        }
    }

    // ====================================================================
    //  Stats tracker per product
    // ====================================================================
    private static final class ProductStats {
        final String institution;
        final String cardName;
        int parses;
        // Account-level: count of parses where the field was populated.
        final Map<String, Integer> acctHits = new LinkedHashMap<>();
        final Set<String> accountLast4s = new LinkedHashSet<>();
        // Transaction-level: per-field hit/total counters.
        int totalTx;
        final Map<String, Integer> txHits = new LinkedHashMap<>();
        final Set<String> distinctTxLast4 = new LinkedHashSet<>();
        final Set<String> distinctTxUsers = new LinkedHashSet<>();
        final Set<String> distinctTxTypes = new LinkedHashSet<>();
        final Set<String> distinctCategories = new LinkedHashSet<>();
        final Set<String> distinctWallets = new LinkedHashSet<>();

        ProductStats(final String institution, final String cardName) {
            this.institution = institution;
            this.cardName = cardName;
        }

        void recordParse(final ImportResult r) {
            parses++;
            final DetectedAccount a = r.getDetectedAccount();
            if (a != null && a.getAccountNumber() != null) {
                accountLast4s.add(a.getAccountNumber());
            }
            // Account-level fields. Use a String-keyed map so the print
            // order is deterministic and readers see every field.
            tickAcct("accountType", a != null && a.getAccountType() != null);
            tickAcct("accountSubtype", a != null && a.getAccountSubtype() != null);
            tickAcct("accountHolderName", a != null && a.getAccountHolderName() != null);
            tickAcct("balance", a != null && a.getBalance() != null);
            tickAcct("statementDate", r.getStatementDate() != null);
            tickAcct("statementStartDate", r.getStatementStartDate() != null);
            tickAcct("statementEndDate", r.getStatementEndDate() != null);
            tickAcct("billingDays", r.getBillingDays() != null);
            tickAcct("newBalance", r.getNewBalance() != null);
            tickAcct("previousBalance", r.getPreviousBalance() != null);
            tickAcct("creditLimit", r.getCreditLimit() != null);
            tickAcct("availableCredit", r.getAvailableCredit() != null);
            tickAcct("pastDueAmount", r.getPastDueAmount() != null);
            tickAcct("paymentDueDate", r.getPaymentDueDate() != null);
            tickAcct("minimumPaymentDue", r.getMinimumPaymentDue() != null);
            tickAcct("purchasesTotal", r.getPurchasesTotal() != null);
            tickAcct("paymentsAndCreditsTotal", r.getPaymentsAndCreditsTotal() != null);
            tickAcct("cashAdvancesTotal", r.getCashAdvancesTotal() != null);
            tickAcct("balanceTransfersTotal", r.getBalanceTransfersTotal() != null);
            tickAcct("feesChargedTotal", r.getFeesChargedTotal() != null);
            tickAcct("interestChargedTotal", r.getInterestChargedTotal() != null);
            tickAcct("purchaseApr", r.getPurchaseApr() != null);
            tickAcct("cashAdvanceApr", r.getCashAdvanceApr() != null);
            tickAcct("balanceTransferApr", r.getBalanceTransferApr() != null);
            tickAcct("penaltyApr", r.getPenaltyApr() != null);
            tickAcct("annualMembershipFee", r.getAnnualMembershipFee() != null);
            tickAcct("foreignTxFeePercent", r.getForeignTransactionFeePercent() != null);
            tickAcct("autoPayEnabled", r.getAutoPayEnabled() != null);
            tickAcct("nextAutoPayAmount", r.getNextAutoPayAmount() != null);
            tickAcct("rewardPoints", r.getRewardPoints() != null);
            tickAcct("pointsBalance", r.getPointsBalance() != null);
            tickAcct("previousPointsBalance", r.getPreviousPointsBalance() != null);
            tickAcct("cashBackBalance", r.getCashBackBalance() != null);
            tickAcct("ytdFeesCharged", r.getYtdFeesCharged() != null);
            tickAcct("ytdInterestCharged", r.getYtdInterestCharged() != null);
            tickAcct("cashAccessLine", r.getCashAccessLine() != null);
            tickAcct("availableForCash", r.getAvailableForCash() != null);

            // Transactions
            for (final ParsedTransaction t : r.getTransactions()) {
                totalTx++;
                tickTx("date", t.getDate() != null);
                tickTx("amount", t.getAmount() != null);
                tickTx("description", isPresent(t.getDescription()));
                tickTx("merchantName", isPresent(t.getMerchantName()));
                tickTx("location", isPresent(t.getLocation()));
                tickTx("city", isPresent(t.getCity()));
                tickTx("state", isPresent(t.getState()));
                tickTx("country", isPresent(t.getCountry()));
                tickTx("postalCode", isPresent(t.getPostalCode()));
                tickTx("phoneNumber", isPresent(t.getPhoneNumber()));
                tickTx("streetAddress", isPresent(t.getStreetAddress()));
                tickTx("currencyCode", isPresent(t.getCurrencyCode()));
                tickTx("paymentChannel", isPresent(t.getPaymentChannel()));
                tickTx("cardLastFour", isPresent(t.getCardLastFour()));
                tickTx("userName", isPresent(t.getUserName()));
                tickTx("flowDirection", t.getFlowDirection() != null);
                tickTx("transactionType", isPresent(t.getTransactionType()));
                tickTx("categoryPrimary", isPresent(t.getCategoryPrimary()));
                tickTx("categoryDetailed", isPresent(t.getCategoryDetailed()));
                tickTx("importerCategoryPrimary", isPresent(t.getImporterCategoryPrimary()));
                tickTx("walletProvider", isPresent(t.getWalletProvider()));
                tickTx("originalCurrencyCode", isPresent(t.getOriginalCurrencyCode()));
                tickTx("originalAmount", t.getOriginalAmount() != null);
                tickTx("exchangeRate", t.getExchangeRate() != null);
                // Distinct-value collectors
                if (isPresent(t.getCardLastFour())) distinctTxLast4.add(t.getCardLastFour());
                if (isPresent(t.getUserName())) distinctTxUsers.add(t.getUserName());
                if (isPresent(t.getTransactionType())) distinctTxTypes.add(t.getTransactionType());
                if (isPresent(t.getCategoryPrimary())) distinctCategories.add(t.getCategoryPrimary());
                if (isPresent(t.getWalletProvider())) distinctWallets.add(t.getWalletProvider());
            }
        }

        private void tickAcct(final String field, final boolean present) {
            if (present) acctHits.merge(field, 1, Integer::sum);
            else acctHits.merge(field, 0, Integer::sum);
        }

        private void tickTx(final String field, final boolean present) {
            if (present) txHits.merge(field, 1, Integer::sum);
            else txHits.merge(field, 0, Integer::sum);
        }

        void print() {
            System.out.println("===============================================================");
            System.out.printf(" %s | %s%n", institution, cardName);
            System.out.printf(" parses=%d  tx=%d  acctLast4s=%s%n",
                    parses, totalTx, accountLast4s);
            System.out.println("---------------------------------------------------------------");
            System.out.println(" ACCOUNT-LEVEL (count of parses where present)");
            for (final var e : acctHits.entrySet()) {
                final int hits = e.getValue();
                final String bar = hits == 0 ? " MISSING"
                        : hits == parses ? "" : "  partial " + hits + "/" + parses;
                System.out.printf("   %-28s %d/%d%s%n",
                        e.getKey(), hits, parses, bar);
            }
            System.out.println(" TRANSACTION-LEVEL (count of tx where present)");
            for (final var e : txHits.entrySet()) {
                final int hits = e.getValue();
                final String pct = totalTx == 0 ? "—"
                        : Math.round(100.0 * hits / totalTx) + "%";
                System.out.printf("   %-28s %d/%d (%s)%n",
                        e.getKey(), hits, totalTx, pct);
            }
            System.out.println(" DISTINCT VALUES");
            System.out.printf("   cardLastFour       %s%n", distinctTxLast4);
            System.out.printf("   userName           %s%n", truncate(distinctTxUsers.toString(), 90));
            System.out.printf("   transactionType    %s%n", distinctTxTypes);
            System.out.printf("   walletProvider     %s%n", distinctWallets);
            System.out.printf("   categoryPrimary    %s%n", truncate(distinctCategories.toString(), 90));
            System.out.println();
        }

        private static String truncate(final String s, final int max) {
            if (s == null || s.length() <= max) return s == null ? "" : s;
            return s.substring(0, max - 1) + "…";
        }
    }

    private static boolean isPresent(final String s) {
        return s != null && !s.isBlank();
    }
}
