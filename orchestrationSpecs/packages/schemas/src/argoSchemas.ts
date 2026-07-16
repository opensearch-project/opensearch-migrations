import {
    CLUSTER_CONFIG,
    DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES,
    HTTP_ENDPOINT_PATTERN,
    KAFKA_CLIENT_CONFIG,
    KAFKA_CLUSTER_CONFIG,
    KAFKA_CLUSTER_CREATION_CONFIG,
    KAFKA_CLUSTERS_MAP,
    NORMALIZED_COMPLETE_SNAPSHOT_CONFIG,
    REPO_CONFIG,
    SNAPSHOT_MIGRATION_FILTER,
    SOLR_COLLECTIONS_OPTION,
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

// zod ≥4.4 makes structural ops (.omit()/.pick()) throw when the object schema
// still carries refinements (.refine/.superRefine/.transform), instead of
// silently discarding them as pre-4.4 did. The Argo-side schemas below are
// resolved/denormalized projections that intentionally shed the user-level
// cross-field refinements, so rebuilding a plain object from the shape before
// the structural op restores the prior (and intended) behavior.
function dropRefinements<T extends z.ZodRawShape>(schema: z.ZodObject<T>): z.ZodObject<T> {
    return z.object(schema.shape);
}

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

export const ARGO_FILE_SOURCE_VOLUME = z.union([
    z.object({
        name: z.string().min(1),
        configMap: z.object({
            name: z.string().min(1)
        }).strict()
    }).strict(),
    z.object({
        name: z.string().min(1),
        image: z.object({
            reference: z.string().min(1),
            pullPolicy: z.enum(["Always", "Never", "IfNotPresent"]).default("IfNotPresent").optional()
        }).strict()
    }).strict(),
]);

export const ARGO_FILE_SOURCE_VOLUME_MOUNT = z.object({
    name: z.string().min(1),
    mountPath: z.string().min(1),
    readOnly: z.literal(true).default(true).optional()
}).strict();

const FILE_SOURCE_RESOLVED_FIELDS = {
    fileSourceVolumes: z.array(ARGO_FILE_SOURCE_VOLUME).default([]).optional(),
    fileSourceVolumeMounts: z.array(ARGO_FILE_SOURCE_VOLUME_MOUNT).default([]).optional(),
} as const;

export const NAMED_KAFKA_CLUSTER_CONFIG = z.object({
    name: z.string(),
    version: z.string(),
    config: makeOptionalDefaultedFieldsRequired(KAFKA_CLUSTER_CREATION_CONFIG),
    topics: z.array(z.string()).readonly(),
    configChecksum: z.string(),
    resourceUid: z.string(),
});

export const NAMED_SOURCE_CLUSTER_CONFIG =
    makeOptionalDefaultedFieldsRequired(SOURCE_CLUSTER_CONFIG.safeExtend({
        label: z.string(), // override to required
        proxy: SOURCE_PROXY_CONFIG.optional(),
    }));

export const NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO =
    dropRefinements(NAMED_SOURCE_CLUSTER_CONFIG).omit({snapshotInfo: true});

export const NAMED_TARGET_CLUSTER_CONFIG =
    makeOptionalDefaultedFieldsRequired(TARGET_CLUSTER_CONFIG.safeExtend({
        label: z.string().regex(/^[a-zA-Z0-9_]+$/), // override to required
    }));

export const DENORMALIZED_REPO_CONFIG =
    makeOptionalDefaultedFieldsRequired(REPO_CONFIG.safeExtend({
        useLocalStack: z.boolean().default(false),
        repoName: z.string(),
    }));

export const CLUSTER_CONNECTION_IDENTITY = z.object({
    label: z.string(),
    version: z.string(),
    endpoint: z.string(),
    allowInsecure: z.boolean(),
    authType: z.string(),
    authBasicSecretName: z.string(),
    authSigv4Region: z.string(),
    authSigv4Service: z.string(),
    authMtlsClientSecretName: z.string(),
    authMtlsCaCertHash: z.string(),
});

export const COMPLETE_SNAPSHOT_CONFIG =
    makeOptionalDefaultedFieldsRequired(NORMALIZED_COMPLETE_SNAPSHOT_CONFIG.safeExtend({
        label: z.string(),
        repoConfig: DENORMALIZED_REPO_CONFIG  // Replace string reference with actual config
    }));

export const ARGO_CREATE_SNAPSHOT_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_CREATE_SNAPSHOT_OPTIONS.omit({snapshotPrefix: true}).extend({
        mode: z.enum(["create", "import"]).default("create").optional()
            .describe("Workflow-internal snapshot/backup mode. 'create' (default) produces a new snapshot or " +
                "backup of the source. 'import' is used only for Solr external-backup prepare: it runs " +
                "CreateSnapshot --mode import to upload the source schema into an externally-managed backup's " +
                "repository without creating a new backup. Not user-configurable; set by the config transformer."),
        // Solr-only, glue-layer field. The config transformer folds the user-facing Solr
        // `collectionAllowlist` into this on the shared create-snapshot config. It lives here rather
        // than in USER_CREATE_SNAPSHOT_OPTIONS so the user-facing ES/OS snapshot schema does not
        // expose a Solr-only field (ES/OS users use indexAllowlist; Solr users use collectionAllowlist).
        solrCollections: SOLR_COLLECTIONS_OPTION.changeRestriction('impossible'),
    })
);
export const ARGO_CREATE_SNAPSHOT_WORKFLOW_OPTION_KEYS = [
    "jvmArgs",
    "loggingConfigurationOverrideConfigMap",
] as const satisfies readonly (keyof z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>)[];

