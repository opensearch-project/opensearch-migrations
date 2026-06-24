import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { buildCoverageOverview, writeCoverageOverview } from "../src/coverageOverview";
import { writeCaseSnapshot } from "../src/snapshotStore";
import { CaseSnapshot } from "../src/reportSchema";

function snapshot(name: string, overrides: Partial<CaseSnapshot> = {}): CaseSnapshot {
    return {
        case: name,
        specPath: "/tmp/spec.yaml",
        outcome: "passed",
        startedAt: "2026-01-01T00:00:00.000Z",
        finishedAt: "2026-01-01T00:00:01.000Z",
        coverage: {
            subject: "datasnapshot:source-snap1",
            subjectStateAtMutation: "in-progress",
            observedSubjectPhaseBeforeMutation: "Initialized",
            declaredChangeClass: "safe",
            dependencyPattern: "subject-change",
            response: null,
            mutatorName: "repo-endpoint",
            changedPaths: ["sourceClusters.source.snapshotInfo.repos.default.endpoint"],
            expectedRerunComponents: ["datasnapshot:source-snap1"],
            poisonPill: {
                name: "bad-repo-endpoint",
                strategy: "config-value",
                expectedCollateral: ["captureproxy:capture-proxy"],
            },
        },
        runs: {},
        events: [],
        checkers: [],
        diagnostics: [],
        violations: [],
        ...overrides,
    };
}

describe("coverage overview", () => {
    let tmpDir: string;
    beforeEach(() => {
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "coverage-overview-"));
    });
    afterEach(() => {
        fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it("groups cases by subject state, change class, response, and poison pill", () => {
        const a = writeCaseSnapshot(snapshot("a"), { outputDir: tmpDir });
        const b = writeCaseSnapshot(snapshot("b", {
            outcome: "partial",
            diagnostics: ["note"],
        }), { outputDir: tmpDir });

        const overview = buildCoverageOverview([a, b], "2026-01-01T00:00:00.000Z");

        expect(overview.caseCount).toBe(2);
        expect(overview.groups).toHaveLength(1);
        expect(overview.groups[0]).toMatchObject({
            subject: "datasnapshot:source-snap1",
            subjectStateAtMutation: "in-progress",
            declaredChangeClass: "safe",
            poisonPill: "bad-repo-endpoint",
            total: 2,
            passed: 1,
            partial: 1,
            diagnostics: 1,
        });
        expect(overview.cases[0]).toMatchObject({
            observedSubjectPhaseBeforeMutation: "Initialized",
            poisonPillStrategy: "config-value",
        });
    });

    it("writes JSON and Markdown summary files", () => {
        const report = writeCaseSnapshot(snapshot("a"), { outputDir: tmpDir });
        const out = writeCoverageOverview([report], tmpDir);

        expect(path.basename(out.jsonPath)).toBe("coverage-summary.json");
        expect(path.basename(out.markdownPath)).toBe("coverage-summary.md");
        expect(fs.existsSync(out.jsonPath)).toBe(true);
        expect(fs.readFileSync(out.markdownPath, "utf8")).toContain("bad-repo-endpoint");
    });
});
