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
 *   2. `writeCaseSnapshot` — serialises a `CaseSnapshot` to disk under
 *      `<outputDir>/<case>.json`. The file is the authoritative
 *      diagnostic log; pass/fail lives in `assertLogic`.
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

/**
 * Write a case snapshot to `<outputDir>/<case>.json`. Creates the
 * directory if needed. Returns the absolute path of the file written.
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
    // Defence in depth: even though the sanitiser should make this
    // impossible, confirm the final path is under outputDir before
    // writing. This guards against future changes to the sanitiser.
    // Check for actual '..' segments, not a literal '..' prefix on a
    // filename (e.g. a sanitised name like '.._foo.json').
    const rel = path.relative(outputDirAbs, outPath);
    const isEscape =
        rel === ".." ||
        rel.startsWith(".." + path.sep) ||
        path.isAbsolute(rel);
    if (isEscape) {
        throw new Error(
            `Refusing to write snapshot: resolved path ${outPath} escapes ${outputDirAbs}`,
        );
    }

    fs.writeFileSync(outPath, JSON.stringify(snapshot, null, 2) + "\n", "utf8");
    return outPath;
}
