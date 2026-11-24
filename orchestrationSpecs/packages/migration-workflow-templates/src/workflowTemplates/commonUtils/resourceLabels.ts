import {AllowLiteralOrExpression, makeStringTypeProxy} from "@opensearch-migrations/argo-workflow-builders";

// See docs/kubernetesLabels.md for how these labels are used and the rules for new pods.
export const MIGRATION_RESOURCE_UID_LABEL =
    "migrations.opensearch.org/migration-resource-uid";
export const COMPONENT_LABEL = "migrations.opensearch.org/component";

// The piece of a task that a pod runs. Each value is unique within its owning migration CR.
export const COMPONENTS = {
    CAPTURE_PROXY: "capture-proxy",
    TRAFFIC_REPLAYER: "traffic-replayer",
    RFS_WORKER: "rfs-worker",
    RFS_COORDINATOR: "rfs-coordinator",
    RFS_MONITOR: "rfs-monitor",
    METADATA_MIGRATION: "metadata-migration",
    SNAPSHOT_CREATE: "snapshot-create",
    SNAPSHOT_MONITOR: "snapshot-monitor",
    S3_TRAFFIC_LOADER: "s3-traffic-loader",
} as const;
export type Component = typeof COMPONENTS[keyof typeof COMPONENTS];

// Every Deployment, StatefulSet, Service and PDB selects on exactly these labels, and every
// zone-spread constraint uses them as matchLabelKeys. Selectors can't be changed in place.
export const WORKLOAD_SELECTOR_LABEL_KEYS = [COMPONENT_LABEL, MIGRATION_RESOURCE_UID_LABEL];

// The RFS memory tier a fleet runs at. When memory-tier scaling is enabled, each tier gets its
// own Deployment and this label joins the IMMUTABLE spec.selector (plus the pod-template labels
// and each tier PDB's selector): component + UID without the tier selects the whole RFS set,
// adding the tier selects one fleet. Copying the two-label selector across tier Deployments
// would give multiple controllers overlapping selectors, which Kubernetes warns fight over
// each other's pods.
export const RFS_MEMORY_TIER_LABEL = "migrations.opensearch.org/rfs-memory-tier";

export function workloadSelectorLabels(
    component: Component,
    ownerUid: AllowLiteralOrExpression<string>,
): Record<string, string> {
    return {
        [COMPONENT_LABEL]: component,
        [MIGRATION_RESOURCE_UID_LABEL]: makeStringTypeProxy(ownerUid),
    };
}
