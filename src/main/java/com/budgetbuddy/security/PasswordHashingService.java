package com.budgetbuddy.security;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Password Hashing Service Implements PBKDF2 with SHA-256 for secure password hashing Supports both
 * client-side hashed passwords (defense in depth) and legacy plaintext
 *
 * <p>Security: Performs server-side hashing on client-side hashed passwords for additional security
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class PasswordHashingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordHashingService.class);

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100_000; // Match client-side iterations
    private static final int KEY_LENGTH = 256; // 32 bytes = 256 bits
    private static final int SALT_LENGTH = 16; // 16 bytes = 128 bits

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Hash a client-side hashed password with server salt (defense in depth) BREAKING CHANGE:
     * Client salt removed - Zero Trust architecture
     *
     * @param clientHash Base64-encoded client-side PBKDF2 hash
     * @param serverSalt Server-side salt (will be generated if null)
     * @return Server-side hash and salt
     */
    public PasswordHashResult hashClientPassword(final String clientHash, final byte[] serverSalt) {
        if (clientHash == null || clientHash.isEmpty()) {
            throw new IllegalArgumentException("Client hash cannot be null or empty");
        }

        try {
            // Generate server salt if not provided
            final byte[] finalServerSalt = serverSalt != null ? serverSalt : generateSalt();

            // Decode client hash
            final byte[] clientHashBytes = Base64.getDecoder().decode(clientHash);

            // Combine client hash with server salt using Base64 encoding
            // This prevents data corruption when converting binary to string
            final String clientHashBase64 = Base64.getEncoder().encodeToString(clientHashBytes);
            final String serverSaltBase64 = Base64.getEncoder().encodeToString(finalServerSalt);
            final String combinedInput =
                    clientHashBase64
                            + ":"
                            + serverSaltBase64; // Use separator to prevent collisions

            // Perform server-side PBKDF2 hashing
            // Use the combined Base64 string as the password input
            final PBEKeySpec spec =
                    new PBEKeySpec(
                            combinedInput.toCharArray(), finalServerSalt, ITERATIONS, KEY_LENGTH);

            final SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            final byte[] serverHash = factory.generateSecret(spec).getEncoded();

            return new PasswordHashResult(
                    Base64.getEncoder().encodeToString(serverHash),
                    Base64.getEncoder().encodeToString(finalServerSalt));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            LOGGER.error("Failed to hash password", e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Password hashing failed", e);
        }
    }

    /**
     * Hash a plaintext password (legacy support)
     *
     * @param plaintextPassword Plaintext password
     * @param salt Salt (will be generated if null)
     * @return Hash and salt
     */
    public PasswordHashResult hashPlaintextPassword(
            final String plaintextPassword, final byte[] salt) {
        if (plaintextPassword == null || plaintextPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        try {
            // Generate salt if not provided
            final byte[] finalSalt = salt != null ? salt : generateSalt();

            // Perform PBKDF2 hashing
            final PBEKeySpec spec =
                    new PBEKeySpec(
                            plaintextPassword.toCharArray(), finalSalt, ITERATIONS, KEY_LENGTH);

            final SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            final byte[] hash = factory.generateSecret(spec).getEncoded();

            return new PasswordHashResult(
                    Base64.getEncoder().encodeToString(hash),
                    Base64.getEncoder().encodeToString(finalSalt));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            LOGGER.error("Failed to hash password", e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Password hashing failed", e);
        }
    }

    /**
     * Verify a client-side hashed password against stored server hash (standard method) BREAKING
     * CHANGE: Client salt removed - Zero Trust architecture
     *
     * @param clientHash Base64-encoded client-side hash
     * @param serverHash Base64-encoded stored server hash
     * @param serverSalt Base64-encoded stored server salt
     * @return true if password matches
     */
    public boolean verifyClientPassword(
            final String clientHash, final String serverHash, final String serverSalt) {
        if (clientHash == null
                || clientHash.isEmpty()
                || serverHash == null
                || serverHash.isEmpty()
                || serverSalt == null
                || serverSalt.isEmpty()) {
            LOGGER.warn(
                    "Password verification failed: missing required parameters (clientHash={}, serverHash={}, serverSalt={})",
                    clientHash != null && !clientHash.isEmpty(),
                    serverHash != null && !serverHash.isEmpty(),
                    serverSalt != null && !serverSalt.isEmpty());
            return false;
        }

        try {
            // Validate Base64 encoding before decoding
            try {
                Base64.getDecoder().decode(clientHash);
                Base64.getDecoder().decode(serverHash);
                Base64.getDecoder().decode(serverSalt);
            } catch (IllegalArgumentException e) {
                LOGGER.error(
                        "Invalid Base64 encoding in password verification: {}", e.getMessage());
                return false;
            }

            // Hash the client password with the stored server salt
            final byte[] serverSaltBytes = Base64.getDecoder().decode(serverSalt);
            final PasswordHashResult result = hashClientPassword(clientHash, serverSaltBytes);

            // Constant-time comparison to prevent timing attacks
            final boolean matches = constantTimeEquals(result.getHash(), serverHash);

            if (matches) {
                LOGGER.debug("Password verification succeeded");
            } else {
                LOGGER.debug(
                        "Password verification failed: computed hash does not match stored hash");
            }

            return matches;
        } catch (IllegalArgumentException e) {
            LOGGER.error("Base64 decoding error during password verification: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("Failed to verify password: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Verify a plaintext password against stored hash (legacy support)
     *
     * @param plaintextPassword Plaintext password
     * @param storedHash Base64-encoded stored hash
     * @param storedSalt Base64-encoded stored salt
     * @return true if password matches
     */
    public boolean verifyPlaintextPassword(
            final String plaintextPassword, final String storedHash, final String storedSalt) {
        if (plaintextPassword == null
                || plaintextPassword.isEmpty()
                || storedHash == null
                || storedHash.isEmpty()
                || storedSalt == null
                || storedSalt.isEmpty()) {
            return false;
        }

        try {
            final byte[] saltBytes = Base64.getDecoder().decode(storedSalt);
            final PasswordHashResult result = hashPlaintextPassword(plaintextPassword, saltBytes);

            // Constant-time comparison to prevent timing attacks
            return constantTimeEquals(result.getHash(), storedHash);
        } catch (Exception e) {
            LOGGER.error("Failed to verify password", e);
            return false;
        }
    }

    /** Generate a random salt */
    public byte[] generateSalt() {
        final byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);
        return salt;
    }

    /** Constant-time string comparison to prevent timing attacks */
    private boolean constantTimeEquals(final String a, final String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length() != b.length()) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /** Password hash result */
    public static class PasswordHashResult {
        private final String hash;
        private final String salt;

        public PasswordHashResult(final String hash, final String salt) {
            this.hash = hash;
            this.salt = salt;
        }

        public String getHash() {
            return hash;
        }

        public String getSalt() {
            return salt;
        }
    }
}
