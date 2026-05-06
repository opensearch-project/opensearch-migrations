import { ActorContext, ActorRegistry, Actor } from "../src/actors";
import { K8sClient } from "../src/k8sClient";
import { WorkflowCli } from "../src/workflowCli";
import { ScenarioSpec } from "../src/types";

const SPEC: ScenarioSpec = {
    baseConfig: "/tmp/baseline.wf.yaml",
    phaseCompletionTimeoutSeconds: 5,
    matrix: { subject: "datasnapshot:source-snap1" },
    lifecycle: { setup: [], teardown: [] },
    approvalGates: [],
    fixtures: {},
};

function stubCtx(phase: "setup" | "teardown"): ActorContext {
    return {
        namespace: "ma",
        phase,
        baselineConfigPath: "/tmp/baseline.wf.yaml",
        spec: SPEC,
        workflowCli: new WorkflowCli({
            runner: () => ({ stdout: "", stderr: "", exitCode: 0 }),
            namespace: "ma",
        }),
        k8sClient: new K8sClient({
            namespace: "ma",
            runner: () => ({ stdout: "", stderr: "", exitCode: 0 }),
        }),
    };
}

describe("ActorRegistry", () => {
    it("registers and looks up actors by name", () => {
        const actor: Actor = { name: "a", run: async () => {} };
        const reg = new ActorRegistry([actor]);
        expect(reg.has("a")).toBe(true);
        expect(reg.get("a")).toBe(actor);
        expect(reg.get("b")).toBeUndefined();
    });

    it("rejects duplicate registration", () => {
        const reg = new ActorRegistry();
        reg.register({ name: "a", run: async () => {} });
        expect(() => reg.register({ name: "a", run: async () => {} })).toThrow(
            /already registered/,
        );
    });

    it("resolveAll throws when a name is unknown", () => {
        const reg = new ActorRegistry([{ name: "good", run: async () => {} }]);
        expect(() => reg.resolveAll(["good", "bad"])).toThrow(/unknown actor.*bad/);
    });

    it("resolveAll preserves order", () => {
        const reg = new ActorRegistry([
            { name: "a", run: async () => {} },
            { name: "b", run: async () => {} },
            { name: "c", run: async () => {} },
        ]);
        expect(reg.resolveAll(["c", "a", "b"]).map((a) => a.name)).toEqual([
            "c",
            "a",
            "b",
        ]);
    });

    it("runAll invokes each actor in order", async () => {
        const log: string[] = [];
        const a = { name: "a", run: async () => { log.push("a"); } };
        const b = { name: "b", run: async () => { log.push("b"); } };
        const reg = new ActorRegistry();
        const { diagnostics } = await reg.runAll([a, b], stubCtx("teardown"));
        expect(log).toEqual(["a", "b"]);
        expect(diagnostics).toEqual([]);
    });

    it("runAll captures failures as diagnostics and continues by default", async () => {
        const log: string[] = [];
        const bad = {
            name: "bad",
            run: async () => { throw new Error("boom"); },
        };
        const after = { name: "after", run: async () => { log.push("after"); } };
        const reg = new ActorRegistry();
        const { diagnostics } = await reg.runAll(
            [bad, after],
            stubCtx("teardown"),
        );
        expect(log).toEqual(["after"]);
        expect(diagnostics).toHaveLength(1);
        expect(diagnostics[0]).toMatch(/teardown actor 'bad' failed: boom/);
    });

    it("runAll stops on first error when stopOnError is set", async () => {
        const log: string[] = [];
        const bad = {
            name: "bad",
            run: async () => { throw new Error("boom"); },
        };
        const after = { name: "after", run: async () => { log.push("after"); } };
        const reg = new ActorRegistry();
        const { diagnostics } = await reg.runAll(
            [bad, after],
            stubCtx("setup"),
            { stopOnError: true },
        );
        expect(log).toEqual([]);
        expect(diagnostics).toHaveLength(1);
        expect(diagnostics[0]).toMatch(/setup actor 'bad' failed: boom/);
    });
});
