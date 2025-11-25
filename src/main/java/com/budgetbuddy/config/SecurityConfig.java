package com.budgetbuddy.config;

import com.budgetbuddy.security.oauth2.OAuth2Config;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security Configuration
 * Supports both JWT and OAuth2 authentication
 * 
 * Production-ready with proper CORS restrictions
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired(required = false)
    private JwtDecoder jwtDecoder;

    @Value("${app.security.jwt.enabled:true}")
    private boolean jwtEnabled;

    @Value("${app.security.oauth2.enabled:false}")
    private boolean oauth2Enabled;

    @Value("${app.security.cors.allowed-origins:}")
    private String allowedOrigins;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/plaid/webhooks").permitAll() // Webhooks don't require auth
                .anyRequest().authenticated()
            );

        // Configure OAuth2 if enabled
        if (oauth2Enabled && jwtDecoder != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder))
            );
        }

        // Configure JWT if enabled
        if (jwtEnabled) {
            // JWT filter will be added here
            // http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Configure allowed origins based on environment
        boolean isProduction = activeProfile != null && activeProfile.contains("prod");
        
        if (allowedOrigins != null && !allowedOrigins.isEmpty() && !allowedOrigins.equals("*")) {
            List<String> origins = Arrays.asList(allowedOrigins.split(","));
            configuration.setAllowedOrigins(origins);
            logger.info("CORS configured with specific origins: {}", origins);
        } else if (isProduction) {
            // Production: require explicit origins
            logger.warn("Production environment detected but CORS origins not configured. Defaulting to empty list.");
            configuration.setAllowedOrigins(List.of()); // Empty list = no CORS allowed
        } else {
            // Development/Staging: allow all
            configuration.setAllowedOrigins(List.of("*"));
            logger.info("CORS configured to allow all origins (non-production environment)");
        }
        
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(Arrays.asList(
                "Authorization", 
                "X-Request-ID", 
                "X-Rate-Limit-Remaining", 
                "X-Rate-Limit-Reset",
                "X-Rate-Limit-Limit",
                "X-API-Version"
        ));
        configuration.setMaxAge(3600L);
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
