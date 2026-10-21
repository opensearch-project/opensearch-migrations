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

export function workloadSelectorLabels(
    component: Component,
    ownerUid: AllowLiteralOrExpression<string>,
): Record<string, string> {
    return {
        [COMPONENT_LABEL]: component,
        [MIGRATION_RESOURCE_UID_LABEL]: makeStringTypeProxy(ownerUid),
    };
}
