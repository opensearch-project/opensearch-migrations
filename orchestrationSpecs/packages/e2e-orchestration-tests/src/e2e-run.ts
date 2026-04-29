/**
 * e2e-run — CLI entrypoint and testable core for the noop-only vertical
 * slice.
 *
 * Scope of this slice (per implementation-plan step 2):
 *   1. Parse spec and resolve topology.
 *   2. Submit baseline config.
 *   3. Approve structural gates.
 *   4. Wait for phase completion.
 *   5. Capture a raw observation snapshot.
 *   6. Re-submit the same config (noop-pre).
 *   7. Wait again, capture snapshot, write file.
 *
 * Assertions (assertLogic) and mutator handling are intentionally
 * deferred to later slices. This module exists to prove that the
 * wiring — spec → cli → k8s → snapshot — produces a useful artefact
 * against a real cluster.
 */

import * as path from "node:path";
import * as fs from "node:fs";

import { K8sClient, MIGRATION_CRD_PLURALS, extractCrdObservation } from "./k8sClient";
import { WorkflowCli, buildWorkflowCliRunner } from "./workflowCli";
import { loadScenarioSpec } from "./specLoader";
import {
    PhaseCompletionOutcome,
    realClock,
    waitForPhaseCompletion,
} from "./phaseCompletion";
import {
    requireTopologyForBaseline,
} from "./componentTopologyResolver";
import { ComponentTopology } from "./componentTopology";
import { CaseSnapshot, CaseOutcome, RunRecord, RunCheckpoint } from "./reportSchema";
import { writeCaseSnapshot } from "./snapshotStore";
import { Checkpoint, ComponentId, ObservedComponent, ScenarioSpec, Violation } from "./types";

// ── Types shared by the CLI entrypoint and its testable core ─────────

export interface ReadClusterObservations {
    (): Promise<{ components: Record<ComponentId, ObservedComponent> }>;
}

export interface LiveRunnerDeps {
    workflowCli: WorkflowCli;
    readObservations: ReadClusterObservations;
    topology: ComponentTopology;
    /** Spec that was loaded/validated by the caller. */
    spec: ScenarioSpec;
    specPath: string;
    /** Absolute path to the baseline config file on disk. */
    baselineConfigPath: string;
    outputDir: string;
    /**
     * Injectable clock — the top-level runner uses the real one; tests
     * provide a deterministic implementation.
     */
    clock?: typeof realClock;
}

// ── Core logic (easy to unit-test with fake deps) ────────────────────

/**
 * Run the noop-only slice against the given dependencies. Writes one
 * snapshot file under `outputDir` and returns the written path.
 */
export async function runNoopSlice(deps: LiveRunnerDeps): Promise<string> {
    const clock = deps.clock ?? realClock;
    const startedAt = new Date(clock.now()).toISOString();
    const caseName = buildCaseName(deps.spec);
    const diagnostics: string[] = [];
    const violations: Violation[] = [];
    const runs: Record<string, RunRecord> = {};
    let outcome: CaseOutcome = "partial";

    try {
        const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");

        // Run 1 — baseline submission.
        const baselineCheckpoints: RunCheckpoint[] = [];
        deps.workflowCli.configureEditStdin(baselineYaml);
        deps.workflowCli.submit({ wait: false });
        for (const gate of deps.spec.approvalGates) {
            deps.workflowCli.approve(gate.approvePattern);
        }
        await waitAndCheckpoint(
            deps,
            baselineCheckpoints,
            "mutated-complete",
            diagnostics,
            clock,
        );
        runs["baseline"] = { name: "baseline", checkpoints: baselineCheckpoints };

        // Run 2 — noop-pre: same YAML again.
        const noopCheckpoints: RunCheckpoint[] = [];
        deps.workflowCli.configureEditStdin(baselineYaml);
        deps.workflowCli.submit({ wait: false });
        for (const gate of deps.spec.approvalGates) {
            deps.workflowCli.approve(gate.approvePattern);
        }
        await waitAndCheckpoint(
            deps,
            noopCheckpoints,
            "noop",
            diagnostics,
            clock,
        );
        runs["noop-pre"] = { name: "noop-pre", checkpoints: noopCheckpoints };

        outcome = diagnostics.length === 0 ? "passed" : "partial";
    } catch (e) {
        outcome = "error";
        diagnostics.push(`fatal: ${(e as Error).message}`);
    }

    const snapshot: CaseSnapshot = {
        case: caseName,
        specPath: deps.specPath,
        outcome,
        startedAt,
        finishedAt: new Date(clock.now()).toISOString(),
        runs,
        checkers: [],
        diagnostics,
        violations,
    };
    return writeCaseSnapshot(snapshot, {
        outputDir: deps.outputDir,
        // We allow writing a partial/error snapshot so operators can see
        // what went wrong even when the run aborted early.
        validate: outcome !== "error",
    });
}

