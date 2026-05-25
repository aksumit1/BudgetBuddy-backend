package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Test subscription detection for OpenAI ChatGPT subscription scenario Tests the specific case
 * where merchant name contains "SUBSCR" abbreviation
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
public class SubscriptionServiceOpenAITest {

    private static final String TECH = "tech";

    @Autowired private SubscriptionService subscriptionService;

    @Autowired private SubscriptionRepository subscriptionRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserService userService;

    @Autowired private DynamoDbClient dynamoDbClient;

    private String testUserId;
    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        // Initialize tables
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);

        // Create test user using UserService (properly hashed password)
        final String testUserEmail = "test-openai-" + UUID.randomUUID() + "@example.com";
        final String base64PasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("test-password".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testUserEmail, base64PasswordHash, "Test", "User");
        testUserId = testUser.getUserId();

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        accountRepository.save(testAccount);

        // Clean up any existing subscriptions for this user
        subscriptionRepository
                .findByUserId(testUserId)
                .forEach(sub -> subscriptionRepository.delete(sub.getSubscriptionId()));
    }

    /** Helper method to create OpenAI ChatGPT transaction */
    private TransactionTable createOpenAITransaction(
            final BigDecimal amount, final LocalDate date) {
        final TransactionTable tx = new TransactionTable();
        tx.setTransactionId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        tx.setUserId(testUserId);
        tx.setAccountId(testAccount.getAccountId());
        tx.setAmount(amount);
        tx.setTransactionDate(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        tx.setDescription("OPENAI *CHATGPT SUBSCR SAN FRANCISCO CA +14158799686");
        tx.setMerchantName("OPENAI *CHATGPT SUBSCR SAN FRANCISCO CA +14158799686");
        tx.setCategoryPrimary(TECH);
        tx.setCategoryDetailed(TECH);
        tx.setTransactionType("PAYMENT");
        return tx;
    }

    @Test
    @DisplayName("Should detect OpenAI ChatGPT subscription with SUBSCR abbreviation over 4 months")
    void testOpenAISubscriptionDetection4Months() {
        // Given: Create 4 monthly OpenAI transactions (exactly 30 days apart for monthly detection)
        final LocalDate baseDate = LocalDate.of(2025, 7, 9); // Start from July 2025
        for (int i = 0; i < 4; i++) {
            final TransactionTable tx =
                    createOpenAITransaction(new BigDecimal("-22.04"), baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then: Verify OpenAI subscription was detected
        assertFalse(subscriptions.isEmpty(), "Should detect at least 1 subscription");

        final Subscription openAISubscription =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("openai"))
                        .findFirst()
                        .orElse(null);

        assertNotNull(openAISubscription, "OpenAI subscription should be detected");
        assertEquals(
                Subscription.SubscriptionFrequency.MONTHLY,
                openAISubscription.getFrequency(),
                "Should detect as monthly subscription");
        assertEquals(
                new BigDecimal("-22.04"), openAISubscription.getAmount(), "Amount should match");
        assertEquals(
                "ai_service",
                openAISubscription.getSubscriptionType(),
                "Should be classified as ai_service subscription");
    }

    @Test
    @DisplayName("Should detect OpenAI ChatGPT subscription with minimum 3 transactions")
    void testOpenAISubscriptionDetection3Transactions() {
        // Given: 4 monthly OpenAI transactions. The MONTHLY occurrence
        // floor was tightened from 3 to 4 to prevent gas-pump / weekly
        // grocery patterns from being mis-flagged as subscriptions;
        // legitimate monthly subs accumulate the 4th cycle within a
        // few months of opening.
        final LocalDate date1 = LocalDate.of(2025, 8, 9);
        final LocalDate date2 = LocalDate.of(2025, 9, 9);
        final LocalDate date3 = LocalDate.of(2025, 10, 9);
        final LocalDate date4 = LocalDate.of(2025, 11, 9);

        transactionRepository.save(createOpenAITransaction(new BigDecimal("-22.04"), date1));
        transactionRepository.save(createOpenAITransaction(new BigDecimal("-22.04"), date2));
        transactionRepository.save(createOpenAITransaction(new BigDecimal("-22.04"), date3));
        transactionRepository.save(createOpenAITransaction(new BigDecimal("-22.04"), date4));

        // When: Detect subscriptions
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then: Verify OpenAI subscription was detected
        assertFalse(subscriptions.isEmpty(), "Should detect at least 1 subscription");

        final Subscription openAISubscription =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("openai"))
                        .findFirst()
                        .orElse(null);

        assertNotNull(
                openAISubscription,
                "OpenAI subscription should be detected once 4 monthly cycles are present");
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, openAISubscription.getFrequency());
    }

    @Test
    @DisplayName(
            "Should detect OpenAI subscription even with tech category (not subscriptions category)")
    void testOpenAISubscriptionDetectionTechCategory() {
        // Given: 4 monthly tech-categorised transactions (the MONTHLY
        // floor was tightened to 4 — see testOpenAISubscriptionDetection3Transactions
        // for the full rationale).
        final LocalDate date1 = LocalDate.of(2025, 8, 9);
        final LocalDate date2 = LocalDate.of(2025, 9, 9);
        final LocalDate date3 = LocalDate.of(2025, 10, 9);
        final LocalDate date4 = LocalDate.of(2025, 11, 9);

        for (final LocalDate d : java.util.List.of(date1, date2, date3, date4)) {
            final TransactionTable tx = createOpenAITransaction(new BigDecimal("-22.04"), d);
            tx.setCategoryPrimary(TECH);
            tx.setCategoryDetailed(TECH);
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then: Verify subscription was detected despite tech category
        assertFalse(subscriptions.isEmpty(), "Should detect subscription even with tech category");

        final Subscription openAISubscription =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains("openai"))
                        .findFirst()
                        .orElse(null);

        assertNotNull(
                openAISubscription, "OpenAI subscription should be detected with tech category");
    }
}
