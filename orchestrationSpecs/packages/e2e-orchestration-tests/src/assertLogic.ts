/**
 * assertLogic — pure constraint walk over an observed snapshot.
 *
 * Implemented checkpoints:
 *   - `baseline-complete`: observation anchor only; no assertions.
 *   - `noop`: every component must be `skipped`.
 *   - `mutated-complete`: safe-case terminal — subject and downstream
 *     must be `reran`; upstream and independent components must be
 *     `skipped`.
 *
 * Remaining checkpoints (gated, impossible) return a single
 * `unimplemented-checkpoint` violation so downstream work sees loud
 * signalling rather than silent passes.
 *
 * The function is deliberately pure: inputs in, violations out. No
 * I/O, no clock, no logging. Callers attach the returned violations
 * to their snapshot.
 */

import { ComponentTopology } from "./componentTopology";
import {
    Checkpoint,
    ComponentId,
    ObservedComponent,
    Violation,
} from "./types";

export interface AssertNoViolationsInput {
    checkpoint: Checkpoint;
    /**
     * Required for `mutated-complete` (and future gated/impossible
     * checkpoints). `null` is only valid on `noop`.
     */
    subject: ComponentId | null;
    topology: ComponentTopology;
    /**
     * All components the framework observed at this checkpoint, keyed
     * by ComponentId. The caller (runner) is responsible for making
     * sure each entry has `behavior` populated — see
     * `behaviorDerivation.ts`.
     */
    observations: Readonly<Record<ComponentId, ObservedComponent>>;
}

/**
 * Run the constraint walk for the given checkpoint and return any
 * violations. Empty array = pass.
 */
export function assertNoViolations(input: AssertNoViolationsInput): Violation[] {
    switch (input.checkpoint) {
        case "baseline-complete":
            return [];
        case "noop":
            return assertNoop(input);
        case "mutated-complete":
            return assertMutatedComplete(input);
        case "before-approval":
        case "after-approval":
        case "on-blocked":
        case "after-approve-without-reset":
        case "after-reset":
        case "after-approve":
            return [
                {
                    type: "unimplemented-checkpoint",
                    checkpoint: input.checkpoint,
                    message: `checkpoint '${input.checkpoint}' is not yet implemented in assertLogic — this is the noop slice`,
                },
            ];
    }
}

/**
 * Noop constraint: every component in the topology must have
 * `behavior === "skipped"`. A missing observation or any other
 * behavior is a violation.
 */
function assertNoop(input: AssertNoViolationsInput): Violation[] {
    const violations: Violation[] = [];

    for (const componentId of input.topology.components) {
        const observed = input.observations[componentId];
        if (!observed) {
            violations.push({
                type: "missing-observation",
                checkpoint: "noop",
                componentId,
                message: `no observation for ${componentId} at checkpoint 'noop'`,
                details: { expectedBehavior: "skipped" },
            });
            continue;
        }

        if (observed.behavior === undefined) {
            violations.push({
                type: "missing-observation",
                checkpoint: "noop",
                componentId,
                message: `observation for ${componentId} at checkpoint 'noop' is missing 'behavior' — runner must populate it before calling assertNoViolations`,
                details: {
                    phase: observed.phase,
                    expectedBehavior: "skipped",
                },
            });
            continue;
        }

        if (observed.behavior !== "skipped") {
            violations.push({
                type: "noop-not-skipped",
                checkpoint: "noop",
                componentId,
                message: `${componentId} should have been 'skipped' at noop checkpoint; observed '${observed.behavior}' (phase='${observed.phase}')`,
                details: {
                    phase: observed.phase,
                    observedBehavior: observed.behavior,
                    expectedBehavior: "skipped",
                },
            });
        }
    }

    return violations;
}

/**
 * Safe `mutated-complete` constraint walk:
 *   1. Subject must have `behavior === 'reran'` (change-class).
 *   2. Every downstream dependent must have `behavior === 'reran'` (cascade).
 *   3. Every upstream prerequisite must have `behavior === 'skipped'`.
 *   4. Every independent component must have `behavior === 'skipped'` (independence).
 *
 * Missing observations produce `missing-observation` violations.
 * `subject: null` is a programmer error — produces a single violation.
 */
