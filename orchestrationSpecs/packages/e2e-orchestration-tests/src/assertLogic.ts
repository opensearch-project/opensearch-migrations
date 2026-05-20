/**
 * assertLogic — pure constraint walk over an observed snapshot.
 *
 * Implemented checkpoints:
 *   - `baseline-complete`: observation anchor only; no assertions.
 *   - `noop`: every component must be `skipped`.
 *   - `mutated-complete`: safe-case terminal — components whose
 *     checksums are material to the changed paths must be `reran`;
 *     upstream, independent, and non-material downstream components
 *     must be `skipped`.
 *   - `before-approval` / `after-approval`: gated-case checkpoints.
 *   - impossible-case checkpoints: `on-blocked`,
 *     `after-approve-without-reset`, `after-reset`, `after-approve`.
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
    /**
     * Materiality-aware rerun set for mutation checkpoints. Topology
     * says which components are downstream; this set says which of
     * those components should actually re-run for the changed paths.
     * If omitted on mutated-complete, only the subject is expected to
     * re-run.
     */
    expectedRerunComponents?: readonly ComponentId[];
    /** User-config paths that caused this checkpoint, for diagnostics. */
    changedPaths?: readonly string[];
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
            return assertGatedBeforeApproval(input);
        case "after-approval":
            return assertGatedAfterApproval(input);
        case "on-blocked":
            return assertImpossibleOnBlocked(input);
        case "after-approve-without-reset":
            return assertImpossibleAfterApproveWithoutReset(input);
        case "after-reset":
            return assertImpossibleAfterReset(input);
        case "after-approve":
            return assertImpossibleAfterApprove(input);
    }
}

const INDEPENDENTS_UNCHECKED: ReadonlySet<Checkpoint> = new Set([
    "on-blocked",
    "after-approve-without-reset",
]);

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
 *   2. Material downstream dependents must have `behavior === 'reran'`.
 *   3. Non-material downstream dependents must have `behavior === 'skipped'`.
 *   4. Every upstream prerequisite must have `behavior === 'skipped'`.
 *   5. Every independent component must have `behavior === 'skipped'`.
 *
 * "Material" comes from transition-tree/mutator metadata, not graph
 * reachability alone. See docs/reconfiguringWorkflows.md: downstream
 * waiters compare per-dependency checksums, so an operational safe
 * change like proxy `numThreads` should rerun the proxy without
 * forcing snapshots/replayers to rerun.
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

    const expectedReruns = new Set<ComponentId>([
        subject,
        ...(input.expectedRerunComponents ?? []),
    ]);
    const changedPaths = input.changedPaths ?? [];

    violations.push(
        ...assertExpectedState({
            observations,
            componentId: subject,
            checkpoint: "mutated-complete",
            expected: {
                behavior: ["reran"],
                violationType: "change-class",
                role: "subject",
            },
            extraDetails: changedPaths.length > 0 ? { changedPaths } : {},
        }),
    );

    // Constraint 2/3: downstream dependents only rerun when the changed
    // paths are material to their per-dependency checksum.
    for (const dep of topology.downstreamOf(subject)) {
        const shouldRerun = expectedReruns.has(dep);
        violations.push(
            ...assertExpectedState({
                observations,
                componentId: dep,
                checkpoint: "mutated-complete",
                expected: {
                    behavior: [shouldRerun ? "reran" : "skipped"],
                    violationType: "cascade",
                    role: "dependent",
                },
                extraDetails: {
                    cascadeFrom: subject,
                    materialToChangedPaths: shouldRerun,
                    ...(changedPaths.length > 0 ? { changedPaths } : {}),
                },
            }),
        );
    }

    // Constraint 4: every upstream prerequisite must be skipped
    for (const up of topology.upstreamOf(subject)) {
        violations.push(
            ...assertExpectedState({
                observations,
                componentId: up,
                checkpoint: "mutated-complete",
                expected: {
                    behavior: ["skipped"],
                    violationType: "upstream-reran",
                    role: "upstream prerequisite",
                },
                extraDetails: {
                    upstreamOf: subject,
                    ...(changedPaths.length > 0 ? { changedPaths } : {}),
                },
            }),
        );
    }

    // Constraint 5: every independent component must be skipped
    for (const ind of topology.independentOf(subject)) {
        violations.push(
            ...assertExpectedState({
                observations,
                componentId: ind,
                checkpoint: "mutated-complete",
                expected: {
                    behavior: ["skipped"],
                    violationType: "independence",
                    role: "independent",
                },
                extraDetails: {
                    independentOf: subject,
                    ...(changedPaths.length > 0 ? { changedPaths } : {}),
                },
            }),
        );
    }

    return violations;
}

