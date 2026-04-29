import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { runNoopSlice, LiveRunnerDeps } from "../src/e2e-run";
import { buildTopology } from "../src/componentTopology";
import { WorkflowCli } from "../src/workflowCli";
import { CaseSnapshot } from "../src/reportSchema";
import { ComponentId, ObservedComponent, ScenarioSpec } from "../src/types";

const COMPONENTS = [
    "captureproxy:source-proxy",
    "kafkacluster:default",
] as ComponentId[];

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

    const readObservations: LiveRunnerDeps["readObservations"] = async () => {
        const components: Record<ComponentId, ObservedComponent> = {};
        for (const c of COMPONENTS) {
            components[c] = {
                componentId: c,
                phase: "Ready",
            };
        }
        return { components };
    };

    const topology = buildTopology({
        components: COMPONENTS,
        edges: [],
    });

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

    const deps: LiveRunnerDeps = {
        workflowCli,
        readObservations,
        topology,
        spec,
        specPath: path.join(tmpDir, "my.test.yaml"),
        baselineConfigPath: baselinePath,
        outputDir: path.join(tmpDir, "snapshots"),
        ...overrides,
    };
    return { deps, calls, tmpDir, baselinePath };
}

describe("runNoopSlice", () => {
    it("runs baseline + noop-pre and writes a snapshot", async () => {
        const { deps, calls, tmpDir } = fakeDeps();
        try {
            const outPath = await runNoopSlice(deps);
            expect(outPath.startsWith(tmpDir)).toBe(true);

            const snapshot: CaseSnapshot = JSON.parse(fs.readFileSync(outPath, "utf8"));
            expect(snapshot.outcome).toBe("passed");
            expect(Object.keys(snapshot.runs)).toEqual(["baseline", "noop-pre"]);
            expect(snapshot.runs["baseline"].checkpoints[0].checkpoint).toBe("mutated-complete");
            expect(snapshot.runs["noop-pre"].checkpoints[0].checkpoint).toBe("noop");
            // Two components, observed at both runs.
            for (const run of ["baseline", "noop-pre"]) {
                const checkpoint = snapshot.runs[run].checkpoints[0];
                expect(Object.keys(checkpoint.components).sort()).toEqual(COMPONENTS.slice().sort());
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
            // Expected order: configure, submit, approve x2 (run 1), configure, submit, approve x2 (run 2).
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
            // Both submit calls include the namespace.
            for (const c of calls.filter((c) => c.args[0] === "submit")) {
                expect(c.args).toEqual(["submit", "--namespace", "ma"]);
            }
            // Approvals use the patterns from the spec.
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

    it("marks outcome as 'partial' with a phase-timeout diagnostic when wait fails", async () => {
        // Return non-terminal phases forever so the phase-completion
        // predicate times out.
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
