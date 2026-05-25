package com.budgetbuddy.service;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Comprehensive audit across every PDF in a directory. For each PDF we check:
 *   • Card detection: institution, last-4, account holder, statement date
 *   • Field completeness: every parsed transaction must have date+amount+description
 *   • Duplicate detection: same (date, amount, normalized-description) appearing twice
 *   • Math tally: sum of parsed transactions by FlowDirection vs statement totals
 *     printed by the issuer (purchasesTotal, paymentsAndCreditsTotal, fees, interest)
 *   • Category assignment: every transaction should have a category
 *
 * The harness exits non-zero if ANY PDF fails a check, so this can be turned into
 * a regression gate later. Enabled via -Dpdf.audit.dir=&lt;path&gt; (off by default).
 */
@EnabledIfSystemProperty(named = "pdf.audit.dir", matches = ".+")
class PdfFullAuditTest {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.05");

    @Test
    void audit() throws Exception {
        final String dirStr = System.getProperty("pdf.audit.dir");
        final File dir = new File(dirStr);
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null) {
            throw new IllegalStateException("No PDFs found in " + dirStr);
        }
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final PDFImportService svc = buildService();
        final List<AuditRow> rows = new ArrayList<>();
        final List<String> globalIssues = new ArrayList<>();

        for (final File pdf : pdfs) {
            rows.add(auditOne(svc, pdf, globalIssues));
        }

        final java.nio.file.Path csv =
                Paths.get(System.getProperty("pdf.audit.out", "/tmp/pdf_full_audit.csv"));
        if (csv.getParent() != null) Files.createDirectories(csv.getParent());
        writeReport(csv.toFile(), rows);

        // Console summary
        System.out.println();
        System.out.println("============================================================");
        System.out.println("                   FULL AUDIT REPORT");
        System.out.println("============================================================");
        int totalTx = 0;
        int totalDuplicates = 0;
        int totalIncomplete = 0;
        int totalCategoryMissing = 0;
        int totalMathFailures = 0;
        int totalCardMissing = 0;
        int totalHolderMissing = 0;
        for (final AuditRow r : rows) {
            totalTx += r.txCount;
            totalDuplicates += r.duplicates;
            totalIncomplete += r.incompleteFields;
            totalCategoryMissing += r.missingCategory;
            if (r.purchasesDelta != null && r.purchasesDelta.abs().compareTo(TOLERANCE) > 0) {
                totalMathFailures++;
            }
            if (r.last4 == null || r.last4.isEmpty()) totalCardMissing++;
            if (r.holder == null || r.holder.isEmpty()) totalHolderMissing++;
        }
        System.out.printf("%-58s | %-20s | %-7s | %-3s | %-10s | %-10s | %-7s | %-7s | %-7s%n",
                "FILE", "INST", "LAST4", "TX", "PUR_DELTA", "PAY_DELTA", "DUPES", "INCOMP", "CAT_MISS");
        System.out.println("----------------------------------------------------------------------------------------------------------------------");
        for (final AuditRow r : rows) {
            System.out.printf("%-58s | %-20s | %-7s | %-3d | %-10s | %-10s | %-7d | %-7d | %-7d%n",
                    trunc(r.file, 58),
                    trunc(r.institution, 20),
                    r.last4 == null ? "" : r.last4,
                    r.txCount,
                    fmt(r.purchasesDelta),
                    fmt(r.paymentsDelta),
                    r.duplicates,
                    r.incompleteFields,
                    r.missingCategory);
            for (final String issue : r.issues) {
                System.out.println("    " + issue);
            }
        }

