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
 * Spring-managed registry of every MCP tool the server exposes. Tools
 * are picked up automatically — every {@link McpTool} bean is indexed
 * by name at startup.
 *
 * <p>Single source of truth for {@code tools/list}.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Spring bean — exposed map is read-only after init")
@Component
public class McpToolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpToolRegistry.class);

    private final Map<String, McpTool> byName;

    public McpToolRegistry(final List<McpTool> tools) {
        final TreeMap<String, McpTool> map = new TreeMap<>();
        for (final McpTool t : tools) {
            if (t == null || t.name() == null || t.name().isBlank()) continue;
            final McpTool prior = map.put(t.name(), t);
            if (prior != null) {
                LOGGER.warn(
                        "McpToolRegistry: duplicate tool name {} — both {} and {} registered."
                                + " Keeping the latter.",
                        t.name(), prior.getClass().getName(), t.getClass().getName());
            }
        }
        this.byName = Collections.unmodifiableMap(map);
        LOGGER.info("McpToolRegistry: registered {} tools", byName.size());
    }

    public Collection<McpTool> all() {
        return byName.values();
    }

    public Optional<McpTool> get(final String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }
}
