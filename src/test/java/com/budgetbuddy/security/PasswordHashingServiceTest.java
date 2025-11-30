package com.budgetbuddy.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for PasswordHashingService
 */
@ExtendWith(MockitoExtension.class)
class PasswordHashingServiceTest {

    @InjectMocks
    private PasswordHashingService passwordHashingService;

    private String testClientHash;
    private String testPlaintextPassword;

    @BeforeEach
    void setUp() {
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // Create test client hash (simulating client-side hashing)
        byte[] testHash = new byte[32];
        testHash[0] = 1; // Non-zero for testing
        testClientHash = Base64.getEncoder().encodeToString(testHash);

        testPlaintextPassword = "TestPassword123!";
    }

    @Test
    void testHashClientPassword_WithValidInput_ReturnsHash() {
        // When - BREAKING CHANGE: Client salt removed, only server salt is used
        PasswordHashingService.PasswordHashResult result =
                passwordHashingService.hashClientPassword(testClientHash, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getHash());
        assertNotNull(result.getSalt());
        assertFalse(result.getHash().isEmpty());
        assertFalse(result.getSalt().isEmpty());
    }

    @Test
    void testHashClientPassword_WithNullClientHash_ThrowsException() {
        // When/Then - BREAKING CHANGE: Client salt removed
        assertThrows(IllegalArgumentException.class, () -> {
            passwordHashingService.hashClientPassword(null, null);
        });
    }

    @Test
    void testHashClientPassword_WithEmptyClientHash_ThrowsException() {
        // When/Then - BREAKING CHANGE: Client salt removed
        assertThrows(IllegalArgumentException.class, () -> {
            passwordHashingService.hashClientPassword("", null);
        });
    }

    @Test
    void testHashClientPassword_WithProvidedServerSalt_UsesProvidedSalt() {
        // Given - BREAKING CHANGE: Client salt removed, only server salt is used
        byte[] providedSalt = new byte[16];
        providedSalt[0] = 99;

        // When
        PasswordHashingService.PasswordHashResult result =
                passwordHashingService.hashClientPassword(testClientHash, providedSalt);

        // Then
        String providedSaltBase64 = Base64.getEncoder().encodeToString(providedSalt);
        assertEquals(providedSaltBase64, result.getSalt());
    }


    @Test
    void testHashPlaintextPassword_WithValidPassword_ReturnsHash() {
        // When
        PasswordHashingService.PasswordHashResult result =
                passwordHashingService.hashPlaintextPassword(testPlaintextPassword, null);

        // Then
        assertNotNull(result);
        assertNotNull(result.getHash());
        assertNotNull(result.getSalt());
    }

    @Test
    void testHashPlaintextPassword_WithNullPassword_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            passwordHashingService.hashPlaintextPassword(null, null);
        });
    }

    @Test
    void testHashPlaintextPassword_WithEmptyPassword_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            passwordHashingService.hashPlaintextPassword("", null);
        });
    }

    @Test
    void testVerifyClientPassword_WithValidPassword_ReturnsTrue() {
        // Given - BREAKING CHANGE: Client salt removed
        PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashClientPassword(testClientHash, null);

        // When
        boolean isValid = passwordHashingService.verifyClientPassword(
                testClientHash, stored.getHash(), stored.getSalt());

        // Then
        assertTrue(isValid);
    }

    @Test
    void testVerifyClientPassword_WithInvalidPassword_ReturnsFalse() {
        // Given - BREAKING CHANGE: Client salt removed
        PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashClientPassword(testClientHash, null);
        String wrongClientHash = Base64.getEncoder().encodeToString(new byte[32]);

        // When
        boolean isValid = passwordHashingService.verifyClientPassword(
                wrongClientHash, stored.getHash(), stored.getSalt());

        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyClientPassword_WithNullInputs_ReturnsFalse() {
        // When/Then - BREAKING CHANGE: Client salt removed
        assertFalse(passwordHashingService.verifyClientPassword(null, "hash", "salt"));
        assertFalse(passwordHashingService.verifyClientPassword(testClientHash, null, "salt"));
        assertFalse(passwordHashingService.verifyClientPassword(testClientHash, "hash", null));
    }

    @Test
    void testVerifyPlaintextPassword_WithValidPassword_ReturnsTrue() {
        // Given
        PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashPlaintextPassword(testPlaintextPassword, null);

        // When
        boolean isValid = passwordHashingService.verifyPlaintextPassword(
                testPlaintextPassword, stored.getHash(), stored.getSalt());

        // Then
        assertTrue(isValid);
    }

    @Test
    void testVerifyPlaintextPassword_WithInvalidPassword_ReturnsFalse() {
        // Given
        PasswordHashingService.PasswordHashResult stored =
                passwordHashingService.hashPlaintextPassword(testPlaintextPassword, null);

        // When
        boolean isValid = passwordHashingService.verifyPlaintextPassword(
                "WrongPassword", stored.getHash(), stored.getSalt());

        // Then
        assertFalse(isValid);
    }

    @Test
    void testGenerateSalt_ReturnsRandomSalt() {
        // When
        byte[] salt1 = passwordHashingService.generateSalt();
        byte[] salt2 = passwordHashingService.generateSalt();

        // Then
        assertNotNull(salt1);
        assertNotNull(salt2);
        assertEquals(16, salt1.length);
        assertEquals(16, salt2.length);
        // Salts should be different (very high probability)
        assertFalse(java.util.Arrays.equals(salt1, salt2));
    }

    @Test
    void testHashClientPassword_WithSameInput_ProducesDifferentHashes() {
        // Given - BREAKING CHANGE: Client salt removed, same client hash but different server salts
        PasswordHashingService.PasswordHashResult result1 =
                passwordHashingService.hashClientPassword(testClientHash, null);
        PasswordHashingService.PasswordHashResult result2 =
                passwordHashingService.hashClientPassword(testClientHash, null);

        // Then - Should produce different hashes (due to different server salts)
        assertNotEquals(result1.getHash(), result2.getHash());
        assertNotEquals(result1.getSalt(), result2.getSalt());
    }
}