/**
 * Gated before-approval checkpoint:
 *   - subject has paused for approval (`behavior === 'gated'`);
 *   - downstream dependents have not advanced yet (`unstarted`);
 *   - upstream prerequisites and independent branches did not rerun.
 */
function assertGatedBeforeApproval(input: AssertNoViolationsInput): Violation[] {
    return assertCheckpointExpectations(input, {
        checkpoint: "before-approval",
        subject: {
            behavior: ["gated"],
            violationType: "change-class",
            role: "subject",
        },
        downstream: {
            behavior: ["unstarted", "skipped"],
            violationType: "cascade",
            role: "dependent",
        },
    });
}

/**
 * Gated after-approval checkpoint:
 * once the gate is approved, the subject and downstream dependents
 * should complete as reruns; unrelated branches should remain skipped.
 */
function assertGatedAfterApproval(input: AssertNoViolationsInput): Violation[] {
    return assertCheckpointExpectations(input, {
        checkpoint: "after-approval",
        subject: {
            behavior: ["reran"],
            violationType: "change-class",
            role: "subject",
        },
        downstream: {
            behavior: ["reran"],
            violationType: "cascade",
            role: "dependent",
        },
    });
}

/**
 * Impossible initial blocked checkpoint:
 * the subject must block; dependents must either remain unstarted or
 * be blocked by the same impossible change. Independent branches are
 * intentionally unchecked here because they may have completed before
 * the blocked branch reached its held state.
 */
function assertImpossibleOnBlocked(input: AssertNoViolationsInput): Violation[] {
    return assertCheckpointExpectations(input, {
        checkpoint: "on-blocked",
        subject: {
            behavior: ["blocked"],
            violationType: "change-class",
            role: "subject",
        },
        downstream: {
            behavior: ["unstarted", "blocked", "skipped"],
            violationType: "cascade",
            role: "dependent",
        },
    });
}

/**
 * Impossible approve-only checkpoint:
 * approval alone must not advance an impossible change. The subject
 * stays blocked and dependents stay unstarted. Independent branches
 * are still unchecked for the same reason as `on-blocked`.
 */
function assertImpossibleAfterApproveWithoutReset(input: AssertNoViolationsInput): Violation[] {
    return assertCheckpointExpectations(input, {
        checkpoint: "after-approve-without-reset",
        subject: {
            behavior: ["blocked"],
            violationType: "change-class",
            role: "subject",
        },
        downstream: {
            behavior: ["unstarted", "blocked", "skipped"],
            violationType: "cascade",
            role: "dependent",
        },
    });
}

/**
 * Impossible after-reset checkpoint:
 * reset alone removes the subject resource but must not advance the
 * dependent branch. The runner should synthesize or retain a subject
 * observation with `phase: 'Deleted'` after reset so this pure checker
 * can verify the state explicitly.
 */
function assertImpossibleAfterReset(input: AssertNoViolationsInput): Violation[] {
    return assertCheckpointExpectations(input, {
        checkpoint: "after-reset",
        subject: {
            phase: ["Deleted"],
            violationType: "change-class",
            role: "subject",
        },
        downstream: {
            behavior: ["unstarted", "skipped"],
            violationType: "cascade",
            role: "dependent",
        },
    });
}

/**
 * Impossible reset-then-approve checkpoint:
 * after both required actions, subject and downstream dependents should
 * complete as reruns while upstream and independent components stay
 * skipped.
 */
function assertImpossibleAfterApprove(input: AssertNoViolationsInput): Violation[] {
    return assertCheckpointExpectations(input, {
        checkpoint: "after-approve",
        subject: {
            behavior: ["reran"],
            violationType: "change-class",
            role: "subject",
        },
        downstream: {
            behavior: ["reran"],
            violationType: "cascade",
            role: "dependent",
        },
    });
}

interface ExpectedState {
    behavior?: readonly ObservedComponent["behavior"][];
    phase?: readonly string[];
    violationType: Violation["type"];
    role: "subject" | "dependent" | "upstream prerequisite" | "independent";
}

interface CheckpointExpectation {
    checkpoint: Exclude<Checkpoint, "baseline-complete" | "noop" | "mutated-complete">;
    subject: ExpectedState;
    downstream: ExpectedState;
}

