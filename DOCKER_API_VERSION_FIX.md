# Docker API Version Mismatch Fix

## Issue

Docker was returning `500 Internal Server Error` with the message:
```
request returned 500 Internal Server Error for API route and version 
http://.../v1.51/containers/.../start, check if the server supports the requested API version
```

## Root Cause

1. **Docker Client Version**: 29.1.1
2. **Docker Server Version**: 29.0.1
3. **API Version Mismatch**: Client trying to use v1.51, but server doesn't support it
4. **LocalStack Configuration**: `DOCKER_HOST=unix:///var/run/docker.sock` was causing LocalStack to use HTTP API instead of direct socket access

## Solution

### 1. Removed DOCKER_HOST Environment Variable

**Problem**: LocalStack was configured with `DOCKER_HOST=unix:///var/run/docker.sock`, which made it try to use the Docker HTTP API instead of direct socket access.

**Fix**: Removed the `DOCKER_HOST` environment variable. LocalStack will now use the mounted socket directly at `/var/run/docker.sock`.

```yaml
# Before (CAUSING ISSUE):
environment:
  - DOCKER_HOST=unix:///var/run/docker.sock

# After (FIXED):
environment:
  # DOCKER_HOST removed - LocalStack uses mounted socket directly
```

### 2. Why This Works

- The Docker socket is already mounted: `/var/run/docker.sock:/var/run/docker.sock`
- LocalStack can access the socket directly without needing `DOCKER_HOST`
- Direct socket access avoids API version compatibility issues
- No HTTP API calls = no API version mismatch

## Alternative Solutions (If Issue Persists)

### Option 1: Update Docker Desktop
```bash
# Update Docker Desktop to latest version
# This ensures client and server versions match
```

### Option 2: Pin Docker API Version (Not Recommended)
If you must use `DOCKER_HOST`, you can try pinning to an older API version:
```yaml
environment:
  - DOCKER_HOST=unix:///var/run/docker.sock
  - DOCKER_API_VERSION=1.40  # Pin to older API version
```

However, this is not recommended as it may cause other compatibility issues.

### Option 3: Use Docker Context
```bash
# Switch to default context (uses /var/run/docker.sock)
docker context use default
```

## Verification

After applying the fix:

1. **Stop existing containers**:
   ```bash
   docker-compose down
   ```

2. **Start services**:
   ```bash
   docker-compose up -d
   ```

3. **Check LocalStack logs**:
   ```bash
   docker-compose logs localstack | grep -i "docker\|api\|error"
   ```

4. **Verify LocalStack is working**:
   ```bash
   curl http://localhost:4566/_localstack/health
   ```

## Summary

✅ **Removed DOCKER_HOST**: LocalStack now uses mounted socket directly  
✅ **No API Version Issues**: Direct socket access avoids HTTP API version mismatch  
✅ **Simpler Configuration**: One less environment variable to manage  

The fix ensures LocalStack communicates with Docker using the Unix socket directly, avoiding any API version compatibility issues.

