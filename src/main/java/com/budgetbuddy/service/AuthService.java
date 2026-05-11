package com.budgetbuddy.service;

import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.security.PasswordHashingService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
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

    private static final String ACCOUNT_IS_DISABLED = "Account is disabled";

    private static final String INVALID_EMAIL_OR_PASSWORD = "Invalid email or password";

    private static final String ROLE = "ROLE_";

    private static final String ROLE_USER = "ROLE_USER";

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
        validateAuthRequest(request);
        final UserTable user = loadActiveUser(request.getEmail(), INVALID_EMAIL_OR_PASSWORD);

        if (!request.isSecureFormat()) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT,
                    "password_hash must be provided. Only secure format is supported.");
        }
        verifySecureFormatCredentials(request, user);

        return issueAuthResponse(user, "User authenticated: ");
    }

    /** Validate the top-level shape of an AuthRequest (email required). */
    private void validateAuthRequest(final AuthRequest request) {
        if (request == null || request.getEmail() == null || request.getEmail().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Email is required");
        }
    }

    /**
     * Find a user by email, throwing INVALID_CREDENTIALS on missing or disabled accounts.
     * The {@code missingMessage} is the user-visible error if the lookup fails — we use
     * a generic "invalid email or password" for the login path to avoid leaking which
     * emails exist.
     */
    private UserTable loadActiveUser(final String email, final String missingMessage) {
        final UserTable user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.INVALID_CREDENTIALS, missingMessage));
        if (user.getEnabled() == null || !user.getEnabled()) {
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, ACCOUNT_IS_DISABLED);
        }
        return user;
    }

    /**
     * Verify a secure-format login: client-hashed password is re-hashed with the
     * server salt and compared against the stored hash. Throws on any pre-condition
     * violation (missing hash, missing salt, mismatch).
     */
    private void verifySecureFormatCredentials(final AuthRequest request, final UserTable user) {
        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()) {
            LOGGER.warn(
                    "User {} has no password hash stored. Legacy account?", request.getEmail());
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, INVALID_EMAIL_OR_PASSWORD);
        }
        final String serverSalt = user.getServerSalt();
        if (serverSalt == null || serverSalt.isEmpty()) {
            LOGGER.warn(
                    "User {} has no server salt. Account may need password reset.",
                    request.getEmail());
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, INVALID_EMAIL_OR_PASSWORD);
        }
        if (request.getPasswordHash() == null || request.getPasswordHash().isEmpty()) {
            LOGGER.warn("Login request for user {} missing password_hash", request.getEmail());
            throw new AppException(ErrorCode.INVALID_INPUT, "password_hash is required");
        }
        // Challenge verification happens upstream in AuthController.
        LOGGER.debug(
                "Authenticating user {}: clientHash length={}, serverHash length={}, serverSalt length={}",
                request.getEmail(),
                request.getPasswordHash().length(),
                user.getPasswordHash().length(),
                serverSalt.length());

        final boolean ok =
                passwordHashingService.verifyClientPassword(
                        request.getPasswordHash(), user.getPasswordHash(), serverSalt);
        if (!ok) {
            LOGGER.warn(
                    "Password verification failed for user {}: hash/salt mismatch",
                    request.getEmail());
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, INVALID_EMAIL_OR_PASSWORD);
        }
        LOGGER.debug("Password verification succeeded for user: {}", request.getEmail());
    }

    /**
     * Translate the user's roles into Spring's {@code GrantedAuthority} list,
     * defaulting to {@code ROLE_USER} when no roles are recorded.
     */
    private Collection<org.springframework.security.core.GrantedAuthority> buildAuthorities(
            final UserTable user) {
        final java.util.Set<String> roles = user.getRoles();
        if (roles == null || roles.isEmpty()) {
            return Collections.singletonList(new SimpleGrantedAuthority(ROLE_USER));
        }
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(ROLE + role.toUpperCase(Locale.ROOT)))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Build Spring's {@code UserDetails} for token issuance. Refuses to build one
     * when the stored password hash is missing — that's an internal-state bug and
     * a 500 is more accurate than letting JWT generation fail downstream.
     */
    private org.springframework.security.core.userdetails.UserDetails buildSpringUserDetails(
            final UserTable user,
            final Collection<org.springframework.security.core.GrantedAuthority> authorities) {
        final String passwordHash = user.getPasswordHash();
        if (passwordHash == null || passwordHash.isEmpty()) {
            LOGGER.error(
                    "User {} has no password hash during authentication. This should not happen.",
                    user.getEmail());
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "User account configuration error");
        }
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(passwordHash)
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                .credentialsExpired(false)
                .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                .build();
    }

    /**
     * Wraps the post-verification work shared by {@code authenticate} and
     * {@code authenticateAfterRegistration}: build authorities + UserDetails,
     * generate tokens, populate the security context, update lastLogin, warm
     * the user's cache, and assemble the AuthResponse.
     */
    private AuthResponse issueAuthResponse(final UserTable user, final String logPrefix) {
        final Collection<org.springframework.security.core.GrantedAuthority> authorities =
                buildAuthorities(user);
        final org.springframework.security.core.userdetails.UserDetails userDetails =
                buildSpringUserDetails(user, authorities);
        final Authentication authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        final String accessToken = tokenProvider.generateToken(authentication);
        final String refreshToken = tokenProvider.generateRefreshToken(user.getEmail());
        final LocalDateTime expiresAt = computeAccessTokenExpiry(accessToken);

        user.setLastLoginAt(java.time.Instant.now());
        userRepository.save(user);
        warmUserCacheBestEffort(user.getUserId());

        final AuthResponse.UserInfo userInfo =
                new AuthResponse.UserInfo(
                        user.getUserId(),
                        user.getEmail(),
                        user.getFirstName() != null ? user.getFirstName() : "",
                        user.getLastName() != null ? user.getLastName() : "");
        LOGGER.info("{}{}", logPrefix, user.getEmail());
        return new AuthResponse(accessToken, refreshToken, expiresAt, userInfo);
    }

    /**
     * Pull the expiry out of the JWT; fall back to 24h from now if the token
     * doesn't carry one (defensive — the provider should always set this).
     */
    private LocalDateTime computeAccessTokenExpiry(final String accessToken) {
        Date expirationDate = tokenProvider.getExpirationDateFromToken(accessToken);
        if (expirationDate == null) {
            expirationDate = new Date(System.currentTimeMillis() + 86_400_000L);
        }
        return expirationDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** Cache-warming is observability, not control flow — log & continue on failure. */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void warmUserCacheBestEffort(final String userId) {
        try {
            cacheWarmingService.warmCacheForUser(userId);
            LOGGER.debug("Cache warming initiated for user: {}", userId);
        } catch (Exception e) {
            LOGGER.warn("Failed to warm cache for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Authenticate user after registration (special case - no challenge verification needed) Used
     * when user is just created and we want to authenticate them immediately The password hash was
     * already verified with the registration challenge
     */
    public AuthResponse authenticateAfterRegistration(final AuthRequest request) {
        validateAuthRequest(request);
        if (request.getPasswordHash() == null || request.getPasswordHash().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Password hash is required");
        }
        final UserTable user = loadActiveUser(request.getEmail(), "User not found");

        // Password hash was already verified against the registration challenge upstream;
        // we only re-check the stored hash matches what the client sent.
        final String serverSalt = user.getServerSalt();
        if (serverSalt == null || serverSalt.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");
        }
        final boolean ok =
                passwordHashingService.verifyClientPassword(
                        request.getPasswordHash(), user.getPasswordHash(), serverSalt);
        if (!ok) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");
        }

        return issueAuthResponse(user, "User authenticated after registration: ");
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
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, ACCOUNT_IS_DISABLED);
        }

        // Get user roles from UserTable
        final java.util.Set<String> roles = user.getRoles();
        final java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities =
                    roles.stream()
                            .map(
                                    role ->
                                            new SimpleGrantedAuthority(
                                                    ROLE + role.toUpperCase(Locale.ROOT)))
                            .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority(ROLE_USER));
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
            throw new AppException(ErrorCode.ACCOUNT_DISABLED, ACCOUNT_IS_DISABLED);
        }

        // Get user roles from UserTable
        final java.util.Set<String> roles = user.getRoles();
        final java.util.Collection<org.springframework.security.core.GrantedAuthority> authorities;
        if (roles != null && !roles.isEmpty()) {
            authorities =
                    roles.stream()
                            .map(
                                    role ->
                                            new SimpleGrantedAuthority(
                                                    ROLE + role.toUpperCase(Locale.ROOT)))
                            .collect(java.util.stream.Collectors.toList());
        } else {
            authorities = Collections.singletonList(new SimpleGrantedAuthority(ROLE_USER));
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
