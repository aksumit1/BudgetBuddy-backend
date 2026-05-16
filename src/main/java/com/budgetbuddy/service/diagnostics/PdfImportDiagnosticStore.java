package com.budgetbuddy.service.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Persists diagnostic blobs to disk (dev) or S3 (prod, future). One JSON file
 * per failure. Filenames embed the issuer + pdf-hash so deduplication is trivial.
 *
 * <p>Path scheme:
 * <pre>
 *   &lt;root&gt;/&lt;institution-slug&gt;/&lt;yyyy-mm&gt;/&lt;pdf_hash&gt;.json
 * </pre>
 *
 * <p>If two parses of the same PDF produce the same failure (same hash), the
 * file is overwritten — that's fine: the latest parser-version metadata wins.
 *
 * <p>The component is best-effort: any storage failure is logged WARN and
 * swallowed. We never want diagnostic-capture to break the import.
 */
@Component
public class PdfImportDiagnosticStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PdfImportDiagnosticStore.class);

    @Value("${pdf.diagnostics.root:/tmp/pdf-diagnostics}")
    private String root;

    @Value("${pdf.diagnostics.enabled:true}")
    private boolean enabled;

    /** When set, blobs ALSO go to this S3 bucket (in addition to local FS). */
    @Value("${pdf.diagnostics.s3.bucket:}")
    private String s3Bucket;

    @Value("${pdf.diagnostics.s3.prefix:pdf-diagnostics/}")
    private String s3Prefix;

    private final ObjectMapper mapper;
    private final S3Client s3Client;

    @Autowired
    public PdfImportDiagnosticStore(
            @Autowired(required = false) final S3Client s3Client) {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.s3Client = s3Client;
    }

    /** No-arg constructor for direct instantiation in tests / harnesses. */
    public PdfImportDiagnosticStore() {
        this(null);
    }

    /** Returns the path written, or null on failure / disabled. */
    public Path store(final PdfImportDiagnostic diagnostic) {
        if (!enabled || diagnostic == null) return null;
        try {
            final String issuerSlug = slugify(
                    diagnostic.getDetectedAccount() == null
                            ? "unknown"
                            : diagnostic.getDetectedAccount().institution);
            final String yyyymm = LocalDate.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM"));
            final String hash = diagnostic.getPdfHash() == null
                    ? "no-hash-" + System.nanoTime()
                    : diagnostic.getPdfHash();
            final String relPath = issuerSlug + "/" + yyyymm + "/" + hash + ".json";

            // Local FS write — always (cheap, useful for dev + as DR backup).
            final Path dir = Paths.get(root, issuerSlug, yyyymm);
            Files.createDirectories(dir);
            final Path file = dir.resolve(hash + ".json");
            mapper.writeValue(file.toFile(), diagnostic);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Stored PDF import diagnostic: {}", file);
            }

            // S3 mirror — only when a bucket is configured AND an S3Client is available.
            // Failures here are logged but never propagated; local FS already succeeded.
            if (s3Client != null && s3Bucket != null && !s3Bucket.isBlank()) {
                try {
                    final byte[] body = mapper.writeValueAsBytes(diagnostic);
                    final String key = (s3Prefix == null ? "" : s3Prefix) + relPath;
                    s3Client.putObject(
                            PutObjectRequest.builder()
                                    .bucket(s3Bucket)
                                    .key(key)
                                    .contentType("application/json")
                                    .build(),
                            RequestBody.fromBytes(body));
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("Mirrored diagnostic to s3://{}/{}", s3Bucket, key);
                    }
                } catch (final RuntimeException s3e) {
                    LOGGER.warn(
                            "S3 mirror failed (local write still succeeded): {}",
                            s3e.getMessage());
                }
            }

            return file;
        } catch (final IOException e) {
            LOGGER.warn("Failed to store PDF import diagnostic: {}", e.getMessage());
            return null;
        } catch (final RuntimeException e) {
            // Never let a diagnostic-storage failure break the import.
            LOGGER.warn(
                    "Unexpected error storing PDF import diagnostic: {}", e.getMessage());
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
