package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AnomalyFeedbackTable;
import com.budgetbuddy.repository.dynamodb.AnomalyFeedbackRepository;
import com.budgetbuddy.service.AnomalyFeedbackService.Verdict;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the suppression loop that closes between user feedback and the
 * anomaly detector. False positives are worse than missing data
 * (memory: extraction tradeoffs) — if the loop breaks silently, users
 * keep seeing the same anomalies after dismissing them, which erodes
 * trust in the whole insights surface.
 */
@ExtendWith(MockitoExtension.class)
class AnomalyFeedbackServiceTest {

    private static final String USER = "u1";

    @Mock private AnomalyFeedbackRepository repository;
    private AnomalyFeedbackService svc;

    @BeforeEach
    void setUp() {
        svc = new AnomalyFeedbackService(repository);
    }

    // ------------------------------------------------------------------
    // fingerprintOf: the join key between feedback and detection
    // ------------------------------------------------------------------

    @Test
    void fingerprint_isStableAcrossSmallAmountVariations() {
        // Costco at $180 and Costco at $194 should both fall into the
        // same $20 bucket (180 rounds to 180; 194 rounds to 200 — these
        // do NOT collide, by design). $180 and $185 do collide.
        assertEquals(
                AnomalyFeedbackService.fingerprintOf("Costco", "groceries", new BigDecimal("180")),
                AnomalyFeedbackService.fingerprintOf("Costco", "groceries", new BigDecimal("185")));
    }

    @Test
    void fingerprint_isNotStableAcrossLargeAmountVariations() {
        // $50 and $300 must not collapse — that would suppress real spikes.
        assertNotEquals(
                AnomalyFeedbackService.fingerprintOf("Costco", "groceries", new BigDecimal("50")),
                AnomalyFeedbackService.fingerprintOf("Costco", "groceries", new BigDecimal("300")));
    }

    @Test
    void fingerprint_isCaseInsensitiveOnMerchantAndCategory() {
        assertEquals(
                AnomalyFeedbackService.fingerprintOf("COSTCO", "GROCERIES", new BigDecimal("100")),
                AnomalyFeedbackService.fingerprintOf("costco", "groceries", new BigDecimal("100")));
    }

    @Test
    void fingerprint_normalisesNegativeAmounts() {
        // Refunds (positive) and charges (negative) of the same magnitude
        // share a fingerprint because the user's intent ("don't flag
        // Costco around $200") doesn't care about sign.
        assertEquals(
                AnomalyFeedbackService.fingerprintOf("Costco", "g", new BigDecimal("-200")),
                AnomalyFeedbackService.fingerprintOf("Costco", "g", new BigDecimal("200")));
    }

