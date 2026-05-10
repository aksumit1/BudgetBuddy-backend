package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for ChallengeService - PAKE2 challenge-response authentication */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@DisplayName("ChallengeService Tests")
class ChallengeServiceTest {

    private ChallengeService challengeService;
    private String testEmail;

    @BeforeEach
    void setUp() {
        challengeService = new ChallengeService();
        testEmail = "test@example.com";
    }

    @Test
    @DisplayName("Should generate challenge for valid email")
    void testGenerateChallengeSuccess() {
        // When
        final ChallengeService.ChallengeResponse response =
                challengeService.generateChallenge(testEmail);

        // Then
        assertNotNull(response);
        assertNotNull(response.getChallenge());
        assertFalse(response.getChallenge().isEmpty());
        assertNotNull(response.getExpiresAt());
        assertTrue(response.getExpiresAt().isAfter(Instant.now()));
        assertTrue(
                response.getExpiresAt()
                        .isBefore(
                                Instant.now()
                                        .plus(6, ChronoUnit.MINUTES))); // Within 6 minutes (5 min +
        // buffer)
    }

    @Test
    @DisplayName("Should generate unique challenges for same email")
    void testGenerateChallengeUniqueNonces() {
        // When
        final ChallengeService.ChallengeResponse response1 =
                challengeService.generateChallenge(testEmail);
        final ChallengeService.ChallengeResponse response2 =
                challengeService.generateChallenge(testEmail);

        // Then
        assertNotEquals(
                response1.getChallenge(), response2.getChallenge(), "Challenges should be unique");
    }

    @Test
    @DisplayName("Should throw exception for null email")
    void testGenerateChallengeNullEmail() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.generateChallenge(null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Email is required"));
    }

    @Test
    @DisplayName("Should throw exception for empty email")
    void testGenerateChallengeEmptyEmail() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.generateChallenge("");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Email is required"));
    }

    @Test
    @DisplayName("Should verify and consume valid challenge")
    void testVerifyAndConsumeChallengeSuccess() {
        // Given
        final ChallengeService.ChallengeResponse response =
                challengeService.generateChallenge(testEmail);
        final String challenge = response.getChallenge();

        // When
        assertDoesNotThrow(
                () -> {
                    challengeService.verifyAndConsumeChallenge(challenge, testEmail);
                });
    }

    @Test
    @DisplayName("Should verify challenge with case-insensitive email")
    void testVerifyAndConsumeChallengeCaseInsensitiveEmail() {
        // Given
        final ChallengeService.ChallengeResponse response =
                challengeService.generateChallenge("Test@Example.COM");
        final String challenge = response.getChallenge();

        // When/Then - Should accept any case variation
        assertDoesNotThrow(
                () -> {
                    challengeService.verifyAndConsumeChallenge(challenge, "test@example.com");
                });
        assertDoesNotThrow(
                () -> {
                    final ChallengeService.ChallengeResponse response2 =
                            challengeService.generateChallenge("test@example.com");
                    challengeService.verifyAndConsumeChallenge(
                            response2.getChallenge(), "TEST@EXAMPLE.COM");
                });
    }

    @Test
    @DisplayName("Should throw exception for null challenge")
    void testVerifyAndConsumeChallengeNullChallenge() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.verifyAndConsumeChallenge(null, testEmail);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Challenge is required"));
    }

    @Test
    @DisplayName("Should throw exception for empty challenge")
    void testVerifyAndConsumeChallengeEmptyChallenge() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.verifyAndConsumeChallenge("", testEmail);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Challenge is required"));
    }

    @Test
    @DisplayName("Should throw exception for null email in verify")
    void testVerifyAndConsumeChallengeNullEmail() {
        // Given
        final ChallengeService.ChallengeResponse response =
                challengeService.generateChallenge(testEmail);
        final String challenge = response.getChallenge();

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.verifyAndConsumeChallenge(challenge, null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Email is required"));
    }

    @Test
    @DisplayName("Should throw exception for invalid challenge")
    void testVerifyAndConsumeChallengeInvalidChallenge() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.verifyAndConsumeChallenge(
                                    "invalid_challenge", testEmail);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Invalid or expired challenge"));
    }

    @Test
    @DisplayName("Should throw exception for email mismatch")
    void testVerifyAndConsumeChallengeEmailMismatch() {
        // Given
        final ChallengeService.ChallengeResponse response =
                challengeService.generateChallenge(testEmail);
        final String challenge = response.getChallenge();
        final String differentEmail = "different@example.com";

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.verifyAndConsumeChallenge(challenge, differentEmail);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Challenge email mismatch"));
    }

    @Test
    @DisplayName("Should throw exception when reusing challenge (one-time use)")
    void testVerifyAndConsumeChallengeOneTimeUse() {
        // Given
        final ChallengeService.ChallengeResponse response =
                challengeService.generateChallenge(testEmail);
        final String challenge = response.getChallenge();

        // When - First verification should succeed
        assertDoesNotThrow(
                () -> {
                    challengeService.verifyAndConsumeChallenge(challenge, testEmail);
                });

        // Then - Second verification should fail (challenge consumed)
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            challengeService.verifyAndConsumeChallenge(challenge, testEmail);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Invalid or expired challenge"));
    }

    @Test
    @DisplayName("Should generate challenges with proper expiration time")
    void testGenerateChallengeExpirationTime() {
        // When
        final Instant beforeGeneration = Instant.now();
        final ChallengeService.ChallengeResponse response =
                challengeService.generateChallenge(testEmail);
        final Instant afterGeneration = Instant.now();

        // Then
        final Instant expiresAt = response.getExpiresAt();
        assertTrue(
                expiresAt.isAfter(beforeGeneration.plus(4, ChronoUnit.MINUTES)),
                "Expiration should be at least 4 minutes in the future");
        assertTrue(
                expiresAt.isBefore(afterGeneration.plus(6, ChronoUnit.MINUTES)),
                "Expiration should be less than 6 minutes in the future");
    }

    @Test
    @DisplayName("Should handle concurrent challenge generation")
    void testGenerateChallengeConcurrentGeneration() throws InterruptedException {
        // Given
        final int threadCount = 10;
        final Thread[] threads = new Thread[threadCount];
        final ChallengeService.ChallengeResponse[] responses =
                new ChallengeService.ChallengeResponse[threadCount];

        // When
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] =
                    new Thread(
                            () -> {
                                responses[index] =
                                        challengeService.generateChallenge(testEmail + index);
                            });
            threads[i].start();
        }

        for (final Thread thread : threads) {
            thread.join();
        }

        // Then - All challenges should be unique
        for (int i = 0; i < threadCount; i++) {
            assertNotNull(responses[i]);
            for (int j = i + 1; j < threadCount; j++) {
                assertNotEquals(
                        responses[i].getChallenge(),
                        responses[j].getChallenge(),
                        "Challenges should be unique across threads");
            }
        }
    }
}
