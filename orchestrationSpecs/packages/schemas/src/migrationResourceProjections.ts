import {z} from "zod";
import {
    ChecksumDependency,
    FieldMeta,
    USER_CREATE_SNAPSHOT_OPTIONS,
    USER_METADATA_OPTIONS,
    USER_PROXY_OPTIONS,
    USER_REPLAYER_OPTIONS,
    USER_RFS_PROCESS_OPTIONS,
    USER_RFS_WORKFLOW_OPTIONS,
} from "./userSchemas";
import {
    ARGO_METADATA_OPTIONS,
    ARGO_REPLAYER_OPTIONS,
    ARGO_RFS_OPTIONS,
} from "./argoSchemas";

export type ChangeRestriction = "safe" | "gated" | "impossible";
export type ResourceLifecycle = "longRunning" | "terminal" | "approvalGate";
export type FieldInvariant = "nonDecreasing";

export interface ProjectedField {
    resourceKind: string;
    specPath: string[];
    schema?: z.ZodTypeAny;
    sourceSchema?: string;
    sourcePath?: string[];
    changeRestriction: ChangeRestriction;
    checksumFor?: ChecksumDependency[];
    invariant?: FieldInvariant;
}

interface SchemaProjection {
    resourceKind: string;
    sourceSchema: string;
    schema: z.ZodObject<any>;
    policySchemas?: readonly z.ZodObject<any>[];
    prefix?: string;
}

interface InternalProjectedField {
    resourceKind: string;
    specPath: string[];
    schema: z.ZodTypeAny;
    changeRestriction?: ChangeRestriction;
    invariant?: FieldInvariant;
}

export interface ResourceProjection {
    kind: string;
    plural: string;
    lifecycle: ResourceLifecycle;
    terminalPhase?: string;
}

export const RESOURCE_PROJECTIONS: readonly ResourceProjection[] = [
    {kind: "KafkaCluster", plural: "kafkaclusters", lifecycle: "longRunning"},
    {kind: "CapturedTraffic", plural: "capturedtraffics", lifecycle: "longRunning"},
    {kind: "CaptureProxy", plural: "captureproxies", lifecycle: "longRunning"},
    {kind: "DataSnapshot", plural: "datasnapshots", lifecycle: "terminal", terminalPhase: "Completed"},
    {kind: "SnapshotMigration", plural: "snapshotmigrations", lifecycle: "terminal", terminalPhase: "Completed"},
    {kind: "TrafficReplay", plural: "trafficreplays", lifecycle: "longRunning"},
    {kind: "ApprovalGate", plural: "approvalgates", lifecycle: "approvalGate"},
] as const;

const SCHEMA_PROJECTIONS: readonly SchemaProjection[] = [
    {
        resourceKind: "CaptureProxy",
        sourceSchema: "USER_PROXY_OPTIONS",
        schema: USER_PROXY_OPTIONS,
    },
    {
        resourceKind: "TrafficReplay",
        sourceSchema: "ARGO_REPLAYER_OPTIONS",
        schema: ARGO_REPLAYER_OPTIONS,
        policySchemas: [USER_REPLAYER_OPTIONS],
    },
    {
        resourceKind: "DataSnapshot",
        sourceSchema: "USER_CREATE_SNAPSHOT_OPTIONS",
        schema: USER_CREATE_SNAPSHOT_OPTIONS,
    },
    {
        resourceKind: "SnapshotMigration",
        sourceSchema: "ARGO_METADATA_OPTIONS",
        schema: ARGO_METADATA_OPTIONS,
        policySchemas: [USER_METADATA_OPTIONS],
        prefix: "metadataMigration",
    },
    {
        resourceKind: "SnapshotMigration",
        sourceSchema: "ARGO_RFS_OPTIONS",
        schema: ARGO_RFS_OPTIONS,
        policySchemas: [USER_RFS_WORKFLOW_OPTIONS, USER_RFS_PROCESS_OPTIONS],
        prefix: "documentBackfill",
    },
] as const;

const GENERIC_JSON_OBJECT = z.record(z.string(), z.any());

const commonDependsOnFields: InternalProjectedField[] = RESOURCE_PROJECTIONS
    .filter(resource => resource.lifecycle !== "approvalGate")
    .map(resource => ({
        resourceKind: resource.kind,
        specPath: ["dependsOn"],
        schema: z.array(z.string()),
    }));

