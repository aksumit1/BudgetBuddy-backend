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
 * Spring-managed registry of every {@link McpPrompt} bean. Mirrors
 * {@link McpToolRegistry} — alphabetically ordered, deduplicated by
 * name, the single source of truth for {@code prompts/list} +
 * {@code prompts/get}.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "Spring bean — exposed map is read-only after init")
@Component
public class McpPromptRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpPromptRegistry.class);

    private final Map<String, McpPrompt> byName;

    public McpPromptRegistry(final List<McpPrompt> prompts) {
        final TreeMap<String, McpPrompt> map = new TreeMap<>();
        for (final McpPrompt p : prompts) {
            if (p == null || p.name() == null || p.name().isBlank()) continue;
            map.put(p.name(), p);
        }
        this.byName = Collections.unmodifiableMap(map);
        LOGGER.info("McpPromptRegistry: registered {} prompts", byName.size());
    }

    public Collection<McpPrompt> all() {
        return byName.values();
    }

    public Optional<McpPrompt> get(final String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }
}
