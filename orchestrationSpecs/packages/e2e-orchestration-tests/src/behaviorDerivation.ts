/**
 * Behavior derivation — turn a pair of ObservedComponent states into
 * a `Behavior` label for assertLogic to consume.
 *
 * The rules here mirror plan step 6 ("Define behavior from CRD state
 * deltas"):
 *
 *   - `ran`      : no prior successful observation for this resource
 *                  in the case (first time we see it reach a terminal
 *                  state). The baseline run has no prior observation,
 *                  so every completed component is `ran`.
 *   - `skipped`  : checksum and UID unchanged from the prior
 *                  observation, and the current phase is terminal but
 *                  not a held state like `Blocked`/`Suspended`.
 *   - `reran`    : checksum or generation changed between prior and
 *                  current observations and the current phase is
 *                  terminal.
 *   - `blocked`  : current phase is `Blocked`.
 *   - `gated`    : current phase is `Suspended` (legacy design-doc
 *                  terminology) or `Paused`.
 *   - `unstarted`: current phase indicates the CRD exists but has not
 *                  progressed this run (`Initialized`, `Pending`, or a
 *                  missing phase field).
 *
 * The input is deliberately narrow — no config diffing, no workflow
 * node inspection. Everything this module needs is on `ObservedComponent`.
 */

import { Behavior, ObservedComponent } from "./types";

/**
 * Phases the migration framework reports for a fully settled
 * component that completed without being held.
 */
const COMPLETED_PHASES: ReadonlySet<string> = new Set(["Ready", "Completed", "Skipped"]);

const BLOCKED_PHASES: ReadonlySet<string> = new Set(["Blocked"]);

const GATED_PHASES: ReadonlySet<string> = new Set(["Suspended", "Paused"]);

const UNSTARTED_PHASES: ReadonlySet<string> = new Set([
    "Initialized",
    "Pending",
    "",
]);

export interface DeriveBehaviorInput {
    /** Observation from the prior run for this component, or null if this is the first run. */
    prev: ObservedComponent | null;
    /** Observation from the current run. */
    curr: ObservedComponent;
}

/**
 * Return the Behavior label implied by the pair of observations.
 *
 * This never throws. Callers pass the output forward into
 * `assertNoViolations`, which enforces the rules of each checkpoint.
 */
export function deriveBehavior({ prev, curr }: DeriveBehaviorInput): Behavior {
    const phase = curr.phase ?? "";

    if (BLOCKED_PHASES.has(phase)) return "blocked";
    if (GATED_PHASES.has(phase)) return "gated";
    if (UNSTARTED_PHASES.has(phase)) return "unstarted";

    if (!COMPLETED_PHASES.has(phase)) {
        // Defensive: an unexpected terminal-ish phase we don't
        // recognise. Treat as unstarted so assertLogic flags it as a
        // constraint violation rather than silently passing.
        return "unstarted";
    }

    // Phase is Ready/Completed/Skipped — decide between ran / reran / skipped.
    if (prev === null) {
        return "ran";
    }

    const uidChanged = !!prev.uid && !!curr.uid && prev.uid !== curr.uid;
    const checksumChanged =
        !!prev.configChecksum &&
        !!curr.configChecksum &&
        prev.configChecksum !== curr.configChecksum;
    const generationChanged =
        typeof prev.generation === "number" &&
        typeof curr.generation === "number" &&
        prev.generation !== curr.generation;

    if (uidChanged || checksumChanged || generationChanged) {
        return "reran";
    }

    return "skipped";
}
