package com.budgetbuddy.service.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Curated merchant brand directory — maps a merchant name to its
 * canonical display name + official domain. iOS uses the domain to
 * render a brand mark via a client-controlled logo service so the
 * backend doesn't fetch any third-party asset at request time.
 *
 * <p>Lookup strategy: lowercase the merchant name and check each
 * configured key (also lowercase) as a substring. Keys are sorted
 * longest-first so "apple music" wins over "apple". When no key
 * matches, the lookup returns empty and iOS renders its existing
 * letter-avatar fallback.
 *
 * <p>The asset table lives in {@code merchant-brand-assets.yaml} so ops
 * can extend it without redeploy. On YAML parse failure the service
 * returns an empty directory rather than crashing the application —
 * brand-mark rendering is purely cosmetic.
 */
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification = "Spring bean — internal map is treated read-only after init")
@Service
public class MerchantBrandAssetService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantBrandAssetService.class);
    private static final String CLASSPATH_RESOURCE = "merchant-brand-assets.yaml";

    /**
     * Lookup keys sorted by length desc so "apple music" wins over
     * "apple". A TreeMap with a length-then-lex comparator gives us
     * deterministic resolution.
     */
    private Map<String, BrandAsset> brandsByKey = Collections.emptyMap();

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource(CLASSPATH_RESOURCE).getInputStream()) {
            final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            final Root root = mapper.readValue(in, Root.class);
            if (root == null || root.brands == null || root.brands.isEmpty()) {
                throw new IllegalStateException("empty brand table");
            }
            // Longest-key-first ordering so the "apple music" entry
            // takes priority over the bare "apple" entry for "apple
            // music".
            final TreeMap<String, BrandAsset> sorted =
                    new TreeMap<>(
                            (a, b) -> b.length() == a.length()
                                    ? a.compareTo(b)
                                    : Integer.compare(b.length(), a.length()));
            for (final Map.Entry<String, BrandAsset> e : root.brands.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                sorted.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
            this.brandsByKey = Collections.unmodifiableMap(sorted);
            LOGGER.info(
                    "MerchantBrandAssetService: loaded {} brand entries from {}",
                    sorted.size(), CLASSPATH_RESOURCE);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn(
                    "MerchantBrandAssetService: failed to load {}: {}. "
                            + "Brand lookups will all return empty.",
                    CLASSPATH_RESOURCE, e.getMessage());
            this.brandsByKey = Collections.emptyMap();
        }
    }

    /** Return the brand asset for a merchant name, or empty if unknown. */
    public Optional<BrandAsset> lookup(final String merchantName) {
        if (merchantName == null || merchantName.isBlank() || brandsByKey.isEmpty()) {
            return Optional.empty();
        }
        final String haystack = merchantName.toLowerCase(Locale.ROOT);
        for (final Map.Entry<String, BrandAsset> e : brandsByKey.entrySet()) {
            if (haystack.contains(e.getKey())) {
                return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** Convenience: brand-asset-or-null, for direct use in JSON responses. */
    public BrandAsset lookupOrNull(final String merchantName) {
        return lookup(merchantName).orElse(null);
    }

    static final class Root {
        public Map<String, BrandAsset> brands;
    }

    public static final class BrandAsset {
        public String domain;
        public String displayName;
    }
}
