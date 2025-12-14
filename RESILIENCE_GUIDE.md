# System Resilience Guide

This guide explains how the system handles service restarts, DNS caching, and connection failures.

## Problem: DNS Cache Corruption

### Root Cause
When services (LocalStack, Redis) restart, Java's DNS cache can become corrupted:
1. Backend tries to connect to `localstack:4566`
2. LocalStack is down → DNS lookup fails
3. Java caches the **failure** (default TTL: forever for positive, 10s for negative)
4. LocalStack comes back up
5. Backend still uses cached failure → connection errors persist

### Why It Happens
- Java's default DNS cache TTL is `-1` (cache forever) for successful lookups
- Failed lookups are cached for 10 seconds by default
- When a service restarts during a lookup, the failure is cached
- Even after the service recovers, the cached failure prevents reconnection

## Solution: Multi-Layer Resilience

### 1. DNS Cache TTL Configuration (`DnsCacheConfig`)
- **Positive lookups**: 3600 seconds (1 hour, configurable via `app.dns.cache.ttl-seconds`)
  - **Purpose**: Keep cached addresses available during DNS server outages
  - **Benefit**: System continues operating using cached addresses when DNS is unavailable
- **Negative lookups**: 1 second (configurable via `app.dns.cache.negative-ttl-seconds`)
  - **Purpose**: Don't cache DNS lookup failures
  - **Benefit**: Quick recovery when DNS server or services are restored
- **Result**: 
  - Cached addresses persist during DNS outages (resilience)
  - Errors don't get cached, allowing quick retry when DNS/server recovers

### 2. Connection Retry Logic (`DynamoDBConfig`)
- **Max retries**: 3 attempts (configurable via `app.aws.dynamodb.retry.max-attempts`)
- **Backoff strategy**: Exponential backoff (100ms → 200ms → 400ms, max 2s)
- **Result**: Transient failures are automatically retried with increasing delays

### 3. DNS Cache Management Endpoint (`SystemManagementController`)
- **Endpoint**: `POST /api/system/dns/clear` (admin only)
- **Purpose**: Clear DNS cache without restarting the backend
- **Use case**: When services restart and DNS cache is corrupted
- **Security**: Requires `ADMIN` role

### 4. Service Startup Order (`docker-compose.yml`)
- **LocalStack** → starts first, must be healthy
- **Redis** → starts second, must be healthy
- **Backend** → starts last, depends on both being healthy
- **Result**: Backend never starts before dependencies are ready

### 5. Auto-Restart Policy
- **All services**: `restart: unless-stopped`
- **Result**: Services automatically restart on failure

## Fixed Ports

All ports are fixed and documented:
- **LocalStack**: `4566` (must not change)
- **Redis**: `6379` (must not change)
- **Backend**: `8080` (must not change)

## Usage

### Normal Operation
Services start in the correct order automatically:
```bash
docker-compose up -d
```

### When Services Restart
1. **Automatic recovery**: 
   - Failed DNS lookups expire in 1 second (negative TTL)
   - System retries quickly when DNS/server is restored
2. **Retry logic**: DynamoDB client automatically retries failed connections
3. **DNS outage resilience**: 
   - Cached addresses remain available for 1 hour (positive TTL)
   - System continues operating using cached addresses during DNS outages
4. **Manual intervention** (if needed): Clear DNS cache via API:
   ```bash
   curl -X POST http://localhost:8080/api/system/dns/clear \
     -H "Authorization: Bearer <admin-token>"
   ```

### Service Restart Order
Always restart in this order:
1. **LocalStack** (if needed): `docker-compose restart localstack`
2. **Redis** (if needed): `docker-compose restart redis`
3. **Backend** (if needed): `docker-compose restart backend`

Or restart all at once (they'll start in correct order):
```bash
docker-compose restart
```

## Configuration

### DNS Cache TTL
```yaml
app:
  dns:
    cache:
      ttl-seconds: 3600  # Positive lookups (1 hour) - keep cached addresses during DNS outages
      negative-ttl-seconds: 1  # Failed lookups (1 second) - don't cache errors, quick recovery
```

### DynamoDB Retry
```yaml
app:
  aws:
    dynamodb:
      retry:
        max-attempts: 3  # Max retry attempts
      timeout-seconds: 10  # Connection timeout
```

## Monitoring

### Health Checks
- **Backend**: `http://localhost:8080/actuator/health`
- **LocalStack**: `http://localhost:4566/_localstack/health`
- **Redis**: `docker exec budgetbuddy-redis redis-cli ping`

### DNS Cache Status
Check logs for DNS cache configuration:
```
DNS cache configured: TTL=10s, Negative TTL=5s
```

## Troubleshooting

### Issue: 401 errors after service restart
**Cause**: DNS cache corruption or service not ready
**Solution**: 
1. Wait 1 second (negative DNS cache TTL) - failed lookups expire quickly
2. Or clear DNS cache: `POST /api/system/dns/clear`
3. Check service health: `docker ps` and service logs

### Issue: DNS server unavailable
**Cause**: DNS server down or network issue
**Solution**: 
- System continues using cached addresses (1 hour TTL)
- When DNS is restored, failed lookups expire in 1 second and system recovers automatically

### Issue: Connection refused errors
**Cause**: Service not ready
**Solution**: Check service health and startup order

### Issue: Persistent connection failures
**Cause**: Service not running or network issue
**Solution**: 
1. Check service status: `docker ps`
2. Check service logs: `docker logs <service-name>`
3. Verify network: `docker network inspect budgetbuddy-network`

## Security

- **DNS cache management**: Requires `ADMIN` role
- **System endpoints**: Protected by Spring Security
- **Ports**: Fixed to prevent accidental exposure

## Best Practices

1. **Always use health checks**: Services wait for dependencies to be healthy
2. **Monitor DNS cache**: Log DNS cache configuration on startup
3. **Use retry logic**: Let the system handle transient failures automatically
4. **Avoid manual DNS cache clearing**: Only use when absolutely necessary
5. **Restart in order**: LocalStack → Redis → Backend

