#!/bin/bash
# Wait for Docker Compose services to be healthy
# Works with both docker-compose (V1) and docker compose (V2)

set -e

COMPOSE_FILE="${1:-docker-compose.yml}"
MAX_WAIT="${2:-120}"

echo "Waiting for services to be healthy..."

# Wait for LocalStack
echo "Waiting for LocalStack..."
for i in $(seq 1 $MAX_WAIT); do
    if curl -f http://localhost:4566/_localstack/health >/dev/null 2>&1; then
        echo "✅ LocalStack is ready"
        break
    fi
    if [ $i -eq $MAX_WAIT ]; then
        echo "⚠️ LocalStack health check timeout after ${MAX_WAIT}s (continuing anyway)"
        break
    fi
    sleep 1
done

# Wait for Redis (check both test and regular Redis containers)
echo "Waiting for Redis..."
REDIS_CONTAINER=""
# Check for test Redis first (used by Maven tests)
if docker ps --format "{{.Names}}" 2>/dev/null | grep -q "budgetbuddy-redis-test"; then
    REDIS_CONTAINER="budgetbuddy-redis-test"
elif docker ps --format "{{.Names}}" 2>/dev/null | grep -q "budgetbuddy-redis"; then
    REDIS_CONTAINER="budgetbuddy-redis"
fi

if [ -n "$REDIS_CONTAINER" ]; then
    for i in $(seq 1 60); do
        # Check if container is still running
        if ! docker ps --format "{{.Names}}" 2>/dev/null | grep -q "$REDIS_CONTAINER"; then
            echo "⚠️ Redis container $REDIS_CONTAINER is not running"
            break
        fi
        # Try to ping Redis - use a simple check that won't hang
        # Use redis-cli directly with a connection test
        if docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q "PONG"; then
            echo "✅ Redis is ready ($REDIS_CONTAINER)"
            break
        fi
        if [ $i -eq 60 ]; then
            echo "⚠️ Redis health check timeout after 60s (continuing anyway)"
            break
        fi
        sleep 1
    done
else
    echo "⚠️ Redis container not found (budgetbuddy-redis-test or budgetbuddy-redis), skipping Redis health check"
fi

echo "Services are ready (or timeout reached)"

