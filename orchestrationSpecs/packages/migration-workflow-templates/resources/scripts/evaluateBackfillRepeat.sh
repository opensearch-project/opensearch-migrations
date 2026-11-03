#!/usr/bin/env bash
set -euo pipefail

: "${RESOURCE_NAME:?}"
: "${RESOURCE_UID:?}"
: "${CONFIG_CHECKSUM:?}"
: "${SNAPSHOT_RESOURCE_NAME:?}"
: "${REPEAT_POLICY:?}"
: "${RUN_NUMBER:?}"
: "${CONFIG_PROCESSOR_DIR:=/root/configProcessor}"

for attempt in {1..5}; do
    migration="$(kubectl get snapshotmigration "$RESOURCE_NAME" -o json)"
    snapshot="$(kubectl get datasnapshot "$SNAPSHOT_RESOURCE_NAME" -o json)"
    result="$(jq -nc \
        --argjson migration "$migration" --argjson snapshot "$snapshot" \
        --argjson policy "$REPEAT_POLICY" --argjson run "$RUN_NUMBER" \
        --arg resourceUid "$RESOURCE_UID" --arg configChecksum "$CONFIG_CHECKSUM" \
        '{migration:$migration,snapshot:$snapshot,policy:$policy,run:$run,
          resourceUid:$resourceUid,configChecksum:$configChecksum}' \
        | node "$CONFIG_PROCESSOR_DIR/index.js" evaluateBackfillRepeat)"

    if kubectl patch snapshotmigration "$RESOURCE_NAME" --subresource=status --type=json \
        -p "$(printf '%s' "$result" | jq -c '.patch')"; then
        break
    fi
    if [ "$attempt" -eq 5 ]; then
        echo "Unable to persist the backfill repeat decision after five attempts." >&2
        exit 1
    fi
    # A monitor can advance resourceVersion between GET and PATCH. Re-read and
    # re-evaluate so resets still fail the UID/checksum checks on the next attempt.
    sleep 1
done
printf '%s\n' "$result" | jq '.decision'
printf '%s' "$result" | jq -r '.decision.action' > /tmp/backfill-repeat-action
if [ "$(cat /tmp/backfill-repeat-action)" = "fail" ]; then
    echo "Backfill run limit reached before the snapshot lag target was met." >&2
    exit 1
fi
