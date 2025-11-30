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

echo "✅ Secrets initialization completed!"

