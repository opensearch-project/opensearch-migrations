/**
 * Outer Workflow Builder
 *
 * Generates the Argo Workflow YAML that IS the test. Each expanded test case
 * becomes a sequence of steps:
 *
 *   1. baseline-configure  — submit inner workflow with base config
 *   2. baseline-wait       — wait for inner workflow to complete
 *   3. baseline-assert     — read CRDs, verify all deployed, record observation
 *   4. noop-configure      — resubmit same config (no changes)
 *   5. noop-wait           — wait for inner workflow to complete
 *   6. noop-assert         — verify all components skipped
 *   7. mutate-configure    — submit inner workflow with mutated config
 *   8. mutate-wait         — wait for inner workflow to complete
 *   9. mutate-assert       — verify reran/unchanged match expectations
 *  10. teardown            — delete inner workflows, CRDs, ConfigMaps
 *
 * All configs, checksums, and expectations are baked in at compile time.
 * Runtime needs only kubectl and argo CLI.
 */
import type { ExpandedTestCase, RunExpectation } from './types';
import {
    observationConfigMapName,
    checksumReportConfigMapName,
    allConfigMapNames,
} from './stateStore';

export type OuterWorkflowConfig = {
    /** Container image with kubectl + argo CLI + assert binary */
    assertImage: string;
    /** Service account with permissions to manage workflows + CRDs + ConfigMaps */
    serviceAccountName: string;
    /** Namespace for the inner migration workflow */
    namespace: string;
};

const DEFAULTS: OuterWorkflowConfig = {
    assertImage: 'migrations/e2e-assert:latest',
    serviceAccountName: 'argo-workflow-executor',
    namespace: 'ma',
};

type RunSpec = {
    name: string;
    index: number;
    /** The wf.yaml config to submit for this run, serialized as JSON */
    configJson: string;
    checksumReportJson: string;
    expectation: RunExpectation;
};

/**
 * Build the outer Argo Workflow for one expanded test case.
 * Returns a plain object ready to be serialized to YAML.
 */
