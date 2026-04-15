/**
 * Demo: E2E Orchestration Test Framework
 *
 * This script demonstrates the full compile-time pipeline:
 *
 *   1. Load a baseline wf.yaml config
 *   2. Build a checksum report (dependency graph + checksums)
 *   3. Derive the subgraph for a focus component
 *   4. Expand a matrix spec into concrete test cases
 *   5. Generate the outer Argo workflow YAML for one test case
 *   6. Show what the assert logic would do with simulated observations
 *
 * The output is a complete Argo Workflow YAML that you could submit
 * to a cluster with `argo submit`. The workflow IS the test.
 *
 * Run with: npx tsx src/demo.ts
 */
import fs from 'node:fs';
import path from 'node:path';
import yaml from 'yaml';
import { buildChecksumReport } from './checksumReporter';
import { deriveSubgraph } from './derivedSubgraph';
import { expandMatrix } from './matrixExpander';
import { defaultMutatorRegistry } from './approvedMutators';
import { buildOuterWorkflow } from './buildOuterWorkflow';
import { assertRun, formatAssertResult } from './assertLogic';
import { formatReport, buildScenarioReport, buildPassingResult } from './reportSchema';
import type { MatrixSpec, ScenarioObservation } from './types';

async function main() {
    const fixturePath = path.resolve(
        __dirname, '..', '..', 'config-processor', 'scripts', 'samples', 'fullMigrationWithTraffic.wf.yaml'
    );
    const config = yaml.parse(fs.readFileSync(fixturePath, 'utf-8')) as Record<string, unknown>;

    // ─── Checksum report ────────────────────────────────────────────
    console.log(`=== Checksum Report from ${path.basename(fixturePath)} ===\n`);
    const report = await buildChecksumReport(config);
    for (const [k, v] of Object.entries(report.components)) {
        console.log(`  ${k}  checksum=${v.configChecksum}  dependsOn=[${v.dependsOn.join(', ')}]`);
    }

    // ─── Subgraph ───────────────────────────────────────────────────
    console.log('\n=== Subgraph for proxy:capture-proxy ===\n');
    const subgraph = deriveSubgraph(report, 'proxy:capture-proxy');
    console.log(`  immediate dependents:   [${subgraph.immediateDependents.join(', ')}]`);
    console.log(`  transitive dependents:  [${subgraph.transitiveDependents.join(', ')}]`);
    console.log(`  upstream prerequisites: [${subgraph.upstreamPrerequisites.join(', ')}]`);

    // ─── Matrix expansion ───────────────────────────────────────────
    const spec: MatrixSpec = {
        focus: 'proxy:capture-proxy',
        select: [{
            changeClass: 'safe',
            patterns: ['focus-change', 'immediate-dependent-change', 'transitive-dependent-change'],
        }],
    };

    console.log('\n=== Matrix Expansion ===\n');
    const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
    for (const c of cases) {
        console.log(`  ${c.name}`);
        console.log(`    reran:     [${c.expect.reran.join(', ')}]`);
        console.log(`    unchanged: [${c.expect.unchanged.join(', ')}]`);
    }

    // ─── Generated outer workflow YAML ──────────────────────────────
    // This is the key output: a complete Argo Workflow that IS the test.
    // You could submit this to a cluster with: argo submit workflow.yaml
    const testCase = cases[0]; // focus-change/proxy-noCapture-toggle
    const outerWorkflow = buildOuterWorkflow(testCase, config);

    console.log('\n=== Generated Outer Workflow (first test case) ===\n');
    console.log(outerWorkflow);

    // ─── Simulated assert logic ─────────────────────────────────────
    // Show what the assert step does when it runs inside the workflow.
    // Here we simulate it with fake observations.
    console.log('=== Simulated Assert Logic ===\n');

    // Simulate baseline observation (all components deployed)
    const baselineObs = makeObservation(report, 0, 'baseline');

    // Simulate mutated observation (proxy + downstream changed)
    const mutatedObs = makeObservation(testCase.mutatedChecksumReport, 2, 'mutate');

    console.log('  Run 2 (mutate) assert result:');
    const result = assertRun(
        { mode: 'selective', reran: testCase.expect.reran, unchanged: testCase.expect.unchanged },
        testCase.mutatedChecksumReport, baselineObs, mutatedObs,
    );
    console.log(formatAssertResult(result));
    console.log(`\n  Overall: ${result.status.toUpperCase()}\n`);

    // ─── Scenario report ────────────────────────────────────────────
    console.log('=== Scenario Report (all 3 test cases) ===\n');
    const results = cases.map(c => buildPassingResult(c));
    const scenarioReport = buildScenarioReport('fullMigrationWithTraffic', results, {
        selectedCases: cases.map(c => `${c.pattern}/${c.changeClass}`),
        expandedCases: cases.map(c => c.name),
        skippedCases: [],
        uncoveredCases: [],
    });
    console.log(formatReport(scenarioReport));
}

function makeObservation(
    report: Awaited<ReturnType<typeof buildChecksumReport>>,
    runIndex: number,
    runName: string,
): ScenarioObservation {
    const resources: ScenarioObservation['resources'] = {};
    for (const [key, entry] of Object.entries(report.components)) {
        resources[key] = {
            kind: entry.kind,
            resourceName: entry.resourceName,
            phase: 'Ready',
            configChecksum: entry.configChecksum,
        };
    }
    return { scenario: 'demo', run: runIndex, runName, observedAt: new Date().toISOString(), resources };
}

main().catch(console.error);
