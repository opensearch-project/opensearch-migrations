/**
 * e2e-run — CLI entrypoint and testable case-plan executor for live
 * orchestration tests.
 *
 * A case plan is an ordered list of workflow submissions. Each step
 * supplies the config to submit and the checkpoint assertion to run
 * after the workflow settles. The executor owns common lifecycle
 * concerns: setup actors, submit/approve/wait,
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
    ApprovalGateCategory,
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
    CaseCoverage,
} from "./reportSchema";
import { CaseReport, writeCaseSnapshot } from "./snapshotStore";
import {
    Checkpoint,
    ComponentId,
    ObservedComponent,
    ScenarioSpec,
    SubjectStateAtMutation,
    Violation,
} from "./types";
import { Actor, ActorContext, ActorRegistry } from "./actors";
import { builtinActors } from "./builtinActors";
import { deriveBehavior } from "./behaviorDerivation";
import { assertNoViolations } from "./assertLogic";
import { ExpandedTestCase, expandCases } from "./matrixExpander";
import { Mutator, MutatorRegistry } from "./fixtures/mutators";
import { builtinMutators } from "./fixtures/builtinMutators";
import {
    PoisonPillMode,
    ResolvedPoisonPill,
    applyPoisonPillSideEffect,
    applyPoisonPillToConfig,
    resolvePoisonPill,
} from "./poisonPills";
import { writeCoverageOverview } from "./coverageOverview";

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
const ARGO_WORKFLOW_STATUS_JSONPATH =
    `{.metadata.name}{"\\n"}{.status.phase}{"\\n"}{.status.message}{"\\n"}` +
    `{.status.startedAt}{"\\n"}{.status.finishedAt}`;
const DEFAULT_INNER_WORKFLOW_MISSING_GRACE_SECONDS = 30;
const COMPLETED_SUBJECT_PHASES: ReadonlySet<string> = new Set([
    "Ready",
    "Completed",
    "Skipped",
    "Deleted",
]);

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
    stateControl?: WorkflowStateControlOperation;
    operations: readonly WorkflowCasePlanOperation[];
}

export type WorkflowCasePlanOperation =
    | WorkflowCheckpointOperation
    | WorkflowApproveOperation
    | WorkflowResetOperation;

export interface WorkflowCheckpointOperation {
    kind: "checkpoint";
    checkpoint: Checkpoint;
    /** Human-facing checkpoint label for reports and progress logs. */
    label?: string;
    subject: ComponentId | null;
    waitMode?: "phase-complete" | "subject-incomplete";
    expectedRerunComponents?: readonly ComponentId[];
    changedPaths?: readonly string[];
    approvalGate?: {
        category: Extract<ApprovalGateCategory, "change" | "retry">;
        pattern: string;
    };
}

export interface WorkflowApproveOperation {
    kind: "approve";
    /** Approval gate name or glob pattern accepted by `workflow approve`. */
    pattern: string;
    /** Defaults to structural step approval. */
    category?: ApprovalGateCategory;
    /** Retry-only: approving before reset is expected to be rejected. */
    allowPrereqFailure?: boolean;
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

export interface WorkflowStateControlOperation {
    poisonPillName: string;
    mode: PoisonPillMode;
}

export interface WorkflowCasePlan {
    caseName: string;
    coverage?: Omit<CaseCoverage, "observedSubjectPhaseBeforeMutation">;
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
                        label: "noop-pre",
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
        coverage: coverageForExpandedCase(deps, expandedCase),
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
                        label: "noop-pre",
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
                        label: "noop-post",
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
 * approval signal is sent into the same workflow execution whose
 * `workflow approve <category> --list` output made the gate actionable.
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
            approvalGate: {
                category: "change",
                pattern: approvalPattern,
            },
        },
    ];

    if (response === "approve") {
        mutatedOperations.push(
            {
                kind: "approve",
                pattern: approvalPattern,
                category: "change",
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
        coverage: coverageForExpandedCase(deps, expandedCase),
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
            approvalGate: approvalPattern
                ? {
                      category: "retry",
                      pattern: approvalPattern,
                  }
                : undefined,
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
                    category: "retry",
                    allowPrereqFailure: true,
                },
                {
                    kind: "checkpoint",
                    checkpoint: "after-approve-without-reset",
                    subject: expandedCase.subject,
                    expectedRerunComponents: expandedCase.expectedRerunComponents,
                    changedPaths: expandedCase.changedPaths,
                    approvalGate: {
                        category: "retry",
                        pattern: approvalPattern!,
                    },
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
                    category: "change",
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
        coverage: coverageForExpandedCase(deps, expandedCase),
        steps,
    });
}

/**
 * Run a state-controlled case where a poison pill first holds the
 * subject short of completion, then the runner submits the real
 * mutation with the poison restored. This lets coverage distinguish
 * "same field changed while completed" from "same field changed while
 * the subject was still in-progress".
 */
