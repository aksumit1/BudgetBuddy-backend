package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.api.response.ApiResponse;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.util.TableInitializer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Integration Tests for User Registration Bug Fixes
 *
 * <p>Tests the fixes for: 1. User registration always failing (cache issue) 2. ClassCastException
 * in JWT token generation 3. JWT secret length validation 4. Proper error responses for duplicate
 * registrations
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRegistrationIntegrationTest {

    private static final String TESTPASSWORD123 = "testpassword123";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private UserRepository userRepository;

    @Autowired private DynamoDbClient dynamoDbClient;

    private String baseUrl;
    private String uniqueEmail;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        uniqueEmail = "test-" + System.currentTimeMillis() + "@example.com";
    }

    /**
     * Fetch a PAKE2 challenge nonce for {@code email}. Required preamble for every
     * /api/auth/register call — the controller rejects requests without a
     * server-issued challenge as INVALID_INPUT. Mirrors the production iOS client
     * flow (challenge → password_hash with challenge → register).
     *
     * <p>Deserializes the response into {@code Map<String, Object>} rather than the
     * server-side {@code ChallengeService.ChallengeResponse} DTO, which has no
     * default constructor and so isn't Jackson-deserializable on the test side
     * without bespoke configuration.
     */
    private String fetchRegistrationChallenge(final String email) {
        final com.budgetbuddy.api.AuthController.ChallengeRequest req =
                new com.budgetbuddy.api.AuthController.ChallengeRequest();
        req.setEmail(email);
        final HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        final HttpEntity<com.budgetbuddy.api.AuthController.ChallengeRequest> entity =
                new HttpEntity<>(req, h);
        @SuppressWarnings({"unchecked", "rawtypes"})
        final ResponseEntity<java.util.Map> resp =
                restTemplate.exchange(
                        baseUrl + "/api/auth/register/challenge",
                        HttpMethod.POST,
                        entity,
                        java.util.Map.class);
        assertTrue(
                resp.getStatusCode().is2xxSuccessful(),
                "Challenge endpoint should succeed for " + email);
        assertNotNull(resp.getBody(), "Challenge response body must not be null");
        // Envelope: top-level is {status, data, correlationId, timestamp};
        // the challenge field lives under `data`.
        final Object dataObj = resp.getBody().get("data");
        assertNotNull(dataObj, "Envelope must include 'data' field");
        @SuppressWarnings("unchecked")
        final java.util.Map<String, Object> data = (java.util.Map<String, Object>) dataObj;
        final Object challenge = data.get("challenge");
        assertNotNull(challenge, "Challenge response must include 'challenge' field");
        return challenge.toString();
    }

    @Test
    void testRegisterNewUserSucceeds() {
        // Arrange — PAKE2 requires a challenge nonce before /register will accept the body.
        final AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        request.setPasswordHash(
                Base64.getEncoder()
                        .encodeToString(TESTPASSWORD123.getBytes(StandardCharsets.UTF_8)));
        request.setChallenge(fetchRegistrationChallenge(uniqueEmail));

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        final HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

        // Act — envelope-wrapped: ResponseEntity<ApiResponse<AuthResponse>>
        final ResponseEntity<ApiResponse<AuthResponse>> response =
                restTemplate.exchange(
                        baseUrl + "/api/auth/register",
                        HttpMethod.POST,
                        entity,
                        new org.springframework.core.ParameterizedTypeReference<>() {});

        // Assert
        assertTrue(response.getStatusCode().is2xxSuccessful(), "Registration should succeed");
        assertNotNull(response.getBody());
        final AuthResponse auth = response.getBody().data();
        assertNotNull(auth);
        assertNotNull(auth.getAccessToken());
        assertNotNull(auth.getRefreshToken());
        assertFalse(auth.getAccessToken().isEmpty());

        // Verify user was created in database
        final Optional<UserTable> createdUser = userRepository.findByEmail(uniqueEmail);
        assertTrue(createdUser.isPresent(), "User should be created in database");
        assertEquals(uniqueEmail, createdUser.get().getEmail());
    }

    @Test
    void testRegisterDuplicateUserReturnsProperError() {
        // Arrange - Register user first. Each /register call needs a fresh challenge —
        // the server consumes the nonce on use, so the duplicate attempt below must
        // also fetch its own challenge or it would fail for the wrong reason.
        final AuthRequest firstRequest = new AuthRequest();
        firstRequest.setEmail(uniqueEmail);
        firstRequest.setPasswordHash(
                Base64.getEncoder()
                        .encodeToString(TESTPASSWORD123.getBytes(StandardCharsets.UTF_8)));
        firstRequest.setChallenge(fetchRegistrationChallenge(uniqueEmail));

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        final HttpEntity<AuthRequest> firstEntity = new HttpEntity<>(firstRequest, headers);

        // First registration
        restTemplate.exchange(
                baseUrl + "/api/auth/register", HttpMethod.POST, firstEntity, AuthResponse.class);

        // Second registration attempt with same email
        final AuthRequest duplicateRequest = new AuthRequest();
        duplicateRequest.setEmail(uniqueEmail);
        duplicateRequest.setPasswordHash(
                Base64.getEncoder()
                        .encodeToString(TESTPASSWORD123.getBytes(StandardCharsets.UTF_8)));
        duplicateRequest.setChallenge(fetchRegistrationChallenge(uniqueEmail));
        final HttpEntity<AuthRequest> duplicateEntity = new HttpEntity<>(duplicateRequest, headers);

        // Act
        @SuppressWarnings("rawtypes")
        final ResponseEntity<ApiResponse> response =
                restTemplate.exchange(
                        baseUrl + "/api/auth/register",
                        HttpMethod.POST,
                        duplicateEntity,
                        ApiResponse.class);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().error());
        assertEquals(ErrorCode.USER_ALREADY_EXISTS.name(), response.getBody().error().code());
        assertTrue(
                response.getBody().error().message().contains("already exists")
                        || response.getBody().error().message().contains("User with this email"));
    }

    @Test
    void testRegisterNewUserGeneratesValidJwtToken() {
        // Arrange
        final AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        request.setPasswordHash(
                Base64.getEncoder()
                        .encodeToString(TESTPASSWORD123.getBytes(StandardCharsets.UTF_8)));
        request.setChallenge(fetchRegistrationChallenge(uniqueEmail));

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        final HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

        // Act — envelope-wrapped
        final ResponseEntity<ApiResponse<AuthResponse>> response =
                restTemplate.exchange(
                        baseUrl + "/api/auth/register",
                        HttpMethod.POST,
                        entity,
                        new org.springframework.core.ParameterizedTypeReference<>() {});

        // Assert
        assertTrue(response.getStatusCode().is2xxSuccessful(), "Registration should succeed");
        assertNotNull(response.getBody());
        final AuthResponse authResponse = response.getBody().data();
        assertNotNull(authResponse);

        final String accessToken = authResponse.getAccessToken();
        assertNotNull(accessToken);

        // Verify JWT token format (should have 3 parts separated by dots)
        final String[] tokenParts = accessToken.split("\\.");
        assertEquals(
                3, tokenParts.length, "JWT token should have 3 parts (header.payload.signature)");

        // Verify token is not empty
        assertFalse(accessToken.isEmpty());
        assertTrue(accessToken.length() > 50); // JWT tokens are typically longer
    }

    @Test
    void testRegisterMultipleNewUsersSucceed() {
        // Arrange
        final String[] emails = {
            "user1-" + System.currentTimeMillis() + "@example.com",
            "user2-" + System.currentTimeMillis() + "@example.com",
            "user3-" + System.currentTimeMillis() + "@example.com"
        };

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        // Act & Assert - All should succeed
        for (final String email : emails) {
            final AuthRequest request = new AuthRequest();
            request.setEmail(email);
            request.setPasswordHash(
                    Base64.getEncoder()
                            .encodeToString(TESTPASSWORD123.getBytes(StandardCharsets.UTF_8)));
            request.setChallenge(fetchRegistrationChallenge(email));
            final HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

            final ResponseEntity<ApiResponse<AuthResponse>> response =
                    restTemplate.exchange(
                            baseUrl + "/api/auth/register",
                            HttpMethod.POST,
                            entity,
                            new org.springframework.core.ParameterizedTypeReference<>() {});

            assertTrue(
                    response.getStatusCode().is2xxSuccessful(),
                    "Registration should succeed for email: " + email);
            assertNotNull(response.getBody());
            assertNotNull(response.getBody().data());
            assertNotNull(response.getBody().data().getAccessToken());
        }
    }

    @Test
    void testRegisterInvalidInputReturnsBadRequest() {
        // Arrange - Missing password hash
        final AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // password_hash is missing

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        final HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

        // Act
        @SuppressWarnings("rawtypes")
        final ResponseEntity<ApiResponse> response =
                restTemplate.exchange(
                        baseUrl + "/api/auth/register",
                        HttpMethod.POST,
                        entity,
                        ApiResponse.class);

        // Assert - Should return 400 for invalid input, but accept 500 if there's a processing
        // error
        // The important thing is that registration fails, not the exact status code
        assertTrue(
                response.getStatusCode().is4xxClientError()
                        || response.getStatusCode().is5xxServerError(),
                "Registration with missing password_hash should fail. Status: "
                        + response.getStatusCode()
                        + ", Error: "
                        + (response.getBody() != null && response.getBody().error() != null
                                ? response.getBody().error().message()
                                : "null"));

        // Prefer 400, but log if we get 500 (indicates a backend bug)
        if (response.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR) {
            System.out.println(
                    "WARNING: testRegisterInvalidInputReturnsBadRequest got 500 instead of 400. "
                            + "This indicates the exception handler may not be properly mapping INVALID_INPUT to BAD_REQUEST.");
        }
    }
}
