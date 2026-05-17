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
        out.setSamples(child.getSamples());
        return out;
    }

    private static PdfTemplateV2.MetadataRules mergeMetadata(
            final PdfTemplateV2.MetadataRules c, final PdfTemplateV2.MetadataRules p) {
        if (p == null) return c;
        if (c == null) return p;
        final PdfTemplateV2.MetadataRules out = new PdfTemplateV2.MetadataRules();
        out.setStatementDate(concat(c.getStatementDate(), p.getStatementDate()));
        out.setStatementPeriod(concatPeriods(c.getStatementPeriod(), p.getStatementPeriod()));
        out.setNewBalance(concat(c.getNewBalance(), p.getNewBalance()));
        out.setPreviousBalance(concat(c.getPreviousBalance(), p.getPreviousBalance()));
        out.setCreditLimit(concat(c.getCreditLimit(), p.getCreditLimit()));
        out.setAvailableCredit(concat(c.getAvailableCredit(), p.getAvailableCredit()));
        out.setMinimumPaymentDue(concat(c.getMinimumPaymentDue(), p.getMinimumPaymentDue()));
        out.setPaymentDueDate(concat(c.getPaymentDueDate(), p.getPaymentDueDate()));
        out.setPurchasesTotal(concat(c.getPurchasesTotal(), p.getPurchasesTotal()));
        out.setPaymentsTotal(concat(c.getPaymentsTotal(), p.getPaymentsTotal()));
        out.setPaymentsTotalSum(c.isPaymentsTotalSum() || p.isPaymentsTotalSum());
        out.setFeesTotal(concat(c.getFeesTotal(), p.getFeesTotal()));
        out.setInterestTotal(concat(c.getInterestTotal(), p.getInterestTotal()));
        out.setYtdFees(concat(c.getYtdFees(), p.getYtdFees()));
        out.setYtdInterest(concat(c.getYtdInterest(), p.getYtdInterest()));
        out.setPurchaseApr(concat(c.getPurchaseApr(), p.getPurchaseApr()));
        out.setCashAdvanceApr(concat(c.getCashAdvanceApr(), p.getCashAdvanceApr()));
        out.setBalanceTransferApr(concat(c.getBalanceTransferApr(), p.getBalanceTransferApr()));
        out.setPenaltyApr(concat(c.getPenaltyApr(), p.getPenaltyApr()));
        out.setPointsBalance(concat(c.getPointsBalance(), p.getPointsBalance()));
        out.setPointsEarned(concat(c.getPointsEarned(), p.getPointsEarned()));
        out.setPreviousPointsBalance(
                concat(c.getPreviousPointsBalance(), p.getPreviousPointsBalance()));
        out.setCashbackBalance(concat(c.getCashbackBalance(), p.getCashbackBalance()));
        out.setAutopayEnabled(concat(c.getAutopayEnabled(), p.getAutopayEnabled()));
        out.setNextAutopayAmount(concat(c.getNextAutopayAmount(), p.getNextAutopayAmount()));
        out.setAnnualFee(concat(c.getAnnualFee(), p.getAnnualFee()));
        out.setAnnualFeeDueDate(concat(c.getAnnualFeeDueDate(), p.getAnnualFeeDueDate()));
        out.setForeignTxFeePercent(
                concat(c.getForeignTxFeePercent(), p.getForeignTxFeePercent()));
        out.setBillingDays(concat(c.getBillingDays(), p.getBillingDays()));
        return out;
    }

    /** Child rules first, parent rules appended. Both lists may be empty. */
    private static List<PdfTemplateV2.LabelRule> concat(
            final List<PdfTemplateV2.LabelRule> child,
            final List<PdfTemplateV2.LabelRule> parent) {
        if (child == null || child.isEmpty()) return parent == null ? List.of() : parent;
        if (parent == null || parent.isEmpty()) return child;
        final List<PdfTemplateV2.LabelRule> out = new ArrayList<>(child.size() + parent.size());
        out.addAll(child);
        out.addAll(parent);
        return out;
    }

    private static List<PdfTemplateV2.PeriodRule> concatPeriods(
            final List<PdfTemplateV2.PeriodRule> child,
            final List<PdfTemplateV2.PeriodRule> parent) {
        if (child == null || child.isEmpty()) return parent == null ? List.of() : parent;
        if (parent == null || parent.isEmpty()) return child;
        final List<PdfTemplateV2.PeriodRule> out = new ArrayList<>(child.size() + parent.size());
        out.addAll(child);
        out.addAll(parent);
        return out;
    }
}
