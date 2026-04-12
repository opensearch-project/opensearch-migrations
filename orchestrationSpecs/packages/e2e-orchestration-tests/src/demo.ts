/**
 * Demo: E2E Orchestration Test Framework
 *
 * This script demonstrates the COMPILE-TIME side of the test framework.
 * It shows how a matrix spec gets expanded into concrete test cases with
 * topology-driven expectations.
 *
 * The pipeline is:
 *
 *   1. Load a baseline wf.yaml config (the same file a user would write)
 *   2. Build a checksum report: run the config processor and extract each
 *      component's checksums and dependency edges
 *   3. Derive the subgraph: classify all components relative to a focus
 *   4. Expand the matrix: for each pattern × mutator combination, apply the
 *      mutation, recompute checksums, and derive expectations from topology
 *   5. Format a report showing what passed/failed
 *
 * WHAT THIS DOES NOT SHOW (yet — Phases 6-8):
 *
 *   The runtime side — actually submitting workflows to Argo and checking
 *   live CRD state. That requires:
 *   - Phase 6: ConfigMap state store (persist observations between runs)
 *   - Phase 7: Assert container (read live CRDs, compare to expectations)
 *   - Phase 8: Outer workflow (Argo workflow that orchestrates the test)
 *
 *   The "Simulated Test Execution" section below shows what that would
 *   look like conceptually, using fake observations instead of live CRDs.
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
import { buildPassingResult, buildFailingResult, buildScenarioReport, formatReport } from './reportSchema';
import type { MatrixSpec, ScenarioObservation, ExpandedTestCase } from './types';

async function main() {
    // ─── Step 1: Load baseline config ───────────────────────────────
    const fixturePath = path.resolve(
        __dirname, '..', '..', 'config-processor', 'scripts', 'samples', 'fullMigrationWithTraffic.wf.yaml'
    );
    const config = yaml.parse(fs.readFileSync(fixturePath, 'utf-8')) as Record<string, unknown>;

    // ─── Step 2: Build checksum report ──────────────────────────────
    // This runs the config processor and extracts the dependency graph.
    console.log(`=== Step 1-2: Checksum Report from ${path.basename(fixturePath)} ===`);
    const report = await buildChecksumReport(config);
    for (const [k, v] of Object.entries(report.components)) {
        const ds = Object.entries(v.downstreamChecksums)
            .map(([dep, cs]) => `${dep}=${cs}`)
            .join(', ');
        console.log(`  ${k}`);
        console.log(`    configChecksum: ${v.configChecksum}`);
        if (ds) console.log(`    downstreamChecksums: ${ds}`);
        console.log(`    dependsOn: ${v.dependsOn.join(', ') || '(none)'}`);
    }

    // ─── Step 3: Derive subgraph ────────────────────────────────────
    console.log('\n=== Step 3: Derived Subgraph (focus=proxy:capture-proxy) ===');
    const subgraph = deriveSubgraph(report, 'proxy:capture-proxy');
    console.log(`  focus:                  ${subgraph.focus}`);
    console.log(`  immediate dependents:   [${subgraph.immediateDependents.join(', ')}]`);
    console.log(`  transitive dependents:  [${subgraph.transitiveDependents.join(', ')}]`);
    console.log(`  upstream prerequisites: [${subgraph.upstreamPrerequisites.join(', ')}]`);
    console.log(`  independent:            [${subgraph.independent.join(', ') || '(none)'}]`);

    // ─── Step 4: Expand matrix ──────────────────────────────────────
    // This is the core of the compile-time pipeline. The matrix spec says
    // "test all safe changes at each level of the dependency chain."
    // The expander finds matching mutators, applies them, and derives
    // expectations from the topology.
    const spec: MatrixSpec = {
        focus: 'proxy:capture-proxy',
        select: [{
            changeClass: 'safe',
            patterns: ['focus-change', 'immediate-dependent-change', 'transitive-dependent-change'],
        }],
    };

    console.log('\n=== Step 4: Matrix Expansion ===');
    const cases = await expandMatrix(spec, report, defaultMutatorRegistry, config, report);
    console.log(`Expanded ${cases.length} concrete test cases:\n`);
    for (const c of cases) {
        console.log(`  ${c.name}`);
        console.log(`    mutator: ${c.mutatorId}`);
        console.log(`    reran:     [${c.expect.reran.join(', ')}]`);
        console.log(`    unchanged: [${c.expect.unchanged.join(', ')}]`);
        if (c.expect.blockedOn) {
            for (const [k, v] of Object.entries(c.expect.blockedOn)) {
                console.log(`    ${k} blocked on: [${v.join(', ')}]`);
            }
        }
        console.log();
    }

    // ─── Step 5: Report (compile-time mock) ─────────────────────────
    console.log('=== Step 5: Report (all passing) ===');
    const passingResults = cases.map(c => buildPassingResult(c));
    const passingReport = buildScenarioReport('fullMigrationWithTraffic', passingResults, {
        selectedCases: cases.map(c => `${c.pattern}/${c.changeClass}`),
        expandedCases: cases.map(c => c.name),
        uncoveredCases: [],
    });
    console.log(formatReport(passingReport));

    // ─── Simulated Test Execution ───────────────────────────────────
    // This shows what the RUNTIME side would look like (Phases 6-8).
    // In reality, each step below would be an Argo workflow step.
    console.log('=== Simulated Test Execution (what Phases 6-8 will do) ===\n');
    await simulateTestExecution(cases[0], config, report);
}

/**
 * Simulates what a real test execution would look like at runtime.
 * Each numbered step below corresponds to an Argo workflow step in the
 * outer test workflow.
 */
