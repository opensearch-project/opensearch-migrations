import { assertNoViolations } from "../src/assertLogic";
import { buildTopology, ComponentTopology } from "../src/componentTopology";
import { ComponentId, ObservedComponent } from "../src/types";

const A = "a:1" as ComponentId;
const B = "b:1" as ComponentId;
const C = "c:1" as ComponentId;

function smallTopology(): ComponentTopology {
    return buildTopology({
        components: [A, B, C],
        edges: [],
    });
}

function skipped(id: ComponentId): ObservedComponent {
    return { componentId: id, phase: "Ready", behavior: "skipped" };
}

function reran(id: ComponentId): ObservedComponent {
    return { componentId: id, phase: "Ready", behavior: "reran" };
}

describe("assertNoViolations — noop checkpoint", () => {
    it("treats baseline-complete as an observation anchor with no assertions", () => {
        const violations = assertNoViolations({
            checkpoint: "baseline-complete",
            subject: null,
            topology: smallTopology(),
            observations: {
                [A]: { componentId: A, phase: "Ready", behavior: "ran" },
                [B]: { componentId: B, phase: "Ready", behavior: "ran" },
                [C]: { componentId: C, phase: "Ready", behavior: "ran" },
            },
        });
        expect(violations).toEqual([]);
    });

    it("returns no violations when every component is skipped", () => {
        const violations = assertNoViolations({
            checkpoint: "noop",
            subject: null,
            topology: smallTopology(),
            observations: {
                [A]: skipped(A),
                [B]: skipped(B),
                [C]: skipped(C),
            },
        });
        expect(violations).toEqual([]);
    });

    it("flags each component that did not skip", () => {
        const violations = assertNoViolations({
            checkpoint: "noop",
            subject: null,
            topology: smallTopology(),
            observations: {
                [A]: reran(A),
                [B]: skipped(B),
                [C]: { componentId: C, phase: "Ready", behavior: "ran" },
            },
        });
        expect(violations).toHaveLength(2);
        const ids = violations.map((v) => v.componentId);
        expect(ids).toEqual(expect.arrayContaining([A, C]));
        for (const v of violations) {
            expect(v.type).toBe("noop-not-skipped");
            expect(v.checkpoint).toBe("noop");
        }
    });

    it("flags a missing observation as 'missing-observation'", () => {
        const violations = assertNoViolations({
            checkpoint: "noop",
            subject: null,
            topology: smallTopology(),
            observations: {
                [A]: skipped(A),
                [B]: skipped(B),
                // C missing
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0]).toMatchObject({
            type: "missing-observation",
            componentId: C,
            checkpoint: "noop",
        });
    });

    it("flags a present observation that lacks a behavior field", () => {
        const violations = assertNoViolations({
            checkpoint: "noop",
            subject: null,
            topology: smallTopology(),
            observations: {
                [A]: skipped(A),
                [B]: { componentId: B, phase: "Ready" }, // no behavior
                [C]: skipped(C),
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0]).toMatchObject({
            type: "missing-observation",
            componentId: B,
            checkpoint: "noop",
        });
        expect(violations[0].message).toMatch(/missing 'behavior'/);
    });

    it("walks every component in the topology, not just the observation map", () => {
        // Observations include a stray component not in the topology —
        // that should not affect the verdict (walk is topology-driven).
        const violations = assertNoViolations({
            checkpoint: "noop",
            subject: null,
            topology: smallTopology(),
            observations: {
                [A]: skipped(A),
                [B]: skipped(B),
                [C]: skipped(C),
                ["unrelated:thing" as ComponentId]: reran(
                    "unrelated:thing" as ComponentId,
                ),
            },
        });
        expect(violations).toEqual([]);
    });

    it("violation details include phase and expected/observed behavior", () => {
        const violations = assertNoViolations({
            checkpoint: "noop",
            subject: null,
            topology: smallTopology(),
            observations: {
                [A]: { componentId: A, phase: "Ready", behavior: "reran" },
                [B]: skipped(B),
                [C]: skipped(C),
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0].details).toEqual({
            phase: "Ready",
            observedBehavior: "reran",
            expectedBehavior: "skipped",
        });
    });
});

// ── mutated-complete checkpoint tests ────────────────────────────────

/**
 * Topology with edges for cascade/independence testing:
 *   A → B → C  (A depends on B, B depends on C)
 *   D is independent of all.
 *
 * If subject = C:
 *   downstream = {B, A} (they depend on C transitively)
 *   upstream = {} (C has no prerequisites)
 *   independent = {D}
 */
const D = "d:1" as ComponentId;

function chainTopology(): ComponentTopology {
    return buildTopology({
        components: [A, B, C, D],
        edges: [
            { from: A, to: B },
            { from: B, to: C },
        ],
    });
}

function obs(id: ComponentId, behavior: string): ObservedComponent {
    return { componentId: id, phase: "Ready", behavior: behavior as any };
}

function phaseObs(id: ComponentId, phase: string, behavior?: string): ObservedComponent {
    return {
        componentId: id,
        phase,
        ...(behavior ? { behavior: behavior as any } : {}),
    };
}

describe("assertNoViolations — mutated-complete checkpoint", () => {
    it("returns no violations when only the subject reran for a non-material safe change", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),
                [C]: obs(C, "reran"),
                [D]: obs(D, "skipped"),
            },
        });
        expect(violations).toEqual([]);
    });

    it("returns no violations when material downstream components reran", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            expectedRerunComponents: [C, B],
            changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.setHeader"],
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "reran"),
                [C]: obs(C, "reran"),
                [D]: obs(D, "skipped"),
            },
        });
        expect(violations).toEqual([]);
    });

    it("flags upstream prerequisite that reran as upstream-reran violation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: B,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),  // downstream of B, not material
                [B]: obs(B, "reran"),    // subject
                [C]: obs(C, "reran"),    // upstream of B, should be skipped
                [D]: obs(D, "skipped"),  // independent
            },
        });
        const upstreamViolations = violations.filter(v => v.type === "upstream-reran");
        expect(upstreamViolations).toHaveLength(1);
        expect(upstreamViolations[0]).toMatchObject({
            type: "upstream-reran",
            componentId: C,
            checkpoint: "mutated-complete",
        });
        expect(upstreamViolations[0].details).toMatchObject({
            observedBehavior: "reran",
            expectedBehavior: "skipped",
            upstreamOf: B,
        });
    });

    it("flags subject that did not reran as change-class violation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),
                [C]: obs(C, "skipped"),
                [D]: obs(D, "skipped"),
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0]).toMatchObject({
            type: "change-class",
            checkpoint: "mutated-complete",
            componentId: C,
        });
        expect(violations[0].details).toMatchObject({
            observedBehavior: "skipped",
            expectedBehavior: "reran",
        });
    });

    it("flags material downstream dependent that stayed skipped as cascade violation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            expectedRerunComponents: [C, B],
            changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.setHeader"],
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),  // downstream of C, should reran
                [C]: obs(C, "reran"),
                [D]: obs(D, "skipped"),
            },
        });
        const cascadeViolations = violations.filter(v => v.type === "cascade");
        expect(cascadeViolations).toHaveLength(1);
        expect(cascadeViolations[0]).toMatchObject({
            type: "cascade",
            componentId: B,
            checkpoint: "mutated-complete",
        });
        expect(cascadeViolations[0].details).toMatchObject({
            observedBehavior: "skipped",
            expectedBehavior: "reran",
            cascadeFrom: C,
            materialToChangedPaths: true,
        });
    });

    it("flags non-material downstream dependent that reran as cascade violation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.numThreads"],
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "reran"),  // downstream of C, but not material
                [C]: obs(C, "reran"),
                [D]: obs(D, "skipped"),
            },
        });
        const cascadeViolations = violations.filter(v => v.type === "cascade");
        expect(cascadeViolations).toHaveLength(1);
        expect(cascadeViolations[0]).toMatchObject({
            type: "cascade",
            componentId: B,
            checkpoint: "mutated-complete",
        });
        expect(cascadeViolations[0].details).toMatchObject({
            observedBehavior: "reran",
            expectedBehavior: "skipped",
            cascadeFrom: C,
            materialToChangedPaths: false,
            changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.numThreads"],
        });
    });

    it("flags independent component that reran as independence violation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),
                [C]: obs(C, "reran"),
                [D]: obs(D, "reran"),  // independent, should be skipped
            },
        });
        const indViolations = violations.filter(v => v.type === "independence");
        expect(indViolations).toHaveLength(1);
        expect(indViolations[0]).toMatchObject({
            type: "independence",
            componentId: D,
            checkpoint: "mutated-complete",
        });
        expect(indViolations[0].details).toMatchObject({
            observedBehavior: "reran",
            expectedBehavior: "skipped",
            independentOf: C,
        });
    });

    it("flags missing observation for subject as missing-observation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),
                // C missing
                [D]: obs(D, "skipped"),
            },
        });
        const missing = violations.filter(v => v.type === "missing-observation" && v.componentId === C);
        expect(missing).toHaveLength(1);
        expect(missing[0].details).toMatchObject({ expectedBehavior: "reran" });
    });

    it("flags missing observation for dependent as missing-observation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            expectedRerunComponents: [C, A],
            topology: chainTopology(),
            observations: {
                // A missing — downstream of C
                [B]: obs(B, "skipped"),
                [C]: obs(C, "reran"),
                [D]: obs(D, "skipped"),
            },
        });
        const missing = violations.filter(v => v.type === "missing-observation" && v.componentId === A);
        expect(missing).toHaveLength(1);
    });

    it("flags missing observation for independent as missing-observation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),
                [C]: obs(C, "reran"),
                // D missing — independent
            },
        });
        const missing = violations.filter(v => v.type === "missing-observation" && v.componentId === D);
        expect(missing).toHaveLength(1);
        expect(missing[0].details).toMatchObject({ expectedBehavior: "skipped" });
    });

    it("flags observation missing behavior field as missing-observation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),
                [C]: { componentId: C, phase: "Ready" },  // no behavior
                [D]: obs(D, "skipped"),
            },
        });
        const missing = violations.filter(v => v.type === "missing-observation" && v.componentId === C);
        expect(missing).toHaveLength(1);
        expect(missing[0].message).toMatch(/missing 'behavior'/);
    });

    it("produces a change-class violation when subject is null (programmer error)", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: null,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),
                [C]: obs(C, "reran"),
                [D]: obs(D, "skipped"),
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0]).toMatchObject({
            type: "change-class",
            checkpoint: "mutated-complete",
        });
        expect(violations[0].message).toMatch(/programmer error/);
    });

    it("can flag multiple violation types simultaneously", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            expectedRerunComponents: [C, B],
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "skipped"),
                [B]: obs(B, "skipped"),  // cascade violation
                [C]: obs(C, "skipped"),  // change-class violation
                [D]: obs(D, "reran"),    // independence violation
            },
        });
        const types = new Set(violations.map(v => v.type));
        expect(types).toEqual(new Set(["change-class", "cascade", "independence"]));
    });

    it("works with a flat topology (no edges) — subject only, all others independent", () => {
        const flat = buildTopology({ components: [A, B, C], edges: [] });
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: A,
            topology: flat,
            observations: {
                [A]: obs(A, "reran"),
                [B]: obs(B, "skipped"),
                [C]: obs(C, "skipped"),
            },
        });
        expect(violations).toEqual([]);
    });
});

