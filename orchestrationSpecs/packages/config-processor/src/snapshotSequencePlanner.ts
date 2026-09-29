import {ARGO_MIGRATION_CONFIG_PRE_ENRICH, SNAPSHOT_SEQUENCE_PLAN} from "@opensearch-migrations/schemas";
import {z} from "zod";

type WorkflowConfig = z.infer<typeof ARGO_MIGRATION_CONFIG_PRE_ENRICH>;
type SequencePlan = z.infer<typeof SNAPSHOT_SEQUENCE_PLAN>;

/** Build a serial plan referencing the canonical arrays that the initializer enriches with UIDs. */
export function planSnapshotSequences(
    snapshots: WorkflowConfig["snapshots"],
    migrations: WorkflowConfig["snapshotMigrations"],
): {snapshots: WorkflowConfig["snapshots"]; snapshotSequences?: SequencePlan[]} {
    const plans = new Map<string, SequencePlan>();
    const snapshotOwners = new Map<string, string>();
    const snapshotPredecessors = new Map<string, string>();
    migrations.forEach((migration, migrationIndex) => {
        const name = migration.sequenceName;
        if (!name) return;
        let plan = plans.get(name);
        if (!plan) {
            plan = {name, steps: []};
            plans.set(name, plan);
        }
        const snapshotKey = `${migration.sourceLabel}/${migration.label}`;
        const sourceIndex = snapshots.findIndex(group => group.sourceConfig.label === migration.sourceLabel);
        const snapshotIndex = sourceIndex < 0 ? -1 : snapshots[sourceIndex].createSnapshotConfig.findIndex(
            snapshot => snapshot.label === migration.label,
        );
        const createSnapshot = snapshotIndex >= 0 && !snapshotOwners.has(snapshotKey);
        if (createSnapshot && migration.previousMigrationResourceName) {
            snapshotPredecessors.set(snapshotKey, migration.previousMigrationResourceName);
        }
        plan.steps.push({
            migrationIndex,
            ...(createSnapshot ? {snapshotCreation: {sourceIndex, snapshotIndex}} : {}),
        });
        snapshotOwners.set(snapshotKey, name);
    });
    if (plans.size === 0) return {snapshots};
    return {
        snapshots: snapshots.map(group => ({
            ...group,
            createSnapshotConfig: group.createSnapshotConfig.map(snapshot => {
                const key = `${group.sourceConfig.label}/${snapshot.label}`;
                const sequenceName = snapshotOwners.get(key);
                const predecessor = snapshotPredecessors.get(key);
                return sequenceName ? {
                    ...snapshot,
                    sequenceName,
                    dependsOn: [...snapshot.dependsOn, ...(predecessor ? [predecessor] : [])],
                } : snapshot;
            }),
        })),
        snapshotSequences: [...plans.values()],
    };
}
