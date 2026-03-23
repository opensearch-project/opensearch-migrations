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

export const S3_REPO_CONFIG = z.object({
    awsRegion: z.string().describe("The AWS region that the bucket reside in (us-east-2, etc)"),
    endpoint: z.string().regex(/(?:^(http|localstack)s?:\/\/[^/]*\/?$)?/).default("").optional()
        .describe("Override the default S3 endpoint for clients to connect to. " +
            "Necessary for testing, when S3 isn't used, or when it's only accessible via another endpoint"),
    s3RepoPathUri: z.string().regex(/^s3:\/\/[a-z0-9][a-z0-9.-]{1,61}[a-z0-9](\/[a-zA-Z0-9!\-_.*'()/]*)?$/).describe("s3://BUCKETNAME/PATH"),
    s3RoleArn: z.string().regex(/^(arn:aws:iam::\d{12}:(user|role|group|policy)\/[a-zA-Z0-9+=,.@_-]+)?$/).default("").optional()
        .describe("IAM role ARN to assume when accessing S3 for snapshot operations")
});

export const PORT_NUMBER_PATTERN = "(?:[1-9]\\d{0,3}|[1-5]\\d{4}|6[0-4]\\d{3}|65[0-4]\\d{2}|655[0-2]\\d|6553[0-5])";
export const OPTIONAL_PORT_PATTERN = `(?::${PORT_NUMBER_PATTERN})?`;
export const HOSTNAME_PATTERN = "[^:\\/\\s]+";
export const HTTP_ENDPOINT_PATTERN = `^https?:\\/\\/${HOSTNAME_PATTERN}${OPTIONAL_PORT_PATTERN}(?:\\/)?$`;
export const OPTIONAL_HTTP_ENDPOINT_PATTERN = `^(?:https?:\\/\\/${HOSTNAME_PATTERN}${OPTIONAL_PORT_PATTERN}(?:\\/)?)?$`;

export const KAFKA_CLIENT_CONFIG = z.object({
    enableMSKAuth: z.boolean().default(false).optional(),
    kafkaConnection: z.string()
        .describe("Sequence of <HOSTNAME:PORT> values delimited by ','.  " +
            "If empty, the cluster is automatically created and this is filled in.")
        .regex(new RegExp(`^(?:[a-z0-9][-a-z0-9.]*:${PORT_NUMBER_PATTERN}(?:,(?!$)|$))*$`)),
    kafkaTopic: z.string().describe("Empty defaults to the name of the target label").default(""),
});

export const K8S_NAMING_PATTERN = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$/;

export const CPU_QUANTITY = z.string()
    .regex(/^[0-9]+m$/)
    .describe("CPU quantity in millicores (e.g., '100m', '500m')");

export const MEMORY_QUANTITY = z.string()
    .regex(/^[0-9]+(([EPTGM])i?|Ki|k)$/)
    .describe("Memory quantity with unit (e.g., '512Mi', '2G')");

export const STORAGE_QUANTITY = z.string()
    .regex(/^[0-9]+(([EPTGM])i?|Ki|k)$/)
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

export const CERT_MANAGER_ISSUER_REF = z.object({
    name: z.string().describe("Name of the cert-manager Issuer or ClusterIssuer."),
    kind: z.enum(["Issuer", "ClusterIssuer"]).default("ClusterIssuer").optional()
        .describe("Kind of the issuer resource."),
    group: z.string().default("cert-manager.io").optional()
        .describe("API group of the issuer. Use 'awspca.cert-manager.io' for AWS PCA issuers."),
});

export const PROXY_TLS_CONFIG = z.discriminatedUnion("mode", [
    z.object({
        mode: z.literal("certManager"),
        issuerRef: CERT_MANAGER_ISSUER_REF,
        commonName: z.string().optional(),
        dnsNames: z.array(z.string()).min(1)
            .describe("DNS names for the certificate. Must include the proxy's service DNS name."),
        duration: z.string().default("2160h").optional()
            .describe("Requested certificate validity duration (default: 90 days)."),
        renewBefore: z.string().default("360h").optional()
            .describe("How long before expiry to renew (default: 15 days)."),
    }).describe("Use cert-manager to provision a TLS certificate for the proxy."),
    z.object({
        mode: z.literal("existingSecret"),
        secretName: z.string()
            .describe("Name of an existing K8s TLS secret with tls.crt and tls.key."),
    }).describe("Use a pre-existing K8s TLS secret."),
]);

export const USER_PROXY_WORKFLOW_OPTIONS = z.object({
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    internetFacing: z.boolean().default(false).optional()
        .describe("When true, configures the proxy Service load balancer scheme as internet-facing."),
    podReplicas: z.number().default(1).optional(),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.PROXY, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Resource limits and requests for proxy container.")
        .default(DEFAULT_RESOURCES.PROXY),
});

export const USER_PROXY_PROCESS_OPTIONS = z.object({
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
    setHeader: z.array(z.string()).optional(),
    destinationConnectionPoolSize: z.number().default(0).optional(),
    destinationConnectionPoolTimeout: z.string()
        .regex(/^[-+]?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/)
        .default("PT30S").optional(),
    kafkaClientId: z.string().default("HttpCaptureProxyProducer").optional(),
    listenPort: z.number(),
    maxTrafficBufferSize: z.number().default(1048576).optional(),
    noCapture: z.boolean().default(false).optional(),
    numThreads: z.number().default(1).optional(),
    sslConfigFile: z.string().optional()
        .describe("Legacy: YAML config for OpenSearch security SSL. Prefer tls config for K8s deployments."),
    tls: PROXY_TLS_CONFIG.optional()
        .describe("TLS certificate configuration for the proxy. Mutually exclusive with sslConfigFile."),
    enableMSKAuth: z.boolean().default(false).optional(),
    suppressCaptureForHeaderMatch: z.array(z.string()).default([]).optional(),
    suppressCaptureForMethod: z.string().default("").optional(),
    suppressCaptureForUriPath: z.string().default("").optional(),
    suppressMethodAndPath: z.string().default("").optional(),
});

export const USER_PROXY_WORKFLOW_OPTION_KEYS = getZodKeys(USER_PROXY_WORKFLOW_OPTIONS);
export const USER_PROXY_PROCESS_OPTION_KEYS = getZodKeys(USER_PROXY_PROCESS_OPTIONS);

export const USER_PROXY_OPTIONS = z.object({
    ...USER_PROXY_WORKFLOW_OPTIONS.shape,
    ...USER_PROXY_PROCESS_OPTIONS.shape,
});

export const USER_REPLAYER_WORKFLOW_OPTIONS = z.object({
    jvmArgs: z.string().default("").optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    podReplicas: z.number().default(1).optional(),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.REPLAYER, (v ?? {})), RESOURCE_REQUIREMENTS)
        .describe("Resource limits and requests for replayer container."),
});

export const USER_REPLAYER_PROCESS_OPTIONS = z.object({
    kafkaTrafficEnableMSKAuth: z.boolean().default(false).optional()
        .describe("Enables SASL properties required for connecting to MSK with IAM auth."),
    kafkaTrafficPropertyFile: z.string().optional()
        .describe("File path for Kafka properties file for additional or overridden Kafka properties."),
    lookaheadTimeSeconds: z.number().default(400).optional()
        .describe("Number of seconds of data that will be buffered."),
    maxConcurrentRequests: z.number().default(10000).optional()
        .describe("Maximum number of requests that can be outstanding at a time."),
    numClientThreads: z.number().default(0).optional()
        .describe("Number of threads to use to send requests from."),
    observedPacketConnectionTimeout: z.number().default(360).optional()
        .describe("Seconds of inactivity before assuming connections were terminated in the captured stream."),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional()
        .describe("Endpoint (host:port) for the OpenTelemetry Collector for metrics forwarding."),
    quiescentPeriodMs: z.number().default(5000).optional()
        .describe("Milliseconds to delay the first request on a resumed connection after Kafka partition reassignment."),
    removeAuthHeader: z.boolean().default(false).optional()
        .describe("Remove the authorization header if present without replacing it."),
    speedupFactor: z.number().default(1.1).optional()
        .describe("Factor to accelerate replayed communications relative to original timing."),
    targetServerResponseTimeoutSeconds: z.number().default(150).optional()
        .describe("Seconds to wait before timing out a replayed request to the target."),
    transformerConfig: z.string().optional()
        .describe("Request transformer configuration as a string or JSON."),
    transformerConfigEncoded: z.string().optional()
        .describe("Base64-encoded request transformer configuration."),
    transformerConfigFile: z.string().optional()
        .describe("Path to the JSON configuration file for request transformers."),
    tupleTransformerConfig: z.string().optional()
        .describe("Tuple transformer configuration as a string or JSON."),
    tupleTransformerConfigBase64: z.string().optional()
        .describe("Base64-encoded tuple transformer configuration."),
    tupleTransformerConfigFile: z.string().optional()
        .describe("Path to the JSON configuration file for tuple transformers."),
    userAgent: z.string().optional()
        .describe("String appended to the user-agent header for requests to the target cluster."),
});

export const USER_REPLAYER_WORKFLOW_OPTION_KEYS = getZodKeys(USER_REPLAYER_WORKFLOW_OPTIONS);
export const USER_REPLAYER_PROCESS_OPTION_KEYS = getZodKeys(USER_REPLAYER_PROCESS_OPTIONS);

export const USER_REPLAYER_OPTIONS = z.object({
    ...USER_REPLAYER_WORKFLOW_OPTIONS.shape,
    ...USER_REPLAYER_PROCESS_OPTIONS.shape,
});

// Note: noWait is not included here as it is hardcoded to true in the workflow.
// The workflow manages snapshot completion polling separately via checkSnapshotStatus.
export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS = z.object({
    snapshotPrefix: z.string().default("").optional(),
    jvmArgs: z.string().default("").optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
});

export const USER_CREATE_SNAPSHOT_PROCESS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional(),
    maxSnapshotRateMbPerNode: z.number().default(0).optional(),
});

