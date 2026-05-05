/**
 * ComponentTopologyResolver — resolves a baseline config filename to a
 * ComponentTopology.
 *
 * Current strategy: hard-code topologies for the small set of baseline
 * configs the live runner supports. This keeps unsupported baselines
 * explicit while a topology extractor from generated CRD output is
 * still pending.
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
 * fullMigrationWithTraffic.wf.yaml — expected CRD graph.
 *
 * This topology is hand-authored to match the CRD resources produced
 * by `MigrationInitializer` when applied to the sample YAML at
 * `orchestrationSpecs/packages/config-processor/scripts/samples/
 * fullMigrationWithTraffic.wf.yaml`. The resolver tests pin this by
 * running the actual initializer on the sample and comparing its
 * output to the topology below, so any drift in the generator or
 * sample is caught at CI time.
 *
 * Conventions derived from `MigrationConfigTransformer` and
 * `MigrationInitializer`:
 *   - KafkaCluster name  = `kafkaClusterConfiguration` key
 *   - CapturedTraffic    = `<proxyName>-topic`
 *   - CaptureProxy       = proxy user-config key
 *   - DataSnapshot       = `<sourceClusterName>-<snapshotKey>`
 *   - SnapshotMigration  = `<source>-<target>-<snapshotKey>-migration-<idx>`
 *   - TrafficReplay      = `<fromProxy>-<toTarget>-<replayerKey>`
 *
 * Edge direction (see componentTopology.ts): `{ from, to }` means
 * `from` depends on `to`. This matches `spec.dependsOn` on the CRDs.
 *
 *   KafkaCluster:default
 *     ▲
 *     │
 *   CapturedTraffic:capture-proxy-topic
 *     ▲
 *     │
 *   CaptureProxy:capture-proxy
 *     ▲           ▲
 *     │           │
 *  TrafficReplay   DataSnapshot:source-snap1
 *
 *   SnapshotMigration:source-target-snap1-migration-0
 *     (spec.dependsOn is absent — independent of everything above)
 *
 * Only `SnapshotMigration` is truly independent of the proxy chain.
 * DataSnapshot does **not** gate SnapshotMigration — the migration runs
 * against whatever snapshot exists when it reconciles.
 */
const FULL_MIGRATION_WITH_TRAFFIC: ComponentTopology = buildTopology({
    components: [
        "kafkacluster:default",
        "capturedtraffic:capture-proxy-topic",
        "captureproxy:capture-proxy",
        "datasnapshot:source-snap1",
        "snapshotmigration:source-target-snap1-migration-0",
        "trafficreplay:capture-proxy-target-replay1",
    ] as ComponentId[],
    edges: [
        {
            from: "capturedtraffic:capture-proxy-topic",
            to: "kafkacluster:default",
        },
        {
            from: "captureproxy:capture-proxy",
            to: "capturedtraffic:capture-proxy-topic",
        },
        {
            from: "datasnapshot:source-snap1",
            to: "captureproxy:capture-proxy",
        },
        {
            from: "trafficreplay:capture-proxy-target-replay1",
            to: "captureproxy:capture-proxy",
        },
        // SnapshotMigration has no dependsOn — it is independent.
    ],
});

const BASIC_SNAPSHOT_MIGRATION: ComponentTopology = buildTopology({
    components: [
        "datasnapshot:source-snap1",
        "snapshotmigration:source-target-snap1-migration-0",
    ] as ComponentId[],
    edges: [
        // SnapshotMigration has no dependsOn; it waits for the snapshot through
        // workflow logic rather than a CRD dependency edge.
    ],
});

/**
 * Keyed by the baseline config filename (basename, case-insensitive).
 * The resolver does not read the file contents — only the filename is
 * consulted for the current resolver.
 */
const TOPOLOGIES_BY_BASELINE: ReadonlyMap<string, ComponentTopology> = new Map([
    ["fullmigrationwithtraffic.wf.yaml", FULL_MIGRATION_WITH_TRAFFIC],
    ["fullmigrationwithtraffic.skipapprovals.wf.yaml", FULL_MIGRATION_WITH_TRAFFIC],
    ["basicsnapshotmigration.local.wf.yaml", BASIC_SNAPSHOT_MIGRATION],
]);

/**
 * Resolve a ComponentTopology from a baseline config path. Returns
 * `null` if no hardcoded topology is registered for the given file.
 *
 * Callers should treat a `null` result as "this baseline is not
 * supported yet" — not as an empty topology.
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
