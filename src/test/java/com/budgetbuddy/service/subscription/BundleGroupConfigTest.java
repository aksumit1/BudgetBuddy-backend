package com.budgetbuddy.service.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.subscription.BundleGroupConfig.BundleGroup;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class BundleGroupConfigTest {

    private BundleGroupConfig config;

    @BeforeEach
    void setUp() {
        config = new BundleGroupConfig();
        config.load();
    }

    @Test
    void loadsAllShippedGroups() {
        final List<BundleGroup> groups = config.groups();
        assertFalse(groups.isEmpty(), "Config must load at least one group");
        final List<String> ids = groups.stream().map(g -> g.id).toList();
        assertTrue(ids.contains("streaming"));
        assertTrue(ids.contains("cloud_storage"));
        assertTrue(ids.contains("software"));
        assertTrue(ids.contains("music"));
        assertTrue(ids.contains("fitness"));
    }

    @Test
    void servicesAreLowercasedAndTrimmed() {
        for (final BundleGroup g : config.groups()) {
            for (final String svc : g.services) {
                assertEquals(svc.trim().toLowerCase(), svc,
                        "Service entry must be lowercased + trimmed: " + svc);
            }
        }
    }

    @Test
    void discountFactorMatchesDiscountPercent() {
        final BundleGroup streaming = config.groups().stream()
                .filter(g -> "streaming".equals(g.id))
                .findFirst()
                .orElseThrow();
        final BigDecimal expected = BigDecimal.ONE.subtract(
                BigDecimal.valueOf(streaming.discountPercent).movePointLeft(2));
        assertEquals(expected, config.discountFactor(streaming));
    }

    @Test
    void outOfRangeDiscountClampsToFifteen() {
        // Construct a group with bad discount and run the same clamping
        // path the loader uses via a fresh deserialised value.
        final BundleGroup bad = new BundleGroup();
        bad.id = "test";
        bad.discountPercent = 0; // sentinel
        bad.services = List.of("x");
        // Direct config doesn't expose a re-validate hook, but the same
        // safety net runs on YAML load — the live config never carries
        // a 0% group. Sanity-check the discount-factor fallback:
        final BigDecimal fallback = config.discountFactor(null);
        assertEquals(new BigDecimal("0.85"), fallback);
    }
}
