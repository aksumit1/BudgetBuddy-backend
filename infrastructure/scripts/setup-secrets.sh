#!/bin/bash
set -e

# Setup AWS Secrets Manager secrets for BudgetBuddy Backend

REGION=${1:-us-east-1}
ENVIRONMENT=${2:-production}

echo "Setting up secrets in AWS Secrets Manager..."
echo "Region: ${REGION}"
echo "Environment: ${ENVIRONMENT}"

# JWT Secret
echo "Creating JWT secret..."
aws secretsmanager create-secret \
  --name budgetbuddy/jwt-secret \
  --description "JWT signing secret" \
  --secret-string "$(openssl rand -base64 32)" \
  --region ${REGION} \
  --tags Key=Environment,Value=${ENVIRONMENT} Key=Service,Value=budgetbuddy-backend \
  2>/dev/null || \
aws secretsmanager update-secret \
  --secret-id budgetbuddy/jwt-secret \
  --secret-string "$(openssl rand -base64 32)" \
  --region ${REGION}

# Plaid Client ID
echo "Creating Plaid Client ID secret..."
read -sp "Enter Plaid Client ID: " PLAID_CLIENT_ID
echo
aws secretsmanager create-secret \
  --name budgetbuddy/plaid \
  --description "Plaid API credentials" \
  --secret-string "{\"clientId\":\"${PLAID_CLIENT_ID}\"}" \
  --region ${REGION} \
  --tags Key=Environment,Value=${ENVIRONMENT} Key=Service,Value=budgetbuddy-backend \
  2>/dev/null || \
aws secretsmanager update-secret \
  --secret-id budgetbuddy/plaid \
  --secret-string "{\"clientId\":\"${PLAID_CLIENT_ID}\"}" \
  --region ${REGION}

# Plaid Secret
echo "Creating Plaid Secret..."
read -sp "Enter Plaid Secret: " PLAID_SECRET
echo
aws secretsmanager update-secret \
  --secret-id budgetbuddy/plaid \
  --secret-string "{\"clientId\":\"${PLAID_CLIENT_ID}\",\"secret\":\"${PLAID_SECRET}\"}" \
  --region ${REGION}

# Stripe Secret Key
echo "Creating Stripe Secret Key..."
read -sp "Enter Stripe Secret Key: " STRIPE_SECRET_KEY
echo
aws secretsmanager create-secret \
  --name budgetbuddy/stripe \
  --description "Stripe API credentials" \
  --secret-string "{\"secretKey\":\"${STRIPE_SECRET_KEY}\"}" \
  --region ${REGION} \
  --tags Key=Environment,Value=${ENVIRONMENT} Key=Service,Value=budgetbuddy-backend \
  2>/dev/null || \
aws secretsmanager update-secret \
  --secret-id budgetbuddy/stripe \
  --secret-string "{\"secretKey\":\"${STRIPE_SECRET_KEY}\"}" \
  --region ${REGION}

echo "Secrets setup completed!"

