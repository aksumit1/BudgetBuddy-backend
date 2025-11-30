# Zero Trust Authentication Implementation Plan

## Phase 1: Token Management (Current Sprint)

### Backend Changes
1. ✅ Update token expiry: 15 min access, 30 day refresh
2. ✅ Add `/api/auth/token/validate` endpoint
3. ✅ Implement token rotation (invalidate old refresh token)
4. ✅ Add refresh token tracking in database

### iOS Changes
1. ✅ Update token expiry handling
2. ✅ Encrypt refresh token in keychain
3. ✅ Implement startup flow: keychain check → local unlock → backend verify
4. ✅ Remove password storage (keep only for initial login)

## Phase 2: PIN Refactoring (Next Sprint)

### iOS Changes
1. Change PIN to only decrypt refresh token (not authenticate with backend)
2. Remove PIN verification endpoint from backend
3. Update PIN setup flow

## Phase 3: FIDO/Passkey (Future)

### Backend
1. Add FIDO credential storage
2. Add Passkey registration endpoint
3. Add Passkey authentication endpoint

### iOS
1. Implement PasskeyManager with Secure Enclave
2. Add Passkey setup UI
3. Integrate with backend

## Phase 4: Device Attestation (Future)

### Backend
1. Add DeviceCheck/Play Integrity verification
2. Add device attestation storage
3. Add trust status checks

### iOS
1. Implement DeviceCheck integration
2. Send attestation tokens to backend
3. Handle untrusted device scenarios

## Current Focus: Phase 1

Starting with token management improvements.

