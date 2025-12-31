#!/bin/bash
set -e
if [ $# -lt 1 ]; then
	echo "❌ need either test, install or verify as argument"
	exit 1
fi

if [[ $1 != "test" && $1 != "install" && $1 != "verify" ]]; then
	echo "❌ need either test, install or verify as argument"
	exit 1
fi

echo "🚀 Starting LocalStack for tests..."
docker-compose up -d localstack-test redis-test

echo "⏳ Waiting for LocalStack to be ready..."
max_attempts=30
attempt=0
while [ $attempt -lt $max_attempts ]; do
    if curl -f http://localhost:4567/_localstack/health > /dev/null 2>&1; then
        echo "✅ LocalStack is ready!"
        break
    fi
    attempt=$((attempt + 1))
    echo "   Waiting... ($attempt/$max_attempts)"
    sleep 1
done

if [ $attempt -eq $max_attempts ]; then
    echo "❌ LocalStack failed to start after $max_attempts seconds"
    exit 1
fi

echo "🧪 Running tests..."
mvn  "$@"

echo "🛑 Stopping test infrastructure..."
docker-compose stop localstack-test redis-test

echo "✅ Tests complete!"
