import { MigrationConfigTransformer } from '@opensearch-migrations/config-processor';
import type { ChecksumReport, ChecksumReportEntry } from './types';

export async function buildChecksumReport(rawConfig: Record<string, unknown>): Promise<ChecksumReport> {
    const transformer = new MigrationConfigTransformer();
    const validated = transformer.validateInput(rawConfig);
    const output = await transformer.transform(validated);
    const components: Record<string, ChecksumReportEntry> = {};

    // Kafka clusters
    for (const k of output.kafkaClusters ?? []) {
        components[`kafka:${k.name}`] = {
            kind: 'kafkaCluster',
            resourceName: k.name,
            configChecksum: k.configChecksum ?? '',
            downstreamChecksums: {},
            dependsOn: [],
        };
    }

    // Proxies
    for (const p of output.proxies ?? []) {
        components[`proxy:${p.name}`] = {
            kind: 'proxy',
            resourceName: p.name,
            configChecksum: p.configChecksum,
            downstreamChecksums: {
                snapshot: p.checksumForSnapshot,
                replayer: p.checksumForReplayer,
            },
            dependsOn: p.kafkaConfig.configChecksum
                ? [`kafka:${p.kafkaConfig.label}`]
                : [],
        };
    }

    // Snapshots
    for (const s of output.snapshots ?? []) {
        for (const item of s.createSnapshotConfig) {
            const key = `snapshot:${s.sourceConfig.label}-${item.label}`;
            components[key] = {
                kind: 'snapshot',
                resourceName: `${s.sourceConfig.label}-${item.label}`,
                configChecksum: item.configChecksum ?? '',
                downstreamChecksums: {},
                dependsOn: (item.dependsOnProxySetups ?? []).map(
                    (dep: { name: string }) => `proxy:${dep.name}`
                ),
            };
        }
    }

    // Snapshot migrations
    for (const m of output.snapshotMigrations ?? []) {
        const targetLabel = (m.targetConfig as Record<string, unknown>).label as string;
        const resourceName = `${m.sourceLabel}-${targetLabel}-${m.label}`;
        const key = `snapshotMigration:${resourceName}`;
        components[key] = {
            kind: 'snapshotMigration',
            resourceName,
            configChecksum: m.configChecksum,
            downstreamChecksums: {
                replayer: m.checksumForReplayer,
            },
            dependsOn: [`snapshot:${m.sourceLabel}-${m.label}`],
        };
    }

    // Traffic replays
    // Build a lookup from (source, snapshot) → migration component key.
    // The replay's dependsOnSnapshotMigrations only has source+snapshot (no target),
    // but the migration key includes the target label.
    const migrationKeyBySourceSnapshot = new Map<string, string>();
    for (const key of Object.keys(components)) {
        if (key.startsWith('snapshotMigration:')) {
            const entry = components[key];
            // The dependsOn for a migration is snapshot:source-snapshotLabel
            for (const dep of entry.dependsOn) {
                if (dep.startsWith('snapshot:')) {
                    // dep is "snapshot:source-snap1", migration key is "snapshotMigration:source-target-snap1"
                    // Map "source-snap1" → full migration key
                    const snapshotId = dep.slice('snapshot:'.length);
                    migrationKeyBySourceSnapshot.set(snapshotId, key);
                }
            }
        }
    }

    for (const r of output.trafficReplays ?? []) {
        const deps: string[] = [`proxy:${r.fromProxy}`];
        for (const dep of r.dependsOnSnapshotMigrations ?? []) {
            const snapshotId = `${dep.source}-${dep.snapshot}`;
            const migrationKey = migrationKeyBySourceSnapshot.get(snapshotId);
            if (migrationKey) {
                deps.push(migrationKey);
            }
        }
        components[`replay:${r.name}`] = {
            kind: 'trafficReplay',
            resourceName: r.name,
            configChecksum: r.configChecksum,
            downstreamChecksums: {},
            dependsOn: deps,
        };
    }

    return { components };
}