async function waitAndCheckpoint(
    deps: LiveRunnerDeps,
    into: RunCheckpoint[],
    checkpoint: Checkpoint,
    diagnostics: string[],
    clock: typeof realClock,
): Promise<void> {
    const obs = await deps.readObservations();
    const phases = Object.values(obs.components).map((c) => ({
        componentId: c.componentId,
        phase: c.phase,
    }));
    const outcome: PhaseCompletionOutcome = await waitForPhaseCompletion({
        components: deps.topology.components,
        timeoutSeconds: deps.spec.phaseCompletionTimeoutSeconds,
        readObservations: async () => {
            const o = await deps.readObservations();
            return Object.values(o.components).map((c) => ({
                componentId: c.componentId,
                phase: c.phase,
            }));
        },
        clock,
    });

    // Re-read final state after wait so the snapshot has the settled
    // phases.
    const finalObs = await deps.readObservations();

    if (outcome.kind === "timeout") {
        diagnostics.push(
            `phase-timeout at ${checkpoint}: ${outcome.blockingComponents
                .map((b) => `${b.componentId}=${b.phase}`)
                .join(", ")}`,
        );
    }

    into.push({
        checkpoint,
        observedAt: new Date(clock.now()).toISOString(),
        components: finalObs.components,
        violations: [],
    });
    // phases is used only for diagnostics debugging; suppress unused warning
    void phases;
}

function buildCaseName(spec: ScenarioSpec): string {
    // For the noop slice we only run one synthetic case per spec.
    return `${spec.matrix.subject.replace(/:/g, "-")}-noop`;
}

// ── CLI entrypoint ───────────────────────────────────────────────────

export interface RunFromSpecOptions {
    specPath: string;
    outputDir: string;
    namespace: string;
    /** Defaults to kubectl-exec against migration-console-0. */
    cliMode?: "local" | "kubectl-exec";
    pod?: string;
}

/**
 * Top-level entrypoint. The CLI below wraps this with argv parsing; the
 * function itself is exported so in-process callers (and tests that
 * choose to use real resources) can run it directly.
 */
export async function runFromSpec(opts: RunFromSpecOptions): Promise<string> {
    const loaded = loadScenarioSpec(opts.specPath);
    const topology = requireTopologyForBaseline(loaded.resolvedBaseConfigPath);

    const cliMode = opts.cliMode ?? "kubectl-exec";
    const runner =
        cliMode === "local"
            ? buildWorkflowCliRunner({ mode: "local" })
            : buildWorkflowCliRunner({
                  mode: "kubectl-exec",
                  namespace: opts.namespace,
                  pod: opts.pod ?? "migration-console-0",
              });
    const workflowCli = new WorkflowCli({ runner, namespace: opts.namespace });
    const k8s = new K8sClient({ namespace: opts.namespace });

    const readObservations: ReadClusterObservations = async () => {
        const components: Record<ComponentId, ObservedComponent> = {};
        for (const plural of MIGRATION_CRD_PLURALS) {
            let items: Record<string, unknown>[] = [];
            try {
                items = k8s.getAll(plural).items;
            } catch {
                // CRDs we care about may not exist until the first submit;
                // treat failures as "no items seen yet".
                continue;
            }
            for (const item of items) {
                const obs = extractCrdObservation(plural, item);
                if (!obs) continue;
                components[obs.componentId] = {
                    componentId: obs.componentId,
                    phase: obs.phase,
                    generation: obs.generation,
                    uid: obs.uid,
                    raw: obs.raw,
                };
            }
        }
        return { components };
    };

    return runNoopSlice({
        workflowCli,
        readObservations,
        topology,
        spec: loaded.spec,
        specPath: path.resolve(opts.specPath),
        baselineConfigPath: loaded.resolvedBaseConfigPath,
        outputDir: opts.outputDir,
    });
}

// ── CLI shim ─────────────────────────────────────────────────────────

async function main(): Promise<void> {
    const args = process.argv.slice(2);
    if (args.length < 1) {
        console.error(
            "Usage: e2e-run <spec-path> [--namespace ma] [--output-dir ./snapshots] [--local]",
        );
        process.exit(2);
    }
    const specPath = args[0];
    const namespace = takeFlag(args, "--namespace") ?? "ma";
    const outputDir = takeFlag(args, "--output-dir") ?? path.resolve("snapshots");
    const cliMode = args.includes("--local") ? "local" : "kubectl-exec";

    try {
        const out = await runFromSpec({ specPath, namespace, outputDir, cliMode });
        console.log(`Snapshot written to ${out}`);
    } catch (e) {
        console.error((e as Error).message);
        process.exit(1);
    }
}

function takeFlag(args: string[], flag: string): string | undefined {
    const idx = args.indexOf(flag);
    if (idx < 0) return undefined;
    const v = args[idx + 1];
    args.splice(idx, 2);
    return v;
}

// `require.main === module` equivalent for CommonJS; tsx/ts-node both
// set `main` to this file when invoked directly.
if (require.main === module) {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    main();
}
