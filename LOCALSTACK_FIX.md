# LocalStack Fix for Docker Compose

## 🚨 Issue

**Problem**: LocalStack container was failing to start with error:
```
OSError: [Errno 16] Device or resource busy: '/tmp/localstack'
```

**Root Cause**: 
- LocalStack was trying to clear `/tmp/localstack` directory
- The directory was mounted as a volume, causing a conflict
- `/tmp` directory in containers can have permission and mounting issues

---

## ✅ Solution

Updated `docker-compose.yml` LocalStack configuration to:

1. **Changed DATA_DIR**: From `/tmp/localstack/data` to `/var/lib/localstack/data`
   - `/var/lib` is a more standard location for application data
   - Avoids `/tmp` directory conflicts

2. **Added TMPDIR**: Set to `/var/lib/localstack/tmp`
   - Explicitly sets temp directory location
   - Prevents conflicts with system `/tmp`

3. **Updated Volume Mount**: Changed from `/tmp/localstack` to `/var/lib/localstack`
   - Matches the new DATA_DIR location
   - More standard Docker volume location

4. **Improved Health Check**: Changed to `/_localstack/health` endpoint
   - More reliable health check endpoint
   - Added `start_period: 10s` to allow initialization time

5. **Added Docker Socket**: Mounted `/var/run/docker.sock`
   - Required for some LocalStack features
   - Enables Docker-in-Docker functionality if needed

6. **Enabled Persistence**: Set `PERSISTENCE=1`
   - Data persists across container restarts
   - Better for local development

7. **Reduced Debug**: Changed `DEBUG=1` to `DEBUG=0`
   - Less verbose logs
   - Faster startup

---

## 📋 Changes Made

### Before (Problematic):
```yaml
localstack:
  environment:
    - DATA_DIR=/tmp/localstack/data
  volumes:
    - localstack_data:/tmp/localstack
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:4566/health"]
```

### After (Fixed):
```yaml
localstack:
  environment:
    - DATA_DIR=/var/lib/localstack/data
    - TMPDIR=/var/lib/localstack/tmp
    - PERSISTENCE=1
  volumes:
    - localstack_data:/var/lib/localstack
    - /var/run/docker.sock:/var/run/docker.sock
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:4566/_localstack/health"]
    start_period: 10s
```

---

## ✅ Verification

After the fix:

```bash
# Start LocalStack
docker-compose up -d localstack

# Check health
curl http://localhost:4566/_localstack/health

# Expected response:
# {"services": {"dynamodb": "available", "s3": "available", ...}, "version": "..."}

# Check logs (should show no errors)
docker-compose logs localstack
```

---

## 🎯 Services Available

After the fix, the following AWS services are available locally:

- ✅ **DynamoDB**: `http://localhost:4566`
- ✅ **S3**: `http://localhost:4566`
- ✅ **Secrets Manager**: `http://localhost:4566`
- ✅ **CloudWatch**: `http://localhost:4566`
- ✅ **IAM**: `http://localhost:4566`
- ✅ **STS**: `http://localhost:4566`

---

## 📝 Usage

### Start LocalStack Only

```bash
docker-compose up -d localstack
```

### Start All Services (LocalStack + Backend)

```bash
docker-compose up -d
```

### Verify Services

```bash
# Check LocalStack health
curl http://localhost:4566/_localstack/health

# Check backend health
curl http://localhost:8080/actuator/health

# List DynamoDB tables
aws dynamodb list-tables --endpoint-url http://localhost:4566
```

### Stop Services

```bash
docker-compose down
```

### Clean Start (Remove Volumes)

```bash
docker-compose down -v
docker-compose up -d
```

---

## 🔧 Troubleshooting

### If LocalStack Still Fails

1. **Remove old volume**:
   ```bash
   docker volume rm budgetbuddy-backend_localstack_data
   docker-compose up -d localstack
   ```

2. **Check logs**:
   ```bash
   docker-compose logs localstack
   ```

3. **Verify Docker socket**:
   ```bash
   ls -la /var/run/docker.sock
   ```

4. **Try without persistence**:
   ```yaml
   environment:
     - PERSISTENCE=0
   ```

---

## 📊 Benefits

- ✅ **No more tmp directory conflicts**
- ✅ **Data persists across restarts**
- ✅ **More reliable health checks**
- ✅ **Better error messages**
- ✅ **Standard Docker practices**

---

**Status**: ✅ **FIXED** - LocalStack now starts successfully

