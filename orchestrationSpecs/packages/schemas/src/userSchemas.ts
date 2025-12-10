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
    awsRegion: z.string()
        .meta({
            title: "AWS Region",
            description: "The AWS region that the bucket resides in (us-east-2, etc)",
            placeholder: "us-east-2",
            order: 1
        }),
    endpoint: z.string().regex(/(?:^(http|localstack)s?:\/\/[^/]*\/?$)/).default("").optional()
        .meta({
            title: "S3 Endpoint Override",
            description: "Override the default S3 endpoint for clients to connect to. Necessary for testing, when S3 isn't used, or when it's only accessible via another endpoint",
            placeholder: "https://s3.us-east-2.amazonaws.com",
            fieldType: "url",
            order: 2,
            advanced: true
        }),
    s3RepoPathUri: z.string().regex(/^s3:\/\/[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$/)
        .meta({
            title: "S3 Repository Path",
            description: "S3 URI for the snapshot repository",
            placeholder: "s3://my-bucket/snapshots",
            constraintText: "Must be in format s3://BUCKETNAME/PATH",
            order: 3
        }),
    repoName: z.string().default("migration_assistant_repo").optional()
        .meta({
            title: "Repository Name",
            description: "Name for the Elasticsearch/OpenSearch snapshot repository",
            placeholder: "migration_assistant_repo",
            order: 4
        })
}).meta({
    title: "Snapshot Repository",
    description: "S3 repository configuration for storing snapshots"
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
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    otelCollectorEndpoint: z.string().default("http://otel-collector:4317").optional(),
    setHeaders: z.array(z.string()).optional(),
    // TODO: Capture proxy resources non-functional currently
    // resources: RESOURCE_REQUIREMENTS.optional()
    //     .describe("Resource limits and requests for proxy container.")
    //     .default(DEFAULT_RESOURCES.CAPTURE_PROXY),
});

export const REPLAYER_OPTIONS = z.object({
    speedupFactor: z.number().default(1.1).optional()
        .meta({
            title: "Speedup Factor",
            description: "Factor to speed up or slow down traffic replay (1.0 = real-time)",
            placeholder: "1.1",
            order: 1
        }),
    podReplicas: z.number().default(1).optional()
        .meta({
            title: "Pod Replicas",
            description: "Number of replayer pod replicas to run",
            placeholder: "1",
            order: 2
        }),
    authHeaderOverride: z.string().default("").optional()
        .meta({
            title: "Auth Header Override",
            description: "Override the authorization header for replayed requests",
            placeholder: "Bearer token...",
            order: 3,
            advanced: true
        }),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional()
        .meta({
            title: "Logging Config Override",
            description: "ConfigMap name for custom logging configuration",
            order: 4,
            advanced: true
        }),
    resources: RESOURCE_REQUIREMENTS
        .describe("Resource limits and requests for replayer container.")
        .default(DEFAULT_RESOURCES.REPLAYER).optional()
        .meta({
            title: "Resource Requirements",
            description: "CPU and memory limits/requests for the replayer container",
            order: 5,
            advanced: true
        }),
}).meta({
    title: "Replayer Options",
    description: "Configuration for the traffic replayer component"
});

export const CREATE_SNAPSHOT_OPTIONS = z.object({
    indexAllowlist: z.array(z.string()).default([]).optional(),
    maxSnapshotRateMbPerNode: z.number().default(0).optional(),
    loggingConfigurationOverrideConfigMap: z.string().default("").optional(),
    s3RoleArn: z.string().regex(/^(arn:aws:iam::\d{12}:(user|role|group|policy)\/[a-zA-Z0-9+=,.@_-]+)?$/).default("").optional()
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
    endpoint: z.string().regex(/^(?:https?:\/\/[^:\/\s]+(:\d+)?(\/)?)?$/).default("").optional()
        .meta({
            title: "Cluster Endpoint",
            description: "URL of the cluster (e.g., https://cluster.example.com:9200)",
            placeholder: "https://cluster.example.com:9200",
            fieldType: "url",
            order: 1
        }),
    allowInsecure: z.boolean().default(false).optional()
        .meta({
            title: "Allow Insecure Connection",
            description: "Allow connections to clusters with self-signed or invalid SSL certificates",
            order: 2,
            advanced: true
        }),
    version: CLUSTER_VERSION_STRING
        .meta({
            title: "Cluster Version",
            description: "Version of the Elasticsearch/OpenSearch cluster",
            placeholder: "ES 7.10.2 or OS 2.11.0",
            constraintText: "Format: ES [1|2|5|6|7|8].x.x or OS [1|2|3].x.x",
            order: 3
        }),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4, HTTP_AUTH_MTLS]).optional()
        .meta({
            title: "Authentication",
            description: "Authentication configuration for connecting to the cluster",
            order: 4,
            variantLabels: {
                basic: "Basic Auth",
                sigv4: "AWS SigV4",
                mtls: "Mutual TLS"
            }
        }),
});

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    endpoint: z.string().regex(/^https?:\/\/[^:\/\s]+(:\d+)?(\/)?$/)
        .meta({
            title: "Cluster Endpoint",
            description: "URL of the target cluster (required)",
            placeholder: "https://target-cluster.example.com:9200",
            fieldType: "url",
            order: 1
        }),
});

