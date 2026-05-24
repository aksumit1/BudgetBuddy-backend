package com.budgetbuddy.service.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads subscription bundling groups from {@code subscription-bundles.yaml}
 * on the classpath. Replaces the previously-hardcoded streaming/cloud/
 * software lists in {@link com.budgetbuddy.service.SubscriptionAdvancedService}.
 *
 * <p>Ops can add a new bundling category (e.g. "music") or extend an
 * existing one (Crunchyroll, Max, …) by editing the YAML and rolling
 * the deployment — no Java change required.
 *
 * <p>Defaults kick in when the YAML can't be parsed for any reason so
 * the service never silently produces zero bundling recommendations
 * because of a typo. The defaults preserve the prior hardcoded shape.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Spring bean — exposed lists are read-only after init")
@Component
public class BundleGroupConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundleGroupConfig.class);
    private static final String CLASSPATH_RESOURCE = "subscription-bundles.yaml";

    private List<BundleGroup> groups = Collections.emptyList();

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource(CLASSPATH_RESOURCE).getInputStream()) {
            final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            final Root root = mapper.readValue(in, Root.class);
            if (root == null || root.groups == null || root.groups.isEmpty()) {
                throw new IllegalStateException("empty groups list");
            }
            // Defensively trim whitespace + lowercase every service name
            // so a casing typo in YAML doesn't silently break matching.
            for (final BundleGroup g : root.groups) {
                if (g.services != null) {
                    g.services.replaceAll(s -> s == null ? null : s.toLowerCase().trim());
                    g.services.removeIf(s -> s == null || s.isBlank());
                }
                if (g.discountPercent <= 0 || g.discountPercent >= 100) {
                    LOGGER.warn(
                            "BundleGroupConfig: group {} has out-of-range discountPercent={};"
                                    + " clamping to 15.",
                            g.id, g.discountPercent);
                    g.discountPercent = 15;
                }
            }
            this.groups = Collections.unmodifiableList(root.groups);
            LOGGER.info(
                    "BundleGroupConfig: loaded {} bundling groups from {}",
                    this.groups.size(),
                    CLASSPATH_RESOURCE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn(
                    "BundleGroupConfig: failed to load {}: {}. Falling back to defaults.",
                    CLASSPATH_RESOURCE, e.getMessage());
            this.groups = defaults();
        }
    }

    public List<BundleGroup> groups() {
        return groups;
    }

    public BigDecimal discountFactor(final BundleGroup group) {
        // 15% discount → return 0.85 (the price after the discount).
        final int pct = group == null ? 15 : group.discountPercent;
        return BigDecimal.ONE.subtract(BigDecimal.valueOf(pct).movePointLeft(2));
    }

    /**
     * Backwards-compat fallback that mirrors the original hardcoded
     * groups exactly. Used only when YAML loading fails so the
     * service never produces zero recommendations because of a bad
     * config file.
     */
    private static List<BundleGroup> defaults() {
        final List<BundleGroup> out = new ArrayList<>();
        out.add(group("streaming", "Streaming", 15,
                List.of("netflix", "hulu", "disney", "hbo", "paramount", "peacock")));
        out.add(group("cloud_storage", "Cloud Storage", 20,
                List.of("dropbox", "icloud", "google drive", "onedrive")));
        out.add(group("software", "Productivity & Software", 15,
                List.of("adobe", "microsoft 365", "office 365")));
        return out;
    }

    private static BundleGroup group(
            final String id, final String label, final int pct, final List<String> services) {
        final BundleGroup g = new BundleGroup();
        g.id = id;
        g.label = label;
        g.discountPercent = pct;
        g.services = new ArrayList<>(services);
        return g;
    }

    static final class Root {
        public List<BundleGroup> groups;
    }

    public static final class BundleGroup {
        public String id;
        public String label;
        public int discountPercent;
        public List<String> services;

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof BundleGroup other)) return false;
            return Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}
