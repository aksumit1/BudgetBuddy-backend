package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.insights.InsightsContext;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The most important AI test in the codebase: pins the privacy
 * contract. Every assertion here is a "this PII type must never leak
 * to the LLM" statement. If any test fails, do not ship.
 */
class PrivacyPreservingExtractorTest {

    private final PrivacyPreservingExtractor svc = new PrivacyPreservingExtractor();

    @Test
    void nullContext_returnsEmptySnapshot_doesNotThrow() {
        final SanitizedSnapshot snap = svc.extract(null);
        assertNotNull(snap);
        assertEquals(0, snap.transactionCount90d());
        assertEquals(0, snap.accountCount());
        assertEquals("USD", snap.currency());
    }

    @Test
    void merchantNamesOutsideAllowlist_areDropped() {
        // "Dr. Smith Family Practice" is highly identifying (locates the
        // user geographically + medically). Must NOT appear in any
        // field the LLM sees.
        final InsightsContext ctx = ctxWith(
                tx("Dr. Smith Family Practice", "groceries", "-150", "2026-05-01"),
                tx("Joe's Diner Around The Corner", "dining", "-45", "2026-05-02"));
        final SanitizedSnapshot snap = svc.extract(ctx);

        // None of the per-merchant entries should contain these names.
        for (final String k : snap.spendingByKnownMerchant90d().keySet()) {
            assertFalse(k.toLowerCase().contains("smith"),
                    "Per-merchant key leaked private merchant name: " + k);
            assertFalse(k.toLowerCase().contains("joe"),
                    "Per-merchant key leaked private merchant name: " + k);
            assertFalse(k.toLowerCase().contains("corner"),
                    "Per-merchant key leaked private merchant name: " + k);
        }
        // The categories DO survive (intentional — categories are not
        // PII; the user has many of each).
        assertTrue(snap.spendingByCategory90d().containsKey("groceries")
                        || snap.spendingByCategory90d().containsKey("dining"),
                "Category buckets must remain populated for non-allowlist merchants");
    }

    @Test
    void merchantNamesInAllowlist_areAllowed() {
        // Netflix is a known global brand — passing it through doesn't
        // reveal anything about THIS user (everyone has Netflix).
        final InsightsContext ctx = ctxWith(
                tx("NETFLIX.COM", "streaming", "-15.99", "2026-05-01"),
                tx("NETFLIX.COM", "streaming", "-15.99", "2026-04-01"));
        final SanitizedSnapshot snap = svc.extract(ctx);
        assertTrue(snap.spendingByKnownMerchant90d().keySet().stream()
                .anyMatch(k -> k.contains("netflix")),
                "Allowlisted brand should survive in spendingByKnownMerchant");
    }

    @Test
    void noFreeFormDescriptions_inAnyOutputField() {
        // Transaction descriptions often contain card last-4, store
        // numbers, terminal IDs, addresses — they MUST NOT appear in
        // any output. Even though the extractor doesn't read
        // descriptions directly, this test pins that it never starts
        // doing so accidentally.
        final TransactionTable t = new TransactionTable();
        t.setMerchantName("Walmart"); // not in allowlist
        t.setDescription("WALMART CARD ENDING 1234 STORE #5678 TERMINAL 99");
        t.setCategoryPrimary("groceries");
        t.setAmount(new BigDecimal("-87.45"));
        t.setTransactionDate("2026-05-15");
        final InsightsContext ctx = new InsightsContext(
                "u1", LocalDate.parse("2026-05-23"), List.of(t), List.of(), List.of());

        final SanitizedSnapshot snap = svc.extract(ctx);
        // None of the description tokens should appear anywhere.
        final String allKeys = String.join(" ",
                snap.spendingByCategory90d().keySet())
                + " "
                + String.join(" ", snap.spendingByKnownMerchant90d().keySet())
                + " "
                + String.join(" ", snap.spendingByMonth().keySet());
        for (final String leak : List.of("1234", "5678", "TERMINAL", "ENDING", "STORE")) {
            assertFalse(allKeys.toUpperCase().contains(leak),
                    "Transaction description leaked: " + leak);
        }
    }

