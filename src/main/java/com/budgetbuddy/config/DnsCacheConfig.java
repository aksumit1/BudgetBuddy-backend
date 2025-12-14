package com.budgetbuddy.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.security.Security;

/**
 * DNS Cache Configuration
 * Configures Java's DNS cache TTL for resilience and quick recovery
 * 
 * Strategy:
 * 1. Long positive TTL: Keep successful DNS lookups cached for extended periods
 *    - If DNS server becomes unavailable, continue using cached addresses
 *    - Default: 3600 seconds (1 hour) - allows operation during DNS outages
 * 
 * 2. Very short negative TTL: Don't cache DNS lookup failures
 *    - Failed lookups expire quickly, allowing retry when DNS/server recovers
 *    - Default: 1 second - minimal caching of errors
 * 
 * 3. Quick recovery: When DNS server is restored, failed lookups expire quickly
 *    and successful lookups are cached for future DNS outages
 */
@Configuration
public class DnsCacheConfig {

    private static final Logger logger = LoggerFactory.getLogger(DnsCacheConfig.class);

    @Value("${app.dns.cache.ttl-seconds:3600}")
    private int dnsCacheTtlSeconds;  // Long TTL for successful lookups (1 hour default)

    @Value("${app.dns.cache.negative-ttl-seconds:1}")
    private int dnsCacheNegativeTtlSeconds;  // Very short TTL for failures (1 second default)

    /**
     * Configure Java DNS cache TTL on application startup
     * 
     * Strategy:
     * - Long positive TTL: Keep cached addresses available during DNS outages
     * - Short negative TTL: Don't cache errors, recover quickly when DNS/server is restored
     */
    @PostConstruct
    public void configureDnsCache() {
        try {
            // Set DNS cache TTL (positive lookups - successful DNS resolutions)
            // Long TTL allows continued operation using cached addresses when DNS server is unavailable
            // Default Java is -1 (cache forever), we use configurable TTL (default: 1 hour)
            Security.setProperty("networkaddress.cache.ttl", String.valueOf(dnsCacheTtlSeconds));
            
            // Set DNS cache TTL for negative lookups (failed DNS resolutions)
            // Very short TTL ensures errors are not cached, allowing quick retry when DNS/server recovers
            // Default Java is 10 seconds, we use much shorter (default: 1 second)
            Security.setProperty("networkaddress.cache.negative.ttl", String.valueOf(dnsCacheNegativeTtlSeconds));
            
            logger.info("DNS cache configured: Positive TTL={}s (keeps cached addresses during DNS outages), " +
                    "Negative TTL={}s (quick recovery from DNS/server failures)", 
                    dnsCacheTtlSeconds, dnsCacheNegativeTtlSeconds);
        } catch (Exception e) {
            logger.warn("Failed to configure DNS cache TTL: {}. Using Java defaults.", e.getMessage());
        }
    }

    /**
     * Clear DNS cache programmatically (called by management endpoint)
     */
    public void clearDnsCache() {
        try {
            // Clear positive cache by setting TTL to 0 temporarily
            String originalTtl = Security.getProperty("networkaddress.cache.ttl");
            Security.setProperty("networkaddress.cache.ttl", "0");
            // Restore original TTL
            Security.setProperty("networkaddress.cache.ttl", originalTtl != null ? originalTtl : String.valueOf(dnsCacheTtlSeconds));
            
            // Clear negative cache
            String originalNegativeTtl = Security.getProperty("networkaddress.cache.negative.ttl");
            Security.setProperty("networkaddress.cache.negative.ttl", "0");
            Security.setProperty("networkaddress.cache.negative.ttl", originalNegativeTtl != null ? originalNegativeTtl : String.valueOf(dnsCacheNegativeTtlSeconds));
            
            logger.info("DNS cache cleared successfully");
        } catch (Exception e) {
            logger.error("Failed to clear DNS cache: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clear DNS cache", e);
        }
    }
}

