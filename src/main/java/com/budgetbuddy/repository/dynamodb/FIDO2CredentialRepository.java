package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.FIDO2CredentialTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/** DynamoDB Repository for FIDO2 Credentials */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Repository
public class FIDO2CredentialRepository {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(FIDO2CredentialRepository.class);

    private final DynamoDbTable<FIDO2CredentialTable> credentialTable;
    private final DynamoDbIndex<FIDO2CredentialTable> userIdIndex;
    private final String tableName;

    public FIDO2CredentialRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.tableName = tablePrefix + "-FIDO2Credentials";
        this.credentialTable =
                enhancedClient.table(
                        this.tableName, TableSchema.fromBean(FIDO2CredentialTable.class));
        this.userIdIndex = credentialTable.index("UserIdIndex");
    }

    @CacheEvict(value = "fido2Credentials", key = "#credential.userId")
    public void save(final FIDO2CredentialTable credential) {
        if (credential == null) {
            throw new IllegalArgumentException("Credential cannot be null");
        }
        credentialTable.putItem(credential);
    }

    public Optional<FIDO2CredentialTable> findByCredentialId(final String credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            return Optional.empty();
        }
        final FIDO2CredentialTable credential =
                credentialTable.getItem(Key.builder().partitionValue(credentialId).build());
        return Optional.ofNullable(credential);
    }

    @Cacheable(
            value = "fido2Credentials",
            key = "#userId",
            unless = "#result == null || #result.isEmpty()")
    public List<FIDO2CredentialTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        final List<FIDO2CredentialTable> credentials = new ArrayList<>();
        try {
            final SdkIterable<
                            software.amazon.awssdk.enhanced.dynamodb.model.Page<
                                    FIDO2CredentialTable>>
                    pages =
                            userIdIndex.query(
                                    QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(userId).build()));
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<FIDO2CredentialTable>
                    page : pages) {
                credentials.addAll(page.items());
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error querying credentials by userId: {} - {}", userId, e.getMessage());
            }
        }
        return credentials;
    }

    @CacheEvict(value = "fido2Credentials", allEntries = true)
    public void delete(final String credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            throw new IllegalArgumentException("Credential ID cannot be null or empty");
        }
        credentialTable.deleteItem(Key.builder().partitionValue(credentialId).build());
    }

    public void updateSignatureCount(final String credentialId, final Long signatureCount) {
        if (credentialId == null || credentialId.isEmpty()) {
            throw new IllegalArgumentException("Credential ID cannot be null or empty");
        }
        final FIDO2CredentialTable credential = new FIDO2CredentialTable();
        credential.setCredentialId(credentialId);
        credential.setSignatureCount(signatureCount);
        credential.setLastUsedAt(java.time.Instant.now());

        credentialTable.updateItem(
                software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest.builder(
                                FIDO2CredentialTable.class)
                        .item(credential)
                        .build());
    }
}
