import { ComponentId, ScenarioSpec } from "../src/types";
import { MutatorRegistry } from "../src/fixtures/mutators";
import { expandCases } from "../src/matrixExpander";

function fakeSpec(overrides: Partial<ScenarioSpec> = {}): ScenarioSpec {
    return {
        baseConfig: "./baseline.wf.yaml",
        phaseCompletionTimeoutSeconds: 600,
        matrix: { subject: "captureproxy:capture-proxy" },
        lifecycle: { setup: [], teardown: [] },
        approvalGates: [],
        fixtures: {},
        ...overrides,
    };
}

function specWithPoisonPill(overrides: Partial<ScenarioSpec> = {}): ScenarioSpec {
    return fakeSpec({
        fixtures: {
            poisonPills: {
                byName: {
                    "bad-source-endpoint": {
                        subject: "captureproxy:capture-proxy" as ComponentId,
                        strategy: "config-value",
                        expectedCollateral: [],
                        poison: {
                            path: "sourceClusters.source.endpoint",
                            value: "https://bad-source:9200",
                        },
                        restore: {
                            path: "sourceClusters.source.endpoint",
                            value: "https://good-source:9200",
                        },
                    },
                },
            },
        },
        ...overrides,
    });
}

function registryWithSafeMutator(): MutatorRegistry {
    const reg = new MutatorRegistry();
    reg.register({
        name: "proxy-numThreads",
        changeClass: "safe",
        dependencyPattern: "subject-change",
        subject: "captureproxy:capture-proxy" as ComponentId,
        changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.numThreads"],
        apply: (c) => structuredClone(c),
    });
    return reg;
}

function registryWithGatedMutator(): MutatorRegistry {
    const reg = new MutatorRegistry();
    reg.register({
        name: "proxy-gated-toggle",
        changeClass: "gated",
        dependencyPattern: "subject-gated-change",
        subject: "captureproxy:capture-proxy" as ComponentId,
        changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.enabled"],
        approvalPattern: "captureproxy.capture-proxy",
        apply: (c) => structuredClone(c),
    });
    return reg;
}

describe("expandCases", () => {
    it("expands default selector to one case per matching mutator", () => {
        const cases = expandCases(fakeSpec(), registryWithSafeMutator());
        expect(cases).toHaveLength(1);
        expect(cases[0].caseName).toBe(
            "captureproxy-capture-proxy-subject-change-proxy-numThreads",
        );
        expect(cases[0].subject).toBe("captureproxy:capture-proxy");
        expect(cases[0].mutator.name).toBe("proxy-numThreads");
        expect(cases[0].response).toBeNull();
        expect(cases[0].subjectStateAtMutation).toBe("completed");
    });

    it("uses explicit select when provided", () => {
        const spec = fakeSpec({
            matrix: {
                subject: "captureproxy:capture-proxy",
                select: [
                    { changeClass: "safe", patterns: ["subject-change"] },
                ],
            },
        });
        const cases = expandCases(spec, registryWithSafeMutator());
        expect(cases).toHaveLength(1);
        expect(cases[0].response).toBeNull();
    });

    it("throws when selector matches zero mutators", () => {
        const emptyReg = new MutatorRegistry();
        expect(() => expandCases(fakeSpec(), emptyReg)).toThrow(
            /matched zero mutators/,
        );
    });

    it("response is always null for safe selectors", () => {
        const cases = expandCases(fakeSpec(), registryWithSafeMutator());
        for (const c of cases) {
            expect(c.response).toBeNull();
        }
    });

    it("expands multiple patterns into separate cases", () => {
        const reg = new MutatorRegistry();
        reg.register({
            name: "m1",
            changeClass: "safe",
            dependencyPattern: "subject-change",
            subject: "captureproxy:capture-proxy" as ComponentId,
            changedPaths: ["a"],
            apply: (c) => structuredClone(c),
        });
        reg.register({
            name: "m2",
            changeClass: "safe",
            dependencyPattern: "immediate-dependent-change",
            subject: "captureproxy:capture-proxy" as ComponentId,
            changedPaths: ["b"],
            apply: (c) => structuredClone(c),
        });
        const spec = fakeSpec({
            matrix: {
                subject: "captureproxy:capture-proxy",
                select: [
                    {
                        changeClass: "safe",
                        patterns: ["subject-change", "immediate-dependent-change"],
                    },
                ],
            },
        });
        const cases = expandCases(spec, reg);
        expect(cases).toHaveLength(2);
        expect(cases.map((c) => c.mutator.name).sort()).toEqual(["m1", "m2"]);
    });

    it("includes response names so gated/impossible variants do not collide", () => {
        const spec = fakeSpec({
            matrix: {
                subject: "captureproxy:capture-proxy",
                select: [
                    {
                        changeClass: "gated",
                        patterns: ["subject-gated-change"],
                        response: "leave-blocked",
                    },
                    {
                        changeClass: "gated",
                        patterns: ["subject-gated-change"],
                        response: "approve",
                    },
                ],
            },
        });

        const cases = expandCases(spec, registryWithGatedMutator());

        expect(cases.map((c) => c.caseName)).toEqual([
            "captureproxy-capture-proxy-subject-gated-change-proxy-gated-toggle-leave-blocked",
            "captureproxy-capture-proxy-subject-gated-change-proxy-gated-toggle-approve",
        ]);
    });

    it("expands a poison-pill selector to an in-progress case", () => {
        const spec = specWithPoisonPill({
            matrix: {
                subject: "captureproxy:capture-proxy",
                select: [
                    {
                        changeClass: "safe",
                        patterns: ["subject-change"],
                        poisonPill: "bad-source-endpoint",
                    },
                ],
            },
        });

        const cases = expandCases(spec, registryWithSafeMutator());

        expect(cases).toHaveLength(1);
        expect(cases[0]).toMatchObject({
            subjectStateAtMutation: "in-progress",
            poisonPillName: "bad-source-endpoint",
            caseName:
                "captureproxy-capture-proxy-subject-change-proxy-numThreads-in-progress-bad-source-endpoint",
        });
    });

    it("can expand both completed and in-progress coverage for one selector", () => {
        const spec = specWithPoisonPill({
            matrix: {
                subject: "captureproxy:capture-proxy",
                select: [
                    {
                        changeClass: "safe",
                        patterns: ["subject-change"],
                        subjectStates: ["completed", "in-progress"],
                        poisonPill: "bad-source-endpoint",
                    },
                ],
            },
        });

        const cases = expandCases(spec, registryWithSafeMutator());

        expect(cases.map((c) => c.subjectStateAtMutation)).toEqual([
            "completed",
            "in-progress",
        ]);
    });

    it("rejects an in-progress selector whose poison pill targets another subject", () => {
        const spec = specWithPoisonPill({
            fixtures: {
                poisonPills: {
                    byName: {
                        "bad-snapshot-repo": {
                            subject: "datasnapshot:source-snap1" as ComponentId,
                            strategy: "config-value",
                            expectedCollateral: [],
                            poison: { path: "sourceClusters.source.endpoint", value: "bad" },
                            restore: { path: "sourceClusters.source.endpoint", value: "good" },
                        },
                    },
                },
            },
            matrix: {
                subject: "captureproxy:capture-proxy",
                select: [
                    {
                        changeClass: "safe",
                        patterns: ["subject-change"],
                        poisonPill: "bad-snapshot-repo",
                    },
                ],
            },
        });

        expect(() => expandCases(spec, registryWithSafeMutator())).toThrow(
            /targets 'datasnapshot:source-snap1'/,
        );
    });
});
