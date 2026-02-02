import {z} from "zod";
import { DEFAULT_RESOURCES, parseK8sQuantity } from "./schemaUtilities";
import deepmerge from "deepmerge";

export function getZodKeys<T extends z.ZodRawShape>(schema: z.ZodObject<T>): readonly (keyof T)[] {
    return Object.keys(schema.shape) as (keyof T)[];
}
class SchemaValidationError extends Error {
    constructor(message: string, public path: string[]) {
        super(`${message} at path: ${path.join('.')}`);
        this.name = 'SchemaValidationError';
    }
}

function validateOptionalDefaultConsistency<T extends z.ZodTypeAny>(
    schema: T,
    path: string[] = []
): T {
    if (schema instanceof z.ZodOptional) {
        const innerType = schema.unwrap();
        const hasDefault = innerType instanceof z.ZodDefault;

        // Unwrap to get the actual type
        const actualType = hasDefault ? (innerType as z.ZodDefault<any>).removeDefault() : innerType;

        // Check if the actual underlying type is complex
        const isComplexType = actualType instanceof z.ZodObject ||
            actualType instanceof z.ZodArray ||
            actualType instanceof z.ZodRecord ||
            actualType instanceof z.ZodUnion ||
            actualType instanceof z.ZodDiscriminatedUnion ||
            actualType instanceof z.ZodIntersection ||
            actualType instanceof z.ZodTuple;

        // Only enforce default requirement for scalar values
        if (!isComplexType && !hasDefault) {
            throw new SchemaValidationError(
                'Optional field must have a default value',
                path
            );
        }

        // Recurse on the actual type
        validateOptionalDefaultConsistency(actualType as z.ZodTypeAny, path);
    } else if (schema instanceof z.ZodDefault) {
        const innerType = (schema as z.ZodDefault<any>).removeDefault();

        // Check if the actual underlying type is complex
        const isComplexType = innerType instanceof z.ZodObject ||
            innerType instanceof z.ZodArray ||
            innerType instanceof z.ZodRecord ||
            innerType instanceof z.ZodUnion ||
            innerType instanceof z.ZodDiscriminatedUnion ||
            innerType instanceof z.ZodIntersection ||
            innerType instanceof z.ZodTuple;

        // Only enforce optional requirement for scalar values
        if (!isComplexType) {
            throw new SchemaValidationError(
                'Default value must be followed by .optional() (use .default(value).optional())',
                path
            );
        }

        // Recurse on the inner type
        validateOptionalDefaultConsistency(innerType as z.ZodTypeAny, path);
    } else if (schema instanceof z.ZodObject) {
        const shape = schema.shape;
        for (const [key, value] of Object.entries(shape)) {
            validateOptionalDefaultConsistency(value as z.ZodTypeAny, [...path, key]);
        }
    } else if (schema instanceof z.ZodArray) {
        validateOptionalDefaultConsistency(schema.element as z.ZodTypeAny, [...path, '[array element]']);
    } else if (schema instanceof z.ZodRecord) {
        validateOptionalDefaultConsistency(schema.valueType as z.ZodTypeAny, [...path, '[record value]']);
    } else if (schema instanceof z.ZodUnion) {
        schema.options.forEach((option, index) => {
            validateOptionalDefaultConsistency(option as z.ZodTypeAny, [...path, `[union option ${index}]`]);
        });
    } else if (schema instanceof z.ZodDiscriminatedUnion) {
        Array.from(schema.options.values()).forEach((option, index) => {
            validateOptionalDefaultConsistency(option as z.ZodTypeAny, [...path, `[discriminated union option ${index}]`]);
        });
    } else if (schema instanceof z.ZodIntersection) {
        validateOptionalDefaultConsistency(schema._def.left as z.ZodTypeAny, [...path, '[intersection left]']);
        validateOptionalDefaultConsistency(schema._def.right as z.ZodTypeAny, [...path, '[intersection right]']);
    } else if (schema instanceof z.ZodTuple) {
        const items = schema._def.items;
        items.forEach((item, index) => {
            validateOptionalDefaultConsistency(item as z.ZodTypeAny, [...path, `[tuple ${index}]`]);
        });
    }
    return schema;
}

export const KAFKA_SERVICES_CONFIG = z.object({
    brokerEndpoints: z.string().describe("Specify an external kafka broker list if using one other than the one managed by the workflow"),
    standard: z.string()
});

