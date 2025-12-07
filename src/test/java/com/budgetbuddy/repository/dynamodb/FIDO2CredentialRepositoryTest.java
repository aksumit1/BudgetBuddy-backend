package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.FIDO2CredentialTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for FIDO2CredentialRepository
 */
@ExtendWith(MockitoExtension.class)
class FIDO2CredentialRepositoryTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbTable<FIDO2CredentialTable> credentialTable;

    @Mock
    private DynamoDbIndex<FIDO2CredentialTable> userIdIndex;

    @Mock
    private SdkIterable<Page<FIDO2CredentialTable>> pages;

    @Mock
    private Page<FIDO2CredentialTable> page;

    private FIDO2CredentialRepository repository;
    private String testCredentialId;
    private String testUserId;
    private FIDO2CredentialTable testCredential;

    @BeforeEach
    void setUp() {
        testCredentialId = "cred-123";
        testUserId = "user-123";
        testCredential = new FIDO2CredentialTable();
        testCredential.setCredentialId(testCredentialId);
        testCredential.setUserId(testUserId);
        testCredential.setPublicKeyCose("test-public-key-cose");
        testCredential.setSignatureCount(0L);
        
        org.mockito.Mockito.lenient().when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(credentialTable);
        org.mockito.Mockito.lenient().when(credentialTable.index("UserIdIndex")).thenReturn(userIdIndex);
        repository = new FIDO2CredentialRepository(enhancedClient, "Test");
    }

    @Test
    void testSave_WithValidCredential_SavesSuccessfully() {
        // When
        repository.save(testCredential);

        // Then
        verify(credentialTable).putItem(testCredential);
    }

    @Test
    void testSave_WithNullCredential_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> repository.save(null));
    }

    @Test
    void testFindByCredentialId_WithValidId_ReturnsCredential() {
        // Given
        org.mockito.Mockito.lenient().when(credentialTable.getItem(org.mockito.ArgumentMatchers.<Key>any())).thenReturn(testCredential);

        // When
        Optional<FIDO2CredentialTable> result = repository.findByCredentialId(testCredentialId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testCredentialId, result.get().getCredentialId());
    }

    @Test
    void testFindByCredentialId_WithNullId_ReturnsEmpty() {
        // When
        Optional<FIDO2CredentialTable> result = repository.findByCredentialId(null);

        // Then
        assertFalse(result.isPresent());
        verify(credentialTable, never()).getItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByCredentialId_WithEmptyId_ReturnsEmpty() {
        // When
        Optional<FIDO2CredentialTable> result = repository.findByCredentialId("");

        // Then
        assertFalse(result.isPresent());
        verify(credentialTable, never()).getItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByCredentialId_WithNonExistentId_ReturnsEmpty() {
        // Given
        org.mockito.Mockito.lenient().when(credentialTable.getItem(any(Key.class))).thenReturn(null);

        // When
        Optional<FIDO2CredentialTable> result = repository.findByCredentialId(testCredentialId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByUserId_WithValidUserId_ReturnsCredentials() {
        // Given
        List<FIDO2CredentialTable> credentials = List.of(testCredential);
        org.mockito.Mockito.lenient().when(userIdIndex.query(org.mockito.ArgumentMatchers.<QueryConditional>any())).thenReturn(pages);
        org.mockito.Mockito.lenient().when(pages.iterator()).thenReturn(List.of(page).iterator());
        org.mockito.Mockito.lenient().when(page.items()).thenReturn(credentials);

        // When
        List<FIDO2CredentialTable> result = repository.findByUserId(testUserId);

        // Then
        assertEquals(1, result.size());
        assertEquals(testCredentialId, result.get(0).getCredentialId());
    }

    @Test
    void testFindByUserId_WithNullUserId_ReturnsEmptyList() {
        // When
        List<FIDO2CredentialTable> result = repository.findByUserId(null);

        // Then
        assertTrue(result.isEmpty());
        verify(userIdIndex, never()).query(org.mockito.ArgumentMatchers.<QueryConditional>any());
    }

    @Test
    void testFindByUserId_WithEmptyUserId_ReturnsEmptyList() {
        // When
        List<FIDO2CredentialTable> result = repository.findByUserId("");

        // Then
        assertTrue(result.isEmpty());
        verify(userIdIndex, never()).query(org.mockito.ArgumentMatchers.<QueryConditional>any());
    }

    @Test
    void testFindByUserId_WithException_ReturnsEmptyList() {
        // Given
        org.mockito.Mockito.lenient().when(userIdIndex.query(any(QueryConditional.class))).thenThrow(new RuntimeException("DynamoDB error"));

        // When
        List<FIDO2CredentialTable> result = repository.findByUserId(testUserId);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testDelete_WithValidId_DeletesCredential() {
        // When
        repository.delete(testCredentialId);

        // Then
        verify(credentialTable).deleteItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testDelete_WithNullId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> repository.delete(null));
    }

    @Test
    void testDelete_WithEmptyId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> repository.delete(""));
    }

    @Test
    void testUpdateSignatureCount_WithValidInput_UpdatesCredential() {
        // Given
        Long newSignatureCount = 5L;
        // updateItem returns void, so we don't need to stub it

        // When
        repository.updateSignatureCount(testCredentialId, newSignatureCount);

        // Then
        verify(credentialTable).updateItem(org.mockito.ArgumentMatchers.isA(software.amazon.awssdk.enhanced.dynamodb.model.UpdateItemEnhancedRequest.class));
    }

    @Test
    void testUpdateSignatureCount_WithNullId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> repository.updateSignatureCount(null, 5L));
    }

    @Test
    void testUpdateSignatureCount_WithEmptyId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> repository.updateSignatureCount("", 5L));
    }
}

