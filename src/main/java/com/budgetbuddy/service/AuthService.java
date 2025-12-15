package com.budgetbuddy.service;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.security.PasswordHashingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;

/**
 * Authentication service
 * Supports secure client-side hashed passwords
 * @Transactional removed as DynamoDB doesn't support transactions
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final JwtTokenProvider tokenProvider;
    private final PasswordHashingService passwordHashingService;
    private final UserRepository userRepository;
    private final CacheWarmingService cacheWarmingService;

    public AuthService(final JwtTokenProvider tokenProvider, final PasswordHashingService passwordHashingService, final UserRepository userRepository, final CacheWarmingService cacheWarmingService) {
        this.tokenProvider = tokenProvider;
        this.passwordHashingService = passwordHashingService;
        this.userRepository = userRepository;
        this.cacheWarmingService = cacheWarmingService;
    }

    /**
     * Authenticate user with secure format (password_hash only)
     * BREAKING CHANGE: Client salt removed - backend uses server salt only
     * Only secure client-side hashed passwords are supported
     */
    public AuthResponse authenticate(final AuthRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }

        // Find user
        UserTable user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password"));

        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }

        // Authenticate based on format
        boolean authenticated = false;

        if (request.isSecureFormat()) {
            // Secure format: client-side hashed password
            if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
                logger.warn("User {} has no password hash stored. Legacy account?", request.getEmail());
                throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
            }

            // Get server salt from user (stored during registration)
            String serverSalt = user.getServerSalt();
            if (serverSalt == null || serverSalt.isEmpty()) {
                logger.warn("User {} has no server salt. Account may need password reset.", request.getEmail());
                throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
            }

            // Validate that client hash is provided
            // BREAKING CHANGE: No longer requires salt
            if (request.getPasswordHash() == null || request.getPasswordHash().isEmpty()) {
                logger.warn("Login request for user {} missing password_hash", request.getEmail());
                throw new AppException(ErrorCode.INVALID_INPUT, "password_hash is required");
            }

            // Log for debugging (redact sensitive data)
            logger.debug("Authenticating user {}: clientHash length={}, serverHash length={}, serverSalt length={}",
                    request.getEmail(),
                    request.getPasswordHash() != null ? request.getPasswordHash().length() : 0,
                    user.getPasswordHash() != null ? user.getPasswordHash().length() : 0,
                    serverSalt != null ? serverSalt.length() : 0);

            // BREAKING CHANGE: No longer requires client salt
            authenticated = passwordHashingService.verifyClientPassword(
                    request.getPasswordHash(),
                    user.getPasswordHash(),
                    serverSalt
            );

            if (!authenticated) {
                logger.warn("Password verification failed for user {}: hash/salt mismatch", request.getEmail());
            }
        } else {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "password_hash must be provided. Only secure format is supported.");
        }

        if (!authenticated) {
            logger.warn("Authentication failed for user: {}", request.getEmail());
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        // Create UserDetails object for token generation
        // Get user roles from UserTable
        java.util.Set<String> roles = user.getRoles();
        java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities = roles.stream()
                    .map((role) -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // Create UserDetails object for JWT token generation
        // Ensure passwordHash is not null (use empty string as fallback for UserDetails)
        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || passwordHash.isEmpty()) {
            logger.error("User {} has no password hash during authentication. This should not happen.", request.getEmail());
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "User account configuration error");
        }
        
        org.springframework.security.core.userdetails.UserDetails userDetails = 
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(passwordHash)
                        .authorities(authorities)
                        .accountExpired(false)
                        .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                        .credentialsExpired(false)
                        .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                        .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails,
                null,
                authorities
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Generate tokens
        String accessToken = tokenProvider.generateToken(authentication);
        String refreshToken = tokenProvider.generateRefreshToken(user.getEmail());

        // Calculate expiration time
        Date expirationDate = tokenProvider.getExpirationDateFromToken(accessToken);
        if (expirationDate == null) {
            // Fallback: set expiration to 24 hours from now
            expirationDate = new Date(System.currentTimeMillis() + 86400000L);
        }
        LocalDateTime expiresAt = expirationDate.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        // Update last login
        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);

        // Pre-warm cache for user on login (async, non-blocking)
        try {
            cacheWarmingService.warmCacheForUser(user.getUserId());
            logger.debug("Cache warming initiated for user: {}", user.getUserId());
        } catch (Exception e) {
            logger.warn("Failed to warm cache for user {}: {}", user.getUserId(), e.getMessage());
            // Don't fail login if cache warming fails
        }

        AuthResponse.UserInfo userInfo = new AuthResponse.UserInfo(
                user.getUserId(),
                user.getEmail(),
                user.getFirstName() != null ? user.getFirstName() : "",
                user.getLastName() != null ? user.getLastName() : ""
        );

        logger.info("User authenticated: {}", request.getEmail());
        return new AuthResponse(accessToken, refreshToken, expiresAt, userInfo);
    }

    /**
     * Refresh token endpoint (Zero Trust)
     * Validates refresh token and issues new tokens with rotation
     * Old refresh token is invalidated (token rotation for security)
     */
    public AuthResponse refreshToken(final String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Refresh token is required");
        }

        // Validate token structure and signature
        if (!tokenProvider.validateToken(refreshToken)) {
            logger.warn("Invalid refresh token provided (validation failed)");
            throw new AppException(ErrorCode.TOKEN_INVALID, "Invalid refresh token");
        }

        String email = tokenProvider.getUsernameFromToken(refreshToken);
        if (email == null || email.isEmpty()) {
            logger.warn("Refresh token missing email claim");
            throw new AppException(ErrorCode.TOKEN_INVALID, "Invalid refresh token: no email found");
        }

        UserTable user = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    logger.warn("User not found for refresh token: {}", email);
                    return new AppException(ErrorCode.USER_NOT_FOUND, "User not found");
                });

        if (user.getEnabled() == null || !user.getEnabled()) {
            logger.warn("Account disabled for user: {}", email);
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }

        // Get user roles from UserTable
        java.util.Set<String> roles = user.getRoles();
        java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities = roles.stream()
                    .map((role) -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // Generate new tokens (Zero Trust: token rotation)
        // Old refresh token is implicitly invalidated (not tracked, but new token issued)
        // Create UserDetails object for token generation
        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || passwordHash.isEmpty()) {
            logger.warn("User {} has no password hash during token refresh. Using empty string.", email);
            passwordHash = ""; // UserDetails requires non-null password
        }
        
        org.springframework.security.core.userdetails.UserDetails userDetails = 
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(passwordHash)
                        .authorities(authorities)
                        .accountExpired(false)
                        .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                        .credentialsExpired(false)
                        .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                        .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        
        // Zero Trust: Generate short-lived access token (15 minutes)
        String newAccessToken = tokenProvider.generateToken(authentication);
        
        // Zero Trust: Generate new refresh token (30 days, will be encrypted in keychain)
        String newRefreshToken = tokenProvider.generateRefreshToken(email);

        Date expirationDate = tokenProvider.getExpirationDateFromToken(newAccessToken);
        if (expirationDate == null) {
            // Fallback: set expiration to 15 minutes from now (matches Zero Trust config)
            expirationDate = new Date(System.currentTimeMillis() + 900000L);
        }
        LocalDateTime expiresAt = expirationDate.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        AuthResponse.UserInfo userInfo = new AuthResponse.UserInfo(
                user.getUserId(),
                user.getEmail(),
                user.getFirstName() != null ? user.getFirstName() : "",
                user.getLastName() != null ? user.getLastName() : ""
        );

        logger.info("Token refreshed successfully for user: {} | New access token expires in 15 minutes", email);
        return new AuthResponse(newAccessToken, newRefreshToken, expiresAt, userInfo);
    }

    /**
     * Generate new tokens for a user (used for PIN verification)
     * This method creates new access and refresh tokens without requiring authentication
     */
    public AuthResponse generateTokensForUser(final UserTable user) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }

        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }

        // Get user roles from UserTable
        java.util.Set<String> roles = user.getRoles();
        java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities = roles.stream()
                    .map((role) -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // Create UserDetails object for token generation
        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || passwordHash.isEmpty()) {
            logger.warn("User {} has no password hash during token generation. Using empty string.", user.getEmail());
            passwordHash = "";
        }
        
        org.springframework.security.core.userdetails.UserDetails userDetails = 
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(passwordHash)
                        .authorities(authorities)
                        .accountExpired(false)
                        .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                        .credentialsExpired(false)
                        .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                        .build();

        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, authorities);
        String newAccessToken = tokenProvider.generateToken(authentication);
        String newRefreshToken = tokenProvider.generateRefreshToken(user.getEmail());

        Date expirationDate = tokenProvider.getExpirationDateFromToken(newAccessToken);
        if (expirationDate == null) {
            expirationDate = new Date(System.currentTimeMillis() + 86400000L);
        }
        LocalDateTime expiresAt = expirationDate.toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();

        AuthResponse.UserInfo userInfo = new AuthResponse.UserInfo(
                user.getUserId(),
                user.getEmail(),
                user.getFirstName() != null ? user.getFirstName() : "",
                user.getLastName() != null ? user.getLastName() : ""
        );

        return new AuthResponse(newAccessToken, newRefreshToken, expiresAt, userInfo);
    }
}
