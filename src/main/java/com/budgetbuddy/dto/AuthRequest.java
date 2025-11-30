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

    // Secure field - client-side hashed password (PBKDF2)
    // Accept both camelCase (passwordHash) and snake_case (password_hash) for compatibility
    @JsonProperty("passwordHash")
    @JsonAlias("password_hash")
    private String passwordHash;

    public AuthRequest() {
    }

    public AuthRequest(final String email, final String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
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
     * Check if request uses secure format (password_hash only)
     * BREAKING CHANGE: No longer requires salt
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSecureFormat() {
        return passwordHash != null && !passwordHash.isEmpty();
    }
}
