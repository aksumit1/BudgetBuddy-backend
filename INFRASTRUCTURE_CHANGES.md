# Infrastructure Changes - Local/Staging/Production

## Overview

This document summarizes all infrastructure changes for local, staging, and production environments to support the complete authentication overhaul.

## ✅ Configuration Files Created

### 1. `application-staging.yml`
- **Purpose**: Staging environment configuration
- **Features**:
  - Production-like settings with relaxed limits for testing
  - MFA, FIDO2, device attestation, behavioral analysis configuration
  - Compliance features enabled
  - Staging-specific endpoints and origins

### 2. `application-production.yml`
- **Purpose**: Production environment configuration
- **Features**:
  - Strict security settings
  - TLS 1.3 only
  - Production endpoints and origins
  - All compliance features enabled
  - Optimized performance settings

## 🔄 Docker Compose Updates

### Environment Variables Added

#### MFA Configuration
```yaml
MFA_TOTP_ISSUER: BudgetBuddy (Local)
MFA_BACKUP_CODES_COUNT: 10
MFA_BACKUP_CODES_LENGTH: 8
MFA_OTP_EXPIRATION_SECONDS: 300
```

#### FIDO2 Configuration
```yaml
FIDO2_RP_ID: localhost
FIDO2_RP_NAME: BudgetBuddy (Local)
FIDO2_ORIGIN: http://localhost:8080
FIDO2_CHALLENGE_EXPIRATION_SECONDS: 300
```

#### Device Attestation Configuration
```yaml
DEVICE_ATTESTATION_ENABLED: true
DEVICE_ATTESTATION_DEVICECHECK_ENABLED: true
DEVICE_ATTESTATION_PLAY_INTEGRITY_ENABLED: true
DEVICE_ATTESTATION_TRUST_WINDOW_HOURS: 24
```

#### Behavioral Analysis Configuration
```yaml
BEHAVIORAL_ANALYSIS_ENABLED: true
BEHAVIORAL_ANALYSIS_HISTORY_SIZE: 100
BEHAVIORAL_ANALYSIS_WINDOW_SECONDS: 3600
BEHAVIORAL_ANALYSIS_HIGH_RISK_THRESHOLD: 70.0
BEHAVIORAL_ANALYSIS_MEDIUM_RISK_THRESHOLD: 40.0
```

## 📋 Application Configuration Updates

### `application.yml` (Local/Default)

#### MFA Configuration
```yaml
app:
  mfa:
    totp:
      issuer: ${MFA_TOTP_ISSUER:BudgetBuddy}
    backup-codes:
      count: ${MFA_BACKUP_CODES_COUNT:10}
      length: ${MFA_BACKUP_CODES_LENGTH:8}
    otp:
      expiration-seconds: ${MFA_OTP_EXPIRATION_SECONDS:300}
```

#### FIDO2 Configuration
```yaml
app:
  fido2:
    rp-id: ${FIDO2_RP_ID:budgetbuddy.com}
    rp-name: ${FIDO2_RP_NAME:BudgetBuddy}
    origin: ${FIDO2_ORIGIN:https://budgetbuddy.com}
    challenge:
      expiration-seconds: ${FIDO2_CHALLENGE_EXPIRATION_SECONDS:300}
```

#### Device Attestation Configuration
```yaml
app:
  device-attestation:
    enabled: ${DEVICE_ATTESTATION_ENABLED:true}
    devicecheck-enabled: ${DEVICE_ATTESTATION_DEVICECHECK_ENABLED:true}
    play-integrity-enabled: ${DEVICE_ATTESTATION_PLAY_INTEGRITY_ENABLED:true}
    trust-window-hours: ${DEVICE_ATTESTATION_TRUST_WINDOW_HOURS:24}
```

#### Behavioral Analysis Configuration
```yaml
app:
  behavioral-analysis:
    enabled: ${BEHAVIORAL_ANALYSIS_ENABLED:true}
    activity-history-size: ${BEHAVIORAL_ANALYSIS_HISTORY_SIZE:100}
    anomaly-detection-window-seconds: ${BEHAVIORAL_ANALYSIS_WINDOW_SECONDS:3600}
    high-risk-threshold: ${BEHAVIORAL_ANALYSIS_HIGH_RISK_THRESHOLD:70.0}
    medium-risk-threshold: ${BEHAVIORAL_ANALYSIS_MEDIUM_RISK_THRESHOLD:40.0}
```

## 🚀 Deployment Configuration

### Local Development
- **Profile**: `local`
- **DynamoDB**: LocalStack
- **Redis**: Local Docker container
- **MFA**: Enabled (TOTP, SMS, Email, Backup Codes)
- **FIDO2**: Enabled (localhost origin)
- **Device Attestation**: Enabled (format validation only)
- **Behavioral Analysis**: Enabled (in-memory)

### Staging
- **Profile**: `staging`
- **DynamoDB**: AWS DynamoDB (staging tables)
- **Redis**: AWS ElastiCache (staging)
- **MFA**: Enabled (full implementation)
- **FIDO2**: Enabled (staging.budgetbuddy.com)
- **Device Attestation**: Enabled (DeviceCheck/Play Integrity APIs)
- **Behavioral Analysis**: Enabled (DynamoDB storage)

### Production
- **Profile**: `production`
- **DynamoDB**: AWS DynamoDB (production tables)
- **Redis**: AWS ElastiCache (production)
- **MFA**: Enabled (full implementation)
- **FIDO2**: Enabled (budgetbuddy.com)
- **Device Attestation**: Enabled (DeviceCheck/Play Integrity APIs)
- **Behavioral Analysis**: Enabled (DynamoDB storage, larger history)

