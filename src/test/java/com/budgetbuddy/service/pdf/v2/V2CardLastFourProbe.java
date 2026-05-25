package com.budgetbuddy.service.pdf.v2;

import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Diagnostic: for every PDF in the corpus, report the detected
 * institution + product + accountNumber (last-4) + per-tx cardLastFour
 * coverage. Lets us quickly answer "did we get last-4 for Prime Visa /
 * Costco Citi / Double Cash / Morgan Stanley Platinum / Active Cash /
 * Amex Blue Cash / etc.".
 */
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class V2CardLastFourProbe {

    private static final String CORPUS_DIR = System.getProperty(
            "pdf.lbl.dir", "/Users/garimaagarwal/Downloads/statements");

    @Test
    void reportCardLastFourPerIssuer() throws Exception {
        final File dir = new File(CORPUS_DIR);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null) return;
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        // Group by detected institution + cardName (account-level).
        final Map<String, Group> byProduct = new LinkedHashMap<>();
        for (final File pdf : pdfs) {
            try (InputStream in = new FileInputStream(pdf)) {
                final ImportResult r = svc.parsePDF(in, pdf.getName(), "probe", null);
                final String institution = r.getDetectedAccount() == null
                        ? "(no-account)" : r.getDetectedAccount().getInstitutionName();
                final String cardName = r.getDetectedAccount() == null
                        ? "(no-cardName)"
                        : nz(r.getDetectedAccount().getAccountName(), "");
                final String accountLast4 = r.getDetectedAccount() == null
                        ? null : r.getDetectedAccount().getAccountNumber();
                final String key = institution + " | " + cardName;
                final Group g = byProduct.computeIfAbsent(key, k -> new Group());
                g.files.add(pdf.getName());
                g.accountLast4s.add(accountLast4);
                int txWithLast4 = 0;
                final Set<String> distinctTxLast4 = new HashSet<>();
                for (final ParsedTransaction t : r.getTransactions()) {
                    if (t.getCardLastFour() != null && !t.getCardLastFour().isBlank()) {
                        txWithLast4++;
                        distinctTxLast4.add(t.getCardLastFour());
                    }
                }
                g.totalTx += r.getTransactions().size();
                g.txWithLast4 += txWithLast4;
                g.distinctTxLast4.addAll(distinctTxLast4);
            } catch (final Exception ignored) { }
        }
        System.out.println();
        System.out.println("======== CARD LAST-4 COVERAGE BY PRODUCT ========");
        System.out.printf("%-60s | %5s | %5s | %20s | %s%n",
                "Issuer + product", "files", "tx", "acct last-4(s)", "tx last-4 coverage");
        System.out.println("-".repeat(140));
        for (final var e : byProduct.entrySet()) {
            final Group g = e.getValue();
            final String accts = g.accountLast4s.toString();
            final String txCov = g.totalTx == 0 ? "0/0 (–)"
                    : g.txWithLast4 + "/" + g.totalTx + " ("
                            + Math.round(100.0 * g.txWithLast4 / g.totalTx)
                            + "%) distinct=" + g.distinctTxLast4;
            System.out.printf("%-60s | %5d | %5d | %20s | %s%n",
                    truncate(e.getKey(), 60), g.files.size(), g.totalTx,
                    truncate(accts, 20), txCov);
        }
    }

    private static String nz(final String a, final String b) {
        if (a != null && !a.isBlank()) return a;
        return b == null ? "" : b;
    }

    private static String truncate(final String s, final int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static final class Group {
        final java.util.List<String> files = new java.util.ArrayList<>();
        final Set<String> accountLast4s = new java.util.LinkedHashSet<>();
        final Set<String> distinctTxLast4 = new java.util.LinkedHashSet<>();
        int totalTx = 0;
        int txWithLast4 = 0;
    }
}
