package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.security.PasswordHashingService;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Unit Tests for AuthService - UserDetails Bug Fix
 *
 * <p>Tests the fix for ClassCastException where AuthService was passing a String (email) instead of
 * UserDetails to JwtTokenProvider.generateToken()
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthServiceUserDetailsTest {

    @Mock private UserRepository userRepository;

    @Mock private JwtTokenProvider tokenProvider;

    @Mock private UserService userService;

    @Mock private PasswordHashingService passwordHashingService;

    @InjectMocks private AuthService authService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setServerSalt("server-salt");
        // BREAKING CHANGE: Client salt removed
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setRoles(Set.of("USER"));
    }

    @Test
    void testAuthenticateCreatesUserDetailsObjectNotString() {
        // Arrange
        final AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any(Authentication.class))).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString()))
                .thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        // Capture the Authentication object passed to generateToken
        final ArgumentCaptor<Authentication> authCaptor =
                ArgumentCaptor.forClass(Authentication.class);

        // Act
        authService.authenticate(request);

        // Assert - Verify that generateToken was called with an Authentication object
        verify(tokenProvider, times(1)).generateToken(authCaptor.capture());

        final Authentication capturedAuth = authCaptor.getValue();
        assertNotNull(capturedAuth);

        // CRITICAL: Verify that the principal is a UserDetails object, not a String
        final Object principal = capturedAuth.getPrincipal();
        assertNotNull(principal, "Principal should not be null");
        assertTrue(
                principal instanceof UserDetails,
                "Principal should be UserDetails, not String. Got: "
                        + principal.getClass().getName());

        final UserDetails userDetails = (UserDetails) principal;
        assertEquals("test@example.com", userDetails.getUsername());
        assertFalse(userDetails.getAuthorities().isEmpty());
        assertTrue(userDetails.isAccountNonLocked());
        assertTrue(userDetails.isEnabled());
    }

    @Test
    void testAuthenticateUserDetailsHasCorrectAuthorities() {
        // Arrange
        testUser.setRoles(Set.of("USER", "ADMIN"));
        final AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any(Authentication.class))).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString()))
                .thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        final ArgumentCaptor<Authentication> authCaptor =
                ArgumentCaptor.forClass(Authentication.class);

        // Act
        authService.authenticate(request);

        // Assert
        verify(tokenProvider).generateToken(authCaptor.capture());
        final UserDetails userDetails = (UserDetails) authCaptor.getValue().getPrincipal();

        assertEquals(2, userDetails.getAuthorities().size());
        assertTrue(
                userDetails.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_USER".equals(a.getAuthority())));
        assertTrue(
                userDetails.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())));
    }

    @Test
    void testAuthenticateUserDetailsReflectsUserEnabledStatus() {
        // Arrange - Test with disabled user (should throw exception, not authenticate)
        testUser.setEnabled(false);
        final AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString()))
                .thenReturn(true);

        // Act & Assert - Disabled users should not be able to authenticate
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            authService.authenticate(request);
                        },
                        "Disabled users should not be able to authenticate");

        assertEquals(ErrorCode.ACCOUNT_DISABLED, exception.getErrorCode());

        // Verify token generation was NOT called for disabled user
        verify(tokenProvider, never()).generateToken(any(Authentication.class));
    }

    @Test
    void testAuthenticateNoClassCastExceptionWhenGeneratingToken() {
        // Arrange
        final AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any(Authentication.class))).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString()))
                .thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        // Act & Assert - Should not throw ClassCastException
        assertDoesNotThrow(
                () -> {
                    authService.authenticate(request);
                },
                "Should not throw ClassCastException when generating token");

        verify(tokenProvider, times(1)).generateToken(any(Authentication.class));
    }
}
