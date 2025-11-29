package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testRegister_Success() throws Exception {
        // Use unique email for each test run to avoid conflicts
        String uniqueEmail = "newuser" + System.currentTimeMillis() + "@example.com";
        AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        // Use proper base64-encoded strings
        request.setPasswordHash(java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes()));
        request.setSalt(java.util.Base64.getEncoder().encodeToString("client-salt".getBytes()));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.user.id").exists());
    }

    @Test
    void testRegister_InvalidEmail() throws Exception {
        AuthRequest request = new AuthRequest();
        request.setEmail("invalid-email");
        // Use proper base64-encoded strings
        request.setPasswordHash(java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes()));
        request.setSalt(java.util.Base64.getEncoder().encodeToString("client-salt".getBytes()));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testLogin_Success() throws Exception {
        // Use unique email for each test run to avoid conflicts
        String uniqueEmail = "loginuser" + System.currentTimeMillis() + "@example.com";
        // First register a user
        String passwordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String clientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());
        
        AuthRequest registerRequest = new AuthRequest();
        registerRequest.setEmail(uniqueEmail);
        registerRequest.setPasswordHash(passwordHash);
        registerRequest.setSalt(clientSalt);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Then login
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(uniqueEmail);
        loginRequest.setPasswordHash(passwordHash);
        loginRequest.setSalt(clientSalt);

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.user.id").exists());
    }

    @Test
    void testLogin_InvalidCredentials() throws Exception {
        AuthRequest request = new AuthRequest();
        request.setEmail("nonexistent@example.com");
        // Use proper base64-encoded strings
        request.setPasswordHash(java.util.Base64.getEncoder().encodeToString("wrong-hash".getBytes()));
        request.setSalt(java.util.Base64.getEncoder().encodeToString("client-salt".getBytes()));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}

