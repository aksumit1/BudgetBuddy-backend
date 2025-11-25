#!/bin/bash
# DynamoDB Schema Migration Script
# Handles schema updates for DynamoDB tables via CloudFormation
# This script is called by CI/CD pipeline when schema changes are detected

set -e

ENVIRONMENT=${1:-staging}
STACK_PREFIX="BudgetBuddy"
AWS_REGION=${AWS_REGION:-us-east-1}
STACK_NAME="${STACK_PREFIX}-${ENVIRONMENT}-dynamodb"

echo "🚀 Starting DynamoDB schema migration for environment: ${ENVIRONMENT}"

# Check if CloudFormation template exists
if [ ! -f "infrastructure/cloudformation/dynamodb.yaml" ]; then
    echo "❌ DynamoDB CloudFormation template not found"
    exit 1
fi

# Validate CloudFormation template
echo "📋 Validating CloudFormation template..."
aws cloudformation validate-template \
    --template-body file://infrastructure/cloudformation/dynamodb.yaml \
    --region ${AWS_REGION} || {
    echo "❌ CloudFormation template validation failed"
    exit 1
}

# Check if stack exists
if aws cloudformation describe-stacks --stack-name ${STACK_NAME} --region ${AWS_REGION} &>/dev/null; then
    echo "📦 Stack ${STACK_NAME} exists, updating..."
    OPERATION="update"
else
    echo "🆕 Stack ${STACK_NAME} does not exist, creating..."
    OPERATION="create"
fi

# Deploy/Update stack
echo "🚀 Deploying DynamoDB tables..."
aws cloudformation deploy \
    --template-file infrastructure/cloudformation/dynamodb.yaml \
    --stack-name ${STACK_NAME} \
    --parameter-overrides \
        Environment=${ENVIRONMENT} \
        TablePrefix=${STACK_PREFIX} \
    --capabilities CAPABILITY_IAM \
    --region ${AWS_REGION} \
    --no-fail-on-empty-changeset

if [ $? -eq 0 ]; then
    echo "✅ DynamoDB schema migration completed successfully"
    
    # Wait for tables to be active
    echo "⏳ Waiting for tables to be active..."
    TABLES=(
        "${STACK_PREFIX}-Users"
        "${STACK_PREFIX}-Accounts"
        "${STACK_PREFIX}-Transactions"
        "${STACK_PREFIX}-Budgets"
        "${STACK_PREFIX}-Goals"
        "${STACK_PREFIX}-AuditLogs"
    )
    
    for table in "${TABLES[@]}"; do
        echo "  Checking ${table}..."
        aws dynamodb wait table-exists \
            --table-name ${table} \
            --region ${AWS_REGION}
        echo "  ✅ ${table} is active"
    done
    
    echo "✅ All tables are active and ready"
else
    echo "❌ DynamoDB schema migration failed"
    exit 1
fi

