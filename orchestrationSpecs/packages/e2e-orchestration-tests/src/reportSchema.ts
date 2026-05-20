/**
 * Snapshot schema. Snapshots are the diagnostic JSON artefacts written
 * per test case to `snapshots/<case-name>.json`. Pass/fail comes from
 * `assertLogic`, not from the snapshot itself — this schema exists to
 * make the on-disk format explicit and parseable for debugging tools.
 */

import { z } from "zod";

import {
    CheckpointSchema,
    ComponentIdSchema,
    ObservedComponent,
    ObservedComponentSchema,
    ViolationSchema,
} from "./types";

/**
 * Compact Argo workflow node evidence captured at a checkpoint. This is
 * diagnostic evidence, not an oracle: assertions still come from CRD
 * observations plus assertLogic. Keeping node evidence in the snapshot
 * makes SUT defects visible when CRD checksums and workflow execution
 * disagree.
 */
export const ArgoWorkflowNodeSchema = z
    .object({
        id: z.string(),
        name: z.string().optional(),
        displayName: z.string().optional(),
        templateName: z.string().optional(),
        phase: z.string().optional(),
        message: z.string().optional(),
        startedAt: z.string().optional(),
        finishedAt: z.string().optional(),
    })
    .strict();
export type ArgoWorkflowNode = z.infer<typeof ArgoWorkflowNodeSchema>;

export const ArgoWorkflowObservationSchema = z
    .object({
        name: z.string(),
        phase: z.string().optional(),
        message: z.string().optional(),
        startedAt: z.string().optional(),
        finishedAt: z.string().optional(),
        nodes: z.array(ArgoWorkflowNodeSchema).default([]),
    })
    .strict();
export type ArgoWorkflowObservation = z.infer<typeof ArgoWorkflowObservationSchema>;

/**
 * A checker result captured at a specific observation key (base name
 * plus lifecycle phase, matching the ObservationBag key format).
 */
export const CheckerVerdictSchema = z
    .object({
        name: z.string(),
        /** `observerName@phase` style location — mirrors the state bag. */
        at: z.string(),
        pass: z.boolean(),
        observations: z.record(z.string(), z.unknown()).default({}),
        message: z.string().optional(),
    })
    .strict();
export type CheckerVerdict = z.infer<typeof CheckerVerdictSchema>;

/**
 * A single checkpoint observation from a run: the observed components
 * at that point, plus any violations flagged by the assertion walk.
 *
 * The components map is keyed by ComponentId, and a refinement checks
 * that each component's stored `componentId` matches its key — this
 * catches programmer errors where the map key and the embedded id drift
 * apart (e.g. a typo in one but not the other).
 */
export const RunCheckpointSchema = z
    .object({
        checkpoint: CheckpointSchema,
        /** Wall-clock time when this checkpoint was captured. */
        observedAt: z.string(),
        /** Diagnostic-only snapshot of the inner Argo workflow. */
        argoWorkflow: ArgoWorkflowObservationSchema.optional(),
        components: z.record(ComponentIdSchema, ObservedComponentSchema),
        violations: z.array(ViolationSchema).default([]),
    })
    .strict()
    .superRefine((rc, ctx) => {
        const entries = Object.entries(rc.components) as [string, ObservedComponent][];
        for (const [key, value] of entries) {
            if (value.componentId !== key) {
                ctx.addIssue({
                    code: "custom",
                    message: `components[${key}].componentId is '${value.componentId}', does not match map key`,
                    path: ["components", key, "componentId"],
                });
            }
        }
    });
export type RunCheckpoint = z.infer<typeof RunCheckpointSchema>;

/**
 * A run is one of the four submissions in a test case (baseline,
 * noop-pre, the mutated submission, noop-post). Each run has one or
 * more checkpoints.
 */
export const RunRecordSchema = z
    .object({
        name: z.string(),
        /**
         * Checkpoints captured during this run, in the order they were
         * observed.
         */
        checkpoints: z.array(RunCheckpointSchema).default([]),
    })
    .strict();
export type RunRecord = z.infer<typeof RunRecordSchema>;

/**
 * Ordered diagnostic event log for the case. This is intentionally
 * operational rather than an oracle: it records what the runner tried
 * to do and whether the command/action succeeded, so failed snapshots
 * remain readable even when no checkpoint was reached.
 */
export const CaseEventSchema = z
    .object({
        at: z.string(),
        phase: z.string(),
        action: z.string(),
        result: z.enum(["ok", "error"]),
        command: z.string().optional(),
        message: z.string().optional(),
        configSha256: z.string().optional(),
        configBytes: z.number().int().nonnegative().optional(),
        stdout: z.string().optional(),
        stderr: z.string().optional(),
    })
    .strict();
export type CaseEvent = z.infer<typeof CaseEventSchema>;

/**
 * Case-level outcome. 'partial' means the runner reached the case but
 * could not exercise the full behavior (e.g. missing implementation,
 * configured to stop early).
 */
export const CaseOutcomeSchema = z.enum(["passed", "failed", "partial", "error"]);
export type CaseOutcome = z.infer<typeof CaseOutcomeSchema>;

/**
 * The on-disk snapshot format. One file per expanded test case.
 */
export const CaseSnapshotSchema = z
    .object({
        /** Stable case identifier; also the filename stem. */
        case: z.string(),
        /** Spec path as given to the runner (for traceability). */
        specPath: z.string(),
        /** Overall case outcome. */
        outcome: CaseOutcomeSchema,
        /** ISO-8601 timestamps of case boundaries. */
        startedAt: z.string(),
        finishedAt: z.string().optional(),
        /** Runs keyed by run name (baseline, noop-pre, etc.). */
        runs: z.record(z.string(), RunRecordSchema),
        /** Ordered operational history of commands/actions attempted. */
        events: z.array(CaseEventSchema).default([]),
        /** Checker verdicts collected across the case. */
        checkers: z.array(CheckerVerdictSchema).default([]),
        /** Additional failure/diagnostic context (phase-timeouts, errors). */
        diagnostics: z.array(z.string()).default([]),
        /** Total violations flagged across all checkpoints. */
        violations: z.array(ViolationSchema).default([]),
    })
    .strict();
export type CaseSnapshot = z.infer<typeof CaseSnapshotSchema>;
