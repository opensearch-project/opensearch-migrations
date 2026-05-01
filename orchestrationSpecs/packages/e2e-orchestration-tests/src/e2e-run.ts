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
import * as crypto from "node:crypto";

import { K8sClient, MIGRATION_CRD_PLURALS, extractCrdObservation } from "./k8sClient";
import {
    WorkflowCli,
    WorkflowCliError,
    WorkflowCliRunResult,
    buildWorkflowCliRunner,
} from "./workflowCli";
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
import {
    CaseEvent,
    CaseSnapshot,
    CaseOutcome,
    RunRecord,
    RunCheckpoint,
} from "./reportSchema";
import { writeCaseSnapshot } from "./snapshotStore";
import { sanitizeWorkflowName } from "./workflowName";
import { Checkpoint, ComponentId, ObservedComponent, ScenarioSpec, Violation } from "./types";
import { Actor, ActorContext, ActorRegistry } from "./actors";
import { builtinActors } from "./builtinActors";
import { deriveBehavior } from "./behaviorDerivation";
import { assertNoViolations } from "./assertLogic";
import { ExpandedTestCase, expandCases } from "./matrixExpander";
import { MutatorRegistry } from "./fixtures/mutators";

// ── Types shared by the CLI entrypoint and its testable core ─────────

const ARGO_WORKFLOW_RESOURCE = "workflows.argoproj.io";
const INNER_MIGRATION_WORKFLOW_NAME = "migration-workflow";

export interface ReadClusterObservations {
    (): Promise<{ components: Record<ComponentId, ObservedComponent> }>;
}

export interface LiveRunnerDeps {
    workflowCli: WorkflowCli;
    /** Used only to build `ActorContext` for lifecycle actors. */
    k8sClient: K8sClient;
    readObservations: ReadClusterObservations;
    topology: ComponentTopology;
    /** Registry of lifecycle actors available to the spec. */
    actorRegistry: ActorRegistry;
    /** Namespace passed through to `ActorContext`. */
    namespace: string;
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
    /**
     * Suffix generator for workflow names. Tests inject a deterministic
     * implementation so assertions can pin the full name. Production
     * uses a short crypto-random hex string.
     */
    workflowNameSuffix?: () => string;
    /**
     * Registry of mutators available for matrix expansion. Optional —
     * only needed when running safe/gated/impossible cases.
     */
    mutatorRegistry?: MutatorRegistry;
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
    const suffixer =
        deps.workflowNameSuffix ?? (() => crypto.randomBytes(3).toString("hex"));
    const diagnostics: string[] = [];
    const violations: Violation[] = [];
    const runs: Record<string, RunRecord> = {};
    const events: CaseEvent[] = [];
    let outcome: CaseOutcome = "partial";

    // Resolved inside the try so an unknown actor becomes a diagnostic
    // on the snapshot rather than an uncaught throw.
    let setupActors: ReturnType<ActorRegistry["resolveAll"]> = [];
    let teardownActors: ReturnType<ActorRegistry["resolveAll"]> = [];
    const actorCtxFor = (phase: "setup" | "teardown"): ActorContext => ({
        workflowCli: deps.workflowCli,
        k8sClient: deps.k8sClient,
        namespace: deps.namespace,
        baselineConfigPath: deps.baselineConfigPath,
        phase,
    });

