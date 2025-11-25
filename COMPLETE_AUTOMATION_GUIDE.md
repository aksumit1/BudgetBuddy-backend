# Complete Automation Guide - One-Time Setup, Fully Automated Operations

## Overview

This guide confirms that **everything is automated via Infrastructure as Code and CI/CD**. Once you provide your AWS account credentials and secrets, the system will:

1. ✅ Set up all AWS infrastructure automatically
2. ✅ Deploy all application code
3. ✅ Run canary tests
4. ✅ Verify everything is working
5. ✅ Handle all future changes automatically

---

## 🚀 Initial Setup (One-Time)

### Step 1: Create AWS Account
1. Go to [AWS Console](https://console.aws.amazon.com)
2. Create a new account or use existing
3. Note your **AWS Account ID**

### Step 2: Configure GitHub Secrets
Add these secrets to your GitHub repository:

```bash
# AWS Credentials
AWS_ACCESS_KEY_ID=<your-access-key>
AWS_SECRET_ACCESS_KEY=<your-secret-key>

# Plaid Credentials (after creating Plaid account)
PLAID_CLIENT_ID=<plaid-client-id>
PLAID_SECRET=<plaid-secret>
PLAID_ENVIRONMENT=sandbox  # or production

# Stripe Credentials (if using Stripe)
STRIPE_SECRET_KEY=<stripe-secret-key>
STRIPE_PUBLISHABLE_KEY=<stripe-publishable-key>

# Domain (optional)
DOMAIN_NAME=budgetbuddy.com
```

### Step 3: Trigger Initial Deployment
Simply push to `main` or `develop` branch, or manually trigger:
- **Infrastructure Deployment**: `.github/workflows/infrastructure-deploy.yml`
- **Backend Deployment**: `.github/workflows/backend-ci-cd.yml`

**That's it!** The system will automatically:
- ✅ Create all AWS infrastructure
- ✅ Deploy DynamoDB tables
- ✅ Deploy ECS cluster and services
- ✅ Set up ALB and networking
- ✅ Configure secrets
- ✅ Deploy application code
- ✅ Run canary tests
- ✅ Verify health endpoints

---

## 🔄 Automated Operations

### 1. **Updating Plaid Password/Secrets**

**Method 1: Via AWS Console (Manual)**
```bash
aws secretsmanager update-secret \
  --secret-id budgetbuddy/production/plaid \
  --secret-string '{"clientId":"NEW_CLIENT_ID","secret":"NEW_SECRET","environment":"production"}' \
  --region us-east-1
```

**Method 2: Via CI/CD (Automated)**
- Update secrets in GitHub Secrets
- Push changes to trigger deployment
- Secrets are automatically updated in AWS Secrets Manager

**Method 3: Via Infrastructure as Code**
- Update `infrastructure/cloudformation/secrets.yaml`
- Push changes
- CloudFormation updates secrets automatically

### 2. **Changing AWS Account**

**If you need to switch AWS accounts:**
1. Update GitHub Secrets with new AWS credentials
2. Push to trigger infrastructure deployment
3. All infrastructure will be recreated in the new account

**Note**: This will create new resources. You may want to export data first.

### 3. **Changing DynamoDB Schema**

**Fully Automated via Infrastructure as Code:**

1. **Update Schema Template**:
   ```yaml
   # infrastructure/cloudformation/dynamodb.yaml
   # Add new table, index, or attribute
   ```

2. **Commit and Push**:
   ```bash
   git add infrastructure/cloudformation/dynamodb.yaml
   git commit -m "Update DynamoDB schema: add new index"
   git push origin main
   ```

3. **Automatic Deployment**:
   - CI/CD detects schema changes
   - Runs `schema-migration.sh`
   - Updates tables via CloudFormation
   - Validates all tables are ACTIVE
   - No downtime (CloudFormation handles updates gracefully)

**Example: Adding a new Global Secondary Index**
```yaml
# In dynamodb.yaml
GlobalSecondaryIndexes:
  - IndexName: NewIndex
    KeySchema:
      - AttributeName: newAttribute
        KeyType: HASH
    Projection:
      ProjectionType: ALL
```

### 4. **Changing Website/Domain Name**

**Update in CloudFormation Template:**

1. **Update Domain Parameter**:
   ```yaml
   # infrastructure/cloudformation/main-stack.yaml
   Parameters:
     DomainName:
       Type: String
       Default: newdomain.com  # Change here
   ```

2. **Update ALB Configuration**:
   ```yaml
   # SSL Certificate will be updated automatically
   SSLCertificate:
     DomainName: !Sub 'api.${DomainName}'
   ```

3. **Commit and Push**:
   - CloudFormation updates ALB configuration
   - New SSL certificate is requested (DNS validation required)
   - ALB listeners are updated automatically

### 5. **Adding New Alarms**

**Fully Automated via Infrastructure as Code:**

1. **Add Alarm to Template**:
   ```yaml
   # infrastructure/cloudformation/monitoring.yaml
   NewAlarm:
     Type: AWS::CloudWatch::Alarm
     Properties:
       AlarmName: !Sub 'budgetbuddy-${Environment}-new-alarm'
       MetricName: YourMetric
       Namespace: AWS/ECS
       Threshold: 90
       # ... alarm configuration
   ```

2. **Commit and Push**:
   - CloudFormation creates new alarm
   - Alarm is automatically configured
   - SNS notifications are set up

### 6. **Adding New Monitors/Dashboards**

**Fully Automated via Infrastructure as Code:**

1. **Update Dashboard Template**:
   ```yaml
   # infrastructure/cloudformation/monitoring.yaml
   MonitoringDashboard:
     DashboardBody: !Sub |
       {
         "widgets": [
           {
             "type": "metric",
             "properties": {
               "metrics": [
                 ["AWS/ECS", "NewMetric", {...}]
               ]
             }
           }
         ]
       }
   ```

2. **Commit and Push**:
   - CloudFormation updates dashboard
   - New metrics are automatically added

### 7. **Deploying Code Changes**

**Fully Automated via CI/CD:**

1. **Push Code Changes**:
   ```bash
   git add .
   git commit -m "Add new feature"
   git push origin main
   ```

2. **Automatic Deployment**:
   - Tests run automatically
   - Docker image is built
   - Image is pushed to ECR
   - Task definition is updated
   - ECS service is updated (blue/green deployment)
   - Health checks verify deployment
   - Canary tests run
   - Smoke tests verify functionality

**No manual intervention required!**

---

## 🧪 Canary Testing

### Automated Canary Deployment

The system includes automated canary testing:

1. **Canary Fleet Template**: `infrastructure/cloudformation/canary-fleet.yaml`
   - Creates canary ECS service
   - Routes small percentage of traffic to canary
   - Monitors metrics and errors
   - Automatically promotes or rolls back

2. **Canary Tests in CI/CD**:
   - Runs after staging deployment
   - Validates canary service health
   - Compares metrics between canary and production
   - Automatically promotes if tests pass

### Manual Canary Testing

If you need to run canary tests manually:

```bash
# Trigger canary deployment
aws ecs update-service \
  --cluster BudgetBuddy-production-cluster \
  --service BudgetBuddy-production-service \
  --task-definition <new-task-definition> \
  --desired-count 2 \
  --deployment-configuration maximumPercent=100,minimumHealthyPercent=50
```

---

## 📋 Complete Automation Checklist

### ✅ Infrastructure Setup
- [x] VPC and Networking (automated)
- [x] DynamoDB Tables (automated)
- [x] ECR Repository (automated)
- [x] ECS Cluster (automated)
- [x] ALB and Target Groups (automated)
- [x] Security Groups (automated)
- [x] IAM Roles (automated)
- [x] Secrets Manager (automated)
- [x] Monitoring and Alarms (automated)

### ✅ Application Deployment
- [x] Docker Build (automated)
- [x] ECR Push (automated)
- [x] Task Definition Update (automated)
- [x] ECS Service Update (automated)
- [x] Health Check Validation (automated)

### ✅ Testing
- [x] Unit Tests (automated)
- [x] Integration Tests (automated)
- [x] Canary Tests (automated)
- [x] Smoke Tests (automated)
- [x] Load Tests (automated)
- [x] Chaos Tests (automated)

### ✅ Operations
- [x] Schema Updates (automated)
- [x] Secret Updates (automated)
- [x] Alarm Updates (automated)
- [x] Monitor Updates (automated)
- [x] Code Deployments (automated)
- [x] Rollback on Failure (automated)

---

## 🔐 Secret Management

### Secrets Stored in AWS Secrets Manager

All secrets are managed via Infrastructure as Code:

1. **JWT Secret**: Auto-generated, stored in Secrets Manager
2. **Plaid Secrets**: Stored in Secrets Manager, can be updated
3. **Stripe Secrets**: Stored in Secrets Manager, can be updated
4. **Database Config**: Stored in Secrets Manager (if needed)

### Updating Secrets

**Via CloudFormation** (Recommended):
```bash
# Update secrets.yaml
# Push changes
# CloudFormation updates secrets automatically
```

**Via AWS CLI** (Manual):
```bash
aws secretsmanager update-secret \
  --secret-id budgetbuddy/production/plaid \
  --secret-string '{"clientId":"NEW","secret":"NEW"}'
```

**Via GitHub Secrets** (CI/CD):
- Update GitHub repository secrets
- Next deployment uses new secrets

---

## 🎯 What You Need to Do

### One-Time Setup:
1. ✅ Create AWS account
2. ✅ Add AWS credentials to GitHub Secrets
3. ✅ Add Plaid credentials to GitHub Secrets (after creating Plaid account)
4. ✅ Push to trigger deployment

### Ongoing Operations:
**Nothing!** Everything is automated:
- Code changes → Automatic deployment
- Schema changes → Automatic migration
- Secret updates → Automatic sync
- Alarm changes → Automatic update
- Monitor changes → Automatic update

---

## 📝 Example Workflows

### Example 1: Update Plaid Password
```bash
# Option 1: Update GitHub Secret
# Go to GitHub → Settings → Secrets → Update PLAID_SECRET
# Push any change → Automatic deployment

# Option 2: Update via CloudFormation
# Edit infrastructure/cloudformation/secrets.yaml
git commit -am "Update Plaid secret"
git push
# CloudFormation updates secret automatically
```

### Example 2: Add New DynamoDB Index
```bash
# Edit infrastructure/cloudformation/dynamodb.yaml
# Add new GSI to Transactions table
git commit -am "Add transaction date index"
git push
# Schema migration runs automatically
# Index is created with zero downtime
```

### Example 3: Deploy Code Changes
```bash
# Make code changes
git add .
git commit -m "Add new feature"
git push origin main
# Everything happens automatically:
# - Tests run
# - Image built
# - Deployed to ECS
# - Canary tests run
# - Health checks verify
```

### Example 4: Add New Alarm
```bash
# Edit infrastructure/cloudformation/monitoring.yaml
# Add new alarm definition
git commit -am "Add high error rate alarm"
git push
# CloudFormation creates alarm automatically
# SNS notifications configured
```

---

## ✅ Verification

After initial setup, verify everything is working:

```bash
# Check infrastructure
aws cloudformation list-stacks --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE

# Check DynamoDB tables
aws dynamodb list-tables

# Check ECS services
aws ecs list-services --cluster BudgetBuddy-production-cluster

# Check health endpoint
curl https://api.budgetbuddy.com/actuator/health
```

---

## 🎉 Summary

**YES - Everything is Fully Automated:**

1. ✅ **Initial Setup**: Just provide AWS credentials and secrets → Everything deploys automatically
2. ✅ **Plaid Password Changes**: Update GitHub secret or CloudFormation → Automatic update
3. ✅ **AWS Account Changes**: Update GitHub secrets → Infrastructure recreated automatically
4. ✅ **DynamoDB Schema Changes**: Update template → Automatic migration
5. ✅ **Website Name Changes**: Update template → Automatic ALB/SSL update
6. ✅ **Alarm Changes**: Update template → Automatic alarm creation
7. ✅ **Monitor Changes**: Update template → Automatic dashboard update
8. ✅ **Code Deployments**: Push code → Automatic build, test, deploy, canary test, verify

**You provide credentials once, and the system handles everything else automatically via Infrastructure as Code and CI/CD!**

