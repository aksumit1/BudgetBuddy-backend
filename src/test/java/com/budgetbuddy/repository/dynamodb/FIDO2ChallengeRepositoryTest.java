package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for FIDO2ChallengeRepository
 */
@ExtendWith(MockitoExtension.class)
class FIDO2ChallengeRepositoryTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbTable<FIDO2ChallengeTable> challengeTable;

    private FIDO2ChallengeRepository repository;
    private String testChallengeKey;
    private FIDO2ChallengeTable testChallenge;

    @BeforeEach
    void setUp() {
        testChallengeKey = "user-123:registration";
        testChallenge = new FIDO2ChallengeTable();
        testChallenge.setChallengeKey(testChallengeKey);
        testChallenge.setUserId("user-123");
        testChallenge.setChallenge("test-challenge");
        testChallenge.setExpiresAt(Instant.now().plusSeconds(300));
        
        org.mockito.Mockito.lenient().when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(challengeTable);
        repository = new FIDO2ChallengeRepository(enhancedClient, "Test");
    }

    @Test
    void testSave_WithValidChallenge_SavesSuccessfully() {
        // When
        repository.save(testChallenge);

        // Then
        verify(challengeTable).putItem(testChallenge);
        assertNotNull(testChallenge.getTtl());
    }

    @Test
    void testSave_WithNullChallenge_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> repository.save(null));
    }

    @Test
    void testSave_WithExpiresAt_SetsTtl() {
        // Given
        testChallenge.setTtl(null);
        testChallenge.setExpiresAt(Instant.now().plusSeconds(300));

        // When
        repository.save(testChallenge);

        // Then
        assertNotNull(testChallenge.getTtl());
        assertEquals(testChallenge.getExpiresAt().getEpochSecond(), testChallenge.getTtl());
    }

    @Test
    void testFindByChallengeKey_WithValidKey_ReturnsChallenge() {
        // Given
        org.mockito.Mockito.lenient().when(challengeTable.getItem(org.mockito.ArgumentMatchers.<Key>any())).thenReturn(testChallenge);

        // When
        Optional<FIDO2ChallengeTable> result = repository.findByChallengeKey(testChallengeKey);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testChallengeKey, result.get().getChallengeKey());
    }

    @Test
    void testFindByChallengeKey_WithNullKey_ReturnsEmpty() {
        // When
        Optional<FIDO2ChallengeTable> result = repository.findByChallengeKey(null);

        // Then
        assertFalse(result.isPresent());
        verify(challengeTable, never()).getItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByChallengeKey_WithEmptyKey_ReturnsEmpty() {
        // When
        Optional<FIDO2ChallengeTable> result = repository.findByChallengeKey("");

        // Then
        assertFalse(result.isPresent());
        verify(challengeTable, never()).getItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByChallengeKey_WithExpiredChallenge_ReturnsEmpty() {
        // Given
        testChallenge.setExpiresAt(Instant.now().minusSeconds(100));
        org.mockito.Mockito.lenient().when(challengeTable.getItem(org.mockito.ArgumentMatchers.<Key>any())).thenReturn(testChallenge);
        // deleteItem returns void, no need to stub it

        // When
        Optional<FIDO2ChallengeTable> result = repository.findByChallengeKey(testChallengeKey);

        // Then
        assertFalse(result.isPresent());
        verify(challengeTable).deleteItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByChallengeKey_WithNonExistentKey_ReturnsEmpty() {
        // Given
        org.mockito.Mockito.lenient().when(challengeTable.getItem(any(Key.class))).thenReturn(null);

        // When
        Optional<FIDO2ChallengeTable> result = repository.findByChallengeKey(testChallengeKey);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testDelete_WithValidKey_DeletesChallenge() {
        // When
        repository.delete(testChallengeKey);

        // Then
        verify(challengeTable).deleteItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testDelete_WithNullKey_DoesNothing() {
        // When
        repository.delete(null);

        // Then
        verify(challengeTable, never()).deleteItem(org.mockito.ArgumentMatchers.isA(Key.class));
    }

    @Test
    void testDelete_WithEmptyKey_DoesNothing() {
        // When
        repository.delete("");

        // Then
        verify(challengeTable, never()).deleteItem(org.mockito.ArgumentMatchers.isA(Key.class));
    }

    @Test
    void testGenerateChallengeKey_WithValidInput_ReturnsKey() {
        // When
        String key = FIDO2ChallengeRepository.generateChallengeKey("user-123", "registration");

        // Then
        assertEquals("user-123:registration", key);
    }
}

