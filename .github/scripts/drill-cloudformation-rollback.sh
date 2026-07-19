#!/usr/bin/env bash
set -euo pipefail
stack="fridge-manager-stg-rollback-drill-${GITHUB_RUN_ID:-local}"
started="$(date +%s)"
cleanup() { aws cloudformation delete-stack --stack-name "$stack" >/dev/null 2>&1 || true; }
trap cleanup EXIT
aws cloudformation create-stack --stack-name "$stack" --on-failure ROLLBACK --template-body file://.github/fixtures/rollback-drill.yml >/dev/null
if aws cloudformation wait stack-create-complete --stack-name "$stack"; then echo 'expected failure did not occur' >&2; exit 1; fi
status="$(aws cloudformation describe-stacks --stack-name "$stack" --query 'Stacks[0].StackStatus' --output text)"
[[ "$status" == ROLLBACK_COMPLETE ]] || { echo "unexpected rollback status: $status" >&2; exit 1; }
aws cloudformation delete-stack --stack-name "$stack"
aws cloudformation wait stack-delete-complete --stack-name "$stack"
elapsed=$(( $(date +%s) - started ))
(( elapsed <= 1800 )) || { echo "rollback exceeded 30 minutes: ${elapsed}s" >&2; exit 1; }
trap - EXIT
echo "CloudFormation rollback drill succeeded in ${elapsed}s"
