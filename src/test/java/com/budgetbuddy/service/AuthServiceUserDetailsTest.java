package com.budgetbuddy.service;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.security.PasswordHashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AuthService - UserDetails Bug Fix
 * 
 * Tests the fix for ClassCastException where AuthService was passing a String (email)
 * instead of UserDetails to JwtTokenProvider.generateToken()
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AuthServiceUserDetailsTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private UserService userService;

    @Mock
    private PasswordHashingService passwordHashingService;

    @InjectMocks
    private AuthService authService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setServerSalt("server-salt");
        testUser.setClientSalt("client-salt");
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setRoles(Set.of("USER"));
    }

    @Test
    void testAuthenticate_CreatesUserDetailsObject_NotString() {
        // Arrange
        AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        request.setSalt("client-salt");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any(Authentication.class))).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString())).thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        // Capture the Authentication object passed to generateToken
        ArgumentCaptor<Authentication> authCaptor = ArgumentCaptor.forClass(Authentication.class);

        // Act
        authService.authenticate(request);

        // Assert - Verify that generateToken was called with an Authentication object
        verify(tokenProvider, times(1)).generateToken(authCaptor.capture());
        
        Authentication capturedAuth = authCaptor.getValue();
        assertNotNull(capturedAuth);
        
        // CRITICAL: Verify that the principal is a UserDetails object, not a String
        Object principal = capturedAuth.getPrincipal();
        assertNotNull(principal, "Principal should not be null");
        assertTrue(principal instanceof UserDetails, 
                "Principal should be UserDetails, not String. Got: " + principal.getClass().getName());
        
        UserDetails userDetails = (UserDetails) principal;
        assertEquals("test@example.com", userDetails.getUsername());
        assertFalse(userDetails.getAuthorities().isEmpty());
        assertTrue(userDetails.isAccountNonLocked());
        assertTrue(userDetails.isEnabled());
    }

    @Test
    void testAuthenticate_UserDetailsHasCorrectAuthorities() {
        // Arrange
        testUser.setRoles(Set.of("USER", "ADMIN"));
        AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        request.setSalt("client-salt");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any(Authentication.class))).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString())).thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        ArgumentCaptor<Authentication> authCaptor = ArgumentCaptor.forClass(Authentication.class);

        // Act
        authService.authenticate(request);

        // Assert
        verify(tokenProvider).generateToken(authCaptor.capture());
        UserDetails userDetails = (UserDetails) authCaptor.getValue().getPrincipal();
        
        assertEquals(2, userDetails.getAuthorities().size());
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void testAuthenticate_UserDetailsReflectsUserEnabledStatus() {
        // Arrange - Test with disabled user (should throw exception, not authenticate)
        testUser.setEnabled(false);
        AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        request.setSalt("client-salt");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        // Act & Assert - Disabled users should not be able to authenticate
        AppException exception = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        }, "Disabled users should not be able to authenticate");
        
        assertEquals(ErrorCode.ACCOUNT_DISABLED, exception.getErrorCode());
        
        // Verify token generation was NOT called for disabled user
        verify(tokenProvider, never()).generateToken(any(Authentication.class));
    }

    @Test
    void testAuthenticate_NoClassCastExceptionWhenGeneratingToken() {
        // Arrange
        AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        request.setSalt("client-salt");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any(Authentication.class))).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString())).thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        // Act & Assert - Should not throw ClassCastException
        assertDoesNotThrow(() -> {
            authService.authenticate(request);
        }, "Should not throw ClassCastException when generating token");
        
        verify(tokenProvider, times(1)).generateToken(any(Authentication.class));
    }
}

