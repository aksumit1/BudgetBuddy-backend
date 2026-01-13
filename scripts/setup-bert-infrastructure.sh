#!/bin/bash
# Setup Infrastructure for DistilBERT Model
# This script sets up S3 bucket, downloads model, and uploads to S3

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODEL_DIR="${PROJECT_ROOT}/models"
MODEL_FILE="${MODEL_DIR}/distilbert-base-uncased.onnx"
BUCKET_NAME="${BERT_MODEL_BUCKET:-budgetbuddy-bert-models}"
AWS_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

echo "=========================================="
echo "Setting up DistilBERT Infrastructure"
echo "=========================================="
echo ""

# Step 1: Download model locally
echo "Step 1: Downloading DistilBERT model..."
"${SCRIPT_DIR}/download-bert-model.sh" "${MODEL_DIR}"

if [ ! -f "${MODEL_FILE}" ]; then
    echo "❌ Model file not found at ${MODEL_FILE}"
    echo "   Please run download-bert-model.sh first"
    exit 1
fi

echo "✅ Model downloaded: ${MODEL_FILE}"
echo "   Size: $(du -h "${MODEL_FILE}" | cut -f1)"
echo ""

# Step 2: Check AWS credentials
if ! command -v aws &> /dev/null; then
    echo "⚠️  AWS CLI not found. Skipping S3 upload."
    echo "   Model is available locally at: ${MODEL_FILE}"
    exit 0
fi

if ! aws sts get-caller-identity &> /dev/null; then
    echo "⚠️  AWS credentials not configured. Skipping S3 upload."
    echo "   Model is available locally at: ${MODEL_FILE}"
    exit 0
fi

# Step 3: Create S3 bucket if it doesn't exist
echo "Step 2: Setting up S3 bucket..."
if aws s3 ls "s3://${BUCKET_NAME}" 2>&1 | grep -q 'NoSuchBucket'; then
    echo "Creating S3 bucket: ${BUCKET_NAME}"
    aws s3 mb "s3://${BUCKET_NAME}" --region "${AWS_REGION}"
    
    # Enable versioning
    aws s3api put-bucket-versioning \
        --bucket "${BUCKET_NAME}" \
        --versioning-configuration Status=Enabled
    
    # Set lifecycle policy (optional: delete old versions after 90 days)
    cat > /tmp/lifecycle.json << EOF
{
    "Rules": [
        {
            "Id": "DeleteOldVersions",
            "Status": "Enabled",
            "Prefix": "",
            "NoncurrentVersionExpiration": {
                "NoncurrentDays": 90
            }
        }
    ]
}
EOF
    aws s3api put-bucket-lifecycle-configuration \
        --bucket "${BUCKET_NAME}" \
        --lifecycle-configuration file:///tmp/lifecycle.json
    
    echo "✅ S3 bucket created: ${BUCKET_NAME}"
else
    echo "✅ S3 bucket already exists: ${BUCKET_NAME}"
fi
echo ""

# Step 4: Upload model to S3
echo "Step 3: Uploading model to S3..."
aws s3 cp "${MODEL_FILE}" \
    "s3://${BUCKET_NAME}/distilbert-base-uncased.onnx" \
    --region "${AWS_REGION}"

echo "✅ Model uploaded to S3: s3://${BUCKET_NAME}/distilbert-base-uncased.onnx"
echo ""

# Step 5: Set bucket policy for ECS task role access
echo "Step 4: Configuring bucket permissions..."
cat > /tmp/bucket-policy.json << EOF
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "AllowECSReadAccess",
            "Effect": "Allow",
            "Principal": {
                "Service": "ecs-tasks.amazonaws.com"
            },
            "Action": "s3:GetObject",
            "Resource": "arn:aws:s3:::${BUCKET_NAME}/*"
        },
        {
            "Sid": "AllowECSTaskRoleAccess",
            "Effect": "Allow",
            "Principal": {
                "AWS": "arn:aws:iam::*:role/*"
            },
            "Action": [
                "s3:GetObject",
                "s3:ListBucket"
            ],
            "Resource": [
                "arn:aws:s3:::${BUCKET_NAME}",
                "arn:aws:s3:::${BUCKET_NAME}/*"
            ],
            "Condition": {
                "StringLike": {
                    "aws:PrincipalArn": "arn:aws:iam::*:role/*ecs*"
                }
            }
        }
    ]
}
EOF

aws s3api put-bucket-policy \
    --bucket "${BUCKET_NAME}" \
    --policy file:///tmp/bucket-policy.json

echo "✅ Bucket permissions configured"
echo ""

# Cleanup
rm -f /tmp/lifecycle.json /tmp/bucket-policy.json

echo "=========================================="
echo "✅ Infrastructure setup complete!"
echo "=========================================="
echo ""
echo "Model location:"
echo "  Local: ${MODEL_FILE}"
echo "  S3:    s3://${BUCKET_NAME}/distilbert-base-uncased.onnx"
echo ""
echo "To use in application:"
echo "  1. Set bert.enabled=true in application.properties"
echo "  2. Set bert.model.path=models/distilbert-base-uncased.onnx (for local)"
echo "     OR configure ECS task to download from S3 on startup"
echo ""
