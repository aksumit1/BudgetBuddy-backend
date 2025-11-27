package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * OAuth2 Controller
 * Provides OAuth2 authentication endpoints
 *
 * Features:
 * - OAuth2 configuration
 * - User info endpoint
 * - JWT token validation
 */
@RestController
@RequestMapping("/api/oauth2")
@Tag(name = "OAuth2", description = "OAuth2 authentication and authorization")
public class OAuth2Controller {

    @SuppressWarnings("unused") // Reserved for future logging
    private static final Logger logger = LoggerFactory.getLogger(OAuth2Controller.class);

    private final boolean oauth2Enabled;

    public OAuth2Controller(@Value("${app.oauth2.enabled:false}") boolean oauth2Enabled) {
        this.oauth2Enabled = oauth2Enabled;
    }

    /**
     * Get OAuth2 Configuration
     * Returns OAuth2 provider configuration
     */
    @GetMapping("/config")
    @Operation(
        summary = "Get OAuth2 Configuration",
        description = "Returns OAuth2 provider configuration including authorization and token endpoints"
    )
    public ResponseEntity<OAuth2ConfigResponse> getOAuth2Config() {
        if (!oauth2Enabled) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE, "OAuth2 is not enabled");
        }

        OAuth2ConfigResponse config = new OAuth2ConfigResponse();
        config.setAuthorizationEndpoint("https://auth.budgetbuddy.com/oauth2/authorize");
        config.setTokenEndpoint("https://auth.budgetbuddy.com/oauth2/token");
        config.setUserInfoEndpoint("https://auth.budgetbuddy.com/oauth2/userinfo");
        config.setClientId("budgetbuddy-client");
        config.setScopes(java.util.List.of("openid", "profile", "email", "financial_data"));

        return ResponseEntity.ok(config);
    }

    /**
     * Get User Info
     * Returns user information from OAuth2 token
     */
    @GetMapping("/userinfo")
    @Operation(
        summary = "Get User Info",
        description = "Returns user information extracted from OAuth2 JWT token"
    )
    public ResponseEntity<Map<String, Object>> getUserInfo(
            @AuthenticationPrincipal Jwt jwt) {

        if (jwt == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED, "JWT token is missing");
        }

        Map<String, Object> userInfo = Map.of(
                "sub", jwt.getSubject() != null ? jwt.getSubject() : "unknown",
                "email", jwt.getClaimAsString("email") != null ? jwt.getClaimAsString("email") : "",
                "name", jwt.getClaimAsString("name") != null ? jwt.getClaimAsString("name") : "",
                "preferred_username", jwt.getClaimAsString("preferred_username") != null ?
                        jwt.getClaimAsString("preferred_username") : ""
        );

        return ResponseEntity.ok(userInfo);
    }

    /**
     * OAuth2 Config Response DTO
     */
    public static class OAuth2ConfigResponse {
        private String authorizationEndpoint;
        private String tokenEndpoint;
        private String userInfoEndpoint;
        private String clientId;
        private java.util.List<String> scopes;

        public String getAuthorizationEndpoint() { return authorizationEndpoint; }
        public void setAuthorizationEndpoint(final String authorizationEndpoint) { this.authorizationEndpoint = authorizationEndpoint; }
        public String getTokenEndpoint() { return tokenEndpoint; }
        public void setTokenEndpoint(final String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }
        public String getUserInfoEndpoint() { return userInfoEndpoint; }
        public void setUserInfoEndpoint(final String userInfoEndpoint) { this.userInfoEndpoint = userInfoEndpoint; }
        public String getClientId() { return clientId; }
        public void setClientId(final String clientId) { this.clientId = clientId; }
        public java.util.List<String> getScopes() { return scopes; }
        public void setScopes(final java.util.List<String> scopes) { this.scopes = scopes; }
    }
}
