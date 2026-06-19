import {describe, expect, it} from "@jest/globals";
import {OVERALL_MIGRATION_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";
import {InputValidationError, MigrationConfigTransformer} from "../src";

/**
 * Tests for the Solr externally-managed-snapshot IMPORT path through the config transformer.
 *
 * An externally-managed Solr snapshot with `importConfig` set must be routed through the
 * snapshot-creation path (so a DataSnapshot CR is created and CreateSnapshot --mode import runs to
 * upload the schema), while still using the pre-existing external snapshot name for the migration.
 * A plain externally-managed snapshot (no importConfig), and any ES/OS source, must keep the old
 * behavior (no DataSnapshot CR; pure external-name resolution).
 */

function solrImportConfig(opts: {withImportConfig: boolean; version?: string}): z.infer<typeof OVERALL_MIGRATION_CONFIG> {
    const externalConfig: any = {externallyManagedSnapshotName: "preexisting-solr-snap"};
    if (opts.withImportConfig) {
        externalConfig.importConfig = {};
    }
    return {
        sourceClusters: {
            solrSource: {
                endpoint: "https://solr.example.com:8983",
                allowInsecure: true,
                version: opts.version ?? "SOLR 9.7.0",
                snapshotInfo: {
                    repos: {
                        default: {
                            awsRegion: "us-east-2",
                            s3RepoPathUri: "s3://bucket/solr-path",
                        },
                    },
                    snapshots: {
                        solrSnap: {
                            repoName: "default",
                            config: externalConfig,
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
                solrSnap: [{
                    metadataMigrationConfig: {},
                }],
            },
        }],
    } as any;
}

describe("Solr externally-managed snapshot import path", () => {
    it("routes a Solr importConfig snapshot through the create path with mode=import", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(solrImportConfig({withImportConfig: true}));

        // A snapshot-creation group must be produced for the Solr source (a plain external snapshot
        // would produce none).
        expect(workflowConfig.snapshots).toBeDefined();
        const createGroups = workflowConfig.snapshots!.filter(
            (s: any) => s.sourceConfig.label === "solrSource");
        expect(createGroups).toHaveLength(1);

        const item = createGroups[0].createSnapshotConfig[0];
        // The create-config carries mode:"import" so runCreateSnapshot emits --mode import...
        expect(item.config.mode).toBe("import");
        // ...and records the pre-existing external snapshot name for the workflow to use verbatim.
        expect(item.importExternalSnapshotName).toBe("preexisting-solr-snap");
    });

    it("emits a combined snapshotNameResolution (CR wait + external name) for Solr import", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(solrImportConfig({withImportConfig: true}));

        expect(workflowConfig.snapshotMigrations).toHaveLength(1);
        const resolution = (workflowConfig.snapshotMigrations as any[])[0].snapshotNameResolution;
        // Both keys present: the migration waits on the DataSnapshot CR (so it blocks until the
        // import step finishes) AND uses the external name (not a generated one).
        expect(resolution.dataSnapshotResourceName).toBe("solrSource-solrSnap");
        expect(resolution.externalSnapshotName).toBe("preexisting-solr-snap");
    });

    it("keeps a plain externally-managed Solr snapshot (no importConfig) on the external-only path", async () => {
        const workflowConfig = await new MigrationConfigTransformer()
            .processFromObject(solrImportConfig({withImportConfig: false}));

        // No DataSnapshot create-config group for this source.
        const createGroups = (workflowConfig.snapshots ?? []).filter(
            (s: any) => s.sourceConfig.label === "solrSource");
        expect(createGroups).toHaveLength(0);

        // Resolution is external-name-only (no CR to wait on).
        const resolution = (workflowConfig.snapshotMigrations as any[])[0].snapshotNameResolution;
        expect(resolution.externalSnapshotName).toBe("preexisting-solr-snap");
        expect("dataSnapshotResourceName" in resolution).toBe(false);
    });

    it("rejects importConfig on a non-Solr (ES) source", async () => {
        let threw: unknown;
        try {
            await new MigrationConfigTransformer()
                .processFromObject(solrImportConfig({withImportConfig: true, version: "ES 7.10.2"}));
        } catch (e) {
            threw = e;
        }
        expect(threw).toBeInstanceOf(InputValidationError);
        expect(String((threw as InputValidationError).message ?? threw))
            .toMatch(/importConfig/);
    });
});