export const S3_REPO_CONFIG = z.object({
    awsRegion: z.string().describe("The AWS region that the bucket reside in (us-east-2, etc)"),
    endpoint: z.string().regex(/(?:^(http|localstack)s?:\/\/[^/]*\/?$)?/).default("").optional()
        .describe("Override the default S3 endpoint for clients to connect to. " +
            "Necessary for testing, when S3 isn't used, or when it's only accessible via another endpoint"),
    s3RepoPathUri: z.string().regex(/^s3:\/\/[a-z0-9][a-z0-9.-]{1,61}[a-z0-9](\/[a-zA-Z0-9!\-_.*'()/]*)?$/).describe("s3://BUCKETNAME/PATH"),
    repoName: z.string().default("migration_assistant_repo").optional(),
    s3RoleArn: z.string().regex(/^(arn:aws:iam::\d{12}:(user|role|group|policy)\/[a-zA-Z0-9+=,.@_-]+)?$/).default("").optional()
        .describe("IAM role ARN to assume when accessing S3 for snapshot operations")
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
}).describe("Compute resource requirements for a container." +
    "There are implications to how a task will be evicted if the resources and limits are different.  " +
    "See https://kubernetes.io/docs/concepts/workloads/pods/pod-qos/#guaranteed for more information.");

export type ResourceRequirementsType = z.infer<typeof RESOURCE_REQUIREMENTS>;

export const PROXY_OPTIONS = z.object({
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
    setHeaders: z.array(z.string()).optional(),
    // TODO: Capture proxy resources non-functional currently
    // resources: RESOURCE_REQUIREMENTS.optional()
    //     .describe("Resource limits and requests for proxy container.")
    //     .default(DEFAULT_RESOURCES.CAPTURE_PROXY),
});

export const REPLAYER_OPTIONS = z.object({
    speedupFactor: z.number().default(1.1).optional(),
    podReplicas: z.number().default(1).optional(),
    authHeaderOverride: z.string().default("").optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    resources: RESOURCE_REQUIREMENTS
        .describe("Resource limits and requests for replayer container.")
        .default(DEFAULT_RESOURCES.REPLAYER).optional(),
    // docTransformerBase64: z.string().default("").optional(),
    // otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
});

// Note: noWait is not included here as it is hardcoded to true in the workflow.
// The workflow manages snapshot completion polling separately via checkSnapshotStatus.
export const CREATE_SNAPSHOT_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional(),
    maxSnapshotRateMbPerNode: z.number().default(0).optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
});

export const USER_METADATA_OPTIONS = z.object({
    componentTemplateAllowlist: z.array(z.string()).default([]).optional(),
    indexAllowlist: z.array(z.string()).default([]).optional(),
    indexTemplateAllowlist: z.array(z.string()).default([]).optional(),

    allowLooseVersionMatching: z.boolean().default(true).optional(),
    clusterAwarenessAttributes: z.number().default(1).optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    multiTypeBehavior: z.union(["NONE", "UNION", "SPLIT"].map(s=>z.literal(s))).default("NONE").optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
    output: z.union(["HUMAN_READABLE", "JSON"].map(s=>z.literal(s))).default("HUMAN_READABLE").optional(),
    transformerConfigBase64: z.string().default("").optional(),

    skipEvaluateApproval: z.boolean().default(false).optional(), // TODO - fullmigration
    skipMigrateApproval: z.boolean().default(false).optional() // TODO - fullmigration
});

export const USER_RFS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional(),
    podReplicas: z.number().default(1).optional(),

    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    allowLooseVersionMatching: z.boolean().default(true).describe("").optional(),
    docTransformerConfigBase64: z.string().default("").optional(),
    documentsPerBulkRequest: z.number().default(0x7fffffff).optional(),
    initialLeaseDuration: z.string().default("PT10M").optional(),
    maxConnections: z.number().default(10).optional(),
    maxShardSizeBytes: z.number().default(80*1024*1024*1024).optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),

    skipApproval: z.boolean().default(false).optional(),  // TODO - fullmigration
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
        const minimumEphemeralBytes = Math.ceil(2.5 * (data.maxShardSizeBytes??0))

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
        secretName: z.string().regex(K8S_NAMING_PATTERN)
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

export const CLUSTER_VERSION_STRING = z.string().regex(/^(?:ES [125678]|OS [123])(?:\.[0-9]+)+$/);

export const CLUSTER_CONFIG = z.object({
    endpoint:  z.string().regex(/^(?:https?:\/\/[^:\/\s]+(:\d+)?(\/)?)?$/).default("").optional(),
    allowInsecure: z.boolean().default(false).optional(),
    version: CLUSTER_VERSION_STRING,
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional(),
});

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.omit({ version: true }).extend({
    endpoint:  z.string().regex(/^https?:\/\/[^:\/\s]+(:\d+)?(\/)?$/), // override to required
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

export const USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    name: z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/).default("").optional(),
    metadataMigrationConfig: USER_METADATA_OPTIONS.optional(),
    documentBackfillConfig: USER_RFS_OPTIONS.optional(),
}).refine(data =>
        data.metadataMigrationConfig !== undefined ||
        data.documentBackfillConfig !== undefined,
    {message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided"});

export const NORMALIZED_SNAPSHOT_MIGRATION_CONFIG = z.object({
    name: z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/).default("").optional(),
    createSnapshotConfig: CREATE_SNAPSHOT_OPTIONS.optional(),
    snapshotConfig: NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG,
    migrations: z.array(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG).min(1)
}).refine(data => {
    const names = data.migrations.map(m => m.name).filter(s => s);
    return names.length == new Set(names).size;
},
    {message: "names of migration items must be unique when they are provided"});

export const NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG = z.object({
    skipApprovals : z.boolean().default(false).optional(), // TODO - format
    fromSource: z.string(),
    toTarget: z.string(),
    snapshotExtractAndLoadConfigs: z.array(NORMALIZED_SNAPSHOT_MIGRATION_CONFIG).min(1).optional(),
    replayerConfig: REPLAYER_OPTIONS.optional()
}).refine(data => {
        const names = data.snapshotExtractAndLoadConfigs?.map(m => m.name).filter(s => s);
        return names ? names.length == new Set(names).size : true;
    },
    {message: "names of snapshotExtractAndLoadConfigs items must be unique when they are provided"});

export const SOURCE_CLUSTERS_MAP = z.record(z.string(), SOURCE_CLUSTER_CONFIG);
export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG);

export const OVERALL_MIGRATION_CONFIG = //validateOptionalDefaultConsistency
(
    z.object({
        skipApprovals : z.boolean().default(false).optional(), // TODO - format
        sourceClusters: SOURCE_CLUSTERS_MAP,
        targetClusters: TARGET_CLUSTERS_MAP,
        migrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG).min(1)
    })
);
