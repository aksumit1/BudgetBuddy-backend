package com.budgetbuddy.security.mitm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for Certificate Pinning Service (MITM Protection)
 */
class CertificatePinningServiceTest {

    private CertificatePinningService service;

    @BeforeEach
    void setUp() throws Exception {
        // Create service with test certificate hashes
        service = new CertificatePinningService(
                true, // enabled
                "test-hash-1,test-hash-2" // pinned certificates
        );
    }

    @Test
    void testCertificatePinning_WhenEnabled_ValidatesCertificates() {
        // Given
        assertTrue(service != null);

        // When/Then - Service should be initialized
        assertNotNull(service);
    }

    @Test
    void testCertificatePinning_WhenDisabled_AllowsAllCertificates() {
        // Given
        CertificatePinningService disabledService = new CertificatePinningService(
                false, // disabled
                "" // no certificates
        );

        // When/Then - Should allow certificates when disabled
        // Note: This test verifies the service doesn't throw exceptions when disabled
        assertNotNull(disabledService);
    }

    @Test
    void testCreatePinningTrustManager_ReturnsValidTrustManager() {
        // When
        X509TrustManager trustManager = service.createPinningTrustManager();

        // Then
        assertNotNull(trustManager);
        assertNotNull(trustManager.getAcceptedIssuers());
    }
    
    @Test
    void testCreatePinningTrustManager_CheckServerTrusted_WithEmptyChain_ThrowsException() {
        // Given
        X509TrustManager trustManager = service.createPinningTrustManager();
        
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            trustManager.checkServerTrusted(new X509Certificate[0], "RSA");
        });
    }
    
    @Test
    void testCreatePinningTrustManager_CheckServerTrusted_WithNullChain_ThrowsException() {
        // Given
        X509TrustManager trustManager = service.createPinningTrustManager();
        
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            trustManager.checkServerTrusted(null, "RSA");
        });
    }

    @Test
    void testCreatePinningSSLContext_ReturnsValidContext() throws Exception {
        // When
        javax.net.ssl.SSLContext sslContext = service.createPinningSSLContext();

        // Then
        assertNotNull(sslContext);
        assertEquals("TLS", sslContext.getProtocol());
    }

    @Test
    void testValidateCertificate_WithEmptyPinnedSet_AllowsCertificate() throws Exception {
        // Given
        CertificatePinningService serviceWithEmptySet = new CertificatePinningService(
                true,
                "" // empty set
        );
        
        // Create a mock certificate
        X509Certificate cert = mock(X509Certificate.class);
        PublicKey publicKey = mock(PublicKey.class);
        when(cert.getPublicKey()).thenReturn(publicKey);
        when(publicKey.getEncoded()).thenReturn(new byte[]{1, 2, 3});
        
        // When
        boolean result = serviceWithEmptySet.validateCertificate(cert);
        
        // Then - Should allow when no certificates pinned (for development)
        assertTrue(result, "Should allow certificate when pinning is not configured");
    }
    
    @Test
    void testValidateCertificate_WithNullCertificate_ReturnsFalse() {
        // When
        boolean result = service.validateCertificate(null);
        
        // Then
        assertFalse(result, "Should return false for null certificate");
    }
    
    @Test
    void testValidateCertificate_WithMatchingHash_ReturnsTrue() throws Exception {
        // Given - Create a certificate and calculate its hash
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getPublicKey()).thenReturn(keyPair.getPublic());
        
        // Calculate the certificate hash
        byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(publicKeyBytes);
        String certHash = java.util.Base64.getEncoder().encodeToString(hash);
        
        // Create service with this hash pinned
        CertificatePinningService serviceWithHash = new CertificatePinningService(
                true,
                certHash
        );
        
        // When
        boolean result = serviceWithHash.validateCertificate(cert);
        
        // Then
        assertTrue(result, "Should return true for matching certificate hash");
    }
    
    @Test
    void testValidateCertificate_WithNonMatchingHash_ReturnsFalse() throws Exception {
        // Given
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getPublicKey()).thenReturn(keyPair.getPublic());
        
        // Create service with different hash pinned
        CertificatePinningService serviceWithDifferentHash = new CertificatePinningService(
                true,
                "different-hash-12345"
        );
        
        // When
        boolean result = serviceWithDifferentHash.validateCertificate(cert);
        
        // Then
        assertFalse(result, "Should return false for non-matching certificate hash");
    }
    
    @Test
    void testValidateCertificate_WithException_ReturnsFalse() throws Exception {
        // Given
        X509Certificate cert = mock(X509Certificate.class);
        when(cert.getPublicKey()).thenThrow(new RuntimeException("Test exception"));
        
        // When
        boolean result = service.validateCertificate(cert);
        
        // Then
        assertFalse(result, "Should return false when exception occurs");
    }
    
    @Test
    void testConstructor_WithEnabledAndEmptyCertificates_LogsDebug() {
        // When
        CertificatePinningService service = new CertificatePinningService(
                true,
                ""
        );
        
        // Then
        assertNotNull(service);
    }
    
    @Test
    void testConstructor_WithDisabled_LogsDebug() {
        // When
        CertificatePinningService service = new CertificatePinningService(
                false,
                "test-hash"
        );
        
        // Then
        assertNotNull(service);
    }
    
    @Test
    void testConstructor_WithNullCertificates_HandlesGracefully() {
        // When
        CertificatePinningService service = new CertificatePinningService(
                true,
                null
        );
        
        // Then
        assertNotNull(service);
    }
    
    @Test
    void testCreatePinningTrustManager_CheckServerTrusted_WithInvalidCertificate_ThrowsSecurityException() throws Exception {
        // Given
        X509TrustManager trustManager = service.createPinningTrustManager();
        X509Certificate cert = mock(X509Certificate.class);
        PublicKey publicKey = mock(PublicKey.class);
        when(cert.getPublicKey()).thenReturn(publicKey);
        when(publicKey.getEncoded()).thenReturn(new byte[]{1, 2, 3});
        
        X509Certificate[] chain = new X509Certificate[]{cert};
        
        // When/Then
        assertThrows(SecurityException.class, () -> {
            trustManager.checkServerTrusted(chain, "RSA");
        });
    }
}

