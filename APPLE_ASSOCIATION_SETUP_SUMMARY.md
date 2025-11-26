# Apple App Site Association Setup Summary

## ✅ Implementation Complete

The Apple App Site Association file is now set up for all three environments:

### 1. ✅ Local Environment
- **Endpoint**: `http://localhost:8080/.well-known/apple-app-site-association`
- **Implementation**: Spring Boot `WellKnownController` serves the file dynamically
- **Configuration**: Set `APPLE_TEAM_ID` and `APPLE_BUNDLE_ID` in `docker-compose.yml`

### 2. ✅ Staging Environment
- **Endpoint**: `https://app-staging.budgetbuddy.com/.well-known/apple-app-site-association`
- **Implementation**: S3 + CloudFront
- **Deployment**: Use `deploy-apple-association.sh` script

### 3. ✅ Production Environment
- **Endpoint**: `https://app.budgetbuddy.com/.well-known/apple-app-site-association`
- **Implementation**: S3 + CloudFront
- **Deployment**: Use `deploy-apple-association.sh` script

---

## Files Created

1. **Backend Controller**: `src/main/java/com/budgetbuddy/api/WellKnownController.java`
   - Serves the file dynamically for local development
   - Sets correct `Content-Type: application/json` header

2. **CloudFormation Template**: `infrastructure/cloudformation/s3-static-assets.yaml`
   - Creates S3 bucket for static assets
   - Creates CloudFront distribution
   - Sets up IAM roles for updates

3. **Deployment Script**: `infrastructure/scripts/deploy-apple-association.sh`
   - Automates S3 deployment
   - Generates file with correct Team ID and Bundle ID
   - Sets correct Content-Type and cache headers

4. **Documentation**: `APPLE_APP_SITE_ASSOCIATION_SETUP.md`
   - Complete setup guide
   - Troubleshooting tips
   - Verification steps

---

## Quick Start

### Local Testing

1. **Set Environment Variables** (optional, defaults work):
   ```bash
   export APPLE_TEAM_ID=YOUR_TEAM_ID
   export APPLE_BUNDLE_ID=com.budgetbuddy.app
   ```

2. **Start Backend**:
   ```bash
   docker-compose up -d backend
   ```

3. **Test Endpoint**:
   ```bash
   curl -H "Accept: application/json" http://localhost:8080/.well-known/apple-app-site-association
   ```

### Staging Deployment

```bash
cd BudgetBuddy-Backend
./infrastructure/scripts/deploy-apple-association.sh staging YOUR_TEAM_ID com.budgetbuddy.app
```

### Production Deployment

```bash
cd BudgetBuddy-Backend
./infrastructure/scripts/deploy-apple-association.sh production YOUR_TEAM_ID com.budgetbuddy.app
```

---

## Configuration

### Required Values

1. **Apple Team ID**: Get from [Apple Developer](https://developer.apple.com/account)
   - Go to **Membership** → Find **Team ID** (e.g., `ABC123DEF4`)

2. **Bundle ID**: `com.budgetbuddy.app` (already configured)

### Environment Variables

- `APPLE_TEAM_ID`: Your Apple Developer Team ID
- `APPLE_BUNDLE_ID`: iOS app bundle identifier (default: `com.budgetbuddy.app`)

---

## Verification

### Local
```bash
curl -I http://localhost:8080/.well-known/apple-app-site-association
# Should return: Content-Type: application/json
```

### Staging/Production
```bash
curl -I https://app.budgetbuddy.com/.well-known/apple-app-site-association
# Should return: Content-Type: application/json
```

### Content Validation
```bash
curl https://app.budgetbuddy.com/.well-known/apple-app-site-association | jq .
```

Expected JSON:
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

## Security Configuration

✅ **SecurityConfig.java** updated to allow public access to `/.well-known/**` paths

✅ **No authentication required** for well-known files (as per Apple requirements)

✅ **HTTPS enforced** in production via CloudFront

---

## Next Steps

1. **Get Your Apple Team ID** from Apple Developer account
2. **Update Environment Variables** with your Team ID
3. **Deploy to Staging** using the deployment script
4. **Test Universal Links** on iOS device
5. **Deploy to Production** after verification

---

## Troubleshooting

See `APPLE_APP_SITE_ASSOCIATION_SETUP.md` for detailed troubleshooting guide.

