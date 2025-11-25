package com.budgetbuddy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Authentication request DTO
 * Supports both client-side hashed passwords (secure) and plaintext
 * passwords (legacy)
 *
 * Security: Client sends password_hash and salt, backend performs
 * additional server-side hashing
 */
public final class AuthRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    // Legacy field - will be deprecated
    private String password;

    // New secure fields - client-side hashed password
    private String passwordHash;
    private String salt;

    public AuthRequest() {
    }

    public AuthRequest(final String email, final String password) {
        this.email = email;
        this.password = password;
    }

    public AuthRequest(final String email, final String passwordHash,
                       final String salt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.salt = salt;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(final String email) {
        this.email = email;
    }

    /**
     * Legacy password field - deprecated
     * @deprecated Use passwordHash and salt instead
     */
    @Deprecated
    public String getPassword() {
        return password;
    }

    @Deprecated
    public void setPassword(final String password) {
        this.password = password;
    }

    /**
     * Client-side hashed password (PBKDF2)
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(final String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Salt used for client-side hashing
     */
    public String getSalt() {
        return salt;
    }

    public void setSalt(final String salt) {
        this.salt = salt;
    }

    /**
     * Check if request uses secure format (password_hash + salt)
     */
    public boolean isSecureFormat() {
        return passwordHash != null && !passwordHash.isEmpty()
               && salt != null && !salt.isEmpty();
    }

    /**
     * Check if request uses legacy format (plaintext password)
     */
    public boolean isLegacyFormat() {
        return password != null && !password.isEmpty()
               && (passwordHash == null || passwordHash.isEmpty());
    }
}
