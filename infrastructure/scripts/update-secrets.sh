#!/bin/bash
# Automated Secrets Update Script
# Updates AWS Secrets Manager secrets from GitHub Secrets or command line

set -e

REGION=${1:-us-east-1}
ENVIRONMENT=${2:-production}
SECRET_TYPE=${3:-all}  # all, plaid, stripe, jwt

echo "🔄 Updating secrets in AWS Secrets Manager..."
echo "Region: ${REGION}"
echo "Environment: ${ENVIRONMENT}"
echo "Secret Type: ${SECRET_TYPE}"

# Function to update Plaid secrets
update_plaid() {
    echo "📝 Updating Plaid secrets..."
    
    # Get values from environment variables or prompt
    if [ -n "$PLAID_CLIENT_ID" ] && [ -n "$PLAID_SECRET" ]; then
        PLAID_ENV=${PLAID_ENVIRONMENT:-sandbox}
        SECRET_JSON="{\"clientId\":\"${PLAID_CLIENT_ID}\",\"secret\":\"${PLAID_SECRET}\",\"environment\":\"${PLAID_ENV}\"}"
        
        aws secretsmanager update-secret \
            --secret-id budgetbuddy/${ENVIRONMENT}/plaid \
            --secret-string "$SECRET_JSON" \
            --region ${REGION}
        
        echo "✅ Plaid secrets updated successfully"
    else
        echo "⚠️ PLAID_CLIENT_ID and PLAID_SECRET environment variables not set"
        echo "   Please set them or run: export PLAID_CLIENT_ID=... PLAID_SECRET=..."
        exit 1
    fi
}

# Function to update Stripe secrets
update_stripe() {
    echo "📝 Updating Stripe secrets..."
    
    if [ -n "$STRIPE_SECRET_KEY" ] && [ -n "$STRIPE_PUBLISHABLE_KEY" ]; then
        SECRET_JSON="{\"secretKey\":\"${STRIPE_SECRET_KEY}\",\"publishableKey\":\"${STRIPE_PUBLISHABLE_KEY}\"}"
        
        aws secretsmanager update-secret \
            --secret-id budgetbuddy/${ENVIRONMENT}/stripe \
            --secret-string "$SECRET_JSON" \
            --region ${REGION}
        
        echo "✅ Stripe secrets updated successfully"
    else
        echo "⚠️ STRIPE_SECRET_KEY and STRIPE_PUBLISHABLE_KEY environment variables not set"
        exit 1
    fi
}

# Function to update JWT secret
update_jwt() {
    echo "📝 Updating JWT secret..."
    
    if [ -n "$JWT_SECRET" ]; then
        aws secretsmanager update-secret \
            --secret-id budgetbuddy/${ENVIRONMENT}/jwt-secret \
            --secret-string "$JWT_SECRET" \
            --region ${REGION}
        
        echo "✅ JWT secret updated successfully"
    else
        echo "⚠️ JWT_SECRET environment variable not set"
        echo "   Generating new JWT secret..."
        NEW_JWT=$(openssl rand -base64 32)
        aws secretsmanager update-secret \
            --secret-id budgetbuddy/${ENVIRONMENT}/jwt-secret \
            --secret-string "$NEW_JWT" \
            --region ${REGION}
        echo "✅ New JWT secret generated and updated"
    fi
}

# Main update logic
case $SECRET_TYPE in
    plaid)
        update_plaid
        ;;
    stripe)
        update_stripe
        ;;
    jwt)
        update_jwt
        ;;
    all)
        update_jwt
        update_plaid
        update_stripe
        ;;
    *)
        echo "❌ Invalid secret type: $SECRET_TYPE"
        echo "   Valid types: all, plaid, stripe, jwt"
        exit 1
        ;;
esac

echo "✅ Secrets update completed!"

