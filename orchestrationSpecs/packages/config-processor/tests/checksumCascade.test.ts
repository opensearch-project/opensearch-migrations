import { describe, it, expect } from '@jest/globals';
import { MigrationConfigTransformer } from '../src';
import * as fs from 'fs';
import * as path from 'path';
import * as yaml from 'yaml';

const SAMPLE_PATH = path.join(__dirname, '..', 'scripts', 'samples', 'fullMigrationWithTraffic.wf.yaml');

async function transformConfig(overrides: (config: any) => void = () => {}) {
    const config = yaml.parse(fs.readFileSync(SAMPLE_PATH, 'utf-8'));
    overrides(config);
    const transformer = new MigrationConfigTransformer();
    return transformer.processFromObject(config);
}

describe('per-dependency checksum cascade', () => {
    it('changing an operational proxy field does NOT change downstream checksums', async () => {
        const baseline = await transformConfig();
        const withMoreReplicas = await transformConfig(c => {
            c.traffic.proxies['capture-proxy'].proxyConfig.podReplicas = 99;
        });

        const baseProxy = baseline.proxies[0];
        const modProxy = withMoreReplicas.proxies[0];

        // Self checksum changes (podReplicas is part of the full spec)
        expect(modProxy.configChecksum).not.toEqual(baseProxy.configChecksum);
        // Downstream checksums do NOT change
        expect(modProxy.checksumForSnapshot).toEqual(baseProxy.checksumForSnapshot);
        expect(modProxy.checksumForReplayer).toEqual(baseProxy.checksumForReplayer);

        // Replayer's fromProxyConfigChecksum is unaffected
        expect(withMoreReplicas.trafficReplays[0].fromProxyConfigChecksum)
            .toEqual(baseline.trafficReplays[0].fromProxyConfigChecksum);
    });

    it('changing a material proxy field DOES change downstream checksums', async () => {
        const baseline = await transformConfig();
        const withNoCapture = await transformConfig(c => {
            c.traffic.proxies['capture-proxy'].proxyConfig.noCapture = true;
        });

        const baseProxy = baseline.proxies[0];
        const modProxy = withNoCapture.proxies[0];

        // All checksums change
        expect(modProxy.configChecksum).not.toEqual(baseProxy.configChecksum);
        expect(modProxy.checksumForSnapshot).not.toEqual(baseProxy.checksumForSnapshot);
        expect(modProxy.checksumForReplayer).not.toEqual(baseProxy.checksumForReplayer);

        // Replayer's upstream checksum cascades
        expect(withNoCapture.trafficReplays[0].fromProxyConfigChecksum)
            .not.toEqual(baseline.trafficReplays[0].fromProxyConfigChecksum);
        // Replayer's own configChecksum also cascades (it folds in the proxy checksum)
        expect(withNoCapture.trafficReplays[0].configChecksum)
            .not.toEqual(baseline.trafficReplays[0].configChecksum);
    });

    it('changing an operational RFS field does NOT change migration checksumForReplayer', async () => {
        const baseline = await transformConfig();
        const withMoreRfsReplicas = await transformConfig(c => {
            const snap1 = c.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0];
            snap1.documentBackfillConfig.podReplicas = 50;
        });

        const baseMig = baseline.snapshotMigrations[0];
        const modMig = withMoreRfsReplicas.snapshotMigrations[0];

        expect(modMig.configChecksum).not.toEqual(baseMig.configChecksum);
        expect(modMig.checksumForReplayer).toEqual(baseMig.checksumForReplayer);
    });

    it('changing a material RFS field DOES change migration checksumForReplayer', async () => {
        const baseline = await transformConfig();
        const withTransformer = await transformConfig(c => {
            const snap1 = c.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0];
            snap1.documentBackfillConfig.docTransformerConfigBase64 = 'eyJ0ZXN0IjogdHJ1ZX0=';
        });

        const baseMig = baseline.snapshotMigrations[0];
        const modMig = withTransformer.snapshotMigrations[0];

        expect(modMig.configChecksum).not.toEqual(baseMig.configChecksum);
        expect(modMig.checksumForReplayer).not.toEqual(baseMig.checksumForReplayer);
    });
});
