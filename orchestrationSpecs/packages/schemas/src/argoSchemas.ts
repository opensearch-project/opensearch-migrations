import {
    CLUSTER_CONFIG,
    KAFKA_SERVICES_CONFIG,
    NORMALIZED_COMPLETE_SNAPSHOT_CONFIG,
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG,
    NORMALIZED_SNAPSHOT_MIGRATION_CONFIG,
    REPLAYER_OPTIONS,
    S3_REPO_CONFIG,
    SOURCE_CLUSTER_CONFIG,
    TARGET_CLUSTER_CONFIG, USER_METADATA_OPTIONS, USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG, USER_RFS_OPTIONS
} from "./userSchemas";
import {z} from "zod";

// DO NOT CHANGE FROM SNAKE CASE - used to create services.yaml files for the console
export const CONSOLE_SERVICES_CONFIG_FILE = z.object({
    kafka: KAFKA_SERVICES_CONFIG.optional(),
    source_cluster: CLUSTER_CONFIG.optional(),
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

export const NAMED_SOURCE_CLUSTER_CONFIG =
    makeOptionalDefaultedFieldsRequired(SOURCE_CLUSTER_CONFIG.extend({
        name: z.string(), // override to required
    }));

export const NAMED_TARGET_CLUSTER_CONFIG =
    makeOptionalDefaultedFieldsRequired(TARGET_CLUSTER_CONFIG.extend({
        name: z.string().regex(/^[a-zA-Z0-9_]+$/), // override to required
    }));

export const DENORMALIZED_S3_REPO_CONFIG =
    makeOptionalDefaultedFieldsRequired(S3_REPO_CONFIG.extend({
        useLocalStack: z.boolean().default(false)
    }));

export const COMPLETE_SNAPSHOT_CONFIG =
    makeOptionalDefaultedFieldsRequired(NORMALIZED_COMPLETE_SNAPSHOT_CONFIG.extend({
        repoConfig: DENORMALIZED_S3_REPO_CONFIG  // Replace string reference with actual config
    }));

export const DYNAMIC_SNAPSHOT_CONFIG =
    makeOptionalDefaultedFieldsRequired(NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG.extend({
        repoConfig: DENORMALIZED_S3_REPO_CONFIG  // Replace string reference with actual config
    }));

export const METADATA_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_METADATA_OPTIONS.omit({skipEvaluateApproval: true, skipMigrateApproval: true})
);

export const RFS_OPTIONS = makeOptionalDefaultedFieldsRequired(
    USER_RFS_OPTIONS.in.omit({skipApproval: true})
);

export const PER_INDICES_SNAPSHOT_MIGRATION_CONFIG =
    makeOptionalDefaultedFieldsRequired(USER_PER_INDICES_SNAPSHOT_MIGRATION_CONFIG
        .omit({name: true, metadataMigrationConfig: true, documentBackfillConfig: true}).safeExtend({
            name: z.string(),
            metadataMigrationConfig: METADATA_OPTIONS.optional(),
            documentBackfillConfig: RFS_OPTIONS.optional()
        })
    ).refine(data =>
            data.metadataMigrationConfig !== undefined ||
            data.documentBackfillConfig !== undefined,
        {message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided"});

export const SNAPSHOT_MIGRATION_CONFIG =
    makeOptionalDefaultedFieldsRequired(NORMALIZED_SNAPSHOT_MIGRATION_CONFIG
        .omit({name: true, snapshotConfig: true, migrations: true}).extend({
            name: z.string(),
            snapshotConfig: DYNAMIC_SNAPSHOT_CONFIG,
            migrations: z.array(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG).min(1)
        }));

export const PARAMETERIZED_MIGRATION_CONFIG =
    z.object({
        sourceConfig: NAMED_SOURCE_CLUSTER_CONFIG,
        targetConfig: NAMED_TARGET_CLUSTER_CONFIG,
        snapshotExtractAndLoadConfigArray: z.array(SNAPSHOT_MIGRATION_CONFIG).optional(),
        replayerConfig: makeOptionalDefaultedFieldsRequired(REPLAYER_OPTIONS.optional()),
    });

export const PARAMETERIZED_MIGRATION_CONFIG_ARRAYS =
    z.array(PARAMETERIZED_MIGRATION_CONFIG);

export type ARGO_WORKFLOW_SCHEMA = z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS>;
