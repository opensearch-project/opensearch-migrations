/* Generated Strimzi type definitions — do not edit manually, run `npm run rebuild` */
// Run `npm run rebuild` with a cluster that has Strimzi installed to regenerate.

export interface KafkaSpec {
    kafka: {
        version?: string;
        metadataVersion?: string;
        replicas?: number;
        listeners: Array<{
            name: string;
            port: number;
            type: string;
            tls: boolean;
        }>;
        config?: Record<string, unknown>;
        storage?: { type: string; [key: string]: unknown };
        readinessProbe?: Record<string, unknown>;
        livenessProbe?: Record<string, unknown>;
    };
    zookeeper?: {
        replicas: number;
        storage: { type: string; [key: string]: unknown };
    };
    entityOperator?: {
        topicOperator?: Record<string, unknown>;
        userOperator?: Record<string, unknown>;
    };
}

export interface Kafka {
    apiVersion?: string;
    kind?: string;
    metadata?: { name?: unknown; annotations?: Record<string, unknown>; [key: string]: unknown };
    spec: KafkaSpec;
    status?: { listeners?: Array<{ name: string; bootstrapServers: string }>; [key: string]: unknown };
}

export interface KafkaNodePool {
    apiVersion?: string;
    kind?: string;
    metadata?: { name?: unknown; labels?: Record<string, unknown>; [key: string]: unknown };
    spec: {
        replicas: number;
        roles: string[];
        storage: { type: string; volumes?: unknown[]; [key: string]: unknown };
    };
}

export interface KafkaTopic {
    apiVersion?: string;
    kind?: string;
    metadata?: { name?: unknown; labels?: Record<string, unknown>; [key: string]: unknown };
    spec: {
        partitions: unknown;
        replicas: unknown;
        config?: Record<string, unknown>;
    };
    status?: { topicName?: string; [key: string]: unknown };
}
