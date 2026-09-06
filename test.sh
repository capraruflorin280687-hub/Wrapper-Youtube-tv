#!/usr/bin/env bash
# Runs every suite against the exact asset the APK ships.
set -euo pipefail
cd "$(dirname "$0")"
fail=0
for t in tests/*.test.mjs; do
  echo "=== $t ==="
  node "$t" || fail=1
done
exit $fail
