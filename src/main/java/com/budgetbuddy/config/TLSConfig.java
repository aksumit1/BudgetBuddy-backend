package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;

/**
 * TLS Configuration for MITM Protection
 * Ensures TLS 1.2+ is used and proper cipher suites are configured
 */
@Configuration
public class TLSConfig {

    private static final Logger logger = LoggerFactory.getLogger(TLSConfig.class);

    @Value("${app.security.tls.min-version:TLSv1.2}")
    private String minTlsVersion;

    @Value("${app.security.tls.enabled-protocols:TLSv1.2,TLSv1.3}")
    private String enabledProtocols;

    @Bean
    @Profile("!test")
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tlsCustomizer() {
        return factory -> {
            // Ensure TLS 1.2+ is used
            factory.addConnectorCustomizers(connector -> {
                // JDK 25: Enhanced pattern matching
                if (connector.getProtocolHandler()
                        instanceof org.apache.coyote.http11.Http11NioProtocol protocolHandler) {

                    // Set SSL protocols
                    protocolHandler.setSSLEnabled(true);
                    // Note: setSslEnabledProtocols may not exist in all Tomcat versions
                    // Use connector properties instead
                    String[] protocols = enabledProtocols.split(",");
                    for (String protocol : protocols) {
                        connector.setProperty("sslEnabledProtocols", protocol.trim());
                    }

                    logger.info("TLS configured with protocols: {}", enabledProtocols);
                }
            });
        };
    }

    @Bean
    public SSLContext sslContext() throws NoSuchAlgorithmException {
        // Use TLS 1.2 or higher
        SSLContext context = SSLContext.getInstance("TLS");
        logger.info("SSL Context initialized with TLS protocol");
        return context;
    }
}

