# BudgetBuddy Backend - Quick Start Guide

## Prerequisites
- AWS Account with appropriate permissions
- AWS CLI v2 installed and configured
- Docker installed
- Maven 3.9+ and Java 17+

## Step 1: Initial Setup

### 1.1 Configure AWS Credentials
```bash
aws configure
# Enter your AWS Access Key ID
# Enter your AWS Secret Access Key
# Enter default region: us-east-1
# Enter default output format: json
```

### 1.2 Clone Repository
```bash
git clone https://github.com/your-org/BudgetBuddy-Backend.git
cd BudgetBuddy-Backend
```

## Step 2: Setup Secrets

### 2.1 Create Secrets in AWS Secrets Manager
```bash
./infrastructure/scripts/setup-secrets.sh us-east-1 production
```

This will prompt you for:
- Plaid Client ID
- Plaid Secret
- Stripe Secret Key

JWT secret will be auto-generated.

## Step 3: Deploy Infrastructure

### 3.1 Deploy Main Stack
```bash
cd infrastructure/cloudformation
aws cloudformation deploy \
  --template-file main-stack.yaml \
  --stack-name budgetbuddy-backend-production \
  --parameter-overrides Environment=production \
  --capabilities CAPABILITY_NAMED_IAM \
  --region us-east-1
```

### 3.2 Get Stack Outputs
```bash
aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs' \
  --output table
```

Note down:
- `ECSClusterName`
- `ALBTargetGroupArn`
- `ECSTaskExecutionRoleArn`
- `ECSTaskRoleArn`
- `ECRRepositoryURI`

## Step 4: Build and Push Docker Image

### 4.1 Build Image
```bash
cd ../..
docker build --platform linux/arm64 -t budgetbuddy-backend:latest .
```

### 4.2 Login to ECR
```bash
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  $(aws sts get-caller-identity --query Account --output text).dkr.ecr.us-east-1.amazonaws.com
```

### 4.3 Tag and Push
```bash
ECR_URI=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs[?OutputKey==`ECRRepositoryURI`].OutputValue' \
  --output text)

docker tag budgetbuddy-backend:latest ${ECR_URI}:latest
docker push ${ECR_URI}:latest
```

## Step 5: Deploy ECS Service

### 5.1 Deploy Service Stack
```bash
cd infrastructure/cloudformation

# Get required values from main stack
CLUSTER_NAME=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs[?OutputKey==`ECSClusterName`].OutputValue' \
  --output text)

TARGET_GROUP_ARN=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBTargetGroupArn`].OutputValue' \
  --output text)

TASK_EXECUTION_ROLE=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs[?OutputKey==`ECSTaskExecutionRoleArn`].OutputValue' \
  --output text)

TASK_ROLE=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs[?OutputKey==`ECSTaskRoleArn`].OutputValue' \
  --output text)

ECR_URI=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs[?OutputKey==`ECRRepositoryURI`].OutputValue' \
  --output text)

# Deploy service
aws cloudformation deploy \
  --template-file ecs-service.yaml \
  --stack-name budgetbuddy-backend-production-service \
  --parameter-overrides \
    Environment=production \
    ClusterName=${CLUSTER_NAME} \
    TargetGroupArn=${TARGET_GROUP_ARN} \
    TaskExecutionRoleArn=${TASK_EXECUTION_ROLE} \
    TaskRoleArn=${TASK_ROLE} \
    ImageURI=${ECR_URI}:latest \
  --capabilities CAPABILITY_NAMED_IAM \
  --region us-east-1
```

## Step 6: Verify Deployment

### 6.1 Check Service Status
```bash
aws ecs describe-services \
  --cluster ${CLUSTER_NAME} \
  --services budgetbuddy-backend \
  --region us-east-1 \
  --query 'services[0].{Status:status,Running:runningCount,Desired:desiredCount}'
```

### 6.2 Get ALB DNS
```bash
ALB_DNS=$(aws cloudformation describe-stacks \
  --stack-name budgetbuddy-backend-production \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBDNSName`].OutputValue' \
  --output text)

echo "ALB DNS: ${ALB_DNS}"
```

### 6.3 Test Health Endpoint
```bash
curl http://${ALB_DNS}/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

## Step 7: Setup CI/CD (Optional)

### 7.1 Deploy Pipeline
```bash
cd ../cicd

# Get GitHub token
read -sp "Enter GitHub Personal Access Token: " GITHUB_TOKEN
echo

aws cloudformation deploy \
  --template-file pipeline.yaml \
  --stack-name budgetbuddy-backend-pipeline \
  --parameter-overrides \
    Environment=production \
    GitHubOwner=your-org \
    GitHubRepo=BudgetBuddy-Backend \
    GitHubBranch=main \
    GitHubToken=${GITHUB_TOKEN} \
  --capabilities CAPABILITY_NAMED_IAM \
  --region us-east-1
```

## Step 8: Setup Monitoring

### 8.1 Create CloudWatch Dashboard
```bash
aws cloudwatch put-dashboard \
  --dashboard-name budgetbuddy-backend \
  --dashboard-body file://../cloudwatch/dashboard.json \
  --region us-east-1
```

### 8.2 View Dashboard
```bash
echo "Dashboard URL: https://console.aws.amazon.com/cloudwatch/home?region=us-east-1#dashboards:name=budgetbuddy-backend"
```

## Troubleshooting

### Service Not Starting
```bash
# Check ECS service events
aws ecs describe-services \
  --cluster ${CLUSTER_NAME} \
  --services budgetbuddy-backend \
  --query 'services[0].events[0:5]' \
  --output table

# Check task logs
aws logs tail /aws/ecs/${CLUSTER_NAME} --follow
```

### Health Check Failing
```bash
# Check target group health
aws elbv2 describe-target-health \
  --target-group-arn ${TARGET_GROUP_ARN} \
  --query 'TargetHealthDescriptions[*].{Target:Target.Id,Health:TargetHealth.State,Reason:TargetHealth.Reason}'
```

### Cannot Connect to DynamoDB
```bash
# Check IAM role permissions
aws iam get-role-policy \
  --role-name budgetbuddy-backend-production-ecs-task-role \
  --policy-name DynamoDBAccess
```

## Next Steps

1. **Review Architecture**: See ARCHITECTURE.md
2. **Read Runbook**: See OPERATIONAL_RUNBOOK.md
3. **Setup Alerts**: Configure CloudWatch alarms
4. **Cost Optimization**: See COST_OPTIMIZATION_GUIDE.md
5. **Security Hardening**: Review security configurations

## Support

- **Documentation**: See README.md
- **Issues**: Create GitHub issue
- **Email**: ops@budgetbuddy.com
