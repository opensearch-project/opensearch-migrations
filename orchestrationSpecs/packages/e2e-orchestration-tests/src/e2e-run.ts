/**
 * E2E Live Runner
 *
 * Runs a single expanded test case against a real K8s cluster with Argo.
 * Uses the real `workflow configure edit --stdin` and `workflow submit`
 * commands via the migration console pod — same path a user would take.
 *
 * Usage: npx tsx src/e2e-run.ts [--namespace ma]
 *
 * NOTE: This runner uses the shared workflow name 'migration-workflow'
 * (determined by createMigrationWorkflowFromUserConfiguration.sh).
 * Do not run this concurrently with other workflow CLI usage in the
 * same namespace.
 *
 * NOTE: This is an integration script that shells into a live cluster.
 * It is not unit-testable — correctness is validated by running it.
 * The pure logic it depends on (assertLogic, checksumReporter, etc.)
 * has full unit test coverage.
 */
import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import yaml from 'yaml';
import { buildChecksumReport } from './checksumReporter';
import { expandMatrix } from './matrixExpander';
import { defaultMutatorRegistry } from './approvedMutators';
import { assertRun, formatAssertResult } from './assertLogic';
import type { MatrixSpec, RunExpectation, ScenarioObservation, ChecksumReport } from './types';

const NAMESPACE = process.argv.includes('--namespace')
    ? process.argv[process.argv.indexOf('--namespace') + 1]
    : 'ma';

const WORKFLOW_NAME = 'migration-workflow';
const OVERALL_TIMEOUT = 3600;       // 1 hour max for the entire test
const STALL_TIMEOUT = 120;          // 2 min with no phase change = stall

// ─── Shell helpers ──────────────────────────────────────────────────

function kubectl(args: string): string {
    const cmd = `kubectl -n ${NAMESPACE} ${args}`;
    console.log(`  $ ${cmd}`);
    return execSync(cmd, { encoding: 'utf-8', timeout: 60000, maxBuffer: 50 * 1024 * 1024 }).trim();
}

function kubectlQuiet(args: string): string {
    return execSync(`kubectl -n ${NAMESPACE} ${args}`, { encoding: 'utf-8', timeout: 30000, maxBuffer: 50 * 1024 * 1024 }).trim();
}

function consoleExec(cmd: string): string {
    const fullCmd = `kubectl -n ${NAMESPACE} exec migration-console-0 -c console -- bash -c '${cmd.replace(/'/g, "'\\''")}'`;
    console.log(`  $ (console) ${cmd}`);
    return execSync(fullCmd, { encoding: 'utf-8', timeout: 120000 }).trim();
}

function sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}

// ─── CRD helpers ────────────────────────────────────────────────────

const CRD_KIND_MAP: Record<string, string> = {
    proxy: 'capturedtraffics.migrations.opensearch.org',
    snapshot: 'datasnapshots.migrations.opensearch.org',
    snapshotMigration: 'snapshotmigrations.migrations.opensearch.org',
    trafficReplay: 'trafficreplays.migrations.opensearch.org',
};

function readCrdStates(report: ChecksumReport, runIndex: number, runName: string): ScenarioObservation {
    const resources: ScenarioObservation['resources'] = {};
    for (const [key, entry] of Object.entries(report.components)) {
        const crdKind = CRD_KIND_MAP[entry.kind];
        if (!crdKind) continue;
        try {
            const phase = kubectlQuiet(`get ${crdKind} ${entry.resourceName} -o jsonpath='{.status.phase}'`);
            const checksum = kubectlQuiet(`get ${crdKind} ${entry.resourceName} -o jsonpath='{.status.configChecksum}'`);
            resources[key] = { kind: entry.kind, resourceName: entry.resourceName, phase, configChecksum: checksum };
        } catch {
            resources[key] = { kind: entry.kind, resourceName: entry.resourceName, phase: 'NotFound', configChecksum: '' };
        }
    }
    return { scenario: 'e2e-live', run: runIndex, runName, observedAt: new Date().toISOString(), resources };
}

// ─── Workflow submission via migration console ──────────────────────

