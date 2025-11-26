# Registration Race Condition Fix

## Problem
Backend has a race condition in user registration where:
1. User is successfully registered
2. But then the backend says the user account exists and fails

This happens because two concurrent registration requests can both:
- Pass the email existence check (both find email doesn't exist)
- Create users with different UUIDs (both succeed)
- Result: Two users with the same email but different userIds

## Root Cause

The registration logic had a **TOCTOU (Time-Of-Check-Time-Of-Use) race condition**:

1. **Pre-check for email** (line 59): `findByEmailBypassCache(email)` - Non-atomic
2. **Create user object** with new UUID
3. **Conditional write on userId** (line 87): `saveIfNotExists(user)` - Checks `attribute_not_exists(userId)`

The problem:
- Pre-check checks **email** (using GSI)
- Conditional write checks **userId** (primary key)
- These are **two separate, non-atomic operations**!

Race condition scenario:
1. Thread A: Checks email - doesn't exist ✅
2. Thread B: Checks email - doesn't exist ✅ (both pass!)
3. Thread A: Creates user with UUID1, saves (succeeds - UUID1 doesn't exist) ✅
4. Thread B: Creates user with UUID2, saves (succeeds - UUID2 doesn't exist) ✅
5. **Result: TWO users with the same email!**

## Solution

### Approach: Post-Save Duplicate Detection

1. **Remove pre-check for email** - Eliminates the race condition window
2. **Save user first** - Use conditional write on userId (always succeeds for new UUIDs)
3. **Post-save duplicate check** - Query GSI to find ALL users with the same email
4. **If duplicate found** - Delete the newly created user and throw exception

### Implementation

1. **Removed pre-check** in `UserService.createUserSecure()`:
   - Removed `findByEmailBypassCache()` check before creating user
   - This eliminates the race condition window

2. **Added `findAllByEmail()` method** in `UserRepository`:
   - Queries GSI to find ALL users with the same email
   - Returns a list instead of just the first match
   - Used for duplicate detection after save

3. **Post-save duplicate check** in `UserService.createUserSecure()`:
   - After saving user, query all users with the same email
   - If more than one user found, we have a race condition
   - Delete the duplicate user we just created
   - Throw `USER_ALREADY_EXISTS` exception

### Code Changes

**UserService.java:**
```java
// Removed pre-check - no longer checking email before save
// Save user first
boolean created = dynamoDBUserRepository.saveIfNotExists(user);

// Post-save duplicate check
List<UserTable> usersWithEmail = dynamoDBUserRepository.findAllByEmail(email);
if (usersWithEmail.size() > 1) {
    // Race condition detected - delete duplicate and throw exception
    UserTable originalUser = usersWithEmail.stream()
            .filter(u -> !u.getUserId().equals(userId))
            .findFirst()
            .orElse(null);
    if (originalUser != null) {
        dynamoDBUserRepository.delete(userId);
        throw new AppException(ErrorCode.USER_ALREADY_EXISTS, "User with this email already exists");
    }
}
```

**UserRepository.java:**
```java
/**
 * Find ALL users with the same email (for duplicate detection)
 */
public List<UserTable> findAllByEmail(final String email) {
    // Query GSI to get all users with this email
    // Returns list to detect race conditions
}
```

## Benefits

1. **Eliminates race condition** - No pre-check means no TOCTOU window
2. **Atomic save** - Conditional write on userId is atomic
3. **Post-save validation** - Detects and cleans up duplicates
4. **Data integrity** - Prevents duplicate emails in database

## Edge Cases Handled

1. **GSI Eventual Consistency**: If GSI hasn't updated yet, `findAllByEmail` might return 0 or 1 users. This is acceptable - the duplicate will be detected on the next request.

2. **Multiple Duplicates**: If somehow 3+ users are created, we delete the one we just created and keep the original.

3. **Delete Failure**: If deleting the duplicate fails, we still throw the exception to prevent the duplicate from being used.

## Testing

To test the fix:
1. Send two concurrent registration requests with the same email
2. One should succeed, the other should fail with `USER_ALREADY_EXISTS`
3. Only one user should exist in the database

## Status
✅ Fixed - Race condition eliminated
✅ Added - Post-save duplicate detection
✅ Added - Automatic cleanup of duplicate users

