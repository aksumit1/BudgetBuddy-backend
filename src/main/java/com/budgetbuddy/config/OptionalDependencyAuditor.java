package com.budgetbuddy.config;

import jakarta.annotation.PostConstruct;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Audits every Spring bean for {@code @Autowired(required=false)}
 * fields whose injection target wasn't satisfied — and surfaces a
 * single startup log line summarising which optional dependencies
 * are absent in this environment.
 *
 * <p>Rationale: the codebase has ~120 such injection sites (PDF
 * importer feature flags, observability adapters, optional clouds).
 * Most are designed to be optional. But a wiring regression — a bean
 * that SHOULD be present but isn't — would otherwise degrade silently.
 * One concise log line lets ops eyeball "did the deploy land what we
 * expected?" without touching every service.
 *
 * <p>Output looks like:
 * <pre>
 *   OptionalDependencyAuditor: 7 optional injections unwired:
 *     - PlaidSyncOrchestrator.cloudWatchService
 *     - PDFImportService.aiMerchantCanonicalizer
 *     - ...
 * </pre>
 *
 * <p>This is observability, not a fix — services that need to act on
 * a missing dependency still implement their own checks. This just
 * makes the inventory visible.
 */
@Component
public class OptionalDependencyAuditor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OptionalDependencyAuditor.class);
    private static final int MAX_REPORTED = 50;

    private final ApplicationContext appContext;

    public OptionalDependencyAuditor(final ApplicationContext appContext) {
        this.appContext = appContext;
    }

    @PostConstruct
    void audit() {
        final List<String> missing = new ArrayList<>();
        final Map<String, Object> beans = appContext.getBeansOfType(Object.class);
        for (final var entry : beans.entrySet()) {
            final Object bean = entry.getValue();
            // Skip framework + library beans — only audit our own.
            if (bean == null
                    || !bean.getClass().getPackageName().startsWith("com.budgetbuddy")) {
                continue;
            }
            scanFields(bean, missing);
        }
        if (missing.isEmpty()) {
            LOGGER.info(
                    "OptionalDependencyAuditor: all @Autowired(required=false) fields wired.");
            return;
        }
        final int reported = Math.min(missing.size(), MAX_REPORTED);
        final StringBuilder sb = new StringBuilder();
        sb.append("OptionalDependencyAuditor: ")
                .append(missing.size())
                .append(" optional @Autowired(required=false) injection(s) are unwired ")
                .append("in this environment. Expected for feature-flagged deps; ")
                .append("investigate any you don't recognize:");
        for (int i = 0; i < reported; i++) {
            sb.append("\n  - ").append(missing.get(i));
        }
        if (missing.size() > reported) {
            sb.append("\n  ... and ").append(missing.size() - reported).append(" more");
        }
        LOGGER.info(sb.toString());
    }

    private static void scanFields(final Object bean, final List<String> missing) {
        final Class<?> raw = unproxy(bean.getClass());
        for (final Field f : raw.getDeclaredFields()) {
            final Autowired ann = f.getAnnotation(Autowired.class);
            if (ann == null || ann.required()) {
                continue;
            }
            try {
                f.setAccessible(true);
                if (f.get(bean) == null) {
                    missing.add(raw.getSimpleName() + "." + f.getName());
                }
            } catch (final IllegalAccessException ignored) {
                // Spring-internal proxies refuse setAccessible(true)
                // under SecurityManager; skip — they're not interesting
                // for this audit.
            }
        }
    }

    /** Unwrap Spring's CGLIB/JDK proxy classes to see the real declaring class. */
    private static Class<?> unproxy(final Class<?> raw) {
        Class<?> c = raw;
        while (c.getName().contains("$$")
                || c.getName().contains("EnhancerBySpringCGLIB")) {
            c = c.getSuperclass();
            if (c == null) {
                return raw;
            }
        }
        return c;
    }
}
