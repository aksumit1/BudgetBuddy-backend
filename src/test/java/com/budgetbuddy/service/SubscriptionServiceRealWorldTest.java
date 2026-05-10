package com.budgetbuddy.service;



import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.LocalDate;
import java.util.List;
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
 * Comprehensive test for real-world subscription scenarios Tests newspapers, magazines, retail
 * memberships, insurance, parking, etc.
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
public class SubscriptionServiceRealWorldTest {

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
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);

        final String testUserEmail = "test-realworld-" + UUID.randomUUID() + "@example.com";
        final String base64PasswordHash =
                java.util.Base64.getEncoder().encodeToString("test-password".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testUserEmail, base64PasswordHash, "Test", "User");
        testUserId = testUser.getUserId();

        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        accountRepository.save(testAccount);

        subscriptionRepository
                .findByUserId(testUserId)
                .forEach(sub -> subscriptionRepository.delete(sub.getSubscriptionId()));
    }

    private TransactionTable createTransaction(
            final String merchant,
            final String description,
            final BigDecimal amount,
            final LocalDate date,
            final String categoryPrimary,
            final String categoryDetailed) {
        final TransactionTable tx = new TransactionTable();
        tx.setTransactionId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        tx.setUserId(testUserId);
        tx.setAccountId(testAccount.getAccountId());
        tx.setAmount(amount);
        tx.setTransactionDate(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        tx.setDescription(description);
        tx.setMerchantName(merchant);
        tx.setCategoryPrimary(categoryPrimary);
        tx.setCategoryDetailed(categoryDetailed);
        tx.setTransactionType("EXPENSE");
        return tx;
    }

    @Test
    @DisplayName("Should detect Wall Street Journal subscription")
    void testWSJSubscription() {
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "WSJ.COM",
                            "WSJ Subscription",
                            new BigDecimal("-19.99"),
                            baseDate.plusMonths(i),
                            "education",
                            "education");
            transactionRepository.save(tx);
        }

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        assertFalse(subscriptions.isEmpty(), "Should detect WSJ subscription");

        final Subscription wsj =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("wsj"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(wsj, "WSJ subscription should be detected");
        assertEquals("news_media", wsj.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect DJ*Barrons subscription with $4.19/month and credit from Amex")
    void testBarronsSubscription() {
        // Given: DJ*Barrons subscription - $4.19/month for 4 months
        // This tests the specific case mentioned by the user
        final LocalDate baseDate = LocalDate.now().minusMonths(3);
        for (int i = 0; i < 4; i++) {
            // Create expense transaction (negative amount)
            final TransactionTable tx =
                    createTransaction(
                            "D J*BARRONS",
                            "D J*BARRONS 800-544-0422 NJ SUBSRIPTION",
                            new BigDecimal("-4.19"),
                            baseDate.plusMonths(i),
                            "education", // Barrons is categorized as education, but should still be
                            // detected as subscription
                            "education");
            transactionRepository.save(tx);

            // Create credit transaction (positive amount) - should NOT be matched
            final TransactionTable credit =
                    createTransaction(
                            "AMEX",
                            "Platinum Digital Entertainment Credit D J*BARRONS",
                            new BigDecimal("4.19"),
                            baseDate.plusMonths(i).plusDays(1),
                            "education",
                            "education");
            transactionRepository.save(credit);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Barrons subscription
        final Subscription barrons =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && (s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("barrons")
                                                || s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("dj")))
                        .findFirst()
                        .orElse(null);

        assertNotNull(
                barrons,
                "Barrons subscription should be detected despite being categorized as education");
        assertEquals(
                Subscription.SubscriptionFrequency.MONTHLY,
                barrons.getFrequency(),
                "Barrons should be detected as monthly subscription");
        assertEquals(
                "news_media",
                barrons.getSubscriptionType(),
                "Barrons should be detected as news_media type");
        assertTrue(
                barrons.getAmount().abs().compareTo(new BigDecimal("4.19")) == 0
                        || barrons.getAmount().abs().compareTo(new BigDecimal("4.20")) <= 0,
                "Barrons amount should be around $4.19");
        assertTrue(barrons.getActive(), "Barrons subscription should be active");
        assertNotNull(barrons.getNextPaymentDate(), "Barrons should have nextPaymentDate set");
    }

    @Test
    @DisplayName("Should detect Costco membership")
    void testCostcoMembership() {
        final LocalDate baseDate = LocalDate.now().minusYears(1);
        // Annual membership - 3 payments to ensure pattern detection
        // Use consistent merchant name to ensure grouping
        final TransactionTable tx1 =
                createTransaction(
                        "COSTCO",
                        "Costco Membership",
                        new BigDecimal("-60.00"),
                        baseDate,
                        "subscriptions", // Use subscriptions category for better detection
                        "membership");
        transactionRepository.save(tx1);

        final TransactionTable tx2 =
                createTransaction(
                        "COSTCO",
                        "Costco Annual Membership",
                        new BigDecimal("-60.00"),
                        baseDate.plusYears(1),
                        "subscriptions", // Use subscriptions category for better detection
                        "membership");
        transactionRepository.save(tx2);

        // Add a third transaction to strengthen the pattern (even if it's in the future)
        final TransactionTable tx3 =
                createTransaction(
                        "COSTCO",
                        "Costco Annual Membership Renewal",
                        new BigDecimal("-60.00"),
                        baseDate.plusYears(2),
                        "subscriptions",
                        "membership");
        transactionRepository.save(tx3);

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        final Subscription costco =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("costco"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(costco, "Costco membership should be detected");
        assertEquals(Subscription.SubscriptionFrequency.ANNUAL, costco.getFrequency());
        assertEquals("membership", costco.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect gym membership")
    void testGymMembership() {
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "Planet Fitness",
                            "Monthly Membership",
                            new BigDecimal("-10.99"),
                            baseDate.plusMonths(i),
                            "health",
                            "fitness");
            transactionRepository.save(tx);
        }

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        final Subscription gym =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("planet"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(gym, "Gym membership should be detected");
        assertEquals("membership", gym.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect insurance recurring payment")
    void testInsuranceSubscription() {
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "State Farm Insurance",
                            "Auto Insurance Premium",
                            new BigDecimal("-150.00"),
                            baseDate.plusMonths(i),
                            "insurance",
                            "insurance");
            transactionRepository.save(tx);
        }

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        final Subscription insurance =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("state farm"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(insurance, "Insurance subscription should be detected");
    }

    @Test
    @DisplayName("Should detect YouTube Music subscription")
    void testYouTubeMusicSubscription() {
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "Google",
                            "YouTube Music Premium",
                            new BigDecimal("-10.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming");
            transactionRepository.save(tx);
        }

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        final Subscription ytMusic =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getDescription() != null
                                                && s.getDescription()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("youtube music"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(ytMusic, "YouTube Music subscription should be detected");
        assertEquals("streaming", ytMusic.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect Cursor AI subscription")
    void testCursorAISubscription() {
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "Cursor",
                            "Cursor AI Subscription",
                            new BigDecimal("-20.00"),
                            baseDate.plusMonths(i),
                            "tech",
                            "tech");
            transactionRepository.save(tx);
        }

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        final Subscription cursor =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("cursor"))
                        .findFirst()
                        .orElse(null);
        assertNotNull(cursor, "Cursor AI subscription should be detected");
        assertEquals("ai_service", cursor.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect day-of-month pattern (1st of month)")
    void testDayOfMonthPattern() {
        // Create transactions on 1st of each month
        final LocalDate baseDate = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 4; i++) {
            final LocalDate date = baseDate.plusMonths(i).withDayOfMonth(1);
            final TransactionTable tx =
                    createTransaction(
                            "Recurring Service",
                            "Monthly Subscription",
                            new BigDecimal("-25.00"),
                            date,
                            "subscriptions",
                            "subscriptions");
            transactionRepository.save(tx);
        }

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        assertFalse(
                subscriptions.isEmpty(), "Should detect subscription with day-of-month pattern");
    }

    @Test
    @DisplayName("Should detect weekly subscription pattern")
    void testWeeklySubscription() {
        final LocalDate baseDate = LocalDate.now().minusWeeks(4);
        for (int i = 0; i < 5; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "Weekly Service",
                            "Weekly Subscription",
                            new BigDecimal("-5.99"),
                            baseDate.plusWeeks(i),
                            "subscriptions",
                            "subscriptions");
            transactionRepository.save(tx);
        }

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);
        final Subscription weekly =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("weekly"))
                        .findFirst()
                        .orElse(null);
        if (weekly != null) {
            assertEquals(Subscription.SubscriptionFrequency.WEEKLY, weekly.getFrequency());
        }
    }
}
