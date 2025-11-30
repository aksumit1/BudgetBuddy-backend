# Redis Connection Pool Analysis

## Current Redis Usage

### What Uses Redis:
1. **Spring Boot Actuator Health Check** - Only component using Redis
   - Performs a `PING` command to check Redis availability
   - Uses a connection from the pool for the health check

### What Does NOT Use Redis:
1. **Spring Cache** - Uses Caffeine (in-memory cache), NOT Redis
2. **DistributedLock** - Class exists but is NOT used anywhere in the codebase
3. **Rate Limiting** - Uses DynamoDB, NOT Redis
4. **DDoS Protection** - Uses in-memory cache, NOT Redis

## Connection Pool Configuration

```yaml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 8      # Maximum 8 connections
          max-idle: 8        # Keep 8 idle connections
          min-idle: 0        # No minimum idle connections
          max-wait: 2000ms   # Wait max 2 seconds for a connection
```

## Root Cause of 14+ Second Delays

### Most Likely Causes:

1. **Network Connectivity Issues** (Most Likely)
   - Redis server slow to respond
   - Network latency between backend and Redis
   - Intermittent connectivity issues
   - Docker network delays

2. **Default Lettuce Retry Behavior**
   - Lettuce retries failed connections by default
   - Multiple retry attempts with exponential backoff
   - Can accumulate to 14+ seconds

3. **Connection Establishment Delays**
   - Slow TCP handshake
   - DNS resolution delays
   - SSL/TLS negotiation (if enabled)

4. **Health Check Retry Logic**
   - Spring Boot Actuator may retry failed health checks
   - Each retry attempts a new connection

## Why Connection Pool Exhaustion is UNLIKELY

- Only 1 component uses Redis (health check)
- Health check is typically called once per request
- With 8 connections, even concurrent health checks shouldn't exhaust the pool
- **However**, if connections are slow to be released or there are connection leaks, exhaustion could occur

## Diagnostic Steps

1. **Check Redis Server Health**
   ```bash
   docker exec budgetbuddy-redis redis-cli ping
   # Should respond with "PONG" immediately
   ```

2. **Monitor Redis Connections**
   ```bash
   docker exec budgetbuddy-redis redis-cli INFO clients
   # Check connected_clients, blocked_clients
   ```

3. **Check Network Latency**
   ```bash
   # From backend container
   time redis-cli -h redis ping
   # Should complete in < 10ms
   ```

4. **Enable Redis Debug Logging**
   ```yaml
   logging:
     level:
       io.lettuce.core: DEBUG
       org.springframework.data.redis: DEBUG
   ```

## Recommendations

1. **Increase Connection Pool Size** (if needed)
   ```yaml
   spring:
     data:
       redis:
         lettuce:
           pool:
             max-active: 16  # Increase from 8
   ```

2. **Add Connection Pool Monitoring**
   - Log connection pool stats
   - Monitor active/idle connections
   - Alert on pool exhaustion

3. **Consider Disabling Redis Health Check** (if Redis is not critical)
   ```yaml
   management:
     health:
       redis:
         enabled: false  # Disable if Redis is optional
   ```

4. **Use Redis Sentinel/Cluster** (for production)
   - Better connection management
   - Automatic failover
   - Health checks built-in

## Conclusion

The 14+ second delay is **NOT** due to connection pool exhaustion from other components holding connections. It's more likely:
- Network connectivity issues
- Default retry behavior
- Slow Redis server response

The `max-wait: 2000ms` configuration we added will prevent indefinite waiting, but the root cause is likely network/connectivity related.

