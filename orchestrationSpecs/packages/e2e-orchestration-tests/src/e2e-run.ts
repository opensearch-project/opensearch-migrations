/**
 * e2e-run — CLI entrypoint and testable case-plan executor for live
 * orchestration tests.
 *
 * A case plan is an ordered list of workflow submissions. Each step
 * supplies the config to submit and the checkpoint assertion to run
 * after the workflow settles. The executor owns common lifecycle
 * concerns: setup actors, workflow cleanup, submit/approve/wait,
 * behavior derivation, violation surfacing, teardown actors, and
 * snapshot writing.
 */

import * as path from "node:path";
import * as fs from "node:fs";
import * as crypto from "node:crypto";
import { parse as parseYaml, stringify as stringifyYaml } from "yaml";
import { OVERALL_MIGRATION_CONFIG } from "@opensearch-migrations/schemas";

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
    ArgoWorkflowObservation,
} from "./reportSchema";
import { CaseReport, writeCaseSnapshot } from "./snapshotStore";
import { sanitizeWorkflowName } from "./workflowName";
import { Checkpoint, ComponentId, ObservedComponent, ScenarioSpec, Violation } from "./types";
import { Actor, ActorContext, ActorRegistry } from "./actors";
import { builtinActors } from "./builtinActors";
import { deriveBehavior } from "./behaviorDerivation";
import { assertNoViolations } from "./assertLogic";
import { ExpandedTestCase, expandCases } from "./matrixExpander";
import { Mutator, MutatorRegistry } from "./fixtures/mutators";
import { builtinMutators } from "./fixtures/builtinMutators";

// ── Types shared by the CLI entrypoint and its testable core ─────────

const ARGO_WORKFLOW_RESOURCE = "workflows.argoproj.io";
const INNER_MIGRATION_WORKFLOW_NAME = "migration-workflow";
const ARGO_WORKFLOW_TERMINAL_PHASES: ReadonlySet<string> = new Set([
    "Succeeded",
    "Failed",
    "Error",
]);
const ARGO_WORKFLOW_FAILURE_PHASES: ReadonlySet<string> = new Set([
    "Failed",
    "Error",
]);
const DEFAULT_INNER_WORKFLOW_MISSING_GRACE_SECONDS = 30;

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
    /**
     * Validate mutated user configs against the public workflow config
     * schema before submitting them. Enabled by the CLI; unit tests
     * that use tiny fake configs leave it off.
     */
    validateMutatedConfig?: boolean;
    /**
     * Wait for the fixed inner Argo workflow to reach a terminal phase
     * before terminal checkpoints. Live runs need this because CRDs can
     * already be Ready/Completed from a prior submission while the new
     * submission is still starting.
     */
    waitForInnerWorkflowCompletion?: boolean;
    /**
     * How long to wait for the inner Argo workflow resource to appear
     * before treating Argo evidence as missing and proceeding with the
     * CRD phase-completion check. Once the workflow appears, the normal
     * phaseCompletionTimeoutSeconds budget still applies.
     */
    innerWorkflowMissingGraceSeconds?: number;
    /** Optional live progress sink for CLI runs. Unit tests leave this unset. */
    progress?: (message: string) => void;
}

export interface WorkflowCasePlanStep {
    runName: string;
    configYaml: string;
    operations: readonly WorkflowCasePlanOperation[];
}

export type WorkflowCasePlanOperation =
    | WorkflowCheckpointOperation
    | WorkflowApproveOperation
    | WorkflowResetOperation;

export interface WorkflowCheckpointOperation {
    kind: "checkpoint";
    checkpoint: Checkpoint;
    subject: ComponentId | null;
    expectedRerunComponents?: readonly ComponentId[];
    changedPaths?: readonly string[];
}

export interface WorkflowApproveOperation {
    kind: "approve";
    /** Approval gate name or glob pattern accepted by `workflow approve`. */
    pattern: string;
    /** Event action label; defaults to `approve-response`. */
    action?: string;
}

export interface WorkflowResetOperation {
    kind: "reset";
    action?: string;
    reset: {
        all?: boolean;
        cascade?: boolean;
        includeProxies?: boolean;
        deleteStorage?: boolean;
        path?: string;
    };
}

export interface WorkflowCasePlan {
    caseName: string;
    steps: readonly WorkflowCasePlanStep[];
}

// ── Core logic (easy to unit-test with injected deps) ────────────────

/**
 * Run the baseline + noop-pre case against the given dependencies. Writes one
 * snapshot file under `outputDir` and returns the written path.
 */
export async function runNoopCase(deps: LiveRunnerDeps): Promise<string> {
    const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");

    return runWorkflowCasePlan(deps, {
        caseName: buildNoopCaseName(deps.spec),
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
                runName: "noop-pre",
                configYaml: baselineYaml,
                operations: [
                    {
                        kind: "checkpoint",
                        checkpoint: "noop",
                        subject: null,
                    },
                ],
            },
        ],
    });
}

/**
 * Run a safe mutation case: baseline → noop-pre → mutated → noop-post.
 * Returns the path to the written snapshot file.
 */
