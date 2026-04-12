import {
    buildPassingResult, buildFailingResult,
    buildScenarioReport, formatReport,
} from '../src/reportSchema';
import type { ExpandedTestCase } from '../src/types';

const mockTestCase: ExpandedTestCase = {
    name: 'proxy:capture-proxy/focus-change/proxy-noCapture-toggle',
    focus: 'proxy:capture-proxy',
    pattern: 'focus-change',
    changeClass: 'safe',
    mutatorId: 'proxy-noCapture-toggle',
    baselineChecksumReport: { components: {} },
    mutatedConfig: {},
    mutatedChecksumReport: { components: {} },
    expect: {
        reran: ['proxy:capture-proxy'],
        unchanged: ['kafka:default', 'snapshot:source-snap1'],
    },
};

describe('reportSchema', () => {
    it('builds a passing result', () => {
        const result = buildPassingResult(mockTestCase);
        expect(result).toMatchSnapshot();
        expect(result.status).toBe('passed');
        expect(result.observed['proxy:capture-proxy']).toEqual({ phase: 'completed', changed: true });
        expect(result.observed['kafka:default']).toEqual({ phase: 'skipped', changed: false });
        expect(result.failures).toBeUndefined();
    });

    it('builds a failing result', () => {
        const observed = {
            'proxy:capture-proxy': { phase: 'completed', changed: true },
            'kafka:default': { phase: 'completed', changed: true },
            'snapshot:source-snap1': { phase: 'skipped', changed: false },
        };
        const result = buildFailingResult(mockTestCase, observed, [
            'kafka:default was expected unchanged but changed',
        ]);
        expect(result).toMatchSnapshot();
        expect(result.status).toBe('failed');
        expect(result.failures).toHaveLength(1);
    });

    it('builds a scenario report', () => {
        const passing = buildPassingResult(mockTestCase);
        const report = buildScenarioReport('test-scenario', [passing], {
            selectedCases: ['focus-change'],
            expandedCases: [mockTestCase.name],
            uncoveredCases: [],
        });
        expect(report).toMatchSnapshot();
        expect(report.status).toBe('passed');
        expect(report.summary.generated).toBe(1);
    });

    it('formats a report as human-readable text', () => {
        const passing = buildPassingResult(mockTestCase);
        const report = buildScenarioReport('test-scenario', [passing], {
            selectedCases: ['focus-change'],
            expandedCases: [mockTestCase.name],
            uncoveredCases: [],
        });
        const text = formatReport(report);
        expect(text).toContain('Scenario: test-scenario');
        expect(text).toContain('Status: PASSED');
        expect(text).toContain('✓');
    });
});
