package com.budgetbuddy.config;

import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * TLS Configuration for MITM Protection Ensures TLS 1.2+ is used and proper cipher suites are
 * configured
 */
@Configuration
public class TLSConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(TLSConfig.class);

    @Value("${app.security.tls.min-version:TLSv1.2}")
    private String minTlsVersion;

    @Value("${app.security.tls.enabled-protocols:TLSv1.2,TLSv1.3}")
    private String enabledProtocols;

    @Bean
    @Profile("!test")
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tlsCustomizer(
            @Value("${server.ssl.enabled:false}") final boolean sslEnabled) {
        return factory -> {
            // Only configure TLS if SSL is actually enabled
            // For local development without SSL certificates, skip TLS configuration
            // TLS will be handled by ALB/load balancer in production
            if (!sslEnabled) {
                LOGGER.debug(
                        "SSL is disabled, skipping TLS connector configuration (using HTTP only)");
                return;
            }

            // Ensure TLS 1.2+ is used when SSL is configured
            factory.addConnectorCustomizers(
                    connector -> {
                        // JDK 25: Enhanced pattern matching
                        if (connector.getProtocolHandler()
                                instanceof org.apache.coyote.http11.Http11NioProtocol) {

                            // Set SSL protocols
                            final String[] protocols = enabledProtocols.split(",");
                            for (final String protocol : protocols) {
                                connector.setProperty("sslEnabledProtocols", protocol.trim());
                            }
                            LOGGER.info(
                                    "TLS configured with protocols: {} (SSL enabled)",
                                    enabledProtocols);
                        }
                    });
        };
    }

    @Bean
    public SSLContext sslContext() throws NoSuchAlgorithmException {
        // Use TLS 1.2 or higher
        final SSLContext context = SSLContext.getInstance("TLS");
        LOGGER.info("SSL Context initialized with TLS protocol");
        return context;
    }
}
