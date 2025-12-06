package com.budgetbuddy.service;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TransactionSyncHelper
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionSyncHelperTest {

    @Autowired
    private TransactionSyncHelper transactionSyncHelper;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @Test
    void testTransactionSyncHelper_IsCreated() {
        // Then
        assertNotNull(transactionSyncHelper, "TransactionSyncHelper should be created");
    }

    @Test
    void testSyncSingleTransaction_WithNullTransaction_ReturnsError() {
        // Given
        if (transactionSyncHelper == null) {
            return;
        }

        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(
                null,
                "plaid-id-123"
        );

        // Then
        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.getErrorCount(), "Should have 1 error");
    }

    @Test
    void testSyncSingleTransaction_WithNullPlaidId_ReturnsError() {
        // Given
        if (transactionSyncHelper == null) {
            return;
        }
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setAmount(new BigDecimal("100.00"));
        transaction.setCreatedAt(Instant.now());

        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(
                transaction,
                null
        );

        // Then
        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.getErrorCount(), "Should have 1 error");
    }
}

