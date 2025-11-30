package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.FIDO2CredentialTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DynamoDB Repository for FIDO2 Credentials
 */
@Repository
public class FIDO2CredentialRepository {

    private final DynamoDbTable<FIDO2CredentialTable> credentialTable;
    private final DynamoDbIndex<FIDO2CredentialTable> userIdIndex;
    private static final String TABLE_NAME = "BudgetBuddy-FIDO2Credentials";

    public FIDO2CredentialRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.credentialTable = enhancedClient.table(TABLE_NAME, 
                TableSchema.fromBean(FIDO2CredentialTable.class));
        this.userIdIndex = credentialTable.index("UserIdIndex");
    }

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
        FIDO2CredentialTable credential = credentialTable.getItem(
                Key.builder().partitionValue(credentialId).build());
        return Optional.ofNullable(credential);
    }

    public List<FIDO2CredentialTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        List<FIDO2CredentialTable> credentials = new ArrayList<>();
        try {
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<FIDO2CredentialTable>> pages =
                    userIdIndex.query(QueryConditional.keyEqualTo(
                            Key.builder().partitionValue(userId).build()));
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<FIDO2CredentialTable> page : pages) {
                credentials.addAll(page.items());
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(FIDO2CredentialRepository.class)
                    .error("Error querying credentials by userId: {} - {}", userId, e.getMessage());
        }
        return credentials;
    }

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
        FIDO2CredentialTable credential = new FIDO2CredentialTable();
        credential.setCredentialId(credentialId);
        credential.setSignatureCount(signatureCount);
        credential.setLastUsedAt(java.time.Instant.now());
        
        credentialTable.updateItem(
                software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest.builder(FIDO2CredentialTable.class)
                        .item(credential)
                        .build());
    }
}