export function buildOuterWorkflow(
    testCase: ExpandedTestCase,
    baseConfig: Record<string, unknown>,
    config: Partial<OuterWorkflowConfig> = {},
): Record<string, unknown> {
    const cfg = { ...DEFAULTS, ...config };
    const scenario = sanitize(testCase.name);
    const innerWorkflowBase = `e2e-inner-${scenario}`;
    const scenarioLabel = scenario;

    const runs: RunSpec[] = [
        {
            name: 'baseline',
            index: 0,
            configJson: JSON.stringify(baseConfig),
            checksumReportJson: JSON.stringify(testCase.baselineChecksumReport),
            expectation: { mode: 'allCompleted' },
        },
        {
            name: 'noop',
            index: 1,
            configJson: JSON.stringify(baseConfig),
            checksumReportJson: JSON.stringify(testCase.baselineChecksumReport),
            expectation: { mode: 'allSkipped' },
        },
        {
            name: 'mutate',
            index: 2,
            configJson: JSON.stringify(testCase.mutatedConfig),
            checksumReportJson: JSON.stringify(testCase.mutatedChecksumReport),
            expectation: {
                mode: 'selective',
                reran: testCase.expect.reran,
                unchanged: testCase.expect.unchanged,
                blockedOn: testCase.expect.blockedOn,
            },
        },
    ];

    // Build sequential step groups
    const steps: Record<string, unknown>[][] = [];

    for (const run of runs) {
        const innerName = `${innerWorkflowBase}-run-${run.index}`;
        const obsConfigMap = observationConfigMapName(scenario, run.index);
        const csConfigMap = checksumReportConfigMapName(scenario, run.index);
        const priorObsConfigMap = run.index > 0
            ? observationConfigMapName(scenario, run.index - 1)
            : '';

        steps.push([{
            name: `${run.name}-configure`,
            template: 'configure-run',
            arguments: { parameters: [
                { name: 'inner-workflow-name', value: innerName },
                { name: 'scenario-label', value: scenarioLabel },
                { name: 'checksum-configmap', value: csConfigMap },
                { name: 'checksum-report', value: run.checksumReportJson },
                { name: 'wf-config', value: run.configJson },
                { name: 'run-label', value: run.name },
            ]},
        }]);

        steps.push([{
            name: `${run.name}-wait`,
            template: 'wait-for-inner',
            arguments: { parameters: [
                { name: 'inner-workflow-name', value: innerName },
            ]},
        }]);

        steps.push([{
            name: `${run.name}-assert`,
            template: 'assert-run',
            arguments: { parameters: [
                { name: 'observation-configmap', value: obsConfigMap },
                { name: 'prior-observation-configmap', value: priorObsConfigMap },
                { name: 'checksum-report', value: run.checksumReportJson },
                { name: 'expected-behavior', value: JSON.stringify(run.expectation) },
                { name: 'run-index', value: String(run.index) },
                { name: 'run-name', value: run.name },
                { name: 'scenario', value: scenario },
            ]},
        }]);
    }

    // Teardown: delete all inner workflows by label + all ConfigMaps by name
    const allCMs = allConfigMapNames(scenario, runs.length);
    const innerNames = runs.map(r => `${innerWorkflowBase}-run-${r.index}`);
    steps.push([{
        name: 'teardown',
        template: 'teardown',
        arguments: { parameters: [
            { name: 'scenario-label', value: scenarioLabel },
            { name: 'inner-workflow-names', value: innerNames.join(' ') },
            { name: 'configmaps', value: allCMs.join(' ') },
        ]},
    }]);

    const templates = [
        mainTemplate(steps),
        configureTemplate(cfg),
        waitTemplate(cfg),
        assertTemplate(cfg),
        teardownTemplate(cfg),
    ];

    return {
        apiVersion: 'argoproj.io/v1alpha1',
        kind: 'Workflow',
        metadata: {
            generateName: `e2e-${scenario}-`,
            namespace: cfg.namespace,
            labels: {
                'e2e-test': 'true',
                'e2e-scenario': scenarioLabel,
                'e2e-focus': testCase.focus,
                'e2e-pattern': testCase.pattern,
            },
        },
        spec: {
            entrypoint: 'main',
            serviceAccountName: cfg.serviceAccountName,
            activeDeadlineSeconds: 1800,
            templates,
        },
    };
}

function mainTemplate(steps: Record<string, unknown>[][]) {
    return { name: 'main', steps };
}

function configureTemplate(cfg: OuterWorkflowConfig) {
    return {
        name: 'configure-run',
        inputs: { parameters: [
            { name: 'inner-workflow-name' },
            { name: 'scenario-label' },
            { name: 'checksum-configmap' },
            { name: 'checksum-report' },
            { name: 'wf-config' },
            { name: 'run-label' },
        ]},
        container: {
            image: cfg.assertImage,
            command: ['/bin/sh', '-c'],
            args: [`set -e
echo "=== Configure: {{inputs.parameters.run-label}} ==="

# Store checksum report in ConfigMap
kubectl create configmap {{inputs.parameters.checksum-configmap}} \
  --from-literal='checksum-report.json={{inputs.parameters.checksum-report}}' \
  --dry-run=client -o yaml | kubectl apply -f -

# Store wf.yaml config in a ConfigMap so workflow-configure can read it
kubectl create configmap {{inputs.parameters.inner-workflow-name}}-config \
  --from-literal='config.json={{inputs.parameters.wf-config}}' \
  --dry-run=client -o yaml | kubectl apply -f -

# Delete previous inner workflow if it exists
argo delete {{inputs.parameters.inner-workflow-name}} --ignore-not-found=true 2>/dev/null || true

# TODO: Replace with actual 'workflow configure' command once it exists.
# workflow-configure reads the config from the ConfigMap, runs the config
# processor + template renderer, and submits the resulting Argo workflow.
#
# workflow configure \
#   --config-from configmap/{{inputs.parameters.inner-workflow-name}}-config \
#   --name {{inputs.parameters.inner-workflow-name}} \
#   --labels e2e-scenario={{inputs.parameters.scenario-label}}
echo "ERROR: workflow configure not yet implemented" && exit 1
`],
        },
    };
}

