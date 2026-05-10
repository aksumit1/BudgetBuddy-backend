package com.budgetbuddy.config;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import java.security.Security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * DNS Cache Configuration Configures Java's DNS cache TTL for resilience and quick recovery
 *
 * <p>Strategy: 1. Long positive TTL: Keep successful DNS lookups cached for extended periods - If
 * DNS server becomes unavailable, continue using cached addresses - Default: 3600 seconds (1 hour)
 * - allows operation during DNS outages
 *
 * <p>2. Very short negative TTL: Don't cache DNS lookup failures - Failed lookups expire quickly,
 * allowing retry when DNS/server recovers - Default: 1 second - minimal caching of errors
 *
 * <p>3. Quick recovery: When DNS server is restored, failed lookups expire quickly and successful
 * lookups are cached for future DNS outages
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Configuration
public class DnsCacheConfig {

    private static final String NETWORKADDRESS_CACHE_NEGATIVE_TTL =
            "networkaddress.cache.negative.ttl";

    private static final String NETWORKADDRESS_CACHE_TTL = "networkaddress.cache.ttl";

    private static final Logger LOGGER = LoggerFactory.getLogger(DnsCacheConfig.class);

    @Value("${app.dns.cache.ttl-seconds:3600}")
    private int dnsCacheTtlSeconds; // Long TTL for successful lookups (1 hour default)

    @Value("${app.dns.cache.negative-ttl-seconds:1}")
    private int dnsCacheNegativeTtlSeconds; // Very short TTL for failures (1 second default)

    /**
     * Configure Java DNS cache TTL on application startup
     *
     * <p>Strategy: - Long positive TTL: Keep cached addresses available during DNS outages - Short
     * negative TTL: Don't cache errors, recover quickly when DNS/server is restored
     */
    @PostConstruct
    public void configureDnsCache() {
        try {
            // Set DNS cache TTL (positive lookups - successful DNS resolutions)
            // Long TTL allows continued operation using cached addresses when DNS server is
            // unavailable
            // Default Java is -1 (cache forever), we use configurable TTL (default: 1 hour)
            Security.setProperty(NETWORKADDRESS_CACHE_TTL, String.valueOf(dnsCacheTtlSeconds));

            // Set DNS cache TTL for negative lookups (failed DNS resolutions)
            // Very short TTL ensures errors are not cached, allowing quick retry when DNS/server
            // recovers
            // Default Java is 10 seconds, we use much shorter (default: 1 second)
            Security.setProperty(
                    NETWORKADDRESS_CACHE_NEGATIVE_TTL, String.valueOf(dnsCacheNegativeTtlSeconds));

            LOGGER.info(
                    "DNS cache configured: Positive TTL={}s (keeps cached addresses during DNS outages), "
                            + "Negative TTL={}s (quick recovery from DNS/server failures)",
                    dnsCacheTtlSeconds,
                    dnsCacheNegativeTtlSeconds);
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to configure DNS cache TTL: {}. Using Java defaults.", e.getMessage());
        }
    }

    /** Clear DNS cache programmatically (called by management endpoint) */
    public void clearDnsCache() {
        try {
            // Clear positive cache by setting TTL to 0 temporarily
            final String originalTtl = Security.getProperty(NETWORKADDRESS_CACHE_TTL);
            Security.setProperty(NETWORKADDRESS_CACHE_TTL, "0");
            // Restore original TTL
            Security.setProperty(
                    NETWORKADDRESS_CACHE_TTL,
                    originalTtl != null ? originalTtl : String.valueOf(dnsCacheTtlSeconds));

            // Clear negative cache
            final String originalNegativeTtl =
                    Security.getProperty(NETWORKADDRESS_CACHE_NEGATIVE_TTL);
            Security.setProperty(NETWORKADDRESS_CACHE_NEGATIVE_TTL, "0");
            Security.setProperty(
                    NETWORKADDRESS_CACHE_NEGATIVE_TTL,
                    originalNegativeTtl != null
                            ? originalNegativeTtl
                            : String.valueOf(dnsCacheNegativeTtlSeconds));

            LOGGER.info("DNS cache cleared successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to clear DNS cache: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to clear DNS cache", e);
        }
    }
}
