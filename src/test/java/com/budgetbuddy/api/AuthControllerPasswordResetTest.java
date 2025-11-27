package com.budgetbuddy.api;

import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.PasswordResetService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
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

    private String testEmail;
    private String testCode;

    @BeforeEach
    void setUp() {
        testEmail = "test@example.com";
        testCode = "123456";
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

