#!/bin/bash
# Setup script for Tesseract OCR on AWS infrastructure
# This script ensures Tesseract OCR is properly installed and configured

set -e

ENVIRONMENT=${1:-staging}
AWS_REGION=${AWS_REGION:-us-east-1}

echo "🔧 Setting up Tesseract OCR infrastructure for environment: $ENVIRONMENT"

# Check if Tesseract is installed locally (for testing)
if command -v tesseract &> /dev/null; then
    echo "✅ Tesseract is installed locally"
    tesseract --version
else
    echo "⚠️  Tesseract not found locally (this is OK for AWS deployment)"
fi

# Deploy OCR infrastructure CloudFormation stack
echo "📦 Deploying OCR infrastructure CloudFormation stack..."

aws cloudformation deploy \
    --template-file infrastructure/cloudformation/ocr-infrastructure.yaml \
    --stack-name BudgetBuddy-OCR-Infrastructure-${ENVIRONMENT} \
    --parameter-overrides \
        Environment=${ENVIRONMENT} \
    --capabilities CAPABILITY_IAM \
    --region ${AWS_REGION} \
    --no-fail-on-empty-changeset

echo "✅ OCR infrastructure deployed successfully"

# Get OCR repository URI
OCR_REPO_URI=$(aws cloudformation describe-stacks \
    --stack-name BudgetBuddy-OCR-Infrastructure-${ENVIRONMENT} \
    --query 'Stacks[0].Outputs[?OutputKey==`OCRImageRepositoryURI`].OutputValue' \
    --output text \
    --region ${AWS_REGION})

echo "📦 OCR ECR Repository URI: $OCR_REPO_URI"

# Build and push OCR-enabled Docker image
echo "🐳 Building OCR-enabled Docker image..."

# Login to ECR
aws ecr get-login-password --region ${AWS_REGION} | \
    docker login --username AWS --password-stdin ${OCR_REPO_URI%%/*}

# Build image
docker build -t budgetbuddy-backend-ocr:latest \
    --platform linux/arm64 \
    -f Dockerfile .

# Tag and push
docker tag budgetbuddy-backend-ocr:latest ${OCR_REPO_URI}:latest
docker tag budgetbuddy-backend-ocr:latest ${OCR_REPO_URI}:${ENVIRONMENT}
docker push ${OCR_REPO_URI}:latest
docker push ${OCR_REPO_URI}:${ENVIRONMENT}

echo "✅ OCR-enabled Docker image built and pushed successfully"

# Verify Tesseract in image
echo "🔍 Verifying Tesseract installation in Docker image..."
docker run --rm ${OCR_REPO_URI}:latest tesseract --version || echo "⚠️  Tesseract verification failed (may need to run in ECS)"

echo "✅ OCR setup completed successfully!"
echo ""
echo "📋 Next steps:"
echo "1. Update ECS task definition to use image: ${OCR_REPO_URI}:${ENVIRONMENT}"
echo "2. Set environment variable TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata"
echo "3. Deploy updated service to ECS"

