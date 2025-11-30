package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import com.yubico.webauthn.exception.AssertionFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * FIDO2/WebAuthn Service using Yubico WebAuthn library
 * Implements passkey authentication using WebAuthn standard
 * Compliant with: FIDO2, WebAuthn, W3C standards
 */
@Service
public class FIDO2Service {

    private static final Logger logger = LoggerFactory.getLogger(FIDO2Service.class);

    private final UserRepository userRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private RelyingParty relyingParty;

    // In-memory storage for challenges (in production, store in DynamoDB with TTL)
    // Key: userId, Value: Challenge with expiration
    private final ConcurrentHashMap<String, ChallengeInfo> registrationChallenges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChallengeInfo> authenticationChallenges = new ConcurrentHashMap<>();

    // In-memory storage for passkey credentials (in production, store encrypted in DynamoDB)
    // Key: userId, Value: Map of credentialId -> RegisteredCredential
    private final ConcurrentHashMap<String, Map<ByteArray, RegisteredCredential>> passkeyCredentials = new ConcurrentHashMap<>();

    @Value("${app.fido2.rp-id:budgetbuddy.com}")
    private String rpId;

    @Value("${app.fido2.rp-name:BudgetBuddy}")
    private String rpName;

    @Value("${app.fido2.origin:https://budgetbuddy.com}")
    private String origin;

    @Value("${app.fido2.challenge.expiration-seconds:300}")
    private long challengeExpirationSeconds;

