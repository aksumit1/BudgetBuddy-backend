package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Direct tests for the v2 plumbing layer: {@link RegexCache}, the
 * {@link ExtractionContext} provenance accumulator, {@link FieldBounds}
 * sanity checks, and {@code after_section} scoping on label rules.
 *
 * <p>These are unit tests for the primitives themselves; end-to-end fixture
 * coverage lives in {@link V2YamlSelfTest} and {@link V2FixtureRegressionTest}.
 */
class V2InfrastructureTest {

    @Nested
    class RegexCacheTests {
        @Test
        void compilesOnceAndReturnsSameInstance() {
            final Pattern a = RegexCache.compile("a+b", Pattern.CASE_INSENSITIVE);
            final Pattern b = RegexCache.compile("a+b", Pattern.CASE_INSENSITIVE);
            assertSame(a, b, "second compile must return cached instance");
        }

        @Test
        void differentFlagsAreCachedSeparately() {
            final Pattern a = RegexCache.compile("foo", 0);
            final Pattern b = RegexCache.compile("foo", Pattern.CASE_INSENSITIVE);
            assertNotNull(a);
            assertNotNull(b);
            // Distinct objects because flags differ.
            assertTrue(a != b);
        }

        @Test
        void uncompileablePatternReturnsNullAndCaches() {
            assertNull(RegexCache.compile("(unclosed"));
            // Second call also returns null without re-logging — but we can't
            // easily assert no-log; verifying the no-throw + null contract is enough.
            assertNull(RegexCache.compile("(unclosed"));
        }

        @Test
        void timedCharSequenceThrowsOnceDeadlineLapses() throws InterruptedException {
            // Tiny deadline; sleep past it; the next charAt must throw.
            final RegexCache.TimedCharSequence ts =
                    new RegexCache.TimedCharSequence("hello", 1);
            Thread.sleep(10);
            assertThrows(RegexCache.RegexTimeoutException.class, () -> ts.charAt(0));
        }

        @Test
        void safeMatcherFindCompletesWhenPatternIsWellBehaved() {
            final Pattern p = RegexCache.compile("\\d+", 0);
            final var matcher = RegexCache.safeMatcher(p, "abc 123 def");
            assertTrue(matcher.find());
            assertEquals("123", matcher.group());
        }
    }

    @Nested
    class ExtractionContextTests {
        @Test
        void splitsAndLowersLinesOnce() {
            final ExtractionContext ctx = new ExtractionContext("Line One\nLINE Two\n");
            // String.split drops trailing empty strings by default.
            assertEquals(2, ctx.lines.length);
            assertEquals("Line One", ctx.lines[0]);
            assertEquals("line one", ctx.linesLower[0]);
            assertEquals("LINE Two", ctx.lines[1]);
            assertEquals("line two", ctx.linesLower[1]);
            assertEquals("line one\nline two\n", ctx.fullTextLower);
        }

        @Test
        void recordHitAccumulatesAndLastWins() {
            final ExtractionContext ctx = new ExtractionContext("text");
            ctx.recordHit("foo", ExtractionContext.RuleHit.hit(0, "pattern", "abc"));
            ctx.recordHit("foo", ExtractionContext.RuleHit.hit(2, "label", "xyz"));
            final ExtractionContext.RuleHit last = ctx.getProvenance().get("foo");
            assertEquals(2, last.ruleIndex);
            assertEquals("label", last.ruleKind);
            assertEquals("xyz", last.captured);
        }

        @Test
        void provenanceIsUnmodifiable() {
            final ExtractionContext ctx = new ExtractionContext("");
            assertThrows(UnsupportedOperationException.class,
                    () -> ctx.getProvenance().put("k",
                            ExtractionContext.RuleHit.missed(0)));
        }
    }

    @Nested
    class ProvenanceIntegrationTests {
        @Test
        void provenanceCarriesRuleIndexAndKindForExtractedField() {
            // Build a small inline template with two rules — the SECOND rule
            // matches, so provenance must show ruleIndex=1.
            final PdfTemplateV2 t = new PdfTemplateV2();
            t.setId("test-v2"); t.setInstitution("Test Bank");
            final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
            final PdfTemplateV2.LabelRule miss = new PdfTemplateV2.LabelRule();
            miss.setPattern("(?im)^Total Balance \\$(\\d+\\.\\d{2})$");
            final PdfTemplateV2.LabelRule hit = new PdfTemplateV2.LabelRule();
            hit.setPattern("(?im)^New Balance \\$(\\d+\\.\\d{2})$");
            m.setNewBalance(List.of(miss, hit));
            t.setMetadata(m);

            final PdfTemplateV2Evaluator eval = new PdfTemplateV2Evaluator();
            final PdfTemplateV2Evaluator.MetadataResult r =
                    eval.evaluateMetadata(t, "Statement\nNew Balance $123.45\n");
            assertEquals(0, new BigDecimal("123.45").compareTo(r.newBalance));
            final ExtractionContext.RuleHit prov = r.provenance.get("new_balance");
            assertNotNull(prov, "provenance entry must exist for matched field");
            assertEquals(1, prov.ruleIndex, "second rule (index 1) won the match");
            assertEquals("pattern", prov.ruleKind);
            assertEquals("123.45", prov.captured);
        }

