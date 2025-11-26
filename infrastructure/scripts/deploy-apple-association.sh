#!/bin/bash
# Deploy Apple App Site Association file to S3 for staging/production

set -e

ENVIRONMENT="${1:-staging}"
APPLE_TEAM_ID="${2:-TEAM_ID}"
BUNDLE_ID="${3:-com.budgetbuddy.app}"
STACK_NAME="budgetbuddy-static-assets-${ENVIRONMENT}"

echo "Deploying Apple App Site Association file for environment: ${ENVIRONMENT}"
echo "Team ID: ${APPLE_TEAM_ID}"
echo "Bundle ID: ${BUNDLE_ID}"

# Check if stack exists
if ! aws cloudformation describe-stacks --stack-name "${STACK_NAME}" &>/dev/null; then
    echo "Stack ${STACK_NAME} does not exist. Creating it..."
    aws cloudformation create-stack \
        --stack-name "${STACK_NAME}" \
        --template-body file://infrastructure/cloudformation/s3-static-assets.yaml \
        --parameters \
            ParameterKey=Environment,ParameterValue="${ENVIRONMENT}" \
            ParameterKey=AppleTeamId,ParameterValue="${APPLE_TEAM_ID}" \
            ParameterKey=BundleId,ParameterValue="${BUNDLE_ID}" \
        --capabilities CAPABILITY_NAMED_IAM
    
    echo "Waiting for stack creation..."
    aws cloudformation wait stack-create-complete --stack-name "${STACK_NAME}"
    echo "Stack created successfully!"
else
    echo "Stack ${STACK_NAME} exists. Updating..."
    aws cloudformation update-stack \
        --stack-name "${STACK_NAME}" \
        --template-body file://infrastructure/cloudformation/s3-static-assets.yaml \
        --parameters \
            ParameterKey=Environment,ParameterValue="${ENVIRONMENT}" \
            ParameterKey=AppleTeamId,ParameterValue="${APPLE_TEAM_ID}" \
            ParameterKey=BundleId,ParameterValue="${BUNDLE_ID}" \
        --capabilities CAPABILITY_NAMED_IAM || echo "No updates to apply"
fi

# Get bucket name from stack output
BUCKET_NAME=$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --query 'Stacks[0].Outputs[?OutputKey==`BucketName`].OutputValue' \
    --output text)

echo "Bucket name: ${BUCKET_NAME}"

# Create apple-app-site-association file content
cat > /tmp/apple-app-site-association.json <<EOF
{
  "applinks": {
    "details": [
      {
        "appIDs": ["${APPLE_TEAM_ID}.${BUNDLE_ID}"],
        "components": [
          {
            "/": "/plaid/*",
            "comment": "Matches Plaid OAuth redirect paths starting with /plaid/"
          }
        ]
      }
    ]
  }
}
EOF

# Upload to S3 with correct Content-Type
echo "Uploading apple-app-site-association file to S3..."
aws s3 cp /tmp/apple-app-site-association.json \
    "s3://${BUCKET_NAME}/.well-known/apple-app-site-association" \
    --content-type "application/json" \
    --cache-control "public, max-age=3600" \
    --metadata-directive REPLACE

# Tag the file as public
aws s3api put-object-tagging \
    --bucket "${BUCKET_NAME}" \
    --key ".well-known/apple-app-site-association" \
    --tagging "TagSet=[{Key=Public,Value=true}]"

echo "✅ Apple App Site Association file deployed successfully!"
echo "File URL: https://${BUCKET_NAME}.s3.amazonaws.com/.well-known/apple-app-site-association"

# Get CloudFront distribution domain
DISTRIBUTION_DOMAIN=$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --query 'Stacks[0].Outputs[?OutputKey==`DistributionDomainName`].OutputValue' \
    --output text)

if [ -n "${DISTRIBUTION_DOMAIN}" ]; then
    echo "CloudFront URL: https://${DISTRIBUTION_DOMAIN}/.well-known/apple-app-site-association"
    echo ""
    echo "⚠️  Note: CloudFront cache may take a few minutes to update."
    echo "   You may need to invalidate the cache:"
    echo "   aws cloudfront create-invalidation --distribution-id <DIST_ID> --paths '/.well-known/apple-app-site-association'"
fi

# Cleanup
rm -f /tmp/apple-app-site-association.json

echo ""
echo "✅ Deployment complete!"

