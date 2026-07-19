#!/usr/bin/env bash
set -euo pipefail

rm -rf .promotion
mkdir -p .promotion
npx cdk synth --output .promotion/cdk.out
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -czf .promotion/cdk.out.tgz -C .promotion cdk.out
(cd .promotion && sha256sum cdk.out.tgz > cdk.out.tgz.sha256)
# stgもarchiveから再展開した内容を使い、prodと同一bytesを検証する。
rm -rf .promotion/cdk.out
bash .github/scripts/verify-promotion.sh
