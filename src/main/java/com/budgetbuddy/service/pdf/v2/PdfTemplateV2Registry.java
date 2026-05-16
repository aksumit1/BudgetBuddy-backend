package com.budgetbuddy.service.pdf.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        this.templates = Collections.unmodifiableList(loaded);
        LOGGER.info("v2 registry: {} template(s) ready", templates.size());
    }

    public List<PdfTemplateV2> all() {
        return templates;
    }

    /** Find the v2 template matching an institution name (case-insensitive). */
    public PdfTemplateV2 findByInstitution(final String institutionName) {
        if (institutionName == null) return null;
        final String needle = institutionName.toLowerCase().trim();
        for (final PdfTemplateV2 t : templates) {
            if (t.getInstitution() != null
                    && t.getInstitution().toLowerCase().equals(needle)) {
                return t;
            }
        }
        return null;
    }

    /** Test seam: load a custom resource pattern (used by harness/tests). */
    public void initForTesting(final String pattern) {
        this.resourcePattern = pattern;
        init();
    }
}
