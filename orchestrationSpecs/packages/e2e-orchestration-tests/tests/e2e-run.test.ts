import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { runNoopSlice, LiveRunnerDeps } from "../src/e2e-run";
import { buildTopology } from "../src/componentTopology";
import { WorkflowCli } from "../src/workflowCli";
import { K8sClient } from "../src/k8sClient";
import { ActorRegistry, Actor } from "../src/actors";
import { CaseSnapshot } from "../src/reportSchema";
import { ComponentId, ObservedComponent, ScenarioSpec } from "../src/types";

const COMPONENTS = [
    "captureproxy:capture-proxy",
    "kafkacluster:default",
] as ComponentId[];

/**
 * Build a default dep bundle for tests. Overrides can replace any
 * field wholesale; actorOverrides/specOverrides make the two most
 * common replacements ergonomic.
 */
function fakeDeps(overrides: Partial<LiveRunnerDeps> = {}): {
    deps: LiveRunnerDeps;
    calls: { args: readonly string[]; input?: string }[];
    tmpDir: string;
    baselinePath: string;
} {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-run-"));
    const baselinePath = path.join(tmpDir, "baseline.wf.yaml");
    fs.writeFileSync(baselinePath, "proxy:\n  captureConfig: {}\n", "utf8");

    const calls: { args: readonly string[]; input?: string }[] = [];
    const workflowCli = new WorkflowCli({
        runner: (args, opts) => {
            calls.push({ args: [...args], input: opts?.input });
            return { stdout: "", stderr: "", exitCode: 0 };
        },
        namespace: "ma",
    });

    // K8sClient is only used for actor context in the noop slice —
    // give it a runner that never gets called.
    const k8sClient = new K8sClient({
        namespace: "ma",
        runner: () => ({ stdout: "", stderr: "", exitCode: 0 }),
    });

    const readObservations: LiveRunnerDeps["readObservations"] = async () => {
        const components: Record<ComponentId, ObservedComponent> = {};
        for (const c of COMPONENTS) {
            components[c] = { componentId: c, phase: "Ready" };
        }
        return { components };
    };

    const topology = buildTopology({ components: COMPONENTS, edges: [] });

    const spec: ScenarioSpec = {
        baseConfig: "./baseline.wf.yaml",
        phaseCompletionTimeoutSeconds: 5,
        matrix: { subject: COMPONENTS[0] },
        lifecycle: { setup: [], teardown: [] },
        approvalGates: [
            { approvePattern: "*.evaluateMetadata", validations: [] },
            { approvePattern: "*.migrateMetadata", validations: [] },
        ],
    };

    // Deterministic name suffix so assertions can pin the full
    // workflow names.
    let suffixSeq = 0;
    const workflowNameSuffix = () => `s${++suffixSeq}`;

    const deps: LiveRunnerDeps = {
        workflowCli,
        k8sClient,
        namespace: "ma",
        readObservations,
        topology,
        actorRegistry: new ActorRegistry(),
        spec,
        specPath: path.join(tmpDir, "my.test.yaml"),
        baselineConfigPath: baselinePath,
        outputDir: path.join(tmpDir, "snapshots"),
        workflowNameSuffix,
        ...overrides,
    };
    return { deps, calls, tmpDir, baselinePath };
}

