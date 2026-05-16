package com.budgetbuddy.service;

import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.FileInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Full parsePDF probe on a single PDF — prints every parsed transaction so you
 * can spot missing rows, sign errors, or wrong amounts. Useful when a specific
 * statement looks off and you want to inspect the actual output without running
 * the whole audit harness. Enabled only via {@code -Dpdf.probe.pdf=path}.
 *
 * <pre>
 *   mvn test -Dtest=PdfDiagnosticProbe -Dpdf.probe.pdf=/path/to/Statement.pdf
 *   # Optional filter:
 *   mvn test -Dtest=PdfDiagnosticProbe \
 *       -Dpdf.probe.pdf=/path/to/Statement.pdf \
 *       -Dpdf.probe.filter=STARBUCKS
 * </pre>
 */
@EnabledIfSystemProperty(named = "pdf.probe.pdf", matches = ".+")
class PdfDiagnosticProbe {

    @Test
    void dump() throws Exception {
        final String path = System.getProperty("pdf.probe.pdf");
        final String filter = System.getProperty("pdf.probe.filter", "");

        final com.budgetbuddy.repository.dynamodb.AccountRepository repo =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        org.mockito.Mockito.when(repo.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Collections.emptyList());
        final AccountDetectionService det =
                new AccountDetectionService(repo, new BalanceExtractor());
        final ImportCategoryParser cat =
                org.mockito.Mockito.mock(ImportCategoryParser.class);
        final EnhancedPatternMatcher pm = new EnhancedPatternMatcher();
        final com.budgetbuddy.service.pdf.PdfTemplateRegistry reg =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        try {
            final java.lang.reflect.Field f =
                    com.budgetbuddy.service.pdf.PdfTemplateRegistry.class.getDeclaredField(
                            "resourcePattern");
            f.setAccessible(true);
            f.set(reg, "classpath*:pdf-templates/*.yaml");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        reg.init();
        final PDFImportService svc =
                new PDFImportService(det, cat, pm, null, null, reg, null, null, null, null);

        try (FileInputStream in = new FileInputStream(path)) {
            final java.io.File pdf = new java.io.File(path);
            final ImportResult r = svc.parsePDF(in, pdf.getName(), "probe-user", null);
            System.out.println();
            System.out.println("=== " + pdf.getName() + " ===");
            if (r.getDetectedAccount() != null) {
                System.out.printf("Institution: %s | Last4: %s | Holder: %s%n",
                        r.getDetectedAccount().getInstitutionName(),
                        r.getDetectedAccount().getAccountNumber(),
                        r.getDetectedAccount().getAccountHolderName());
            }
            System.out.printf("Statement date: %s   Period: %s → %s%n",
                    r.getStatementDate(), r.getStatementStartDate(), r.getStatementEndDate());
            System.out.printf("Stmt totals: purchases=%s payments=%s fees=%s interest=%s newBal=%s%n",
                    r.getPurchasesTotal(), r.getPaymentsAndCreditsTotal(),
                    r.getFeesChargedTotal(), r.getInterestChargedTotal(), r.getNewBalance());
            System.out.println();
            int idx = 0;
            for (final ParsedTransaction t : r.getTransactions()) {
                idx++;
                final String desc = t.getDescription() == null ? "" : t.getDescription();
                if (!filter.isEmpty() && !desc.toUpperCase().contains(filter.toUpperCase())) continue;
                System.out.printf("%3d %s %10s | %s%n",
                        idx, t.getDate(), t.getAmount(),
                        desc.length() > 90 ? desc.substring(0, 90) + "..." : desc);
            }
            System.out.println();
            System.out.println("Total transactions: " + r.getTransactions().size());
            if (!r.getErrors().isEmpty()) {
                System.out.println();
                System.out.println("Errors:");
                r.getErrors().forEach(e -> System.out.println("  " + e));
            }
        }
    }
}
