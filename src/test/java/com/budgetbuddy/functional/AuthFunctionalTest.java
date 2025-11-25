package com.budgetbuddy.functional;

import com.budgetbuddy.api.AuthController;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional Tests for Authentication API
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    private String testEmail;
    private String testPasswordHash;
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        testClientSalt = UUID.randomUUID().toString();
        testPasswordHash = "hashed-password-" + UUID.randomUUID();
    }

    @Test
    void testRegister_CompleteWorkflow() throws Exception {
        // Given
        String requestBody = String.format(
                "{\"email\":\"%s\",\"passwordHash\":\"%s\",\"salt\":\"%s\",\"firstName\":\"Test\",\"lastName\":\"User\"}",
                testEmail, testPasswordHash, testClientSalt
        );

        // When/Then
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated() || status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists());
    }

    @Test
    void testLogin_WithValidCredentials_Succeeds() throws Exception {
        // Given - Register first
        userService.createUserSecure(testEmail, testPasswordHash, testClientSalt, "Test", "User");

        String requestBody = String.format(
                "{\"email\":\"%s\",\"passwordHash\":\"%s\",\"salt\":\"%s\"}",
                testEmail, testPasswordHash, testClientSalt
        );

        // When/Then
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void testLogin_WithInvalidCredentials_Fails() throws Exception {
        // Given
        String requestBody = String.format(
                "{\"email\":\"%s\",\"passwordHash\":\"wrong-hash\",\"salt\":\"wrong-salt\"}",
                testEmail
        );

        // When/Then
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnauthorized() || status().isBadRequest());
    }

    @Test
    void testRefreshToken_WithValidToken_Succeeds() throws Exception {
        // Given - Register and login
        userService.createUserSecure(testEmail, testPasswordHash, testClientSalt, "Test", "User");
        AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(testPasswordHash);
        authRequest.setSalt(testClientSalt);
        AuthResponse authResponse = authService.authenticate(authRequest);
        String refreshToken = authResponse.getRefreshToken();

        // When/Then
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }
}

