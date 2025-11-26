# User Registration Cache Fix

## Problem
After deleting all accounts, trying to create a new account with email "s@yahoo.com" was failing with "user already exists" error, even though the user was deleted from the database.

## Root Cause
1. **Stale Cache**: The `findByEmail` method uses `@Cacheable`, which caches the result. After a user is deleted, the cache might still contain the deleted user's data.
2. **GSI Eventual Consistency**: DynamoDB Global Secondary Indexes (GSI) have eventual consistency, so a query might return stale data even after deletion.
3. **Code Not Rebuilt**: The backend needs to be rebuilt to pick up the new `findByEmailBypassCache` method.

## Solution Implemented

### 1. Added Cache-Bypass Method
- Created `findByEmailBypassCache()` in `UserRepository` that queries DynamoDB directly without using cache
- This ensures registration checks always get fresh data from DynamoDB

### 2. Updated Registration Logic
- Changed `UserService.createUserSecure()` to use `findByEmailBypassCache()` instead of `findByEmail()`
- This prevents stale cache from blocking registration after user deletion

### 3. Created Helper Scripts
- `scripts/delete-user-by-email.sh` - Delete a specific user by email
- `scripts/clear-all-users.sh` - Clear all users from DynamoDB

## How to Fix the Issue

### Option 1: Restart LocalStack (Clears All Data)
```bash
docker-compose restart localstack
docker-compose restart backend
# Wait 10-15 seconds for services to start
# Then try registering again
```

### Option 2: Clear All Users
```bash
./scripts/clear-all-users.sh
# Wait 10 seconds for GSI to update
# Then try registering again
```

### Option 3: Delete Specific User
```bash
./scripts/delete-user-by-email.sh s@yahoo.com
docker-compose restart backend
# Wait 10 seconds for GSI to update
# Then try registering again
```

### Option 4: Rebuild Backend (Required for Code Changes)
```bash
docker-compose build backend
docker-compose up -d backend
# Wait 20 seconds for backend to start
# Then try registering again
```

## Important Notes

1. **GSI Eventual Consistency**: After deleting a user, wait 5-10 seconds before trying to register with the same email. DynamoDB GSI has eventual consistency.

2. **Cache Clearing**: Restarting the backend clears the in-memory cache. The new code uses `findByEmailBypassCache` which doesn't use cache at all.

3. **Code Changes**: The fix requires rebuilding the backend. If the build fails, you can:
   - Use the scripts to clear data
   - Restart LocalStack to clear everything
   - Try registering with a different email address

## Status
✅ Code fix implemented - Uses cache-bypass method for registration checks
✅ Scripts created - Helper scripts to clear users
⚠️ Backend needs rebuild - Code changes require `docker-compose build backend`

## Testing
After applying the fix:
1. Clear all users: `./scripts/clear-all-users.sh`
2. Wait 10 seconds
3. Register with email: Should succeed with 201 status
4. Register same email again: Should fail with 400 status and USER_ALREADY_EXISTS

