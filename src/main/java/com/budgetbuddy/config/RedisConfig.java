package com.budgetbuddy.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redis Configuration
 * Configures Lettuce client with proper timeouts to prevent health check delays
 * 
 * Root causes of slow health checks:
 * 1. Connection pool exhaustion - no max-wait timeout, waits indefinitely
 * 2. Default retry behavior - multiple retries on connection failures
 * 3. Network connectivity issues - slow connection establishment
 * 
 * Key fixes:
 * - Socket timeouts (prevents hanging connections)
 * - Command timeout (prevents slow commands from blocking)
 * - Queue commands during reconnection (ACCEPT_COMMANDS) - allows graceful recovery from Redis restarts
 * - Connection pool max-wait configured in application.yml
 * - Aggressive connection validation and eviction to handle Redis restarts
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.timeout:2000ms}")
    private Duration redisTimeout;

    /**
     * Customize Lettuce client configuration to add timeouts and optimize for speed
     * This prevents health checks from hanging when Redis is slow or unavailable
     * 
     * Optimizations:
     * - Fast connection establishment (reduced timeouts)
     * - TCP_NODELAY for low latency
     * - Connection reuse and keep-alive
     * - Fast fail on errors
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceClientConfigurationBuilderCustomizer() {
        return clientConfigurationBuilder -> {
            // Socket options optimized for speed
            SocketOptions socketOptions = SocketOptions.builder()
                    .connectTimeout(redisTimeout)
                    .keepAlive(true)  // Keep connections alive to avoid reconnection overhead
                    .tcpNoDelay(true)  // Disable Nagle's algorithm for low latency
                    .build();

            // Timeout options - fail fast (no retries for health checks)
            TimeoutOptions timeoutOptions = TimeoutOptions.builder()
                    .fixedTimeout(redisTimeout)
                    .build();

            // Client options optimized for speed and resilience
            ClientOptions clientOptions = ClientOptions.builder()
                    .socketOptions(socketOptions)
                    .timeoutOptions(timeoutOptions)
                    .autoReconnect(true)  // Auto-reconnect for resilience
                    // ACCEPT_COMMANDS allows commands to queue during reconnection (better for Redis restarts)
                    // Commands will timeout per timeoutOptions, preventing indefinite hangs
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.ACCEPT_COMMANDS) // Queue commands during reconnection
                    .pingBeforeActivateConnection(true)  // Validate connections before use (prevents stale connections)
                    .build();

            clientConfigurationBuilder
                    .clientOptions(clientOptions)
                    .commandTimeout(redisTimeout) // Command timeout
                    .shutdownTimeout(Duration.ofMillis(100)); // Fast shutdown
        };
    }
}