export async function runSafeCase(deps: LiveRunnerDeps, expandedCase: ExpandedTestCase): Promise<string> {
    const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");
    const mutatedConfig = expandedCase.mutator.apply(parseYaml(baselineYaml));
    if (deps.validateMutatedConfig) {
        validateWorkflowConfig(mutatedConfig, expandedCase.caseName);
    }
    const mutatedYaml = stringifyYaml(mutatedConfig);

    return runWorkflowCasePlan(deps, {
        caseName: expandedCase.caseName,
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
                runName: "noop-pre",
                configYaml: baselineYaml,
                operations: [
                    {
                        kind: "checkpoint",
                        checkpoint: "noop",
                        subject: null,
                    },
                ],
            },
            {
                runName: "mutated",
                configYaml: mutatedYaml,
                operations: [
                    {
                        kind: "checkpoint",
                        checkpoint: "mutated-complete",
                        subject: expandedCase.subject,
                        expectedRerunComponents: expandedCase.expectedRerunComponents,
                        changedPaths: expandedCase.changedPaths,
                    },
                ],
            },
            {
                runName: "noop-post",
                configYaml: mutatedYaml,
                operations: [
                    {
                        kind: "checkpoint",
                        checkpoint: "noop",
                        subject: null,
                    },
                ],
            },
        ],
    });
}

/**
 * Run a gated mutation case. The mutated workflow stays alive across
 * the pre-approval checkpoint and optional approval action so the
 * ApprovalGate signal is sent into the same workflow execution that
 * hit the gate.
 */
export async function runGatedCase(deps: LiveRunnerDeps, expandedCase: ExpandedTestCase): Promise<string> {
    const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");
    const mutatedYaml = buildMutatedYaml(deps, expandedCase, baselineYaml);
    const response = requireResponse(expandedCase, ["approve", "leave-blocked"]);
    const approvalPattern = requireApprovalPattern(expandedCase);
    const mutatedOperations: WorkflowCasePlanOperation[] = [
        {
            kind: "checkpoint",
            checkpoint: "before-approval",
            subject: expandedCase.subject,
            expectedRerunComponents: expandedCase.expectedRerunComponents,
            changedPaths: expandedCase.changedPaths,
        },
    ];

    if (response === "approve") {
        mutatedOperations.push(
            {
                kind: "approve",
                pattern: approvalPattern,
            },
            {
                kind: "checkpoint",
                checkpoint: "after-approval",
                subject: expandedCase.subject,
                expectedRerunComponents: expandedCase.expectedRerunComponents,
                changedPaths: expandedCase.changedPaths,
            },
        );
    }

    const steps: WorkflowCasePlanStep[] = [
        baselineStep(baselineYaml),
        noopStep("noop-pre", baselineYaml),
        {
            runName: "mutated",
            configYaml: mutatedYaml,
            operations: mutatedOperations,
        },
    ];

    if (response === "approve") {
        steps.push(noopStep("noop-post", mutatedYaml));
    }

    return runWorkflowCasePlan(deps, {
        caseName: expandedCase.caseName,
        steps,
    });
}

/**
 * Run an impossible mutation case. Response-specific operations prove
 * that approval and reset are independent preconditions:
 * approve-only and reset-only must not advance; reset-then-approve
 * should advance; leave-blocked observes the initial hold only.
 */
export async function runImpossibleCase(deps: LiveRunnerDeps, expandedCase: ExpandedTestCase): Promise<string> {
    const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");
    const mutatedYaml = buildMutatedYaml(deps, expandedCase, baselineYaml);
    const response = requireResponse(expandedCase, [
        "reset-then-approve",
        "approve-only",
        "reset-only",
        "leave-blocked",
    ]);
    const approvalPattern = expandedCase.mutator.approvalPattern;
    const reset = expandedCase.mutator.reset;
    const mutatedOperations: WorkflowCasePlanOperation[] = [
        {
            kind: "checkpoint",
            checkpoint: "on-blocked",
            subject: expandedCase.subject,
            expectedRerunComponents: expandedCase.expectedRerunComponents,
            changedPaths: expandedCase.changedPaths,
        },
    ];

    if (response === "approve-only" || response === "reset-then-approve") {
        if (!approvalPattern) {
            throw new Error(`case '${expandedCase.caseName}' requires mutator.approvalPattern`);
        }
    }
    if (response === "reset-only" || response === "reset-then-approve") {
        if (!reset) {
            throw new Error(`case '${expandedCase.caseName}' requires mutator.reset metadata`);
        }
    }

    switch (response) {
        case "leave-blocked":
            break;
        case "approve-only":
            mutatedOperations.push(
                {
                    kind: "approve",
                    pattern: approvalPattern!,
                },
                {
                    kind: "checkpoint",
                    checkpoint: "after-approve-without-reset",
                    subject: expandedCase.subject,
                    expectedRerunComponents: expandedCase.expectedRerunComponents,
                    changedPaths: expandedCase.changedPaths,
                },
            );
            break;
        case "reset-only":
            mutatedOperations.push(
                {
                    kind: "reset",
                    reset: reset!,
                },
                {
                    kind: "checkpoint",
                    checkpoint: "after-reset",
                    subject: expandedCase.subject,
                    expectedRerunComponents: expandedCase.expectedRerunComponents,
                    changedPaths: expandedCase.changedPaths,
                },
            );
            break;
        case "reset-then-approve":
            mutatedOperations.push(
                {
                    kind: "reset",
                    reset: reset!,
                },
                {
                    kind: "checkpoint",
                    checkpoint: "after-reset",
                    subject: expandedCase.subject,
                    expectedRerunComponents: expandedCase.expectedRerunComponents,
                    changedPaths: expandedCase.changedPaths,
                },
                {
                    kind: "approve",
                    pattern: approvalPattern!,
                },
                {
                    kind: "checkpoint",
                    checkpoint: "after-approve",
                    subject: expandedCase.subject,
                    expectedRerunComponents: expandedCase.expectedRerunComponents,
                    changedPaths: expandedCase.changedPaths,
                },
            );
            break;
    }

    const steps: WorkflowCasePlanStep[] = [
        baselineStep(baselineYaml),
        noopStep("noop-pre", baselineYaml),
        {
            runName: "mutated",
            configYaml: mutatedYaml,
            operations: mutatedOperations,
        },
    ];

    if (response === "reset-then-approve") {
        steps.push(noopStep("noop-post", mutatedYaml));
    }

    return runWorkflowCasePlan(deps, {
        caseName: expandedCase.caseName,
        steps,
    });
}

