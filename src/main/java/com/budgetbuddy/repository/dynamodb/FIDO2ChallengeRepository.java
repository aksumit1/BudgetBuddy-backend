package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 * DynamoDB Repository for FIDO2 Challenges Challenges are stored with TTL for automatic expiration
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@Repository
public class FIDO2ChallengeRepository {

    private final DynamoDbTable<FIDO2ChallengeTable> challengeTable;
    private final String tableName;

    public FIDO2ChallengeRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.tableName = tablePrefix + "-FIDO2Challenges";
        this.challengeTable =
                enhancedClient.table(
                        this.tableName, TableSchema.fromBean(FIDO2ChallengeTable.class));
    }

    public void save(final FIDO2ChallengeTable challenge) {
        if (challenge == null) {
            throw new IllegalArgumentException("Challenge cannot be null");
        }
        // Set TTL to expiresAt timestamp (Unix seconds)
        if (challenge.getExpiresAt() != null && challenge.getTtl() == null) {
            challenge.setTtl(challenge.getExpiresAt().getEpochSecond());
        }
        challengeTable.putItem(challenge);
    }

    public Optional<FIDO2ChallengeTable> findByChallengeKey(final String challengeKey) {
        if (challengeKey == null || challengeKey.isEmpty()) {
            return Optional.empty();
        }
        final FIDO2ChallengeTable challenge =
                challengeTable.getItem(Key.builder().partitionValue(challengeKey).build());

        // Check if challenge has expired (even if TTL hasn't removed it yet)
        if (challenge != null && challenge.getExpiresAt() != null) {
            if (challenge.getExpiresAt().isBefore(Instant.now())) {
                // Challenge expired, delete it and return empty
                delete(challengeKey);
                return Optional.empty();
            }
        }

        return Optional.ofNullable(challenge);
    }

    public void delete(final String challengeKey) {
        if (challengeKey == null || challengeKey.isEmpty()) {
            return;
        }
        challengeTable.deleteItem(Key.builder().partitionValue(challengeKey).build());
    }

    /** Generate challenge key from userId and type */
    public static String generateChallengeKey(final String userId, final String type) {
        return userId + ":" + type;
    }
}
