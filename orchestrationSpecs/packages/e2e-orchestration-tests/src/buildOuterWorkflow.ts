/**
 * Outer Workflow Builder
 *
 * Generates the Argo Workflow that IS the test using the type-safe
 * argo-workflow-builders API. Each expanded test case becomes a
 * sequence of steps:
 *
 *   1. cleanup-cluster     — backstop: clear target indices + source snapshots
 *   2. baseline-configure  — load config (skipApprovals: false) + submit
 *   3. baseline-wait       — monitor inner workflow, approve gates, run validations
 *   4. baseline-assert     — read CRDs, verify all deployed
 *   5. noop-configure      — resubmit same config (skipApprovals: true)
 *   6. noop-wait           — wait for inner workflow
 *   7. noop-assert         — verify all components skipped
 *   8. mutate-configure    — submit mutated config (skipApprovals: true)
 *   9. mutate-wait         — wait for inner workflow
 *  10. mutate-assert       — verify reran/unchanged match expectations
 *  11. approve-teardown    — suspend: user confirms before cleanup
 *  12. teardown            — delete workflows + CRDs + cluster data
 */
import type { ExpandedTestCase, RunExpectation } from './types';
import {
    observationConfigMapName,
    checksumReportConfigMapName,
    allConfigMapNames,
} from './stateStore';
import {
    WorkflowBuilder,
    INTERNAL,
    typeToken,
    selectInputsForRegister,
    renderWorkflowTemplate,
    toSafeYamlOutput,
    defineParam,
} from '@opensearch-migrations/argo-workflow-builders';
import { DEFAULT_RESOURCES } from '@opensearch-migrations/schemas';
import {
    makeRequiredImageParametersForKeys,
} from '@opensearch-migrations/migration-workflow-templates/src/workflowTemplates/commonUtils/imageDefinitions';

const MONITOR_SCRIPT = `
set -e
PHASE=$(kubectl get workflow migration-workflow -o jsonpath='{.status.phase}' 2>/dev/null || echo "Pending")

# Check for suspended nodes (approval gates)
SUSPENDED=$(kubectl get workflow migration-workflow -o json 2>/dev/null | \
  jq -r '[.status.nodes // {} | to_entries[] | select(.value.type == "Suspend" and .value.phase == "Running") | .value.displayName] | join(",")' 2>/dev/null || echo "")

if [ -n "$SUSPENDED" ]; then
    echo "Suspended at: $SUSPENDED"
    # Approve gates from APPROVE_PATTERNS (space-separated)
    for pattern in $APPROVE_PATTERNS; do
        workflow approve "$pattern" 2>/dev/null || true
    done
    echo "Approved gates, continuing..."
    exit 1  # retry to keep monitoring
fi

if [ "$PHASE" = "Succeeded" ] || [ "$PHASE" = "Failed" ] || [ "$PHASE" = "Error" ]; then
    mkdir -p /tmp/outputs
    echo "$PHASE" > /tmp/outputs/monitorResult
    echo "Inner workflow phase: $PHASE"
    exit 0
fi

echo "Workflow phase=$PHASE, still running..."
exit 1  # retry
`;

