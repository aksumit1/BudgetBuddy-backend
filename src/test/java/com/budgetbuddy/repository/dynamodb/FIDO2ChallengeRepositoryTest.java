package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

/** Unit Tests for FIDO2ChallengeRepository */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class FIDO2ChallengeRepositoryTest {

    @Mock private DynamoDbEnhancedClient enhancedClient;

    @Mock private DynamoDbTable<FIDO2ChallengeTable> challengeTable;

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

        org.mockito.Mockito.lenient()
                .when(
                        enhancedClient.table(
                                anyString(),
                                any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(challengeTable);
        repository = new FIDO2ChallengeRepository(enhancedClient, "Test");
    }

    @Test
    void testSaveWithValidChallengeSavesSuccessfully() {
        // When
        repository.save(testChallenge);

        // Then
        verify(challengeTable).putItem(testChallenge);
        assertNotNull(testChallenge.getTtl());
    }

    @Test
    void testSaveWithNullChallengeThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> repository.save(null));
    }

    @Test
    void testSaveWithExpiresAtSetsTtl() {
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
    void testFindByChallengeKeyWithValidKeyReturnsChallenge() {
        // Given
        org.mockito.Mockito.lenient()
                .when(challengeTable.getItem(org.mockito.ArgumentMatchers.<Key>any()))
                .thenReturn(testChallenge);

        // When
        final Optional<FIDO2ChallengeTable> result =
                repository.findByChallengeKey(testChallengeKey);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testChallengeKey, result.get().getChallengeKey());
    }

    @Test
    void testFindByChallengeKeyWithNullKeyReturnsEmpty() {
        // When
        final Optional<FIDO2ChallengeTable> result = repository.findByChallengeKey(null);

        // Then
        assertFalse(result.isPresent());
        verify(challengeTable, never()).getItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByChallengeKeyWithEmptyKeyReturnsEmpty() {
        // When
        final Optional<FIDO2ChallengeTable> result = repository.findByChallengeKey("");

        // Then
        assertFalse(result.isPresent());
        verify(challengeTable, never()).getItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByChallengeKeyWithExpiredChallengeReturnsEmpty() {
        // Given
        testChallenge.setExpiresAt(Instant.now().minusSeconds(100));
        org.mockito.Mockito.lenient()
                .when(challengeTable.getItem(org.mockito.ArgumentMatchers.<Key>any()))
                .thenReturn(testChallenge);
        // deleteItem returns void, no need to stub it

        // When
        final Optional<FIDO2ChallengeTable> result =
                repository.findByChallengeKey(testChallengeKey);

        // Then
        assertFalse(result.isPresent());
        verify(challengeTable).deleteItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testFindByChallengeKeyWithNonExistentKeyReturnsEmpty() {
        // Given
        org.mockito.Mockito.lenient().when(challengeTable.getItem(any(Key.class))).thenReturn(null);

        // When
        final Optional<FIDO2ChallengeTable> result =
                repository.findByChallengeKey(testChallengeKey);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testDeleteWithValidKeyDeletesChallenge() {
        // When
        repository.delete(testChallengeKey);

        // Then
        verify(challengeTable).deleteItem(org.mockito.ArgumentMatchers.<Key>any());
    }

    @Test
    void testDeleteWithNullKeyDoesNothing() {
        // When
        repository.delete(null);

        // Then
        verify(challengeTable, never()).deleteItem(org.mockito.ArgumentMatchers.isA(Key.class));
    }

    @Test
    void testDeleteWithEmptyKeyDoesNothing() {
        // When
        repository.delete("");

        // Then
        verify(challengeTable, never()).deleteItem(org.mockito.ArgumentMatchers.isA(Key.class));
    }

    @Test
    void testGenerateChallengeKeyWithValidInputReturnsKey() {
        // When
        final String key =
                FIDO2ChallengeRepository.generateChallengeKey("user-123", "registration");

        // Then
        assertEquals("user-123:registration", key);
    }
}
