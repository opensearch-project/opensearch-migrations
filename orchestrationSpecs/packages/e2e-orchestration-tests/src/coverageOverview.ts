/**
 * Coverage overview writer.
 *
 * Per-case snapshots are intentionally detailed. This module builds the
 * run-level index a developer can scan to see which subject states and
 * change classes were actually exercised.
 */

import * as fs from "node:fs";
import * as path from "node:path";

import { CaseSnapshot } from "./reportSchema";

export interface CoverageCaseSummary {
    case: string;
    outcome: CaseSnapshot["outcome"];
    subject?: string;
    subjectStateAtMutation?: string;
    observedSubjectPhaseBeforeMutation?: string;
    fieldChangeClass?: string;
    declaredChangeClass?: string;
    effectiveChangeClass?: string;
    effectiveChangeReason?: string;
    dependencyPattern?: string;
    response?: string;
    mutatorName?: string;
    poisonPill?: string;
    poisonPillStrategy?: string;
    expectedCollateral?: string[];
    changedPaths?: readonly string[];
    violationCount: number;
    diagnosticCount: number;
    reportPath: string;
    detailPath?: string;
}

export interface CoverageGroupSummary {
    key: string;
    subject?: string;
    subjectStateAtMutation?: string;
    fieldChangeClass?: string;
    declaredChangeClass?: string;
    effectiveChangeClass?: string;
    effectiveChangeReason?: string;
    dependencyPattern?: string;
    response?: string;
    poisonPill?: string;
    total: number;
    passed: number;
    partial: number;
    failed: number;
    error: number;
    violations: number;
    diagnostics: number;
}

export interface CoverageOverview {
    generatedAt: string;
    caseCount: number;
    groups: CoverageGroupSummary[];
    cases: CoverageCaseSummary[];
}

export function buildCoverageOverview(
    reportPaths: readonly string[],
    generatedAt = new Date().toISOString(),
): CoverageOverview {
    const cases = reportPaths.map(readCoverageCaseSummary);
    const groupsByKey = new Map<string, CoverageGroupSummary>();

    for (const c of cases) {
        const key = [
            c.subject ?? "(unknown-subject)",
            c.subjectStateAtMutation ?? "(unknown-state)",
            c.effectiveChangeClass ?? c.declaredChangeClass ?? "(unknown-class)",
            c.dependencyPattern ?? "(unknown-pattern)",
            c.response ?? "(no-response)",
            c.poisonPill ?? "(no-poison)",
        ].join("|");
        const group = groupsByKey.get(key) ?? {
            key,
            subject: c.subject,
            subjectStateAtMutation: c.subjectStateAtMutation,
            fieldChangeClass: c.fieldChangeClass,
            declaredChangeClass: c.declaredChangeClass,
            effectiveChangeClass: c.effectiveChangeClass,
            effectiveChangeReason: c.effectiveChangeReason,
            dependencyPattern: c.dependencyPattern,
            response: c.response,
            poisonPill: c.poisonPill,
            total: 0,
            passed: 0,
            partial: 0,
            failed: 0,
            error: 0,
            violations: 0,
            diagnostics: 0,
        };
        group.total += 1;
        group[c.outcome] += 1;
        group.violations += c.violationCount;
        group.diagnostics += c.diagnosticCount;
        groupsByKey.set(key, group);
    }

    return {
        generatedAt,
        caseCount: cases.length,
        groups: [...groupsByKey.values()].sort((a, b) => a.key.localeCompare(b.key)),
        cases,
    };
}

export function writeCoverageOverview(
    reportPaths: readonly string[],
    outputDir: string,
): { jsonPath: string; markdownPath: string } {
    fs.mkdirSync(outputDir, { recursive: true });
    const overview = buildCoverageOverview(reportPaths);
    const jsonPath = path.resolve(outputDir, "coverage-summary.json");
    const markdownPath = path.resolve(outputDir, "coverage-summary.md");
    fs.writeFileSync(jsonPath, JSON.stringify(overview, null, 2) + "\n", "utf8");
    fs.writeFileSync(markdownPath, renderCoverageMarkdown(overview), "utf8");
    return { jsonPath, markdownPath };
}

