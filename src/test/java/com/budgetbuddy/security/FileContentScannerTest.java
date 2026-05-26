package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileContentScannerTest {

    private FileContentScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new FileContentScanner();
    }

    @Test
    void testScanFileEmptyFileShouldBeSafe() throws IOException {
        // Given
        final InputStream inputStream = new ByteArrayInputStream(new byte[0]);

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "empty.txt");

        // Then
        assertTrue(result.isSafe());
        assertFalse(result.hasFindings());
    }

    @Test
    void testScanFileSafeTextFileShouldBeSafe() throws IOException {
        // Given
        final String content = "This is a safe text file with normal content.";
        final InputStream inputStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "safe.txt");

        // Then
        assertTrue(result.isSafe());
        assertFalse(result.hasFindings());
    }

    @Test
    void testScanFileWithScriptInjectionShouldDetect() throws IOException {
        // Given
        final String content = "<script>alert('xss')</script>";
        final InputStream inputStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final FileContentScanner.ScanResult result =
                scanner.scanFile(inputStream, "malicious.html");

        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("SUSPICIOUS_PATTERN")));
    }

    @Test
    void testScanFileWithSQLInjectionShouldDetect() throws IOException {
        // Given
        final String content = "SELECT * FROM users WHERE id = 1; DROP TABLE users;";
        final InputStream inputStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "sql.txt");

        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFileWithCommandInjectionShouldDetect() throws IOException {
        // Given
        final String content = "test; rm -rf /";
        final InputStream inputStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "command.txt");

        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFilePDFFileShouldSkipTextScanning() throws IOException {
        // Given - PDF signature
        final byte[] pdfContent =
                new byte[] {
                    (byte) 0x25, (byte) 0x50, (byte) 0x44, (byte) 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A
                };
        final InputStream inputStream = new ByteArrayInputStream(pdfContent);

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "document.pdf");

        // Then - PDF files should skip text pattern scanning (may contain suspicious patterns
        // legitimately)
        // Only executable detection should run
        assertTrue(
                result.isSafe()
                        || result.getFindings().isEmpty()
                        || result.getFindings().stream()
                                .noneMatch(f -> f.contains("SUSPICIOUS_PATTERN")));
    }

    @Test
    void testScanFilePDFByExtensionShouldSkipTextScanning() throws IOException {
        // Given - PDF by extension (even without PDF signature in first bytes)
        final String content = "This might contain | & ; characters which are normal in PDFs";
        final InputStream inputStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "statement.pdf");

        // Then - PDF files should skip text pattern scanning
        assertTrue(result.isSafe());
    }

    @Test
    void testScanFileWithExecutableSignatureShouldDetect() throws IOException {
        // Given - PE executable signature
        final byte[] peSignature = new byte[] {(byte) 0x4D, (byte) 0x5A, 0x00, 0x00};
        final InputStream inputStream = new ByteArrayInputStream(peSignature);

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "file.exe");

        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
        assertTrue(result.getFindings().stream().anyMatch(f -> f.contains("EXECUTABLE_DETECTED")));
    }

    @Test
    void testScanFileWithPathTraversalShouldDetect() throws IOException {
        // Given
        final String content = "../../etc/passwd";
        final InputStream inputStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "traversal.txt");

        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFileWithSuspiciousFileReferenceShouldDetect() throws IOException {
        // Given
        final String content = "/etc/passwd content here";
        final InputStream inputStream =
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "reference.txt");

        // Then
        assertFalse(result.isSafe());
        assertTrue(result.hasFindings());
    }

    @Test
    void testScanFileBinaryContentShouldHandle() throws IOException {
        // Given - Binary content (not text)
        final byte[] binaryContent = new byte[100];
        for (int i = 0; i < 100; i++) {
            binaryContent[i] = (byte) (i % 256);
        }
        final InputStream inputStream = new ByteArrayInputStream(binaryContent);

        // When
        final FileContentScanner.ScanResult result = scanner.scanFile(inputStream, "binary.bin");

        // Then - Should not crash and should return a result
        assertNotNull(result);
    }

    @Test
    void testScanResultMethods() {
        // Given
        final FileContentScanner.ScanResult result = new FileContentScanner.ScanResult();

        // When/Then
        assertTrue(result.isSafe());
        assertFalse(result.hasFindings());
        assertTrue(result.getFindings().isEmpty());

        result.addFinding("TEST", "Test finding");
        assertTrue(result.hasFindings());
        assertEquals(1, result.getFindings().size());
        assertTrue(result.getFindings().getFirst().contains("TEST"));

        result.setSafe(false);
        assertFalse(result.isSafe());
    }
}
