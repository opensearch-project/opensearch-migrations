import {describe, expect, it} from "@jest/globals";
import {InputValidationError, MigrationConfigTransformer, MigrationInitializer} from "../src";

/**
 * Tests for the Solr externally-managed-backup IMPORT path through the config transformer.
 *
 * Any externally-managed Solr backup must be routed through the snapshot-creation path so
 * a DataSnapshot CR gates metadata/backfill until CreateSnapshot --mode import prepares and
 * validates the external backup. ES/OS external snapshots keep the old external-only behavior.
 */

function snapshotMigrationConfig(opts: {
    shape?: "elasticsearchSnapshots" | "solrExternalBackups" | "solrCreateBackups";
    version?: string;
    externalSolrBackupName?: string;
    externalElasticsearchSnapshotName?: string;
    collectionAllowlist?: string[];
    snapshotPrefix?: string;
    withDocumentBackfill?: boolean;
}): Record<string, unknown> {
    const snapshotInfo = opts.shape === "solrExternalBackups"
        ? {
            repos: {
                default: {
                    awsRegion: "us-east-2",
                    repoPathUri: "s3://bucket/solr-path",
                },
            },
            backups: {
                solrBackup: {
                    repoName: "default",
                    externalBackupName: opts.externalSolrBackupName ?? "preexisting-solr-backup",
                    collectionAllowlist: opts.collectionAllowlist ?? [],
                },
            },
        }
        : opts.shape === "solrCreateBackups"
            ? {
                repos: {
                    default: {
                        awsRegion: "us-east-2",
                        repoPathUri: "s3://bucket/solr-path",
                    },
                },
                backups: {
                    solrBackup: {
                        repoName: "default",
                        createBackupConfig: {
                            ...(opts.snapshotPrefix !== undefined ? {snapshotPrefix: opts.snapshotPrefix} : {}),
                            collectionAllowlist: opts.collectionAllowlist ?? [],
                        },
                    },
                },
            }
        : {
            repos: {
                default: {
                    awsRegion: "us-east-2",
                    repoPathUri: "s3://bucket/solr-path",
                },
            },
            snapshots: {
                esSnapshot: {
                    repoName: "default",
                    config: {
                        externallyManagedSnapshotName: opts.externalElasticsearchSnapshotName ?? "preexisting-es-snapshot"
                    },
                },
            },
        };
    const itemName = opts.shape === "solrExternalBackups" || opts.shape === "solrCreateBackups"
        ? "solrBackup"
        : "esSnapshot";
    return {
        sourceClusters: {
            solrSource: {
                endpoint: "https://solr.example.com:8983",
                allowInsecure: true,
                version: opts.version ?? "SOLR 9.7.0",
                snapshotInfo,
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
                [itemName]: [{
                    metadataMigrationConfig: {},
                    ...(opts.withDocumentBackfill ? {documentBackfillConfig: {}} : {}),
                }],
            },
        }],
    };
}

