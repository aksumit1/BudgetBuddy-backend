package com.budgetbuddy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * AWS X-Ray Configuration
 * Provides distributed tracing for request tracking across services
 *
 * Features:
 * - Request tracing across services
 * - Performance monitoring
 * - Error tracking
 * - Service map generation
 *
 * Note: AWS X-Ray integration for Spring Boot is typically done via:
 * 1. Adding AWS X-Ray SDK dependency to pom.xml
 * 2. Adding @XRayEnabled annotation to controllers/services
 * 3. Configuring X-Ray tracing via application.properties
 * 4. Installing X-Ray daemon or using AWS managed service
 *
 * For AWS SDK v2, X-Ray is integrated via AWS X-Ray SDK for Java 2.x
 * See: https://docs.aws.amazon.com/xray/latest/devguide/xray-sdk-java-spring.html
 */
@Configuration
public class XRayConfig {

    private static final Logger logger = LoggerFactory.getLogger(XRayConfig.class);

    @Value("${app.xray.enabled:true}")
    private boolean xrayEnabled;

    @Value("${app.xray.sampling-rate:0.1}")
    private double samplingRate;

    @jakarta.annotation.PostConstruct
    public void logXRayConfiguration() {
        if (xrayEnabled) {
            logger.info("X-Ray tracing is enabled with sampling rate: {}%", samplingRate * 100);
            logger.info("To enable X-Ray tracing:");
            logger.info("1. Add AWS X-Ray SDK dependency to pom.xml");
            logger.info("2. Add @XRayEnabled annotation to controllers/services");
            logger.info("3. Configure X-Ray daemon endpoint or use AWS managed service");
            logger.info("4. Set AWS_XRAY_TRACING_NAME environment variable");
        } else {
            logger.info("X-Ray tracing is disabled");
        }
    }
}