describe("assertNoViolations — gated checkpoints", () => {
    it("before-approval passes when subject is gated, downstream is unstarted, and stable branches skipped", () => {
        const violations = assertNoViolations({
            checkpoint: "before-approval",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Initialized", "unstarted"),
                [C]: phaseObs(C, "Paused", "gated"),
                [D]: skipped(D),
            },
        });
        expect(violations).toEqual([]);
    });

    it("before-approval flags a subject that did not pause for approval", () => {
        const violations = assertNoViolations({
            checkpoint: "before-approval",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Initialized", "unstarted"),
                [C]: obs(C, "reran"),
                [D]: skipped(D),
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0]).toMatchObject({
            type: "change-class",
            checkpoint: "before-approval",
            componentId: C,
        });
        expect(violations[0].details).toMatchObject({
            observedBehavior: "reran",
            expectedBehavior: "gated",
        });
    });

    it("before-approval flags downstream components that advanced too early", () => {
        const violations = assertNoViolations({
            checkpoint: "before-approval",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: obs(B, "reran"),
                [C]: phaseObs(C, "Paused", "gated"),
                [D]: skipped(D),
            },
        });
        const cascade = violations.filter((v) => v.type === "cascade");
        expect(cascade).toHaveLength(1);
        expect(cascade[0]).toMatchObject({
            checkpoint: "before-approval",
            componentId: B,
        });
        expect(cascade[0].details).toMatchObject({
            observedBehavior: "reran",
            expectedBehavior: "unstarted | skipped",
            cascadeFrom: C,
        });
    });

    it("after-approval passes when subject and material downstream reran", () => {
        const violations = assertNoViolations({
            checkpoint: "after-approval",
            subject: C,
            expectedRerunComponents: [C, A, B],
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
                [C]: obs(C, "reran"),
                [D]: skipped(D),
            },
        });
        expect(violations).toEqual([]);
    });

    it("after-approval still enforces upstream stability", () => {
        const violations = assertNoViolations({
            checkpoint: "after-approval",
            subject: B,
            expectedRerunComponents: [B, A],
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
                [C]: obs(C, "reran"),
                [D]: skipped(D),
            },
        });
        const upstream = violations.filter((v) => v.type === "upstream-reran");
        expect(upstream).toHaveLength(1);
        expect(upstream[0]).toMatchObject({
            checkpoint: "after-approval",
            componentId: C,
        });
    });
});

