# Apple App Site Association Setup Guide

This guide explains how the Apple App Site Association file is set up for local, staging, and production environments.

## Overview

The Apple App Site Association file is required for iOS Universal Links to work with Plaid OAuth redirects. It must be:
- Served at `https://domain/.well-known/apple-app-site-association`
- Served with `Content-Type: application/json`
- Accessible via HTTPS (no redirects)
- Without a file extension

## Setup by Environment

### ✅ Local Environment

**Implementation**: Served directly by the Spring Boot backend

**Endpoint**: `http://localhost:8080/.well-known/apple-app-site-association`

**Configuration**:
1. The `WellKnownController` serves the file dynamically
2. Configured in `application.yml`:
   ```yaml
   app:
     apple:
       team-id: ${APPLE_TEAM_ID:TEAM_ID}
       bundle-id: ${APPLE_BUNDLE_ID:com.budgetbuddy.app}
   ```

3. Set environment variables in `docker-compose.yml`:
   ```yaml
   environment:
     - APPLE_TEAM_ID=${APPLE_TEAM_ID:-TEAM_ID}
     - APPLE_BUNDLE_ID=${APPLE_BUNDLE_ID:-com.budgetbuddy.app}
   ```

**Testing**:
```bash
curl -H "Accept: application/json" http://localhost:8080/.well-known/apple-app-site-association
```

**Note**: For local testing with Universal Links, you may need to:
- Use a local domain (e.g., `app.localhost`) with `/etc/hosts` configuration
- Or use ngrok/tunneling service to expose localhost via HTTPS

---

### ✅ Staging Environment

**Implementation**: S3 + CloudFront

**Setup Steps**:

1. **Deploy Infrastructure**:
   ```bash
   cd BudgetBuddy-Backend
   ./infrastructure/scripts/deploy-apple-association.sh staging YOUR_TEAM_ID com.budgetbuddy.app
   ```

2. **Configure Domain**:
   - Point `app-staging.budgetbuddy.com` (or your staging domain) to CloudFront distribution
   - Or use CloudFront domain directly

3. **Verify**:
   ```bash
   curl -H "Accept: application/json" https://app-staging.budgetbuddy.com/.well-known/apple-app-site-association
   ```

**CloudFormation Stack**: `budgetbuddy-static-assets-staging`

**Resources Created**:
- S3 bucket: `budgetbuddy-static-assets-staging`
- CloudFront distribution
- IAM role for updates

---

### ✅ Production Environment

**Implementation**: S3 + CloudFront

**Setup Steps**:

1. **Deploy Infrastructure**:
   ```bash
   cd BudgetBuddy-Backend
   ./infrastructure/scripts/deploy-apple-association.sh production YOUR_TEAM_ID com.budgetbuddy.app
   ```

2. **Configure Domain**:
   - Point `app.budgetbuddy.com` to CloudFront distribution
   - Ensure SSL certificate is configured

3. **Verify**:
   ```bash
   curl -H "Accept: application/json" https://app.budgetbuddy.com/.well-known/apple-app-site-association
   ```

**CloudFormation Stack**: `budgetbuddy-static-assets-production`

**Resources Created**:
- S3 bucket: `budgetbuddy-static-assets-production`
- CloudFront distribution
- IAM role for updates

---

## Configuration

### Apple Team ID

Get your Apple Developer Team ID:
1. Log in to [Apple Developer](https://developer.apple.com/)
2. Go to **Membership**
3. Find your **Team ID** (e.g., `ABC123DEF4`)

### Bundle ID

The bundle ID is: `com.budgetbuddy.app`

### File Content

The file is automatically generated with this structure:
```json
{
  "applinks": {
    "details": [
      {
        "appIDs": ["TEAM_ID.com.budgetbuddy.app"],
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
```

---

## Updating the File

### Local Environment

The file is served dynamically, so updates to `application.yml` or environment variables will take effect after restarting the backend.

### Staging/Production

Update the file in S3:
```bash
# Staging
./infrastructure/scripts/deploy-apple-association.sh staging YOUR_TEAM_ID com.budgetbuddy.app

# Production
./infrastructure/scripts/deploy-apple-association.sh production YOUR_TEAM_ID com.budgetbuddy.app
```

**Note**: After updating, invalidate CloudFront cache:
```bash
DIST_ID=$(aws cloudformation describe-stacks \
    --stack-name budgetbuddy-static-assets-production \
    --query 'Stacks[0].Outputs[?OutputKey==`DistributionId`].OutputValue' \
    --output text)

aws cloudfront create-invalidation \
    --distribution-id "${DIST_ID}" \
    --paths "/.well-known/apple-app-site-association"
```

---

## Verification

### 1. Check File Accessibility

```bash
# Local
curl -I http://localhost:8080/.well-known/apple-app-site-association

# Staging
curl -I https://app-staging.budgetbuddy.com/.well-known/apple-app-site-association

# Production
curl -I https://app.budgetbuddy.com/.well-known/apple-app-site-association
```

**Expected Response**:
- Status: `200 OK`
- Content-Type: `application/json`
- No redirects

### 2. Validate Content

```bash
curl https://app.budgetbuddy.com/.well-known/apple-app-site-association | jq .
```

### 3. Test Universal Links

1. Send yourself an email with link: `https://app.budgetbuddy.com/plaid/callback?test=1`
2. Open link on iOS device with app installed
3. Should open in app (not Safari) if configured correctly

### 4. Apple Validator

Use Apple's Universal Links validator:
- [Branch.io Universal Links Validator](https://branch.io/resources/aasa-validator/)
- Or test directly on device

---

## Troubleshooting

### File Not Accessible

**Issue**: 404 Not Found
- ✅ Check file exists in S3 (staging/production)
- ✅ Check CloudFront distribution is active
- ✅ Check domain DNS configuration
- ✅ Check security group/firewall rules

### Wrong Content-Type

**Issue**: File served with wrong MIME type
- ✅ Verify S3 object has `Content-Type: application/json`
- ✅ Check CloudFront cache behavior
- ✅ Verify backend controller sets correct header

### Universal Links Not Working

**Issue**: Links open in Safari instead of app
- ✅ Verify associated domains in `Info.plist`
- ✅ Check Team ID and Bundle ID match
- ✅ Verify file is accessible via HTTPS
- ✅ Test on real device (not simulator)
- ✅ Check Apple App Site Association file format

### CloudFront Cache Issues

**Issue**: Changes not reflected
- ✅ Invalidate CloudFront cache
- ✅ Wait 5-10 minutes for propagation
- ✅ Check CloudFront distribution status

---

## Security Considerations

1. **Public Access**: The file must be publicly accessible (no authentication)
2. **HTTPS Only**: Must be served over HTTPS in production
3. **No Redirects**: File must be directly accessible (no 301/302 redirects)
4. **Correct Content-Type**: Must be `application/json`, not `text/plain`

---

## Cost Optimization

- **S3**: Minimal cost (small file, infrequent access)
- **CloudFront**: Free tier includes 1TB data transfer/month
- **Cache**: File is cached for 1 hour to reduce requests

---

## References

- [Apple: Universal Links](https://developer.apple.com/documentation/xcode/supporting-universal-links-in-your-app)
- [Plaid: iOS OAuth Setup](https://plaid.com/docs/link/ios/)
- [Apple App Site Association File Format](https://developer.apple.com/documentation/xcode/supporting-universal-links-in-your-app#create-an-apple-app-site-association-file)

