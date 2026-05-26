#!/usr/bin/env bash
# =============================================================================
# run-app-docker.sh - Runs the app + DynamoDB Local together via docker-compose
#
# Usage:
#   ./scripts/run-app-docker.sh [--stop] [--dynamodb-client-type <type>]
#
# Optional arguments:
#   --stop         Stop all containers
#   --dynamodb-client-type  high-level | low-level (default: high-level)
#
# The app container connects to DynamoDB Local via the Docker network
# (endpoint: http://dynamodb-local:8000).
#
# Prerequisites:
#   - Docker / Rancher Desktop running
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

STOP=false
CLIENT_TYPE="high-level"

require_option_value() {
    local OPTION_NAME="$1"
    local OPTION_VALUE="${2-}"

    if [[ -z "$OPTION_VALUE" || "$OPTION_VALUE" == --* ]]; then
        echo "ERROR: Option $OPTION_NAME requires a value"
        exit 1
    fi
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --stop)
            STOP=true; shift ;;
        --dynamodb-client-type)
            require_option_value "$1" "${2-}"
            CLIENT_TYPE="$2"; shift 2 ;;
        *)
            echo "ERROR: Unknown option: $1"; exit 1 ;;
    esac
done

cd "$PROJECT_DIR"

if $STOP; then
    echo "Stopping all containers..."
    docker compose --profile app down
    exit 0
fi

export DYNAMODB_CLIENTTYPE="$CLIENT_TYPE"
echo "Starting app + DynamoDB Local via docker-compose..."
echo "  Client type: $CLIENT_TYPE"
docker compose --profile app build --no-cache gaming-platform-app && docker compose --profile app up
