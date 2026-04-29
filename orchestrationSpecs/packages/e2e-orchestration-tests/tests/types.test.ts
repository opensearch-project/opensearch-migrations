import {
    BehaviorSchema,
    ChangeClassSchema,
    CheckpointSchema,
    ComponentIdSchema,
    DependencyPatternSchema,
    LifecyclePhaseSchema,
    ResponseSchema,
    ViolationSchema,
} from "../src/types";

describe("enum schemas", () => {
    it("accepts every documented ChangeClass", () => {
        for (const v of ["safe", "gated", "impossible"]) {
            expect(ChangeClassSchema.parse(v)).toBe(v);
        }
    });

    it("rejects unknown ChangeClass values", () => {
        expect(ChangeClassSchema.safeParse("sometimes").success).toBe(false);
    });

    it("covers every checkpoint from the design doc", () => {
        for (const v of [
            "noop",
            "mutated-complete",
            "before-approval",
            "after-approval",
            "on-blocked",
            "after-approve-without-reset",
            "after-reset",
            "after-approve",
        ]) {
            expect(CheckpointSchema.parse(v)).toBe(v);
        }
    });

    it("covers every lifecycle phase from the design doc", () => {
        const phases = [
            "setup",
            "baseline-complete",
            "noop-pre-complete",
            "mutated-complete",
            "mutation-gated",
            "mutation-gate-approved",
            "mutation-blocked",
            "mutation-after-reset",
            "noop-post-complete",
            "teardown",
        ];
        for (const v of phases) {
            expect(LifecyclePhaseSchema.parse(v)).toBe(v);
        }
    });

    it.each([
        ["subject-change"],
        ["subject-gated-change"],
        ["subject-impossible-change"],
        ["immediate-dependent-change"],
        ["transitive-dependent-change"],
    ])("accepts DependencyPattern %s", (v) => {
        expect(DependencyPatternSchema.parse(v)).toBe(v);
    });

    it.each([
        ["approve"],
        ["leave-blocked"],
        ["reset-then-approve"],
        ["approve-only"],
        ["reset-only"],
    ])("accepts Response %s", (v) => {
        expect(ResponseSchema.parse(v)).toBe(v);
    });

    it.each([
        ["ran"],
        ["reran"],
        ["skipped"],
        ["blocked"],
        ["gated"],
        ["unstarted"],
    ])("accepts Behavior %s", (v) => {
        expect(BehaviorSchema.parse(v)).toBe(v);
    });
});

describe("ComponentIdSchema", () => {
    it.each([
        "proxy:capture-proxy",
        "kafkacluster:default",
        "captureproxy:source-proxy",
        "snapshotmigration:source-target-snap1-migration-0",
        "trafficreplay:source-proxy-target-target-replay",
        "datasnapshot:source-snap1",
    ])("accepts valid id %s", (id) => {
        expect(ComponentIdSchema.parse(id)).toBe(id);
    });

    it.each([
        "",
        "no-colon",
        ":missing-kind",
        "kind:",
        "Kind:Name",  // uppercase kind
        "kind:-leading-hyphen",
        "kind name:resource",
    ])("rejects invalid id %s", (id) => {
        expect(ComponentIdSchema.safeParse(id).success).toBe(false);
    });
});

describe("ViolationSchema", () => {
    it("accepts a minimal violation", () => {
        const v = { type: "independence", message: "component reran unexpectedly" };
        expect(ViolationSchema.parse(v)).toEqual(v);
    });

    it("accepts a fully populated violation", () => {
        const v = {
            type: "change-class",
            checkpoint: "mutated-complete",
            componentId: "proxy:capture-proxy",
            message: "expected reran but saw Suspended",
            details: { tree: "safe", observed: "Suspended" },
        };
        expect(ViolationSchema.parse(v)).toEqual(v);
    });

    it("rejects unknown violation type", () => {
        expect(
            ViolationSchema.safeParse({ type: "banana", message: "x" }).success,
        ).toBe(false);
    });
});

