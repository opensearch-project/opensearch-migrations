/**
 * ComponentTopologyResolver — resolves a baseline config filename to a
 * ComponentTopology.
 *
 * First-slice strategy (per implementation plan step 4): hard-code
 * topologies for the small set of baseline configs the live runner
 * currently supports. This keeps the first slice honest about what is
 * and isn't supported while a proper graph extractor can be built
 * afterwards from generated CRD output.
 *
 * Adding a new baseline means registering it in `TOPOLOGIES_BY_BASELINE`.
 */

import * as path from "node:path";

import { ComponentTopology, buildTopology } from "./componentTopology";
import { ComponentId } from "./types";

export class TopologyResolverError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "TopologyResolverError";
    }
}

/**
 * fullMigrationWithTraffic.wf.yaml:
 *
 *   kafkacluster:default
 *         │
 *         ▼
 *   capturedtraffic:source-proxy-topic
 *         │
 *         ▼
 *   captureproxy:source-proxy
 *         │
 *         ▼
 *   trafficreplay:source-proxy-target-target-replay
 *
 *   datasnapshot:source-snap1
 *         │
 *         ▼
 *   snapshotmigration:source-target-snap1-migration-0
 *
 * The proxy/kafka/replay chain and the snapshot/migration chain are
 * independent branches. Names follow the kind:resource-name convention
 * described in the implementation plan; they track the CRDs the
 * migration framework creates for this baseline.
 */
const FULL_MIGRATION_WITH_TRAFFIC: ComponentTopology = buildTopology({
    components: [
        "kafkacluster:default",
        "capturedtraffic:source-proxy-topic",
        "captureproxy:source-proxy",
        "trafficreplay:source-proxy-target-target-replay",
        "datasnapshot:source-snap1",
        "snapshotmigration:source-target-snap1-migration-0",
    ] as ComponentId[],
    edges: [
        { from: "capturedtraffic:source-proxy-topic", to: "kafkacluster:default" },
        { from: "captureproxy:source-proxy", to: "capturedtraffic:source-proxy-topic" },
        { from: "trafficreplay:source-proxy-target-target-replay", to: "captureproxy:source-proxy" },
        { from: "snapshotmigration:source-target-snap1-migration-0", to: "datasnapshot:source-snap1" },
    ],
});

/**
 * Keyed by the baseline config filename (basename, case-insensitive).
 * The resolver does not read the file contents — only the filename is
 * consulted for the first slice.
 */
const TOPOLOGIES_BY_BASELINE: ReadonlyMap<string, ComponentTopology> = new Map([
    ["fullmigrationwithtraffic.wf.yaml", FULL_MIGRATION_WITH_TRAFFIC],
]);

/**
 * Resolve a ComponentTopology from a baseline config path. Returns
 * `null` if no hardcoded topology is registered for the given file.
 *
 * Callers should treat a `null` result as "first slice does not yet
 * support this baseline" — not as an empty topology.
 */
export function resolveTopologyForBaseline(
    baselineConfigPath: string,
): ComponentTopology | null {
    const key = path.basename(baselineConfigPath).toLowerCase();
    return TOPOLOGIES_BY_BASELINE.get(key) ?? null;
}

/**
 * Like `resolveTopologyForBaseline` but throws when the baseline has no
 * registered topology. Use from the live runner which requires one.
 */
export function requireTopologyForBaseline(
    baselineConfigPath: string,
): ComponentTopology {
    const topology = resolveTopologyForBaseline(baselineConfigPath);
    if (!topology) {
        const known = [...TOPOLOGIES_BY_BASELINE.keys()].sort().join(", ");
        throw new TopologyResolverError(
            `No hardcoded topology registered for baseline '${path.basename(baselineConfigPath)}'. ` +
                `Known baselines: ${known || "(none)"}`,
        );
    }
    return topology;
}

/**
 * Exposed for tests so they don't need to import the private constant.
 */
export function registeredBaselines(): readonly string[] {
    return [...TOPOLOGIES_BY_BASELINE.keys()];
}