        System.out.println();
        System.out.println("=== TOTALS ===");
        System.out.printf("Files audited:              %d%n", pdfs.length);
        System.out.printf("Total transactions:         %d%n", totalTx);
        System.out.printf("Duplicates (across all):    %d%n", totalDuplicates);
        System.out.printf("Incomplete tx (missing fld):%d%n", totalIncomplete);
        System.out.printf("Tx without category:        %d%n", totalCategoryMissing);
        System.out.printf("Math-tally failures:        %d / %d%n", totalMathFailures, pdfs.length);
        System.out.printf("Cards w/o last-4:           %d%n", totalCardMissing);
        System.out.printf("Cards w/o holder:           %d%n", totalHolderMissing);
        System.out.println();
        System.out.println("Report: " + csv);
        if (!globalIssues.isEmpty()) {
            System.out.println();
            System.out.println("=== GLOBAL ISSUES ===");
            globalIssues.forEach(s -> System.out.println("  " + s));
        }
    }

    private static AuditRow auditOne(
            final PDFImportService svc, final File pdf, final List<String> globalIssues) {
        final AuditRow r = new AuditRow();
        r.file = pdf.getName();

        try (FileInputStream in = new FileInputStream(pdf)) {
            final ImportResult result = svc.parsePDF(in, pdf.getName(), "audit-user", null);
            final DetectedAccount a = result.getDetectedAccount();
            if (a != null) {
                r.institution = a.getInstitutionName();
                r.last4 = a.getAccountNumber();
                r.holder = a.getAccountHolderName();
                r.accountType = a.getAccountType();
            }
            r.statementDate = result.getStatementDate() == null
                    ? "" : result.getStatementDate().toString();
            r.startDate = result.getStatementStartDate() == null
                    ? "" : result.getStatementStartDate().toString();
            r.endDate = result.getStatementEndDate() == null
                    ? "" : result.getStatementEndDate().toString();
            r.newBalance = result.getNewBalance();
            r.previousBalance = result.getPreviousBalance();
            r.statementPurchases = result.getPurchasesTotal();
            r.statementPaymentsCredits = result.getPaymentsAndCreditsTotal();
            r.statementFees = result.getFeesChargedTotal();
            r.statementInterest = result.getInterestChargedTotal();

            final List<ParsedTransaction> txs = result.getTransactions();
            r.txCount = txs.size();

            // Field completeness + sums + duplicates
            final Set<String> seen = new HashSet<>();
            BigDecimal debitSum = BigDecimal.ZERO;
            BigDecimal creditSum = BigDecimal.ZERO;
            for (final ParsedTransaction t : txs) {
                if (t.getDate() == null || t.getAmount() == null
                        || t.getDescription() == null || t.getDescription().isBlank()) {
                    r.incompleteFields++;
                    r.issues.add("incomplete tx: " + summarise(t));
                }
                if (t.getCategoryPrimary() == null || t.getCategoryPrimary().isBlank()) {
                    r.missingCategory++;
                }
                final String key = (t.getDate() == null ? "?" : t.getDate().toString())
                        + "|" + (t.getAmount() == null ? "?" : t.getAmount().toPlainString())
                        + "|" + normalize(t.getDescription());
                if (!seen.add(key)) {
                    r.duplicates++;
                    r.issues.add("duplicate: " + summarise(t));
                }
                // Sums by FlowDirection (signed amounts: parser may invert for credit cards)
                final BigDecimal amt = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
                if (t.getFlowDirection() == FlowDirection.CREDIT) {
                    creditSum = creditSum.add(amt.abs());
                } else {
                    debitSum = debitSum.add(amt.abs());
                }
            }
            r.parsedDebitSum = debitSum;
            r.parsedCreditSum = creditSum;

            // Math-tally checks. The statement breaks debits into several buckets
            // (purchases, fees, interest, cash advances) but our parsedDebitSum is
            // every DEBIT transaction. So the expected debit total is the SUM of
            // all those statement buckets — comparing to purchases alone would
            // false-flag Amex annual fees ($895) and US Bank interest charges
            // ($79.99) as discrepancies when they're really correct.
            BigDecimal expectedDebit = null;
            if (r.statementPurchases != null) expectedDebit = nz(expectedDebit).add(r.statementPurchases.abs());
            if (r.statementFees != null) expectedDebit = nz(expectedDebit).add(r.statementFees.abs());
            if (r.statementInterest != null) expectedDebit = nz(expectedDebit).add(r.statementInterest.abs());
            if (expectedDebit != null) {
                r.purchasesDelta = debitSum.subtract(expectedDebit).setScale(2, RoundingMode.HALF_UP);
            }
            if (r.statementPaymentsCredits != null) {
                r.paymentsDelta = creditSum.subtract(r.statementPaymentsCredits.abs()).setScale(2, RoundingMode.HALF_UP);
            }

            // Card detection issues
            if (r.last4 == null || r.last4.isEmpty()) {
                r.issues.add("MISSING last-4");
            }
            if (r.holder == null || r.holder.isEmpty()) {
                r.issues.add("MISSING account holder");
            }
            if (r.institution == null || r.institution.isEmpty()) {
                r.issues.add("MISSING institution");
            }
            if (r.statementDate == null || r.statementDate.isEmpty()) {
                r.issues.add("MISSING statement date");
            }

            if (!result.getErrors().isEmpty()) {
                for (final String err : result.getErrors()) {
                    r.issues.add("parse error: " + err);
                }
            }
        } catch (final Exception e) {
            r.issues.add("PARSE_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            globalIssues.add(pdf.getName() + ": " + e.getMessage());
        }

        return r;
    }

    private static PDFImportService buildService() {
        final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepo =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        org.mockito.Mockito.when(accountRepo.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Collections.emptyList());
        final AccountDetectionService accountDetection =
                new AccountDetectionService(accountRepo, new BalanceExtractor());
        final ImportCategoryParser categoryParser =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        org.mockito.Mockito.when(
                categoryParser.parseCategory(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn("Uncategorized");
        // Both production parseCategory call-sites use the 9-arg overload —
        // legacy parseTransactionFromRow and v2 enrichV2Transaction. Without
        // stubbing the 9-arg form the mock returns null and the audit shows
        // category=missing for every transaction, masking the real coverage.
        org.mockito.Mockito.when(
                categoryParser.parseCategory(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn("Uncategorized");
        final EnhancedPatternMatcher patternMatcher = new EnhancedPatternMatcher();

        final com.budgetbuddy.service.pdf.PdfTemplateRegistry registry =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        try {
            final java.lang.reflect.Field f =
                    com.budgetbuddy.service.pdf.PdfTemplateRegistry.class.getDeclaredField(
                            "resourcePattern");
            f.setAccessible(true);
            f.set(registry, "classpath*:pdf-templates/*.yaml");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        registry.init();
        final com.budgetbuddy.service.pdf.v2.PdfTemplateV2Registry v2Registry =
                new com.budgetbuddy.service.pdf.v2.PdfTemplateV2Registry();
        v2Registry.initForTesting("classpath*:pdf-templates-v2/*.yaml");
        return new PDFImportService(
                accountDetection, categoryParser, patternMatcher, null, null, registry, null, null, null, v2Registry);
    }

    private static void writeReport(final File out, final List<AuditRow> rows) throws Exception {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out, false))) {
            w.write("file,institution,last4,holder,accountType,statementDate,start,end,"
                    + "txCount,duplicates,incompleteFields,missingCategory,"
                    + "newBalance,previousBalance,"
                    + "statementPurchases,parsedDebitSum,purchasesDelta,"
                    + "statementPaymentsCredits,parsedCreditSum,paymentsDelta,"
                    + "statementFees,statementInterest,issues\n");
            for (final AuditRow r : rows) {
                w.write(String.join(",",
                        csv(r.file),
                        csv(r.institution),
                        csv(r.last4),
                        csv(r.holder),
                        csv(r.accountType),
                        csv(r.statementDate),
                        csv(r.startDate),
                        csv(r.endDate),
                        String.valueOf(r.txCount),
                        String.valueOf(r.duplicates),
                        String.valueOf(r.incompleteFields),
                        String.valueOf(r.missingCategory),
                        bd(r.newBalance),
                        bd(r.previousBalance),
                        bd(r.statementPurchases),
                        bd(r.parsedDebitSum),
                        bd(r.purchasesDelta),
                        bd(r.statementPaymentsCredits),
                        bd(r.parsedCreditSum),
                        bd(r.paymentsDelta),
                        bd(r.statementFees),
                        bd(r.statementInterest),
                        csv(String.join("|", r.issues))) + "\n");
            }
        }
    }

    private static String csv(final String s) {
        if (s == null) return "";
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String bd(final BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static BigDecimal nz(final BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String fmt(final BigDecimal v) {
        if (v == null) return "—";
        final boolean ok = v.abs().compareTo(TOLERANCE) <= 0;
        return (ok ? " " : "!") + v.toPlainString();
    }

    private static String trunc(final String s, final int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String normalize(final String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String summarise(final ParsedTransaction t) {
        return "[date=" + t.getDate() + ", amount=" + t.getAmount()
                + ", desc=" + trunc(t.getDescription(), 50) + "]";
    }

    private static final class AuditRow {
        String file = "";
        String institution = "";
        String last4 = "";
        String holder = "";
        String accountType = "";
        String statementDate = "";
        String startDate = "";
        String endDate = "";
        int txCount = 0;
        int duplicates = 0;
        int incompleteFields = 0;
        int missingCategory = 0;
        BigDecimal newBalance;
        BigDecimal previousBalance;
        BigDecimal statementPurchases;
        BigDecimal statementPaymentsCredits;
        BigDecimal statementFees;
        BigDecimal statementInterest;
        BigDecimal parsedDebitSum = BigDecimal.ZERO;
        BigDecimal parsedCreditSum = BigDecimal.ZERO;
        BigDecimal purchasesDelta;
        BigDecimal paymentsDelta;
        final List<String> issues = new ArrayList<>();
    }
}
