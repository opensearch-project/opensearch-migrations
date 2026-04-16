/**
 * Test Spec Loader
 *
 * Loads a test spec JSON file and resolves fixture references
 * into concrete FixtureAction objects.
 */
import fs from 'node:fs';
import path from 'node:path';
import type { MatrixSpec } from './types';
import type { TestFixtures, ApprovalGate } from './fixtures';
import { resolveFixtures } from './fixtureRegistry';

/** Raw shape of the JSON file — fixtures are string references */
type TestSpecJson = {
    baseConfig: string;
    matrix: MatrixSpec;
    fixtures: {
        setup: string[];
        cleanup: string[];
        approvalGates: Array<{
            approvePattern: string;
            description: string;
            validations: string[];
        }>;
    };
};

export type TestSpec = {
    baseConfigPath: string;
    matrix: MatrixSpec;
    fixtures: TestFixtures;
};

export function loadTestSpec(specPath: string): TestSpec {
    const raw = JSON.parse(fs.readFileSync(specPath, 'utf-8')) as TestSpecJson;
    const specDir = path.dirname(specPath);

    const gates: ApprovalGate[] = raw.fixtures.approvalGates.map(g => ({
        approvePattern: g.approvePattern,
        description: g.description,
        validations: resolveFixtures(g.validations),
    }));

    return {
        baseConfigPath: path.resolve(specDir, raw.baseConfig),
        matrix: raw.matrix,
        fixtures: {
            setup: resolveFixtures(raw.fixtures.setup),
            cleanup: resolveFixtures(raw.fixtures.cleanup),
            approvalGates: gates,
        },
    };
}
