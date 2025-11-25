package com.budgetbuddy.security.cloudauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

/**
 * AWS Cognito Integration Service
 * Provides CloudAuth support using AWS Cognito
 *
 * Features:
 * - User authentication
 * - User registration
 * - Password reset
 * - MFA support
 */
@Service
public class CloudAuthService {

    private static final Logger logger = LoggerFactory.getLogger(CloudAuthService.class);

    private final CognitoIdentityProviderClient cognitoClient;
    private final String userPoolId;
    private final String clientId;

    public CloudAuthService(final CognitoIdentityProviderClient cognitoClient, @Value("${app.aws.cognito.user-pool-id:}") String userPoolId,
            @Value("${app.aws.cognito.client-id:}") String clientId) {
        this.cognitoClient = cognitoClient;
        this.userPoolId = userPoolId;
        this.clientId = clientId;
    }

    /**
     * Authenticate user with Cognito
     */
    public CloudAuthResult authenticate(final String username, final String password) {
        try {
            AdminInitiateAuthResponse response = cognitoClient.adminInitiateAuth(
                    AdminInitiateAuthRequest.builder()
                            .userPoolId(userPoolId)
                            .clientId(clientId)
                            .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                            .authParameters(java.util.Map.of(
                                    "USERNAME", username,
                                    "PASSWORD", password
                            ))
                            .build());

            CloudAuthResult result = new CloudAuthResult();
            result.setSuccess(true);
            result.setAccessToken(response.authenticationResult().accessToken());
            result.setIdToken(response.authenticationResult().idToken());
            result.setRefreshToken(response.authenticationResult().refreshToken());
            result.setExpiresIn(response.authenticationResult().expiresIn());

            logger.info("CloudAuth: User authenticated: {}", username);
            return result;
        } catch (NotAuthorizedException e) {
            logger.warn("CloudAuth: Authentication failed for user: {}", username);
            CloudAuthResult result = new CloudAuthResult();
            result.setSuccess(false);
            result.setError("Invalid credentials");
            return result;
        } catch (Exception e) {
            logger.error("CloudAuth: Authentication error: {}", e.getMessage());
            CloudAuthResult result = new CloudAuthResult();
            result.setSuccess(false);
            result.setError("Authentication service error");
            return result;
        }
    }

    /**
     * Register new user with Cognito
     */
    public CloudAuthResult register(final String email, final String password, final String firstName, final String lastName) {
        try {
            AdminCreateUserResponse response = cognitoClient.adminCreateUser(
                    AdminCreateUserRequest.builder()
                            .userPoolId(userPoolId)
                            .username(email)
                            .userAttributes(
                                    AttributeType.builder()
                                            .name("email")
                                            .value(email)
                                            .build(),
                                    AttributeType.builder()
                                            .name("given_name")
                                            .value(firstName)
                                            .build(),
                                    AttributeType.builder()
                                            .name("family_name")
                                            .value(lastName)
                                            .build(),
                                    AttributeType.builder()
                                            .name("email_verified")
                                            .value("false")
                                            .build()
                            )
                            .messageAction(MessageActionType.SUPPRESS) // Don't send welcome email
                            .build());

            // Set permanent password
            cognitoClient.adminSetUserPassword(
                    AdminSetUserPasswordRequest.builder()
                            .userPoolId(userPoolId)
                            .username(email)
                            .password(password)
                            .permanent(true)
                            .build());

            CloudAuthResult result = new CloudAuthResult();
            result.setSuccess(true);
            result.setUserId(response.user().username());

            logger.info("CloudAuth: User registered: {}", email);
            return result;
        } catch (UsernameExistsException e) {
            logger.warn("CloudAuth: User already exists: {}", email);
            CloudAuthResult result = new CloudAuthResult();
            result.setSuccess(false);
            result.setError("User already exists");
            return result;
        } catch (Exception e) {
            logger.error("CloudAuth: Registration error: {}", e.getMessage());
            CloudAuthResult result = new CloudAuthResult();
            result.setSuccess(false);
            result.setError("Registration service error");
            return result;
        }
    }

    /**
     * Verify token with Cognito
     */
    public boolean verifyToken(final String token) {
        try {
            GetUserRequest request = GetUserRequest.builder()
                    .accessToken(token)
                    .build();
            cognitoClient.getUser(request);
            return true;
        } catch (Exception e) {
            logger.warn("CloudAuth: Token verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * CloudAuth Result
     */
    public static class CloudAuthResult {
        private boolean success;
        private String userId;
        private String accessToken;
        private String idToken;
        private String refreshToken;
        private Integer expiresIn;
        private String error;

        // Getters and setters
        public boolean isSuccess() { return success; }
        public void setSuccess(final boolean success) { this.success = success; }
        public String getUserId() { return userId; }
        public void setUserId(final String userId) { this.userId = userId; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(final String accessToken) { this.accessToken = accessToken; }
        public String getIdToken() { return idToken; }
        public void setIdToken(final String idToken) { this.idToken = idToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(final String refreshToken) { this.refreshToken = refreshToken; }
        public Integer getExpiresIn() { return expiresIn; }
        public void setExpiresIn(final Integer expiresIn) { this.expiresIn = expiresIn; }
        public String getError() { return error; }
        public void setError(final String error) { this.error = error; }
    }
}

