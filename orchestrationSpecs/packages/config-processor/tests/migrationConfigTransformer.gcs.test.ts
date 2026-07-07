import { describe, it, expect } from '@jest/globals';
import { MigrationConfigTransformer } from "../src";
import { OVERALL_MIGRATION_CONFIG } from "@opensearch-migrations/schemas";
import { z } from "zod";

/**
 * GCS routing tests: under the unified provider-agnostic REPO_CONFIG schema,
 * snapshot configs with gs:// URIs flow through the same `snapshots` and
 * `snapshotMigrations` output arrays as s3:// URIs. The URI scheme on
 * `repoPathUri` selects the backend at the Java tools layer.
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
                            repoPathUri: "gs://my-bucket/snapshots",
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

    it('emits GCS createSnapshot configs into the unified snapshots array', async () => {
        const result = await transformer.processFromObject(baseGcsConfig);
        expect(result.snapshots).toBeDefined();
        expect(result.snapshots!.length).toBe(1);
        const ss = result.snapshots![0];
        expect(ss.sourceConfig.label).toBe("gcs_source");
        expect(ss.createSnapshotConfig).toHaveLength(1);
        expect(ss.createSnapshotConfig[0].label).toBe("snap1");
        expect(ss.createSnapshotConfig[0].repo.repoPathUri).toBe("gs://my-bucket/snapshots");
        expect(ss.createSnapshotConfig[0].configChecksum).toBeTruthy();
    });

    it('emits GCS migrations into the unified snapshotMigrations array and resolves snapshotConfigChecksum', async () => {
        const result = await transformer.processFromObject(baseGcsConfig);
        expect(result.snapshotMigrations).toBeDefined();
        expect(result.snapshotMigrations!.length).toBe(1);
        const m = result.snapshotMigrations![0];
        expect(m.sourceLabel).toBe("gcs_source");
        expect(m.label).toBe("snap1");
        expect(m.snapshotConfigChecksum).toBeTruthy();
        expect(m.snapshotConfigChecksum)
            .toBe(result.snapshots![0].createSnapshotConfig[0].configChecksum);
    });

    it('mixes S3 and GCS repos in the same output arrays', async () => {
        const mixed: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
            sourceClusters: {
                s3_source: {
                    endpoint: "https://s3-source.example.com",
                    allowInsecure: true,
                    version: "ES 7.10.2",
                    authConfig: { basic: { secretName: "s3-creds" } },
                    snapshotInfo: {
                        repos: {
                            default: { awsRegion: "us-east-2", repoPathUri: "s3://my-bucket/path" }
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
        expect(result.snapshots?.length).toBe(2);
        expect(result.snapshotMigrations?.length).toBe(2);
        const sourceLabels = result.snapshots!.map(s => s.sourceConfig.label).sort();
        expect(sourceLabels).toEqual(["gcs_source", "s3_source"]);
        const repoUris = result.snapshots!
            .flatMap(s => s.createSnapshotConfig.map(c => c.repo.repoPathUri))
            .sort();
        expect(repoUris).toEqual(["gs://my-bucket/snapshots", "s3://my-bucket/path"]);
    });
});
