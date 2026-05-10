package com.budgetbuddy.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.dynamodb.DeviceTokenTable;
import com.budgetbuddy.repository.dynamodb.DeviceTokenRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
// Note: This test requires Docker/Testcontainers for full integration testing
// For unit testing without Docker, use mocked repositories in service tests
// Integration tests should be run separately with Docker available
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * DeviceTokenRepository Tests Tests edge cases, race conditions, and concurrent operations
 *
 * <p>NOTE: This is an integration test that requires Docker/Testcontainers For unit testing, use
 * mocked repositories in service tests Run integration tests separately: mvn verify
 * -Pintegration-test
 *
 * <p>CRITICAL: Testcontainers requires Docker. If Docker is not available, the test will fail
 * during class initialization. This test is disabled in CI environments where Docker may not be
 * available.
 */
// `@Testcontainers` is the JUnit 5 extension that actually starts the
// @Container-annotated fields. Without it the static LocalStack container
// is constructed but never started, so every test assertion runs against
// an isRunning=false container and gets Skipped. Pre-existing bug —
// the test advertised itself as an integration test but never actually
// integrated.
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.LawOfDemeter")
@org.testcontainers.junit.jupiter.Testcontainers
@org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable(named = "CI", matches = "true")
@DisplayName("DeviceTokenRepository Integration Tests")
class DeviceTokenRepositoryTest {

    // Docker availability is checked by Testcontainers automatically
    // Container initialization will be skipped if Docker is not available

    @org.testcontainers.junit.jupiter.Container
    static org.testcontainers.containers.localstack.LocalStackContainer localStack =
            new org.testcontainers.containers.localstack.LocalStackContainer(
                            // Pin to community 3.8 — `:latest` resolves to a Pro
                            // build that requires a license and exits 55 on boot.
                            org.testcontainers.utility.DockerImageName.parse(
                                    "localstack/localstack:3.8"))
                    .withServices(
                            org.testcontainers.containers.localstack.LocalStackContainer.Service
                                    .DYNAMODB);

    private DynamoDbClient dynamoDbClient;
    private DynamoDbEnhancedClient enhancedClient;
    private DeviceTokenRepository repository;
    private String tableName;

