import {z} from "zod";
import { DEFAULT_RESOURCES, parseK8sQuantity } from "./schemaUtilities";
import deepmerge from "deepmerge";

export function getZodKeys<T extends z.ZodRawShape>(schema: z.ZodObject<T>): readonly (keyof T)[] {
    return Object.keys(schema.shape) as (keyof T)[];
}

export const KAFKA_SERVICES_CONFIG = z.object({
    brokerEndpoints: z.string().describe("Specify an external kafka broker list if using one other than the one managed by the workflow"),
    standard: z.string()
});

export const S3_REPO_CONFIG = z.object({
    awsRegion: z.string().describe("The AWS region that the bucket reside in (us-east-2, etc)"),
    endpoint: z.string().regex(/(?:^(http|localstack)s?:\/\/[^/]*\/?$)/).optional()
        .describe("Override the default S3 endpoint for clients to connect to.  " +
            "Necessary for testing, when S3 isn't used, or when it's only accessible via another endpoint"),
    s3RepoPathUri: z.string().describe("s3://BUCKETNAME/PATH"),
    repoName: z.string().default("migration_assistant_repo")
});

export const CPU_QUANTITY = z.string()
    .regex(/^[0-9]+m$/)
    .describe("CPU quantity in millicores (e.g., '100m', '500m')");

export const MEMORY_QUANTITY = z.string()
    .regex(/^[0-9]+((E|P|T|G|M)i?|Ki|k)$/)
    .describe("Memory quantity with unit (e.g., '512Mi', '2G')");

export const STORAGE_QUANTITY = z.string()
    .regex(/^[0-9]+((E|P|T|G|M)i?|Ki|k)$/)
    .describe("Storage quantity with unit (e.g., '10Gi', '5G')");

export const CONTAINER_RESOURCES = {
    cpu: CPU_QUANTITY,
    memory: MEMORY_QUANTITY,
    "ephemeral-storage": STORAGE_QUANTITY.optional()
}

export const RESOURCE_REQUIREMENTS = z.object({
    limits: z.object(CONTAINER_RESOURCES).describe("Resource upper bound for a container"),
    requests: z.object(CONTAINER_RESOURCES).describe("Resource lower bound for a container")
}).describe("Compute resource requirements for a container");

export type ResourceRequirementsType = z.infer<typeof RESOURCE_REQUIREMENTS>;

export const PROXY_OPTIONS = z.object({
    loggingConfigurationOverrideConfigMap: z.string().default(""),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
    setHeaders: z.array(z.string()).optional(),
    // TODO: Capture proxy resources non-functional currently
    // resources: RESOURCE_REQUIREMENTS.optional()
    //     .describe("Resource limits and requests for proxy container.")
    //     .default(DEFAULT_RESOURCES.CAPTURE_PROXY),
});

export const REPLAYER_OPTIONS = z.object({
    speedupFactor: z.number().default(1.1),
    podReplicas: z.number().default(1),
    authHeaderOverride: z.string().default(""),
    loggingConfigurationOverrideConfigMap: z.string().default(""),
    resources: RESOURCE_REQUIREMENTS.optional()
        .describe("Resource limits and requests for replayer container.")
        .default(DEFAULT_RESOURCES.REPLAYER),
    // docTransformerBase64: z.string().default(""),
    // otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
});

export const CREATE_SNAPSHOT_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]),
    maxSnapshotRateMbPerNode: z.number().default(0),
    loggingConfigurationOverrideConfigMap: z.string().default(""),
    s3RoleArn: z.string().default("")
});

export const METADATA_OPTIONS = z.object({
    componentTemplateAllowlist: z.array(z.string()).default([]),
    indexAllowlist: z.array(z.string()).default([]),
    indexTemplateAllowlist: z.array(z.string()).default([]),

    allowLooseVersionMatching: z.boolean().default(true),
    clusterAwarenessAttributes: z.number().default(1),
    loggingConfigurationOverrideConfigMap: z.string().default(""),
    multiTypeBehavior: z.union(["NONE", "UNION", "SPLIT"].map(s=>z.literal(s))).default("NONE"),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),
    output: z.union(["HUMAN_READABLE", "JSON"].map(s=>z.literal(s))).default("HUMAN_READABLE"),
    transformerConfigBase64: z.string().default(""),
});

