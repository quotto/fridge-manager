#!/usr/bin/env bash
set -euo pipefail

[[ -f .promotion/cdk.out.tgz && -f .promotion/cdk.out.tgz.sha256 ]] || { echo 'promotion artifact is incomplete' >&2; exit 2; }
(cd .promotion && sha256sum --check cdk.out.tgz.sha256)
rm -rf .promotion/cdk.out
tar -xzf .promotion/cdk.out.tgz -C .promotion
[[ -f .promotion/cdk.out/manifest.json ]] || { echo 'CDK manifest is missing' >&2; exit 1; }
for stack in FridgeManagerStgFoundation FridgeManagerStgFoundationAnalysisApi FridgeManagerProdFoundation FridgeManagerProdFoundationAnalysisApi; do
  jq -e --arg stack "$stack" '.artifacts[$stack].type == "aws:cloudformation:stack"' \
    .promotion/cdk.out/manifest.json >/dev/null || { echo "required stack is missing from assembly: $stack" >&2; exit 1; }
done
