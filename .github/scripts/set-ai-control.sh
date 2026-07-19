#!/usr/bin/env bash
set -euo pipefail
environment="${1:-}"; state="${2:-}"
case "$environment" in stg) stack=FridgeManagerStgFoundationAnalysisApi ;; prod) stack=FridgeManagerProdFoundationAnalysisApi ;; *) exit 2 ;; esac
case "$state" in stopped) enabled=false; expected=False ;; enabled) enabled=true; expected=True ;; *) exit 2 ;; esac
[[ "${CONTROL_ISSUE:-}" =~ ^[0-9]+$ ]] || { echo 'issue must be numeric' >&2; exit 2; }
reason_pattern='^[A-Za-z0-9._:/ -]{3,100}$'
[[ "${CONTROL_REASON:-}" =~ $reason_pattern ]] || { echo 'reason contains unsupported characters' >&2; exit 2; }
function_name="$(aws cloudformation describe-stacks --stack-name "$stack" --query "Stacks[0].Outputs[?OutputKey=='AiControlFunctionName'].OutputValue | [0]" --output text)"
table_name="$(aws cloudformation describe-stacks --stack-name "$stack" --query "Stacks[0].Outputs[?OutputKey=='AiControlTableName'].OutputValue | [0]" --output text)"
payload="$(jq -cn --argjson enabled "$enabled" --arg reason "ISSUE_${CONTROL_ISSUE}: ${CONTROL_REASON}" '{enabled:$enabled,reason:$reason}')"
response="$(mktemp)"; trap 'rm -f "$response"' EXIT
aws lambda invoke --function-name "$function_name" --cli-binary-format raw-in-base64-out --payload "$payload" "$response" >/dev/null
actual="$(aws dynamodb get-item --table-name "$table_name" --key '{"controlId":{"S":"CONTROL#AI"}}' --consistent-read --query 'Item.enabled.BOOL' --output text)"
[[ "$actual" == "$expected" ]] || { echo 'desired AI state was not applied' >&2; exit 1; }
echo "AI state verified: $state"
