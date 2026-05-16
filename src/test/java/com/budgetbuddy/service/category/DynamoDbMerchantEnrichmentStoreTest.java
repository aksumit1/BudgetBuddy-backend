package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.MerchantEnrichmentCacheTable;
import com.budgetbuddy.repository.dynamodb.MerchantEnrichmentCacheRepository;
import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Verifies the two-level cache contract:
 * <ul>
 *   <li>Reads check in-memory first, fall through to DynamoDB.
 *   <li>Writes go to BOTH in-memory and DynamoDB.
 *   <li>A repository hiccup never breaks categorisation.
 *   <li>Source provenance survives the round-trip.
 *   <li>TTL is set to ~365 days from now.
 * </ul>
 */
class DynamoDbMerchantEnrichmentStoreTest {

    private MerchantEnrichmentCacheRepository repo;
    private DynamoDbMerchantEnrichmentStore store;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(MerchantEnrichmentCacheRepository.class);
        store = new DynamoDbMerchantEnrichmentStore(repo);
    }

    @Test
    @DisplayName("Empty store returns Optional.empty without touching DynamoDB twice")
    void emptyReturnsEmpty() {
        when(repo.get(any())).thenReturn(Optional.empty());
        Optional<CategoryResult> r = store.get("Starbucks", "Bellevue", "WA", "US");
        assertTrue(r.isEmpty(), "no data → empty");
    }

    @Test
    @DisplayName("Put writes to repo and round-trips through get")
    void putThenGetReturnsValue() {
        store.put("Starbucks", "Bellevue", "WA", "US",
                new CategoryResult("dining", "dining", "OSM_TAG:amenity=cafe", 0.88));

        // verify repository write
        ArgumentCaptor<MerchantEnrichmentCacheTable> captor =
                ArgumentCaptor.forClass(MerchantEnrichmentCacheTable.class);
        verify(repo, times(1)).put(captor.capture());
        MerchantEnrichmentCacheTable row = captor.getValue();
        assertEquals("dining", row.getCategoryPrimary());
        assertEquals("OSM_TAG:amenity=cafe", row.getSource());
        assertEquals(0.88, row.getConfidence(), 0.001);

        // round-trip via in-memory hot path — no second repo.get() call
        Optional<CategoryResult> r = store.get("Starbucks", "Bellevue", "WA", "US");
        assertTrue(r.isPresent());
        assertEquals("dining", r.get().getCategoryPrimary());
        assertEquals("OSM_TAG:amenity=cafe", r.get().getSource());
        verify(repo, never()).get(any());
    }

    @Test
    @DisplayName("DynamoDB miss + memory miss → empty (no crash)")
    void allMissReturnsEmpty() {
        when(repo.get(any())).thenReturn(Optional.empty());
        Optional<CategoryResult> r = store.get("Unknown Corp", "Atlanta", "GA", "US");
        assertTrue(r.isEmpty());
    }

    @Test
    @DisplayName("Cold start: hit comes from DynamoDB, gets cached in memory")
    void coldStartReadsFromDynamo() {
        MerchantEnrichmentCacheTable row = new MerchantEnrichmentCacheTable();
        row.setCacheKey(MerchantEnrichmentStore.key("Chipotle", "Bellevue", "WA", "US"));
        row.setCategoryPrimary("dining");
        row.setCategoryDetailed("dining");
        row.setSource("OSM_TAG:amenity=fast_food");
        row.setConfidence(0.88);
        when(repo.get(row.getCacheKey())).thenReturn(Optional.of(row));

        Optional<CategoryResult> first = store.get("Chipotle", "Bellevue", "WA", "US");
        assertTrue(first.isPresent());
        assertEquals("dining", first.get().getCategoryPrimary());

        // Second call should NOT re-query DynamoDB (now in hot map)
        store.get("Chipotle", "Bellevue", "WA", "US");
        verify(repo, times(1)).get(row.getCacheKey());
    }

    @Test
    @DisplayName("Repository exception on read is swallowed, returns empty")
    void readExceptionIsSafe() {
        when(repo.get(any())).thenThrow(new RuntimeException("DynamoDB down"));
        Optional<CategoryResult> r = store.get("AnyMerchant", "Atlanta", "GA", "US");
        assertTrue(r.isEmpty(), "exception → fall through to empty, not propagate");
    }

    @Test
    @DisplayName("Repository exception on write is swallowed but in-memory still holds the value")
    void writeExceptionDoesntStopHotCache() {
        Mockito.doThrow(new RuntimeException("DynamoDB down")).when(repo).put(any());
        store.put("Starbucks", "Bellevue", "WA", "US",
                new CategoryResult("dining", "dining", "TEST", 0.9));

        // Even though DynamoDB write failed, the in-memory hot path
        // should serve subsequent reads — categorisation keeps working.
        Optional<CategoryResult> r = store.get("Starbucks", "Bellevue", "WA", "US");
        assertTrue(r.isPresent());
    }

    @Test
    @DisplayName("Null inputs are safe no-ops")
    void nullInputsAreSafe() {
        assertFalse(store.get(null, null, null, null).isPresent());
        store.put(null, null, null, null,
                new CategoryResult("x", "x", "X", 0.5));
        verify(repo, never()).put(any());
    }

    @Test
    @DisplayName("TTL is set ~365 days in the future")
    void ttlIsOneYear() {
        store.put("Test", "City", "WA", "US",
                new CategoryResult("dining", "dining", "TEST", 1.0));
        ArgumentCaptor<MerchantEnrichmentCacheTable> captor =
                ArgumentCaptor.forClass(MerchantEnrichmentCacheTable.class);
        verify(repo).put(captor.capture());
        long ttl = captor.getValue().getTtl();
        long now = java.time.Instant.now().getEpochSecond();
        long days = (ttl - now) / 86_400;
        assertTrue(days >= 364 && days <= 366,
                "TTL should be ~365 days, got " + days + " days");
    }
}
