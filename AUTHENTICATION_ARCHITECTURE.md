# Authentication Architecture - Complete Documentation

## Overview

BudgetBuddy implements a **Zero Trust authentication architecture** with Multi-Factor Authentication (MFA), FIDO2/WebAuthn passkeys, device attestation, and comprehensive compliance (PCI-DSS, SOC2, FINRA, HIPAA, GDPR).

## Architecture Principles

### Zero Trust
- **Never Trust, Always Verify**: Every request is verified, no implicit trust
- **Least Privilege**: Users have minimum necessary access
- **Continuous Verification**: Authentication is verified continuously, not just at login
- **Assume Breach**: System designed to minimize impact of breaches

### Security Layers
1. **Device Attestation**: Verify device integrity (DeviceCheck/Play Integrity)
2. **Identity Verification**: Multi-factor authentication
3. **Behavioral Analysis**: Continuous monitoring of user behavior
4. **Risk Scoring**: Dynamic risk assessment for each request
5. **Access Control**: Role-based access control (RBAC)

## Authentication Flow

### 1. Registration Flow
```
User → iOS App → Hash Password (Client) → Backend
                                      ↓
                              Server-side Hash
                                      ↓
                              Store in DynamoDB
                                      ↓
                              Generate Tokens
                                      ↓
                              Return to iOS App
                                      ↓
                              Store Refresh Token (Encrypted in Keychain)
```

### 2. Login Flow
```
User → iOS App → Hash Password (Client) → Backend
                                      ↓
                              Verify Password Hash
                                      ↓
                              Check MFA Status
                                      ↓
                              If MFA Enabled: Require MFA
                                      ↓
                              Generate Tokens
                                      ↓
                              Return to iOS App
                                      ↓
                              Store Refresh Token (Encrypted in Keychain)
```

### 3. Zero Trust Startup Flow
```
App Launch → Check Keychain for Refresh Token
                    ↓
            If Found: Decrypt with PIN/Biometric
                    ↓
            Validate with Backend
                    ↓
            If Valid: Issue New Tokens
                    ↓
            Grant Access
```

### 4. MFA Flow
```
Login → Check MFA Status
            ↓
    If Enabled: Request MFA
            ↓
    User Provides: TOTP/SMS/Email/Backup Code
            ↓
    Verify MFA
            ↓
    If Valid: Complete Login
```

### 5. FIDO2/Passkey Flow
```
Registration:
    User → Request Registration Challenge
                ↓
        Generate Challenge
                ↓
        User Creates Passkey (Secure Enclave)
                ↓
        Send Registration Data
                ↓
        Verify and Store Credential

Authentication:
    User → Request Authentication Challenge
                ↓
        Generate Challenge
                ↓
        User Authenticates with Passkey
                ↓
        Send Authentication Data
                ↓
        Verify and Grant Access
```

## Token Management

### Access Tokens
- **Lifetime**: 15 minutes
- **Purpose**: Short-lived tokens for API access
- **Storage**: Not stored, used immediately
- **Rotation**: Automatic on refresh

### Refresh Tokens
- **Lifetime**: 30 days
- **Purpose**: Long-lived tokens for token refresh
- **Storage**: Encrypted in iOS Keychain (protected by PIN/Biometric)
- **Rotation**: Rotated on each refresh (Zero Trust)

### Token Refresh Flow
```
API Request with Expired Access Token
            ↓
    Send Refresh Token to Backend
            ↓
    Validate Refresh Token
            ↓
    Generate New Access Token + New Refresh Token
            ↓
    Return New Tokens
            ↓
    Update Refresh Token in Keychain
```

## MFA Implementation

### Supported Methods
1. **TOTP** (Time-based One-Time Password)
   - 6-digit codes, 30-second windows
   - Compatible with Google Authenticator, Authy, etc.
   - QR code for setup

2. **SMS OTP**
   - 6-digit codes, 5-minute expiration
   - Sent via AWS SNS (in production)

3. **Email OTP**
   - 6-digit codes, 5-minute expiration
   - Sent via AWS SES (in production)

