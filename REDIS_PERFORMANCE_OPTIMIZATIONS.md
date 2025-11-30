# Redis Performance Optimizations

## Issues Identified and Fixed

### 1. **DNS Resolution Delays**
**Problem**: Using hostname `redis` requires DNS lookup on every connection attempt
**Fix**: 
- Configured Docker's embedded DNS server (`127.0.0.11`) for fastest resolution
- Docker Compose's internal DNS is very fast (< 1ms) but explicit configuration ensures it
- Created dedicated Docker network for better service discovery

### 2. **TCP Handshake Delays**
**Problem**: Cold connections require full TCP handshake (3-way handshake)
**Fixes**:
- **Connection Pool Pre-warming**: Set `min-idle: 2` to keep 2 connections ready
- **Connection Warmup on Startup**: `RedisConnectionWarmup` establishes connections at startup
- **TCP Keep-Alive**: Enabled to maintain connections and detect failures quickly
- **TCP_NODELAY**: Disabled Nagle's algorithm for low latency

### 3. **Connection Pool Exhaustion**
**Problem**: Only 8 connections, no pre-warming, could exhaust under load
**Fixes**:
- Increased `max-active` from 8 to 16 connections
- Set `min-idle: 2` to pre-warm pool (avoids cold start)
- Added `test-on-borrow: true` to detect stale connections
- Added `test-while-idle: true` to validate idle connections

### 4. **Slow Timeouts**
**Problem**: 2 second timeouts were too long, causing delays
**Fixes**:
- Reduced timeouts from 2000ms to 1000ms (fail fast)
- Added connection validation (`pingBeforeActivateConnection`)
- Fast fail on disconnection (`REJECT_COMMANDS`)

### 5. **No Connection Reuse**
**Problem**: Connections not being reused efficiently
**Fixes**:
- Enabled TCP keep-alive
- Connection pool with idle connection management
- Connection validation before reuse

## Configuration Changes

### Docker Compose (`docker-compose.yml`)
```yaml
networks:
  budgetbuddy-network:
    driver: bridge

services:
  backend:
    networks:
      - budgetbuddy-network
    dns:
      - 127.0.0.11  # Docker's embedded DNS (fastest)
  
  redis:
    networks:
      - budgetbuddy-network
    command: >
      redis-server
      --tcp-backlog 511
      --tcp-keepalive 300
```

### Application Configuration (`application.yml`)
```yaml
spring:
  data:
    redis:
      timeout: 1000ms  # Reduced from 2000ms
      connect-timeout: 1000ms  # Reduced from 2000ms
      lettuce:
        pool:
          max-active: 16  # Increased from 8
          min-idle: 2  # Pre-warm pool (NEW)
          max-wait: 1000ms  # Reduced from 2000ms
          test-on-borrow: true  # NEW
          test-while-idle: true  # NEW
```

### Redis Client Configuration (`RedisConfig.java`)
- `tcpNoDelay: true` - Disable Nagle's algorithm
- `keepAlive: true` - Maintain connections
- `pingBeforeActivateConnection: true` - Validate connections
- `REJECT_COMMANDS` - Fast fail on disconnection

## Expected Performance Improvements

### Before:
- Health check: 14+ seconds (on failure/slow connection)
- First connection: 2+ seconds (cold start)
- DNS resolution: Variable (could be slow)

### After:
- Health check: < 1 second (with pre-warmed connections)
- First connection: < 100ms (pre-warmed at startup)
- DNS resolution: < 1ms (Docker embedded DNS)

## Connection Pool Lifecycle

1. **Application Startup**:
   - `RedisConnectionWarmup` runs and establishes 2 connections (min-idle)
   - These connections are ready for immediate use

2. **Health Check Request**:
   - Reuses pre-warmed connection from pool
   - No TCP handshake needed
   - No DNS lookup needed (connection already established)
   - Response time: < 10ms

3. **Connection Management**:
   - Idle connections validated every 30 seconds
   - Stale connections detected and replaced
   - Pool maintains 2-16 connections as needed

## Monitoring

To verify improvements, check logs for:
```
Redis connection pool warmed up in Xms
```

If warmup takes > 100ms, investigate network/Redis server issues.

## Additional Optimizations (Future)

1. **Use Redis Sentinel** (production) - Automatic failover, better connection management
2. **Connection Pool Metrics** - Monitor active/idle connections
3. **Health Check Circuit Breaker** - Skip Redis health check if it fails repeatedly
4. **Redis Cluster** (if needed) - For high availability and scalability

