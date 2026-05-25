package com.budgetbuddy.mcp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Spring-managed registry of every {@link McpResource} bean. Mirrors
 * {@link McpToolRegistry} — alphabetically ordered, deduplicated by
 * URI, the single source of truth for {@code resources/list} +
 * {@code resources/read}.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Spring bean — exposed map is read-only after init")
@Component
public class McpResourceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpResourceRegistry.class);

    private final Map<String, McpResource> byUri;

    public McpResourceRegistry(final List<McpResource> resources) {
        final TreeMap<String, McpResource> map = new TreeMap<>();
        for (final McpResource r : resources) {
            if (r == null || r.uri() == null || r.uri().isBlank()) continue;
            map.put(r.uri(), r);
        }
        this.byUri = Collections.unmodifiableMap(map);
        LOGGER.info("McpResourceRegistry: registered {} resources", byUri.size());
    }

    public Collection<McpResource> all() {
        return byUri.values();
    }

    public Optional<McpResource> get(final String uri) {
        return Optional.ofNullable(uri == null ? null : byUri.get(uri));
    }
}
