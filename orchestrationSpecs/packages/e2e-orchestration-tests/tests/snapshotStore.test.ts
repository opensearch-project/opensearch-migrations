import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { ObservationBag, ObservationBagError, writeCaseSnapshot } from "../src/snapshotStore";
import { CaseSnapshot } from "../src/reportSchema";

describe("ObservationBag", () => {
    it("stores and retrieves by observer+phase", () => {
        const bag = new ObservationBag();
        bag.record("target-indices", "baseline-complete", { docCount: 5 });
        expect(bag.get("target-indices", "baseline-complete")).toEqual({ docCount: 5 });
        expect(bag.has("target-indices", "baseline-complete")).toBe(true);
        expect(bag.size()).toBe(1);
    });

    it("returns undefined for a missing key", () => {
        const bag = new ObservationBag();
        expect(bag.get("nope", "setup")).toBeUndefined();
        expect(bag.has("nope", "setup")).toBe(false);
    });

    it("is append-only — duplicate writes throw", () => {
        const bag = new ObservationBag();
        bag.record("target-indices", "baseline-complete", { a: 1 });
        expect(() =>
            bag.record("target-indices", "baseline-complete", { a: 2 }),
        ).toThrow(ObservationBagError);
        // And the original value is preserved.
        expect(bag.get("target-indices", "baseline-complete")).toEqual({ a: 1 });
    });

    it("distinguishes same observer at different phases", () => {
        const bag = new ObservationBag();
        bag.record("target-indices", "baseline-complete", { n: 1 });
        bag.record("target-indices", "mutated-complete", { n: 2 });
        expect(bag.keys()).toEqual(
            expect.arrayContaining([
                "target-indices@baseline-complete",
                "target-indices@mutated-complete",
            ]),
        );
    });

    it("composes keys with the documented format", () => {
        expect(ObservationBag.key("foo", "noop-pre-complete")).toBe(
            "foo@noop-pre-complete",
        );
    });
});

const MIN_SNAPSHOT: CaseSnapshot = {
    case: "proxy-subjectChange-numThreads",
    specPath: "/tmp/specs/proxy-subjectChange.test.yaml",
    outcome: "partial",
    startedAt: "2025-01-01T00:00:00Z",
    runs: {
        baseline: {
            name: "baseline",
            checkpoints: [],
        },
    },
    checkers: [],
    diagnostics: [],
    violations: [],
};

describe("writeCaseSnapshot", () => {
    let tmpDir: string;
    beforeEach(() => {
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-snap-"));
    });
    afterEach(() => {
        fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it("writes a validated snapshot to <outputDir>/<case>.json", () => {
        const out = writeCaseSnapshot(MIN_SNAPSHOT, { outputDir: tmpDir });
        expect(out).toBe(
            path.resolve(tmpDir, "proxy-subjectChange-numThreads.json"),
        );
        const written = JSON.parse(fs.readFileSync(out, "utf8"));
        expect(written.case).toBe("proxy-subjectChange-numThreads");
        expect(written.outcome).toBe("partial");
    });

    it("creates the output directory if needed", () => {
        const nested = path.join(tmpDir, "a", "b", "c");
        const out = writeCaseSnapshot(MIN_SNAPSHOT, { outputDir: nested });
        expect(fs.existsSync(out)).toBe(true);
    });

    it("sanitises case names in the filename", () => {
        const snap = { ...MIN_SNAPSHOT, case: "weird/name:with spaces?" };
        const out = writeCaseSnapshot(snap, { outputDir: tmpDir, validate: false });
        expect(path.basename(out)).not.toMatch(/[ ?:]/);
    });

    it("refuses to write an invalid snapshot when validate=true (default)", () => {
        // Outcome 'never' isn't in the schema.
        const bad = { ...MIN_SNAPSHOT, outcome: "never" } as unknown as CaseSnapshot;
        expect(() => writeCaseSnapshot(bad, { outputDir: tmpDir })).toThrow(
            /Refusing to write invalid snapshot/,
        );
    });

    it("allows writing an invalid snapshot when validate=false", () => {
        const bad = { ...MIN_SNAPSHOT, outcome: "never" } as unknown as CaseSnapshot;
        const out = writeCaseSnapshot(bad, { outputDir: tmpDir, validate: false });
        expect(fs.existsSync(out)).toBe(true);
    });
});

describe("writeCaseSnapshot path safety", () => {
    let tmpDir: string;
    beforeEach(() => {
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-snap-safe-"));
    });
    afterEach(() => {
        fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it("refuses to escape outputDir via '../' in the case name", () => {
        const snap = { ...MIN_SNAPSHOT, case: "../escapee" };
        const out = writeCaseSnapshot(snap, { outputDir: tmpDir, validate: false });
        // The sanitiser collapses '/' so the file stays inside tmpDir.
        // (The filename may still start with '..' — that's fine since
        // it's treated as a literal filename, not a path segment.)
        expect(path.dirname(out)).toBe(path.resolve(tmpDir));
        expect(path.basename(out)).not.toContain("/");
        expect(path.basename(out)).not.toContain(path.sep);
    });

    it("flattens design-style nested names like 'foo/approve'", () => {
        const snap = { ...MIN_SNAPSHOT, case: "proxy-subjectGatedChange-x/approve" };
        const out = writeCaseSnapshot(snap, { outputDir: tmpDir, validate: false });
        expect(path.dirname(out)).toBe(path.resolve(tmpDir));
        expect(path.basename(out)).toBe("proxy-subjectGatedChange-x_approve.json");
    });

    it("rejects a case name that sanitises to an empty or dot value", () => {
        const bad = { ...MIN_SNAPSHOT, case: "///" };
        expect(() =>
            writeCaseSnapshot(bad, { outputDir: tmpDir, validate: false }),
        ).toThrow(/unsafe filename/);
    });
});

describe("RunCheckpoint schema", () => {
    const { RunCheckpointSchema } = require("../src/reportSchema") as typeof import("../src/reportSchema");

    const base = {
        checkpoint: "mutated-complete" as const,
        observedAt: "2025-01-01T00:00:00Z",
        violations: [],
    };

    it("requires map keys to match component.componentId", () => {
        const bad = {
            ...base,
            components: {
                "proxy:capture-proxy": {
                    componentId: "kafka:wrong-id",
                    phase: "Ready",
                },
            },
        };
        const result = RunCheckpointSchema.safeParse(bad);
        expect(result.success).toBe(false);
    });

    it("accepts consistent map keys", () => {
        const good = {
            ...base,
            components: {
                "proxy:capture-proxy": {
                    componentId: "proxy:capture-proxy",
                    phase: "Ready",
                },
            },
        };
        expect(RunCheckpointSchema.safeParse(good).success).toBe(true);
    });

    it("rejects invalid ComponentId in map keys", () => {
        const bad = {
            ...base,
            components: {
                "NotAComponentId": {
                    componentId: "NotAComponentId",
                    phase: "Ready",
                },
            },
        };
        expect(RunCheckpointSchema.safeParse(bad).success).toBe(false);
    });
});
