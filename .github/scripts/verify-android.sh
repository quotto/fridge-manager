#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f ./gradlew ]]; then
  echo "gradlew が見つかりません。リポジトリのルートから実行してください。" >&2
  exit 1
fi

bash ./gradlew --no-daemon --stacktrace lint testDebugUnitTest assembleDebug