export async function runStateControlledCase(
    deps: LiveRunnerDeps,
    expandedCase: ExpandedTestCase,
): Promise<string> {
    if (expandedCase.subjectStateAtMutation !== "in-progress") {
        throw new Error(
            `runStateControlledCase requires subjectStateAtMutation='in-progress'; got '${expandedCase.subjectStateAtMutation}'`,
        );
    }
    const poisonPillName = requirePoisonPillName(expandedCase);
    const pill = resolvePoisonPill(deps.spec, poisonPillName, expandedCase.subject);
    const baselineYaml = fs.readFileSync(deps.baselineConfigPath, "utf8");
    const baselineConfig = parseYaml(baselineYaml);
    const poisonedBaselineYaml = stringifyYaml(
        applyPoisonPillToConfig(baselineConfig, pill.spec, "poison"),
    );
    const unpoisonedBaselineConfig = applyPoisonPillToConfig(
        baselineConfig,
        pill.spec,
        "restore",
    );
    const mutatedConfig = expandedCase.mutator.apply(unpoisonedBaselineConfig);
    if (deps.validateMutatedConfig) {
        validateWorkflowConfig(mutatedConfig, expandedCase.caseName);
    }
    const mutatedYaml = stringifyYaml(mutatedConfig);

    const steps: WorkflowCasePlanStep[] = [
        {
            runName: "poison-baseline",
            configYaml: poisonedBaselineYaml,
            stateControl: {
                poisonPillName,
                mode: "poison",
            },
            operations: [
                {
                    kind: "checkpoint",
                    checkpoint: "subject-held",
                    subject: expandedCase.subject,
                    waitMode: "subject-incomplete",
                },
            ],
        },
        {
            runName: "mutated",
            configYaml: mutatedYaml,
            stateControl: {
                poisonPillName,
                mode: "restore",
            },
            operations: mutationOperationsForStateControlledCase(expandedCase),
        },
    ];

    if (shouldRunNoopPost(expandedCase)) {
        steps.push(noopStep("noop-post", mutatedYaml));
    }

    return runWorkflowCasePlan(deps, {
        caseName: expandedCase.caseName,
        coverage: coverageForExpandedCase(deps, expandedCase, pill),
        steps,
    });
}

function mutationOperationsForStateControlledCase(
    expandedCase: ExpandedTestCase,
): WorkflowCasePlanOperation[] {
    const common = {
        subject: expandedCase.subject,
        expectedRerunComponents: expandedCase.expectedRerunComponents,
        changedPaths: expandedCase.changedPaths,
    };
    if (expandedCase.mutator.changeClass === "safe") {
        return [
            {
                kind: "checkpoint",
                checkpoint: "mutated-complete",
                ...common,
            },
        ];
    }
    if (expandedCase.mutator.changeClass === "gated") {
        const response = requireResponse(expandedCase, ["approve", "leave-blocked"]);
        const approvalPattern = requireApprovalPattern(expandedCase);
        const operations: WorkflowCasePlanOperation[] = [
            {
                kind: "checkpoint",
                checkpoint: "before-approval",
                approvalGate: {
                    category: "change",
                    pattern: approvalPattern,
                },
                ...common,
            },
        ];
        if (response === "approve") {
            operations.push(
                {
                    kind: "approve",
                    pattern: approvalPattern,
                    category: "change",
                },
                {
                    kind: "checkpoint",
                    checkpoint: "after-approval",
                    ...common,
                },
            );
        }
        return operations;
    }

    const response = requireResponse(expandedCase, [
        "reset-then-approve",
        "approve-only",
        "reset-only",
        "leave-blocked",
    ]);
    const approvalPattern = expandedCase.mutator.approvalPattern;
    const reset = expandedCase.mutator.reset;
    const operations: WorkflowCasePlanOperation[] = [
        {
            kind: "checkpoint",
            checkpoint: "on-blocked",
            approvalGate: approvalPattern
                ? {
                      category: "retry",
                      pattern: approvalPattern,
                  }
                : undefined,
            ...common,
        },
    ];
    if (response === "leave-blocked") return operations;
    if ((response === "approve-only" || response === "reset-then-approve") && !approvalPattern) {
        throw new Error(`case '${expandedCase.caseName}' requires mutator.approvalPattern`);
    }
    if ((response === "reset-only" || response === "reset-then-approve") && !reset) {
        throw new Error(`case '${expandedCase.caseName}' requires mutator.reset metadata`);
    }
    if (response === "approve-only") {
        operations.push(
            {
                kind: "approve",
                pattern: approvalPattern!,
                category: "retry",
                allowPrereqFailure: true,
            },
            {
                kind: "checkpoint",
                checkpoint: "after-approve-without-reset",
                approvalGate: {
                    category: "retry",
                    pattern: approvalPattern!,
                },
                ...common,
            },
        );
    } else if (response === "reset-only") {
        operations.push(
            {
                kind: "reset",
                reset: reset!,
            },
            {
                kind: "checkpoint",
                checkpoint: "after-reset",
                ...common,
            },
        );
    } else {
        operations.push(
            {
                kind: "reset",
                reset: reset!,
            },
            {
                kind: "checkpoint",
                checkpoint: "after-reset",
                ...common,
            },
            {
                kind: "approve",
                pattern: approvalPattern!,
                category: "change",
            },
            {
                kind: "checkpoint",
                checkpoint: "after-approve",
                ...common,
            },
        );
    }
    return operations;
}

function shouldRunNoopPost(expandedCase: ExpandedTestCase): boolean {
    if (expandedCase.mutator.changeClass === "safe") return true;
    return expandedCase.response === "approve" || expandedCase.response === "reset-then-approve";
}

function requirePoisonPillName(expandedCase: ExpandedTestCase): string {
    if (!expandedCase.poisonPillName) {
        throw new Error(
            `case '${expandedCase.caseName}' requires poisonPillName for in-progress subject-state coverage`,
        );
    }
    return expandedCase.poisonPillName;
}

