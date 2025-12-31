package com.budgetbuddy.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Scans file content for potentially malicious patterns
 * Detects:
 * - Script injection attempts
 * - SQL injection patterns
 * - Command injection patterns
 * - Suspicious binary content
 * - Embedded executables
 */
@Component
public class FileContentScanner {

    private static final Logger logger = LoggerFactory.getLogger(FileContentScanner.class);

    // Suspicious patterns to detect
    private static final List<Pattern> SUSPICIOUS_PATTERNS = Arrays.asList(
            // Script injection
            Pattern.compile("(?i)<script[^>]*>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)javascript:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)onerror\\s*=", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)onload\\s*=", Pattern.CASE_INSENSITIVE),
            
            // SQL injection
            Pattern.compile("(?i)(union|select|insert|update|delete|drop|create|alter|exec|execute)\\s+", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i);\\s*(drop|delete|truncate)", Pattern.CASE_INSENSITIVE),
            
            // Command injection
            Pattern.compile("(?i)(\\||&|;|`|\\$\\()", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)(cmd|command|powershell|bash|sh)\\s*[=/]", Pattern.CASE_INSENSITIVE),
            
            // Path traversal in content
            Pattern.compile("\\.\\.(/|\\\\)", Pattern.CASE_INSENSITIVE),
            
            // Suspicious file references
            Pattern.compile("(?i)(/etc/passwd|/etc/shadow|/windows/system32|C:\\\\windows)", Pattern.CASE_INSENSITIVE)
    );

    // Binary file signatures (executables)
    private static final Map<String, byte[]> EXECUTABLE_SIGNATURES = Map.of(
            "PE", new byte[]{(byte)0x4D, (byte)0x5A}, // PE executable (Windows)
            "ELF", new byte[]{(byte)0x7F, (byte)0x45, (byte)0x4C, (byte)0x46}, // ELF executable (Linux)
            "MACHO", new byte[]{(byte)0xFE, (byte)0xED, (byte)0xFA, (byte)0xCE} // Mach-O executable (macOS)
    );

    /**
     * Scan file content for malicious patterns
     *
     * @param inputStream File input stream
     * @param fileName File name (for context)
     * @return ScanResult with findings
     */
    public ScanResult scanFile(InputStream inputStream, String fileName) throws IOException {
        ScanResult result = new ScanResult();
        
        // Read file content (limit to first 1MB for scanning)
        byte[] buffer = new byte[1024 * 1024]; // 1MB
        int bytesRead = inputStream.read(buffer);
        
        if (bytesRead <= 0) {
            result.setSafe(true);
            return result;
        }

        // Check for binary executable signatures
        for (Map.Entry<String, byte[]> entry : EXECUTABLE_SIGNATURES.entrySet()) {
            if (startsWith(buffer, entry.getValue())) {
                result.addFinding("EXECUTABLE_DETECTED", 
                        String.format("Detected %s executable signature in file", entry.getKey()));
                result.setSafe(false);
            }
        }

        // Check for suspicious patterns in text content
        // Only scan if file appears to be text (not binary)
        // CRITICAL: Skip ALL text pattern scanning for PDF files - they contain legitimate user data
        // PDF files can contain characters like |, &, ;, etc. in transaction descriptions, merchant names, etc.
        // PDF files should only be checked for binary executable signatures, not text injection patterns
        boolean isPDF = fileName != null && fileName.toLowerCase().endsWith(".pdf");
        boolean isPDFBinary = bytesRead >= 4 && 
                              buffer[0] == 0x25 && buffer[1] == 0x50 && 
                              buffer[2] == 0x44 && buffer[3] == 0x46; // "%PDF" signature
        
        // Skip text pattern scanning entirely for PDF files (both by extension and binary signature)
        boolean shouldSkipTextScanning = isPDF || isPDFBinary;
        
        if (isTextContent(buffer, bytesRead) && !shouldSkipTextScanning) {
            String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
            
            for (Pattern pattern : SUSPICIOUS_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    result.addFinding("SUSPICIOUS_PATTERN", 
                            String.format("Found suspicious pattern: %s", pattern.pattern()));
                    result.setSafe(false);
                }
            }
        } else if (shouldSkipTextScanning) {
            // PDF file detected - skip text pattern scanning
            logger.debug("Skipping text pattern scanning for PDF file: {}", fileName);
        } else {
            // Binary file - check for high entropy (could be encrypted/compressed malware)
            double entropy = calculateEntropy(buffer, bytesRead);
            if (entropy > 7.5) { // High entropy threshold
                result.addFinding("HIGH_ENTROPY", 
                        String.format("File has high entropy (%.2f), may be encrypted or compressed", entropy));
                // Don't mark as unsafe for high entropy alone (PDFs, images are compressed)
                // But log it for review
                logger.warn("High entropy detected in file {}: {}", fileName, entropy);
            }
        }

        return result;
    }

    /**
     * Check if content appears to be text
     */
    private boolean isTextContent(byte[] buffer, int length) {
        int textBytes = 0;
        for (int i = 0; i < Math.min(length, 1024); i++) { // Check first 1KB
            byte b = buffer[i];
            // Text bytes: printable ASCII, tab, newline, carriage return
            if ((b >= 0x09 && b <= 0x0D) || (b >= 0x20 && b <= 0x7E) || (b >= 0x80 && b <= 0xFF)) {
                textBytes++;
            }
        }
        // Consider text if >80% of bytes are text-like
        return (double) textBytes / Math.min(length, 1024) > 0.8;
    }

    /**
     * Calculate Shannon entropy of byte array
     */
    private double calculateEntropy(byte[] buffer, int length) {
        Map<Byte, Integer> frequency = new HashMap<>();
        for (int i = 0; i < length; i++) {
            frequency.put(buffer[i], frequency.getOrDefault(buffer[i], 0) + 1);
        }

        double entropy = 0.0;
        for (Integer count : frequency.values()) {
            double probability = (double) count / length;
            entropy -= probability * (Math.log(probability) / Math.log(2));
        }
        return entropy;
    }

    /**
     * Check if byte array starts with prefix
     */
    private boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scan result containing findings
     */
    public static class ScanResult {
        private boolean safe = true;
        private final List<String> findings = new ArrayList<>();

        public boolean isSafe() {
            return safe;
        }

        public void setSafe(boolean safe) {
            this.safe = safe;
        }

        public List<String> getFindings() {
            return new ArrayList<>(findings);
        }

        public void addFinding(String type, String message) {
            findings.add(String.format("[%s] %s", type, message));
        }

        public boolean hasFindings() {
            return !findings.isEmpty();
        }
    }
}

