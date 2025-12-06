package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RedisConfig
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class RedisConfigTest {

    @Autowired(required = false)
    private LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer;

    @Test
    void testLettuceClientConfigurationBuilderCustomizer_IsCreated() {
        // Then
        assertNotNull(lettuceClientConfigurationBuilderCustomizer, 
                "LettuceClientConfigurationBuilderCustomizer should be created");
    }
}

