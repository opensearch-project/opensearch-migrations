import { buildChecksumReport } from '../src/checksumReporter';
import { loadFullMigrationConfig } from './helpers';

describe('buildChecksumReport', () => {
    const config = loadFullMigrationConfig();

    it('produces a report with all expected components', async () => {
        const report = await buildChecksumReport(config);
        expect(report.components).toMatchSnapshot();
    });

    it('includes capture-proxy with downstream checksums', async () => {
        const report = await buildChecksumReport(config);
        const proxy = report.components['proxy:capture-proxy'];
        expect(proxy).toBeDefined();
        expect(proxy.kind).toBe('proxy');
        expect(proxy.downstreamChecksums.snapshot).toBeDefined();
        expect(proxy.downstreamChecksums.snapshot).not.toBe('');
        expect(proxy.downstreamChecksums.replayer).toBeDefined();
        expect(proxy.downstreamChecksums.replayer).not.toBe('');
    });

    it('has dependsOn edges for snapshots → proxies', async () => {
        const report = await buildChecksumReport(config);
        const snapshot = report.components['snapshot:source-snap1'];
        expect(snapshot).toBeDefined();
        expect(snapshot.dependsOn).toContain('proxy:capture-proxy');
    });

    it('has dependsOn edges for replays → proxies and migrations', async () => {
        const report = await buildChecksumReport(config);
        const replay = report.components['replay:capture-proxy-target-replay1'];
        expect(replay).toBeDefined();
        expect(replay.dependsOn).toContain('proxy:capture-proxy');
    });

    it('has dependsOn edges for migrations → snapshots', async () => {
        const report = await buildChecksumReport(config);
        const migration = report.components['snapshotMigration:source-target-snap1'];
        expect(migration).toBeDefined();
        expect(migration.dependsOn).toContain('snapshot:source-snap1');
    });

    it('all checksums are non-empty hex strings', async () => {
        const report = await buildChecksumReport(config);
        for (const [key, entry] of Object.entries(report.components)) {
            expect(entry.configChecksum).toMatch(/^[0-9a-f]+$/);
        }
    });
});
