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

function referencedKafkaClusterNames(userConfig: {
    traffic?: {
        proxies?: Record<string, {kafka?: string | null | undefined}>,
        s3Sources?: Record<string, {kafka?: string | null | undefined}>,
    },
}): Set<string> {
    const names = new Set<string>();
    for (const source of Object.values(userConfig.traffic?.proxies ?? {})) {
        names.add(kafkaClusterNameForReference(source));
    }
    for (const source of Object.values(userConfig.traffic?.s3Sources ?? {})) {
        names.add(kafkaClusterNameForReference(source));
    }
    return names;
}

/**
 * Resolve traffic.kafkaClusters, inferring referenced clusters when no map was
 * authored and preserving the implicit "default" beside explicit clusters.
 */
export function resolveKafkaClusters(userConfig: {
    traffic?: {
        kafkaClusters?: Record<string, KafkaClusterConfig>,
        proxies?: Record<string, {kafka?: string | null | undefined}>,
        s3Sources?: Record<string, {kafka?: string | null | undefined}>,
    },
}): Record<string, KafkaClusterConfig> {
    const explicit = userConfig.traffic?.kafkaClusters ?? {};
    const resolved = {...explicit};
    const referenced = referencedKafkaClusterNames(userConfig);
    if (Object.keys(explicit).length === 0) {
        addReferencedKafkaClusters(resolved, Object.values(userConfig.traffic?.proxies ?? {}));
        addReferencedKafkaClusters(resolved, Object.values(userConfig.traffic?.s3Sources ?? {}));
    } else if (
        referenced.has(DEFAULT_KAFKA_CLUSTER_NAME)
        && !(DEFAULT_KAFKA_CLUSTER_NAME in resolved)
    ) {
        resolved[DEFAULT_KAFKA_CLUSTER_NAME] = DEFAULT_AUTO_CREATE_CONFIG;
    }
    return resolved;
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
        const referenced = referencedKafkaClusterNames({
            traffic: {
                proxies: Object.fromEntries(recordEntries(traffic.proxies)),
                s3Sources: Object.fromEntries(recordEntries(traffic.s3Sources)),
            },
        });
        if (
            referenced.has(DEFAULT_KAFKA_CLUSTER_NAME)
            && !explicit.some(([name]) => name === DEFAULT_KAFKA_CLUSTER_NAME)
        ) {
            return [
                ...explicit,
                [
                    DEFAULT_KAFKA_CLUSTER_NAME,
                    structuredClone(DEFAULT_AUTO_CREATE_CONFIG) as Record<string, unknown>,
                ],
            ];
        }
        return explicit;
    }

    const names = referencedKafkaClusterNames({
        traffic: {
            proxies: Object.fromEntries(recordEntries(traffic.proxies).map(
                ([name, proxy]) => [name, {kafka: asString(proxy.kafka)}],
            )),
            s3Sources: Object.fromEntries(recordEntries(traffic.s3Sources).map(
                ([name, source]) => [name, {kafka: asString(source.kafka)}],
            )),
        },
    });
    return [...names].sort().map(name =>
        [name, structuredClone(DEFAULT_AUTO_CREATE_CONFIG) as Record<string, unknown>] as [string, Record<string, unknown>]
    );
}
