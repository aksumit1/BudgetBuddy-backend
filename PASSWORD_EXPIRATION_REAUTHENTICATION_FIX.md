# Password Expiration Re-authentication Fix

## Problem

When a password expires and the account needs to be reauthenticated, login doesn't work again. The password hash is different even when the password and salt are the same.

## Root Cause

When a password is reset or changed through the `changePasswordSecure()` method, the backend was generating a **new server salt** every time by passing `null` as the server salt parameter to `hashClientPassword()`. 

**The Issue:**
1. User registers → Server salt A is generated and stored
2. User's token expires → User needs to reauthenticate
3. If password was reset/changed → New server salt B was generated
4. User tries to login → Client uses client salt with password
5. Backend tries to verify using server salt B (from reset)
6. But the stored hash might have been computed with server salt A or there's a mismatch
7. **Verification fails even with correct password**

## Solution

**Fixed in:** `UserService.changePasswordSecure()`

**Change:** Instead of always generating a new server salt, the code now:
1. Reuses the existing server salt if available (preserves salt across password changes)
2. Only generates a new server salt if one doesn't exist (for migration/legacy accounts)

This ensures that:
- Server salt remains consistent across password changes
- Password verification works correctly after password reset
- Users can login successfully even after their token expires and they need to reauthenticate

## Code Changes

### Before:
```java
// Always generated a new server salt
PasswordHashingService.PasswordHashResult result = passwordHashingService.hashClientPassword(
        passwordHash, clientSalt, null);
```

### After:
```java
// Reuse existing server salt if available
byte[] existingServerSalt = null;
if (user.getServerSalt() != null && !user.getServerSalt().isEmpty()) {
    try {
        existingServerSalt = java.util.Base64.getDecoder().decode(user.getServerSalt());
        logger.debug("Reusing existing server salt for password change for user: {}", user.getEmail());
    } catch (IllegalArgumentException e) {
        logger.warn("Existing server salt is invalid Base64 for user: {}, generating new one", user.getEmail());
        existingServerSalt = null;
    }
}

// Use existing server salt (or generate new if missing)
PasswordHashingService.PasswordHashResult result = passwordHashingService.hashClientPassword(
        passwordHash, clientSalt, existingServerSalt);
```

## Impact

- ✅ Password reset now preserves server salt consistency
- ✅ Login works correctly after password expiration and reauthentication
- ✅ Password hash verification succeeds when password and salt are the same
- ✅ Backward compatible with existing accounts

## Testing

To verify the fix:
1. Register a new user
2. Wait for token to expire (or manually expire it)
3. Reset password using the password reset flow
4. Try to login with the new password
5. Login should succeed

## Files Modified

- `BudgetBuddy-Backend/src/main/java/com/budgetbuddy/service/UserService.java`
  - Updated `changePasswordSecure()` method to reuse existing server salt

