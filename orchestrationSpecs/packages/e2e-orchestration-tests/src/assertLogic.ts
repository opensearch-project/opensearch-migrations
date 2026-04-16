/**
 * Assert Logic
 *
 * Compares live CRD observations against expected behavior.
 * Supports three run modes:
 *   - allCompleted: baseline — every component should be Ready/Completed
 *   - allSkipped: noop — every component checksum unchanged from prior
 *   - selective: mutation — specific components reran, others unchanged
 *
 * Checks both checksums AND resource phase.
 */
import type {
    ChecksumReport, RunExpectation,
    ScenarioObservation,
} from './types';

export type ComponentAssertResult = {
    component: string;
    status: 'pass' | 'fail';
    expectation: string;
    details: {
        expectedChecksum: string;
        observedChecksum: string;
        priorChecksum: string;
        observedPhase: string;
        checksumMatchesExpected: boolean;
        changedFromPrior: boolean;
    };
    failures: string[];
};

export type AssertResult = {
    status: 'pass' | 'fail';
    components: ComponentAssertResult[];
    failures: string[];
};

const TERMINAL_PHASES = new Set(['Ready', 'Completed']);

/**
 * Compare observations against expectations.
 * Pure function, no I/O.
 */
export function assertRun(
    expected: RunExpectation,
    expectedChecksums: ChecksumReport,
    priorObservation: ScenarioObservation | null,
    currentObservation: ScenarioObservation,
): AssertResult {
    const components: ComponentAssertResult[] = [];
    const allFailures: string[] = [];

    const allKeys = Object.keys(expectedChecksums.components);

    if (expected.mode === 'allCompleted') {
        // Baseline: every component should reach a terminal phase with the expected checksum
        for (const key of allKeys) {
            const result = assertComponent(key, 'completed', expectedChecksums, priorObservation, currentObservation);
            components.push(result);
            allFailures.push(...result.failures);
        }
    } else if (expected.mode === 'allSkipped') {
        // Noop: every component checksum should be unchanged from prior
        for (const key of allKeys) {
            const result = assertComponent(key, 'unchanged', expectedChecksums, priorObservation, currentObservation);
            components.push(result);
            allFailures.push(...result.failures);
        }
    } else {
        // Selective: specific components reran, others unchanged
        for (const key of expected.reran) {
            const result = assertComponent(key, 'reran', expectedChecksums, priorObservation, currentObservation);
            components.push(result);
            allFailures.push(...result.failures);
        }
        for (const key of expected.unchanged) {
            const result = assertComponent(key, 'unchanged', expectedChecksums, priorObservation, currentObservation);
            components.push(result);
            allFailures.push(...result.failures);
        }
    }

    return {
        status: allFailures.length === 0 ? 'pass' : 'fail',
        components,
        failures: allFailures,
    };
}

function assertComponent(
    key: string,
    expectation: 'completed' | 'reran' | 'unchanged',
    expectedChecksums: ChecksumReport,
    priorObservation: ScenarioObservation | null,
    currentObservation: ScenarioObservation,
): ComponentAssertResult {
    const failures: string[] = [];

    const expectedEntry = expectedChecksums.components[key];
    const prior = priorObservation?.resources[key];
    const current = currentObservation.resources[key];

    if (!expectedEntry) {
        failures.push(`${key}: not found in expected checksum report`);
    }
    if (!current) {
        failures.push(`${key}: not found in current observation`);
    }

    const expectedChecksum = expectedEntry?.configChecksum ?? '';
    const observedChecksum = current?.configChecksum ?? '';
    const priorChecksum = prior?.configChecksum ?? '';
    const observedPhase = current?.phase ?? '';

    const checksumMatchesExpected = observedChecksum === expectedChecksum;
    const changedFromPrior = prior ? observedChecksum !== priorChecksum : true;

    // Phase assertion: component should be in a terminal state
    if (current && !TERMINAL_PHASES.has(observedPhase)) {
        failures.push(
            `${key}: expected terminal phase (Ready/Completed), observed '${observedPhase}'`
        );
    }

    // Checksum assertion: observed should match what the config processor computed
    if (!checksumMatchesExpected) {
        failures.push(
            `${key}: checksum mismatch — expected ${expectedChecksum}, observed ${observedChecksum}`
        );
    }

    // Behavioral assertion based on expectation mode
    if (expectation === 'reran' && !changedFromPrior) {
        failures.push(
            `${key}: expected to rerun (checksum should differ from prior) but unchanged (${priorChecksum})`
        );
    }
    if (expectation === 'unchanged' && changedFromPrior && prior) {
        failures.push(
            `${key}: expected unchanged but checksum changed from ${priorChecksum} to ${observedChecksum}`
        );
    }
    // 'completed' mode: just needs terminal phase + correct checksum (already checked above)

    return {
        component: key,
        status: failures.length === 0 ? 'pass' : 'fail',
        expectation,
        details: {
            expectedChecksum,
            observedChecksum,
            priorChecksum,
            observedPhase,
            checksumMatchesExpected,
            changedFromPrior,
        },
        failures,
    };
}

/** Format an AssertResult for human-readable output. */
export function formatAssertResult(result: AssertResult): string {
    const lines: string[] = [];
    for (const c of result.components) {
        const icon = c.status === 'pass' ? '✓' : '✗';
        lines.push(`  ${icon} ${c.component}: ${c.expectation} phase=${c.details.observedPhase} (${c.details.priorChecksum || '(none)'} → ${c.details.observedChecksum})`);
        for (const f of c.failures) {
            lines.push(`    FAIL: ${f}`);
        }
    }
    return lines.join('\n');
}