async function simulateTestExecution(
    testCase: ExpandedTestCase,
    baseConfig: Record<string, unknown>,
    baseReport: typeof testCase.baselineChecksumReport,
) {
    console.log(`Test: ${testCase.name}\n`);

    // ── Run 0: Baseline ─────────────────────────────────────────────
    // In reality: submit the inner migration workflow with the base config.
    // The workflow deploys everything from scratch.
    console.log('  Run 0: Baseline (deploy everything)');
    console.log('    [outer workflow] configure: submit inner workflow with base config');
    console.log('    [outer workflow] wait: poll inner workflow until Succeeded');

    // After the inner workflow completes, the assert step reads all CRDs
    // and records the observation in a ConfigMap.
    const baselineObservation: ScenarioObservation = {
        scenario: testCase.name,
        run: 0,
        runName: 'baseline',
        observedAt: new Date().toISOString(),
        resources: {},
    };
    for (const [key, entry] of Object.entries(baseReport.components)) {
        baselineObservation.resources[key] = {
            kind: entry.kind,
            resourceName: entry.resourceName,
            phase: 'Ready',
            configChecksum: entry.configChecksum,
        };
    }
    console.log('    [outer workflow] assert: read CRDs, record observation:');
    for (const [key, obs] of Object.entries(baselineObservation.resources)) {
        console.log(`      ${key}: phase=${obs.phase} checksum=${obs.configChecksum}`);
    }
    console.log('    [outer workflow] write observation to ConfigMap\n');

    // ── Run 1: No-op resubmit ───────────────────────────────────────
    // Resubmit the exact same config. Everything should skip.
    console.log('  Run 1: No-op resubmit (verify skip logic works)');
    console.log('    [outer workflow] configure: submit inner workflow with SAME config');
    console.log('    [outer workflow] wait: poll inner workflow until Succeeded');
    console.log('    [outer workflow] assert: every component checksum unchanged from Run 0');
    console.log('    → All components skipped ✓\n');

    // ── Run 2: Apply mutation ───────────────────────────────────────
    // Apply the mutator and resubmit. The expected behavior comes from
    // the compile-time expansion.
    console.log(`  Run 2: Apply mutation (${testCase.mutatorId})`);
    console.log('    [outer workflow] configure: submit inner workflow with MUTATED config');
    console.log('    [outer workflow] wait: poll inner workflow until Succeeded');
    console.log('    [outer workflow] assert: compare CRD state to expectations:');

    // The assert step reads the prior observation from the ConfigMap,
    // reads the current CRD state, and compares:
    const mutatedReport = testCase.mutatedChecksumReport;
    for (const key of testCase.expect.reran) {
        const prior = baselineObservation.resources[key]?.configChecksum ?? '?';
        const current = mutatedReport.components[key]?.configChecksum ?? '?';
        console.log(`      ${key}: checksum ${prior} → ${current} (CHANGED → reran ✓)`);
    }
    for (const key of testCase.expect.unchanged) {
        const prior = baselineObservation.resources[key]?.configChecksum ?? '?';
        console.log(`      ${key}: checksum ${prior} → ${prior} (SAME → skipped ✓)`);
    }
    if (testCase.expect.blockedOn) {
        console.log('    ordering:');
        for (const [downstream, upstreams] of Object.entries(testCase.expect.blockedOn)) {
            console.log(`      ${downstream} started after ${upstreams.join(', ')} finished ✓`);
        }
    }

    console.log('\n    [outer workflow] teardown: delete inner workflow, CRDs, ConfigMaps');
    console.log('    → Test PASSED ✓');
}

main().catch(console.error);
