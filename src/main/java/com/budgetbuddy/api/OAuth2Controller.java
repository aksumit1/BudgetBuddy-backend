package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OAuth2 Controller Provides OAuth2 authentication endpoints
 *
 * <p>Features: - OAuth2 configuration - User info endpoint - JWT token validation
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings("PMD.DataClass")
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/oauth2")
@Tag(name = "OAuth2", description = "OAuth2 authentication and authorization")
public class OAuth2Controller {

    private static final String EMAIL = "email";

    private static final String PREFERRED_USERNAME = "preferred_username";

    @SuppressWarnings("unused") // Reserved for future logging
    private static final Logger LOGGER = LoggerFactory.getLogger(OAuth2Controller.class);

    private final boolean oauth2Enabled;

    public OAuth2Controller(@Value("${app.oauth2.enabled:false}") final boolean oauth2Enabled) {
        this.oauth2Enabled = oauth2Enabled;
    }

    /** Get OAuth2 Configuration Returns OAuth2 provider configuration */
    @GetMapping("/config")
    @Operation(
            summary = "Get OAuth2 Configuration",
            description =
                    "Returns OAuth2 provider configuration including authorization and token endpoints")
    public ResponseEntity<OAuth2ConfigResponse> getOAuth2Config() {
        if (!oauth2Enabled) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE, "OAuth2 is not enabled");
        }

        final OAuth2ConfigResponse config = new OAuth2ConfigResponse();
        config.setAuthorizationEndpoint("https://auth.budgetbuddy.com/oauth2/authorize");
        config.setTokenEndpoint("https://auth.budgetbuddy.com/oauth2/token");
        config.setUserInfoEndpoint("https://auth.budgetbuddy.com/oauth2/userinfo");
        config.setClientId("budgetbuddy-client");
        config.setScopes(java.util.List.of("openid", "profile", EMAIL, "financial_data"));

        return ResponseEntity.ok(config);
    }

    /** Get User Info Returns user information from OAuth2 token */
    @GetMapping("/userinfo")
    @Operation(
            summary = "Get User Info",
            description = "Returns user information extracted from OAuth2 JWT token")
    public ResponseEntity<Map<String, Object>> getUserInfo(@AuthenticationPrincipal final Jwt jwt) {

        if (jwt == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "JWT token is missing");
        }

        final Map<String, Object> userInfo =
                Map.of(
                        "sub",
                        jwt.getSubject() != null ? jwt.getSubject() : "unknown",
                        EMAIL,
                        jwt.getClaimAsString(EMAIL) != null ? jwt.getClaimAsString(EMAIL) : "",
                        "name",
                        jwt.getClaimAsString("name") != null ? jwt.getClaimAsString("name") : "",
                        PREFERRED_USERNAME,
                        jwt.getClaimAsString(PREFERRED_USERNAME) != null
                                ? jwt.getClaimAsString(PREFERRED_USERNAME)
                                : "");

        return ResponseEntity.ok(userInfo);
    }

    /** OAuth2 Config Response DTO */
    public static class OAuth2ConfigResponse {
        private String authorizationEndpoint;
        private String tokenEndpoint;
        private String userInfoEndpoint;
        private String clientId;
        private java.util.List<String> scopes;

        public String getAuthorizationEndpoint() {
            return authorizationEndpoint;
        }

        public void setAuthorizationEndpoint(final String authorizationEndpoint) {
            this.authorizationEndpoint = authorizationEndpoint;
        }

        public String getTokenEndpoint() {
            return tokenEndpoint;
        }

        public void setTokenEndpoint(final String tokenEndpoint) {
            this.tokenEndpoint = tokenEndpoint;
        }

        public String getUserInfoEndpoint() {
            return userInfoEndpoint;
        }

        public void setUserInfoEndpoint(final String userInfoEndpoint) {
            this.userInfoEndpoint = userInfoEndpoint;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(final String clientId) {
            this.clientId = clientId;
        }

        public java.util.List<String> getScopes() {
            return scopes;
        }

        public void setScopes(final java.util.List<String> scopes) {
            this.scopes = scopes;
        }
    }
}