export const SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    snapshotRepo: S3_REPO_CONFIG.optional()
        .meta({
            title: "Snapshot Repository",
            description: "S3 repository configuration for storing snapshots",
            order: 5
        }),
    proxy: PROXY_OPTIONS.optional()
        .meta({
            title: "Capture Proxy",
            description: "Configuration for the traffic capture proxy",
            order: 6,
            advanced: true
        })
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
    skipApprovals: z.boolean().default(false).optional()
        .meta({
            title: "Skip Approvals",
            description: "Skip approval steps for this migration configuration",
            order: 0,
            advanced: true
        }),
    fromSource: z.string()
        .meta({
            title: "Source Cluster",
            description: "Name of the source cluster (must match a key in sourceClusters)",
            placeholder: "source-cluster-name",
            order: 1
        }),
    toTarget: z.string()
        .meta({
            title: "Target Cluster",
            description: "Name of the target cluster (must match a key in targetClusters)",
            placeholder: "target-cluster-name",
            order: 2
        }),
    snapshotExtractAndLoadConfigs: z.array(NORMALIZED_SNAPSHOT_MIGRATION_CONFIG).min(1).optional()
        .meta({
            title: "Snapshot Migrations",
            description: "Configure snapshot-based data migration (metadata and/or documents)",
            order: 3,
            itemTitle: "Snapshot Migration",
            addButtonText: "Add Snapshot Migration"
        }),
    replayerConfig: REPLAYER_OPTIONS.optional()
        .meta({
            title: "Traffic Replayer",
            description: "Configure live traffic replay from source to target",
            order: 4
        })
}).meta({
    title: "Migration Configuration",
    description: "Configuration for migrating data from a source to target cluster"
}).refine(data => {
        const names = data.snapshotExtractAndLoadConfigs?.map(m => m.name).filter(s => s);
        return names ? names.length == new Set(names).size : true;
    },
    {message: "names of snapshotExtractAndLoadConfigs items must be unique when they are provided"});

export const SOURCE_CLUSTERS_MAP = z.record(z.string(), SOURCE_CLUSTER_CONFIG)
    .meta({
        title: "Source Clusters",
        description: "Define the source Elasticsearch/OpenSearch clusters to migrate from",
        itemTitle: "Source Cluster",
        addButtonText: "Add Source Cluster"
    });

export const TARGET_CLUSTERS_MAP = z.record(z.string(), TARGET_CLUSTER_CONFIG)
    .meta({
        title: "Target Clusters",
        description: "Define the target OpenSearch clusters to migrate to",
        itemTitle: "Target Cluster",
        addButtonText: "Add Target Cluster"
    });

export const OVERALL_MIGRATION_CONFIG = //validateOptionalDefaultConsistency
(
    z.object({
        skipApprovals: z.boolean().default(false).optional()
            .meta({
                title: "Skip All Approvals",
                description: "Skip all approval steps during migration (use with caution)",
                order: 0,
                advanced: true
            }),
        sourceClusters: SOURCE_CLUSTERS_MAP
            .meta({
                title: "Source Clusters",
                description: "Define the source Elasticsearch/OpenSearch clusters to migrate from",
                order: 1,
                itemTitle: "Source Cluster",
                addButtonText: "Add Source Cluster"
            }),
        targetClusters: TARGET_CLUSTERS_MAP
            .meta({
                title: "Target Clusters",
                description: "Define the target OpenSearch clusters to migrate to",
                order: 2,
                itemTitle: "Target Cluster",
                addButtonText: "Add Target Cluster"
            }),
        migrationConfigs: z.array(NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG).min(1)
            .meta({
                title: "Migration Configurations",
                description: "Define the migration workflows between source and target clusters",
                order: 3,
                itemTitle: "Migration Configuration",
                addButtonText: "Add Migration"
            })
    }).meta({
        title: "Migration Configuration",
        description: "Complete configuration for migrating data between Elasticsearch/OpenSearch clusters"
    }).default({
        skipApprovals: false,
        sourceClusters: {
            source1: {
                endpoint: "",
                version: "ES 7.10.2",
                allowInsecure: false,
                authConfig: {
                    basic: {
                        secretName: "source-secret"
                    }
                }
            }
        },
        targetClusters: {
            target1: {
                endpoint: "",
                version: "OS 2.11.0",
                allowInsecure: false,
                authConfig: {
                    basic: {
                        secretName: "target-secret"
                    }
                }
            }
        },
        migrationConfigs: [
            {
                skipApprovals: false,
                fromSource: "source1",
                toTarget: "target1",
                replayerConfig: {
                    speedupFactor: 1.1,
                    podReplicas: 1,
                    authHeaderOverride: "",
                    loggingConfigurationOverrideConfigMap: "",
                    resources: {
                        limits: {
                            cpu: "2000m",
                            memory: "4000Mi"
                        },
                        requests: {
                            cpu: "2000m",
                            memory: "4000Mi"
                        }
                    }
                }
            }
        ]
    })
);
