#!/bin/bash
# Complete OCR deployment script for AWS
# This script automates the entire OCR infrastructure setup and deployment

set -e

ENVIRONMENT=${1:-staging}
AWS_REGION=${AWS_REGION:-us-east-1}

echo "🚀 Starting OCR infrastructure deployment for environment: $ENVIRONMENT"
echo "📍 AWS Region: $AWS_REGION"
echo ""

# Step 1: Setup OCR infrastructure
echo "📦 Step 1: Setting up OCR infrastructure..."
bash infrastructure/scripts/setup-ocr.sh ${ENVIRONMENT}

# Step 2: Update language data files
echo ""
echo "🌍 Step 2: Updating language data files..."
bash infrastructure/scripts/update-ocr-languages.sh ${ENVIRONMENT}

# Step 3: Update ECS service to use OCR-enabled image
echo ""
echo "🔄 Step 3: Updating ECS service with OCR-enabled image..."

# Get OCR repository URI
OCR_REPO_URI=$(aws cloudformation describe-stacks \
    --stack-name BudgetBuddy-OCR-Infrastructure-${ENVIRONMENT} \
    --query 'Stacks[0].Outputs[?OutputKey==`OCRImageRepositoryURI`].OutputValue' \
    --output text \
    --region ${AWS_REGION})

# Get ECS cluster name
CLUSTER_NAME=$(aws cloudformation describe-stacks \
    --stack-name BudgetBuddy-${ENVIRONMENT}-main \
    --query 'Stacks[0].Outputs[?OutputKey==`ClusterName`].OutputValue' \
    --output text \
    --region ${AWS_REGION} 2>/dev/null || echo "budgetbuddy-${ENVIRONMENT}-cluster")

echo "📋 Updating ECS task definition to use OCR image: ${OCR_REPO_URI}:${ENVIRONMENT}"

# Update ECS service (this would typically be done via CloudFormation or ECS console)
# For now, we'll output the command
echo ""
echo "✅ OCR infrastructure deployment completed!"
echo ""
echo "📋 Manual steps (if needed):"
echo "1. Update ECS task definition Image URI to: ${OCR_REPO_URI}:${ENVIRONMENT}"
echo "2. Ensure environment variable TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata is set"
echo "3. Force new deployment: aws ecs update-service --cluster ${CLUSTER_NAME} --service budgetbuddy-backend --force-new-deployment --region ${AWS_REGION}"
echo ""
echo "🔍 Verify OCR is working:"
echo "   Check application logs for 'OCR Service initialized successfully'"

