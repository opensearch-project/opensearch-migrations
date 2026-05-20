/**
 * Built-in mutator registry contents.
 *
 * Mirrors `builtinActors.ts`: modules that want a populated mutator
 * registry can start from here and layer more on top. Keep the list
 * narrow — new entries should be justified by at least one spec in
 * the repo.
 */

import { Mutator } from "./mutators";
import { proxyNumThreadsMutator, snapshotMigrationMaxConnectionsMutator } from "./mutators";

/**
 * Returns fresh mutator instances. Each call returns new objects so
 * callers can safely own them.
 */
export function builtinMutators(): Mutator[] {
    return [
        proxyNumThreadsMutator(),
        snapshotMigrationMaxConnectionsMutator(),
    ];
}

/**
 * The names of the mutators returned by `builtinMutators()`. Stable
 * enough to reference in tests and documentation.
 */
export const BUILTIN_MUTATOR_NAMES: readonly string[] = [
    "proxy-numThreads",
    "snapshotMigration-maxConnections",
] as const;
