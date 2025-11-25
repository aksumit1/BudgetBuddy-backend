package com.budgetbuddy.api;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testRegister_Success() throws Exception {
        AuthRequest request = new AuthRequest();
        request.setEmail("newuser@example.com");
        request.setPasswordHash("hashed-password");
        request.setSalt("client-salt");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.user.id").exists());
    }

    @Test
    void testRegister_InvalidEmail() throws Exception {
        AuthRequest request = new AuthRequest();
        request.setEmail("invalid-email");
        request.setPasswordHash("hashed-password");
        request.setSalt("client-salt");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testLogin_Success() throws Exception {
        // First register a user
        AuthRequest registerRequest = new AuthRequest();
        registerRequest.setEmail("loginuser@example.com");
        registerRequest.setPasswordHash("hashed-password");
        registerRequest.setSalt("client-salt");

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk());

        // Then login
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail("loginuser@example.com");
        loginRequest.setPasswordHash("hashed-password");
        loginRequest.setSalt("client-salt");

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
        request.setPasswordHash("wrong-hash");
        request.setSalt("client-salt");

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}