    public FIDO2Service(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Initialize RelyingParty (called after properties are set)
     */
    private RelyingParty getRelyingParty() {
        if (relyingParty == null) {
            try {
                relyingParty = RelyingParty.builder()
                        .identity(RelyingPartyIdentity.builder()
                                .id(rpId)
                                .name(rpName)
                                .build())
                        .credentialRepository(new InMemoryCredentialRepository())
                        .origins(Collections.singleton(origin))
                        .build();
            } catch (Exception e) {
                logger.error("Failed to initialize RelyingParty: {}", e.getMessage(), e);
                throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to initialize FIDO2 service");
            }
        }
        return relyingParty;
    }

    /**
     * Generate registration challenge
     * Returns challenge and options for passkey registration
     */
    public RegistrationChallengeResult generateRegistrationChallenge(final String userId, final String username) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        if (username == null || username.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Username is required");
        }

        try {
            UserIdentity userIdentity = UserIdentity.builder()
                    .name(username)
                    .displayName(username)
                    .id(ByteArray.fromBase64Url(userId))
                    .build();

            StartRegistrationOptions options = StartRegistrationOptions.builder()
                    .user(userIdentity)
                    .timeout(challengeExpirationSeconds)
                    .build();

            RelyingParty rp = getRelyingParty();
            PublicKeyCredentialCreationOptions creationOptions = rp.startRegistration(options);

            // Store challenge with expiration
            ChallengeInfo challengeInfo = new ChallengeInfo(
                    creationOptions.getChallenge(),
                    Instant.now().plusSeconds(challengeExpirationSeconds)
            );
            registrationChallenges.put(userId, challengeInfo);

            logger.info("Registration challenge generated for user: {}", userId);

            return new RegistrationChallengeResult(creationOptions);
        } catch (Base64UrlException e) {
            logger.error("Invalid user ID format: {}", e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid user ID format");
        } catch (Exception e) {
            logger.error("Failed to generate registration challenge: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to generate registration challenge");
        }
    }

    /**
     * Verify registration response
     * Validates the passkey registration and stores the credential
     * @param userId User ID
     * @param credentialJson Full PublicKeyCredential JSON string from client
     */
    public boolean verifyRegistration(final String userId, final String credentialJson) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        if (credentialJson == null || credentialJson.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential JSON is required");
        }

        // Get stored challenge
        ChallengeInfo challengeInfo = registrationChallenges.get(userId);
        if (challengeInfo == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Registration challenge not found or expired");
        }

        // Check expiration
        if (challengeInfo.getExpiresAt().isBefore(Instant.now())) {
            registrationChallenges.remove(userId);
            throw new AppException(ErrorCode.INVALID_INPUT, "Registration challenge expired");
        }

        try {
            // Parse the full credential JSON
            PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential =
                    PublicKeyCredential.parseRegistrationResponseJson(credentialJson);

            // Get the original creation options (we need to reconstruct it)
            // In production, store the original options with the challenge
            UserIdentity userIdentity = UserIdentity.builder()
                    .name(userId)
                    .displayName(userId)
                    .id(ByteArray.fromBase64Url(userId))
                    .build();

            PublicKeyCredentialCreationOptions creationOptions = PublicKeyCredentialCreationOptions.builder()
                    .rp(RelyingPartyIdentity.builder()
                            .id(rpId)
                            .name(rpName)
                            .build())
                    .user(userIdentity)
                    .challenge(challengeInfo.getChallenge())
                    .pubKeyCredParams(Collections.singletonList(PublicKeyCredentialParameters.builder()
                            .alg(COSEAlgorithmIdentifier.ES256)
                            .build()))
                    .build();

            // Finish registration
            RelyingParty rp = getRelyingParty();
            FinishRegistrationOptions finishOptions = FinishRegistrationOptions.builder()
                    .request(creationOptions)
                    .response(credential)
                    .build();

            RegistrationResult result = rp.finishRegistration(finishOptions);

            // Create RegisteredCredential from RegistrationResult
            RegisteredCredential registeredCredential = RegisteredCredential.builder()
                    .credentialId(result.getKeyId().getId())
                    .userHandle(ByteArray.fromBase64Url(userId))
                    .publicKeyCose(result.getPublicKeyCose())
                    .signatureCount(result.getSignatureCount())
                    .build();

            // Store passkey credential
            passkeyCredentials.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(registeredCredential.getCredentialId(), registeredCredential);

            // Remove used challenge
            registrationChallenges.remove(userId);

            logger.info("Passkey registration verified and stored for user: {}", userId);
            return true;
        } catch (RegistrationFailedException e) {
            logger.warn("Passkey registration verification failed for user {}: {}", userId, e.getMessage());
            return false;
        } catch (Base64UrlException e) {
            logger.error("Invalid base64 URL format: {}", e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid credential format");
        } catch (Exception e) {
            logger.error("Failed to verify passkey registration: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to verify passkey registration");
        }
    }

    /**
     * Generate authentication challenge
     * Returns challenge for passkey authentication
     */
    public AuthenticationChallengeResult generateAuthenticationChallenge(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        // Check if user has passkeys
        Map<ByteArray, RegisteredCredential> credentials = passkeyCredentials.get(userId);
        if (credentials == null || credentials.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "No passkeys registered for this user");
        }

        try {
            StartAssertionOptions options = StartAssertionOptions.builder()
                    .username(userId)
                    .timeout(challengeExpirationSeconds)
                    .build();

            RelyingParty rp = getRelyingParty();
            AssertionRequest requestOptions = rp.startAssertion(options);

            // Store challenge with expiration
            ChallengeInfo challengeInfo = new ChallengeInfo(
                    requestOptions.getPublicKeyCredentialRequestOptions().getChallenge(),
                    Instant.now().plusSeconds(challengeExpirationSeconds)
            );
            authenticationChallenges.put(userId, challengeInfo);

            logger.info("Authentication challenge generated for user: {}", userId);

            return new AuthenticationChallengeResult(requestOptions.getPublicKeyCredentialRequestOptions());
        } catch (Exception e) {
            logger.error("Failed to generate authentication challenge: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to generate authentication challenge");
        }
    }

    /**
     * Verify authentication response
     * Validates the passkey authentication
     * @param userId User ID
     * @param credentialJson Full PublicKeyCredential JSON string from client
     */
    public boolean verifyAuthentication(final String userId, final String credentialJson) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        if (credentialJson == null || credentialJson.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential JSON is required");
        }

