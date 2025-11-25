#!/bin/bash
set -e

# Rollback Script for BudgetBuddy Backend
# Quickly rolls back to previous deployment

ENVIRONMENT=${1:-production}
REGION=${2:-us-east-1}
STACK_NAME="budgetbuddy-backend-${ENVIRONMENT}"

echo "=========================================="
echo "BudgetBuddy Backend - Rollback"
echo "Environment: ${ENVIRONMENT}"
echo "Region: ${REGION}"
echo "=========================================="

# Get ALB listener ARN
ALB_DNS=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBDNSName`].OutputValue' \
  --output text \
  --region ${REGION})

BLUE_TARGET_GROUP=$(aws cloudformation describe-stacks \
  --stack-name ${STACK_NAME} \
  --query 'Stacks[0].Outputs[?OutputKey==`ALBTargetGroupArn`].OutputValue' \
  --output text \
  --region ${REGION})

LISTENER_ARN=$(aws elbv2 describe-listeners \
  --load-balancer-arn $(aws elbv2 describe-load-balancers \
    --query "LoadBalancers[?DNSName=='${ALB_DNS}'].LoadBalancerArn" \
    --output text \
    --region ${REGION}) \
  --query 'Listeners[0].ListenerArn' \
  --output text \
  --region ${REGION})

echo "Switching traffic back to blue (previous version)..."

# Switch traffic back to blue
aws elbv2 modify-listener \
  --listener-arn ${LISTENER_ARN} \
  --default-actions Type=forward,TargetGroupArn=${BLUE_TARGET_GROUP} \
  --region ${REGION}

echo "Traffic switched back to blue"

# Delete green stack
echo "Cleaning up green deployment..."
aws cloudformation delete-stack \
  --stack-name ${STACK_NAME}-green \
  --region ${REGION}

echo "Rollback completed successfully!"
echo "=========================================="