function assertCheckpointExpectations(
    input: AssertNoViolationsInput,
    expectation: CheckpointExpectation,
): Violation[] {
    if (input.subject === null) {
        return [
            {
                type: "change-class",
                checkpoint: expectation.checkpoint,
                message: `subject is null at '${expectation.checkpoint}' checkpoint — this is a programmer error; the runner must provide a subject for mutation checkpoints`,
            },
        ];
    }

    const { subject, topology, observations } = input;
    const violations: Violation[] = [];
    const expectedReruns = new Set<ComponentId>([
        subject,
        ...(input.expectedRerunComponents ?? []),
    ]);
    const changedPaths = input.changedPaths ?? [];

    violations.push(
        ...assertExpectedState({
            observations,
            componentId: subject,
            checkpoint: expectation.checkpoint,
            expected: expectation.subject,
            extraDetails: changedPaths.length > 0 ? { changedPaths } : {},
        }),
    );

    for (const dep of topology.downstreamOf(subject)) {
        const materialToChangedPaths = expectedReruns.has(dep);
        violations.push(
            ...assertExpectedState({
                observations,
                componentId: dep,
                checkpoint: expectation.checkpoint,
                expected: downstreamExpectedState(
                    expectation.downstream,
                    materialToChangedPaths,
                ),
                extraDetails: {
                    cascadeFrom: subject,
                    materialToChangedPaths,
                    ...(changedPaths.length > 0 ? { changedPaths } : {}),
                },
            }),
        );
    }

    // Upstream prerequisites are expected to be stable at every
    // mutation checkpoint. If they rerun, the changed scope leaked
    // backward through the topology.
    for (const up of topology.upstreamOf(subject)) {
        violations.push(
            ...assertExpectedState({
                observations,
                componentId: up,
                checkpoint: expectation.checkpoint,
                expected: {
                    behavior: ["skipped"],
                    violationType: "upstream-reran",
                    role: "upstream prerequisite",
                },
                extraDetails: {
                    upstreamOf: subject,
                    ...(changedPaths.length > 0 ? { changedPaths } : {}),
                },
            }),
        );
    }

    if (!INDEPENDENTS_UNCHECKED.has(expectation.checkpoint)) {
        for (const ind of topology.independentOf(subject)) {
            violations.push(
                ...assertExpectedState({
                    observations,
                    componentId: ind,
                    checkpoint: expectation.checkpoint,
                    expected: {
                        behavior: ["skipped"],
                        violationType: "independence",
                        role: "independent",
                    },
                    extraDetails: {
                        independentOf: subject,
                        ...(changedPaths.length > 0 ? { changedPaths } : {}),
                    },
                }),
            );
        }
    }

    return violations;
}

function downstreamExpectedState(
    expected: ExpectedState,
    materialToChangedPaths: boolean,
): ExpectedState {
    if (expected.behavior?.length === 1 && expected.behavior[0] === "reran") {
        return {
            ...expected,
            behavior: [materialToChangedPaths ? "reran" : "skipped"],
            role: "dependent",
        };
    }
    return {
        ...expected,
        role: "dependent",
    };
}

function assertExpectedState(args: {
    observations: Readonly<Record<ComponentId, ObservedComponent>>;
    componentId: ComponentId;
    checkpoint: Checkpoint;
    expected: ExpectedState;
    extraDetails?: Record<string, unknown>;
}): Violation[] {
    const { observations, componentId, checkpoint, expected, extraDetails = {} } = args;
    const obs = observations[componentId];
    const expectedBehavior = expected.behavior?.join(" | ");
    const expectedPhase = expected.phase?.join(" | ");

    if (!obs) {
        return [
            {
                type: "missing-observation",
                checkpoint,
                componentId,
                message: `no observation for ${expected.role} ${componentId} at checkpoint '${checkpoint}'`,
                details: {
                    ...(expectedBehavior ? { expectedBehavior } : {}),
                    ...(expectedPhase ? { expectedPhase } : {}),
                    ...extraDetails,
                },
            },
        ];
    }

    if (expected.behavior) {
        if (obs.behavior === undefined) {
            return [
                {
                    type: "missing-observation",
                    checkpoint,
                    componentId,
                    message: `observation for ${expected.role} ${componentId} at checkpoint '${checkpoint}' is missing 'behavior'`,
                    details: {
                        phase: obs.phase,
                        expectedBehavior,
                        ...extraDetails,
                    },
                },
            ];
        }
        if (!expected.behavior.includes(obs.behavior)) {
            return [
                {
                    type: expected.violationType,
                    checkpoint,
                    componentId,
                    message: `${expected.role} ${componentId} expected behavior '${expectedBehavior}' at ${checkpoint}; observed '${obs.behavior}' (phase='${obs.phase}')`,
                    details: {
                        phase: obs.phase,
                        observedBehavior: obs.behavior,
                        expectedBehavior,
                        ...extraDetails,
                    },
                },
            ];
        }
    }

    if (expected.phase && !expected.phase.includes(obs.phase)) {
        return [
            {
                type: expected.violationType,
                checkpoint,
                componentId,
                message: `${expected.role} ${componentId} expected phase '${expectedPhase}' at ${checkpoint}; observed phase='${obs.phase}'`,
                details: {
                    phase: obs.phase,
                    expectedPhase,
                    ...(obs.behavior ? { observedBehavior: obs.behavior } : {}),
                    ...extraDetails,
                },
            },
        ];
    }

    return [];
}
