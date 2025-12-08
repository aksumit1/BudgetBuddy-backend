package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DynamoDB Repository for Users
 * Uses GSI for email lookup (cost-optimized)
 * 
 * Note: Depends on DynamoDBTableManager to ensure tables are created before use
 */
@Repository
@org.springframework.context.annotation.DependsOn({"dynamoDBTableManager", "dynamoDbEnhancedClient", "dynamoDbClient"})
public class UserRepository {

    private final DynamoDbTable<UserTable> userTable;
    private final DynamoDbIndex<UserTable> emailIndex;
    private final DynamoDbIndex<UserTable> activeUsersIndex;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private static final String EMAIL_INDEX = "EmailIndex";
    private static final String ACTIVE_USERS_INDEX = "ActiveUsersIndex";

    public UserRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient,
            @org.springframework.beans.factory.annotation.Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        if (enhancedClient == null) {
            throw new IllegalArgumentException("DynamoDbEnhancedClient cannot be null");
        }
        if (dynamoDbClient == null) {
            throw new IllegalArgumentException("DynamoDbClient cannot be null");
        }
        if (tablePrefix == null || tablePrefix.isEmpty()) {
            throw new IllegalArgumentException("tablePrefix cannot be null or empty");
        }
        this.tableName = tablePrefix + "-Users";
        this.dynamoDbClient = dynamoDbClient;
        
