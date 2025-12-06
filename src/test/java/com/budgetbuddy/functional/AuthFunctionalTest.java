package com.budgetbuddy.functional;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.budgetbuddy.security.ddos.DDoSProtectionService;
import com.budgetbuddy.security.rate.RateLimitService;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional Tests for Authentication API
 * 
 */
@SpringBootTest(
    classes = com.budgetbuddy.BudgetBuddyApplication.class,
    webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AuthFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;
    
    // Note: @MockBean is deprecated in Spring Boot 3.4.0, but still functional
    // Suppressing deprecation warning as replacement API is not yet stable
    @SuppressWarnings("deprecation")
    @org.springframework.boot.test.mock.mockito.MockBean
    private RateLimitService rateLimitService;

    @SuppressWarnings("deprecation")
    @org.springframework.boot.test.mock.mockito.MockBean
    private DDoSProtectionService ddosProtectionService;
    
    private ObjectMapper getObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }
        return objectMapper;
    }

    private String testEmail;
    private String testPasswordHash;

    @BeforeEach
    void setUp() {
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        // Use base64-encoded value for password hash
        testPasswordHash = java.util.Base64.getEncoder().encodeToString(("hashed-password-" + UUID.randomUUID()).getBytes());
        
        // Ensure ObjectMapper has JavaTimeModule for Instant serialization
        ObjectMapper mapper = getObjectMapper();
        if (mapper.getRegisteredModuleIds().stream().noneMatch(id -> id.toString().contains("JavaTimeModule"))) {
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }
        // Mock rate limiting services to allow all requests in tests
        when(rateLimitService.isAllowed(anyString(), anyString())).thenReturn(true);
        when(ddosProtectionService.isAllowed(anyString())).thenReturn(true);
    }

    @Test
    void testRegister_CompleteWorkflow() throws Exception {
        // Given - Use new registration format (no salt, no firstName/lastName)
        // BREAKING CHANGE: Backend now only accepts email and password_hash
        String requestBody = String.format(
                "{\"email\":\"%s\",\"password_hash\":\"%s\"}",
                testEmail, testPasswordHash
        );

        // When/Then - Functional tests require LocalStack to be running
        // These are integration tests that test the full stack including DynamoDB
        var result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andReturn();
        
        int status = result.getResponse().getStatus();
        String responseBody = result.getResponse().getContentAsString();
        
        // Fail on 500 errors - tables should be initialized before tests run
        if (status == 500) {
            throw new AssertionError("Registration failed with 500 error: " + responseBody);
        }
        
        // Verify success (2xx status) - use the result we already have
        if (status >= 200 && status < 300) {
            // Verify response content using the result we already have
            org.junit.jupiter.api.Assertions.assertTrue(
                    responseBody.contains("accessToken") || responseBody.contains("\"accessToken\""),
                    "Response should contain accessToken: " + responseBody
            );
            org.junit.jupiter.api.Assertions.assertTrue(
                    responseBody.contains("refreshToken") || responseBody.contains("\"refreshToken\""),
                    "Response should contain refreshToken: " + responseBody
            );
        } else {
            // Otherwise, it's a real validation error
            throw new AssertionError("Registration failed with status " + status + ": " + responseBody);
        }
    }

    @Test
    void testLogin_WithValidCredentials_Succeeds() throws Exception {
        // Given - Register first - tables should be initialized before tests run
        // BREAKING CHANGE: firstName and lastName are optional (can be null)
        userService.createUserSecure(testEmail, testPasswordHash, null, null);

        // BREAKING CHANGE: Login now only requires email and password_hash (no salt)
        String requestBody = String.format(
                "{\"email\":\"%s\",\"password_hash\":\"%s\"}",
                testEmail, testPasswordHash
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
        // Given - Use new format (no salt)
        String requestBody = String.format(
                "{\"email\":\"%s\",\"password_hash\":\"wrong-hash\"}",
                testEmail
        );

        // When/Then - Should return 401 (Unauthorized) or 400 (Bad Request)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void testRefreshToken_WithValidToken_Succeeds() throws Exception {
        // Given - Register and login - tables should be initialized before tests run
        // BREAKING CHANGE: firstName and lastName are optional (can be null)
        userService.createUserSecure(testEmail, testPasswordHash, null, null);
        AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(testPasswordHash);
        AuthResponse authResponse = authService.authenticate(authRequest);
        String refreshToken = authResponse.getRefreshToken();

        // When/Then
        var result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn();
        
        int status = result.getResponse().getStatus();
        
        // Fail on 500 errors - tables should be initialized before tests run
        if (status == 500) {
            String errorBody = result.getResponse().getContentAsString();
            throw new AssertionError("Refresh token failed with 500 error: " + errorBody);
        }
        
        // Verify success
        String responseBody = result.getResponse().getContentAsString();
        if (status >= 200 && status < 300) {
            // Verify response content using the result we already have
            org.junit.jupiter.api.Assertions.assertTrue(
                    responseBody.contains("accessToken") || responseBody.contains("\"accessToken\""),
                    "Response should contain accessToken: " + responseBody
            );
        } else {
            throw new AssertionError("Refresh token failed with status " + status + ": " + responseBody);
        }
    }
}

