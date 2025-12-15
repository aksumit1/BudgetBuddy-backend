#!/bin/bash
set -e

# Container Safeguard Script
# Prevents accidental shutdown of shared infrastructure containers
# This script checks container ownership and warns before stopping

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Protected containers (shared infrastructure)
PROTECTED_CONTAINERS=(
    "budgetbuddy-localstack"
    "budgetbuddy-redis"
    "budgetbuddy-backend"
)

# Colors for output
RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

is_protected() {
    local container_name=$1
    for protected in "${PROTECTED_CONTAINERS[@]}"; do
        if [ "$container_name" = "$protected" ]; then
            return 0  # Is protected
        fi
    done
    return 1  # Not protected
}

check_container_ownership() {
    local container_name=$1
    
    # Check if container exists
    if ! docker ps -a --format "{{.Names}}" | grep -q "^${container_name}$"; then
        echo -e "${YELLOW}Container '${container_name}' does not exist${NC}"
        return 1
    fi
    
    # Get container labels
    local labels=$(docker inspect "$container_name" --format '{{range $k, $v := .Config.Labels}}{{$k}}={{$v}} {{end}}' 2>/dev/null || echo "")
    
    # Check for warning label
    if echo "$labels" | grep -q "com.budgetbuddy.warning=DO_NOT_STOP_MANUALLY"; then
        return 0  # Is protected
    fi
    
    return 1  # Not explicitly protected
}

warn_before_stop() {
    local container_name=$1
    local action=$2  # "stop" or "rm"
    
    echo -e "${RED}╔════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}║  WARNING: Protected Infrastructure Container              ║${NC}"
    echo -e "${RED}╚════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${YELLOW}Container: ${container_name}${NC}"
    echo -e "${YELLOW}Action: ${action}${NC}"
    echo ""
    echo -e "${RED}This container is part of the shared infrastructure and should${NC}"
    echo -e "${RED}NOT be stopped manually unless you are the owner.${NC}"
    echo ""
    echo "Impact:"
    echo "  - Other developers may be using this service"
    echo "  - Backend API depends on this service"
    echo "  - Tests may fail if this service is unavailable"
    echo ""
    echo "If you need to stop this container:"
    echo "  1. Check if anyone else is using it: docker ps | grep ${container_name}"
    echo "  2. Use docker-compose to stop: cd ${PROJECT_ROOT} && docker-compose stop ${container_name}"
    echo "  3. Or use the safeguard override: $0 --force-stop ${container_name}"
    echo ""
    read -p "Are you sure you want to ${action} this container? (type 'yes' to confirm): " confirmation
    
    if [ "$confirmation" != "yes" ]; then
        echo -e "${GREEN}Operation cancelled${NC}"
        exit 1
    fi
}

# Main function
main() {
    local force_flag=false
    local container_name=""
    local action=""
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            --force-stop|--force-rm)
                force_flag=true
                action="${1#--force-}"
                shift
                container_name="$1"
                shift
                ;;
            --check)
                # Check status of all protected containers
                echo "Checking protected containers..."
                for container in "${PROTECTED_CONTAINERS[@]}"; do
                    if docker ps --format "{{.Names}}" | grep -q "^${container}$"; then
                        local status=$(docker ps --filter "name=${container}" --format "{{.Status}}")
                        echo -e "${GREEN}✓${NC} ${container}: ${status}"
                    else
                        echo -e "${RED}✗${NC} ${container}: Not running"
                    fi
                done
                exit 0
                ;;
            *)
                echo "Usage: $0 [--check] [--force-stop|--force-rm <container>]"
                echo ""
                echo "Options:"
                echo "  --check              Check status of all protected containers"
                echo "  --force-stop <name>  Force stop a container (bypasses safeguard)"
                echo "  --force-rm <name>    Force remove a container (bypasses safeguard)"
                exit 1
                ;;
        esac
    done
    
    # If no arguments, show usage
    if [ $# -eq 0 ]; then
        echo "Container Safeguard Script"
        echo ""
        echo "This script protects shared infrastructure containers from accidental shutdown."
        echo ""
        echo "Protected containers:"
        for container in "${PROTECTED_CONTAINERS[@]}"; do
            echo "  - ${container}"
        done
        echo ""
        echo "Usage: $0 --check"
        echo "       $0 --force-stop <container>"
        exit 0
    fi
}

# Hook into docker commands (if sourced)
if [ "${BASH_SOURCE[0]}" != "${0}" ]; then
    # This script is being sourced, set up aliases
    alias docker-stop='docker_stop_safe'
    alias docker-rm='docker_rm_safe'
    
    docker_stop_safe() {
        local container_name=$1
        if is_protected "$container_name" || check_container_ownership "$container_name"; then
            warn_before_stop "$container_name" "stop"
        fi
        docker stop "$container_name"
    }
    
    docker_rm_safe() {
        local container_name=$1
        if is_protected "$container_name" || check_container_ownership "$container_name"; then
            warn_before_stop "$container_name" "remove"
        fi
        docker rm "$container_name"
    }
fi

main "$@"

