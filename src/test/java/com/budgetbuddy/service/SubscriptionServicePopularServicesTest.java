package com.budgetbuddy.service;



import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Test subscription detection for popular subscription services Tests OpenAI, Hulu+, Uber One, and
 * other common subscriptions
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
public class SubscriptionServicePopularServicesTest {

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

        final String testUserEmail = "test-popular-" + UUID.randomUUID() + "@example.com";
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
    @DisplayName("Should detect OpenAI ChatGPT subscription")
    void testOpenAISubscription() {
        // Given: OpenAI ChatGPT subscription - $22.04/month for 4 months
        final LocalDate baseDate = LocalDate.now().minusMonths(3);
        for (int i = 0; i < 4; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "OPENAI *CHATGPT SUBSCR",
                            "OPENAI *CHATGPT SUBSCR SAN FRANCISCO CA +14158799686",
                            new BigDecimal("-22.04"),
                            baseDate.plusMonths(i),
                            "tech",
                            "tech");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect OpenAI subscription
        final Subscription openai =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && (s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("openai")
                                                || s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("chatgpt")))
                        .findFirst()
                        .orElse(null);

        assertNotNull(openai, "OpenAI subscription should be detected");
        assertEquals(
                Subscription.SubscriptionFrequency.MONTHLY,
                openai.getFrequency(),
                "OpenAI should be detected as monthly subscription");
        assertEquals(
                "ai_service",
                openai.getSubscriptionType(),
                "OpenAI should be detected as ai_service subscription");
        assertTrue(
                openai.getAmount().abs().compareTo(new BigDecimal("22.04")) == 0
                        || openai.getAmount().abs().compareTo(new BigDecimal("22.10")) <= 0,
                "OpenAI amount should be around $22.04");
        assertTrue(openai.getActive(), "OpenAI subscription should be active");
        assertNotNull(openai.getNextPaymentDate(), "OpenAI should have nextPaymentDate set");
    }

    @Test
    @DisplayName("Should detect Hulu+ subscription")
    void testHuluPlusSubscription() {
        // Given: Hulu+ subscription - $14.99/month for 3 months
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "HULU",
                            "HULU.COM/BILL CA",
                            new BigDecimal("-14.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Hulu subscription
        final Subscription hulu =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("hulu"))
                        .findFirst()
                        .orElse(null);

        assertNotNull(hulu, "Hulu subscription should be detected");
        assertEquals(
                Subscription.SubscriptionFrequency.MONTHLY,
                hulu.getFrequency(),
                "Hulu should be detected as monthly subscription");
        assertEquals(
                "streaming",
                hulu.getSubscriptionType(),
                "Hulu should be detected as streaming subscription");
        assertTrue(
                hulu.getAmount().abs().compareTo(new BigDecimal("14.99")) == 0
                        || hulu.getAmount().abs().compareTo(new BigDecimal("15.00")) <= 0,
                "Hulu amount should be around $14.99");
        assertTrue(hulu.getActive(), "Hulu subscription should be active");
    }

    @Test
    @DisplayName("Should detect Uber One subscription")
    void testUberOneSubscription() {
        // Given: Uber One subscription - $9.99/month for 3 months
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "UBER",
                            "UBER ONE MEMBERSHIP",
                            new BigDecimal("-9.99"),
                            baseDate.plusMonths(i),
                            "transportation",
                            "transportation");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Uber One subscription
        final Subscription uber =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && (s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("uber")
                                                || (s.getDescription() != null
                                                && s.getDescription()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("uber one"))))
                        .findFirst()
                        .orElse(null);

        assertNotNull(uber, "Uber One subscription should be detected");
        assertEquals(
                Subscription.SubscriptionFrequency.MONTHLY,
                uber.getFrequency(),
                "Uber One should be detected as monthly subscription");
        assertTrue(
                uber.getAmount().abs().compareTo(new BigDecimal("9.99")) == 0
                        || uber.getAmount().abs().compareTo(new BigDecimal("10.00")) <= 0,
                "Uber One amount should be around $9.99");
        assertTrue(uber.getActive(), "Uber One subscription should be active");
    }

    @Test
    @DisplayName("Should detect Netflix subscription")
    void testNetflixSubscription() {
        // Given: Netflix subscription - $15.99/month for 3 months
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "NETFLIX.COM",
                            "NETFLIX.COM",
                            new BigDecimal("-15.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Netflix subscription
        final Subscription netflix =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("netflix"))
                        .findFirst()
                        .orElse(null);

        assertNotNull(netflix, "Netflix subscription should be detected");
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, netflix.getFrequency());
        assertEquals("streaming", netflix.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect Spotify subscription")
    void testSpotifySubscription() {
        // Given: Spotify subscription - $9.99/month for 3 months
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "SPOTIFY",
                            "SPOTIFY USA",
                            new BigDecimal("-9.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Spotify subscription
        final Subscription spotify =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("spotify"))
                        .findFirst()
                        .orElse(null);

        assertNotNull(spotify, "Spotify subscription should be detected");
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, spotify.getFrequency());
        assertEquals("streaming", spotify.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect Amazon Prime subscription")
    void testAmazonPrimeSubscription() {
        // Given: Amazon Prime subscription - $14.99/month for 3 months
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "AMAZON PRIME",
                            "AMAZON PRIME MEMBERSHIP",
                            new BigDecimal("-14.99"),
                            baseDate.plusMonths(i),
                            "shopping",
                            "membership");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Amazon Prime subscription
        final Subscription prime =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && (s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("amazon")
                                                || s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("prime")))
                        .findFirst()
                        .orElse(null);

        assertNotNull(prime, "Amazon Prime subscription should be detected");
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, prime.getFrequency());
    }

    @Test
    @DisplayName("Should detect Disney+ subscription")
    void testDisneyPlusSubscription() {
        // Given: Disney+ subscription - $10.99/month for 3 months
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "DISNEY PLUS",
                            "DISNEY+ SUBSCRIPTION",
                            new BigDecimal("-10.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Disney+ subscription
        final Subscription disney =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && (s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("disney")
                                                || s.getDescription() != null
                                                && s.getDescription()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("disney")))
                        .findFirst()
                        .orElse(null);

        assertNotNull(disney, "Disney+ subscription should be detected");
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, disney.getFrequency());
        assertEquals("streaming", disney.getSubscriptionType());
    }

    @Test
    @DisplayName("Should detect Apple Music subscription")
    void testAppleMusicSubscription() {
        // Given: Apple Music subscription - $10.99/month for 3 months
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(
                            "APPLE.COM/BILL",
                            "APPLE MUSIC SUBSCRIPTION",
                            new BigDecimal("-10.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming");
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect Apple Music subscription
        final Subscription apple =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && (s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("apple")
                                                || (s.getDescription() != null
                                                && s.getDescription()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("apple music"))))
                        .findFirst()
                        .orElse(null);

        assertNotNull(apple, "Apple Music subscription should be detected");
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, apple.getFrequency());
    }

    @Test
    @DisplayName("Should detect multiple subscriptions simultaneously")
    void testMultipleSubscriptions() {
        // Given: Multiple subscriptions (OpenAI, Hulu, Netflix)
        final LocalDate baseDate = LocalDate.now().minusMonths(2);

        // OpenAI
        for (int i = 0; i < 3; i++) {
            transactionRepository.save(
                    createTransaction(
                            "OPENAI *CHATGPT SUBSCR",
                            "OPENAI *CHATGPT SUBSCR",
                            new BigDecimal("-22.04"),
                            baseDate.plusMonths(i),
                            "tech",
                            "tech"));
        }

        // Hulu
        for (int i = 0; i < 3; i++) {
            transactionRepository.save(
                    createTransaction(
                            "HULU",
                            "HULU.COM/BILL",
                            new BigDecimal("-14.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming"));
        }

        // Netflix
        for (int i = 0; i < 3; i++) {
            transactionRepository.save(
                    createTransaction(
                            "NETFLIX.COM",
                            "NETFLIX.COM",
                            new BigDecimal("-15.99"),
                            baseDate.plusMonths(i),
                            "entertainment",
                            "streaming"));
        }

        // When: Detect subscriptions
        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then: Should detect all three subscriptions
        assertTrue(
                subscriptions.size() >= 3,
                "Should detect at least 3 subscriptions (OpenAI, Hulu, Netflix)");

        final long openaiCount =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("openai"))
                        .count();
        final long huluCount =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("hulu"))
                        .count();
        final long netflixCount =
                subscriptions.stream()
                        .filter(
                                s ->
                                        s.getMerchantName() != null
                                                && s.getMerchantName()
                                                .toLowerCase(Locale.ROOT)
                                                .contains("netflix"))
                        .count();

        assertTrue(openaiCount >= 1, "Should detect OpenAI subscription");
        assertTrue(huluCount >= 1, "Should detect Hulu subscription");
        assertTrue(netflixCount >= 1, "Should detect Netflix subscription");
    }
}
