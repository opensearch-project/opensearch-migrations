#!/bin/bash
#
# SPDX-License-Identifier: Apache-2.0
#
# Runs Gradle with the rebuild's mandatory Spotless exclusions. Full output remains in /private/tmp;
# stdout/stderr contain only a concise result or useful failure excerpts.

set -u -o pipefail
umask 077

cd -- "${BASH_SOURCE[0]%/*}/.." || exit 2

GRADLE_RUNNER="${GRADLE_EVIDENCE_GRADLEW:-./gradlew}"
LOG_FILE="/private/tmp/gradle-evidence.$$.$RANDOM.log"

if ! (set -o noclobber; : > "$LOG_FILE") 2>/dev/null; then
    echo "ERROR: refusing to overwrite existing evidence log $LOG_FILE" >&2
    exit 2
fi

"$GRADLE_RUNNER" -x spotlessJavaCheck -x spotlessJavaApply "$@" > "$LOG_FILE" 2>&1
gradle_status=$?

if [ "$gradle_status" -eq 0 ]; then
    echo "PASS: Gradle completed with exit code 0."
    grep -E '^(BUILD SUCCESSFUL|[0-9]+ actionable tasks?:)' "$LOG_FILE" | tail -n 3 || true
    echo "Full log: $LOG_FILE"
    exit 0
fi

echo "FAIL: Gradle exited with code $gradle_status." >&2
echo "Failure markers:" >&2
grep -n -m 12 -E '(^> Task .* FAILED$|^FAILURE:|^\* What went wrong:|(^|[[:space:]])error:|Compilation failed)' \
    "$LOG_FILE" >&2 || echo "  no standard Gradle failure marker found" >&2
echo "Last 30 log lines:" >&2
tail -n 30 "$LOG_FILE" >&2
echo "Full log: $LOG_FILE" >&2
exit "$gradle_status"
