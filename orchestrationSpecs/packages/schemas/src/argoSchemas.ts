import {
    CLUSTER_CONFIG,
    DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
    getZodKeys,
    HTTP_ENDPOINT_PATTERN,
    KAFKA_CLIENT_CONFIG,
    KAFKA_CLUSTER_CONFIG,
    KAFKA_CLUSTER_CREATION_CONFIG,
    KAFKA_CLUSTERS_MAP,
    NORMALIZED_COMPLETE_SNAPSHOT_CONFIG,
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG,
    S3_REPO_CONFIG,
    SNAPSHOT_MIGRATION_FILTER,
    SOURCE_CLUSTER_CONFIG,
    TARGET_CLUSTER_CONFIG,
    USER_CREATE_SNAPSHOT_OPTIONS,
    USER_METADATA_OPTIONS,
    USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    USER_PROXY_OPTIONS,
    USER_REPLAYER_OPTIONS,
    USER_RFS_OPTIONS,
} from "./userSchemas";
import {z} from "zod";

// DO NOT CHANGE FROM SNAKE CASE - used to create services.yaml files for the console
export const SOURCE_PROXY_CONFIG = z.object({
    name: z.string(),
    endpoint: z.string().regex(new RegExp(HTTP_ENDPOINT_PATTERN)),
    allowInsecure: z.boolean().default(false).optional(),
});

export const CONSOLE_SOURCE_CLUSTER_CONFIG = CLUSTER_CONFIG.safeExtend({
    proxy: SOURCE_PROXY_CONFIG.optional(),
});

// These keys are emitted into the services.yaml-style config used by the console.
export const CONSOLE_SERVICES_CONFIG_FILE = z.object({
    kafka: KAFKA_CLIENT_CONFIG.optional(),
    source_cluster: CONSOLE_SOURCE_CLUSTER_CONFIG.optional(),
    snapshot: NORMALIZED_COMPLETE_SNAPSHOT_CONFIG.optional(),
    target_cluster: TARGET_CLUSTER_CONFIG.optional()
});

function transformField(field: z.ZodTypeAny): z.ZodTypeAny {
    const s = makeOptionalDefaultedFieldsRequired(field);

    if (s instanceof z.ZodOptional && s._def.innerType instanceof z.ZodDefault) {
        // Pattern 1: Optional<Default<T>> -> Default<T>
        return s._def.innerType;
    } else if (s instanceof z.ZodDefault && s._def.innerType instanceof z.ZodOptional) {
        // Pattern 2: Default<Optional<T>> -> Default<T>
        const innerOptional = s._def.innerType;
        const base =
            makeOptionalDefaultedFieldsRequired(innerOptional.unwrap() as z.ZodTypeAny);

        // Reuse the same default value (or factory) that was on the original schema
        const defaultValue = s._def.defaultValue; // may be a value or a () => value
        return (base as any).default(defaultValue);
    }

    return s;
}

function makeOptionalDefaultedFieldsRequired<T extends z.ZodTypeAny>(schema: T): T {
    // Handle objects by transforming their shape
    if (schema instanceof z.ZodObject) {
        const shape = schema.shape;
        const updatedShape: Record<string, z.ZodTypeAny> = {};

        for (const key in shape) {
            updatedShape[key] = transformField(shape[key]);
        }

        return schema.safeExtend(updatedShape) as any as T;
    }

    // Handle arrays: recurse into the element type
    if (schema instanceof z.ZodArray) {
        const element = schema.element;
        const newElement =
            makeOptionalDefaultedFieldsRequired(element as z.ZodTypeAny);
        if (newElement === element) {
            return schema;
        }
        return z.array(newElement) as any as T;
    }

    // Non-object/array leaves are returned as-is
    return schema;
}

export const NAMED_KAFKA_CLUSTER_CONFIG = z.object({
    name: z.string(),
    version: z.string(),
    config: makeOptionalDefaultedFieldsRequired(KAFKA_CLUSTER_CREATION_CONFIG),
    topics: z.array(z.string()).readonly()
});