export const WORKFLOW_SNAPSHOT_NAME_CONFIG = z.union([
    z.object({
        externallyManagedSnapshotName: z.string()
            .describe("Name of a pre-existing snapshot or backup in the configured repository."),
    }),
    z.object({
        createSnapshotConfig: ARGO_CREATE_SNAPSHOT_OPTIONS
            .describe("Workflow-internal configuration for creating a snapshot or Solr backup."),
    }),
]).describe("Workflow-internal snapshot or backup name source.");

export const DENORMALIZED_WORKFLOW_SNAPSHOT_CONFIG =
    makeOptionalDefaultedFieldsRequired(z.object({
        config: WORKFLOW_SNAPSHOT_NAME_CONFIG,
        repoConfig: DENORMALIZED_REPO_CONFIG,
        label: z.string()
    }));

export const ARGO_METADATA_OPTIONS = makeOptionalDefaultedFieldsRequired(
    dropRefinements(USER_METADATA_OPTIONS).omit({
        metadataTransforms: true,
    }).extend(FILE_SOURCE_RESOLVED_FIELDS)
);
export const ARGO_METADATA_WORKFLOW_OPTION_KEYS = [
    "jvmArgs",
    "loggingConfigurationOverrideConfigMap",
    "skipEvaluateApproval",
    "skipMigrateApproval",
    "fileSourceVolumes",
    "fileSourceVolumeMounts",
] as const satisfies readonly (keyof z.infer<typeof ARGO_METADATA_OPTIONS>)[];

export const ARGO_RFS_OPTIONS = makeOptionalDefaultedFieldsRequired(
    dropRefinements(USER_RFS_OPTIONS.in).omit({
        documentTransforms: true,
    }).extend(FILE_SOURCE_RESOLVED_FIELDS)
);
export const ARGO_RFS_WORKFLOW_OPTION_KEYS = [
    "podReplicas",
    "minPodReplicas",
    "jvmArgs",
    "loggingConfigurationOverrideConfigMap",
    "skipApproval",
    "useTargetClusterForWorkCoordination",
    "resources",
    "fileSourceVolumes",
    "fileSourceVolumeMounts",
] as const satisfies readonly (keyof z.infer<typeof ARGO_RFS_OPTIONS>)[];

