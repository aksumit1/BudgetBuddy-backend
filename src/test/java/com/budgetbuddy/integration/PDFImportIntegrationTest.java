package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.service.PDFImportService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PDF import with actual PDF files
 * Tests end-to-end PDF parsing with real PDF creation
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@Import(AWSTestConfiguration.class)
@ActiveProfiles("test")
@DisplayName("PDF Import Integration Tests")
class PDFImportIntegrationTest {

    @Autowired
    private PDFImportService pdfImportService;

    @BeforeEach
    void setUp() {
        // Service is autowired
    }

    @Test
    @DisplayName("Should parse PDF with standard credit card statement format")
    void testPDFImport_StandardCreditCardStatement() throws Exception {
        String pdfText = """
            Account Summary
            Trans. Post date date Description Amount
            Payments, Credits and Adjustments
            11/28 11/28 AUTOPAY 999990000012756RAUTOPAY AUTO-PMT -$436.80
            Standard Purchases
            11/25 11/25 SAFEWAY #1444 BELLEVUE WA $14.27
            11/26 11/26 PCC - ISSAQUAH ISSAQUAH WA $37.25
            11/29 11/29 SUBWAY 2874 BELLEVUE WA $22.01
            11/30 11/30 Pet Supplies Plus 4445 Bellevue WA $14.00
            12/01 12/01 CURSOR, AI POWERED IDE NEW YORK NY $66.12
            Purchases Prior to 11/25/25
            11/03 11/05 STARBUCKS STORE 00331 TUKWILA WA $13.02
            11/04 11/05 SQ *JAYAM'S TIFFINS AN Bellevue WA $14.48
            11/06 11/06 THE HOME DEPOT #4723 REDMOND WA $5.81
            11/06 11/06 PROCLUB BELLEVUE BELLEVUE WA $7.85
            """;

        InputStream pdfStream = createPDFFromText(pdfText, "statement_2025.pdf");
        PDFImportService.ImportResult result = pdfImportService.parsePDF(pdfStream, "statement_2025.pdf", null, null);

        assertTrue(result.getSuccessCount() > 0, "Should parse at least some transactions");
        assertTrue(result.getFailureCount() < result.getSuccessCount(), 
                   "Should have more successes than failures");
        
        // Verify specific transactions
        boolean foundSafeway = result.getTransactions().stream()
            .anyMatch(t -> t.getDescription().contains("SAFEWAY") && 
                          t.getAmount().compareTo(new BigDecimal("14.27")) == 0);
        assertTrue(foundSafeway, "Should parse SAFEWAY transaction");
        
        boolean foundAutopay = result.getTransactions().stream()
            .anyMatch(t -> t.getDescription().contains("AUTOPAY") && 
                          t.getAmount().compareTo(new BigDecimal("-436.80")) == 0);
        assertTrue(foundAutopay, "Should parse negative AUTOPAY amount");
    }

    @Test
    @DisplayName("Should parse PDF with bank statement format")
    void testPDFImport_BankStatementFormat() throws Exception {
        String pdfText = """
            Bank Statement
            Statement Period: 01/01/2025 - 01/31/2025
            Date\tDescription\tAmount\tBalance
            01/15/2025\tDEPOSIT\t$2000.00\t$2000.00
            01/16/2025\tACH DEBIT GROCERY STORE\t-$50.00\t$1950.00
            01/17/2025\tCHECK #1234\t-$100.00\t$1850.00
            01/18/2025\tONLINE TRANSFER\t-$25.00\t$1825.00
            """;

        InputStream pdfStream = createPDFFromText(pdfText, "bank_statement_2025.pdf");
        PDFImportService.ImportResult result = pdfImportService.parsePDF(pdfStream, "bank_statement_2025.pdf", null, null);

        assertTrue(result.getSuccessCount() >= 4, "Should parse all 4 transactions");
        
        // Verify deposit transaction
        boolean foundDeposit = result.getTransactions().stream()
            .anyMatch(t -> t.getDescription().contains("DEPOSIT") && 
                          t.getAmount().compareTo(new BigDecimal("2000.00")) == 0);
        assertTrue(foundDeposit, "Should parse deposit transaction");
    }

    @Test
    @DisplayName("Should parse PDF with year in filename")
    void testPDFImport_YearInFilename() throws Exception {
        String pdfText = """
            Date\tDescription\tAmount
            12/01\tTRANSACTION $14.27
            """;

        InputStream pdfStream = createPDFFromText(pdfText, "statement_2025.pdf");
        PDFImportService.ImportResult result = pdfImportService.parsePDF(pdfStream, "statement_2025.pdf", null, null);

        assertEquals(1, result.getSuccessCount(), "Should parse transaction");
        
        PDFImportService.ParsedTransaction tx = result.getTransactions().get(0);
        assertEquals(2025, tx.getDate().getYear(), "Should infer year 2025 from filename");
    }

