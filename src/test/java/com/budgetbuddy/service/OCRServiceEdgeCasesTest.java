package com.budgetbuddy.service;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/** Edge cases and boundary condition tests for OCRService */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@ExtendWith(MockitoExtension.class)
class OCRServiceEdgeCasesTest {

    private OCRService ocrService;

    @BeforeEach
    void setUp() {
        // Note: This will fail if Tesseract is not installed, but that's OK for unit tests
        // In production, Tesseract will be available in Docker container
        try {
            ocrService = new OCRService();
        } catch (Exception e) {
            // Skip tests if OCR service cannot be initialized (Tesseract not installed)
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "Tesseract not available for testing");
        }
    }

    @Test
    void testExtractTextFromPDFNullInputStream() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ocrService.extractTextFromPDF(null, Collections.singletonList("eng"));
                });
    }

    @Test
    void testExtractTextFromPDFEmptyInputStream() {
        final InputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        assertThrows(
                RuntimeException.class,
                () -> {
                    ocrService.extractTextFromPDF(emptyStream, Collections.singletonList("eng"));
                });
    }

    @Test
    void testExtractTextFromPDFNullLanguages() {
        // Should default to English
        final InputStream stream = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));
        // This will fail because it's not a valid PDF, but should handle null languages gracefully
        assertThrows(
                RuntimeException.class,
                () -> {
                    ocrService.extractTextFromPDF(stream, null);
                });
    }

    @Test
    void testExtractTextFromPDFEmptyLanguages() {
        // Should default to English
        final InputStream stream = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                RuntimeException.class,
                () -> {
                    ocrService.extractTextFromPDF(stream, Collections.emptyList());
                });
    }

    @Test
    void testExtractTextFromImageNullInputStream() {
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    ocrService.extractTextFromImage(null, Collections.singletonList("eng"));
                });
    }

    @Test
    void testExtractTextFromImageInvalidFormat() {
        // Invalid image format
        final InputStream invalidStream = new ByteArrayInputStream("not an image".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                RuntimeException.class,
                () -> {
                    ocrService.extractTextFromImage(
                            invalidStream, Collections.singletonList("eng"));
                });
    }

    @Test
    void testIsScannedPDFNullInputStream() {
        assertFalse(ocrService.isScannedPDF(null));
    }

    @Test
    void testIsScannedPDFEmptyInputStream() {
        final InputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        // Should return false (assume text-based on error)
        assertFalse(ocrService.isScannedPDF(emptyStream));
    }

    @Test
    void testDetectLanguagesNullText() {
        final List<String> languages = ocrService.detectLanguages(null);
        assertEquals(1, languages.size());
        assertEquals("eng", languages.get(0));
    }

    @Test
    void testDetectLanguagesEmptyText() {
        final List<String> languages = ocrService.detectLanguages("");
        assertEquals(1, languages.size());
        assertEquals("eng", languages.get(0));
    }

    @Test
    void testDetectLanguagesWhitespaceOnly() {
        final List<String> languages = ocrService.detectLanguages("   \n\t  ");
        assertEquals(1, languages.size());
        assertEquals("eng", languages.get(0));
    }

    @Test
    void testDetectLanguagesChinese() {
        final List<String> languages = ocrService.detectLanguages("账户号码 1234");
        assertTrue(languages.contains("chi_sim"));
    }

    @Test
    void testDetectLanguagesJapanese() {
        final List<String> languages = ocrService.detectLanguages("口座番号 1234");
        assertTrue(languages.contains("jpn"));
    }

    @Test
    void testDetectLanguagesRussian() {
        final List<String> languages = ocrService.detectLanguages("Номер счета 1234");
        assertTrue(languages.contains("rus"));
    }

    @Test
    void testDetectLanguagesArabic() {
        final List<String> languages = ocrService.detectLanguages("رقم الحساب 1234");
        assertTrue(languages.contains("ara"));
    }

    @Test
    void testGetSupportedLanguages() {
        final List<String> languages = ocrService.getSupportedLanguages();
        assertNotNull(languages);
        assertFalse(languages.isEmpty());
        assertTrue(languages.contains("eng"));
        assertTrue(languages.contains("fra"));
        assertTrue(languages.contains("deu"));
    }
}