// Fields config lowering adds on top of the user-facing proxy schema, named as a
// single shape so ARGO_PROXY_OPTIONS and ARGO_PROXY_RESOLVED_ONLY_KEYS share one
// source of truth.
const PROXY_RESOLVED_FIELDS = {
    sslTrustCertFile: z.string().min(1).optional()
        .describe("Resolved mount path of tls.clientAuth.trustedClientCaFile, passed to the proxy process. Stripped from the CaptureProxy CR."),
    sslTrustCertPem: z.string().min(1).optional()
        .describe("Inline PEM from tls.clientAuth.trustedClientCaPem, passed to the proxy process. Stripped from the CaptureProxy CR."),
    sslTrustCertPemEnvVar: z.string().min(1).optional()
        .describe("Name of the env var carrying the trusted-client-CA PEM into the proxy process. Stripped from the CaptureProxy CR."),
    requireClientAuth: z.boolean().optional()
        .describe("Flattened tls.clientAuth.required for the proxy process. Stripped from the CaptureProxy CR."),
    ...FILE_SOURCE_RESOLVED_FIELDS,
} as const;

export const ARGO_PROXY_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_PROXY_OPTIONS.safeExtend(PROXY_RESOLVED_FIELDS)
);
export const ARGO_PROXY_WORKFLOW_OPTION_KEYS = [
    "loggingConfigurationOverrideConfigMap",
    "serviceType",
    "internetFacing",
    "podReplicas",
    "minPodReplicas",
    "resources",
    "tls",
    "sslTrustCertPem",
    "fileSourceVolumes",
    "fileSourceVolumeMounts",
] as const satisfies readonly (keyof z.infer<typeof ARGO_PROXY_OPTIONS>)[];

// Resolved-only keys are the fields PROXY_RESOLVED_FIELDS adds over the user
// schema, i.e. keys(ARGO_PROXY_OPTIONS) - keys(USER_PROXY_OPTIONS). The CaptureProxy
// CRD is projected from USER_PROXY_OPTIONS, so these are the top-level keys the CRD
// does not define and that must be stripped from the CR. Derived from the shape so
// the set stays in sync as PROXY_RESOLVED_FIELDS changes.
export const ARGO_PROXY_RESOLVED_ONLY_KEYS =
    Object.keys(PROXY_RESOLVED_FIELDS) as (keyof typeof PROXY_RESOLVED_FIELDS)[];

// All keys stripped from the CaptureProxy custom resource: workflow-option fields,
// which makeCaptureProxyManifest re-adds explicitly as spec fields, plus the
// resolved-only fields above. Deduped because the two sets overlap, so the rendered
// sprig.omit lists each key once.
export const ARGO_PROXY_CR_OMITTED_KEYS = [
    ...new Set([...ARGO_PROXY_WORKFLOW_OPTION_KEYS, ...ARGO_PROXY_RESOLVED_ONLY_KEYS]),
];

export const ARGO_REPLAYER_OPTIONS = makeOptionalDefaultedFieldsRequired(
    dropRefinements(USER_REPLAYER_OPTIONS).omit({
        requestTransforms: true,
        tupleTransforms: true,
    }).extend(FILE_SOURCE_RESOLVED_FIELDS)
);
export const ARGO_REPLAYER_WORKFLOW_OPTION_KEYS = [
    "jvmArgs",
    "loggingConfigurationOverrideConfigMap",
    "podReplicas",
    "minPodReplicas",
    "useLocalStack",
    "resources",
    "fileSourceVolumes",
    "fileSourceVolumeMounts",
] as const satisfies readonly (keyof z.infer<typeof ARGO_REPLAYER_OPTIONS>)[];

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
    // Solr import-prepare: an externally-managed backup that still needs the schema uploaded by
    // CreateSnapshot --mode import. A DataSnapshot CR (dataSnapshotResourceName) is created so the
    // migration waits for the import step to finish, but the resolved name used downstream is the
    // external backup name (externalSnapshotName), not a CR-resolved generated name.
    z.object({ dataSnapshotResourceName: z.string(), externalSnapshotName: z.string() }).strict(),
    // External snapshot with no workflow-side preparation (ES/OS):
    // the migration uses the external name directly and waits on nothing.
    z.object({ externalSnapshotName: z.string() }).strict(),
    // Workflow-generated snapshot: the migration waits on the DataSnapshot CR named here, then
    // reads the resolved snapshot name from the CR's status.
    z.object({ dataSnapshotResourceName: z.string() }).strict()
]);

export const SNAPSHOT_REPO_CONFIG = z.object({
    label: z.string(),
    repoConfig: DENORMALIZED_REPO_CONFIG
});

