/**
 * Derived Subgraph
 *
 * Given a checksum report (the full dependency graph of all components) and a
 * "focus" component, this module classifies every other component into one of
 * four buckets:
 *
 *   - immediateDependents: components that directly depend on the focus
 *     (e.g. snapshot depends on proxy, replayer depends on proxy)
 *
 *   - transitiveDependents: components that depend on the focus indirectly
 *     through one or more intermediate components
 *     (e.g. snapshotMigration depends on snapshot, which depends on proxy)
 *
 *   - upstreamPrerequisites: components that the focus itself depends on
 *     (e.g. proxy depends on kafka)
 *     These should NOT rerun when the focus changes — they're above it in the DAG.
 *
 *   - independent: components with no dependency relationship to the focus
 *     in either direction. These should never be affected by changes to the focus.
 *
 * This classification drives the matrix expander's expectations:
 *   - A focus-change should rerun the focus + all immediate + transitive dependents
 *   - An immediate-dependent-change should rerun only that dependent + its own dependents
 *   - Upstream prerequisites and independent components should always be unchanged
 *
 * Example for focus=proxy:capture-proxy in fullMigrationWithTraffic:
 *
 *   kafka:default                    → upstreamPrerequisite
 *   proxy:capture-proxy              → focus
 *   snapshot:source-snap1            → immediateDependent
 *   snapshotMigration:source-target-snap1 → transitiveDependent (via snapshot)
 *   replay:capture-proxy-target-replay1   → immediateDependent
 */
import type { ChecksumReport, DerivedSubgraph } from './types';

export function deriveSubgraph(report: ChecksumReport, focus: string): DerivedSubgraph {
    if (!(focus in report.components)) {
        throw new Error(`Focus component '${focus}' not found in report`);
    }

    // Build reverse dependency map: for each component, which components depend on it?
    // The forward edges in the report are "A dependsOn B" (A needs B).
    // The reverse map gives us "B → [A]" (B is needed by A).
    const reverseDeps = new Map<string, string[]>();
    for (const [key, entry] of Object.entries(report.components)) {
        for (const dep of entry.dependsOn) {
            const existing = reverseDeps.get(dep) ?? [];
            existing.push(key);
            reverseDeps.set(dep, existing);
        }
    }

    // Immediate dependents: components that directly list the focus in their dependsOn
    const immediateDependents = reverseDeps.get(focus) ?? [];

    // Transitive dependents: BFS outward from immediate dependents through the reverse map
    const transitiveSet = new Set<string>();
    const queue = [...immediateDependents];
    while (queue.length > 0) {
        const current = queue.shift()!;
        for (const next of reverseDeps.get(current) ?? []) {
            if (!immediateDependents.includes(next) && !transitiveSet.has(next)) {
                transitiveSet.add(next);
                queue.push(next);
            }
        }
    }

    // Upstream prerequisites: BFS upward through the forward dependsOn edges
    const upstreamSet = new Set<string>();
    const upstreamQueue = [...report.components[focus].dependsOn];
    while (upstreamQueue.length > 0) {
        const current = upstreamQueue.shift()!;
        if (!upstreamSet.has(current)) {
            upstreamSet.add(current);
            const entry = report.components[current];
            if (entry) {
                upstreamQueue.push(...entry.dependsOn);
            }
        }
    }

    // Independent: everything not in any of the above sets
    const allRelated = new Set([focus, ...immediateDependents, ...transitiveSet, ...upstreamSet]);
    const independent = Object.keys(report.components).filter(k => !allRelated.has(k));

    return {
        focus,
        immediateDependents: [...immediateDependents].sort(),
        transitiveDependents: [...transitiveSet].sort(),
        upstreamPrerequisites: [...upstreamSet].sort(),
        independent: independent.sort(),
    };
}
