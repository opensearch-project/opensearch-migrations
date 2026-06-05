import { describe, it, expect } from '@jest/globals';
import { MigrationConfigTransformer } from "../src";
import { OVERALL_MIGRATION_CONFIG } from "@opensearch-migrations/schemas";
import { z } from "zod";

/**
 * Stage 4 GCS routing tests: verify that source clusters with GCS repos flow
 * into the parallel `snapshotsGcs` and `snapshotMigrationsGcs` output fields
 * rather than the S3 `snapshots`/`snapshotMigrations` arrays.
 */
describe('GCS repo routing', () => {
    const transformer = new MigrationConfigTransformer();

    const baseGcsConfig: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
        sourceClusters: {
            gcs_source: {
                endpoint: "https://gcs-source.example.com",
                allowInsecure: true,
                version: "ES 7.10.2",
                authConfig: { basic: { secretName: "src-creds" } },
                snapshotInfo: {
                    repos: {
                        default: {
                            type: "gcs",
                            gcsRepoPathUri: "gs://my-bucket/snapshots",
                        }
                    },
                    snapshots: {
                        snap1: {
                            repoName: "default",
                            config: { createSnapshotConfig: { snapshotPrefix: "snap1" } }
                        }
                    }
                }
            }
        },
        targetClusters: {
            target: {
                endpoint: "https://target.example.com",
                allowInsecure: true,
                authConfig: { basic: { secretName: "target-creds" } }
            }
        },
        snapshotMigrationConfigs: [
            {
                fromSource: "gcs_source",
                toTarget: "target",
                perSnapshotConfig: {
                    snap1: [{
                        metadataMigrationConfig: { skipEvaluateApproval: true, skipMigrateApproval: true }
                    }]
                }
            }
        ]
    };

    it('routes GCS createSnapshot configs into snapshotsGcs (not snapshots)', async () => {
        const result = await transformer.processFromObject(baseGcsConfig);
        expect(result.snapshots ?? []).toEqual([]);
        expect(result.snapshotsGcs).toBeDefined();
        expect(result.snapshotsGcs!.length).toBe(1);
        const ss = result.snapshotsGcs![0];
        expect(ss.sourceConfig.label).toBe("gcs_source");
        expect(ss.createSnapshotConfig).toHaveLength(1);
        expect(ss.createSnapshotConfig[0].label).toBe("snap1");
        expect(ss.createSnapshotConfig[0].repo.type).toBe("gcs");
        expect(ss.createSnapshotConfig[0].configChecksum).toBeTruthy();
    });

    it('routes GCS migrations into snapshotMigrationsGcs and resolves snapshotConfigChecksum', async () => {
        const result = await transformer.processFromObject(baseGcsConfig);
        expect(result.snapshotMigrations ?? []).toEqual([]);
        expect(result.snapshotMigrationsGcs).toBeDefined();
        expect(result.snapshotMigrationsGcs!.length).toBe(1);
        const m = result.snapshotMigrationsGcs![0];
        expect(m.sourceLabel).toBe("gcs_source");
        expect(m.label).toBe("snap1");
        // snapshotChecksums map must include GCS entries so the migration can resolve its parent.
        expect(m.snapshotConfigChecksum).toBeTruthy();
        expect(m.snapshotConfigChecksum)
            .toBe(result.snapshotsGcs![0].createSnapshotConfig[0].configChecksum);
    });

    it('keeps S3 and GCS repos in separate output arrays when both are present', async () => {
        const mixed: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
            sourceClusters: {
                s3_source: {
                    endpoint: "https://s3-source.example.com",
                    allowInsecure: true,
                    version: "ES 7.10.2",
                    authConfig: { basic: { secretName: "s3-creds" } },
                    snapshotInfo: {
                        repos: {
                            default: { type: "s3", awsRegion: "us-east-2", s3RepoPathUri: "s3://my-bucket/path" }
                        },
                        snapshots: {
                            s3snap: { repoName: "default", config: { createSnapshotConfig: { snapshotPrefix: "s3snap" } } }
                        }
                    }
                },
                gcs_source: baseGcsConfig.sourceClusters.gcs_source,
            },
            targetClusters: baseGcsConfig.targetClusters,
            snapshotMigrationConfigs: [
                {
                    fromSource: "s3_source",
                    toTarget: "target",
                    perSnapshotConfig: {
                        s3snap: [{ metadataMigrationConfig: { skipEvaluateApproval: true, skipMigrateApproval: true } }]
                    }
                },
                baseGcsConfig.snapshotMigrationConfigs[0],
            ]
        };

        const result = await transformer.processFromObject(mixed);
        expect(result.snapshots?.length).toBe(1);
        expect(result.snapshotsGcs?.length).toBe(1);
        expect(result.snapshotMigrations?.length).toBe(1);
        expect(result.snapshotMigrationsGcs?.length).toBe(1);
        expect(result.snapshots![0].sourceConfig.label).toBe("s3_source");
        expect(result.snapshotsGcs![0].sourceConfig.label).toBe("gcs_source");
    });
});
