package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for PasswordHashingService */
@ExtendWith(MockitoExtension.class)
class PasswordHashingServiceTest {

    @InjectMocks private PasswordHashingService passwordHashingService;

    private String testClientHash;
    private String testPlaintextPassword;

    @BeforeEach
    void setUp() {
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // Create test client hash (simulating client-side hashing)
        final byte[] testHash = new byte[32];
        testHash[0] = 1; // Non-zero for testing
        testClientHash = Base64.getEncoder().encodeToString(testHash);

        testPlaintextPassword = "TestPassword123!";
    }

    @Test
    void testHashClientPasswordWithValidInputReturnsHash() {
        // When - BREAKING CHANGE: Client salt removed, only server salt is used
        final PasswordHashingService.PasswordHashResult result =
                passwordHashingService.hashClientPassword(testClientHash, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getHash());
        assertNotNull(result.getSalt());
        assertFalse(result.getHash().isEmpty());
        assertFalse(result.getSalt().isEmpty());
    }

    @Test
    void testHashClientPasswordWithNullClientHashThrowsException() {
        // When/Then - BREAKING CHANGE: Client salt removed
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    passwordHashingService.hashClientPassword(null, null);
                });
    }

    @Test
    void testHashClientPasswordWithEmptyClientHashThrowsException() {
        // When/Then - BREAKING CHANGE: Client salt removed
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    passwordHashingService.hashClientPassword("", null);
                });
    }

    @Test
    void testHashClientPasswordWithProvidedServerSaltUsesProvidedSalt() {
        // Given - BREAKING CHANGE: Client salt removed, only server salt is used
        final byte[] providedSalt = new byte[16];
        providedSalt[0] = 99;

        // When
        final PasswordHashingService.PasswordHashResult result =
                passwordHashingService.hashClientPassword(testClientHash, providedSalt);

        // Then
        final String providedSaltBase64 = Base64.getEncoder().encodeToString(providedSalt);
        assertEquals(providedSaltBase64, result.getSalt());
    }

    @Test
    void testHashPlaintextPasswordWithValidPasswordReturnsHash() {
        // When
        final PasswordHashingService.PasswordHashResult result =
                passwordHashingService.hashPlaintextPassword(testPlaintextPassword, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getHash());
        assertNotNull(result.getSalt());
    }

    @Test
    void testHashPlaintextPasswordWithNullPasswordThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    passwordHashingService.hashPlaintextPassword(null, null);
                });
    }

    @Test
    void testHashPlaintextPasswordWithEmptyPasswordThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    passwordHashingService.hashPlaintextPassword("", null);
                });
    }

    @Test
    void testVerifyClientPasswordWithValidPasswordReturnsTrue() {
        // Given - BREAKING CHANGE: Client salt removed
        final PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashClientPassword(testClientHash, null);

        // When
        final boolean isValid =
                passwordHashingService.verifyClientPassword(
                        testClientHash, stored.getHash(), stored.getSalt());

        // Then
        assertTrue(isValid);
    }

    @Test
    void testVerifyClientPasswordWithInvalidPasswordReturnsFalse() {
        // Given - BREAKING CHANGE: Client salt removed
        final PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashClientPassword(testClientHash, null);
        final String wrongClientHash = Base64.getEncoder().encodeToString(new byte[32]);

        // When
        final boolean isValid =
                passwordHashingService.verifyClientPassword(
                        wrongClientHash, stored.getHash(), stored.getSalt());

        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyClientPasswordWithNullInputsReturnsFalse() {
        // When/Then - BREAKING CHANGE: Client salt removed
        assertFalse(passwordHashingService.verifyClientPassword(null, "hash", "salt"));
        assertFalse(passwordHashingService.verifyClientPassword(testClientHash, null, "salt"));
        assertFalse(passwordHashingService.verifyClientPassword(testClientHash, "hash", null));
    }

    @Test
    void testVerifyPlaintextPasswordWithValidPasswordReturnsTrue() {
        // Given
        final PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashPlaintextPassword(testPlaintextPassword, null);

        // When
        final boolean isValid =
                passwordHashingService.verifyPlaintextPassword(
                        testPlaintextPassword, stored.getHash(), stored.getSalt());

        // Then
        assertTrue(isValid);
    }

    @Test
    void testVerifyPlaintextPasswordWithInvalidPasswordReturnsFalse() {
        // Given
        final PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashPlaintextPassword(testPlaintextPassword, null);

        // When
        final boolean isValid =
                passwordHashingService.verifyPlaintextPassword(
                        "WrongPassword", stored.getHash(), stored.getSalt());

        // Then
        assertFalse(isValid);
    }

    @Test
    void testGenerateSaltReturnsRandomSalt() {
        // When
        final byte[] salt1 = passwordHashingService.generateSalt();
        final byte[] salt2 = passwordHashingService.generateSalt();

        // Then
        assertNotNull(salt1);
        assertNotNull(salt2);
        assertEquals(16, salt1.length);
        assertEquals(16, salt2.length);
        // Salts should be different (very high probability)
        assertFalse(java.util.Arrays.equals(salt1, salt2));
    }

    @Test
    void testHashClientPasswordWithSameInputProducesDifferentHashes() {
        // Given - BREAKING CHANGE: Client salt removed, same client hash but different server salts
        final PasswordHashingService.PasswordHashResult result1 =
                passwordHashingService.hashClientPassword(testClientHash, null);
        final PasswordHashingService.PasswordHashResult result2 =
                passwordHashingService.hashClientPassword(testClientHash, null);

        // Then - Should produce different hashes (due to different server salts)
        assertNotEquals(result1.getHash(), result2.getHash());
        assertNotEquals(result1.getSalt(), result2.getSalt());
    }
}
