package com.budgetbuddy.plaid;

import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Verifies Plaid webhook signatures per <a
 * href="https://plaid.com/docs/api/webhooks/webhook-verification/">Plaid's spec</a>: the {@code
 * Plaid-Verification} header is an ES256 JWT whose payload contains a SHA-256 of the raw request
 * body and an {@code iat} timestamp. The signing key is fetched via {@code
 * /webhook_verification_key/get} and identified by the JWT's {@code kid} header.
 *
 * <p>Verification keys are cached by {@code kid} for {@link #KEY_CACHE_TTL} to avoid hitting Plaid
 * on every webhook. The {@code iat} claim must be within {@link #IAT_TOLERANCE} of server time to
 * defend against replay; this matches Plaid's documented window.
 */
@Service
public class PlaidWebhookVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlaidWebhookVerifier.class);

    /** How long a fetched JWK stays cached. Plaid rotates infrequently; 24h is generous. */
    /* default */ static final Duration KEY_CACHE_TTL = Duration.ofHours(24);

    /** Allowed clock skew between Plaid's {@code iat} and server time. Matches Plaid's docs. */
    /* default */ static final Duration IAT_TOLERANCE = Duration.ofMinutes(5);

    private final PlaidService plaidService;
    private final ConcurrentHashMap<String, CachedKey> keyCache = new ConcurrentHashMap<>();

    public PlaidWebhookVerifier(final PlaidService plaidService) {
        this.plaidService = plaidService;
    }

    /**
     * Verify a Plaid webhook. Returns true only when the JWT signs the exact bytes of {@code
     * rawBody}, the {@code iat} is fresh, and the signing key was fetched from Plaid.
     *
     * @param rawBody the unmodified HTTP request body bytes as received (string form)
     * @param verificationHeader the {@code Plaid-Verification} header value (a compact JWT)
     */
    public boolean verify(final String rawBody, final String verificationHeader) {
        if (rawBody == null || verificationHeader == null || verificationHeader.isBlank()) {
            return false;
        }
        try {
            final Claims claims =
                    Jwts.parser()
                            .keyLocator(
                                    header -> {
                                        final Object kid = header.get("kid");
                                        if (!(kid instanceof String kidStr) || kidStr.isBlank()) {
                                            throw new JwtException(
                                                    "Plaid verification header missing kid");
                                        }
                                        final Object alg = header.get("alg");
                                        if (!"ES256".equals(alg)) {
                                            throw new JwtException(
                                                    "Plaid verification header alg must be ES256,"
                                                            + " got: "
                                                            + alg);
                                        }
                                        return resolveKey(kidStr)
                                                .orElseThrow(
                                                        () ->
                                                                new JwtException(
                                                                        "No verification key for"
                                                                                + " kid="
                                                                                + kidStr));
                                    })
                            .build()
                            .parseSignedClaims(verificationHeader)
                            .getPayload();

            final Number iat = claims.get("iat", Number.class);
            if (iat == null) {
                LOGGER.warn("Plaid webhook JWT missing iat claim");
                return false;
            }
            final long nowEpoch = Instant.now().getEpochSecond();
            if (Math.abs(nowEpoch - iat.longValue()) > IAT_TOLERANCE.toSeconds()) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Plaid webhook iat outside tolerance: iat={} now={} toleranceSec={}",
                            iat.longValue(),
                            nowEpoch,
                            IAT_TOLERANCE.toSeconds());
                }
                return false;
            }

            final String expectedHash = claims.get("request_body_sha256", String.class);
            if (expectedHash == null || expectedHash.isBlank()) {
                LOGGER.warn("Plaid webhook JWT missing request_body_sha256 claim");
                return false;
            }
            final String actualHash = sha256Hex(rawBody.getBytes(StandardCharsets.UTF_8));

            // Constant-time comparison
            final byte[] expectedBytes =
                    expectedHash.toLowerCase().getBytes(StandardCharsets.US_ASCII);
            final byte[] actualBytes = actualHash.getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
                LOGGER.warn(
                        "Plaid webhook body hash mismatch (expectedLen={} actualLen={})",
                        expectedBytes.length,
                        actualBytes.length);
                return false;
            }
            return true;
        } catch (JwtException e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Plaid webhook JWT verification failed: {}", e.getMessage());
            }
            return false;
        }
    }

    private Optional<ECPublicKey> resolveKey(final String kid) {
        final Instant now = Instant.now();
        final CachedKey cached = keyCache.get(kid);
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return Optional.of(cached.key);
        }
        try {
            final WebhookVerificationKeyGetResponse response =
                    plaidService.webhookVerificationKeyGet(kid);
            final JWKPublicKey jwk = response.getKey();
            if (jwk == null) {
                return Optional.empty();
            }
            if (jwk.getExpiredAt() != null
                    && jwk.getExpiredAt().longValue() < now.getEpochSecond()) {
                LOGGER.warn("Plaid JWK for kid={} is already expired", kid);
                return Optional.empty();
            }
            final ECPublicKey key = buildEcKey(jwk);
            keyCache.put(kid, new CachedKey(key, now.plus(KEY_CACHE_TTL)));
            return Optional.of(key);
        } catch (GeneralSecurityException | RuntimeException e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Failed to resolve Plaid JWK for kid={}: {}", kid, e.getMessage(), e);
            }
            return Optional.empty();
        }
    }

    private static ECPublicKey buildEcKey(final JWKPublicKey jwk) throws GeneralSecurityException {
        if (!"EC".equals(jwk.getKty())) {
            throw new GeneralSecurityException("Unsupported kty: " + jwk.getKty());
        }
        if (!"P-256".equals(jwk.getCrv())) {
            throw new GeneralSecurityException("Unsupported crv: " + jwk.getCrv());
        }
        if (jwk.getX() == null || jwk.getY() == null) {
            throw new GeneralSecurityException("JWK missing x or y coordinate");
        }
        final Base64.Decoder urlDecoder = Base64.getUrlDecoder();
        final BigInteger x = new BigInteger(1, urlDecoder.decode(jwk.getX()));
        final BigInteger y = new BigInteger(1, urlDecoder.decode(jwk.getY()));
        final ECPoint point = new ECPoint(x, y);

        final AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        final ECParameterSpec ecParams = params.getParameterSpec(ECParameterSpec.class);

        final ECPublicKeySpec spec = new ECPublicKeySpec(point, ecParams);
        final KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(spec);
    }

    private static String sha256Hex(final byte[] bytes) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record CachedKey(ECPublicKey key, Instant expiresAt) {
        // jjwt's KeyLocator expects a Key; ECPublicKey extends Key so this is compatible.
        /* default */ Key asKey() {
            return key;
        }
    }
}
