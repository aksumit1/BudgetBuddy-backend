package com.budgetbuddy.service.pdf.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads v2 YAML templates from the classpath at startup. Returns
 * issuer-matching templates so PDFImportService can pick the right one for a
 * given statement without scanning every template per parse.
 *
 * <p>v2 is additive: a missing v2 template means the parser uses v1 / legacy
 * code paths. As more issuers are migrated, the v2 set grows and the legacy
 * paths get retired.
 */
@Component
public class PdfTemplateV2Registry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfTemplateV2Registry.class);

    @Value("${pdf.templates.v2.resource-pattern:classpath*:pdf-templates-v2/*.yaml}")
    private String resourcePattern;

    private List<PdfTemplateV2> templates = Collections.emptyList();
    /**
     * Lower-cased institution-name → template index. Built once at load and
     * replaces the previous linear-scan {@code findByInstitution}; lookup is
     * O(1) and stays fast as the template set grows. Lower-cased so
     * "Citibank" / "CITIBANK" / "citibank" all hit the same key.
     */
    private Map<String, PdfTemplateV2> byInstitution = Collections.emptyMap();

    @PostConstruct
    public void init() {
        final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final List<PdfTemplateV2> loaded = new ArrayList<>();
        try {
            final Resource[] resources = resolver.getResources(resourcePattern);
            for (final Resource r : resources) {
                try (InputStream is = r.getInputStream()) {
                    final PdfTemplateV2 t = mapper.readValue(is, PdfTemplateV2.class);
                    final List<PdfTemplateV2Validator.Issue> issues =
                            PdfTemplateV2Validator.validate(t);
                    boolean hardError = false;
                    for (final PdfTemplateV2Validator.Issue issue : issues) {
                        if (issue.severity == PdfTemplateV2Validator.Severity.ERROR) {
                            hardError = true;
                            LOGGER.warn("v2 template '{}' ERROR at {}: {}",
                                    r.getFilename(), issue.path, issue.message);
                        } else {
                            LOGGER.info("v2 template '{}' WARN at {}: {}",
                                    r.getFilename(), issue.path, issue.message);
                        }
                    }
                    if (hardError) {
                        LOGGER.warn(
                                "v2 template '{}' loaded despite ERROR issues — see warnings above",
                                r.getFilename());
                    }
                    loaded.add(t);
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(
                                "Loaded v2 template '{}' for institution '{}'",
                                t.getId(), t.getInstitution());
                    }
                } catch (final RuntimeException ex) {
                    LOGGER.warn(
                            "v2 template at {} failed to load: {}",
                            r.getFilename(), ex.getMessage());
                }
            }
        } catch (final IOException e) {
            LOGGER.warn(
                    "v2 registry resource scan failed ({}): {}",
                    resourcePattern, e.getMessage());
        }
        // Resolve extends: chains so templates that inherit from a shared
        // fragment carry the combined rule list. The merger logs WARN on
        // missing parents or cycles and otherwise produces a new list of
        // post-merge templates in the same order.
        final List<PdfTemplateV2> resolved = TemplateMerger.resolve(loaded);
        this.templates = Collections.unmodifiableList(resolved);
        final Map<String, PdfTemplateV2> index = new HashMap<>();
        for (final PdfTemplateV2 t : resolved) {
            if (t.getInstitution() != null) {
                index.put(t.getInstitution().toLowerCase(Locale.ROOT).trim(), t);
            }
        }
        this.byInstitution = Collections.unmodifiableMap(index);
        LOGGER.info("v2 registry: {} template(s) ready (after extends resolution)",
                templates.size());
    }

    public List<PdfTemplateV2> all() {
        return templates;
    }

    /**
     * Find the v2 template matching an institution name (case-insensitive).
     * O(1) hashmap lookup; built once at load and reused across every PDF.
     */
    public PdfTemplateV2 findByInstitution(final String institutionName) {
        if (institutionName == null) return null;
        return byInstitution.get(institutionName.toLowerCase(Locale.ROOT).trim());
    }

    /** Test seam: load a custom resource pattern (used by harness/tests). */
    public void initForTesting(final String pattern) {
        this.resourcePattern = pattern;
        init();
    }
}