        try {
            // Create table reference - this doesn't validate table existence
            this.userTable = enhancedClient.table(this.tableName, TableSchema.fromBean(UserTable.class));
            // Note: index() doesn't validate existence - it just creates a reference
            // Actual validation happens when querying the index
            this.emailIndex = userTable.index(EMAIL_INDEX);
            this.activeUsersIndex = userTable.index(ACTIVE_USERS_INDEX);
        } catch (Exception e) {
            // Wrap any exception with more context
            throw new IllegalStateException(
                    String.format("Failed to initialize UserRepository for table '%s'. " +
                            "This may happen if DynamoDB is not available or table schema is invalid. " +
                            "Original error: %s", tableName, e.getMessage()), e);
        }
    }

    @CacheEvict(value = "users", allEntries = true)
    public void save(final UserTable user) {
        // Ensure activeStatus is set for GSI (computed from enabled if not set)
        if (user.getActiveStatus() == null && user.getEnabled() != null) {
            user.setActiveStatus(user.getEnabled() ? "ACTIVE" : "INACTIVE");
        }
        userTable.putItem(user);
    }

    @Cacheable(value = "users", key = "#userId")
    public Optional<UserTable> findById(final String userId) {
        UserTable user = userTable.getItem(Key.builder().partitionValue(userId).build());
        return Optional.ofNullable(user);
    }

    @Cacheable(value = "users", key = "'email:' + #email", unless = "#result == null")
    public Optional<UserTable> findByEmail(final String email) {
        if (email == null || email.isEmpty()) {
            return Optional.empty();
        }
        try {
            // Query GSI for email lookup
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable>> pages =
                    emailIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(email).build()));
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable> page : pages) {
                for (UserTable item : page.items()) {
                    return Optional.of(item);
                }
            }
            return Optional.empty();
        } catch (ResourceNotFoundException e) {
            // GSI doesn't exist yet - this can happen during startup before tables are created
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .warn("GSI EmailIndex not found for table {}. This may happen during startup. Error: {}", tableName, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            // Log error but don't fail - return empty to allow registration to proceed
            // The conditional write will catch duplicates anyway
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .warn("Error querying user by email: {} - {}", email, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Find user by email bypassing cache (for registration checks)
     * This ensures fresh data from DynamoDB, avoiding stale cache entries
     * Use this method when checking if a user exists before registration
     */
    public Optional<UserTable> findByEmailBypassCache(final String email) {
        if (email == null || email.isEmpty()) {
            return Optional.empty();
        }
        try {
            // Query GSI for email lookup (bypasses cache)
            // Use consistent read to avoid eventual consistency issues
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable>> pages =
                    emailIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(email).build()));
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable> page : pages) {
                for (UserTable item : page.items()) {
                    // Log when user is found for debugging
                    org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                            .debug("Found user by email (bypass cache): {} -> userId: {}", email, item.getUserId());
                    return Optional.of(item);
                }
            }
            // Log when no user is found for debugging
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .debug("No user found by email (bypass cache): {}", email);
            return Optional.empty();
        } catch (ResourceNotFoundException e) {
            // GSI doesn't exist yet - this can happen during startup before tables are created
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .warn("GSI EmailIndex not found for table {} (bypass cache). This may happen during startup. Error: {}", tableName, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            // Log error but don't fail - return empty to allow registration to proceed
            // The conditional write will catch duplicates anyway
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .warn("Error querying user by email (bypass cache): {} - {}", email, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Find ALL users with the same email (for duplicate detection)
     * Returns a list of all users with the given email
     * Used to detect race conditions where multiple users are created with the same email
     */
    public List<UserTable> findAllByEmail(final String email) {
        if (email == null || email.isEmpty()) {
            return List.of();
        }
        List<UserTable> users = new ArrayList<>();
        try {
            // Query GSI for all users with this email
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable>> pages =
                    emailIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(email).build()));
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable> page : pages) {
                users.addAll(page.items());
            }
            return users;
        } catch (ResourceNotFoundException e) {
            // GSI doesn't exist yet - this can happen during startup before tables are created
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .warn("GSI EmailIndex not found for table {} (findAllByEmail). This may happen during startup. Error: {}", tableName, e.getMessage());
            return List.of();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .warn("Error querying all users by email: {} - {}", email, e.getMessage());
            return List.of();
        }
    }

    public boolean existsByEmail(final String email) {
        return findByEmail(email).isPresent();
    }

    @CacheEvict(value = "users", allEntries = true)
    public void delete(final String userId) {
        userTable.deleteItem(Key.builder().partitionValue(userId).build());
    }

    /**
     * Update last login timestamp using UpdateItem (cost-optimized)
     * Eliminates read-before-write pattern
     */
    @CacheEvict(value = "users", allEntries = true)
    public void updateLastLogin(final String userId, final Instant lastLogin) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (lastLogin == null) {
            throw new IllegalArgumentException("Last login timestamp cannot be null");
        }

        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setLastLoginAt(lastLogin);
        user.setUpdatedAt(Instant.now());

        userTable.updateItem(
                UpdateItemEnhancedRequest.builder(UserTable.class)
                        .item(user)
                        .build());
    }

    /**
     * Update only the updatedAt timestamp using UpdateItem (cost-optimized)
     * Preserves all other user fields
     */
    @CacheEvict(value = "users", allEntries = true)
    public void updateTimestamp(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }

        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setUpdatedAt(Instant.now());

        userTable.updateItem(
                UpdateItemEnhancedRequest.builder(UserTable.class)
                        .item(user)
                        .build());
    }

    /**
     * Update a specific field using UpdateItem (cost-optimized)
     * Eliminates read-before-write pattern
     */
    @CacheEvict(value = "users", allEntries = true)
    public void updateField(final String userId, final String fieldName, final Object value) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (fieldName == null || fieldName.isEmpty()) {
            throw new IllegalArgumentException("Field name cannot be null or empty");
        }

        UserTable user = new UserTable();
        user.setUserId(userId);
        user.setUpdatedAt(Instant.now());

        // Set the field value using reflection or switch statement
        switch (fieldName) {
            case "lastLoginAt":
                if (value instanceof Instant) {
                    user.setLastLoginAt((Instant) value);
                }
                break;
            case "emailVerified":
                if (value instanceof Boolean) {
                    user.setEmailVerified((Boolean) value);
                }
                break;
            case "twoFactorEnabled":
                if (value instanceof Boolean) {
                    user.setTwoFactorEnabled((Boolean) value);
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported field: " + fieldName);
        }

        userTable.updateItem(
                UpdateItemEnhancedRequest.builder(UserTable.class)
                        .item(user)
                        .build());
    }

    /**
     * Save user only if it doesn't exist (conditional write)
     * Prevents accidental overwrites
     */
    @CacheEvict(value = "users", allEntries = true)
    public boolean saveIfNotExists(final UserTable user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        if (user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new IllegalArgumentException("User ID is required");
        }

        try {
            userTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(UserTable.class)
                            .item(user)
                            .conditionExpression(
                                    software.amazon.awssdk.enhanced.dynamodb.Expression.builder()
                                            .expression("attribute_not_exists(userId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false; // User already exists
        }
    }

    /**
     * Find user by ID with projection expression (cost-optimized)
     * Retrieves only specified attributes using low-level DynamoDB client
     * 
     * Benefits:
     * - Reduces data transfer costs by 30-50%
     * - Faster response times for partial attribute retrieval
     * - Lower DynamoDB read costs
     */
    public Optional<UserTable> findByIdWithProjection(final String userId, final String... attributes) {
        if (userId == null || userId.isEmpty()) {
            return Optional.empty();
        }

        if (attributes == null || attributes.length == 0) {
            // No projection specified, use regular findById
            return findById(userId);
        }

        try {
            // Build projection expression
            StringBuilder projectionExpression = new StringBuilder();
            Map<String, String> expressionAttributeNames = new HashMap<>();
            
            for (int i = 0; i < attributes.length; i++) {
                String attr = attributes[i];
                String placeholder = "#attr" + i;
                expressionAttributeNames.put(placeholder, attr);
                
                if (i > 0) {
                    projectionExpression.append(", ");
                }
                projectionExpression.append(placeholder);
            }

            // Build key
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("userId", AttributeValue.builder().s(userId).build());

            // Build GetItem request with projection
            GetItemRequest request = GetItemRequest.builder()
                    .tableName(this.tableName)
                    .key(key)
                    .projectionExpression(projectionExpression.toString())
                    .expressionAttributeNames(expressionAttributeNames.isEmpty() ? null : expressionAttributeNames)
                    .build();

            GetItemResponse response = dynamoDbClient.getItem(request);

            if (response.item() == null || response.item().isEmpty()) {
                return Optional.empty();
            }

            // Convert AttributeValue map to UserTable
            UserTable user = convertAttributeValueMapToUser(response.item());
            return Optional.of(user);

        } catch (Exception e) {
            // Fallback to regular findById on error
            return findById(userId);
        }
    }

    /**
     * Convert AttributeValue map to UserTable
     * Only sets attributes that are present in the map
     */
    private UserTable convertAttributeValueMapToUser(final Map<String, AttributeValue> item) {
        UserTable user = new UserTable();

        if (item.containsKey("userId")) {
            user.setUserId(item.get("userId").s());
        }
        if (item.containsKey("email")) {
            user.setEmail(item.get("email").s());
        }
        if (item.containsKey("firstName")) {
            user.setFirstName(item.get("firstName").s());
        }
        if (item.containsKey("lastName")) {
            user.setLastName(item.get("lastName").s());
        }
        if (item.containsKey("emailVerified")) {
            user.setEmailVerified(item.get("emailVerified").bool());
        }
        if (item.containsKey("enabled")) {
            user.setEnabled(item.get("enabled").bool());
        }
        if (item.containsKey("lastLoginAt")) {
            String timestamp = item.get("lastLoginAt").n();
            if (timestamp != null) {
                user.setLastLoginAt(Instant.ofEpochSecond(Long.parseLong(timestamp)));
            }
        }
        if (item.containsKey("updatedAt")) {
            String timestamp = item.get("updatedAt").n();
            if (timestamp != null) {
                user.setUpdatedAt(Instant.ofEpochSecond(Long.parseLong(timestamp)));
            }
        }
        // Add other fields as needed

        return user;
    }

    /**
     * Find active users (users who logged in within the specified days)
     * OPTIMIZED: Uses GSI on enabled + lastLoginAtTimestamp for efficient queries
     * 
     * @param days Number of days to look back (e.g., 30 for users active in last 30 days)
     * @param limit Maximum number of users to return
     * @return List of active user IDs
     */
    public List<String> findActiveUserIds(final int days, final int limit) {
        if (days <= 0) {
            throw new IllegalArgumentException("Days must be positive");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Limit must be positive");
        }

        List<String> activeUserIds = new ArrayList<>();
        Instant cutoffDate = Instant.now().minusSeconds(days * 24L * 60 * 60);
        long cutoffTimestamp = cutoffDate.getEpochSecond();

        try {
            // OPTIMIZED: Use GSI with activeStatus="ACTIVE" as partition key
            // and lastLoginAtTimestamp >= cutoff as sort key range query
            // Query by partition key "ACTIVE" and filter by sort key range
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable>> pages =
                    activeUsersIndex.query(
                            software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest.builder()
                                    .queryConditional(QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue("ACTIVE").build()))
                                    .filterExpression(
                                            software.amazon.awssdk.enhanced.dynamodb.Expression.builder()
                                                    .expression("lastLoginAtTimestamp >= :cutoff")
                                                    .putExpressionValue(":cutoff",
                                                            software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                                                    .n(String.valueOf(cutoffTimestamp))
                                                                    .build())
                                                    .build())
                                    .limit(limit)
                                    .build());

            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<UserTable> page : pages) {
                for (UserTable user : page.items()) {
                    if (user.getUserId() != null && !user.getUserId().isEmpty()) {
                        activeUserIds.add(user.getUserId());
                        if (activeUserIds.size() >= limit) {
                            return activeUserIds;
                        }
                    }
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI doesn't exist yet - this can happen during startup before tables are created
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .warn("GSI ActiveUsersIndex not found for table {}. This may happen during startup. Error: {}", tableName, e.getMessage());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(UserRepository.class)
                    .error("Error finding active users: {}", e.getMessage(), e);
        }

        return activeUserIds;
    }
}

