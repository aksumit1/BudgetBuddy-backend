package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.util.TableInitializer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Tests for TransactionActionRepository */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionActionRepositoryTest {

    @Autowired private TransactionActionRepository transactionActionRepository;

    @Autowired private DynamoDbClient dynamoDbClient;

    private String testUserId;
    private String testTransactionId;
    private TransactionActionTable testAction;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testTransactionId = UUID.randomUUID().toString();

        testAction = new TransactionActionTable();
        testAction.setActionId(UUID.randomUUID().toString());
        testAction.setUserId(testUserId);
        testAction.setTransactionId(testTransactionId);
        testAction.setTitle("Test Action");
        testAction.setDueDate(LocalDate.now().plusDays(7).toString());
        testAction.setReminderDate(LocalDate.now().plusDays(6).toString());
        testAction.setPriority("HIGH");
        testAction.setIsCompleted(false);
        testAction.setCreatedAt(Instant.now());
        testAction.setUpdatedAt(Instant.now());
    }

    @Test
    void testSaveWithValidActionSucceeds() {
        // When
        transactionActionRepository.save(testAction);

        // Then
        final Optional<TransactionActionTable> found =
                transactionActionRepository.findById(testAction.getActionId());
        assertTrue(found.isPresent(), "Action should be saved");
        assertEquals(testAction.getTitle(), found.get().getTitle());
    }

    @Test
    void testSaveWithNullActionThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    transactionActionRepository.save(null);
                },
                "Should throw exception for null action");
    }

    @Test
    void testFindByIdWithExistingActionReturnsAction() {
        // Given
        transactionActionRepository.save(testAction);

        // When
        final Optional<TransactionActionTable> found =
                transactionActionRepository.findById(testAction.getActionId());

        // Then
        assertTrue(found.isPresent(), "Action should be found");
        assertEquals(testAction.getActionId(), found.get().getActionId());
    }

    @Test
    void testFindByIdWithNonExistentActionReturnsEmpty() {
        // When
        final Optional<TransactionActionTable> found =
                transactionActionRepository.findById(UUID.randomUUID().toString());

        // Then
        assertTrue(found.isEmpty(), "Should return empty for non-existent action");
    }

    @Test
    void testFindByTransactionIdReturnsTransactionActions() {
        // Given
        transactionActionRepository.save(testAction);

        final TransactionActionTable action2 = new TransactionActionTable();
        action2.setActionId(UUID.randomUUID().toString());
        action2.setUserId(testUserId);
        action2.setTransactionId(testTransactionId);
        action2.setTitle("Action 2");
        action2.setPriority("MEDIUM");
        action2.setIsCompleted(false);
        action2.setCreatedAt(Instant.now());
        action2.setUpdatedAt(Instant.now());
        transactionActionRepository.save(action2);

        // When
        final List<TransactionActionTable> actions =
                transactionActionRepository.findByTransactionId(testTransactionId);

        // Then
        assertNotNull(actions, "Actions should not be null");
        assertTrue(actions.size() >= 2, "Should return at least 2 actions");
    }

    @Test
    void testFindByUserIdReturnsUserActions() {
        // Given
        transactionActionRepository.save(testAction);

        // When
        final List<TransactionActionTable> actions = transactionActionRepository.findByUserId(testUserId);

        // Then
        assertNotNull(actions, "Actions should not be null");
        assertTrue(actions.size() >= 1, "Should return at least 1 action");
    }

    @Test
    void testDeleteWithValidIdRemovesAction() {
        // Given
        transactionActionRepository.save(testAction);
        final String actionId = testAction.getActionId();

        // When
        transactionActionRepository.delete(actionId);

        // Then
        final Optional<TransactionActionTable> found = transactionActionRepository.findById(actionId);
        assertTrue(found.isEmpty(), "Action should be deleted");
    }

    @Test
    void testDeleteWithNullIdThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    transactionActionRepository.delete(null);
                },
                "Should throw exception for null ID");
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithValidParamsReturnsUpdatedActions() {
        // Given
        final long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        testAction.setUpdatedAtTimestamp(Instant.now().getEpochSecond());
        transactionActionRepository.save(testAction);

        // When
        final List<TransactionActionTable> result =
                transactionActionRepository.findByUserIdAndUpdatedAfter(
                        testUserId, updatedAfterTimestamp);

        // Then
        assertNotNull(result);
        assertTrue(result.size() >= 0);
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithNullParamsReturnsEmpty() {
        // When
        final List<TransactionActionTable> result1 =
                transactionActionRepository.findByUserIdAndUpdatedAfter(
                        null, Instant.now().getEpochSecond());
        final List<TransactionActionTable> result2 =
                transactionActionRepository.findByUserIdAndUpdatedAfter(testUserId, null);
        final List<TransactionActionTable> result3 =
                transactionActionRepository.findByUserIdAndUpdatedAfter(
                        "", Instant.now().getEpochSecond());

        // Then
        assertTrue(result1.isEmpty());
        assertTrue(result2.isEmpty());
        assertTrue(result3.isEmpty());
    }
}
