import {z} from "zod";
import {transformZodObjectToParams} from "@/utils";

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
    name: z.string(),
    endpoint: z.string().optional(),
    allow_insecure: z.boolean().optional(),
    version: z.string().optional(),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional(),
});

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    endpoint: z.string(), // override to required
});

export const UNKNOWN = z.object({});

export const S3_REPO_CONFIG = z.object({
    aws_region: z.string(),
    endpoint: z.string(),
    repoPath: z.string()
});

export const DYNAMIC_SNAPSHOT_CONFIG = z.object({
    repoConfig: S3_REPO_CONFIG,
    snapshotName: z.string().optional()
});

export const COMPLETE_SNAPSHOT_CONFIG = DYNAMIC_SNAPSHOT_CONFIG.extend({
    snapshotName: z.string() // override to required
});

export const METADATA_OPTIONS = z.object({

});

export const RFS_OPTIONS = z.object({
    requiredThing: z.number(),
    loggingConfigurationOverrideConfigMap: z.string().default("default-log4j-config"),
    allowLooseVersionMatching: z.boolean().default(true).describe(""),
    docTransformerBase64: z.string().default("not a transform"),
    documentsPerBulkRequest: z.number().default(0),
    indexAllowlist: z.string().default(""),
    initialLeaseDuration: z.string().default(""),
    maxConnections: z.number().default(0),
    maxShardSizeBytes: z.number().default(0),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
    targetCompression: z.boolean().default(true)
});

export const PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    metadata: z.object({
        indices: z.array(z.string()),
        options: METADATA_OPTIONS
    }),
    documentBackfillConfigs: z.object({
        indices: z.array(z.string()),
        options: RFS_OPTIONS
    })
});

export const SNAPSHOT_MIGRATION_CONFIG = z.object({
    indices: z.array(z.string()),
    migrations: z.array(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG),
    snapshotConfig: DYNAMIC_SNAPSHOT_CONFIG
});

export const REPLAYER_OPTIONS = z.object({
    speedupFactor: z.number(),
    podReplicas: z.number(),
    authHeaderOverride: z.optional(z.string()),
    loggingConfigurationOverrideConfigMap: z.string().default("default-log4j-config"),
    docTransformerBase64: z.string().default("not a transform"),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
});

export const SOURCE_MIGRATION_CONFIG = z.object({
    source: CLUSTER_CONFIG,
    snapshotExtractAndLoadConfigs: z.array(SNAPSHOT_MIGRATION_CONFIG),
    replayerConfig: REPLAYER_OPTIONS,
});

export const CONSOLE_SERVICES_CONFIG_FILE = z.object({
    kafka: z.string(), // TODO
    source_cluster: CLUSTER_CONFIG,
    snapshot: z.string(), // TODO
    target_cluster: TARGET_CLUSTER_CONFIG
});

export const OVERALL_MIGRATION_CONFIG = z.object({
    targets: z.array(TARGET_CLUSTER_CONFIG),
    sourceMigrationConfigs: SOURCE_MIGRATION_CONFIG
});
