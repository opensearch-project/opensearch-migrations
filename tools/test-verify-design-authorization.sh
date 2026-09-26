#!/bin/bash
#
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

script=$(cd "$(dirname "$0")" && pwd)/verify-design-authorization.sh
scratch=$(mktemp -d "${TMPDIR:-/tmp}/verify-design-authorization-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT

cd "$scratch"
git init -q
git config user.name "Guard Test"
git config user.email "guard-test@example.com"
mkdir -p docs/captureAndReplay docs TrafficCapture
printf 'baseline\n' > docs/captureAndReplay/replayerLowLevelDesign.md
printf '## Design changes\n\n| Date | Document | Decision |\n' > docs/replayerRebuildStatus.md
printf 'class Test {}\n' > TrafficCapture/Test.java
git add .
git commit -q -m "baseline"
baseline=$(git rev-parse HEAD)
printf '%s\n' "$baseline" > docs/captureAndReplay/APPROVED-AT
git add docs/captureAndReplay/APPROVED-AT
git commit -q -m "record baseline"

printf 'changed\n' >> docs/captureAndReplay/replayerLowLevelDesign.md
printf 'class Test { int value; }\n' > TrafficCapture/Test.java
printf '| 2026-09-26 | replayerLLD | Test authorization |\n' >> docs/replayerRebuildStatus.md
git add .
git commit -q -m "ordinary mixed commit"
ordinary=$(git rev-parse HEAD)
printf '%s\n' "$ordinary" > allowlist

if "$script" "$baseline" >/dev/null 2>&1; then
    echo "FAIL: ordinary mixed commit passed without an allowlist" >&2
    exit 1
fi
if "$script" "$baseline" --allow-history-wave-mixes allowlist >/dev/null 2>&1; then
    echo "FAIL: ordinary mixed commit passed with a history-wave allowlist" >&2
    exit 1
fi

git commit --amend -q -m "History wave (abandoned): test experiment" \
    -m "History treatment: abandoned."
wave=$(git rev-parse HEAD)
printf '%s\n' "$wave" > allowlist
"$script" "$baseline" --allow-history-wave-mixes allowlist >/dev/null

printf '%s\n' "$baseline" >> allowlist
if "$script" "$baseline" --allow-history-wave-mixes allowlist >/dev/null 2>&1; then
    echo "FAIL: stale allowlist entry passed" >&2
    exit 1
fi

echo "PASS: design authorization history-wave exception is exact, labeled, and opt-in."
