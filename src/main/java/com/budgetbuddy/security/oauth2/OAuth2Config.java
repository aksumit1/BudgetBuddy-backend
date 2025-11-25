package com.budgetbuddy.security.oauth2;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * OAuth2 Configuration
 * Supports OAuth2 authentication with JWT tokens
 */
@Configuration
@EnableWebSecurity
public class OAuth2Config {

    @Value("${app.oauth2.jwt.issuer-uri:}")
    private String issuerUri;

    @Value("${app.oauth2.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    /**
     * JWT Decoder for OAuth2
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (jwkSetUri != null && !jwkSetUri.isEmpty()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } else if (issuerUri != null && !issuerUri.isEmpty()) {
            return NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        }
        // Fallback to custom JWT decoder if OAuth2 not configured
        return null;
    }

    /**
     * JWT Authentication Converter
     * Extracts authorities from JWT claims
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthorityPrefix("ROLE_");
        authoritiesConverter.setAuthoritiesClaimName("authorities");

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        authenticationConverter.setPrincipalClaimName("sub");

        return authenticationConverter;
    }
}

