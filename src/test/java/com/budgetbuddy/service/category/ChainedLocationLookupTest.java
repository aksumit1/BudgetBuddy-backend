package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Locks in the contract of {@link ChainedLocationLookup}:
 *
 * <ul>
 *   <li>Tries each external source in order; first non-null wins.
 *   <li>Writes successful lookups to {@link MerchantEnrichmentStore}
 *       so repeats hit the cache, not the network.
 *   <li>Repeat lookups return the cached value WITHOUT touching the
 *       external sources again.
 *   <li>Tolerates flaky sources (one throws) and moves to the next.
 * </ul>
 */
class ChainedLocationLookupTest {

    private final MerchantEnrichmentStore store = new MerchantEnrichmentStore.InProcess();

    @Test
    @DisplayName("First non-null source wins")
    void firstNonNullWins() {
        ChainedLocationLookup.ExternalCategorySource a =
                Mockito.mock(ChainedLocationLookup.ExternalCategorySource.class);
        ChainedLocationLookup.ExternalCategorySource b =
                Mockito.mock(ChainedLocationLookup.ExternalCategorySource.class);
        when(a.lookup("Starbucks", "Bellevue", "WA", null))
                .thenReturn(new CategoryResult("dining", "dining", "TEST_A", 0.9));
        when(b.lookup("Starbucks", "Bellevue", "WA", null))
                .thenReturn(new CategoryResult("groceries", "groceries", "TEST_B", 0.9));

        ChainedLocationLookup chain = new ChainedLocationLookup(List.of(a, b), store);
        CategoryResult r = chain.lookup("Starbucks", "Bellevue", "WA", null);

        assertEquals("dining", r.getCategoryPrimary(), "first source's result should win");
        verify(a, times(1)).lookup("Starbucks", "Bellevue", "WA", null);
        Mockito.verifyNoInteractions(b);
    }

    @Test
    @DisplayName("Caches first-hit so subsequent calls bypass sources")
    void cachesFirstHit() {
        ChainedLocationLookup.ExternalCategorySource a =
                Mockito.mock(ChainedLocationLookup.ExternalCategorySource.class);
        when(a.lookup("Chipotle", "Bellevue", "WA", null))
                .thenReturn(new CategoryResult("dining", "dining", "TEST", 0.95));
        ChainedLocationLookup chain = new ChainedLocationLookup(List.of(a), store);

        chain.lookup("Chipotle", "Bellevue", "WA", null);
        chain.lookup("Chipotle", "Bellevue", "WA", null);
        chain.lookup("Chipotle", "Bellevue", "WA", null);

        verify(a, times(1)).lookup("Chipotle", "Bellevue", "WA", null);
    }

    @Test
    @DisplayName("Falls through to next source when one throws")
    void tolerantOfFlakySource() {
        ChainedLocationLookup.ExternalCategorySource flaky =
                Mockito.mock(ChainedLocationLookup.ExternalCategorySource.class);
        ChainedLocationLookup.ExternalCategorySource good =
                Mockito.mock(ChainedLocationLookup.ExternalCategorySource.class);
        when(flaky.lookup(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenThrow(new RuntimeException("Overpass rate-limited"));
        when(good.lookup("Costco", "Issaquah", "WA", null))
                .thenReturn(new CategoryResult("groceries", "groceries", "TEST", 0.92));

        ChainedLocationLookup chain = new ChainedLocationLookup(List.of(flaky, good), store);
        CategoryResult r = chain.lookup("Costco", "Issaquah", "WA", null);

        assertEquals("groceries", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("Returns null when every source returns null")
    void allNullReturnsNull() {
        ChainedLocationLookup.ExternalCategorySource a =
                Mockito.mock(ChainedLocationLookup.ExternalCategorySource.class);
        when(a.lookup(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(null);
        ChainedLocationLookup chain = new ChainedLocationLookup(List.of(a), store);

        assertNull(chain.lookup("UnknownCorp", null, null, null));
    }

    @Test
    @DisplayName("Empty sources list → null, doesn't crash")
    void emptyChainReturnsNull() {
        ChainedLocationLookup chain = new ChainedLocationLookup(List.of(), store);
        assertNull(chain.lookup("Starbucks", "Bellevue", "WA", null));
    }

    @Test
    @DisplayName("Cached value is returned identity-equal next call")
    void cacheReturnsSameInstance() {
        ChainedLocationLookup.ExternalCategorySource a =
                Mockito.mock(ChainedLocationLookup.ExternalCategorySource.class);
        CategoryResult original = new CategoryResult("travel", "travel", "TEST", 0.88);
        when(a.lookup("Hyatt", "Bellevue", "WA", null)).thenReturn(original);
        ChainedLocationLookup chain = new ChainedLocationLookup(List.of(a), store);

        CategoryResult first = chain.lookup("Hyatt", "Bellevue", "WA", null);
        CategoryResult second = chain.lookup("Hyatt", "Bellevue", "WA", null);

        assertSame(original, first);
        assertSame(original, second);
    }
}
