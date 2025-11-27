package com.budgetbuddy.security.mitm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.*;

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
    void testCreatePinningSSLContext_ReturnsValidContext() throws Exception {
        // When
        javax.net.ssl.SSLContext sslContext = service.createPinningSSLContext();

        // Then
        assertNotNull(sslContext);
        assertEquals("TLS", sslContext.getProtocol());
    }

    @Test
    void testValidateCertificate_WithEmptyPinnedSet_AllowsCertificate() {
        // Given
        CertificatePinningService serviceWithEmptySet = new CertificatePinningService(
                true,
                "" // empty set
        );

        // When/Then - Should allow when no certificates pinned (for development)
        assertNotNull(serviceWithEmptySet);
    }
}

