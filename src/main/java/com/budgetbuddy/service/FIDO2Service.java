package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.yubico.webauthn.*;
import com.yubico.webauthn.data.*;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import com.yubico.webauthn.exception.AssertionFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * FIDO2/WebAuthn Service using Yubico WebAuthn library
 * Implements passkey authentication using WebAuthn standard
 * Compliant with: FIDO2, WebAuthn, W3C standards
 */
@Service
public class FIDO2Service {

    private static final Logger logger = LoggerFactory.getLogger(FIDO2Service.class);

    private final com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository credentialRepository;
    private final com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository challengeRepository;
    private RelyingParty relyingParty;

    @Value("${app.fido2.rp-id:budgetbuddy.com}")
    private String rpId;

    @Value("${app.fido2.rp-name:BudgetBuddy}")
    private String rpName;

    @Value("${app.fido2.origin:https://budgetbuddy.com}")
    private String origin;

    @Value("${app.fido2.challenge.expiration-seconds:300}")
    private long challengeExpirationSeconds;

    public FIDO2Service(
            final com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository credentialRepository,
            final com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository challengeRepository) {
        this.credentialRepository = credentialRepository;
        this.challengeRepository = challengeRepository;
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
                        .credentialRepository(new DynamoDBCredentialRepository())
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

            // Store challenge in DynamoDB with TTL
            com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challenge = new com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable();
            challenge.setChallengeKey(com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository.generateChallengeKey(userId, "registration"));
            challenge.setChallenge(creationOptions.getChallenge().getBase64Url());
            challenge.setChallengeType("registration");
            challenge.setUserId(userId);
            challenge.setExpiresAt(Instant.now().plusSeconds(challengeExpirationSeconds));
            challengeRepository.save(challenge);

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

        // Get stored challenge from DynamoDB
        String challengeKey = com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository.generateChallengeKey(userId, "registration");
        java.util.Optional<com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable> challengeOpt = challengeRepository.findByChallengeKey(challengeKey);
        if (challengeOpt.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Registration challenge not found or expired");
        }
        com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challengeTable = challengeOpt.get();
        
        // Check expiration
        if (challengeTable.getExpiresAt().isBefore(Instant.now())) {
            challengeRepository.delete(challengeKey);
            throw new AppException(ErrorCode.INVALID_INPUT, "Registration challenge expired");
        }
        
        // Convert challenge from base64 to ByteArray
        ByteArray challenge;
        try {
            challenge = ByteArray.fromBase64Url(challengeTable.getChallenge());
        } catch (Base64UrlException e) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid challenge format");
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
                    .challenge(challenge)
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

            // Store passkey credential in DynamoDB
            com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable = new com.budgetbuddy.model.dynamodb.FIDO2CredentialTable();
            credentialTable.setCredentialId(registeredCredential.getCredentialId().getBase64Url());
            credentialTable.setUserId(userId);
            credentialTable.setUserHandle(userId);
            // publicKeyCose is already bytes, encode to base64 for storage
            credentialTable.setPublicKeyCose(java.util.Base64.getEncoder().encodeToString(registeredCredential.getPublicKeyCose().getBytes()));
            credentialTable.setSignatureCount(registeredCredential.getSignatureCount());
            credentialTable.setCreatedAt(Instant.now());
            credentialTable.setLastUsedAt(Instant.now());
            credentialTable.setEnabled(true);
            credentialRepository.save(credentialTable);

            // Remove used challenge
            challengeRepository.delete(challengeKey);

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
        java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials = credentialRepository.findByUserId(userId);
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

            // Store challenge in DynamoDB with TTL
            com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challenge = new com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable();
            challenge.setChallengeKey(com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository.generateChallengeKey(userId, "authentication"));
            challenge.setChallenge(requestOptions.getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url());
            challenge.setChallengeType("authentication");
            challenge.setUserId(userId);
            challenge.setExpiresAt(Instant.now().plusSeconds(challengeExpirationSeconds));
            challengeRepository.save(challenge);

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

        // Get stored challenge from DynamoDB
        String challengeKey = com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository.generateChallengeKey(userId, "authentication");
        java.util.Optional<com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable> challengeOpt = challengeRepository.findByChallengeKey(challengeKey);
        if (challengeOpt.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Authentication challenge not found or expired");
        }
        com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challengeTable = challengeOpt.get();
        
        // Check expiration
        if (challengeTable.getExpiresAt().isBefore(Instant.now())) {
            challengeRepository.delete(challengeKey);
            throw new AppException(ErrorCode.INVALID_INPUT, "Authentication challenge expired");
        }
        
        // Convert challenge from base64 to ByteArray
        ByteArray challenge;
        try {
            challenge = ByteArray.fromBase64Url(challengeTable.getChallenge());
        } catch (Base64UrlException e) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid challenge format");
        }

