package com.budgetbuddy.service;

import com.budgetbuddy.service.AccountDetectionService.DetectedAccount;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * One-shot batch harness: runs every PDF in a directory through PDFImportService.parsePDF()
 * and writes a CSV with the detected account, last4, holder, and per-statement totals so we
 * can spot silent drops. Enabled only when -Dpdf.batch.dir=&lt;path&gt; is supplied so it
 * doesn't run in CI.
 */
@EnabledIfSystemProperty(named = "pdf.batch.dir", matches = ".+")
class PdfBatchHarnessTest {

    @Test
    void scanAllPdfs() throws Exception {
        final String dirStr = System.getProperty("pdf.batch.dir");
        final File dir = new File(dirStr);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + dirStr);
        }
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) {
            System.out.println("No PDFs found in " + dirStr);
            return;
        }
        Arrays.sort(pdfs, Comparator.comparing(File::getName));

        final com.budgetbuddy.repository.dynamodb.AccountRepository accountRepo =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        org.mockito.Mockito.when(accountRepo.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Collections.emptyList());
        final AccountDetectionService accountDetection =
                new AccountDetectionService(accountRepo, new BalanceExtractor());
        final ImportCategoryParser categoryParser =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        final EnhancedPatternMatcher patternMatcher = new EnhancedPatternMatcher();

        // Wire the YAML template registry — the legacy 4-arg constructor passes null
        // here, which silently disables the Amex / Apple Card / etc. YAML templates
        // and produces a misleading "transactions dropped" picture. Production has
        // this bean autowired, so the harness must too.
        final com.budgetbuddy.service.pdf.PdfTemplateRegistry pdfTemplateRegistry =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        try {
            final java.lang.reflect.Field f =
                    com.budgetbuddy.service.pdf.PdfTemplateRegistry.class.getDeclaredField(
                            "resourcePattern");
            f.setAccessible(true);
            f.set(pdfTemplateRegistry, "classpath*:pdf-templates/*.yaml");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        pdfTemplateRegistry.init();

        final PDFImportService svc =
                new PDFImportService(
                        accountDetection,
                        categoryParser,
                        patternMatcher,
                        null,
                        null,
                        pdfTemplateRegistry,
                        null,
                        null,
                        null,
                        null);

        final Path outCsv =
                Paths.get(
                        System.getProperty(
                                "pdf.batch.out", "/tmp/pdf_batch_report.csv"));
        final Path outDetail =
                Paths.get(
                        System.getProperty(
                                "pdf.batch.detail", "/tmp/pdf_batch_transactions.csv"));
        Files.createDirectories(outCsv.getParent());

        int totalTx = 0;
        int totalSuccess = 0;
        int totalFailure = 0;
        final Map<String, Integer> institutionCounts = new TreeMap<>();
        final Map<String, Integer> last4Counts = new TreeMap<>();

        try (BufferedWriter w =
                        new BufferedWriter(new FileWriter(outCsv.toFile(), false));
                BufferedWriter dw =
                        new BufferedWriter(new FileWriter(outDetail.toFile(), false))) {

            w.write(
                    "file,institution,accountType,last4,holder,txCount,success,failure,total,newBalance,statementDate,start,end,errors,info\n");
            dw.write("file,date,amount,description,merchant,location,cardLast4,category\n");

            for (final File pdf : pdfs) {
                final String name = pdf.getName();
                String institution = "";
                String accountType = "";
                String last4 = "";
                String holder = "";
                int txCount = 0;
                int success = 0;
                int failure = 0;
                BigDecimal total = BigDecimal.ZERO;
                String newBalance = "";
                String stmtDate = "";
                String startDate = "";
                String endDate = "";
                String errStr = "";
                String infoStr = "";

                try (FileInputStream in = new FileInputStream(pdf)) {
                    final ImportResult r = svc.parsePDF(in, name, "harness-user", null);
                    final DetectedAccount a = r.getDetectedAccount();
                    if (a != null) {
                        institution = nz(a.getInstitutionName());
                        accountType = nz(a.getAccountType());
                        last4 = nz(a.getAccountNumber());
                        holder = nz(a.getAccountHolderName());
                    }
                    txCount = r.getTransactions().size();
                    success = r.getSuccessCount();
                    failure = r.getFailureCount();
                    for (final ParsedTransaction t : r.getTransactions()) {
                        if (t.getAmount() != null) total = total.add(t.getAmount());
                        dw.write(
                                csv(name)
                                        + ","
                                        + csv(String.valueOf(t.getDate()))
                                        + ","
                                        + csv(String.valueOf(t.getAmount()))
                                        + ","
                                        + csv(t.getDescription())
                                        + ","
                                        + csv(t.getMerchantName())
                                        + ","
                                        + csv(t.getLocation())
                                        + ","
                                        + csv(t.getCardLastFour())
                                        + ","
                                        + csv(t.getCategoryPrimary())
                                        + "\n");
                    }
                    newBalance = String.valueOf(r.getNewBalance());
                    stmtDate = String.valueOf(r.getStatementDate());
                    startDate = String.valueOf(r.getStatementStartDate());
                    endDate = String.valueOf(r.getStatementEndDate());
                    errStr = String.join(" | ", r.getErrors());
                    infoStr = String.join(" | ", r.getInfoMessages());
                } catch (final Exception e) {
                    errStr = "PARSE_FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                }

                totalTx += txCount;
                totalSuccess += success;
                totalFailure += failure;
                institutionCounts.merge(institution.isEmpty() ? "(none)" : institution, 1, Integer::sum);
                final String l4key = last4.isEmpty() ? "(none)" : last4;
                last4Counts.merge(l4key, 1, Integer::sum);

                w.write(
                        csv(name)
                                + ","
                                + csv(institution)
                                + ","
                                + csv(accountType)
                                + ","
                                + csv(last4)
                                + ","
                                + csv(holder)
                                + ","
                                + txCount
                                + ","
                                + success
                                + ","
                                + failure
                                + ","
                                + csv(total.toPlainString())
                                + ","
                                + csv(newBalance)
                                + ","
                                + csv(stmtDate)
                                + ","
                                + csv(startDate)
                                + ","
                                + csv(endDate)
                                + ","
                                + csv(errStr)
                                + ","
                                + csv(infoStr)
                                + "\n");

                System.out.printf(
                        "%-58s | %-30s | last4=%-7s | holder=%-30s | tx=%3d (s=%d f=%d) | newBal=%-10s%n",
                        truncate(name, 58),
                        truncate(institution, 30),
                        truncate(last4, 7),
                        truncate(holder, 30),
                        txCount,
                        success,
                        failure,
                        truncate(newBalance, 10));
                if (!errStr.isEmpty()) {
                    System.out.println("   ERR: " + truncate(errStr, 180));
                }
            }
        }

        System.out.println();
        System.out.println("=== TOTALS ===");
        System.out.println("PDFs processed: " + pdfs.length);
        System.out.println("Total transactions parsed: " + totalTx);
        System.out.println("Success: " + totalSuccess + "  Failure: " + totalFailure);
        System.out.println();
        System.out.println("=== Institutions ===");
        institutionCounts.forEach((k, v) -> System.out.printf("  %-40s %d%n", k, v));
        System.out.println();
        System.out.println("=== Card last-4 ===");
        last4Counts.forEach((k, v) -> System.out.printf("  %-10s %d%n", k, v));
        System.out.println();
        System.out.println("CSV: " + outCsv);
        System.out.println("Detail: " + outDetail);
    }

    private static String nz(final String s) {
        return s == null ? "" : s;
    }

    private static String csv(final String raw) {
        if (raw == null || raw.equals("null")) return "";
        if (raw.indexOf(',') >= 0 || raw.indexOf('"') >= 0 || raw.indexOf('\n') >= 0) {
            return "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
    }

    private static String truncate(final String s, final int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
