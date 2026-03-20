import { describe, it, expect } from '@jest/globals';
import * as fs from 'fs/promises';
import * as os from 'os';
import * as path from 'path';
// Use require here to avoid type-only export resolution issues under ts-jest/workspace mapping.
const { MigrationInitializer } = require('../src/migrationInitializer');

describe('generateOutputFiles', () => {
    it('writes transformed workflows even when only transformed-config input is available', async () => {
        const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), 'config-processor-output-'));
        const workflows = [{ hello: 'world' }] as any;
        const initializer = Object.create(MigrationInitializer.prototype);

        try {
            await initializer.generateOutputFiles(workflows, outputDir, null);

            await expect(fs.readFile(path.join(outputDir, 'workflowMigration.config.yaml'), 'utf-8'))
                .resolves.toBe(JSON.stringify(workflows, null, 2));

            await expect(fs.readFile(path.join(outputDir, 'approvalConfigMaps.yaml'), 'utf-8'))
                .resolves.toContain('kind: List');
            await expect(fs.readFile(path.join(outputDir, 'concurrencyConfigMaps.yaml'), 'utf-8'))
                .resolves.toContain('kind: List');
            await expect(fs.readFile(path.join(outputDir, 'crdResources.yaml'), 'utf-8'))
                .resolves.toContain('kind: List');
        } finally {
            await fs.rm(outputDir, { recursive: true, force: true });
        }
    });
});
