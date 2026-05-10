package com.budgetbuddy.functional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.security.ddos.DDoSProtectionService;
import com.budgetbuddy.security.rate.RateLimitService;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Functional Tests for Authentication API */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthFunctionalTest {

    @Autowired private MockMvc mockMvc;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired private AuthService authService;

    @Autowired private UserService userService;

    @Autowired private DynamoDbClient dynamoDbClient;

    // Note: @MockitoBean is deprecated in Spring Boot 3.4.0, but still functional
    // Suppressing deprecation warning as replacement API is not yet stable
    @SuppressWarnings("deprecation")
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private RateLimitService rateLimitService;

    @SuppressWarnings("deprecation")
    @org.springframework.test.context.bean.override.mockito.MockitoBean
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

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        // Use a consistent base64-encoded string as client hash (representing a client-side PBKDF2
        // hash)
        // This must be the same for both createUserSecure and authenticate
        testPasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("testPassword123".getBytes(StandardCharsets.UTF_8));

        // Ensure ObjectMapper has JavaTimeModule for Instant serialization
        final ObjectMapper mapper = getObjectMapper();
        if (mapper.getRegisteredModuleIds().stream()
                .noneMatch(id -> id.toString().contains("JavaTimeModule"))) {
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }
        // Mock rate limiting services to allow all requests in tests
        when(rateLimitService.isAllowed(anyString(), anyString())).thenReturn(true);
        when(ddosProtectionService.isAllowed(anyString())).thenReturn(true);
    }

    @Test
    void testRegisterCompleteWorkflow() throws Exception {
        // Given - PAKE2 requires challenge first
        // Step 1: Get registration challenge
        final String challengeRequestBody = String.format("{\"email\":\"%s\"}", testEmail);
        final var challengeResult =
                mockMvc.perform(
                                post("/api/auth/register/challenge")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(challengeRequestBody))
                        .andExpect(status().isOk())
                        .andReturn();

        final String challengeResponse = challengeResult.getResponse().getContentAsString();
        final com.fasterxml.jackson.databind.JsonNode challengeNode =
                getObjectMapper().readTree(challengeResponse);
        final String challenge = challengeNode.get("challenge").asText();

        // Step 2: Register with challenge and password_hash
        final String requestBody =
                String.format(
                        "{\"email\":\"%s\",\"password_hash\":\"%s\",\"challenge\":\"%s\"}",
                        testEmail, testPasswordHash, challenge);

        // When/Then - Functional tests require LocalStack to be running
        // These are integration tests that test the full stack including DynamoDB
        final var result =
                mockMvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestBody))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        final String responseBody = result.getResponse().getContentAsString();

        // Fail on 500 errors - tables should be initialized before tests run
        if (status == 500) {
            throw new AssertionError("Registration failed with 500 error: " + responseBody);
        }

        // Verify success (2xx status) - use the result we already have
        if (status >= 200 && status < 300) {
            // Verify response content using the result we already have
            org.junit.jupiter.api.Assertions.assertTrue(
                    responseBody.contains("accessToken")
                            || responseBody.contains("\"accessToken\""),
                    "Response should contain accessToken: " + responseBody);
            org.junit.jupiter.api.Assertions.assertTrue(
                    responseBody.contains("refreshToken")
                            || responseBody.contains("\"refreshToken\""),
                    "Response should contain refreshToken: " + responseBody);
        } else {
            // Otherwise, it's a real validation error
            throw new AssertionError(
                    "Registration failed with status " + status + ": " + responseBody);
        }
    }

    @Test
    void testLoginWithValidCredentialsSucceeds() throws Exception {
        // Given - Register first - tables should be initialized before tests run
        // BREAKING CHANGE: firstName and lastName are optional (can be null)
        userService.createUserSecure(testEmail, testPasswordHash, null, null);

        // PAKE2 requires challenge first
        // Step 1: Get login challenge
        final String challengeRequestBody = String.format("{\"email\":\"%s\"}", testEmail);
        final var challengeResult =
                mockMvc.perform(
                                post("/api/auth/login/challenge")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(challengeRequestBody))
                        .andExpect(status().isOk())
                        .andReturn();

        final String challengeResponse = challengeResult.getResponse().getContentAsString();
        final com.fasterxml.jackson.databind.JsonNode challengeNode =
                getObjectMapper().readTree(challengeResponse);
        final String challenge = challengeNode.get("challenge").asText();

        // Step 2: Login with challenge and password_hash
        final String requestBody =
                String.format(
                        "{\"email\":\"%s\",\"password_hash\":\"%s\",\"challenge\":\"%s\"}",
                        testEmail, testPasswordHash, challenge);

        // When/Then
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void testLoginWithInvalidCredentialsFails() throws Exception {
        // Given - Use new format (no salt)
        final String requestBody =
                String.format("{\"email\":\"%s\",\"password_hash\":\"wrong-hash\"}", testEmail);

        // When/Then - Should return 401 (Unauthorized) or 400 (Bad Request)
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void testRefreshTokenWithValidTokenSucceeds() throws Exception {
        // Given - Register and login - tables should be initialized before tests run
        // BREAKING CHANGE: firstName and lastName are optional (can be null)
        userService.createUserSecure(testEmail, testPasswordHash, null, null);
        final AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(testPasswordHash);
        final AuthResponse authResponse = authService.authenticate(authRequest);
        final String refreshToken = authResponse.getRefreshToken();

        // When/Then
        final var result =
                mockMvc.perform(
                                post("/api/auth/refresh")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                        .andReturn();

        final int status = result.getResponse().getStatus();

        // Fail on 500 errors - tables should be initialized before tests run
        if (status == 500) {
            final String errorBody = result.getResponse().getContentAsString();
            throw new AssertionError("Refresh token failed with 500 error: " + errorBody);
        }

        // Verify success
        final String responseBody = result.getResponse().getContentAsString();
        if (status >= 200 && status < 300) {
            // Verify response content using the result we already have
            org.junit.jupiter.api.Assertions.assertTrue(
                    responseBody.contains("accessToken")
                            || responseBody.contains("\"accessToken\""),
                    "Response should contain accessToken: " + responseBody);
        } else {
            throw new AssertionError(
                    "Refresh token failed with status " + status + ": " + responseBody);
        }
    }
}