function buildMutatedYaml(
    deps: Pick<LiveRunnerDeps, "validateMutatedConfig">,
    expandedCase: ExpandedTestCase,
    baselineYaml: string,
): string {
    const mutatedConfig = expandedCase.mutator.apply(parseYaml(baselineYaml));
    if (deps.validateMutatedConfig) {
        validateWorkflowConfig(mutatedConfig, expandedCase.caseName);
    }
    return stringifyYaml(mutatedConfig);
}

function baselineStep(baselineYaml: string): WorkflowCasePlanStep {
    return {
        runName: "baseline",
        configYaml: baselineYaml,
        operations: [
            {
                kind: "checkpoint",
                checkpoint: "baseline-complete",
                subject: null,
            },
        ],
    };
}

function noopStep(runName: "noop-pre" | "noop-post", configYaml: string): WorkflowCasePlanStep {
    return {
        runName,
        configYaml,
        operations: [
            {
                kind: "checkpoint",
                checkpoint: "noop",
                subject: null,
            },
        ],
    };
}

function requireApprovalPattern(expandedCase: ExpandedTestCase): string {
    const pattern = expandedCase.mutator.approvalPattern;
    if (!pattern) {
        throw new Error(`case '${expandedCase.caseName}' requires mutator.approvalPattern`);
    }
    return pattern;
}

function requireResponse<T extends NonNullable<ExpandedTestCase["response"]>>(
    expandedCase: ExpandedTestCase,
    allowed: readonly T[],
): T {
    const response = expandedCase.response;
    if (!response || !allowed.includes(response as T)) {
        throw new Error(
            `case '${expandedCase.caseName}' requires response ${allowed.join(" | ")}; got '${response ?? "null"}'`,
        );
    }
    return response as T;
}