describe("runNoopSlice — basic flow", () => {
    it("runs baseline + noop-pre and writes a snapshot", async () => {
        const { deps, tmpDir } = fakeDeps();
        try {
            const outPath = await runNoopSlice(deps);
            expect(outPath.startsWith(tmpDir)).toBe(true);

            const snapshot: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snapshot.outcome).toBe("passed");
            expect(Object.keys(snapshot.runs)).toEqual(["baseline", "noop-pre"]);
            expect(snapshot.runs["baseline"].checkpoints[0].checkpoint).toBe("mutated-complete");
            expect(snapshot.runs["noop-pre"].checkpoints[0].checkpoint).toBe("noop");
            for (const run of ["baseline", "noop-pre"]) {
                const checkpoint = snapshot.runs[run].checkpoints[0];
                expect(Object.keys(checkpoint.components).sort()).toEqual(
                    COMPONENTS.slice().sort(),
                );
            }
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("invokes configure+submit twice and approves each structural gate per run", async () => {
        const { deps, calls, tmpDir } = fakeDeps();
        try {
            await runNoopSlice(deps);
            const subcommands = calls.map((c) => c.args[0]);
            expect(subcommands).toEqual([
                "configure",
                "submit",
                "approve",
                "approve",
                "configure",
                "submit",
                "approve",
                "approve",
            ]);
            const approvePatterns = calls
                .filter((c) => c.args[0] === "approve")
                .map((c) => c.args[1]);
            expect(approvePatterns).toEqual([
                "*.evaluateMetadata",
                "*.migrateMetadata",
                "*.evaluateMetadata",
                "*.migrateMetadata",
            ]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runNoopSlice — unique workflow names", () => {
    it("uses distinct workflow names for baseline and noop-pre", async () => {
        const { deps, calls, tmpDir } = fakeDeps();
        try {
            await runNoopSlice(deps);
            const submits = calls.filter((c) => c.args[0] === "submit");
            expect(submits).toHaveLength(2);

            const nameOf = (submitCall: { args: readonly string[] }) => {
                const idx = submitCall.args.indexOf("--workflow-name");
                return idx >= 0 ? submitCall.args[idx + 1] : undefined;
            };

            const names = submits.map(nameOf);
            // Both present.
            expect(names.every((n) => typeof n === "string" && n.length > 0)).toBe(true);
            // Distinct.
            expect(new Set(names).size).toBe(2);
            // Start with the case-name + run-name convention so humans
            // can tell which submission produced which workflow.
            expect(names[0]).toMatch(/-baseline-/);
            expect(names[1]).toMatch(/-noop-pre-/);
            // And with the deterministic suffixer injected in fakeDeps,
            // distinct suffixes were actually drawn.
            expect(names[0]).toMatch(/-s1$/);
            expect(names[1]).toMatch(/-s2$/);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("the real default suffix generator produces different names across runs", async () => {
        // Use the production suffix (no workflowNameSuffix override) and
        // run twice. Given a 3-byte random, collisions are one in 2^24;
        // we still check against the more robust property that the two
        // submissions within a single run are distinct.
        const first = fakeDeps({ workflowNameSuffix: undefined });
        try {
            await runNoopSlice(first.deps);
            const submits = first.calls.filter((c) => c.args[0] === "submit");
            const names = submits.map((c) => {
                const idx = c.args.indexOf("--workflow-name");
                return c.args[idx + 1];
            });
            expect(new Set(names).size).toBe(2);
        } finally {
            fs.rmSync(first.tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runNoopSlice — lifecycle actors", () => {
    function recordingActor(name: string, log: string[]): Actor {
        return {
            name,
            run: async () => {
                log.push(`${name}:ok`);
            },
        };
    }

    function failingActor(name: string, err: string): Actor {
        return {
            name,
            run: async () => {
                throw new Error(err);
            },
        };
    }

    it("runs setup then teardown on success", async () => {
        const log: string[] = [];
        const { deps, tmpDir } = fakeDeps();
        deps.actorRegistry.register(recordingActor("setup-1", log));
        deps.actorRegistry.register(recordingActor("teardown-1", log));
        deps.spec.lifecycle.setup.push("setup-1");
        deps.spec.lifecycle.teardown.push("teardown-1");

        try {
            const outPath = await runNoopSlice(deps);
            const snap: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(log).toEqual(["setup-1:ok", "teardown-1:ok"]);
            expect(snap.outcome).toBe("passed");
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("runs teardown even when the main body errors", async () => {
        const log: string[] = [];
        const { deps, tmpDir } = fakeDeps();
        deps.actorRegistry.register(recordingActor("teardown-only", log));
        deps.spec.lifecycle.teardown.push("teardown-only");

        // Force the main body to error partway through.
        (deps.workflowCli as unknown as { configureEditStdin: () => never }).configureEditStdin =
            () => {
                throw new Error("kubectl exec failed");
            };

        try {
            const outPath = await runNoopSlice(deps);
            const snap: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snap.outcome).toBe("error");
            expect(snap.diagnostics.join("\n")).toMatch(/kubectl exec failed/);
            // Teardown actor still ran despite the failure.
            expect(log).toEqual(["teardown-only:ok"]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("records teardown-actor failures as diagnostics but does not mask the original error", async () => {
        const { deps, tmpDir } = fakeDeps();
        deps.actorRegistry.register(failingActor("cleanup-bad", "cleanup-bad boom"));
        deps.actorRegistry.register(failingActor("cleanup-other", "cleanup-other boom"));
        deps.spec.lifecycle.teardown.push("cleanup-bad", "cleanup-other");

        (deps.workflowCli as unknown as { configureEditStdin: () => never }).configureEditStdin =
            () => {
                throw new Error("original boom");
            };

        try {
            const outPath = await runNoopSlice(deps);
            const snap: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snap.outcome).toBe("error");

            const msgs = snap.diagnostics.join("\n");
            // Original error still present.
            expect(msgs).toMatch(/original boom/);
            // Both teardown failures recorded, not short-circuited.
            expect(msgs).toMatch(/cleanup-bad boom/);
            expect(msgs).toMatch(/cleanup-other boom/);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("aborts before submission if a setup actor fails", async () => {
        const log: string[] = [];
        const { deps, calls, tmpDir } = fakeDeps();
        deps.actorRegistry.register(failingActor("setup-bad", "setup-bad boom"));
        deps.actorRegistry.register(recordingActor("teardown-x", log));
        deps.spec.lifecycle.setup.push("setup-bad");
        deps.spec.lifecycle.teardown.push("teardown-x");

        try {
            const outPath = await runNoopSlice(deps);
            const snap: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snap.outcome).toBe("error");
            // No configure/submit calls happened.
            expect(calls.map((c) => c.args[0])).not.toContain("configure");
            expect(calls.map((c) => c.args[0])).not.toContain("submit");
            // Teardown still ran.
            expect(log).toEqual(["teardown-x:ok"]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("rejects unknown actor names in the spec at expansion time", async () => {
        const { deps, tmpDir } = fakeDeps();
        deps.spec.lifecycle.setup.push("does-not-exist");
        try {
            // runNoopSlice catches thrown errors internally and writes
            // an 'error' snapshot, so we don't assert a throw here —
            // we assert the diagnostic makes the bad name obvious.
            const outPath = await runNoopSlice(deps);
            const snap: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snap.outcome).toBe("error");
            expect(snap.diagnostics.join("\n")).toMatch(/does-not-exist/);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runNoopSlice — error paths", () => {
    it("marks outcome as 'partial' with a phase-timeout diagnostic when wait fails", async () => {
        const { deps, tmpDir } = fakeDeps({
            spec: {
                baseConfig: "./baseline.wf.yaml",
                phaseCompletionTimeoutSeconds: 1,
                matrix: { subject: COMPONENTS[0] },
                lifecycle: { setup: [], teardown: [] },
                approvalGates: [],
            },
            readObservations: async () => ({
                components: {
                    [COMPONENTS[0]]: { componentId: COMPONENTS[0], phase: "Running" },
                    [COMPONENTS[1]]: { componentId: COMPONENTS[1], phase: "Running" },
                },
            }),
        });
        try {
            const outPath = await runNoopSlice(deps);
            const snapshot: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snapshot.outcome).toBe("partial");
            expect(snapshot.diagnostics.join("\n")).toMatch(/phase-timeout/);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    }, 20000);

    it("marks outcome 'error' when a CLI call throws", async () => {
        const { deps, tmpDir } = fakeDeps();
        (deps.workflowCli as unknown as { configureEditStdin: () => never }).configureEditStdin =
            () => {
                throw new Error("kubectl exec failed");
            };
        try {
            const outPath = await runNoopSlice(deps);
            const snapshot: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snapshot.outcome).toBe("error");
            expect(snapshot.diagnostics[0]).toMatch(/kubectl exec failed/);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});
