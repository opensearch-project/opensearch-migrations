/**
 * Snapshot store.
 *
 * Two pieces live in this module:
 *
 *   1. `ObservationBag` — append-only keyed store for observer output.
 *      Keys are `<observerName>@<phase>`. Duplicate writes throw. This
 *      mirrors the "state bag" described in the design doc and makes
 *      "the checker's phase never fired" a loud error (missing key)
 *      rather than a silent pass.
 *
 *   2. `writeCaseSnapshot` — serialises a short case report to
 *      `<outputDir>/<case>.json` and the full diagnostic log to
 *      `<outputDir>/<case>.details.json`. Pass/fail lives in
 *      `assertLogic`.
 */

import * as fs from "node:fs";
import * as path from "node:path";

import { LifecyclePhase } from "./types";
import { CaseSnapshot, CaseSnapshotSchema } from "./reportSchema";

// ── ObservationBag ───────────────────────────────────────────────────

export class ObservationBagError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "ObservationBagError";
    }
}

export type ObservationKey = `${string}@${LifecyclePhase}`;

export class ObservationBag {
    private readonly entries = new Map<string, unknown>();

    /** Compose the bag key for a given observer + phase. */
    static key(observerName: string, phase: LifecyclePhase): ObservationKey {
        return `${observerName}@${phase}` as ObservationKey;
    }

    record(observerName: string, phase: LifecyclePhase, value: unknown): void {
        const key = ObservationBag.key(observerName, phase);
        if (this.entries.has(key)) {
            throw new ObservationBagError(
                `Duplicate observation: ${key} — bag is append-only`,
            );
        }
        this.entries.set(key, value);
    }

    /** Raw get by already-composed key. */
    getByKey<T>(key: string): T | undefined {
        return this.entries.get(key) as T | undefined;
    }

    get<T>(observerName: string, phase: LifecyclePhase): T | undefined {
        return this.getByKey<T>(ObservationBag.key(observerName, phase));
    }

    has(observerName: string, phase: LifecyclePhase): boolean {
        return this.entries.has(ObservationBag.key(observerName, phase));
    }

    keys(): readonly string[] {
        return [...this.entries.keys()];
    }

    size(): number {
        return this.entries.size;
    }

    /** Snapshot the bag as a plain object (for serialisation). */
    toJSON(): Record<string, unknown> {
        return Object.fromEntries(this.entries);
    }
}

// ── Snapshot file I/O ────────────────────────────────────────────────

export interface WriteCaseSnapshotOptions {
    outputDir: string;
    /**
     * If true (default), validate against `CaseSnapshotSchema` before
     * writing. Callers that need to write partial snapshots for
     * diagnostics can set this to false.
     */
    validate?: boolean;
}

export interface CaseReport {
    case: string;
    specPath: string;
    outcome: CaseSnapshot["outcome"];
    startedAt: string;
    finishedAt?: string;
    durationMs?: number;
    duration?: string;
    detailPath: string;
    runCount: number;
    violationCount: number;
    diagnosticCount: number;
    runs: Array<{
        name: string;
        durationMs?: number;
        duration?: string;
        checkpoints: Array<{
            checkpoint: string;
            durationMs?: number;
            duration?: string;
            workflowWaitDurationMs?: number;
            workflowWaitDuration?: string;
            componentReadyWaitDurationMs?: number;
            componentReadyWaitDuration?: string;
            componentCount: number;
            violationCount: number;
            argoPhase?: string;
            argoNodeCount?: number;
        }>;
    }>;
    workflowSteps: string[];
    diagnostics: string[];
    violations: Array<{
        type: string;
        checkpoint: string;
        componentId: string;
        message: string;
    }>;
}

/**
 * Write a short case report to `<outputDir>/<case>.json` and a full
 * diagnostic snapshot to `<outputDir>/<case>.details.json`. Creates
 * the directory if needed. Returns the absolute path of the short
 * report.
 */
