
package com.budgetbuddy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileContentScannerTest {

    private FileContentScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new FileContentScanner();
    }

    @Test
    void testScanFile_EmptyFile_ShouldBeSafe() throws IOException {
        // Given
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "empty.txt");
        
        // Then
        assertTrue(result.isSafe());
        assertFalse(result.hasFindings());
    }

    @Test
    void testScanFile_SafeTextFile_ShouldBeSafe() throws IOException {
        // Given
        String content = "This is a safe text file with normal content.";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "safe.txt");
        
        // Then
        assertTrue(result.isSafe());
        assertFalse(result.hasFindings());
    }

    @Test
    void testScanFile_WithScriptInjection_ShouldDetect() throws IOException {
        // Given
        String content = "<script>alert('xss')</script>";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "malicious.html");
        
        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
        assertTrue(result.getFindings().stream()
                .anyMatch(f -> f.contains("SUSPICIOUS_PATTERN")));
    }

    @Test
    void testScanFile_WithSQLInjection_ShouldDetect() throws IOException {
        // Given
        String content = "SELECT * FROM users WHERE id = 1; DROP TABLE users;";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "sql.txt");
        
        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFile_WithCommandInjection_ShouldDetect() throws IOException {
        // Given
        String content = "test; rm -rf /";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "command.txt");
        
        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFile_PDFFile_ShouldSkipTextScanning() throws IOException {
        // Given - PDF signature
        byte[] pdfContent = new byte[]{(byte)0x25, (byte)0x50, (byte)0x44, (byte)0x46, 
                                       0x2D, 0x31, 0x2E, 0x34, 0x0A};
        InputStream inputStream = new ByteArrayInputStream(pdfContent);
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "document.pdf");
        
        // Then - PDF files should skip text pattern scanning (may contain suspicious patterns legitimately)
        // Only executable detection should run
        assertTrue(result.isSafe() || result.getFindings().isEmpty() || 
                   result.getFindings().stream().noneMatch(f -> f.contains("SUSPICIOUS_PATTERN")));
    }

    @Test
    void testScanFile_PDFByExtension_ShouldSkipTextScanning() throws IOException {
        // Given - PDF by extension (even without PDF signature in first bytes)
        String content = "This might contain | & ; characters which are normal in PDFs";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "statement.pdf");
        
        // Then - PDF files should skip text pattern scanning
        assertTrue(result.isSafe());
    }

    @Test
    void testScanFile_WithExecutableSignature_ShouldDetect() throws IOException {
        // Given - PE executable signature
        byte[] peSignature = new byte[]{(byte)0x4D, (byte)0x5A, 0x00, 0x00};
        InputStream inputStream = new ByteArrayInputStream(peSignature);
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "file.exe");
        
        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
        assertTrue(result.getFindings().stream()
                .anyMatch(f -> f.contains("EXECUTABLE_DETECTED")));
    }

    @Test
    void testScanFile_WithPathTraversal_ShouldDetect() throws IOException {
        // Given
        String content = "../../etc/passwd";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "traversal.txt");
        
        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFile_WithSuspiciousFileReference_ShouldDetect() throws IOException {
        // Given
        String content = "/etc/passwd content here";
        InputStream inputStream = new ByteArrayInputStream(content.getBytes());
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "reference.txt");
        
        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFile_BinaryContent_ShouldHandle() throws IOException {
        // Given - Binary content (not text)
        byte[] binaryContent = new byte[100];
        for (int i = 0; i < 100; i++) {
            binaryContent[i] = (byte)(i % 256);
        }
        InputStream inputStream = new ByteArrayInputStream(binaryContent);
        
        // When
        FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "binary.bin");
        
        // Then - Should not crash and should return a result
        assertNotNull(result);
    }

    @Test
    void testScanResult_Methods() {
        // Given
        FileContentScanner.ScanResult result = new FileContentScanner.ScanResult();
        
        // When/Then
        assertTrue(result.isSafe());
        assertFalse(result.hasFindings());
        assertTrue(result.getFindings().isEmpty());
        
        result.addFinding("TEST", "Test finding");
        assertTrue(result.hasFindings());
        assertEquals(1, result.getFindings().size());
        assertTrue(result.getFindings().get(0).contains("TEST"));
        
        result.setSafe(false);
        assertFalse(result.isSafe());
    }
}

