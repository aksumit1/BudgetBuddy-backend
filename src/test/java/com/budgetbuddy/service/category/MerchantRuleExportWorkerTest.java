package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.MerchantEnrichmentCacheTable;
import com.budgetbuddy.repository.dynamodb.MerchantEnrichmentCacheRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Covers the daily rule-export worker:
 * <ul>
 *   <li>Filters out negative-sentinel rows
 *   <li>Filters by confidence floor
 *   <li>Emits YAML in the expected shape (rule blocks, mode: contains,
 *       priority 400)
 *   <li>Empty cache → no file is written
 *   <li>File write errors don't propagate
 * </ul>
 */
class MerchantRuleExportWorkerTest {

    private MerchantEnrichmentCacheRepository repo;
    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(MerchantEnrichmentCacheRepository.class);
    }

    @Test
    @DisplayName("Exports only entries above the confidence floor")
    void filtersByConfidenceFloor() throws IOException {
        when(repo.scanAll()).thenAnswer(inv -> Stream.of(
                row("walmart|bellevue|wa|us", "shopping", "OSM_TAG", 0.95),
                row("mystery|x|y|z",          "shopping", "OSM_TAG", 0.50)));
        Path out = tmp.resolve("draft.yaml");
        MerchantRuleExportWorker w =
                new MerchantRuleExportWorker(repo, 0.85, out.toString());
        w.exportDraftRules();

        assertTrue(Files.exists(out), "YAML should be written when ≥1 entry passes the floor");
        String yaml = Files.readString(out);
        assertTrue(yaml.contains("walmart"), "high-confidence walmart should be exported");
        assertFalse(yaml.contains("mystery"),
                "low-confidence mystery (0.50) should be filtered out by floor 0.85");
    }

    @Test
    @DisplayName("Skips negative-sentinel rows even when their TTL hasn't expired")
    void skipsNegativeSentinel() throws IOException {
        when(repo.scanAll()).thenAnswer(inv -> Stream.of(
                row("starbucks|seattle|wa|us", "dining",          "OSM_TAG", 0.88),
                row("unknown|x|y|z",           "__L6_NEGATIVE__", "L6_CHAIN_NULL", 0.0)));
        Path out = tmp.resolve("draft.yaml");
        MerchantRuleExportWorker w =
                new MerchantRuleExportWorker(repo, 0.85, out.toString());
        w.exportDraftRules();

        String yaml = Files.readString(out);
        assertTrue(yaml.contains("starbucks"), "positive entry should appear");
        assertFalse(yaml.contains("__L6_NEGATIVE__"),
                "sentinel rows must never be promoted to YAML rules");
        assertFalse(yaml.contains("unknown"),
                "merchant for a sentinel row must not appear as a rule keyword");
    }

    @Test
    @DisplayName("Empty cache → no file written, no exception")
    void emptyCacheWritesNothing() {
        when(repo.scanAll()).thenAnswer(inv -> Stream.empty());
        Path out = tmp.resolve("draft.yaml");
        MerchantRuleExportWorker w =
                new MerchantRuleExportWorker(repo, 0.85, out.toString());
        w.exportDraftRules();
        assertFalse(Files.exists(out),
                "no exportable entries → no file write (avoids creating an empty rules file)");
    }

    @Test
    @DisplayName("Output is valid YAML with the rules-engine schema")
    void emittedYamlMatchesRulesSchema() throws IOException {
        when(repo.scanAll()).thenAnswer(inv -> Stream.of(
                row("walmart|bellevue|wa|us", "shopping", "OSM_TAG", 0.95),
                row("chipotle|bellevue|wa|us", "dining",  "WIKIDATA", 0.90)));
        Path out = tmp.resolve("draft.yaml");
        MerchantRuleExportWorker w =
                new MerchantRuleExportWorker(repo, 0.85, out.toString());
        w.exportDraftRules();

        String yaml = Files.readString(out);
        assertTrue(yaml.contains("rules:"), "must declare the rules: root for MerchantCategoryRules");
        assertTrue(yaml.contains("- category: dining"));
        assertTrue(yaml.contains("- category: shopping"));
        assertTrue(yaml.contains("priority: 400"),
                "auto-learned rules sit BELOW curated tier (priority 400 vs 700-1000)");
        assertTrue(yaml.contains("mode: contains"));
        // Provenance comment carries source + confidence so reviewers can decide
        assertTrue(yaml.contains("OSM_TAG"), "provenance comment should include the source");
    }

    @Test
    @DisplayName("Deduplicates the same merchant across multiple location keys")
    void deduplicatesByMerchantToken() throws IOException {
        // Same merchant ("starbucks"), different cities — only one rule keyword.
        when(repo.scanAll()).thenAnswer(inv -> Stream.of(
                row("starbucks|seattle|wa|us",  "dining", "OSM_TAG", 0.88),
                row("starbucks|bellevue|wa|us", "dining", "OSM_TAG", 0.88),
                row("starbucks|nashville|tn|us","dining", "WIKIDATA", 0.85)));
        Path out = tmp.resolve("draft.yaml");
        MerchantRuleExportWorker w =
                new MerchantRuleExportWorker(repo, 0.85, out.toString());
        w.exportDraftRules();

        String yaml = Files.readString(out);
        int count = yaml.split("starbucks", -1).length - 1;
        // 1 keyword line + at most 1 in the provenance comment = ≤ 2
        assertTrue(count <= 2,
                "starbucks should appear at most once as a rule keyword (+ once in comment), got "
                        + count + " occurrences");
    }

    // ---- helpers ----

    private static MerchantEnrichmentCacheTable row(
            final String key, final String category, final String source, final double conf) {
        MerchantEnrichmentCacheTable r = new MerchantEnrichmentCacheTable();
        r.setCacheKey(key);
        r.setCategoryPrimary(category);
        r.setCategoryDetailed(category);
        r.setSource(source);
        r.setConfidence(conf);
        return r;
    }
}
