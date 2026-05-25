package com.budgetbuddy.service.pdf.v2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves {@code extends:} chains on v2 templates so a common fragment
 * (e.g. {@code common.yaml} carrying Payment Due Date / Minimum Payment Due
 * / FX fee / billing-days patterns shared across most issuers) doesn't have
 * to be duplicated in every per-issuer YAML.
 *
 * <p>Merge semantics: <b>child rules come first, parent rules append after.</b>
 * The evaluator uses first-match-wins, so a child can override (or refine)
 * any parent rule by declaring its own at the top of the list. The parent
 * remains as a fallback. This matches how every existing YAML's multi-pattern
 * rule lists already behave; inheritance is just a way to source the tail of
 * the list from a different file.
 *
 * <p>Detection: cycles are guarded by a visited set; a cycle logs WARN and
 * the broken parent is skipped. Missing parent IDs log WARN. Both leave the
 * child loadable — graceful degradation matters more than strict resolution.
 */
public final class TemplateMerger {

    private static final Logger LOGGER = LoggerFactory.getLogger(TemplateMerger.class);

    private TemplateMerger() { }

    /**
     * Resolve the extends chain for every template in {@code all}, returning a
     * new list of post-merge templates in the same iteration order. The input
     * list isn't mutated; templates without an {@code extends:} are returned
     * unchanged.
     */
    public static List<PdfTemplateV2> resolve(final List<PdfTemplateV2> all) {
        if (all == null || all.isEmpty()) return List.of();
        final Map<String, PdfTemplateV2> byId = new HashMap<>();
        for (final PdfTemplateV2 t : all) {
            if (t.getId() != null) byId.put(t.getId(), t);
        }
        final List<PdfTemplateV2> out = new ArrayList<>(all.size());
        for (final PdfTemplateV2 t : all) {
            if (t.getExtendsList().isEmpty()) {
                out.add(t);
            } else {
                final Set<String> seen = new HashSet<>();
                if (t.getId() != null) seen.add(t.getId());
                out.add(mergeChain(t, byId, seen));
            }
        }
        return out;
    }

    private static PdfTemplateV2 mergeChain(
            final PdfTemplateV2 child, final Map<String, PdfTemplateV2> byId,
            final Set<String> seen) {
        PdfTemplateV2 merged = child;
        for (final String parentId : child.getExtendsList()) {
            if (parentId == null || parentId.isBlank()) continue;
            if (seen.contains(parentId)) {
                LOGGER.warn("v2 extends cycle detected: {} -> {} (skipping)",
                        child.getId(), parentId);
                continue;
            }
            final PdfTemplateV2 parent = byId.get(parentId);
            if (parent == null) {
                LOGGER.warn("v2 extends target missing: {} -> {} (parent not found)",
                        child.getId(), parentId);
                continue;
            }
            // Mark THIS parent as seen for the duration of resolving its
            // ancestors so a cycle deeper in the chain is also caught.
            seen.add(parentId);
            final PdfTemplateV2 resolvedParent = parent.getExtendsList().isEmpty()
                    ? parent : mergeChain(parent, byId, seen);
            merged = mergePair(merged, resolvedParent);
        }
        return merged;
    }

    /**
     * Build a NEW template carrying child's identity fields, child's rules
     * followed by parent's rules, child's preprocessing/layouts when present
     * (parent's only when child has none).
     */
    private static PdfTemplateV2 mergePair(
            final PdfTemplateV2 child, final PdfTemplateV2 parent) {
        final PdfTemplateV2 out = new PdfTemplateV2();
        out.setId(child.getId());
        out.setInstitution(child.getInstitution());
        out.setDescription(child.getDescription());
        out.setStatus(child.getStatus());
        out.setExtendsList(child.getExtendsList());
        out.setCardDetection(child.getCardDetection() != null
                ? child.getCardDetection() : parent.getCardDetection());
        out.setMetadata(mergeMetadata(child.getMetadata(), parent.getMetadata()));
        out.setLayouts(child.getLayouts().isEmpty()
                ? parent.getLayouts() : child.getLayouts());
        out.setPreprocessing(child.getPreprocessing() != null
                ? child.getPreprocessing() : parent.getPreprocessing());
        // Inherit transactions: from parent when child has none. Pre-fix this
        // silently dropped a parent's transaction-shape list — agents working
        // on issuer templates had to inline shapes that should have lived
        // in common.yaml as fallbacks. Same rule for card_holders and
        // samples: child wins when populated, parent fills the gap.
        out.setTransactions(child.getTransactions().isEmpty()
                ? parent.getTransactions() : child.getTransactions());
        out.setCardHolders(child.getCardHolders().isEmpty()
                ? parent.getCardHolders() : child.getCardHolders());
        out.setSamples(child.getSamples().isEmpty()
                ? parent.getSamples() : child.getSamples());
        return out;
    }

