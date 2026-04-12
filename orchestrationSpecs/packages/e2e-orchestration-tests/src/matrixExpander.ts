import type {
    ApprovedMutator, ChecksumReport, DependencyPattern,
    ExpandedExpectation, ExpandedTestCase, MatrixSpec,
} from './types';
import { buildChecksumReport } from './checksumReporter';
import { deriveSubgraph } from './derivedSubgraph';
import { findMutators } from './approvedMutators';

/**
 * Resolve which component a dependency pattern targets, given the focus subgraph.
 * Returns the component key that the mutator should be changing.
 */
function resolvePatternTarget(
    pattern: DependencyPattern,
    focus: string,
    subgraph: ReturnType<typeof deriveSubgraph>,
): string | undefined {
    switch (pattern) {
        case 'focus-change':
        case 'focus-gated-change':
            return focus;
        case 'immediate-dependent-change':
        case 'immediate-dependent-gated-change':
        case 'immediate-dependent-impossible-change':
            return subgraph.immediateDependents[0];
        case 'transitive-dependent-change':
            return subgraph.transitiveDependents[0];
    }
}

/**
 * Build expectations from the subgraph topology and checksum diffs.
 * The key insight: we use the subgraph to determine WHICH components SHOULD
 * be affected, then verify the checksum diffs match that expectation.
 */
function buildExpectation(
    pattern: DependencyPattern,
    focus: string,
    baseReport: ChecksumReport,
    mutatedReport: ChecksumReport,
): ExpandedExpectation {
    const subgraph = deriveSubgraph(baseReport, focus);
    const target = resolvePatternTarget(pattern, focus, subgraph);

    // Determine which components should rerun based on topology:
    // The target component and all of its downstream dependents should rerun.
    const targetSubgraph = target ? deriveSubgraph(baseReport, target) : undefined;
    const shouldRerun = new Set<string>();
    if (target) {
        shouldRerun.add(target);
        if (targetSubgraph) {
            for (const dep of targetSubgraph.immediateDependents) shouldRerun.add(dep);
            for (const dep of targetSubgraph.transitiveDependents) shouldRerun.add(dep);
        }
    }

    // Verify checksum diffs match topology expectations
    const reran: string[] = [];
    const unchanged: string[] = [];
    const blockedOn: Record<string, string[]> = {};

    for (const key of Object.keys(baseReport.components)) {
        const baseChecksum = baseReport.components[key].configChecksum;
        const mutatedChecksum = mutatedReport.components[key]?.configChecksum;
        const checksumChanged = baseChecksum !== mutatedChecksum;

        if (shouldRerun.has(key)) {
            // Topology says this should rerun — verify checksum actually changed
            if (checksumChanged) {
                reran.push(key);
            } else {
                // Topology says rerun but checksum didn't change — still mark as unchanged
                // This catches mismatches between mutator tags and actual behavior
                unchanged.push(key);
            }
        } else {
            unchanged.push(key);
        }
    }

    // Build blockedOn from dependency edges within the reran set
    for (const key of reran) {
        const deps = baseReport.components[key].dependsOn.filter(d => reran.includes(d));
        if (deps.length > 0) {
            blockedOn[key] = deps;
        }
    }

    return {
        reran: reran.sort(),
        unchanged: unchanged.sort(),
        ...(Object.keys(blockedOn).length > 0 ? { blockedOn } : {}),
    };
}

export async function expandMatrix(
    spec: MatrixSpec,
    report: ChecksumReport,
    mutators: ApprovedMutator[],
    baseConfig: Record<string, unknown>,
    baseReport: ChecksumReport,
): Promise<ExpandedTestCase[]> {
    const cases: ExpandedTestCase[] = [];
    const subgraph = deriveSubgraph(report, spec.focus);

    for (const selector of spec.select) {
        // Collect all patterns that have no matching mutators (for coverage enforcement)
        const uncoveredPatterns: DependencyPattern[] = [];

        for (const pattern of selector.patterns) {
            const target = resolvePatternTarget(pattern, spec.focus, subgraph);
            if (!target) continue;

            const matching = findMutators(mutators, selector.changeClass, pattern);

            if (matching.length === 0) {
                uncoveredPatterns.push(pattern);
                continue;
            }

            for (const mutator of matching) {
                const mutatedConfig = mutator.apply(baseConfig);
                const mutatedReport = await buildChecksumReport(mutatedConfig);

                // Validate that the mutator actually changes the resolved target component
                // and does NOT change components above it in the dependency chain.
                if (target) {
                    const targetChanged = baseReport.components[target].configChecksum
                        !== mutatedReport.components[target]?.configChecksum;
                    if (!targetChanged) {
                        throw new Error(
                            `Mutator '${mutator.id}' is tagged for pattern '${pattern}' ` +
                            `(target: ${target}) but did not change that component's checksum. ` +
                            `Check that the mutator's path corresponds to the resolved target.`
                        );
                    }

                    // Verify no component above the target in the focus subgraph changed
                    const targetSubgraph = deriveSubgraph(baseReport, target);
                    for (const upstream of targetSubgraph.upstreamPrerequisites) {
                        const upstreamChanged = baseReport.components[upstream].configChecksum
                            !== mutatedReport.components[upstream]?.configChecksum;
                        if (upstreamChanged) {
                            throw new Error(
                                `Mutator '${mutator.id}' is tagged for pattern '${pattern}' ` +
                                `(target: ${target}) but also changed upstream component '${upstream}'. ` +
                                `This mutator is mis-tagged — it should target a higher-level pattern.`
                            );
                        }
                    }
                }

                const expectation = buildExpectation(pattern, spec.focus, baseReport, mutatedReport);

                cases.push({
                    name: `${spec.focus}/${pattern}/${mutator.id}`,
                    focus: spec.focus,
                    pattern,
                    changeClass: selector.changeClass,
                    mutatorId: mutator.id,
                    baselineChecksumReport: baseReport,
                    mutatedChecksumReport: mutatedReport,
                    expect: expectation,
                });
            }
        }

        // Enforce requireFullCoverage
        if (selector.requireFullCoverage && uncoveredPatterns.length > 0) {
            throw new Error(
                `requireFullCoverage: no approved mutators for patterns: ${uncoveredPatterns.join(', ')} ` +
                `(changeClass: ${selector.changeClass}, focus: ${spec.focus})`
            );
        }
    }

    return cases;
}
