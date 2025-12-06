package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.util.TableInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthControllerIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(AuthControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @Test
    void testRegister_Success() throws Exception {
        // Use unique email for each test run to avoid conflicts
        String uniqueEmail = "newuser" + System.currentTimeMillis() + "@example.com";
        AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        // Use proper base64-encoded strings
        request.setPasswordHash(java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes()));
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // request.setSalt(java.util.Base64.getEncoder().encodeToString("client-salt".getBytes()));

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
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // request.setSalt(java.util.Base64.getEncoder().encodeToString("client-salt".getBytes()));

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
        

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Then login
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(uniqueEmail);
        loginRequest.setPasswordHash(passwordHash);
        

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
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // request.setSalt(java.util.Base64.getEncoder().encodeToString("client-salt".getBytes()));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testChangePassword_Success() throws Exception {
        // Register and login to get auth token
        String uniqueEmail = "changepwd" + System.currentTimeMillis() + "@example.com";
        String passwordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String clientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());
        
        AuthRequest registerRequest = new AuthRequest();
        registerRequest.setEmail(uniqueEmail);
        registerRequest.setPasswordHash(passwordHash);
        

        String registerResponse = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract access token from response
        String accessToken = objectMapper.readTree(registerResponse).get("accessToken").asText();

        // Prepare password change request
        String newPasswordHash = java.util.Base64.getEncoder().encodeToString("new-hashed-password".getBytes());
        String newClientSalt = java.util.Base64.getEncoder().encodeToString("new-client-salt".getBytes());

        // BREAKING CHANGE: Client salt removed
        AuthController.ChangePasswordRequest changeRequest = new AuthController.ChangePasswordRequest();
        changeRequest.setCurrentPasswordHash(passwordHash);
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        changeRequest.setNewPasswordHash(newPasswordHash);
        // BREAKING CHANGE: Client salt removed

        // Change password
        mockMvc.perform(post("/api/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(changeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").exists());

        // Verify new password works by logging in with it
        AuthRequest newLoginRequest = new AuthRequest();
        newLoginRequest.setEmail(uniqueEmail);
        newLoginRequest.setPasswordHash(newPasswordHash);
        

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newLoginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void testChangePassword_InvalidCurrentPassword() throws Exception {
        // Register and login to get auth token
        String uniqueEmail = "changepwd2" + System.currentTimeMillis() + "@example.com";
        String passwordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String clientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());
        
        AuthRequest registerRequest = new AuthRequest();
        registerRequest.setEmail(uniqueEmail);
        registerRequest.setPasswordHash(passwordHash);
        

        String registerResponse = mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Extract access token from response
        String accessToken = objectMapper.readTree(registerResponse).get("accessToken").asText();

        // Prepare password change request with wrong current password
        String newPasswordHash = java.util.Base64.getEncoder().encodeToString("new-hashed-password".getBytes());
        String newClientSalt = java.util.Base64.getEncoder().encodeToString("new-client-salt".getBytes());

        // BREAKING CHANGE: Client salt removed
        AuthController.ChangePasswordRequest changeRequest = new AuthController.ChangePasswordRequest();
        changeRequest.setCurrentPasswordHash("wrong-password-hash");
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        changeRequest.setNewPasswordHash(newPasswordHash);
        // BREAKING CHANGE: Client salt removed

        // Change password should fail
        mockMvc.perform(post("/api/auth/change-password")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(changeRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testChangePassword_WithoutAuthentication() throws Exception {
        // BREAKING CHANGE: Client salt removed
        String newPasswordHash = java.util.Base64.getEncoder().encodeToString("new-password-hash".getBytes());

        // BREAKING CHANGE: Client salt removed
        AuthController.ChangePasswordRequest changeRequest = new AuthController.ChangePasswordRequest();
        changeRequest.setCurrentPasswordHash("some-hash");
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        changeRequest.setNewPasswordHash(newPasswordHash);
        // BREAKING CHANGE: Client salt removed

        // Change password without authentication should fail
        mockMvc.perform(post("/api/auth/change-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(changeRequest)))
                .andExpect(status().isUnauthorized());
    }
}

