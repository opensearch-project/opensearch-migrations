import {
    KAFKA_CLUSTER_CONFIG,
    KAFKA_CLUSTER_CREATION_CONFIG,
} from "@opensearch-migrations/schemas";
import {z} from "zod";

export type KafkaClusterConfig = z.infer<typeof KAFKA_CLUSTER_CONFIG>;
export type WorkflowManagedKafkaClusterConfig = KafkaClusterConfig & {
    autoCreate: z.infer<typeof KAFKA_CLUSTER_CREATION_CONFIG>;
};

export const DEFAULT_KAFKA_CLUSTER_NAME = "default";

/** Kafka version deployed by auto-created clusters. Not user-configurable. */
export const KAFKA_VERSION = "4.0.0";

export const DEFAULT_AUTO_CREATE_CONFIG: KafkaClusterConfig = {autoCreate: {}};

const DEFAULT_WORKFLOW_MANAGED_KAFKA_AUTH = {type: "scram-sha-512" as const};

export function kafkaClusterNameForReference(source: {kafka?: string | null | undefined}): string {
    return source.kafka || DEFAULT_KAFKA_CLUSTER_NAME;
}

export function resolveWorkflowManagedKafkaAuth(cluster: WorkflowManagedKafkaClusterConfig) {
    return cluster.autoCreate.auth ?? DEFAULT_WORKFLOW_MANAGED_KAFKA_AUTH;
}

export function normalizeKafkaClusterConfig(cluster: KafkaClusterConfig): KafkaClusterConfig {
    // Keep the cluster in the user-config schema family while resolving
    // workflow-managed defaults into an explicit canonical form.
    if ("existing" in cluster) {
        return cluster;
    }

    return {
        autoCreate: {
            ...cluster.autoCreate,
            auth: resolveWorkflowManagedKafkaAuth(cluster as WorkflowManagedKafkaClusterConfig),
        },
    };
}

function addReferencedKafkaClusters(
    target: Record<string, KafkaClusterConfig>,
    sources: Array<{kafka?: string | null | undefined}> | undefined,
) {
    for (const source of sources ?? []) {
        const key = kafkaClusterNameForReference(source);
        if (!(key in target)) {
            target[key] = DEFAULT_AUTO_CREATE_CONFIG;
        }
    }
}

/** Resolve traffic.kafkaClusters, auto-injecting autoCreate entries only when no explicit kafka config was provided. */
export function resolveKafkaClusters(userConfig: {
    traffic?: {
        kafkaClusters?: Record<string, KafkaClusterConfig>,
        proxies?: Record<string, {kafka?: string | null | undefined}>,
        s3Sources?: Record<string, {kafka?: string | null | undefined}>,
    },
}): Record<string, KafkaClusterConfig> {
    const explicit = userConfig.traffic?.kafkaClusters ?? {};
    if (Object.keys(explicit).length > 0) {
        return explicit;
    }
    const clusters: Record<string, KafkaClusterConfig> = {};
    addReferencedKafkaClusters(clusters, Object.values(userConfig.traffic?.proxies ?? {}));
    addReferencedKafkaClusters(clusters, Object.values(userConfig.traffic?.s3Sources ?? {}));
    return clusters;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asRecord(value: unknown): Record<string, unknown> {
    return isRecord(value) ? value : {};
}

function recordEntries(value: unknown): [string, Record<string, unknown>][] {
    if (!isRecord(value)) {
        return [];
    }
    return Object.entries(value).flatMap(([key, child]) =>
        isRecord(child) ? [[key, child] as [string, Record<string, unknown>]] : []
    );
}

function asString(value: unknown): string | undefined {
    return typeof value === "string" && value !== "" ? value : undefined;
}

export function looseKafkaEntriesForConfig(config: Record<string, unknown>): [string, Record<string, unknown>][] {
    const traffic = asRecord(config.traffic);
    const explicit = recordEntries(traffic.kafkaClusters);
    if (explicit.length > 0) {
        return explicit;
    }

    const names = new Set<string>();
    for (const [, proxy] of recordEntries(traffic.proxies)) {
        names.add(kafkaClusterNameForReference({kafka: asString(proxy.kafka)}));
    }
    for (const [, s3] of recordEntries(traffic.s3Sources)) {
        names.add(kafkaClusterNameForReference({kafka: asString(s3.kafka)}));
    }
    return [...names].sort().map(name =>
        [name, structuredClone(DEFAULT_AUTO_CREATE_CONFIG) as Record<string, unknown>] as [string, Record<string, unknown>]
    );
}
