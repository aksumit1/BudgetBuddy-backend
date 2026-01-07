package com.budgetbuddy.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Authentication request DTO
 * Zero Trust: Client sends password_hash (client-side hashed), backend performs
 * additional server-side hashing with server salt only
 *
 * BREAKING CHANGE: Client salt removed - no backward compatibility
 */
public final class AuthRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    // Secure field - client-side hashed password (PBKDF2 with challenge nonce)
    // Accept both camelCase (passwordHash) and snake_case (password_hash) for compatibility
    @JsonProperty("passwordHash")
    @JsonAlias("password_hash")
    private String passwordHash;
    
    // Challenge nonce (PAKE2) - required for authentication/registration
    private String challenge;

    public AuthRequest() {
    }

    public AuthRequest(final String email, final String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }
    
    public AuthRequest(final String email, final String passwordHash, final String challenge) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.challenge = challenge;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(final String email) {
        this.email = email;
    }

    /**
     * Client-side hashed password (PBKDF2)
     * Backend will perform additional server-side hashing with server salt
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(final String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Challenge nonce for PAKE2 authentication
     */
    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(final String challenge) {
        this.challenge = challenge;
    }

    /**
     * Check if request uses secure format (password_hash only)
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSecureFormat() {
        return passwordHash != null && !passwordHash.isEmpty();
    }
}