function configureAndSubmit(rawConfig: Record<string, unknown>): void {
    const configYaml = yaml.stringify(rawConfig);

    console.log(`  Loading config via 'workflow configure edit --stdin'...`);
    const escaped = configYaml.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\$/g, '\\$').replace(/`/g, '\\`');
    consoleExec(`echo "${escaped}" | workflow configure edit --stdin`);

    console.log(`  Deleting previous workflow...`);
    try { kubectl(`delete workflow ${WORKFLOW_NAME} --ignore-not-found=true`); } catch { /* ok */ }

    console.log(`  Submitting via 'workflow submit'...`);
    consoleExec(`workflow submit`);
}

/**
 * Wait for workflow with progress-based stall detection.
 * Fails fast if no node changes phase for STALL_TIMEOUT seconds.
 */
async function waitForWorkflow(): Promise<string> {
    console.log(`  Waiting for workflow ${WORKFLOW_NAME} (stall timeout ${STALL_TIMEOUT}s)...`);
    const overallStart = Date.now();
    let lastNodeSnapshot = '';
    let lastProgressTime = Date.now();

    while (Date.now() - overallStart < OVERALL_TIMEOUT * 1000) {
        try {
            const phase = kubectlQuiet(`get workflow ${WORKFLOW_NAME} -o jsonpath='{.status.phase}'`);
            if (['Succeeded', 'Failed', 'Error'].includes(phase)) {
                console.log(`\n  Workflow ${WORKFLOW_NAME}: ${phase}`);
                return phase;
            }

            // Check for progress: snapshot all node phases (nodes is a map, not array)
            const nodeSnapshot = execSync(
                `kubectl -n ${NAMESPACE} get workflow ${WORKFLOW_NAME} -o json 2>/dev/null | jq -r '[.status.nodes // {} | .[].phase] | sort | join(",")'`,
                { encoding: 'utf-8', timeout: 30000, maxBuffer: 50 * 1024 * 1024 }
            ).trim();
            if (nodeSnapshot !== lastNodeSnapshot) {
                lastNodeSnapshot = nodeSnapshot;
                lastProgressTime = Date.now();
                process.stdout.write('.');
            }

            // Stall detection
            if (Date.now() - lastProgressTime > STALL_TIMEOUT * 1000) {
                console.log(`\n  ✗ Workflow stalled — no progress for ${STALL_TIMEOUT}s`);
                printWorkflowFailures();
                return 'Failed';
            }
        } catch { /* workflow may not exist yet */ }
        await sleep(5000);
    }
    throw new Error(`Workflow ${WORKFLOW_NAME} exceeded overall timeout of ${OVERALL_TIMEOUT}s`);
}

function printWorkflowFailures(): void {
    try {
        // Use jq to extract only failed nodes — avoids ENOBUFS from full workflow JSON
        const raw = execSync(
            `kubectl -n ${NAMESPACE} get workflow ${WORKFLOW_NAME} -o json | jq -r '.status.nodes | to_entries[] | select(.value.phase == "Failed" or .value.phase == "Error") | "\\(.value.displayName)\\t\\(.value.phase)\\t\\(.value.message // "")"'`,
            { encoding: 'utf-8', timeout: 30000, maxBuffer: 50 * 1024 * 1024 }
        ).trim();
        for (const line of raw.split('\n')) {
            if (!line) continue;
            const [name, phase, msg] = line.split('\t');
            console.log(`    ${name}: ${phase} — ${(msg ?? '').substring(0, 200)}`);
        }
    } catch (e) {
        console.log(`    (could not read workflow failures: ${e})`);
    }
}

// ─── Cleanup ────────────────────────────────────────────────────────

function collectAllCrdResources(reports: ChecksumReport[]): Array<{ kind: string; resourceName: string }> {
    const seen = new Set<string>();
    const result: Array<{ kind: string; resourceName: string }> = [];
    for (const report of reports) {
        for (const entry of Object.values(report.components)) {
            const crdKind = CRD_KIND_MAP[entry.kind];
            if (!crdKind) continue;
            const key = `${crdKind}/${entry.resourceName}`;
            if (!seen.has(key)) {
                seen.add(key);
                result.push({ kind: entry.kind, resourceName: entry.resourceName });
            }
        }
    }
    return result;
}