4. **Backup Codes**
   - 8-character alphanumeric codes
   - 10 codes generated
   - Single-use, removed after use

### MFA Enforcement
- **Sensitive Operations**: Password change, MFA setup/disable, account deletion
- **High-Risk Activities**: Large transactions, account modifications
- **New Device Login**: First login from new device
- **Unusual Activity**: Detected by behavioral analysis

## FIDO2/WebAuthn Implementation

### Passkey Registration
1. User requests registration challenge
2. Backend generates challenge and returns options
3. iOS app creates passkey in Secure Enclave
4. User authenticates with biometric
5. Passkey credential created and stored
6. Registration data sent to backend
7. Backend verifies and stores credential

### Passkey Authentication
1. User requests authentication challenge
2. Backend generates challenge
3. iOS app prompts for passkey
4. User authenticates with biometric
5. Authentication data sent to backend
6. Backend verifies and grants access

### Security Features
- **Secure Enclave**: Passkeys stored in hardware security module
- **Biometric Protection**: Passkeys protected by Face ID/Touch ID
- **No Password**: Passkeys eliminate password-based attacks
- **Phishing Resistant**: Domain-bound credentials

## Device Attestation

### iOS (DeviceCheck)
- **Token Generation**: iOS app generates DeviceCheck token
- **Token Verification**: Backend verifies token with Apple
- **Device Trust**: Trusted devices can skip additional verification

### Android (Play Integrity)
- **Token Generation**: Android app generates Play Integrity token
- **Token Verification**: Backend verifies token with Google
- **Device Trust**: Trusted devices can skip additional verification

### Device Trust Levels
- **HIGH**: Verified device, recent attestation, no anomalies
- **MEDIUM**: Verified device, older attestation, minor anomalies
- **LOW**: Unverified device, suspicious activity
- **UNTRUSTED**: Compromised device, blocked

## Behavioral Analysis

### Risk Factors
1. **Time Anomaly**: Activity at unusual times
2. **Location Anomaly**: Activity from unusual locations
3. **Frequency Anomaly**: Unusual activity frequency
4. **Resource Sensitivity**: Accessing sensitive resources
5. **Action Sensitivity**: Performing sensitive actions
6. **Device Anomaly**: Using unknown/untrusted device
7. **Pattern Deviation**: Deviating from normal behavior patterns

### Risk Scoring
- **0-40**: LOW RISK - Normal access
- **40-70**: MEDIUM RISK - Additional verification may be required
- **70-100**: HIGH RISK - Blocked or requires additional verification

### Anomaly Detection
- **Unusual Frequency**: Activity frequency > 3x normal
- **Unusual Time Pattern**: >50% of activities at unusual times
- **Unusual Resource Access**: Accessing >2x normal number of resources
- **Pattern Deviation**: Activity type/resource/action not in normal pattern

## Compliance Implementation

### PCI-DSS
- **Req. 3**: Cardholder data encrypted (AES-256-GCM)
- **Req. 4**: Transmission encrypted (TLS 1.3)
- **Req. 8**: Multi-factor authentication
- **Req. 10**: Comprehensive audit logging

### SOC2
- **CC6.1**: Logical access controls (Zero Trust, RBAC)
- **CC6.2**: Authentication and authorization (MFA, FIDO2)
- **CC7.2**: Monitoring and logging (Behavioral analysis, audit logs)
- **CC8.1**: Change management (Version control, CI/CD)

### FINRA
- **Rule 4511**: Record keeping (7-year retention)
- **Rule 3110**: Supervision (Supervisory logging)
- **Rule 4530**: Reporting (Suspicious activity reporting)
- **Rule 2210**: Communications (Communication surveillance)

### HIPAA
- **164.308**: Administrative safeguards (Access management)
- **164.312**: Technical safeguards (Encryption, audit controls)
- **164.400-414**: Breach notification (Automated workflow)

### GDPR
- **Art. 15**: Right to access (Data export)
- **Art. 17**: Right to erasure (Data deletion)
- **Art. 20**: Right to data portability (JSON export)
- **Art. 33**: Breach notification (72-hour notification)
- **Art. 7**: Consent management (Consent recording/withdrawal)