        // Get stored challenge
        ChallengeInfo challengeInfo = authenticationChallenges.get(userId);
        if (challengeInfo == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Authentication challenge not found or expired");
        }

        // Check expiration
        if (challengeInfo.getExpiresAt().isBefore(Instant.now())) {
            authenticationChallenges.remove(userId);
            throw new AppException(ErrorCode.INVALID_INPUT, "Authentication challenge expired");
        }

        // Check if user has passkeys
        Map<ByteArray, RegisteredCredential> credentials = passkeyCredentials.get(userId);
        if (credentials == null || credentials.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "No passkeys registered for this user");
        }

        try {
            // Parse the full credential JSON
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> publicKeyCredential =
                    PublicKeyCredential.parseAssertionResponseJson(credentialJson);

            // Extract credential ID from response to find the right credential
            ByteArray credentialId = publicKeyCredential.getId();
            RegisteredCredential credential = credentials.get(credentialId);
            if (credential == null) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Credential not found for this user");
            }

            // Create assertion request options
            PublicKeyCredentialRequestOptions requestOptions = PublicKeyCredentialRequestOptions.builder()
                    .challenge(challengeInfo.getChallenge())
                    .rpId(rpId)
                    .allowCredentials(Collections.singletonList(
                            PublicKeyCredentialDescriptor.builder()
                                    .id(credential.getCredentialId())
                                    .type(PublicKeyCredentialType.PUBLIC_KEY)
                                    .build()))
                    .build();

            // Finish assertion
            RelyingParty rp = getRelyingParty();
            FinishAssertionOptions finishOptions = FinishAssertionOptions.builder()
                    .request(AssertionRequest.builder()
                            .publicKeyCredentialRequestOptions(requestOptions)
                            .build())
                    .response(publicKeyCredential)
                    .build();

            AssertionResult result = rp.finishAssertion(finishOptions);

            if (result.isSuccess()) {
                // Update signature counter for replay attack prevention
                // In production, persist this to DynamoDB
                credential = RegisteredCredential.builder()
                        .credentialId(credential.getCredentialId())
                        .userHandle(credential.getUserHandle())
                        .publicKeyCose(credential.getPublicKeyCose())
                        .signatureCount(result.getSignatureCount())
                        .build();
                credentials.put(credential.getCredentialId(), credential);

                // Remove used challenge
                authenticationChallenges.remove(userId);

                logger.info("Passkey authentication verified for user: {}", userId);
                return true;
            } else {
                logger.warn("Passkey authentication verification failed for user: {}", userId);
                return false;
            }
        } catch (AssertionFailedException e) {
            logger.warn("Passkey authentication verification failed for user {}: {}", userId, e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("Failed to verify passkey authentication: {}", e.getMessage(), e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to verify passkey authentication");
        }
    }

    /**
     * List passkeys for a user
     */
    public List<PasskeyInfo> listPasskeys(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        Map<ByteArray, RegisteredCredential> credentials = passkeyCredentials.get(userId);
        if (credentials == null || credentials.isEmpty()) {
            return Collections.emptyList();
        }

        List<PasskeyInfo> passkeyInfos = new ArrayList<>();
        for (RegisteredCredential credential : credentials.values()) {
            passkeyInfos.add(new PasskeyInfo(
                    credential.getCredentialId().getBase64Url(),
                    Instant.now() // In production, store creation time
            ));
        }

        return passkeyInfos;
    }

    /**
     * Delete a passkey
     */
    public void deletePasskey(final String userId, final String credentialId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }
        if (credentialId == null || credentialId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential ID is required");
        }

        Map<ByteArray, RegisteredCredential> credentials = passkeyCredentials.get(userId);
        if (credentials == null || credentials.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Passkey not found");
        }

        try {
            ByteArray credentialIdBytes = ByteArray.fromBase64Url(credentialId);
            RegisteredCredential removed = credentials.remove(credentialIdBytes);
            if (removed == null) {
                throw new AppException(ErrorCode.INVALID_INPUT, "Passkey not found");
            }

            logger.info("Passkey deleted for user: {}, credential: {}", userId, credentialId);
        } catch (Base64UrlException e) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid credential ID format");
        }
    }

    // MARK: - Inner Classes

    /**
     * Registration challenge result
     */
    public static class RegistrationChallengeResult {
        private final PublicKeyCredentialCreationOptions options;

        public RegistrationChallengeResult(final PublicKeyCredentialCreationOptions options) {
            this.options = options;
        }

        public PublicKeyCredentialCreationOptions getOptions() {
            return options;
        }
    }

    /**
     * Authentication challenge result
     */
    public static class AuthenticationChallengeResult {
        private final PublicKeyCredentialRequestOptions options;

        public AuthenticationChallengeResult(final PublicKeyCredentialRequestOptions options) {
            this.options = options;
        }

        public PublicKeyCredentialRequestOptions getOptions() {
            return options;
        }
    }

    /**
     * Challenge information with expiration
     */
    private static class ChallengeInfo {
        private final ByteArray challenge;
        private final Instant expiresAt;

        public ChallengeInfo(final ByteArray challenge, final Instant expiresAt) {
            this.challenge = challenge;
            this.expiresAt = expiresAt;
        }

        public ByteArray getChallenge() {
            return challenge;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }
    }

    /**
     * Passkey information (public)
     */
    public static class PasskeyInfo {
        private final String credentialId;
        private final Instant createdAt;

        public PasskeyInfo(final String credentialId, final Instant createdAt) {
            this.credentialId = credentialId;
            this.createdAt = createdAt;
        }

        public String getCredentialId() {
            return credentialId;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }
    }

    /**
     * In-memory credential repository for Yubico library
     * In production, implement proper CredentialRepository interface with DynamoDB
     */
    private class InMemoryCredentialRepository implements CredentialRepository {
        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            Map<ByteArray, RegisteredCredential> credentials = passkeyCredentials.get(username);
            if (credentials == null || credentials.isEmpty()) {
                return Collections.emptySet();
            }
            return credentials.keySet().stream()
                    .map(credentialId -> PublicKeyCredentialDescriptor.builder()
                            .id(credentialId)
                            .type(PublicKeyCredentialType.PUBLIC_KEY)
                            .build())
                    .collect(Collectors.toSet());
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            Map<ByteArray, RegisteredCredential> credentials = passkeyCredentials.get(username);
            if (credentials == null || credentials.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(ByteArray.fromBase64Url(username));
            } catch (Base64UrlException e) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            for (Map.Entry<String, Map<ByteArray, RegisteredCredential>> entry : passkeyCredentials.entrySet()) {
                try {
                    ByteArray userIdBytes = ByteArray.fromBase64Url(entry.getKey());
                    if (userIdBytes.equals(userHandle)) {
                        return Optional.of(entry.getKey());
                    }
                } catch (Base64UrlException e) {
                    // Skip invalid user IDs
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
            // Find credential by credentialId and userHandle
            for (Map.Entry<String, Map<ByteArray, RegisteredCredential>> entry : passkeyCredentials.entrySet()) {
                try {
                    ByteArray userIdBytes = ByteArray.fromBase64Url(entry.getKey());
                    if (userIdBytes.equals(userHandle)) {
                        RegisteredCredential credential = entry.getValue().get(credentialId);
                        if (credential != null) {
                            return Optional.of(credential);
                        }
                    }
                } catch (Base64UrlException e) {
                    // Skip invalid user IDs
                }
            }
            return Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray userHandle) {
            Set<RegisteredCredential> results = new HashSet<>();
            for (Map.Entry<String, Map<ByteArray, RegisteredCredential>> entry : passkeyCredentials.entrySet()) {
                try {
                    ByteArray userIdBytes = ByteArray.fromBase64Url(entry.getKey());
                    if (userIdBytes.equals(userHandle)) {
                        results.addAll(entry.getValue().values());
                    }
                } catch (Base64UrlException e) {
                    // Skip invalid user IDs
                }
            }
            return results;
        }
    }
}