export const USER_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS);
export const USER_CREATE_SNAPSHOT_PROCESS_OPTION_KEYS = getZodKeys(USER_CREATE_SNAPSHOT_PROCESS_OPTIONS);

export const USER_CREATE_SNAPSHOT_OPTIONS = z.object({
    ...USER_CREATE_SNAPSHOT_WORKFLOW_OPTIONS.shape,
    ...USER_CREATE_SNAPSHOT_PROCESS_OPTIONS.shape,
});

export const USER_METADATA_WORKFLOW_OPTIONS = z.object({
    jvmArgs: z.string().default("").optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    skipEvaluateApproval: z.boolean().default(false).optional(), // TODO - fullmigration
    skipMigrateApproval: z.boolean().default(false).optional() // TODO - fullmigration
});

export const USER_METADATA_PROCESS_OPTIONS = z.object({
    componentTemplateAllowlist: z.array(z.string()).default([]).optional(),
    indexAllowlist: z.array(z.string()).default([]).optional(),
    indexTemplateAllowlist: z.array(z.string()).default([]).optional(),

    allowLooseVersionMatching: z.boolean().default(true).optional(),
    clusterAwarenessAttributes: z.number().default(1).optional(),
    multiTypeBehavior: z.union(["NONE", "UNION", "SPLIT"].map(s=>z.literal(s))).default("NONE").optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
    output: z.union(["HUMAN_READABLE", "JSON"].map(s=>z.literal(s))).default("HUMAN_READABLE").optional(),
    transformerConfigBase64: z.string().default("").optional(),
});

