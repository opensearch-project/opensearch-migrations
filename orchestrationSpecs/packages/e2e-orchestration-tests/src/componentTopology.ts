/**
 * ComponentTopology — pure dependency-graph model of a migration config.
 *
 * The topology knows which components exist and which component depends
 * on which. It has no opinion on mutators, checkpoints, or change
 * classes; those live upstream in assertLogic. Helpers on the topology
 * answer the three questions assertLogic needs:
 *
 *   downstreamOf(subject)  — all components transitively depending on subject
 *   upstreamOf(subject)    — all components subject transitively depends on
 *   independentOf(subject) — all components with no path to/from subject
 *
 * For the first implementation slice we build `ComponentTopology` from
 * hand-authored data for a specific baseline config (see
 * `componentTopologyResolver.ts`). Later slices can synthesise it from
 * a generated CRD graph.
 */

import { ComponentId } from "./types";

export interface ComponentEdge {
    /** The component that depends on the target. */
    from: ComponentId;
    /** The component being depended on. */
    to: ComponentId;
}

export interface ComponentTopology {
    components: readonly ComponentId[];
    /** Directed edges: `from` depends on `to`. */
    edges: readonly ComponentEdge[];
    /** Transitive downstream closure (from → direct and indirect dependents). */
    downstreamOf(subject: ComponentId): ReadonlySet<ComponentId>;
    /** Transitive upstream closure (from → direct and indirect prerequisites). */
    upstreamOf(subject: ComponentId): ReadonlySet<ComponentId>;
    /** Components with no path to or from `subject`. */
    independentOf(subject: ComponentId): ReadonlySet<ComponentId>;
    has(component: ComponentId): boolean;
}

export interface BuildTopologyInput {
    components: readonly ComponentId[];
    edges: readonly ComponentEdge[];
}

export class TopologyError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "TopologyError";
    }
}

/**
 * Build a ComponentTopology from a list of components and edges.
 *
 * Validates:
 *   - every edge endpoint is a known component
 *   - no self-loops
 *   - no cycles (dependency graphs must be acyclic — if we hit a cycle
 *     it's a topology resolver bug)
 */
export function buildTopology(input: BuildTopologyInput): ComponentTopology {
    const componentSet = new Set(input.components);
    for (const e of input.edges) {
        if (!componentSet.has(e.from)) {
            throw new TopologyError(`edge 'from' refers to unknown component: ${e.from}`);
        }
        if (!componentSet.has(e.to)) {
            throw new TopologyError(`edge 'to' refers to unknown component: ${e.to}`);
        }
        if (e.from === e.to) {
            throw new TopologyError(`self-loop edge on component: ${e.from}`);
        }
    }

    // upstreamMap: component → its direct prerequisites (from → to).
    // downstreamMap: component → its direct dependents (reverse).
    const upstreamMap = new Map<ComponentId, Set<ComponentId>>();
    const downstreamMap = new Map<ComponentId, Set<ComponentId>>();
    for (const c of input.components) {
        upstreamMap.set(c, new Set());
        downstreamMap.set(c, new Set());
    }
    for (const e of input.edges) {
        upstreamMap.get(e.from)!.add(e.to);
        downstreamMap.get(e.to)!.add(e.from);
    }

    detectCycle(input.components, upstreamMap);

    function bfs(start: ComponentId, adj: Map<ComponentId, Set<ComponentId>>): Set<ComponentId> {
        const out = new Set<ComponentId>();
        const queue: ComponentId[] = [start];
        while (queue.length > 0) {
            const cur = queue.shift()!;
            for (const next of adj.get(cur) ?? []) {
                if (!out.has(next)) {
                    out.add(next);
                    queue.push(next);
                }
            }
        }
        return out;
    }

    const downstreamCache = new Map<ComponentId, Set<ComponentId>>();
    const upstreamCache = new Map<ComponentId, Set<ComponentId>>();

    return {
        components: input.components,
        edges: input.edges,
        has: (c) => componentSet.has(c),
        downstreamOf(subject) {
            if (!componentSet.has(subject)) {
                throw new TopologyError(`unknown component: ${subject}`);
            }
            const hit = downstreamCache.get(subject);
            if (hit) return hit;
            const set = bfs(subject, downstreamMap);
            downstreamCache.set(subject, set);
            return set;
        },
        upstreamOf(subject) {
            if (!componentSet.has(subject)) {
                throw new TopologyError(`unknown component: ${subject}`);
            }
            const hit = upstreamCache.get(subject);
            if (hit) return hit;
            const set = bfs(subject, upstreamMap);
            upstreamCache.set(subject, set);
            return set;
        },
        independentOf(subject) {
            if (!componentSet.has(subject)) {
                throw new TopologyError(`unknown component: ${subject}`);
            }
            const down = this.downstreamOf(subject);
            const up = this.upstreamOf(subject);
            const result = new Set<ComponentId>();
            for (const c of input.components) {
                if (c !== subject && !down.has(c) && !up.has(c)) {
                    result.add(c);
                }
            }
            return result;
        },
    };
}

function detectCycle(
    components: readonly ComponentId[],
    upstreamMap: Map<ComponentId, Set<ComponentId>>,
): void {
    const WHITE = 0;
    const GRAY = 1;
    const BLACK = 2;
    const colour = new Map<ComponentId, number>();
    for (const c of components) colour.set(c, WHITE);

    function visit(c: ComponentId, stack: ComponentId[]): void {
        const col = colour.get(c);
        if (col === GRAY) {
            const cycle = [...stack.slice(stack.indexOf(c)), c].join(" → ");
            throw new TopologyError(`cycle in component topology: ${cycle}`);
        }
        if (col === BLACK) return;
        colour.set(c, GRAY);
        stack.push(c);
        for (const next of upstreamMap.get(c) ?? []) {
            visit(next, stack);
        }
        stack.pop();
        colour.set(c, BLACK);
    }

    for (const c of components) {
        if (colour.get(c) === WHITE) visit(c, []);
    }
}
