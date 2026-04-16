#!/usr/bin/env npx tsx
/**
 * Generate the outer workflow YAML for submission to a cluster.
 * Usage: npx tsx src/generate-outer.ts > /tmp/outer.yaml
 *        kubectl -n ma create -f /tmp/outer.yaml
 */
import fs from 'node:fs';
import yaml from 'yaml';
import { buildOuterWorkflow } from './buildOuterWorkflow';
import { buildChecksumReport } from './checksumReporter';
import { expandMatrix } from './matrixExpander';
import { defaultMutatorRegistry } from './approvedMutators';
import { loadTestSpec } from './specLoader';
import path from 'node:path';

async function main() {
    const specPath = process.argv[2]
        ?? path.resolve(__dirname, '..', 'specs', 'proxy-focus-change.test.json');
    const testSpec = loadTestSpec(specPath);
    const baseConfig = yaml.parse(fs.readFileSync(testSpec.baseConfigPath, 'utf-8'));
    const report = await buildChecksumReport(baseConfig);
    const [tc] = await expandMatrix(testSpec.matrix, report, defaultMutatorRegistry, baseConfig, report);
    const approvalPatterns = testSpec.fixtures.approvalGates.map(g => g.approvePattern);
    process.stdout.write(buildOuterWorkflow(tc, baseConfig, approvalPatterns));
}

main().catch(e => { console.error(e); process.exit(1); });