    try {
        setupActors = deps.actorRegistry.resolveAll(deps.spec.lifecycle.setup);
        teardownActors = deps.actorRegistry.resolveAll(
            deps.spec.lifecycle.teardown,
        );

        // Setup actors — stop on first error so we don't submit a
        // workflow against an unprepared cluster.
        if (setupActors.length > 0) {
            events.push(event(clock, "setup", "run-actors", "ok", {
                message: `running ${setupActors.length} setup actor(s)`,
            }));
            const res = await deps.actorRegistry.runAll(
                setupActors,
                actorCtxFor("setup"),
                { stopOnError: true },
            );
            diagnostics.push(...res.diagnostics);
            if (res.diagnostics.length > 0) {
                events.push(event(clock, "setup", "run-actors", "error", {
                    message: res.diagnostics.join("; "),
                }));
                throw new Error(
                    `setup aborted: ${res.diagnostics.join("; ")}`,
                );
            }
            events.push(event(clock, "setup", "run-actors", "ok", {
                message: "setup actors completed",
            }));
        }

        const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");

        const baselineWorkflowName = sanitizeWorkflowName(
            `${caseName}-baseline-${suffixer()}`,
        );
        runs["baseline"] = await runSubmittedWorkflow({
            deps,
            runName: "baseline",
            workflowName: baselineWorkflowName,
            configYaml: baselineYaml,
            checkpoint: "baseline-complete",
            subject: null,
            priorComponents: null,
            diagnostics,
            events,
            clock,
        });

        // Hand baseline observations to noop-pre so behavior can be
        // derived (skipped vs reran) at the noop checkpoint.
        const baselineLast =
            runs["baseline"].checkpoints[runs["baseline"].checkpoints.length - 1]?.components ??
            null;

        const noopWorkflowName = sanitizeWorkflowName(
            `${caseName}-noop-pre-${suffixer()}`,
        );
        runs["noop-pre"] = await runSubmittedWorkflow({
            deps,
            runName: "noop-pre",
            workflowName: noopWorkflowName,
            configYaml: baselineYaml,
            checkpoint: "noop",
            subject: null,
            priorComponents: baselineLast,
            diagnostics,
            events,
            clock,
        });

        // Noop checkpoint violations bump outcome to partial; we never
        // promote past 'partial' from within assertLogic output alone.
        const noopViolations = collectViolations(runs["noop-pre"]);
        violations.push(...noopViolations);

        outcome = diagnostics.length === 0 && violations.length === 0 ? "passed" : "partial";
    } catch (e) {
        outcome = "error";
        diagnostics.push(...formatFatalDiagnostics(e));
    } finally {
        // Teardown actors always run, regardless of what went wrong.
        // Their own failures are recorded as diagnostics but do not
        // mask the original error or promote outcome beyond "error".
        if (teardownActors.length > 0) {
            events.push(event(clock, "teardown", "run-actors", "ok", {
                message: `running ${teardownActors.length} teardown actor(s)`,
            }));
            const res = await deps.actorRegistry.runAll(
                teardownActors,
                actorCtxFor("teardown"),
            );
            diagnostics.push(...res.diagnostics);
            events.push(event(clock, "teardown", "run-actors", res.diagnostics.length > 0 ? "error" : "ok", {
                message: res.diagnostics.length > 0
                    ? res.diagnostics.join("; ")
                    : "teardown actors completed",
            }));
            if (outcome === "passed" && res.diagnostics.length > 0) {
                outcome = "partial";
            }
        }
    }

