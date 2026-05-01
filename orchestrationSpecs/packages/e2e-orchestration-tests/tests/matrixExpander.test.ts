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
        ...overrides,
    };
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
});