export const RFS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]),
    podReplicas: z.number().default(1),
    loggingConfigurationOverrideConfigMap: z.string().default(""),
    allowLooseVersionMatching: z.boolean().default(true).describe(""),
    docTransformerConfigBase64: z.string().default(""),
    documentsPerBulkRequest: z.number().default(0x7fffffff),
    initialLeaseDuration: z.string().default("PT10M"),
    maxConnections: z.number().default(10),
    maxShardSizeBytes: z.number().default(80*1024*1024*1024),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317"),

    resources: z.preprocess((v) => 
        deepmerge(DEFAULT_RESOURCES.RFS, (v ?? {})),
        RESOURCE_REQUIREMENTS
    )
    .pipe(RESOURCE_REQUIREMENTS.extend({
        requests: RESOURCE_REQUIREMENTS.shape.requests.extend({
            "ephemeral-storage":
            RESOURCE_REQUIREMENTS.shape.requests.shape["ephemeral-storage"].describe(
                "If omitted, automatically computed during transform as ceil(2.5 * maxShardSizeBytes)."
            ),
        }),
    }))
    .describe("Resource limits and requests for RFS container"),
})
.transform((data) => {
    const requestEphemeral = data.resources?.requests?.["ephemeral-storage"];
    const userRequestEphemeralStorageBytes = requestEphemeral
        ? parseK8sQuantity(requestEphemeral)
        : undefined;
    const limitsEphemeral = data.resources?.limits?.["ephemeral-storage"];
    const userLimitsEphemeralStorageBytes = limitsEphemeral
        ? parseK8sQuantity(limitsEphemeral)
        : undefined;
    const minimumEphemeralBytes = Math.ceil(2.5 * data.maxShardSizeBytes)

    if (userRequestEphemeralStorageBytes != undefined && userRequestEphemeralStorageBytes < minimumEphemeralBytes) {
        throw new Error(
            `Resource requests for RFS storage of ${userRequestEphemeralStorageBytes} is too low to support maxShardSizeBytes value of ${data.maxShardSizeBytes}.
            Increase to at least ${minimumEphemeralBytes} to support the migration or decrease maxShardSizeBytes`)
    }

    if (userLimitsEphemeralStorageBytes != undefined && userLimitsEphemeralStorageBytes < minimumEphemeralBytes) {
        throw new Error(
            `Resource limits for RFS storage of ${userLimitsEphemeralStorageBytes} is too low to support maxShardSizeBytes value of ${data.maxShardSizeBytes}.
            Increase to at least ${minimumEphemeralBytes} to support the migration or decrease maxShardSizeBytes`)
    }

    // pick the larger of (user, minimum); if neither present fall back to minimum
    const reqBytes = Math.max(userRequestEphemeralStorageBytes ?? 0, minimumEphemeralBytes);
    // ensure limits >= requests; if user set a higher limit, keep it
    const limBytes = Math.max(userLimitsEphemeralStorageBytes ?? 0, reqBytes);

    return deepmerge(
        data,
        {
            resources: {
                requests: { "ephemeral-storage": Math.ceil(reqBytes / 1024) + "Ki" },
                limits: { "ephemeral-storage": Math.ceil(limBytes / 1024) + "Ki" },
            },
        },
    );
});

export const K8S_NAMING_PATTERN = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/;

export const HTTP_AUTH_BASIC = z.object({
    basic: z.object({
        secretName: z.string().regex(K8S_NAMING_PATTERN),
        username: z.string(),
        password: z.string()
    })
});

export const HTTP_AUTH_SIGV4 = z.object({
    sigv4: z.object({
        region: z.string(),
        service: z.string().default("es").optional(),
    })
});

export const HTTP_AUTH_MTLS = z.object({
    mtls: z.object({
        caCert: z.string(),
        clientSecretName: z.string()
    })
});

export const CLUSTER_VERSION_STRING = z.string().default("ES 7.10");

export const CLUSTER_CONFIG = z.object({
    endpoint: z.string(),
    allowInsecure: z.boolean().optional(),
    version: CLUSTER_VERSION_STRING,
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional(),
});

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    endpoint: z.string(), // override to required
});

export const SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    snapshotRepo: S3_REPO_CONFIG.optional(),
    proxy: PROXY_OPTIONS.optional()
});

export const EXTERNALLY_MANAGED_SNAPSHOT = z.object({
    externallyManagedSnapshot: z.string()
});

export const GENERATED_SNAPSHOT = z.object({
    snapshotNamePrefix: z.string()
});

export const SNAPSHOT_NAME_CONFIG = z.union([
    EXTERNALLY_MANAGED_SNAPSHOT, GENERATED_SNAPSHOT
]);

export const NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG = z.object({
    snapshotNameConfig: SNAPSHOT_NAME_CONFIG
});

export const NORMALIZED_COMPLETE_SNAPSHOT_CONFIG = z.object({
    snapshotName: z.string() // override to required
});

export const PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    metadataMigrationConfig: METADATA_OPTIONS.optional(),
    documentBackfillConfig: RFS_OPTIONS.optional()
});

export const NORMALIZED_SNAPSHOT_MIGRATION_CONFIG = z.object({
    createSnapshotConfig: CREATE_SNAPSHOT_OPTIONS.optional(),
    snapshotConfig: NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG,
    migrations: z.array(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG).min(1)
});

export const NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG = z.object({
    fromSource: z.string(),
    toTarget: z.string(),
    snapshotExtractAndLoadConfigs: z.array(NORMALIZED_SNAPSHOT_MIGRATION_CONFIG).optional(),
    replayerConfig: REPLAYER_OPTIONS.optional()
});

export const SOURCE_CLUSTERS_MAP = z.record(z.string(), SOURCE_CLUSTER_CONFIG);
export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG);

export const OVERALL_MIGRATION_CONFIG = z.object({
    sourceClusters: SOURCE_CLUSTERS_MAP,
    targetClusters: TARGET_CLUSTERS_MAP,
    migrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG).min(1)
});