## 🔧 Required Infrastructure Setup

### DynamoDB Tables

#### MFA Secrets Table (Future)
- **Table Name**: `BudgetBuddy-MFASecrets`
- **Partition Key**: `userId`
- **Sort Key**: `secretType` (TOTP, SMS, Email)
- **TTL**: `expiresAt`
- **Status**: Currently in-memory, needs DynamoDB migration

#### FIDO2 Credentials Table (Future)
- **Table Name**: `BudgetBuddy-FIDO2Credentials`
- **Partition Key**: `userId`
- **Sort Key**: `credentialId`
- **Attributes**: `publicKey` (encrypted), `counter`, `createdAt`
- **Status**: Currently in-memory, needs DynamoDB migration

#### Behavioral Analysis Table (Future)
- **Table Name**: `BudgetBuddy-BehavioralAnalysis`
- **Partition Key**: `userId`
- **Sort Key**: `timestamp`
- **TTL**: `expiresAt`
- **Status**: Currently in-memory, needs DynamoDB migration

### AWS Services

#### DeviceCheck API (iOS)
- **Service**: Apple DeviceCheck API
- **Configuration**: Requires Apple Developer account
- **Endpoint**: `https://api.development.devicecheck.apple.com` (development)
- **Endpoint**: `https://api.devicecheck.apple.com` (production)
- **Status**: Format validation only (needs API integration)

#### Play Integrity API (Android)
- **Service**: Google Play Integrity API
- **Configuration**: Requires Google Cloud project
- **Endpoint**: `https://playintegrity.googleapis.com`
- **Status**: Format validation only (needs API integration)

#### SMS OTP Delivery
- **Service**: AWS SNS
- **Configuration**: Requires SNS topic and phone number verification
- **Status**: OTP generation works, SMS delivery needs SNS setup

#### Email OTP Delivery
- **Service**: AWS SES
- **Configuration**: Requires SES verified sender email
- **Status**: OTP generation works, email delivery needs SES setup

## 📝 Environment-Specific Notes

### Local
- All services run in Docker Compose
- LocalStack for AWS services
- Redis in Docker container
- Relaxed security for development
- All features enabled for testing

### Staging
- AWS services (DynamoDB, ElastiCache, SNS, SES)
- Staging-specific endpoints
- Production-like configuration
- Testing-friendly limits
- All compliance features enabled

### Production
- AWS services (DynamoDB, ElastiCache, SNS, SES)
- Production endpoints
- Strict security settings
- Production limits
- All compliance features enabled
- TLS 1.3 only
- Certificate pinning enabled

## 🔐 Security Configuration

### TLS Configuration
- **Local**: TLS disabled (HTTP only)
- **Staging**: TLS 1.2 and 1.3
- **Production**: TLS 1.3 only

### CORS Configuration
- **Local**: Allow all origins (`*`)
- **Staging**: Specific staging origins
- **Production**: Specific production origins (must be explicitly set)

### Rate Limiting
- **Local**: Very high limits (10000/min)
- **Staging**: Moderate limits (5000/min)
- **Production**: Strict limits (10000/min, but stricter per-endpoint)

## 📊 Monitoring Configuration

### CloudWatch
- **Local**: Disabled (use LocalStack)
- **Staging**: Enabled (staging namespace)
- **Production**: Enabled (production namespace)

### Health Checks
- **All Environments**: `/actuator/health`
- **Timeout**: 2 seconds (Redis), 10 seconds (DynamoDB)
- **Interval**: 30 seconds (Docker), 10 seconds (ECS)

## 🎯 Next Steps

### Infrastructure Setup Required

1. **DynamoDB Tables**:
   - Create MFA secrets table
   - Create FIDO2 credentials table
   - Create behavioral analysis table

2. **AWS Services**:
   - Configure DeviceCheck API integration
   - Configure Play Integrity API integration
   - Set up SMS OTP delivery (AWS SNS)
   - Set up Email OTP delivery (AWS SES)

3. **Environment Variables**:
   - Set all MFA configuration variables
   - Set all FIDO2 configuration variables
   - Set all device attestation configuration variables
   - Set all behavioral analysis configuration variables

4. **Secrets Management**:
   - Store JWT secret in AWS Secrets Manager
   - Store encryption keys in AWS Secrets Manager
   - Store API keys (Plaid, Stripe) in AWS Secrets Manager

## ✅ Verification Checklist

### Local
- [x] Docker Compose updated with new environment variables
- [x] LocalStack configured for all AWS services
- [x] Redis configured and healthy
- [x] All new endpoints accessible
- [x] MFA endpoints working
- [x] FIDO2 endpoints working

### Staging
- [ ] DynamoDB tables created
- [ ] ElastiCache cluster configured
- [ ] SNS topic created for SMS
- [ ] SES verified sender configured
- [ ] DeviceCheck API configured
- [ ] Play Integrity API configured
- [ ] All environment variables set
- [ ] Secrets stored in AWS Secrets Manager

### Production
- [ ] DynamoDB tables created
- [ ] ElastiCache cluster configured
- [ ] SNS topic created for SMS
- [ ] SES verified sender configured
- [ ] DeviceCheck API configured
- [ ] Play Integrity API configured
- [ ] All environment variables set
- [ ] Secrets stored in AWS Secrets Manager
- [ ] TLS 1.3 certificates configured
- [ ] Certificate pinning configured
- [ ] CORS origins configured