export const USER_METADATA_WORKFLOW_OPTION_KEYS = getZodKeys(USER_METADATA_WORKFLOW_OPTIONS);
export const USER_METADATA_PROCESS_OPTION_KEYS = getZodKeys(USER_METADATA_PROCESS_OPTIONS);

export const USER_METADATA_OPTIONS = z.object({
    ...USER_METADATA_WORKFLOW_OPTIONS.shape,
    ...USER_METADATA_PROCESS_OPTIONS.shape,
});

export const USER_RFS_WORKFLOW_OPTIONS = z.object({
    podReplicas: z.number().default(1).optional(),
    jvmArgs: z.string().default("").optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    skipApproval: z.boolean().default(false).optional(),  // TODO - fullmigration
    useTargetClusterForWorkCoordination: z.boolean().default(false)
        .describe("When true, uses the target OpenSearch cluster for RFS work coordination. When false, a dedicated single-node OpenSearch coordinator cluster is deployed and managed for the lifetime of the migration, then torn down on completion."),
    resources: z.preprocess((v) => deepmerge(DEFAULT_RESOURCES.RFS, (v ?? {})), RESOURCE_REQUIREMENTS)
        .pipe(RESOURCE_REQUIREMENTS.extend({
            requests: RESOURCE_REQUIREMENTS.shape.requests.extend({
                "ephemeral-storage":
                    RESOURCE_REQUIREMENTS.shape.requests.shape["ephemeral-storage"].describe(
                        "If omitted, automatically computed during transform as ceil(2.5 * maxShardSizeBytes)."
                    ),
            }),
        }))
        .describe("Resource limits and requests for RFS container"),
});

