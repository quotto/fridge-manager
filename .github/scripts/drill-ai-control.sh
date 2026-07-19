#!/usr/bin/env bash
set -euo pipefail

environment="${1:-}"
case "$environment" in
  stg) stack=FridgeManagerStgFoundationAnalysisApi ;;
  prod) stack=FridgeManagerProdFoundationAnalysisApi ;;
  *) echo "usage: $0 <stg|prod>" >&2; exit 2 ;;
esac

function_name="$(aws cloudformation describe-stacks --stack-name "$stack" --query "Stacks[0].Outputs[?OutputKey=='AiControlFunctionName'].OutputValue | [0]" --output text)"
table_name="$(aws cloudformation describe-stacks --stack-name "$stack" --query "Stacks[0].Outputs[?OutputKey=='AiControlTableName'].OutputValue | [0]" --output text)"
started="$(date +%s)"
response="$(mktemp)"
trap 'rm -f "$response"' EXIT

change_and_verify() {
  local enabled="$1" expected="$2" reason="$3"
  aws lambda invoke --function-name "$function_name" --cli-binary-format raw-in-base64-out \
    --payload "{\"enabled\":${enabled},\"reason\":\"${reason}\"}" "$response" >/dev/null
  local actual
  actual="$(aws dynamodb get-item --table-name "$table_name" --key '{"controlId":{"S":"CONTROL#AI"}}' \
    --consistent-read --query 'Item.enabled.BOOL' --output text)"
  [[ "$actual" == "$expected" ]] || { echo "AI control verification failed" >&2; return 1; }
}

# 復旧をtrapに登録し、停止検証後の途中失敗でもAIを停止状態に残さない。
restore() { change_and_verify true True "DRILL_RECOVERY_ISSUE_29" || true; }
trap 'restore; rm -f "$response"' EXIT
change_and_verify false False "DRILL_STOP_ISSUE_29"
change_and_verify true True "DRILL_RECOVERY_ISSUE_29"
trap 'rm -f "$response"' EXIT

elapsed=$(( $(date +%s) - started ))
(( elapsed <= 300 )) || { echo "stop/recovery drill exceeded 300 seconds: ${elapsed}s" >&2; exit 1; }
echo "AI stop/recovery drill succeeded in ${elapsed}s"
