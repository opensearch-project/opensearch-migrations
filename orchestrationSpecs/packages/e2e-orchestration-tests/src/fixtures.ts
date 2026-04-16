/**
 * Test Fixtures
 *
 * Scriptable setup/cleanup/validation/approval actions that run shell
 * commands via the migration console pod. Fully declarative — each
 * action is a named shell command.
 *
 * Cleanup actions run both before the first run (backstop against
 * leftover state from a prior crash) and after the last run.
 *
 * Approval gates define what to validate and approve at each workflow
 * suspension point. When skipApprovals is false, the workflow pauses
 * at each gate and the runner executes validations before approving.
 */

export type FixtureAction = {
    name: string;
    description: string;
    /** Shell command to run inside the migration console pod */
    consoleCommand: string;
};

/**
 * An approval gate: the workflow suspends here, the runner runs
 * validations, then approves to continue.
 */
export type ApprovalGate = {
    /** Glob pattern matching the suspended node name (e.g. "*.evaluateMetadata") */
    approvePattern: string;
    /** Human-readable description of what this gate is for */
    description: string;
    /** Validations to run while the workflow is suspended at this gate */
    validations: FixtureAction[];
};

export type TestFixtures = {
    /** Run before the first workflow submission (e.g. load test data) */
    setup: FixtureAction[];
    /** Run before setup (backstop) and after teardown (clean cluster data) */
    cleanup: FixtureAction[];
    /**
     * Approval gates in the order they'll be encountered.
     * Only used when skipApprovals is false.
     */
    approvalGates: ApprovalGate[];
};

// ─── Validation actions ─────────────────────────────────────────────

export const compareIndices: FixtureAction = {
    name: 'compare-indices',
    description: 'Compare non-system indices between source and target',
    consoleCommand: `python3 -c '
import json, subprocess, sys

def get_indices(endpoint):
    raw = subprocess.check_output([
        "curl", "-sk", "-u", "admin:admin",
        f"{endpoint}/_cat/indices?format=json&h=index,docs.count,store.size"
    ]).decode()
    indices = json.loads(raw)
    return {i["index"]: i for i in indices
            if not i["index"].startswith(".")
            and not i["index"].startswith("security-")}

src = get_indices("https://elasticsearch-master-headless:9200")
tgt = get_indices("https://opensearch-cluster-master-headless:9200")

src_names = set(src.keys())
tgt_names = set(tgt.keys())
missing = src_names - tgt_names
extra = tgt_names - src_names

print(f"Source indices: {sorted(src_names)}")
print(f"Target indices: {sorted(tgt_names)}")
if missing:
    print(f"MISSING on target: {sorted(missing)}")
if extra:
    print(f"EXTRA on target: {sorted(extra)}")
if not missing and not extra:
    print("OK: index sets match")
    mismatched = []
    for name in sorted(src_names):
        s, t = src[name], tgt[name]
        sd, td = s["docs.count"], t["docs.count"]
        status = "OK" if sd == td else "MISMATCH"
        print(f"  {name}: source={sd} docs, target={td} docs [{status}]")
        if sd != td:
            mismatched.append(name)
    if mismatched:
        print(f"Doc count mismatches: {mismatched}")
        sys.exit(1)
if missing:
    sys.exit(1)
'`,
};

export const verifyProxyReady: FixtureAction = {
    name: 'verify-proxy-ready',
    description: 'Verify capture proxy CRD is in Ready phase',
    consoleCommand:
        'kubectl get capturedtraffics.migrations.opensearch.org capture-proxy -o jsonpath="{.status.phase}" | grep -q Ready',
};

export const verifyReplayerReady: FixtureAction = {
    name: 'verify-replayer-ready',
    description: 'Verify traffic replayer CRD is in Ready phase',
    consoleCommand:
        'kubectl get trafficreplays.migrations.opensearch.org capture-proxy-target-replay1 -o jsonpath="{.status.phase}" | grep -q Ready',
};

export const verifyProxyBlocked: FixtureAction = {
    name: 'verify-proxy-blocked',
    description: 'Verify workflow is suspended at a proxy approval gate (gated change)',
    consoleCommand:
        'kubectl get workflow migration-workflow -o json | jq -e \'[.status.nodes // {} | to_entries[] | select(.value.type == "Suspend" and .value.phase == "Running")] | length > 0\'',
};

export const verifySourceIndices: FixtureAction = {
    name: 'verify-source-indices',
    description: 'List source cluster indices as a pre-flight sanity check',
    consoleCommand:
        'console clusters cat-indices --cluster source || true',
};

// ─── Default cleanup actions ────────────────────────────────────────

export const deleteTargetIndices: FixtureAction = {
    name: 'delete-target-indices',
    description: 'Clear all non-system indices from target cluster',
    consoleCommand: [
        // console clear-indices skips searchguard*/sg7* by design, so we do both:
        'console clusters clear-indices --cluster target --acknowledge-risk;',
        'curl -sk -u admin:admin -X DELETE',
        '"https://opensearch-cluster-master-headless:9200/searchguard,sg7-auditlog-*"',
        '|| true',
    ].join(' '),
};

export const deleteSourceSnapshots: FixtureAction = {
    name: 'delete-source-snapshots',
    description: 'Remove test snapshots from source cluster',
    consoleCommand:
        'curl -sk -u admin:admin -X DELETE "https://elasticsearch-master-headless:9200/_snapshot/default/snap1_*" || true',
};

export const defaultCleanupActions: FixtureAction[] = [
    deleteTargetIndices,
    deleteSourceSnapshots,
];

// ─── Default approval gates ─────────────────────────────────────────

export const defaultApprovalGates: ApprovalGate[] = [
    {
        approvePattern: '*.evaluateMetadata',
        description: 'After metadata evaluation — verify evaluate ran without errors',
        validations: [],
    },
    {
        approvePattern: '*.migrateMetadata',
        description: 'After metadata migration — verify indices exist on target',
        validations: [compareIndices],
    },
    {
        approvePattern: '*.documentBackfill',
        description: 'After document backfill — verify doc counts match',
        validations: [compareIndices],
    },
];

// ─── Default fixtures ───────────────────────────────────────────────

export const defaultFixtures: TestFixtures = {
    setup: [],
    cleanup: defaultCleanupActions,
    approvalGates: defaultApprovalGates,
};
