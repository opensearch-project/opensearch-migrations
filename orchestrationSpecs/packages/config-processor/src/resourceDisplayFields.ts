import {collectProjectedFields} from "@opensearch-migrations/schemas";

const DISPLAY_FIELD_EXCLUSIONS = new Set(["dependsOn"]);

export const CLUSTER_CLIENT_DISPLAY_FIELDS = [
    "endpoint",
    "allow_insecure",
    "version",
    "basic_auth.k8s_secret_name",
    "sigv4.region",
    "sigv4.service",
    "mtls_auth.certName",
];

export const KAFKA_CONFIG_DISPLAY_FIELDS = [
    "type",
    "clusterName",
    "authType",
    "listenerName",
];

export const PROJECTED_RESOURCE_DISPLAY_FIELDS: Record<string, string[]> = {
    KafkaCluster: ["version", "auth.type", "nodePool.replicas"],
    CapturedTraffic: ["topicName", "partitions", "replicas"],
    CaptureProxy: ["podReplicas", "listenPort", "internetFacing", "serviceType"],
    DataSnapshot: ["snapshotPrefix", "indexAllowlist"],
    SnapshotMigration: [
        "documentBackfillPodReplicas",
        "sourceVersion",
        "documentBackfillIndexAllowlist",
        "metadataMigrationIndexAllowlist",
        "metadataMigrationMultiTypeBehavior",
    ],
    TrafficReplay: ["podReplicas", "speedupFactor", "removeAuthHeader"],
};

function hasPath(source: Record<string, unknown>, path: string[]): boolean {
    let cursor: unknown = source;
    for (const key of path) {
        if (typeof cursor !== "object" || cursor === null || Array.isArray(cursor) || !(key in cursor)) {
            return false;
        }
        cursor = (cursor as Record<string, unknown>)[key];
    }
    return true;
}

export function displayFieldsForProjectedKind(kind: string, parameters: Record<string, unknown>): string[] | undefined {
    const curatedFields = PROJECTED_RESOURCE_DISPLAY_FIELDS[kind];
    if (curatedFields) {
        const fields = curatedFields.filter(field => hasPath(parameters, field.split(".")));
        return fields.length > 0 ? fields : undefined;
    }
    const fields = collectProjectedFields()
        .filter(field => field.resourceKind === kind && hasPath(parameters, field.specPath))
        .map(field => field.specPath.join("."))
        .filter(field => !DISPLAY_FIELD_EXCLUSIONS.has(field));
    return fields.length > 0 ? [...new Set(fields)] : undefined;
}
