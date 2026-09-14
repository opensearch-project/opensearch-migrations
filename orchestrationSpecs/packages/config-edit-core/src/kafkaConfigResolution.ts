import {
    KAFKA_CLUSTER_CONFIG,
    KAFKA_CLUSTER_CREATION_CONFIG,
} from "@opensearch-migrations/schemas/browser";
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
    const name = source.kafka?.trim();
    if (!name) {
        throw new Error("Kafka cluster reference is required");
    }
    return name;
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
        ...cluster,
        autoCreate: {
            ...cluster.autoCreate,
            auth: resolveWorkflowManagedKafkaAuth(cluster as WorkflowManagedKafkaClusterConfig),
        },
    };
}

/**
 * Return the explicitly authored Kafka clusters. References never synthesize
 * hidden cluster definitions; every deployable cluster has a YAML entry.
 */
export function resolveKafkaClusters(userConfig: {
    traffic?: {
        kafkaClusters?: Record<string, KafkaClusterConfig>,
        proxies?: Record<string, {kafka?: string | null | undefined}>,
        s3Sources?: Record<string, {kafka?: string | null | undefined}>,
    },
}): Record<string, KafkaClusterConfig> {
    return {...(userConfig.traffic?.kafkaClusters ?? {})};
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

export function looseKafkaEntriesForConfig(config: Record<string, unknown>): [string, Record<string, unknown>][] {
    const traffic = asRecord(config.traffic);
    return recordEntries(traffic.kafkaClusters);
}