export const SNAPSHOT_MIGRATION_CONFIG = z.object({
    label: z.string(), // from the record of the user config
    migrationLabel: z.string(),
    snapshotNameResolution: SNAPSHOT_NAME_RESOLUTION,
    snapshotConfigChecksum: z.string(),
    metadataMigrationConfig: ARGO_METADATA_OPTIONS.optional(),
    documentBackfillConfig: ARGO_RFS_OPTIONS.optional(),
    sourceConnectionIdentity: CLUSTER_CONNECTION_IDENTITY,
    targetConnectionIdentity: CLUSTER_CONNECTION_IDENTITY,
    sourceVersion: z.string(),
    sourceLabel: z.string(),
    targetConfig: NAMED_TARGET_CLUSTER_CONFIG,
    snapshotConfig: SNAPSHOT_REPO_CONFIG,
    // For Solr/HTTP API sources (no snapshot)
    sourceEndpoint: z.string().optional(),
    sourceAllowInsecure: z.boolean().optional(),
    sourceAuth: z.any().optional(),
    configChecksum: z.string(),
    checksumForReplayer: z.string(),
    workloadIdentityChecksum: z.string(),
    resourceUid: z.string(),
    resourceName: z.string(),
});

export const NAMED_KAFKA_CLIENT_CONFIG =
    makeOptionalDefaultedFieldsRequired(KAFKA_CLIENT_CONFIG).extend({
        topicSpecOverrides: z.object({
            partitions: z.number(),
            replicas: z.number(),
            config: z.record(z.string(), z.any()),
        }).default(DEFAULT_KAFKA_TOPIC_SPEC_OVERRIDES),
        label: z.string(),
        configChecksum: z.string(),
        clusterResourceUid: z.string().optional(),
    });

export const DENORMALIZED_PROXY_CONFIG = z.object({
    name: z.string(),
    sourceConfig: NAMED_SOURCE_CLUSTER_CONFIG,
    sourceConnectionIdentity: CLUSTER_CONNECTION_IDENTITY,
    kafkaConfig: NAMED_KAFKA_CLIENT_CONFIG,
    proxyConfig: ARGO_PROXY_OPTIONS,
    configChecksum: z.string(),
    topicConfigChecksum: z.string(),
    checksumForSnapshot: z.string(),
    checksumForReplayer: z.string(),
    // When true, the proxy-setup approval gate is auto-skipped. The config
    // processor resolves this from proxy-level skipApproval first, then global
    // skipApprovals, then false.
    skipApproval: z.boolean().default(false),
    resourceUid: z.string(),
});

export const DENORMALIZED_PROXY_SETUP_CONFIG = DENORMALIZED_PROXY_CONFIG.omit({
    skipApproval: true,
});

export const PER_SOURCE_CREATE_SNAPSHOTS_CONFIG = z.object({
    label: z.string(),
    snapshotPrefix: z.string(),
    sourceConnectionIdentity: CLUSTER_CONNECTION_IDENTITY,
    config: ARGO_CREATE_SNAPSHOT_OPTIONS,
    repo: DENORMALIZED_REPO_CONFIG,
    semaphoreConfigMapName: z.string(),
    semaphoreKey: z.string(),
    dependsOnProxySetups: z.array(z.object({
        name: z.string(),
        configChecksum: z.string(),
    })),
    // Resolved dependency-graph edge names (the proxy-setup names) written to the DataSnapshot
    // CR's spec.dependsOn by tryApply. Kept as a flat name list so the apply manifest does not
    // need to map over dependsOnProxySetups; the reset CLI reads spec.dependsOn from the live CR.
    dependsOn: z.array(z.string()),
    configChecksum: z.string(),
    resourceUid: z.string(),
    // Solr external-backup prepare only. When present, this item does NOT create
    // a new backup; CreateSnapshot runs with `--mode import` against the
    // externally-managed backup named here, uploading the source schema into the
    // repo. `config` carries mode:"import" and the prepare options.
    solrExternalBackupName: z.string().optional(),
});

export const ENRICHED_SNAPSHOT_MIGRATION_FILTER = SNAPSHOT_MIGRATION_FILTER.extend({
    migrationLabel: z.string(),
    configChecksum: z.string(),
    resourceName: z.string(),
});