function waitTemplate(cfg: OuterWorkflowConfig) {
    return {
        name: 'wait-for-inner',
        inputs: { parameters: [{ name: 'inner-workflow-name' }] },
        container: {
            image: cfg.assertImage,
            command: ['/bin/sh', '-c'],
            args: [`set -e
echo "=== Waiting for {{inputs.parameters.inner-workflow-name}} ==="
argo wait {{inputs.parameters.inner-workflow-name}} --timeout 600s
echo "Inner workflow completed"
`],
        },
    };
}

function assertTemplate(cfg: OuterWorkflowConfig) {
    return {
        name: 'assert-run',
        inputs: { parameters: [
            { name: 'observation-configmap' },
            { name: 'prior-observation-configmap' },
            { name: 'checksum-report' },
            { name: 'expected-behavior' },
            { name: 'run-index' },
            { name: 'run-name' },
            { name: 'scenario' },
        ]},
        container: {
            image: cfg.assertImage,
            command: ['/bin/sh', '-c'],
            args: [`set -e
echo "=== Assert: {{inputs.parameters.run-name}} (run {{inputs.parameters.run-index}}) ==="

# TODO: Replace with actual e2e-assert binary once the container is built.
# The binary (compiled from assertLogic.ts) will:
# 1. Read current CRD states (kubectl get for each component in the checksum report)
# 2. Read prior observation from ConfigMap (if prior-observation-configmap is set)
# 3. Compare observed checksums + phases against expected behavior
# 4. Write current observation to ConfigMap for the next run
# 5. Exit 0 on pass, 1 on fail with structured JSON output
#
# e2e-assert \
#   --checksum-report '{{inputs.parameters.checksum-report}}' \
#   --expected '{{inputs.parameters.expected-behavior}}' \
#   --prior-observation-configmap '{{inputs.parameters.prior-observation-configmap}}' \
#   --observation-configmap '{{inputs.parameters.observation-configmap}}' \
#   --run-index {{inputs.parameters.run-index}} \
#   --run-name '{{inputs.parameters.run-name}}' \
#   --scenario '{{inputs.parameters.scenario}}'
echo "ERROR: e2e-assert binary not yet built" && exit 1
`],
        },
    };
}

function teardownTemplate(cfg: OuterWorkflowConfig) {
    return {
        name: 'teardown',
        inputs: { parameters: [
            { name: 'scenario-label' },
            { name: 'inner-workflow-names' },
            { name: 'configmaps' },
        ]},
        container: {
            image: cfg.assertImage,
            command: ['/bin/sh', '-c'],
            args: ['set -e\n'
+ 'echo "=== Teardown ==="\n'
+ '\n'
+ '# Delete inner workflows by explicit name\n'
+ 'for wf in {{inputs.parameters.inner-workflow-names}}; do\n'
+ '  argo delete "$wf" --ignore-not-found=true 2>/dev/null || true\n'
+ 'done\n'
+ '\n'
+ '# Delete config ConfigMaps for inner workflows\n'
+ 'for wf in {{inputs.parameters.inner-workflow-names}}; do\n'
+ '  kubectl delete configmap "${wf}-config" --ignore-not-found=true 2>/dev/null || true\n'
+ 'done\n'
+ '\n'
+ '# Delete observation and checksum ConfigMaps\n'
+ 'kubectl delete configmap {{inputs.parameters.configmaps}} --ignore-not-found=true 2>/dev/null || true\n'
+ '\n'
+ '# TODO: Delete CRDs created by the scenario\n'
+ '# kubectl delete capturedtraffic,trafficreplay,datasnapshot,snapshotmigration \\\n'
+ '#   -l e2e-scenario={{inputs.parameters.scenario-label}} --ignore-not-found=true\n'
+ '\n'
+ 'echo "Teardown complete"\n'],
        },
    };
}

function sanitize(name: string): string {
    return name.replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').substring(0, 50);
}
