/**
 * matrixExpander — expand a spec's matrix selectors into concrete test
 * cases by matching against registered mutators.
 *
 * This is the matrix expansion needed by the current safe mutation
 * runner. Gated/impossible responses will add more case-plan shapes.
 */

import {
    ComponentId,
    MatrixSelector,
    Response,
    ScenarioSpec,
    SubjectStateAtMutation,
} from "./types";
import { Mutator, MutatorRegistry } from "./fixtures/mutators";

export interface ExpandedTestCase {
    caseName: string;
    subject: ComponentId;
    mutator: Mutator;
    /**
     * Materiality-aware expected rerun set for mutation checkpoints.
     * Topology still classifies upstream/downstream/independent
     * relationships, but this set controls which components should
     * actually re-run for the changed paths.
     */
    expectedRerunComponents: readonly ComponentId[];
    changedPaths: readonly string[];
    /** null for safe cases — no response action needed. */
    response: Response | null;
    /**
     * Whether the mutation is submitted after the subject has already
     * completed, or while a poison pill is holding it short of
     * completion.
     */
    subjectStateAtMutation: SubjectStateAtMutation;
    /** fixtures.poisonPills.byName key used for in-progress cases. */
    poisonPillName?: string;
}

/**
 * Expand the spec's matrix into concrete test cases. Each selector
 * is matched against the registry; each matching mutator produces one
 * case per (pattern, mutator) pair.
 *
 * If `spec.matrix.select` is absent, a default safe selector with
 * `subject-change` is used.
 */
export function expandCases(
    spec: ScenarioSpec,
    mutatorRegistry: MutatorRegistry,
): ExpandedTestCase[] {
    const subject = spec.matrix.subject as ComponentId;
    const selectors: MatrixSelector[] = spec.matrix.select ?? [
        { changeClass: "safe", patterns: ["subject-change"] },
    ];

    const cases: ExpandedTestCase[] = [];

    for (const sel of selectors) {
        const subjectStates = subjectStatesForSelector(sel);
        for (const pattern of sel.patterns) {
            const matches = mutatorRegistry.findBySubjectAndSelector(subject, {
                changeClass: sel.changeClass,
                pattern,
            });
            if (matches.length === 0) {
                throw new Error(
                    `matrix selector {changeClass: '${sel.changeClass}', pattern: '${pattern}'} ` +
                    `matched zero mutators for subject '${subject}' — register a mutator or narrow the selector`,
                );
            }
            for (const mutator of matches) {
                for (const subjectStateAtMutation of subjectStates) {
                    if (subjectStateAtMutation === "in-progress") {
                        validatePoisonPillSelection(spec, sel, subject);
                    }
                    const stateSuffix =
                        subjectStateAtMutation === "completed"
                            ? ""
                            : `-${subjectStateAtMutation}-${sanitizeCaseNameToken(sel.poisonPill!)}`;
                    const caseName =
                        `${mutator.subject.replace(/:/g, "-")}-${pattern}-${mutator.name}${stateSuffix}`;
                    cases.push({
                        caseName,
                        subject,
                        mutator,
                        expectedRerunComponents: mutator.expectedRerunComponents ?? [mutator.subject],
                        changedPaths: mutator.changedPaths,
                        // Safe selectors have no response; gated/impossible
                        // case plans will use sel.response.
                        response: sel.response ?? null,
                        subjectStateAtMutation,
                        poisonPillName:
                            subjectStateAtMutation === "in-progress"
                                ? sel.poisonPill
                                : undefined,
                    });
                }
            }
        }
    }

    return cases;
}

function subjectStatesForSelector(sel: MatrixSelector): SubjectStateAtMutation[] {
    if (sel.subjectStates) return [...sel.subjectStates];
    return sel.poisonPill ? ["in-progress"] : ["completed"];
}

function validatePoisonPillSelection(
    spec: ScenarioSpec,
    sel: MatrixSelector,
    subject: ComponentId,
): void {
    const name = sel.poisonPill;
    if (!name) {
        throw new Error(
            `matrix selector for '${subject}' requested in-progress state but did not set poisonPill`,
        );
    }
    const poisonPill = spec.fixtures.poisonPills?.byName[name];
    if (!poisonPill) {
        throw new Error(
            `matrix selector for '${subject}' references unknown poisonPill '${name}'`,
        );
    }
    if (poisonPill.subject !== subject) {
        throw new Error(
            `poisonPill '${name}' targets '${poisonPill.subject}', not matrix subject '${subject}'`,
        );
    }
}

function sanitizeCaseNameToken(value: string): string {
    return value.replace(/[^a-zA-Z0-9._-]/g, "_");
}