## API Endpoints

### Authentication
- `POST /api/auth/register` - Register new user
- `POST /api/auth/login` - Login with password
- `POST /api/auth/refresh` - Refresh tokens
- `POST /api/auth/token/validate` - Validate refresh token (Zero Trust)
- `POST /api/auth/forgot-password` - Request password reset
- `POST /api/auth/verify-reset-code` - Verify reset code
- `POST /api/auth/reset-password` - Reset password
- `POST /api/auth/change-password` - Change password (authenticated)

### MFA
- `POST /api/mfa/totp/setup` - Setup TOTP
- `POST /api/mfa/totp/verify` - Verify TOTP (setup)
- `POST /api/mfa/totp/authenticate` - Authenticate with TOTP
- `DELETE /api/mfa/totp` - Remove TOTP
- `POST /api/mfa/backup-codes/generate` - Generate backup codes
- `POST /api/mfa/backup-codes/verify` - Verify backup code
- `POST /api/mfa/sms/request` - Request SMS OTP
- `POST /api/mfa/sms/verify` - Verify SMS OTP
- `POST /api/mfa/email/request` - Request Email OTP
- `POST /api/mfa/email/verify` - Verify Email OTP
- `GET /api/mfa/status` - Get MFA status
- `DELETE /api/mfa` - Disable MFA

### FIDO2
- `POST /api/fido2/register/challenge` - Generate registration challenge
- `POST /api/fido2/register/verify` - Verify registration
- `POST /api/fido2/authenticate/challenge` - Generate authentication challenge
- `POST /api/fido2/authenticate/verify` - Verify authentication
- `GET /api/fido2/passkeys` - List passkeys
- `DELETE /api/fido2/passkeys/{credentialId}` - Delete passkey

## Security Best Practices

### For Developers
1. **Never store passwords**: Only password hashes
2. **Always use HTTPS**: TLS 1.3 required
3. **Validate all inputs**: Server-side validation
4. **Log security events**: Comprehensive audit logging
5. **Use parameterized queries**: Prevent SQL injection
6. **Implement rate limiting**: Prevent brute force attacks
7. **Use secure random**: For tokens, challenges, salts
8. **Encrypt sensitive data**: At rest and in transit

### For Users
1. **Enable MFA**: Use TOTP or passkeys
2. **Use strong passwords**: 12+ characters, mixed case, numbers, symbols
3. **Save backup codes**: Store in secure location
4. **Use passkeys**: More secure than passwords
5. **Review account activity**: Check for suspicious activity
6. **Keep app updated**: Security patches included

## Troubleshooting

### Authentication Failures
- **Check token expiration**: Access tokens expire in 15 minutes
- **Verify refresh token**: Refresh token may be expired or invalid
- **Check MFA status**: MFA may be required
- **Review device attestation**: Device may not be trusted

### MFA Issues
- **TOTP not working**: Check device time sync
- **Backup codes not working**: Codes are single-use
- **SMS/Email OTP not received**: Check spam folder, verify phone/email

### FIDO2 Issues
- **Passkey not working**: Check Secure Enclave availability
- **Registration fails**: Verify challenge not expired
- **Authentication fails**: Verify credential still exists

## Future Enhancements

1. **Adaptive Authentication**: Dynamic MFA requirements based on risk
2. **Biometric Authentication**: Face ID/Touch ID for sensitive operations
3. **Location-Based Security**: Geo-fencing for account access
4. **Machine Learning**: Enhanced behavioral analysis
5. **Quantum-Resistant Cryptography**: Prepare for post-quantum security

## References

- [Zero Trust Architecture](https://www.nist.gov/publications/zero-trust-architecture)
- [WebAuthn Specification](https://www.w3.org/TR/webauthn/)
- [FIDO2 Specification](https://fidoalliance.org/specifications/)
- [NIST 800-63B](https://pages.nist.gov/800-63-3/sp800-63b.html)
- [PCI-DSS Requirements](https://www.pcisecuritystandards.org/)
- [HIPAA Requirements](https://www.hhs.gov/hipaa/index.html)
- [GDPR Requirements](https://gdpr.eu/)

