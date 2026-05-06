/**
 * phaseCompletion — polling predicate that waits for every component in
 * a topology to reach a terminal or held state.
 *
 * The predicate is a pure function over an observation-read callback
 * and a clock. Two injectable seams make it unit-testable without
 * touching Kubernetes or the system clock:
 *
 *   - `readObservations`: returns a phase-by-component map for the
 *     current moment.
 *   - `clock`: `{ now(): number, sleep(ms: number): Promise<void> }`.
 *
 * The production executor wires the real k8sClient and a real clock;
 * tests can drive deterministic scenarios by returning scripted phases.
 */

import { ComponentId } from "./types";

/**
 * Terminal or held phases the framework treats as "done waiting" for
 * ordinary topology components. See design doc's "Phase-completion
 * predicate".
 *
 * Note: `Pending` is NOT terminal here. An ordinary component in
 * `Pending` means it has not started yet, and waiting for the rest of
 * the graph must not advance while it's in that state. Only
 * `ApprovalGate` resources use `Pending` as a legitimate held state —
 * see `APPROVAL_GATE_HELD_PHASES`.
 *
 * The exact vocabulary is pinned to what the real CRDs report and may
 * evolve with the live runner; when it does, update this list and
 * document the change.
 */
export const TERMINAL_OR_HELD_PHASES: ReadonlySet<string> = new Set([
    "Ready",
    "Completed",
    "Skipped",
    "Failed",
    "Blocked",
    "Suspended",
    "Paused",
    "Deleted",
]);

/**
 * Phases that an `ApprovalGate` CRD is expected to sit at while it
 * waits for user action or records the outcome. A gate in `Pending`
 * is held (waiting on approval); `Approved` is terminal. These are
 * distinct from topology-component phases above — don't fold them
 * together.
 */
export const APPROVAL_GATE_HELD_PHASES: ReadonlySet<string> = new Set([
    "Pending",
    "Approved",
]);

export const NON_TERMINAL_PHASES: ReadonlySet<string> = new Set([
    "Initialized",
    "Pending",
    "Running",
    "Deleting",
]);

export interface PhaseObservation {
    componentId: ComponentId;
    phase: string;
}

export interface Clock {
    now(): number;
    sleep(ms: number): Promise<void>;
}

export const realClock: Clock = {
    now: () => Date.now(),
    sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

export interface WaitForPhaseCompletionOptions {
    components: readonly ComponentId[];
    timeoutSeconds: number;
    pollIntervalMs?: number;
    readObservations: () => Promise<readonly PhaseObservation[]>;
    clock?: Clock;
    /** Treat any phase in this set as terminal/held. Overrides defaults. */
    terminalPhases?: ReadonlySet<string>;
}

export type PhaseCompletionOutcome =
    | {
          kind: "ready";
          waitedMs: number;
          observations: readonly PhaseObservation[];
      }
    | {
          kind: "timeout";
          waitedMs: number;
          blockingComponents: readonly { componentId: ComponentId; phase: string }[];
          lastObservations: readonly PhaseObservation[];
      };

/**
 * Wait until every component in `components` is in a terminal or held
 * phase, or the deadline passes. Returns a structured outcome rather
 * than throwing — callers translate it into a `phase-timeout`
 * diagnostic or proceed to `assertNoViolations`.
 */
export async function waitForPhaseCompletion(
    opts: WaitForPhaseCompletionOptions,
): Promise<PhaseCompletionOutcome> {
    const clock = opts.clock ?? realClock;
    const terminal = opts.terminalPhases ?? TERMINAL_OR_HELD_PHASES;
    const pollMs = opts.pollIntervalMs ?? 2000;
    const deadlineMs = clock.now() + opts.timeoutSeconds * 1000;
    const start = clock.now();

    let last: readonly PhaseObservation[] = [];
    while (true) {
        last = await opts.readObservations();
        const byId = new Map(last.map((o) => [o.componentId, o]));
        const blocking: { componentId: ComponentId; phase: string }[] = [];
        for (const c of opts.components) {
            const obs = byId.get(c);
            if (!obs) {
                // Missing observation — treat as non-terminal; the
                // component may not have been created yet.
                blocking.push({ componentId: c, phase: "(missing)" });
                continue;
            }
            if (!terminal.has(obs.phase)) {
                blocking.push({ componentId: c, phase: obs.phase });
            }
        }
        if (blocking.length === 0) {
            return { kind: "ready", waitedMs: clock.now() - start, observations: last };
        }
        if (clock.now() >= deadlineMs) {
            return {
                kind: "timeout",
                waitedMs: clock.now() - start,
                blockingComponents: blocking,
                lastObservations: last,
            };
        }
        await clock.sleep(pollMs);
    }
}
