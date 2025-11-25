#!/bin/bash
set -e

# Apply cost allocation tags to all resources

REGION=${1:-us-east-1}
ENVIRONMENT=${2:-production}

echo "Applying cost allocation tags to all resources..."
echo "Region: ${REGION}"
echo "Environment: ${ENVIRONMENT}"

# Tags to apply
TAGS=(
    "Key=Environment,Value=${ENVIRONMENT}"
    "Key=Service,Value=budgetbuddy-backend"
    "Key=CostCenter,Value=engineering"
    "Key=Team,Value=backend"
    "Key=Application,Value=BudgetBuddy"
    "Key=ManagedBy,Value=CloudFormation"
)

# Tag ECS Cluster
CLUSTER_NAME="budgetbuddy-backend-${ENVIRONMENT}-cluster"
echo "Tagging ECS cluster: ${CLUSTER_NAME}"
aws ecs tag-resource \
  --resource-arn "arn:aws:ecs:${REGION}:$(aws sts get-caller-identity --query Account --output text):cluster/${CLUSTER_NAME}" \
  --tags "${TAGS[@]}" \
  --region ${REGION} 2>/dev/null || echo "Cluster not found or already tagged"

# Tag ECS Services
echo "Tagging ECS services..."
aws ecs list-services --cluster ${CLUSTER_NAME} --region ${REGION} | \
  jq -r '.serviceArns[]' | \
  while read serviceArn; do
    echo "Tagging service: ${serviceArn}"
    aws ecs tag-resource \
      --resource-arn "${serviceArn}" \
      --tags "${TAGS[@]}" \
      --region ${REGION} 2>/dev/null || true
  done

# Tag DynamoDB Tables
echo "Tagging DynamoDB tables..."
aws dynamodb list-tables --region ${REGION} | \
  jq -r '.TableNames[]' | \
  grep "BudgetBuddy" | \
  while read tableName; do
    echo "Tagging table: ${tableName}"
    aws dynamodb tag-resource \
      --resource-arn "arn:aws:dynamodb:${REGION}:$(aws sts get-caller-identity --query Account --output text):table/${tableName}" \
      --tags "${TAGS[@]}" \
      --region ${REGION} 2>/dev/null || true
  done

# Tag S3 Buckets
echo "Tagging S3 buckets..."
aws s3api list-buckets --region ${REGION} | \
  jq -r '.Buckets[].Name' | \
  grep "budgetbuddy" | \
  while read bucketName; do
    echo "Tagging bucket: ${bucketName}"
    aws s3api put-bucket-tagging \
      --bucket "${bucketName}" \
      --tagging "TagSet=[$(IFS=,; echo "${TAGS[*]}")]" \
      --region ${REGION} 2>/dev/null || true
  done

# Tag ALB
echo "Tagging Application Load Balancer..."
aws elbv2 describe-load-balancers --region ${REGION} | \
  jq -r '.LoadBalancers[] | select(.LoadBalancerName | contains("budgetbuddy")) | .LoadBalancerArn' | \
  while read albArn; do
    echo "Tagging ALB: ${albArn}"
    aws elbv2 add-tags \
      --resource-arns "${albArn}" \
      --tags "${TAGS[@]}" \
      --region ${REGION} 2>/dev/null || true
  done

echo "Cost allocation tags applied successfully!"

