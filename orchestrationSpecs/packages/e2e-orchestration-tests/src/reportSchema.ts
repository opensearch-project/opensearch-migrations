import type {
    ExpandedTestCase, TestCaseResult, ScenarioReport, ExpandedExpectation,
} from './types';

/**
 * Build a passing (or partial) result for a test case.
 *
 * When `realObserved` is provided, it is used directly. Otherwise the observed
 * map is fabricated from expectations — phases like 'completed' and 'skipped'
 * are synthetic labels that mirror what we expected, not what the cluster
 * actually reported.
 *
 * When `caveats` is non-empty the result is marked 'partial' instead of
 * 'passed', indicating the runner could not fully exercise the spec's intended
 * behavior (e.g. gated spec run with skipApprovals).
 *
 * `beforeAction` and `afterAction` are not yet populated — that requires
 * runtime support added in Phase 16/17.
 */
export function buildPassingResult(
    testCase: ExpandedTestCase,
    realObserved?: Record<string, { phase: string; changed: boolean }>,
    caveats?: string[],
): TestCaseResult {
    const observed = realObserved ?? (() => {
        const synth: Record<string, { phase: string; changed: boolean }> = {};
        for (const key of testCase.expect.reran) {
            synth[key] = { phase: 'completed', changed: true };
        }
        for (const key of testCase.expect.unchanged) {
            synth[key] = { phase: 'skipped', changed: false };
        }
        return synth;
    })();
    const hasCaveats = caveats && caveats.length > 0;
    return {
        name: testCase.name,
        focus: testCase.focus,
        pattern: testCase.pattern,
        changeClass: testCase.changeClass,
        mutatorId: testCase.mutatorId,
        status: hasCaveats ? 'partial' : 'passed',
        expect: testCase.expect,
        observed,
        ...(hasCaveats ? { caveats } : {}),
    };
}

export function buildFailingResult(
    testCase: ExpandedTestCase,
    observed: Record<string, { phase: string; changed: boolean }>,
    failures: string[],
): TestCaseResult {
    return {
        name: testCase.name,
        focus: testCase.focus,
        pattern: testCase.pattern,
        changeClass: testCase.changeClass,
        mutatorId: testCase.mutatorId,
        status: 'failed',
        expect: testCase.expect,
        observed,
        failures,
    };
}

export function buildScenarioReport(
    scenario: string,
    results: TestCaseResult[],
    coverage: { selectedCases: string[]; expandedCases: string[]; skippedCases: string[]; uncoveredCases: string[] },
): ScenarioReport {
    const passed = results.filter(r => r.status === 'passed').length;
    const failed = results.filter(r => r.status === 'failed').length;
    const partial = results.filter(r => r.status === 'partial').length;
    const status = failed > 0 ? 'failed' : partial > 0 ? 'partial' : 'passed';
    return {
        scenario,
        status,
        summary: { generated: results.length, passed, failed, partial },
        expandedTests: results,
        coverage,
    };
}

export function formatReport(report: ScenarioReport): string {
    const lines: string[] = [
        `Scenario: ${report.scenario}`,
        `Status: ${report.status.toUpperCase()}`,
        `Summary: ${report.summary.generated} tests, ${report.summary.passed} passed, ${report.summary.failed} failed, ${report.summary.partial} partial`,
        '',
    ];

    for (const test of report.expandedTests) {
        const icon = test.status === 'passed' ? '✓' : test.status === 'partial' ? '◐' : '✗';
        lines.push(`  ${icon} ${test.name} [${test.changeClass}]`);
        if (test.caveats && test.caveats.length > 0) {
            for (const c of test.caveats) {
                lines.push(`    CAVEAT: ${c}`);
            }
        }
        if (test.beforeAction) {
            lines.push('    before-action:');
            for (const [k, v] of Object.entries(test.beforeAction)) {
                lines.push(`      ${k}: phase=${v.phase} checksum=${v.configChecksum}`);
            }
        }
        if (test.afterAction) {
            lines.push('    after-action:');
            for (const [k, v] of Object.entries(test.afterAction)) {
                lines.push(`      ${k}: phase=${v.phase} checksum=${v.configChecksum}`);
            }
        }
        if (test.failures && test.failures.length > 0) {
            for (const f of test.failures) {
                lines.push(`    FAIL: ${f}`);
            }
        }
    }

    if (report.coverage.skippedCases.length > 0) {
        lines.push('');
        lines.push('Skipped (expanded but not executed):');
        for (const c of report.coverage.skippedCases) {
            lines.push(`  - ${c}`);
        }
    }

    if (report.coverage.uncoveredCases.length > 0) {
        lines.push('');
        lines.push('Uncovered (no mutator found):');
        for (const c of report.coverage.uncoveredCases) {
            lines.push(`  - ${c}`);
        }
    }

    return lines.join('\n');
}
