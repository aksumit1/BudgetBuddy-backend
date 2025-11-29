package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.PasswordResetService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.MessageUtil;
import com.budgetbuddy.security.ddos.DDoSProtectionService;
import com.budgetbuddy.security.rate.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthControllerPasswordResetTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private UserService userService;

    @MockBean
    private PasswordResetService passwordResetService;

    @MockBean
    private MessageUtil messageUtil;

    @MockBean
    private DDoSProtectionService ddosProtectionService;

    @MockBean
    private RateLimitService rateLimitService;

    private String testEmail;
    private String testCode;

    @BeforeEach
    void setUp() {
        testEmail = "test@example.com";
        testCode = "123456";
        // Mock MessageUtil to return the key if not found (for exception handler)
        when(messageUtil.getErrorMessage(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return "error." + key.toLowerCase().replace("_", ".");
        });
        // Mock DDoS protection to allow all requests in tests
        when(ddosProtectionService.isAllowed(anyString())).thenReturn(true);
        // Mock rate limiting to allow all requests in tests
        when(rateLimitService.isAllowed(anyString(), anyString())).thenReturn(true);
        // Ensure ObjectMapper has JavaTimeModule for Instant serialization
        if (objectMapper.getRegisteredModuleIds().stream().noneMatch(id -> id.toString().contains("JavaTimeModule"))) {
            objectMapper.registerModule(new JavaTimeModule());
        }
    }

    @Test
    void testForgotPassword_Success() throws Exception {
        // Given
        doNothing().when(passwordResetService).requestPasswordReset(testEmail);
        AuthController.ForgotPasswordRequest request = new AuthController.ForgotPasswordRequest();
        request.setEmail(testEmail);

        // When/Then
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Verification code sent to your email"));

        verify(passwordResetService).requestPasswordReset(testEmail);
    }

    @Test
    void testForgotPassword_InvalidEmail() throws Exception {
        // Given
        AuthController.ForgotPasswordRequest request = new AuthController.ForgotPasswordRequest();
        request.setEmail("invalid-email");

        // When/Then
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).requestPasswordReset(anyString());
    }

    @Test
    void testForgotPassword_EmailServiceFailure() throws Exception {
        // Given
        doThrow(new com.budgetbuddy.exception.AppException(
                com.budgetbuddy.exception.ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to send verification email. Please try again later."))
                .when(passwordResetService).requestPasswordReset(testEmail);
        
        AuthController.ForgotPasswordRequest request = new AuthController.ForgotPasswordRequest();
        request.setEmail(testEmail);

        // When/Then
        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());

        verify(passwordResetService).requestPasswordReset(testEmail);
    }

    @Test
    void testVerifyResetCode_Success() throws Exception {
        // Given
        doNothing().when(passwordResetService).verifyResetCode(testEmail, testCode);
        AuthController.VerifyCodeRequest request = new AuthController.VerifyCodeRequest();
        request.setEmail(testEmail);
        request.setCode(testCode);

        // When/Then
        mockMvc.perform(post("/api/auth/verify-reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Code verified successfully"));

        verify(passwordResetService).verifyResetCode(testEmail, testCode);
    }

    @Test
    void testResetPassword_Success() throws Exception {
        // Given
        doNothing().when(passwordResetService).resetPassword(testEmail, testCode, "password_hash", "salt");
        doNothing().when(userService).resetPasswordByEmail(testEmail, "password_hash", "salt");

        AuthController.PasswordResetRequest request = new AuthController.PasswordResetRequest();
        request.setEmail(testEmail);
        request.setCode(testCode);
        request.setPasswordHash("password_hash");
        request.setSalt("salt");

        // When/Then
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Password reset successful"));

        verify(passwordResetService).resetPassword(testEmail, testCode, "password_hash", "salt");
        verify(userService).resetPasswordByEmail(testEmail, "password_hash", "salt");
    }

    @Test
    void testResetPassword_MissingCode() throws Exception {
        // Given
        AuthController.PasswordResetRequest request = new AuthController.PasswordResetRequest();
        request.setEmail(testEmail);
        request.setPasswordHash("password_hash");
        request.setSalt("salt");
        // Code is missing

        // When/Then
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void testResetPassword_InvalidFormat() throws Exception {
        // Given
        AuthController.PasswordResetRequest request = new AuthController.PasswordResetRequest();
        request.setEmail(testEmail);
        request.setCode(testCode);
        // Missing password_hash and salt

        // When/Then
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString(), anyString());
    }
}

