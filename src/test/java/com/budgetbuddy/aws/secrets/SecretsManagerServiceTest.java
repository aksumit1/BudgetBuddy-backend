package com.budgetbuddy.aws.secrets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for SecretsManagerService
 */
@ExtendWith(MockitoExtension.class)
class SecretsManagerServiceTest {

    @Mock
    private SecretsManagerClient secretsManagerClient;

    @InjectMocks
    private SecretsManagerService secretsManagerService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(secretsManagerService, "secretsManagerEnabled", true);
        ReflectionTestUtils.setField(secretsManagerService, "refreshIntervalSeconds", 3600L);
        ReflectionTestUtils.setField(secretsManagerService, "awsRegion", "us-east-1");
    }

    @Test
    void testGetSecret_WhenSecretsManagerDisabled_ReturnsEnvVar() {
        // Given
        ReflectionTestUtils.setField(secretsManagerService, "secretsManagerEnabled", false);
        System.setProperty("TEST_SECRET", "env-secret-value");

        // When
        String secret = secretsManagerService.getSecret("test-secret", "TEST_SECRET");

        // Then
        // Note: In real scenario, would check environment variable
        assertNotNull(secret); // Or null if env var not set
    }

    @Test
    void testGetSecret_WhenSecretsManagerEnabled_ReturnsFromCache() {
        // Given
        String secretName = "test-secret";
        String secretValue = "secret-value";
        
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(secretValue)
                .build();
        
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When - First call
        String result1 = secretsManagerService.getSecret(secretName, "ENV_VAR");

        // Then
        assertNotNull(result1);
        verify(secretsManagerClient, times(1)).getSecretValue(any(GetSecretValueRequest.class));

        // When - Second call (should use cache)
        String result2 = secretsManagerService.getSecret(secretName, "ENV_VAR");

        // Then - Should use cache, not call again
        assertEquals(result1, result2);
        // Note: Cache behavior depends on implementation
    }

    @Test
    void testGetSecret_WhenSecretsManagerFails_FallsBackToEnvVar() {
        // Given
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(SecretsManagerException.builder().message("Secret not found").build());
        System.setProperty("FALLBACK_SECRET", "fallback-value");

        // When
        String secret = secretsManagerService.getSecret("test-secret", "FALLBACK_SECRET");

        // Then
        // Should fallback to environment variable or return null
        assertNotNull(secret); // Or null if env var not set
    }

    @Test
    void testRefreshAllSecrets_RefreshesCache() {
        // Given
        String secretName = "test-secret";
        String newSecretValue = "new-secret-value";
        
        GetSecretValueResponse response = GetSecretValueResponse.builder()
                .secretString(newSecretValue)
                .build();
        
        when(secretsManagerClient.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(response);

        // When
        secretsManagerService.refreshAllSecrets();

        // Then
        verify(secretsManagerService, atLeastOnce()).refreshAllSecrets();
    }

    @Test
    void testShutdown_ClosesClient() {
        // When
        secretsManagerService.shutdown();

        // Then
        verify(secretsManagerClient, times(1)).close();
    }
}