        // Check if user has passkeys
        java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials = credentialRepository.findByUserId(userId);
        if (credentials == null || credentials.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "No passkeys registered for this user");
        }

        try {
            // Parse the full credential JSON
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> publicKeyCredential =
                    PublicKeyCredential.parseAssertionResponseJson(credentialJson);

            // Extract credential ID from response to find the right credential
            ByteArray credentialId = publicKeyCredential.getId();
            String credentialIdBase64 = credentialId.getBase64Url();
            
            // Find credential in DynamoDB
            com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable = credentials.stream()
                    .filter(c -> credentialIdBase64.equals(c.getCredentialId()))
                    .findFirst()
                    .orElseThrow(() -> new AppException(ErrorCode.INVALID_INPUT, "Credential not found for this user"));
            
            // Convert to RegisteredCredential
            // publicKeyCose is already base64 encoded in the table, so we decode it to bytes first
            byte[] publicKeyCoseBytes = java.util.Base64.getDecoder().decode(credentialTable.getPublicKeyCose());
            RegisteredCredential credential = RegisteredCredential.builder()
                    .credentialId(credentialId)
                    .userHandle(ByteArray.fromBase64Url(credentialTable.getUserHandle()))
                    .publicKeyCose(new ByteArray(publicKeyCoseBytes))
                    .signatureCount(credentialTable.getSignatureCount() != null ? credentialTable.getSignatureCount() : 0L)
                    .build();

            // Create assertion request options
            PublicKeyCredentialRequestOptions requestOptions = PublicKeyCredentialRequestOptions.builder()
                    .challenge(challenge)
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
                // Update signature counter for replay attack prevention in DynamoDB
                credentialRepository.updateSignatureCount(credentialIdBase64, result.getSignatureCount());
                
                // Update last used timestamp
                com.budgetbuddy.model.dynamodb.FIDO2CredentialTable updatedCredential = new com.budgetbuddy.model.dynamodb.FIDO2CredentialTable();
                updatedCredential.setCredentialId(credentialIdBase64);
                updatedCredential.setLastUsedAt(Instant.now());
                credentialRepository.save(updatedCredential);

                // Remove used challenge
                challengeRepository.delete(challengeKey);

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

        java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials = credentialRepository.findByUserId(userId);
        if (credentials == null || credentials.isEmpty()) {
            return Collections.emptyList();
        }

        List<PasskeyInfo> passkeyInfos = new ArrayList<>();
        for (com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credential : credentials) {
            if (credential.getEnabled() != null && credential.getEnabled()) {
                passkeyInfos.add(new PasskeyInfo(
                        credential.getCredentialId(),
                        credential.getCreatedAt() != null ? credential.getCreatedAt() : Instant.now()
                ));
            }
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

        // Verify credential belongs to user
        java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials = credentialRepository.findByUserId(userId);
        boolean found = credentials.stream()
                .anyMatch(c -> credentialId.equals(c.getCredentialId()));
        if (!found) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Passkey not found");
        }

        // Delete from DynamoDB
        credentialRepository.delete(credentialId);

        logger.info("Passkey deleted for user: {}, credential: {}", userId, credentialId);
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
     * DynamoDB credential repository for Yubico library
     * Implements CredentialRepository interface using DynamoDB storage
     */
    private class DynamoDBCredentialRepository implements CredentialRepository {
        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials = credentialRepository.findByUserId(username);
            if (credentials == null || credentials.isEmpty()) {
                return Collections.emptySet();
            }
            return credentials.stream()
                    .filter(c -> c.getEnabled() != null && c.getEnabled())
                    .map(credential -> {
                        try {
                            ByteArray credentialId = ByteArray.fromBase64Url(credential.getCredentialId());
                            return PublicKeyCredentialDescriptor.builder()
                                    .id(credentialId)
                                    .type(PublicKeyCredentialType.PUBLIC_KEY)
                                    .build();
                        } catch (Base64UrlException e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            try {
                return Optional.of(ByteArray.fromBase64Url(username));
            } catch (Base64UrlException e) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            try {
                String userId = userHandle.getBase64Url();
                // Verify user exists by checking if they have any credentials
                java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials = credentialRepository.findByUserId(userId);
                if (credentials != null && !credentials.isEmpty()) {
                    return Optional.of(userId);
                }
            } catch (Exception e) {
                // Skip invalid user handles
            }
            return Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
            try {
                String userId = userHandle.getBase64Url();
                String credentialIdBase64 = credentialId.getBase64Url();
                
                java.util.Optional<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentialOpt = 
                        credentialRepository.findByCredentialId(credentialIdBase64);
                
                if (credentialOpt.isPresent()) {
                    com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable = credentialOpt.get();
                    if (userId.equals(credentialTable.getUserId()) && 
                        (credentialTable.getEnabled() == null || credentialTable.getEnabled())) {
                        byte[] publicKeyCoseBytes = java.util.Base64.getDecoder().decode(credentialTable.getPublicKeyCose());
                        RegisteredCredential credential = RegisteredCredential.builder()
                                .credentialId(credentialId)
                                .userHandle(userHandle)
                                .publicKeyCose(new ByteArray(publicKeyCoseBytes))
                                .signatureCount(credentialTable.getSignatureCount() != null ? credentialTable.getSignatureCount() : 0L)
                                .build();
                        return Optional.of(credential);
                    }
                }
            } catch (Exception e) {
                logger.debug("Error looking up credential: {}", e.getMessage());
            }
            return Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray userHandle) {
            Set<RegisteredCredential> results = new HashSet<>();
            try {
                String userId = userHandle.getBase64Url();
                java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials = credentialRepository.findByUserId(userId);
                
                for (com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable : credentials) {
                    if (credentialTable.getEnabled() == null || credentialTable.getEnabled()) {
                        try {
                            ByteArray credentialId = ByteArray.fromBase64Url(credentialTable.getCredentialId());
                            byte[] publicKeyCoseBytes = java.util.Base64.getDecoder().decode(credentialTable.getPublicKeyCose());
                            RegisteredCredential credential = RegisteredCredential.builder()
                                    .credentialId(credentialId)
                                    .userHandle(userHandle)
                                    .publicKeyCose(new ByteArray(publicKeyCoseBytes))
                                    .signatureCount(credentialTable.getSignatureCount() != null ? credentialTable.getSignatureCount() : 0L)
                                    .build();
                            results.add(credential);
                        } catch (Exception e) {
                            logger.debug("Error converting credential: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Error looking up all credentials: {}", e.getMessage());
            }
            return results;
        }
    }
}
