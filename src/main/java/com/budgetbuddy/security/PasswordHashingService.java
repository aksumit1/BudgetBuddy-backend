package com.budgetbuddy.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
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
    public PasswordHashResult hashClientPassword(final String clientHash, final String clientSalt, final byte[] serverSalt) {
        if (clientHash == null || clientHash.isEmpty()) {
            throw new IllegalArgumentException("Client hash cannot be null or empty");
        }
        if (clientSalt == null || clientSalt.isEmpty()) {
            throw new IllegalArgumentException("Client salt cannot be null or empty");
        }

        try {
            // Generate server salt if not provided
            byte[] finalServerSalt = serverSalt != null ? serverSalt : generateSalt();

            // Decode client hash (client salt is not needed here - it was already used client-side)
            byte[] clientHashBytes = Base64.getDecoder().decode(clientHash);
            
            // Validate client salt format (must be valid Base64, but we don't use it in hashing)
            try {
                Base64.getDecoder().decode(clientSalt);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid client salt format: " + e.getMessage());
            }

            // CRITICAL FIX: Combine client hash with server salt using Base64 encoding
            // This prevents data corruption when converting binary to string
            // The original code was converting binary data directly to UTF-8 string, which can corrupt data
            // Convert both to Base64 strings, concatenate, then use as password input
            String clientHashBase64 = Base64.getEncoder().encodeToString(clientHashBytes);
            String serverSaltBase64 = Base64.getEncoder().encodeToString(finalServerSalt);
            String combinedInput = clientHashBase64 + ":" + serverSaltBase64; // Use separator to prevent collisions

            // Perform server-side PBKDF2 hashing
            // Use the combined Base64 string as the password input
            PBEKeySpec spec = new PBEKeySpec(
                    combinedInput.toCharArray(),
                    finalServerSalt,
                    ITERATIONS,
                    KEY_LENGTH
            );

            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] serverHash = factory.generateSecret(spec).getEncoded();

            return new PasswordHashResult(
                    Base64.getEncoder().encodeToString(serverHash),
                    Base64.getEncoder().encodeToString(finalServerSalt)
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
    public PasswordHashResult hashPlaintextPassword(final String plaintextPassword, final byte[] salt) {
        if (plaintextPassword == null || plaintextPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        try {
            // Generate salt if not provided
            byte[] finalSalt = salt != null ? salt : generateSalt();

            // Perform PBKDF2 hashing
            PBEKeySpec spec = new PBEKeySpec(
                    plaintextPassword.toCharArray(),
                    finalSalt,
                    ITERATIONS,
                    KEY_LENGTH
            );

            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();

            return new PasswordHashResult(
                    Base64.getEncoder().encodeToString(hash),
                    Base64.getEncoder().encodeToString(finalSalt)
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
    public boolean verifyClientPassword(final String clientHash, final String clientSalt, final String serverHash, final String serverSalt) {
        if (clientHash == null || clientHash.isEmpty() ||
            clientSalt == null || clientSalt.isEmpty() ||
            serverHash == null || serverHash.isEmpty() ||
            serverSalt == null || serverSalt.isEmpty()) {
            logger.warn("Password verification failed: missing required parameters (clientHash={}, clientSalt={}, serverHash={}, serverSalt={})",
                    clientHash != null && !clientHash.isEmpty(),
                    clientSalt != null && !clientSalt.isEmpty(),
                    serverHash != null && !serverHash.isEmpty(),
                    serverSalt != null && !serverSalt.isEmpty());
            return false;
        }

        try {
            // Validate Base64 encoding before decoding
            try {
                Base64.getDecoder().decode(clientHash);
                Base64.getDecoder().decode(clientSalt);
                Base64.getDecoder().decode(serverHash);
                Base64.getDecoder().decode(serverSalt);
            } catch (IllegalArgumentException e) {
                logger.error("Invalid Base64 encoding in password verification: {}", e.getMessage());
                return false;
            }

            // Hash the client password with the stored server salt (new method)
            byte[] serverSaltBytes = Base64.getDecoder().decode(serverSalt);
            PasswordHashResult result = hashClientPassword(clientHash, clientSalt, serverSaltBytes);

            // Constant-time comparison to prevent timing attacks
            boolean matches = constantTimeEquals(result.getHash(), serverHash);
            
            if (matches) {
                logger.debug("Password verification succeeded using new hashing method");
                return true;
            }
            
            // BACKWARD COMPATIBILITY: Try old method if new method fails
            // This handles users who registered before the fix
            logger.debug("New method failed, trying backward compatibility method");
            boolean oldMethodMatches = verifyClientPasswordLegacy(clientHash, clientSalt, serverHash, serverSalt);
            
            if (oldMethodMatches) {
                logger.info("Password verification succeeded using legacy method - user should reset password for security");
            } else {
                logger.debug("Password verification failed: computed hash does not match stored hash (both methods)");
            }
            
            return oldMethodMatches;
        } catch (IllegalArgumentException e) {
            logger.error("Base64 decoding error during password verification: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("Failed to verify password: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Legacy password verification method (for backward compatibility)
     * Uses the old binary-to-UTF-8 conversion method
     * @deprecated This method has a bug (binary to UTF-8 conversion can corrupt data) but is kept for backward compatibility
     */
    @Deprecated
    private boolean verifyClientPasswordLegacy(final String clientHash, final String clientSalt, final String serverHash, final String serverSalt) {
        try {
            byte[] clientHashBytes = Base64.getDecoder().decode(clientHash);
            byte[] serverSaltBytes = Base64.getDecoder().decode(serverSalt);
            
            // Old method: Combine binary data directly (can corrupt data if invalid UTF-8)
            byte[] combined = new byte[clientHashBytes.length + serverSaltBytes.length];
            System.arraycopy(clientHashBytes, 0, combined, 0, clientHashBytes.length);
            System.arraycopy(serverSaltBytes, 0, combined, clientHashBytes.length, serverSaltBytes.length);
            
            // Convert to string (may corrupt binary data)
            String combinedString;
            try {
                combinedString = new String(combined, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                // If UTF-8 conversion fails, try ISO-8859-1 (preserves all bytes)
                combinedString = new String(combined, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            
            // Perform PBKDF2 hashing
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    combinedString.toCharArray(),
                    serverSaltBytes,
                    ITERATIONS,
                    KEY_LENGTH
            );
            
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] computedHash = factory.generateSecret(spec).getEncoded();
            String computedHashBase64 = Base64.getEncoder().encodeToString(computedHash);
            
            return constantTimeEquals(computedHashBase64, serverHash);
        } catch (Exception e) {
            logger.debug("Legacy password verification failed: {}", e.getMessage());
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
    public boolean verifyPlaintextPassword(final String plaintextPassword, final String storedHash, final String storedSalt) {
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

