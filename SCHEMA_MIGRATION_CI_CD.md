# Schema Migration and Account Setup via CI/CD

## Overview

This document describes how DynamoDB schema management and account setup are handled via CI/CD pipelines.

---

## Current Implementation Status

### ✅ What's Enabled via CI/CD

1. **Infrastructure Deployment** (`infrastructure-deploy.yml`)
   - ✅ DynamoDB tables deployment via CloudFormation
   - ✅ Automatic table creation when infrastructure changes
   - ✅ Table validation after deployment
   - ✅ Environment-specific deployments (staging/production)

2. **Schema Migration Script** (`schema-migration.sh`)
   - ✅ Automated schema deployment via CloudFormation
   - ✅ Table validation and health checks
   - ✅ Support for schema updates (adding indexes, attributes)
   - ✅ Environment-aware deployments

3. **Backend CI/CD Integration** (`backend-ci-cd.yml`)
   - ✅ Schema deployment before application deployment
   - ✅ Automatic schema migration when DynamoDB template changes
   - ✅ Integration with infrastructure deployment workflow

### ⚠️ What's NOT Fully Automated

1. **Runtime Table Creation** (`DynamoDBTableManager`)
   - ⚠️ Tables are created on application startup if they don't exist
   - ⚠️ This is a fallback mechanism, not the primary deployment method
   - ⚠️ Schema changes (new indexes, attributes) require CloudFormation updates

2. **Schema Versioning**
   - ⚠️ No explicit schema version tracking
   - ⚠️ Changes are managed via CloudFormation template updates
   - ⚠️ No rollback mechanism for schema changes

3. **Data Migrations**
   - ⚠️ No automated data migration scripts
   - ⚠️ Data transformations must be handled manually or via application code

---

## How It Works

### 1. Schema Deployment Flow

```
Code Push (dynamodb.yaml changes)
    ↓
infrastructure-deploy.yml triggered
    ↓
Validate CloudFormation template
    ↓
Deploy DynamoDB tables via CloudFormation
    ↓
Wait for tables to be ACTIVE
    ↓
Validate all tables exist and are active
    ↓
✅ Schema deployment complete
```

### 2. Backend CI/CD Integration

```
Backend Code Push
    ↓
Run Tests
    ↓
Build Application
    ↓
Deploy DynamoDB Schema (if template changed)
    ↓
Ensure Infrastructure Exists
    ↓
Build and Push Docker Image
    ↓
Deploy to ECS
    ↓
✅ Deployment complete
```

### 3. Schema Update Process

When you need to update a table schema (e.g., add a new index):

1. **Update CloudFormation Template** (`dynamodb.yaml`)
   - Add new Global Secondary Index (GSI)
   - Add new attribute definitions
   - Update table configuration

2. **Commit and Push**
   - Changes trigger `infrastructure-deploy.yml`
   - CloudFormation updates the table automatically
   - New indexes are created without downtime

3. **Validation**
   - CI/CD validates tables are ACTIVE
   - Application can use new indexes immediately

---

## Files and Configuration

### CloudFormation Template
- **Location**: `infrastructure/cloudformation/dynamodb.yaml`
- **Purpose**: Defines all DynamoDB tables, indexes, and configurations
- **Managed Tables**:
  - Users
  - Accounts
  - Transactions
  - Budgets
  - Goals
  - AuditLogs
  - RateLimit
  - DDoSProtection
  - DeviceAttestation

### Schema Migration Script
- **Location**: `infrastructure/scripts/schema-migration.sh`
- **Purpose**: Automated schema deployment and validation
- **Features**:
  - CloudFormation template validation
  - Stack creation/update
  - Table health checks
  - Environment-aware deployment

### CI/CD Workflows
- **Infrastructure**: `.github/workflows/infrastructure-deploy.yml`
- **Backend**: `.github/workflows/backend-ci-cd.yml`
- **Triggers**: 
  - Push to `main`/`develop` when infrastructure files change
  - Manual workflow dispatch
  - Automatic trigger when infrastructure is missing

---

## Best Practices

### 1. Schema Changes
- ✅ Always update `dynamodb.yaml` for schema changes
- ✅ Test changes in staging first
- ✅ Use CloudFormation for all table modifications
- ❌ Don't modify tables directly in AWS Console
- ❌ Don't rely on `DynamoDBTableManager` for production schema changes

### 2. Index Management
- ✅ Add new GSIs via CloudFormation
- ✅ Remove unused indexes to reduce costs
- ✅ Test query performance before deploying
- ⚠️ GSI creation can take time (monitor in CI/CD)

### 3. Environment Management
- ✅ Use separate CloudFormation stacks per environment
- ✅ Test schema changes in staging before production
- ✅ Use parameter overrides for environment-specific configs

---

## Limitations and Future Improvements

### Current Limitations
1. **No Schema Versioning**: Changes are tracked via Git, not explicit version numbers
2. **No Data Migration**: Schema changes that require data transformation must be handled manually
3. **No Rollback**: CloudFormation can rollback stack, but data changes are not reversible
4. **Runtime Fallback**: `DynamoDBTableManager` creates tables on startup, which can cause inconsistencies

### Recommended Improvements
1. **Add Schema Versioning**: Track schema versions in a metadata table
2. **Data Migration Scripts**: Create migration scripts for data transformations
3. **Schema Validation**: Add pre-deployment validation to ensure backward compatibility
4. **Disable Runtime Creation**: Remove `DynamoDBTableManager` or make it environment-aware (dev only)

---

## Summary

**✅ YES** - Account setup and schema management are enabled via CI/CD:
- DynamoDB tables are deployed via CloudFormation in CI/CD
- Schema changes trigger automatic deployments
- Tables are validated after deployment
- Environment-specific deployments are supported

**⚠️ BUT** - Some improvements are recommended:
- Add explicit schema versioning
- Create data migration scripts for complex changes
- Consider disabling runtime table creation in production
- Add schema change validation

