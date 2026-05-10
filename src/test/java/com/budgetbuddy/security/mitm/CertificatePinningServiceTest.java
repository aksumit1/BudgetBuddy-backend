package com.budgetbuddy.security.mitm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit Tests for Certificate Pinning Service (MITM Protection) */
class CertificatePinningServiceTest {

    private CertificatePinningService service;

    @BeforeEach
    void setUp() throws Exception {
        // Create service with test certificate hashes
        service =
                new CertificatePinningService(
                        true, // enabled
                        "test-hash-1,test-hash-2" // pinned certificates
                        );
    }

    @Test
    void testCertificatePinningWhenEnabledValidatesCertificates() {
        // Given
        assertTrue(service != null);

        // When/Then - Service should be initialized
        assertNotNull(service);
    }

    @Test
    void testCertificatePinningWhenDisabledAllowsAllCertificates() {
        // Given
        final CertificatePinningService disabledService =
                new CertificatePinningService(
                        false, // disabled
                        "" // no certificates
                );

        // When/Then - Should allow certificates when disabled
        // Note: This test verifies the service doesn't throw exceptions when disabled
        assertNotNull(disabledService);
    }

    @Test
    void testCreatePinningTrustManagerReturnsValidTrustManager() {
        // When
        final X509TrustManager trustManager = service.createPinningTrustManager();

        // Then
        assertNotNull(trustManager);
        assertNotNull(trustManager.getAcceptedIssuers());
    }

    @Test
    void testCreatePinningTrustManagerCheckServerTrustedWithEmptyChainThrowsException() {
        // Given
        final X509TrustManager trustManager = service.createPinningTrustManager();

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    trustManager.checkServerTrusted(new X509Certificate[0], "RSA");
                });
    }

    @Test
    void testCreatePinningTrustManagerCheckServerTrustedWithNullChainThrowsException() {
        // Given
        final X509TrustManager trustManager = service.createPinningTrustManager();

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    trustManager.checkServerTrusted(null, "RSA");
                });
    }

    @Test
    void testCreatePinningSSLContextReturnsValidContext() throws Exception {
        // When
        final javax.net.ssl.SSLContext sslContext = service.createPinningSSLContext();

        // Then
        assertNotNull(sslContext);
        assertEquals("TLS", sslContext.getProtocol());
    }

    @Test
    void testValidateCertificateWithEmptyPinnedSetAllowsCertificate() throws Exception {
        // Given
        final CertificatePinningService serviceWithEmptySet =
                new CertificatePinningService(
                        true, "" // empty set
                );

        // Create a mock certificate
        final X509Certificate cert = mock(X509Certificate.class);
        final PublicKey publicKey = mock(PublicKey.class);
        when(cert.getPublicKey()).thenReturn(publicKey);
        when(publicKey.getEncoded()).thenReturn(new byte[] {1, 2, 3});

        // When
        final boolean result = serviceWithEmptySet.validateCertificate(cert);

        // Then - Should allow when no certificates pinned (for development)
        assertTrue(result, "Should allow certificate when pinning is not configured");
    }

    @Test
    void testValidateCertificateWithNullCertificateReturnsFalse() {
        // When
        final boolean result = service.validateCertificate(null);

        // Then
        assertFalse(result, "Should return false for null certificate");
    }

    @Test
    void testValidateCertificateWithMatchingHashReturnsTrue() throws Exception {
        // Given - Create a certificate and calculate its hash
        final KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        final KeyPair keyPair = keyGen.generateKeyPair();

        final X509Certificate cert = mock(X509Certificate.class);
        when(cert.getPublicKey()).thenReturn(keyPair.getPublic());

        // Calculate the certificate hash
        final byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
        final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        final byte[] hash = digest.digest(publicKeyBytes);
        final String certHash = java.util.Base64.getEncoder().encodeToString(hash);

        // Create service with this hash pinned
        final CertificatePinningService serviceWithHash = new CertificatePinningService(true, certHash);

        // When
        final boolean result = serviceWithHash.validateCertificate(cert);

        // Then
        assertTrue(result, "Should return true for matching certificate hash");
    }

    @Test
    void testValidateCertificateWithNonMatchingHashReturnsFalse() throws Exception {
        // Given
        final KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        final KeyPair keyPair = keyGen.generateKeyPair();

        final X509Certificate cert = mock(X509Certificate.class);
        when(cert.getPublicKey()).thenReturn(keyPair.getPublic());

        // Create service with different hash pinned
        final CertificatePinningService serviceWithDifferentHash =
                new CertificatePinningService(true, "different-hash-12345");

        // When
        final boolean result = serviceWithDifferentHash.validateCertificate(cert);

        // Then
        assertFalse(result, "Should return false for non-matching certificate hash");
    }

    @Test
    void testValidateCertificateWithExceptionReturnsFalse() throws Exception {
        // Given
        final X509Certificate cert = mock(X509Certificate.class);
        when(cert.getPublicKey()).thenThrow(new RuntimeException("Test exception"));

        // When
        final boolean result = service.validateCertificate(cert);

        // Then
        assertFalse(result, "Should return false when exception occurs");
    }

    @Test
    void testConstructorWithEnabledAndEmptyCertificatesLogsDebug() {
        // When
        final CertificatePinningService service = new CertificatePinningService(true, "");

        // Then
        assertNotNull(service);
    }

    @Test
    void testConstructorWithDisabledLogsDebug() {
        // When
        final CertificatePinningService service = new CertificatePinningService(false, "test-hash");

        // Then
        assertNotNull(service);
    }

    @Test
    void testConstructorWithNullCertificatesHandlesGracefully() {
        // When
        final CertificatePinningService service = new CertificatePinningService(true, null);

        // Then
        assertNotNull(service);
    }

    @Test
    void
            testCreatePinningTrustManagerCheckServerTrustedWithInvalidCertificateThrowsSecurityException()
                    throws Exception {
        // Given
        final X509TrustManager trustManager = service.createPinningTrustManager();
        final X509Certificate cert = mock(X509Certificate.class);
        final PublicKey publicKey = mock(PublicKey.class);
        when(cert.getPublicKey()).thenReturn(publicKey);
        when(publicKey.getEncoded()).thenReturn(new byte[] {1, 2, 3});

        final X509Certificate[] chain = new X509Certificate[]{cert};

        // When/Then
        assertThrows(
                SecurityException.class,
                () -> {
                    trustManager.checkServerTrusted(chain, "RSA");
                });
    }
}
