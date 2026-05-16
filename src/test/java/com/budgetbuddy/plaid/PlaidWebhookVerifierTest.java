package com.budgetbuddy.plaid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.plaid.client.model.JWKPublicKey;
import com.plaid.client.model.WebhookVerificationKeyGetResponse;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Deep-validation coverage for {@link PlaidWebhookVerifier}. These tests synthesize a real
 * ES256 keypair, build a JWT exactly the way Plaid does (compact serialization, ES256 alg,
 * kid header, request_body_sha256 claim, iat claim), and then verify the verifier's
 * happy-path acceptance and rejection of every documented failure mode:
 *
 * <ul>
 *   <li>missing kid / non-string kid
 *   <li>alg != ES256 (e.g. someone presenting an HS256-signed token whose body they control)
 *   <li>iat outside the 5-minute tolerance (replay defense)
 *   <li>missing iat or missing request_body_sha256 claim
 *   <li>body-hash mismatch (tampered body)
 *   <li>signing key not resolvable (Plaid /webhook_verification_key/get returns null or
 *       already-expired JWK)
 *   <li>unsupported kty / crv on the JWK
 *   <li>null / blank inputs
 *   <li>JWK cache hit on second call within TTL (no second fetch)
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidWebhookVerifierTest {

    private static final String KID = "test-kid-001";

    @Mock private PlaidService plaidService;

    private PlaidWebhookVerifier verifier;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        verifier = new PlaidWebhookVerifier(plaidService);
        keyPair = generateEcKeyPair();
        // Default: the mocked plaidService returns a valid JWK derived from the EC public key.
        when(plaidService.webhookVerificationKeyGet(eq(KID)))
                .thenReturn(buildJwkResponse((ECPublicKey) keyPair.getPublic(), null));
    }

    // --------- happy path ---------

    @Test
    void verify_returnsTrue_forValidJwtSignedBody() {
        final String body = "{\"webhook_type\":\"TRANSACTIONS\"}";
        final String token = buildSignedToken(body, Instant.now(), KID, "ES256");

        assertTrue(verifier.verify(body, token));
    }

    @Test
    void verify_returnsTrue_andCachesKey_secondVerifyDoesNotRefetch() {
        final String body = "{\"a\":1}";
        final String tokenA = buildSignedToken(body, Instant.now(), KID, "ES256");
        final String tokenB = buildSignedToken(body, Instant.now(), KID, "ES256");

        assertTrue(verifier.verify(body, tokenA));
        assertTrue(verifier.verify(body, tokenB));

        // Cache hit on the second call. Plaid's /webhook_verification_key/get must NOT have
        // been called twice — that's a per-webhook latency regression and a Plaid-rate-limit
        // burner if it slips.
        verify(plaidService, times(1)).webhookVerificationKeyGet(eq(KID));
    }

    // --------- replay / freshness ---------

    @Test
    void verify_returnsFalse_whenIatOlderThanFiveMinutes() {
        final String body = "{\"a\":1}";
        // 5 min + 1 sec older than now — outside IAT_TOLERANCE (5 min).
        final Instant stale = Instant.now().minusSeconds(301);
        final String token = buildSignedToken(body, stale, KID, "ES256");

        assertFalse(verifier.verify(body, token));
    }

    @Test
    void verify_returnsFalse_whenIatTooFarInFuture() {
        final String body = "{\"a\":1}";
        // 5 min + 1 sec in the future — outside IAT_TOLERANCE (5 min).
        final Instant future = Instant.now().plusSeconds(301);
        final String token = buildSignedToken(body, future, KID, "ES256");

        assertFalse(verifier.verify(body, token));
    }

    @Test
    void verify_returnsFalse_whenIatClaimMissing() {
        final String body = "{\"a\":1}";
        final String token =
                buildSignedTokenWithClaims(
                        body, KID, "ES256", claims -> claims.remove("iat"));
        assertFalse(verifier.verify(body, token));
    }

    @Test
    void verify_returnsFalse_whenRequestBodyHashClaimMissing() {
        final String body = "{\"a\":1}";
        final String token =
                buildSignedTokenWithClaims(
                        body, KID, "ES256", claims -> claims.remove("request_body_sha256"));
        assertFalse(verifier.verify(body, token));
    }

    // --------- body tampering ---------

    @Test
    void verify_returnsFalse_whenBodyDiffersFromHashClaim() {
        final String signedBody = "{\"a\":1}";
        final String receivedBody = "{\"a\":2}"; // attacker swapped the body
        final String token = buildSignedToken(signedBody, Instant.now(), KID, "ES256");

        assertFalse(verifier.verify(receivedBody, token));
    }

    @Test
    void verify_returnsFalse_whenBodyHashClaimIsBlank() {
        final String body = "{\"a\":1}";
        final String token =
                buildSignedTokenWithClaims(
                        body, KID, "ES256", claims -> claims.put("request_body_sha256", "  "));
        assertFalse(verifier.verify(body, token));
    }

    // --------- alg / kid / header tampering ---------

    @Test
    void verify_returnsFalse_whenAlgIsNotEs256() {
        final String body = "{\"a\":1}";
        // Even if we sign with the correct key under ES256, the verifier rejects if the JWT
        // header claims a different alg. We can't easily forge an HS256 token signed with
        // an EC key via jjwt's typed builder, so we instead build a valid ES256 token and
        // then rewrite its header to claim HS256. The signature will fail to verify, but
        // the verifier should reject on alg-check BEFORE attempting signature verification.
        final String validToken = buildSignedToken(body, Instant.now(), KID, "ES256");
        final String[] parts = validToken.split("\\.");
        // Replace the alg in the header with HS256. (Don't fix the signature; the alg
        // check is the first gate.)
        final String tamperedHeader =
                base64UrlEncode("{\"alg\":\"HS256\",\"kid\":\"" + KID + "\",\"typ\":\"JWT\"}");
        final String tampered = tamperedHeader + "." + parts[1] + "." + parts[2];

        assertFalse(verifier.verify(body, tampered));
    }

    @Test
    void verify_returnsFalse_whenKidHeaderMissing() {
        final String body = "{\"a\":1}";
        final String validToken = buildSignedToken(body, Instant.now(), KID, "ES256");
        final String[] parts = validToken.split("\\.");
        final String tamperedHeader = base64UrlEncode("{\"alg\":\"ES256\",\"typ\":\"JWT\"}");
        final String tampered = tamperedHeader + "." + parts[1] + "." + parts[2];

        assertFalse(verifier.verify(body, tampered));
    }

    @Test
    void verify_returnsFalse_whenKidUnknownToPlaid() {
        // Plaid returns null for an unrecognised kid → verifier must reject.
        when(plaidService.webhookVerificationKeyGet(eq("unknown-kid")))
                .thenReturn(new WebhookVerificationKeyGetResponse());
        final String body = "{\"a\":1}";
        final String token = buildSignedToken(body, Instant.now(), "unknown-kid", "ES256");

        assertFalse(verifier.verify(body, token));
    }

    @Test
    void verify_returnsFalse_whenJwkExpiredAtAlreadyPast() {
        // Plaid returns a JWK whose expired_at is in the past. The verifier must NOT use
        // a key whose Plaid-side validity has already lapsed even though the JWT signature
        // technically still verifies.
        final long pastEpoch = Instant.now().minusSeconds(3600).getEpochSecond();
        when(plaidService.webhookVerificationKeyGet(eq("expired-kid")))
                .thenReturn(buildJwkResponse((ECPublicKey) keyPair.getPublic(), pastEpoch));
        final String body = "{\"a\":1}";
        final String token = buildSignedToken(body, Instant.now(), "expired-kid", "ES256");

        assertFalse(verifier.verify(body, token));
    }

    @Test
    void verify_returnsFalse_whenJwkUsesUnsupportedKeyType() {
        // Plaid only ever issues EC P-256 JWKs. Anything else (RSA, OKP) must be rejected
        // outright — a downgrade attack vector if we silently accepted them.
        final JWKPublicKey badJwk = new JWKPublicKey();
        badJwk.setKty("RSA");
        badJwk.setKid("bad-kty-kid");
        final WebhookVerificationKeyGetResponse response = new WebhookVerificationKeyGetResponse();
        response.setKey(badJwk);
        when(plaidService.webhookVerificationKeyGet(eq("bad-kty-kid"))).thenReturn(response);
        final String body = "{\"a\":1}";
        final String token = buildSignedToken(body, Instant.now(), "bad-kty-kid", "ES256");

        assertFalse(verifier.verify(body, token));
    }

    @Test
    void verify_returnsFalse_whenJwkUsesUnsupportedCurve() {
        // Same logic for crv: P-256 only. P-384 / P-521 must be rejected even if they're EC.
        final JWKPublicKey badJwk = new JWKPublicKey();
        badJwk.setKty("EC");
        badJwk.setCrv("P-384");
        badJwk.setX("AAAA"); // bytes don't matter; key build will fail at curve check first
        badJwk.setY("AAAA");
        badJwk.setKid("bad-crv-kid");
        final WebhookVerificationKeyGetResponse response = new WebhookVerificationKeyGetResponse();
        response.setKey(badJwk);
        when(plaidService.webhookVerificationKeyGet(eq("bad-crv-kid"))).thenReturn(response);
        final String body = "{\"a\":1}";
        final String token = buildSignedToken(body, Instant.now(), "bad-crv-kid", "ES256");

        assertFalse(verifier.verify(body, token));
    }

    // --------- input validation ---------

    @Test
    void verify_returnsFalse_forNullBody() {
        assertFalse(verifier.verify(null, "anything"));
    }

    @Test
    void verify_returnsFalse_forNullHeader() {
        assertFalse(verifier.verify("{\"a\":1}", null));
    }

    @Test
    void verify_returnsFalse_forBlankHeader() {
        assertFalse(verifier.verify("{\"a\":1}", "  "));
    }

    @Test
    void verify_returnsFalse_forGarbageHeader() {
        assertFalse(verifier.verify("{\"a\":1}", "not.a.jwt"));
    }

    // ---------- helpers ----------

    /** Generate a fresh ES256 keypair. */
    private static KeyPair generateEcKeyPair() throws Exception {
        final KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    /** Build a WebhookVerificationKeyGetResponse that wraps a JWK derived from {@code pub}. */
    private static WebhookVerificationKeyGetResponse buildJwkResponse(
            final ECPublicKey pub, final Long expiredAtEpoch) {
        final JWKPublicKey jwk = new JWKPublicKey();
        jwk.setKty("EC");
        jwk.setCrv("P-256");
        jwk.setKid(KID);
        jwk.setX(base64UrlEncodeBytes(toFixed32(pub.getW().getAffineX().toByteArray())));
        jwk.setY(base64UrlEncodeBytes(toFixed32(pub.getW().getAffineY().toByteArray())));
        if (expiredAtEpoch != null) {
            // Plaid's generated JWKPublicKey models expired_at as Integer (epoch seconds).
            // Cast safely — the value is always a recent / past timestamp in tests.
            jwk.setExpiredAt(Math.toIntExact(expiredAtEpoch));
        }
        final WebhookVerificationKeyGetResponse response = new WebhookVerificationKeyGetResponse();
        response.setKey(jwk);
        return response;
    }

    /** Normalize a BigInteger-encoded coordinate to exactly 32 bytes (left-pad / strip sign). */
    private static byte[] toFixed32(final byte[] in) {
        if (in.length == 32) {
            return in;
        }
        if (in.length == 33 && in[0] == 0) {
            // BigInteger sign byte — drop it.
            final byte[] out = new byte[32];
            System.arraycopy(in, 1, out, 0, 32);
            return out;
        }
        final byte[] out = new byte[32];
        System.arraycopy(in, 0, out, 32 - in.length, in.length);
        return out;
    }

    /** Build a valid Plaid-style JWT. */
    private String buildSignedToken(
            final String body, final Instant iat, final String kid, final String alg) {
        return buildSignedTokenWithClaims(body, kid, alg, claims -> claims.put("iat", iat.getEpochSecond()));
    }

    /** Build a Plaid-style JWT and apply {@code mutator} to the claims before signing. */
    private String buildSignedTokenWithClaims(
            final String body,
            final String kid,
            final String alg,
            final java.util.function.Consumer<Map<String, Object>> mutator) {
        final Map<String, Object> claims = new HashMap<>();
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("request_body_sha256", sha256Hex(body.getBytes(StandardCharsets.UTF_8)));
        mutator.accept(claims);

        final JwtBuilder builder =
                Jwts.builder().header().keyId(kid).and().claims(claims).signWith(keyPair.getPrivate());
        if (!"ES256".equals(alg)) {
            builder.header().add("alg", alg).and();
        }
        return builder.compact();
    }

    private static String sha256Hex(final byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64UrlEncode(final String s) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64UrlEncodeBytes(final byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // Suppress -Xlint unused for the ECPrivateKey import; the cast in tests references it.
    @SuppressWarnings("unused")
    private ECPrivateKey unused() {
        return (ECPrivateKey) keyPair.getPrivate();
    }
}
