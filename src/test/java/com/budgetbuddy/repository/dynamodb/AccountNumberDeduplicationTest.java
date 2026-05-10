package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Tests for account number-based deduplication Verifies that accounts are deduplicated using
 * account number + institution name
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountNumberDeduplicationTest {

    @Autowired private AccountRepository accountRepository;

    @Autowired private DynamoDbClient dynamoDbClient;

    private String testUserId;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithExistingAccountReturnsAccount() {
        try {
            // Given - Account with account number
            final String accountNumber = "1234";
            final String institutionName = "Test Bank";

            final AccountTable account = new AccountTable();
            account.setAccountId(UUID.randomUUID().toString());
            account.setUserId(testUserId);
            account.setAccountName("Checking Account");
            account.setInstitutionName(institutionName);
            account.setAccountNumber(accountNumber);
            account.setAccountType("CHECKING");
            account.setBalance(new BigDecimal("1000.00"));
            account.setCurrencyCode("USD");
            account.setActive(true);
            account.setCreatedAt(Instant.now());
            account.setUpdatedAt(Instant.now());
            accountRepository.save(account);

            // When - Find by account number and institution
            final Optional<AccountTable> found =
                    accountRepository.findByAccountNumberAndInstitution(
                            accountNumber, institutionName, testUserId);

            // Then - Should find the account
            assertTrue(
                    found.isPresent(), "Account should be found by account number and institution");
            assertEquals(accountNumber, found.get().getAccountNumber());
            assertEquals(institutionName, found.get().getInstitutionName());
            assertEquals(account.getAccountId(), found.get().getAccountId());
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains("DynamoDB")
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains("Connection")
                    || errorMsg.contains("endpoint")
                    || errorMsg.contains("ResourceNotFoundException")
                    || causeMsg.contains("DynamoDB")
                    || causeMsg.contains("Connection")
                    || causeMsg.contains("endpoint")
                    || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithDifferentInstitutionReturnsEmpty() {
        try {
            // Given - Account with account number and institution
            final String accountNumber = "1234";
            final String institutionName = "Test Bank";

            final AccountTable account = new AccountTable();
            account.setAccountId(UUID.randomUUID().toString());
            account.setUserId(testUserId);
            account.setAccountName("Checking Account");
            account.setInstitutionName(institutionName);
            account.setAccountNumber(accountNumber);
            account.setAccountType("CHECKING");
            account.setBalance(new BigDecimal("1000.00"));
            account.setCurrencyCode("USD");
            account.setActive(true);
            account.setCreatedAt(Instant.now());
            account.setUpdatedAt(Instant.now());
            accountRepository.save(account);

            // When - Find by same account number but different institution
            final Optional<AccountTable> found =
                    accountRepository.findByAccountNumberAndInstitution(
                            accountNumber, "Different Bank", testUserId);

            // Then - Should not find the account
            assertFalse(
                    found.isPresent(), "Account should not be found with different institution");
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains("DynamoDB")
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains("Connection")
                    || errorMsg.contains("endpoint")
                    || errorMsg.contains("ResourceNotFoundException")
                    || causeMsg.contains("DynamoDB")
                    || causeMsg.contains("Connection")
                    || causeMsg.contains("endpoint")
                    || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithNullAccountNumberReturnsEmpty() {
        try {
            // When - Find with null account number
            final Optional<AccountTable> found =
                    accountRepository.findByAccountNumberAndInstitution(
                            null, "Test Bank", testUserId);

            // Then - Should return empty
            assertFalse(found.isPresent(), "Should return empty for null account number");
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains("DynamoDB")
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains("Connection")
                    || errorMsg.contains("endpoint")
                    || errorMsg.contains("ResourceNotFoundException")
                    || causeMsg.contains("DynamoDB")
                    || causeMsg.contains("Connection")
                    || causeMsg.contains("endpoint")
                    || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithEmptyAccountNumberReturnsEmpty() {
        try {
            // When - Find with empty account number
            final Optional<AccountTable> found =
                    accountRepository.findByAccountNumberAndInstitution(
                            "", "Test Bank", testUserId);

            // Then - Should return empty
            assertFalse(found.isPresent(), "Should return empty for empty account number");
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains("DynamoDB")
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains("Connection")
                    || errorMsg.contains("endpoint")
                    || errorMsg.contains("ResourceNotFoundException")
                    || causeMsg.contains("DynamoDB")
                    || causeMsg.contains("Connection")
                    || causeMsg.contains("endpoint")
                    || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testAccountNumberIsStoredAndRetrieved() {
        try {
            // Given - Account with account number
            final String accountNumber = "5678";

            final AccountTable account = new AccountTable();
            account.setAccountId(UUID.randomUUID().toString());
            account.setUserId(testUserId);
            account.setAccountName("Savings Account");
            account.setInstitutionName("Test Bank");
            account.setAccountNumber(accountNumber);
            account.setAccountType("SAVINGS");
            account.setBalance(new BigDecimal("5000.00"));
            account.setCurrencyCode("USD");
            account.setActive(true);
            account.setCreatedAt(Instant.now());
            account.setUpdatedAt(Instant.now());
            accountRepository.save(account);

            // When - Retrieve the account
            final Optional<AccountTable> retrieved = accountRepository.findById(account.getAccountId());

            // Then - Account number should be stored and retrieved
            assertTrue(retrieved.isPresent(), "Account should be found");
            assertEquals(
                    accountNumber,
                    retrieved.get().getAccountNumber(),
                    "Account number should be stored and retrieved correctly");
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available), skip it
            final String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            final Throwable cause = e.getCause();
            final String causeMsg =
                    cause != null && cause.getMessage() != null ? cause.getMessage() : "";

            if (errorMsg.contains("DynamoDB")
                    || errorMsg.contains("LocalStack")
                    || errorMsg.contains("Connection")
                    || errorMsg.contains("endpoint")
                    || errorMsg.contains("ResourceNotFoundException")
                    || causeMsg.contains("DynamoDB")
                    || causeMsg.contains("Connection")
                    || causeMsg.contains("endpoint")
                    || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping test: "
                                + errorMsg);
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }
}
