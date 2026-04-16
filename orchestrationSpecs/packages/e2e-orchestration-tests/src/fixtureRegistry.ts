/**
 * Fixture Registry
 *
 * Named fixtures that test specs reference by string ID.
 * The registry maps names to FixtureAction definitions.
 */
import type { FixtureAction } from './fixtures';
import {
    deleteTargetIndices,
    deleteSourceSnapshots,
    compareIndices,
    verifyProxyReady,
    verifyReplayerReady,
    verifyProxyBlocked,
    verifySourceIndices,
} from './fixtures';

const registry = new Map<string, FixtureAction>([
    [deleteTargetIndices.name, deleteTargetIndices],
    [deleteSourceSnapshots.name, deleteSourceSnapshots],
    [compareIndices.name, compareIndices],
    [verifyProxyReady.name, verifyProxyReady],
    [verifyReplayerReady.name, verifyReplayerReady],
    [verifyProxyBlocked.name, verifyProxyBlocked],
    [verifySourceIndices.name, verifySourceIndices],
]);

export function resolveFixture(name: string): FixtureAction {
    const action = registry.get(name);
    if (!action) {
        throw new Error(`Unknown fixture: "${name}". Available: ${[...registry.keys()].join(', ')}`);
    }
    return action;
}

export function resolveFixtures(names: string[]): FixtureAction[] {
    return names.map(resolveFixture);
}
