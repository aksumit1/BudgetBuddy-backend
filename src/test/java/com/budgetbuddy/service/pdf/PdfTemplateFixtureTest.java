package com.budgetbuddy.service.pdf;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Fixture-driven regression harness for PDF template parsing.
 *
 * <h3>How it works</h3>
 *
 * Every institution that wants VALIDATED-grade coverage drops a fixture pair under {@code
 * src/test/resources/pdf-template-fixtures/<slug>/}:
 *
 * <ul>
 *   <li>{@code *.txt} — anonymised text extracted from a real PDF (or synthetic, but clearly
 *       labelled).
 *   <li>{@code *.expected.json} — the exact set of transactions the parser must produce from that
 *       text, in order.
 * </ul>
 *
 * On each CI run, {@link #allFixtures()} discovers every pair and emits a dynamic test per fixture.
 * A failing test blocks the merge. Promoting a template from {@code UNVERIFIED} → {@code VALIDATED}
 * requires at least one passing fixture — enforced at template-load time by {@link
 * PdfTemplateRegistry} (see {@code ensureValidatedTemplatesHaveFixtures}).
 *
 * <h3>Fixture JSON shape</h3>
 *
 * <pre>
 * {
 *   "institution": "Chase",
 *   "note": "synthetic — pending real anonymised sample",
 *   "expected": [
 *     { "date": "2024-03-15", "description": "AMZN MKTP", "amount": "34.99" },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <h3>Why text fixtures, not PDF fixtures</h3>
 *
 * PDF binary diffs are noisy in code review, and anonymising a real statement well enough to commit
 * to the repo is a legal/ops problem. Text excerpts after PDFBox extraction are what the parser
 * actually consumes, so the fixture layer tests what matters while staying review-friendly.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// `\n` in the format strings here is a literal LF (CSV rows / raw
// HTTP body templates), not a platform newline — we do NOT want %n.
@SuppressFBWarnings(
        value = "VA_FORMAT_STRING_USES_NEWLINE",
        justification = "literal LF in CSV / wire format, not platform newline")
@SuppressWarnings("PMD.LawOfDemeter")
class PdfTemplateFixtureTest {

    private static final Path FIXTURES_ROOT = Paths.get("src/test/resources/pdf-template-fixtures");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Discover every fixture pair under {@code pdf-template-fixtures/}. One dynamic test per
     * fixture — fixture name shows up in test output so a failure instantly points at the right
     * institution + sample.
     */
    @TestFactory
    Stream<DynamicTest> allFixtures() throws IOException {
        if (!Files.isDirectory(FIXTURES_ROOT)) {
            // No fixtures yet → emit a single placeholder test. Keeps CI green
            // when the repo hasn't grown fixtures, without hiding "no coverage".
            return Stream.of(
                    DynamicTest.dynamicTest(
                            "no-fixtures-present",
                            () ->
                                    System.out.println(
                                            "pdf-template-fixtures/ is empty — add fixture pairs to lock in parser behaviour")));
        }

        try (Stream<Path> files = Files.walk(FIXTURES_ROOT)) {
            final List<Path> textFixtures =
                    files.filter(p -> p.toString().endsWith(".txt"))
                            .sorted()
                            .collect(Collectors.toList());

            return textFixtures.stream()
                    .map(
                            txt -> {
                                final Path expectedPath =
                                        Paths.get(
                                                txt.toString()
                                                        .replaceFirst("\\.txt$", ".expected.json"));
                                final String displayName = FIXTURES_ROOT.relativize(txt).toString();
                                return DynamicTest.dynamicTest(
                                        displayName, () -> runFixture(txt, expectedPath));
                            });
        }
    }

    private void runFixture(final Path textPath, final Path expectedPath) throws IOException {
        // Load the expected result first; a fixture without an expected file is
        // a setup bug, not a parse failure — fail loudly.
        assertTrue(
                Files.isRegularFile(expectedPath),
                "Fixture " + textPath + " is missing its expected JSON sibling at " + expectedPath);

        final String text = new String(Files.readAllBytes(textPath), StandardCharsets.UTF_8);
        final FixtureExpectation expectation;
        try (InputStream in = Files.newInputStream(expectedPath)) {
            expectation = MAPPER.readValue(in, FixtureExpectation.class);
        }

        final PdfTemplateRegistry registry = loadRegistryForTest();
        final List<PdfTemplate> templates = registry.orderedFor(expectation.institution);

        if (templates.isEmpty()) {
            fail(
                    "No templates loaded from registry — check that pdf-templates/*.yaml "
                            + "are on the test classpath. Fixture: "
                            + textPath);
        }

        final List<Map<String, String>> actual = new ArrayList<>();
        final List<String> unmatchedLines = new ArrayList<>();
        for (final String line : text.split("\\r?\\n")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            boolean matched = false;
            for (final PdfTemplate template : templates) {
                final Map<String, String> row = template.apply(line, 2024);
                if (row != null) {
                    actual.add(row);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                unmatchedLines.add(line);
            }
        }

        if (actual.size() != expectation.expected.size() && !unmatchedLines.isEmpty()) {
            System.err.println(
                    "  Templates tried (count="
                            + templates.size()
                            + ", preferred institution="
                            + expectation.institution
                            + "):");
            for (final PdfTemplate t : templates) {
                System.err.println(
                        "    - "
                                + t.getId()
                                + " (institution="
                                + t.getInstitution()
                                + ", layouts="
                                + t.getLayouts().size()
                                + ")");
            }
            System.err.println("  Unmatched lines (first 5):");
            for (int i = 0; i < Math.min(5, unmatchedLines.size()); i++) {
                System.err.println("    " + i + ": \"" + unmatchedLines.get(i) + "\"");
            }
        }

        assertEquals(
                expectation.expected.size(),
                actual.size(),
                String.format(
                        "Row count mismatch for %s.\nExpected %d rows, got %d.\nFirst few actual: %s",
                        textPath,
                        expectation.expected.size(),
                        actual.size(),
                        actual.stream().limit(5).collect(Collectors.toList())));

        for (int i = 0; i < expectation.expected.size(); i++) {
            final ExpectedRow exp = expectation.expected.get(i);
            final Map<String, String> got = actual.get(i);
            assertEquals(exp.date, got.get("date"), "Row " + i + " date mismatch in " + textPath);
            assertEquals(
                    exp.description,
                    got.get("description"),
                    "Row " + i + " description mismatch in " + textPath);
            assertEquals(
                    exp.amount, got.get("amount"), "Row " + i + " amount mismatch in " + textPath);
        }
    }

    /**
     * Build a registry bound to the classpath templates. We instantiate directly rather than via
     * Spring so the test stays fast (no ApplicationContext) and hermetic (no accidental @Value
     * pickup from test profiles).
     */
    private PdfTemplateRegistry loadRegistryForTest() {
        final PdfTemplateRegistry registry = new PdfTemplateRegistry();
        // Inject the default resource pattern via reflection — the field is
        // @Value-driven in production but just a plain string field underneath.
        try {
            final java.lang.reflect.Field resourceField =
                    PdfTemplateRegistry.class.getDeclaredField("resourcePattern");
            resourceField.setAccessible(true);
            resourceField.set(registry, "classpath*:pdf-templates/*.yaml");
        } catch (NoSuchFieldException | IllegalAccessException e) {
            fail("Could not initialise PdfTemplateRegistry via reflection: " + e.getMessage());
        }
        registry.init();
        return registry;
    }

    // ---- fixture JSON wire types ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class FixtureExpectation {
        public String institution;
        public String note;
        public List<ExpectedRow> expected = Collections.emptyList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ExpectedRow {
        public String date;
        public String description;
        public String amount;
    }
}
