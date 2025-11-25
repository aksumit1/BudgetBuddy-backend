# Comprehensive AWS Cost Optimization Guide

## Overview

This document outlines all cost optimization opportunities for the BudgetBuddy backend infrastructure, including implemented optimizations and recommendations for further improvements.

---

## ✅ Implemented Optimizations

### 1. **NAT Gateway Optimization**
- ✅ **Status**: Implemented (Single NAT Gateway)
- ✅ **Configuration**: Single NAT Gateway for external APIs only
- ✅ **Details**: 
  - AWS services use VPC Endpoints (no NAT Gateway usage)
  - NAT Gateway only used for external APIs (Plaid, Stripe)
  - Minimizes data transfer costs through NAT Gateway

### 2. **VPC Endpoints Configuration**
- ✅ **Gateway Endpoints** (FREE):
  - DynamoDB
  - S3
- ✅ **Interface Endpoints** (~$7/month each):
  - CloudWatch Logs
  - Secrets Manager
  - ECR (Docker API)
  - ECR API
  - CloudWatch Metrics
  - KMS
- ✅ **Total Interface Endpoint Cost**: ~$35/month

### 3. **CloudWatch Log Retention**
- ✅ **Status**: Implemented
- ✅ **Retention**: 3 days (reduced from 30 days)
- ✅ **Savings**: ~$15-20/month
- ✅ **Locations**:
  - `main-stack.yaml`: ECS log group (3 days)
  - `monitoring.yaml`: ECS log group (3 days)

### 4. **ALB Access Logs Retention**
- ✅ **Status**: Implemented
- ✅ **Retention**: 3 days (reduced from 90 days)
- ✅ **Savings**: ~$2-5/month
- ✅ **Location**: `main-stack.yaml`: S3 lifecycle policy

### 5. **ECS Fargate Optimization**
- ✅ **Status**: Implemented
- ✅ **Configuration**:
  - Production: 256 CPU, 512 MB (minimal)
  - Staging: Fargate Spot (70% savings)
  - Desired count: 1 task
- ✅ **Savings**: ~$3-5/month

### 6. **ECR Lifecycle Policy**
- ✅ **Status**: Implemented
- ✅ **Policy**: Keep last 5 images
- ✅ **Savings**: ~$1-2/month

### 7. **DynamoDB On-Demand Billing**
- ✅ **Status**: Implemented
- ✅ **Billing**: PAY_PER_REQUEST
- ✅ **Benefits**: No provisioned capacity, pay only for usage

### 8. **DynamoDB TTL**
- ✅ **Status**: Implemented
- ✅ **Tables with TTL**:
  - RateLimit
  - DDoSProtection
  - DeviceAttestation
- ✅ **Benefits**: Automatic cleanup, reduced storage costs

---

## 🔧 Recommended Optimizations

### 1. **DynamoDB Read-Before-Write Elimination** ⚠️ HIGH PRIORITY

**Current Issues:**
- `UserService.updateLastLogin()`: Reads user, then saves (2 operations)
- `UserService.updatePlaidAccessToken()`: Reads user, then saves (2 operations)
- `GoalService.updateGoalProgress()`: Reads goal, then saves (2 operations)

**Optimization:**
- Use `UpdateItem` with `SET` expressions for single-field updates
- Use `UpdateItem` with `ADD` expressions for numeric increments

**Implementation:**
```java
// ❌ Current: Read then write (2 operations)
UserTable user = repository.findById(userId);
user.setLastLogin(Instant.now());
repository.save(user);

// ✅ Optimized: Direct update (1 operation)
repository.updateField(userId, "lastLogin", Instant.now());
```

**Estimated Savings**: 30-50% reduction in DynamoDB write costs

**Priority**: HIGH
**Effort**: Medium (requires repository method updates)

---

### 2. **DynamoDB Conditional Writes** ⚠️ MEDIUM PRIORITY

**Current Issue:**
- No conditional writes to prevent accidental overwrites
- No optimistic locking

**Optimization:**
- Add `ConditionExpression` to prevent overwrites
- Use version numbers or timestamps for optimistic locking

**Implementation:**
```java
// ✅ Prevent overwrites
repository.saveIfNotExists(user, "attribute_not_exists(userId)");
```

**Estimated Savings**: Prevents data loss, reduces unnecessary writes

**Priority**: MEDIUM
**Effort**: Low (add condition expressions)

---

### 3. **DynamoDB Projection Expressions** ⚠️ MEDIUM PRIORITY

**Current Issue:**
- Full item retrieval when only specific attributes needed

**Optimization:**
- Use `ProjectionExpression` to retrieve only needed attributes

**Implementation:**
```java
// ❌ Current: Retrieve full item
UserTable user = repository.findById(userId);
String email = user.getEmail();

// ✅ Optimized: Retrieve only needed attribute
String email = repository.findByIdWithProjection(userId, "email");
```

**Estimated Savings**: 30-50% reduction in read costs for partial retrievals

**Priority**: MEDIUM
**Effort**: Medium (add projection expression support)

---

### 4. **DynamoDB Batch Operations** ⚠️ LOW PRIORITY

**Current Issue:**
- Individual `putItem` calls for multiple items

