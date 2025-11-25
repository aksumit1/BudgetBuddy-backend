# AWS Free Tier Optimization Guide

## Overview

This guide ensures all infrastructure is optimized for AWS Free Tier to minimize costs. The canary service runs only during deployments (1 request) and is stopped immediately after to avoid constant costs.

---

## ✅ Cost Optimizations Applied

### 1. **Canary Service - Deployment-Time Only**

**Configuration:**
- ✅ Canary service desired count: **0** (stopped by default)
- ✅ Canary deployed only during CI/CD deployments
- ✅ Single test request during deployment
- ✅ Service stopped immediately after test
- ✅ No constant traffic generation
- ✅ Canary stack can be deleted after deployment

**Cost Impact:** $0 when not deploying (canary service stopped)

### 2. **ECS Fargate - Free Tier Optimized**

**Configuration:**
- ✅ Use **Fargate Spot** for staging (up to 70% savings)
- ✅ Use **Fargate** for production (free tier eligible)
- ✅ **Minimal task size**: 256 CPU, 512 MB memory (smallest possible)
- ✅ **Desired count**: 1 task (minimum for production)
- ✅ Auto-scaling disabled for free tier

**Cost Impact:** 
- Staging: ~$0 (Fargate Spot)
- Production: ~$7-10/month (1 Fargate task, 256 CPU, 512 MB)

### 3. **DynamoDB - On-Demand Billing**

**Configuration:**
- ✅ **Billing mode**: PAY_PER_REQUEST (on-demand)
- ✅ No provisioned capacity
- ✅ Free tier: 25 GB storage, 25 read/write units per second

**Cost Impact:** $0 for first 25 GB storage, then $0.25/GB

### 4. **VPC Endpoints + NAT Gateway (Optimized)**

**Configuration:**
- ✅ **DynamoDB VPC Endpoint** (Gateway - FREE)
- ✅ **S3 VPC Endpoint** (Gateway - FREE)
- ✅ **CloudWatch Logs VPC Endpoint** (Interface - ~$7/month)
- ✅ **Secrets Manager VPC Endpoint** (Interface - ~$7/month)
- ✅ **ECR VPC Endpoint** (Interface - ~$7/month) - for Docker image pulls
- ✅ **ECR API VPC Endpoint** (Interface - ~$7/month) - for ECR API calls
- ✅ **CloudWatch Metrics VPC Endpoint** (Interface - ~$7/month) - for PutMetricData
- ✅ **KMS VPC Endpoint** (Interface - ~$7/month) - for encryption/decryption
- ✅ **NAT Gateway: Single Gateway** - Required for external APIs (Plaid, Stripe)

**Cost Impact:** 
- Gateway endpoints (DynamoDB, S3): FREE
- Interface endpoints: ~$35/month (5 endpoints: Logs, Secrets Manager, ECR, ECR API, CloudWatch Metrics, KMS)
- NAT Gateway: ~$32/month (only for external API calls - Plaid, Stripe)
- **Optimization**: AWS service traffic uses VPC Endpoints (no NAT Gateway usage), minimizing data transfer costs

### 5. **ECR - Free Tier**

**Configuration:**
- ✅ Image scanning enabled (free)
- ✅ Lifecycle policy: Keep last 5 images (minimize storage)
- ✅ Free tier: 500 MB storage per month

**Cost Impact:** $0 for first 500 MB, then $0.10/GB

### 6. **CloudWatch - Free Tier Optimized**

**Configuration:**
- ✅ **Log retention**: 3 days (optimized for cost)
- ✅ **Dashboard**: 1 dashboard (free tier: 3 dashboards)
- ✅ **Alarms**: 10 alarms (free tier: 10 alarms)
- ✅ **Metrics**: Standard metrics only (free)

**Cost Impact:** $0 (within free tier limits)

### 7. **ALB - Free Tier**

**Configuration:**
- ✅ Standard ALB (no cost optimization available)
- ✅ Access logs: 90-day retention with lifecycle (minimize storage)

**Cost Impact:** ~$16/month (ALB) + ~$0.008/LCU-hour

### 8. **ECS Service - Minimal Configuration**

**Configuration:**
- ✅ **Desired count**: 1 (minimum)
- ✅ **Auto-scaling**: Disabled (free tier)
- ✅ **Deployment circuit breaker**: Enabled (prevent bad deployments)

**Cost Impact:** Minimal (1 task only)

---

## 📊 Estimated Monthly Costs (Free Tier)

### Free Tier Eligible:
- ✅ DynamoDB: $0 (first 25 GB)
- ✅ ECR: $0 (first 500 MB)
- ✅ CloudWatch: $0 (within limits)
- ✅ VPC: $0
- ✅ VPC Gateway Endpoints: $0

### Paid Services (Minimal):
- ECS Fargate: ~$7-10/month (1 task, 256 CPU, 512 MB)
- ALB: ~$16/month
- VPC Interface Endpoints: ~$35/month (5 endpoints: Logs, Secrets Manager, ECR, ECR API, CloudWatch Metrics, KMS)
- NAT Gateway: ~$32/month (required for external APIs - Plaid, Stripe)
- Data Transfer (NAT): ~$0-1/month (only for external API calls)
- Data Transfer (Other): ~$0.09/GB (first 1 GB free)

