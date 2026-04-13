/**
 * Test Fixtures
 *
 * Scriptable setup/cleanup actions that run shell commands via the
 * migration console pod. Each action is a named shell command — no
 * hardcoded logic, fully declarative.
 *
 * Cleanup actions run both before the first run (backstop against
 * leftover state from a prior crash) and after the last run.
 */

export type FixtureAction = {
    name: string;
    description: string;
    /** Shell command to run inside the migration console pod */
    consoleCommand: string;
};

export type TestFixtures = {
    /** Run before the first workflow submission (e.g. load test data) */
    setup: FixtureAction[];
    /** Run before setup (backstop) and after teardown (clean cluster data) */
    cleanup: FixtureAction[];
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

export const defaultFixtures: TestFixtures = {
    setup: [],
    cleanup: defaultCleanupActions,
};
