package com.budgetbuddy.dto;

import java.time.LocalDateTime;

/**
 * Authentication response DTO
 */
public final class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private LocalDateTime expiresAt;
    private UserInfo user;

    public AuthResponse() {
    }

    public AuthResponse(final String accessToken, final String refreshToken,
                        final LocalDateTime expiresAt, final UserInfo user) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
        this.user = user;
    }

    // Getters and Setters
    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(final String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(final String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(final String tokenType) {
        this.tokenType = tokenType;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(final LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public UserInfo getUser() {
        return user;
    }

    public void setUser(final UserInfo user) {
        this.user = user;
    }

    public static final class UserInfo {
        private String id; // Changed to String for DynamoDB
        private String email;
        private String firstName;
        private String lastName;

        public UserInfo() {
        }

        public UserInfo(final String id, final String email,
                        final String firstName, final String lastName) {
            this.id = id;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
        }

        // Getters and Setters
        public String getId() {
            return id;
        }

        public void setId(final String id) {
            this.id = id;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(final String email) {
            this.email = email;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(final String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(final String lastName) {
            this.lastName = lastName;
        }
    }
}