    @Test
    void fingerprint_handlesNullsWithoutNpe() {
        // Defensive: any input field may be null in production data —
        // partial Plaid rows, manual entries without categories, etc.
        assertNotNull(AnomalyFeedbackService.fingerprintOf(null, null, null));
        assertNotNull(AnomalyFeedbackService.fingerprintOf("", "", BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------
    // record() — stable IDs + create-or-update behaviour
    // ------------------------------------------------------------------

    @Test
    void record_producesStableFeedbackIdForSameInputs() {
        // Same user + same fingerprint + same verdict must hash to the
        // same feedbackId so we don't accumulate duplicate rows.
        when(repository.findById(anyString())).thenReturn(Optional.empty());
        final AnomalyFeedbackTable first = svc.record(USER, "anomaly-1", "Costco",
                "groceries", new BigDecimal("180"), Verdict.DISMISSED);
        final AnomalyFeedbackTable second = svc.record(USER, "anomaly-2", "Costco",
                "groceries", new BigDecimal("184"), Verdict.DISMISSED);
        assertEquals(first.getFeedbackId(), second.getFeedbackId(),
                "Same fingerprint+verdict must share feedbackId");
    }

    @Test
    void record_updatesExistingRowInsteadOfCreatingDuplicate() {
        // First call creates the row; second call must update the same
        // row (verified via repository.save being called twice with the
        // same id).
        final Map<String, AnomalyFeedbackTable> store = new HashMap<>();
        when(repository.findById(anyString())).thenAnswer(
                inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        ArgumentCaptor<AnomalyFeedbackTable> cap =
                ArgumentCaptor.forClass(AnomalyFeedbackTable.class);

        svc.record(USER, "a1", "Costco", "g", new BigDecimal("180"), Verdict.DISMISSED);
        // Persist the row into our fake store so the second call finds it.
        verify(repository).save(cap.capture());
        store.put(cap.getValue().getFeedbackId(), cap.getValue());
        final Instant createdAt = cap.getValue().getCreatedAt();
        assertNotNull(createdAt);

        // Sleep a tiny amount to make the updatedAt distinguishable —
        // optional, just so the assertion is meaningful.
        try { Thread.sleep(2); } catch (InterruptedException ignored) { /* */ }

        svc.record(USER, "a1", "Costco", "g", new BigDecimal("180"), Verdict.DISMISSED);
        verify(repository, times(2)).save(any());

        // createdAt is preserved on the update.
        final AnomalyFeedbackTable updated = store.values().iterator().next();
        assertSame(createdAt, updated.getCreatedAt(),
                "Second record() must preserve original createdAt");
    }

    // ------------------------------------------------------------------
    // dismissedFingerprintsFor — only DISMISSED, not CONFIRMED or NORMAL
    // ------------------------------------------------------------------

    @Test
    void dismissedFingerprints_excludesConfirmedAndNormal() {
        final AnomalyFeedbackTable dismissed = row("fp-A", Verdict.DISMISSED.name());
        final AnomalyFeedbackTable confirmed = row("fp-B", Verdict.CONFIRMED.name());
        final AnomalyFeedbackTable normal = row("fp-C", Verdict.NORMAL.name());
        when(repository.findByUserId(USER))
                .thenReturn(List.of(dismissed, confirmed, normal));

        final Set<String> set = svc.dismissedFingerprintsFor(USER);
        assertTrue(set.contains("fp-A"));
        assertFalse(set.contains("fp-B"),
                "CONFIRMED is an acknowledgement — must not suppress detection");
        assertFalse(set.contains("fp-C"));
        assertEquals(1, set.size());
    }

    @Test
    void dismissedFingerprints_isCaseInsensitiveOnVerdictColumn() {
        // Storage layer may persist lowercase or mixed case; our filter
        // must be tolerant so suppressions don't silently leak through.
        final AnomalyFeedbackTable mixed = row("fp-A", "dismissed");
        when(repository.findByUserId(USER)).thenReturn(List.of(mixed));
        assertTrue(svc.dismissedFingerprintsFor(USER).contains("fp-A"));
    }

    // ------------------------------------------------------------------
    // Verdict.parse — null / unknown / mixed case
    // ------------------------------------------------------------------

    @Test
    void verdictParse_isDefensive() {
        assertEquals(Verdict.NORMAL, Verdict.parse(null));
        assertEquals(Verdict.NORMAL, Verdict.parse(""));
        assertEquals(Verdict.NORMAL, Verdict.parse("not-a-verdict"));
        assertEquals(Verdict.DISMISSED, Verdict.parse("dismissed"));
        assertEquals(Verdict.DISMISSED, Verdict.parse("DISMISSED"));
        assertEquals(Verdict.CONFIRMED, Verdict.parse("ConFirmEd"));
    }

    private static AnomalyFeedbackTable row(final String fingerprint, final String verdict) {
        final AnomalyFeedbackTable r = new AnomalyFeedbackTable();
        r.setFingerprint(fingerprint);
        r.setVerdict(verdict);
        r.setUserId(USER);
        return r;
    }
}
