import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { runNoopCase, runWorkflowCasePlan, LiveRunnerDeps } from "../src/e2e-run";
import { buildTopology } from "../src/componentTopology";
import { WorkflowCli } from "../src/workflowCli";
import { WorkflowCliError } from "../src/workflowCli";
import { K8sClient } from "../src/k8sClient";
import { ActorRegistry, Actor } from "../src/actors";
import { CaseSnapshot } from "../src/reportSchema";
import { ComponentId, ObservedComponent, ScenarioSpec } from "../src/types";

function readDetailSnapshot(reportPath: string): CaseSnapshot {
    const report = JSON.parse(fs.readFileSync(reportPath, "utf8")) as { detailPath: string };
    return JSON.parse(fs.readFileSync(report.detailPath, "utf8")) as CaseSnapshot;
}

const COMPONENTS = [
    "captureproxy:capture-proxy",
    "kafkacluster:default",
] as ComponentId[];

/**
 * Build a default dep bundle for tests. Overrides can replace any
 * field wholesale.
 */
function makeRunnerTestDeps(overrides: Partial<LiveRunnerDeps> = {}): {
    deps: LiveRunnerDeps;
    calls: { args: readonly string[]; input?: string }[];
    k8sCalls: { args: readonly string[]; input?: string }[];
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

    const k8sCalls: { args: readonly string[]; input?: string }[] = [];
    const k8sClient = new K8sClient({
        namespace: "ma",
        runner: (args, opts) => {
            k8sCalls.push({ args: [...args], input: opts?.input });
            return { stdout: "", stderr: "", exitCode: 0 };
        },
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
        fixtures: {},
    };

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
        ...overrides,
    };
    return { deps, calls, k8sCalls, tmpDir, baselinePath };
}