        @Test
        void provenanceIsAbsentWhenAllRulesMissed() {
            final PdfTemplateV2 t = new PdfTemplateV2();
            t.setId("test-v2"); t.setInstitution("Test Bank");
            final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
            final PdfTemplateV2.LabelRule miss = new PdfTemplateV2.LabelRule();
            miss.setPattern("(?im)^Total Balance \\$(\\d+\\.\\d{2})$");
            m.setNewBalance(List.of(miss));
            t.setMetadata(m);

            final PdfTemplateV2Evaluator eval = new PdfTemplateV2Evaluator();
            final PdfTemplateV2Evaluator.MetadataResult r =
                    eval.evaluateMetadata(t, "Statement only\n");
            assertNull(r.newBalance);
            assertNull(r.provenance.get("new_balance"));
        }
    }

    @Nested
    class AfterSectionScopeTests {
        @Test
        void afterSectionRestrictsRuleToTextFollowingHeader() {
            // The fixture has two "Total $X" lines. Without after_section, the
            // first match wins ($100). With after_section anchored on
            // "Cash Advances", the rule only sees the second occurrence ($55).
            final PdfTemplateV2 t = new PdfTemplateV2();
            t.setId("test-v2"); t.setInstitution("Test Bank");
            final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
            final PdfTemplateV2.LabelRule scoped = new PdfTemplateV2.LabelRule();
            scoped.setLabel("Total");
            scoped.setAdjacent("dollar");
            scoped.setAfterSection("Cash Advances");
            m.setNewBalance(List.of(scoped));
            t.setMetadata(m);

            final String fixture = String.join("\n",
                    "Purchases",
                    "Total $100.00",
                    "Cash Advances",
                    "Total $55.00",
                    "");
            final PdfTemplateV2Evaluator eval = new PdfTemplateV2Evaluator();
            final PdfTemplateV2Evaluator.MetadataResult r =
                    eval.evaluateMetadata(t, fixture);
            assertEquals(0, new BigDecimal("55.00").compareTo(r.newBalance),
                    "after_section must restrict to Cash Advances block");
        }

        @Test
        void afterSectionMissingMeansNoMatch() {
            final PdfTemplateV2 t = new PdfTemplateV2();
            t.setId("test-v2"); t.setInstitution("Test Bank");
            final PdfTemplateV2.MetadataRules m = new PdfTemplateV2.MetadataRules();
            final PdfTemplateV2.LabelRule scoped = new PdfTemplateV2.LabelRule();
            scoped.setLabel("Total");
            scoped.setAdjacent("dollar");
            scoped.setAfterSection("Nonexistent Section");
            m.setNewBalance(List.of(scoped));
            t.setMetadata(m);

            final PdfTemplateV2Evaluator eval = new PdfTemplateV2Evaluator();
            final PdfTemplateV2Evaluator.MetadataResult r =
                    eval.evaluateMetadata(t, "Total $99.00\n");
            assertNull(r.newBalance, "rule must fail to match when section header is absent");
        }
    }

    @Nested
    class FieldBoundsTests {
        @Test
        void inRangeValuesPassWithoutWarning() {
            final PdfTemplateV2Evaluator.MetadataResult r =
                    new PdfTemplateV2Evaluator.MetadataResult();
            r.newBalance = new BigDecimal("1234.56");
            r.creditLimit = new BigDecimal("15000.00");
            r.purchaseApr = new BigDecimal("19.49");
            r.pointsBalance = 50_000L;
            r.billingDays = 30;
            // No throw, no exception — just sanity check call.
            FieldBounds.checkAll(r);
        }

        @Test
        void outOfRangeValuesDoNotNullTheResult() {
            // Out-of-range produces a WARN log but the value stays set on the
            // result. This pins the contract: bounds are advisory, not enforced.
            final PdfTemplateV2Evaluator.MetadataResult r =
                    new PdfTemplateV2Evaluator.MetadataResult();
            r.purchaseApr = new BigDecimal("999"); // way out of range
            FieldBounds.checkAll(r);
            assertEquals(0, new BigDecimal("999").compareTo(r.purchaseApr),
                    "out-of-range value must remain set; bounds check is advisory only");
        }

        @Test
        void nullValuesAreSkipped() {
            final PdfTemplateV2Evaluator.MetadataResult r =
                    new PdfTemplateV2Evaluator.MetadataResult();
            // All null — must not throw NPE.
            FieldBounds.checkAll(r);
        }
    }
}
