package com.budgetbuddy.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * File Security Configuration
 * Centralizes file security limits and thresholds
 */
@Configuration
public class FileSecurityConfig {

    @Value("${app.security.file.max-scan-size-bytes:1048576}")
    private int maxScanSizeBytes; // Default: 1MB (1024 * 1024)

    @Value("${app.security.file.high-entropy-threshold:7.5}")
    private double highEntropyThreshold;

    @Value("${app.security.file.max-filename-length:200}")
    private int maxFilenameLength;

    public int getMaxScanSizeBytes() {
        return maxScanSizeBytes;
    }

    public double getHighEntropyThreshold() {
        return highEntropyThreshold;
    }

    public int getMaxFilenameLength() {
        return maxFilenameLength;
    }
}

