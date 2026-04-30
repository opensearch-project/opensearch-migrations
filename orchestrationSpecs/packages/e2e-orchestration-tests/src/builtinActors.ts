/**
 * Built-in actor stubs.
 *
 * The design doc describes a handful of actor names that most specs
 * will reference (`delete-target-indices`, `delete-source-snapshots`,
 * etc.). The first-slice runner does not yet implement the cluster
 * operations those names imply; but the CLI still needs to *resolve*
 * them so specs that declare them in `lifecycle.setup`/`.teardown`
 * can run end-to-end without tripping the "unknown actor" guard.
 *
 * Each built-in here is intentionally a stub that:
 *   1. logs a diagnostic via the caller-supplied logger, and
 *   2. throws a `NotImplementedActorError`, so the runner records a
 *      diagnostic and (for teardown) moves on to the next actor.
 *
 * Making the stubs throw means a test that claims to have torn down
 * target indices does not silently succeed — the snapshot records
 * "teardown actor 'delete-target-indices' failed: not implemented in
 * first slice". That's honest signalling for the first live runs.
 *
 * When the real implementations land, replace each stub in this file
 * (or register a full-featured actor under the same name via
 * `extraActors` in `RunFromSpecOptions`).
 */

import { Actor } from "./actors";

export class NotImplementedActorError extends Error {
    constructor(name: string) {
        super(
            `actor '${name}' is a first-slice stub and has no implementation yet`,
        );
        this.name = "NotImplementedActorError";
    }
}

function stub(name: string): Actor {
    return {
        name,
        run: async () => {
            throw new NotImplementedActorError(name);
        },
    };
}

/**
 * Names the design doc's example specs reference. Keeping the list
 * small — additions should be justified by at least one spec in the
 * repo or a reviewed mutator that needs the hook.
 */
export const BUILTIN_ACTOR_NAMES: readonly string[] = [
    "delete-target-indices",
    "delete-source-snapshots",
] as const;

export function builtinActors(): Actor[] {
    return BUILTIN_ACTOR_NAMES.map(stub);
}
