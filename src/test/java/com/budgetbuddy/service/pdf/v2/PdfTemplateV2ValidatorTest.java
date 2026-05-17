package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Linter contract: every committed v2 YAML template must produce zero ERROR
 * issues from {@link PdfTemplateV2Validator}. WARNs are advisory and allowed.
 *
 * <p>This pins authoring quality at the repo boundary — a typo in a new YAML
 * field name, an uncompileable regex, or a stacked-rule with no stacked_index
 * fails CI rather than silently degrading extraction at runtime.
 */
class PdfTemplateV2ValidatorTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @TestFactory
    Iterable<DynamicTest> everyCommittedYamlLintsCleanly() throws IOException {
        final List<DynamicTest> out = new ArrayList<>();
        final Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:pdf-templates-v2/*.yaml");
        assertTrue(resources.length > 0, "no v2 YAMLs found on classpath");
        for (final Resource r : resources) {
            out.add(DynamicTest.dynamicTest("lint: " + r.getFilename(), () -> {
                final PdfTemplateV2 t = YAML_MAPPER.readValue(
                        r.getInputStream(), PdfTemplateV2.class);
                final List<PdfTemplateV2Validator.Issue> issues =
                        PdfTemplateV2Validator.validate(t);
                final List<PdfTemplateV2Validator.Issue> errors = new ArrayList<>();
                for (final PdfTemplateV2Validator.Issue i : issues) {
                    if (i.severity == PdfTemplateV2Validator.Severity.ERROR) errors.add(i);
                }
                assertTrue(errors.isEmpty(),
                        "ERROR issues in " + r.getFilename() + ":\n  " + String.join("\n  ",
                                errors.stream().map(Object::toString).toList()));
            }));
        }
        return out;
    }

    @Test
    void detectsMissingRequiredFields() {
        final PdfTemplateV2 t = new PdfTemplateV2();
        // Both id and institution missing.
        final List<PdfTemplateV2Validator.Issue> issues = PdfTemplateV2Validator.validate(t);
        assertEquals(2, issues.stream()
                .filter(i -> i.severity == PdfTemplateV2Validator.Severity.ERROR)
                .count());
    }

    @Test
    void detectsUncompileableRegex() {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId("test");
        t.setInstitution("Test Bank");
        final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
        final PdfTemplateV2.LabelRule bad = new PdfTemplateV2.LabelRule();
        bad.setPattern("(unclosed");
        m.setNewBalance(List.of(bad));
        t.setMetadata(m);
        final boolean foundError = PdfTemplateV2Validator.validate(t).stream()
                .anyMatch(i -> i.severity == PdfTemplateV2Validator.Severity.ERROR
                        && i.path.contains("new_balance")
                        && i.message.contains("invalid regex"));
        assertTrue(foundError, "uncompileable regex must be flagged as ERROR");
    }

    @Test
    void detectsRuleWithNoLabelOrPatternOrStacked() {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId("test");
        t.setInstitution("Test Bank");
        final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
        final PdfTemplateV2.LabelRule empty = new PdfTemplateV2.LabelRule();
        m.setPreviousBalance(List.of(empty));
        t.setMetadata(m);
        final boolean foundError = PdfTemplateV2Validator.validate(t).stream()
                .anyMatch(i -> i.severity == PdfTemplateV2Validator.Severity.ERROR
                        && i.message.contains("can never match"));
        assertTrue(foundError, "empty rule must be flagged as ERROR");
    }

    @Test
    void detectsStackedIndexOutOfRange() {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId("test");
        t.setInstitution("Test Bank");
        final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
        final PdfTemplateV2.LabelRule rule = new PdfTemplateV2.LabelRule();
        rule.setStackedHeader("Account Summary");
        rule.setStackedLabels(List.of("A", "B"));
        rule.setStackedIndex(5);
        m.setPurchasesTotal(List.of(rule));
        t.setMetadata(m);
        final boolean foundError = PdfTemplateV2Validator.validate(t).stream()
                .anyMatch(i -> i.severity == PdfTemplateV2Validator.Severity.ERROR
                        && i.message.contains("out of range"));
        assertTrue(foundError, "stacked_index out of range must be flagged");
    }

    @Test
    void detectsUnknownAdjacentKind() {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId("test");
        t.setInstitution("Test Bank");
        final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
        final PdfTemplateV2.LabelRule rule = new PdfTemplateV2.LabelRule();
        rule.setLabel("New Balance");
        rule.setAdjacent("widgets");
        m.setNewBalance(List.of(rule));
        t.setMetadata(m);
        final boolean foundWarn = PdfTemplateV2Validator.validate(t).stream()
                .anyMatch(i -> i.severity == PdfTemplateV2Validator.Severity.WARN
                        && i.message.contains("unrecognized adjacent"));
        assertTrue(foundWarn, "unknown adjacent kind must be flagged as WARN");
    }

    @Test
    void validSampleSchemaPassesWithoutIssues() {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId("minimal");
        t.setInstitution("Minimal Bank");
        final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
        final PdfTemplateV2.LabelRule ok = new PdfTemplateV2.LabelRule();
        ok.setLabel("New Balance");
        ok.setAdjacent("dollar");
        m.setNewBalance(List.of(ok));
        t.setMetadata(m);
        final boolean anyError = PdfTemplateV2Validator.validate(t).stream()
                .anyMatch(i -> i.severity == PdfTemplateV2Validator.Severity.ERROR);
        assertFalse(anyError, "minimal valid template must not produce ERROR issues");
    }
}
