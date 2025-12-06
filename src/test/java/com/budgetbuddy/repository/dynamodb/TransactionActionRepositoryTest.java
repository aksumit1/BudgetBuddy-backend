package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TransactionActionRepository
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionActionRepositoryTest {

    @Autowired
    private TransactionActionRepository transactionActionRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

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
    void testSave_WithValidAction_Succeeds() {
        // When
        transactionActionRepository.save(testAction);

        // Then
        Optional<TransactionActionTable> found = transactionActionRepository.findById(testAction.getActionId());
        assertTrue(found.isPresent(), "Action should be saved");
        assertEquals(testAction.getTitle(), found.get().getTitle());
    }

    @Test
    void testSave_WithNullAction_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            transactionActionRepository.save(null);
        }, "Should throw exception for null action");
    }

    @Test
    void testFindById_WithExistingAction_ReturnsAction() {
        // Given
        transactionActionRepository.save(testAction);

        // When
        Optional<TransactionActionTable> found = transactionActionRepository.findById(testAction.getActionId());

        // Then
        assertTrue(found.isPresent(), "Action should be found");
        assertEquals(testAction.getActionId(), found.get().getActionId());
    }

    @Test
    void testFindById_WithNonExistentAction_ReturnsEmpty() {
        // When
        Optional<TransactionActionTable> found = transactionActionRepository.findById(UUID.randomUUID().toString());

        // Then
        assertTrue(found.isEmpty(), "Should return empty for non-existent action");
    }

    @Test
    void testFindByTransactionId_ReturnsTransactionActions() {
        // Given
        transactionActionRepository.save(testAction);
        
        TransactionActionTable action2 = new TransactionActionTable();
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
        List<TransactionActionTable> actions = transactionActionRepository.findByTransactionId(testTransactionId);

        // Then
        assertNotNull(actions, "Actions should not be null");
        assertTrue(actions.size() >= 2, "Should return at least 2 actions");
    }

    @Test
    void testFindByUserId_ReturnsUserActions() {
        // Given
        transactionActionRepository.save(testAction);

        // When
        List<TransactionActionTable> actions = transactionActionRepository.findByUserId(testUserId);

        // Then
        assertNotNull(actions, "Actions should not be null");
        assertTrue(actions.size() >= 1, "Should return at least 1 action");
    }

    @Test
    void testDelete_WithValidId_RemovesAction() {
        // Given
        transactionActionRepository.save(testAction);
        String actionId = testAction.getActionId();

        // When
        transactionActionRepository.delete(actionId);

        // Then
        Optional<TransactionActionTable> found = transactionActionRepository.findById(actionId);
        assertTrue(found.isEmpty(), "Action should be deleted");
    }

    @Test
    void testDelete_WithNullId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            transactionActionRepository.delete(null);
        }, "Should throw exception for null ID");
    }
}