const ASSERT_SCRIPT = `
set -e
echo "=== Assert: $RUN_NAME (run $RUN_INDEX) ==="
mkdir -p /tmp/outputs

# Build observation from CRD states
OBSERVATION="{}"
for kind in capturedtraffics datasnapshots snapshotmigrations trafficreplays; do
    ITEMS=$(kubectl get "$kind.migrations.opensearch.org" -o json 2>/dev/null | \
        jq -c '[.items[] | {name: .metadata.name, phase: .status.phase, checksum: .status.configChecksum}]' 2>/dev/null || echo "[]")
    echo "  $kind: $ITEMS"
    OBSERVATION=$(echo "$OBSERVATION" | jq --arg k "$kind" --argjson v "$ITEMS" '. + {($k): $v}')
done

# Store observation — will be overwritten with assert result at the end
echo "Observation collected for $OBSERVATION_CONFIGMAP"

# Load prior observation if available
PRIOR="{}"
if [ -n "$PRIOR_OBSERVATION_CONFIGMAP" ]; then
    PRIOR=$(kubectl get configmap "$PRIOR_OBSERVATION_CONFIGMAP" -o jsonpath='{.data.observation\\.json}' 2>/dev/null || echo "{}")
fi

# Parse expected behavior and compare
MODE=$(echo "$EXPECTED_BEHAVIOR" | jq -r '.mode')
echo "Expected mode: $MODE"

COMPONENTS=$(echo "$CHECKSUM_REPORT" | jq -r '.components | to_entries[] | "\\(.key):\\(.value.configChecksum)"')

FAILED=0
RESULTS="[]"
for entry in $COMPONENTS; do
    KEY=$(echo "$entry" | cut -d: -f1,2)
    EXPECTED_CS=$(echo "$entry" | cut -d: -f3)
    KIND=$(echo "$KEY" | cut -d: -f1)
    NAME=$(echo "$KEY" | cut -d: -f2)

    case "$KIND" in
        proxy) CRD="capturedtraffics" ;;
        snapshot) CRD="datasnapshots" ;;
        snapshotMigration) CRD="snapshotmigrations" ;;
        replay) CRD="trafficreplays" ;;
        *) continue ;;
    esac

    ACTUAL_CS=$(echo "$OBSERVATION" | jq -r --arg crd "$CRD" --arg name "$NAME" \
        '.[$crd] // [] | map(select(.name == $name)) | .[0].checksum // "none"')
    ACTUAL_PHASE=$(echo "$OBSERVATION" | jq -r --arg crd "$CRD" --arg name "$NAME" \
        '.[$crd] // [] | map(select(.name == $name)) | .[0].phase // "none"')

    PRIOR_CS=$(echo "$PRIOR" | jq -r --arg crd "$CRD" --arg name "$NAME" \
        '.[$crd] // [] | map(select(.name == $name)) | .[0].checksum // "none"' 2>/dev/null || echo "none")

    STATUS="FAIL"
    DETAIL=""
    if [ "$MODE" = "allCompleted" ]; then
        if [ "$ACTUAL_PHASE" = "Ready" ] || [ "$ACTUAL_PHASE" = "Completed" ]; then
            STATUS="OK"; DETAIL="phase=$ACTUAL_PHASE checksum=$ACTUAL_CS"
        else
            DETAIL="expected Ready/Completed, got phase=$ACTUAL_PHASE"; FAILED=1
        fi
    elif [ "$MODE" = "allSkipped" ]; then
        if [ "$ACTUAL_CS" = "$PRIOR_CS" ]; then
            STATUS="OK"; DETAIL="unchanged ($ACTUAL_CS)"
        else
            DETAIL="expected unchanged, was $PRIOR_CS -> $ACTUAL_CS"; FAILED=1
        fi
    elif [ "$MODE" = "selective" ]; then
        IS_RERAN=$(echo "$EXPECTED_BEHAVIOR" | jq -r --arg k "$KEY" '[.reran // [] | .[] | select(. == $k)] | length')
        if [ "$IS_RERAN" -gt 0 ]; then
            if [ "$ACTUAL_CS" != "$PRIOR_CS" ]; then
                STATUS="OK"; DETAIL="reran ($PRIOR_CS -> $ACTUAL_CS)"
            else
                DETAIL="expected reran, but checksum unchanged ($ACTUAL_CS)"; FAILED=1
            fi
        else
            if [ "$ACTUAL_CS" = "$PRIOR_CS" ]; then
                STATUS="OK"; DETAIL="unchanged ($ACTUAL_CS)"
            else
                DETAIL="expected unchanged, was $PRIOR_CS -> $ACTUAL_CS"; FAILED=1
            fi
        fi
    fi

    echo "  $STATUS $KEY: $DETAIL"
    RESULTS=$(echo "$RESULTS" | jq --arg k "$KEY" --arg s "$STATUS" --arg d "$DETAIL" '. + [{component: $k, status: $s, detail: $d}]')
done

# Write structured result artifact
RESULT_JSON=$(jq -n --arg run "$RUN_NAME" --arg mode "$MODE" --argjson results "$RESULTS" --argjson failed "$FAILED" \
  '{run: $run, mode: $mode, passed: ($failed == 0), results: $results}')
echo "$RESULT_JSON" > /tmp/outputs/assert-result.json

# Also store result in the observation ConfigMap for the report step
kubectl create configmap "$OBSERVATION_CONFIGMAP" \
  --from-literal="observation.json=$OBSERVATION" \
  --from-literal="assert-result.json=$RESULT_JSON" \
  --dry-run=client -o yaml | kubectl apply -f -

exit $FAILED
`;

