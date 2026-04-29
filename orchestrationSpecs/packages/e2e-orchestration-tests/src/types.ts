/**
 * Core type definitions and Zod schemas for the e2e orchestration test
 * framework.
 *
 * This module is intentionally pure: no cluster calls, no filesystem, no
 * process-level side effects. Everything downstream of specs and snapshots
 * consumes these types.
 *
 * See:
 *   orchestrationSpecs/docs/workflowTesting/e2eOrchestrationTestFramework.md
 *   orchestrationSpecs/docs/workflowTesting/e2eOrchestrationImplementationPlan.md
 */

import { z } from "zod";

// ── Enumerations ─────────────────────────────────────────────────────

/** What the migration framework does when it sees this config change. */
export const ChangeClassSchema = z.enum(["safe", "gated", "impossible"]);
export type ChangeClass = z.infer<typeof ChangeClassSchema>;

/** Where the change lands relative to the subject component. */
export const DependencyPatternSchema = z.enum([
    "subject-change",
    "subject-gated-change",
    "subject-impossible-change",
    "immediate-dependent-change",
    "transitive-dependent-change",
]);
export type DependencyPattern = z.infer<typeof DependencyPatternSchema>;

/** What the test does after a gated/impossible mutation. */
export const ResponseSchema = z.enum([
    "approve",
    "leave-blocked",
    "reset-then-approve",
    "approve-only",
    "reset-only",
]);
export type Response = z.infer<typeof ResponseSchema>;

/** Lifecycle phases — keys in the observation state bag use these. */
export const LifecyclePhaseSchema = z.enum([
    "setup",
    "baseline-complete",
    "noop-pre-complete",
    "mutated-complete",
    "mutation-gated",
    "mutation-gate-approved",
    "mutation-blocked",
    "mutation-after-reset",
    "noop-post-complete",
    "teardown",
]);
export type LifecyclePhase = z.infer<typeof LifecyclePhaseSchema>;

/** Checkpoints at which assertNoViolations is called. */
export const CheckpointSchema = z.enum([
    "noop",
    "mutated-complete",
    "before-approval",
    "after-approval",
    "on-blocked",
    "after-approve-without-reset",
    "after-reset",
    "after-approve",
]);
export type Checkpoint = z.infer<typeof CheckpointSchema>;

/** Component behavior labels used in snapshot observations. */
export const BehaviorSchema = z.enum([
    "ran",
    "reran",
    "skipped",
    "blocked",
    "gated",
    "unstarted",
]);
export type Behavior = z.infer<typeof BehaviorSchema>;

// ── Component identity ───────────────────────────────────────────────

/**
 * Component IDs are "kind:resource-name" strings. We keep the pattern
 * permissive enough to cover the current CRD types (kafkacluster,
 * captureproxy, snapshotmigration, trafficreplay, approvalgate, etc.)
 * while still rejecting obviously malformed values.
 */
export const ComponentIdSchema = z
    .string()
    .regex(/^[a-z][a-z0-9-]*:[a-z0-9][a-z0-9._-]*$/);
export type ComponentId = z.infer<typeof ComponentIdSchema>;

// ── Spec shape ───────────────────────────────────────────────────────

/**
 * A single selector entry in matrix.select.
 *
 * The `response` field is only valid for gated/impossible selectors
 * and each class accepts a fixed set of responses. Safe selectors must
 * omit `response` entirely. See the design doc's "Scenario Model"
 * section.
 *
 * Some `DependencyPattern` values embed a change class in their name
 * (`subject-gated-change`, `subject-impossible-change`). Those may only
 * appear with their matching `changeClass`. Patterns without an embedded
 * class (`subject-change`, `immediate-dependent-change`,
 * `transitive-dependent-change`) are class-neutral and accepted with
 * any `changeClass`.
 *
 * The `subject-change` pattern is the exception to the "class-neutral"
 * rule by convention — the design doc pairs it with `safe`. We keep it
 * open here so that future selectors for gated or impossible
 * subject-scoped mutators on dependent components are still expressible
 * via `immediate-dependent-change`/`transitive-dependent-change`.
 */
const GATED_RESPONSES = ["approve", "leave-blocked"] as const;
const IMPOSSIBLE_RESPONSES = [
    "reset-then-approve",
    "approve-only",
    "reset-only",
    "leave-blocked",
] as const;

/** Patterns whose name embeds a specific change class. */
const CLASS_SPECIFIC_PATTERNS: Readonly<Record<ChangeClass, DependencyPattern>> = {
    safe: "subject-change",
    gated: "subject-gated-change",
    impossible: "subject-impossible-change",
};
const CLASS_SPECIFIC_PATTERN_SET: ReadonlySet<DependencyPattern> = new Set(
    Object.values(CLASS_SPECIFIC_PATTERNS),
);

