#!/bin/bash
# Docker Compose wrapper that works with both V1 (docker-compose) and V2 (docker compose)

set -e

# Try docker-compose first (V1), fall back to docker compose (V2)
if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
elif docker compose version >/dev/null 2>&1; then
    docker compose "$@"
else
    echo "Error: Neither 'docker-compose' nor 'docker compose' is available" >&2
    exit 1
fi

