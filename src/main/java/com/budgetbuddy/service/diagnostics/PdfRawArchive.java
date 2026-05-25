package com.budgetbuddy.service.diagnostics;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Persistent archive of raw PDF imports. Distinct from
 * {@link PdfImportDiagnosticStore} which only stores a redacted JSON summary:
 * this stores the ORIGINAL bytes so a future fix to the extractor can be
 * verified by re-parsing the same file. Without this, every newly-found bug
 * pattern is lost as soon as the user moves on to their next import.
 *
 * <h3>Path scheme</h3>
 *
 * Mirrors the diagnostic scheme so the two are easy to correlate:
 * <pre>
 *   &lt;root&gt;/&lt;institution-slug&gt;/&lt;yyyy-mm&gt;/&lt;pdf_hash&gt;.pdf
 * </pre>
 *
 * <p>The SHA-256 hash deduplicates: re-importing the same PDF overwrites the
 * existing file with no harm. The diagnostic JSON next to it gets refreshed
 * with the latest parser version.
 *
 * <h3>Privacy</h3>
 *
 * Raw PDFs may contain PII (full account numbers, transaction descriptions,
 * statement addresses). This archive is OFF BY DEFAULT — only enable in
 * environments where you have user consent and the storage is appropriately
 * access-controlled (encrypted at rest, audit-logged read access).
 *
 * <h3>Operational use</h3>
 *
 * When a user reports "import looks wrong":
 * <pre>
 *   1. Look up the SHA from the diagnostic JSON or the logged hash.
 *   2. Fetch the archived PDF from the same scheme.
 *   3. Re-parse locally with the proposed fix.
 *   4. Confirm the fix doesn't regress other archived PDFs in the
 *      same issuer folder.
 * </pre>
 *
 * <h3>Safety</h3>
 *
 * Any storage failure is logged WARN and swallowed — the archive must never
 * break the import path. Disabled-by-default means existing deployments are
 * unchanged unless an operator explicitly turns it on.
 */
