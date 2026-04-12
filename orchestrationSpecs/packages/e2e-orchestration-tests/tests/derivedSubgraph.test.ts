import { buildChecksumReport } from '../src/checksumReporter';
import { deriveSubgraph } from '../src/derivedSubgraph';
import { loadFullMigrationConfig } from './helpers';

describe('deriveSubgraph', () => {
    it('derives correct subgraph for capture-proxy', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);
        const subgraph = deriveSubgraph(report, 'proxy:capture-proxy');

        expect(subgraph.focus).toBe('proxy:capture-proxy');
        expect(subgraph.immediateDependents).toContain('snapshot:source-snap1');
        expect(subgraph.immediateDependents).toContain('replay:capture-proxy-target-replay1');
        expect(subgraph.transitiveDependents).toContain('snapshotMigration:source-target-snap1');
        // kafka is an upstream prerequisite, not independent
        expect(subgraph.upstreamPrerequisites).toContain('kafka:default');
        expect(subgraph.independent).not.toContain('kafka:default');
        expect(subgraph.independent).toHaveLength(0);
    });

    it('throws for unknown focus', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);
        expect(() => deriveSubgraph(report, 'nonexistent')).toThrow('not found');
    });

    it('kafka subgraph has proxy as immediate dependent and everything downstream', async () => {
        const config = loadFullMigrationConfig();
        const report = await buildChecksumReport(config);
        const subgraph = deriveSubgraph(report, 'kafka:default');

        expect(subgraph.immediateDependents).toContain('proxy:capture-proxy');
        expect(subgraph.upstreamPrerequisites).toHaveLength(0);
        expect(subgraph.independent).toHaveLength(0);
    });
});
