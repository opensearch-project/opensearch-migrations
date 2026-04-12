import { expandMatrix } from '../src/matrixExpander';
import { buildChecksumReport } from '../src/checksumReporter';
import { defaultMutatorRegistry } from '../src/approvedMutators';
import { loadFullMigrationConfig } from './helpers';
import type { ApprovedMutator, MatrixSpec } from '../src/types';

describe('expandMatrix', () => {
    it('focus-change: proxy mutation reruns proxy + all downstream', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'safe', patterns: ['focus-change'] }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(1);
        expect(cases[0].name).toBe('proxy:capture-proxy/focus-change/proxy-noCapture-toggle');

        // noCapture is checksumFor(snapshot, replayer), so proxy + snapshot + migration + replay should rerun
        expect(cases[0].expect.reran).toContain('proxy:capture-proxy');
        expect(cases[0].expect.reran).toContain('snapshot:source-snap1');
        expect(cases[0].expect.reran).toContain('replay:capture-proxy-target-replay1');
        // kafka should be unchanged (upstream prerequisite)
        expect(cases[0].expect.unchanged).toContain('kafka:default');
    });

    it('immediate-dependent-change: replayer mutation reruns only replayer', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'safe', patterns: ['immediate-dependent-change'] }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(1);
        expect(cases[0].name).toBe('proxy:capture-proxy/immediate-dependent-change/replayer-speedupFactor');

        // Only the replayer should rerun (it has no downstream dependents)
        expect(cases[0].expect.reran).toEqual(['replay:capture-proxy-target-replay1']);
        // Everything else unchanged
        expect(cases[0].expect.unchanged).toContain('proxy:capture-proxy');
        expect(cases[0].expect.unchanged).toContain('kafka:default');
        expect(cases[0].expect.unchanged).toContain('snapshot:source-snap1');
    });

    it('transitive-dependent-change: migration mutation reruns only migration', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'safe', patterns: ['transitive-dependent-change'] }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(1);
        expect(cases[0].name).toBe('proxy:capture-proxy/transitive-dependent-change/rfs-maxConnections');

        // Only the migration should rerun
        expect(cases[0].expect.reran).toEqual(['snapshotMigration:source-target-snap1']);
        expect(cases[0].expect.unchanged).toContain('proxy:capture-proxy');
        expect(cases[0].expect.unchanged).toContain('snapshot:source-snap1');
    });

    it('returns empty for no matching mutators', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'impossible', patterns: ['focus-change'] }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(0);
    });

    it('requireFullCoverage throws when patterns have no mutators', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{
                changeClass: 'gated',
                patterns: ['focus-gated-change'],
                requireFullCoverage: true,
            }],
        };

        await expect(expandMatrix(spec, report, defaultMutatorRegistry, config, report))
            .rejects.toThrow('requireFullCoverage');
    });

    it('rejects a mis-tagged mutator that changes the wrong component', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);

        // A mutator that changes the proxy but is tagged as immediate-dependent-change
        const badMutator: ApprovedMutator = {
            id: 'mis-tagged',
            path: 'traffic.proxies.capture-proxy.proxyConfig.noCapture',
            changeClass: 'safe',
            patterns: ['immediate-dependent-change'],
            apply: (c) => {
                const clone = JSON.parse(JSON.stringify(c)) as Record<string, unknown>;
                const traffic = clone['traffic'] as Record<string, unknown>;
                const proxies = traffic['proxies'] as Record<string, unknown>;
                const proxy = proxies['capture-proxy'] as Record<string, unknown>;
                const proxyConfig = proxy['proxyConfig'] as Record<string, unknown>;
                proxyConfig['noCapture'] = true;
                return clone;
            },
            rationale: 'intentionally mis-tagged for testing',
        };

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'safe', patterns: ['immediate-dependent-change'] }],
        };

        await expect(expandMatrix(spec, report, [badMutator], config, report))
            .rejects.toThrow('also changed upstream');
    });
});
