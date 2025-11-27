package com.budgetbuddy.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    // Secure fields - client-side hashed password
    // Accept both camelCase (passwordHash) and snake_case (password_hash) for compatibility
    @JsonProperty("passwordHash")
    @JsonAlias("password_hash")
    private String passwordHash;
    private String salt;

    public AuthRequest() {
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
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSecureFormat() {
        return passwordHash != null && !passwordHash.isEmpty()
               && salt != null && !salt.isEmpty();
    }
}
