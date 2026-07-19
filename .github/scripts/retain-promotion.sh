#!/usr/bin/env bash
set -euo pipefail
[[ "${RELEASE_BUCKET:-}" =~ ^[a-z0-9.-]{3,63}$ ]] || { echo 'PROMOTION_ARTIFACT_BUCKET is missing or invalid' >&2; exit 2; }
[[ "${RELEASE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || { echo 'release SHA is invalid' >&2; exit 2; }
prefix="s3://${RELEASE_BUCKET}/releases/${RELEASE_SHA}"
aws s3 cp .promotion/cdk.out.tgz "${prefix}/cdk.out.tgz" --no-progress
aws s3 cp .promotion/cdk.out.tgz.sha256 "${prefix}/cdk.out.tgz.sha256" --no-progress
