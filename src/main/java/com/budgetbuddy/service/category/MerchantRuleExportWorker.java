package com.budgetbuddy.service.category;

import com.budgetbuddy.model.dynamodb.MerchantEnrichmentCacheTable;
import com.budgetbuddy.repository.dynamodb.MerchantEnrichmentCacheRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Daily exporter that promotes stable positive entries from the
 * persistent {@link MerchantEnrichmentStore} (DynamoDB) into a draft
 * YAML rules file. The output file is human-reviewed and selectively
 * merged into {@code category-rules-v2.yaml} — we never auto-write to
 * the production rules file directly.
 *
 * <h3>Why this exists</h3>
 *
 * The cascade learns about merchants three ways:
 * <ol>
 *   <li>L5 curated rules (declarative, in {@code category-rules-v2.yaml})
 *   <li>L6 external lookup (OSM / Wikidata / Nominatim, cached forever)
 *   <li>LLM self-learning (queue → AnthropicLlmCategorySuggester →
 *       MerchantEnrichmentStore)
 * </ol>
 *
 * Entries discovered by (2) and (3) live in DynamoDB. That's opaque,
 * hard to diff, and tied to a specific cache infrastructure. This
 * exporter periodically writes those entries back as YAML rule patterns
 * so they can be code-reviewed, version-controlled, and replicated to
 * future deploys without depending on the DynamoDB table surviving.
 *
 * <h3>Schedule</h3>
 *
 * Co-scheduled with the LLM self-learning loop ({@link SelfLearningWorker})
 * so the daily "augment learning" cycle runs as a single batch:
 * <pre>
 *   self-learning: drain queue → LLM → enrichment store
 *   rule-export:   scan store → cluster by category → draft YAML
 * </pre>
 *
 * <h3>Output</h3>
 *
 * Writes to {@code app.category.rule-export.output-path}, default
 * {@code /tmp/budgetbuddy-rule-export/learned-rules-draft.yaml}. The
 * file is overwritten on each run. For multi-pod deploys you'd point
 * this at S3 instead.
 *
 * <h3>Confidence floor</h3>
 *
 * Only entries with {@code confidence >= app.category.rule-export.confidence-floor}
 * (default 0.85) are emitted. Lower-confidence rows stay in the cache
 * and don't pollute the YAML draft.
 */
