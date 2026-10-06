import {describe, expect, it} from "@jest/globals";
import {MigrationConfigTransformer, MigrationInitializer} from "../src";

/**
 * S3 client settings for an s3:// snapshot repo reach migration pods through a per-repo ConfigMap
 * (endpoint, region, addressing style) and an optional credentials Secret. The ConfigMap must only
 * carry keys that are set: an env var that is present but empty (e.g. AWS_ENDPOINT_URL_S3="") is read
 * by the AWS SDKs as an invalid endpoint rather than as "unset".
 */

function solrBackupConfig(repo: Record<string, unknown>): Record<string, unknown> {
    return {
        sourceClusters: {
            solrSource: {
                endpoint: "https://solr.example.com:8983",
                allowInsecure: true,
                version: "SOLR 9.7.0",
                snapshotInfo: {
                    repos: {default: repo},
                    backups: {
                        solrBackup: {
                            repoName: "default",
                            createBackupConfig: {},
                        },
                    },
                },
            },
        },
        targetClusters: {
            target: {
                endpoint: "https://target.example.com",
                allowInsecure: true,
            },
        },
        snapshotMigrationConfigs: [{
            fromSource: "solrSource",
            toTarget: "target",
            perSnapshotConfig: {
                solrBackup: [{metadataMigrationConfig: {}, documentBackfillConfig: {}}],
            },
        }],
    };
}

async function bundleFor(repo: Record<string, unknown>) {
    return new MigrationInitializer()
        .generateMigrationBundle(solrBackupConfig(repo), "migration-workflow", {runNumber: 1700000000000});
}

describe("S3 repo settings ConfigMap", () => {
    it("carries endpoint, region, and addressing style for a custom S3 endpoint", async () => {
        const bundle = await bundleFor({
            repoPathUri: "s3://solr-np/solr-backup",
            awsRegion: "us-east-1",
            endpoint: "https://northamerica-1.object-storage.apple.com",
            s3AddressingStyle: "path",
            s3CredentialsSecretName: "solr-s3-creds",
        });

        expect(bundle.s3RepoSettingsConfigMaps.items).toEqual([{
            apiVersion: "v1",
            kind: "ConfigMap",
            metadata: {name: "s3-repo-settings-solrsource-default"},
            data: {
                AWS_ENDPOINT_URL_S3: "https://northamerica-1.object-storage.apple.com",
                AWS_DEFAULT_REGION: "us-east-1",
                AWS_S3_ADDRESSING_STYLE: "path",
            },
        }]);

        const createRepo = bundle.workflows.snapshots[0].createSnapshotConfig[0].repo;
        const migrationRepo = bundle.workflows.snapshotMigrations[0].snapshotConfig.repoConfig;
        for (const repo of [createRepo, migrationRepo]) {
            expect(repo.s3SettingsConfigMapName).toBe("s3-repo-settings-solrsource-default");
            expect(repo.s3CredentialsSecretName).toBe("solr-s3-creds");
        }
    });

    it("omits unset keys so pods never see an empty AWS_ENDPOINT_URL_S3", async () => {
        const bundle = await bundleFor({
            repoPathUri: "s3://bucket/solr-path",
            awsRegion: "us-east-2",
        });

        expect(bundle.s3RepoSettingsConfigMaps.items).toHaveLength(1);
        expect(bundle.s3RepoSettingsConfigMaps.items[0].data).toEqual({AWS_DEFAULT_REGION: "us-east-2"});
        expect(bundle.workflows.snapshots[0].createSnapshotConfig[0].repo.s3CredentialsSecretName).toBe("");
    });

    it("rejects a credentials secret name that is not a valid Kubernetes name", async () => {
        await expect(new MigrationConfigTransformer().processFromObject(solrBackupConfig({
            repoPathUri: "s3://bucket/solr-path",
            awsRegion: "us-east-2",
            s3CredentialsSecretName: "Not_A_Valid_Name",
        }))).rejects.toThrow();
    });
});
