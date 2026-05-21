import {
    BUILTIN_MUTATOR_NAMES,
    builtinMutators,
} from "../src/fixtures/builtinMutators";
import { MutatorRegistry } from "../src/fixtures/mutators";
import { expandCases } from "../src/matrixExpander";
import { ScenarioSpec } from "../src/types";

describe("builtinMutators", () => {
    it("exposes proxy-numThreads in BUILTIN_MUTATOR_NAMES", () => {
        expect(BUILTIN_MUTATOR_NAMES).toContain("proxy-numThreads");
        expect(BUILTIN_MUTATOR_NAMES).toContain("dataSnapshot-maxSnapshotRate");
        expect(BUILTIN_MUTATOR_NAMES).toContain("snapshotMigration-maxConnections-gated");
        expect(BUILTIN_MUTATOR_NAMES).toContain("snapshotMigration-maxConnections");
    });

    it("returns Mutator instances that the registry accepts", () => {
        const reg = new MutatorRegistry();
        for (const m of builtinMutators()) reg.register(m);
        expect(reg.has("proxy-numThreads")).toBe(true);
        expect(reg.has("dataSnapshot-maxSnapshotRate")).toBe(true);
        expect(reg.has("snapshotMigration-maxConnections-gated")).toBe(true);
        expect(reg.has("snapshotMigration-maxConnections")).toBe(true);
    });

    it("returns a fresh array on each call (callers can mutate safely)", () => {
        const a = builtinMutators();
        const b = builtinMutators();
        expect(a).not.toBe(b);
        expect(a[0]).not.toBe(b[0]);
    });

    it("enables matrix expansion for a default safe spec on captureproxy:capture-proxy", () => {
        const reg = new MutatorRegistry();
        for (const m of builtinMutators()) reg.register(m);

        const spec: ScenarioSpec = {
            baseConfig: "./anything.yaml",
            phaseCompletionTimeoutSeconds: 600,
            matrix: {
                subject: "captureproxy:capture-proxy",
                select: [{ changeClass: "safe", patterns: ["subject-change"] }],
            },
            lifecycle: { setup: [], teardown: [] },
            approvalGates: [],
            fixtures: {},
        };
        const cases = expandCases(spec, reg);
        expect(cases).toHaveLength(1);
        expect(cases[0].caseName).toBe(
            "captureproxy-capture-proxy-subject-change-proxy-numThreads",
        );
    });

    it("enables matrix expansion for the basic snapshot-migration impossible spec", () => {
        const reg = new MutatorRegistry();
        for (const m of builtinMutators()) reg.register(m);

        const spec: ScenarioSpec = {
            baseConfig: "./anything.yaml",
            phaseCompletionTimeoutSeconds: 600,
            matrix: {
                subject: "snapshotmigration:source-target-snap1-migration-0",
                select: [{
                    changeClass: "impossible",
                    patterns: ["subject-impossible-change"],
                    response: "leave-blocked",
                }],
            },
            lifecycle: { setup: [], teardown: [] },
            approvalGates: [],
            fixtures: {},
        };
        const cases = expandCases(spec, reg);
        expect(cases).toHaveLength(1);
        expect(cases[0].caseName).toBe(
            "snapshotmigration-source-target-snap1-migration-0-subject-impossible-change-snapshotMigration-maxConnections",
        );
        expect(cases[0].response).toBe("leave-blocked");
    });

    it("enables in-progress DataSnapshot expansion with a poison pill", () => {
        const reg = new MutatorRegistry();
        for (const m of builtinMutators()) reg.register(m);

        const spec: ScenarioSpec = {
            baseConfig: "./anything.yaml",
            phaseCompletionTimeoutSeconds: 600,
            matrix: {
                subject: "datasnapshot:source-snap1",
                select: [{
                    changeClass: "safe",
                    patterns: ["subject-change"],
                    poisonPill: "bad-repo-endpoint",
                }],
            },
            lifecycle: { setup: [], teardown: [] },
            approvalGates: [],
            fixtures: {
                poisonPills: {
                    byName: {
                        "bad-repo-endpoint": {
                            subject: "datasnapshot:source-snap1",
                            strategy: "config-value",
                            expectedCollateral: [],
                            poison: {
                                path: "sourceClusters.source.snapshotInfo.repos.default.endpoint",
                                value: "http://bad:4566",
                            },
                            restore: {
                                path: "sourceClusters.source.snapshotInfo.repos.default.endpoint",
                                value: "localstack://localstack:4566",
                            },
                        },
                    },
                },
            },
        };
        const cases = expandCases(spec, reg);
        expect(cases).toHaveLength(1);
        expect(cases[0]).toMatchObject({
            caseName:
                "datasnapshot-source-snap1-subject-change-dataSnapshot-maxSnapshotRate-in-progress-bad-repo-endpoint",
            subjectStateAtMutation: "in-progress",
            poisonPillName: "bad-repo-endpoint",
        });
    });

    it("enables in-progress SnapshotMigration gated expansion with a poison pill", () => {
        const reg = new MutatorRegistry();
        for (const m of builtinMutators()) reg.register(m);

        const spec: ScenarioSpec = {
            baseConfig: "./anything.yaml",
            phaseCompletionTimeoutSeconds: 600,
            matrix: {
                subject: "snapshotmigration:source-target-snap1-migration-0",
                select: [{
                    changeClass: "gated",
                    patterns: ["subject-gated-change"],
                    response: "leave-blocked",
                    poisonPill: "bad-target-auth",
                }],
            },
            lifecycle: { setup: [], teardown: [] },
            approvalGates: [],
            fixtures: {
                poisonPills: {
                    byName: {
                        "bad-target-auth": {
                            subject: "snapshotmigration:source-target-snap1-migration-0",
                            strategy: "basic-auth-credentials",
                            expectedCollateral: [],
                            secretName: "target-creds",
                            poison: {
                                usernameEnv: "E2E_BAD_TARGET_USERNAME",
                                passwordEnv: "E2E_BAD_TARGET_PASSWORD",
                            },
                            restore: {
                                usernameEnv: "E2E_TARGET_USERNAME",
                                passwordEnv: "E2E_TARGET_PASSWORD",
                            },
                        },
                    },
                },
            },
        };
        const cases = expandCases(spec, reg);
        expect(cases).toHaveLength(1);
        expect(cases[0]).toMatchObject({
            caseName:
                "snapshotmigration-source-target-snap1-migration-0-subject-gated-change-snapshotMigration-maxConnections-gated-in-progress-bad-target-auth",
            subjectStateAtMutation: "in-progress",
            poisonPillName: "bad-target-auth",
        });
    });
});
