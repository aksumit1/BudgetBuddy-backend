# DynamoDB Cost Optimization Guide

## Overview

This guide documents DynamoDB cost optimizations and best practices to minimize read/write costs and improve performance.

---

## ✅ Current Optimizations Applied

### 1. **On-Demand Billing**
- ✅ **Billing mode**: `PAY_PER_REQUEST` (on-demand)
- ✅ **Benefits**: No provisioned capacity costs, pay only for actual usage
- ✅ **Free tier**: 25 GB storage, 25 read/write units per second

### 2. **Efficient Indexing**
- ✅ **GSI Usage**: Only necessary Global Secondary Indexes created
- ✅ **Email Index**: Used for user lookup (cost-effective alternative to scan)
- ✅ **Date Range Indexes**: Used for transaction queries (efficient range queries)

### 3. **TTL (Time-to-Live)**
- ✅ **RateLimit Table**: TTL enabled (auto-expire old entries)
- ✅ **DDoSProtection Table**: TTL enabled (auto-expire old entries)
- ✅ **DeviceAttestation Table**: TTL enabled (auto-expire old entries)
- ✅ **Benefits**: Automatic cleanup reduces storage costs

---

## 🔧 Optimizations to Implement

### 1. **Eliminate Read-Before-Write Patterns**

**Current Issues:**
- `UserService.updateLastLogin()`: Reads user, then saves (2 operations)
- `UserService.updatePlaidAccessToken()`: Reads user, then saves (2 operations)
- `GoalService.updateGoalProgress()`: Reads goal, then saves (2 operations)
- `UserService.updateUser()`: Uses `PutItem` instead of `UpdateItem`

**Optimization:**
- Use `UpdateItem` with `SET` expressions for single-field updates
- Use `UpdateItem` with `ADD` expressions for numeric increments
- Use conditional writes to prevent overwrites

**Cost Savings:** ~50% reduction in write operations for updates

### 2. **Use Conditional Writes**

**Current Issue:**
- No conditional writes to prevent accidental overwrites
- No optimistic locking

**Optimization:**
- Add `ConditionExpression` to prevent overwrites
- Use version numbers or timestamps for optimistic locking

**Benefits:**
- Prevents data loss from concurrent updates
- Reduces unnecessary write operations

### 3. **Use Projection Expressions**

**Current Issue:**
- Full item retrieval when only specific attributes needed

**Optimization:**
- Use `ProjectionExpression` to retrieve only needed attributes
- Reduces data transfer costs

**Cost Savings:** ~30-50% reduction in read costs for partial retrievals

### 4. **Batch Operations**

**Current Issue:**
- Individual `putItem` calls for multiple items

**Optimization:**
- Use `BatchWriteItem` for bulk operations
- Batch up to 25 items per request

**Cost Savings:** ~25% reduction in write costs for bulk operations

### 5. **Use UpdateItem Instead of PutItem**

**Current Issue:**
- `save()` method uses `PutItem` which replaces entire item
- `UpdateItem` is more efficient for partial updates

**Optimization:**
- Create `update()` methods that use `UpdateItem`
- Use `UpdateItem` for single-field updates

**Cost Savings:** ~40% reduction in write costs for updates

---

## 📋 Implementation Plan

### Phase 1: Update Repository Methods

1. **Add UpdateItem Methods**
   - `UserRepository.updateLastLogin(userId, timestamp)`
   - `UserRepository.updateField(userId, field, value)`
   - `GoalRepository.incrementProgress(goalId, amount)`

2. **Add Conditional Write Methods**
   - `UserRepository.saveIfNotExists(user)`
   - `TransactionRepository.saveIfNotExists(transaction)`

3. **Add Projection Expression Support**
   - `UserRepository.findByIdWithProjection(userId, attributes)`
   - `TransactionRepository.findByUserIdWithProjection(userId, attributes)`

### Phase 2: Update Service Methods

1. **Replace Read-Before-Write Patterns**
   - `UserService.updateLastLogin()` → Use `UpdateItem`
   - `UserService.updatePlaidAccessToken()` → Use `UpdateItem`
   - `GoalService.updateGoalProgress()` → Use `UpdateItem` with `ADD`

2. **Use Conditional Writes**
   - `UserService.createUser()` → Use conditional write
   - `TransactionService.saveTransaction()` → Use conditional write for deduplication

### Phase 3: Batch Operations

1. **Add Batch Write Methods**
   - `TransactionRepository.batchSave(transactions)`
   - `AccountRepository.batchSave(accounts)`

2. **Use Batch Operations in Services**
   - `PlaidSyncService.syncTransactions()` → Use batch writes

---

## 💰 Estimated Cost Savings

### Current Costs (Estimated):
- Read operations: ~$0.25 per million (on-demand)
- Write operations: ~$1.25 per million (on-demand)
- Storage: $0.25/GB (after free tier)

### After Optimizations:
- **Read operations**: ~30-50% reduction (projection expressions, fewer reads)
- **Write operations**: ~40-50% reduction (UpdateItem, conditional writes, batch operations)
- **Storage**: Minimal impact (TTL already enabled)

### Monthly Savings Estimate:
- **Small application** (1M reads, 500K writes/month): ~$0.50-1.00/month
- **Medium application** (10M reads, 5M writes/month): ~$5-10/month
- **Large application** (100M reads, 50M writes/month): ~$50-100/month

---

## 🎯 Best Practices

### 1. **Always Use UpdateItem for Updates**
```java
// ❌ Bad: Read then write
UserTable user = repository.findById(userId);
user.setLastLogin(Instant.now());
repository.save(user);

// ✅ Good: Direct update
repository.updateField(userId, "lastLogin", Instant.now());
```

### 2. **Use Conditional Writes for Safety**
```java
// ✅ Good: Prevent overwrites
repository.saveIfNotExists(user, "attribute_not_exists(userId)");
```

### 3. **Use Projection Expressions**
```java
// ❌ Bad: Retrieve full item
UserTable user = repository.findById(userId);
String email = user.getEmail();

// ✅ Good: Retrieve only needed attribute
String email = repository.findByIdWithProjection(userId, "email");
```

### 4. **Batch Operations**
```java
// ❌ Bad: Individual writes
for (Transaction t : transactions) {
    repository.save(t);
}

// ✅ Good: Batch write
repository.batchSave(transactions);
```

### 5. **Use TTL for Temporary Data**
```java
// ✅ Good: Auto-expire old data
item.setTtl(Instant.now().plusSeconds(3600)); // 1 hour TTL
```

---

## 📊 Monitoring

### Key Metrics to Monitor:
1. **Read Capacity Units (RCU)**: Track read operations
2. **Write Capacity Units (WCU)**: Track write operations
3. **Throttling Events**: Monitor for capacity issues
4. **Item Count**: Track table size
5. **Storage Size**: Monitor storage costs

### CloudWatch Alarms:
- High read/write utilization
- Throttling events
- Storage size approaching limits

---

## ✅ Summary

**Current Status:**
- ✅ On-demand billing enabled
- ✅ TTL enabled for temporary tables
- ✅ Efficient GSI usage
- ⚠️ Read-before-write patterns exist (to be optimized)
- ⚠️ No conditional writes (to be implemented)
- ⚠️ No projection expressions (to be implemented)
- ⚠️ No batch operations (to be implemented)

**Next Steps:**
1. Implement UpdateItem methods in repositories
2. Replace read-before-write patterns in services
3. Add conditional write support
4. Add projection expression support
5. Implement batch operations

**Expected Savings:** 30-50% reduction in DynamoDB costs

