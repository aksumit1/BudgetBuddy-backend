package com.budgetbuddy.security;

import com.budgetbuddy.aws.secrets.SecretsManagerService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT Token Provider for generating and validating JWT tokens
 * Supports AWS Secrets Manager for secure secret storage
 */
@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    private final SecretsManagerService secretsManagerService;

    @Value("${app.jwt.secret:}")
    private String jwtSecretFallback;

    @Value("${app.jwt.secret-name:budgetbuddy/jwt-secret}")
    private String jwtSecretName;

    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    @Value("${app.jwt.refresh-expiration}")
    private long refreshExpiration;

    // Cache the JWT secret to ensure consistency between token generation and validation
    // This prevents issues where the secret might change between calls
    private volatile String cachedJwtSecret = null;
    private volatile SecretKey cachedSigningKey = null;
    private final Object secretLock = new Object();

    public JwtTokenProvider(final SecretsManagerService secretsManagerService) {
        this.secretsManagerService = secretsManagerService;
    }

    private SecretKey getSigningKey() {
        // Use cached secret if available to ensure consistency
        if (cachedSigningKey != null) {
            return cachedSigningKey;
        }

        synchronized (secretLock) {
            // Double-check after acquiring lock
            if (cachedSigningKey != null) {
                return cachedSigningKey;
            }

            String secret = getJwtSecret();
            SecretKey signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            
            // Cache both secret and signing key
            cachedJwtSecret = secret;
            cachedSigningKey = signingKey;
            
            logger.debug("JWT signing key initialized and cached | Secret source: {}", 
                    cachedJwtSecret.equals(jwtSecretFallback) ? "fallback" : "Secrets Manager");
            
            return signingKey;
        }
    }

    private String getJwtSecret() {
        // Return cached secret if available
        if (cachedJwtSecret != null) {
            return cachedJwtSecret;
        }

        synchronized (secretLock) {
            // Double-check after acquiring lock
            if (cachedJwtSecret != null) {
                return cachedJwtSecret;
            }

            String secret = null;
            try {
                // Try to get from Secrets Manager first
                secret = secretsManagerService.getSecret(jwtSecretName, "JWT_SECRET");
                if (secret != null && !secret.isEmpty()) {
                    logger.info("JWT secret loaded from Secrets Manager: {}", jwtSecretName);
                    cachedJwtSecret = secret;
                    return secret;
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch JWT secret from Secrets Manager, using fallback: {}", e.getMessage());
            }

            // Fallback to environment variable or configuration
            if (jwtSecretFallback != null && !jwtSecretFallback.isEmpty()) {
                logger.info("JWT secret loaded from fallback (environment variable or configuration)");
                cachedJwtSecret = jwtSecretFallback;
                return jwtSecretFallback;
            }

            throw new IllegalStateException("JWT secret not configured. Set app.jwt.secret or configure AWS Secrets Manager.");
        }
    }

    /**
     * Generate JWT token for user
     */
    public String generateToken(final Authentication authentication) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userPrincipal.getUsername(), jwtExpiration);
    }

    /**
     * Generate refresh token
     */
    public String generateRefreshToken(final String username) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, username, refreshExpiration);
    }

    /**
     * Create JWT token
     */
    private String createToken(final Map<String, Object> claims, final String subject, final long expiration) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey(), Jwts.SIG.HS512)
                .compact();
    }

    /**
     * Get username from token
     */
    public String getUsernameFromToken(final String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    /**
     * Get expiration date from token
     */
    public Date getExpirationDateFromToken(final String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    /**
     * Get claim from token
     */
    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Get all claims from token
     */
    private Claims getAllClaimsFromToken(final String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Check if token is expired
     */
    private Boolean isTokenExpired(final String token) {
        final Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }

    /**
     * Validate token
     */
    public Boolean validateToken(final String token, final UserDetails userDetails) {
        try {
            final String username = getUsernameFromToken(token);
            return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
        } catch (JwtException | IllegalArgumentException e) {
            logger.error("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validate token without user details
     */
    public Boolean validateToken(final String token) {
        if (token == null || token.isEmpty()) {
            logger.error("JWT token is null or empty");
            return false;
        }
        
        // Clean control characters before validation
        String cleanedToken = cleanControlCharacters(token);
        if (!cleanedToken.equals(token)) {
            logger.warn("JWT token contained control characters, cleaned before validation");
        }
        
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(cleanedToken);
            return true;
        } catch (ExpiredJwtException e) {
            logger.warn("JWT token is expired: {} | Token preview: {}", 
                    e.getMessage(), cleanedToken.length() > 20 ? cleanedToken.substring(0, 20) + "..." : cleanedToken);
        } catch (UnsupportedJwtException e) {
            logger.warn("JWT token is unsupported: {} | Token preview: {}", 
                    e.getMessage(), cleanedToken.length() > 20 ? cleanedToken.substring(0, 20) + "..." : cleanedToken);
        } catch (MalformedJwtException e) {
            logger.warn("Invalid JWT token: {} | Token preview: {}", 
                    e.getMessage(), cleanedToken.length() > 20 ? cleanedToken.substring(0, 20) + "..." : cleanedToken);
        } catch (IllegalArgumentException e) {
            logger.warn("JWT claims string is empty: {} | Token preview: {}", 
                    e.getMessage(), cleanedToken.length() > 20 ? cleanedToken.substring(0, 20) + "..." : cleanedToken);
        } catch (io.jsonwebtoken.security.SignatureException e) {
            logger.warn("JWT signature verification failed: {} | This may indicate a secret mismatch | Token preview: {}", 
                    e.getMessage(), cleanedToken.length() > 20 ? cleanedToken.substring(0, 20) + "..." : cleanedToken);
        }
        return false;
    }
    
    /**
     * Remove control characters from token string
     * Control characters (0-31) except whitespace (\r, \n, \t) are not allowed in JWT tokens
     */
    private String cleanControlCharacters(final String token) {
        if (token == null || token.isEmpty()) {
            return token;
        }
        
        StringBuilder cleaned = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            // Keep printable characters and whitespace (\r, \n, \t)
            // Remove control characters (0-31) except whitespace
            if (c >= 32 || c == '\r' || c == '\n' || c == '\t') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }
}

