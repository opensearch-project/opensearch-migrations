import { assertNoViolations } from "../src/assertLogic";
import { buildTopology, ComponentTopology } from "../src/componentTopology";
import { Checkpoint, ComponentId, ObservedComponent } from "../src/types";

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

describe("assertNoViolations — other checkpoints are unimplemented stubs", () => {
    const others: Checkpoint[] = [
        "before-approval",
        "after-approval",
        "on-blocked",
        "after-approve-without-reset",
        "after-reset",
        "after-approve",
    ];

    it.each(others)("returns a single unimplemented-checkpoint violation for %s", (checkpoint) => {
        const violations = assertNoViolations({
            checkpoint,
            subject: A,
            topology: smallTopology(),
            observations: { [A]: skipped(A), [B]: skipped(B), [C]: skipped(C) },
        });
        expect(violations).toEqual([
            {
                type: "unimplemented-checkpoint",
                checkpoint,
                message: expect.stringContaining(checkpoint),
            },
        ]);
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

describe("assertNoViolations — mutated-complete checkpoint", () => {
    it("returns no violations when subject and downstream reran, independents skipped", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "reran"),
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
                [A]: obs(A, "reran"),    // downstream of B
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
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
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

    it("flags downstream dependent that stayed skipped as cascade violation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "reran"),
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
        });
    });

    it("flags independent component that reran as independence violation", () => {
        const violations = assertNoViolations({
            checkpoint: "mutated-complete",
            subject: C,
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
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
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
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
            topology: chainTopology(),
            observations: {
                // A missing — downstream of C
                [B]: obs(B, "reran"),
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
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
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
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
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
                [A]: obs(A, "reran"),
                [B]: obs(B, "reran"),
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
            topology: chainTopology(),
            observations: {
                [A]: obs(A, "reran"),
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
