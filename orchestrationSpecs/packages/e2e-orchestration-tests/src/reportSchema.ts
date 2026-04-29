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
 * A single checkpoint-level slice of a run: the observed components at
 * that point, plus any violations flagged by the assertion walk.
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
        /** Checker verdicts collected across the case. */
        checkers: z.array(CheckerVerdictSchema).default([]),
        /** Additional failure/diagnostic context (phase-timeouts, errors). */
        diagnostics: z.array(z.string()).default([]),
        /** Total violations flagged across all checkpoints. */
        violations: z.array(ViolationSchema).default([]),
    })
    .strict();
export type CaseSnapshot = z.infer<typeof CaseSnapshotSchema>;
