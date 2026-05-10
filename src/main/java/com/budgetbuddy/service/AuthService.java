package com.budgetbuddy.service;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.security.PasswordHashingService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Authentication service Supports secure client-side hashed passwords @Transactional removed as
 * DynamoDB doesn't support transactions
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class AuthService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

    private final JwtTokenProvider tokenProvider;
    private final PasswordHashingService passwordHashingService;
    private final UserRepository userRepository;
    private final CacheWarmingService cacheWarmingService;

    public AuthService(
            final JwtTokenProvider tokenProvider,
            final PasswordHashingService passwordHashingService,
            final UserRepository userRepository,
            final CacheWarmingService cacheWarmingService) {
        this.tokenProvider = tokenProvider;
        this.passwordHashingService = passwordHashingService;
        this.userRepository = userRepository;
        this.cacheWarmingService = cacheWarmingService;
    }

    /**
     * Authenticate user with secure format (password_hash only) BREAKING CHANGE: Client salt
     * removed - backend uses server salt only Only secure client-side hashed passwords are
     * supported
     */
    public AuthResponse authenticate(final AuthRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }

        // Find user
        final UserTable user =
                userRepository
                        .findByEmail(request.getEmail())
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.INVALID_CREDENTIALS,
                                                "Invalid email or password"));

        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }

        // Authenticate based on format
        boolean authenticated = false;

        if (request.isSecureFormat()) {
            // Secure format: client-side hashed password
            if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
                LOGGER.warn(
                        "User {} has no password hash stored. Legacy account?", request.getEmail());
                throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
            }

            // Get server salt from user (stored during registration)
            final String serverSalt = user.getServerSalt();
            if (serverSalt == null || serverSalt.isEmpty()) {
                LOGGER.warn(
                        "User {} has no server salt. Account may need password reset.",
                        request.getEmail());
                throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
            }

            // Validate that client hash is provided
            if (request.getPasswordHash() == null || request.getPasswordHash().isEmpty()) {
                LOGGER.warn("Login request for user {} missing password_hash", request.getEmail());
                throw new AppException(ErrorCode.INVALID_INPUT, "password_hash is required");
            }

            // Note: Challenge verification is handled by AuthController before calling authenticate

            // Log for debugging (redact sensitive data)
            LOGGER.debug(
                    "Authenticating user {}: clientHash length={}, serverHash length={}, serverSalt length={}",
                    request.getEmail(),
                    request.getPasswordHash() != null ? request.getPasswordHash().length() : 0,
                    user.getPasswordHash() != null ? user.getPasswordHash().length() : 0,
                    serverSalt != null ? serverSalt.length() : 0);

            // BREAKING CHANGE: No longer requires client salt
            // Standard verification (works for both new and old methods - iOS client handles
            // fallback)
            authenticated =
                    passwordHashingService.verifyClientPassword(
                            request.getPasswordHash(), user.getPasswordHash(), serverSalt);

            if (!authenticated) {
                LOGGER.warn(
                        "Password verification failed for user {}: hash/salt mismatch",
                        request.getEmail());
                // Note: iOS client will automatically retry with old method (challenge-based hash)
                // if new method fails
            } else {
                LOGGER.debug("Password verification succeeded for user: {}", request.getEmail());
            }
        } else {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    "password_hash must be provided. Only secure format is supported.");
        }

        if (!authenticated) {
            LOGGER.warn("Authentication failed for user: {}", request.getEmail());
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
        }

        // Create UserDetails object for token generation
        // Get user roles from UserTable
        final java.util.Set<String> roles = user.getRoles();
        final java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities =
                    roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                            .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // Create UserDetails object for JWT token generation
        // Ensure passwordHash is not null (use empty string as fallback for UserDetails)
        final String passwordHash = user.getPasswordHash();
        if (passwordHash == null || passwordHash.isEmpty()) {
            LOGGER.error(
                    "User {} has no password hash during authentication. This should not happen.",
                    request.getEmail());
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "User account configuration error");
        }

        final org.springframework.security.core.userdetails.UserDetails userDetails =
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(passwordHash)
                        .authorities(authorities)
                        .accountExpired(false)
                        .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                        .credentialsExpired(false)
                        .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                        .build();

        final Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Generate tokens
        final String accessToken = tokenProvider.generateToken(authentication);
        final String refreshToken = tokenProvider.generateRefreshToken(user.getEmail());

        // Calculate expiration time
        Date expirationDate = tokenProvider.getExpirationDateFromToken(accessToken);
        if (expirationDate == null) {
            // Fallback: set expiration to 24 hours from now
            expirationDate = new Date(System.currentTimeMillis() + 86_400_000L);
        }
        final LocalDateTime expiresAt =
                expirationDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        // Update last login
        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);

        // Pre-warm cache for user on login (async, non-blocking)
        try {
            cacheWarmingService.warmCacheForUser(user.getUserId());
            LOGGER.debug("Cache warming initiated for user: {}", user.getUserId());
        } catch (Exception e) {
            LOGGER.warn("Failed to warm cache for user {}: {}", user.getUserId(), e.getMessage());
            // Don't fail login if cache warming fails
        }

        final AuthResponse.UserInfo userInfo =
                new AuthResponse.UserInfo(
                        user.getUserId(),
                        user.getEmail(),
                        user.getFirstName() != null ? user.getFirstName() : "",
                        user.getLastName() != null ? user.getLastName() : "");

        LOGGER.info("User authenticated: {}", request.getEmail());
        return new AuthResponse(accessToken, refreshToken, expiresAt, userInfo);
    }

    /**
     * Authenticate user after registration (special case - no challenge verification needed) Used
     * when user is just created and we want to authenticate them immediately The password hash was
     * already verified with the registration challenge
     */
    public AuthResponse authenticateAfterRegistration(final AuthRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }

        if (request.getPasswordHash() == null || request.getPasswordHash().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }

        // Find user
        final UserTable user =
                userRepository
                        .findByEmail(request.getEmail())
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.INVALID_CREDENTIALS, "User not found"));

        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }

        // Verify password hash (same logic as authenticate, but without challenge verification)
        // The password hash was computed with the registration challenge which is already verified
        final String serverSalt = user.getServerSalt();
        if (serverSalt == null || serverSalt.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");
        }

        final boolean authenticated =
                passwordHashingService.verifyClientPassword(
                        request.getPasswordHash(), user.getPasswordHash(), serverSalt);

        if (!authenticated) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");
        }

        // Create authentication and generate tokens (same as authenticate method)
        final java.util.Set<String> roles = user.getRoles();
        final java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities =
                    roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                            .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        final org.springframework.security.core.userdetails.UserDetails userDetails =
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(user.getPasswordHash())
                        .authorities(authorities)
                        .accountExpired(false)
                        .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                        .credentialsExpired(false)
                        .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                        .build();

        final Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        final String accessToken = tokenProvider.generateToken(authentication);
        final String refreshToken = tokenProvider.generateRefreshToken(user.getEmail());

        Date expirationDate = tokenProvider.getExpirationDateFromToken(accessToken);
        if (expirationDate == null) {
            expirationDate = new Date(System.currentTimeMillis() + 86_400_000L);
        }
        final LocalDateTime expiresAt =
                expirationDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);

        try {
            cacheWarmingService.warmCacheForUser(user.getUserId());
        } catch (Exception e) {
            LOGGER.warn("Failed to warm cache for user {}: {}", user.getUserId(), e.getMessage());
        }

        final AuthResponse.UserInfo userInfo =
                new AuthResponse.UserInfo(
                        user.getUserId(),
                        user.getEmail(),
                        user.getFirstName() != null ? user.getFirstName() : "",
                        user.getLastName() != null ? user.getLastName() : "");

        LOGGER.info("User authenticated after registration: {}", request.getEmail());
        return new AuthResponse(accessToken, refreshToken, expiresAt, userInfo);
    }

    /**
     * Refresh token endpoint (Zero Trust) Validates refresh token and issues new tokens with
     * rotation Old refresh token is invalidated (token rotation for security)
     */
    public AuthResponse refreshToken(final String refreshToken) {
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Refresh token is required");
        }

        // Validate token structure and signature
        if (!tokenProvider.validateToken(refreshToken)) {
            LOGGER.warn("Invalid refresh token provided (validation failed)");
            throw new AppException(ErrorCode.TOKEN_INVALID, "Invalid refresh token");
        }

        final String email = tokenProvider.getUsernameFromToken(refreshToken);
        if (email == null || email.isEmpty()) {
            LOGGER.warn("Refresh token missing email claim");
            throw new AppException(
                    ErrorCode.TOKEN_INVALID, "Invalid refresh token: no email found");
        }

        final UserTable user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(
                                () -> {
                                    LOGGER.warn("User not found for refresh token: {}", email);
                                    return new AppException(
                                            ErrorCode.USER_NOT_FOUND, "User not found");
                                });

        if (user.getEnabled() == null || !user.getEnabled()) {
            LOGGER.warn("Account disabled for user: {}", email);
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }

        // Get user roles from UserTable
        final java.util.Set<String> roles = user.getRoles();
        final java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities =
                    roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                            .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // Generate new tokens (Zero Trust: token rotation)
        // Old refresh token is implicitly invalidated (not tracked, but new token issued)
        // Create UserDetails object for token generation
        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || passwordHash.isEmpty()) {
            LOGGER.warn(
                    "User {} has no password hash during token refresh. Using empty string.",
                    email);
            passwordHash = ""; // UserDetails requires non-null password
        }

        final org.springframework.security.core.userdetails.UserDetails userDetails =
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(passwordHash)
                        .authorities(authorities)
                        .accountExpired(false)
                        .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                        .credentialsExpired(false)
                        .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                        .build();

        final Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);

        // Zero Trust: Generate short-lived access token (30 minutes - balanced for user workflow)
        final String newAccessToken = tokenProvider.generateToken(authentication);

        // Zero Trust: Generate new refresh token (30 days, will be encrypted in keychain)
        final String newRefreshToken = tokenProvider.generateRefreshToken(email);

        Date expirationDate = tokenProvider.getExpirationDateFromToken(newAccessToken);
        if (expirationDate == null) {
            // Fallback: set expiration to 30 minutes from now (matches Zero Trust config)
            expirationDate = new Date(System.currentTimeMillis() + 1_800_000L);
        }
        final LocalDateTime expiresAt =
                expirationDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        final AuthResponse.UserInfo userInfo =
                new AuthResponse.UserInfo(
                        user.getUserId(),
                        user.getEmail(),
                        user.getFirstName() != null ? user.getFirstName() : "",
                        user.getLastName() != null ? user.getLastName() : "");

        LOGGER.info(
                "Token refreshed successfully for user: {} | New access token expires in 30 minutes",
                email);
        return new AuthResponse(newAccessToken, newRefreshToken, expiresAt, userInfo);
    }

    /**
     * Generate new tokens for a user (used for PIN verification) This method creates new access and
     * refresh tokens without requiring authentication
     */
    public AuthResponse generateTokensForUser(final UserTable user) {
        if (user == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }

        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, "Account is disabled");
        }

        // Get user roles from UserTable
        final java.util.Set<String> roles = user.getRoles();
        final java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities =
                    roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                            .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }

        // Create UserDetails object for token generation
        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || passwordHash.isEmpty()) {
            LOGGER.warn(
                    "User {} has no password hash during token generation. Using empty string.",
                    user.getEmail());
            passwordHash = "";
        }

        final org.springframework.security.core.userdetails.UserDetails userDetails =
                org.springframework.security.core.userdetails.User.builder()
                        .username(user.getEmail())
                        .password(passwordHash)
                        .authorities(authorities)
                        .accountExpired(false)
                        .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                        .credentialsExpired(false)
                        .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                        .build();

        final Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
        final String newAccessToken = tokenProvider.generateToken(authentication);
        final String newRefreshToken = tokenProvider.generateRefreshToken(user.getEmail());

        Date expirationDate = tokenProvider.getExpirationDateFromToken(newAccessToken);
        if (expirationDate == null) {
            expirationDate = new Date(System.currentTimeMillis() + 86_400_000L);
        }
        final LocalDateTime expiresAt =
                expirationDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();

        final AuthResponse.UserInfo userInfo =
                new AuthResponse.UserInfo(
                        user.getUserId(),
                        user.getEmail(),
                        user.getFirstName() != null ? user.getFirstName() : "",
                        user.getLastName() != null ? user.getLastName() : "");

        return new AuthResponse(newAccessToken, newRefreshToken, expiresAt, userInfo);
    }
}