export async function runWorkflowCasePlan(
    deps: LiveRunnerDeps,
    plan: WorkflowCasePlan,
): Promise<string> {
    const clock = deps.clock ?? realClock;
    const startedAt = new Date(clock.now()).toISOString();
    const { caseName } = plan;
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
        spec: deps.spec,
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
            progress(deps, `[${caseName}] setup: running ${setupActors.length} actor(s)`);
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
            progress(deps, `[${caseName}] setup: complete`);
        }

        let priorComponents: Readonly<Record<ComponentId, ObservedComponent>> | null = null;
        for (const step of plan.steps) {
            const workflowName = sanitizeWorkflowName(
                `${caseName}-${step.runName}-${suffixer()}`,
            );
            progress(deps, `[${caseName}] ${step.runName}: submit as ${workflowName}`);
            const run = await runSubmittedWorkflow({
                deps,
                runName: step.runName,
                workflowName,
                configYaml: step.configYaml,
                operations: step.operations,
                priorComponents,
                diagnostics,
                events,
                clock,
            });
            runs[step.runName] = run;
            violations.push(...collectViolations(run));
            priorComponents = lastComponents(run);
            progress(deps, `[${caseName}] ${step.runName}: complete`);
        }

        outcome = diagnostics.length === 0 && violations.length === 0 ? "passed" : "partial";
    } catch (e) {
        outcome = "error";
        diagnostics.push(...formatFatalDiagnostics(e));
    } finally {
        // Teardown actors always run, regardless of what went wrong.
        // Their own failures are recorded as diagnostics but do not
        // mask the original error or promote outcome beyond "error".
        if (teardownActors.length > 0) {
            progress(deps, `[${caseName}] teardown: running ${teardownActors.length} actor(s)`);
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
            progress(deps, `[${caseName}] teardown: complete`);
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

/** Extract the last checkpoint's components from a run, or null. */
function lastComponents(run: RunRecord): Readonly<Record<ComponentId, ObservedComponent>> | null {
    return run.checkpoints[run.checkpoints.length - 1]?.components ?? null;
}

async function runSubmittedWorkflow(args: {
    deps: LiveRunnerDeps;
    runName: string;
    workflowName: string;
    configYaml: string;
    operations: readonly WorkflowCasePlanOperation[];
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
        operations,
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
        progress(deps, `[${workflowName}] cleanup-before-submit`);
        const preSubmitCleanupOk = cleanupWorkflowResources({
            deps,
            runName,
            workflowName,
            deleteOuterWorkflow: false,
            diagnostics,
            events,
            clock,
        });
        if (!preSubmitCleanupOk) {
            throw new Error(`pre-submit workflow cleanup failed for ${runName}`);
        }
        progress(deps, `[${workflowName}] configure`);
        workflowEvent(
            events,
            clock,
            runName,
            "configure",
            "workflow configure edit --stdin",
            () => deps.workflowCli.configureEditStdin(configYaml),
            {
                configSha256: sha256(configYaml),
                configBytes: Buffer.byteLength(configYaml, "utf8"),
            },
        );
        attemptedSubmit = true;
        progress(deps, `[${workflowName}] submit`);
        workflowEvent(
            events,
            clock,
            runName,
            "submit",
            `workflow submit --namespace ${deps.namespace} --workflow-name ${workflowName}`,
            () => deps.workflowCli.submit({ wait: false, workflowName }),
        );
        for (const gate of deps.spec.approvalGates) {
            progress(deps, `[${workflowName}] approve structural gate ${gate.approvePattern}`);
            workflowEvent(
                events,
                clock,
                runName,
                "approve",
                workflowApproveCommand(gate.approvePattern, deps.namespace),
                () => deps.workflowCli.approve(gate.approvePattern),
            );
        }
        for (const operation of operations) {
            if (operation.kind === "checkpoint") {
                progress(deps, `[${workflowName}] checkpoint ${operation.checkpoint}`);
                await waitAndCheckpoint(
                    deps,
                    checkpoints,
                    workflowName,
                    operation.checkpoint,
                    operation.subject,
                    operation.expectedRerunComponents,
                    operation.changedPaths,
                    priorComponents,
                    diagnostics,
                    events,
                    clock,
                );
                continue;
            }
            if (operation.kind === "approve") {
                progress(deps, `[${workflowName}] approve ${operation.pattern}`);
                workflowEvent(
                    events,
                    clock,
                    runName,
                    operation.action ?? "approve-response",
                    workflowApproveCommand(operation.pattern, deps.namespace),
                    () => deps.workflowCli.approve(operation.pattern),
                );
                continue;
            }
            progress(deps, `[${workflowName}] reset`);
            workflowEvent(
                events,
                clock,
                runName,
                operation.action ?? "reset-response",
                workflowResetCommand(operation.reset, deps.namespace),
                () => deps.workflowCli.reset(operation.reset),
            );
        }
    } catch (e) {
        primaryError = e;
    } finally {
        progress(deps, `[${workflowName}] cleanup-after-submit`);
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

function progress(deps: Pick<LiveRunnerDeps, "progress">, message: string): void {
    deps.progress?.(message);
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
    details: Omit<CaseEvent, "at" | "phase" | "action" | "result" | "command" | "stdout" | "stderr" | "message"> = {},
): WorkflowCliRunResult {
    try {
        const result = fn();
        events.push(event(clock, phase, action, "ok", {
            ...details,
            command,
            stdout: result.stdout || undefined,
            stderr: result.stderr || undefined,
        }));
        return result;
    } catch (e) {
        const err = e as Partial<WorkflowCliError>;
        events.push(event(clock, phase, action, "error", {
            ...details,
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
    workflowName: string,
    checkpoint: Checkpoint,
    subject: ComponentId | null,
    expectedRerunComponents: readonly ComponentId[] | undefined,
    changedPaths: readonly string[] | undefined,
    priorComponents: Readonly<Record<ComponentId, ObservedComponent>> | null,
    diagnostics: string[],
    events: CaseEvent[],
    clock: typeof realClock,
): Promise<void> {
    events.push(event(clock, checkpoint, "wait-phase-completion", "ok", {
        message: `waiting up to ${deps.spec.phaseCompletionTimeoutSeconds}s`,
    }));
    let workflowWaitTimedOut = false;
    if (deps.waitForInnerWorkflowCompletion && shouldWaitForInnerWorkflow(checkpoint)) {
        const missingGraceSeconds = Math.min(
            deps.innerWorkflowMissingGraceSeconds ?? DEFAULT_INNER_WORKFLOW_MISSING_GRACE_SECONDS,
            deps.spec.phaseCompletionTimeoutSeconds,
        );
        events.push(event(clock, checkpoint, "wait-workflow-completion", "ok", {
            message:
                `waiting for ${INNER_MIGRATION_WORKFLOW_NAME} to appear up to ${missingGraceSeconds}s ` +
                `and complete up to ${deps.spec.phaseCompletionTimeoutSeconds}s`,
        }));
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpoint}: wait for ${INNER_MIGRATION_WORKFLOW_NAME} up to ${deps.spec.phaseCompletionTimeoutSeconds}s`,
        );
        const workflowOutcome = await waitForInnerWorkflowCompletion({
            k8sClient: deps.k8sClient,
            timeoutSeconds: deps.spec.phaseCompletionTimeoutSeconds,
            missingGraceSeconds,
            clock,
        });
        if (workflowOutcome.kind === "missing-timeout") {
            events.push(event(clock, checkpoint, "wait-workflow-completion", "error", {
                message:
                    `${INNER_MIGRATION_WORKFLOW_NAME} was not observed after ${workflowOutcome.waitedMs}ms; ` +
                    "continuing with CRD phase completion",
            }));
            diagnostics.push(
                `workflow-missing at ${checkpoint}: ${INNER_MIGRATION_WORKFLOW_NAME} was not observed within ${workflowOutcome.waitedMs}ms`,
            );
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpoint}: ${INNER_MIGRATION_WORKFLOW_NAME} missing after ${workflowOutcome.waitedMs}ms; continuing with CRD phases`,
            );
        } else if (workflowOutcome.kind === "timeout") {
            workflowWaitTimedOut = true;
            events.push(event(clock, checkpoint, "wait-workflow-completion", "error", {
                message: `${INNER_MIGRATION_WORKFLOW_NAME} did not complete after ${workflowOutcome.waitedMs}ms; last phase=${workflowOutcome.phase}`,
            }));
            diagnostics.push(
                `workflow-timeout at ${checkpoint}: ${INNER_MIGRATION_WORKFLOW_NAME}=${workflowOutcome.phase}`,
            );
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpoint}: ${INNER_MIGRATION_WORKFLOW_NAME} still ${workflowOutcome.phase} after ${workflowOutcome.waitedMs}ms`,
            );
        } else {
            const result = ARGO_WORKFLOW_FAILURE_PHASES.has(workflowOutcome.phase)
                ? "error"
                : "ok";
            events.push(event(clock, checkpoint, "wait-workflow-completion", result, {
                message: `${INNER_MIGRATION_WORKFLOW_NAME} ${workflowOutcome.phase} after ${workflowOutcome.waitedMs}ms`,
            }));
            if (ARGO_WORKFLOW_FAILURE_PHASES.has(workflowOutcome.phase)) {
                diagnostics.push(
                    `workflow-failed at ${checkpoint}: ${INNER_MIGRATION_WORKFLOW_NAME}=${workflowOutcome.phase}` +
                        (workflowOutcome.message ? ` (${workflowOutcome.message})` : ""),
                );
            }
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpoint}: ${INNER_MIGRATION_WORKFLOW_NAME} ${workflowOutcome.phase} after ${workflowOutcome.waitedMs}ms`,
            );
        }
    }
    progress(
        deps,
        `[${workflowName}] checkpoint ${checkpoint}: wait for ${deps.topology.components.length} component phase(s)`,
    );
    const outcome: PhaseCompletionOutcome = await waitForPhaseCompletion({
        components: deps.topology.components,
        timeoutSeconds: workflowWaitTimedOut ? 1 : deps.spec.phaseCompletionTimeoutSeconds,
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
        const workflowSummary = summarizeArgoWorkflow(deps.k8sClient);
        events.push(event(clock, checkpoint, "wait-phase-completion", "error", {
            message: outcome.blockingComponents
                .map((b) => `${b.componentId}=${b.phase}`)
                .join(", ") + (workflowSummary ? `; ${workflowSummary}` : ""),
        }));
        diagnostics.push(
            `phase-timeout at ${checkpoint}: ${outcome.blockingComponents
                .map((b) => `${b.componentId}=${b.phase}`)
                .join(", ")}`,
        );
        if (workflowSummary) {
            diagnostics.push(`argo: ${workflowSummary}`);
        }
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpoint}: component phase wait timed out after ${outcome.waitedMs}ms`,
        );
    } else {
        events.push(event(clock, checkpoint, "wait-phase-completion", "ok", {
            message: `ready after ${outcome.waitedMs}ms`,
        }));
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpoint}: component phases ready after ${outcome.waitedMs}ms`,
        );
    }
    const argoWorkflow = readArgoWorkflowObservation(deps.k8sClient);
    events.push(event(clock, checkpoint, "observe", "ok", {
        message: [
            `captured ${Object.keys(finalObs.components).length} component(s)`,
            argoWorkflow
                ? `argo ${argoWorkflow.name}=${argoWorkflow.phase ?? "Unknown"}`
                : undefined,
        ].filter(Boolean).join("; "),
    }));

    const withBehavior = deriveBehaviorOnAll(finalObs.components, priorComponents);

    into.push({
        checkpoint,
        observedAt: new Date(clock.now()).toISOString(),
        argoWorkflow,
        components: withBehavior,
        violations: assertNoViolations({
            checkpoint,
            subject,
            expectedRerunComponents,
            changedPaths,
            topology: deps.topology,
            observations: withBehavior,
        }),
    });
}

function buildNoopCaseName(spec: ScenarioSpec): string {
    // Noop-only mode runs one synthetic case per spec.
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
     * can replace the registry wholesale by calling `runNoopCase`
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
     * runner expands the matrix and runs each matching case.
     */
    mutatorRegistry?: MutatorRegistry;
    /**
     * Extra mutators to register alongside the built-ins. Ignored if
     * `mutatorRegistry` is provided.
     */
    extraMutators?: readonly Mutator[];
    /**
     * When true, built-in mutators are not registered. Use this when a
     * caller wants complete control over available mutators. Ignored if
     * `mutatorRegistry` is provided.
     */
    omitBuiltinMutators?: boolean;
    /**
     * When true, ignore the matrix and run only the baseline → noop-pre
     * case. Defaults to false for CLI runs — normal execution expands
     * the matrix and runs every case.
     */
    noopOnly?: boolean;
    /**
     * Optional per-invocation override for the spec's phase completion
     * timeout. This is useful for live smoke runs where the environment
     * may be unhealthy and the caller wants a quick diagnostic snapshot
     * without editing the checked-in spec.
     */
    phaseCompletionTimeoutSeconds?: number;
    /** Optional progress sink; the CLI writes progress to stderr. */
    progress?: (message: string) => void;
}

/**
 * Top-level entrypoint. The CLI below wraps this with argv parsing; the
 * function itself is exported so in-process callers (and tests that
 * choose to use real resources) can run it directly.
 *
 * Returns an array of written snapshot paths — one per expanded case,
 * or a single entry when `noopOnly` is set.
 */
export async function runFromSpec(opts: RunFromSpecOptions): Promise<string[]> {
    const loaded = loadScenarioSpec(opts.specPath);
    if (opts.phaseCompletionTimeoutSeconds !== undefined) {
        loaded.spec.phaseCompletionTimeoutSeconds = opts.phaseCompletionTimeoutSeconds;
    }
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
                    configChecksum: obs.configChecksum,
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

    // Build a mutator registry by layering built-ins under any caller
    // overrides, unless the caller supplied a pre-built registry.
    const mutatorRegistry =
        opts.mutatorRegistry ??
        (() => {
            const reg = new MutatorRegistry();
            if (!opts.omitBuiltinMutators) {
                for (const m of builtinMutators()) reg.register(m);
            }
            for (const m of opts.extraMutators ?? []) reg.register(m);
            return reg;
        })();

    const sharedDeps: LiveRunnerDeps = {
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
        mutatorRegistry,
        validateMutatedConfig: true,
        waitForInnerWorkflowCompletion: true,
        progress: opts.progress,
    };

    // Noop-only mode is useful for bring-up and for debugging
    // phase-completion issues before touching mutation flow.
    if (opts.noopOnly) {
        return [await runNoopCase(sharedDeps)];
    }

    return runExpandedCases(sharedDeps, mutatorRegistry);
}

/**
 * Expand the shared deps' spec into cases and run each one in order.
 *
 * Factored out of `runFromSpec` so tests can exercise the
 * expansion-and-run orchestration against injected deps without having
 * to build a real kubectl/workflow CLI stack.
 */
export async function runExpandedCases(
    deps: LiveRunnerDeps,
    mutatorRegistry: MutatorRegistry,
): Promise<string[]> {
    const cases = expandCases(deps.spec, mutatorRegistry);
    const out: string[] = [];
    for (const expanded of cases) {
        out.push(await runExpandedCase(deps, expanded));
    }
    return out;
}

export async function runExpandedCase(
    deps: LiveRunnerDeps,
    expanded: ExpandedTestCase,
): Promise<string> {
    if (expanded.mutator.changeClass === "safe") {
        return runSafeCase(deps, expanded);
    }
    if (expanded.mutator.changeClass === "gated") {
        return runGatedCase(deps, expanded);
    }
    return runImpossibleCase(deps, expanded);
}

// ── Result summarisation ─────────────────────────────────────────────

export interface CaseSummary {
    path: string;
    case: string;
    outcome: CaseOutcome;
    violationCount: number;
    diagnosticCount: number;
}

export interface RunSummary {
    cases: CaseSummary[];
    totalViolations: number;
    errorCount: number;
    partialCount: number;
    anyFailed: boolean;
}

/**
 * Walk the snapshot files produced by `runFromSpec`/`runExpandedCases`
 * and summarise case outcomes. A run has "failed" (for the CLI exit
 * code) if any case is `error`, or if any case has at least one
 * violation. `partial` without violations (e.g. teardown stub
 * "NotImplementedActorError") is allowed to pass.
 */
export function summariseSnapshots(paths: readonly string[]): RunSummary {
    const cases: CaseSummary[] = [];
    let totalViolations = 0;
    let errorCount = 0;
    let partialCount = 0;

    for (const p of paths) {
        const raw = fs.readFileSync(p, "utf8");
        const parsed = JSON.parse(raw) as Partial<CaseSnapshot> & Partial<CaseReport>;
        const violationCount =
            typeof parsed.violationCount === "number"
                ? parsed.violationCount
                : (parsed.violations ?? []).length;
        const diagnosticCount =
            typeof parsed.diagnosticCount === "number"
                ? parsed.diagnosticCount
                : (parsed.diagnostics ?? []).length;
        if (!parsed.case || !parsed.outcome) {
            throw new Error(`Summary file ${p} is missing case or outcome`);
        }
        const caseName = parsed.case;
        const outcome = parsed.outcome;
        cases.push({
            path: p,
            case: caseName,
            outcome,
            violationCount,
            diagnosticCount,
        });
        totalViolations += violationCount;
        if (parsed.outcome === "error") errorCount += 1;
        if (parsed.outcome === "partial") partialCount += 1;
    }

    return {
        cases,
        totalViolations,
        errorCount,
        partialCount,
        anyFailed: errorCount > 0 || totalViolations > 0,
    };
}

// ── CLI shim ─────────────────────────────────────────────────────────

async function main(): Promise<void> {
    const args = process.argv.slice(2);
    if (args.length < 1) {
        console.error(
            "Usage: e2e-run <spec-path> [--namespace ma] [--output-dir ./snapshots] [--local] [--noop-only] [--phase-timeout-seconds 600]",
        );
        process.exit(2);
    }
    const specPath = args[0];
    const namespace = takeFlag(args, "--namespace") ?? "ma";
    const outputDir = takeFlag(args, "--output-dir") ?? path.resolve("snapshots");
    const cliMode = args.includes("--local") ? "local" : "kubectl-exec";
    const noopOnly = args.includes("--noop-only");

    try {
        const phaseCompletionTimeoutSeconds = parsePositiveIntFlag(
            takeFlag(args, "--phase-timeout-seconds"),
            "--phase-timeout-seconds",
        );
        const out = await runFromSpec({
            specPath,
            namespace,
            outputDir,
            cliMode,
            noopOnly,
            phaseCompletionTimeoutSeconds,
            progress: (message) => console.error(message),
        });
        const summary = summariseSnapshots(out);
        for (const c of summary.cases) {
            const suffix =
                c.violationCount > 0
                    ? ` — ${c.violationCount} violation(s)`
                    : "";
            console.log(
                `Snapshot written to ${c.path} [${c.outcome}]${suffix}`,
            );
        }
        if (summary.anyFailed) {
            console.error(
                `Run failed: ${summary.errorCount} case(s) errored, ${summary.totalViolations} violation(s) across ${summary.cases.length} case(s).`,
            );
            process.exit(1);
        }
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

function parsePositiveIntFlag(value: string | undefined, flag: string): number | undefined {
    if (value === undefined) return undefined;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed <= 0) {
        throw new Error(`${flag} must be a positive integer`);
    }
    return parsed;
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

function workflowApproveCommand(pattern: string, namespace: string): string {
    return `workflow approve ${pattern} --namespace ${namespace}`;
}

function workflowResetCommand(
    reset: WorkflowResetOperation["reset"],
    namespace: string,
): string {
    const args = ["workflow reset"];
    if (reset.path) args.push(reset.path);
    if (reset.all) args.push("--all");
    if (reset.cascade) args.push("--cascade");
    if (reset.includeProxies) args.push("--include-proxies");
    if (reset.deleteStorage) args.push("--delete-storage");
    args.push("--namespace", namespace);
    return args.join(" ");
}

function sha256(value: string): string {
    return crypto.createHash("sha256").update(value).digest("hex");
}

function validateWorkflowConfig(config: unknown, caseName: string): void {
    const parsed = OVERALL_MIGRATION_CONFIG.safeParse(config);
    if (parsed.success) return;
    const issues = parsed.error.issues
        .slice(0, 8)
        .map((issue) => {
            const path = issue.path.length > 0 ? issue.path.join(".") : "<root>";
            return `${path}: ${issue.message}`;
        })
        .join("; ");
    const suffix = parsed.error.issues.length > 8
        ? `; ... ${parsed.error.issues.length - 8} more issue(s)`
        : "";
    throw new Error(
        `mutated config for case '${caseName}' failed OVERALL_MIGRATION_CONFIG validation: ${issues}${suffix}`,
    );
}

function summarizeArgoWorkflow(k8sClient: K8sClient): string | null {
    try {
        const workflow = k8sClient.getOne(ARGO_WORKFLOW_RESOURCE, INNER_MIGRATION_WORKFLOW_NAME);
        if (!workflow) return null;
        const status = asRecord(workflow["status"]);
        const phase = typeof status?.["phase"] === "string" ? status["phase"] : "(unknown)";
        const nodes = asRecord(status?.["nodes"]);
        const interestingNodes = nodes
            ? Object.values(nodes)
                  .map((n) => asRecord(n))
                  .filter((n): n is Record<string, unknown> => Boolean(n))
                  .map((n) => ({
                      name: workflowNodeName(n),
                      phase: typeof n["phase"] === "string" ? n["phase"] : undefined,
                      message: truncate(
                          typeof n["message"] === "string" ? n["message"] : undefined,
                          120,
                      ),
                  }))
                  .filter((n) => n.phase && n.phase !== "Succeeded")
                  .slice(0, 6)
            : [];
        if (interestingNodes.length === 0) {
            return `${INNER_MIGRATION_WORKFLOW_NAME} phase=${phase}`;
        }
        return `${INNER_MIGRATION_WORKFLOW_NAME} phase=${phase}; nodes=${interestingNodes
            .map((n) => `${n.name ?? "(unnamed)"}:${n.phase}${n.message ? ` (${n.message})` : ""}`)
            .join(", ")}`;
    } catch {
        return null;
    }
}

function readArgoWorkflowObservation(k8sClient: K8sClient): ArgoWorkflowObservation | undefined {
    try {
        const workflow = k8sClient.getOne(ARGO_WORKFLOW_RESOURCE, INNER_MIGRATION_WORKFLOW_NAME);
        if (!workflow) return undefined;
        return extractArgoWorkflowObservation(workflow);
    } catch {
        // Argo workflow evidence is diagnostic. Missing or malformed
        // workflow data must not mask the CRD observation that drives
        // pass/fail assertions.
        return undefined;
    }
}

interface WaitForInnerWorkflowCompletionOptions {
    k8sClient: K8sClient;
    timeoutSeconds: number;
    missingGraceSeconds: number;
    pollIntervalMs?: number;
    clock: typeof realClock;
}

type InnerWorkflowCompletionOutcome =
    | {
          kind: "ready";
          waitedMs: number;
          phase: string;
          message?: string;
      }
    | {
          kind: "missing-timeout";
          waitedMs: number;
      }
    | {
          kind: "timeout";
          waitedMs: number;
          phase: string;
      };

async function waitForInnerWorkflowCompletion(
    opts: WaitForInnerWorkflowCompletionOptions,
): Promise<InnerWorkflowCompletionOutcome> {
    const pollMs = opts.pollIntervalMs ?? 2000;
    const start = opts.clock.now();
    const deadlineMs = start + opts.timeoutSeconds * 1000;
    const missingDeadlineMs = start + opts.missingGraceSeconds * 1000;
    let lastPhase = "(missing)";
    let observedWorkflow = false;

    while (true) {
        const workflow = readArgoWorkflowObservation(opts.k8sClient);
        lastPhase = workflow?.phase ?? "(missing)";
        if (workflow?.phase) {
            observedWorkflow = true;
        }
        if (workflow?.phase && ARGO_WORKFLOW_TERMINAL_PHASES.has(workflow.phase)) {
            return {
                kind: "ready",
                waitedMs: opts.clock.now() - start,
                phase: workflow.phase,
                message: workflow.message,
            };
        }
        if (!observedWorkflow && opts.clock.now() >= missingDeadlineMs) {
            return {
                kind: "missing-timeout",
                waitedMs: opts.clock.now() - start,
            };
        }
        if (opts.clock.now() >= deadlineMs) {
            return {
                kind: "timeout",
                waitedMs: opts.clock.now() - start,
                phase: lastPhase,
            };
        }
        await opts.clock.sleep(pollMs);
    }
}

function shouldWaitForInnerWorkflow(checkpoint: Checkpoint): boolean {
    return !new Set<Checkpoint>([
        "before-approval",
        "on-blocked",
        "after-approve-without-reset",
    ]).has(checkpoint);
}

function extractArgoWorkflowObservation(workflow: Record<string, unknown>): ArgoWorkflowObservation {
    const metadata = asRecord(workflow["metadata"]);
    const status = asRecord(workflow["status"]);
    const nodes = asRecord(status?.["nodes"]);
    return {
        name: typeof metadata?.["name"] === "string"
            ? metadata["name"]
            : INNER_MIGRATION_WORKFLOW_NAME,
        phase: stringField(status, "phase"),
        message: truncate(stringField(status, "message"), 200),
        startedAt: stringField(status, "startedAt"),
        finishedAt: stringField(status, "finishedAt"),
        nodes: nodes
            ? Object.entries(nodes).map(([id, node]) => {
                  const n = asRecord(node) ?? {};
                  const templateName = stringField(n, "templateName");
                  const displayName = stringField(n, "displayName");
                  return {
                      id,
                      name: truncate(templateName ?? displayName, 120),
                      displayName: truncate(displayName, 180),
                      templateName: truncate(templateName, 120),
                      phase: stringField(n, "phase"),
                      message: truncate(stringField(n, "message"), 240),
                      startedAt: stringField(n, "startedAt"),
                      finishedAt: stringField(n, "finishedAt"),
                  };
              })
            : [],
    };
}

function asRecord(value: unknown): Record<string, unknown> | null {
    return value && typeof value === "object" && !Array.isArray(value)
        ? (value as Record<string, unknown>)
        : null;
}

function workflowNodeName(node: Record<string, unknown>): string | undefined {
    const templateName =
        typeof node["templateName"] === "string" ? node["templateName"] : undefined;
    const displayName =
        typeof node["displayName"] === "string" ? node["displayName"] : undefined;
    return truncate(templateName ?? displayName, 80);
}

function stringField(obj: Record<string, unknown> | null, key: string): string | undefined {
    const value = obj?.[key];
    return typeof value === "string" ? value : undefined;
}

function truncate(value: string | undefined, max: number): string | undefined {
    if (!value || value.length <= max) return value;
    return `${value.slice(0, Math.max(0, max - 3))}...`;
}

// `require.main === module` equivalent for CommonJS; tsx/ts-node both
// set `main` to this file when invoked directly.
if (require.main === module) {
    // eslint-disable-next-line @typescript-eslint/no-floating-promises
    main();
}
