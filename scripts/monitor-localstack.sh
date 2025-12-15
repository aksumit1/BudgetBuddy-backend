#!/bin/bash
set -e

# LocalStack Health Monitor and Auto-Restart Script
# This script monitors LocalStack health and automatically restarts it if it becomes unavailable
# Run this as a background process or via cron to ensure LocalStack stays running

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$PROJECT_ROOT/docker-compose.yml"
CONTAINER_NAME="budgetbuddy-localstack"
HEALTH_CHECK_URL="http://localhost:4566/_localstack/health"
CHECK_INTERVAL=30  # Check every 30 seconds
MAX_RESTART_ATTEMPTS=3
RESTART_COOLDOWN=60  # Wait 60 seconds between restart attempts

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log_error() {
    echo -e "${RED}[$(date '+%Y-%m-%d %H:%M:%S')] ERROR: $1${NC}" >&2
}

log_warning() {
    echo -e "${YELLOW}[$(date '+%Y-%m-%d %H:%M:%S')] WARNING: $1${NC}"
}

log_success() {
    echo -e "${GREEN}[$(date '+%Y-%m-%d %H:%M:%S')] $1${NC}"
}

check_localstack_health() {
    # Check if container exists and is running
    if ! docker ps --format "{{.Names}}" | grep -q "^${CONTAINER_NAME}$"; then
        return 1  # Container not running
    fi
    
    # Check health endpoint
    if curl -f -s "${HEALTH_CHECK_URL}" > /dev/null 2>&1; then
        return 0  # Healthy
    fi
    
    return 1  # Unhealthy
}

restart_localstack() {
    log_warning "Attempting to restart LocalStack..."
    cd "$PROJECT_ROOT"
    
    # Try to restart using docker-compose
    if docker-compose restart localstack > /dev/null 2>&1; then
        log_success "LocalStack restart command sent"
        
        # Wait for LocalStack to become healthy
        log "Waiting for LocalStack to become healthy..."
        local wait_count=0
        local max_wait=60  # Wait up to 60 seconds
        
        while [ $wait_count -lt $max_wait ]; do
            sleep 2
            if check_localstack_health; then
                log_success "LocalStack is now healthy"
                return 0
            fi
            wait_count=$((wait_count + 2))
        done
        
        log_error "LocalStack did not become healthy after restart"
        return 1
    else
        # If restart fails, try starting it
        log_warning "Restart failed, attempting to start LocalStack..."
        if docker-compose up -d localstack > /dev/null 2>&1; then
            log_success "LocalStack start command sent"
            sleep 5
            if check_localstack_health; then
                log_success "LocalStack is now healthy"
                return 0
            fi
        fi
        
        log_error "Failed to restart/start LocalStack"
        return 1
    fi
}

# Main monitoring loop
monitor_loop() {
    local consecutive_failures=0
    local last_restart_time=0
    
    log_success "LocalStack monitor started (checking every ${CHECK_INTERVAL}s)"
    
    while true; do
        if check_localstack_health; then
            if [ $consecutive_failures -gt 0 ]; then
                log_success "LocalStack recovered (was down for $((consecutive_failures * CHECK_INTERVAL))s)"
                consecutive_failures=0
            fi
        else
            consecutive_failures=$((consecutive_failures + 1))
            log_warning "LocalStack health check failed (failure #${consecutive_failures})"
            
            # Only attempt restart if we've had multiple failures and enough time has passed
            if [ $consecutive_failures -ge 2 ]; then
                local current_time=$(date +%s)
                local time_since_restart=$((current_time - last_restart_time))
                
                if [ $time_since_restart -ge $RESTART_COOLDOWN ]; then
                    if restart_localstack; then
                        last_restart_time=$(date +%s)
                        consecutive_failures=0
                    else
                        log_error "Restart failed. Will retry after cooldown period."
                    fi
                else
                    log_warning "Skipping restart (cooldown period: ${time_since_restart}s / ${RESTART_COOLDOWN}s)"
                fi
            fi
        fi
        
        sleep $CHECK_INTERVAL
    done
}

# Handle script termination
trap 'log "LocalStack monitor stopped"; exit 0' SIGINT SIGTERM

# Check if running as daemon
if [ "${1:-}" = "--daemon" ]; then
    # Run in background
    nohup "$0" > /tmp/localstack-monitor.log 2>&1 &
    echo $! > /tmp/localstack-monitor.pid
    log_success "LocalStack monitor started in background (PID: $(cat /tmp/localstack-monitor.pid))"
    log "Logs: tail -f /tmp/localstack-monitor.log"
    exit 0
fi

# Run monitoring loop
monitor_loop