export const NAMED_SOURCE_CLUSTER_CONFIG =
    makeOptionalDefaultedFieldsRequired(SOURCE_CLUSTER_CONFIG.safeExtend({
        label: z.string(), // override to required
        proxy: SOURCE_PROXY_CONFIG.optional(),
    }));

export const NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO =
    NAMED_SOURCE_CLUSTER_CONFIG.omit({snapshotInfo: true});

export const NAMED_TARGET_CLUSTER_CONFIG =
    makeOptionalDefaultedFieldsRequired(TARGET_CLUSTER_CONFIG.safeExtend({
        label: z.string().regex(/^[a-zA-Z0-9_]+$/), // override to required
    }));

export const DENORMALIZED_S3_REPO_CONFIG =
    makeOptionalDefaultedFieldsRequired(S3_REPO_CONFIG.safeExtend({
        useLocalStack: z.boolean().default(false),
        repoName: z.string(),
    }));

export const COMPLETE_SNAPSHOT_CONFIG =
    makeOptionalDefaultedFieldsRequired(NORMALIZED_COMPLETE_SNAPSHOT_CONFIG.safeExtend({
        label: z.string(),
        repoConfig: DENORMALIZED_S3_REPO_CONFIG  // Replace string reference with actual config
    }));

export const DYNAMIC_SNAPSHOT_CONFIG =
    makeOptionalDefaultedFieldsRequired(NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG
        .omit({repoName: true})
        .safeExtend({
            repoConfig: DENORMALIZED_S3_REPO_CONFIG,  // Replace string reference with actual config
            label: z.string()
    }));

export const ARGO_METADATA_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_METADATA_OPTIONS.omit({skipEvaluateApproval: true, skipMigrateApproval: true})
);
export const ARGO_METADATA_WORKFLOW_OPTION_KEYS = getZodKeys(ARGO_METADATA_OPTIONS.pick({
    jvmArgs: true,
    loggingConfigurationOverrideConfigMap: true,
}));

export const ARGO_CREATE_SNAPSHOT_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_CREATE_SNAPSHOT_OPTIONS.omit({snapshotPrefix: true})
);
export const ARGO_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS = getZodKeys(ARGO_CREATE_SNAPSHOT_OPTIONS.pick({
    jvmArgs: true,
    loggingConfigurationOverrideConfigMap: true,
}));

export const ARGO_RFS_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_RFS_OPTIONS.in.omit({skipApproval: true})
);
export const ARGO_RFS_WORKFLOW_OPTION_KEYS = getZodKeys(ARGO_RFS_OPTIONS.pick({
    podReplicas: true,
    jvmArgs: true,
    loggingConfigurationOverrideConfigMap: true,
    useTargetClusterForWorkCoordination: true,
    resources: true,
}));

export const ARGO_PROXY_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_PROXY_OPTIONS
);
export const ARGO_PROXY_WORKFLOW_OPTION_KEYS = getZodKeys(ARGO_PROXY_OPTIONS.pick({
    loggingConfigurationOverrideConfigMap: true,
    internetFacing: true,
    podReplicas: true,
    resources: true,
}));

export const ARGO_REPLAYER_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_REPLAYER_OPTIONS
);
export const ARGO_REPLAYER_WORKFLOW_OPTION_KEYS = getZodKeys(ARGO_REPLAYER_OPTIONS.pick({
    jvmArgs: true,
    loggingConfigurationOverrideConfigMap: true,
    podReplicas: true,
    resources: true,
}));

