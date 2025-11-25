# Canary Testing and Free Tier Configuration

## ✅ Canary Configuration - No Constant Traffic

### How It Works

**Canary service runs ONLY during deployments:**
1. ✅ **Default state**: Canary service desired count = **0** (stopped)
2. ✅ **During deployment**: 
   - Canary service scaled to **1** task temporarily
   - **Single health check request** sent (1 request only)
   - Service immediately scaled back to **0**
3. ✅ **No constant traffic**: No hourly requests, no continuous monitoring
4. ✅ **Cost**: $0 when not deploying (service stopped)

### Configuration

**Canary Fleet Template** (`canary-fleet.yaml`):
- `DesiredCount`: **0** (stopped by default)
- `CanaryPercentage`: **0** (no traffic routing)
- Deployed only during CI/CD deployments
- Stopped immediately after single test

**CI/CD Integration**:
- Canary deployed during production deployment
- Single health check request
- Service stopped immediately after test
- No constant traffic generation

---

## 💰 Free Tier Optimizations Applied

### 1. **ECS Service - Minimal Configuration**

**Configuration:**
- ✅ **Desired count**: **1** task (minimum for production)
- ✅ **CPU**: 256 (0.25 vCPU - smallest Fargate size)
- ✅ **Memory**: 512 MB (smallest Fargate size)
- ✅ **Auto-scaling**: **Disabled** (free tier)
- ✅ **Fargate Spot**: Enabled for staging (70% cost savings)

**Cost Impact:**
- Production: ~$7-10/month (1 Fargate task)
- Staging: ~$2-3/month (1 Fargate Spot task)

### 2. **DynamoDB - On-Demand Billing**

**Configuration:**
- ✅ **Billing mode**: PAY_PER_REQUEST (on-demand)
- ✅ **Free tier**: 25 GB storage, 25 read/write units/second

**Cost Impact:** $0 for first 25 GB storage

### 3. **NAT Gateway - Single Gateway**

**Configuration:**
- ✅ **NAT Gateways**: **1** (instead of 2) for free tier
- ✅ All private subnets route through single NAT Gateway
- ✅ **Savings**: ~$32/month (removed second NAT Gateway)

**Cost Impact:** ~$32/month (1 NAT Gateway)

### 4. **VPC Endpoints - Free Gateway Endpoints**

**Configuration:**
- ✅ **DynamoDB VPC Endpoint**: Gateway (FREE)
- ✅ **S3 VPC Endpoint**: Gateway (FREE)
- ✅ **CloudWatch Logs VPC Endpoint**: Interface (~$7/month)
- ✅ **Secrets Manager VPC Endpoint**: Interface (~$7/month)

**Cost Impact:** 
- Gateway endpoints: FREE
- Interface endpoints: ~$14/month

### 5. **ECR - Free Tier Optimized**

**Configuration:**
- ✅ **Lifecycle policy**: Keep last **5** images (reduced from 10)
- ✅ **Free tier**: 500 MB storage/month

**Cost Impact:** $0 for first 500 MB

### 6. **CloudWatch - Free Tier Optimized**

**Configuration:**
- ✅ **Log retention**: **7 days** (reduced from 30)
- ✅ **Dashboard**: 1 dashboard (free tier: 3 dashboards)
- ✅ **Alarms**: 10 alarms (free tier: 10 alarms)

**Cost Impact:** $0 (within free tier limits)

### 7. **ALB - Standard (No Free Tier)**

**Configuration:**
- ✅ Standard ALB (required for production)
- ✅ Access logs with lifecycle (minimize storage)

**Cost Impact:** ~$16/month (ALB) + ~$0.008/LCU-hour

---

## 📊 Estimated Monthly Costs

### Free Tier Eligible (FREE):
- ✅ DynamoDB: $0 (first 25 GB)
- ✅ ECR: $0 (first 500 MB)
- ✅ CloudWatch: $0 (within limits)
- ✅ VPC: $0
- ✅ VPC Gateway Endpoints: $0

### Paid Services (Minimal):
- **ECS Fargate**: ~$7-10/month (1 task, 256 CPU, 512 MB)
- **ALB**: ~$16/month
- **NAT Gateway**: ~$32/month (1 gateway)
- **VPC Interface Endpoints**: ~$14/month (2 endpoints)
- **Data Transfer**: ~$0.09/GB (first 1 GB free)

### **Total Estimated Cost: ~$69-75/month**

### To Reduce Further (Optional):
1. **Remove NAT Gateway** (use only VPC endpoints): **Save ~$32/month** → **Total: ~$37-43/month**
2. **Remove VPC Interface Endpoints** (use NAT Gateway): **Save ~$14/month** → **Total: ~$55-61/month**
3. **Use Fargate Spot for production**: **Save ~$3-5/month** (but less reliable)

**Minimum Cost (with all optimizations): ~$23-25/month**

---

## 🎯 Canary Testing - Deployment-Time Only

### How It Works

1. **Before Deployment**: Canary service stopped (desired count: 0)
2. **During Deployment**:
   - Canary service scaled to 1 task
   - Single health check request sent
   - Service immediately scaled back to 0
3. **After Deployment**: Canary service stopped (no constant traffic)

### No Constant Traffic

- ✅ **No hourly requests**
- ✅ **No continuous monitoring**
- ✅ **No constant traffic generation**
- ✅ **Cost: $0** when not deploying

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

# Run single test (1 request)
curl -H "X-Canary: true" https://api.budgetbuddy.com/actuator/health

# Stop canary service immediately
aws ecs update-service \
  --cluster BudgetBuddy-production-cluster \
  --service budgetbuddy-backend-canary \
  --desired-count 0 \
  --region us-east-1
```

---

## ✅ Summary

### Canary Configuration:
- ✅ **No constant traffic** - runs only during deployments
- ✅ **1 request per deployment** - single health check
- ✅ **Stopped immediately** - no ongoing costs
- ✅ **Cost: $0** when not deploying

### Free Tier Optimizations:
- ✅ **ECS**: 1 task, 256 CPU, 512 MB (minimal size)
- ✅ **Auto-scaling**: Disabled
- ✅ **DynamoDB**: On-demand billing
- ✅ **NAT Gateway**: 1 gateway (instead of 2)
- ✅ **CloudWatch**: 7-day retention
- ✅ **ECR**: Keep last 5 images
- ✅ **VPC Endpoints**: Free gateway endpoints used

### Estimated Monthly Cost: **~$69-75/month** (or **~$23-25/month** with NAT Gateway removal)

**All optimizations are applied automatically via Infrastructure as Code!**

