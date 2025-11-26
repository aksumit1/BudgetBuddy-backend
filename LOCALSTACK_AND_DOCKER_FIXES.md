# LocalStack and Docker Compose Fixes

## 🚨 Issues Fixed

### 1. LocalStack tmp Directory Conflict ✅ **FIXED**

**Problem**: LocalStack was failing with error:
```
OSError: [Errno 16] Device or resource busy: '/tmp/localstack'
```

**Root Cause**: 
- LocalStack was trying to clear `/tmp/localstack` directory
- The directory was mounted as a volume, causing a conflict
- `/tmp` directory in containers can have permission and mounting issues

**Solution**: 
- Changed `DATA_DIR` from `/tmp/localstack/data` to `/var/lib/localstack/data`
- Updated volume mount from `/tmp/localstack` to `/var/lib/localstack`
- Removed deprecated `DATA_DIR` and `TMPDIR` environment variables
- Added `PERSISTENCE=1` for data persistence
- Improved health check endpoint to `/_localstack/health`

---

### 2. Docker Build Java Version Mismatch ✅ **FIXED**

**Problem**: Docker build failed with:
```
Fatal error compiling: error: release version 25 not supported
```

**Root Cause**: 
- Project uses Java 25, but Dockerfile used JDK 21
- Java 25 Docker images may not be available yet

**Solution**: 
- Created `Dockerfile.local` that uses Java 21 for local development
- Temporarily modifies `pom.xml` during build to use Java 21
- Builds successfully with Java 21
- Runs with JRE 21

---

### 3. Duplicate YAML Keys in application.yml ✅ **FIXED**

**Problem**: Application failed to start with:
```
found duplicate key app
```

**Root Cause**: 
- Multiple `app:` keys in `application.yml` (lines 24, 158, 187)
- YAML doesn't allow duplicate top-level keys

**Solution**: 
- Merged all `app:` sections into a single section
- Consolidated `features`, `aws.appconfig`, and `aws.secrets-manager` under main `app:` section

---

### 4. Duplicate CloudWatchService Bean ✅ **FIXED**

**Problem**: Application failed to start with:
```
ConflictingBeanDefinitionException: Annotation-specified bean name 'cloudWatchService'
```

**Root Cause**: 
- Two classes with same name: `CloudWatchService`
  - `com.budgetbuddy.service.aws.CloudWatchService`
  - `com.budgetbuddy.aws.cloudwatch.CloudWatchService`

**Solution**: 
- Merged both classes into `com.budgetbuddy.service.aws.CloudWatchService`
- Added all methods from both classes
- Updated imports in `AWSMonitoringController`
- Deleted duplicate class

---

### 5. Duplicate CloudWatchClient Bean ✅ **FIXED**

**Problem**: Application failed to start with:
```
BeanDefinitionOverrideException: Invalid bean definition with name 'cloudWatchClient'
```

**Root Cause**: 
- `cloudWatchClient` bean defined in both:
  - `AwsConfig.class`
  - `AwsServicesConfig.class`

**Solution**: 
- Removed duplicate from `AwsConfig`
- Kept the one in `AwsServicesConfig` (better credential handling)

---

### 6. Duplicate LocaleResolver Bean ✅ **FIXED**

**Problem**: Application failed to start with:
```
BeanDefinitionOverrideException: Invalid bean definition with name 'localeResolver'
```

**Root Cause**: 
- `localeResolver` bean defined in both:
  - `LocaleConfig.class`
  - `InternationalizationConfig.class`

**Solution**: 
- Deleted `LocaleConfig.class` (duplicate)
- Kept `InternationalizationConfig.class` (more features)

---

### 7. Duplicate MessageSource Bean ✅ **FIXED**

**Problem**: Application failed to start with:
```
BeanDefinitionOverrideException: Invalid bean definition with name 'messageSource'
```

**Root Cause**: 
- `messageSource` bean defined in both:
  - `MessageSourceConfig.class`
  - `InternationalizationConfig.class`

**Solution**: 
- Deleted `MessageSourceConfig.class` (duplicate)
- Kept `InternationalizationConfig.class` (already has messageSource)

---

## ✅ Final Status

### LocalStack
- ✅ Starts successfully
- ✅ Health check passes
- ✅ All services available (DynamoDB, S3, Secrets Manager, CloudWatch, IAM, STS)

### Backend
- ✅ Builds successfully
- ✅ All duplicate beans resolved
- ✅ Application starts
- ✅ Ready for local testing

---

## 📋 Usage

```bash
cd BudgetBuddy-Backend

# Start all services
docker-compose up -d

# Check LocalStack health
curl http://localhost:4566/_localstack/health

# Check backend health (wait 30-60 seconds for startup)
curl http://localhost:8080/actuator/health

# View logs
docker-compose logs -f backend

# Stop services
docker-compose down
```

---

## 🔧 Files Modified

1. **docker-compose.yml**:
   - Fixed LocalStack configuration
   - Updated volume mounts
   - Improved health checks

2. **Dockerfile.local** (new):
   - Uses Java 21 for local development
   - Modifies pom.xml during build

3. **application.yml**:
   - Fixed duplicate `app:` keys
   - Merged all app configuration sections

4. **CloudWatchService.java**:
   - Merged duplicate classes
   - Added all methods from both implementations

5. **AwsConfig.java**:
   - Removed duplicate `cloudWatchClient` bean

6. **LocaleConfig.java** (deleted):
   - Removed duplicate locale configuration

7. **MessageSourceConfig.java** (deleted):
   - Removed duplicate message source configuration

---

**Status**: ✅ **ALL ISSUES FIXED** - LocalStack and Backend now work correctly

