package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases and boundary condition tests for OCRService
 */
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
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Tesseract not available for testing");
        }
    }
    
    @Test
    void testExtractTextFromPDF_NullInputStream() {
        assertThrows(IllegalArgumentException.class, () -> {
            ocrService.extractTextFromPDF(null, Collections.singletonList("eng"));
        });
    }
    
    @Test
    void testExtractTextFromPDF_EmptyInputStream() {
        InputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        assertThrows(RuntimeException.class, () -> {
            ocrService.extractTextFromPDF(emptyStream, Collections.singletonList("eng"));
        });
    }
    
    @Test
    void testExtractTextFromPDF_NullLanguages() {
        // Should default to English
        InputStream stream = new ByteArrayInputStream("test".getBytes());
        // This will fail because it's not a valid PDF, but should handle null languages gracefully
        assertThrows(RuntimeException.class, () -> {
            ocrService.extractTextFromPDF(stream, null);
        });
    }
    
    @Test
    void testExtractTextFromPDF_EmptyLanguages() {
        // Should default to English
        InputStream stream = new ByteArrayInputStream("test".getBytes());
        assertThrows(RuntimeException.class, () -> {
            ocrService.extractTextFromPDF(stream, Collections.emptyList());
        });
    }
    
    @Test
    void testExtractTextFromImage_NullInputStream() {
        assertThrows(IllegalArgumentException.class, () -> {
            ocrService.extractTextFromImage(null, Collections.singletonList("eng"));
        });
    }
    
    @Test
    void testExtractTextFromImage_InvalidFormat() {
        // Invalid image format
        InputStream invalidStream = new ByteArrayInputStream("not an image".getBytes());
        assertThrows(RuntimeException.class, () -> {
            ocrService.extractTextFromImage(invalidStream, Collections.singletonList("eng"));
        });
    }
    
    @Test
    void testIsScannedPDF_NullInputStream() {
        assertFalse(ocrService.isScannedPDF(null));
    }
    
    @Test
    void testIsScannedPDF_EmptyInputStream() {
        InputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        // Should return false (assume text-based on error)
        assertFalse(ocrService.isScannedPDF(emptyStream));
    }
    
    @Test
    void testDetectLanguages_NullText() {
        List<String> languages = ocrService.detectLanguages(null);
        assertEquals(1, languages.size());
        assertEquals("eng", languages.get(0));
    }
    
    @Test
    void testDetectLanguages_EmptyText() {
        List<String> languages = ocrService.detectLanguages("");
        assertEquals(1, languages.size());
        assertEquals("eng", languages.get(0));
    }
    
    @Test
    void testDetectLanguages_WhitespaceOnly() {
        List<String> languages = ocrService.detectLanguages("   \n\t  ");
        assertEquals(1, languages.size());
        assertEquals("eng", languages.get(0));
    }
    
    @Test
    void testDetectLanguages_Chinese() {
        List<String> languages = ocrService.detectLanguages("账户号码 1234");
        assertTrue(languages.contains("chi_sim"));
    }
    
    @Test
    void testDetectLanguages_Japanese() {
        List<String> languages = ocrService.detectLanguages("口座番号 1234");
        assertTrue(languages.contains("jpn"));
    }
    
    @Test
    void testDetectLanguages_Russian() {
        List<String> languages = ocrService.detectLanguages("Номер счета 1234");
        assertTrue(languages.contains("rus"));
    }
    
    @Test
    void testDetectLanguages_Arabic() {
        List<String> languages = ocrService.detectLanguages("رقم الحساب 1234");
        assertTrue(languages.contains("ara"));
    }
    
    @Test
    void testGetSupportedLanguages() {
        List<String> languages = ocrService.getSupportedLanguages();
        assertNotNull(languages);
        assertFalse(languages.isEmpty());
        assertTrue(languages.contains("eng"));
        assertTrue(languages.contains("fra"));
        assertTrue(languages.contains("deu"));
    }
}

