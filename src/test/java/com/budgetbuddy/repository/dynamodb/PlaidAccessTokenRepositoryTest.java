package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.PlaidAccessTokenTable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/**
 * Deep-validation coverage for {@link PlaidAccessTokenRepository}. The repository is the
 * GDPR + scheduled-sync seam: every persistence concern (encryption, KMS, replication) lives
 * on the table, but ROUTING — find by user, find by item, walk every row, delete every row
 * for one user — lives here. A bug at this layer either leaks user data past account
 * deletion or makes the scheduled Plaid sync skip rows it should sync.
 *
 * <p>These tests use Mockito over the AWS Enhanced Client to stay hermetic (no LocalStack),
 * matching the pattern set by {@code AccountRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidAccessTokenRepositoryTest {

    private static final String UNCHECKED = "unchecked";

    @Mock private DynamoDbEnhancedClient enhancedClient;
    @Mock private DynamoDbTable<PlaidAccessTokenTable> table;
    @Mock private DynamoDbIndex<PlaidAccessTokenTable> itemIdIndex;

    private PlaidAccessTokenRepository repo;

    @BeforeEach
    void setUp() {
        when(enhancedClient.table(
                        anyString(),
                        any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(table);
        when(table.index("PlaidItemIdIndex")).thenReturn(itemIdIndex);
        repo = new PlaidAccessTokenRepository(enhancedClient, "TestBudgetBuddy");
    }

    // ---------- save ----------

    @Test
    void save_setsCreatedAtOnFirstInsert_andAlwaysSetsUpdatedAt() {
        final PlaidAccessTokenTable row = newRow("user-1", "item-1");
        assertNotNull(row); // sanity
        // intentionally do NOT pre-set createdAt — repo must populate it
        repo.save(row);

        assertNotNull(row.getCreatedAt(), "createdAt must be initialised on first save");
        assertNotNull(row.getUpdatedAt(), "updatedAt must always be set");
        assertEquals(row.getCreatedAt(), row.getUpdatedAt(),
                "On first insert createdAt == updatedAt — they're both 'now'");

        final ArgumentCaptor<PlaidAccessTokenTable> captor =
                ArgumentCaptor.forClass(PlaidAccessTokenTable.class);
        verify(table).putItem(captor.capture());
        assertEquals(row, captor.getValue());
    }

    @Test
    void save_preservesExistingCreatedAt_andRefreshesUpdatedAt() throws InterruptedException {
        final PlaidAccessTokenTable row = newRow("user-1", "item-1");
        final java.time.Instant originalCreatedAt = java.time.Instant.now().minusSeconds(3600);
        row.setCreatedAt(originalCreatedAt);
        row.setUpdatedAt(originalCreatedAt);
        // Sleep enough that the new updatedAt is provably distinct.
        Thread.sleep(5);

        repo.save(row);

        assertEquals(originalCreatedAt, row.getCreatedAt(),
                "createdAt is immutable after first insert");
        assertTrue(
                row.getUpdatedAt().isAfter(originalCreatedAt),
                "updatedAt must be refreshed on every save");
    }

    @Test
    void save_isNoOpForNullRow() {
        // No NPE, no putItem invocation.
        repo.save(null);
        verify(table, never()).putItem(any(PlaidAccessTokenTable.class));
    }

    // ---------- findByUserAndItem ----------

    @Test
    void findByUserAndItem_returnsEmpty_forNullUserId() {
        assertFalse(repo.findByUserAndItem(null, "item-1").isPresent());
    }

    @Test
    void findByUserAndItem_returnsEmpty_forNullItemId() {
        assertFalse(repo.findByUserAndItem("user-1", null).isPresent());
    }

    @Test
    void findByUserAndItem_returnsEmpty_whenTableNotProvisioned() {
        when(table.getItem(any(Key.class)))
                .thenThrow(ResourceNotFoundException.builder().message("table missing").build());
        assertFalse(repo.findByUserAndItem("user-1", "item-1").isPresent());
    }

    @Test
    void findByUserAndItem_returnsRow_whenPresent() {
        final PlaidAccessTokenTable row = newRow("user-1", "item-1");
        when(table.getItem(any(Key.class))).thenReturn(row);

        final Optional<PlaidAccessTokenTable> result = repo.findByUserAndItem("user-1", "item-1");
        assertTrue(result.isPresent());
        assertEquals("user-1", result.get().getUserId());
        assertEquals("item-1", result.get().getPlaidItemId());
    }

    // ---------- findByItemId (GSI) ----------

    @Test
    void findByItemId_returnsEmpty_forNullItemId() {
        assertFalse(repo.findByItemId(null).isPresent());
    }

    @Test
    void findByItemId_returnsEmpty_forBlankItemId() {
        assertFalse(repo.findByItemId("").isPresent());
    }

    @Test
    void findByItemId_returnsEmpty_whenIndexNotProvisioned() {
        when(itemIdIndex.query(any(QueryConditional.class)))
                .thenThrow(ResourceNotFoundException.builder().message("index missing").build());
        assertFalse(repo.findByItemId("item-1").isPresent());
    }

    @Test
    void findByItemId_returnsFirstRowFromGsi() {
        final PlaidAccessTokenTable row = newRow("user-7", "item-99");
        final Page<PlaidAccessTokenTable> page = mockPage(List.of(row));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<PlaidAccessTokenTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(itemIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        final Optional<PlaidAccessTokenTable> result = repo.findByItemId("item-99");
        assertTrue(result.isPresent());
        assertEquals("user-7", result.get().getUserId());
    }

    // ---------- findByUserId ----------

    @Test
    void findByUserId_returnsEmptyList_forNullUserId() {
        assertTrue(repo.findByUserId(null).isEmpty());
    }

    @Test
    void findByUserId_returnsEmptyList_forBlankUserId() {
        assertTrue(repo.findByUserId("").isEmpty());
    }

    @Test
    void findByUserId_returnsAllRowsAcrossPages() {
        final PlaidAccessTokenTable a = newRow("user-1", "item-A");
        final PlaidAccessTokenTable b = newRow("user-1", "item-B");
        final PlaidAccessTokenTable c = newRow("user-1", "item-C");
        final Page<PlaidAccessTokenTable> p1 = mockPage(List.of(a, b));
        final Page<PlaidAccessTokenTable> p2 = mockPage(List.of(c));

        @SuppressWarnings(UNCHECKED)
        final PageIterable<PlaidAccessTokenTable> pages = mock(PageIterable.class);
        when(pages.iterator()).thenReturn(Arrays.asList(p1, p2).iterator());
        when(table.query(any(QueryConditional.class))).thenReturn(pages);

        final List<PlaidAccessTokenTable> result = repo.findByUserId("user-1");
        assertEquals(3, result.size());
        assertTrue(result.contains(a));
        assertTrue(result.contains(b));
        assertTrue(result.contains(c));
    }

    @Test
    void findByUserId_returnsEmpty_whenTableNotProvisioned() {
        when(table.query(any(QueryConditional.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not provisioned").build());
        assertTrue(repo.findByUserId("user-1").isEmpty());
    }

    // ---------- deleteByUserId (GDPR hook) ----------

    @Test
    void deleteByUserId_returnsZeroAndDoesNotCallDelete_forNullUserId() {
        assertEquals(0, repo.deleteByUserId(null));
        verify(table, never()).deleteItem(any(Key.class));
    }

    @Test
    void deleteByUserId_returnsZeroAndDoesNotCallDelete_forBlankUserId() {
        assertEquals(0, repo.deleteByUserId(""));
        verify(table, never()).deleteItem(any(Key.class));
    }

    @Test
    void deleteByUserId_deletesEveryRowReturnedFromQuery_andReportsCount() {
        final PlaidAccessTokenTable a = newRow("user-1", "item-A");
        final PlaidAccessTokenTable b = newRow("user-1", "item-B");
        final PlaidAccessTokenTable c = newRow("user-1", "item-C");
        final Page<PlaidAccessTokenTable> p1 = mockPage(List.of(a, b));
        final Page<PlaidAccessTokenTable> p2 = mockPage(List.of(c));

        @SuppressWarnings(UNCHECKED)
        final PageIterable<PlaidAccessTokenTable> pages = mock(PageIterable.class);
        when(pages.iterator()).thenReturn(Arrays.asList(p1, p2).iterator());
        when(table.query(any(QueryConditional.class))).thenReturn(pages);

        final int deleted = repo.deleteByUserId("user-1");
        assertEquals(3, deleted, "Count must match the number of rows actually deleted");
        verify(table, times(3)).deleteItem(any(Key.class));
    }

    @Test
    void deleteByUserId_skipsRowsMissingUserIdOrItemId_butCountsTheRest() {
        final PlaidAccessTokenTable valid = newRow("user-1", "item-A");
        final PlaidAccessTokenTable malformedNoUser = newRow(null, "item-B"); // shouldn't happen, but
        final PlaidAccessTokenTable malformedNoItem = newRow("user-1", null);
        final Page<PlaidAccessTokenTable> page =
                mockPage(List.of(valid, malformedNoUser, malformedNoItem));

        @SuppressWarnings(UNCHECKED)
        final PageIterable<PlaidAccessTokenTable> pages = mock(PageIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(table.query(any(QueryConditional.class))).thenReturn(pages);

        final int deleted = repo.deleteByUserId("user-1");
        assertEquals(1, deleted, "Only the well-formed row should be counted as deleted");
        verify(table, times(1)).deleteItem(any(Key.class));
    }

    @Test
    void deleteByUserId_returnsZero_whenTableNotProvisioned() {
        when(table.query(any(QueryConditional.class)))
                .thenThrow(ResourceNotFoundException.builder().message("not provisioned").build());
        assertEquals(0, repo.deleteByUserId("user-1"));
        verify(table, never()).deleteItem(any(Key.class));
    }

    // ---------- findAll (scheduled sync) ----------

    @Test
    void findAll_returnsAllRowsAcrossPages_lazyIteration() {
        final PlaidAccessTokenTable a = newRow("user-1", "item-A");
        final PlaidAccessTokenTable b = newRow("user-2", "item-B");
        final PlaidAccessTokenTable c = newRow("user-3", "item-C");
        final Page<PlaidAccessTokenTable> p1 = mockPage(List.of(a));
        final Page<PlaidAccessTokenTable> p2 = mockPage(List.of(b, c));

        @SuppressWarnings(UNCHECKED)
        final PageIterable<PlaidAccessTokenTable> pages = mock(PageIterable.class);
        when(pages.iterator()).thenReturn(Arrays.asList(p1, p2).iterator());
        when(table.scan()).thenReturn(pages);

        final List<PlaidAccessTokenTable> collected = new ArrayList<>();
        for (final PlaidAccessTokenTable r : repo.findAll()) {
            collected.add(r);
        }
        assertEquals(3, collected.size());
    }

    @Test
    void findAll_returnsEmpty_whenTableMissing() {
        when(table.scan())
                .thenThrow(ResourceNotFoundException.builder().message("missing").build());

        final Iterator<PlaidAccessTokenTable> it = repo.findAll().iterator();
        assertFalse(it.hasNext(),
                "Scheduled sync must degrade to a no-op rather than crash before CFN ships");
    }

    // ---------- helpers ----------

    private static PlaidAccessTokenTable newRow(final String userId, final String itemId) {
        final PlaidAccessTokenTable row = new PlaidAccessTokenTable();
        row.setUserId(userId);
        row.setPlaidItemId(itemId);
        row.setAccessToken("encrypted-access-token-for-" + itemId);
        row.setInstitutionId("ins_1");
        row.setInstitutionName("Test Bank");
        return row;
    }

    @SuppressWarnings(UNCHECKED)
    private static Page<PlaidAccessTokenTable> mockPage(final List<PlaidAccessTokenTable> items) {
        final Page<PlaidAccessTokenTable> page = mock(Page.class);
        when(page.items()).thenReturn(items);
        return page;
    }
}
