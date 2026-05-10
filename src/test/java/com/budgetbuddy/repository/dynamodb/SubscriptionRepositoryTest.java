package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Integration tests for SubscriptionRepository */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.aws.dynamodb.table-prefix=TestBudgetBuddy"})
class SubscriptionRepositoryTest {

    @Autowired private SubscriptionRepository subscriptionRepository;

    @Autowired private DynamoDbClient dynamoDbClient;

    private String testUserId;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUserId = "test-user-" + System.currentTimeMillis();
    }

    private SubscriptionTable createSubscription(
            final String subscriptionId, final String userId, final boolean active) {
        final SubscriptionTable subscription = new SubscriptionTable();
        subscription.setSubscriptionId(subscriptionId);
        subscription.setUserId(userId);
        subscription.setMerchantName("Test Subscription");
        subscription.setDescription("Test Subscription Description");
        subscription.setAmount(new BigDecimal("9.99"));
        subscription.setFrequency("MONTHLY");
        subscription.setStartDate(LocalDate.now().toString());
        subscription.setActive(active);
        return subscription;
    }

    @Test
    void testSaveWithValidSubscriptionSavesSuccessfully() {
        // Given
        final String subscriptionId = "sub-" + System.currentTimeMillis();
        final SubscriptionTable subscription = createSubscription(subscriptionId, testUserId, true);

        // When
        assertDoesNotThrow(() -> subscriptionRepository.save(subscription));

        // Then
        final Optional<SubscriptionTable> found = subscriptionRepository.findById(subscriptionId);
        assertTrue(found.isPresent());
        assertEquals(subscriptionId, found.get().getSubscriptionId());
        assertEquals(testUserId, found.get().getUserId());
    }

    @Test
    void testSaveWithNullSubscriptionThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> subscriptionRepository.save(null));
    }

    @Test
    void testFindByIdWithValidIdReturnsSubscription() {
        // Given
        final String subscriptionId = "sub-" + System.currentTimeMillis();
        final SubscriptionTable subscription = createSubscription(subscriptionId, testUserId, true);
        subscriptionRepository.save(subscription);

        // When
        final Optional<SubscriptionTable> found = subscriptionRepository.findById(subscriptionId);

        // Then
        assertTrue(found.isPresent());
        assertEquals(subscriptionId, found.get().getSubscriptionId());
    }

    @Test
    void testFindByIdWithInvalidIdReturnsEmpty() {
        // When
        final Optional<SubscriptionTable> found =
                subscriptionRepository.findById("non-existent-id");

        // Then
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByIdWithNullIdReturnsEmpty() {
        // When
        final Optional<SubscriptionTable> found = subscriptionRepository.findById(null);

        // Then
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByUserIdWithValidUserIdReturnsSubscriptions() {
        // Given
        final String sub1Id = "sub-1-" + System.currentTimeMillis();
        final String sub2Id = "sub-2-" + System.currentTimeMillis();
        final SubscriptionTable sub1 = createSubscription(sub1Id, testUserId, true);
        final SubscriptionTable sub2 = createSubscription(sub2Id, testUserId, false);
        subscriptionRepository.save(sub1);
        subscriptionRepository.save(sub2);

        // When
        final List<SubscriptionTable> subscriptions =
                subscriptionRepository.findByUserId(testUserId);

        // Then
        assertTrue(subscriptions.size() >= 2);
        assertTrue(subscriptions.stream().anyMatch(s -> s.getSubscriptionId().equals(sub1Id)));
        assertTrue(subscriptions.stream().anyMatch(s -> s.getSubscriptionId().equals(sub2Id)));
    }

    @Test
    void testFindByUserIdWithInvalidUserIdReturnsEmpty() {
        // When
        final List<SubscriptionTable> subscriptions =
                subscriptionRepository.findByUserId("non-existent-user");

        // Then
        assertTrue(subscriptions.isEmpty());
    }

    @Test
    void testFindByUserIdWithNullUserIdReturnsEmpty() {
        // When
        final List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId(null);

        // Then
        assertTrue(subscriptions.isEmpty());
    }

    @Test
    void testFindActiveByUserIdWithActiveSubscriptionsReturnsOnlyActive() {
        // Given
        final String activeSubId = "active-sub-" + System.currentTimeMillis();
        final String inactiveSubId = "inactive-sub-" + System.currentTimeMillis();
        final SubscriptionTable activeSub = createSubscription(activeSubId, testUserId, true);
        final SubscriptionTable inactiveSub = createSubscription(inactiveSubId, testUserId, false);
        subscriptionRepository.save(activeSub);
        subscriptionRepository.save(inactiveSub);

        // When
        final List<SubscriptionTable> activeSubscriptions =
                subscriptionRepository.findActiveByUserId(testUserId);

        // Then
        assertTrue(
                activeSubscriptions.stream()
                        .anyMatch(s -> s.getSubscriptionId().equals(activeSubId)));
        assertFalse(
                activeSubscriptions.stream()
                        .anyMatch(s -> s.getSubscriptionId().equals(inactiveSubId)));
        activeSubscriptions.forEach(s -> assertTrue(s.getActive()));
    }

    @Test
    void testFindActiveByUserIdWithNoActiveSubscriptionsReturnsEmpty() {
        // Given
        final String inactiveSubId = "inactive-sub-" + System.currentTimeMillis();
        final SubscriptionTable inactiveSub = createSubscription(inactiveSubId, testUserId, false);
        subscriptionRepository.save(inactiveSub);

        // When
        final List<SubscriptionTable> activeSubscriptions =
                subscriptionRepository.findActiveByUserId(testUserId);

        // Then
        assertTrue(
                activeSubscriptions.isEmpty()
                        || activeSubscriptions.stream()
                                .noneMatch(s -> s.getSubscriptionId().equals(inactiveSubId)));
    }

    @Test
    void testDeleteWithValidIdDeletesSubscription() {
        // Given
        final String subscriptionId = "sub-to-delete-" + System.currentTimeMillis();
        final SubscriptionTable subscription = createSubscription(subscriptionId, testUserId, true);
        subscriptionRepository.save(subscription);

        // Verify it exists
        assertTrue(subscriptionRepository.findById(subscriptionId).isPresent());

        // When
        assertDoesNotThrow(() -> subscriptionRepository.delete(subscriptionId));

        // Then
        final Optional<SubscriptionTable> found = subscriptionRepository.findById(subscriptionId);
        assertFalse(found.isPresent());
    }

    @Test
    void testDeleteWithNullIdThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> subscriptionRepository.delete(null));
    }

    @Test
    void testDeleteWithEmptyIdThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> subscriptionRepository.delete(""));
    }

    @Test
    void testGetTableNameReturnsCorrectTableName() {
        // When
        final String tableName = subscriptionRepository.getTableName();

        // Then
        assertNotNull(tableName);
        assertTrue(tableName.contains("Subscriptions"));
    }
}
