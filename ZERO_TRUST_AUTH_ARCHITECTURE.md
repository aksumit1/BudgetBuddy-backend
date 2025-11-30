# Zero Trust Authentication Architecture

## Foundational Principles

1. **No Stored Passwords**: App never stores user's primary password or PIN in plaintext or hash form on device
2. **Token-Based Access**: All authentication uses short-lived access tokens and longer-lived, encrypted refresh tokens
3. **Phishing-Resistant MFA**: Biometrics use secure public-key cryptography (FIDO/Passkeys)

## Architecture Overview

### Token Lifecycle

```
Registration/Login → Backend Issues:
  - Access Token (short-lived, 15 minutes)
  - Refresh Token (longer-lived, 30 days, encrypted in keychain)
  
App Startup:
  1. Check keychain for encrypted refresh token
  2. If found: Local unlock (biometric/PIN) → Decrypt token
  3. Send decrypted token to backend for validation
  4. Backend validates → Issues new access token
  5. If invalid: Clear token, route to sign-in
```

### FIDO/Passkey Flow

```
User Enables Biometrics:
  1. Generate key pair in Secure Enclave (private key never leaves device)
  2. Send public key to backend
  3. Backend stores public key with user account
  
Authentication:
  1. User authenticates with biometric
  2. Device signs challenge with private key
  3. Backend verifies signature with stored public key
  4. If valid: Issue tokens
```

## Backend Implementation

### New Endpoints

1. **POST /api/auth/token/validate** - Validate refresh token
   - Input: Encrypted refresh token
   - Output: New access token + new refresh token (rotation)
   - Security: Device attestation check

2. **POST /api/auth/passkey/register** - Register FIDO/Passkey
   - Input: Public key, credential ID
   - Output: Success confirmation
   - Security: Requires authenticated session

3. **POST /api/auth/passkey/authenticate** - Authenticate with Passkey
   - Input: Credential ID, signature, challenge
   - Output: Access token + refresh token
   - Security: Verify signature with stored public key

4. **POST /api/auth/device/attest** - Device attestation
   - Input: Device attestation token (DeviceCheck/Play Integrity)
   - Output: Device trust status
   - Security: Verify attestation with platform APIs

### Token Management

- **Access Token**: 15 minutes expiry, stored in memory only
- **Refresh Token**: 30 days expiry, encrypted in keychain
- **Token Rotation**: Every refresh issues new tokens (old refresh token invalidated)
- **Token Revocation**: Backend maintains revocation list

### Database Schema Changes

```sql
-- New table for FIDO credentials
CREATE TABLE fido_credentials (
    credential_id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    public_key TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    device_info TEXT,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- New table for refresh token tracking
CREATE TABLE refresh_tokens (
    token_id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    device_id VARCHAR(255),
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    last_used_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- New table for device attestation
CREATE TABLE device_attestations (
    device_id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    platform VARCHAR(50) NOT NULL, -- 'ios' or 'android'
    attestation_token TEXT,
    verified_at TIMESTAMP,
    trust_status VARCHAR(50), -- 'trusted', 'untrusted', 'jailbroken'
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);
```

## iOS Implementation

### New Components

1. **RefreshTokenManager** - Manages encrypted refresh token in keychain
2. **PasskeyManager** - Handles FIDO/Passkey operations with Secure Enclave
3. **DeviceAttestationService** - Handles DeviceCheck attestation
4. **TokenValidationService** - Handles backend token validation

### Startup Flow

```swift
func checkAuthenticationState() async -> AuthState {
    // 1. Check keychain for encrypted refresh token
    guard let encryptedToken = try? security.loadEncryptedRefreshToken() else {
        return .needsSignIn
    }
    
    // 2. Attempt local unlock (biometric/PIN)
    guard let decryptedToken = try? await localUnlock(encryptedToken: encryptedToken) else {
        return .needsSignIn
    }
    
    // 3. Validate with backend
    do {
        let response = try await tokenValidationService.validate(decryptedToken)
        // Store new tokens
        try security.saveAccessToken(response.accessToken)
        try security.saveEncryptedRefreshToken(response.refreshToken)
        return .authenticated
    } catch {
        // Token invalid - clear and sign in
        security.clearTokens()
        return .needsSignIn
    }
}
```

### PIN Usage

- PIN is **only** used to decrypt the local refresh token
- PIN is **never** sent to backend
- PIN is stored as encrypted key material (not hash) in keychain
- PIN can be used as fallback if biometrics fail

## Security Controls

### 1. Secure Communications
- All API calls use HTTPS/TLS 1.3
- Certificate pinning for production
- HSTS headers

### 2. Session Management
- Short-lived access tokens (15 minutes)
- Rotating refresh tokens (new token on each refresh)
- Token revocation on logout/compromise

### 3. Device Attestation
- iOS: Apple DeviceCheck API
- Android: Google Play Integrity API
- Verify device is not jailbroken/rooted
- Track device trust status

### 4. Continuous Monitoring
- Log all authentication attempts
- Track device changes
- Behavioral analysis for anomalies
- Alert on suspicious activity

## Migration Strategy

### Phase 1: Backend Infrastructure
1. Add token validation endpoint
2. Add FIDO/Passkey endpoints
3. Add device attestation
4. Update token generation (shorter expiry)

### Phase 2: iOS Core Changes
1. Remove password/PIN storage
2. Implement encrypted refresh token storage
3. Implement new startup flow
4. Update PIN to only decrypt tokens

### Phase 3: FIDO/Passkey
1. Implement PasskeyManager
2. Add Secure Enclave key generation
3. Integrate with backend
4. Update UI for Passkey setup

### Phase 4: Device Attestation
1. Implement DeviceCheck
2. Integrate with backend
3. Add trust status checks
4. Update authentication flow

### Phase 5: Monitoring & Analytics
1. Add authentication logging
2. Implement behavioral analysis
3. Add anomaly detection
4. Create monitoring dashboard

## Testing Strategy

1. **Unit Tests**: Token validation, encryption/decryption
2. **Integration Tests**: Backend token flow, FIDO operations
3. **E2E Tests**: Complete authentication flow
4. **Security Tests**: Token leakage, replay attacks, device attestation bypass

## Rollout Plan

1. **Development**: Implement all components
2. **Staging**: Test with real devices
3. **Beta**: Limited user rollout
4. **Production**: Gradual rollout with feature flags

