#!/usr/bin/env bash
# =============================================================================
# delete-dynamodb-table.sh - Deletes DynamoDB table(s) for the Gaming Platform app
#
# Usage:
#   ./scripts/delete-dynamodb-table.sh [--dynamodb-endpoint <url>] [--dynamodb-region <region>] [--table-name <table-name>]
#
# Optional arguments:
#   --dynamodb-endpoint  DynamoDB endpoint (default: http://localhost:8000)
#   --dynamodb-region    AWS region (default: eu-west-1)
#   --table-name         Table name. If omitted, deletes JavaPlayerState,
#                        JavaGameEvent, and JavaLeaderboard (defaults from application.yml).
#
# WARNING: This permanently deletes the table (or tables) and all their data.
#
# After delete-table returns, DynamoDB may still be removing the table asynchronously.
# This script waits until describe-table reports each table is gone (avoids racing the
# next create against the same name).
#
# To recreate the tables for this sample, start the Spring Boot app once. It creates the
# tables when missing with the same specification.
#
# Prerequisites:
#   - AWS CLI v2 installed
#   - DynamoDB Local running (for local usage) or valid AWS credentials (for AWS)
# =============================================================================
set -euo pipefail

ENDPOINT="http://localhost:8000"
REGION="eu-west-1"
TABLE_NAME=""

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
        --table-name)
            require_option_value "$1" "${2-}"
            TABLE_NAME="$2"; shift 2 ;;
        -*)
            echo "ERROR: Unknown option: $1"; exit 1 ;;
        *)
            echo "ERROR: Unexpected argument: $1"; exit 1 ;;
    esac
done

if [[ -n "$TABLE_NAME" ]]; then
    TABLES_TO_DELETE=("$TABLE_NAME")
else
    TABLES_TO_DELETE=(JavaPlayerState JavaGameEvent JavaLeaderboard)
fi

delete_and_wait() {
    local name="$1"
    echo "Deleting DynamoDB table '$name' at endpoint '$ENDPOINT' in region '$REGION'..."

    aws dynamodb delete-table \
        --table-name "$name" \
        --endpoint-url "$ENDPOINT" \
        --region "$REGION" \
        --no-cli-pager

    echo "Waiting until table '$name' no longer exists..."
    aws dynamodb wait table-not-exists \
        --table-name "$name" \
        --endpoint-url "$ENDPOINT" \
        --region "$REGION" \
        --no-cli-pager

    echo "Table '$name' has been fully deleted."
}

for t in "${TABLES_TO_DELETE[@]}"; do
    if aws dynamodb describe-table \
        --table-name "$t" \
        --endpoint-url "$ENDPOINT" \
        --region "$REGION" \
        --no-cli-pager >/dev/null 2>&1; then
        delete_and_wait "$t"
    else
        echo "Table '$t' does not exist; skipping."
    fi
done
