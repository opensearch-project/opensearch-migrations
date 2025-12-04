import {
    defineParam,
    expr,
    INTERNAL,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";
import {
    DEFAULT_RESOURCES,
} from "@opensearch-migrations/schemas";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

const configComponentParameters = {
    // SOURCE parameters
    sourceEndpoint: defineParam({ 
        expression: expr.literal(""),
        description: "Source cluster endpoint URL"
    }),
    sourceAllowInsecure: defineParam({ 
        expression: expr.literal("true"),
        description: "Allow insecure connections to source cluster"
    }),
    sourceVersion: defineParam({ 
        expression: expr.literal(""),
        description: "Source cluster version (example : ES 7.10)"
    }),
    
    // TARGET parameters
    targetEndpoint: defineParam({ 
        expression: expr.literal(""),
        description: "Target cluster endpoint URL"
    }),
    targetAllowInsecure: defineParam({ 
        expression: expr.literal("true"),
        description: "Allow insecure connections to target cluster"
    }),
    targetVersion: defineParam({ 
        expression: expr.literal(""),
        description: "Target cluster version (example : OS 2.19)"
    }),
    
    // SNAPSHOT parameters
    snapshotConfig: defineParam({
        expression: expr.literal(""),
        description: "Snapshot repository configuration as JSON object fragment (S3_REPO_CONFIG)"
    })
};

const WORKFLOW_CONFIGURE_AND_SUBMIT_SCRIPT = `
set -e -x

echo "Building and submitting migration workflow..."

# Create migration config JSON
cat > /tmp/migration_config.json << 'EOF'
{
  "skipApprovals": true,
  "sourceClusters": {
    "source1": {
      "endpoint": "{{inputs.parameters.sourceEndpoint}}",
      "allowInsecure": {{inputs.parameters.sourceAllowInsecure}},
      "version": "{{inputs.parameters.sourceVersion}}",
      "snapshotRepo": {{inputs.parameters.snapshotConfig}}
    }
  },
  "targetClusters": {
    "target1": {
      "endpoint": "{{inputs.parameters.targetEndpoint}}",
      "allowInsecure": {{inputs.parameters.targetAllowInsecure}},
      "version": "{{inputs.parameters.targetVersion}}"
    }
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
                "indexAllowlist": ["*"]
              },
              "documentBackfillConfig": {
                "indexAllowlist": ["*"]
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

. /etc/profile.d/venv.sh
source /.venv/bin/activate

# Clear and load configuration
echo "Clearing existing workflow configuration..."
workflow configure clear --confirm
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
        .addInputsFromRecord(configComponentParameters)
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/bin/sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs([WORKFLOW_CONFIGURE_AND_SUBMIT_SCRIPT])
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
            limit: "864",          // ~72 hours (864 * 5min max backoff)
            retryPolicy: "Always",
            backoff: {
                duration: "5",     // Start at 5 seconds
                factor: "2",       // Exponential backoff  
                cap: "300"         // Cap at 5 minutes
            }
        })
    )

    .addTemplate("testRunMigration", t => t
        // Source cluster parameters
        .addRequiredInput("sourceEndpoint", typeToken<string>(), "Source cluster endpoint URL")
        .addRequiredInput("sourceAllowInsecure", typeToken<string>(), "Allow insecure connections to source cluster")
        .addRequiredInput("sourceVersion", typeToken<string>(), "Source cluster version (example: ES 7.10)")
        
        // Target cluster parameters
        .addRequiredInput("targetEndpoint", typeToken<string>(), "Target cluster endpoint URL")
        .addRequiredInput("targetAllowInsecure", typeToken<string>(), "Allow insecure connections to target cluster")
        .addRequiredInput("targetVersion", typeToken<string>(), "Target cluster version (example: OS 2.19)")
        
        // Snapshot configuration (JSON blob)
        .addRequiredInput("snapshotConfigJson", typeToken<string>(), 
            "Snapshot repository configuration as JSON object fragment (S3_REPO_CONFIG)")

        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            // Step 1: Configure and submit workflow (combined)
            .addStep("configureAndSubmitWorkflow", INTERNAL, "configureAndSubmitWorkflow", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceEndpoint: b.inputs.sourceEndpoint,
                    sourceAllowInsecure: b.inputs.sourceAllowInsecure,
                    sourceVersion: b.inputs.sourceVersion,
                    targetEndpoint: b.inputs.targetEndpoint,
                    targetAllowInsecure: b.inputs.targetAllowInsecure,
                    targetVersion: b.inputs.targetVersion,
                    snapshotConfig: b.inputs.snapshotConfigJson
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

    .setEntrypoint("testRunMigration")
    .getFullScope();
