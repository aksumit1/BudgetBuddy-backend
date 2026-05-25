package com.budgetbuddy.service.pdf.v2;

import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.BalanceExtractor;
import com.budgetbuddy.service.EnhancedPatternMatcher;
import com.budgetbuddy.service.ImportCategoryParser;
import com.budgetbuddy.service.PDFImportService;
import java.util.Collections;

/**
 * Shared factory for tests that need a wired-up {@link PDFImportService}
 * pointing at the v1 + v2 template registries on the classpath. Mirrors the
 * pattern in {@link com.budgetbuddy.service.PdfLineByLineAuditTest} but
 * exposed here so multiple regression-pinning tests can reuse it.
 */
final class TestPdfImportFactory {

    private TestPdfImportFactory() { }

    static PDFImportService newSvc() {
        final com.budgetbuddy.repository.dynamodb.AccountRepository repo =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        org.mockito.Mockito.when(repo.findByUserId(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Collections.emptyList());
        final AccountDetectionService det = new AccountDetectionService(repo, new BalanceExtractor());
        final ImportCategoryParser cat = org.mockito.Mockito.mock(ImportCategoryParser.class);
        org.mockito.Mockito.when(cat.parseCategory(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn("Uncategorized");
        final EnhancedPatternMatcher pm = new EnhancedPatternMatcher();
        final com.budgetbuddy.service.pdf.PdfTemplateRegistry reg =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        try {
            final java.lang.reflect.Field f = com.budgetbuddy.service.pdf.PdfTemplateRegistry.class
                    .getDeclaredField("resourcePattern");
            f.setAccessible(true);
            f.set(reg, "classpath*:pdf-templates/*.yaml");
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        reg.init();
        final PdfTemplateV2Registry v2 = new PdfTemplateV2Registry();
        v2.initForTesting("classpath*:pdf-templates-v2/*.yaml");
        return new PDFImportService(det, cat, pm, null, null, reg, null, null, null, v2);
    }

    static AccountDetectionService newAccountDetectionService() {
        final com.budgetbuddy.repository.dynamodb.AccountRepository repo =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.repository.dynamodb.AccountRepository.class);
        return new AccountDetectionService(repo, new BalanceExtractor());
    }
}
