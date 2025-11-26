package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for User Registration Bug Fixes
 * 
 * Tests the fixes for:
 * 1. User registration always failing (cache issue)
 * 2. ClassCastException in JWT token generation
 * 3. JWT secret length validation
 * 4. Proper error responses for duplicate registrations
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class UserRegistrationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    private String baseUrl;
    private String uniqueEmail;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        uniqueEmail = "test-" + System.currentTimeMillis() + "@example.com";
    }

    @Test
    void testRegister_NewUser_Succeeds() {
        // Arrange
        AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        request.setPasswordHash(Base64.getEncoder().encodeToString("testpassword123".getBytes()));
        request.setSalt(Base64.getEncoder().encodeToString("somesalt".getBytes()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

        // Act
        ResponseEntity<AuthResponse> response = restTemplate.exchange(
                baseUrl + "/api/auth/register",
                HttpMethod.POST,
                entity,
                AuthResponse.class
        );

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getAccessToken());
        assertNotNull(response.getBody().getRefreshToken());
        assertFalse(response.getBody().getAccessToken().isEmpty());
        
        // Verify user was created in database
        Optional<UserTable> createdUser = userRepository.findByEmail(uniqueEmail);
        assertTrue(createdUser.isPresent(), "User should be created in database");
        assertEquals(uniqueEmail, createdUser.get().getEmail());
    }

    @Test
    void testRegister_DuplicateUser_ReturnsProperError() {
        // Arrange - Register user first
        AuthRequest firstRequest = new AuthRequest();
        firstRequest.setEmail(uniqueEmail);
        firstRequest.setPasswordHash(Base64.getEncoder().encodeToString("testpassword123".getBytes()));
        firstRequest.setSalt(Base64.getEncoder().encodeToString("somesalt".getBytes()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<AuthRequest> firstEntity = new HttpEntity<>(firstRequest, headers);

        // First registration
        restTemplate.exchange(
                baseUrl + "/api/auth/register",
                HttpMethod.POST,
                firstEntity,
                AuthResponse.class
        );

        // Second registration attempt with same email
        AuthRequest duplicateRequest = new AuthRequest();
        duplicateRequest.setEmail(uniqueEmail);
        duplicateRequest.setPasswordHash(Base64.getEncoder().encodeToString("testpassword123".getBytes()));
        duplicateRequest.setSalt(Base64.getEncoder().encodeToString("somesalt".getBytes()));
        HttpEntity<AuthRequest> duplicateEntity = new HttpEntity<>(duplicateRequest, headers);

        // Act
        ResponseEntity<com.budgetbuddy.exception.EnhancedGlobalExceptionHandler.ErrorResponse> response = 
                restTemplate.exchange(
                        baseUrl + "/api/auth/register",
                        HttpMethod.POST,
                        duplicateEntity,
                        com.budgetbuddy.exception.EnhancedGlobalExceptionHandler.ErrorResponse.class
                );

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ErrorCode.USER_ALREADY_EXISTS.name(), response.getBody().getErrorCode());
        assertTrue(response.getBody().getMessage().contains("already exists") 
                || response.getBody().getMessage().contains("User with this email"));
    }

    @Test
    void testRegister_NewUser_GeneratesValidJwtToken() {
        // Arrange
        AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        request.setPasswordHash(Base64.getEncoder().encodeToString("testpassword123".getBytes()));
        request.setSalt(Base64.getEncoder().encodeToString("somesalt".getBytes()));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

        // Act
        ResponseEntity<AuthResponse> response = restTemplate.exchange(
                baseUrl + "/api/auth/register",
                HttpMethod.POST,
                entity,
                AuthResponse.class
        );

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        AuthResponse authResponse = response.getBody();
        assertNotNull(authResponse);
        
        String accessToken = authResponse.getAccessToken();
        assertNotNull(accessToken);
        
        // Verify JWT token format (should have 3 parts separated by dots)
        String[] tokenParts = accessToken.split("\\.");
        assertEquals(3, tokenParts.length, "JWT token should have 3 parts (header.payload.signature)");
        
        // Verify token is not empty
        assertFalse(accessToken.isEmpty());
        assertTrue(accessToken.length() > 50); // JWT tokens are typically longer
    }

    @Test
    void testRegister_MultipleNewUsers_Succeed() {
        // Arrange
        String[] emails = {
                "user1-" + System.currentTimeMillis() + "@example.com",
                "user2-" + System.currentTimeMillis() + "@example.com",
                "user3-" + System.currentTimeMillis() + "@example.com"
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        // Act & Assert - All should succeed
        for (String email : emails) {
            AuthRequest request = new AuthRequest();
            request.setEmail(email);
            request.setPasswordHash(Base64.getEncoder().encodeToString("testpassword123".getBytes()));
            request.setSalt(Base64.getEncoder().encodeToString("somesalt".getBytes()));
            HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<AuthResponse> response = restTemplate.exchange(
                    baseUrl + "/api/auth/register",
                    HttpMethod.POST,
                    entity,
                    AuthResponse.class
            );

            assertEquals(HttpStatus.CREATED, response.getStatusCode(), 
                    "Registration should succeed for email: " + email);
            assertNotNull(response.getBody().getAccessToken());
        }
    }

    @Test
    void testRegister_InvalidInput_ReturnsBadRequest() {
        // Arrange - Missing password hash
        AuthRequest request = new AuthRequest();
        request.setEmail(uniqueEmail);
        request.setSalt(Base64.getEncoder().encodeToString("somesalt".getBytes()));
        // password_hash is missing

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        HttpEntity<AuthRequest> entity = new HttpEntity<>(request, headers);

        // Act
        ResponseEntity<com.budgetbuddy.exception.EnhancedGlobalExceptionHandler.ErrorResponse> response = 
                restTemplate.exchange(
                        baseUrl + "/api/auth/register",
                        HttpMethod.POST,
                        entity,
                        com.budgetbuddy.exception.EnhancedGlobalExceptionHandler.ErrorResponse.class
                );

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}

