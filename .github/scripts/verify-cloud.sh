#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f package-lock.json ]]; then
  echo "package-lock.json が見つかりません。先に npm ci を実行してください。" >&2
  exit 1
fi

npm run lint
npm run test:coverage
npm run build
npm run synth