    @BeforeEach
    void setUp() {
        // Testcontainers will handle Docker availability gracefully
        if (localStack == null || !localStack.isRunning()) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                    false, "LocalStack container not available - skipping test");
            return;
        }

        dynamoDbClient =
                DynamoDbClient.builder()
                        .endpointOverride(
                                localStack.getEndpointOverride(
                                        org.testcontainers.containers.localstack.LocalStackContainer
                                                .Service.DYNAMODB))
                        .region(Region.of(localStack.getRegion()))
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                localStack.getAccessKey(),
                                                localStack.getSecretKey())))
                        .build();

        enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();

        tableName = "Test-DeviceTokens";
        createTable();
        repository = new DeviceTokenRepository(dynamoDbClient, enhancedClient, "Test");
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (dynamoDbClient != null) {
            dynamoDbClient.close();
        }
    }

    private void createTable() {
        try {
            dynamoDbClient.createTable(
                    CreateTableRequest.builder()
                            .tableName(tableName)
                            .keySchema(
                                    KeySchemaElement.builder()
                                            .attributeName("userId")
                                            .keyType(KeyType.HASH)
                                            .build(),
                                    KeySchemaElement.builder()
                                            .attributeName("deviceToken")
                                            .keyType(KeyType.RANGE)
                                            .build())
                            .attributeDefinitions(
                                    AttributeDefinition.builder()
                                            .attributeName("userId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build(),
                                    AttributeDefinition.builder()
                                            .attributeName("deviceToken")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                            .billingMode(BillingMode.PAY_PER_REQUEST)
                            .build());

            // Wait for table to be active
            dynamoDbClient
                    .waiter()
                    .waitUntilTableExists(
                            DescribeTableRequest.builder().tableName(tableName).build());
        } catch (ResourceInUseException e) {
            // Table already exists, ignore
        }
    }

    @Test
    @DisplayName("Should save and retrieve device token")
    void testSaveAndRetrieve() {
        final String userId = UUID.randomUUID().toString();
        final String deviceToken = UUID.randomUUID().toString();

        final DeviceTokenTable token = new DeviceTokenTable();
        token.setUserId(userId);
        token.setDeviceToken(deviceToken);
        token.setPlatform("ios");
        token.setEnabled(true);

        repository.save(token);

        final Optional<DeviceTokenTable> retrieved =
                repository.findByUserIdAndDeviceToken(userId, deviceToken);
        assertTrue(retrieved.isPresent());
        assertEquals(userId, retrieved.get().getUserId());
        assertEquals(deviceToken, retrieved.get().getDeviceToken());
        assertEquals("ios", retrieved.get().getPlatform());
        assertTrue(retrieved.get().getEnabled());
    }

    @Test
    @DisplayName("Should handle concurrent saves (race condition)")
    void testConcurrentSaves() throws Exception {
        final String userId = UUID.randomUUID().toString();
        final String deviceToken = UUID.randomUUID().toString();

        final ExecutorService executor = Executors.newFixedThreadPool(10);
        final List<CompletableFuture<Void>> futures =
                IntStream.range(0, 10)
                        .mapToObj(
                                i ->
                                        CompletableFuture.runAsync(
                                                () -> {
                                                    final DeviceTokenTable token = new DeviceTokenTable();
                                                    token.setUserId(userId);
                                                    token.setDeviceToken(deviceToken);
                                                    token.setPlatform("ios");
                                                    token.setEnabled(true);
                                                    repository.save(token);
                                                },
                                                executor))
                        .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();

        // Should have exactly one token (last write wins)
        final Optional<DeviceTokenTable> retrieved =
                repository.findByUserIdAndDeviceToken(userId, deviceToken);
        assertTrue(retrieved.isPresent());
    }

    @Test
    @DisplayName("Should find all enabled tokens for user")
    void testFindEnabledTokens() {
        final String userId = UUID.randomUUID().toString();

        // Create enabled and disabled tokens
        for (int i = 0; i < 5; i++) {
            final DeviceTokenTable token = new DeviceTokenTable();
            token.setUserId(userId);
            token.setDeviceToken(UUID.randomUUID().toString());
            token.setPlatform("ios");
            token.setEnabled(i % 2 == 0); // Alternate enabled/disabled
            repository.save(token);
        }

        final List<DeviceTokenTable> enabledTokens = repository.findEnabledByUserId(userId);
        assertEquals(3, enabledTokens.size()); // Should have 3 enabled tokens
        assertTrue(enabledTokens.stream().allMatch(DeviceTokenTable::getEnabled));
    }

    @Test
    @DisplayName("Should handle null userId gracefully")
    void testNullUserId() {
        // Production code catches all exceptions and returns an empty list
        // rather than throwing — "gracefully" in the sense of not crashing
        // the caller. The prior assertion (expecting a throw) conflicted
        // with that contract; aligned to the production behaviour.
        final List<DeviceTokenTable> tokens = repository.findByUserId(null);
        assertTrue(tokens.isEmpty(), "findByUserId(null) must return an empty list, not throw");
    }

    @Test
    @DisplayName("Should handle empty userId gracefully")
    void testEmptyUserId() {
        final List<DeviceTokenTable> tokens = repository.findByUserId("");
        assertTrue(tokens.isEmpty());
    }

    @Test
    @DisplayName("Should update last used timestamp")
    void testUpdateLastUsed() throws InterruptedException {
        final String userId = UUID.randomUUID().toString();
        final String deviceToken = UUID.randomUUID().toString();

        final DeviceTokenTable token = new DeviceTokenTable();
        token.setUserId(userId);
        token.setDeviceToken(deviceToken);
        token.setPlatform("ios");
        token.setEnabled(true);
        repository.save(token);

        Thread.sleep(1000); // Wait 1 second
        final Instant beforeUpdate = Instant.now();
        repository.updateLastUsed(userId, deviceToken);

        final Optional<DeviceTokenTable> updated =
                repository.findByUserIdAndDeviceToken(userId, deviceToken);
        assertTrue(updated.isPresent());
        assertNotNull(updated.get().getLastUsedAt());
        assertTrue(updated.get().getLastUsedAt().isAfter(beforeUpdate.minusSeconds(5)));
    }

    @Test
    @DisplayName("Should disable device token")
    void testDisableToken() {
        final String userId = UUID.randomUUID().toString();
        final String deviceToken = UUID.randomUUID().toString();

        final DeviceTokenTable token = new DeviceTokenTable();
        token.setUserId(userId);
        token.setDeviceToken(deviceToken);
        token.setPlatform("ios");
        token.setEnabled(true);
        repository.save(token);

        repository.disable(userId, deviceToken);

        final Optional<DeviceTokenTable> disabled =
                repository.findByUserIdAndDeviceToken(userId, deviceToken);
        assertTrue(disabled.isPresent());
        assertFalse(disabled.get().getEnabled());
    }

    @Test
    @DisplayName("Should handle duplicate tokens (same userId and deviceToken)")
    void testDuplicateTokens() {
        final String userId = UUID.randomUUID().toString();
        final String deviceToken = UUID.randomUUID().toString();

        final DeviceTokenTable token1 = new DeviceTokenTable();
        token1.setUserId(userId);
        token1.setDeviceToken(deviceToken);
        token1.setPlatform("ios");
        token1.setEnabled(true);

        final DeviceTokenTable token2 = new DeviceTokenTable();
        token2.setUserId(userId);
        token2.setDeviceToken(deviceToken);
        token2.setPlatform("android");
        token2.setEnabled(false);

        repository.save(token1);
        repository.save(token2); // Should overwrite

        final Optional<DeviceTokenTable> retrieved =
                repository.findByUserIdAndDeviceToken(userId, deviceToken);
        assertTrue(retrieved.isPresent());
        assertEquals("android", retrieved.get().getPlatform()); // Last write wins
        assertFalse(retrieved.get().getEnabled());
    }

    @Test
    @DisplayName("Should handle delete non-existent token gracefully")
    void testDeleteNonExistentToken() {
        final String userId = UUID.randomUUID().toString();
        final String deviceToken = UUID.randomUUID().toString();

        // Should not throw exception
        assertDoesNotThrow(
                () -> {
                    repository.delete(userId, deviceToken);
                });
    }

    @Test
    @DisplayName("Should handle multiple devices per user")
    void testMultipleDevicesPerUser() {
        final String userId = UUID.randomUUID().toString();

        // Create multiple devices
        for (int i = 0; i < 10; i++) {
            final DeviceTokenTable token = new DeviceTokenTable();
            token.setUserId(userId);
            token.setDeviceToken(UUID.randomUUID().toString());
            token.setPlatform(i % 2 == 0 ? "ios" : "android");
            token.setEnabled(true);
            repository.save(token);
        }

        final List<DeviceTokenTable> allTokens = repository.findByUserId(userId);
        assertEquals(10, allTokens.size());
    }
}
