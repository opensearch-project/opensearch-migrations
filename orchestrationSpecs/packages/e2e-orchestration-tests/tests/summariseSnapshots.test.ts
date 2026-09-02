import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { summariseSnapshots } from "../src/e2e-run";
import { CaseSnapshot } from "../src/reportSchema";

function writeSnap(dir: string, snap: CaseSnapshot): string {
    const p = path.join(dir, `${snap.case}.json`);
    fs.writeFileSync(p, JSON.stringify(snap, null, 2) + "\n", "utf8");
    return p;
}

function baseSnap(
    name: string,
    overrides: Partial<CaseSnapshot> = {},
): CaseSnapshot {
    return {
        case: name,
        specPath: "/nowhere/test.yaml",
        outcome: "passed",
        startedAt: "2025-01-01T00:00:00Z",
        runs: {},
        events: [],
        checkers: [],
        diagnostics: [],
        violations: [],
        ...overrides,
    } as CaseSnapshot;
}

describe("summariseSnapshots", () => {
    let tmp: string;
    beforeEach(() => {
        tmp = fs.mkdtempSync(path.join(os.tmpdir(), "summary-"));
    });
    afterEach(() => {
        fs.rmSync(tmp, { recursive: true, force: true });
    });

    it("reports no failure when all cases passed with no violations", () => {
        const a = writeSnap(tmp, baseSnap("a"));
        const b = writeSnap(tmp, baseSnap("b"));
        const s = summariseSnapshots([a, b]);
        expect(s.anyFailed).toBe(false);
        expect(s.totalViolations).toBe(0);
        expect(s.errorCount).toBe(0);
        expect(s.partialCount).toBe(0);
        expect(s.cases).toHaveLength(2);
    });

    it("marks any error outcome as failed", () => {
        const a = writeSnap(tmp, baseSnap("a"));
        const b = writeSnap(tmp, baseSnap("b", { outcome: "error" }));
        const s = summariseSnapshots([a, b]);
        expect(s.anyFailed).toBe(true);
        expect(s.errorCount).toBe(1);
    });

    it("marks any case with violations as failed, even if outcome is partial", () => {
        const a = writeSnap(
            tmp,
            baseSnap("a", {
                outcome: "partial",
                violations: [
                    {
                        type: "noop-not-skipped",
                        checkpoint: "noop",
                        componentId: "captureproxy:capture-proxy",
                        message: "boom",
                    },
                ],
            }),
        );
        const s = summariseSnapshots([a]);
        expect(s.anyFailed).toBe(true);
        expect(s.totalViolations).toBe(1);
        expect(s.partialCount).toBe(1);
    });

    it("passes partial outcomes with zero violations (e.g. harmless teardown notes)", () => {
        const a = writeSnap(
            tmp,
            baseSnap("a", {
                outcome: "partial",
                diagnostics: ["teardown stub: not implemented yet"],
            }),
        );
        const s = summariseSnapshots([a]);
        expect(s.anyFailed).toBe(false);
        expect(s.totalViolations).toBe(0);
        expect(s.cases[0].diagnosticCount).toBe(1);
    });
});
