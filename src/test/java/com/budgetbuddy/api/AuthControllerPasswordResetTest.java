package com.budgetbuddy.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.security.ddos.DDoSProtectionService;
import com.budgetbuddy.security.rate.RateLimitService;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.ChallengeService;
import com.budgetbuddy.service.PasswordResetService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.MessageUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AuthControllerPasswordResetTest {

    private static final String DEPRECATION = "deprecation";
    private static final String PASSWORD_HASH = "password_hash";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    // Note: @MockitoBean is deprecated in Spring Boot 3.4.0, but still functional
    @SuppressWarnings(DEPRECATION)
    @MockitoBean
    private AuthService authService;

    @SuppressWarnings(DEPRECATION)
    @MockitoBean
    private UserService userService;

    @SuppressWarnings(DEPRECATION)
    @MockitoBean
    private PasswordResetService passwordResetService;

    @SuppressWarnings(DEPRECATION)
    @MockitoBean
    private MessageUtil messageUtil;

    @SuppressWarnings(DEPRECATION)
    @MockitoBean
    private DDoSProtectionService ddosProtectionService;

    @SuppressWarnings(DEPRECATION)
    @MockitoBean
    private RateLimitService rateLimitService;

    @SuppressWarnings(DEPRECATION)
    @MockitoBean
    private ChallengeService challengeService;

    private String testEmail;
    private String testCode;

    @BeforeEach
    void setUp() {
        testEmail = "test@example.com";
        testCode = "123456";
        // Mock MessageUtil to return the key if not found (for exception handler)
        when(messageUtil.getErrorMessage(anyString()))
                .thenAnswer(
                        invocation -> {
                            final String key = invocation.getArgument(0);
                            return "error." + key.toLowerCase(Locale.ROOT).replace("_", ".");
                        });
        // Mock DDoS protection to allow all requests in tests
        when(ddosProtectionService.isAllowed(anyString())).thenReturn(true);
        // Mock rate limiting to allow all requests in tests
        when(rateLimitService.isAllowed(anyString(), anyString())).thenReturn(true);
        // Ensure ObjectMapper has JavaTimeModule for Instant serialization
        if (objectMapper.getRegisteredModuleIds().stream()
                .noneMatch(id -> id.toString().contains("JavaTimeModule"))) {
            objectMapper.registerModule(new JavaTimeModule());
        }
    }

    @Test
    void testForgotPasswordSuccess() throws Exception {
        // Given
        doNothing().when(passwordResetService).requestPasswordReset(testEmail);
        final AuthController.ForgotPasswordRequest request =
                new AuthController.ForgotPasswordRequest();
        request.setEmail(testEmail);

        // When/Then
        mockMvc.perform(
                        post("/api/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(
                        jsonPath("$.message")
                                .value("Verification code sent to your email if it's valid."));

        verify(passwordResetService).requestPasswordReset(testEmail);
    }

    @Test
    void testForgotPasswordInvalidEmail() throws Exception {
        // Given
        final AuthController.ForgotPasswordRequest request =
                new AuthController.ForgotPasswordRequest();
        request.setEmail("invalid-email");

        // When/Then
        mockMvc.perform(
                        post("/api/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).requestPasswordReset(anyString());
    }

    @Test
    void testForgotPasswordEmailServiceFailure() throws Exception {
        // Given
        // Intentionally throw AppException with INTERNAL_SERVER_ERROR to test error handling
        // Note: EnhancedGlobalExceptionHandler will log this at ERROR level, which is correct
        // for system errors (INTERNAL_SERVER_ERROR is a system error, not a business logic error)
        doThrow(
                        new com.budgetbuddy.exception.AppException(
                                com.budgetbuddy.exception.ErrorCode.INTERNAL_SERVER_ERROR,
                                "Failed to send verification email. Please try again later."))
                .when(passwordResetService)
                .requestPasswordReset(testEmail);

        final AuthController.ForgotPasswordRequest request =
                new AuthController.ForgotPasswordRequest();
        request.setEmail(testEmail);

        // When/Then
        mockMvc.perform(
                        post("/api/auth/forgot-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());

        verify(passwordResetService).requestPasswordReset(testEmail);

        // Note: EnhancedGlobalExceptionHandler will log an ERROR for INTERNAL_SERVER_ERROR
        // This is expected and correct behavior - system errors should be logged at ERROR level
    }

    @Test
    void testVerifyResetCodeSuccess() throws Exception {
        // Given
        doNothing().when(passwordResetService).verifyResetCode(testEmail, testCode);
        final AuthController.VerifyCodeRequest request = new AuthController.VerifyCodeRequest();
        request.setEmail(testEmail);
        request.setCode(testCode);

        // When/Then
        mockMvc.perform(
                        post("/api/auth/verify-reset-code")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Code verified successfully"));

        verify(passwordResetService).verifyResetCode(testEmail, testCode);
    }

    @Test
    void testResetPasswordSuccess() throws Exception {
        // Given
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // PAKE2: Challenge is now required
        final String testChallenge = "test-challenge-123";
        doNothing().when(passwordResetService).resetPassword(testEmail, testCode, PASSWORD_HASH);
        doNothing().when(userService).resetPasswordByEmail(testEmail, PASSWORD_HASH);
        // Mock challenge verification to succeed
        doNothing().when(challengeService).verifyAndConsumeChallenge(testChallenge, testEmail);

        final AuthController.PasswordResetRequest request =
                new AuthController.PasswordResetRequest();
        request.setEmail(testEmail);
        request.setCode(testCode);
        request.setPasswordHash(PASSWORD_HASH);
        request.setChallenge(testChallenge);
        // BREAKING CHANGE: Client salt removed, PAKE2 challenge required

        // When/Then
        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Password reset successful"));

        // BREAKING CHANGE: Client salt removed
        verify(passwordResetService).resetPassword(testEmail, testCode, PASSWORD_HASH);
        verify(userService).resetPasswordByEmail(testEmail, PASSWORD_HASH);
        verify(challengeService).verifyAndConsumeChallenge(testChallenge, testEmail);
    }

    @Test
    void testResetPasswordMissingCode() throws Exception {
        // Given
        final AuthController.PasswordResetRequest request =
                new AuthController.PasswordResetRequest();
        request.setEmail(testEmail);
        request.setPasswordHash(PASSWORD_HASH);

        // Code is missing

        // When/Then
        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString());
    }

    @Test
    void testResetPasswordInvalidFormat() throws Exception {
        // Given
        final AuthController.PasswordResetRequest request =
                new AuthController.PasswordResetRequest();
        request.setEmail(testEmail);
        request.setCode(testCode);
        // Missing password_hash and salt

        // When/Then
        mockMvc.perform(
                        post("/api/auth/reset-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(passwordResetService, never()).resetPassword(anyString(), anyString(), anyString());
    }
}
