#!/usr/bin/env bash
# =============================================================================
# run-app-local.sh - Runs the app on the host machine
#
# DynamoDB Local must already be running in Docker (use start-dynamodb-local.sh).
#
# Usage:
#   ./scripts/run-app-local.sh [--dynamodb-endpoint <url>] [--dynamodb-region <region>] [--dynamodb-client-type <type>]
#
# Optional arguments:
#   --dynamodb-endpoint     DynamoDB endpoint (default: http://localhost:8000)
#   --dynamodb-region       AWS region (default: eu-west-1)
#   --dynamodb-client-type  high-level | low-level (default: high-level)
#
# Prerequisites:
#   - Application built (run build-app.sh first)
#   - For local: DynamoDB Local running (run start-dynamodb-local.sh)
#   - For AWS: valid AWS credentials configured
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

ENDPOINT="http://localhost:8000"
REGION="eu-west-1"
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
        --dynamodb-endpoint)
            require_option_value "$1" "${2-}"
            ENDPOINT="$2"; shift 2 ;;
        --dynamodb-region)
            require_option_value "$1" "${2-}"
            REGION="$2"; shift 2 ;;
        --dynamodb-client-type)
            require_option_value "$1" "${2-}"
            CLIENT_TYPE="$2"; shift 2 ;;
        *)
            echo "ERROR: Unknown option: $1"; exit 1 ;;
    esac
done

JAR=$(find "$PROJECT_DIR/target" -maxdepth 1 -name "*.jar" \
    -not -name "*-sources.jar" -not -name "*.original.jar" | sort | head -1)
if [[ -z "$JAR" ]]; then
    echo "ERROR: No JAR found in target/. Run build-app.sh first."
    exit 1
fi

echo "Starting Gaming Platform app..."
echo "  DynamoDB endpoint    : $ENDPOINT"
echo "  DynamoDB region      : $REGION"
echo "  DynamoDB client type : $CLIENT_TYPE"
echo "  JAR                  : $JAR"

java -jar "$JAR" \
    --dynamodb.endpoint="$ENDPOINT" \
    --dynamodb.region="$REGION" \
    --dynamodb.client-type="$CLIENT_TYPE"