export const MatrixSelectorSchema = z
    .object({
        changeClass: ChangeClassSchema,
        patterns: z.array(DependencyPatternSchema).min(1),
        /** Required for gated/impossible; must be omitted for safe. */
        response: ResponseSchema.optional(),
    })
    .strict()
    .superRefine((sel, ctx) => {
        // Pattern vs. changeClass: class-specific patterns must match the
        // selector's changeClass.
        const allowedClassSpecific = CLASS_SPECIFIC_PATTERNS[sel.changeClass];
        for (let i = 0; i < sel.patterns.length; i++) {
            const p = sel.patterns[i];
            if (
                CLASS_SPECIFIC_PATTERN_SET.has(p) &&
                p !== allowedClassSpecific
            ) {
                ctx.addIssue({
                    code: "custom",
                    message: `pattern '${p}' only combines with changeClass='${reverseLookup(p)}', not '${sel.changeClass}'`,
                    path: ["patterns", i],
                });
            }
        }

        if (sel.changeClass === "safe") {
            if (sel.response !== undefined) {
                ctx.addIssue({
                    code: "custom",
                    message:
                        "safe selectors must not set 'response' — safe mutations proceed automatically",
                    path: ["response"],
                });
            }
            return;
        }
        if (sel.response === undefined) {
            ctx.addIssue({
                code: "custom",
                message: `${sel.changeClass} selectors require 'response'`,
                path: ["response"],
            });
            return;
        }
        const allowed =
            sel.changeClass === "gated"
                ? (GATED_RESPONSES as readonly string[])
                : (IMPOSSIBLE_RESPONSES as readonly string[]);
        if (!allowed.includes(sel.response)) {
            ctx.addIssue({
                code: "custom",
                message: `${sel.changeClass} selectors accept response values ${allowed.join(
                    " | ",
                )}; got '${sel.response}'`,
                path: ["response"],
            });
        }
    });
export type MatrixSelector = z.infer<typeof MatrixSelectorSchema>;

function reverseLookup(p: DependencyPattern): ChangeClass | "unknown" {
    for (const [cls, pat] of Object.entries(CLASS_SPECIFIC_PATTERNS) as [ChangeClass, DependencyPattern][]) {
        if (pat === p) return cls;
    }
    return "unknown";
}

export const MatrixSpecSchema = z
    .object({
        subject: ComponentIdSchema,
        /** Omit to run all registered mutators for the subject. */
        select: z.array(MatrixSelectorSchema).optional(),
    })
    .strict();
export type MatrixSpec = z.infer<typeof MatrixSpecSchema>;

export const LifecycleActorsSchema = z
    .object({
        setup: z.array(z.string()).default([]),
        teardown: z.array(z.string()).default([]),
    })
    .strict();
export type LifecycleActors = z.infer<typeof LifecycleActorsSchema>;

export const ApprovalGateSpecSchema = z
    .object({
        approvePattern: z.string(),
        validations: z.array(z.string()).default([]),
    })
    .strict();
export type ApprovalGateSpec = z.infer<typeof ApprovalGateSpecSchema>;

/**
 * Full test spec. This is what `src/specLoader.ts` parses from YAML on
 * disk. The baseline config path is resolved relative to the spec file.
 */
export const ScenarioSpecSchema = z
    .object({
        baseConfig: z.string().min(1),
        /** Per-phase budget for the phase-completion predicate. */
        phaseCompletionTimeoutSeconds: z.number().int().positive().default(600),
        matrix: MatrixSpecSchema,
        lifecycle: LifecycleActorsSchema.default({ setup: [], teardown: [] }),
        approvalGates: z.array(ApprovalGateSpecSchema).default([]),
    })
    .strict();
export type ScenarioSpec = z.infer<typeof ScenarioSpecSchema>;

// ── Observations ─────────────────────────────────────────────────────

/**
 * A single component's observed state at a checkpoint. We intentionally
 * keep this permissive: `phase` comes from real CRD status and the exact
 * vocabulary is still being pinned down. `behavior` is derived by the
 * runner from phase deltas and run context.
 */
export const ObservedComponentSchema = z
    .object({
        componentId: ComponentIdSchema,
        phase: z.string(),
        behavior: BehaviorSchema.optional(),
        configChecksum: z.string().optional(),
        generation: z.number().int().optional(),
        uid: z.string().optional(),
        startedAtSeconds: z.number().optional(),
        durationSeconds: z.number().optional(),
        gatePending: z.boolean().optional(),
        /** For impossible-case non-advancement verification. */
        advanced: z.boolean().optional(),
        /** Diagnostic-only raw data from the CRD. */
        raw: z.unknown().optional(),
    })
    .strict();
export type ObservedComponent = z.infer<typeof ObservedComponentSchema>;

/** A bag of component observations keyed by component ID. */
export const ObservedSnapshotSchema = z.object({
    components: z.record(ComponentIdSchema, ObservedComponentSchema),
});
export type ObservedSnapshot = z.infer<typeof ObservedSnapshotSchema>;

// ── Violations ───────────────────────────────────────────────────────

export const ViolationTypeSchema = z.enum([
    "noop-not-skipped",
    "change-class",
    "cascade",
    "independence",
    "phase-timeout",
    "missing-observation",
    "topology-mismatch",
]);
export type ViolationType = z.infer<typeof ViolationTypeSchema>;

export const ViolationSchema = z
    .object({
        type: ViolationTypeSchema,
        checkpoint: CheckpointSchema.optional(),
        componentId: ComponentIdSchema.optional(),
        message: z.string(),
        /** Additional context: expected vs observed, changed paths, etc. */
        details: z.record(z.string(), z.unknown()).optional(),
    })
    .strict();
export type Violation = z.infer<typeof ViolationSchema>;