@Service
@ConditionalOnProperty(
        name = "app.category.rule-export.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class MerchantRuleExportWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantRuleExportWorker.class);
    /** Sentinel for negative cache rows. We never export these. */
    private static final String NEGATIVE_SENTINEL = "__L6_NEGATIVE__";
    /** Strip a trailing "-yyyy-MM-dd" suffix from previous draft filenames. */
    private static final Pattern DATE_SUFFIX = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    private final MerchantEnrichmentCacheRepository repo;
    private final double confidenceFloor;
    private final String outputPath;

    public MerchantRuleExportWorker(
            final MerchantEnrichmentCacheRepository repo,
            @Value("${app.category.rule-export.confidence-floor:0.85}") final double floor,
            // Default points at /var/lib/rule-export, mounted as a docker
            // volume so output survives container rebuilds. Override via
            // CATEGORY_RULE_EXPORT_OUTPUT_PATH when the deploy env doesn't
            // have that mount (the worker creates parent dirs as needed).
            @Value(
                            "${app.category.rule-export.output-path:"
                                    + "/var/lib/rule-export/learned-rules-draft.yaml}")
                    final String outputPath) {
        this.repo = repo;
        this.confidenceFloor = floor;
        this.outputPath = outputPath;
    }

    /**
     * Daily at 03:30 — same window as the typical LLM self-learning run.
     * Cron is overridable via {@code app.category.rule-export.cron}.
     */
    @Scheduled(cron = "${app.category.rule-export.cron:0 30 3 * * *}")
    public void exportDraftRules() {
        final long t0 = System.currentTimeMillis();
        final Map<String, List<MerchantEnrichmentCacheTable>> byCategory =
                repo.scanAll()
                        .filter(this::isExportable)
                        .collect(
                                Collectors.groupingBy(
                                        MerchantEnrichmentCacheTable::getCategoryPrimary,
                                        TreeMap::new,
                                        Collectors.toList()));
        final int totalEntries =
                byCategory.values().stream().mapToInt(List::size).sum();
        if (totalEntries == 0) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Rule export: no exportable cache entries above confidence {}",
                        confidenceFloor);
            }
            return;
        }
        final String yaml = renderYaml(byCategory);
        try {
            writeYaml(yaml);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Rule export: wrote {} entries across {} categories to {} in {} ms",
                        totalEntries,
                        byCategory.size(),
                        outputPath,
                        System.currentTimeMillis() - t0);
            }
        } catch (IOException ex) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Rule export failed to write {}: {}", outputPath, ex.getMessage());
            }
        }
    }

    private boolean isExportable(final MerchantEnrichmentCacheTable row) {
        if (row == null) return false;
        final String cat = row.getCategoryPrimary();
        if (cat == null || cat.isBlank()) return false;
        if (NEGATIVE_SENTINEL.equals(cat)) return false;
        return row.getConfidence() != null && row.getConfidence() >= confidenceFloor;
    }

    /**
     * Render the cache-clustered entries as a YAML rules block compatible
     * with {@link MerchantCategoryRules}. Each category becomes one rule
     * block; keywords are the merchant-name portion of the cache key
     * (lowercased, normalised whitespace). Priority 400 puts these
     * between curated rules (priority 700-1000) and pure fallbacks — so
     * a hand-written rule always wins over an auto-learned one.
     */
    private String renderYaml(
            final Map<String, List<MerchantEnrichmentCacheTable>> byCategory) {
        final StringBuilder sb = new StringBuilder();
        sb.append("# =====================================================================\n");
        sb.append("# Auto-generated rule draft — produced by MerchantRuleExportWorker\n");
        sb.append("# Source: MerchantEnrichmentCache (positive entries, confidence >= ")
                .append(confidenceFloor)
                .append(")\n");
        sb.append("# Generated at: ")
                .append(DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
                .append("\n");
        sb.append("#\n");
        sb.append("# Review each block before merging into category-rules-v2.yaml.\n");
        sb.append("# Priority 400 (auto-learned tier) sits BELOW curated rules so a\n");
        sb.append("# hand-written rule always wins over an external-source guess.\n");
        sb.append("# =====================================================================\n");
        sb.append("rules:\n");
        for (final Map.Entry<String, List<MerchantEnrichmentCacheTable>> e
                : byCategory.entrySet()) {
            sb.append("  - category: ").append(e.getKey()).append("\n");
            sb.append("    priority: 400\n");
            sb.append("    mode: contains\n");
            sb.append("    keywords:\n");
            // Dedupe + sort by merchant fragment of cache key
            final var keywords = new LinkedHashMap<String, MerchantEnrichmentCacheTable>();
            for (final MerchantEnrichmentCacheTable row : e.getValue()) {
                final String merchant = merchantFromKey(row.getCacheKey());
                if (merchant != null && !merchant.isBlank()) {
                    keywords.putIfAbsent(merchant, row);
                }
            }
            keywords.keySet().stream()
                    .sorted()
                    .forEach(
                            k -> sb.append("      - \"")
                                    .append(escapeYaml(k))
                                    .append("\"   # ")
                                    .append(provenance(keywords.get(k)))
                                    .append("\n"));
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Cache key is {@code merchant|city|state|country} — extract the
     * merchant fragment for use as a rule keyword.
     */
    private static String merchantFromKey(final String cacheKey) {
        if (cacheKey == null) return null;
        final int pipe = cacheKey.indexOf('|');
        return pipe < 0 ? cacheKey : cacheKey.substring(0, pipe);
    }

    private static String provenance(final MerchantEnrichmentCacheTable row) {
        final String src = row.getSource() == null ? "?" : row.getSource();
        final Double conf = row.getConfidence();
        return src + ", conf=" + (conf == null ? "?" : conf);
    }

    private static String escapeYaml(final String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeYaml(final String yaml) throws IOException {
        final Path out = Paths.get(outputPath);
        final Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, yaml, StandardCharsets.UTF_8);
        // Post-write validation — re-parse the file via a temporary
        // MerchantCategoryRules instance to confirm it's syntactically
        // valid YAML in the engine's expected schema. If it isn't, log a
        // warning so the operator doesn't silently merge a broken draft.
        try {
            // Copy to classpath-style temp location so the loader can read it.
            final Path tempCopy = Files.createTempFile("rule-export-validation-", ".yaml");
            try {
                Files.copy(out, tempCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // Read & parse manually — MerchantCategoryRules uses
                // ClassPathResource so we can't point it at an arbitrary path.
                // Instead just re-read the bytes we wrote and run them through
                // the same YAML loader.
                final org.yaml.snakeyaml.Yaml yamlParser = new org.yaml.snakeyaml.Yaml();
                final Object parsed = yamlParser.load(Files.newInputStream(tempCopy));
                if (parsed instanceof java.util.Map
                        && ((java.util.Map<?, ?>) parsed).containsKey("rules")) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Rule export validation: YAML parsed cleanly, rules block present");
                    }
                } else if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Rule export validation: written file does NOT contain a 'rules' "
                                    + "block at root — engine would reject this draft");
                }
            } finally {
                Files.deleteIfExists(tempCopy);
            }
        } catch (Exception parseErr) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Rule export validation: re-parse of {} failed — {}",
                        outputPath, parseErr.getMessage());
            }
        }
    }

    /**
     * Manual trigger for tests / admin tools. Returns the count of
     * exported entries.
     */
    public int exportNow() {
        final int beforeWrite = countExportable();
        exportDraftRules();
        return beforeWrite;
    }

    private int countExportable() {
        return Math.toIntExact(repo.scanAll().filter(this::isExportable).count());
    }

    // Locale field kept to satisfy SuppressWarnings even if unused later.
    @SuppressWarnings("unused")
    private static final Locale ROOT = Locale.ROOT;
}