function coverageForExpandedCase(
    deps: Pick<LiveRunnerDeps, "spec">,
    expandedCase: ExpandedTestCase,
    resolvedPoisonPill?: ResolvedPoisonPill,
): Omit<CaseCoverage, "observedSubjectPhaseBeforeMutation"> {
    const pill = resolvedPoisonPill ??
        (expandedCase.poisonPillName
            ? resolvePoisonPill(deps.spec, expandedCase.poisonPillName, expandedCase.subject)
            : undefined);
    const fieldChangeClass = expandedCase.mutator.fieldChangeClass ?? expandedCase.mutator.changeClass;
    return {
        subject: expandedCase.subject,
        subjectStateAtMutation: expandedCase.subjectStateAtMutation,
        fieldChangeClass,
        declaredChangeClass: expandedCase.mutator.changeClass,
        effectiveChangeClass: expandedCase.mutator.changeClass,
        effectiveChangeReason: expandedCase.mutator.effectiveChangeReason,
        dependencyPattern: expandedCase.mutator.dependencyPattern,
        response: expandedCase.response,
        mutatorName: expandedCase.mutator.name,
        changedPaths: [...expandedCase.changedPaths],
        expectedRerunComponents: [...expandedCase.expectedRerunComponents],
        poisonPill: pill
            ? {
                  name: pill.name,
                  strategy: pill.spec.strategy,
                  expectedCollateral: [...pill.spec.expectedCollateral],
              }
            : undefined,
    };
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
                label: runName,
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
    const diagnostics: string[] = [];
    const violations: Violation[] = [];
    const runs: Record<string, RunRecord> = {};
    const events: CaseEvent[] = [];
    const pendingStateControlRestores = new Map<string, ResolvedPoisonPill>();
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
            const workflowName = INNER_MIGRATION_WORKFLOW_NAME;
            progress(deps, `[${caseName}] ${step.runName}: submit ${workflowName}`);
            const diagnosticStart = diagnostics.length;
            const run = await runSubmittedWorkflow({
                deps,
                runName: step.runName,
                workflowName,
                configYaml: step.configYaml,
                stateControl: step.stateControl,
                operations: step.operations,
                priorComponents,
                diagnostics,
                events,
                clock,
                pendingStateControlRestores,
            });
            runs[step.runName] = run;
            violations.push(...collectViolations(run));
            priorComponents = lastComponents(run);
            progress(deps, `[${caseName}] ${step.runName}: complete`);
            const blockingDiagnostics = diagnostics
                .slice(diagnosticStart)
                .filter(isBlockingCheckpointDiagnostic);
            if (blockingDiagnostics.length > 0) {
                outcome = "error";
                const message =
                    `stopping after ${step.runName}; blocking checkpoint failure: ` +
                    blockingDiagnostics.join("; ");
                events.push(event(clock, step.runName, "abort-after-checkpoint-failure", "error", {
                    message,
                }));
                progress(deps, `[${caseName}] ${message}`);
                break;
            }
        }

        if (outcome !== "error") {
            outcome = diagnostics.length === 0 && violations.length === 0 ? "passed" : "partial";
        }
    } catch (e) {
        outcome = "error";
        diagnostics.push(...formatFatalDiagnostics(e));
    } finally {
        restorePendingStateControls({
            deps,
            pendingStateControlRestores,
            diagnostics,
            events,
            clock,
        });
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
        coverage: plan.coverage
            ? enrichCoverageWithObservedSubjectState(plan.coverage, runs)
            : undefined,
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

function enrichCoverageWithObservedSubjectState(
    coverage: Omit<CaseCoverage, "observedSubjectPhaseBeforeMutation">,
    runs: Record<string, RunRecord>,
): CaseCoverage {
    return {
        ...coverage,
        observedSubjectPhaseBeforeMutation: subjectPhaseBeforeMutation(
            coverage.subject,
            runs,
        ),
    };
}

function subjectPhaseBeforeMutation(
    subject: ComponentId,
    runs: Record<string, RunRecord>,
): string | undefined {
    let lastPhase: string | undefined;
    for (const [runName, run] of Object.entries(runs)) {
        if (runName === "mutated") return lastPhase;
        for (const checkpoint of run.checkpoints) {
            const observed = checkpoint.components[subject];
            if (observed?.phase) lastPhase = observed.phase;
        }
    }
    return lastPhase;
}

async function runSubmittedWorkflow(args: {
    deps: LiveRunnerDeps;
    runName: string;
    workflowName: string;
    configYaml: string;
    stateControl: WorkflowStateControlOperation | undefined;
    operations: readonly WorkflowCasePlanOperation[];
    priorComponents: Readonly<Record<ComponentId, ObservedComponent>> | null;
    diagnostics: string[];
    events: CaseEvent[];
    clock: typeof realClock;
    pendingStateControlRestores: Map<string, ResolvedPoisonPill>;
}): Promise<RunRecord> {
    const {
        deps,
        runName,
        workflowName,
        configYaml,
        stateControl,
        operations,
        priorComponents,
        diagnostics,
        events,
        clock,
        pendingStateControlRestores,
    } = args;
    const checkpoints: RunCheckpoint[] = [];
    if (stateControl) {
        progress(
            deps,
            `[${workflowName}] ${stateControl.mode} poison pill ${stateControl.poisonPillName}`,
        );
        const pill = runStateControlOperation({
            deps,
            stateControl,
            runName,
            events,
            clock,
        });
        if (stateControl.mode === "poison") {
            pendingStateControlRestores.set(pill.name, pill);
        } else {
            pendingStateControlRestores.delete(pill.name);
        }
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
    progress(deps, `[${workflowName}] submit`);
    workflowEvent(
        events,
        clock,
        runName,
        "submit",
        `workflow submit --namespace ${deps.namespace}`,
        () => deps.workflowCli.submit({ wait: false }),
    );
    for (const gate of deps.spec.approvalGates) {
        progress(deps, `[${workflowName}] approve structural gate ${gate.approvePattern}`);
        workflowEvent(
            events,
            clock,
            runName,
            "approve",
            workflowApproveCommand("step", gate.approvePattern, deps.namespace),
            () => deps.workflowCli.approveStep(gate.approvePattern),
        );
    }
    for (const operation of operations) {
        if (operation.kind === "checkpoint") {
            const checkpointLabel = operation.label ?? operation.checkpoint;
            progress(deps, `[${workflowName}] checkpoint ${checkpointLabel}`);
            await waitAndCheckpoint(
                deps,
                checkpoints,
                workflowName,
                operation.checkpoint,
                checkpointLabel,
                operation.subject,
                operation.waitMode,
                operation.expectedRerunComponents,
                operation.changedPaths,
                operation.approvalGate,
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
                workflowApproveCommand(operation.category ?? "step", operation.pattern, deps.namespace),
                () => approveWorkflowGate(deps.workflowCli, operation),
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
    return { name: runName, checkpoints };
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

function isBlockingCheckpointDiagnostic(diagnostic: string): boolean {
    return /^(workflow-timeout|workflow-failed|phase-timeout|subject-incomplete-timeout|approval-gate-timeout|approval-gate-workflow-error) at /.test(diagnostic);
}

function isExpectedAdmissionPolicyBlock(
    checkpoint: Checkpoint,
    category: Extract<ApprovalGateCategory, "change" | "retry">,
    message: string,
): boolean {
    return (
        (checkpoint === "on-blocked" ||
            checkpoint === "after-approve-without-reset") &&
        category === "retry" &&
        /ValidatingAdmissionPolicy|is invalid/i.test(message) &&
        /snapshotmigration|upsertsnapshotmigrationresource/i.test(message)
    );
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

function runStateControlOperation(args: {
    deps: LiveRunnerDeps;
    stateControl: WorkflowStateControlOperation;
    runName: string;
    events: CaseEvent[];
    clock: typeof realClock;
}): ResolvedPoisonPill {
    const { deps, stateControl, runName, events, clock } = args;
    const pill = resolvePoisonPill(
        deps.spec,
        stateControl.poisonPillName,
        deps.spec.matrix.subject as ComponentId,
    );
    try {
        applyPoisonPillSideEffect(deps.workflowCli, pill, stateControl.mode);
        events.push(event(clock, runName, "state-control", "ok", {
            message:
                `${stateControl.mode} poisonPill '${pill.name}' ` +
                `(${pill.spec.strategy}) for ${pill.spec.subject}`,
        }));
        return pill;
    } catch (e) {
        const err = e as Partial<WorkflowCliError> & Partial<Error>;
        events.push(event(clock, runName, "state-control", "error", {
            message:
                `${stateControl.mode} poisonPill '${pill.name}' failed: ` +
                (err.message ?? String(e)),
            stdout: err.stdout || undefined,
            stderr: err.stderr || undefined,
        }));
        throw e;
    }
}

function restorePendingStateControls(args: {
    deps: LiveRunnerDeps;
    pendingStateControlRestores: Map<string, ResolvedPoisonPill>;
    diagnostics: string[];
    events: CaseEvent[];
    clock: typeof realClock;
}): void {
    const {
        deps,
        pendingStateControlRestores,
        diagnostics,
        events,
        clock,
    } = args;
    for (const pill of pendingStateControlRestores.values()) {
        try {
            applyPoisonPillSideEffect(deps.workflowCli, pill, "restore");
            events.push(event(clock, "teardown", "state-control-restore", "ok", {
                message: `restored poisonPill '${pill.name}' (${pill.spec.strategy})`,
            }));
        } catch (e) {
            const err = e as Partial<WorkflowCliError> & Partial<Error>;
            const diagnostic =
                `state-control restore for poisonPill '${pill.name}' failed: ` +
                (err.message ?? String(e));
            diagnostics.push(diagnostic);
            events.push(event(clock, "teardown", "state-control-restore", "error", {
                message: diagnostic,
                stdout: err.stdout || undefined,
                stderr: err.stderr || undefined,
            }));
        }
    }
    pendingStateControlRestores.clear();
}

async function waitAndCheckpoint(
    deps: LiveRunnerDeps,
    into: RunCheckpoint[],
    workflowName: string,
    checkpoint: Checkpoint,
    checkpointLabel: string,
    subject: ComponentId | null,
    waitMode: WorkflowCheckpointOperation["waitMode"] | undefined,
    expectedRerunComponents: readonly ComponentId[] | undefined,
    changedPaths: readonly string[] | undefined,
    approvalGate: WorkflowCheckpointOperation["approvalGate"] | undefined,
    priorComponents: Readonly<Record<ComponentId, ObservedComponent>> | null,
    diagnostics: string[],
    events: CaseEvent[],
    clock: typeof realClock,
): Promise<void> {
    if (waitMode === "subject-incomplete") {
        await waitAndCheckpointSubjectIncomplete({
            deps,
            into,
            workflowName,
            checkpoint,
            checkpointLabel,
            subject,
            priorComponents,
            diagnostics,
            events,
            clock,
        });
        return;
    }

    events.push(event(clock, checkpointLabel, "wait-phase-completion", "ok", {
        message: `waiting up to ${deps.spec.phaseCompletionTimeoutSeconds}s`,
    }));
    let workflowWaitTimedOut = false;
    if (deps.waitForInnerWorkflowCompletion && shouldWaitForInnerWorkflow(checkpoint)) {
        const missingGraceSeconds = Math.min(
            deps.innerWorkflowMissingGraceSeconds ?? DEFAULT_INNER_WORKFLOW_MISSING_GRACE_SECONDS,
            deps.spec.phaseCompletionTimeoutSeconds,
        );
        events.push(event(clock, checkpointLabel, "wait-workflow-completion", "ok", {
            message:
                `waiting for ${INNER_MIGRATION_WORKFLOW_NAME} to appear up to ${missingGraceSeconds}s ` +
                `and complete up to ${deps.spec.phaseCompletionTimeoutSeconds}s`,
        }));
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpointLabel}: wait for ${INNER_MIGRATION_WORKFLOW_NAME} up to ${deps.spec.phaseCompletionTimeoutSeconds}s`,
        );
        const workflowOutcome = await waitForInnerWorkflowCompletion({
            k8sClient: deps.k8sClient,
            timeoutSeconds: deps.spec.phaseCompletionTimeoutSeconds,
            missingGraceSeconds,
            clock,
        });
        if (workflowOutcome.kind === "missing-timeout") {
            events.push(event(clock, checkpointLabel, "wait-workflow-completion", "error", {
                message:
                    `${INNER_MIGRATION_WORKFLOW_NAME} was not observed after ${workflowOutcome.waitedMs}ms; ` +
                    "continuing with CRD phase completion",
            }));
            diagnostics.push(
                `workflow-missing at ${checkpointLabel}: ${INNER_MIGRATION_WORKFLOW_NAME} was not observed within ${workflowOutcome.waitedMs}ms`,
            );
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpointLabel}: ${INNER_MIGRATION_WORKFLOW_NAME} missing after ${workflowOutcome.waitedMs}ms; continuing with CRD phases`,
            );
        } else if (workflowOutcome.kind === "timeout") {
            workflowWaitTimedOut = true;
            events.push(event(clock, checkpointLabel, "wait-workflow-completion", "error", {
                message: `${INNER_MIGRATION_WORKFLOW_NAME} did not complete after ${workflowOutcome.waitedMs}ms; last phase=${workflowOutcome.phase}`,
            }));
            diagnostics.push(
                `workflow-timeout at ${checkpointLabel}: ${INNER_MIGRATION_WORKFLOW_NAME}=${workflowOutcome.phase}`,
            );
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpointLabel}: ${INNER_MIGRATION_WORKFLOW_NAME} still ${workflowOutcome.phase} after ${workflowOutcome.waitedMs}ms`,
            );
        } else {
            const result = ARGO_WORKFLOW_FAILURE_PHASES.has(workflowOutcome.phase)
                ? "error"
                : "ok";
            events.push(event(clock, checkpointLabel, "wait-workflow-completion", result, {
                message: `${INNER_MIGRATION_WORKFLOW_NAME} ${workflowOutcome.phase} after ${workflowOutcome.waitedMs}ms`,
            }));
            if (ARGO_WORKFLOW_FAILURE_PHASES.has(workflowOutcome.phase)) {
                diagnostics.push(
                    `workflow-failed at ${checkpointLabel}: ${INNER_MIGRATION_WORKFLOW_NAME}=${workflowOutcome.phase}` +
                        (workflowOutcome.message ? ` (${workflowOutcome.message})` : ""),
                );
            }
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpointLabel}: ${INNER_MIGRATION_WORKFLOW_NAME} ${workflowOutcome.phase} after ${workflowOutcome.waitedMs}ms`,
            );
        }
    }
    let approvalGateActionable = false;
    let admissionPolicyBlocked = false;
    let approvalGateWaitFailed = false;
    if (approvalGate) {
        events.push(event(clock, checkpointLabel, "wait-approval-gate", "ok", {
            command: workflowApproveListCommand(approvalGate.category, deps.namespace),
            message:
                `waiting for actionable ${approvalGate.category} gate ` +
                `${approvalGate.pattern} from workflow approve --list`,
        }));
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpointLabel}: wait for actionable ${approvalGate.category} gate ${approvalGate.pattern}`,
        );
        const gateOutcome = await waitForApprovalGate({
            workflowCli: deps.workflowCli,
            k8sClient: deps.k8sClient,
            category: approvalGate.category,
            pattern: approvalGate.pattern,
            timeoutSeconds: deps.spec.phaseCompletionTimeoutSeconds,
            clock,
        });
        if (gateOutcome.kind === "ready") {
            approvalGateActionable = true;
            events.push(event(clock, checkpointLabel, "wait-approval-gate", "ok", {
                command: workflowApproveListCommand(approvalGate.category, deps.namespace),
                message:
                    `${approvalGate.category} gate ${approvalGate.pattern} ` +
                    `actionable after ${gateOutcome.waitedMs}ms`,
                stdout: gateOutcome.stdout || undefined,
            }));
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpointLabel}: ${approvalGate.category} gate actionable after ${gateOutcome.waitedMs}ms`,
            );
        } else if (gateOutcome.kind === "workflow-error") {
            if (
                isExpectedAdmissionPolicyBlock(
                    checkpoint,
                    approvalGate.category,
                    gateOutcome.message,
                )
            ) {
                admissionPolicyBlocked = true;
                events.push(event(clock, checkpointLabel, "wait-approval-gate", "ok", {
                    command: workflowApproveListCommand(approvalGate.category, deps.namespace),
                    message:
                        "mutation was rejected by admission policy before a retry gate became actionable: " +
                        gateOutcome.message,
                    stdout: gateOutcome.stdout || undefined,
                    stderr: gateOutcome.stderr || undefined,
                }));
                progress(
                    deps,
                    `[${workflowName}] checkpoint ${checkpointLabel}: mutation rejected by admission policy after ${gateOutcome.waitedMs}ms`,
                );
            } else {
                approvalGateWaitFailed = true;
                events.push(event(clock, checkpointLabel, "wait-approval-gate", "error", {
                    command: workflowApproveListCommand(approvalGate.category, deps.namespace),
                    message: gateOutcome.message,
                    stdout: gateOutcome.stdout || undefined,
                    stderr: gateOutcome.stderr || undefined,
                }));
                diagnostics.push(
                    `approval-gate-workflow-error at ${checkpointLabel}: ${gateOutcome.message}`,
                );
                progress(
                    deps,
                    `[${workflowName}] checkpoint ${checkpointLabel}: ${approvalGate.category} gate blocked by workflow error after ${gateOutcome.waitedMs}ms`,
                );
            }
        } else {
            approvalGateWaitFailed = true;
            events.push(event(clock, checkpointLabel, "wait-approval-gate", "error", {
                command: workflowApproveListCommand(approvalGate.category, deps.namespace),
                message: `${approvalGate.category} gate ${approvalGate.pattern} was not actionable after ${gateOutcome.waitedMs}ms`,
                stdout: gateOutcome.stdout || undefined,
                stderr: gateOutcome.stderr || undefined,
            }));
            diagnostics.push(
                `approval-gate-timeout at ${checkpointLabel}: ${approvalGate.category} gate ${approvalGate.pattern} was not actionable after ${gateOutcome.waitedMs}ms`,
            );
            progress(
                deps,
                `[${workflowName}] checkpoint ${checkpointLabel}: ${approvalGate.category} gate not actionable after ${gateOutcome.waitedMs}ms`,
            );
        }
    }
    progress(
        deps,
        `[${workflowName}] checkpoint ${checkpointLabel}: wait for ${deps.topology.components.length} component phase(s)`,
    );
    const outcome: PhaseCompletionOutcome = await waitForPhaseCompletion({
        components: deps.topology.components,
        timeoutSeconds: workflowWaitTimedOut || approvalGateWaitFailed
            ? 1
            : deps.spec.phaseCompletionTimeoutSeconds,
        readObservations: async () => {
            const o = await deps.readObservations();
            const observations = Object.values(o.components).map((c) => ({
                componentId: c.componentId,
                // If the workflow CLI says the subject's gate is
                // actionable, the checkpoint has been reached even
                // though the subject CRD may still be Pending/Created.
                // For impossible changes, admission-policy rejection is
                // also a valid blocked checkpoint. In both cases, do
                // not wait for the subject to complete before capturing
                // the checkpoint.
                phase:
                    (approvalGateActionable || admissionPolicyBlocked) &&
                    subject === c.componentId
                        ? "Blocked"
                        : c.phase,
            }));
            if (
                checkpoint === "after-reset" &&
                subject &&
                !o.components[subject]
            ) {
                observations.push({ componentId: subject, phase: "Deleted" });
            }
            return observations;
        },
        clock,
    });

    // Re-read final state after wait so the snapshot has the settled
    // phases.
    const finalObs = await deps.readObservations();
    if (checkpoint === "after-reset" && subject && !finalObs.components[subject]) {
        finalObs.components[subject] = {
            componentId: subject,
            phase: "Deleted",
        };
    }
    if ((approvalGateActionable || admissionPolicyBlocked) && subject) {
        const subjectObs = finalObs.components[subject];
        if (subjectObs) {
            finalObs.components[subject] = {
                ...subjectObs,
                ...(approvalGateActionable
                    ? {
                          approvalGateActionable: true,
                          approvalGateCategory: approvalGate!.category,
                      }
                    : { admissionPolicyBlocked: true }),
            };
        }
    }

    if (outcome.kind === "timeout") {
        const workflowSummary = summarizeArgoWorkflow(deps.k8sClient);
        events.push(event(clock, checkpointLabel, "wait-phase-completion", "error", {
            message: outcome.blockingComponents
                .map((b) => `${b.componentId}=${b.phase}`)
                .join(", ") + (workflowSummary ? `; ${workflowSummary}` : ""),
        }));
        diagnostics.push(
            `phase-timeout at ${checkpointLabel}: ${outcome.blockingComponents
                .map((b) => `${b.componentId}=${b.phase}`)
                .join(", ")}`,
        );
        if (workflowSummary) {
            diagnostics.push(`argo: ${workflowSummary}`);
        }
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpointLabel}: component phase wait timed out after ${outcome.waitedMs}ms`,
        );
    } else {
        events.push(event(clock, checkpointLabel, "wait-phase-completion", "ok", {
            message: `ready after ${outcome.waitedMs}ms`,
        }));
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpointLabel}: component phases ready after ${outcome.waitedMs}ms`,
        );
    }
    const argoWorkflow = readArgoWorkflowObservation(deps.k8sClient);
    events.push(event(clock, checkpointLabel, "observe", "ok", {
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
        label: checkpointLabel,
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

async function waitAndCheckpointSubjectIncomplete(args: {
    deps: LiveRunnerDeps;
    into: RunCheckpoint[];
    workflowName: string;
    checkpoint: Checkpoint;
    checkpointLabel: string;
    subject: ComponentId | null;
    priorComponents: Readonly<Record<ComponentId, ObservedComponent>> | null;
    diagnostics: string[];
    events: CaseEvent[];
    clock: typeof realClock;
}): Promise<void> {
    const {
        deps,
        into,
        workflowName,
        checkpoint,
        checkpointLabel,
        subject,
        priorComponents,
        diagnostics,
        events,
        clock,
    } = args;
    if (!subject) {
        throw new Error(`${checkpoint} checkpoint with waitMode=subject-incomplete requires subject`);
    }
    events.push(event(clock, checkpointLabel, "wait-subject-incomplete", "ok", {
        message: `waiting up to ${deps.spec.phaseCompletionTimeoutSeconds}s for ${subject} to exist without reaching completion`,
    }));
    progress(
        deps,
        `[${workflowName}] checkpoint ${checkpointLabel}: wait for ${subject} to remain incomplete`,
    );
    const outcome = await waitForSubjectIncomplete({
        readObservations: deps.readObservations,
        subject,
        timeoutSeconds: deps.spec.phaseCompletionTimeoutSeconds,
        clock,
    });

    if (outcome.kind === "timeout") {
        events.push(event(clock, checkpointLabel, "wait-subject-incomplete", "error", {
            message: `${subject} did not reach an incomplete observable state after ${outcome.waitedMs}ms; last phase=${outcome.lastPhase ?? "(missing)"}`,
        }));
        diagnostics.push(
            `subject-incomplete-timeout at ${checkpointLabel}: ${subject}=${outcome.lastPhase ?? "(missing)"}`,
        );
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpointLabel}: ${subject} did not become incomplete after ${outcome.waitedMs}ms`,
        );
    } else {
        events.push(event(clock, checkpointLabel, "wait-subject-incomplete", "ok", {
            message: `${subject}=${outcome.phase} after ${outcome.waitedMs}ms`,
        }));
        progress(
            deps,
            `[${workflowName}] checkpoint ${checkpointLabel}: ${subject}=${outcome.phase} after ${outcome.waitedMs}ms`,
        );
    }

    const finalObs = await deps.readObservations();
    const argoWorkflow = readArgoWorkflowObservation(deps.k8sClient);
    events.push(event(clock, checkpointLabel, "observe", "ok", {
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
        label: checkpointLabel,
        observedAt: new Date(clock.now()).toISOString(),
        argoWorkflow,
        components: withBehavior,
        violations: assertNoViolations({
            checkpoint,
            subject,
            topology: deps.topology,
            observations: withBehavior,
        }),
    });
}

type SubjectIncompleteOutcome =
    | {
          kind: "ready";
          waitedMs: number;
          phase: string;
      }
    | {
          kind: "timeout";
          waitedMs: number;
          lastPhase?: string;
      };

async function waitForSubjectIncomplete(opts: {
    readObservations: ReadClusterObservations;
    subject: ComponentId;
    timeoutSeconds: number;
    pollIntervalMs?: number;
    clock: typeof realClock;
}): Promise<SubjectIncompleteOutcome> {
    const pollMs = opts.pollIntervalMs ?? 2000;
    const start = opts.clock.now();
    const deadlineMs = start + opts.timeoutSeconds * 1000;
    let lastPhase: string | undefined;

    while (true) {
        const observations = await opts.readObservations();
        const subjectObs = observations.components[opts.subject];
        lastPhase = subjectObs?.phase;
        if (subjectObs && !COMPLETED_SUBJECT_PHASES.has(subjectObs.phase)) {
            return {
                kind: "ready",
                waitedMs: opts.clock.now() - start,
                phase: subjectObs.phase,
            };
        }
        if (opts.clock.now() >= deadlineMs) {
            return {
                kind: "timeout",
                waitedMs: opts.clock.now() - start,
                lastPhase,
            };
        }
        await opts.clock.sleep(pollMs);
    }
}

type ApprovalGateWaitOutcome =
    | {
          kind: "ready";
          waitedMs: number;
          stdout: string;
      }
    | {
          kind: "workflow-error";
          waitedMs: number;
          message: string;
          stdout: string;
          stderr: string;
      }
    | {
          kind: "timeout";
          waitedMs: number;
          stdout: string;
          stderr: string;
      };

async function waitForApprovalGate(opts: {
    workflowCli: WorkflowCli;
    k8sClient?: K8sClient;
    category: Extract<ApprovalGateCategory, "change" | "retry">;
    pattern: string;
    timeoutSeconds: number;
    pollIntervalMs?: number;
    workflowFailureGraceMs?: number;
    clock: typeof realClock;
}): Promise<ApprovalGateWaitOutcome> {
    const pollMs = opts.pollIntervalMs ?? 2000;
    const workflowFailureGraceMs = opts.workflowFailureGraceMs ?? 10000;
    const start = opts.clock.now();
    const deadlineMs = start + opts.timeoutSeconds * 1000;
    let lastStdout = "";
    let lastStderr = "";
    let firstWorkflowFailureAt: number | null = null;
    let lastWorkflowFailure: string | null = null;

    while (true) {
        try {
            const result = opts.workflowCli.listApprovalGates(opts.category);
            lastStdout = result.stdout;
            lastStderr = result.stderr;
            if (approvalGateListContains(result.stdout, opts.pattern)) {
                return {
                    kind: "ready",
                    waitedMs: opts.clock.now() - start,
                    stdout: result.stdout,
                };
            }
        } catch (e) {
            const err = e as Partial<WorkflowCliError>;
            lastStdout = err.stdout ?? "";
            lastStderr = err.stderr ?? err.message ?? "";
        }

        const workflowFailure = opts.k8sClient
            ? summarizeFailedArgoNodes(opts.k8sClient)
            : null;
        if (workflowFailure) {
            lastWorkflowFailure = workflowFailure;
            firstWorkflowFailureAt ??= opts.clock.now();
            if (opts.clock.now() - firstWorkflowFailureAt >= workflowFailureGraceMs) {
                return {
                    kind: "workflow-error",
                    waitedMs: opts.clock.now() - start,
                    message: workflowFailure,
                    stdout: lastStdout,
                    stderr: lastStderr,
                };
            }
        } else {
            firstWorkflowFailureAt = null;
            lastWorkflowFailure = null;
        }

        if (opts.clock.now() >= deadlineMs) {
            return {
                kind: "timeout",
                waitedMs: opts.clock.now() - start,
                stdout: lastStdout,
                stderr: lastWorkflowFailure
                    ? `${lastStderr}\n${lastWorkflowFailure}`.trim()
                    : lastStderr,
            };
        }
        await opts.clock.sleep(pollMs);
    }
}

function approvalGateListContains(stdout: string, pattern: string): boolean {
    const stripped = stripAnsi(stdout);
    const variants = approvalGatePatternVariants(pattern);
    return stripped
        .split(/\r?\n/)
        .some((line) => variants.some((v) => line.includes(v)));
}

function approvalGatePatternVariants(pattern: string): string[] {
    const out = new Set<string>([pattern]);
    if (pattern.endsWith(".vapretry")) {
        out.add(pattern.slice(0, -".vapretry".length));
    } else {
        out.add(`${pattern}.vapretry`);
    }
    return [...out].filter((v) => v.length > 0);
}

function stripAnsi(s: string): string {
    return s.replace(/\x1B\[[0-?]*[ -/]*[@-~]/g, "");
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
     * Optional exact expanded-case name to run. This is a developer
     * convenience for live validation; it intentionally selects after
     * matrix expansion so the same spec remains the source of truth.
     */
    caseName?: string;
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

    if (opts.noopOnly && opts.caseName) {
        throw new Error("--case cannot be combined with --noop-only");
    }

    // Noop-only mode is useful for bring-up and for debugging
    // phase-completion issues before touching mutation flow.
    if (opts.noopOnly) {
        return [await runNoopCase(sharedDeps)];
    }

    return runExpandedCases(sharedDeps, mutatorRegistry, {
        caseName: opts.caseName,
    });
}

export interface RunExpandedCasesOptions {
    /** Optional exact expanded-case name to run. */
    caseName?: string;
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
    opts: RunExpandedCasesOptions = {},
): Promise<string[]> {
    const cases = selectExpandedCases(
        expandCases(deps.spec, mutatorRegistry),
        opts.caseName,
    );
    const out: string[] = [];
    for (const expanded of cases) {
        out.push(await runExpandedCase(deps, expanded));
    }
    return out;
}

function selectExpandedCases(
    cases: ExpandedTestCase[],
    caseName: string | undefined,
): ExpandedTestCase[] {
    if (!caseName) return cases;
    const selected = cases.filter((c) => c.caseName === caseName);
    if (selected.length > 0) return selected;
    const available = cases.map((c) => c.caseName).sort().join("\n  ");
    throw new Error(
        `case '${caseName}' did not match any expanded case. Available cases:\n  ${available}`,
    );
}

export async function runExpandedCase(
    deps: LiveRunnerDeps,
    expanded: ExpandedTestCase,
): Promise<string> {
    if (expanded.subjectStateAtMutation === "in-progress") {
        return runStateControlledCase(deps, expanded);
    }
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
            "Usage: e2e-run <spec-path> [--namespace ma] [--output-dir ./snapshots] [--local] [--noop-only] [--case <expanded-case-name>] [--phase-timeout-seconds 600]",
        );
        process.exit(2);
    }
    const specPath = args[0];
    const namespace = takeFlag(args, "--namespace") ?? "ma";
    const outputDir = takeFlag(args, "--output-dir") ?? path.resolve("snapshots");
    const cliMode = args.includes("--local") ? "local" : "kubectl-exec";
    const noopOnly = args.includes("--noop-only");
    const caseName = takeFlag(args, "--case");

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
            caseName,
            phaseCompletionTimeoutSeconds,
            progress: (message) => console.error(message),
        });
        const summary = summariseSnapshots(out);
        const coverage = writeCoverageOverview(out, outputDir);
        for (const c of summary.cases) {
            const suffix =
                c.violationCount > 0
                    ? ` — ${c.violationCount} violation(s)`
                    : "";
            console.log(
                `Snapshot written to ${c.path} [${c.outcome}]${suffix}`,
            );
        }
        console.log(`Coverage overview written to ${coverage.jsonPath}`);
        console.log(`Coverage overview written to ${coverage.markdownPath}`);
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

function approveWorkflowGate(
    workflowCli: WorkflowCli,
    operation: WorkflowApproveOperation,
): WorkflowCliRunResult {
    switch (operation.category ?? "step") {
        case "step":
            return workflowCli.approveStep(operation.pattern);
        case "change":
            return workflowCli.approveChange(operation.pattern);
        case "retry":
            return workflowCli.approveRetry(operation.pattern, {
                allowPrereqFailure: operation.allowPrereqFailure,
            });
    }
}

function workflowApproveCommand(
    category: ApprovalGateCategory,
    pattern: string,
    namespace: string,
): string {
    return `workflow approve ${category} ${pattern} --namespace ${namespace}`;
}

function workflowApproveListCommand(
    category: ApprovalGateCategory,
    namespace: string,
): string {
    return `workflow approve ${category} --list --namespace ${namespace}`;
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

function summarizeFailedArgoNodes(k8sClient: K8sClient): string | null {
    try {
        const workflow = k8sClient.getOne(ARGO_WORKFLOW_RESOURCE, INNER_MIGRATION_WORKFLOW_NAME);
        if (!workflow) return null;
        const status = asRecord(workflow["status"]);
        const phase = typeof status?.["phase"] === "string" ? status["phase"] : "(unknown)";
        const nodes = asRecord(status?.["nodes"]);
        const failedNodes = nodes
            ? Object.values(nodes)
                  .map((n) => asRecord(n))
                  .filter((n): n is Record<string, unknown> => Boolean(n))
                  .map((n) => ({
                      name: workflowNodeName(n),
                      phase: typeof n["phase"] === "string" ? n["phase"] : undefined,
                      message: truncate(
                          typeof n["message"] === "string" ? n["message"] : undefined,
                          180,
                      ),
                  }))
                  .filter((n) => n.phase && ARGO_WORKFLOW_FAILURE_PHASES.has(n.phase))
                  .slice(0, 4)
            : [];
        if (failedNodes.length === 0) return null;
        return `${INNER_MIGRATION_WORKFLOW_NAME} phase=${phase}; failed nodes=${failedNodes
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
        // Full Argo workflow JSON can get large enough to exceed the default
        // child_process stdout buffer. Keep checkpoint evidence useful by
        // falling back to the lightweight status projection used by the wait
        // loop.
        return readArgoWorkflowStatus(k8sClient);
    }
}

function readArgoWorkflowStatus(k8sClient: K8sClient): ArgoWorkflowObservation | undefined {
    try {
        const raw = k8sClient.getJsonPath(
            ARGO_WORKFLOW_RESOURCE,
            INNER_MIGRATION_WORKFLOW_NAME,
            ARGO_WORKFLOW_STATUS_JSONPATH,
        );
        if (raw === null) return undefined;
        const [name, phase, message, startedAt, finishedAt] = raw.split("\n");
        if (!name && !phase) return undefined;
        return {
            name: name || INNER_MIGRATION_WORKFLOW_NAME,
            phase: phase || undefined,
            message: message || undefined,
            startedAt: startedAt || undefined,
            finishedAt: finishedAt || undefined,
            nodes: [],
        };
    } catch {
        // Workflow status is diagnostic. Missing or malformed workflow data
        // must not mask the CRD observation that drives pass/fail assertions.
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
        const workflow = readArgoWorkflowStatus(opts.k8sClient);
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
        "after-reset",
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
