import { assertRun } from '../src/assertLogic';
import { buildChecksumReport } from '../src/checksumReporter';
import { expandMatrix } from '../src/matrixExpander';
import { defaultMutatorRegistry } from '../src/approvedMutators';
import { loadFullMigrationConfig } from './helpers';
import type { MatrixSpec, ScenarioObservation, RunExpectation } from '../src/types';

function makeObservation(
    report: Awaited<ReturnType<typeof buildChecksumReport>>,
    runIndex: number,
): ScenarioObservation {
    const resources: ScenarioObservation['resources'] = {};
    for (const [key, entry] of Object.entries(report.components)) {
        resources[key] = {
            kind: entry.kind,
            resourceName: entry.resourceName,
            phase: 'Ready',
            configChecksum: entry.configChecksum,
        };
    }
    return { scenario: 'test', run: runIndex, runName: `run-${runIndex}`, observedAt: '', resources };
}

describe('assertLogic', () => {
    it('allCompleted: passes when all components are Ready with expected checksums', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);
        const obs = makeObservation(report, 0);
        const expectation: RunExpectation = { mode: 'allCompleted' };

        const result = assertRun(expectation, report, null, obs);
        expect(result.status).toBe('pass');
    });

    it('allCompleted: fails when a component has wrong phase', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);
        const obs = makeObservation(report, 0);
        obs.resources['proxy:capture-proxy'].phase = 'Running';
        const expectation: RunExpectation = { mode: 'allCompleted' };

        const result = assertRun(expectation, report, null, obs);
        expect(result.status).toBe('fail');
        expect(result.failures.some(f => f.includes('terminal phase'))).toBe(true);
    });

    it('allSkipped: passes when all checksums unchanged from prior', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);
        const prior = makeObservation(report, 0);
        const current = makeObservation(report, 1);
        const expectation: RunExpectation = { mode: 'allSkipped' };

        const result = assertRun(expectation, report, prior, current);
        expect(result.status).toBe('pass');
    });

    it('selective: passes when observations match expectations (focus-change)', async () => {
        const config = loadFullMigrationConfig();
        const baseReport = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'safe', patterns: ['focus-change'] }],
        };
        const [testCase] = await expandMatrix(spec, baseReport, defaultMutatorRegistry, config, baseReport);

        const priorObs = makeObservation(baseReport, 0);
        const currentObs = makeObservation(testCase.mutatedChecksumReport, 1);
        const expectation: RunExpectation = {
            mode: 'selective',
            reran: testCase.expect.reran,
            unchanged: testCase.expect.unchanged,
        };

        const result = assertRun(expectation, testCase.mutatedChecksumReport, priorObs, currentObs);
        expect(result.status).toBe('pass');
        expect(result.failures).toHaveLength(0);
    });

    it('selective: fails when a component expected to rerun has unchanged checksum', async () => {
        const config = loadFullMigrationConfig();
        const baseReport = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'safe', patterns: ['focus-change'] }],
        };
        const [testCase] = await expandMatrix(spec, baseReport, defaultMutatorRegistry, config, baseReport);

        const priorObs = makeObservation(baseReport, 0);
        const currentObs = makeObservation(baseReport, 1); // same as prior — bug!
        const expectation: RunExpectation = {
            mode: 'selective',
            reran: testCase.expect.reran,
            unchanged: testCase.expect.unchanged,
        };

        const result = assertRun(expectation, testCase.mutatedChecksumReport, priorObs, currentObs);
        expect(result.status).toBe('fail');
        expect(result.failures.some(f => f.includes('expected to rerun'))).toBe(true);
    });

    it('selective: fails when a component expected unchanged actually changed', async () => {
        const config = loadFullMigrationConfig();
        const baseReport = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'safe', patterns: ['immediate-dependent-change'] }],
        };
        const [testCase] = await expandMatrix(spec, baseReport, defaultMutatorRegistry, config, baseReport);

        const priorObs = makeObservation(baseReport, 0);
        const currentObs = makeObservation(testCase.mutatedChecksumReport, 1);
        currentObs.resources['proxy:capture-proxy'].configChecksum = 'tampered';
        const expectation: RunExpectation = {
            mode: 'selective',
            reran: testCase.expect.reran,
            unchanged: testCase.expect.unchanged,
        };

        const result = assertRun(expectation, testCase.mutatedChecksumReport, priorObs, currentObs);
        expect(result.status).toBe('fail');
        expect(result.failures.some(f => f.includes('proxy:capture-proxy') && f.includes('expected unchanged'))).toBe(true);
    });
});
