package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.DevicePinRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.DevicePinService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Tests for PINController
 * Tests PIN storage, update (change), deletion, and verification endpoints
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class PINControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private DevicePinService devicePinService;

    private UserTable testUser;
    private String accessToken;
    private String deviceId;

    @BeforeEach
    void setUp() {
        // Clear security context to ensure clean state for each test
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        
        // Create a test user
        String email = "test-pin-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String base64ClientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());
        testUser = userService.createUserSecure(
                email,
                base64PasswordHash,
                base64ClientSalt,
                "Test",
                "User"
        );

        // Authenticate and get JWT token
        AuthRequest authRequest = new AuthRequest(email, base64PasswordHash, base64ClientSalt);
        AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();
        
        // Generate a unique device ID for each test
        deviceId = UUID.randomUUID().toString();
    }

    /**
     * Helper method to add JWT token to request
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }

    @Test
    void testStorePIN_WithNewPIN_CreatesSuccessfully() throws Exception {
        // Given
        PINController.StorePINRequest request = new PINController.StorePINRequest();
        request.setDeviceId(deviceId);
        request.setPin("123456");

        // When/Then
        mockMvc.perform(withAuth(post("/api/pin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // Verify PIN was stored
        assertTrue(devicePinService.verifyPIN(testUser, deviceId, "123456"),
                "PIN should be verifiable after storage");
    }

    @Test
    void testChangePIN_WithExistingPIN_UpdatesSuccessfully() throws Exception {
        // Given - Store initial PIN
        devicePinService.storePIN(testUser, deviceId, "123456");
        assertTrue(devicePinService.verifyPIN(testUser, deviceId, "123456"),
                "Initial PIN should be verifiable");

        // When - Change PIN
        PINController.StorePINRequest request = new PINController.StorePINRequest();
        request.setDeviceId(deviceId);
        request.setPin("654321");

        mockMvc.perform(withAuth(post("/api/pin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // Then - New PIN should work, old PIN should not
        assertTrue(devicePinService.verifyPIN(testUser, deviceId, "654321"),
                "New PIN should be verifiable");
        assertFalse(devicePinService.verifyPIN(testUser, deviceId, "123456"),
                "Old PIN should not be verifiable after change");
    }

    @Test
    void testDeletePIN_WithValidDeviceId_DeletesSuccessfully() throws Exception {
        // Given - Store PIN first
        devicePinService.storePIN(testUser, deviceId, "123456");
        assertTrue(devicePinService.verifyPIN(testUser, deviceId, "123456"),
                "PIN should be verifiable before deletion");

        // When - Delete PIN
        mockMvc.perform(withAuth(delete("/api/pin/" + deviceId)))
                .andExpect(status().isNoContent());

        // Then - PIN should not be verifiable
        assertFalse(devicePinService.verifyPIN(testUser, deviceId, "123456"),
                "PIN should not be verifiable after deletion");
    }

    @Test
    void testDeletePIN_WithoutAuthentication_Returns401() throws Exception {
        // Given - Store PIN first
        devicePinService.storePIN(testUser, deviceId, "123456");

        // When/Then - Should return 401 if not authenticated
        mockMvc.perform(delete("/api/pin/" + deviceId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testStorePIN_WithoutAuthentication_Returns401() throws Exception {
        // Given
        PINController.StorePINRequest request = new PINController.StorePINRequest();
        request.setDeviceId(deviceId);
        request.setPin("123456");

        // When/Then - Should return 401 if not authenticated
        mockMvc.perform(post("/api/pin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testStorePIN_WithInvalidPIN_Returns400() throws Exception {
        // Given - Invalid PIN (5 digits instead of 6)
        PINController.StorePINRequest request = new PINController.StorePINRequest();
        request.setDeviceId(deviceId);
        request.setPin("12345");

        // When/Then
        mockMvc.perform(withAuth(post("/api/pin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testStorePIN_WithNonNumericPIN_Returns400() throws Exception {
        // Given - Non-numeric PIN
        PINController.StorePINRequest request = new PINController.StorePINRequest();
        request.setDeviceId(deviceId);
        request.setPin("12345a");

        // When/Then
        mockMvc.perform(withAuth(post("/api/pin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testDeletePIN_WithNonExistentPIN_ReturnsNoContent() throws Exception {
        // Given - No PIN stored
        String nonExistentDeviceId = UUID.randomUUID().toString();

        // When/Then - Should return 204 even if PIN doesn't exist (idempotent operation)
        mockMvc.perform(withAuth(delete("/api/pin/" + nonExistentDeviceId)))
                .andExpect(status().isNoContent());
    }

    @Test
    void testChangePIN_ResetsFailedAttempts() throws Exception {
        // Given - Store PIN and simulate failed attempts
        devicePinService.storePIN(testUser, deviceId, "123456");
        
        // Simulate failed verification attempts
        for (int i = 0; i < 3; i++) {
            devicePinService.verifyPIN(testUser, deviceId, "999999");
        }

        // When - Change PIN
        PINController.StorePINRequest request = new PINController.StorePINRequest();
        request.setDeviceId(deviceId);
        request.setPin("654321");

        mockMvc.perform(withAuth(post("/api/pin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        // Then - New PIN should work and failed attempts should be reset
        assertTrue(devicePinService.verifyPIN(testUser, deviceId, "654321"),
                "New PIN should be verifiable after change");
    }
}

