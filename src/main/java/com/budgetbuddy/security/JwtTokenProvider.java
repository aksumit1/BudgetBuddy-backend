package com.budgetbuddy.security;

import com.budgetbuddy.aws.secrets.SecretsManagerService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

/**
 * JWT Token Provider for generating and validating JWT tokens Supports AWS Secrets Manager for
 * secure secret storage
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Component
public class JwtTokenProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretsManagerService secretsManagerService;

    @Value("${app.jwt.secret:}")
    private String jwtSecretFallback;

    @Value("${app.jwt.secret-name:budgetbuddy/jwt-secret}")
    private String jwtSecretName;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    @Value("${app.jwt.refresh-expiration}")
    private long refreshExpiration;

    // TTL-bounded cache of the JWT signing secret. Without a TTL, rotating the secret in
    // AWS Secrets Manager required a redeploy of the service before new tokens were honoured;
    // any cached worker would have continued to sign with the old material indefinitely.
    // We refresh on demand: every {@link #SECRET_REFRESH_TTL_MS} ms a getSigningKey() call
    // re-reads Secrets Manager; if the fetched value differs from the cached one, the
    // SecretKey is rebuilt atomically. Tokens issued under the previous key will fail
    // verification after rotation — the trade-off for not running a multi-key keyring; that
    // matches the existing fallback behaviour where a redeploy would have invalidated them
    // anyway. Configurable via {@code app.jwt.secret-refresh-ttl-ms} (default 5 min).
    @Value("${app.jwt.secret-refresh-ttl-ms:300000}")
    private long secretRefreshTtlMs;

    /** Default 5 min — bounds rotation lag while keeping Secrets Manager call volume tiny. */
    private static final long SECRET_REFRESH_TTL_MS = 300_000L;

    private volatile String cachedJwtSecret;
    private volatile SecretKey cachedSigningKey;
    private volatile long lastSecretFetchMillis;
    private final Object secretLock = new Object();

    public JwtTokenProvider(final SecretsManagerService secretsManagerService) {
        this.secretsManagerService = secretsManagerService;
    }

    private SecretKey getSigningKey() {
        // Refresh the raw secret first (TTL-bounded); rebuild SecretKey iff the secret changed
        // (or has never been built yet for the current cached value).
        final String secret = getJwtSecret();
        if (cachedSigningKey != null) {
            return cachedSigningKey;
        }
        synchronized (secretLock) {
            if (cachedSigningKey != null) {
                return cachedSigningKey;
            }
            cachedSigningKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "JWT signing key (re)built (source: {})",
                        secret.equals(jwtSecretFallback) ? "fallback" : "Secrets Manager");
            }
            return cachedSigningKey;
        }
    }

    private String getJwtSecret() {
        // Honour the same TTL as the SecretKey cache so callers that ask for the raw secret
        // (legacy paths, tests that ReflectionTestUtils.invokeMethod into this) see the same
        // rotation behaviour as JWT issuance/verification — without re-deriving the SecretKey
        // here, which would fail for shorter (test-fixture) secrets that aren't HS512-grade.
        final long now = System.currentTimeMillis();
        final long ttl = secretRefreshTtlMs > 0 ? secretRefreshTtlMs : SECRET_REFRESH_TTL_MS;
        if (cachedJwtSecret != null && now - lastSecretFetchMillis < ttl) {
            return cachedJwtSecret;
        }
        synchronized (secretLock) {
            final long nowInside = System.currentTimeMillis();
            if (cachedJwtSecret != null && nowInside - lastSecretFetchMillis < ttl) {
                return cachedJwtSecret;
            }
            final String fresh = fetchJwtSecret();
            if (!fresh.equals(cachedJwtSecret)) {
                cachedJwtSecret = fresh;
                // SecretKey is invalidated on swap — getSigningKey will rebuild lazily.
                cachedSigningKey = null;
            }
            lastSecretFetchMillis = nowInside;
            return cachedJwtSecret;
        }
    }

    private String fetchJwtSecret() {
        try {
            String secret = secretsManagerService.getSecret(jwtSecretName, "JWT_SECRET");
            if (secret != null && !secret.isEmpty()) {
                return secret;
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Failed to fetch JWT secret from Secrets Manager, using fallback: {}",
                        e.getMessage());
            }
        }

        // Fallback to environment variable or configuration
        if (jwtSecretFallback != null && !jwtSecretFallback.isEmpty()) {
            if ("your-256-bit-secret-key-change-in-production".equals(jwtSecretFallback)) {
                LOGGER.error(
                        "JWT secret is using placeholder value. Please set JWT_SECRET environment"
                                + " variable or app.jwt.secret property.");
                throw new IllegalStateException(
                        "JWT secret is not configured. Please set JWT_SECRET environment variable"
                                + " or app.jwt.secret property. The default placeholder value is"
                                + " not secure and must be changed in production.");
            }
            return jwtSecretFallback;
        }

        throw new IllegalStateException(
                "JWT secret not configured. Set app.jwt.secret or configure AWS Secrets Manager.");
    }

    /** Generate JWT token for user */
    public String generateToken(final Authentication authentication) {
        final UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        final Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userPrincipal.getUsername(), jwtExpiration);
    }

    /** Generate refresh token */
    public String generateRefreshToken(final String username) {
        final Map<String, Object> claims = new HashMap<>();
        return createToken(claims, username, refreshExpiration);
    }

    /**
     * Generate a short-lived connection token scoped for the MCP
     * server. Issued by {@code POST /mcp/connection-token} so the
     * user can paste it into Claude Desktop / Cursor / any MCP-aware
     * client without exposing their long-lived session JWT. Uses the
     * same signing key + subject so the existing JWT filter accepts
     * it; the {@code scope=mcp} claim is informational for downstream
     * audit + future scope-narrowing.
     *
     * @param username the authenticated user's email
     * @param ttlMillis token lifetime in ms — caller picks; the
     *     controller defaults to 24h.
     */
    public String generateMcpConnectionToken(final String username, final long ttlMillis) {
        final Map<String, Object> claims = new HashMap<>();
        claims.put("scope", "mcp");
        return createToken(claims, username, ttlMillis);
    }

    /** Create JWT token */
    private String createToken(
            final Map<String, Object> claims, final String subject, final long expiration) {
        final Date now = new Date();
        final Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    /** Get username from token */
    public String getUsernameFromToken(final String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    /** Get expiration date from token */
    public Date getExpirationDateFromToken(final String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    /** Get claim from token */
    public <T> T getClaimFromToken(final String token, final Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }

    /** Get all claims from token */
    private Claims getAllClaimsFromToken(final String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** Check if token is expired */
    private Boolean isTokenExpired(final String token) {
        final Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }

    /** Validate token */
    public Boolean validateToken(final String token, final UserDetails userDetails) {
        try {
            final String username = getUsernameFromToken(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Invalid JWT token: {}", e.getMessage());
            }
            return false;
        }
    }

    /** Validate token without user details */
    public Boolean validateToken(final String token) {
        if (token == null || token.isEmpty()) {
            LOGGER.error("JWT token is null or empty");
            return false;
        }

        // Clean control characters before validation
        final String cleanedToken = cleanControlCharacters(token);
        if (!cleanedToken.equals(token)) {
            LOGGER.warn("JWT token contained control characters, cleaned before validation");
        }

        try {
            Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(cleanedToken);
            return true;
        } catch (ExpiredJwtException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "JWT token is expired: {} | Token preview: {}",
                        e.getMessage(),
                        cleanedToken.length() > 20
                                ? cleanedToken.substring(0, 20) + "..."
                                : cleanedToken);
            }
        } catch (UnsupportedJwtException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "JWT token is unsupported: {} | Token preview: {}",
                        e.getMessage(),
                        cleanedToken.length() > 20
                                ? cleanedToken.substring(0, 20) + "..."
                                : cleanedToken);
            }
        } catch (MalformedJwtException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Invalid JWT token: {} | Token preview: {}",
                        e.getMessage(),
                        cleanedToken.length() > 20
                                ? cleanedToken.substring(0, 20) + "..."
                                : cleanedToken);
            }
        } catch (IllegalArgumentException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "JWT claims string is empty: {} | Token preview: {}",
                        e.getMessage(),
                        cleanedToken.length() > 20
                                ? cleanedToken.substring(0, 20) + "..."
                                : cleanedToken);
            }
        } catch (io.jsonwebtoken.security.SignatureException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "JWT signature verification failed: {} | This may indicate a secret mismatch | Token preview: {}",
                        e.getMessage(),
                        cleanedToken.length() > 20
                                ? cleanedToken.substring(0, 20) + "..."
                                : cleanedToken);
            }
        }
        return false;
    }

    /**
     * Remove control characters from token string Control characters (0-31) except whitespace (\r,
     * \n, \t) are not allowed in JWT tokens
     */
    private String cleanControlCharacters(final String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }

        final StringBuilder cleaned = new StringBuilder(token.length());
        for (final char c : token.toCharArray()) {
            // Keep printable characters and whitespace (\r, \n, \t)
            // Remove control characters (0-31) except whitespace
            if (c >= 32 || c == '\r' || c == '\n' || c == '\t') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }
}