    @Test
    @DisplayName("Should parse PDF with year in statement period")
    void testPDFImport_YearInStatementPeriod() throws Exception {
        String pdfText = """
            Statement Period: 01/01/2024 - 01/31/2024
            Date\tDescription\tAmount
            12/01\tTRANSACTION $14.27
            """;

        InputStream pdfStream = createPDFFromText(pdfText, "statement.pdf");
        PDFImportService.ImportResult result = pdfImportService.parsePDF(pdfStream, "statement.pdf", null, null);

        assertEquals(1, result.getSuccessCount(), "Should parse transaction");
        
        PDFImportService.ParsedTransaction tx = result.getTransactions().get(0);
        // Should infer year 2024 from statement period
        assertEquals(2024, tx.getDate().getYear(), "Should infer year from statement period");
    }

    @Test
    @DisplayName("Should handle PDF with mixed date formats")
    void testPDFImport_MixedDateFormats() throws Exception {
        String pdfText = """
            Date\tDescription\tAmount
            01/15/2024\tTRANSACTION 1 $14.27
            01-16-2024\tTRANSACTION 2 $25.50
            2024-01-17\tTRANSACTION 3 $36.75
            01/18\tTRANSACTION 4 $47.90
            """;

        InputStream pdfStream = createPDFFromText(pdfText, "mixed_dates_2024.pdf");
        PDFImportService.ImportResult result = pdfImportService.parsePDF(pdfStream, "mixed_dates_2024.pdf", null, null);

        assertEquals(4, result.getSuccessCount(), "Should parse all transactions with mixed date formats");
    }

    @Test
    @DisplayName("Should handle PDF with multiple pages")
    void testPDFImport_MultiplePages() throws Exception {
        String page1Text = """
            Page 1
            Date\tDescription\tAmount
            01/15\tTRANSACTION 1 $14.27
            01/16\tTRANSACTION 2 $25.50
            """;
        
        String page2Text = """
            Page 2
            Date\tDescription\tAmount
            01/17\tTRANSACTION 3 $36.75
            01/18\tTRANSACTION 4 $47.90
            """;

        InputStream pdfStream = createMultiPagePDF(new String[]{page1Text, page2Text}, "multipage_2025.pdf");
        PDFImportService.ImportResult result = pdfImportService.parsePDF(pdfStream, "multipage_2025.pdf", null, null);

        assertTrue(result.getSuccessCount() >= 4, "Should parse transactions from all pages");
    }

    @Test
    @DisplayName("Should handle PDF with invalid format")
    void testPDFImport_InvalidPDF_ThrowsException() {
        InputStream invalidStream = new ByteArrayInputStream("Not a PDF file".getBytes());
        
        AppException exception = assertThrows(AppException.class, () -> {
            pdfImportService.parsePDF(invalidStream, "invalid.pdf", null, null);
        });
        
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Invalid PDF file format"));
    }

    @Test
    @DisplayName("Should handle empty PDF gracefully")
    void testPDFImport_EmptyPDF_ThrowsException() {
        InputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        
        AppException exception = assertThrows(AppException.class, () -> {
            pdfImportService.parsePDF(emptyStream, "empty.pdf", null, null);
        });
        
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    /**
     * Create a simple PDF from text content
     */
    private InputStream createPDFFromText(String text, String fileName) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                PDFont font = new PDType1Font(Standard14Fonts.FontName.COURIER);
                contentStream.setFont(font, 10);
                contentStream.setLeading(12);
                contentStream.newLineAtOffset(25, 750);
                
                String[] lines = text.split("\n");
                for (String line : lines) {
                    if (line != null && !line.trim().isEmpty()) {
                        // Replace tabs with spaces (Courier font doesn't support tab characters)
                        String cleanedLine = line.trim().replace('\t', ' ');
                        contentStream.showText(cleanedLine);
                        contentStream.newLine();
                    }
                }
                
                contentStream.endText();
            }
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            document.close();
            
            return new ByteArrayInputStream(baos.toByteArray());
        }
    }

    /**
     * Create a multi-page PDF from text content
     */
    private InputStream createMultiPagePDF(String[] pageTexts, String fileName) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    PDFont font = new PDType1Font(Standard14Fonts.FontName.COURIER);
                    contentStream.setFont(font, 10);
                    contentStream.setLeading(12);
                    contentStream.newLineAtOffset(25, 750);
                    
                    String[] lines = pageText.split("\n");
                    for (String line : lines) {
                        if (line != null && !line.trim().isEmpty()) {
                            // Replace tabs with spaces (Courier font doesn't support tab characters)
                            String cleanedLine = line.trim().replace('\t', ' ');
                            contentStream.showText(cleanedLine);
                            contentStream.newLine();
                        }
                    }
                    
                    contentStream.endText();
                }
            }
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            document.close();
            
            return new ByteArrayInputStream(baos.toByteArray());
        }
    }
}
