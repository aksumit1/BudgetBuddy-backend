#!/bin/bash
# Initialize AWS Secrets Manager secrets in LocalStack for local development
# This script runs as a LocalStack init hook (in /etc/localstack/init/ready.d/)
# It creates the secrets that the application expects to exist

set -e

LOCALSTACK_ENDPOINT="http://localhost:4566"
AWS_REGION="${AWS_REGION:-us-east-1}"

echo "🔐 Initializing secrets in LocalStack..."
echo "Endpoint: ${LOCALSTACK_ENDPOINT}"
echo "Region: ${AWS_REGION}"

# Set AWS credentials for LocalStack
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION="${AWS_REGION}"

# Function to create or update secret
create_or_update_secret() {
    local secret_name=$1
    local secret_string=$2
    local description=$3
    
    echo "Creating/updating secret: ${secret_name}"
    
    # Try to create secret, if it exists, update it
    if aws secretsmanager create-secret \
        --endpoint-url "${LOCALSTACK_ENDPOINT}" \
        --region "${AWS_REGION}" \
        --name "${secret_name}" \
        --description "${description}" \
        --secret-string "${secret_string}" \
        2>&1; then
        echo "✅ Secret ${secret_name} created successfully"
    elif aws secretsmanager update-secret \
        --endpoint-url "${LOCALSTACK_ENDPOINT}" \
        --region "${AWS_REGION}" \
        --secret-id "${secret_name}" \
        --secret-string "${secret_string}" \
        2>&1; then
        echo "✅ Secret ${secret_name} updated successfully"
    else
        echo "⚠️ Failed to create/update secret ${secret_name} (this is non-fatal - will use env vars)"
        return 0  # Don't fail - allow fallback to env vars
    fi
}

# 1. JWT Secret
# Use JWT_SECRET from environment or generate a default one
JWT_SECRET_VALUE="${JWT_SECRET:-test-secret-change-in-production-this-must-be-at-least-64-characters-long-for-hs512-algorithm-to-work-properly}"
create_or_update_secret \
    "budgetbuddy/jwt-secret" \
    "${JWT_SECRET_VALUE}" \
    "JWT signing secret for authentication tokens"

# 2. Plaid Secrets (if credentials are provided)
if [ -n "${PLAID_CLIENT_ID}" ] && [ -n "${PLAID_SECRET}" ]; then
    PLAID_ENV="${PLAID_ENVIRONMENT:-sandbox}"
    PLAID_JSON="{\"clientId\":\"${PLAID_CLIENT_ID}\",\"secret\":\"${PLAID_SECRET}\",\"environment\":\"${PLAID_ENV}\"}"
    create_or_update_secret \
        "budgetbuddy/plaid" \
        "${PLAID_JSON}" \
        "Plaid API credentials (clientId, secret, environment)"
else
    echo "⚠️ PLAID_CLIENT_ID and PLAID_SECRET not set - skipping Plaid secret creation"
    echo "   Plaid will use environment variables as fallback"
fi

# 3. Stripe Secrets (if credentials are provided)
if [ -n "${STRIPE_SECRET_KEY}" ]; then
    STRIPE_JSON="{\"secretKey\":\"${STRIPE_SECRET_KEY}\"}"
    if [ -n "${STRIPE_PUBLISHABLE_KEY}" ]; then
        STRIPE_JSON="{\"secretKey\":\"${STRIPE_SECRET_KEY}\",\"publishableKey\":\"${STRIPE_PUBLISHABLE_KEY}\"}"
    fi
    create_or_update_secret \
        "budgetbuddy/stripe" \
        "${STRIPE_JSON}" \
        "Stripe API credentials (secretKey, publishableKey)"
else
    echo "⚠️ STRIPE_SECRET_KEY not set - skipping Stripe secret creation"
    echo "   Stripe will use environment variables as fallback"
fi

# 4. Create S3 bucket for file storage with security best practices
S3_BUCKET="${AWS_S3_BUCKET:-budgetbuddy-storage}"
echo "📦 Creating S3 bucket: ${S3_BUCKET} with security best practices..."

