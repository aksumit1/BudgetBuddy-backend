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
    
    @org.springframework.boot.test.mock.mockito.MockBean
    private RateLimitService rateLimitService;

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
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        // Use base64-encoded values for password hash and salt
        testClientSalt = java.util.Base64.getEncoder().encodeToString((UUID.randomUUID().toString()).getBytes());
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
        // Given
        String requestBody = String.format(
                "{\"email\":\"%s\",\"passwordHash\":\"%s\",\"salt\":\"%s\",\"firstName\":\"Test\",\"lastName\":\"User\"}",
                testEmail, testPasswordHash, testClientSalt
        );

        // When/Then - Functional tests require LocalStack to be running
        // These are integration tests that test the full stack including DynamoDB
        try {
            var result = mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andReturn();
            
            int status = result.getResponse().getStatus();
            String responseBody = result.getResponse().getContentAsString();
            
            // Check if it's an infrastructure error (500)
            if (status == 500) {
                // Skip test if it's a DynamoDB/connection error
                if (responseBody.contains("DynamoDB") || responseBody.contains("Connection") || 
                    responseBody.contains("LocalStack") || responseBody.contains("endpoint") ||
                    responseBody.contains("ResourceNotFoundException")) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(
                            false,
                            "Test requires DynamoDB/LocalStack to be running. Got 500 error: " + responseBody
                    );
                }
                // Other 500 errors are real failures
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
                // Validation error (400) or other client error - check if it's expected
                // For functional tests, 400 might be due to missing validation or infrastructure
                if (status == 400 && (responseBody.contains("DynamoDB") || 
                    responseBody.contains("Connection") || responseBody.contains("endpoint"))) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(
                            false,
                            "Test requires DynamoDB/LocalStack to be running. Got 400 error: " + responseBody
                    );
                }
                // Otherwise, it's a real validation error
                throw new AssertionError("Registration failed with status " + status + ": " + responseBody);
            }
        } catch (AssertionError e) {
            // Re-throw assertion errors as-is
            throw e;
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available), skip it
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            Throwable cause = e.getCause();
            String causeMsg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "";
            
            if (errorMsg.contains("DynamoDB") || errorMsg.contains("LocalStack") || 
                errorMsg.contains("Connection") || errorMsg.contains("endpoint") ||
                errorMsg.contains("ResourceNotFoundException") ||
                causeMsg.contains("DynamoDB") || causeMsg.contains("Connection") ||
                causeMsg.contains("endpoint") || causeMsg.contains("ResourceNotFoundException")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping functional test: " + errorMsg
                );
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }

    @Test
    void testLogin_WithValidCredentials_Succeeds() throws Exception {
        // Given - Register first (skip if infrastructure not available)
        try {
            userService.createUserSecure(testEmail, testPasswordHash, testClientSalt, "Test", "User");
        } catch (Exception e) {
            // If user creation fails due to infrastructure, skip test
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            Throwable cause = e.getCause();
            String causeMsg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "";
            
            if (errorMsg.contains("DynamoDB") || errorMsg.contains("LocalStack") || 
                errorMsg.contains("Connection") || errorMsg.contains("endpoint") ||
                causeMsg.contains("DynamoDB") || causeMsg.contains("Connection") ||
                causeMsg.contains("endpoint")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping functional test: " + errorMsg
                );
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }

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

        // When/Then - Should return 401 (Unauthorized) or 400 (Bad Request)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void testRefreshToken_WithValidToken_Succeeds() throws Exception {
        // Given - Register and login
        // Skip if DynamoDB operations fail (LocalStack not running)
        try {
            userService.createUserSecure(testEmail, testPasswordHash, testClientSalt, "Test", "User");
            AuthRequest authRequest = new AuthRequest();
            authRequest.setEmail(testEmail);
            authRequest.setPasswordHash(testPasswordHash);
            authRequest.setSalt(testClientSalt);
            AuthResponse authResponse = authService.authenticate(authRequest);
            String refreshToken = authResponse.getRefreshToken();

            // When/Then
            try {
                var result = mockMvc.perform(post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                        .andReturn();
                
                int status = result.getResponse().getStatus();
                
                // Check if it's an infrastructure error (500)
                if (status == 500) {
                    String errorBody = result.getResponse().getContentAsString();
                    if (errorBody.contains("DynamoDB") || errorBody.contains("Connection") || 
                        errorBody.contains("LocalStack") || errorBody.contains("endpoint")) {
                        org.junit.jupiter.api.Assumptions.assumeTrue(
                                false,
                                "Test requires DynamoDB/LocalStack to be running. Got 500 error: " + errorBody
                        );
                    }
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
                    // Check if it's a validation error that might be infrastructure-related
                    if (status == 400 && (responseBody.contains("DynamoDB") || 
                        responseBody.contains("Connection") || responseBody.contains("endpoint"))) {
                        org.junit.jupiter.api.Assumptions.assumeTrue(
                                false,
                                "Test requires DynamoDB/LocalStack to be running. Got 400 error: " + responseBody
                        );
                    }
                    throw new AssertionError("Refresh token failed with status " + status + ": " + responseBody);
                }
            } catch (Exception e) {
                // If test fails due to infrastructure, skip it
                String errorMsg = e.getMessage() != null ? e.getMessage() : "";
                Throwable cause = e.getCause();
                String causeMsg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "";
                
                if (errorMsg.contains("DynamoDB") || errorMsg.contains("LocalStack") || 
                    errorMsg.contains("Connection") || errorMsg.contains("endpoint") ||
                    causeMsg.contains("DynamoDB") || causeMsg.contains("Connection") ||
                    causeMsg.contains("endpoint")) {
                    org.junit.jupiter.api.Assumptions.assumeTrue(
                            false,
                            "Test requires DynamoDB/LocalStack to be running. Skipping functional test: " + errorMsg
                    );
                }
                throw e; // Re-throw if it's not an infrastructure issue
            }
        } catch (Exception e) {
            // If test fails due to infrastructure (DynamoDB not available), skip it
            String errorMsg = e.getMessage() != null ? e.getMessage() : "";
            Throwable cause = e.getCause();
            String causeMsg = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "";
            
            if (errorMsg.contains("DynamoDB") || errorMsg.contains("LocalStack") || 
                errorMsg.contains("Connection") || errorMsg.contains("endpoint") ||
                causeMsg.contains("DynamoDB") || causeMsg.contains("Connection") ||
                causeMsg.contains("endpoint")) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        false,
                        "Test requires DynamoDB/LocalStack to be running. Skipping functional test: " + errorMsg
                );
            }
            throw e; // Re-throw if it's not an infrastructure issue
        }
    }
}

