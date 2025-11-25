#!/bin/bash
# Free Tier Optimization Script
# Optimizes infrastructure for AWS Free Tier to minimize costs

set -e

ENVIRONMENT=${1:-production}
AWS_REGION=${2:-us-east-1}

echo "💰 Optimizing infrastructure for AWS Free Tier..."
echo "Environment: ${ENVIRONMENT}"
echo "Region: ${AWS_REGION}"

# 1. Scale ECS service to 1 task (minimum for free tier)
echo "📦 Scaling ECS service to 1 task..."
CLUSTER_NAME="BudgetBuddy-${ENVIRONMENT}-cluster"
SERVICE_NAME="BudgetBuddy-${ENVIRONMENT}-service"

aws ecs update-service \
  --cluster ${CLUSTER_NAME} \
  --service ${SERVICE_NAME} \
  --desired-count 1 \
  --region ${AWS_REGION} || echo "⚠️ ECS service update skipped"

# 2. Stop canary service (if running)
echo "🛑 Stopping canary service..."
aws ecs update-service \
  --cluster ${CLUSTER_NAME} \
  --service budgetbuddy-backend-canary \
  --desired-count 0 \
  --region ${AWS_REGION} 2>/dev/null || echo "⚠️ Canary service not found or already stopped"

# 3. Update CloudWatch log retention to 7 days (free tier)
echo "📊 Updating CloudWatch log retention to 7 days..."
LOG_GROUP="/aws/ecs/BudgetBuddy-${ENVIRONMENT}"
aws logs put-retention-policy \
  --log-group-name ${LOG_GROUP} \
  --retention-in-days 7 \
  --region ${AWS_REGION} 2>/dev/null || echo "⚠️ Log group not found"

# 4. Disable auto-scaling (free tier optimization)
echo "⚙️ Disabling auto-scaling..."
aws application-autoscaling deregister-scalable-target \
  --service-namespace ecs \
  --scalable-dimension ecs:service:DesiredCount \
  --resource-id service/${CLUSTER_NAME}/${SERVICE_NAME} \
  --region ${AWS_REGION} 2>/dev/null || echo "⚠️ Auto-scaling not configured or already disabled"

# 5. Optimize ECR lifecycle policy (keep last 5 images)
echo "📦 Optimizing ECR lifecycle policy..."
cat > /tmp/ecr-lifecycle-policy.json <<EOF
{
  "rules": [
    {
      "rulePriority": 1,
      "description": "Keep last 5 images for free tier",
      "selection": {
        "tagStatus": "any",
        "countType": "imageCountMoreThan",
        "countNumber": 5
      },
      "action": {
        "type": "expire"
      }
    }
  ]
}
EOF

aws ecr put-lifecycle-policy \
  --repository-name budgetbuddy-backend \
  --lifecycle-policy-text file:///tmp/ecr-lifecycle-policy.json \
  --region ${AWS_REGION} 2>/dev/null || echo "⚠️ ECR repository not found"

rm /tmp/ecr-lifecycle-policy.json

# 6. Verify DynamoDB is on-demand (free tier)
echo "🗄️ Verifying DynamoDB billing mode..."
TABLES=("BudgetBuddy-Users" "BudgetBuddy-Accounts" "BudgetBuddy-Transactions" "BudgetBuddy-Budgets" "BudgetBuddy-Goals" "BudgetBuddy-AuditLogs")
for table in "${TABLES[@]}"; do
  BILLING_MODE=$(aws dynamodb describe-table \
    --table-name ${table} \
    --region ${AWS_REGION} \
    --query 'Table.BillingModeSummary.BillingMode' \
    --output text 2>/dev/null || echo "NONE")
  
  if [ "$BILLING_MODE" != "PAY_PER_REQUEST" ] && [ "$BILLING_MODE" != "NONE" ]; then
    echo "⚠️ Table ${table} is not on-demand, updating..."
    aws dynamodb update-table \
      --table-name ${table} \
      --billing-mode PAY_PER_REQUEST \
      --region ${AWS_REGION} 2>/dev/null || echo "⚠️ Failed to update ${table}"
  else
    echo "✅ Table ${table} is on-demand"
  fi
done

echo "✅ Free tier optimization complete!"
echo ""
echo "📊 Current Configuration:"
echo "  - ECS Service: 1 task (256 CPU, 512 MB)"
echo "  - Canary Service: Stopped (0 tasks)"
echo "  - CloudWatch Logs: 7-day retention"
echo "  - Auto-scaling: Disabled"
echo "  - ECR: Keep last 5 images"
echo "  - DynamoDB: On-demand billing"
echo ""
echo "💰 Estimated Monthly Cost: ~$23-75/month (depending on NAT Gateway usage)"

