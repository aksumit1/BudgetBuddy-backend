# NAT Gateway for External API Access

## Overview

A single NAT Gateway has been added back to the infrastructure to enable ECS tasks in private subnets to make external API calls to services like Plaid and Stripe.

---

## ✅ Why NAT Gateway is Required

### External Services:
1. **Plaid API** (`api.plaid.com`, `production.plaid.com`, `sandbox.plaid.com`)
   - Bank account linking
   - Transaction synchronization
   - Account information retrieval

2. **Stripe API** (`api.stripe.com`)
   - Payment processing
   - Refund processing

### Network Configuration:
- ECS tasks are in **private subnets** (security best practice)
- `AssignPublicIp: DISABLED` (no public IPs)
- **No internet access** without NAT Gateway
- VPC Endpoints only work for **AWS services**, not external APIs

---

## 💰 Cost Optimization Strategy

### Minimize NAT Gateway Usage:

1. **VPC Endpoints for AWS Services** (No NAT Gateway traffic):
   - ✅ DynamoDB (Gateway Endpoint - FREE)
   - ✅ S3 (Gateway Endpoint - FREE)
   - ✅ CloudWatch Logs (Interface Endpoint)
   - ✅ Secrets Manager (Interface Endpoint)
   - ✅ ECR (Interface Endpoint)
   - ✅ CloudWatch Metrics (Interface Endpoint)
   - ✅ KMS (Interface Endpoint)

2. **NAT Gateway Only For**:
   - ✅ Plaid API calls
   - ✅ Stripe API calls
   - ✅ Other external HTTP/HTTPS requests

### Cost Impact:
- **NAT Gateway**: ~$32/month (fixed cost)
- **Data Transfer**: ~$0.045/GB (only for external API calls)
- **Estimated External API Traffic**: ~1-5 GB/month
- **Total NAT Gateway Cost**: ~$32-32.25/month

**Note**: AWS service traffic (DynamoDB, S3, CloudWatch, etc.) does NOT use NAT Gateway, keeping data transfer costs minimal.

---

## 🔧 Configuration

### Single NAT Gateway:
- Deployed in **PublicSubnet1**
- Routes traffic from **all private subnets**
- High availability within the AZ

### Route Tables:
- **PrivateRouteTable1**: Routes to NAT Gateway
- **PrivateRouteTable2**: Routes to NAT Gateway
- **PublicRouteTable**: Routes to Internet Gateway (for ALB)

---

## 📊 Updated Cost Breakdown

### With Single NAT Gateway (Optimized):

| Service | Cost | Notes |
|---------|------|-------|
| ECS Fargate | ~$7-10 | 1 task, 256 CPU, 512 MB |
| ALB | ~$16 | Fixed cost |
| VPC Interface Endpoints | ~$35 | 5 endpoints (AWS services) |
| **NAT Gateway** | **~$32** | **For external APIs only** |
| DynamoDB | ~$0-5 | On-demand |
| ECR | ~$0-1 | Within free tier |
| Data Transfer (NAT) | ~$0-1 | Only for Plaid/Stripe calls |
| **Total** | **~$90-100/month** | |

### Cost Optimization:
- ✅ VPC Endpoints handle AWS service traffic (no NAT Gateway usage)
- ✅ Single NAT Gateway (not multiple)
- ✅ NAT Gateway only used for external APIs (minimal data transfer)

---

## ✅ Benefits

1. **Security**: ECS tasks remain in private subnets
2. **Cost-Effective**: Single NAT Gateway, VPC Endpoints for AWS services
3. **Reliability**: NAT Gateway is highly available
4. **Functionality**: External APIs (Plaid, Stripe) work correctly

---

## 🎯 Summary

**Status**: ✅ NAT Gateway added back for external API access

**Configuration**:
- Single NAT Gateway in PublicSubnet1
- Routes configured for all private subnets
- VPC Endpoints still used for AWS services (cost optimization)

**Cost**: ~$32/month (acceptable for production reliability)

**Result**: 
- ✅ Plaid API calls work
- ✅ Stripe API calls work
- ✅ AWS services use VPC Endpoints (no NAT Gateway usage)
- ✅ Minimal data transfer through NAT Gateway

