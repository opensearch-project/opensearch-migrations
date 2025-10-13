import {
    NORMALIZED_COMPLETE_SNAPSHOT_CONFIG,
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG,
    NORMALIZED_SNAPSHOT_MIGRATION_CONFIG,
    REPLAYER_OPTIONS,
    S3_REPO_CONFIG,
    SOURCE_CLUSTER_CONFIG,
    TARGET_CLUSTER_CONFIG
} from "./userSchemas";
import {z} from "zod";

export const NAMED_SOURCE_CLUSTER_CONFIG =
    SOURCE_CLUSTER_CONFIG.extend({
        name: z.string(), // override to required
});

export const NAMED_TARGET_CLUSTER_CONFIG =
    TARGET_CLUSTER_CONFIG.extend({
        name: z.string().regex(/^[a-zA-Z0-9_]+$/), // override to required
});

export const DENORMALIZED_S3_REPO_CONFIG = S3_REPO_CONFIG.extend({
    useLocalStack: z.boolean().default(false)
});

export const COMPLETE_SNAPSHOT_CONFIG =
    NORMALIZED_COMPLETE_SNAPSHOT_CONFIG.extend({
        repoConfig: DENORMALIZED_S3_REPO_CONFIG  // Replace string reference with actual config
});

export const DYNAMIC_SNAPSHOT_CONFIG =
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG.extend({
        repoConfig: DENORMALIZED_S3_REPO_CONFIG  // Replace string reference with actual config
});

export const SNAPSHOT_MIGRATION_CONFIG =
    NORMALIZED_SNAPSHOT_MIGRATION_CONFIG.omit({snapshotConfig: true}).extend({
        snapshotConfig: DYNAMIC_SNAPSHOT_CONFIG
});

export const PARAMETERIZED_MIGRATION_CONFIG = z.object({
    sourceConfig: NAMED_SOURCE_CLUSTER_CONFIG,
    targetConfig: NAMED_TARGET_CLUSTER_CONFIG,
    snapshotExtractAndLoadConfigArray: z.array(SNAPSHOT_MIGRATION_CONFIG).optional(),
    replayerConfig: REPLAYER_OPTIONS.optional(),
})

export const PARAMETERIZED_MIGRATION_CONFIG_ARRAYS =
    z.array(PARAMETERIZED_MIGRATION_CONFIG);

export type ARGO_WORKFLOW_SCHEMA = z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG_ARRAYS>;
