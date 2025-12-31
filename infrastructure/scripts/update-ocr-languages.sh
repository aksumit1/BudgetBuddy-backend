#!/bin/bash
# Script to update Tesseract language data files in Docker image
# This ensures all required languages are available

set -e

ENVIRONMENT=${1:-staging}
AWS_REGION=${AWS_REGION:-us-east-1}

echo "🌍 Updating Tesseract language data files..."

# Get OCR repository URI
OCR_REPO_URI=$(aws cloudformation describe-stacks \
    --stack-name BudgetBuddy-OCR-Infrastructure-${ENVIRONMENT} \
    --query 'Stacks[0].Outputs[?OutputKey==`OCRImageRepositoryURI`].OutputValue' \
    --output text \
    --region ${AWS_REGION} 2>/dev/null || echo "")

if [ -z "$OCR_REPO_URI" ]; then
    echo "⚠️  OCR infrastructure not found. Running setup-ocr.sh first..."
    bash infrastructure/scripts/setup-ocr.sh ${ENVIRONMENT}
    OCR_REPO_URI=$(aws cloudformation describe-stacks \
        --stack-name BudgetBuddy-OCR-Infrastructure-${ENVIRONMENT} \
        --query 'Stacks[0].Outputs[?OutputKey==`OCRImageRepositoryURI`].OutputValue' \
        --output text \
        --region ${AWS_REGION})
fi

# List of required languages
LANGUAGES=(
    "eng"      # English
    "deu"      # German
    "fra"      # French
    "spa"      # Spanish
    "ita"      # Italian
    "por"      # Portuguese
    "rus"      # Russian
    "chi_sim"  # Chinese Simplified
    "chi_tra"  # Chinese Traditional
    "jpn"      # Japanese
    "kor"      # Korean
    "ara"      # Arabic
    "hin"      # Hindi
    "nld"      # Dutch
    "pol"      # Polish
    "tur"      # Turkish
    "vie"      # Vietnamese
    "tha"      # Thai
    "ind"      # Indonesian
    "msa"      # Malay
)

echo "📦 Building Docker image with all language data files..."

# Build image with all languages
docker build -t budgetbuddy-backend-ocr:latest \
    --platform linux/arm64 \
    -f Dockerfile .

# Verify languages in image
echo "🔍 Verifying language data files in image..."
for lang in "${LANGUAGES[@]}"; do
    if docker run --rm budgetbuddy-backend-ocr:latest \
        test -f /usr/share/tesseract-ocr/tessdata/${lang}.traineddata; then
        echo "✅ ${lang} language data found"
    else
        echo "⚠️  ${lang} language data not found"
    fi
done

# Push updated image
echo "📤 Pushing updated image to ECR..."
aws ecr get-login-password --region ${AWS_REGION} | \
    docker login --username AWS --password-stdin ${OCR_REPO_URI%%/*}

docker tag budgetbuddy-backend-ocr:latest ${OCR_REPO_URI}:latest
docker tag budgetbuddy-backend-ocr:latest ${OCR_REPO_URI}:${ENVIRONMENT}
docker push ${OCR_REPO_URI}:latest
docker push ${OCR_REPO_URI}:${ENVIRONMENT}

echo "✅ Language data files updated successfully!"

