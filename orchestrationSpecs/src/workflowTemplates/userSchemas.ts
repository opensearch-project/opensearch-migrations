import {z} from "zod";

export const KAFKA_SERVICES_CONFIG = z.object({
    broker_endpoints: z.string(),
    standard: z.string()
});

export const HTTP_AUTH_BASIC = z.object({
    username: z.string(),
    password: z.string(),
});

export const HTTP_AUTH_SIGV4 = z.object({
    region: z.string(),
    service: z.string().default("es").optional(),
});

export const HTTP_AUTH_MTLS = z.object({
    caCert: z.string(),
    clientSecretName: z.string()
});

export const CLUSTER_CONFIG = z.object({
    endpoint: z.string().optional(),
    allow_insecure: z.boolean().optional(),
    version: z.string().optional(),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional(),
});

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    endpoint: z.string(), // override to required
});

export const S3_REPO_CONFIG = z.object({
    aws_region: z.string(),
    endpoint: z.string(),
    repoPath: z.string()
});

export const NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG = z.object({
    repoConfigName: z.string(),
    snapshotName: z.string().optional()
});

export const NORMALIZED_COMPLETE_SNAPSHOT_CONFIG = NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG.extend({
    snapshotName: z.string() // override to required
});

export const METADATA_OPTIONS = z.object({
    enabled: z.boolean(),

    componentTemplateAllowlist: z.array(z.string()).optional(),
    indexAllowlist: z.array(z.string()).optional(),
    indexTemplateAllowlist: z.array(z.string()).optional(),

    allowLooseVersionMatching: z.boolean().optional(),
    clusterAwarenessAttributes: z.number().optional(),
    disableCompression: z.boolean().optional(),
    loggingConfigurationOverrideConfigMap: z.string().default(""),
    multiTypeBehavior: z.union(["NONE", "UNION", "SPLIT"].map(s=>z.literal(s))).optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
    output: z.union(["HUMAN_READABLE", "JSON"].map(s=>z.literal(s))).optional(),
    transformerBase64: z.string().default(""),
});

export const RFS_OPTIONS = z.object({
    enabled: z.boolean(),

    indexAllowlist: z.array(z.string()).optional(),

    loggingConfigurationOverrideConfigMap: z.string().default(""),
    allowLooseVersionMatching: z.boolean().default(true).describe(""),
    docTransformerBase64: z.string().default(""),
    documentsPerBulkRequest: z.number().default(0),
    initialLeaseDuration: z.string().default(""),
    maxConnections: z.number().default(0),
    maxShardSizeBytes: z.number().default(0),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
    targetCompression: z.boolean().default(true),
});

export const PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    metadataConfig: METADATA_OPTIONS.optional(),
    documentBackfillConfig: RFS_OPTIONS.optional()
});

export const NORMALIZED_SNAPSHOT_MIGRATION_CONFIG = z.object({
    indices: z.array(z.string()),
    snapshotConfig: NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG,
    migrations: z.array(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)
});

export const REPLAYER_OPTIONS = z.object({
    enabled: z.boolean(),
    speedupFactor: z.number(),
    podReplicas: z.number(),
    authHeaderOverride: z.optional(z.string()),
    loggingConfigurationOverrideConfigMap: z.string().default(""),
    docTransformerBase64: z.string().default(""),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
});

export const NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG = z.object({
    sources: z.array(z.string()),
    targets: z.array(z.string()),
    snapshotExtractAndLoadConfigs: z.array(NORMALIZED_SNAPSHOT_MIGRATION_CONFIG),
    replayerConfig: REPLAYER_OPTIONS,
});

export const CONSOLE_SERVICES_CONFIG_FILE = z.object({
    kafka: KAFKA_SERVICES_CONFIG.optional(),
    source_cluster: CLUSTER_CONFIG.optional(),
    snapshot: NORMALIZED_COMPLETE_SNAPSHOT_CONFIG.optional(),
    target_cluster: TARGET_CLUSTER_CONFIG.optional()
});

export const SOURCE_CLUSTERS_MAP = z.record(z.string(), CLUSTER_CONFIG);
export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG);
export const REPO_CONFIGS_MAP = z.record(z.string(), S3_REPO_CONFIG);

export const OVERALL_MIGRATION_CONFIG = z.object({
    sourceClusters: SOURCE_CLUSTERS_MAP,
    targetClusters: TARGET_CLUSTERS_MAP,
    migrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG),
    repoConfigs: REPO_CONFIGS_MAP
});