# Create bucket if it doesn't exist
if ! aws s3 ls "s3://${S3_BUCKET}" \
    --endpoint-url "${LOCALSTACK_ENDPOINT}" \
    --region "${AWS_REGION}" \
    > /dev/null 2>&1; then
    if aws s3 mb "s3://${S3_BUCKET}" \
        --endpoint-url "${LOCALSTACK_ENDPOINT}" \
        --region "${AWS_REGION}" \
        2>&1; then
        echo "✅ S3 bucket ${S3_BUCKET} created successfully"
    else
        echo "⚠️ Failed to create S3 bucket ${S3_BUCKET} (this is non-fatal - bucket may be created on first use)"
        echo "✅ Secrets initialization completed!"
        exit 0
    fi
else
    echo "✅ S3 bucket ${S3_BUCKET} already exists"
fi

# Configure bucket security settings
echo "🔒 Configuring S3 bucket security settings..."

# 1. Enable versioning (for data protection and recovery)
if aws s3api put-bucket-versioning \
    --endpoint-url "${LOCALSTACK_ENDPOINT}" \
    --region "${AWS_REGION}" \
    --bucket "${S3_BUCKET}" \
    --versioning-configuration Status=Enabled \
    2>&1; then
    echo "✅ Versioning enabled"
else
    echo "⚠️ Failed to enable versioning (non-fatal)"
fi

# 2. Block public access (security best practice)
if aws s3api put-public-access-block \
    --endpoint-url "${LOCALSTACK_ENDPOINT}" \
    --region "${AWS_REGION}" \
    --bucket "${S3_BUCKET}" \
    --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
    2>&1; then
    echo "✅ Public access blocked"
else
    echo "⚠️ Failed to block public access (non-fatal)"
fi

# 3. Enable server-side encryption (AES256 - LocalStack may not support KMS)
if aws s3api put-bucket-encryption \
    --endpoint-url "${LOCALSTACK_ENDPOINT}" \
    --region "${AWS_REGION}" \
    --bucket "${S3_BUCKET}" \
    --server-side-encryption-configuration '{
        "Rules": [{
            "ApplyServerSideEncryptionByDefault": {
                "SSEAlgorithm": "AES256"
            },
            "BucketKeyEnabled": true
        }]
    }' \
    2>&1; then
    echo "✅ Server-side encryption enabled (AES256)"
else
    echo "⚠️ Failed to enable encryption (non-fatal - LocalStack may not support all features)"
fi

# 4. Configure lifecycle policy for cost optimization
LIFECYCLE_POLICY='{
    "Rules": [
        {
            "Id": "MoveToStandardIA",
            "Status": "Enabled",
            "Transitions": [
                {
                    "Days": 15,
                    "StorageClass": "STANDARD_IA"
                }
            ]
        },
        {
            "Id": "MoveToGlacier",
            "Status": "Enabled",
            "Transitions": [
                {
                    "Days": 45,
                    "StorageClass": "GLACIER"
                }
            ]
        },
        {
            "Id": "DeleteIncompleteUploads",
            "Status": "Enabled",
            "AbortIncompleteMultipartUpload": {
                "DaysAfterInitiation": 7
            }
        },
        {
            "Id": "DeleteOldVersions",
            "Status": "Enabled",
            "NoncurrentVersionExpirationInDays": 30
        }
    ]
}'

if aws s3api put-bucket-lifecycle-configuration \
    --endpoint-url "${LOCALSTACK_ENDPOINT}" \
    --region "${AWS_REGION}" \
    --bucket "${S3_BUCKET}" \
    --lifecycle-configuration "${LIFECYCLE_POLICY}" \
    2>&1; then
    echo "✅ Lifecycle policies configured (cost optimization)"
else
    echo "⚠️ Failed to configure lifecycle policies (non-fatal - LocalStack may not support all features)"
fi

echo "✅ S3 bucket configuration completed!"

echo "✅ Secrets initialization completed!"

