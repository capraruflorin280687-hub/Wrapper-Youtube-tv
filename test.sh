#!/usr/bin/env bash
# Runs the ad-stripping test suite against the exact asset the APK ships.
set -euo pipefail
cd "$(dirname "$0")"
node tests/strip.test.mjs
