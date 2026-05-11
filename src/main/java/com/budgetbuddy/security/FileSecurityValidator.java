package com.budgetbuddy.security;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Security validator for file uploads and file system operations Protects against: - Path traversal
 * attacks - Malicious file uploads - File type spoofing - Oversized files - Dangerous file
 * extensions
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class FileSecurityValidator {

    private static final String INVALID_FILE_PATH_ABSOLUTE_PATHS_ARE_NOT =
            "Invalid file path: absolute paths are not allowed";

    private static final String INVALID_FILE_PATH_PATH_TRAVERSAL =
            "Invalid file path: path traversal detected";

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSecurityValidator.class);

    // Maximum file size: 10MB
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    // Dangerous file extensions that should never be allowed
    private static final Set<String> DANGEROUS_EXTENSIONS =
            new HashSet<>(
                    Arrays.asList(
                            "exe", "bat", "cmd", "com", "pif", "scr", "vbs", "js", "jar", "war",
                            "sh", "ps1", "dll", "so", "dylib", "app", "deb", "rpm", "msi", "apk",
                            "ipa"));

    // Pattern to detect path traversal attempts
    private static final Pattern PATH_TRAVERSAL_PATTERN =
            Pattern.compile(
                    "(\\.\\./|\\.\\.\\\\|%2e%2e%2f|%2e%2e%5c|%252e%252e%252f|%c0%ae%c0%ae%c0%af)");

    // Pattern to detect null bytes (used in path traversal)
    private static final Pattern NULL_BYTE_PATTERN = Pattern.compile("%00|\\x00");

    // Magic bytes for file type detection (first few bytes of file)
    private static final byte[] CSV_MAGIC =
            new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; // UTF-8 BOM
    private static final byte[] PDF_MAGIC = new byte[] {0x25, 0x50, 0x44, 0x46}; // %PDF
    private static final byte[] XLSX_MAGIC =
            new byte[] {
                0x50, 0x4B, 0x03, 0x04 // ZIP signature (XLSX is a ZIP file)
            };
    private static final byte[] XLS_MAGIC =
            new byte[] {
                (byte) 0xD0,
                (byte) 0xCF,
                0x11,
                (byte) 0xE0,
                (byte) 0xA1,
                (byte) 0xB1,
                0x1A,
                (byte) 0xE1
            }; // OLE2 signature

    /**
     * Validate file upload for security
     *
     * @param file The uploaded file
     * @param allowedTypes Set of allowed file types (e.g., "csv", "pdf")
     * @throws AppException if file is invalid or malicious
     */
    public void validateFileUpload(final MultipartFile file, final Set<String> allowedTypes) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "File is required and cannot be empty");
        }

        final String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "File name is required");
        }

        // 1. Validate filename for path traversal
        validateFileName(originalFilename);

        // 2. Validate file extension
        final String extension = getFileExtension(originalFilename);
        validateFileExtension(extension, allowedTypes);

        // 3. Validate file size
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    String.format(
                            "File size exceeds maximum allowed size of %d MB",
                            MAX_FILE_SIZE / (1024 * 1024)));
        }

        // 4. Validate content type matches extension
        validateContentType(file, extension);

        // 5. Validate magic bytes (file signature)
        try {
            validateMagicBytes(file, extension);
        } catch (IOException e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error reading file for magic byte validation: {}", e.getMessage());
            }
            throw new AppException(ErrorCode.INVALID_INPUT, "Unable to validate file content");
        }
    }

    /**
     * Validate S3 key for path traversal and malicious patterns
     *
     * @param key S3 object key
     * @throws AppException if key is invalid
     */
    public void validateS3Key(final String key) {
        if (key == null || key.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "S3 key cannot be null or empty");
        }

        // Check for path traversal patterns
        if (PATH_TRAVERSAL_PATTERN.matcher(key).find()) {
            LOGGER.warn("Path traversal attempt detected in S3 key: {}", key);
            throw new AppException(ErrorCode.INVALID_INPUT, INVALID_FILE_PATH_PATH_TRAVERSAL);
        }

        // Check for null bytes
        if (NULL_BYTE_PATTERN.matcher(key).find()) {
            LOGGER.warn("Null byte detected in S3 key: {}", key);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Invalid file path: null byte detected");
        }

        // Validate key length
        if (key.length() > 1024) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "S3 key is too long (maximum 1024 characters)");
        }

        // Ensure key doesn't start with / (S3 keys shouldn't start with /)
        if (key.startsWith("/")) {
            throw new AppException(ErrorCode.INVALID_INPUT, "S3 key cannot start with /");
        }
    }

    // Trusted system directories for absolute paths (for internal use only)
    // Note: System temp directory varies by OS (e.g., /tmp on Linux, /var/folders/.../T on macOS)
    private static final String SYSTEM_TEMP_DIR = System.getProperty("java.io.tmpdir", "/tmp");
    private static final Set<String> TRUSTED_SYSTEM_DIRECTORIES =
            new HashSet<>(Arrays.asList("/tmp", "/var/tmp", SYSTEM_TEMP_DIR));

    /**
     * Validate file path for path traversal
     *
     * @param filePath File path to validate
     * @return Normalized and validated path
     * @throws AppException if path is invalid
     */
    public Path validateFilePath(final String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "File path cannot be null or empty");
        }
        rejectUntrustedUnixAbsolutePath(filePath);
        rejectWindowsAbsolutePath(filePath);
        rejectNullByteInPath(filePath);
        return normalizeAndValidate(filePath);
    }

    /** Reject Unix-style absolute paths unless they sit inside a trusted system directory. */
    private void rejectUntrustedUnixAbsolutePath(final String filePath) {
        if (!filePath.startsWith("/")) {
            return;
        }
        if (isInTrustedSystemDirectory(filePath) || isWithinSystemTempDir(filePath)) {
            return;
        }
        LOGGER.warn("Absolute path detected (not in trusted directory): {}", filePath);
        throw new AppException(ErrorCode.INVALID_INPUT, INVALID_FILE_PATH_ABSOLUTE_PATHS_ARE_NOT);
    }

    /** Reject Windows-style absolute paths ("C:\..."). */
    private static void rejectWindowsAbsolutePath(final String filePath) {
        if (filePath.matches("^[A-Za-z]:[/\\\\].*")) {
            LOGGER.warn("Windows absolute path detected: {}", filePath);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, INVALID_FILE_PATH_ABSOLUTE_PATHS_ARE_NOT);
        }
    }

    /** Reject null bytes embedded in the raw path (before normalization). */
    private static void rejectNullByteInPath(final String filePath) {
        if (NULL_BYTE_PATTERN.matcher(filePath).find()) {
            LOGGER.warn("Null byte detected in file path: {}", filePath);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Invalid file path: null byte detected");
        }
    }

    /**
     * Run the post-normalize traversal/absolute-path checks. Anything thrown from Paths.get() is
     * wrapped into an AppException so callers see a uniform error.
     */
    private Path normalizeAndValidate(final String filePath) {
        try {
            final Path normalized = Paths.get(filePath).normalize();
            enforcePostNormalizationRules(normalized);
            return normalized;
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error validating file path: {}", e.getMessage());
            }
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid file path: " + e.getMessage());
        }
    }

    /** Post-normalization checks: traversal regex, trusted absolute, stray ".." segments. */
    private void enforcePostNormalizationRules(final Path normalized) {
        final String normalizedStr = normalized.toString();
        if (PATH_TRAVERSAL_PATTERN.matcher(normalizedStr).find()) {
            LOGGER.warn("Path traversal attempt detected after normalization: {}", normalizedStr);
            throw new AppException(ErrorCode.INVALID_INPUT, INVALID_FILE_PATH_PATH_TRAVERSAL);
        }
        if (normalized.isAbsolute()) {
            rejectUntrustedNormalizedAbsolutePath(normalized);
        }
        // Defense-in-depth: a malformed segment like "..foo" can survive Path.normalize().
        if (normalizedStr.contains("..")) {
            LOGGER.warn("Path traversal detected after normalization: {}", normalized);
            throw new AppException(ErrorCode.INVALID_INPUT, INVALID_FILE_PATH_PATH_TRAVERSAL);
        }
    }

    private void rejectUntrustedNormalizedAbsolutePath(final Path normalized) {
        final String normalizedStr = normalized.toString();
        if (isInTrustedSystemDirectory(normalizedStr) || isPathWithinSystemTempDir(normalized)) {
            return;
        }
        LOGGER.warn(
                "Absolute path detected after normalization (not in trusted directory): {}",
                normalized);
        throw new AppException(ErrorCode.INVALID_INPUT, INVALID_FILE_PATH_ABSOLUTE_PATHS_ARE_NOT);
    }

    /** True if {@code filePath} starts with any TRUSTED_SYSTEM_DIRECTORIES entry. */
    private static boolean isInTrustedSystemDirectory(final String filePath) {
        for (final String trustedDir : TRUSTED_SYSTEM_DIRECTORIES) {
            if (filePath.equals(trustedDir) || filePath.startsWith(trustedDir + "/")) {
                return true;
            }
        }
        return false;
    }

    /** True if the raw {@code filePath} normalizes to a location inside the system temp dir. */
    private static boolean isWithinSystemTempDir(final String filePath) {
        try {
            return Paths.get(filePath)
                    .normalize()
                    .startsWith(Paths.get(SYSTEM_TEMP_DIR).normalize());
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Error comparing paths: {}", e.getMessage());
            }
            return false;
        }
    }

    /** True if an already-normalized {@code path} is inside the system temp dir. */
    private static boolean isPathWithinSystemTempDir(final Path normalized) {
        try {
            return normalized.startsWith(Paths.get(SYSTEM_TEMP_DIR).normalize());
        } catch (Exception e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Error comparing normalized paths: {}", e.getMessage());
            }
            return false;
        }
    }

    /** Validate filename for security */
    private void validateFileName(final String filename) {
        // Check for path traversal
        if (PATH_TRAVERSAL_PATTERN.matcher(filename).find()) {
            LOGGER.warn("Path traversal attempt in filename: {}", filename);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Invalid file name: path traversal detected");
        }

        // Check for null bytes
        if (NULL_BYTE_PATTERN.matcher(filename).find()) {
            LOGGER.warn("Null byte in filename: {}", filename);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Invalid file name: null byte detected");
        }

        // Check filename length
        if (filename.length() > 255) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "File name is too long (maximum 255 characters)");
        }

        // Check for dangerous characters
        if (filename.contains("\0") || filename.contains("\r") || filename.contains("\n")) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "File name contains invalid characters");
        }
    }

    /** Validate file extension */
    private void validateFileExtension(String extension, final Set<String> allowedTypes) {
        if (extension == null || extension.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "File must have an extension");
        }

        extension = extension.toLowerCase(Locale.ROOT);

        // Check for dangerous extensions
        if (DANGEROUS_EXTENSIONS.contains(extension)) {
            LOGGER.warn("Dangerous file extension detected: {}", extension);
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "File type not allowed for security reasons");
        }

        // Check if extension is in allowed list
        if (allowedTypes != null && !allowedTypes.isEmpty() && !allowedTypes.contains(extension)) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    String.format(
                            "File type '%s' is not allowed. Allowed types: %s",
                            extension, allowedTypes));
        }
    }

    /** Validate content type matches file extension */
    private void validateContentType(final MultipartFile file, String extension) {
        final String contentType = file.getContentType();
        if (contentType == null) {
            // Content type is optional, but log warning
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("File has no content type: {}", file.getOriginalFilename());
            }
            return;
        }

        // Basic content type validation
        extension = extension.toLowerCase(Locale.ROOT);
        switch (extension) {
            case "csv":
                if (!"text/csv".equals(contentType)
                        && !"application/csv".equals(contentType)
                        && !contentType.startsWith("text/plain")) {
                    LOGGER.warn(
                            "Content type mismatch for CSV file: {} (expected text/csv)",
                            contentType);
                }
                break;
            case "pdf":
                if (!"application/pdf".equals(contentType)) {
                    LOGGER.warn(
                            "Content type mismatch for PDF file: {} (expected application/pdf)",
                            contentType);
                }
                break;
            case "xlsx":
                if (!"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        .equals(contentType)) {
                    LOGGER.warn("Content type mismatch for XLSX file: {}", contentType);
                }
                break;
            case "xls":
                if (!"application/vnd.ms-excel".equals(contentType)) {
                    LOGGER.warn("Content type mismatch for XLS file: {}", contentType);
                }
                break;
            default:
                // Extensions outside csv/pdf/xlsx/xls are filtered earlier; if one
                // slips through we don't enforce a content-type pairing for it.
                break;
        }
    }

    /** Validate magic bytes (file signature) to detect file type spoofing */
    private void validateMagicBytes(final MultipartFile file, String extension) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            final byte[] header = new byte[8];
            final int bytesRead = inputStream.read(header);

            if (bytesRead < 4) {
                throw new AppException(ErrorCode.INVALID_INPUT, "File is too small or corrupted");
            }

            extension = extension.toLowerCase(Locale.ROOT);
            boolean valid = false;

            switch (extension) {
                case "csv":
                    // CSV files can start with UTF-8 BOM or plain text
                    // Check if it starts with UTF-8 BOM or is plain text (no binary)
                    valid = startsWith(header, CSV_MAGIC) || isTextFile(header);
                    break;
                case "pdf":
                    valid = startsWith(header, PDF_MAGIC);
                    break;
                case "xlsx":
                    valid = startsWith(header, XLSX_MAGIC);
                    break;
                case "xls":
                    valid = startsWith(header, XLS_MAGIC);
                    break;
                default:
                    // For unknown types, just check it's not a dangerous binary
                    valid = true;
            }

            if (!valid) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Magic byte validation failed for file: {} (extension: {})",
                            file.getOriginalFilename(),
                            extension);
                }
                throw new AppException(
                        ErrorCode.INVALID_INPUT,
                        String.format(
                                "File content does not match declared type '%s'. File may be corrupted or malicious.",
                                extension));
            }
        }
    }

    /** Check if byte array starts with prefix */
    private boolean startsWith(final byte[] array, final byte[] prefix) {
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

    /** Check if file appears to be a text file (not binary) */
    private boolean isTextFile(final byte[] header) {
        for (final byte b : header) {
            // Text files should contain printable ASCII or UTF-8 characters
            // Exclude control characters except common ones (tab, newline, carriage return)
            if (b < 0x09 || (b > 0x0D && b < 0x20 && b != 0x1B)) {
                return false;
            }
        }
        return true;
    }

    /** Get file extension from filename */
    private String getFileExtension(final String filename) {
        final int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) {
            return "";
        }
        return filename.substring(lastDot + 1);
    }
}
