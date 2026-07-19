#!/usr/bin/env bash
set -euo pipefail

environment="${1:-}"
case "$environment" in
  stg) stack=FridgeManagerStgFoundationAnalysisApi ;;
  prod) stack=FridgeManagerProdFoundationAnalysisApi ;;
  *) echo "usage: $0 <stg|prod>" >&2; exit 2 ;;
esac

output() {
  aws cloudformation describe-stacks --stack-name "$stack" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue | [0]" --output text
}

api_url="$(output AnalysisApiUrl)"
control_table="$(output AiControlTableName)"
[[ "$api_url" == https://* ]] || { echo 'API output is missing or not HTTPS' >&2; exit 1; }
[[ "$control_table" != None && -n "$control_table" ]] || { echo 'control table output is missing' >&2; exit 1; }

# 認証情報や画像を使用せず、未認証要求が拒否されることだけを確認する。
status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request POST --header 'content-type: application/json' --data '{}' "$api_url")"
case "$status" in 401|403) ;; *) echo "unauthenticated API request was not rejected: HTTP $status" >&2; exit 1 ;; esac

enabled="$(aws dynamodb get-item --table-name "$control_table" \
  --key '{"controlId":{"S":"CONTROL#AI"}}' --consistent-read \
  --query 'Item.enabled.BOOL' --output text)"
[[ "$enabled" == True ]] || { echo 'AI control is not enabled after deployment' >&2; exit 1; }
