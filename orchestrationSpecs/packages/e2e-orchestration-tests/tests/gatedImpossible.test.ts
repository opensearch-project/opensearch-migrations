import { expandMatrix } from '../src/matrixExpander';
import { buildChecksumReport } from '../src/checksumReporter';
import { defaultMutatorRegistry } from '../src/approvedMutators';
import { loadProxyOnlyConfig } from './helpers';
import type { MatrixSpec } from '../src/types';

describe('gated and impossible case expansion', () => {
    it('focus-gated-change: expands proxy-noCapture-toggle as gated', async () => {
        const config = loadProxyOnlyConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'gated', patterns: ['focus-gated-change'] }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(1);
        expect(cases[0].name).toBe('proxy:capture-proxy/focus-gated-change/proxy-noCapture-toggle');
        expect(cases[0].changeClass).toBe('gated');
        expect(cases[0].expect.reran).toContain('proxy:capture-proxy');
    });

    it('immediate-dependent-gated-change: expands replayer-removeAuthHeader', async () => {
        const config = loadProxyOnlyConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'gated', patterns: ['immediate-dependent-gated-change'] }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(1);
        expect(cases[0].name).toBe('proxy:capture-proxy/immediate-dependent-gated-change/replayer-removeAuthHeader');
        expect(cases[0].changeClass).toBe('gated');
        expect(cases[0].expect.reran).toEqual(['replay:capture-proxy-target-replay1']);
        expect(cases[0].expect.unchanged).toContain('proxy:capture-proxy');
    });

    it('immediate-dependent-impossible-change: expands replayer-invalidTimeout', async () => {
        const config = loadProxyOnlyConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{ changeClass: 'impossible', patterns: ['immediate-dependent-impossible-change'] }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(1);
        expect(cases[0].name).toBe('proxy:capture-proxy/immediate-dependent-impossible-change/replayer-invalidTimeout');
        expect(cases[0].changeClass).toBe('impossible');
        expect(cases[0].expect.reran).toEqual(['replay:capture-proxy-target-replay1']);
    });

    it('mixed selection: safe + gated patterns expand independently', async () => {
        const config = loadProxyOnlyConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [
                { changeClass: 'safe', patterns: ['focus-change'] },
                { changeClass: 'gated', patterns: ['focus-gated-change'] },
            ],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(2);
        expect(cases.map(c => c.changeClass)).toEqual(['safe', 'gated']);
    });

    it('requireFullCoverage passes when gated mutators cover all patterns', async () => {
        const config = loadProxyOnlyConfig();
        const report = await buildChecksumReport(config);

        const spec: MatrixSpec = {
            focus: 'proxy:capture-proxy',
            select: [{
                changeClass: 'gated',
                patterns: ['focus-gated-change', 'immediate-dependent-gated-change'],
                requireFullCoverage: true,
            }],
        };

        const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
        expect(cases).toHaveLength(2);
    });
});