    @Test
    void dates_areTruncatedToYearMonth() {
        // Full day-of-month makes a user more identifiable (e.g. you
        // can match "$X on 2026-05-15" against a person's known
        // schedule). Only YYYY-MM should appear.
        final InsightsContext ctx = ctxWith(
                tx("Netflix", "streaming", "-15.99", "2026-05-15"),
                tx("Netflix", "streaming", "-15.99", "2026-04-15"));
        final SanitizedSnapshot snap = svc.extract(ctx);
        for (final String month : snap.spendingByMonth().keySet()) {
            assertEquals(7, month.length(), "Month bucket must be YYYY-MM (got " + month + ")");
        }
    }

    @Test
    void subscriptions_outsideAllowlist_areAnonymised() {
        // A subscription to "My-Therapist-Weekly-Sessions" (made up)
        // would expose the user's mental-health status to the LLM. The
        // extractor anonymises non-allowlist subs to "subscription_N".
        final Subscription sub = new Subscription();
        sub.setSubscriptionId("s1");
        sub.setActive(Boolean.TRUE);
        sub.setMerchantName("Anchor Counseling Services");
        sub.setAmount(new BigDecimal("180"));
        sub.setFrequency(Subscription.SubscriptionFrequency.MONTHLY);

        final InsightsContext ctx = new InsightsContext(
                "u1", LocalDate.now(), List.of(), List.of(), List.of(sub));
        final SanitizedSnapshot snap = svc.extract(ctx);
        assertEquals(1, snap.subscriptions().size());
        final String name = snap.subscriptions().get(0).displayName();
        assertFalse(name.toLowerCase().contains("anchor"),
                "Subscription merchant name leaked: " + name);
        assertFalse(name.toLowerCase().contains("counseling"),
                "Subscription merchant name leaked: " + name);
        assertTrue(name.startsWith("subscription_"),
                "Anonymised subscription should be subscription_<N>");
    }

    @Test
    void amounts_areRoundedToWholeDollars_noCents() {
        // Cents make amounts more identifiable; round to dollars.
        final InsightsContext ctx = ctxWith(
                tx("Netflix", "streaming", "-15.49", "2026-05-01"),
                tx("Netflix", "streaming", "-15.51", "2026-05-02"));
        final SanitizedSnapshot snap = svc.extract(ctx);
        for (final BigDecimal v : snap.spendingByCategory90d().values()) {
            assertEquals(0, v.scale(),
                    "Per-category amount must have zero decimal places");
        }
        for (final BigDecimal v : snap.spendingByMonth().values()) {
            assertEquals(0, v.scale(),
                    "Per-month amount must have zero decimal places");
        }
    }

    @Test
    void incomeTransactions_areExcludedFromAggregates() {
        // We never tell the LLM the user's income amount.
        final InsightsContext ctx = ctxWith(
                tx("Payroll", "income", "+5000", "2026-05-01"),
                tx("Netflix", "streaming", "-15.99", "2026-05-02"));
        final SanitizedSnapshot snap = svc.extract(ctx);
        final BigDecimal total = snap.spendingByCategory90d().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(total.intValue() < 100,
                "Income must not leak into spending aggregates");
    }

    @Test
    void onlyTransactionsInLast90Days_areIncluded() {
        // Older transactions are dropped. asOf = 2026-05-23.
        final InsightsContext ctx = new InsightsContext(
                "u1", LocalDate.parse("2026-05-23"),
                List.of(
                        tx("Netflix", "streaming", "-15.99", "2026-05-01"), // included
                        tx("Netflix", "streaming", "-15.99", "2025-12-01")), // dropped (>90d)
                List.of(), List.of());
        final SanitizedSnapshot snap = svc.extract(ctx);
        assertEquals(1, snap.spendingByMonth().size());
        assertTrue(snap.spendingByMonth().containsKey("2026-05"));
    }

    // ---- helpers ----

    private InsightsContext ctxWith(final TransactionTable... txs) {
        return new InsightsContext(
                "u1", LocalDate.parse("2026-05-23"), List.of(txs), List.of(), List.of());
    }

    private TransactionTable tx(
            final String merchant, final String category,
            final String amount, final String date) {
        final TransactionTable t = new TransactionTable();
        t.setMerchantName(merchant);
        t.setCategoryPrimary(category);
        t.setAmount(new BigDecimal(amount));
        t.setTransactionDate(date);
        return t;
    }
}
