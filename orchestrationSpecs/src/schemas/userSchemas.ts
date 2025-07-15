import { z } from "zod";

// Define everything in Zod
export const HTTP_AUTH_BASIC = z.object({
    username: z.string(),
    password: z.string(),
});

export const HTTP_AUTH_SIGV4 = z.object({
    region: z.string(),
    service: z.string().default("es").optional(),
});

export const CLUSTER_CONFIG = z.object({
    name: z.string(),
    endpoint: z.string().optional(),
    allow_insecure: z.boolean().optional(),
    version: z.string().optional(),
    authConfig: z.union([HTTP_AUTH_BASIC, HTTP_AUTH_SIGV4]).optional(),
});

export const TARGET_CLUSTER_CONFIG = CLUSTER_CONFIG.extend({
    endpoint: z.string(), // override to required
});

export const SNAPSHOT_MIGRATION_CONFIG = z.object({
    indices: z.array(z.string()),
    migrations: z.array(z.object({
        metadata: z.object({
            mappings: z.object({
                properties: z.record(z.string(), z.any()),
            }),
            documentBackfillConfigs: z.object({
                indices: z.array(z.string()),
                config: z.object({
                    batchSize: z.number(),
                    initialReplicas: z.number(),
                }),
            }),
        }),
    })),
});

export const REPLAYER_CONFIG = z.object({
    speedupFactor: z.number(),
    initialReplicas: z.number(),
});

export const SOURCE_MIGRATION_CONFIG = z.object({
    source: CLUSTER_CONFIG,
    snapshotAndMigrationConfigs: z.array(SNAPSHOT_MIGRATION_CONFIG),
    replayerConfig: REPLAYER_CONFIG,
});
//
// export  = z.object({
//     sessionName: z.string(),
//     sourceMigrations: SOURCE_MIGRATION_CONFIG,
//     targets: z.array(TARGET_CLUSTER_CONFIG),
// });
