package com.budgetbuddy.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.util.TableInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/** */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthControllerIntegrationTest {

    private static final String HASHED_PASSWORD = "hashed-password";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AuthControllerIntegrationTest.class);

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private DynamoDbClient dynamoDbClient;

    // Note: @MockitoBean is deprecated in Spring Boot 3.4.0, but still functional
    @SuppressWarnings("deprecation")
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.budgetbuddy.security.rate.RateLimitService rateLimitService;

    @SuppressWarnings("deprecation")
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.budgetbuddy.security.ddos.DDoSProtectionService ddosProtectionService;

    @BeforeEach
    void setUp() {
        // Mock rate limiting services to allow all requests in tests
        when(rateLimitService.isAllowed(anyString(), anyString())).thenReturn(true);
        when(ddosProtectionService.isAllowed(anyString())).thenReturn(true);
    }

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        try {
            TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
        } catch (RuntimeException e) {
            // If LocalStack is not available, skip this test class
            if (e.getCause() != null
                    && (e.getCause().getMessage().contains("Connection refused")
                            || e.getCause()
                                    .getMessage()
                                    .contains("Unable to execute HTTP request"))) {
                LOGGER.warn(
                        "LocalStack/DynamoDB not available, skipping integration tests: {}",
                        e.getMessage());
                Assumptions.assumeTrue(
                        false,
                        "LocalStack/DynamoDB is not available. Please start Docker and LocalStack to run integration tests.");
            } else {
                throw e; // Re-throw if it's a different error
            }
        }
    }

    /**
     * Fetches a fresh PAKE2 challenge nonce for the given email from the
     * specified challenge endpoint. The backend consumes the nonce on
     * use, so each register/login attempt needs its own challenge.
     */
    private String fetchChallenge(final String challengePath, final String email)
            throws Exception {
        final AuthController.ChallengeRequest req = new AuthController.ChallengeRequest();
        req.setEmail(email);
        final String body =
                mockMvc.perform(
                                post(challengePath)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        // Envelope: {status:"ok", data:{challenge:"..."}, ...}
        return objectMapper.readTree(body).get("data").get("challenge").asText();
    }

    @Test
    void testRegisterSuccess() throws Exception {
        // Use unique email for each test run to avoid conflicts
        final String uniqueEmail = "newuser" + System.currentTimeMillis() + "@example.com";
        final AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        // Use proper base64-encoded strings
        request.setPasswordHash(
                java.util.Base64.getEncoder()
                        .encodeToString(HASHED_PASSWORD.getBytes(StandardCharsets.UTF_8)));
        request.setChallenge(fetchChallenge("/api/auth/register/challenge", uniqueEmail));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.user.id").exists());
    }

    @Test
    void testRegisterInvalidEmail() throws Exception {
        final AuthRequest request = new AuthRequest();
        request.setEmail("invalid-email");
        // Use proper base64-encoded strings
        request.setPasswordHash(
                java.util.Base64.getEncoder()
                        .encodeToString(HASHED_PASSWORD.getBytes(StandardCharsets.UTF_8)));
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // request.setSalt(java.util.Base64.getEncoder().encodeToString("client-salt".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testLoginSuccess() throws Exception {
        // Use unique email for each test run to avoid conflicts
        final String uniqueEmail = "loginuser" + System.currentTimeMillis() + "@example.com";
        // First register a user (needs its own challenge).
        final String passwordHash =
                java.util.Base64.getEncoder()
                        .encodeToString(HASHED_PASSWORD.getBytes(StandardCharsets.UTF_8));

        final AuthRequest registerRequest = new AuthRequest();
        registerRequest.setEmail(uniqueEmail);
        registerRequest.setPasswordHash(passwordHash);
        registerRequest.setChallenge(fetchChallenge("/api/auth/register/challenge", uniqueEmail));

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // Then login (needs its own login-challenge nonce).
        final AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(uniqueEmail);
        loginRequest.setPasswordHash(passwordHash);
        loginRequest.setChallenge(fetchChallenge("/api/auth/login/challenge", uniqueEmail));

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.user.id").exists());
    }

    @Test
    void testLoginInvalidCredentials() throws Exception {
        final AuthRequest request = new AuthRequest();
        request.setEmail("nonexistent@example.com");
        request.setPasswordHash(
                java.util.Base64.getEncoder()
                        .encodeToString("wrong-hash".getBytes(StandardCharsets.UTF_8)));
        // PAKE2 requires a fresh challenge even for failed credential
        // attempts — without it the login endpoint short-circuits to 400.
        request.setChallenge(
                fetchChallenge("/api/auth/login/challenge", "nonexistent@example.com"));

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testChangePasswordSuccess() throws Exception {
        final String uniqueEmail = "changepwd" + System.currentTimeMillis() + "@example.com";
        final String passwordHash =
                java.util.Base64.getEncoder()
                        .encodeToString(HASHED_PASSWORD.getBytes(StandardCharsets.UTF_8));

        final AuthRequest registerRequest = new AuthRequest();
        registerRequest.setEmail(uniqueEmail);
        registerRequest.setPasswordHash(passwordHash);
        registerRequest.setChallenge(fetchChallenge("/api/auth/register/challenge", uniqueEmail));

        final String registerResponse =
                mockMvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(registerRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Extract access token from envelope.
        final String accessToken =
                objectMapper.readTree(registerResponse).get("data").get("accessToken").asText();

        final String newPasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("new-hashed-password".getBytes(StandardCharsets.UTF_8));

        final AuthController.ChangePasswordRequest changeRequest =
                new AuthController.ChangePasswordRequest();
        changeRequest.setCurrentPasswordHash(passwordHash);
        changeRequest.setNewPasswordHash(newPasswordHash);
        // PAKE2: change-password verifies BOTH the current password
        // (authenticate flow) and the new password — each needs its own
        // challenge nonce.
        changeRequest.setCurrentPasswordChallenge(
                fetchChallenge("/api/auth/login/challenge", uniqueEmail));
        changeRequest.setNewPasswordChallenge(
                fetchChallenge("/api/auth/login/challenge", uniqueEmail));

        mockMvc.perform(
                        post("/api/auth/change-password")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(changeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.message").exists());

        // Verify new password works by logging in (needs fresh login challenge).
        final AuthRequest newLoginRequest = new AuthRequest();
        newLoginRequest.setEmail(uniqueEmail);
        newLoginRequest.setPasswordHash(newPasswordHash);
        newLoginRequest.setChallenge(fetchChallenge("/api/auth/login/challenge", uniqueEmail));

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(newLoginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists());
    }

    @Test
    void testChangePasswordInvalidCurrentPassword() throws Exception {
        // Register and login to get auth token
        final String uniqueEmail = "changepwd2" + System.currentTimeMillis() + "@example.com";
        final String passwordHash =
                java.util.Base64.getEncoder()
                        .encodeToString(HASHED_PASSWORD.getBytes(StandardCharsets.UTF_8));
        final String clientSalt =
                java.util.Base64.getEncoder()
                        .encodeToString("client-salt".getBytes(StandardCharsets.UTF_8));

        final AuthRequest registerRequest = new AuthRequest();
        registerRequest.setEmail(uniqueEmail);
        registerRequest.setPasswordHash(passwordHash);
        registerRequest.setChallenge(fetchChallenge("/api/auth/register/challenge", uniqueEmail));

        final String registerResponse =
                mockMvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(registerRequest)))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        final String accessToken =
                objectMapper.readTree(registerResponse).get("data").get("accessToken").asText();

        final String newPasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("new-hashed-password".getBytes(StandardCharsets.UTF_8));

        final AuthController.ChangePasswordRequest changeRequest =
                new AuthController.ChangePasswordRequest();
        changeRequest.setCurrentPasswordHash("wrong-password-hash");
        changeRequest.setNewPasswordHash(newPasswordHash);
        // PAKE2 challenges required so the request gets past validation
        // and reaches the credential check (which is the assertion target).
        changeRequest.setCurrentPasswordChallenge(
                fetchChallenge("/api/auth/login/challenge", uniqueEmail));
        changeRequest.setNewPasswordChallenge(
                fetchChallenge("/api/auth/login/challenge", uniqueEmail));

        mockMvc.perform(
                        post("/api/auth/change-password")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(changeRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testChangePasswordWithoutAuthentication() throws Exception {
        // BREAKING CHANGE: Client salt removed
        final String newPasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("new-password-hash".getBytes(StandardCharsets.UTF_8));

        // BREAKING CHANGE: Client salt removed
        final AuthController.ChangePasswordRequest changeRequest =
                new AuthController.ChangePasswordRequest();
        changeRequest.setCurrentPasswordHash("some-hash");
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        changeRequest.setNewPasswordHash(newPasswordHash);
        // BREAKING CHANGE: Client salt removed

        // Change password without authentication should fail
        mockMvc.perform(
                        post("/api/auth/change-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(changeRequest)))
                .andExpect(status().isUnauthorized());
    }
}
