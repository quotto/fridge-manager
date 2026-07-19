#!/usr/bin/env bash
set -euo pipefail

[[ "${AWS_ACCOUNT_ID:-}" =~ ^[0-9]{12}$ ]] || { echo 'AWS_ACCOUNT_ID is missing or invalid' >&2; exit 2; }
actual_account="$(aws sts get-caller-identity --query Account --output text)"
[[ "$actual_account" == "$AWS_ACCOUNT_ID" ]] || { echo 'OIDC role account does not match the target environment' >&2; exit 1; }
