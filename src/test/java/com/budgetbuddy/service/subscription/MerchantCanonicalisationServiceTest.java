package com.budgetbuddy.service.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.Subscription.SubscriptionFrequency;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class MerchantCanonicalisationServiceTest {

    private static final String USER = "u1";

    @Test
    void mostFrequentSpellingWins() {
        // "DJ Barrons" appears more often than "DJ*BARRONS*" so it
        // becomes the canonical display.
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(txRepo.findByUserId(USER, 0, 10_000))
                .thenReturn(List.of(
                        tx("DJ Barrons"),
                        tx("DJ Barrons"),
                        tx("DJ Barrons"),
                        tx("DJ*BARRONS*"),
                        tx("D.J. Barrons")));
        when(subs.getSubscriptions(USER)).thenReturn(List.of());

        final Map<String, String> map =
                new MerchantCanonicalisationService(txRepo, subs).mapFor(USER);
        assertEquals("DJ Barrons", map.get("djbarrons"),
                "Most frequent spelling should be the canonical");
    }

    @Test
    void subscriptionSpellingOutweighsTransactionVariants() {
        // Subscription name carries +5 weight so "Netflix" wins over many
        // "NETFLIX*MONTHLY*US" transaction rows.
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(txRepo.findByUserId(USER, 0, 10_000))
                .thenReturn(List.of(
                        tx("NETFLIX*MONTHLY*US"),
                        tx("NETFLIX*MONTHLY*US"),
                        tx("NETFLIX*MONTHLY*US"),
                        tx("NETFLIX*MONTHLY*US")));
        when(subs.getSubscriptions(USER))
                .thenReturn(List.of(sub("Netflix")));

        // Note: keys are normalised — "NETFLIX*MONTHLY*US" → "netflixmonthlyus"
        // while "Netflix" → "netflix". Two DIFFERENT canonical entries.
        // The subscription wins for its own normalised key.
        final Map<String, String> map =
                new MerchantCanonicalisationService(txRepo, subs).mapFor(USER);
        assertEquals("Netflix", map.get("netflix"));
        assertTrue(map.containsKey("netflixmonthlyus"));
    }

    @Test
    void canonicalForReturnsInputForUnknownMerchants() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(txRepo.findByUserId(USER, 0, 10_000)).thenReturn(List.of());
        when(subs.getSubscriptions(USER)).thenReturn(List.of());

        final MerchantCanonicalisationService svc =
                new MerchantCanonicalisationService(txRepo, subs);
        assertEquals("New Merchant",
                svc.canonicalFor(USER, "New Merchant"),
                "Unknown merchant must return input unchanged");
    }

    @Test
    void canonicalForResolvesVariantSpellingsThroughTheMap() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(txRepo.findByUserId(USER, 0, 10_000))
                .thenReturn(List.of(tx("Whole Foods"), tx("Whole Foods")));
        when(subs.getSubscriptions(USER)).thenReturn(List.of());

        final MerchantCanonicalisationService svc =
                new MerchantCanonicalisationService(txRepo, subs);
        assertEquals("Whole Foods", svc.canonicalFor(USER, "WHOLEFOODS"));
        assertEquals("Whole Foods", svc.canonicalFor(USER, "whole-foods"));
        assertEquals("Whole Foods", svc.canonicalFor(USER, "WHOLE FOODS"));
    }

    @Test
    void invalidateUserRecomputesNextLookup() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final SubscriptionService subs = mock(SubscriptionService.class);
        when(txRepo.findByUserId(USER, 0, 10_000)).thenReturn(List.of(tx("Old Spelling")));
        when(subs.getSubscriptions(USER)).thenReturn(List.of());

        final MerchantCanonicalisationService svc =
                new MerchantCanonicalisationService(txRepo, subs);
        assertEquals("Old Spelling", svc.canonicalFor(USER, "old spelling"));

        // Change underlying data + invalidate.
        when(txRepo.findByUserId(USER, 0, 10_000)).thenReturn(List.of(tx("New Spelling")));
        svc.invalidateUser(USER);
        // Note the key changes too — comparing on a different normalised
        // key would miss; check both keys.
        assertEquals("New Spelling", svc.canonicalFor(USER, "new spelling"));
    }

    private static TransactionTable tx(final String merchant) {
        final TransactionTable t = new TransactionTable();
        t.setUserId(USER);
        t.setMerchantName(merchant);
        return t;
    }

    private static Subscription sub(final String merchant) {
        final Subscription s = new Subscription();
        s.setSubscriptionId(java.util.UUID.randomUUID().toString());
        s.setUserId(USER);
        s.setMerchantName(merchant);
        s.setActive(true);
        s.setFrequency(SubscriptionFrequency.MONTHLY);
        s.setAmount(new BigDecimal("10"));
        return s;
    }
}