const INTERNAL_PROJECTED_FIELDS: readonly InternalProjectedField[] = [
    ...commonDependsOnFields,

    {resourceKind: "KafkaCluster", specPath: ["version"], schema: z.string(), changeRestriction: "gated"},
    {resourceKind: "KafkaCluster", specPath: ["auth", "type"], schema: z.enum(["scram-sha-512", "none"]), changeRestriction: "impossible"},
    {resourceKind: "KafkaCluster", specPath: ["nodePool", "replicas"], schema: z.number().min(1), changeRestriction: "gated"},
    {resourceKind: "KafkaCluster", specPath: ["nodePool", "roles"], schema: z.array(z.string()), changeRestriction: "impossible"},
    {resourceKind: "KafkaCluster", specPath: ["nodePool", "storage", "size"], schema: z.string(), changeRestriction: "gated"},
    {resourceKind: "KafkaCluster", specPath: ["nodePool", "storage", "type"], schema: z.string(), changeRestriction: "impossible"},

    {resourceKind: "CapturedTraffic", specPath: ["kafkaClusterName"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "CapturedTraffic", specPath: ["kafkaBrokers"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "CapturedTraffic", specPath: ["topicName"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "CapturedTraffic", specPath: ["partitions"], schema: z.number().min(1), changeRestriction: "gated", invariant: "nonDecreasing"},
    {resourceKind: "CapturedTraffic", specPath: ["replicas"], schema: z.number().min(1), changeRestriction: "gated"},
    {resourceKind: "CapturedTraffic", specPath: ["topicConfig"], schema: GENERIC_JSON_OBJECT, changeRestriction: "gated"},
    {resourceKind: "CapturedTraffic", specPath: ["sourceKind"], schema: z.enum(["proxy", "s3"]), changeRestriction: "impossible"},
    {resourceKind: "CapturedTraffic", specPath: ["s3SourceUri"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "CapturedTraffic", specPath: ["loadStarted"], schema: z.boolean(), changeRestriction: "impossible"},

    {resourceKind: "DataSnapshot", specPath: ["sourceLabel"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceVersion"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceEndpoint"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceAllowInsecure"], schema: z.boolean(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceAuthType"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceAuthBasicSecretName"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceAuthSigv4Region"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceAuthSigv4Service"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceAuthMtlsClientSecretName"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["sourceAuthMtlsCaCertHash"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["snapshotLabel"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["repoName"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["repoPathUri"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["repoAwsRegion"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["repoEndpoint"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["repoS3RoleArn"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["repoUseLocalStack"], schema: z.boolean(), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["mode"], schema: z.enum(["create", "import"]), changeRestriction: "impossible"},
    {resourceKind: "DataSnapshot", specPath: ["solrExternalBackupName"], schema: z.string(), changeRestriction: "impossible"},

    {resourceKind: "SnapshotMigration", specPath: ["migrationLabel"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "SnapshotMigration", specPath: ["sourceVersion"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "SnapshotMigration", specPath: ["sourceLabel"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "SnapshotMigration", specPath: ["targetLabel"], schema: z.string(), changeRestriction: "impossible"},
    {resourceKind: "SnapshotMigration", specPath: ["snapshotLabel"], schema: z.string(), changeRestriction: "impossible"},
] as const;

function prefixedFieldName(prefix: string | undefined, fieldName: string): string {
    if (!prefix) {
        return fieldName;
    }
    return `${prefix}${fieldName.charAt(0).toUpperCase()}${fieldName.slice(1)}`;
}

function fieldMeta(fieldSchema: z.ZodTypeAny): FieldMeta | undefined {
    return fieldSchema.meta() as FieldMeta | undefined;
}

function projectedFieldMeta(projection: SchemaProjection, fieldName: string, fieldSchema: z.ZodTypeAny): FieldMeta | undefined {
    const directMeta = fieldMeta(fieldSchema);
    if (directMeta) {
        return directMeta;
    }
    return projection.policySchemas
        ?.map(policySchema => policySchema.shape[fieldName] as z.ZodTypeAny | undefined)
        .filter((schema): schema is z.ZodTypeAny => schema !== undefined)
        .map(fieldMeta)
        .find((meta): meta is FieldMeta => meta !== undefined);
}

export function collectProjectedFields(): ProjectedField[] {
    const schemaFields = SCHEMA_PROJECTIONS.flatMap(projection =>
        Object.entries(projection.schema.shape).map(([fieldName, fieldSchema]) => {
            const meta = projectedFieldMeta(projection, fieldName, fieldSchema as z.ZodTypeAny);
            return {
                resourceKind: projection.resourceKind,
                specPath: [prefixedFieldName(projection.prefix, fieldName)],
                schema: fieldSchema as z.ZodTypeAny,
                sourceSchema: projection.sourceSchema,
                sourcePath: [fieldName],
                changeRestriction: meta?.changeRestriction ?? "safe",
                checksumFor: meta?.checksumFor,
            } satisfies ProjectedField;
        })
    );

    const internalFields = INTERNAL_PROJECTED_FIELDS.map(field => ({
        ...field,
        changeRestriction: field.changeRestriction ?? "safe",
    } satisfies ProjectedField));

    return [...schemaFields, ...internalFields];
}

export function collectRestrictedProjectedFields(): ProjectedField[] {
    return collectProjectedFields()
        .filter(field => field.changeRestriction === "gated" || field.changeRestriction === "impossible");
}

export function specPathKey(field: Pick<ProjectedField, "resourceKind" | "specPath">): string {
    return `${field.resourceKind}:${field.specPath.join(".")}`;
}
