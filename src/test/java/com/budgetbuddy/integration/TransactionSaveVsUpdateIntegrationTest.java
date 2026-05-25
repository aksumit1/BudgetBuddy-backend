package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
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

/**
 * Pins the {@code saveTransaction} vs {@code updateTransaction} semantic
 * difference so the production bug that motivated this test can't come back.
 *
 * <h3>The bug</h3>
 *
 * <p>{@link TransactionService#saveTransaction} runs through
 * {@code saveIfPlaidTransactionNotExists}, which issues a conditional
 * DynamoDB put with {@code attribute_not_exists(transactionId)}. That's
 * correct for first-time Plaid ingest (prevents concurrent writers from
 * clobbering each other), but it silently REJECTS a second write for an
 * already-persisted transactionId — the conditional check fails and the
 * catch clause returns {@code false} without surfacing an error.
 *
 * <p>The PDF-import path calls {@code createTransaction} (which persists the
 * row) and then needs a follow-up save to attach FX context / wallet / geo
 * fields. Before {@link TransactionService#updateTransaction} existed, the
 * follow-up call was {@code saveTransaction} — so every FX/wallet/geo write
 * silently dropped on the floor. Domestic transactions worked because the
 * fields were null anyway; international and Apple-Pay rows lost their
 * structured fields entirely.
 *
 * <h3>What this test pins</h3>
 *
 * <ul>
 *   <li>{@code updateTransaction} after {@code createTransaction} actually
 *       overwrites the row — the new field is visible in DDB.</li>
 *   <li>{@code saveTransaction} on an existing transactionId silently fails
 *       to update (proves the conditional-write contract).</li>
 * </ul>
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionSaveVsUpdateIntegrationTest {

    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DynamoDbClient dynamoDbClient;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("save-vs-update-" + UUID.randomUUID() + "@example.com");
        testUser.setPreferredCurrency("USD");
        userRepository.save(testUser);

        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test");
        accountRepository.save(testAccount);
    }

    @Test
    void updateTransaction_overwritesFollowupFieldsAfterCreate() {
        // Step 1: create — this persists the base row.
        final TransactionTable created = transactionService.createTransaction(
                testUser, testAccount.getAccountId(),
                new BigDecimal("100.00"), LocalDate.of(2026, 5, 1),
                "STARBUCKS BELLEVUE WA", "FOOD", "COFFEE", null, null,
                UUID.randomUUID().toString(),
                null, null, null, "EXPENSE", "USD",
                "PDF", UUID.randomUUID().toString(), "test.pdf",
                null, "STARBUCKS", "Bellevue, WA", null, null, null, null);
        // Sanity: city not yet set.
        final TransactionTable beforeUpdate = transactionRepository
                .findById(created.getTransactionId()).orElseThrow();
        assertEquals(null, beforeUpdate.getCity(),
                "city must be null before follow-up update");

        // Step 2: attach a follow-up field via updateTransaction.
        created.setCity("Bellevue");
        created.setCountry("US");
        transactionService.updateTransaction(created);

        // Step 3: re-read from DDB. Field MUST be visible.
        final TransactionTable afterUpdate = transactionRepository
                .findById(created.getTransactionId()).orElseThrow();
        assertEquals("Bellevue", afterUpdate.getCity(),
                "updateTransaction must overwrite — this catches the silent-drop bug");
        assertEquals("US", afterUpdate.getCountry());
    }

    @Test
    void saveTransaction_silentlyDropsWriteWhenIdExists() {
        // Document and pin the existing conditional-write semantic so future
        // contributors don't accidentally route the PDF follow-up save back
        // through this method.
        final TransactionTable created = transactionService.createTransaction(
                testUser, testAccount.getAccountId(),
                new BigDecimal("50.00"), LocalDate.of(2026, 5, 2),
                "MERCHANT FOR SAVE TEST", "OTHER", "OTHER", null, null,
                UUID.randomUUID().toString(),
                null, null, null, "EXPENSE", "USD",
                "PDF", UUID.randomUUID().toString(), "test.pdf",
                null, "MERCHANT", "Bellevue, WA", null, null, null, null);

        // Attempt to overwrite via saveTransaction. This SHOULD silently
        // be rejected by the conditional-write guard, so the geo field
        // must NOT be visible after re-read. If this assertion ever fails
        // it means saveTransaction stopped being conditional and the bug
        // class is no longer caught — review the change and confirm intent.
        created.setCity("Bellevue");
        try {
            transactionService.saveTransaction(created);
        } catch (final Exception ignored) {
            // Either silently no-ops OR throws — both behaviors document the
            // contract that this method isn't for in-place updates.
        }

        final TransactionTable afterSave = transactionRepository
                .findById(created.getTransactionId()).orElseThrow();
        // saveTransaction's conditional write rejected; city is still null.
        assertEquals(null, afterSave.getCity(),
                "saveTransaction must NOT silently update an existing row. "
                        + "If this fails, the conditional-write contract changed — "
                        + "review whether the PDF follow-up save path needs to "
                        + "switch back from updateTransaction.");
    }

    @Test
    void updateTransaction_failsLoudlyOnNullId() {
        final TransactionTable t = new TransactionTable();
        t.setUserId(testUser.getUserId());
        try {
            transactionService.updateTransaction(t);
            org.junit.jupiter.api.Assertions.fail(
                    "updateTransaction with no transactionId must throw");
        } catch (final RuntimeException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