function readCoverageCaseSummary(reportPath: string): CoverageCaseSummary {
    const reportRaw = JSON.parse(fs.readFileSync(reportPath, "utf8")) as Partial<CaseSnapshot> & {
        detailPath?: string;
        violationCount?: number;
        diagnosticCount?: number;
    };
    const snapshot = reportRaw.detailPath && fs.existsSync(reportRaw.detailPath)
        ? (JSON.parse(fs.readFileSync(reportRaw.detailPath, "utf8")) as CaseSnapshot)
        : (reportRaw as CaseSnapshot);
    const coverage = snapshot.coverage;
    return {
        case: snapshot.case,
        outcome: snapshot.outcome,
        subject: coverage?.subject,
        subjectStateAtMutation: coverage?.subjectStateAtMutation,
        observedSubjectPhaseBeforeMutation: coverage?.observedSubjectPhaseBeforeMutation,
        fieldChangeClass: coverage?.fieldChangeClass,
        declaredChangeClass: coverage?.declaredChangeClass,
        effectiveChangeClass: coverage?.effectiveChangeClass,
        effectiveChangeReason: coverage?.effectiveChangeReason,
        dependencyPattern: coverage?.dependencyPattern,
        response: coverage?.response ?? undefined,
        mutatorName: coverage?.mutatorName,
        poisonPill: coverage?.poisonPill?.name,
        poisonPillStrategy: coverage?.poisonPill?.strategy,
        expectedCollateral: coverage?.poisonPill?.expectedCollateral,
        changedPaths: coverage?.changedPaths,
        violationCount:
            typeof reportRaw.violationCount === "number"
                ? reportRaw.violationCount
                : (snapshot.violations ?? []).length,
        diagnosticCount:
            typeof reportRaw.diagnosticCount === "number"
                ? reportRaw.diagnosticCount
                : (snapshot.diagnostics ?? []).length,
        reportPath: path.resolve(reportPath),
        detailPath: reportRaw.detailPath,
    };
}

function renderCoverageMarkdown(overview: CoverageOverview): string {
    const lines: string[] = [
        "# E2E Orchestration Coverage Summary",
        "",
        `Generated: ${overview.generatedAt}`,
        "",
        `Cases: ${overview.caseCount}`,
        "",
        "## Groups",
        "",
        "| Subject | State | Field class | Effective class | Reason | Pattern | Response | Poison pill | Cases | Outcomes | Violations | Diagnostics |",
        "|---|---|---|---|---|---|---|---|---:|---|---:|---:|",
    ];

    for (const g of overview.groups) {
        lines.push([
            cell(g.subject),
            cell(g.subjectStateAtMutation),
            cell(g.fieldChangeClass),
            cell(g.effectiveChangeClass ?? g.declaredChangeClass),
            cell(g.effectiveChangeReason),
            cell(g.dependencyPattern),
            cell(g.response),
            cell(g.poisonPill),
            String(g.total),
            `passed ${g.passed}, partial ${g.partial}, failed ${g.failed}, error ${g.error}`,
            String(g.violations),
            String(g.diagnostics),
        ].join(" | ").replace(/^/, "| ").replace(/$/, " |"));
    }

    lines.push(
        "",
        "## Cases",
        "",
        "| Case | Outcome | Subject Phase Before Mutation | Effective Class | Reason | Poison | Violations | Diagnostics |",
        "|---|---|---|---|---|---|---:|---:|",
    );
    for (const c of overview.cases) {
        lines.push([
            cell(c.case),
            cell(c.outcome),
            cell(c.observedSubjectPhaseBeforeMutation),
            cell(c.effectiveChangeClass ?? c.declaredChangeClass),
            cell(c.effectiveChangeReason),
            cell(c.poisonPill),
            String(c.violationCount),
            String(c.diagnosticCount),
        ].join(" | ").replace(/^/, "| ").replace(/$/, " |"));
    }

    return lines.join("\n") + "\n";
}

function cell(value: string | undefined): string {
    return (value ?? "").replace(/\\/g, "\\\\").replace(/\|/g, "\\|");
}