const CLEANUP_SCRIPT = `
set -e
echo "=== Cluster Data Cleanup ==="
console clusters clear-indices --cluster target --acknowledge-risk || true
curl -sk -u admin:admin -X DELETE "https://opensearch-cluster-master-headless:9200/searchguard,sg7-auditlog-*" || true
curl -sk -u admin:admin -X DELETE "https://elasticsearch-master-headless:9200/_snapshot/default/snap1_*" || true
echo "Cluster data cleanup complete"
`;

const TEARDOWN_SCRIPT = `
set -e
echo "=== Teardown ==="
kubectl delete workflow migration-workflow --ignore-not-found=true 2>/dev/null || true
for cm in $CONFIGMAPS; do
  kubectl delete configmap "$cm" --ignore-not-found=true 2>/dev/null || true
done
for kind in capturedtraffics datasnapshots snapshotmigrations trafficreplays; do
  kubectl delete "$kind.migrations.opensearch.org" --all --ignore-not-found=true 2>/dev/null || true
done
# Clean cluster data
console clusters clear-indices --cluster target --acknowledge-risk || true
curl -sk -u admin:admin -X DELETE "https://opensearch-cluster-master-headless:9200/searchguard,sg7-auditlog-*" || true
curl -sk -u admin:admin -X DELETE "https://elasticsearch-master-headless:9200/_snapshot/default/snap1_*" || true
echo "Teardown complete"
`;