**Optimization:**
- Use `BatchWriteItem` for bulk operations
- Batch up to 25 items per request

**Implementation:**
```java
// ❌ Current: Individual writes
for (Transaction t : transactions) {
    repository.save(t);
}

// ✅ Optimized: Batch write
repository.batchSave(transactions);
```

**Estimated Savings**: 25% reduction in write costs for bulk operations

**Priority**: LOW
**Effort**: Medium (add batch methods)

---

### 5. **CloudWatch Metrics Optimization** ⚠️ LOW PRIORITY

**Current Issue:**
- All metrics sent to CloudWatch
- No metric filtering

**Optimization:**
- Filter out unnecessary metrics
- Use custom metrics only for critical metrics
- Reduce metric resolution for non-critical metrics

**Estimated Savings**: ~$1-3/month

**Priority**: LOW
**Effort**: Low (configure metric filters)

---

### 6. **S3 Lifecycle Policies** ⚠️ LOW PRIORITY

**Current Issue:**
- ALB access logs stored in S3 with 3-day retention
- No additional optimization needed (already optimized)

**Potential Optimization:**
- Consider moving to CloudWatch Logs Insights for querying
- Use S3 Intelligent-Tiering for cost optimization

**Estimated Savings**: Minimal (~$0.50/month)

**Priority**: LOW
**Effort**: Low

---

### 7. **ECS Task Right-Sizing** ✅ ALREADY OPTIMIZED

**Current Status:**
- ✅ Minimal task size: 256 CPU, 512 MB
- ✅ Single task for production
- ✅ Fargate Spot for staging

**No Further Optimization Needed**

---

### 8. **ALB Optimization** ⚠️ NOT APPLICABLE

**Current Status:**
- ✅ Standard ALB (no cost optimization available)
- ✅ Access logs retention: 3 days
- ✅ No further optimization possible

**Note**: ALB is a fixed cost service with minimal optimization opportunities.

---

## 📊 Cost Breakdown

### Current Monthly Costs (Optimized):

| Service | Cost | Notes |
|---------|------|-------|
| ECS Fargate | ~$7-10 | 1 task, 256 CPU, 512 MB |
| ALB | ~$16 | Fixed cost |
| VPC Interface Endpoints | ~$35 | 5 endpoints |
| CloudWatch Logs | ~$0-2 | 3-day retention |
| DynamoDB | ~$0-5 | On-demand, within free tier |
| ECR | ~$0-1 | Within free tier |
| S3 | ~$0-1 | Minimal storage |
| Data Transfer | ~$0-2 | First 1 GB free |
| **Total** | **~$58-65/month** | |

### Potential Additional Savings:

| Optimization | Estimated Savings | Priority |
|--------------|-------------------|----------|
| DynamoDB Read-Before-Write Elimination | ~$5-10/month | HIGH |
| DynamoDB Conditional Writes | ~$1-3/month | MEDIUM |
| DynamoDB Projection Expressions | ~$2-5/month | MEDIUM |
| DynamoDB Batch Operations | ~$1-2/month | LOW |
| CloudWatch Metrics Filtering | ~$1-3/month | LOW |
| **Total Potential Savings** | **~$10-23/month** | |

### Optimized Total: **~$85-95/month** (with all optimizations, including NAT Gateway for external APIs)

---

## 🎯 Implementation Priority

### Phase 1: High Priority (Immediate)
1. ✅ NAT Gateway removal (DONE)
2. ✅ CloudWatch log retention (DONE)
3. ✅ ALB access logs retention (DONE)
4. ⚠️ DynamoDB read-before-write elimination (TODO)

### Phase 2: Medium Priority (Next Sprint)
1. ⚠️ DynamoDB conditional writes
2. ⚠️ DynamoDB projection expressions

### Phase 3: Low Priority (Future)
1. ⚠️ DynamoDB batch operations
2. ⚠️ CloudWatch metrics filtering

---

## 📋 Best Practices

### 1. **Monitor Costs Regularly**
- Set up AWS Budgets with alerts
- Review Cost Explorer monthly
- Monitor CloudWatch metrics for usage patterns

### 2. **Use AWS Free Tier**
- Leverage free tier limits where possible
- Monitor usage to stay within free tier

### 3. **Right-Size Resources**
- Use minimal resource allocation
- Monitor utilization and adjust as needed

### 4. **Optimize Data Storage**
- Use TTL for temporary data
- Archive old data to cheaper storage
- Delete unused resources

### 5. **Use Efficient Data Access Patterns**
- Avoid read-before-write patterns
- Use UpdateItem for updates
- Use projection expressions for partial retrievals
- Batch operations where possible

---

## ✅ Summary

**Current Status:**
- ✅ Major optimizations implemented (NAT Gateway removal, log retention)
- ✅ Cost: ~$58-65/month
- ⚠️ Additional optimizations available (DynamoDB patterns)

**Next Steps:**
1. Implement DynamoDB read-before-write elimination
2. Add conditional writes for data safety
3. Add projection expressions for partial retrievals
4. Monitor costs and adjust as needed

**Target Cost**: ~$48-55/month (with all optimizations)

