package com.budgetbuddy.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password Hashing Service
 * Implements PBKDF2 with SHA-256 for secure password hashing
 * Supports both client-side hashed passwords (defense in depth) and legacy plaintext
 *
 * Security: Performs server-side hashing on client-side hashed passwords for additional security
 */
@Service
public class PasswordHashingService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordHashingService.class);

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100_000; // Match client-side iterations
    private static final int KEY_LENGTH = 256; // 32 bytes = 256 bits
    private static final int SALT_LENGTH = 16; // 16 bytes = 128 bits

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Hash a client-side hashed password with server salt (defense in depth)
     *
     * @param clientHash Base64-encoded client-side PBKDF2 hash
     * @param clientSalt Base64-encoded client-side salt
     * @param serverSalt Server-side salt (will be generated if null)
     * @return Server-side hash and salt
     */
    public PasswordHashResult hashClientPassword((final String clientHash, final String clientSalt, final byte[] serverSalt) {
        if (clientHash == null || clientHash.isEmpty()) {
            throw new IllegalArgumentException("Client hash cannot be null or empty");
        }
        if (clientSalt == null || clientSalt.isEmpty()) {
            throw new IllegalArgumentException("Client salt cannot be null or empty");
        }

        try {
            // Generate server salt if not provided
            if (serverSalt == null) {
                serverSalt = generateSalt();
            }

            // Decode client hash and salt
            byte[] clientHashBytes = Base64.getDecoder().decode(clientHash);
            byte[] clientSaltBytes = Base64.getDecoder().decode(clientSalt);

            // Combine client hash with server salt for additional security
            byte[] combined = new byte[clientHashBytes.length + serverSalt.length];
            System.arraycopy(clientHashBytes, 0, combined, 0, clientHashBytes.length);
            System.arraycopy(serverSalt, 0, combined, clientHashBytes.length, serverSalt.length);

            // Perform server-side PBKDF2 hashing
            PBEKeySpec spec = new PBEKeySpec(
                    new String(combined, StandardCharsets.UTF_8).toCharArray(),
                    serverSalt,
                    ITERATIONS,
                    KEY_LENGTH
            );

            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] serverHash = factory.generateSecret(spec).getEncoded();

            return new PasswordHashResult(
                    Base64.getEncoder().encodeToString(serverHash),
                    Base64.getEncoder().encodeToString(serverSalt)
            );
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            logger.error("Failed to hash password", e);
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    /**
     * Hash a plaintext password (legacy support)
     *
     * @param plaintextPassword Plaintext password
     * @param salt Salt (will be generated if null)
     * @return Hash and salt
     */
    public PasswordHashResult hashPlaintextPassword((final String plaintextPassword, final byte[] salt) {
        if (plaintextPassword == null || plaintextPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        try {
            // Generate salt if not provided
            if (salt == null) {
                salt = generateSalt();
            }

            // Perform PBKDF2 hashing
            PBEKeySpec spec = new PBEKeySpec(
                    plaintextPassword.toCharArray(),
                    salt,
                    ITERATIONS,
                    KEY_LENGTH
            );

            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();

            return new PasswordHashResult(
                    Base64.getEncoder().encodeToString(hash),
                    Base64.getEncoder().encodeToString(salt)
            );
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            logger.error("Failed to hash password", e);
            throw new RuntimeException("Password hashing failed", e);
        }
    }

    /**
     * Verify a client-side hashed password against stored server hash
     *
     * @param clientHash Base64-encoded client-side hash
     * @param clientSalt Base64-encoded client-side salt
     * @param serverHash Base64-encoded stored server hash
     * @param serverSalt Base64-encoded stored server salt
     * @return true if password matches
     */
    public boolean verifyClientPassword((final String clientHash, final String clientSalt, final String serverHash, final String serverSalt) {
        if (clientHash == null || clientHash.isEmpty() ||
            clientSalt == null || clientSalt.isEmpty() ||
            serverHash == null || serverHash.isEmpty() ||
            serverSalt == null || serverSalt.isEmpty()) {
            return false;
        }

        try {
            // Hash the client password with the stored server salt
            byte[] serverSaltBytes = Base64.getDecoder().decode(serverSalt);
            PasswordHashResult result = hashClientPassword(clientHash, clientSalt, serverSaltBytes);

            // Constant-time comparison to prevent timing attacks
            return constantTimeEquals(result.getHash(), serverHash);
        } catch (Exception e) {
            logger.error("Failed to verify password", e);
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
    public boolean verifyPlaintextPassword((final String plaintextPassword, final String storedHash, final String storedSalt) {
        if (plaintextPassword == null || plaintextPassword.isEmpty() ||
            storedHash == null || storedHash.isEmpty() ||
            storedSalt == null || storedSalt.isEmpty()) {
            return false;
        }

        try {
            byte[] saltBytes = Base64.getDecoder().decode(storedSalt);
            PasswordHashResult result = hashPlaintextPassword(plaintextPassword, saltBytes);

            // Constant-time comparison to prevent timing attacks
            return constantTimeEquals(result.getHash(), storedHash);
        } catch (Exception e) {
            logger.error("Failed to verify password", e);
            return false;
        }
    }

    /**
     * Generate a random salt
     */
    public byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);
        return salt;
    }

    /**
     * Constant-time string comparison to prevent timing attacks
     */
    private boolean constantTimeEquals((final String a, final String b) {
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

    /**
     * Password hash result
     */
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

