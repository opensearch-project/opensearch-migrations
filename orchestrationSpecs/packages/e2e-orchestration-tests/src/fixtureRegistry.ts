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
} from './fixtures';

const registry = new Map<string, FixtureAction>([
    [deleteTargetIndices.name, deleteTargetIndices],
    [deleteSourceSnapshots.name, deleteSourceSnapshots],
    [compareIndices.name, compareIndices],
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