const E2eOuterWorkflowTemplate = WorkflowBuilder.create({
    k8sResourceName: 'e2e-outer',
    serviceAccountName: 'argo-workflow-executor',
})

    .addParams({
        'monitor-retry-limit': defineParam({ expression: '60' }),
    })

    .addTemplate('configureRun', t => t
        .addRequiredInput('wfConfig', typeToken<string>())
        .addRequiredInput('runLabel', typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(['MigrationConsole']))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(['/bin/bash', '-c'])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar('WF_CONFIG', cb.inputs.wfConfig)
            .addEnvVar('RUN_LABEL', cb.inputs.runLabel)
            .addArgs([`
set -e
echo "=== Configure: $RUN_LABEL ==="
echo "$WF_CONFIG" | workflow configure edit --stdin
kubectl delete workflow migration-workflow --ignore-not-found=true 2>/dev/null || true
workflow submit
echo "Submitted inner workflow"
`])
        )
    )

    .addTemplate('waitForInner', t => t
        .addRequiredInput('approvePatterns', typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(['MigrationConsole']))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(['/bin/bash', '-c'])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar('APPROVE_PATTERNS', cb.inputs.approvePatterns)
            .addArgs([MONITOR_SCRIPT])
            .addPathOutput('monitorResult', '/tmp/outputs/monitorResult', typeToken<string>(), 'Inner workflow phase')
            .addArtifactOutput('monitorResult', '/tmp/outputs/monitorResult')
        )
        .addRetryParameters({
            limit: '{{workflow.parameters.monitor-retry-limit}}',
            retryPolicy: 'Always',
            backoff: { duration: '30', factor: '1' },
        })
    )

    .addTemplate('assertRun', t => t
        .addRequiredInput('observationConfigmap', typeToken<string>())
        .addRequiredInput('priorObservationConfigmap', typeToken<string>())
        .addRequiredInput('checksumReport', typeToken<string>())
        .addRequiredInput('expectedBehavior', typeToken<string>())
        .addRequiredInput('runIndex', typeToken<string>())
        .addRequiredInput('runName', typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(['MigrationConsole']))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(['/bin/bash', '-c'])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar('OBSERVATION_CONFIGMAP', cb.inputs.observationConfigmap)
            .addEnvVar('PRIOR_OBSERVATION_CONFIGMAP', cb.inputs.priorObservationConfigmap)
            .addEnvVar('CHECKSUM_REPORT', cb.inputs.checksumReport)
            .addEnvVar('EXPECTED_BEHAVIOR', cb.inputs.expectedBehavior)
            .addEnvVar('RUN_INDEX', cb.inputs.runIndex)
            .addEnvVar('RUN_NAME', cb.inputs.runName)
            .addArgs([ASSERT_SCRIPT])
            .addArtifactOutput('assert-result', '/tmp/outputs/assert-result.json')
        )
    )

    .addTemplate('cleanupClusterData', t => t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(['MigrationConsole']))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(['/bin/bash', '-c'])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addArgs([CLEANUP_SCRIPT])
        )
    )

    .addTemplate('generateReport', t => t
        .addRequiredInput('runCount', typeToken<string>())
        .addRequiredInput('obsConfigmapPrefix', typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(['MigrationConsole']))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(['/bin/bash', '-c'])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar('RUN_COUNT', cb.inputs.runCount)
            .addEnvVar('OBS_PREFIX', cb.inputs.obsConfigmapPrefix)
            .addArgs([`
set -e
mkdir -p /tmp/outputs
echo "=== E2E Test Report ===" | tee /tmp/outputs/report.txt
ALL_PASSED=true
for i in $(seq 0 $((RUN_COUNT - 1))); do
    CM="$OBS_PREFIX-$i"
    RESULT=$(kubectl get configmap "$CM" -o jsonpath='{.data.assert-result\\.json}' 2>/dev/null || echo "")
    if [ -z "$RESULT" ]; then
        echo "  (run $i: no result in $CM)" | tee -a /tmp/outputs/report.txt
        continue
    fi
    RUN=$(echo "$RESULT" | jq -r '.run')
    MODE=$(echo "$RESULT" | jq -r '.mode')
    PASSED=$(echo "$RESULT" | jq -r '.passed')
    echo "" | tee -a /tmp/outputs/report.txt
    echo "--- $RUN ($MODE) ---" | tee -a /tmp/outputs/report.txt
    echo "$RESULT" | jq -r '.results[] | "  \\(.status) \\(.component): \\(.detail)"' | tee -a /tmp/outputs/report.txt
    if [ "$PASSED" = "false" ]; then
        ALL_PASSED=false
    fi
done
echo "" | tee -a /tmp/outputs/report.txt
if [ "$ALL_PASSED" = "true" ]; then
    echo "=== ALL RUNS PASSED ===" | tee -a /tmp/outputs/report.txt
else
    echo "=== SOME RUNS FAILED ===" | tee -a /tmp/outputs/report.txt
fi
`])
            .addArtifactOutput('report', '/tmp/outputs/report.txt')
        )
    )

    .addTemplate('approveTeardown', t => t
        .addSuspend()
    )

    .addTemplate('teardown', t => t
        .addRequiredInput('configmaps', typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(['MigrationConsole']))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(['/bin/bash', '-c'])
            .addResources(DEFAULT_RESOURCES.PYTHON_MIGRATION_CONSOLE_CLI)
            .addEnvVar('CONFIGMAPS', cb.inputs.configmaps)
            .addArgs([TEARDOWN_SCRIPT])
        )
    )

    .addTemplate('main', t => t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(['MigrationConsole']))
        .addSteps(b => b
            .addStep('configureRun', INTERNAL, 'configureRun', c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    wfConfig: '{}',
                    runLabel: 'placeholder',
                })
            )
        )
    )

    .setEntrypoint('main')
    .getFullScope();

// ─── Public API ─────────────────────────────────────────────────────