@Component
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
public class PdfRawArchive {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfRawArchive.class);

    @Value("${pdf.archive.root:/tmp/pdf-archive}")
    private String root;

    /**
     * OFF BY DEFAULT for privacy. Operators must explicitly opt in via
     * {@code pdf.archive.enabled=true} after confirming user consent +
     * encrypted-at-rest storage.
     */
    @Value("${pdf.archive.enabled:false}")
    private boolean enabled;

    @Value("${pdf.archive.s3.bucket:}")
    private String s3Bucket;

    @Value("${pdf.archive.s3.prefix:pdf-archive/}")
    private String s3Prefix;

    /**
     * Maximum PDF size to archive (bytes). Defaults to 25MB — covers virtually
     * every real bank statement while preventing accidental storage of huge
     * scanned-document PDFs that would blow up disk usage.
     */
    @Value("${pdf.archive.max-bytes:26214400}")
    private long maxBytes;

    private final S3Client s3Client;

    @Autowired
    public PdfRawArchive(@Autowired(required = false) final S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /** No-arg constructor for direct instantiation in tests. */
    public PdfRawArchive() {
        this(null);
    }

    /**
     * Archive raw PDF bytes. Idempotent — a re-import with identical content
     * is a no-op (same hash, same file). Returns the archived path or null
     * when disabled / oversize / store failed.
     */
    public Path archive(
            final byte[] pdfBytes,
            final String institution,
            final String originalFilename) {
        if (!enabled) {
            return null;
        }
        if (pdfBytes == null || pdfBytes.length == 0) {
            LOGGER.debug("PdfRawArchive: skipping null/empty bytes");
            return null;
        }
        if (pdfBytes.length > maxBytes) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "PdfRawArchive: skipping oversize PDF ({} bytes, max {}) — "
                                + "file={}, institution={}",
                        pdfBytes.length, maxBytes, originalFilename, institution);
            }
            return null;
        }
        final String hash = sha256(pdfBytes);
        if (hash == null) {
            // SHA-256 should always be available; defensive.
            return null;
        }
        try {
            // Slugify via the shared helper so this path matches the
            // diagnostic JSON's path exactly (same institution → same
            // dir). See PdfDiagnosticCorrelation.
            final String issuerSlug = PdfDiagnosticCorrelation.institutionSlug(institution);
            final String yyyymm = LocalDate.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM"));
            final String relPath = issuerSlug + "/" + yyyymm + "/" + hash + ".pdf";

            // Local FS write. Skip if file already exists with the same size
            // (cheap dedup; trust the hash to be sufficient identity).
            final Path dir = Paths.get(root, issuerSlug, yyyymm);
            Files.createDirectories(dir);
            final Path file = dir.resolve(hash + ".pdf");
            if (Files.exists(file) && Files.size(file) == pdfBytes.length) {
                LOGGER.debug("PdfRawArchive: file already archived: {}", file);
                return file;
            }
            Files.write(file, pdfBytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Archived raw PDF: {} ({} bytes, hash={}, file={})",
                        file, pdfBytes.length, hash, originalFilename);
            }

            // Write a small companion sidecar: original filename + size +
            // timestamp. Useful for forensics ("the user named it X").
            writeSidecar(dir, hash, originalFilename, pdfBytes.length);

            // S3 mirror — only when configured AND not already present.
            mirrorToS3(pdfBytes, hash, relPath);

            return file;
        } catch (final IOException e) {
            LOGGER.warn(
                    "PdfRawArchive: local write failed for {} ({}): {}",
                    originalFilename, hash, e.getMessage());
            return null;
        } catch (final RuntimeException e) {
            LOGGER.warn(
                    "PdfRawArchive: unexpected error storing {} ({}): {}",
                    originalFilename, hash, e.getMessage());
            return null;
        }
    }

    private void writeSidecar(
            final Path dir, final String hash,
            final String originalFilename, final int size) {
        try {
            final Path sidecar = dir.resolve(hash + ".meta.txt");
            // Filename is user-supplied — sanitize so a malicious filename
            // can't escape the sidecar (newlines, control chars).
            final String safeName = originalFilename == null ? ""
                    : originalFilename.replaceAll("[\\r\\n\\u0000-\\u001f]", "_");
            final String contents = "filename=" + safeName + "\n"
                    + "size=" + size + "\n"
                    + "sha256=" + hash + "\n"
                    + "archived_at=" + java.time.Instant.now() + "\n";
            Files.write(sidecar,
                    contents.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException e) {
            LOGGER.debug("PdfRawArchive: sidecar write failed: {}", e.getMessage());
        }
    }

    private void mirrorToS3(final byte[] pdfBytes, final String hash, final String relPath) {
        if (s3Client == null || s3Bucket == null || s3Bucket.isBlank()) {
            return;
        }
        final String key = (s3Prefix == null ? "" : s3Prefix) + relPath;
        try {
            // Skip if already exists in S3 — saves a redundant PUT.
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(s3Bucket).key(key).build());
            LOGGER.debug("PdfRawArchive: S3 object exists, skipping put: {}", key);
            return;
        } catch (final NoSuchKeyException ok) {
            // Expected — object doesn't exist yet.
        } catch (final RuntimeException headErr) {
            LOGGER.debug(
                    "PdfRawArchive: S3 head check failed for {} ({}): {}",
                    key, hash, headErr.getMessage());
        }
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(s3Bucket)
                            .key(key)
                            .contentType("application/pdf")
                            .build(),
                    RequestBody.fromBytes(pdfBytes));
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("PdfRawArchive: mirrored to s3://{}/{}", s3Bucket, key);
            }
        } catch (final RuntimeException s3err) {
            LOGGER.warn(
                    "PdfRawArchive: S3 mirror failed for {} (local already wrote): {}",
                    key, s3err.getMessage());
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] digest = md.digest(bytes);
            final StringBuilder sb = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static String slugify(final String s) {
        if (s == null || s.isBlank()) return "unknown";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
