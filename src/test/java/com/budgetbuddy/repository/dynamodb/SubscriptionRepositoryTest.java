package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.util.TableInitializer;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SubscriptionRepository
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "app.aws.dynamodb.table-prefix=TestBudgetBuddy"
})
class SubscriptionRepositoryTest {

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private String testUserId;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUserId = "test-user-" + System.currentTimeMillis();
    }

    private SubscriptionTable createSubscription(String subscriptionId, String userId, boolean active) {
        SubscriptionTable subscription = new SubscriptionTable();
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
    void testSave_WithValidSubscription_SavesSuccessfully() {
        // Given
        String subscriptionId = "sub-" + System.currentTimeMillis();
        SubscriptionTable subscription = createSubscription(subscriptionId, testUserId, true);

        // When
        assertDoesNotThrow(() -> subscriptionRepository.save(subscription));

        // Then
        Optional<SubscriptionTable> found = subscriptionRepository.findById(subscriptionId);
        assertTrue(found.isPresent());
        assertEquals(subscriptionId, found.get().getSubscriptionId());
        assertEquals(testUserId, found.get().getUserId());
    }

    @Test
    void testSave_WithNullSubscription_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> subscriptionRepository.save(null));
    }

    @Test
    void testFindById_WithValidId_ReturnsSubscription() {
        // Given
        String subscriptionId = "sub-" + System.currentTimeMillis();
        SubscriptionTable subscription = createSubscription(subscriptionId, testUserId, true);
        subscriptionRepository.save(subscription);

        // When
        Optional<SubscriptionTable> found = subscriptionRepository.findById(subscriptionId);

        // Then
        assertTrue(found.isPresent());
        assertEquals(subscriptionId, found.get().getSubscriptionId());
    }

    @Test
    void testFindById_WithInvalidId_ReturnsEmpty() {
        // When
        Optional<SubscriptionTable> found = subscriptionRepository.findById("non-existent-id");

        // Then
        assertFalse(found.isPresent());
    }

    @Test
    void testFindById_WithNullId_ReturnsEmpty() {
        // When
        Optional<SubscriptionTable> found = subscriptionRepository.findById(null);

        // Then
        assertFalse(found.isPresent());
    }

    @Test
    void testFindByUserId_WithValidUserId_ReturnsSubscriptions() {
        // Given
        String sub1Id = "sub-1-" + System.currentTimeMillis();
        String sub2Id = "sub-2-" + System.currentTimeMillis();
        SubscriptionTable sub1 = createSubscription(sub1Id, testUserId, true);
        SubscriptionTable sub2 = createSubscription(sub2Id, testUserId, false);
        subscriptionRepository.save(sub1);
        subscriptionRepository.save(sub2);

        // When
        List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId(testUserId);

        // Then
        assertTrue(subscriptions.size() >= 2);
        assertTrue(subscriptions.stream().anyMatch(s -> s.getSubscriptionId().equals(sub1Id)));
        assertTrue(subscriptions.stream().anyMatch(s -> s.getSubscriptionId().equals(sub2Id)));
    }

    @Test
    void testFindByUserId_WithInvalidUserId_ReturnsEmpty() {
        // When
        List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId("non-existent-user");

        // Then
        assertTrue(subscriptions.isEmpty());
    }

    @Test
    void testFindByUserId_WithNullUserId_ReturnsEmpty() {
        // When
        List<SubscriptionTable> subscriptions = subscriptionRepository.findByUserId(null);

        // Then
        assertTrue(subscriptions.isEmpty());
    }

    @Test
    void testFindActiveByUserId_WithActiveSubscriptions_ReturnsOnlyActive() {
        // Given
        String activeSubId = "active-sub-" + System.currentTimeMillis();
        String inactiveSubId = "inactive-sub-" + System.currentTimeMillis();
        SubscriptionTable activeSub = createSubscription(activeSubId, testUserId, true);
        SubscriptionTable inactiveSub = createSubscription(inactiveSubId, testUserId, false);
        subscriptionRepository.save(activeSub);
        subscriptionRepository.save(inactiveSub);

        // When
        List<SubscriptionTable> activeSubscriptions = subscriptionRepository.findActiveByUserId(testUserId);

        // Then
        assertTrue(activeSubscriptions.stream().anyMatch(s -> s.getSubscriptionId().equals(activeSubId)));
        assertFalse(activeSubscriptions.stream().anyMatch(s -> s.getSubscriptionId().equals(inactiveSubId)));
        activeSubscriptions.forEach(s -> assertTrue(s.getActive()));
    }

    @Test
    void testFindActiveByUserId_WithNoActiveSubscriptions_ReturnsEmpty() {
        // Given
        String inactiveSubId = "inactive-sub-" + System.currentTimeMillis();
        SubscriptionTable inactiveSub = createSubscription(inactiveSubId, testUserId, false);
        subscriptionRepository.save(inactiveSub);

        // When
        List<SubscriptionTable> activeSubscriptions = subscriptionRepository.findActiveByUserId(testUserId);

        // Then
        assertTrue(activeSubscriptions.isEmpty() || 
                activeSubscriptions.stream().noneMatch(s -> s.getSubscriptionId().equals(inactiveSubId)));
    }

    @Test
    void testDelete_WithValidId_DeletesSubscription() {
        // Given
        String subscriptionId = "sub-to-delete-" + System.currentTimeMillis();
        SubscriptionTable subscription = createSubscription(subscriptionId, testUserId, true);
        subscriptionRepository.save(subscription);

        // Verify it exists
        assertTrue(subscriptionRepository.findById(subscriptionId).isPresent());

        // When
        assertDoesNotThrow(() -> subscriptionRepository.delete(subscriptionId));

        // Then
        Optional<SubscriptionTable> found = subscriptionRepository.findById(subscriptionId);
        assertFalse(found.isPresent());
    }

    @Test
    void testDelete_WithNullId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> subscriptionRepository.delete(null));
    }

    @Test
    void testDelete_WithEmptyId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> subscriptionRepository.delete(""));
    }

    @Test
    void testGetTableName_ReturnsCorrectTableName() {
        // When
        String tableName = subscriptionRepository.getTableName();

        // Then
        assertNotNull(tableName);
        assertTrue(tableName.contains("Subscriptions"));
    }
}

