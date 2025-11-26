# Cache Stale Data Fix

## Problem
After deleting all accounts, trying to create a new account with email "s@yahoo.com" was failing with "user already exists" error, even though the user was deleted from the database.

## Root Cause
1. **Stale Cache**: The `findByEmail` method uses `@Cacheable`, which caches the result. After a user is deleted, the cache might still contain the deleted user's data.
2. **Cache Eviction**: While `delete` method has `@CacheEvict(value = "users", allEntries = true)`, if the cache was populated before deletion or if there are multiple cache instances, stale data can persist.
3. **GSI Eventual Consistency**: DynamoDB Global Secondary Indexes (GSI) have eventual consistency, so a query might return stale data even after deletion.

## Solution
1. **Added `findByEmailBypassCache` method**: This method queries DynamoDB directly without using the cache, ensuring fresh data.
2. **Updated registration logic**: Changed `UserService.createUserSecure` to use `findByEmailBypassCache` instead of `findByEmail` for registration checks.
3. **Created deletion script**: Added `scripts/delete-user-by-email.sh` to help manually delete users by email.

## Changes Made

### 1. UserRepository.java
- Added `findByEmailBypassCache()` method that queries DynamoDB directly without cache
- This method is identical to `findByEmail()` but without the `@Cacheable` annotation

### 2. UserService.java
- Updated `createUserSecure()` to use `findByEmailBypassCache()` instead of `findByEmail()`
- This ensures registration checks always get fresh data from DynamoDB, avoiding stale cache

### 3. Scripts
- Created `scripts/delete-user-by-email.sh` to help delete users by email from LocalStack DynamoDB

## Testing
1. Delete a user (or all users)
2. Wait a few seconds for GSI to update (eventual consistency)
3. Restart backend to clear cache: `docker-compose restart backend`
4. Try to register with the same email: Should succeed if user was deleted, fail if user still exists

## Status
✅ Fixed - Registration now uses cache-bypass method to avoid stale cache issues
✅ Added - Script to delete users by email for testing

## Notes
- GSI eventual consistency: After deleting a user, wait a few seconds before trying to register with the same email
- Cache clearing: Restarting the backend clears the in-memory cache
- For production: Consider using DynamoDB transactions or conditional writes on email to ensure uniqueness

