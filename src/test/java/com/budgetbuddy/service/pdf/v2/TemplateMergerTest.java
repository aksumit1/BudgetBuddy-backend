package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

class TemplateMergerTest {

    private static PdfTemplateV2.LabelRule pattern(final String p) {
        final PdfTemplateV2.LabelRule r = new PdfTemplateV2.LabelRule();
        r.setPattern(p);
        return r;
    }

    private static PdfTemplateV2 template(final String id, final List<String> extendsList) {
        final PdfTemplateV2 t = new PdfTemplateV2();
        t.setId(id);
        t.setInstitution(id + " Bank");
        t.setExtendsList(extendsList);
        return t;
    }

    @Test
    void templateWithoutExtendsIsReturnedUnchanged() {
        final PdfTemplateV2 t = template("solo", List.of());
        final List<PdfTemplateV2> out = TemplateMerger.resolve(List.of(t));
        assertSame(t, out.get(0));
    }

    @Test
    void childRulesComeBeforeParentRules() {
        final PdfTemplateV2 parent = template("common", List.of());
        final PdfTemplateV2.MetadataRules pm = new PdfTemplateV2.MetadataRules();
        pm.setNewBalance(List.of(pattern("parent-A"), pattern("parent-B")));
        parent.setMetadata(pm);

        final PdfTemplateV2 child = template("issuer", List.of("common"));
        final PdfTemplateV2.MetadataRules cm = new PdfTemplateV2.MetadataRules();
        cm.setNewBalance(List.of(pattern("child-1")));
        child.setMetadata(cm);

        final List<PdfTemplateV2> out = TemplateMerger.resolve(List.of(parent, child));
        final PdfTemplateV2 resolved = out.get(1);
        final List<PdfTemplateV2.LabelRule> merged = resolved.getMetadata().getNewBalance();
        assertEquals(3, merged.size());
        assertEquals("child-1", merged.get(0).getPattern(),
                "child rule must come first so it wins on first-match");
        assertEquals("parent-A", merged.get(1).getPattern());
        assertEquals("parent-B", merged.get(2).getPattern());
    }

    @Test
    void childPreservesItsOwnIdAndExtendsList() {
        final PdfTemplateV2 parent = template("common", List.of());
        final PdfTemplateV2 child = template("issuer", List.of("common"));
        final List<PdfTemplateV2> out = TemplateMerger.resolve(List.of(parent, child));
        final PdfTemplateV2 resolved = out.get(1);
        assertEquals("issuer", resolved.getId());
        assertEquals(List.of("common"), resolved.getExtendsList());
    }

    @Test
    void missingParentDoesNotBreakLoad() {
        final PdfTemplateV2 child = template("orphan", List.of("nonexistent"));
        final PdfTemplateV2.MetadataRules cm = new PdfTemplateV2.MetadataRules();
        cm.setNewBalance(List.of(pattern("own")));
        child.setMetadata(cm);
        // Should not throw — missing parent logs WARN and the child loads with
        // only its own rules.
        final List<PdfTemplateV2> out = TemplateMerger.resolve(List.of(child));
        final PdfTemplateV2 resolved = out.get(0);
        assertEquals(1, resolved.getMetadata().getNewBalance().size());
        assertEquals("own", resolved.getMetadata().getNewBalance().get(0).getPattern());
    }

    @Test
    void transitiveChainIsResolved() {
        // base <- mid <- leaf, with one rule per level. Final order: leaf, mid, base.
        final PdfTemplateV2 base = template("base", List.of());
        final PdfTemplateV2.MetadataRules bm = new PdfTemplateV2.MetadataRules();
        bm.setNewBalance(List.of(pattern("base")));
        base.setMetadata(bm);

        final PdfTemplateV2 mid = template("mid", List.of("base"));
        final PdfTemplateV2.MetadataRules mm = new PdfTemplateV2.MetadataRules();
        mm.setNewBalance(List.of(pattern("mid")));
        mid.setMetadata(mm);

        final PdfTemplateV2 leaf = template("leaf", List.of("mid"));
        final PdfTemplateV2.MetadataRules lm = new PdfTemplateV2.MetadataRules();
        lm.setNewBalance(List.of(pattern("leaf")));
        leaf.setMetadata(lm);

        final List<PdfTemplateV2> out = TemplateMerger.resolve(List.of(base, mid, leaf));
        final PdfTemplateV2 resolvedLeaf = out.get(2);
        final List<PdfTemplateV2.LabelRule> merged =
                resolvedLeaf.getMetadata().getNewBalance();
        assertEquals(3, merged.size());
        assertEquals("leaf", merged.get(0).getPattern());
        assertEquals("mid", merged.get(1).getPattern());
        assertEquals("base", merged.get(2).getPattern());
    }

    @Test
    void cycleIsBrokenAndDoesNotInfiniteLoop() {
        // a -> b -> a. The merger should detect the cycle on the second hop,
        // log WARN, and load with whatever it managed to merge before the cycle.
        final PdfTemplateV2 a = template("a", List.of("b"));
        final PdfTemplateV2.MetadataRules am = new PdfTemplateV2.MetadataRules();
        am.setNewBalance(List.of(pattern("a")));
        a.setMetadata(am);

        final PdfTemplateV2 b = template("b", List.of("a"));
        final PdfTemplateV2.MetadataRules bm = new PdfTemplateV2.MetadataRules();
        bm.setNewBalance(List.of(pattern("b")));
        b.setMetadata(bm);

        final List<PdfTemplateV2> out = TemplateMerger.resolve(List.of(a, b));
        // The contract: NO infinite loop, both templates loadable.
        assertNotNull(out.get(0).getMetadata());
        assertNotNull(out.get(1).getMetadata());
    }

    @Test
    void paymentsSumFlagIsOrAcrossInheritance() {
        final PdfTemplateV2 parent = template("common", List.of());
        final PdfTemplateV2.MetadataRules pm = new PdfTemplateV2.MetadataRules();
        pm.setPaymentsTotalSum(true);
        parent.setMetadata(pm);

        final PdfTemplateV2 child = template("issuer", List.of("common"));
        final PdfTemplateV2.MetadataRules cm = new PdfTemplateV2.MetadataRules();
        // child doesn't set the flag; parent should pull it through.
        child.setMetadata(cm);

        final PdfTemplateV2 resolved = TemplateMerger.resolve(List.of(parent, child)).get(1);
        assertEquals(true, resolved.getMetadata().isPaymentsTotalSum());
    }

    @Test
    void childMetadataAloneWorksWhenParentHasNone() {
        final PdfTemplateV2 parent = template("common", List.of());
        // parent has no metadata at all
        final PdfTemplateV2 child = template("issuer", List.of("common"));
        final PdfTemplateV2.MetadataRules cm = new PdfTemplateV2.MetadataRules();
        cm.setNewBalance(List.of(pattern("child-only")));
        child.setMetadata(cm);

        final PdfTemplateV2 resolved = TemplateMerger.resolve(List.of(parent, child)).get(1);
        assertEquals(1, resolved.getMetadata().getNewBalance().size());
        assertEquals("child-only", resolved.getMetadata().getNewBalance().get(0).getPattern());
    }
}
