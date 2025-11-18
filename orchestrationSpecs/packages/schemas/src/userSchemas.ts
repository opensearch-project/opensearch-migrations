import {z} from "zod";

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
    endpoint: z.string().regex(/(?:^(http|localstack)s?:\/\/[^/]*\/?$)/).default("").optional()
        .describe("Override the default S3 endpoint for clients to connect to.  " +
            "Necessary for testing, when S3 isn't used, or when it's only accessible via another endpoint"),
    s3RepoPathUri: z.string().describe("s3:///BUCKETNAME/PATH"),
    repoName: z.string().default("migration_assistant_repo").optional()
});

export const PROXY_OPTIONS = z.object({
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
    setHeaders: z.array(z.string()).optional(),
});

export const REPLAYER_OPTIONS = z.object({
    speedupFactor: z.number().default(1.1).optional(),
    podReplicas: z.number().default(1).optional(),
    authHeaderOverride: z.string().default("").optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    // docTransformerBase64: z.string().default("").optional(),
    // otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
});

export const CREATE_SNAPSHOT_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional(),
    maxSnapshotRateMbPerNode: z.number().default(0).optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    s3RoleArn: z.string().default("").optional()
});

export const METADATA_OPTIONS = z.object({
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

export const RFS_OPTIONS = z.object({
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

    skipApproval: z.boolean().default(false).optional()  // TODO - fullmigration
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

export const CLUSTER_VERSION_STRING = z.string().regex(/^(?:ES [125678]|OS [123])(?:\.[0-9]+)+$/);

export const CLUSTER_CONFIG = z.object({
    endpoint: z.string(),
    allowInsecure: z.boolean().default(false).optional(),
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
    name: z.string().default("").optional(),
    metadataMigrationConfig: METADATA_OPTIONS.optional(),
    documentBackfillConfig: RFS_OPTIONS.optional(),
}).refine(data =>
        data.metadataMigrationConfig !== undefined ||
        data.documentBackfillConfig !== undefined,
    {message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided"});

export const NORMALIZED_SNAPSHOT_MIGRATION_CONFIG = z.object({
    createSnapshotConfig: CREATE_SNAPSHOT_OPTIONS.optional(),
    snapshotConfig: NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG,
    migrations: z.array(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG).min(1)
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
});

export const SOURCE_CLUSTERS_MAP = z.record(z.string(), SOURCE_CLUSTER_CONFIG);
export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG);

export const OVERALL_MIGRATION_CONFIG = validateOptionalDefaultConsistency(
    z.object({
        skipApprovals : z.boolean().default(false).optional(), // TODO - format
        sourceClusters: SOURCE_CLUSTERS_MAP,
        targetClusters: TARGET_CLUSTERS_MAP,
        migrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG).min(1)
    })
);