### Total Estimated Cost: **~$90-100/month**

### Cost Optimization Applied:
1. ✅ **Single NAT Gateway** - Only for external APIs (Plaid, Stripe): **Minimized usage**
2. ✅ **VPC Gateway Endpoints** (DynamoDB, S3) - FREE: **AWS services don't use NAT Gateway**
3. ✅ **VPC Interface Endpoints** - AWS services use endpoints, not NAT Gateway: **Optimized**
4. ✅ **Minimal NAT Gateway Traffic** - Only external API calls use NAT Gateway: **Cost optimized**

**Current Cost (fully optimized): ~$90-100/month**

**Note**: NAT Gateway is required for external API access (Plaid, Stripe). VPC Endpoints ensure AWS service traffic doesn't use NAT Gateway, keeping costs minimal.

---

## 🔧 Canary Service Configuration

### Deployment-Time Only (No Constant Traffic)

**How it works:**
1. Canary service desired count: **0** (stopped)
2. During deployment:
   - Canary service scaled to **1** task
   - Single health check request sent
   - Service immediately scaled back to **0**
3. No constant traffic generation
4. No hourly requests

**Cost:** $0 (service stopped when not deploying)

### Manual Canary Testing (If Needed)

If you need to test canary manually:

```bash
# Start canary service
aws ecs update-service \
  --cluster BudgetBuddy-production-cluster \
  --service budgetbuddy-backend-canary \
  --desired-count 1 \
  --region us-east-1

# Wait for healthy
sleep 60

# Run single test
curl -H "X-Canary: true" https://api.budgetbuddy.com/actuator/health

# Stop canary service immediately
aws ecs update-service \
  --cluster BudgetBuddy-production-cluster \
  --service budgetbuddy-backend-canary \
  --desired-count 0 \
  --region us-east-1
```

---

## 🎯 Free Tier Limits

### DynamoDB Free Tier:
- ✅ 25 GB storage
- ✅ 25 read units/second
- ✅ 25 write units/second
- ✅ 2.5 million stream read requests

### ECS Fargate Free Tier:
- ❌ No free tier (pay per use)
- ✅ Optimized: 256 CPU, 512 MB (smallest size)

### ECR Free Tier:
- ✅ 500 MB storage/month
- ✅ Unlimited image pulls

### CloudWatch Free Tier:
- ✅ 10 custom metrics
- ✅ 10 alarms
- ✅ 3 dashboards
- ✅ 5 GB log ingestion
- ✅ 5 GB log storage

### VPC Free Tier:
- ✅ VPC, subnets, route tables: FREE
- ✅ Internet Gateway: FREE
- ✅ VPC Gateway Endpoints (DynamoDB, S3): FREE
- ❌ VPC Interface Endpoints: ~$7/month each (5 endpoints: ~$35/month)
- ❌ NAT Gateway: ~$32/month (required for external APIs - Plaid, Stripe)

---

## 📝 Recommendations for Free Tier

### Immediate Optimizations:
1. ✅ **Canary service**: Stopped by default (already configured)
2. ✅ **ECS tasks**: 1 task, 256 CPU, 512 MB (already configured)
3. ✅ **DynamoDB**: On-demand billing (already configured)
4. ✅ **CloudWatch logs**: 7-day retention (already configured)
5. ✅ **ECR**: Keep last 5 images (already configured)

### Optional Further Optimizations:
1. ✅ **NAT Gateway Removed** (already applied):
   - All AWS services accessed via VPC Endpoints
   - Gateway Endpoints for DynamoDB and S3 (FREE)
   - Interface Endpoints for CloudWatch Logs, Secrets Manager, ECR, CloudWatch Metrics, KMS
   - **Savings**: ~$32/month (already applied)

2. **Use Fargate Spot for Production**:
   - 70% cost savings
   - Less reliable (can be interrupted)
   - **Savings**: ~$3-5/month

3. **Reduce ALB** (if possible):
   - Use single AZ (not recommended for production)
   - **Savings**: Minimal

---

## ✅ Summary

**Canary Service:**
- ✅ No constant traffic
- ✅ Runs only during deployments
- ✅ Single test request per deployment
- ✅ Stopped immediately after test
- ✅ Cost: $0 when not deploying

**Free Tier Optimizations:**
- ✅ Minimal ECS task size (256 CPU, 512 MB)
- ✅ Single ECS task (desired count: 1)
- ✅ DynamoDB on-demand billing
- ✅ CloudWatch 3-day retention
- ✅ ECR lifecycle policy (keep last 5)
- ✅ VPC Gateway Endpoints (DynamoDB, S3 - free)
- ✅ VPC Interface Endpoints (CloudWatch Logs, Secrets Manager, ECR, CloudWatch Metrics, KMS)
- ✅ NAT Gateway removed (all AWS services via VPC Endpoints)

**Estimated Monthly Cost:** ~$90-100/month (Single NAT Gateway for external APIs, VPC Endpoints for AWS services)