describe("MatrixSelectorSchema", () => {
    const { MatrixSelectorSchema } = require("../src/types") as typeof import("../src/types");

    it("accepts a safe selector with no response", () => {
        expect(
            MatrixSelectorSchema.parse({
                changeClass: "safe",
                patterns: ["subject-change"],
            }),
        ).toEqual({ changeClass: "safe", patterns: ["subject-change"] });
    });

    it("rejects a safe selector with any response", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "safe",
                patterns: ["subject-change"],
                response: "approve",
            }).success,
        ).toBe(false);
    });

    it.each([["approve"], ["leave-blocked"]])(
        "accepts gated response %s",
        (response) => {
            expect(
                MatrixSelectorSchema.parse({
                    changeClass: "gated",
                    patterns: ["subject-gated-change"],
                    response,
                }),
            ).toMatchObject({ response });
        },
    );

    it("rejects gated selector with impossible response", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "gated",
                patterns: ["subject-gated-change"],
                response: "reset-only",
            }).success,
        ).toBe(false);
    });

    it("requires gated selectors to set a response", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "gated",
                patterns: ["subject-gated-change"],
            }).success,
        ).toBe(false);
    });

    it.each([
        ["reset-then-approve"],
        ["approve-only"],
        ["reset-only"],
        ["leave-blocked"],
    ])("accepts impossible response %s", (response) => {
        expect(
            MatrixSelectorSchema.parse({
                changeClass: "impossible",
                patterns: ["subject-impossible-change"],
                response,
            }),
        ).toMatchObject({ response });
    });

    it("rejects impossible selector with gated-only response 'approve'", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "impossible",
                patterns: ["subject-impossible-change"],
                response: "approve",
            }).success,
        ).toBe(false);
    });
});

describe("MatrixSelectorSchema pattern/class pairing", () => {
    const { MatrixSelectorSchema } = require("../src/types") as typeof import("../src/types");

    it("rejects safe + subject-gated-change", () => {
        const res = MatrixSelectorSchema.safeParse({
            changeClass: "safe",
            patterns: ["subject-gated-change"],
        });
        expect(res.success).toBe(false);
        if (!res.success) {
            expect(JSON.stringify(res.error.issues)).toMatch(/subject-gated-change/);
        }
    });

    it("rejects safe + subject-impossible-change", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "safe",
                patterns: ["subject-impossible-change"],
            }).success,
        ).toBe(false);
    });

    it("rejects gated + subject-change", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "gated",
                patterns: ["subject-change"],
                response: "approve",
            }).success,
        ).toBe(false);
    });

    it("rejects gated + subject-impossible-change", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "gated",
                patterns: ["subject-impossible-change"],
                response: "approve",
            }).success,
        ).toBe(false);
    });

    it("rejects impossible + subject-gated-change", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "impossible",
                patterns: ["subject-gated-change"],
                response: "reset-then-approve",
            }).success,
        ).toBe(false);
    });

    it("accepts class-neutral patterns with any changeClass", () => {
        // safe + immediate
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "safe",
                patterns: ["immediate-dependent-change"],
            }).success,
        ).toBe(true);

        // gated + transitive
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "gated",
                patterns: ["transitive-dependent-change"],
                response: "approve",
            }).success,
        ).toBe(true);

        // impossible + immediate
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "impossible",
                patterns: ["immediate-dependent-change"],
                response: "reset-only",
            }).success,
        ).toBe(true);
    });

    it("accepts the matching class+subject pattern for each class", () => {
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "safe",
                patterns: ["subject-change"],
            }).success,
        ).toBe(true);
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "gated",
                patterns: ["subject-gated-change"],
                response: "approve",
            }).success,
        ).toBe(true);
        expect(
            MatrixSelectorSchema.safeParse({
                changeClass: "impossible",
                patterns: ["subject-impossible-change"],
                response: "reset-then-approve",
            }).success,
        ).toBe(true);
    });

    it("flags each mismatched pattern in a multi-pattern selector", () => {
        const res = MatrixSelectorSchema.safeParse({
            changeClass: "safe",
            patterns: [
                "subject-change",            // ok
                "subject-gated-change",      // bad
                "immediate-dependent-change",// ok
                "subject-impossible-change", // bad
            ],
        });
        expect(res.success).toBe(false);
        if (!res.success) {
            // Expect two issues on patterns[1] and patterns[3].
            const pathHits = res.error.issues
                .map((i) => i.path.join("."))
                .filter((p) => p.startsWith("patterns."));
            expect(pathHits).toEqual(
                expect.arrayContaining(["patterns.1", "patterns.3"]),
            );
            expect(pathHits).not.toContain("patterns.0");
            expect(pathHits).not.toContain("patterns.2");
        }
    });
});
