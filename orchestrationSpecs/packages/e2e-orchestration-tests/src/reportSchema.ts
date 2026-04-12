import type {
    ExpandedTestCase, TestCaseResult, ScenarioReport, ExpandedExpectation,
} from './types';

export function buildPassingResult(testCase: ExpandedTestCase): TestCaseResult {
    const observed: Record<string, { phase: string; changed: boolean }> = {};
    for (const key of testCase.expect.reran) {
        observed[key] = { phase: 'completed', changed: true };
    }
    for (const key of testCase.expect.unchanged) {
        observed[key] = { phase: 'skipped', changed: false };
    }
    return {
        name: testCase.name,
        focus: testCase.focus,
        pattern: testCase.pattern,
        changeClass: testCase.changeClass,
        mutatorId: testCase.mutatorId,
        status: 'passed',
        expect: testCase.expect,
        observed,
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
    coverage: { selectedCases: string[]; expandedCases: string[]; uncoveredCases: string[] },
): ScenarioReport {
    const passed = results.filter(r => r.status === 'passed').length;
    const failed = results.filter(r => r.status === 'failed').length;
    return {
        scenario,
        status: failed > 0 ? 'failed' : 'passed',
        summary: { generated: results.length, passed, failed },
        expandedTests: results,
        coverage,
    };
}

export function formatReport(report: ScenarioReport): string {
    const lines: string[] = [
        `Scenario: ${report.scenario}`,
        `Status: ${report.status.toUpperCase()}`,
        `Summary: ${report.summary.generated} tests, ${report.summary.passed} passed, ${report.summary.failed} failed`,
        '',
    ];

    for (const test of report.expandedTests) {
        const icon = test.status === 'passed' ? '✓' : '✗';
        lines.push(`  ${icon} ${test.name} [${test.changeClass}]`);
        if (test.failures && test.failures.length > 0) {
            for (const f of test.failures) {
                lines.push(`    FAIL: ${f}`);
            }
        }
    }

    if (report.coverage.uncoveredCases.length > 0) {
        lines.push('');
        lines.push('Uncovered cases:');
        for (const c of report.coverage.uncoveredCases) {
            lines.push(`  - ${c}`);
        }
    }

    return lines.join('\n');
}