export const USER_RFS_PROCESS_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional(),
    allowLooseVersionMatching: z.boolean().default(true).describe("").optional(),
    docTransformerConfigBase64: z.string().default("").optional(),
    documentsPerBulkRequest: z.number().default(0x7fffffff).optional(),
    initialLeaseDuration: z.string().default("PT1H").optional(),
    maxConnections: z.number().default(10).optional(),
    maxShardSizeBytes: z.number().default(80*1024*1024*1024).optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
});

export const USER_RFS_WORKFLOW_OPTION_KEYS = getZodKeys(USER_RFS_WORKFLOW_OPTIONS);
export const USER_RFS_PROCESS_OPTION_KEYS = getZodKeys(USER_RFS_PROCESS_OPTIONS);

export const USER_RFS_OPTIONS = z.object({
    ...USER_RFS_WORKFLOW_OPTIONS.shape,
    ...USER_RFS_PROCESS_OPTIONS.shape,
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

export const KAFKA_CLUSTER_CREATION_CONFIG = z.object({
    replicas: z.number().int().min(1).default(1).optional(),
    storage: z.discriminatedUnion("type", [
        z.object({ type: z.literal("ephemeral") }),
        z.object({ type: z.literal("persistent-claim"), size: z.string() })
    ]).default({ type: "ephemeral" }).optional(),
    partitions: z.number().int().min(1).default(1).optional(),
    topicReplicas: z.number().int().min(1).default(1).optional(),
});

export const KAFKA_CLUSTER_CONFIG = z.union([
    z.object({autoCreate: KAFKA_CLUSTER_CREATION_CONFIG}),
    z.object({existing: KAFKA_CLIENT_CONFIG })
]);

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
    endpoint:  z.string().regex(new RegExp(OPTIONAL_HTTP_ENDPOINT_PATTERN)).default("").optional(),
    allowInsecure: z.boolean().default(false).optional(),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional(),
});

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    enabled: z.boolean().default(true).optional(),
    endpoint:  z.string().regex(new RegExp(HTTP_ENDPOINT_PATTERN)), // override to required
});

export const SOURCE_CLUSTER_REPOS_RECORD =
    z.record(z.string(), S3_REPO_CONFIG)
        .describe("Keys are the repository names that are managed by the source cluster");

export const CAPTURE_CONFIG = z.object({
    kafka: z.string().regex(K8S_NAMING_PATTERN).default("default").optional(),
    kafkaTopic: z.string().regex(K8S_NAMING_PATTERN).default("").optional()
        .describe("Kafka topic for captured traffic. Empty defaults to the proxy name."),
    source: z.string(),
    proxyConfig: USER_PROXY_OPTIONS
});

export const SNAPSHOT_MIGRATION_FILTER = z.object({
    source: z.string(),
    snapshot: z.string()
});

export const REPLAYER_CONFIG = z.object({
    skipApprovals: z.boolean().default(false).optional(), // TODO - format
    fromProxy: z.string(),
    toTarget: z.string(),
    dependsOnSnapshotMigrations: z.array(SNAPSHOT_MIGRATION_FILTER).default([]).optional(),
    replayerConfig: USER_REPLAYER_OPTIONS.optional()
});

