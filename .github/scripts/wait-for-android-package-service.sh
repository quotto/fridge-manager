#!/usr/bin/env bash
set -euo pipefail

for ((attempt = 1; attempt <= 90; attempt++)); do
  if adb shell service check package 2>/dev/null | grep -q "found"; then
    exit 0
  fi
  if ((attempt == 90)); then
    echo "Android package service did not become ready" >&2
    exit 1
  fi
  sleep 2
done
