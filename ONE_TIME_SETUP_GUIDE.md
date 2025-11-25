# One-Time Setup Guide - Fully Automated AWS Deployment

## ✅ Confirmation: Everything is Automated

**YES** - Once you provide your AWS account credentials and secrets, the system will:
1. ✅ Set up ALL AWS infrastructure automatically
2. ✅ Deploy ALL application code automatically
3. ✅ Run canary tests automatically
4. ✅ Verify everything is working automatically
5. ✅ Handle ALL future changes automatically via Infrastructure as Code

---

## 🚀 One-Time Setup Steps

### Step 1: Create AWS Account
1. Go to [AWS Console](https://console.aws.amazon.com)
2. Create account or use existing
3. Note your **AWS Account ID**

### Step 2: Create IAM User for CI/CD
1. Go to **IAM** → **Users** → **Create user**
2. Username: `budgetbuddy-cicd`
3. Attach policies:
   - `AdministratorAccess` (or create custom policy with required permissions)
4. Create **Access Key** → Save **Access Key ID** and **Secret Access Key**

### Step 3: Add GitHub Secrets
Go to your GitHub repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

Add these secrets:

```
AWS_ACCESS_KEY_ID=<your-aws-access-key-id>
AWS_SECRET_ACCESS_KEY=<your-aws-secret-access-key>
```

**Optional (can be added later):**
```
PLAID_CLIENT_ID=<plaid-client-id>
PLAID_SECRET=<plaid-secret>
PLAID_ENVIRONMENT=sandbox
STRIPE_SECRET_KEY=<stripe-secret-key>
STRIPE_PUBLISHABLE_KEY=<stripe-publishable-key>
```

### Step 4: Trigger Deployment
**That's it!** Simply push to `main` or `develop` branch, or manually trigger:

1. Go to **Actions** tab in GitHub
2. Select **Infrastructure Deployment** workflow
3. Click **Run workflow**
4. Select environment: `staging` or `production`
5. Click **Run workflow**

**The system will automatically:**
- ✅ Create VPC, subnets, gateways, route tables
- ✅ Create DynamoDB tables with schema
- ✅ Create ECR repository
- ✅ Create ECS cluster and services
- ✅ Set up ALB with HTTPS
- ✅ Configure security groups
- ✅ Set up IAM roles
- ✅ Create secrets in Secrets Manager
- ✅ Set up monitoring and alarms
- ✅ Deploy application code
- ✅ Run canary tests
- ✅ Verify health endpoints
- ✅ Complete deployment

---

## 🔄 Future Changes - All Automated

### 1. Update Plaid Password/Secrets

**Option A: Via GitHub Secrets (Recommended)**
1. Update `PLAID_SECRET` in GitHub Secrets
2. Push any change or trigger deployment
3. Secrets are automatically synced to AWS Secrets Manager

**Option B: Via Script (Automated)**
```bash
export PLAID_CLIENT_ID="new-client-id"
export PLAID_SECRET="new-secret"
bash infrastructure/scripts/update-secrets.sh us-east-1 production plaid
```

**Option C: Via CloudFormation (Infrastructure as Code)**
1. Update `infrastructure/cloudformation/secrets.yaml`
2. Push changes
3. CloudFormation updates secrets automatically

### 2. Change AWS Account

1. Update GitHub Secrets with new AWS credentials
2. Push to trigger infrastructure deployment
3. All infrastructure is automatically recreated in new account

### 3. Change DynamoDB Schema

1. **Edit** `infrastructure/cloudformation/dynamodb.yaml`
2. **Add** new tables, indexes, or attributes
3. **Commit and push**:
   ```bash
   git add infrastructure/cloudformation/dynamodb.yaml
   git commit -m "Add new DynamoDB index"
   git push
   ```
4. **Automatic**: Schema migration runs, tables updated, zero downtime

### 4. Change Website/Domain Name

1. **Edit** `infrastructure/cloudformation/main-stack.yaml`:
   ```yaml
   Parameters:
     DomainName:
       Default: newdomain.com  # Change here
   ```
2. **Push changes**
3. **Automatic**: ALB updated, new SSL certificate requested, DNS validation required

### 5. Add New Alarms

1. **Edit** `infrastructure/cloudformation/monitoring.yaml`
2. **Add** new alarm definition
3. **Push changes**
4. **Automatic**: CloudFormation creates alarm, SNS notifications configured

### 6. Add New Monitors/Dashboards

1. **Edit** `infrastructure/cloudformation/monitoring.yaml`
2. **Update** dashboard body with new metrics
3. **Push changes**
4. **Automatic**: CloudFormation updates dashboard

### 7. Deploy Code Changes

1. **Make code changes**
2. **Commit and push**:
   ```bash
   git add .
   git commit -m "Add new feature"
   git push origin main
   ```
3. **Automatic**:
   - Tests run
   - Docker image built
   - Pushed to ECR
   - Task definition updated
   - ECS service updated (blue/green)
   - Canary tests run
   - Health checks verify
   - Smoke tests run
   - Deployment verified

---

## 🧪 Canary Testing - Fully Automated

### Automatic Canary Deployment

The system automatically:
1. ✅ Deploys canary fleet after production deployment
2. ✅ Routes 10% of traffic to canary
3. ✅ Monitors canary health and metrics
4. ✅ Runs canary-specific tests
5. ✅ Validates canary performance
6. ✅ Promotes or rolls back based on results

### Canary Test Flow

```
Production Deployment
    ↓
Canary Fleet Deployed
    ↓
10% Traffic Routed to Canary
    ↓
Canary Health Checks
    ↓
Canary Metrics Monitored
    ↓
Canary Tests Run
    ↓
✅ If Pass: Canary Promoted
❌ If Fail: Automatic Rollback
```

---

## 📋 Complete Automation Checklist

### ✅ Infrastructure (All Automated)
- [x] VPC and Networking
- [x] DynamoDB Tables and Schema
- [x] ECR Repository
- [x] ECS Cluster and Services
- [x] ALB and Target Groups
- [x] Security Groups
- [x] IAM Roles
- [x] Secrets Manager
- [x] Monitoring and Alarms

### ✅ Deployment (All Automated)
- [x] Docker Build
- [x] ECR Push
- [x] Task Definition Update
- [x] ECS Service Update
- [x] Blue/Green Deployment
- [x] Canary Testing
- [x] Health Check Validation

### ✅ Operations (All Automated)
- [x] Secret Updates
- [x] Schema Updates
- [x] Alarm Updates
- [x] Monitor Updates
- [x] Code Deployments
- [x] Rollback on Failure

---

## 🎯 What You Need to Do

### Initial Setup (One-Time):
1. ✅ Create AWS account
2. ✅ Create IAM user with access key
3. ✅ Add AWS credentials to GitHub Secrets
4. ✅ (Optional) Add Plaid/Stripe secrets to GitHub Secrets
5. ✅ Push to trigger deployment

### Ongoing Operations:
**NOTHING!** Everything is automated:
- Code changes → Automatic deployment
- Schema changes → Automatic migration
- Secret updates → Automatic sync
- Alarm changes → Automatic update
- Monitor changes → Automatic update
- Canary tests → Automatic execution

---

## ✅ Verification After Setup

After initial deployment, verify everything:

```bash
# Check infrastructure
aws cloudformation list-stacks --stack-status-filter CREATE_COMPLETE

# Check DynamoDB tables
aws dynamodb list-tables

# Check ECS services
aws ecs list-services --cluster BudgetBuddy-production-cluster

# Check health endpoint
curl https://api.budgetbuddy.com/actuator/health

# Check canary service
aws ecs describe-services \
  --cluster BudgetBuddy-production-cluster \
  --services budgetbuddy-backend-canary
```

---

## 🎉 Summary

**YES - Everything is Fully Automated:**

1. ✅ **Initial Setup**: Provide AWS credentials → Everything deploys automatically
2. ✅ **Plaid Password Changes**: Update GitHub secret → Automatic sync
3. ✅ **AWS Account Changes**: Update GitHub secrets → Infrastructure recreated
4. ✅ **DynamoDB Schema Changes**: Update template → Automatic migration
5. ✅ **Website Name Changes**: Update template → Automatic ALB/SSL update
6. ✅ **Alarm Changes**: Update template → Automatic alarm creation
7. ✅ **Monitor Changes**: Update template → Automatic dashboard update
8. ✅ **Code Deployments**: Push code → Automatic build, test, deploy, canary test, verify

**You provide credentials ONCE, and the system handles EVERYTHING else automatically via Infrastructure as Code and CI/CD!**

---

## 📞 Support

If you encounter any issues:
1. Check GitHub Actions logs
2. Check CloudFormation stack events
3. Check CloudWatch logs
4. All infrastructure changes are tracked in Git

**Everything is Infrastructure as Code - you can review, modify, and deploy changes with confidence!**

