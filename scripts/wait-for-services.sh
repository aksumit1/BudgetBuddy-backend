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

# Wait for Redis
echo "Waiting for Redis..."
for i in $(seq 1 60); do
    if docker exec budgetbuddy-redis redis-cli ping >/dev/null 2>&1; then
        echo "✅ Redis is ready"
        break
    fi
    if [ $i -eq 60 ]; then
        echo "⚠️ Redis health check timeout after 60s (continuing anyway)"
        break
    fi
    sleep 1
done

echo "Services are ready (or timeout reached)"