describe("Solr backup snapshotInfo paths", () => {
    it("routes a Solr backup through the create path with mode=import", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(snapshotMigrationConfig({shape: "solrExternalBackups"}));

        // A snapshot-creation group must be produced for the Solr source.
        expect(workflowConfig.snapshots).toBeDefined();
        const createGroups = (workflowConfig.snapshots ?? []).filter(
            s => s.sourceConfig.label === "solrSource");
        expect(createGroups).toHaveLength(1);

        const item = createGroups[0].createSnapshotConfig[0];
        // The create-config carries mode:"import" so runCreateSnapshot emits --mode import...
        expect(item.config.mode).toBe("import");
        expect(item.config.solrCollections).toEqual([]);
        // ...and records the pre-existing external backup name for the workflow to use verbatim.
        expect(item.solrExternalBackupName).toBe("preexisting-solr-backup");
    });

    it("passes Solr collectionAllowlist to import prepare and default migration allowlists", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(snapshotMigrationConfig({
                shape: "solrExternalBackups",
                collectionAllowlist: ["orders", "products"],
                withDocumentBackfill: true,
            }));

        const item = workflowConfig.snapshots[0].createSnapshotConfig[0];
        expect(item.config.mode).toBe("import");
        expect(item.config.solrCollections).toEqual(["orders", "products"]);

        const migration = workflowConfig.snapshotMigrations[0];
        expect(migration.metadataMigrationConfig?.indexAllowlist)
            .toEqual(["orders", "products"]);
        expect(migration.documentBackfillConfig?.indexAllowlist)
            .toEqual(["orders", "products"]);
    });

    it("emits a combined snapshotNameResolution (CR wait + external backup name) for Solr import", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(snapshotMigrationConfig({shape: "solrExternalBackups"}));

        expect(workflowConfig.snapshotMigrations).toHaveLength(1);
        const resolution = workflowConfig.snapshotMigrations[0].snapshotNameResolution;
        // Both keys present: the migration waits on the DataSnapshot CR (so it blocks until the
        // import step finishes) AND uses the external backup name (not a generated one).
        expect(resolution).toEqual({
            dataSnapshotResourceName: "solrSource-solrBackup",
            externalSnapshotName: "preexisting-solr-backup",
        });
    });

    it("includes the external backup name in the import DataSnapshot checksum", async () => {
        const transformer = new MigrationConfigTransformer();
        const first = await transformer.processFromObject(snapshotMigrationConfig({
            shape: "solrExternalBackups",
            externalSolrBackupName: "preexisting-solr-backup"
        }));
        const second = await transformer.processFromObject(snapshotMigrationConfig({
            shape: "solrExternalBackups",
            externalSolrBackupName: "replacement-solr-backup"
        }));

        const firstItem = first.snapshots[0].createSnapshotConfig[0];
        const secondItem = second.snapshots[0].createSnapshotConfig[0];
        expect(firstItem.configChecksum).not.toBe(secondItem.configChecksum);
    });

    it("creates a Solr backup through the normal create path", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(snapshotMigrationConfig({
                shape: "solrCreateBackups",
                snapshotPrefix: "orders-backup",
                collectionAllowlist: ["orders"],
                withDocumentBackfill: true,
            }));

        expect(workflowConfig.snapshots).toHaveLength(1);
        const item = workflowConfig.snapshots[0].createSnapshotConfig[0];
        expect(item.label).toBe("solrBackup");
        expect(item.snapshotPrefix).toBe("orders-backup");
        expect(item.config.mode).toBe("create");
        expect(item.config.solrCollections).toEqual(["orders"]);
        expect(item.solrExternalBackupName).toBeUndefined();

        const resolution = workflowConfig.snapshotMigrations[0].snapshotNameResolution;
        expect(resolution).toEqual({
            dataSnapshotResourceName: "solrSource-solrBackup",
        });

        const migration = workflowConfig.snapshotMigrations[0];
        expect(migration.metadataMigrationConfig?.indexAllowlist).toEqual(["orders"]);
        expect(migration.documentBackfillConfig?.indexAllowlist).toEqual(["orders"]);
    });

    it("includes material snapshot identity in generated DataSnapshot CR specs", async () => {
        for (const shape of ["solrExternalBackups", "solrCreateBackups"] as const) {
            const config = snapshotMigrationConfig({shape, collectionAllowlist: ["orders"]});
            const workflowConfig = await new MigrationConfigTransformer().processFromObject(config);
            const item = workflowConfig.snapshots[0].createSnapshotConfig[0];

            expect(item.config.mode).toBe(shape === "solrExternalBackups" ? "import" : "create");
            expect(item.sourceConnectionIdentity).toMatchObject({
                label: "solrSource",
                version: "SOLR 9.7.0",
                endpoint: "https://solr.example.com:8983",
                allowInsecure: true,
                authType: "none",
                authBasicSecretName: "",
                authSigv4Region: "",
                authSigv4Service: "",
                authMtlsClientSecretName: "",
                authMtlsCaCertHash: "",
            });

            const bundle = await new MigrationInitializer()
                .generateMigrationBundle(config, undefined, {runNumber: 1700000000000});
            const dataSnapshot = bundle.customMigrationResources.items
                .find((resource: any) => resource.kind === "DataSnapshot");

            expect(dataSnapshot).toBeDefined();
            expect(dataSnapshot?.spec).toMatchObject({
                sourceLabel: "solrSource",
                sourceVersion: "SOLR 9.7.0",
                sourceEndpoint: "https://solr.example.com:8983",
                sourceAllowInsecure: true,
                sourceAuthType: "none",
                sourceAuthBasicSecretName: "",
                sourceAuthSigv4Region: "",
                sourceAuthSigv4Service: "",
                sourceAuthMtlsClientSecretName: "",
                sourceAuthMtlsCaCertHash: "",
                snapshotLabel: "solrBackup",
                repoName: "default",
                repoPathUri: "s3://bucket/solr-path",
                repoAwsRegion: "us-east-2",
                repoEndpoint: "",
                repoS3RoleArn: "",
                repoUseLocalStack: false,
                mode: shape === "solrExternalBackups" ? "import" : "create",
                solrCollections: ["orders"],
                solrExternalBackupName: shape === "solrExternalBackups"
                    ? "preexisting-solr-backup"
                    : "",
            });
        }
    });

    it("includes Solr backup semaphore keys in generated concurrency ConfigMaps", async () => {
        for (const shape of ["solrExternalBackups", "solrCreateBackups"] as const) {
            const config = snapshotMigrationConfig({shape});
            const bundle = await new MigrationInitializer()
                .generateMigrationBundle(config, undefined, {runNumber: 1700000000000});
            const workflowSemaphoreKeys = new Set(
                bundle.workflows.snapshots
                    ?.flatMap(snapshot => snapshot.createSnapshotConfig.map(item => item.semaphoreKey))
            );
            const concurrencyConfigMapKeys = new Set(
                Object.keys(bundle.concurrencyConfigMaps.items[0].data)
            );

            expect(workflowSemaphoreKeys).toEqual(concurrencyConfigMapKeys);
            expect(bundle.concurrencyConfigMaps.items[0].data)
                .toEqual({"snapshot-modern-solrSource-solrBackup": "1"});
        }
    });

    it("uses the backup label as the generated Solr backup prefix when no prefix is configured", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(snapshotMigrationConfig({shape: "solrCreateBackups"}));

        const item = workflowConfig.snapshots[0].createSnapshotConfig[0];
        expect(item.snapshotPrefix).toBe("solrBackup");
        expect(item.config.solrCollections).toEqual([]);
    });

    it("keeps an ES external snapshot on the external-only path", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(snapshotMigrationConfig({
                shape: "elasticsearchSnapshots",
                version: "ES 7.10.2",
            }));

        // No DataSnapshot create-config group for this source.
        const createGroups = (workflowConfig.snapshots ?? []).filter(
            s => s.sourceConfig.label === "solrSource");
        expect(createGroups).toHaveLength(0);

        // Resolution is external-name-only (no CR to wait on).
        const resolution = workflowConfig.snapshotMigrations[0].snapshotNameResolution;
        expect(resolution).toEqual({externalSnapshotName: "preexisting-es-snapshot"});
        expect("dataSnapshotResourceName" in resolution).toBe(false);
    });

    it("rejects Elasticsearch snapshot shape on a Solr source", async () => {
        let threw: unknown;
        try {
            await new MigrationConfigTransformer()
                .processFromObject(snapshotMigrationConfig({
                    shape: "elasticsearchSnapshots",
                    version: "SOLR 9.7.0"
                }));
        } catch (e) {
            threw = e;
        }
        expect(threw).toBeInstanceOf(InputValidationError);
        expect(String((threw as InputValidationError).message ?? threw))
            .toMatch(/snapshotInfo\.snapshots.*Elasticsearch\/OpenSearch/);
    });

    it("rejects Solr backup shape on a non-Solr source", async () => {
        let threw: unknown;
        try {
            await new MigrationConfigTransformer()
                .processFromObject(snapshotMigrationConfig({
                    shape: "solrExternalBackups",
                    version: "ES 7.10.2"
                }));
        } catch (e) {
            threw = e;
        }
        expect(threw).toBeInstanceOf(InputValidationError);
        expect(String((threw as InputValidationError).message ?? threw))
            .toMatch(/snapshotInfo\.backups.*Solr/);
    });

    it("rejects user-authored createSnapshotConfig mode=import", async () => {
        const config = snapshotMigrationConfig({
            shape: "elasticsearchSnapshots",
            version: "ES 7.10.2"
        }) as {
            sourceClusters: {
                solrSource: {
                    snapshotInfo: {
                        snapshots: {
                            esSnapshot: {
                                config: unknown;
                            };
                        };
                    };
                };
            };
        };
        config.sourceClusters.solrSource.snapshotInfo.snapshots.esSnapshot.config = {
            createSnapshotConfig: {
                mode: "import",
            },
        };

        let threw: unknown;
        try {
            await new MigrationConfigTransformer().processFromObject(config);
        } catch (e) {
            threw = e;
        }
        expect(threw).toBeInstanceOf(Error);
        expect(String((threw as Error).message ?? threw))
            .toMatch(/Unrecognized keys.*mode/);
    });
});