export function writeCaseSnapshot(
    snapshot: CaseSnapshot,
    opts: WriteCaseSnapshotOptions,
): string {
    const validate = opts.validate ?? true;
    if (validate) {
        const parsed = CaseSnapshotSchema.safeParse(snapshot);
        if (!parsed.success) {
            const issues = parsed.error.issues
                .map((i) => `  - ${i.path.join(".") || "<root>"}: ${i.message}`)
                .join("\n");
            throw new Error(
                `Refusing to write invalid snapshot for case ${snapshot.case}:\n${issues}`,
            );
        }
    }

    fs.mkdirSync(opts.outputDir, { recursive: true });
    const outputDirAbs = path.resolve(opts.outputDir);

    // Sanitise: replace every non-alphanumeric/underscore/dot/hyphen character
    // with '_'. This also collapses path separators and '..' tokens the
    // design doc's case names can contain (e.g. "foo/approve").
    const safeName = snapshot.case.replace(/[^a-zA-Z0-9._-]/g, "_");
    if (!safeName || !/[a-zA-Z0-9]/.test(safeName) || safeName === "." || safeName === "..") {
        throw new Error(
            `Refusing to write snapshot: case name '${snapshot.case}' produced an unsafe filename '${safeName}'`,
        );
    }

    const outPath = path.resolve(outputDirAbs, `${safeName}.json`);
    const detailPath = path.resolve(outputDirAbs, `${safeName}.details.json`);
    // Defence in depth: even though the sanitiser should make this
    // impossible, confirm the final path is under outputDir before
    // writing. This guards against future changes to the sanitiser.
    // Check for actual '..' segments, not a literal '..' prefix on a
    // filename (e.g. a sanitised name like '.._foo.json').
    assertInsideOutputDir(outputDirAbs, outPath);
    assertInsideOutputDir(outputDirAbs, detailPath);

    fs.writeFileSync(detailPath, JSON.stringify(snapshot, null, 2) + "\n", "utf8");
    fs.writeFileSync(
        outPath,
        JSON.stringify(buildCaseReport(snapshot, detailPath), null, 2) + "\n",
        "utf8",
    );
    return outPath;
}

export function buildCaseReport(snapshot: CaseSnapshot, detailPath: string): CaseReport {
    const nextCheckpointEvents = checkpointEventReader(snapshot.events);
    const runs = Object.values(snapshot.runs).map((run) => ({
        name: run.name,
        ...durationFromTimes(runBoundaryTimes(snapshot, run.name, run.checkpoints)),
        checkpoints: run.checkpoints.map((cp) => {
            const events = nextCheckpointEvents(cp.checkpoint, cp.observedAt);
            return {
                checkpoint: cp.checkpoint,
                ...durationFromTimes([
                    ...events.map((e) => parseTimeMs(e.at)),
                    parseTimeMs(cp.observedAt),
                ]),
                ...prefixedDuration(
                    "workflowWait",
                    waitDurationFromEvents(events, "wait-workflow-completion"),
                ),
                ...prefixedDuration(
                    "componentReadyWait",
                    waitDurationFromEvents(events, "wait-phase-completion"),
                ),
                componentCount: Object.keys(cp.components).length,
                violationCount: (cp.violations ?? []).length,
                argoPhase: cp.argoWorkflow?.phase,
                argoNodeCount: cp.argoWorkflow?.nodes.length,
            };
        }),
    }));
    const violations: CaseReport["violations"] = (snapshot.violations ?? []).map((v) => ({
        type: v.type,
        checkpoint: v.checkpoint ?? "(case)",
        componentId: v.componentId ?? "(none)",
        message: v.message,
    }));
    return {
        case: snapshot.case,
        specPath: snapshot.specPath,
        outcome: snapshot.outcome,
        startedAt: snapshot.startedAt,
        finishedAt: snapshot.finishedAt,
        ...durationBetween(snapshot.startedAt, snapshot.finishedAt),
        detailPath,
        runCount: runs.length,
        violationCount: (snapshot.violations ?? []).length,
        diagnosticCount: (snapshot.diagnostics ?? []).length,
        runs,
        workflowSteps: summarizeWorkflowSteps(snapshot),
        diagnostics: snapshot.diagnostics ?? [],
        violations,
    };
}

type CaseEvent = CaseSnapshot["events"][number];
type RunCheckpoint = CaseSnapshot["runs"][string]["checkpoints"][number];

function runBoundaryTimes(
    snapshot: CaseSnapshot,
    runName: string,
    checkpoints: readonly RunCheckpoint[],
): Array<number | undefined> {
    return [
        ...snapshot.events
            .filter((e) => e.phase === runName)
            .map((e) => parseTimeMs(e.at)),
        ...checkpoints.map((cp) => parseTimeMs(cp.observedAt)),
    ];
}