function assertMutatedComplete(input: AssertNoViolationsInput): Violation[] {
    if (input.subject === null) {
        return [
            {
                type: "change-class",
                checkpoint: "mutated-complete",
                message: "subject is null at 'mutated-complete' checkpoint — this is a programmer error; the runner must provide a subject for mutation checkpoints",
            },
        ];
    }

    const violations: Violation[] = [];
    const { subject, topology, observations } = input;

    // Constraint 1: subject must have reran
    const subjectObs = observations[subject];
    if (!subjectObs) {
        violations.push({
            type: "missing-observation",
            checkpoint: "mutated-complete",
            componentId: subject,
            message: `no observation for subject ${subject} at checkpoint 'mutated-complete'`,
            details: { expectedBehavior: "reran" },
        });
    } else if (subjectObs.behavior === undefined) {
        violations.push({
            type: "missing-observation",
            checkpoint: "mutated-complete",
            componentId: subject,
            message: `observation for subject ${subject} at checkpoint 'mutated-complete' is missing 'behavior'`,
            details: { phase: subjectObs.phase, expectedBehavior: "reran" },
        });
    } else if (subjectObs.behavior !== "reran") {
        violations.push({
            type: "change-class",
            checkpoint: "mutated-complete",
            componentId: subject,
            message: `subject ${subject} should have been 'reran' at mutated-complete; observed '${subjectObs.behavior}' (phase='${subjectObs.phase}')`,
            details: {
                phase: subjectObs.phase,
                observedBehavior: subjectObs.behavior,
                expectedBehavior: "reran",
            },
        });
    }

    // Constraint 2: every downstream dependent must have reran (cascade)
    for (const dep of topology.downstreamOf(subject)) {
        const obs = observations[dep];
        if (!obs) {
            violations.push({
                type: "missing-observation",
                checkpoint: "mutated-complete",
                componentId: dep,
                message: `no observation for dependent ${dep} at checkpoint 'mutated-complete'`,
                details: { expectedBehavior: "reran" },
            });
            continue;
        }
        if (obs.behavior === undefined) {
            violations.push({
                type: "missing-observation",
                checkpoint: "mutated-complete",
                componentId: dep,
                message: `observation for dependent ${dep} at checkpoint 'mutated-complete' is missing 'behavior'`,
                details: { phase: obs.phase, expectedBehavior: "reran" },
            });
            continue;
        }
        if (obs.behavior !== "reran") {
            violations.push({
                type: "cascade",
                checkpoint: "mutated-complete",
                componentId: dep,
                message: `dependent ${dep} should have been 'reran' (cascade from ${subject}); observed '${obs.behavior}' (phase='${obs.phase}')`,
                details: {
                    phase: obs.phase,
                    observedBehavior: obs.behavior,
                    expectedBehavior: "reran",
                    cascadeFrom: subject,
                },
            });
        }
    }

    // Constraint 3: every upstream prerequisite must be skipped
    for (const up of topology.upstreamOf(subject)) {
        const obs = observations[up];
        if (!obs) {
            violations.push({
                type: "missing-observation",
                checkpoint: "mutated-complete",
                componentId: up,
                message: `no observation for upstream prerequisite ${up} at checkpoint 'mutated-complete'`,
                details: { expectedBehavior: "skipped", upstreamOf: subject },
            });
            continue;
        }
        if (obs.behavior === undefined) {
            violations.push({
                type: "missing-observation",
                checkpoint: "mutated-complete",
                componentId: up,
                message: `observation for upstream prerequisite ${up} at checkpoint 'mutated-complete' is missing 'behavior'`,
                details: { phase: obs.phase, expectedBehavior: "skipped", upstreamOf: subject },
            });
            continue;
        }
        if (obs.behavior !== "skipped") {
            violations.push({
                type: "upstream-reran",
                checkpoint: "mutated-complete",
                componentId: up,
                message: `upstream prerequisite ${up} should have been 'skipped' at mutated-complete; observed '${obs.behavior}' (phase='${obs.phase}')`,
                details: {
                    phase: obs.phase,
                    observedBehavior: obs.behavior,
                    expectedBehavior: "skipped",
                    upstreamOf: subject,
                },
            });
        }
    }

    // Constraint 4: every independent component must be skipped
    for (const ind of topology.independentOf(subject)) {
        const obs = observations[ind];
        if (!obs) {
            violations.push({
                type: "missing-observation",
                checkpoint: "mutated-complete",
                componentId: ind,
                message: `no observation for independent ${ind} at checkpoint 'mutated-complete'`,
                details: { expectedBehavior: "skipped" },
            });
            continue;
        }
        if (obs.behavior === undefined) {
            violations.push({
                type: "missing-observation",
                checkpoint: "mutated-complete",
                componentId: ind,
                message: `observation for independent ${ind} at checkpoint 'mutated-complete' is missing 'behavior'`,
                details: { phase: obs.phase, expectedBehavior: "skipped" },
            });
            continue;
        }
        if (obs.behavior !== "skipped") {
            violations.push({
                type: "independence",
                checkpoint: "mutated-complete",
                componentId: ind,
                message: `independent ${ind} should have been 'skipped' at mutated-complete; observed '${obs.behavior}' (phase='${obs.phase}')`,
                details: {
                    phase: obs.phase,
                    observedBehavior: obs.behavior,
                    expectedBehavior: "skipped",
                    independentOf: subject,
                },
            });
        }
    }

    return violations;
}
