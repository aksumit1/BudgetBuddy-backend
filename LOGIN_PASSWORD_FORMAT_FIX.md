# Login Password Format Fix

## Problem
Backend was throwing `UnrecognizedPropertyException` when iOS app tried to login:
```
Unrecognized field "password" (class com.budgetbuddy.dto.AuthRequest), 
not marked as ignorable (3 known properties: "passwordHash", "email", "salt"])
```

## Root Cause
The iOS app's `login()` method was sending plaintext `password` instead of the secure format with `password_hash` and `salt` that the backend expects.

**Request Body (Before Fix):**
```json
{
  "email": "user@example.com",
  "password": "plaintext_password"  // ❌ Wrong format
}
```

**Expected Request Body:**
```json
{
  "email": "user@example.com",
  "password_hash": "base64_encoded_pbkdf2_hash",
  "salt": "base64_encoded_salt"
}
```

## Solution

### 1. Store Client Salt After Registration
- Added `saveClientSalt()` method to `SecurityService` to store the client salt securely in Keychain
- Salt is stored after successful registration
- Key format: `clientSalt_{email}`

### 2. Retrieve Client Salt During Login
- Added `loadClientSalt()` method to `SecurityService` to retrieve the stored salt
- Login method now retrieves the same salt used during registration
- Uses that salt to hash the password before sending to backend

### 3. Updated Login Method
- Changed from sending plaintext `password` to sending `password_hash` and `salt`
- Uses the same client-side hashing as registration
- Matches the secure format expected by backend

### 4. Clear Salt on Logout
- Added `clearClientSalt()` method to `SecurityService`
- Logout now clears the stored client salt for security

## Code Changes

**SecurityService.swift:**
```swift
func saveClientSalt(_ salt: String, forEmail email: String) throws
func loadClientSalt(forEmail email: String) throws -> String?
func clearClientSalt(forEmail email: String)
```

**AuthService.swift - Registration:**
```swift
// After successful registration, store the client salt
try security.saveClientSalt(salt.base64EncodedString(), forEmail: sanitizedEmail)
```

**AuthService.swift - Login:**
```swift
// Retrieve stored client salt
guard let storedSaltBase64 = try security.loadClientSalt(forEmail: sanitizedEmail),
      let storedSaltData = Data(base64Encoded: storedSaltBase64) else {
    throw AuthError.securityError(.keychainError(OSStatus(errSecItemNotFound)))
}

let salt = storedSaltData
let passwordHash = hashPassword(sanitizedPassword, salt: salt)

// Send secure format
struct LoginBody: Codable {
    let email: String
    let passwordHash: String
    let salt: String
    
    enum CodingKeys: String, CodingKey {
        case email
        case passwordHash = "password_hash"
        case salt
    }
}
```

## Security Impact
✅ **CRITICAL** - Login now uses secure client-side password hashing
✅ Passwords are never sent in plaintext
✅ Same security level as registration

## Testing
1. Register a new user - salt is stored
2. Login with same credentials - uses stored salt to hash password
3. Backend receives `password_hash` and `salt` (not plaintext `password`)
4. Backend verifies password correctly

## Status
✅ Fixed - Login now sends secure format
✅ Backend exception resolved

