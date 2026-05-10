package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// PMD's LawOfDemeter is documented as imprecise on chains involving

// standard library types (BigDecimal, String, Optional) and DTO

// getters; this class has many such idiomatic uses. Suppress at

// class level rather than littering every method.

@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEnabled(true);
        testUser.setRoles(Set.of("USER"));
    }

    @Test
    void testAuthenticateSuccess() {
        // Arrange - BREAKING CHANGE: Client salt removed
        final AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        // BREAKING CHANGE: Client salt removed - backend handles salt management

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any())).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString()))
                .thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        // Act
        final AuthResponse result = authService.authenticate(request);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getAccessToken());
        assertEquals("user-123", result.getUser().getId());
        verify(userRepository, times(1)).save(any(UserTable.class));
    }

    @Test
    void testAuthenticateInvalidCredentials() {
        // Arrange - BREAKING CHANGE: Client salt removed
        final AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        // BREAKING CHANGE: Client salt removed - backend handles salt management

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString()))
                .thenReturn(false);

        // Act & Assert
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            authService.authenticate(request);
                        });

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void testAuthenticateUserNotFound() {
        // Arrange - BREAKING CHANGE: Client salt removed
        final AuthRequest request = new AuthRequest();
        request.setEmail("nonexistent@example.com");
        request.setPasswordHash("client-hash");
        // BREAKING CHANGE: Client salt removed - backend handles salt management

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        // Act & Assert
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            authService.authenticate(request);
                        });

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }
}