export const TRAFFIC_CONFIG = z.object({
    proxies: z.record(z.string().regex(K8S_NAMING_PATTERN), CAPTURE_CONFIG),
    replayers: z.record(z.string(), REPLAYER_CONFIG)
}).superRefine((data, ctx) => {
    for (const [name, rc] of Object.entries(data.replayers)) {
        if (!(rc.fromProxy in data.proxies)) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Replayer '${name}' references unknown proxy '${rc.fromProxy}'. Available: ${Object.keys(data.proxies).join(', ')}`,
                path: ['replayers', name, 'fromProxy']
            });
        }
    }
});

export const EXTERNALLY_MANAGED_SNAPSHOT = z.object({
    externallyManagedSnapshotName: z.string()
});

export const GENERATE_SNAPSHOT = z.object({
    createSnapshotConfig: USER_CREATE_SNAPSHOT_OPTIONS,
    requiredForCompleteMigration: z.union([
        z.object({toTargets: z.array(z.string())}),
        z.boolean().default(true).optional()
    ])
});

export const SNAPSHOT_NAME_CONFIG = z.union([
    EXTERNALLY_MANAGED_SNAPSHOT, GENERATE_SNAPSHOT
]);

export const NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG = z.object({
    config: SNAPSHOT_NAME_CONFIG,
    repoName: z.string()
});

export const SNAPSHOT_CONFIGS_MAP = z.record(
    z.string(),
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG
);

export const SNAPSHOT_INFO = z.object({
    repos: SOURCE_CLUSTER_REPOS_RECORD.optional(),
    snapshots: SNAPSHOT_CONFIGS_MAP
});

export const SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    version: CLUSTER_VERSION_STRING,
    enabled: z.boolean().default(true).optional(),
    snapshotInfo: SNAPSHOT_INFO.optional()
}).superRefine((data, ctx) => {
    const repos = data.snapshotInfo?.repos;
    const snapshots = data.snapshotInfo?.snapshots ?? {};
    for (const [snapName, snapConfig] of Object.entries(snapshots)) {
        const repoName = snapConfig.repoName;
        if (repoName) {
            if (!repos) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `Snapshot '${snapName}' references repoName '${repoName}' but no repos are defined`,
                    path: ['snapshotInfo', 'snapshots', snapName, 'repoName']
                });
            } else if (!(repoName in repos)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `Snapshot '${snapName}' references unknown repoName '${repoName}'. Available: ${Object.keys(repos).join(', ')}`,
                    path: ['snapshotInfo', 'snapshots', snapName, 'repoName']
                });
            }
        }
    }
});

export const NORMALIZED_COMPLETE_SNAPSHOT_CONFIG = z.object({
    snapshotName: z.string() // override to required
});

export const USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    label: z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/).default("").optional(),
    metadataMigrationConfig: USER_METADATA_OPTIONS.optional(),
    documentBackfillConfig: USER_RFS_OPTIONS.optional(),
}).refine(data =>
        data.metadataMigrationConfig !== undefined ||
        data.documentBackfillConfig !== undefined,
    {message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided"});

export const SNAPSHOT_MIGRATION_CONFIG_ARRAY =
    z.array(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG);

export const PER_SNAPSHOT_MIGRATION_CONFIG_RECORD =
    z.record(z.string().regex(/^[a-zA-Z][a-zA-Z0-9]*/),
        SNAPSHOT_MIGRATION_CONFIG_ARRAY.min(1));

export const NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG = z.object({
    skipApprovals: z.boolean().default(false).optional(), // TODO - format
    fromSource: z.string(),
    toTarget: z.string(),
    perSnapshotConfig: PER_SNAPSHOT_MIGRATION_CONFIG_RECORD.optional(),
}).superRefine((data, ctx) => {
    if (!data.perSnapshotConfig) return;
    for (const [snapName, migrations] of Object.entries(data.perSnapshotConfig)) {
        const labels = migrations.map(m => m.label).filter(Boolean);
        if (labels.length !== new Set(labels).size) {
            ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: `Duplicate labels in perSnapshotConfig['${snapName}']`,
                path: ['perSnapshotConfig', snapName]
            });
        }
    }
});

export const KAFKA_CLUSTERS_MAP = z.record(z.string().regex(K8S_NAMING_PATTERN), KAFKA_CLUSTER_CONFIG);
export const SOURCE_CLUSTERS_MAP = z.record(z.string(), SOURCE_CLUSTER_CONFIG);
export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG);

export const OVERALL_MIGRATION_CONFIG = //validateOptionalDefaultConsistency
(
    z.object({
        skipApprovals: z.boolean().default(false).optional(), // TODO - format
        kafkaClusterConfiguration: KAFKA_CLUSTERS_MAP.default({}).optional(),
        sourceClusters: SOURCE_CLUSTERS_MAP,
        targetClusters: TARGET_CLUSTERS_MAP,
        snapshotMigrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG),
        traffic: TRAFFIC_CONFIG
            .describe("Top-level items are independent of each other but " +
                "items in the inner-arrays require all snapshot activities across each of the items' " +
                "sources to finish before any replays in this group can start.")
            .optional()
    }).superRefine((data, ctx) => {
        for (let i = 0; i < data.snapshotMigrationConfigs.length; i++) {
            const mc = data.snapshotMigrationConfigs[i];

            if (!(mc.fromSource in data.sourceClusters)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `snapshotMigrationConfigs[${i}] references unknown source '${mc.fromSource}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                    path: ['snapshotMigrationConfigs', i, 'fromSource']
                });
            }

            if (!(mc.toTarget in data.targetClusters)) {
                ctx.addIssue({
                    code: z.ZodIssueCode.custom,
                    message: `snapshotMigrationConfigs[${i}] references unknown target '${mc.toTarget}'. Available: ${Object.keys(data.targetClusters).join(', ')}`,
                    path: ['snapshotMigrationConfigs', i, 'toTarget']
                });
            }

            if (mc.perSnapshotConfig) {
                const sourceCluster = data.sourceClusters[mc.fromSource];
                const availableSnapshots = sourceCluster?.snapshotInfo?.snapshots ?? {};
                for (const snapName of Object.keys(mc.perSnapshotConfig)) {
                    if (!(snapName in availableSnapshots)) {
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `perSnapshotConfig references unknown snapshot '${snapName}' in source '${mc.fromSource}'. Available: ${Object.keys(availableSnapshots).join(', ') || '(none)'}`,
                            path: ['snapshotMigrationConfigs', i, 'perSnapshotConfig', snapName]
                        });
                    }
                }
            }
        }

        if (data.traffic) {
            for (const [proxyName, proxyConfig] of Object.entries(data.traffic.proxies)) {
                if (!(proxyConfig.source in data.sourceClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Proxy '${proxyName}' references unknown source '${proxyConfig.source}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                        path: ['traffic', 'proxies', proxyName, 'source']
                    });
                }
                const kafkaRef = proxyConfig.kafka;
                const kafkaClusters = data.kafkaClusterConfiguration ?? {};
                if (kafkaRef && Object.keys(kafkaClusters).length > 0 && !(kafkaRef in kafkaClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Proxy '${proxyName}' references unknown kafka cluster '${kafkaRef}'. Available: ${Object.keys(kafkaClusters).join(', ')}`,
                        path: ['traffic', 'proxies', proxyName, 'kafka']
                    });
                }
            }

            for (const [replayerName, rc] of Object.entries(data.traffic.replayers)) {
                if (!(rc.toTarget in data.targetClusters)) {
                    ctx.addIssue({
                        code: z.ZodIssueCode.custom,
                        message: `Replayer '${replayerName}' references unknown target '${rc.toTarget}'. Available: ${Object.keys(data.targetClusters).join(', ')}`,
                        path: ['traffic', 'replayers', replayerName, 'toTarget']
                    });
                }

                for (let j = 0; j < (rc.dependsOnSnapshotMigrations?.length ?? 0); j++) {
                    const dep = rc.dependsOnSnapshotMigrations![j];
                    if (!(dep.source in data.sourceClusters)) {
                        ctx.addIssue({
                            code: z.ZodIssueCode.custom,
                            message: `Replayer '${replayerName}' dependsOnSnapshotMigrations[${j}] references unknown source '${dep.source}'. Available: ${Object.keys(data.sourceClusters).join(', ')}`,
                            path: ['traffic', 'replayers', replayerName, 'dependsOnSnapshotMigrations', j, 'source']
                        });
                    }
                }
            }
        }
    })
);
