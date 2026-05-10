package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.COSEAlgorithmIdentifier;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialParameters;
import com.yubico.webauthn.data.PublicKeyCredentialRequestOptions;
import com.yubico.webauthn.data.PublicKeyCredentialType;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * FIDO2/WebAuthn Service using Yubico WebAuthn library Implements passkey authentication using
 * WebAuthn standard Compliant with: FIDO2, WebAuthn, W3C standards
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class FIDO2Service {

    private static final String USER_ID_IS_REQUIRED = "User ID is required";

    private static final String AUTHENTICATION = "authentication";

    private static final String REGISTRATION = "registration";

    private static final Logger LOGGER = LoggerFactory.getLogger(FIDO2Service.class);

    private final com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository
            credentialRepository;
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
            final com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository
                    credentialRepository,
            final com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository
                    challengeRepository) {
        this.credentialRepository = credentialRepository;
        this.challengeRepository = challengeRepository;
    }

    /** Initialize RelyingParty (called after properties are set) */
    private RelyingParty getRelyingParty() {
        if (relyingParty == null) {
            try {
                relyingParty =
                        RelyingParty.builder()
                                .identity(
                                        RelyingPartyIdentity.builder()
                                                .id(rpId)
                                                .name(rpName)
                                                .build())
                                .credentialRepository(new DynamoDBCredentialRepository())
                                .origins(Collections.singleton(origin))
                                .build();
            } catch (Exception e) {
                LOGGER.error("Failed to initialize RelyingParty: {}", e.getMessage(), e);
                throw new AppException(
                        ErrorCode.INTERNAL_SERVER_ERROR, "Failed to initialize FIDO2 service");
            }
        }
        return relyingParty;
    }

    /** Generate registration challenge Returns challenge and options for passkey registration */
    public RegistrationChallengeResult generateRegistrationChallenge(
            final String userId, final String username) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }
        if (username == null || username.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Username is required");
        }

        try {
            final UserIdentity userIdentity =
                    UserIdentity.builder()
                            .name(username)
                            .displayName(username)
                            .id(ByteArray.fromBase64Url(userId))
                            .build();

            final StartRegistrationOptions options =
                    StartRegistrationOptions.builder()
                            .user(userIdentity)
                            .timeout(challengeExpirationSeconds)
                            .build();

            final RelyingParty rp = getRelyingParty();
            final PublicKeyCredentialCreationOptions creationOptions =
                    rp.startRegistration(options);

            // Store challenge in DynamoDB with TTL
            final com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challenge =
                    new com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable();
            challenge.setChallengeKey(
                    com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository
                            .generateChallengeKey(userId, REGISTRATION));
            challenge.setChallenge(creationOptions.getChallenge().getBase64Url());
            challenge.setChallengeType(REGISTRATION);
            challenge.setUserId(userId);
            challenge.setExpiresAt(Instant.now().plusSeconds(challengeExpirationSeconds));
            challengeRepository.save(challenge);

            LOGGER.info("Registration challenge generated for user: {}", userId);

            return new RegistrationChallengeResult(creationOptions);
        } catch (Base64UrlException e) {
            LOGGER.error("Invalid user ID format: {}", e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid user ID format");
        } catch (Exception e) {
            LOGGER.error("Failed to generate registration challenge: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to generate registration challenge");
        }
    }

    /**
     * Verify registration response Validates the passkey registration and stores the credential
     *
     * @param userId User ID
     * @param credentialJson Full PublicKeyCredential JSON string from client
     */
    public boolean verifyRegistration(final String userId, final String credentialJson) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }
        if (credentialJson == null || credentialJson.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential JSON is required");
        }

        // Get stored challenge from DynamoDB
        final String challengeKey =
                com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository.generateChallengeKey(
                        userId, REGISTRATION);
        final Optional<com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable> challengeOpt =
                challengeRepository.findByChallengeKey(challengeKey);
        if (challengeOpt.isEmpty()) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Registration challenge not found or expired");
        }
        final com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challengeTable =
                challengeOpt.get();

        // Check expiration
        if (challengeTable.getExpiresAt().isBefore(Instant.now())) {
            challengeRepository.delete(challengeKey);
            throw new AppException(ErrorCode.INVALID_INPUT, "Registration challenge expired");
        }

        // Convert challenge from base64 to ByteArray
        final ByteArray challenge;
        try {
            challenge = ByteArray.fromBase64Url(challengeTable.getChallenge());
        } catch (Base64UrlException e) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid challenge format");
        }

        try {
            // Parse the full credential JSON
            final PublicKeyCredential<
                            AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs>
                    credential = PublicKeyCredential.parseRegistrationResponseJson(credentialJson);

            // Get the original creation options (we need to reconstruct it)
            // In production, store the original options with the challenge
            final UserIdentity userIdentity =
                    UserIdentity.builder()
                            .name(userId)
                            .displayName(userId)
                            .id(ByteArray.fromBase64Url(userId))
                            .build();

            final PublicKeyCredentialCreationOptions creationOptions =
                    PublicKeyCredentialCreationOptions.builder()
                            .rp(RelyingPartyIdentity.builder().id(rpId).name(rpName).build())
                            .user(userIdentity)
                            .challenge(challenge)
                            .pubKeyCredParams(
                                    Collections.singletonList(
                                            PublicKeyCredentialParameters.builder()
                                                    .alg(COSEAlgorithmIdentifier.ES256)
                                                    .build()))
                            .build();

            // Finish registration
            final RelyingParty rp = getRelyingParty();
            final FinishRegistrationOptions finishOptions =
                    FinishRegistrationOptions.builder()
                            .request(creationOptions)
                            .response(credential)
                            .build();

            final RegistrationResult result = rp.finishRegistration(finishOptions);

            // Create RegisteredCredential from RegistrationResult
            final RegisteredCredential registeredCredential =
                    RegisteredCredential.builder()
                            .credentialId(result.getKeyId().getId())
                            .userHandle(ByteArray.fromBase64Url(userId))
                            .publicKeyCose(result.getPublicKeyCose())
                            .signatureCount(result.getSignatureCount())
                            .build();

            // Store passkey credential in DynamoDB
            final com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable =
                    new com.budgetbuddy.model.dynamodb.FIDO2CredentialTable();
            credentialTable.setCredentialId(registeredCredential.getCredentialId().getBase64Url());
            credentialTable.setUserId(userId);
            credentialTable.setUserHandle(userId);
            // publicKeyCose is already bytes, encode to base64 for storage
            credentialTable.setPublicKeyCose(
                    java.util.Base64.getEncoder()
                            .encodeToString(registeredCredential.getPublicKeyCose().getBytes()));
            credentialTable.setSignatureCount(registeredCredential.getSignatureCount());
            credentialTable.setCreatedAt(Instant.now());
            credentialTable.setLastUsedAt(Instant.now());
            credentialTable.setEnabled(true);
            credentialRepository.save(credentialTable);

            // Remove used challenge
            challengeRepository.delete(challengeKey);

            LOGGER.info("Passkey registration verified and stored for user: {}", userId);
            return true;
        } catch (RegistrationFailedException e) {
            LOGGER.warn(
                    "Passkey registration verification failed for user {}: {}",
                    userId,
                    e.getMessage());
            return false;
        } catch (Base64UrlException e) {
            LOGGER.error("Invalid base64 URL format: {}", e.getMessage());
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid credential format");
        } catch (Exception e) {
            LOGGER.error("Failed to verify passkey registration: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to verify passkey registration");
        }
    }

    /** Generate authentication challenge Returns challenge for passkey authentication */
    public AuthenticationChallengeResult generateAuthenticationChallenge(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }

        // Check if user has passkeys
        final List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials =
                credentialRepository.findByUserId(userId);
        if (credentials == null || credentials.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "No passkeys registered for this user");
        }

        try {
            final StartAssertionOptions options =
                    StartAssertionOptions.builder()
                            .username(userId)
                            .timeout(challengeExpirationSeconds)
                            .build();

            final RelyingParty rp = getRelyingParty();
            final AssertionRequest requestOptions = rp.startAssertion(options);

            // Store challenge in DynamoDB with TTL
            final com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challenge =
                    new com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable();
            challenge.setChallengeKey(
                    com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository
                            .generateChallengeKey(userId, AUTHENTICATION));
            challenge.setChallenge(
                    requestOptions
                            .getPublicKeyCredentialRequestOptions()
                            .getChallenge()
                            .getBase64Url());
            challenge.setChallengeType(AUTHENTICATION);
            challenge.setUserId(userId);
            challenge.setExpiresAt(Instant.now().plusSeconds(challengeExpirationSeconds));
            challengeRepository.save(challenge);

            LOGGER.info("Authentication challenge generated for user: {}", userId);

            return new AuthenticationChallengeResult(
                    requestOptions.getPublicKeyCredentialRequestOptions());
        } catch (Exception e) {
            LOGGER.error("Failed to generate authentication challenge: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to generate authentication challenge");
        }
    }

    /**
     * Verify authentication response Validates the passkey authentication
     *
     * @param userId User ID
     * @param credentialJson Full PublicKeyCredential JSON string from client
     */
    public boolean verifyAuthentication(final String userId, final String credentialJson) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }
        if (credentialJson == null || credentialJson.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential JSON is required");
        }

        // Get stored challenge from DynamoDB
        final String challengeKey =
                com.budgetbuddy.repository.dynamodb.FIDO2ChallengeRepository.generateChallengeKey(
                        userId, AUTHENTICATION);
        final Optional<com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable> challengeOpt =
                challengeRepository.findByChallengeKey(challengeKey);
        if (challengeOpt.isEmpty()) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "Authentication challenge not found or expired");
        }
        final com.budgetbuddy.model.dynamodb.FIDO2ChallengeTable challengeTable =
                challengeOpt.get();

        // Check expiration
        if (challengeTable.getExpiresAt().isBefore(Instant.now())) {
            challengeRepository.delete(challengeKey);
            throw new AppException(ErrorCode.INVALID_INPUT, "Authentication challenge expired");
        }

        // Convert challenge from base64 to ByteArray
        final ByteArray challenge;
        try {
            challenge = ByteArray.fromBase64Url(challengeTable.getChallenge());
        } catch (Base64UrlException e) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Invalid challenge format");
        }

        // Check if user has passkeys
        final List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials =
                credentialRepository.findByUserId(userId);
        if (credentials == null || credentials.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "No passkeys registered for this user");
        }

        try {
            // Parse the full credential JSON
            final PublicKeyCredential<
                            AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs>
                    publicKeyCredential =
                            PublicKeyCredential.parseAssertionResponseJson(credentialJson);

            // Extract credential ID from response to find the right credential
            final ByteArray credentialId = publicKeyCredential.getId();
            final String credentialIdBase64 = credentialId.getBase64Url();

            // Find credential in DynamoDB
            final com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable =
                    credentials.stream()
                            .filter(c -> credentialIdBase64.equals(c.getCredentialId()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorCode.INVALID_INPUT,
                                                    "Credential not found for this user"));

            // Convert to RegisteredCredential
            // publicKeyCose is already base64 encoded in the table, so we decode it to bytes first
            final byte[] publicKeyCoseBytes =
                    java.util.Base64.getDecoder().decode(credentialTable.getPublicKeyCose());
            final RegisteredCredential credential =
                    RegisteredCredential.builder()
                            .credentialId(credentialId)
                            .userHandle(ByteArray.fromBase64Url(credentialTable.getUserHandle()))
                            .publicKeyCose(new ByteArray(publicKeyCoseBytes))
                            .signatureCount(
                                    credentialTable.getSignatureCount() != null
                                            ? credentialTable.getSignatureCount()
                                            : 0L)
                            .build();

            // Create assertion request options
            final PublicKeyCredentialRequestOptions requestOptions =
                    PublicKeyCredentialRequestOptions.builder()
                            .challenge(challenge)
                            .rpId(rpId)
                            .allowCredentials(
                                    Collections.singletonList(
                                            PublicKeyCredentialDescriptor.builder()
                                                    .id(credential.getCredentialId())
                                                    .type(PublicKeyCredentialType.PUBLIC_KEY)
                                                    .build()))
                            .build();

            // Finish assertion
            final RelyingParty rp = getRelyingParty();
            final FinishAssertionOptions finishOptions =
                    FinishAssertionOptions.builder()
                            .request(
                                    AssertionRequest.builder()
                                            .publicKeyCredentialRequestOptions(requestOptions)
                                            .build())
                            .response(publicKeyCredential)
                            .build();

            final AssertionResult result = rp.finishAssertion(finishOptions);

            if (result.isSuccess()) {
                // Update signature counter for replay attack prevention in DynamoDB
                credentialRepository.updateSignatureCount(
                        credentialIdBase64, result.getSignatureCount());

                // Update last used timestamp
                final com.budgetbuddy.model.dynamodb.FIDO2CredentialTable updatedCredential =
                        new com.budgetbuddy.model.dynamodb.FIDO2CredentialTable();
                updatedCredential.setCredentialId(credentialIdBase64);
                updatedCredential.setLastUsedAt(Instant.now());
                credentialRepository.save(updatedCredential);

                // Remove used challenge
                challengeRepository.delete(challengeKey);

                LOGGER.info("Passkey authentication verified for user: {}", userId);
                return true;
            } else {
                LOGGER.warn("Passkey authentication verification failed for user: {}", userId);
                return false;
            }
        } catch (AssertionFailedException e) {
            LOGGER.warn(
                    "Passkey authentication verification failed for user {}: {}",
                    userId,
                    e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.error("Failed to verify passkey authentication: {}", e.getMessage(), e);
            throw new AppException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to verify passkey authentication");
        }
    }

    /** List passkeys for a user */
    public List<PasskeyInfo> listPasskeys(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }

        final List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials =
                credentialRepository.findByUserId(userId);
        if (credentials == null || credentials.isEmpty()) {
            return Collections.emptyList();
        }

        final List<PasskeyInfo> passkeyInfos = new ArrayList<>();
        for (final com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credential : credentials) {
            if (credential.getEnabled() != null && credential.getEnabled()) {
                passkeyInfos.add(
                        new PasskeyInfo(
                                credential.getCredentialId(),
                                credential.getCreatedAt() != null
                                        ? credential.getCreatedAt()
                                        : Instant.now()));
            }
        }

        return passkeyInfos;
    }

    /** Delete a passkey */
    public void deletePasskey(final String userId, final String credentialId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_ID_IS_REQUIRED);
        }
        if (credentialId == null || credentialId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Credential ID is required");
        }

        // Verify credential belongs to user
        final List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials =
                credentialRepository.findByUserId(userId);
        final boolean found =
                credentials.stream().anyMatch(c -> credentialId.equals(c.getCredentialId()));
        if (!found) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Passkey not found");
        }

        // Delete from DynamoDB
        credentialRepository.delete(credentialId);

        LOGGER.info("Passkey deleted for user: {}, credential: {}", userId, credentialId);
    }

    // MARK: - Inner Classes

    /** Registration challenge result */
    public static class RegistrationChallengeResult {
        private final PublicKeyCredentialCreationOptions options;

        public RegistrationChallengeResult(final PublicKeyCredentialCreationOptions options) {
            this.options = options;
        }

        public PublicKeyCredentialCreationOptions getOptions() {
            return options;
        }
    }

    /** Authentication challenge result */
    public static class AuthenticationChallengeResult {
        private final PublicKeyCredentialRequestOptions options;

        public AuthenticationChallengeResult(final PublicKeyCredentialRequestOptions options) {
            this.options = options;
        }

        public PublicKeyCredentialRequestOptions getOptions() {
            return options;
        }
    }

    /** Passkey information (public) */
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
     * DynamoDB credential repository for Yubico library Implements CredentialRepository interface
     * using DynamoDB storage
     */
    private final class DynamoDBCredentialRepository implements CredentialRepository {
        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(
                final String username) {
            final java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable> credentials =
                    credentialRepository.findByUserId(username);
            if (credentials == null || credentials.isEmpty()) {
                return Collections.emptySet();
            }
            return credentials.stream()
                    .filter(c -> c.getEnabled() != null && c.getEnabled())
                    .map(
                            credential -> {
                                try {
                                    final ByteArray credentialId =
                                            ByteArray.fromBase64Url(credential.getCredentialId());
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
        public Optional<ByteArray> getUserHandleForUsername(final String username) {
            try {
                return Optional.of(ByteArray.fromBase64Url(username));
            } catch (Base64UrlException e) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<String> getUsernameForUserHandle(final ByteArray userHandle) {
            try {
                final String userId = userHandle.getBase64Url();
                // Verify user exists by checking if they have any credentials
                final java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable>
                        credentials = credentialRepository.findByUserId(userId);
                if (credentials != null && !credentials.isEmpty()) {
                    return Optional.of(userId);
                }
            } catch (Exception e) {
                // Skip invalid user handles
            }
            return Optional.empty();
        }

        @Override
        public Optional<RegisteredCredential> lookup(
                final ByteArray credentialId, final ByteArray userHandle) {
            try {
                final String userId = userHandle.getBase64Url();
                final String credentialIdBase64 = credentialId.getBase64Url();

                final java.util.Optional<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable>
                        credentialOpt = credentialRepository.findByCredentialId(credentialIdBase64);

                if (credentialOpt.isPresent()) {
                    final com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable =
                            credentialOpt.get();
                    if (userId.equals(credentialTable.getUserId())
                            && (credentialTable.getEnabled() == null
                                    || credentialTable.getEnabled())) {
                        final byte[] publicKeyCoseBytes =
                                java.util.Base64.getDecoder()
                                        .decode(credentialTable.getPublicKeyCose());
                        final RegisteredCredential credential =
                                RegisteredCredential.builder()
                                        .credentialId(credentialId)
                                        .userHandle(userHandle)
                                        .publicKeyCose(new ByteArray(publicKeyCoseBytes))
                                        .signatureCount(
                                                credentialTable.getSignatureCount() != null
                                                        ? credentialTable.getSignatureCount()
                                                        : 0L)
                                        .build();
                        return Optional.of(credential);
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Error looking up credential: {}", e.getMessage());
            }
            return Optional.empty();
        }

        @Override
        public Set<RegisteredCredential> lookupAll(final ByteArray userHandle) {
            final Set<RegisteredCredential> results = new HashSet<>();
            try {
                final String userId = userHandle.getBase64Url();
                final java.util.List<com.budgetbuddy.model.dynamodb.FIDO2CredentialTable>
                        credentials = credentialRepository.findByUserId(userId);

                for (final com.budgetbuddy.model.dynamodb.FIDO2CredentialTable credentialTable :
                        credentials) {
                    if (credentialTable.getEnabled() == null || credentialTable.getEnabled()) {
                        try {
                            final ByteArray credentialId =
                                    ByteArray.fromBase64Url(credentialTable.getCredentialId());
                            final byte[] publicKeyCoseBytes =
                                    java.util.Base64.getDecoder()
                                            .decode(credentialTable.getPublicKeyCose());
                            final RegisteredCredential credential =
                                    RegisteredCredential.builder()
                                            .credentialId(credentialId)
                                            .userHandle(userHandle)
                                            .publicKeyCose(new ByteArray(publicKeyCoseBytes))
                                            .signatureCount(
                                                    credentialTable.getSignatureCount() != null
                                                            ? credentialTable.getSignatureCount()
                                                            : 0L)
                                            .build();
                            results.add(credential);
                        } catch (Exception e) {
                            LOGGER.debug("Error converting credential: {}", e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Error looking up all credentials: {}", e.getMessage());
            }
            return results;
        }
    }
}
