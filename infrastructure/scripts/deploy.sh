#!/bin/bash
set -e

# BudgetBuddy Backend Deployment Script
# Enterprise-grade deployment with zero-downtime

ENVIRONMENT=${1:-production}
REGION=${2:-us-east-1}
STACK_NAME="budgetbuddy-backend-${ENVIRONMENT}"

echo "=========================================="
echo "BudgetBuddy Backend Deployment"
echo "Environment: ${ENVIRONMENT}"
echo "Region: ${REGION}"
echo "Stack Name: ${STACK_NAME}"
echo "=========================================="

# Check prerequisites
command -v aws >/dev/null 2>&1 || { echo "AWS CLI required but not installed. Aborting." >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "Docker required but not installed. Aborting." >&2; exit 1; }

# Get AWS account ID
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REPOSITORY_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/budgetbuddy-backend"

echo "Step 1: Building Docker image..."
docker build --platform linux/arm64 -t budgetbuddy-backend:latest .

echo "Step 2: Logging in to ECR..."
aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ECR_REPOSITORY_URI}

echo "Step 3: Tagging image..."
docker tag budgetbuddy-backend:latest ${ECR_REPOSITORY_URI}:latest
docker tag budgetbuddy-backend:latest ${ECR_REPOSITORY_URI}:$(git rev-parse --short HEAD)

echo "Step 4: Pushing image to ECR..."
docker push ${ECR_REPOSITORY_URI}:latest
docker push ${ECR_REPOSITORY_URI}:$(git rev-parse --short HEAD)

echo "Step 5: Deploying CloudFormation stack..."
cd infrastructure/cloudformation

# Deploy main stack
aws cloudformation deploy \
  --template-file main-stack.yaml \
  --stack-name ${STACK_NAME} \
  --parameter-overrides \
    Environment=${ENVIRONMENT} \
  --capabilities CAPABILITY_NAMED_IAM \
  --region ${REGION}

# Get stack outputs
CLUSTER_NAME=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ECSClusterName`].OutputValue' \
  --output text \
  --region ${REGION})

TARGET_GROUP_ARN=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBTargetGroupArn`].OutputValue' \
  --output text \
  --region ${REGION})

TASK_EXECUTION_ROLE=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ECSTaskExecutionRoleArn`].OutputValue' \
  --output text \
  --region ${REGION})

TASK_ROLE=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ECSTaskRoleArn`].OutputValue' \
  --output text \
  --region ${REGION})

echo "Step 6: Deploying ECS service..."
aws cloudformation deploy \
  --template-file ecs-service.yaml \
  --stack-name ${STACK_NAME}-service \
  --parameter-overrides \
    Environment=${ENVIRONMENT} \
    ClusterName=${CLUSTER_NAME} \
    TargetGroupArn=${TARGET_GROUP_ARN} \
    TaskExecutionRoleArn=${TASK_EXECUTION_ROLE} \
    TaskRoleArn=${TASK_ROLE} \
    ImageURI=${ECR_REPOSITORY_URI}:latest \
  --capabilities CAPABILITY_NAMED_IAM \
  --region ${REGION}

echo "Step 7: Waiting for service to stabilize..."
aws ecs wait servicesStable \
  --cluster ${CLUSTER_NAME} \
  --services budgetbuddy-backend \
  --region ${REGION}

echo "Step 8: Running health checks..."
ALB_DNS=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBDNSName`].OutputValue' \
  --output text \
  --region ${REGION})

HEALTH_CHECK_URL="http://${ALB_DNS}/actuator/health"
echo "Health check URL: ${HEALTH_CHECK_URL}"

for i in {1..30}; do
  if curl -f -s ${HEALTH_CHECK_URL} > /dev/null; then
    echo "✓ Health check passed!"
    break
  fi
  echo "Waiting for health check... (${i}/30)"
  sleep 10
done

echo "=========================================="
echo "Deployment completed successfully!"
echo "ALB DNS: ${ALB_DNS}"
echo "=========================================="

