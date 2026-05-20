#!/usr/bin/env bash
set -e

: "${MONITOR_RESULT:?}"

echo "Evaluating workflow result..."
echo "Monitor output: $MONITOR_RESULT"

if echo "$MONITOR_RESULT" | grep -q "Phase: Succeeded"; then
    echo "Migration workflow completed successfully"
    exit 0
else
    echo "Migration workflow did not succeed"
    exit 1
fi