export function buildOuterWorkflow(
    testCase: ExpandedTestCase,
    baseConfig: Record<string, unknown>,
    approvalPatterns: string[] = [],
): string {
    const scenario = sanitize(testCase.name);

    const runs = [
        { name: 'baseline', index: 0, config: { ...baseConfig, skipApprovals: false }, report: testCase.baselineChecksumReport, expectation: { mode: 'allCompleted' } satisfies RunExpectation, approvePatterns: approvalPatterns.join(' ') },
        { name: 'noop', index: 1, config: { ...baseConfig, skipApprovals: true }, report: testCase.baselineChecksumReport, expectation: { mode: 'allSkipped' } satisfies RunExpectation, approvePatterns: '' },
        {
            name: 'mutate', index: 2, config: { ...testCase.mutatedConfig, skipApprovals: true }, report: testCase.mutatedChecksumReport,
            expectation: { mode: 'selective', reran: testCase.expect.reran, unchanged: testCase.expect.unchanged, blockedOn: testCase.expect.blockedOn } satisfies RunExpectation, approvePatterns: '',
        },
    ];

    const rendered = renderWorkflowTemplate(E2eOuterWorkflowTemplate);
    const steps: Record<string, unknown>[][] = [];

    const imageArgs = [
        { name: 'imageMigrationConsoleLocation', value: '{{workflow.parameters.imageMigrationConsoleLocation}}' },
        { name: 'imageMigrationConsolePullPolicy', value: '{{workflow.parameters.imageMigrationConsolePullPolicy}}' },
    ];

    // Step 0: backstop cleanup
    steps.push([{ name: 'cleanup-cluster', template: 'cleanupclusterdata', arguments: { parameters: [...imageArgs] } }]);

    for (const run of runs) {
        const obsConfigMap = observationConfigMapName(scenario, run.index);
        const priorObsConfigMap = run.index > 0
            ? observationConfigMapName(scenario, run.index - 1)
            : '';

        steps.push([{
            name: `${run.name}-configure`,
            template: 'configurerun',
            arguments: { parameters: [
                ...imageArgs,
                { name: 'wfConfig', value: JSON.stringify(run.config) },
                { name: 'runLabel', value: run.name },
            ]},
        }]);

        steps.push([{
            name: `${run.name}-wait`,
            template: 'waitforinner',
            arguments: { parameters: [...imageArgs, { name: 'approvePatterns', value: run.approvePatterns }] },
        }]);

        steps.push([{
            name: `${run.name}-assert`,
            template: 'assertrun',
            arguments: { parameters: [
                ...imageArgs,
                { name: 'observationConfigmap', value: obsConfigMap },
                { name: 'priorObservationConfigmap', value: priorObsConfigMap },
                { name: 'checksumReport', value: JSON.stringify(run.report) },
                { name: 'expectedBehavior', value: JSON.stringify(run.expectation) },
                { name: 'runIndex', value: String(run.index) },
                { name: 'runName', value: run.name },
            ]},
        }]);
    }

    // Generate consolidated report from assert artifacts
    const obsPrefix = `${sanitize(testCase.name)}-obs`;
    steps.push([{
        name: 'generate-report',
        template: 'generatereport',
        arguments: {
            parameters: [
                ...imageArgs,
                { name: 'runCount', value: String(runs.length) },
                { name: 'obsConfigmapPrefix', value: `e2e-${obsPrefix}` },
            ],
        },
    }]);

    // Approval gate before teardown — user can inspect state
    steps.push([{ name: 'approve-teardown', template: 'approveteardown' }]);

    const allCMs = allConfigMapNames(scenario, runs.length);
    steps.push([{
        name: 'teardown',
        template: 'teardown',
        arguments: { parameters: [
            ...imageArgs,
            { name: 'configmaps', value: allCMs.join(' ') },
        ]},
    }]);

    // Replace placeholder main steps
    const mainTemplate = (rendered.spec.templates as Array<{ name: string; steps?: unknown }>)
        .find(t => t.name === 'main');
    if (mainTemplate) {
        mainTemplate.steps = steps;
    }

    // Add image parameters as workflow arguments with ConfigMap defaults
    const args = rendered.spec.arguments as { parameters: Array<{ name: string; value?: string; valueFrom?: unknown }> };
    args.parameters.push(
        { name: 'imageMigrationConsoleLocation', valueFrom: { configMapKeyRef: { name: 'migration-image-config', key: 'migrationConsoleImage' } } },
        { name: 'imageMigrationConsolePullPolicy', valueFrom: { configMapKeyRef: { name: 'migration-image-config', key: 'migrationConsolePullPolicy' } } },
    );

    rendered.kind = 'Workflow';
    rendered.metadata = {
        ...rendered.metadata,
        generateName: `e2e-${scenario}-`,
        name: '' as string,  // empty — Argo uses generateName
        labels: {
            ...rendered.metadata.labels,
            'e2e-test': 'true',
            'e2e-scenario': scenario,
            'e2e-focus': sanitize(testCase.focus),
            'e2e-pattern': sanitize(testCase.pattern),
        },
    };

    return toSafeYamlOutput(rendered);
}

function sanitize(name: string): string {
    return name.replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').substring(0, 50);
}
