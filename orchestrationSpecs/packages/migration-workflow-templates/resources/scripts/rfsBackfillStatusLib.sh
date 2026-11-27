#!/usr/bin/env bash
# Writes SnapshotMigration.status.documentBackfill. Sourced by rfsMonitorCronJob.sh and
# applyRfsMonitorCronJob.sh; needs SNAPSHOT_MIGRATION_NAME and RFS_SCRIPT_DIR.

# documentBackfill for a status-check result: `console --json backfill status --deep-check` output, or
# nothing when the check produced nothing usable. The optional fourth argument is the documentBackfill being
# replaced, whose failed-document count is kept when this result has none. The mapping is rfsBackfillStatus.jq.
document_backfill() {
    phase="$1"
    message="$2"
    status_json_text="${3:-}"
    previous="${4:-null}"
    input='{}'
    if [ -n "$status_json_text" ] && printf '%s' "$status_json_text" | jq -e 'type == "object"' >/dev/null 2>&1; then
        input="$status_json_text"
    fi
    if ! printf '%s' "$previous" | jq -e 'type == "object" or type == "null"' >/dev/null 2>&1; then
        previous=null
    fi
    printf '%s' "$input" | jq -c \
        --arg phase "$phase" \
        --arg message "$message" \
        --arg updatedAt "$(date -u +"%Y-%m-%dT%H:%M:%SZ")" \
        --argjson previous "$previous" \
        -f "$RFS_SCRIPT_DIR/rfsBackfillStatus.jq"
}

# Replace status.documentBackfill whole, so no field survives from an earlier refresh or an earlier run
# (a merge patch would keep keys the new status no longer has). Extra status "add" ops apply atomically
# with it. A JSON patch needs status to exist; until it does, the equivalent merge patch creates it.
replace_document_backfill() {
    backfill="$1"
    extra_ops="${2:-[]}"
    json_patch="$(jq -nc --argjson b "$backfill" --argjson extra "$extra_ops" \
        '$extra + [{op: "add", path: "/status/documentBackfill", value: $b}]')"
    merge_patch="$(jq -nc --argjson b "$backfill" --argjson extra "$extra_ops" \
        '{status: (reduce $extra[] as $op ({}; .[$op.path | ltrimstr("/status/")] = $op.value)
                   | .documentBackfill = $b)}')"
    kubectl patch snapshotmigration "$SNAPSHOT_MIGRATION_NAME" \
        --subresource=status --type=json -p "$json_patch" 2>/dev/null \
    || kubectl patch snapshotmigration "$SNAPSHOT_MIGRATION_NAME" \
        --subresource=status --type=merge -p "$merge_patch"
}
