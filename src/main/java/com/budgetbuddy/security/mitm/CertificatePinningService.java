package com.budgetbuddy.security.mitm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Set;

/**
 * Certificate Pinning Service
 * Prevents MITM attacks by validating server certificates
 * Implements trust as code - certificates are defined in configuration
 */
@Service
public class CertificatePinningService {

    private static final Logger logger = LoggerFactory.getLogger(CertificatePinningService.class);

    private final Set<String> pinnedCertificates;

    public CertificatePinningService(@Value("${app.security.certificate-pinning.enabled:true}") boolean enabled,
            @Value("${app.security.certificate-pinning.certificates:}") String certificates) {
        if (enabled && certificates != null && !certificates.isEmpty()) {
            this.pinnedCertificates = Set.of(certificates.split(","));
            logger.info("Certificate pinning enabled with {} certificates", pinnedCertificates.size());
        } else {
            this.pinnedCertificates = Set.of();
            if (enabled) {
                logger.debug("Certificate pinning is disabled - no certificates configured (expected in local development)");
            } else {
                logger.debug("Certificate pinning is disabled via configuration");
            }
        }
    }

    /**
     * Validate certificate against pinned certificates
     * Returns true if certificate is trusted
     */
    public boolean validateCertificate(final X509Certificate certificate) {
        if (pinnedCertificates.isEmpty()) {
            logger.warn("Certificate pinning not configured - allowing all certificates");
            return true; // Allow if not configured (for development)
        }

        try {
            // Get certificate's public key hash
            String certHash = getCertificateHash(certificate);

            // Check if certificate is in pinned set
            if (pinnedCertificates.contains(certHash)) {
                logger.debug("Certificate validated against pinned certificates");
                return true;
            }

            // Log at WARN level - this is a security check failure, not necessarily an error
            // In production, this would be caught and handled appropriately
            // In tests, this is expected behavior when testing non-matching certificates
            logger.warn("Certificate validation failed - certificate not in pinned set. Hash: {}", certHash);
            return false;
        } catch (Exception e) {
            logger.error("Failed to validate certificate: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Create custom TrustManager that validates certificates
     */
    public X509TrustManager createPinningTrustManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
                // Not used for server-side
            }

            @Override
            public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
                if (chain == null || chain.length == 0) {
                    throw new IllegalArgumentException("Certificate chain is empty");
                }

                // Validate each certificate in the chain
                for (X509Certificate cert : chain) {
                    if (!validateCertificate(cert)) {
                        throw new SecurityException("Certificate pinning validation failed");
                    }
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    /**
     * Get SHA-256 hash of certificate's public key
     */
    private String getCertificateHash(final X509Certificate certificate) throws Exception {
        byte[] publicKeyBytes = certificate.getPublicKey().getEncoded();
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(publicKeyBytes);
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Configure SSL context with certificate pinning
     */
    public SSLContext createPinningSSLContext() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{createPinningTrustManager()}, new java.security.SecureRandom());
        return sslContext;
    }
}