describe("assertNoViolations — impossible checkpoints", () => {
    it("on-blocked passes when subject is blocked and downstream has not advanced", () => {
        const violations = assertNoViolations({
            checkpoint: "on-blocked",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Blocked", "blocked"),
                [C]: phaseObs(C, "Blocked", "blocked"),
                // Independents are intentionally unchecked at this checkpoint.
                [D]: obs(D, "reran"),
            },
        });
        expect(violations).toEqual([]);
    });

    it("after-approve-without-reset proves approval alone did not advance the blocked subject", () => {
        const violations = assertNoViolations({
            checkpoint: "after-approve-without-reset",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Initialized", "unstarted"),
                [C]: phaseObs(C, "Blocked", "blocked"),
                // Independents are still unchecked for this non-advancement check.
                [D]: obs(D, "reran"),
            },
        });
        expect(violations).toEqual([]);
    });

    it("after-approve-without-reset flags a subject that advanced after approval alone", () => {
        const violations = assertNoViolations({
            checkpoint: "after-approve-without-reset",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Initialized", "unstarted"),
                [C]: obs(C, "reran"),
                [D]: obs(D, "reran"),
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0]).toMatchObject({
            type: "change-class",
            checkpoint: "after-approve-without-reset",
            componentId: C,
        });
    });

    it("after-reset passes when the subject is deleted and downstream has not advanced", () => {
        const violations = assertNoViolations({
            checkpoint: "after-reset",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Initialized", "unstarted"),
                [C]: phaseObs(C, "Deleted"),
                [D]: skipped(D),
            },
        });
        expect(violations).toEqual([]);
    });

    it("after-reset enforces independent stability again", () => {
        const violations = assertNoViolations({
            checkpoint: "after-reset",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Initialized", "unstarted"),
                [C]: phaseObs(C, "Deleted"),
                [D]: obs(D, "reran"),
            },
        });
        const independence = violations.filter((v) => v.type === "independence");
        expect(independence).toHaveLength(1);
        expect(independence[0]).toMatchObject({
            checkpoint: "after-reset",
            componentId: D,
        });
    });

    it("after-approve passes when reset plus approval lets subject and material downstream rerun", () => {
        const violations = assertNoViolations({
            checkpoint: "after-approve",
            subject: C,
            expectedRerunComponents: [C, A, B],
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
                [C]: obs(C, "reran"),
                [D]: skipped(D),
            },
        });
        expect(violations).toEqual([]);
    });

    it("mutation checkpoints report null subject as a programmer error", () => {
        const violations = assertNoViolations({
            checkpoint: "on-blocked",
            subject: null,
            topology: chainTopology(),
            observations: {
                [A]: phaseObs(A, "Initialized", "unstarted"),
                [B]: phaseObs(B, "Initialized", "unstarted"),
                [C]: phaseObs(C, "Blocked", "blocked"),
                [D]: skipped(D),
            },
        });
        expect(violations).toHaveLength(1);
        expect(violations[0]).toMatchObject({
            type: "change-class",
            checkpoint: "on-blocked",
        });
        expect(violations[0].message).toMatch(/programmer error/);
    });
});