export const PER_INDICES_SNAPSHOT_MIGRATION_CONFIG = z.object({
    // override label because when not specified, we'll use an integer,
    // which purposefully conflicts with the user schema to prevent collision
    label: z.string(),
    metadataMigrationConfig: ARGO_METADATA_OPTIONS.optional(),
    documentBackfillConfig: ARGO_RFS_OPTIONS.optional()
}).refine(data =>
        data.metadataMigrationConfig !== undefined ||
        data.documentBackfillConfig !== undefined,
    {message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided"});

export const SNAPSHOT_NAME_RESOLUTION = z.union([
    z.object({ externalSnapshotName: z.string() }),
    z.object({ dataSnapshotResourceName: z.string() })
]);

export const SNAPSHOT_REPO_CONFIG = z.object({
    label: z.string(),
    repoConfig: DENORMALIZED_S3_REPO_CONFIG
});

export const SNAPSHOT_MIGRATION_CONFIG = z.object({
    label: z.string(), // from the record of the user config
    snapshotNameResolution: SNAPSHOT_NAME_RESOLUTION,
    migrations: z.array(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG).min(1),
    sourceVersion: z.string(),
    sourceLabel: z.string(),
    targetConfig: NAMED_TARGET_CLUSTER_CONFIG,
    snapshotConfig: SNAPSHOT_REPO_CONFIG,
    // For Solr/HTTP API sources (no snapshot)
    sourceEndpoint: z.string().optional(),
    sourceAllowInsecure: z.boolean().optional(),
    sourceAuth: z.any().optional(),
});

export const NAMED_KAFKA_CLIENT_CONFIG =
    makeOptionalDefaultedFieldsRequired(KAFKA_CLIENT_CONFIG).extend({
        topicSpecOverrides: z.object({
            partitions: z.number(),
            replicas: z.number(),
            config: z.record(z.string(), z.any()),
        }).default(DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES),
        label: z.string()
    });

export const DENORMALIZED_PROXY_CONFIG = z.object({
    name: z.string(),
    kafkaConfig: NAMED_KAFKA_CLIENT_CONFIG,
    sourceEndpoint: z.string(),
    sourceAllowInsecure: z.boolean().default(false),
    proxyConfig: ARGO_PROXY_OPTIONS
});

export const PER_SOURCE_CREATE_SNAPSHOTS_CONFIG = z.object({
    label: z.string(),
    snapshotPrefix: z.string(),
    config: ARGO_CREATE_SNAPSHOT_OPTIONS,
    repo: DENORMALIZED_S3_REPO_CONFIG,
    semaphoreConfigMapName: z.string(),
    semaphoreKey: z.string(),
    dependsOnProxySetups: z.array(z.string())
});

export const DENORMALIZED_CREATE_SNAPSHOTS_CONFIG = z.object({
    createSnapshotConfig: z.array(PER_SOURCE_CREATE_SNAPSHOTS_CONFIG).min(1),
    sourceConfig: NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO
});

export const DENORMALIZED_REPLAY_CONFIG = z.object({
    dependsOnSnapshotMigrations: z.array(SNAPSHOT_MIGRATION_FILTER),
    fromProxy: z.string(),
    kafkaClusterName: z.string(),
    kafkaConfig: NAMED_KAFKA_CLIENT_CONFIG,
    replayerConfig: ARGO_REPLAYER_OPTIONS,
    toTarget: NAMED_TARGET_CLUSTER_CONFIG
});

export const ARGO_MIGRATION_CONFIG = z.object({
    kafkaClusters: z.array(NAMED_KAFKA_CLUSTER_CONFIG).min(1).optional(),
    proxies: z.array(DENORMALIZED_PROXY_CONFIG).default([]),
    snapshots: z.array(DENORMALIZED_CREATE_SNAPSHOTS_CONFIG).default([]),
    snapshotMigrations: z.array(SNAPSHOT_MIGRATION_CONFIG).default([]),
    trafficReplays: z.array(DENORMALIZED_REPLAY_CONFIG).default([]),
});

export type ARGO_WORKFLOW_SCHEMA = z.infer<typeof ARGO_MIGRATION_CONFIG>;
