/**
 * matrixExpander — expand a spec's matrix selectors into concrete test
 * cases by matching against registered mutators.
 *
 * This is the minimal slice needed for the safe mutation runner. Full
 * multi-selector, multi-response expansion arrives in plan step 7.
 */

import { ComponentId, MatrixSelector, Response, ScenarioSpec } from "./types";
import { Mutator, MutatorRegistry } from "./fixtures/mutators";

export interface ExpandedTestCase {
    caseName: string;
    subject: ComponentId;
    mutator: Mutator;
    /** null for safe cases — no response action needed. */
    response: Response | null;
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
                const caseName = `${mutator.subject.replace(/:/g, "-")}-${pattern}-${mutator.name}`;
                cases.push({
                    caseName,
                    subject,
                    mutator,
                    // Safe selectors have no response; gated/impossible
                    // will carry sel.response once those slices land.
                    response: sel.response ?? null,
                });
            }
        }
    }

    return cases;
}