    private static PdfTemplateV2.MetadataRules mergeMetadata(
            final PdfTemplateV2.MetadataRules c, final PdfTemplateV2.MetadataRules p) {
        if (p == null) return c;
        if (c == null) return p;
        // OVERRIDE SEMANTICS: when child declares ANY rule for a field, the
        // child's list wins entirely (no concat with parent). Parent only
        // fills fields the child omits completely. This prevents
        // common.yaml's generic patterns from polluting issuer-tuned ones —
        // for example, common's "AUTOPAY IS ON" label firing after Wells
        // Fargo's sentence-anchored autopay flag is already set, doubling
        // the next-autopay roll-up. The semantic matches author intuition:
        // "extends pulls in patterns I haven't bothered to define myself,
        // not patterns to run alongside mine."
        final PdfTemplateV2.MetadataRules out = new PdfTemplateV2.MetadataRules();
        out.setStatementDate(override(c.getStatementDate(), p.getStatementDate()));
        out.setStatementPeriod(overridePeriods(c.getStatementPeriod(), p.getStatementPeriod()));
        out.setNewBalance(override(c.getNewBalance(), p.getNewBalance()));
        out.setPreviousBalance(override(c.getPreviousBalance(), p.getPreviousBalance()));
        out.setCreditLimit(override(c.getCreditLimit(), p.getCreditLimit()));
        out.setAvailableCredit(override(c.getAvailableCredit(), p.getAvailableCredit()));
        out.setMinimumPaymentDue(override(c.getMinimumPaymentDue(), p.getMinimumPaymentDue()));
        out.setPaymentDueDate(override(c.getPaymentDueDate(), p.getPaymentDueDate()));
        out.setPurchasesTotal(override(c.getPurchasesTotal(), p.getPurchasesTotal()));
        out.setPaymentsTotal(override(c.getPaymentsTotal(), p.getPaymentsTotal()));
        out.setPaymentsTotalSum(c.isPaymentsTotalSum() || p.isPaymentsTotalSum());
        out.setPurchasesTotalSum(c.isPurchasesTotalSum() || p.isPurchasesTotalSum());
        out.setFeesTotal(override(c.getFeesTotal(), p.getFeesTotal()));
        out.setInterestTotal(override(c.getInterestTotal(), p.getInterestTotal()));
        out.setOtherCreditsTotal(
                override(c.getOtherCreditsTotal(), p.getOtherCreditsTotal()));
        out.setYtdFees(override(c.getYtdFees(), p.getYtdFees()));
        out.setYtdInterest(override(c.getYtdInterest(), p.getYtdInterest()));
        out.setPurchaseApr(override(c.getPurchaseApr(), p.getPurchaseApr()));
        out.setCashAdvanceApr(override(c.getCashAdvanceApr(), p.getCashAdvanceApr()));
        out.setBalanceTransferApr(override(c.getBalanceTransferApr(), p.getBalanceTransferApr()));
        out.setPenaltyApr(override(c.getPenaltyApr(), p.getPenaltyApr()));
        out.setPointsBalance(override(c.getPointsBalance(), p.getPointsBalance()));
        out.setPointsEarned(override(c.getPointsEarned(), p.getPointsEarned()));
        out.setPreviousPointsBalance(
                override(c.getPreviousPointsBalance(), p.getPreviousPointsBalance()));
        out.setCashbackBalance(override(c.getCashbackBalance(), p.getCashbackBalance()));
        out.setAutopayEnabled(override(c.getAutopayEnabled(), p.getAutopayEnabled()));
        out.setNextAutopayAmount(override(c.getNextAutopayAmount(), p.getNextAutopayAmount()));
        out.setAnnualFee(override(c.getAnnualFee(), p.getAnnualFee()));
        out.setAnnualFeeDueDate(override(c.getAnnualFeeDueDate(), p.getAnnualFeeDueDate()));
        out.setForeignTxFeePercent(
                override(c.getForeignTxFeePercent(), p.getForeignTxFeePercent()));
        out.setBillingDays(override(c.getBillingDays(), p.getBillingDays()));
        return out;
    }

    /**
     * Override semantics: when child declares ANY rule, the child's list is
     * the final list — parent's rules are ignored entirely for that field.
     * The parent only contributes when the child has nothing declared
     * (empty/null list). Matches author intuition for {@code extends:}.
     */
    private static List<PdfTemplateV2.LabelRule> override(
            final List<PdfTemplateV2.LabelRule> child,
            final List<PdfTemplateV2.LabelRule> parent) {
        if (child == null || child.isEmpty()) return parent == null ? List.of() : parent;
        return child;
    }

    private static List<PdfTemplateV2.PeriodRule> overridePeriods(
            final List<PdfTemplateV2.PeriodRule> child,
            final List<PdfTemplateV2.PeriodRule> parent) {
        if (child == null || child.isEmpty()) return parent == null ? List.of() : parent;
        return child;
    }
}
