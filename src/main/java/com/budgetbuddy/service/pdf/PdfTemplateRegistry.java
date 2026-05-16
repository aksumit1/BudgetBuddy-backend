package com.budgetbuddy.service.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Data-driven PDF parse templates, loaded from classpath YAML at startup.
 *
 * <h3>Why this exists</h3>
 *
 * The legacy PDFImportService has 7 hardcoded {@code parsePattern1..7} methods and one {@code
 * EnhancedPatternMatcher}. Adding support for a new bank today means writing Java, extending the
 * dispatcher, and shipping a backend deploy. That cadence doesn't scale — every new issuer, every
 * layout change on an existing issuer, is an engineering task.
 *
 * <p>The registry inverts the contract: a template is a YAML file in {@code
 * src/main/resources/pdf-templates/} describing the line shape. The service code stays stable; new
 * banks are a data change that can be written by anyone comfortable with regex.
 *
 * <h3>Template schema</h3>
 *
 * <pre>
 *   id: chase-checking-v1         # stable identifier, used in logs
 *   institution: Chase            # optional — enables institution-keyed priority
 *   accountType: checking         # optional — extra scoping hint
 *   description: "Chase checking statement, Q2 2024 layout"
 *   # Line regex: must contain exactly these three named groups.
 *   #   (?&lt;date&gt;...), (?&lt;description&gt;...), (?&lt;amount&gt;...)
 *   lineRegex: "^(?&lt;date&gt;\\d{2}/\\d{2})\\s+(?&lt;description&gt;.+?)\\s+\\$?(?&lt;amount&gt;[-\\d,.]+)$"
 *   dateFormat: "MM/dd"           # optional; defaults to auto-detection in PDFImportService
 *   # Per-institution sign convention. Some banks put charges as positive
 *   # (credit-card style), others as negative (checking-style). Defaults to
 *   # "as-is" — the structured parser then applies its own sign logic.
 *   signConvention: as-is         # as-is | negate | credit-positive
 *   minAmount: 0.01               # safety floor to reject noise rows
 * </pre>
 *
 * <h3>Rollout</h3>
 *
 * The legacy Pattern 1-7 parsers are unchanged; registry templates run <em>alongside</em> them so
 * this refactor is purely additive. Over time, each legacy pattern can be extracted into a YAML
 * file and the Java implementation deleted.
 *
 * <h3>Graceful absence</h3>
 *
 * If the resource pattern matches zero files, the registry reports empty and the rest of the import
 * pipeline is unaffected. Malformed YAML logs a WARN and is skipped — one bad template file can't
 * take down the whole service.
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class PdfTemplateRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfTemplateRegistry.class);

    @Value("${pdf.templates.resource-pattern:classpath*:pdf-templates/*.yaml}")
    private String resourcePattern;

    private List<PdfTemplate> templates = Collections.emptyList();

    @PostConstruct
    public void init() {
        final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        final List<PdfTemplate> loaded = new ArrayList<>();

        final Resource[] resources;
        try {
            resources = resolver.getResources(resourcePattern);
        } catch (IOException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "PdfTemplateRegistry: couldn't resolve \"{}\" — no templates loaded. Cause: {}",
                        resourcePattern,
                        e.getMessage());
            }
            return;
        }

        if (resources.length == 0) {
            LOGGER.info(
                    "PdfTemplateRegistry: no YAML templates on \"{}\" — registry empty (legacy parsers still active).",
                    resourcePattern);
            return;
        }

        for (final Resource resource : resources) {
            final String name =
                    resource.getFilename() != null ? resource.getFilename() : "(unnamed)";
            try (InputStream in = resource.getInputStream()) {
                final PdfTemplate template = yamlMapper.readValue(in, PdfTemplate.class);
                if (template.validate()) {
                    loaded.add(template);
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(
                                "PdfTemplateRegistry: loaded template \"{}\" (institution={}, layouts={}, status={})",
                                template.getId(),
                                template.getInstitution(),
                                template.getLayouts().size(),
                                template.getStatus());
                    }
                } else {
                    LOGGER.warn(
                            "PdfTemplateRegistry: template \"{}\" failed validation — skipped",
                            name);
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "PdfTemplateRegistry: failed to load \"{}\" — skipped. Cause: {}",
                            name,
                            e.getMessage());
                }
            }
        }

        this.templates = Collections.unmodifiableList(loaded);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("PdfTemplateRegistry: {} template(s) ready", templates.size());
        }
    }

    public List<PdfTemplate> all() {
        return templates;
    }

    /**
     * Ordered view with institution-matching templates first. Caller passes the detected
     * institution name (from {@code AccountDetectionService}); pass null/empty if unknown, we'll
     * return the unordered list.
     *
     * <p>Match is case-insensitive substring on institution name, which keeps "Chase" and "JPMorgan
     * Chase Bank" matching the same template.
     */
    public List<PdfTemplate> orderedFor(final String detectedInstitution) {
        if (detectedInstitution == null || detectedInstitution.isBlank() || templates.isEmpty()) {
            return templates;
        }
        final String needle = detectedInstitution.toLowerCase(Locale.ROOT);
        final List<PdfTemplate> preferred = new ArrayList<>();
        for (final PdfTemplate t : templates) {
            final String inst = t.getInstitution();
            if (inst != null
                    && !inst.isBlank()
                    && (needle.contains(inst.toLowerCase(Locale.ROOT))
                            || inst.toLowerCase(Locale.ROOT).contains(needle))) {
                preferred.add(t);
            }
        }
        // When the institution is confidently detected, return ONLY the matching
        // templates. Pre-fix, off-institution templates (PNC, Regions, TD Bank,
        // U.S. Bank checking-single-line) were picked up as fallbacks on lines
        // the detected issuer's own template couldn't match — and they often
        // stripped the negative sign on payment amounts, producing a phantom
        // duplicate of an already-extracted AutoPay row. The structured-parse
        // path already covers any line the YAML registry doesn't, so the
        // off-institution fallback was strictly harmful here.
        //
        // Fall back to the full template list only when NO institution-keyed
        // template is registered for this issuer — that's the legitimate
        // "let's try anything" case for an unknown layout.
        if (preferred.isEmpty()) {
            return templates;
        }
        return Collections.unmodifiableList(preferred);
    }
}
