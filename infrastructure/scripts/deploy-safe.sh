#!/bin/bash
set -e

# Safe Deployment Script for BudgetBuddy Backend
# Implements blue/green deployment with validation and rollback

ENVIRONMENT=${1:-production}
REGION=${2:-us-east-1}
IMAGE_TAG=${3:-latest}
STACK_NAME="budgetbuddy-backend-${ENVIRONMENT}"

echo "=========================================="
echo "BudgetBuddy Backend - Safe Deployment"
echo "Environment: ${ENVIRONMENT}"
echo "Region: ${REGION}"
echo "Image Tag: ${IMAGE_TAG}"
echo "=========================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get stack outputs
CLUSTER_NAME=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ECSClusterName`].OutputValue' \
  --output text \
  --region ${REGION})

BLUE_TARGET_GROUP=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBTargetGroupArn`].OutputValue' \
  --output text \
  --region ${REGION})

ALB_DNS=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBDNSName`].OutputValue' \
  --output text \
  --region ${REGION})

ECR_URI=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ECRRepositoryURI`].OutputValue' \
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

echo -e "${YELLOW}Step 1: Deploying Green Environment${NC}"
cd infrastructure/cloudformation

# Deploy green environment
aws cloudformation deploy \
  --template-file blue-green-deployment.yaml \
  --stack-name ${STACK_NAME}-green \
  --parameter-overrides \
    Environment=${ENVIRONMENT} \
    ClusterName=${CLUSTER_NAME} \
    ImageURI=${ECR_URI}:${IMAGE_TAG} \
    TaskExecutionRoleArn=${TASK_EXECUTION_ROLE} \
    TaskRoleArn=${TASK_ROLE} \
  --capabilities CAPABILITY_NAMED_IAM \
  --region ${REGION}

GREEN_TARGET_GROUP=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME}-green \
  --query 'Stacks[0].Outputs[?OutputKey==`GreenTargetGroupArn`].OutputValue' \
  --output text \
  --region ${REGION})

echo -e "${GREEN}Green environment deployed${NC}"

echo -e "${YELLOW}Step 2: Validating Green Deployment${NC}"

# Wait for green service to stabilize
echo "Waiting for green service to stabilize..."
aws ecs wait servicesStable \
  --cluster ${CLUSTER_NAME} \
  --services ${STACK_NAME}-green \
  --region ${REGION} || {
    echo -e "${RED}Green service failed to stabilize${NC}"
    exit 1
  }

# Health check validation
echo "Performing health checks..."
MAX_ATTEMPTS=12
ATTEMPT=0
HEALTHY=false

while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
    HEALTH_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://${ALB_DNS}/actuator/health || echo "000")
    
    if [ "$HEALTH_RESPONSE" = "200" ]; then
        HEALTHY=true
        break
    fi
    
    ATTEMPT=$((ATTEMPT + 1))
    echo "Health check attempt ${ATTEMPT}/${MAX_ATTEMPTS}..."
    sleep 5
done

if [ "$HEALTHY" = false ]; then
    echo -e "${RED}Health check failed. Rolling back...${NC}"
    aws cloudformation delete-stack \
      --stack-name ${STACK_NAME}-green \
      --region ${REGION}
    exit 1
fi

echo -e "${GREEN}Health checks passed${NC}"

# Smoke tests
echo -e "${YELLOW}Step 3: Running Smoke Tests${NC}"
SMOKE_TESTS=(
    "/actuator/health"
    "/api/auth/register"
)

SMOKE_FAILED=false
for endpoint in "${SMOKE_TESTS[@]}"; do
    RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" http://${ALB_DNS}${endpoint} || echo "000")
    if [ "$RESPONSE" != "200" ] && [ "$RESPONSE" != "201" ] && [ "$RESPONSE" != "400" ]; then
        echo -e "${RED}Smoke test failed for ${endpoint}: ${RESPONSE}${NC}"
        SMOKE_FAILED=true
    else
        echo -e "${GREEN}Smoke test passed for ${endpoint}${NC}"
    fi
done

if [ "$SMOKE_FAILED" = true ]; then
    echo -e "${RED}Smoke tests failed. Rolling back...${NC}"
    aws cloudformation delete-stack \
      --stack-name ${STACK_NAME}-green \
      --region ${REGION}
    exit 1
fi

echo -e "${GREEN}Smoke tests passed${NC}"

# Switch traffic to green
echo -e "${YELLOW}Step 4: Switching Traffic to Green${NC}"

# Get ALB listener ARN
LISTENER_ARN=$(aws elbv2 describe-listeners \
  --load-balancer-arn $(aws elbv2 describe-load-balancers \
    --query "LoadBalancers[?DNSName=='${ALB_DNS}'].LoadBalancerArn" \
    --output text \
    --region ${REGION}) \
  --query 'Listeners[0].ListenerArn' \
  --output text \
  --region ${REGION})

# Update listener to forward to green target group
aws elbv2 modify-listener \
  --listener-arn ${LISTENER_ARN} \
  --default-actions Type=forward,TargetGroupArn=${GREEN_TARGET_GROUP} \
  --region ${REGION}

echo -e "${GREEN}Traffic switched to green${NC}"

# Monitor for issues
echo -e "${YELLOW}Step 5: Monitoring Deployment${NC}"
echo "Monitoring for 5 minutes..."

for i in {1..10}; do
    ERROR_RATE=$(aws cloudwatch get-metric-statistics \
      --namespace AWS/ApplicationELB \
      --metric-name HTTPCode_Target_5XX_Count \
      --dimensions Name=TargetGroup,Value=${GREEN_TARGET_GROUP##*/} \
      --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
      --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
      --period 60 \
      --statistics Sum \
      --query 'Datapoints[0].Sum' \
      --output text \
      --region ${REGION} || echo "0")
    
    if [ "$ERROR_RATE" != "None" ] && [ "$ERROR_RATE" != "0" ] && [ -n "$ERROR_RATE" ]; then
        echo -e "${RED}High error rate detected: ${ERROR_RATE}. Rolling back...${NC}"
        aws elbv2 modify-listener \
          --listener-arn ${LISTENER_ARN} \
          --default-actions Type=forward,TargetGroupArn=${BLUE_TARGET_GROUP} \
          --region ${REGION}
        exit 1
    fi
    
    echo "Monitoring check ${i}/10..."
    sleep 30
done

echo -e "${GREEN}Deployment completed successfully!${NC}"
echo "=========================================="
echo "Green deployment is live"
echo "ALB DNS: ${ALB_DNS}"
echo "=========================================="