describe("runNoopCase — basic flow", () => {
    it("runs baseline + noop-pre and writes a snapshot", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        try {
            const outPath = await runNoopCase(deps);
            expect(outPath.startsWith(tmpDir)).toBe(true);

            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("passed");
            expect(Object.keys(snapshot.runs)).toEqual(["baseline", "noop-pre"]);
            expect(snapshot.runs["baseline"].checkpoints[0].checkpoint).toBe("baseline-complete");
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
        const { deps, calls, tmpDir } = makeRunnerTestDeps();
        try {
            await runNoopCase(deps);
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
            const approveCalls = calls
                .filter((c) => c.args[0] === "approve")
                .map((c) => c.args);
            expect(approveCalls).toEqual([
                ["approve", "step", "*.evaluateMetadata", "--namespace", "ma"],
                ["approve", "step", "*.migrateMetadata", "--namespace", "ma"],
                ["approve", "step", "*.evaluateMetadata", "--namespace", "ma"],
                ["approve", "step", "*.migrateMetadata", "--namespace", "ma"],
            ]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("does not directly delete the default workflow resource between submissions", async () => {
        const { deps, k8sCalls, tmpDir } = makeRunnerTestDeps();
        try {
            await runNoopCase(deps);
            const deletes = k8sCalls.filter((c) => c.args[0] === "delete");
            expect(deletes).toEqual([]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("captures inner Argo workflow evidence at checkpoints", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        deps.k8sClient = new K8sClient({
            namespace: "ma",
            runner: (args) => {
                if (args[0] === "delete") {
                    return { stdout: "", stderr: "", exitCode: 0 };
                }
                if (
                    args[0] === "get" &&
                    args[1] === "workflows.argoproj.io" &&
                    args[2] === "migration-workflow"
                ) {
                    return {
                        stdout: JSON.stringify({
                            metadata: { name: "migration-workflow" },
                            status: {
                                phase: "Succeeded",
                                startedAt: "2026-05-05T00:00:00Z",
                                finishedAt: "2026-05-05T00:01:00Z",
                                nodes: {
                                    "node-1": {
                                        displayName: "createSnapshot",
                                        templateName: "runcreatesnapshot",
                                        phase: "Succeeded",
                                        startedAt: "2026-05-05T00:00:10Z",
                                        finishedAt: "2026-05-05T00:00:20Z",
                                    },
                                    "node-2": {
                                        displayName: "waitForDataSnapshot",
                                        phase: "Running",
                                        message: "waiting for DataSnapshot",
                                    },
                                },
                            },
                        }),
                        stderr: "",
                        exitCode: 0,
                    };
                }
                return { stdout: "", stderr: "unexpected kubectl call", exitCode: 99 };
            },
        });

        try {
            const outPath = await runNoopCase(deps);
            const report = JSON.parse(fs.readFileSync(outPath, "utf8"));
            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            const baselineCp = snapshot.runs["baseline"].checkpoints[0];

            expect(baselineCp.argoWorkflow).toMatchObject({
                name: "migration-workflow",
                phase: "Succeeded",
                nodes: expect.arrayContaining([
                    expect.objectContaining({
                        id: "node-1",
                        templateName: "runcreatesnapshot",
                        phase: "Succeeded",
                    }),
                    expect.objectContaining({
                        id: "node-2",
                        displayName: "waitForDataSnapshot",
                        phase: "Running",
                    }),
                ]),
            });
            expect(report.runs[0].checkpoints[0]).toMatchObject({
                argoPhase: "Succeeded",
                argoNodeCount: 2,
            });
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("waits for the inner workflow to complete before checkpointing live runs", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        let now = 0;
        deps.clock = {
            now: () => now,
            sleep: (ms: number) => {
                now += ms;
                return Promise.resolve();
            },
        };
        deps.waitForInnerWorkflowCompletion = true;

        let getWorkflowCalls = 0;
        deps.k8sClient = new K8sClient({
            namespace: "ma",
            runner: (args) => {
                if (args[0] === "delete") {
                    return { stdout: "", stderr: "", exitCode: 0 };
                }
                if (
                    args[0] === "get" &&
                    args[1] === "workflows.argoproj.io" &&
                    args[2] === "migration-workflow"
                ) {
                    getWorkflowCalls += 1;
                    const phase = getWorkflowCalls === 1 || getWorkflowCalls === 4
                        ? "Running"
                        : "Succeeded";
                    if (args.includes("-o") && args.includes("jsonpath={.metadata.name}{\"\\n\"}{.status.phase}{\"\\n\"}{.status.message}{\"\\n\"}{.status.startedAt}{\"\\n\"}{.status.finishedAt}")) {
                        return {
                            stdout: `migration-workflow\n${phase}\n\n\n`,
                            stderr: "",
                            exitCode: 0,
                        };
                    }
                    return {
                        stdout: JSON.stringify({
                            metadata: { name: "migration-workflow" },
                            status: { phase, nodes: {} },
                        }),
                        stderr: "",
                        exitCode: 0,
                    };
                }
                return { stdout: "", stderr: "unexpected kubectl call", exitCode: 99 };
            },
        });

        try {
            const outPath = await runNoopCase(deps);
            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            const waitEvents = snapshot.events.filter(
                (e) => e.action === "wait-workflow-completion",
            );

            expect(waitEvents.map((e) => e.result)).toEqual([
                "ok",
                "ok",
                "ok",
                "ok",
            ]);
            expect(waitEvents.filter((e) => /Succeeded after 2000ms/.test(e.message ?? ""))).toHaveLength(2);
            expect(snapshot.runs["baseline"].checkpoints[0].argoWorkflow?.phase).toBe("Succeeded");
            expect(snapshot.runs["noop-pre"].checkpoints[0].argoWorkflow?.phase).toBe("Succeeded");
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("does not spend the full phase timeout when inner workflow evidence is missing", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        let now = 0;
        deps.clock = {
            now: () => now,
            sleep: (ms: number) => {
                now += ms;
                return Promise.resolve();
            },
        };
        deps.waitForInnerWorkflowCompletion = true;
        deps.innerWorkflowMissingGraceSeconds = 4;
        deps.spec.phaseCompletionTimeoutSeconds = 60;
        deps.k8sClient = new K8sClient({
            namespace: "ma",
            runner: (args) => {
                if (args[0] === "delete") {
                    return { stdout: "", stderr: "", exitCode: 0 };
                }
                if (
                    args[0] === "get" &&
                    args[1] === "workflows.argoproj.io" &&
                    args[2] === "migration-workflow"
                ) {
                    return { stdout: "", stderr: "not found", exitCode: 1 };
                }
                return { stdout: "", stderr: "unexpected kubectl call", exitCode: 99 };
            },
        });

        try {
            const outPath = await runNoopCase(deps);
            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);

            expect(snapshot.outcome).toBe("partial");
            expect(snapshot.diagnostics.join("\n")).toContain("workflow-missing");
            expect(snapshot.diagnostics.join("\n")).not.toContain("workflow-timeout");
            expect(now).toBe(8000);
            expect(snapshot.runs["baseline"].checkpoints[0].violations).toHaveLength(0);
            expect(snapshot.runs["noop-pre"].checkpoints[0].violations).toHaveLength(0);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("stops approval-gate waits when the inner workflow has a failed node", async () => {
        const { deps, tmpDir, baselinePath } = makeRunnerTestDeps();
        let now = 0;
        deps.clock = {
            now: () => now,
            sleep: (ms: number) => {
                now += ms;
                return Promise.resolve();
            },
        };
        deps.spec.phaseCompletionTimeoutSeconds = 60;
        deps.workflowCli = new WorkflowCli({
            namespace: "ma",
            runner: () => ({ stdout: "No gates available.\n", stderr: "", exitCode: 0 }),
        });
        deps.k8sClient = new K8sClient({
            namespace: "ma",
            runner: (args) => {
                if (args[0] === "delete") {
                    return { stdout: "", stderr: "", exitCode: 0 };
                }
                if (
                    args[0] === "get" &&
                    args[1] === "workflows.argoproj.io" &&
                    args[2] === "migration-workflow"
                ) {
                    return {
                        stdout: JSON.stringify({
                            metadata: { name: "migration-workflow" },
                            status: {
                                phase: "Running",
                                nodes: {
                                    failed: {
                                        templateName: "upsertsnapshotmigrationresource",
                                        phase: "Failed",
                                        message: "VAP denied immutable field update",
                                    },
                                },
                            },
                        }),
                        stderr: "",
                        exitCode: 0,
                    };
                }
                return { stdout: "", stderr: "unexpected kubectl call", exitCode: 99 };
            },
        });

        try {
            const outPath = await runWorkflowCasePlan(deps, {
                caseName: "approval-gate-failed-workflow",
                steps: [
                    {
                        runName: "mutated",
                        configYaml: fs.readFileSync(baselinePath, "utf8"),
                        operations: [
                            {
                                kind: "checkpoint",
                                checkpoint: "on-blocked",
                                subject: COMPONENTS[0],
                                approvalGate: {
                                    category: "retry",
                                    pattern: "snapshotmigration.source-target-snap1-migration-0",
                                },
                            },
                        ],
                    },
                ],
            });

            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("error");
            expect(snapshot.diagnostics.join("\n")).toContain(
                "approval-gate-workflow-error at on-blocked",
            );
            expect(snapshot.diagnostics.join("\n")).toContain(
                "upsertsnapshotmigrationresource:Failed",
            );
            expect(now).toBe(10000);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it.each(["on-blocked", "after-approve-without-reset"] as const)(
        "treats admission-policy rejection as the blocked signal for retry-gated impossible changes at %s",
        async (checkpoint) => {
        const { deps, tmpDir, baselinePath } = makeRunnerTestDeps();
        let now = 0;
        deps.clock = {
            now: () => now,
            sleep: (ms: number) => {
                now += ms;
                return Promise.resolve();
            },
        };
        deps.spec.phaseCompletionTimeoutSeconds = 60;
        deps.topology = buildTopology({ components: [COMPONENTS[0]], edges: [] });
        deps.workflowCli = new WorkflowCli({
            namespace: "ma",
            runner: () => ({ stdout: "No gates available.\n", stderr: "", exitCode: 0 }),
        });
        deps.k8sClient = new K8sClient({
            namespace: "ma",
            runner: (args) => {
                if (args[0] === "delete") {
                    return { stdout: "", stderr: "", exitCode: 0 };
                }
                if (
                    args[0] === "get" &&
                    args[1] === "workflows.argoproj.io" &&
                    args[2] === "migration-workflow"
                ) {
                    return {
                        stdout: JSON.stringify({
                            metadata: { name: "migration-workflow" },
                            status: {
                                phase: "Running",
                                nodes: {
                                    failed: {
                                        templateName: "upsertsnapshotmigrationresource",
                                        phase: "Failed",
                                        message:
                                            "The snapshotmigrations \"source-target-snap1-migration-0\" is invalid: " +
                                            "ValidatingAdmissionPolicy denied immutable field update",
                                    },
                                },
                            },
                        }),
                        stderr: "",
                        exitCode: 0,
                    };
                }
                return { stdout: "", stderr: "unexpected kubectl call", exitCode: 99 };
            },
        });

        try {
            const outPath = await runWorkflowCasePlan(deps, {
                caseName: "admission-policy-blocked",
                steps: [
                    {
                        runName: "mutated",
                        configYaml: fs.readFileSync(baselinePath, "utf8"),
                        operations: [
                            {
                                kind: "checkpoint",
                                checkpoint,
                                subject: COMPONENTS[0],
                                approvalGate: {
                                    category: "retry",
                                    pattern: "snapshotmigration.source-target-snap1-migration-0",
                                },
                            },
                        ],
                    },
                ],
            });

            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("passed");
            expect(snapshot.diagnostics.join("\n")).not.toContain(
                "approval-gate-workflow-error",
            );
            expect(
                snapshot.runs["mutated"].checkpoints[0].components[COMPONENTS[0]].behavior,
            ).toBe("blocked");
            expect(now).toBe(10000);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("does not wait for a gated subject to leave Pending once the gate is observed", async () => {
        const { deps, tmpDir, baselinePath } = makeRunnerTestDeps();
        let now = 0;
        deps.clock = {
            now: () => now,
            sleep: (ms: number) => {
                now += ms;
                return Promise.resolve();
            },
        };
        deps.topology = buildTopology({ components: [COMPONENTS[0]], edges: [] });
        deps.readObservations = async () => ({
            components: {
                [COMPONENTS[0]]: {
                    componentId: COMPONENTS[0],
                    phase: "Pending",
                    configChecksum: "c1",
                    generation: 1,
                    uid: "u1",
                },
            } as Record<ComponentId, ObservedComponent>,
        });
        deps.workflowCli = new WorkflowCli({
            namespace: "ma",
            runner: (args) => ({
                stdout:
                    args[0] === "approve" && args[1] === "change" && args[2] === "--list"
                        ? "captureproxy.capture-proxy\n"
                        : "",
                stderr: "",
                exitCode: 0,
            }),
        });

        try {
            const outPath = await runWorkflowCasePlan(deps, {
                caseName: "gated-subject-held",
                steps: [
                    {
                        runName: "mutated",
                        configYaml: fs.readFileSync(baselinePath, "utf8"),
                        operations: [
                            {
                                kind: "checkpoint",
                                checkpoint: "before-approval",
                                subject: COMPONENTS[0],
                                approvalGate: {
                                    category: "change",
                                    pattern: "captureproxy.capture-proxy",
                                },
                            },
                        ],
                    },
                ],
            });

            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("passed");
            expect(
                snapshot.runs["mutated"].checkpoints[0].components[COMPONENTS[0]].behavior,
            ).toBe("gated");
            expect(now).toBe(0);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runNoopCase — workflow submit path", () => {
    it("uses the default workflow submit path for each run", async () => {
        const { deps, calls, tmpDir } = makeRunnerTestDeps();
        try {
            await runNoopCase(deps);
            const submits = calls.filter((c) => c.args[0] === "submit");
            expect(submits.map((c) => c.args)).toEqual([
                ["submit", "--namespace", "ma"],
                ["submit", "--namespace", "ma"],
            ]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runNoopCase — lifecycle actors", () => {
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
        const { deps, tmpDir } = makeRunnerTestDeps();
        deps.actorRegistry.register(recordingActor("setup-1", log));
        deps.actorRegistry.register(recordingActor("teardown-1", log));
        deps.spec.lifecycle.setup.push("setup-1");
        deps.spec.lifecycle.teardown.push("teardown-1");

        try {
            const outPath = await runNoopCase(deps);
            const snap: CaseSnapshot = readDetailSnapshot(outPath);
            expect(log).toEqual(["setup-1:ok", "teardown-1:ok"]);
            expect(snap.outcome).toBe("passed");
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("runs teardown even when the main body errors", async () => {
        const log: string[] = [];
        const { deps, tmpDir } = makeRunnerTestDeps();
        deps.actorRegistry.register(recordingActor("teardown-only", log));
        deps.spec.lifecycle.teardown.push("teardown-only");

        // Force the main body to error partway through.
        (deps.workflowCli as unknown as { configureEditStdin: () => never }).configureEditStdin =
            () => {
                throw new Error("kubectl exec failed");
            };

        try {
            const outPath = await runNoopCase(deps);
            const snap: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snap.outcome).toBe("error");
            expect(snap.diagnostics.join("\n")).toMatch(/kubectl exec failed/);
            // Teardown actor still ran despite the failure.
            expect(log).toEqual(["teardown-only:ok"]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("records teardown-actor failures as diagnostics but does not mask the original error", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        deps.actorRegistry.register(failingActor("cleanup-bad", "cleanup-bad boom"));
        deps.actorRegistry.register(failingActor("cleanup-other", "cleanup-other boom"));
        deps.spec.lifecycle.teardown.push("cleanup-bad", "cleanup-other");

        (deps.workflowCli as unknown as { configureEditStdin: () => never }).configureEditStdin =
            () => {
                throw new Error("original boom");
            };

        try {
            const outPath = await runNoopCase(deps);
            const snap: CaseSnapshot = readDetailSnapshot(outPath);
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
        const { deps, calls, tmpDir } = makeRunnerTestDeps();
        deps.actorRegistry.register(failingActor("setup-bad", "setup-bad boom"));
        deps.actorRegistry.register(recordingActor("teardown-x", log));
        deps.spec.lifecycle.setup.push("setup-bad");
        deps.spec.lifecycle.teardown.push("teardown-x");

        try {
            const outPath = await runNoopCase(deps);
            const snap: CaseSnapshot = readDetailSnapshot(outPath);
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
        const { deps, tmpDir } = makeRunnerTestDeps();
        deps.spec.lifecycle.setup.push("does-not-exist");
        try {
            // runNoopCase catches thrown errors internally and writes
            // an 'error' snapshot, so we don't assert a throw here —
            // we assert the diagnostic makes the bad name obvious.
            const outPath = await runNoopCase(deps);
            const snap: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snap.outcome).toBe("error");
            expect(snap.diagnostics.join("\n")).toMatch(/does-not-exist/);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runNoopCase — error paths", () => {
    it("stops before noop-pre when baseline phase completion times out", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps({
            spec: {
                baseConfig: "./baseline.wf.yaml",
                phaseCompletionTimeoutSeconds: 1,
                matrix: { subject: COMPONENTS[0] },
                lifecycle: { setup: [], teardown: [] },
                approvalGates: [],
                fixtures: {},
            },
            readObservations: async () => ({
                components: {
                    [COMPONENTS[0]]: { componentId: COMPONENTS[0], phase: "Running" },
                    [COMPONENTS[1]]: { componentId: COMPONENTS[1], phase: "Running" },
                },
            }),
        });
        try {
            const outPath = await runNoopCase(deps);
            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("error");
            expect(snapshot.diagnostics.join("\n")).toMatch(/phase-timeout/);
            expect(Object.keys(snapshot.runs)).toEqual(["baseline"]);
            expect(snapshot.events).toEqual(
                expect.arrayContaining([
                    expect.objectContaining({
                        phase: "baseline",
                        action: "abort-after-checkpoint-failure",
                        result: "error",
                    }),
                ]),
            );
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    }, 20000);

    it("marks outcome 'error' when a CLI call throws", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        (deps.workflowCli as unknown as { configureEditStdin: () => never }).configureEditStdin =
            () => {
                throw new WorkflowCliError(
                    "workflow configure edit --stdin exited 1",
                    1,
                    "configuration updated",
                    "missing secret source-creds",
                );
            };
        try {
            const outPath = await runNoopCase(deps);
            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("error");
            expect(snapshot.diagnostics[0]).toMatch(/workflow configure edit/);
            expect(snapshot.diagnostics.join("\n")).toMatch(/stderr: missing secret source-creds/);
            expect(snapshot.diagnostics.join("\n")).toMatch(/stdout: configuration updated/);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("leaves workflow resources in place when a post-submit command fails", async () => {
        const { deps, k8sCalls, tmpDir } = makeRunnerTestDeps();
        (deps.workflowCli as unknown as { approveStep: () => never }).approveStep =
            () => {
                throw new WorkflowCliError(
                    "workflow approve step *.evaluateMetadata --namespace ma exited 1",
                    1,
                    "",
                    "No gates are currently being waited on by the workflow.",
                );
            };

        try {
            const outPath = await runNoopCase(deps);
            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("error");
            const deletes = k8sCalls.filter((c) => c.args[0] === "delete");
            expect(deletes).toEqual([]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("does not let kubectl workflow deletion behavior affect case progress", async () => {
        const { deps, calls, tmpDir } = makeRunnerTestDeps();
        const k8sClient = new K8sClient({
            namespace: "ma",
            runner: (args) => {
                return {
                    stdout: "",
                    stderr: args[0] === "delete" ? "delete should not be called" : "",
                    exitCode: args[0] === "delete" ? 1 : 0,
                };
            },
        });
        deps.k8sClient = k8sClient;

        try {
            const outPath = await runNoopCase(deps);
            const snapshot: CaseSnapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("passed");
            expect(calls.filter((c) => c.args[0] === "submit")).toHaveLength(2);
            expect(snapshot.runs["noop-pre"]).toBeDefined();
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runNoopCase — assertLogic integration", () => {
    it("records 'noop-not-skipped' violations when noop-pre observations differ from baseline", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        // Flip the readObservations implementation so the checksum
        // differs on the second read. deriveBehavior will see that as
        // 'reran' and assertNoViolations will flag it.
        let callCount = 0;
        deps.readObservations = async () => {
            callCount += 1;
            const checksumFor = (c: ComponentId) =>
                callCount < 3 ? `baseline-${c}` : `mutated-${c}`;
            const components: Record<ComponentId, ObservedComponent> = {};
            for (const c of COMPONENTS) {
                components[c] = {
                    componentId: c,
                    phase: "Ready",
                    configChecksum: checksumFor(c),
                    uid: `uid-${c}`,
                };
            }
            return { components };
        };

        try {
            const outPath = await runNoopCase(deps);
            const snap: CaseSnapshot = readDetailSnapshot(outPath);

            // A violation per component on the noop checkpoint.
            const noopCp = snap.runs["noop-pre"].checkpoints[0];
            expect(noopCp.checkpoint).toBe("noop");
            expect(noopCp.violations).toHaveLength(COMPONENTS.length);
            for (const v of noopCp.violations) {
                expect(v.type).toBe("noop-not-skipped");
                expect(v.details).toMatchObject({
                    observedBehavior: "reran",
                    expectedBehavior: "skipped",
                });
            }
            // The runner surfaces those violations at the top level
            // and bumps outcome to 'partial'.
            expect(snap.violations.length).toBe(COMPONENTS.length);
            expect(snap.outcome).toBe("partial");

            // And behavior is populated on the noop-pre observations
            // themselves, so a human reading the snapshot can see it.
            for (const id of Object.keys(noopCp.components) as ComponentId[]) {
                expect(noopCp.components[id].behavior).toBe("reran");
            }
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("populates behavior='ran' on baseline observations (no prior state)", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        try {
            const outPath = await runNoopCase(deps);
            const snap: CaseSnapshot = readDetailSnapshot(outPath);
            const baselineCp = snap.runs["baseline"].checkpoints[0];
            for (const id of Object.keys(baselineCp.components) as ComponentId[]) {
                expect(baselineCp.components[id].behavior).toBe("ran");
            }
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runWorkflowCasePlan — multi-checkpoint run steps", () => {
    it("keeps a submitted workflow alive across checkpoint, approval action, and follow-up checkpoint", async () => {
        const { deps, calls, k8sCalls, tmpDir, baselinePath } = makeRunnerTestDeps({
            spec: {
                baseConfig: "./baseline.wf.yaml",
                phaseCompletionTimeoutSeconds: 5,
                matrix: { subject: COMPONENTS[0] },
                lifecycle: { setup: [], teardown: [] },
                approvalGates: [],
                fixtures: {},
            },
        });

        let callCount = 0;
        deps.readObservations = async () => {
            callCount += 1;
            const beforeApproval = callCount >= 3 && callCount <= 4;
            const afterApproval = callCount >= 5;
            return {
                components: {
                    [COMPONENTS[0]]: beforeApproval
                        ? {
                              componentId: COMPONENTS[0],
                              phase: "Paused",
                              configChecksum: "mutated-subject",
                              uid: "subject-uid",
                          }
                        : {
                              componentId: COMPONENTS[0],
                              phase: "Ready",
                              configChecksum: afterApproval ? "mutated-subject" : "baseline-subject",
                              uid: "subject-uid",
                          },
                    [COMPONENTS[1]]: {
                        componentId: COMPONENTS[1],
                        phase: "Ready",
                        configChecksum: "baseline-upstream",
                        uid: "upstream-uid",
                    },
                },
            };
        };

        try {
            const baselineYaml = fs.readFileSync(baselinePath, "utf8");
            const outPath = await runWorkflowCasePlan(deps, {
                caseName: "captureproxy-capture-proxy-gated-action",
                steps: [
                    {
                        runName: "baseline",
                        configYaml: baselineYaml,
                        operations: [
                            {
                                kind: "checkpoint",
                                checkpoint: "baseline-complete",
                                subject: null,
                            },
                        ],
                    },
                    {
                        runName: "mutated",
                        configYaml: baselineYaml,
                        operations: [
                            {
                                kind: "checkpoint",
                                checkpoint: "before-approval",
                                subject: COMPONENTS[0],
                            },
                            {
                                kind: "approve",
                                pattern: "capture-proxy.vapretry",
                            },
                            {
                                kind: "checkpoint",
                                checkpoint: "after-approval",
                                subject: COMPONENTS[0],
                            },
                        ],
                    },
                ],
            });

            const snapshot = readDetailSnapshot(outPath);
            expect(snapshot.outcome).toBe("passed");
            expect(snapshot.runs["mutated"].checkpoints.map((cp) => cp.checkpoint)).toEqual([
                "before-approval",
                "after-approval",
            ]);
            expect(calls.map((c) => c.args[0])).toEqual([
                "configure",
                "submit",
                "configure",
                "submit",
                "approve",
            ]);
            expect(calls[calls.length - 1].args).toEqual([
                "approve",
                "step",
                "capture-proxy.vapretry",
                "--namespace",
                "ma",
            ]);

            const deletes = k8sCalls.filter((c) => c.args[0] === "delete");
            expect(deletes).toEqual([]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});
