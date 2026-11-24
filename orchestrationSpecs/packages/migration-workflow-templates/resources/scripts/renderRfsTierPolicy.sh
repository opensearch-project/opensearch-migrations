#!/usr/bin/env bash
# Render the memory-tier fleet policy: per-tier Deployment + PDB manifests (derived from the LIVE
# tier-0 objects) and the tier table / pod budget / approval policy, stored in one ConfigMap that
# the monitor CronJob reads on every sweep. The workflow runs this once at setup — all later
# fleet reconciliation belongs to the monitor.
set -eu

: "${SESSION_NAME:?}"
: "${SNAPSHOT_MIGRATION_NAME:?}"
: "${SM_UID:?}"
: "${TIER_TABLE_JSON:?}"
: "${MAX_TOTAL_PODS:?}"
: "${ESCALATION_APPROVAL:?}"
: "${TIER_POLICY_CONFIGMAP:?}"
: "${TIER0_WORKLOAD_NAME:?}"
: "${RFS_MEMORY_TIER_LABEL:?}"
: "${BULK_LOADER_CONTAINER:?}"
: "${WORKFLOW_SCRIPTS_ROOT:?}"

DERIVE_JQ="$WORKFLOW_SCRIPTS_ROOT/deriveRfsTierManifest.jq"

dep_json="$(kubectl get deployment "$TIER0_WORKLOAD_NAME" -o json)"
pdb_json="$(kubectl get pdb "$TIER0_WORKLOAD_NAME" -o json)"

tier_count="$(jq 'length' <<<"$TIER_TABLE_JSON")"
if [ "$tier_count" -lt 1 ]; then
    echo "tier table is empty: $TIER_TABLE_JSON" >&2
    exit 1
fi

data="$(jq -nc \
    --arg sessionName "$SESSION_NAME" \
    --arg approval "$ESCALATION_APPROVAL" \
    --arg maxTotalPods "$MAX_TOTAL_PODS" \
    --arg tierLabel "$RFS_MEMORY_TIER_LABEL" \
    --arg container "$BULK_LOADER_CONTAINER" \
    --argjson tierTable "$TIER_TABLE_JSON" \
    '{"policy.json": ({
        sessionName: $sessionName,
        maxTotalPods: ($maxTotalPods | tonumber),
        escalationApproval: $approval,
        tierTable: $tierTable,
        tierLabel: $tierLabel,
        bulkLoaderContainer: $container
     } | tostring)}')"

n=0
while [ "$n" -lt "$tier_count" ]; do
    memory="$(jq -r ".[$n]" <<<"$TIER_TABLE_JSON")"
    name="${SESSION_NAME}-rfs-t${n}"
    tier_dep="$(jq -c -f "$DERIVE_JQ" \
        --arg tier "$n" --arg memory "$memory" --arg tierLabel "$RFS_MEMORY_TIER_LABEL" \
        --arg name "$name" --arg container "$BULK_LOADER_CONTAINER" <<<"$dep_json")"
    tier_pdb="$(jq -c -f "$DERIVE_JQ" \
        --arg tier "$n" --arg memory "$memory" --arg tierLabel "$RFS_MEMORY_TIER_LABEL" \
        --arg name "$name" --arg container "$BULK_LOADER_CONTAINER" <<<"$pdb_json")"
    data="$(jq -c \
        --arg depKey "deployment-t${n}.json" --arg dep "$tier_dep" \
        --arg pdbKey "pdb-t${n}.json" --arg pdb "$tier_pdb" \
        '. + {($depKey): $dep, ($pdbKey): $pdb}' <<<"$data")"
    n=$((n + 1))
done

jq -n \
    --arg name "$TIER_POLICY_CONFIGMAP" \
    --arg smName "$SNAPSHOT_MIGRATION_NAME" \
    --arg smUid "$SM_UID" \
    --argjson data "$data" \
    '{
        apiVersion: "v1",
        kind: "ConfigMap",
        metadata: {
            name: $name,
            ownerReferences: [{
                apiVersion: "migrations.opensearch.org/v1alpha1",
                kind: "SnapshotMigration",
                name: $smName,
                uid: $smUid,
                controller: false,
                blockOwnerDeletion: true
            }]
        },
        data: $data
    }' | kubectl apply -f -

echo "rendered tier policy ConfigMap $TIER_POLICY_CONFIGMAP with $tier_count tier(s)"
