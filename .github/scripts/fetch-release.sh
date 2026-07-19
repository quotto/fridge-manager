#!/usr/bin/env bash
set -euo pipefail
[[ "${RELEASE_BUCKET:-}" =~ ^[a-z0-9.-]{3,63}$ ]] || { echo 'release bucket is invalid' >&2; exit 2; }
[[ "${RELEASE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || { echo 'known-good SHA must be 40 lowercase hex characters' >&2; exit 2; }
rm -rf .promotion; mkdir -p .promotion
prefix="s3://${RELEASE_BUCKET}/releases/${RELEASE_SHA}"
aws s3 cp "${prefix}/cdk.out.tgz" .promotion/cdk.out.tgz --no-progress
aws s3 cp "${prefix}/cdk.out.tgz.sha256" .promotion/cdk.out.tgz.sha256 --no-progress
bash .github/scripts/verify-promotion.sh
