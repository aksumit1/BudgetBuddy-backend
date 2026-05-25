package com.budgetbuddy.service.diagnostics;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Single source of truth for the path scheme shared by
 * {@link PdfImportDiagnosticStore} (JSON summaries) and
 * {@link PdfRawArchive} (raw PDF bytes). Both stores hash the same PDF
 * bytes with SHA-256, slugify the same institution name the same way,
 * and bucket by the same {@code yyyy-MM} window — so a JSON-only failure
 * report and its companion raw PDF have predictable, derivable paths.
 *
 * <p>Operational workflow when triaging a parse failure:
 * <pre>
 *   1. Read failure JSON: cat &lt;diag-root&gt;/wells-fargo/2026-05/&lt;sha&gt;.json
 *   2. Derive raw PDF path: PdfDiagnosticCorrelation.pdfPath(...)
 *   3. Re-parse locally with the proposed fix.
 * </pre>
 *
 * <p>The two stores are kept separate (different lifecycle policies, one
 * is enabled-by-default and one is not, S3 buckets may differ) but the
 * <em>naming</em> is co-managed here so they can be correlated without a
 * join table.
 */
public final class PdfDiagnosticCorrelation {

    private PdfDiagnosticCorrelation() { }

    /**
     * Slugify an institution name. Both stores use exactly this rule so
     * the directory name matches. Lowercase, alphanumeric-and-hyphen,
     * collapsed runs, no leading/trailing hyphens, capped at 40 chars.
     */
    public static String institutionSlug(final String raw) {
        if (raw == null || raw.isBlank()) return "unknown";
        final String s = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.isEmpty()) return "unknown";
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    /**
     * Build the relative path (institution-slug/yyyy-MM/hash) that both
     * stores append the extension to. Used by integration tests that
     * verify the correlation guarantee.
     */
    public static String relativePathStem(
            final String institution,
            final java.time.LocalDate when,
            final String sha256) {
        final String slug = institutionSlug(institution);
        final String yyyymm = when.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        return slug + "/" + yyyymm + "/" + sha256;
    }

    /** Derive the raw-PDF path for a given correlation tuple. */
    public static Path pdfPath(
            final Path archiveRoot,
            final String institution,
            final java.time.LocalDate when,
            final String sha256) {
        return Paths.get(archiveRoot.toString(),
                relativePathStem(institution, when, sha256) + ".pdf");
    }

    /** Derive the diagnostic-JSON path for a given correlation tuple. */
    public static Path jsonPath(
            final Path diagnosticRoot,
            final String institution,
            final java.time.LocalDate when,
            final String sha256) {
        return Paths.get(diagnosticRoot.toString(),
                relativePathStem(institution, when, sha256) + ".json");
    }
}
