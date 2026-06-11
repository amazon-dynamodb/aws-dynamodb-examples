#!/usr/bin/env bash
# =============================================================================
# delete-dynamodb-table.sh - Deletes the DynamoDB table for the Instant Payments app
#
# Usage:
#   ./scripts/delete-dynamodb-table.sh [--dynamodb-endpoint <url>] [--dynamodb-region <region>] [--table-name <table-name>]
#
# Optional arguments:
#   --dynamodb-endpoint  DynamoDB endpoint (default: http://localhost:8000)
#   --dynamodb-region    AWS region (default: eu-west-1)
#   --table-name         Table name (default: JavaInstantPayments)
#
# WARNING: This permanently deletes the table and all its data.
#
# After delete-table returns, DynamoDB may still be removing the table asynchronously.
# This script waits until describe-table reports the table is gone (avoids racing the
# next create against the same name).
#
# To bring the table back for this sample, run the Spring Boot app once. With
# dynamodb.create-resources enabled (the default), the app detects the missing table, recreates it
# with the same configuration (Streams NEW_IMAGE, TTL), and re-seeds the demo accounts. In
# production this flag stays off and the table is created separately through CDK, CloudFormation,
# Terraform, or the console.
#
# Prerequisites:
#   - AWS CLI v2 installed
#   - DynamoDB Local running (for local usage) or valid AWS credentials (for AWS)
# =============================================================================
set -euo pipefail

ENDPOINT="http://localhost:8000"
REGION="eu-west-1"
TABLE_NAME="JavaInstantPayments"

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

echo "Deleting DynamoDB table '$TABLE_NAME' at endpoint '$ENDPOINT' in region '$REGION'..."

aws dynamodb delete-table \
    --table-name "$TABLE_NAME" \
    --endpoint-url "$ENDPOINT" \
    --region "$REGION" \
    --no-cli-pager

echo "Waiting until table '$TABLE_NAME' no longer exists..."
aws dynamodb wait table-not-exists \
    --table-name "$TABLE_NAME" \
    --endpoint-url "$ENDPOINT" \
    --region "$REGION" \
    --no-cli-pager

echo "Table '$TABLE_NAME' has been fully deleted."
