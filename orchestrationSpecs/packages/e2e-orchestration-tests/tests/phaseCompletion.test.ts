import {
    Clock,
    waitForPhaseCompletion,
} from "../src/phaseCompletion";
import { ComponentId } from "../src/types";

/** Deterministic clock for the tests. */
function makeFakeClock(initial = 0): Clock & { tick(ms: number): void; get(): number } {
    let t = initial;
    const sleeps: number[] = [];
    return {
        now: () => t,
        sleep: (ms) => {
            sleeps.push(ms);
            t += ms;
            return Promise.resolve();
        },
        tick: (ms) => {
            t += ms;
        },
        get: () => t,
    };
}

const COMPONENTS = ["a:1", "b:1"] as ComponentId[];

describe("waitForPhaseCompletion", () => {
    it("returns immediately when all components are terminal on first poll", async () => {
        const clock = makeFakeClock();
        const result = await waitForPhaseCompletion({
            components: COMPONENTS,
            timeoutSeconds: 30,
            pollIntervalMs: 100,
            clock,
            readObservations: async () => [
                { componentId: "a:1" as ComponentId, phase: "Ready" },
                { componentId: "b:1" as ComponentId, phase: "Completed" },
            ],
        });
        expect(result.kind).toBe("ready");
    });

    it("polls until ready, then returns", async () => {
        const clock = makeFakeClock();
        let call = 0;
        const result = await waitForPhaseCompletion({
            components: COMPONENTS,
            timeoutSeconds: 30,
            pollIntervalMs: 100,
            clock,
            readObservations: async () => {
                call += 1;
                return [
                    { componentId: "a:1" as ComponentId, phase: call < 3 ? "Running" : "Ready" },
                    { componentId: "b:1" as ComponentId, phase: "Ready" },
                ];
            },
        });
        expect(result.kind).toBe("ready");
        expect(call).toBe(3);
    });

    it("times out when a component stays non-terminal", async () => {
        const clock = makeFakeClock();
        const result = await waitForPhaseCompletion({
            components: COMPONENTS,
            timeoutSeconds: 1,
            pollIntervalMs: 200,
            clock,
            readObservations: async () => [
                { componentId: "a:1" as ComponentId, phase: "Running" },
                { componentId: "b:1" as ComponentId, phase: "Ready" },
            ],
        });
        expect(result.kind).toBe("timeout");
        if (result.kind === "timeout") {
            expect(result.blockingComponents).toEqual([
                { componentId: "a:1", phase: "Running" },
            ]);
            expect(result.waitedMs).toBeGreaterThanOrEqual(1000);
        }
    });

    it("treats missing observations as non-terminal and blocking", async () => {
        const clock = makeFakeClock();
        const result = await waitForPhaseCompletion({
            components: COMPONENTS,
            timeoutSeconds: 1,
            pollIntervalMs: 200,
            clock,
            readObservations: async () => [
                { componentId: "a:1" as ComponentId, phase: "Ready" },
                // b:1 missing
            ],
        });
        expect(result.kind).toBe("timeout");
        if (result.kind === "timeout") {
            expect(result.blockingComponents.map((b) => b.componentId)).toEqual(["b:1"]);
        }
    });

    it("allows callers to override the terminal set", async () => {
        const clock = makeFakeClock();
        const result = await waitForPhaseCompletion({
            components: COMPONENTS,
            timeoutSeconds: 1,
            pollIntervalMs: 200,
            clock,
            // Only our custom set is terminal — 'Ready' is not.
            terminalPhases: new Set(["FooPhase"]),
            readObservations: async () => [
                { componentId: "a:1" as ComponentId, phase: "Ready" },
                { componentId: "b:1" as ComponentId, phase: "FooPhase" },
            ],
        });
        expect(result.kind).toBe("timeout");
        if (result.kind === "timeout") {
            expect(result.blockingComponents[0].componentId).toBe("a:1");
        }
    });
});

describe("waitForPhaseCompletion — Pending is not terminal for generic components", () => {
    it("times out when a topology component stays in Pending", async () => {
        const clock = makeFakeClock();
        const result = await waitForPhaseCompletion({
            components: COMPONENTS,
            timeoutSeconds: 1,
            pollIntervalMs: 200,
            clock,
            readObservations: async () => [
                { componentId: "a:1" as ComponentId, phase: "Pending" },
                { componentId: "b:1" as ComponentId, phase: "Ready" },
            ],
        });
        expect(result.kind).toBe("timeout");
        if (result.kind === "timeout") {
            expect(result.blockingComponents).toEqual([
                { componentId: "a:1", phase: "Pending" },
            ]);
        }
    });

    it("still returns ready when every component is in a true terminal/held phase", async () => {
        const clock = makeFakeClock();
        const result = await waitForPhaseCompletion({
            components: COMPONENTS,
            timeoutSeconds: 10,
            pollIntervalMs: 200,
            clock,
            readObservations: async () => [
                { componentId: "a:1" as ComponentId, phase: "Ready" },
                { componentId: "b:1" as ComponentId, phase: "Blocked" },
            ],
        });
        expect(result.kind).toBe("ready");
    });
});

describe("APPROVAL_GATE_HELD_PHASES", () => {
    it("treats Pending and Approved as held — but this set is NOT the generic default", async () => {
        const { APPROVAL_GATE_HELD_PHASES, TERMINAL_OR_HELD_PHASES } =
            require("../src/phaseCompletion") as typeof import("../src/phaseCompletion");

        expect(APPROVAL_GATE_HELD_PHASES.has("Pending")).toBe(true);
        expect(APPROVAL_GATE_HELD_PHASES.has("Approved")).toBe(true);
        // The generic topology set must not include Pending, or
        // ordinary components in Pending would falsely satisfy phase
        // completion (regression guard for plan step 3).
        expect(TERMINAL_OR_HELD_PHASES.has("Pending")).toBe(false);
    });
});
