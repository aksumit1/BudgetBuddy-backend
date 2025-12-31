package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

/**
 * Tests for "Send general inquiries to" false positive bug fix
 */
@DisplayName("PDFImportService Username Detection - Send General Inquiries False Positive Test")
public class PDFImportServiceUsernameDetectionSendInquiriesTest {

    private PDFImportService pdfImportService;
    private Method isValidNameFormat;

    @BeforeEach
    void setUp() throws Exception {
        pdfImportService = new PDFImportService(null, null, null, null, null);
        
        isValidNameFormat = PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
    }

    @Test
    @DisplayName("Should reject 'Send general inquiries to' as a username")
    void testFalsePositive_SendGeneralInquiriesTo() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Send general inquiries to"),
            "Should reject 'Send general inquiries to'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "send general inquiries to"),
            "Should reject 'send general inquiries to' (lowercase)");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "SEND GENERAL INQUIRIES TO"),
            "Should reject 'SEND GENERAL INQUIRIES TO' (uppercase)");
    }

    @Test
    @DisplayName("Should reject 'general inquiries' as a username")
    void testFalsePositive_GeneralInquiries() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "general inquiries"),
            "Should reject 'general inquiries'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "General Inquiries"),
            "Should reject 'General Inquiries' (title case)");
    }

    @Test
    @DisplayName("Should reject phrases starting with 'send' and containing 'inquir'")
    void testFalsePositive_SendInquiriesPattern() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Send inquiries to"),
            "Should reject 'Send inquiries to'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Send all inquiries"),
            "Should reject 'Send all inquiries'");
    }

    @Test
    @DisplayName("Should still accept valid names that don't match instruction patterns")
    void testPositive_ValidNamesStillAccepted() throws Exception {
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "John Doe"),
            "Should accept 'John Doe' (valid name)");
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "JANE SMITH"),
            "Should accept 'JANE SMITH' (valid all-caps name)");
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "Mary-Jane Watson"),
            "Should accept 'Mary-Jane Watson' (valid hyphenated name)");
    }
}