function checkpointEventReader(events: readonly CaseEvent[]) {
    const byPhase = new Map<string, CaseEvent[]>();
    for (const e of events) {
        const current = byPhase.get(e.phase) ?? [];
        current.push(e);
        byPhase.set(e.phase, current);
    }

    const cursors = new Map<string, number>();
    return (checkpoint: string, observedAt: string): CaseEvent[] => {
        const phaseEvents = byPhase.get(checkpoint) ?? [];
        const observedMs = parseTimeMs(observedAt);
        const start = cursors.get(checkpoint) ?? 0;
        if (observedMs === undefined) {
            cursors.set(checkpoint, phaseEvents.length);
            return phaseEvents.slice(start);
        }

        let end = start;
        while (end < phaseEvents.length) {
            const eventMs = parseTimeMs(phaseEvents[end]?.at);
            if (eventMs === undefined || eventMs > observedMs) break;
            end += 1;
        }
        cursors.set(checkpoint, end);
        return phaseEvents.slice(start, end);
    };
}

function durationBetween(
    startedAt: string | undefined,
    finishedAt: string | undefined,
): { durationMs?: number; duration?: string } {
    const start = parseTimeMs(startedAt);
    const end = parseTimeMs(finishedAt);
    if (start === undefined || end === undefined || end < start) return {};
    return durationFields(end - start);
}

function durationFromTimes(
    times: ReadonlyArray<number | undefined>,
): { durationMs?: number; duration?: string } {
    const valid = times.filter((t): t is number => t !== undefined);
    if (valid.length < 2) return {};
    const start = Math.min(...valid);
    const end = Math.max(...valid);
    if (end < start) return {};
    return durationFields(end - start);
}

function waitDurationFromEvents(
    events: readonly CaseEvent[],
    action: string,
): { durationMs?: number; duration?: string } {
    for (let i = events.length - 1; i >= 0; i -= 1) {
        const e = events[i];
        if (e?.action !== action) continue;
        const match = e.message?.match(/\bafter (\d+)ms\b/);
        if (!match) continue;
        return durationFields(Number(match[1]));
    }
    return {};
}

function prefixedDuration(
    prefix: "workflowWait" | "componentReadyWait",
    duration: { durationMs?: number; duration?: string },
): Record<string, string | number> {
    if (duration.durationMs === undefined || duration.duration === undefined) return {};
    return {
        [`${prefix}DurationMs`]: duration.durationMs,
        [`${prefix}Duration`]: duration.duration,
    };
}

function durationFields(durationMs: number): { durationMs: number; duration: string } {
    const roundedMs = Math.max(0, Math.round(durationMs));
    return {
        durationMs: roundedMs,
        duration: formatDuration(roundedMs),
    };
}

function parseTimeMs(value: string | undefined): number | undefined {
    if (!value) return undefined;
    const ms = Date.parse(value);
    return Number.isFinite(ms) ? ms : undefined;
}

function formatDuration(ms: number): string {
    if (ms < 1000) return `${ms}ms`;

    const totalSeconds = Math.floor(ms / 1000);
    const remainingMs = ms % 1000;
    if (totalSeconds < 60) {
        if (remainingMs === 0) return `${totalSeconds}s`;
        return `${(ms / 1000).toFixed(3).replace(/\.?0+$/, "")}s`;
    }

    const totalMinutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    if (totalMinutes < 60) return `${totalMinutes}m ${seconds}s`;

    const hours = Math.floor(totalMinutes / 60);
    const minutes = totalMinutes % 60;
    return `${hours}h ${minutes}m ${seconds}s`;
}

function summarizeWorkflowSteps(snapshot: CaseSnapshot): string[] {
    const phases = new Map<string, string[]>();
    for (const e of snapshot.events) {
        const action =
            e.action === "run-actors"
                ? `${e.action}:${e.result}`
                : e.result === "ok"
                  ? e.action
                  : `${e.action}:error`;
        const current = phases.get(e.phase) ?? [];
        if (current[current.length - 1] !== action) current.push(action);
        phases.set(e.phase, current);
    }
    return [...phases.entries()].map(([phase, actions]) => `${phase}: ${actions.join(" -> ")}`);
}

function assertInsideOutputDir(outputDirAbs: string, filePath: string): void {
    const rel = path.relative(outputDirAbs, filePath);
    const isEscape =
        rel === ".." ||
        rel.startsWith(".." + path.sep) ||
        path.isAbsolute(rel);
    if (isEscape) {
        throw new Error(
            `Refusing to write snapshot: resolved path ${filePath} escapes ${outputDirAbs}`,
        );
    }
}