export const DENORMALIZED_CREATE_SNAPSHOTS_CONFIG = z.object({
    createSnapshotConfig: z.array(PER_SOURCE_CREATE_SNAPSHOTS_CONFIG).min(1),
    sourceConfig: NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO
});

export const DENORMALIZED_REPLAY_CONFIG = z.object({
    name: z.string(),
    sourceLabel: z.string(),
    dependsOn: z.array(z.string()),
    dependsOnSnapshotMigrations: z.array(ENRICHED_SNAPSHOT_MIGRATION_FILTER),
    fromCapturedTraffic: z.string(),
    fromCapturedTrafficSourceKind: z.enum(["proxy", "s3"]),
    fromCapturedTrafficConfigChecksum: z.string(),
    kafkaClusterName: z.string(),
    kafkaConfig: NAMED_KAFKA_CLIENT_CONFIG,
    replayerConfig: ARGO_REPLAYER_OPTIONS,
    toTarget: NAMED_TARGET_CLUSTER_CONFIG,
    targetConnectionIdentity: CLUSTER_CONNECTION_IDENTITY,
    configChecksum: z.string(),
    resourceUid: z.string(),
});

// One-time S3 → Kafka topic load. Created from a traffic.s3Sources entry.
// Workflow runs the loader exactly once per CapturedTraffic resource;
// re-runs are blocked by the resource's lifecycle (loadStarted gate +
// lock-on-complete VAP).
export const DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG = z.object({
    name: z.string(),
    sourceLabel: z.string(),
    s3Uri: z.string(),
    awsRegion: z.string(),
    endpoint: z.string().default("").optional(),
    kafkaClusterName: z.string(),
    kafkaConfig: NAMED_KAFKA_CLIENT_CONFIG,
    topicConfigChecksum: z.string(),
    checksumForReplayer: z.string(),
    configChecksum: z.string(),
    resourceUid: z.string(),
});

function makeResourceUidOptional<
    T extends z.ZodRawShape & { resourceUid: z.ZodString }
>(schema: z.ZodObject<T>) {
    return schema.extend({
        resourceUid: z.string().optional(),
    });
}

export const ARGO_MIGRATION_CONFIG = z.object({
    kafkaClusters: z.array(NAMED_KAFKA_CLUSTER_CONFIG).min(1).optional(),
    proxies: z.array(DENORMALIZED_PROXY_CONFIG).default([]),
    s3TrafficLoaders: z.array(DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG).default([]),
    snapshots: z.array(DENORMALIZED_CREATE_SNAPSHOTS_CONFIG).default([]),
    snapshotMigrations: z.array(SNAPSHOT_MIGRATION_CONFIG).default([]),
    trafficReplays: z.array(DENORMALIZED_REPLAY_CONFIG).default([]),
});

function makePreEnrichMigrationConfigSchema() {
    const preEnrichCreateSnapshotsConfig = DENORMALIZED_CREATE_SNAPSHOTS_CONFIG.extend({
        createSnapshotConfig: z.array(
            PER_SOURCE_CREATE_SNAPSHOTS_CONFIG.extend({
                resourceUid: z.string().optional(),
            })
        ).min(1),
    });

    return ARGO_MIGRATION_CONFIG.extend({
        kafkaClusters: z.array(
            makeResourceUidOptional(NAMED_KAFKA_CLUSTER_CONFIG)
        ).min(1).optional(),
        proxies: z.array(
            makeResourceUidOptional(DENORMALIZED_PROXY_CONFIG)
        ).default([]),
        s3TrafficLoaders: z.array(
            makeResourceUidOptional(DENORMALIZED_S3_TRAFFIC_LOADER_CONFIG)
        ).default([]),
        snapshots: z.array(preEnrichCreateSnapshotsConfig).default([]),
        snapshotMigrations: z.array(
            makeResourceUidOptional(SNAPSHOT_MIGRATION_CONFIG)
        ).default([]),
        trafficReplays: z.array(
            makeResourceUidOptional(DENORMALIZED_REPLAY_CONFIG)
        ).default([]),
    });
}

export const ARGO_MIGRATION_CONFIG_PRE_ENRICH = makePreEnrichMigrationConfigSchema();

export type ARGO_WORKFLOW_SCHEMA = z.infer<typeof ARGO_MIGRATION_CONFIG>;
