import {
    NORMALIZED_COMPLETE_SNAPSHOT_CONFIG,
    NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG, S3_REPO_CONFIG,
    NORMALIZED_SNAPSHOT_MIGRATION_CONFIG,
    TARGET_CLUSTER_CONFIG, REPLAYER_OPTIONS, SOURCE_CLUSTER_CONFIG
} from "./userSchemas";
import {z} from "zod";

export const NAMED_SOURCE_CLUSTER_CONFIG = SOURCE_CLUSTER_CONFIG.extend({
    name: z.string(), // override to required
});

export const NAMED_TARGET_CLUSTER_CONFIG = TARGET_CLUSTER_CONFIG.extend({
    name: z.string(), // override to required
});

export const COMPLETE_SNAPSHOT_CONFIG = NORMALIZED_COMPLETE_SNAPSHOT_CONFIG.omit({ repoConfigName: true }).extend({
    repoConfig: S3_REPO_CONFIG  // Replace string reference with actual config
});

export const DYNAMIC_SNAPSHOT_CONFIG = NORMALIZED_DYNAMIC_SNAPSHOT_CONFIG.omit({ repoConfigName: true }).extend({
    repoConfig: S3_REPO_CONFIG  // Replace string reference with actual config
});

export const SNAPSHOT_MIGRATION_CONFIG = NORMALIZED_SNAPSHOT_MIGRATION_CONFIG.omit({snapshotConfig: true}).extend({
    snapshotConfig: DYNAMIC_SNAPSHOT_CONFIG
});

export const PARAMETERIZED_MIGRATION_CONFIG = z.object({
    sourceConfig: NAMED_SOURCE_CLUSTER_CONFIG,
    targetConfig: NAMED_TARGET_CLUSTER_CONFIG,
    snapshotExtractAndLoadConfigArray: z.array(SNAPSHOT_MIGRATION_CONFIG),
    replayerConfig: REPLAYER_OPTIONS,
})