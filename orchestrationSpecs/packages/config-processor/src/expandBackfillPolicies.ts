import {OVERALL_MIGRATION_CONFIG} from "@opensearch-migrations/schemas";
import {z} from "zod";

type UserConfig = z.infer<typeof OVERALL_MIGRATION_CONFIG>;

/** Lower a validated repeat policy into the same durable resources as an explicit snapshot sequence. */
export function expandBackfillPolicies(config: UserConfig): UserConfig {
    const sourceClusters = {...config.sourceClusters};
    const snapshotMigrationConfigs = config.snapshotMigrationConfigs.map(migration => {
        const backfill = migration.backfill;
        if (!backfill) return migration;
        const source = sourceClusters[migration.fromSource];
        if (!source || !source.snapshotInfo || !("snapshots" in source.snapshotInfo)) {
            throw new Error(`Repeated backfill source '${migration.fromSource}' has no snapshot repository`);
        }
        const labels = Array.from({length: backfill.repeat.maxRuns}, (_, index) => `backfill-${index + 1}`);
        sourceClusters[migration.fromSource] = {
            ...source,
            snapshotInfo: {
                ...source.snapshotInfo,
                snapshots: Object.fromEntries(labels.map(label => [label, {
                    repoName: backfill.snapshot.repoName,
                    config: {createSnapshotConfig: backfill.snapshot.createSnapshotConfig},
                }])),
            },
        };
        return {
            ...migration,
            snapshotSequence: labels,
            perSnapshotConfig: Object.fromEntries(labels.map(label => [label, [{
                metadataMigrationConfig: backfill.metadataMigrationConfig,
                documentBackfillConfig: backfill.documentBackfillConfig,
            }]])),
        };
    });
    return {...config, sourceClusters, snapshotMigrationConfigs};
}