function cleanup(allResources: Array<{ kind: string; resourceName: string }>): void {
    console.log('\n=== Teardown ===');
    try { kubectl(`delete workflow ${WORKFLOW_NAME} --ignore-not-found=true`); } catch { /* ok */ }
    for (const { kind, resourceName } of allResources) {
        const crdKind = CRD_KIND_MAP[kind];
        if (crdKind) {
            try { kubectl(`delete ${crdKind} ${resourceName} --ignore-not-found=true`); } catch { /* ok */ }
        }
    }
}

// ─── Main ───────────────────────────────────────────────────────────

async function main() {
    const fixturePath = path.resolve(
        __dirname, '..', '..', 'config-processor', 'scripts', 'samples', 'fullMigrationWithTraffic.wf.yaml'
    );
    const baseConfig = yaml.parse(fs.readFileSync(fixturePath, 'utf-8')) as Record<string, unknown>;

    console.log('=== E2E Live Runner ===');
    console.log(`  namespace: ${NAMESPACE}`);
    console.log(`  base config: ${path.basename(fixturePath)}\n`);

    const baseReport = await buildChecksumReport(baseConfig);
    const spec: MatrixSpec = {
        focus: 'proxy:capture-proxy',
        select: [{ changeClass: 'safe', patterns: ['focus-change'] }],
    };
    const [testCase] = await expandMatrix(spec, baseReport, defaultMutatorRegistry, baseConfig, baseReport);
    console.log(`Test case: ${testCase.name}`);
    console.log(`  expect reran:     [${testCase.expect.reran.join(', ')}]`);
    console.log(`  expect unchanged: [${testCase.expect.unchanged.join(', ')}]`);

    const filterToCrdComponents = (report: ChecksumReport): ChecksumReport => ({
        components: Object.fromEntries(
            Object.entries(report.components).filter(([, e]) => e.kind in CRD_KIND_MAP)
        ),
    });

    const baseReportCrd = filterToCrdComponents(baseReport);
    const mutatedReportCrd = filterToCrdComponents(testCase.mutatedChecksumReport);
    const allCrdResources = collectAllCrdResources([baseReportCrd, mutatedReportCrd]);

    const runs: Array<{
        name: string;
        config: Record<string, unknown>;
        checksumReport: ChecksumReport;
        expectation: RunExpectation;
    }> = [
        { name: 'baseline', config: baseConfig, checksumReport: baseReportCrd, expectation: { mode: 'allCompleted' } },
        { name: 'noop', config: baseConfig, checksumReport: baseReportCrd, expectation: { mode: 'allSkipped' } },
        { name: 'mutate', config: testCase.mutatedConfig, checksumReport: mutatedReportCrd, expectation: { mode: 'selective', reran: testCase.expect.reran.filter(k => !k.startsWith('kafka:')), unchanged: testCase.expect.unchanged.filter(k => !k.startsWith('kafka:')) } },
    ];

    let priorObservation: ScenarioObservation | null = null;
    let allPassed = true;

    // Wrap in try/finally so cleanup always runs
    try {
        for (const run of runs) {
            console.log(`\n${'='.repeat(60)}`);
            console.log(`=== Run: ${run.name} ===`);

            configureAndSubmit(run.config);
            const phase = await waitForWorkflow();

            if (phase !== 'Succeeded') {
                console.log(`\n  ✗ ${run.name} workflow failed: ${phase}`);
                printWorkflowFailures();
                allPassed = false;
                const obs = readCrdStates(run.checksumReport, runs.indexOf(run), run.name);
                console.log('  CRD states at failure:');
                for (const [k, v] of Object.entries(obs.resources)) {
                    console.log(`    ${k}: phase=${v.phase} checksum=${v.configChecksum}`);
                }
                break;
            }

            const obs = readCrdStates(run.checksumReport, runs.indexOf(run), run.name);
            const result = assertRun(run.expectation, run.checksumReport, priorObservation, obs);
            console.log(`\n  Assert (${run.expectation.mode}):`);
            console.log(formatAssertResult(result));
            if (result.status === 'fail') { allPassed = false; }
            priorObservation = obs;
        }
    } finally {
        cleanup(allCrdResources);
    }

    console.log(`\n${'='.repeat(60)}`);
    if (allPassed) {
        console.log('  ✓ ALL RUNS PASSED');
    } else {
        console.log('  ✗ SOME RUNS FAILED');
        process.exit(1);
    }
}

main().catch(err => {
    console.error('Fatal error:', err);
    process.exit(1);
});