    const snapshot: CaseSnapshot = {
        case: caseName,
        specPath: deps.specPath,
        outcome,
        startedAt,
        finishedAt: new Date(clock.now()).toISOString(),
        runs,
        events,
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

/**
 * Run a 4-run safe mutation case: baseline → noop-pre → mutated → noop-post.
 * Returns the path to the written snapshot file.
 */
export async function runSafeCase(deps: LiveRunnerDeps, expandedCase: ExpandedTestCase): Promise<string> {
    const clock = deps.clock ?? realClock;
    const startedAt = new Date(clock.now()).toISOString();
    const caseName = expandedCase.caseName;
    const suffixer =
        deps.workflowNameSuffix ?? (() => crypto.randomBytes(3).toString("hex"));
    const diagnostics: string[] = [];
    const violations: Violation[] = [];
    const runs: Record<string, RunRecord> = {};
    const events: CaseEvent[] = [];
    let outcome: CaseOutcome = "partial";

    let setupActors: ReturnType<ActorRegistry["resolveAll"]> = [];
    let teardownActors: ReturnType<ActorRegistry["resolveAll"]> = [];
    const actorCtxFor = (phase: "setup" | "teardown"): ActorContext => ({
        workflowCli: deps.workflowCli,
        k8sClient: deps.k8sClient,
        namespace: deps.namespace,
        baselineConfigPath: deps.baselineConfigPath,
        phase,
    });

    try {
        setupActors = deps.actorRegistry.resolveAll(deps.spec.lifecycle.setup);
        teardownActors = deps.actorRegistry.resolveAll(deps.spec.lifecycle.teardown);

        if (setupActors.length > 0) {
            events.push(event(clock, "setup", "run-actors", "ok", {
                message: `running ${setupActors.length} setup actor(s)`,
            }));
            const res = await deps.actorRegistry.runAll(setupActors, actorCtxFor("setup"), { stopOnError: true });
            diagnostics.push(...res.diagnostics);
            if (res.diagnostics.length > 0) {
                events.push(event(clock, "setup", "run-actors", "error", { message: res.diagnostics.join("; ") }));
                throw new Error(`setup aborted: ${res.diagnostics.join("; ")}`);
            }
            events.push(event(clock, "setup", "run-actors", "ok", { message: "setup actors completed" }));
        }

        const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");

        // Run 1: baseline
        const baselineWorkflowName = sanitizeWorkflowName(`${caseName}-baseline-${suffixer()}`);
        runs["baseline"] = await runSubmittedWorkflow({
            deps, runName: "baseline", workflowName: baselineWorkflowName,
            configYaml: baselineYaml, checkpoint: "baseline-complete",
            subject: null,
            priorComponents: null, diagnostics, events, clock,
        });
        const baselineLast = lastComponents(runs["baseline"]);

        // Run 2: noop-pre
        const noopPreName = sanitizeWorkflowName(`${caseName}-noop-pre-${suffixer()}`);
        runs["noop-pre"] = await runSubmittedWorkflow({
            deps, runName: "noop-pre", workflowName: noopPreName,
            configYaml: baselineYaml, checkpoint: "noop",
            subject: null,
            priorComponents: baselineLast, diagnostics, events, clock,
        });
        violations.push(...collectViolations(runs["noop-pre"]));
        const noopPreLast = lastComponents(runs["noop-pre"]);

        // Run 3: mutated submission
        const mutatedConfig = expandedCase.mutator.apply(
            (await import("yaml")).parse(baselineYaml),
        );
        const mutatedYaml = (await import("yaml")).stringify(mutatedConfig);
        const mutatedWorkflowName = sanitizeWorkflowName(`${caseName}-mutated-${suffixer()}`);
        runs["mutated"] = await runSubmittedWorkflow({
            deps, runName: "mutated", workflowName: mutatedWorkflowName,
            configYaml: mutatedYaml, checkpoint: "mutated-complete",
            subject: expandedCase.subject,
            priorComponents: noopPreLast, diagnostics, events, clock,
        });
        violations.push(...collectViolations(runs["mutated"]));
        const mutatedLast = lastComponents(runs["mutated"]);

        // Run 4: noop-post
        const noopPostName = sanitizeWorkflowName(`${caseName}-noop-post-${suffixer()}`);
        runs["noop-post"] = await runSubmittedWorkflow({
            deps, runName: "noop-post", workflowName: noopPostName,
            configYaml: mutatedYaml, checkpoint: "noop",
            subject: null,
            priorComponents: mutatedLast, diagnostics, events, clock,
        });
        violations.push(...collectViolations(runs["noop-post"]));

        outcome = diagnostics.length === 0 && violations.length === 0 ? "passed" : "partial";
    } catch (e) {
        outcome = "error";
        diagnostics.push(...formatFatalDiagnostics(e));
    } finally {
        if (teardownActors.length > 0) {
            events.push(event(clock, "teardown", "run-actors", "ok", {
                message: `running ${teardownActors.length} teardown actor(s)`,
            }));
            const res = await deps.actorRegistry.runAll(teardownActors, actorCtxFor("teardown"));
            diagnostics.push(...res.diagnostics);
            events.push(event(clock, "teardown", "run-actors", res.diagnostics.length > 0 ? "error" : "ok", {
                message: res.diagnostics.length > 0 ? res.diagnostics.join("; ") : "teardown actors completed",
            }));
            if (outcome === "passed" && res.diagnostics.length > 0) outcome = "partial";
        }
    }

    const snapshot: CaseSnapshot = {
        case: caseName,
        specPath: deps.specPath,
        outcome,
        startedAt,
        finishedAt: new Date(clock.now()).toISOString(),
        runs,
        events,
        checkers: [],
        diagnostics,
        violations,
    };
    return writeCaseSnapshot(snapshot, {
        outputDir: deps.outputDir,
        validate: outcome !== "error",
    });
}

/** Extract the last checkpoint's components from a run, or null. */
function lastComponents(run: RunRecord): Readonly<Record<ComponentId, ObservedComponent>> | null {
    return run.checkpoints[run.checkpoints.length - 1]?.components ?? null;
}

async function runSubmittedWorkflow(args: {
    deps: LiveRunnerDeps;
    runName: string;
    workflowName: string;
    configYaml: string;
    checkpoint: Checkpoint;
    subject: ComponentId | null;
    priorComponents: Readonly<Record<ComponentId, ObservedComponent>> | null;
    diagnostics: string[];
    events: CaseEvent[];
    clock: typeof realClock;
}): Promise<RunRecord> {
    const {
        deps,
        runName,
        workflowName,
        configYaml,
        checkpoint,
        subject,
        priorComponents,
        diagnostics,
        events,
        clock,
    } = args;
    const checkpoints: RunCheckpoint[] = [];
    let attemptedSubmit = false;
    let primaryError: unknown;
    let cleanupFailed = false;

    try {
        workflowEvent(
            events,
            clock,
            runName,
            "configure",
            "workflow configure edit --stdin",
            () => deps.workflowCli.configureEditStdin(configYaml),
        );
        attemptedSubmit = true;
        workflowEvent(
            events,
            clock,
            runName,
            "submit",
            `workflow submit --namespace ${deps.namespace} --workflow-name ${workflowName}`,
            () => deps.workflowCli.submit({ wait: false, workflowName }),
        );
        for (const gate of deps.spec.approvalGates) {
            workflowEvent(
                events,
                clock,
                runName,
                "approve",
                `workflow approve ${gate.approvePattern} --namespace ${deps.namespace}`,
                () => deps.workflowCli.approve(gate.approvePattern),
            );
        }
        await waitAndCheckpoint(
            deps,
            checkpoints,
            checkpoint,
            subject,
            priorComponents,
            diagnostics,
            events,
            clock,
        );
    } catch (e) {
        primaryError = e;
    } finally {
        cleanupFailed = !cleanupWorkflowResources({
            deps,
            runName,
            workflowName,
            deleteOuterWorkflow: attemptedSubmit,
            diagnostics,
            events,
            clock,
        });
    }

    if (primaryError) throw primaryError;
    if (cleanupFailed) {
        throw new Error(`workflow cleanup failed for ${runName}`);
    }
    return { name: runName, checkpoints };
}

function cleanupWorkflowResources(args: {
    deps: LiveRunnerDeps;
    runName: string;
    workflowName: string;
    deleteOuterWorkflow: boolean;
    diagnostics: string[];
    events: CaseEvent[];
    clock: typeof realClock;
}): boolean {
    const {
        deps,
        runName,
        workflowName,
        deleteOuterWorkflow,
        diagnostics,
        events,
        clock,
    } = args;
    const targets = [
        ...(deleteOuterWorkflow ? [workflowName] : []),
        INNER_MIGRATION_WORKFLOW_NAME,
    ];
    let ok = true;

    for (const name of targets) {
        try {
            deps.k8sClient.deleteResourceAndWait(ARGO_WORKFLOW_RESOURCE, name);
            events.push(event(clock, runName, "delete-workflow", "ok", {
                command: `kubectl delete ${ARGO_WORKFLOW_RESOURCE} ${name} -n ${deps.namespace} --ignore-not-found --wait=true --timeout=60s`,
            }));
        } catch (e) {
            ok = false;
            const err = e as Partial<Error> & { stderr?: string };
            const diagnostic = `cleanup failed for ${ARGO_WORKFLOW_RESOURCE}/${name}: ${err.message ?? String(e)}`;
            diagnostics.push(diagnostic);
            events.push(event(clock, runName, "delete-workflow", "error", {
                command: `kubectl delete ${ARGO_WORKFLOW_RESOURCE} ${name} -n ${deps.namespace} --ignore-not-found --wait=true --timeout=60s`,
                message: err.message,
                stderr: err.stderr || undefined,
            }));
        }
    }
    return ok;
}

function formatFatalDiagnostics(e: unknown): string[] {
    const err = e as Partial<Error> & {
        stdout?: string;
        stderr?: string;
        exitCode?: number | null;
    };
    const diagnostics = [`fatal: ${err.message ?? String(e)}`];
    if (err.stderr) diagnostics.push(`stderr: ${err.stderr.trim()}`);
    if (err.stdout) diagnostics.push(`stdout: ${err.stdout.trim()}`);
    return diagnostics;
}

function event(
    clock: typeof realClock,
    phase: string,
    action: string,
    result: "ok" | "error",
    details: Omit<CaseEvent, "at" | "phase" | "action" | "result"> = {},
): CaseEvent {
    return {
        at: new Date(clock.now()).toISOString(),
        phase,
        action,
        result,
        ...details,
    };
}

function workflowEvent(
    events: CaseEvent[],
    clock: typeof realClock,
    phase: string,
    action: string,
    command: string,
    fn: () => WorkflowCliRunResult,
): WorkflowCliRunResult {
    try {
        const result = fn();
        events.push(event(clock, phase, action, "ok", {
            command,
            stdout: result.stdout || undefined,
            stderr: result.stderr || undefined,
        }));
        return result;
    } catch (e) {
        const err = e as Partial<WorkflowCliError>;
        events.push(event(clock, phase, action, "error", {
            command,
            message: err.message,
            stdout: err.stdout || undefined,
            stderr: err.stderr || undefined,
        }));
        throw e;
    }
}

async function waitAndCheckpoint(
    deps: LiveRunnerDeps,
    into: RunCheckpoint[],
    checkpoint: Checkpoint,
    subject: ComponentId | null,
    priorComponents: Readonly<Record<ComponentId, ObservedComponent>> | null,
    diagnostics: string[],
    events: CaseEvent[],
    clock: typeof realClock,
): Promise<void> {
    events.push(event(clock, checkpoint, "wait-phase-completion", "ok", {
        message: `waiting up to ${deps.spec.phaseCompletionTimeoutSeconds}s`,
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
        events.push(event(clock, checkpoint, "wait-phase-completion", "error", {
            message: outcome.blockingComponents
                .map((b) => `${b.componentId}=${b.phase}`)
                .join(", "),
        }));
        diagnostics.push(
            `phase-timeout at ${checkpoint}: ${outcome.blockingComponents
                .map((b) => `${b.componentId}=${b.phase}`)
                .join(", ")}`,
        );
    } else {
        events.push(event(clock, checkpoint, "wait-phase-completion", "ok", {
            message: `ready after ${outcome.waitedMs}ms`,
        }));
    }
    events.push(event(clock, checkpoint, "observe", "ok", {
        message: `captured ${Object.keys(finalObs.components).length} component(s)`,
    }));

    const withBehavior = deriveBehaviorOnAll(finalObs.components, priorComponents);

    into.push({
        checkpoint,
        observedAt: new Date(clock.now()).toISOString(),
        components: withBehavior,
        violations: assertNoViolations({
            checkpoint,
            subject,
            topology: deps.topology,
            observations: withBehavior,
        }),
    });
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
    /**
     * Optional additional actors to register. The CLI always starts
     * with the default built-in registry (see `builtinActors.ts`);
     * anything passed here is layered on top. Programmatic callers
     * can replace the registry wholesale by calling `runNoopSlice`
     * directly.
     */
    extraActors?: readonly Actor[];
    /**
     * When true, built-in stubs are not registered. Use this when a
     * caller wants complete control over lifecycle actors.
     */
    omitBuiltinActors?: boolean;
    /**
     * Optional mutator registry for safe/gated/impossible cases. When
     * provided and the spec expands to at least one safe case, the
     * runner executes the first expanded case instead of the noop slice.
     */
    mutatorRegistry?: MutatorRegistry;
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

    const actorRegistry = new ActorRegistry();
    if (!opts.omitBuiltinActors) {
        for (const a of builtinActors()) actorRegistry.register(a);
    }
    for (const a of opts.extraActors ?? []) actorRegistry.register(a);

    return runNoopSlice({
        workflowCli,
        k8sClient: k8s,
        namespace: opts.namespace,
        readObservations,
        topology,
        actorRegistry,
        spec: loaded.spec,
        specPath: path.resolve(opts.specPath),
        baselineConfigPath: loaded.resolvedBaseConfigPath,
        outputDir: opts.outputDir,
        mutatorRegistry: opts.mutatorRegistry,
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

/**
 * Apply `deriveBehavior` to every observed component, using the
 * matching component in `prior` (if any) as the previous state. The
 * returned record preserves every entry in `curr` plus the derived
 * behavior; entries in `prior` not present in `curr` are dropped —
 * we observe what the CRD API returned now.
 */
function deriveBehaviorOnAll(
    curr: Record<ComponentId, ObservedComponent>,
    prior: Readonly<Record<ComponentId, ObservedComponent>> | null,
): Record<ComponentId, ObservedComponent> {
    const out: Record<ComponentId, ObservedComponent> = {};
    for (const [id, current] of Object.entries(curr) as [ComponentId, ObservedComponent][]) {
        const prev = prior?.[id] ?? null;
        out[id] = { ...current, behavior: deriveBehavior({ prev, curr: current }) };
    }
    return out;
}

/** Collect every checkpoint-attached violation across a run's checkpoints. */
function collectViolations(run: RunRecord): Violation[] {
    return run.checkpoints.flatMap((cp) => cp.violations ?? []);
}

// `require.main === module` equivalent for CommonJS; tsx/ts-node both
// set `main` to this file when invoked directly.
if (require.main === module) {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    main();
}
