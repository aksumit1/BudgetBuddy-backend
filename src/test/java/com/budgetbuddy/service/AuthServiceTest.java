package com.budgetbuddy.service;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.security.PasswordHashingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Disabled("Java 25 compatibility: Mockito mocking issues")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
        testUser.setFirstName("Test");
        testUser.setLastName("User");
        testUser.setEnabled(true);
        testUser.setRoles(Set.of("USER"));
    }

    @Test
    void testAuthenticate_Success() {
        // Arrange
        AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        request.setSalt("client-salt");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);
        when(tokenProvider.generateToken(any())).thenReturn("access-token");
        when(tokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(tokenProvider.getExpirationDateFromToken(anyString())).thenReturn(new java.util.Date());
        doNothing().when(userRepository).save(any(UserTable.class));

        // Act
        AuthResponse result = authService.authenticate(request);

        // Assert
        assertNotNull(result);
        assertNotNull(result.getAccessToken());
        assertEquals("user-123", result.getUser().getId());
        verify(userRepository, times(1)).save(any(UserTable.class));
    }

    @Test
    void testAuthenticate_InvalidCredentials() {
        // Arrange
        AuthRequest request = new AuthRequest();
        request.setEmail("test@example.com");
        request.setPasswordHash("client-hash");
        request.setSalt("client-salt");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(testUser));
        when(passwordHashingService.verifyClientPassword(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        });

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }

    @Test
    void testAuthenticate_UserNotFound() {
        // Arrange
        AuthRequest request = new AuthRequest();
        request.setEmail("nonexistent@example.com");
        request.setPasswordHash("client-hash");
        request.setSalt("client-salt");

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> {
            authService.authenticate(request);
        });

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());
    }
}

