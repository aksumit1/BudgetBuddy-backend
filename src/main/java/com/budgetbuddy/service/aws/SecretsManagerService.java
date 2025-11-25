package com.budgetbuddy.service.aws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

/**
 * AWS Secrets Manager Service
 * Caches secrets to minimize API calls and costs
 */
@Service
public class SecretsManagerService {

    private static final Logger logger = LoggerFactory.getLogger(SecretsManagerService.class);

    private final SecretsManagerClient secretsManagerClient;

    public SecretsManagerService(final SecretsManagerClient secretsManagerClient) {
        this.secretsManagerClient = secretsManagerClient;
    }

    /**
     * Get secret value with caching to reduce API calls
     */
    @Cacheable(value = "secrets", key = "#secretName")
    public String getSecret((final String secretName) {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            return response.secretString();
        } catch (SecretsManagerException e) {
            logger.error("Error retrieving secret {}: {}", secretName, e.getMessage());
            throw new RuntimeException("Failed to retrieve secret: " + secretName, e);
        }
    }
}

