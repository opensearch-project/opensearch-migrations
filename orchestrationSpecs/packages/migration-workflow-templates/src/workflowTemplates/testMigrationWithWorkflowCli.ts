import {
    BaseExpression,
    expr,
    INTERNAL,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    DEFAULT_RESOURCES,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
} from "@opensearch-migrations/schemas";
import {z} from "zod";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

function makeMigrationParams(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>
) {
    return expr.makeDict({
        skipApprovals: true,
        sourceClusters: expr.makeDict({
            source1: expr.deserializeRecord(sourceConfig)
        }),
        targetClusters: expr.makeDict({
            target1: expr.deserializeRecord(targetConfig)
        }),
        migrationConfigs: [
            {
                fromSource: "source1",
                toTarget: "target1",
                snapshotExtractAndLoadConfigs: [
                    {
                        snapshotConfig: {
                            snapshotNameConfig: {
                                snapshotNamePrefix: "source1snapshot1"
                            }
                        },
                        migrations: [
                            {
                                metadataMigrationConfig: {},
                                documentBackfillConfig: {}
                            }
                        ]
                    }
                ]
            }
        ]
    });
}


const WORKFLOW_CONFIGURE_AND_SUBMIT_SCRIPT = `
set -e -x

echo "Building and submitting migration workflow..."

# Create migration config JSON
cat > /tmp/migration_config.json << 'EOF'
{
  "skipApprovals": true,
  "sourceClusters": {
    "source1": {{inputs.parameters.sourceConfig}}
    }
  },
  "targetClusters": {
    "target1": {{inputs.parameters.targetConfig}}
  },
  "migrationConfigs": [
    {
      "fromSource": "source1",
      "toTarget": "target1",
      "snapshotExtractAndLoadConfigs": [
        {
          "snapshotConfig": {
            "snapshotNameConfig": {
              "snapshotNamePrefix": "source1snapshot1"
            }
          },
          "migrations": [
            {
              "metadataMigrationConfig": {
              },
              "documentBackfillConfig": {
              }
            }
          ]
        }
      ]
    }
  ]
}
EOF

echo "Migration config contents:"
cat /tmp/migration_config.json

echo "Loading configuration from JSON..."
cat /tmp/migration_config.json | workflow configure edit --stdin

 # Submit workflow
echo "Submitting workflow..."
WORKFLOW_OUTPUT=$(workflow submit 2>&1)
echo "Workflow submit output: $WORKFLOW_OUTPUT"
`;

const WORKFLOW_MONITOR_SCRIPT = `
set -e -x

echo "Checking workflow status"
. /etc/profile.d/venv.sh
source /.venv/bin/activate

STATUS_OUTPUT=$(workflow status 2>&1 || true)
echo "Status output:"
echo "$STATUS_OUTPUT"

RESULT="ERROR"
EXIT_CODE=2

# 1) Terminal success: stop monitoring, proceed, mark success
if echo "$STATUS_OUTPUT" | grep -q "Phase: Succeeded"; then
    echo "Workflow completed successfully"
    RESULT="SUCCEEDED"
    EXIT_CODE=0

# 2) Terminal failure: stop monitoring, proceed, mark failure
elif echo "$STATUS_OUTPUT" | grep -q "Phase: Failed"; then
    echo "Workflow failed permanently"
    RESULT="FAILED"
    EXIT_CODE=0

# 3) Suspended / waiting for approval: try to approve once, then retry
elif echo "$STATUS_OUTPUT" | grep -q "Suspended\\|Waiting"; then
    echo "Workflow is suspended or waiting for approval, attempting to approve..."
    APPROVE_OUTPUT=$(workflow approve 2>&1 || true)
    echo "Approve output:"
    echo "$APPROVE_OUTPUT"
    # Regardless of approve result, tell Argo to check again
    RESULT="RETRY"
    EXIT_CODE=1

# 4) Still running: keep checking
elif echo "$STATUS_OUTPUT" | grep -q "Phase: Running\\|Phase: Pending"; then
    echo "⏳ Workflow still running, will retry..."
    RESULT="RETRY"
    EXIT_CODE=1

# 5) Anything else is treated as an error (e.g. CLI failure, unknown output)
else
    echo "Unknown or error status from 'workflow status'"
    RESULT="ERROR"
    EXIT_CODE=2
fi

mkdir -p /tmp/outputs
echo "$RESULT" > /tmp/outputs/monitorResult
sync

echo "Monitor result: $RESULT (exit code: $EXIT_CODE)"
exit $EXIT_CODE
`;

export const testMigrationWithWorkflowCli = WorkflowBuilder.create({
    k8sResourceName: "full-migration-with-clusters",
    parallelism: 1,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)

    .addTemplate("configureAndSubmitWorkflow", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([WORKFLOW_CONFIGURE_AND_SUBMIT_SCRIPT,
                expr.asString(expr.serialize(
                    makeMigrationParams(cb.inputs.sourceConfig, cb.inputs.targetConfig)
                ))
            ])
        )
    )

    .addTemplate("monitorWorkflow", t => t
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([WORKFLOW_MONITOR_SCRIPT])
            .addPathOutput(
                "monitorResult",
                "/tmp/outputs/monitorResult",
                typeToken<string>(),
                "Result of monitoring (SUCCEEDED, FAILED, RETRY, ERROR)"
            )
        )
        .addRetryParameters({
            limit: "3",          // ~72 hours (864 * 5min max backoff)
            retryPolicy: "Always",
            backoff: {
                duration: "5",     // Start at 5 seconds
                factor: "2",       // Exponential backoff  
                cap: "300"         // Cap at 5 minutes
            }
        })
    )

    .addTemplate("main", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            // Step 1: Configure and submit workflow (combined)
            .addStep("configureAndSubmitWorkflow", INTERNAL, "configureAndSubmitWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceConfig: b.inputs.sourceConfig,
                    targetConfig: b.inputs.targetConfig,
                })
            )

            // Step 2: Monitor workflow to completion
            .addStep("monitorWorkflow", INTERNAL, "monitorWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                })
            )
        )
    )

    .setEntrypoint("main")
    .getFullScope();
